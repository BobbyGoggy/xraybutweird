package dev.secret.orezones;

import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Finds ore zones from data the server already sent to the client.
 *
 * Vanilla places ore as small veins (a few blocks across). Caves are carved first, so a vein that touches a
 * cave shows part of itself on the cave wall while the rest stays buried right next to it. So:
 *
 *  1. scan loaded chunks for ore blocks that touch air or fluid (hidden ore is never read, so anti-xray
 *     decoys cannot register),
 *  2. group those blocks into vein-sized clusters,
 *  3. draw one box per cluster, grown by how far that ore's veins can reach.
 *
 * Zones depend only on the blocks, never on where the player stands, so they do not move.
 */
final class ZoneEstimator {
    // ---- config ----
    static final int kScanRadius = 12;              // chunks around the player
    static final int kProbePerTick = 40;            // chunk slots checked per tick
    static final int kMaxScansPerTick = 3;
    static final long kScanBudgetNanos = 2_500_000L;
    static final int kNearChunks = 2;
    static final int kNearRescanTicks = 20;         // chunks next to you: catches mining
    static final int kFarRescanTicks = 1200;
    static final int kIncompleteRetryTicks = 40;    // neighbour chunk was missing at scan time
    static final int kRebuildMinTicks = 10;
    static final int kVisibleEveryTicks = 10;
    static final double kRenderDistance = 96.0;
    static final int kMaxDrawn = 600;

    record Zone(OreType ore, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, float conf) {}

    private record ChunkOres(long[] pos, byte[] kind) {
        boolean same(ChunkOres o) {
            return Arrays.equals(pos, o.pos) && Arrays.equals(kind, o.kind);
        }
    }

    private static final Zone[] NONE = new Zone[0];
    private static final int[] DX = {-1, 1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, -1, 1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, -1, 1};

    private static final Map<Long, ChunkOres> sightings = new HashMap<>();
    private static final Map<Long, Integer> scannedAt = new HashMap<>();
    private static Zone[] allZones = NONE;
    private static Zone[] visible = NONE;
    private static ClientLevel lastLevel;
    private static int lastDim = -1;
    private static int tickCounter, cursor, lastBuild, lastVisible;
    private static boolean dirty, visibleDirty;

    static Zone[] visible() {
        return visible;
    }

    static void invalidateVisible() {
        visibleDirty = true;
    }

    static void clear() {
        sightings.clear();
        scannedAt.clear();
        allZones = NONE;
        visible = NONE;
        dirty = false;
        visibleDirty = false;
        cursor = 0;
    }

    static String summary() {
        int[] n = new int[OreType.VALUES.length];
        for (Zone z : allZones) {
            n[z.ore().ordinal()]++;
        }
        StringBuilder sb = new StringBuilder("Ore Zones ")
                .append(OreZonesClient.kModeNames[OreZonesClient.mode]).append(" | ");
        boolean any = false;
        for (int i = OreType.VALUES.length - 1; i >= 0; i--) {
            if (n[i] > 0) {
                sb.append(OreType.VALUES[i].tag).append(n[i]).append(' ');
                any = true;
            }
        }
        if (!any) {
            sb.append("no zones yet ");
        }
        return sb.append("| shown ").append(visible.length).append(" | chunks ").append(sightings.size()).toString();
    }

    // ------------------------------------------------------------------ tick

    static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        int dim = level.dimension() == Level.OVERWORLD ? 1 : level.dimension() == Level.NETHER ? 2 : 0;
        if (level != lastLevel || dim != lastDim) {
            clear();
            lastLevel = level;
            lastDim = dim;
        }
        tickCounter++;
        if (dim == 0) {
            return;
        }

        BlockPos pp = mc.player.blockPosition();
        int pcx = pp.getX() >> 4;
        int pcz = pp.getZ() >> 4;

        scanSome(level, pcx, pcz, dim);

        if (tickCounter % 100 == 0) {
            prune(pcx, pcz);
        }
        if (dirty && tickCounter - lastBuild >= kRebuildMinTicks) {
            rebuild();
            dirty = false;
            lastBuild = tickCounter;
            visibleDirty = true;
        }
        if (visibleDirty || tickCounter - lastVisible >= kVisibleEveryTicks) {
            refreshVisible(mc);
            visibleDirty = false;
            lastVisible = tickCounter;
        }
    }

    // -------------------------------------------------------------- scanning

    private static void scanSome(ClientLevel level, int pcx, int pcz, int dim) {
        int side = 2 * kScanRadius + 1;
        int total = side * side;
        long deadline = System.nanoTime() + kScanBudgetNanos;
        int scans = 0;
        for (int i = 0; i < kProbePerTick && scans < kMaxScansPerTick; i++) {
            cursor = (cursor + 1) % total;
            int cx = pcx + (cursor % side) - kScanRadius;
            int cz = pcz + (cursor / side) - kScanRadius;
            long key = ChunkPos.asLong(cx, cz);

            boolean near = Math.abs(cx - pcx) <= kNearChunks && Math.abs(cz - pcz) <= kNearChunks;
            int interval = near ? kNearRescanTicks : kFarRescanTicks;
            Integer at = scannedAt.get(key);
            if (at != null && tickCounter - at < interval) {
                continue;
            }

            int result = scanChunk(level, cx, cz, key, dim);
            if (result == 0) {
                scannedAt.remove(key);
                if (sightings.remove(key) != null) {
                    dirty = true;
                }
                continue;
            }
            scannedAt.put(key, result == 1 ? tickCounter : tickCounter - interval + kIncompleteRetryTicks);
            scans++;
            if (System.nanoTime() > deadline) {
                break;
            }
        }
    }

    private static void prune(int pcx, int pcz) {
        int lim = kScanRadius + 1;
        Iterator<Long> it = scannedAt.keySet().iterator();
        while (it.hasNext()) {
            long k = it.next();
            if (Math.abs(ChunkPos.getX(k) - pcx) > lim || Math.abs(ChunkPos.getZ(k) - pcz) > lim) {
                it.remove();
                if (sightings.remove(k) != null) {
                    dirty = true;
                }
            }
        }
    }

    private static boolean isOre(BlockState s) {
        return OreType.of(s.getBlock()) != null;
    }

    /** 0 = chunk not loaded, 1 = scanned, 2 = scanned but a neighbour chunk was missing. */
    private static int scanChunk(ClientLevel level, int cx, int cz, long key, int dim) {
        ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null) {
            return 0;
        }
        ChunkAccess west = level.getChunk(cx - 1, cz, ChunkStatus.FULL, false);
        ChunkAccess east = level.getChunk(cx + 1, cz, ChunkStatus.FULL, false);
        ChunkAccess north = level.getChunk(cx, cz - 1, ChunkStatus.FULL, false);
        ChunkAccess south = level.getChunk(cx, cz + 1, ChunkStatus.FULL, false);
        boolean complete = west != null && east != null && north != null && south != null;

        int minY = level.getMinY();
        int maxY = level.getMaxY();
        LongArrayList pos = null;
        ByteArrayList kind = null;
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();

        for (int si = 0; si < chunk.getSectionsCount(); si++) {
            LevelChunkSection sec = chunk.getSection(si);
            if (sec.hasOnlyAir() || !sec.maybeHas(ZoneEstimator::isOre)) {
                continue;
            }
            int baseY = minY + (si << 4);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        OreType t = OreType.of(sec.getBlockState(x, y, z).getBlock());
                        if (t == null || (t.dims & dim) == 0) {
                            continue;
                        }
                        int wx = (cx << 4) + x;
                        int wy = baseY + y;
                        int wz = (cz << 4) + z;
                        if (exposed(chunk, west, east, north, south, cx, cz, wx, wy, wz, minY, maxY, q)) {
                            if (pos == null) {
                                pos = new LongArrayList();
                                kind = new ByteArrayList();
                            }
                            pos.add(BlockPos.asLong(wx, wy, wz));
                            kind.add((byte) t.ordinal());
                        }
                    }
                }
            }
        }

        ChunkOres old = sightings.get(key);
        if (pos == null) {
            if (old != null) {
                sightings.remove(key);
                dirty = true;
            }
        } else {
            ChunkOres now = new ChunkOres(pos.toLongArray(), kind.toByteArray());
            if (old == null || !old.same(now)) {
                sightings.put(key, now);
                dirty = true;
            }
        }
        return complete ? 1 : 2;
    }

    private static boolean exposed(ChunkAccess chunk, ChunkAccess west, ChunkAccess east,
                                   ChunkAccess north, ChunkAccess south, int cx, int cz,
                                   int wx, int wy, int wz, int minY, int maxY,
                                   BlockPos.MutableBlockPos q) {
        for (int d = 0; d < 6; d++) {
            int nx = wx + DX[d];
            int ny = wy + DY[d];
            int nz = wz + DZ[d];
            if (ny < minY || ny > maxY) {
                continue;
            }
            int ncx = nx >> 4;
            int ncz = nz >> 4;
            ChunkAccess c;
            if (ncx == cx && ncz == cz) {
                c = chunk;
            } else if (ncx < cx) {
                c = west;
            } else if (ncx > cx) {
                c = east;
            } else if (ncz < cz) {
                c = north;
            } else {
                c = south;
            }
            if (c == null) {
                continue;   // unloaded neighbour reads as air, so never treat it as a cave
            }
            BlockState s = c.getBlockState(q.set(nx, ny, nz));
            if (s.isAir() || !s.getFluidState().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ clustering

    private static void rebuild() {
        int[] counts = new int[OreType.VALUES.length];
        for (ChunkOres c : sightings.values()) {
            for (byte k : c.kind()) {
                counts[k]++;
            }
        }
        List<Zone> out = new ArrayList<>();
        for (OreType t : OreType.VALUES) {
            int n = counts[t.ordinal()];
            if (n == 0) {
                continue;
            }
            long[] pos = new long[n];
            int i = 0;
            for (ChunkOres c : sightings.values()) {
                byte[] kinds = c.kind();
                long[] ps = c.pos();
                for (int j = 0; j < kinds.length; j++) {
                    if (kinds[j] == t.ordinal()) {
                        pos[i++] = ps[j];
                    }
                }
            }
            cluster(t, pos, out);
        }
        allZones = out.toArray(NONE);
    }

    private static void cluster(OreType t, long[] pos, List<Zone> out) {
        Arrays.sort(pos);   // fixed order: same ores give the same zones
        int merge = t.margin;
        int span = t.maxSpan();
        List<int[]> clusters = new ArrayList<>();   // minX, minY, minZ, maxX, maxY, maxZ, count
        Long2ObjectOpenHashMap<IntArrayList> grid = new Long2ObjectOpenHashMap<>();

        for (long p : pos) {
            int x = BlockPos.getX(p);
            int y = BlockPos.getY(p);
            int z = BlockPos.getZ(p);
            int gx = Math.floorDiv(x, span);
            int gy = Math.floorDiv(y, span);
            int gz = Math.floorDiv(z, span);

            int[] hit = null;
            search:
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        IntArrayList list = grid.get(BlockPos.asLong(gx + dx, gy + dy, gz + dz));
                        if (list == null) {
                            continue;
                        }
                        for (int k = 0; k < list.size(); k++) {
                            int[] c = clusters.get(list.getInt(k));
                            if (fits(c, x, y, z, merge, span)) {
                                hit = c;
                                break search;
                            }
                        }
                    }
                }
            }

            if (hit != null) {
                hit[0] = Math.min(hit[0], x);
                hit[1] = Math.min(hit[1], y);
                hit[2] = Math.min(hit[2], z);
                hit[3] = Math.max(hit[3], x);
                hit[4] = Math.max(hit[4], y);
                hit[5] = Math.max(hit[5], z);
                hit[6]++;
            } else {
                clusters.add(new int[]{x, y, z, x, y, z, 1});
                long key = BlockPos.asLong(gx, gy, gz);
                IntArrayList list = grid.get(key);
                if (list == null) {
                    list = new IntArrayList();
                    grid.put(key, list);
                }
                list.add(clusters.size() - 1);
            }
        }

        for (int[] c : clusters) {
            int count = c[6];
            int e = count == 1 ? t.margin : count <= 3 ? Math.max(1, t.margin - 1) : Math.max(1, t.margin / 2);
            int ey = Math.max(1, e - 1);
            float hidden = Math.max(0f, (t.size - count) / (float) t.size);
            out.add(new Zone(t, c[0] - e, c[1] - ey, c[2] - e, c[3] + 1 + e, c[4] + 1 + ey, c[5] + 1 + e,
                    0.35f + 0.65f * hidden));
        }
    }

    private static boolean fits(int[] c, int x, int y, int z, int merge, int span) {
        int nminX = Math.min(c[0], x), nmaxX = Math.max(c[3], x);
        int nminY = Math.min(c[1], y), nmaxY = Math.max(c[4], y);
        int nminZ = Math.min(c[2], z), nmaxZ = Math.max(c[5], z);
        if (nmaxX - nminX + 1 > span || nmaxY - nminY + 1 > span || nmaxZ - nminZ + 1 > span) {
            return false;
        }
        int gap = Math.max(Math.max(Math.max(c[0] - x, x - c[3]), Math.max(c[1] - y, y - c[4])),
                Math.max(c[2] - z, z - c[5]));
        return gap <= merge;
    }

    // --------------------------------------------------------------- visible

    private static void refreshVisible(Minecraft mc) {
        double px = mc.player.getX();
        double py = mc.player.getY();
        double pz = mc.player.getZ();
        double max2 = kRenderDistance * kRenderDistance;

        List<Zone> tmp = new ArrayList<>();
        for (Zone z : allZones) {
            if (OreZonesClient.accepts(z.ore()) && dist2(z, px, py, pz) <= max2) {
                tmp.add(z);
            }
        }
        tmp.sort((a, b) -> Double.compare(dist2(a, px, py, pz), dist2(b, px, py, pz)));
        if (tmp.size() > kMaxDrawn) {
            tmp = tmp.subList(0, kMaxDrawn);
        }
        visible = tmp.toArray(NONE);
    }

    private static double dist2(Zone z, double px, double py, double pz) {
        double dx = (z.minX() + z.maxX()) * 0.5 - px;
        double dy = (z.minY() + z.maxY()) * 0.5 - py;
        double dz = (z.minZ() + z.maxZ()) * 0.5 - pz;
        return dx * dx + dy * dy + dz * dz;
    }
}
