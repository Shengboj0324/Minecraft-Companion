package io.github.shengboj0324.architect.world;

import io.github.shengboj0324.architect.core.VoxelModel;
import java.util.Arrays;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

/** Reads the terrain around the player (main thread) and decides where and how a finished model is placed. */
public final class SiteSurvey {

    private SiteSurvey() { }

    /** Where a build should go: a point, the direction the viewer looks, and whether the point is the footprint centre. */
    public record Site(Location point, BlockFace look, boolean centred) {}

    /** Horizontal direction the player looks, snapped to a cardinal face. */
    public static BlockFace facing(Player p) { return facing(p.getLocation().getYaw()); }

    public static BlockFace facing(float yawDeg) {
        float yaw = (yawDeg % 360 + 360) % 360;
        if (yaw >= 315 || yaw < 45) return BlockFace.SOUTH;
        if (yaw < 135) return BlockFace.WEST;
        if (yaw < 225) return BlockFace.NORTH;
        return BlockFace.EAST;
    }

    /** A short description of the site ahead of the player, given to Claude as context. */
    public static String describe(Player p, int radius) {
        World w = p.getWorld();
        BlockFace f = facing(p);
        Location c = p.getLocation().getBlock().getRelative(f, radius).getLocation();
        int feet = p.getLocation().getBlockY();
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE, water = 0, trees = 0, n = 0;
        long sum = 0;
        int step = Math.max(4, radius / 12);
        for (int dx = -radius; dx <= radius; dx += step) {
            for (int dz = -radius; dz <= radius; dz += step) {
                int x = c.getBlockX() + dx, z = c.getBlockZ() + dz;
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                Block top = w.getBlockAt(x, y, z);
                if (top.isLiquid()) water++;
                if (w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING) > y + 2) trees++;
                min = Math.min(min, y);
                max = Math.max(max, y);
                sum += y;
                n++;
            }
        }
        String biome = w.getBiome(c).getKey().getKey().replace('_', ' ');
        int headroom = w.getMaxHeight() - 1 - (min == Integer.MAX_VALUE ? feet : (int) (sum / Math.max(1, n)));
        return "The player stands at y=" + feet + " looking " + f.name().toLowerCase() + ". The build goes in front of them, its front (z=0) facing them.\n"
                + "World build limit is y=" + (w.getMaxHeight() - 1) + ", so about " + headroom + " blocks of height are available above the ground here.\n"
                + "Terrain within " + radius + " blocks ahead: ground from y=" + (n == 0 ? feet : min) + " to y=" + (n == 0 ? feet : max) + " (average " + (sum / Math.max(1, n)) + "), "
                + pct(water, n) + " water, " + pct(trees, n) + " tree cover, biome " + biome + ", world " + w.getEnvironment().name().toLowerCase() + ".\n"
                + "The server levels the site: it clears trees and terrain above the ground layer inside the footprint and fills foundations down to solid ground. "
                + "Design the y=0 layer as the ground floor/plinth; for sloped or watery sites a raised plinth, terraces or a dock suit well. Time of day: "
                + (w.getTime() > 12500 && w.getTime() < 23500 ? "night" : "day") + ".";
    }

    /** The opening site description for a design on surveyed land: where the player is and what the land ahead is like. */
    public static String describe(Player p, SitePlan plan) {
        World w = p.getWorld();
        int feet = p.getLocation().getBlockY();
        return "The player stands at y=" + feet + " looking " + facing(p).name().toLowerCase() + ". The build goes in front of them, its front (z=0) facing them.\n"
                + "World build limit is y=" + (w.getMaxHeight() - 1) + ", world " + w.getEnvironment().name().toLowerCase() + ". Time of day: "
                + (w.getTime() > 12500 && w.getTime() < 23500 ? "night" : "day") + ".\n"
                + plan.overview()
                + "This is real, naturally generated land, not a flat world. set_plan fixes where the envelope stands and answers with a terrain report and an elevation map "
                + "of the envelope in model coordinates (ground heights relative to the ground layer y=0); survey_terrain gives exact heights. Choose how the build meets the land with "
                + "set_plan terrain: \"level\" (default; the server clears the footprint, levels a pad at y=0, fills foundations down to solid ground and grades the pad's edges smoothly "
                + "into the surrounding terrain) or \"natural\" (the land stays; only trees and plants in the footprint are cleared; design onto the terrain with terraces, stepped floors, "
                + "stilts, retaining walls, piers or bridges, and carve into slopes with air where you need to).";
    }

    private static String pct(int a, int n) { return n == 0 ? "0%" : Math.round(100.0 * a / n) + "%"; }

    /**
     * Chooses the placement for a finished model: rotated to face the player, its front centred a few blocks ahead,
     * and its ground layer on the median terrain height of the footprint.
     */
    public static Placement place(Player p, VoxelModel m, int distance) {
        return place(new Site(p.getLocation(), facing(p), false), m, distance);
    }

    /**
     * Places the model for a site: either its front centred {@code distance} blocks ahead of the point (facing back
     * toward it), or, when the site is centred, with its footprint centred on the point.
     */
    public static Placement place(Site site, VoxelModel m, int distance) {
        int[] b = m.bounds();
        BlockFace look = site.look();
        int q = Placement.quarterFacing(look.getOppositeFace());
        Block feet = site.point().getBlock();
        int ox, oz;
        if (site.centred()) {
            int[] mid = Placement.rot((b[0] + b[3]) / 2, (b[2] + b[5]) / 2, q);
            ox = feet.getX() - mid[0];
            oz = feet.getZ() - mid[1];
        } else {
            int tx = feet.getX() + look.getModX() * (distance + 1), tz = feet.getZ() + look.getModZ() * (distance + 1);
            int[] front = Placement.rot((b[0] + b[3]) / 2, b[2] - 1, q);
            ox = tx - front[0];
            oz = tz - front[1];
        }
        World w = site.point().getWorld();
        // median surface height under the used footprint
        int W = b[3] - b[0] + 1, D = b[5] - b[2] + 1;
        int step = Math.max(1, Math.max(W, D) / 24);
        int[] hs = new int[((W + step - 1) / step) * ((D + step - 1) / step)];
        int n = 0;
        for (int x = b[0]; x <= b[3]; x += step) for (int z = b[2]; z <= b[5]; z += step) {
            int[] r = Placement.rot(x, z, q);
            int sx = ox + r[0], sz = oz + r[1];
            if (!w.isChunkLoaded(sx >> 4, sz >> 4)) continue; // never load chunks synchronously just to sample terrain
            if (n < hs.length) hs[n++] = w.getHighestBlockYAt(sx, sz, HeightMap.OCEAN_FLOOR);
        }
        Arrays.sort(hs, 0, n);
        int ground = n == 0 ? feet.getY() - 1 : hs[n / 2];
        int oy = ground - Math.max(0, b[1]);
        oy = Math.max(w.getMinHeight() + 1, Math.min(oy, w.getMaxHeight() - (b[4] - b[1]) - 2));
        return new Placement(w, ox, oy, oz, q);
    }
}
