package io.github.shengboj0324.architect.world;

import org.bukkit.World;
import org.bukkit.block.BlockFace;

/**
 * Maps model coordinates to the world. The model's front faces north (-z); a clockwise quarter-turn rotation
 * makes it face the player. Rotation is about the origin and independent of the model size, so a design that is
 * later expanded (toward +x/+z) keeps every existing block where it was.
 */
public record Placement(World world, int ox, int oy, int oz, int quarter) {

    public int wx(int x, int z) {
        return ox + switch (quarter & 3) { case 1 -> -z; case 2 -> -x; case 3 -> z; default -> x; };
    }

    public int wz(int x, int z) {
        return oz + switch (quarter & 3) { case 1 -> x; case 2 -> -z; case 3 -> -x; default -> z; };
    }

    public int wy(int y) { return oy + y; }

    /** Quarter turns that make a north-facing front face the given direction. */
    public static int quarterFacing(BlockFace front) {
        return switch (front) { case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; };
    }

    /** Rotated offset of a local (x, z) about the origin, without translation. */
    public static int[] rot(int x, int z, int q) {
        return switch (q & 3) {
            case 1 -> new int[]{-z, x};
            case 2 -> new int[]{-x, -z};
            case 3 -> new int[]{z, -x};
            default -> new int[]{x, z};
        };
    }
}
