package dev.secret.orezones;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Estimates where ore is likely to be, from data the server already sent the client.
 *
 * Inputs:
 *  - exposed ore faces in loaded chunks (an ore only counts if it touches air or fluid, i.e. a block
 *    the server would have sent as a real ore even with anti-xray on; decoys never count)
 *  - vanilla 1.18+ y-level distributions per ore (approximated)
 *  - how solid the surrounding 8x8x8 cell is (hidden ore needs rock to sit in)
 *
 * Output: coarse 8x8x8 cells with a score. Never an exact block position.
 */
final class ZoneEstimator {
    // ---- config ----
    static final int kScanRadiusChunks = 4;
    static final int kScanEveryTicks = 2;       // one chunk per this many ticks
    static final int kRescoreEveryTicks = 20;
    static final int kRescanAfterTicks = 600;   // re-read a chunk after this long (mining changes it)
    static final int kCell = 8;
    static final int kReachCellsXZ = 5;         // zones within 40 blocks horizontally
    static final int kReachCellsY = 3;          // and 24 blocks vertically
    static final int kMaxZonesPerOre = 4;
    static final double kMinZoneSpacing = 12.0;
    static final double kSightingRange = 14.0;
    static final double kSightingFalloff = 6.0;
    static final float kMinScore = 0.05f;

    enum Ore {
        DIAMOND(0x55, 0xD5, 0xFF),   // light blue
        LAPIS(0x24, 0x4B, 0xFF),     // blue
        COAL(0x10, 0x10, 0x10);      // black

        final int r, g, b;

        Ore(int r, int g, int b) {
            this.r = r;
            this.g = g;
            this.b = b;
        }
    }

    record Sighting(Ore ore, int x, int y, int z) {}

    record Zone(Ore ore, int x, int y, int z, float score) {}

    private static final Map<Long, List<Sighting>> sightings = new HashMap<>();
    private static final Map<Long, Integer> scannedAt = new HashMap<>();
    private static volatile List<Zone> zones = List.of();
    private static ClientLevel lastLevel;
    private static int tickCounter = 0;

    static List<Zone> zones() {
        return zones;
    }

    static void clear() {
        sightings.clear();
        scannedAt.clear();
        zones = List.of();
    }

    static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level != lastLevel) {
            clear();
            lastLevel = level;
        }
        tickCounter++;

        if (level.dimension() != Level.OVERWORLD) {
            zones = List.of();
            return;
        }

        if (tickCounter % kScanEveryTicks == 0) {
            scanNextChunk(mc, level);
        }
        if (tickCounter % kRescoreEveryTicks == 0) {
            rescore(mc, level);
        }
    }

    // ---------------------------------------------------------------- scanning

    private static void scanNextChunk(Minecraft mc, ClientLevel level) {
        int pcx = mc.player.blockPosition().getX() >> 4;
        int pcz = mc.player.blockPosition().getZ() >> 4;

        long best = 0L;
        int bestAge = -1;
        boolean found = false;
        for (int dx = -kScanRadiusChunks; dx <= kScanRadiusChunks; dx++) {
            for (int dz = -kScanRadiusChunks; dz <= kScanRadiusChunks; dz++) {
                long key = ChunkPos.asLong(pcx + dx, pcz + dz);
                Integer at = scannedAt.get(key);
                int age = at == null ? Integer.MAX_VALUE : tickCounter - at;
                if (at != null && age < kRescanAfterTicks) {
                    continue;
                }
                if (age > bestAge) {
                    bestAge = age;
                    best = key;
                    found = true;
                }
            }
        }
        if (!found) {
            return;
        }
        int cx = ChunkPos.getX(best);
        int cz = ChunkPos.getZ(best);
        scannedAt.put(best, tickCounter);
        scanChunk(level, cx, cz, best);
    }

    private static void scanChunk(ClientLevel level, int cx, int cz, long key) {
        ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
        if (chunk == null) {
            return;
        }
        List<Sighting> out = new ArrayList<>();
        int minY = level.getMinY();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        for (int si = 0; si < chunk.getSectionsCount(); si++) {
            LevelChunkSection sec = chunk.getSection(si);
            if (sec.hasOnlyAir() || !sec.maybeHas(ZoneEstimator::isOre)) {
                continue;
            }
            int baseY = minY + (si << 4);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        Ore ore = oreOf(sec.getBlockState(x, y, z).getBlock());
                        if (ore == null) {
                            continue;
                        }
                        int wx = (cx << 4) + x;
                        int wy = baseY + y;
                        int wz = (cz << 4) + z;
                        if (exposed(level, p.set(wx, wy, wz))) {
                            out.add(new Sighting(ore, wx, wy, wz));
                        }
                    }
                }
            }
        }
        sightings.put(key, out);
    }

    private static boolean isOre(BlockState s) {
        return oreOf(s.getBlock()) != null;
    }

    private static Ore oreOf(Block b) {
        if (b == Blocks.DIAMOND_ORE || b == Blocks.DEEPSLATE_DIAMOND_ORE) return Ore.DIAMOND;
        if (b == Blocks.LAPIS_ORE || b == Blocks.DEEPSLATE_LAPIS_ORE) return Ore.LAPIS;
        if (b == Blocks.COAL_ORE || b == Blocks.DEEPSLATE_COAL_ORE) return Ore.COAL;
        return null;
    }

    private static boolean exposed(ClientLevel level, BlockPos.MutableBlockPos origin) {
        int ox = origin.getX(), oy = origin.getY(), oz = origin.getZ();
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (Direction d : Direction.values()) {
            q.set(ox + d.getStepX(), oy + d.getStepY(), oz + d.getStepZ());
            BlockState s = level.getBlockState(q);
            if (s.isAir() || !s.getFluidState().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- scoring

    /** Approximate vanilla 1.18+ ore height distributions, 0..1. */
    private static float yPrior(Ore ore, int y) {
        switch (ore) {
            case DIAMOND: {
                if (y < -64 || y > 16) return 0f;
                float t = (16f - y) / 80f;          // 1.0 at the bottom, 0 at y=16
                return t * t;
            }
            case LAPIS: {
                if (y < -64 || y > 64) return 0f;
                return 1f - Math.abs(y) / 64f;      // triangle around y=0
            }
            case COAL: {
                if (y < 0) return 0.02f;
                if (y > 192) return 0f;
                return 0.25f + 0.75f * (1f - Math.abs(y - 96f) / 96f);
            }
        }
        return 0f;
    }

    private static void rescore(Minecraft mc, ClientLevel level) {
        BlockPos pp = mc.player.blockPosition();
        int pcx = Math.floorDiv(pp.getX(), kCell);
        int pcy = Math.floorDiv(pp.getY(), kCell);
        int pcz = Math.floorDiv(pp.getZ(), kCell);

        // flatten sightings per ore
        List<List<Sighting>> perOre = new ArrayList<>();
        for (int i = 0; i < Ore.values().length; i++) perOre.add(new ArrayList<>());
        for (List<Sighting> list : sightings.values()) {
            for (Sighting s : list) perOre.get(s.ore().ordinal()).add(s);
        }

        List<Zone> candidates = new ArrayList<>();
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        int minY = level.getMinY();
        int maxY = level.getMaxY();

        for (int dx = -kReachCellsXZ; dx <= kReachCellsXZ; dx++) {
            for (int dz = -kReachCellsXZ; dz <= kReachCellsXZ; dz++) {
                for (int dy = -kReachCellsY; dy <= kReachCellsY; dy++) {
                    int cx = pcx + dx, cy = pcy + dy, cz = pcz + dz;
                    int x0 = cx * kCell, y0 = cy * kCell, z0 = cz * kCell;
                    if (y0 < minY || y0 > maxY) continue;
                    if (dx == 0 && dy == 0 && dz == 0) continue;

                    float solid = solidFraction(level, q, x0, y0, z0);
                    if (solid < 0.35f) continue;   // mostly air: nothing hidden in there
                    // cells that border open cave volume have more surface we already saw
                    float surfaceBonus = (solid < 0.95f) ? 1.15f : 1.0f;

                    double mx = x0 + kCell / 2.0, my = y0 + kCell / 2.0, mz = z0 + kCell / 2.0;

                    for (Ore ore : Ore.values()) {
                        float prior = yPrior(ore, y0 + kCell / 2);
                        if (prior <= 0f) continue;
                        double boost = 0;
                        for (Sighting s : perOre.get(ore.ordinal())) {
                            double ddx = s.x() - mx, ddy = s.y() - my, ddz = s.z() - mz;
                            double d = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
                            if (d <= kSightingRange) {
                                boost += Math.exp(-d / kSightingFalloff);
                            }
                        }
                        double score = prior * solid * surfaceBonus * (0.35 + 2.5 * Math.min(boost, 2.0));
                        if (score >= kMinScore) {
                            candidates.add(new Zone(ore, x0, y0, z0, (float) Math.min(score, 1.0)));
                        }
                    }
                }
            }
        }

        // top zones per ore, spaced out so they don't pile up on one spot
        List<Zone> chosen = new ArrayList<>();
        candidates.sort((a, b) -> Float.compare(b.score(), a.score()));
        int[] counts = new int[Ore.values().length];
        for (Zone z : candidates) {
            if (counts[z.ore().ordinal()] >= kMaxZonesPerOre) continue;
            boolean tooClose = false;
            for (Zone c : chosen) {
                if (c.ore() != z.ore()) continue;
                double ddx = c.x() - z.x(), ddy = c.y() - z.y(), ddz = c.z() - z.z();
                if (Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz) < kMinZoneSpacing) {
                    tooClose = true;
                    break;
                }
            }
            if (tooClose) continue;
            chosen.add(z);
            counts[z.ore().ordinal()]++;
        }
        zones = chosen;
    }

    /** Fraction of 27 sample points in the cell that are non-air, non-fluid. */
    private static float solidFraction(ClientLevel level, BlockPos.MutableBlockPos q, int x0, int y0, int z0) {
        int solid = 0, total = 0;
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    q.set(x0 + 1 + i * 3, y0 + 1 + j * 3, z0 + 1 + k * 3);
                    BlockState s = level.getBlockState(q);
                    if (!s.isAir() && s.getFluidState().isEmpty()) solid++;
                    total++;
                }
            }
        }
        return solid / (float) total;
    }
}
