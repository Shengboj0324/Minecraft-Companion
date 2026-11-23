package io.github.shengboj0324.architect.core;

import java.util.Locale;

/** Horizontal directions in Minecraft's frame: north = -Z, south = +Z, east = +X, west = -X. */
public enum Dir {
    NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);

    public final int dx;
    public final int dz;

    Dir(int dx, int dz) {
        this.dx = dx;
        this.dz = dz;
    }

    public Dir opposite() { return values()[(ordinal() + 2) % 4]; }

    public Dir cw() { return values()[(ordinal() + 1) % 4]; }

    public Dir ccw() { return values()[(ordinal() + 3) % 4]; }

    public boolean isX() { return dx != 0; }

    public String key() { return name().toLowerCase(Locale.ROOT); }

    public static Dir parse(String s) {
        if (s == null) return null;
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "north", "n", "-z" -> NORTH;
            case "south", "s", "+z", "z" -> SOUTH;
            case "east", "e", "+x", "x" -> EAST;
            case "west", "w", "-x" -> WEST;
            default -> null;
        };
    }
}
