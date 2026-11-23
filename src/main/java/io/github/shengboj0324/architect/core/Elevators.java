package io.github.shengboj0324.architect.core;

import java.util.Arrays;

/**
 * Vertical circulation for multi-floor builds, from vanilla mechanics:
 * <ul>
 * <li>{@code bubble}: a glass shaft with soul sand at the bottom and an upward bubble column (walk in at the bottom,
 * rise, step out through a door at any floor; doors hold the water in). An optional down shaft beside it uses magma.</li>
 * <li>{@code ladder}: a ladder run against a wall through openings in each floor.</li>
 * <li>{@code native}: the plugin's own elevator pads, a lodestone set into each floor in one column (jump to go up,
 * sneak to go down), when the server has elevators enabled.</li>
 * </ul>
 * Floors are walking-surface y levels; the shaft serves every one of them.
 */
public final class Elevators {

    private Elevators() { }

    public static int build(Canvas c, String type, int x, int z, int[] floors, Dir door, String wall, String doorWood, boolean down) {
        if (floors == null || floors.length < 2) throw new IllegalArgumentException("an elevator needs at least two floors (\"floors\": [y1, y2, ...])");
        int[] f = floors.clone();
        Arrays.sort(f);
        long before = c.placedCount();
        switch (type == null ? "bubble" : type) {
            case "bubble", "water" -> {
                bubble(c, x, z, f, door, wall, doorWood, false);
                if (down) bubble(c, x + 2 * door.cw().dx, z + 2 * door.cw().dz, f, door, wall, doorWood, true);
            }
            case "ladder" -> ladder(c, x, z, f, door, wall);
            case "native", "pad", "lodestone" -> {
                for (int y : f) {
                    c.set(x, y, z, "lodestone");
                    c.air(x, y + 1, z);
                    c.air(x, y + 2, z);
                }
            }
            default -> throw new IllegalArgumentException("unknown elevator type '" + type + "' (bubble, ladder, native)");
        }
        return (int) (c.placedCount() - before);
    }

    private static void bubble(Canvas c, int x, int z, int[] f, Dir door, String wall, String doorWood, boolean down) {
        int bottom = f[0], top = f[f.length - 1];
        String w = wall == null ? "glass" : wall;
        // shaft walls all the way up, then a cap above the top exit
        for (int y = bottom; y <= top + 3; y++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            c.set(x + dx, y, z + dz, w);
        }
        c.set(x, top + 3, z, w);
        c.set(x, bottom, z, down ? "magma_block" : "soul_sand");
        for (int y = bottom + 1; y <= top + 1; y++) c.set(x, y, z, "bubble_column[drag=" + down + "]");
        c.air(x, top + 2, z);
        // a door at every floor (doors hold water back), with floor outside it
        String wood = doorWood == null ? "spruce" : doorWood;
        for (int y : f) {
            int dx = x + door.dx, dz = z + door.dz;
            c.set(dx, y, dz, w);
            c.set(dx, y + 1, dz, wood + "_door[facing=" + door.opposite().key() + ",half=lower,hinge=left]");
            c.set(dx, y + 2, dz, wood + "_door[facing=" + door.opposite().key() + ",half=upper,hinge=left]");
            if (!c.has(dx + door.dx, y, dz + door.dz)) c.set(dx + door.dx, y, dz + door.dz, w.contains("glass") ? "smooth_stone" : w);
        }
    }

    private static void ladder(Canvas c, int x, int z, int[] f, Dir wallSide, String wall) {
        int bottom = f[0], top = f[f.length - 1];
        String w = wall == null ? "@wall" : wall;
        int wx = x + wallSide.dx, wz = z + wallSide.dz;
        // the ladder runs up through every floor above the bottom (it replaces the floor block there) to the top floor
        for (int y = bottom + 1; y <= top; y++) {
            if (!c.has(wx, y, wz)) c.set(wx, y, wz, w);
            c.set(x, y, z, "ladder[facing=" + wallSide.opposite().key() + "]");
        }
        c.air(x, top + 1, z);
        c.air(x, top + 2, z);
    }
}
