package dev.secret.orezones;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Every ore the mod tracks.
 *
 * size   = typical vein size from vanilla worldgen (used to estimate how much of a vein is still hidden)
 * margin = how far (blocks) the rest of the vein can reach past a block you can see
 * tier   = 0 common, 1 valuable, 2 top (used by the F7 filter)
 * dims   = bitmask, 1 = Overworld, 2 = Nether
 */
enum OreType {
    COAL("C", 0x101010, 17, 5, 0, 1, Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE),
    IRON("I", 0xE0B090, 9, 4, 0, 1, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE),
    COPPER("U", 0xE8833A, 10, 4, 0, 1, Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE),
    GOLD("G", 0xFFD83D, 9, 4, 1, 1, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE),
    REDSTONE("R", 0xFF2A2A, 8, 3, 1, 1, Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE),
    LAPIS("L", 0x2447FF, 7, 3, 1, 1, Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE),
    DIAMOND("D", 0x55D5FF, 8, 3, 2, 1, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE),
    EMERALD("E", 0x22DD66, 3, 2, 2, 1, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE),
    QUARTZ("Q", 0xF0F0F0, 14, 5, 0, 2, Blocks.NETHER_QUARTZ_ORE),
    NETHER_GOLD("N", 0xFFB300, 10, 4, 1, 2, Blocks.NETHER_GOLD_ORE),
    DEBRIS("A", 0xB44CFF, 3, 2, 2, 2, Blocks.ANCIENT_DEBRIS);

    static final OreType[] VALUES = values();
    private static final Map<Block, OreType> BY_BLOCK = new IdentityHashMap<>();

    static {
        for (OreType t : VALUES) {
            for (Block b : t.blocks) {
                BY_BLOCK.put(b, t);
            }
        }
    }

    final String tag;
    final int r, g, b;
    final int size, margin, tier, dims;
    final Block[] blocks;

    OreType(String tag, int rgb, int size, int margin, int tier, int dims, Block... blocks) {
        this.tag = tag;
        this.r = (rgb >> 16) & 0xFF;
        this.g = (rgb >> 8) & 0xFF;
        this.b = rgb & 0xFF;
        this.size = size;
        this.margin = margin;
        this.tier = tier;
        this.dims = dims;
        this.blocks = blocks;
    }

    /** Max edge length of one zone box. */
    int maxSpan() {
        return Math.max(4, margin * 2);
    }

    static OreType of(Block block) {
        return BY_BLOCK.get(block);
    }
}
