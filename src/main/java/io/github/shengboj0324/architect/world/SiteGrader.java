package io.github.shengboj0324.architect.world;

import io.github.shengboj0324.architect.core.BlockState;
import io.github.shengboj0324.architect.core.Ground;
import io.github.shengboj0324.architect.core.VoxelModel;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Set;

/**
 * Blends a levelled build site into natural terrain. The placer cuts and fills a flat pad under the footprint;
 * without grading that leaves a cliff where it cut into a hill and a ledge where it filled a dip. The grader
 * shapes a ring of columns around the footprint so the ground eases from the pad's height back to the natural
 * height, using the local surface (grass over dirt, sand over sandstone, ...). Its cells are added to a copy of
 * the design, so they are placed, journaled and undone like the rest of the build.
 */
public final class SiteGrader {

    private SiteGrader() { }

    private static final Set<String> NATURAL = Set.of("grass_block", "dirt", "coarse_dirt", "podzol", "mycelium", "rooted_dirt", "moss_block", "mud", "clay",
            "sand", "red_sand", "gravel", "snow_block", "stone", "andesite", "diorite", "granite", "deepslate", "tuff", "calcite", "dripstone_block",
            "sandstone", "red_sandstone", "terracotta", "white_terracotta", "orange_terracotta", "yellow_terracotta", "brown_terracotta", "red_terracotta",
            "light_gray_terracotta", "netherrack", "soul_sand", "soul_soil", "basalt", "blackstone", "end_stone", "packed_ice", "blue_ice", "pale_moss_block");

    /** Columns (keys with y=0) where the design has any cell: the footprint the placer levels. */
    public static LongOpenHashSet footprint(VoxelModel m) {
        LongOpenHashSet out = new LongOpenHashSet();
        for (long k : m.keys()) out.add(VoxelModel.key(VoxelModel.kx(k), 0, VoxelModel.kz(k)));
        return out;
    }

    /** The block for an exposed or rebuilt ground surface: the natural one, or grass when it is not a natural ground block. */
    public static String surfaceFor(String natural) {
        return natural != null && NATURAL.contains(natural) ? natural : "grass_block";
    }

    /** What goes under a surface block when ground is raised. */
    public static String subsoilFor(String surface) {
        return switch (surface) {
            case "grass_block", "podzol", "mycelium", "moss_block", "coarse_dirt", "rooted_dirt", "pale_moss_block" -> "dirt";
            case "sand" -> "sandstone";
            case "red_sand" -> "red_sandstone";
            case "gravel", "snow_block", "packed_ice", "blue_ice" -> "stone";
            case "soul_sand" -> "soul_soil";
            default -> surface;
        };
    }

    /**
     * Grades a ring {@code margin} columns wide around {@code foot} into {@code out}: each ring column's ground moves
     * from the pad (y=0) toward its natural height along a smooth curve, cutting (with any trees on top) or filling,
     * never by more than {@code maxChange} blocks. Columns under water or not surveyed are left alone, and cells the
     * design already sets win. Returns the columns it changed.
     */
    public static LongOpenHashSet grade(VoxelModel out, Ground ground, LongOpenHashSet foot, int margin, int maxChange) {
        LongOpenHashSet changed = new LongOpenHashSet();
        if (margin <= 0 || foot.isEmpty()) return changed;
        TerrainMap.View view = ground instanceof TerrainMap.View tv ? tv : null;
        // Chebyshev distance from the footprint, breadth first
        Long2IntOpenHashMap dist = new Long2IntOpenHashMap();
        dist.defaultReturnValue(-1);
        LongArrayFIFOQueue q = new LongArrayFIFOQueue();
        for (long k : foot) { dist.put(k, 0); q.enqueue(k); }
        while (!q.isEmpty()) {
            long k = q.dequeueLong();
            int d = dist.get(k);
            if (d >= margin) continue;
            int x = VoxelModel.kx(k), z = VoxelModel.kz(k);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                long n = VoxelModel.key(x + dx, 0, z + dz);
                if (dist.get(n) < 0) { dist.put(n, d + 1); q.enqueue(n); }
            }
        }
        for (Long2IntOpenHashMap.Entry e : dist.long2IntEntrySet()) {
            int d = e.getIntValue();
            if (d <= 0) continue;
            int x = VoxelModel.kx(e.getLongKey()), z = VoxelModel.kz(e.getLongKey());
            int g = ground.groundY(x, z);
            if (g == Ground.UNKNOWN || ground.waterDepth(x, z) > 0) continue;
            double s = smooth(d / (double) (margin + 1));
            int t = (int) Math.round(g * s);
            t = g - Math.max(-maxChange, Math.min(maxChange, g - t));
            if (t == g) continue;
            String sf = surfaceFor(view == null ? null : view.surface(x, z));
            BlockState surface = BlockState.of(sf), sub = BlockState.of(subsoilFor(sf));
            if (g > t) {
                int canopy = view == null ? 0 : Math.min(40, view.canopy(x, z));
                for (int y = t + 1; y <= g + canopy; y++) if (out.get(x, y, z) == null) out.set(x, y, z, BlockState.AIR);
            } else {
                for (int y = g + 1; y < t; y++) if (out.get(x, y, z) == null) out.set(x, y, z, sub);
            }
            if (out.get(x, t, z) == null) out.set(x, t, z, surface);
            changed.add(e.getLongKey());
        }
        return changed;
    }

    private static double smooth(double v) {
        v = Math.max(0, Math.min(1, v));
        return v * v * (3 - 2 * v);
    }
}
