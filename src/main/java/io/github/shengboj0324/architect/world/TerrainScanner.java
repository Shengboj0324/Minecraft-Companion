package io.github.shengboj0324.architect.world;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Surveys real terrain into a {@link TerrainMap} (main thread). Only columns in loaded chunks are read, so a survey
 * never loads chunks synchronously; the rest stay unknown. For each column it finds the top block that is real
 * ground: below water, and below trees, plants, snow layers and other vegetation, which the placer clears anyway.
 */
public final class TerrainScanner {

    private TerrainScanner() { }

    /** Surveys the world rectangle [x1..x2] x [z1..z2] (inclusive). */
    public static TerrainMap scan(World w, int x1, int z1, int x2, int z2) {
        TerrainMap t = new TerrainMap(x1, z1, x2, z2);
        Map<Long, String> biomes = new HashMap<>();
        int min = w.getMinHeight();
        for (int z = t.minZ(); z <= t.maxZ(); z++) {
            for (int x = t.minX(); x <= t.maxX(); x++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                int water = 0, steps = 0;
                Block b = w.getBlockAt(x, y, z);
                while (!isGround(b) && y > min + 1 && steps++ < 160) {
                    if (isWater(b)) water++;
                    y--;
                    b = w.getBlockAt(x, y, z);
                }
                if (!isGround(b)) continue;
                int canopy = Math.max(0, w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING) - y - water);
                final int fx = x, fy = y, fz = z;
                String biome = biomes.computeIfAbsent(((long) (x >> 2) << 32) | ((z >> 2) & 0xFFFFFFFFL),
                        k -> w.getBiome(fx, fy + 1, fz).getKey().getKey());
                t.set(x, z, y, water, canopy, b.getType().getKey().getKey(), biome);
            }
        }
        return t;
    }

    private static boolean isWater(Block b) {
        Material m = b.getType();
        return b.isLiquid() || m == Material.KELP || m == Material.KELP_PLANT || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS || m == Material.BUBBLE_COLUMN;
    }

    /** Solid natural ground: not air, water, plants, trees or other vegetation. */
    static boolean isGround(Block b) {
        if (b.isEmpty() || b.isLiquid() || !b.isSolid()) return false;
        return !isVegetation(b.getType());
    }

    /** Trees and large plants: cleared from a footprint and ignored when measuring ground height. */
    public static boolean isVegetation(Material m) {
        return Tag.LOGS.isTagged(m) || Tag.LEAVES.isTagged(m) || m == Material.BAMBOO || m == Material.CACTUS || m == Material.MUSHROOM_STEM
                || m == Material.BROWN_MUSHROOM_BLOCK || m == Material.RED_MUSHROOM_BLOCK || m == Material.BEE_NEST || m == Material.MANGROVE_ROOTS
                || m == Material.MUDDY_MANGROVE_ROOTS || m == Material.PUMPKIN || m == Material.MELON || m == Material.SNOW || m == Material.POWDER_SNOW
                || m == Material.VINE || m == Material.COCOA || m == Material.SWEET_BERRY_BUSH || m == Material.BIG_DRIPLEAF || m == Material.PALE_HANGING_MOSS
                || m == Material.CREAKING_HEART;
    }
}
