package io.github.shengboj0324.architect.core;

import java.util.Map;
import java.util.TreeMap;

/** Rotation / mirroring of directional block-state properties (pure string logic, no server needed). */
public final class StateTransforms {

    private StateTransforms() {}

    /** Rotates clockwise (seen from above) by q quarter turns. */
    public static BlockState rotate(BlockState s, int quarter) {
        final int q = ((quarter % 4) + 4) % 4;
        if (q == 0 || s.props().isEmpty()) return s;
        Map<String, String> p = new TreeMap<>(s.props());
        String facing = p.get("facing");
        if (facing != null) {
            Dir d = Dir.parse(facing);
            if (d != null) p.put("facing", rot(d, q).key());
        }
        if ((q & 1) == 1) {
            String axis = p.get("axis");
            if ("x".equals(axis)) p.put("axis", "z");
            else if ("z".equals(axis)) p.put("axis", "x");
            String shape = p.get("shape");
            if ("north_south".equals(shape)) p.put("shape", "east_west");
            else if ("east_west".equals(shape)) p.put("shape", "north_south");
        }
        String rotation = p.get("rotation");
        if (rotation != null) {
            try { p.put("rotation", Integer.toString((Integer.parseInt(rotation) + 4 * q) % 16)); } catch (NumberFormatException ignored) {}
        }
        permuteSides(s.props(), p, d -> rot(d, q));
        return BlockState.of(s.id(), p);
    }

    /** Mirrors across a vertical plane: axis 'x' flips east<->west, axis 'z' flips north<->south. */
    public static BlockState mirror(BlockState s, char axis) {
        if (s.props().isEmpty()) return s;
        Map<String, String> p = new TreeMap<>(s.props());
        java.util.function.UnaryOperator<Dir> flip = d -> axis == 'x'
                ? (d == Dir.EAST ? Dir.WEST : d == Dir.WEST ? Dir.EAST : d)
                : (d == Dir.NORTH ? Dir.SOUTH : d == Dir.SOUTH ? Dir.NORTH : d);
        String facing = p.get("facing");
        if (facing != null) {
            Dir d = Dir.parse(facing);
            if (d != null) p.put("facing", flip.apply(d).key());
        }
        String shape = p.get("shape");
        if (shape != null) {
            if (shape.endsWith("_left")) p.put("shape", shape.replace("_left", "_right"));
            else if (shape.endsWith("_right")) p.put("shape", shape.replace("_right", "_left"));
        }
        String hinge = p.get("hinge");
        if (hinge != null) p.put("hinge", hinge.equals("left") ? "right" : "left");
        String rotation = p.get("rotation");
        if (rotation != null) {
            try {
                int r = Integer.parseInt(rotation);
                p.put("rotation", Integer.toString(axis == 'x' ? (16 - r) % 16 : (24 - r) % 16));
            } catch (NumberFormatException ignored) {}
        }
        permuteSides(s.props(), p, flip);
        return BlockState.of(s.id(), p);
    }

    private static void permuteSides(Map<String, String> src, Map<String, String> dst, java.util.function.UnaryOperator<Dir> f) {
        boolean any = false;
        for (Dir d : Dir.values()) if (src.containsKey(d.key())) { any = true; break; }
        if (!any) return;
        for (Dir d : Dir.values()) dst.remove(d.key());
        for (Dir d : Dir.values()) {
            String v = src.get(d.key());
            if (v != null) dst.put(f.apply(d).key(), v);
        }
    }

    private static Dir rot(Dir d, int q) {
        Dir r = d;
        for (int i = 0; i < q; i++) r = r.cw();
        return r;
    }
}
