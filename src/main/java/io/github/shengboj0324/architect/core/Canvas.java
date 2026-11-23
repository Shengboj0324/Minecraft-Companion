package io.github.shengboj0324.architect.core;

import java.util.Map;

/** Geometric drawing primitives over a {@link VoxelModel}, resolving material specs through {@link Materials}. */
public final class Canvas {

    public enum Mode { REPLACE, KEEP, }

    private final VoxelModel model;
    private final Materials materials;
    private Mode mode = Mode.REPLACE;
    private long placed;
    private Existing existing; // the world around a build being renovated (null for new designs)

    public Canvas(VoxelModel model, Materials materials) {
        this.model = model;
        this.materials = materials;
    }

    public VoxelModel model() { return model; }
    public Materials materials() { return materials; }
    public BlockCatalog catalog() { return materials.catalog(); }
    public Mode mode() { return mode; }
    public void mode(Mode m) { this.mode = m; }
    public long placedCount() { return placed; }
    public Existing existing() { return existing; }
    public void existing(Existing e) { this.existing = e; }

    // ---------------------------------------------------------------- single blocks

    public void set(int x, int y, int z, String spec) {
        put(x, y, z, materials.resolve(spec, x, y, z));
    }

    public void set(int x, int y, int z, String spec, Map<String, String> props) {
        put(x, y, z, materials.resolve(spec, x, y, z, props));
    }

    /** Places the first spec that resolves (e.g. "@trim:wall", "@trim:fence", "cobblestone_wall"). */
    public boolean trySet(int x, int y, int z, String... specs) {
        for (String spec : specs) {
            try { set(x, y, z, spec); return true; } catch (IllegalArgumentException ignored) { }
        }
        return false;
    }

    public void put(int x, int y, int z, BlockState s) {
        boolean ok = mode == Mode.KEEP ? model.setIfEmpty(x, y, z, s) : model.set(x, y, z, s);
        if (ok) placed++;
    }

    public void air(int x, int y, int z) { model.set(x, y, z, BlockState.AIR); }

    public boolean has(int x, int y, int z) { return model.solidAt(x, y, z); }

    /** Places only when the cell touches an existing solid block face-to-face (avoids floating trim). */
    public boolean setAttached(int x, int y, int z, String spec, Map<String, String> props) {
        if (!(has(x + 1, y, z) || has(x - 1, y, z) || has(x, y + 1, z) || has(x, y - 1, z) || has(x, y, z + 1) || has(x, y, z - 1))) return false;
        if (props == null) set(x, y, z, spec); else set(x, y, z, spec, props);
        return true;
    }

    /** First free y above the highest solid block in column (x,z) at or below fromY; 0 when the column is empty. */
    public int groundAt(int x, int z, int fromY) {
        for (int y = fromY; y >= -16; y--) if (has(x, y, z)) return y + 1;
        return 0;
    }

    public BlockState get(int x, int y, int z) { return model.get(x, y, z); }

    public static Map<String, String> stairs(Dir facing, boolean top) {
        return Map.of("facing", facing.key(), "half", top ? "top" : "bottom");
    }

    public static Map<String, String> slab(boolean top) {
        return Map.of("type", top ? "top" : "bottom");
    }

    public static Map<String, String> axis(char a) {
        return Map.of("axis", String.valueOf(a));
    }

    // ---------------------------------------------------------------- boxes

    public void fill(int x1, int y1, int z1, int x2, int y2, int z2, String spec) {
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2), ay = Math.min(y1, y2), by = Math.max(y1, y2), az = Math.min(z1, z2), bz = Math.max(z1, z2);
        for (int y = ay; y <= by; y++) for (int x = ax; x <= bx; x++) for (int z = az; z <= bz; z++) set(x, y, z, spec);
    }

    public void carve(int x1, int y1, int z1, int x2, int y2, int z2) {
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2), ay = Math.min(y1, y2), by = Math.max(y1, y2), az = Math.min(z1, z2), bz = Math.max(z1, z2);
        for (int y = ay; y <= by; y++) for (int x = ax; x <= bx; x++) for (int z = az; z <= bz; z++) air(x, y, z);
    }

    /** Hollow box: shell of thickness 1 (floor, ceiling and walls). */
    public void hollow(int x1, int y1, int z1, int x2, int y2, int z2, String spec) {
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2), ay = Math.min(y1, y2), by = Math.max(y1, y2), az = Math.min(z1, z2), bz = Math.max(z1, z2);
        for (int y = ay; y <= by; y++) for (int x = ax; x <= bx; x++) for (int z = az; z <= bz; z++) {
            if (x == ax || x == bx || y == ay || y == by || z == az || z == bz) set(x, y, z, spec);
        }
    }

    /** Four vertical walls only. */
    public void walls(int x1, int y1, int z1, int x2, int y2, int z2, String spec) {
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2), ay = Math.min(y1, y2), by = Math.max(y1, y2), az = Math.min(z1, z2), bz = Math.max(z1, z2);
        for (int y = ay; y <= by; y++) for (int x = ax; x <= bx; x++) for (int z = az; z <= bz; z++) {
            if (x == ax || x == bx || z == az || z == bz) set(x, y, z, spec);
        }
    }

    /** The 12 edges of a box (timber frames, glass curtain mullions). */
    public void frame(int x1, int y1, int z1, int x2, int y2, int z2, String spec) {
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2), ay = Math.min(y1, y2), by = Math.max(y1, y2), az = Math.min(z1, z2), bz = Math.max(z1, z2);
        for (int y = ay; y <= by; y++) for (int x = ax; x <= bx; x++) for (int z = az; z <= bz; z++) {
            int edges = (x == ax || x == bx ? 1 : 0) + (y == ay || y == by ? 1 : 0) + (z == az || z == bz ? 1 : 0);
            if (edges >= 2) {
                if (catalog().kind(idOf(spec)) == BlockCatalog.Kind.LOG) {
                    char a = (y != ay && y != by) ? 'y' : (x != ax && x != bx) ? 'x' : 'z';
                    if (ay == by) a = (x != ax && x != bx) ? 'x' : 'z';
                    set(x, y, z, spec, axis(a));
                } else set(x, y, z, spec);
            }
        }
    }

    private String idOf(String spec) {
        try { return materials.resolve(spec, 0, 0, 0).id(); } catch (IllegalArgumentException e) { return "stone"; }
    }

    // ---------------------------------------------------------------- lines

    /** 3D Bresenham-style line with optional square thickness. Logs are oriented along the dominant axis. */
    public void line(int x1, int y1, int z1, int x2, int y2, int z2, String spec, int thickness) {
        int dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        int n = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        char ax = Math.abs(dy) >= Math.abs(dx) && Math.abs(dy) >= Math.abs(dz) ? 'y' : Math.abs(dx) >= Math.abs(dz) ? 'x' : 'z';
        boolean log = catalog().kind(idOf(spec)) == BlockCatalog.Kind.LOG;
        int t = Math.max(1, thickness);
        int lo = -(t - 1) / 2, hi = t / 2;
        int lx = x1, ly = y1, lz = z1;
        for (int i = 0; i <= n; i++) {
            double f = n == 0 ? 0 : (double) i / n;
            int x = (int) Math.round(x1 + dx * f), y = (int) Math.round(y1 + dy * f), z = (int) Math.round(z1 + dz * f);
            // insert face-connecting steps so diagonal lines never touch only at edges/corners (they would float in-game)
            if (t == 1 && i > 0) {
                if (x != lx && (y != ly || z != lz)) lineCell(x, ly, lz, spec, log, ax);
                if (y != ly && z != lz) lineCell(x, ly, z, spec, log, ax);
            }
            lx = x; ly = y; lz = z;
            for (int a = lo; a <= hi; a++) for (int b = lo; b <= hi; b++) {
                int px = x, py = y, pz = z;
                switch (ax) {
                    case 'y' -> { px += a; pz += b; }
                    case 'x' -> { py += a; pz += b; }
                    default -> { px += a; py += b; }
                }
                if (log) set(px, py, pz, spec, axis(ax)); else set(px, py, pz, spec);
            }
        }
    }

    private void lineCell(int x, int y, int z, String spec, boolean log, char ax) {
        if (log) set(x, y, z, spec, axis(ax)); else set(x, y, z, spec);
    }

    // ---------------------------------------------------------------- round shapes

    private static boolean inDisk(double dx, double dz, double r) {
        return dx * dx + dz * dz <= (r + 0.35) * (r + 0.35);
    }

    /**
     * Vertical (axis y) or horizontal (axis x|z) cylinder. Block x's center is x itself, so an integer center gives an
     * odd diameter and a .5 center (e.g. 10.5) an even diameter. For horizontal cylinders cy is the axis height and the
     * cylinder runs h blocks from cx (axis x) or cz (axis z).
     */
    public void cylinder(double cx, int cy, double cz, double r, int h, String spec, boolean hollow, char axis) {
        int R = (int) Math.ceil(r + 1);
        for (int i = 0; i < h; i++) {
            for (int a = -R; a <= R; a++) for (int b = -R; b <= R; b++) {
                int x, y, z;
                double du, dv;
                if (axis == 'x') {
                    x = (int) Math.floor(cx) + i; y = cy + a; z = (int) Math.floor(cz) + b;
                    du = a; dv = z - cz;
                } else if (axis == 'z') {
                    z = (int) Math.floor(cz) + i; y = cy + a; x = (int) Math.floor(cx) + b;
                    du = a; dv = x - cx;
                } else {
                    x = (int) Math.floor(cx) + a; z = (int) Math.floor(cz) + b; y = cy + i;
                    du = x - cx; dv = z - cz;
                }
                if (!inDisk(du, dv, r)) continue;
                // 8-neighbour boundary test => walls are 4-connected (no see-through diagonal gaps)
                if (hollow && inDisk(du + 1, dv, r) && inDisk(du - 1, dv, r) && inDisk(du, dv + 1, r) && inDisk(du, dv - 1, r)
                        && inDisk(du + 1, dv + 1, r) && inDisk(du - 1, dv - 1, r) && inDisk(du + 1, dv - 1, r) && inDisk(du - 1, dv + 1, r)) continue;
                set(x, y, z, spec);
            }
        }
    }

    /** Ring of blocks around a vertical axis at one height (string courses, tower trims). */
    public void ring(double cx, int y, double cz, double r, String spec) {
        cylinder(cx, y, cz, r, 1, spec, true, 'y');
    }

    /** Ellipsoid / sphere; half = "top", "bottom" or null. Same center convention as {@link #cylinder}. */
    public void ellipsoid(double cx, int cy, double cz, double rx, double ry, double rz, String spec, boolean hollow, String half) {
        int RX = (int) Math.ceil(rx + 1), RY = (int) Math.ceil(ry + 1), RZ = (int) Math.ceil(rz + 1);
        for (int a = -RX; a <= RX; a++) for (int b = -RY; b <= RY; b++) for (int c = -RZ; c <= RZ; c++) {
            if ("top".equals(half) && b < 0) continue;
            if ("bottom".equals(half) && b > 0) continue;
            int x = (int) Math.floor(cx) + a, y = cy + b, z = (int) Math.floor(cz) + c;
            double dx = x - cx, dy = b, dz = z - cz;
            if (!inEll(dx, dy, dz, rx, ry, rz)) continue;
            if (hollow) {
                boolean interior = true;
                for (int i = -1; i <= 1 && interior; i++) for (int j = -1; j <= 1 && interior; j++) for (int k = -1; k <= 1; k++) {
                    if ((i != 0 || j != 0 || k != 0) && !inEll(dx + i, dy + j, dz + k, rx, ry, rz)) { interior = false; break; }
                }
                if (interior) continue;
            }
            set(x, y, z, spec);
        }
    }

    private static boolean inEll(double dx, double dy, double dz, double rx, double ry, double rz) {
        double ex = rx + 0.35, ey = ry + 0.35, ez = rz + 0.35;
        return dx * dx / (ex * ex) + dy * dy / (ey * ey) + dz * dz / (ez * ez) <= 1.0;
    }

    /** Cone narrowing from radius r at cy to a point at cy+h-1. */
    public void cone(double cx, int cy, double cz, double r, int h, String spec, boolean hollow) {
        for (int i = 0; i < h; i++) {
            double rr = r * (1.0 - (double) i / Math.max(1, h));
            if (rr < 0.25) rr = 0;
            cylinder(cx, cy + i, cz, rr, 1, spec, hollow, 'y');
        }
    }

    /** Square stepped pyramid over footprint [x1..x2]x[z1..z2] starting at y. */
    public void pyramid(int x1, int y, int z1, int x2, int z2, String spec, boolean hollow) {
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2), az = Math.min(z1, z2), bz = Math.max(z1, z2);
        int layer = 0;
        while (ax <= bx && az <= bz) {
            for (int x = ax; x <= bx; x++) for (int z = az; z <= bz; z++) {
                if (hollow && x != ax && x != bx && z != az && z != bz) continue;
                set(x, y + layer, z, spec);
            }
            ax++; bx--; az++; bz--; layer++;
        }
    }

    // ---------------------------------------------------------------- arches & openings

    /**
     * Arch spanning [a1..a2] along an axis ('x' or 'z'), standing on y, at plane position p (and thickness depth along the other axis).
     * style: round | pointed | segmental | flat. carve=true cuts the opening below the arch curve (doorways in walls).
     * The arch ring itself is drawn with spec (thickness = ring thickness in blocks).
     */
    public void arch(char axis, int a1, int a2, int y, int springHeight, int p, int depth, String style, String spec, int thickness, boolean carve) {
        int lo = Math.min(a1, a2), hi = Math.max(a1, a2);
        double span = hi - lo + 1;
        double mid = (lo + hi) / 2.0;
        double half = span / 2.0;
        int maxRise = (int) Math.ceil(switch (style) {
            case "pointed" -> half * 1.6;
            case "segmental" -> half * 0.5;
            case "flat" -> 0;
            default -> half;
        });
        int n = hi - lo + 1;
        int[] inner = new int[n];
        boolean shoulders = false;
        for (int a = lo; a <= hi; a++) {
            double off = Math.abs(a + 0.5 - (mid + 0.5));
            inner[a - lo] = springHeight + (int) Math.floor(curve(style, off, half));
            if (a > lo && inner[a - lo] != inner[a - lo - 1]) shoulders = true;
        }
        // Upside-down stairs soften the curve: on each shoulder, or in the top corners of small arches that would
        // otherwise read as a flat lintel.
        String stair = spec != null && !spec.contains(":") && !style.equals("flat") && n >= 3 ? spec + ":stairs" : null;
        for (int d = 0; d < Math.max(1, depth); d++) {
            int plane = p + d;
            for (int a = lo; a <= hi; a++) {
                double off = Math.abs(a + 0.5 - (mid + 0.5));
                int in = inner[a - lo];
                int outer = springHeight + (int) Math.floor(curve(style, Math.max(0, off - thickness), half)) + thickness;
                if (carve) for (int yy = 0; yy < in; yy++) placeAt(axis, a, y + yy, plane, null);
                if (spec != null) for (int yy = in; yy < Math.min(outer, springHeight + maxRise + thickness); yy++) placeAt(axis, a, y + yy, plane, spec);
                if (stair == null || a == mid) continue;
                Dir outward = axis == 'x' ? (a < mid ? Dir.WEST : Dir.EAST) : (a < mid ? Dir.NORTH : Dir.SOUTH);
                int towardIdx = a - lo + (a < mid ? 1 : -1);
                if (shoulders) {
                    if (towardIdx >= 0 && towardIdx < n && inner[towardIdx] > in) placeStair(axis, a, y + in, plane, stair, outward);
                } else if ((a == lo || a == hi) && in >= 2) {
                    placeStair(axis, a, y + in - 1, plane, stair, outward);
                }
            }
        }
    }

    private void placeStair(char axis, int a, int y, int plane, String spec, Dir facing) {
        int x = axis == 'x' ? a : plane, z = axis == 'x' ? plane : a;
        set(x, y, z, spec, stairs(facing, true));
    }

    private static double curve(String style, double off, double half) {
        if (off >= half) return 0;
        return switch (style) {
            case "pointed" -> {
                // two circular arcs of radius = span*0.8 centered at the opposite springs
                double R = half * 1.6;
                double c = half - R; // center offset from midline (negative => other side)
                double dx = off - c;
                yield Math.sqrt(Math.max(0, R * R - dx * dx));
            }
            case "segmental" -> {
                double R = (half * half + (half * 0.5) * (half * 0.5)) / (2 * half * 0.5);
                yield Math.sqrt(Math.max(0, R * R - off * off)) - (R - half * 0.5);
            }
            case "flat" -> 0;
            default -> Math.sqrt(Math.max(0, half * half - off * off));
        };
    }

    private void placeAt(char axis, int a, int y, int plane, String spec) {
        int x = axis == 'x' ? a : plane, z = axis == 'x' ? plane : a;
        if (spec == null) air(x, y, z); else set(x, y, z, spec);
    }

    // ---------------------------------------------------------------- texture & replace

    public void replace(int x1, int y1, int z1, int x2, int y2, int z2, String findId, String spec) {
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2), ay = Math.min(y1, y2), by = Math.max(y1, y2), az = Math.min(z1, z2), bz = Math.max(z1, z2);
        String find = findId == null ? null : findId.replace("minecraft:", "");
        for (int y = ay; y <= by; y++) for (int x = ax; x <= bx; x++) for (int z = az; z <= bz; z++) {
            BlockState cur = model.get(x, y, z);
            if (cur == null || cur.isAir()) continue;
            if (find != null && !cur.id().equals(find)) continue;
            BlockState repl = materials.resolve(spec, x, y, z);
            // keep shape-defining properties when swapping between members of the same kind
            if (catalog().kind(repl.id()) == catalog().kind(cur.id())) {
                for (Map.Entry<String, String> e : cur.props().entrySet()) if (repl.prop(e.getKey()) == null) repl = repl.with(e.getKey(), e.getValue());
            }
            model.set(x, y, z, repl);
        }
    }

    // ---------------------------------------------------------------- copy / mirror / rotate regions

    public void copy(int x1, int y1, int z1, int x2, int y2, int z2, int dx, int dy, int dz, int times, int rotateQuarter) {
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2), ay = Math.min(y1, y2), by = Math.max(y1, y2), az = Math.min(z1, z2), bz = Math.max(z1, z2);
        java.util.List<int[]> src = new java.util.ArrayList<>();
        java.util.List<BlockState> st = new java.util.ArrayList<>();
        for (int y = ay; y <= by; y++) for (int x = ax; x <= bx; x++) for (int z = az; z <= bz; z++) {
            BlockState s = model.get(x, y, z);
            if (s != null) { src.add(new int[]{x, y, z}); st.add(s); }
        }
        double cx = (ax + bx) / 2.0, cz = (az + bz) / 2.0;
        for (int t = 1; t <= Math.max(1, times); t++) {
            int q = ((rotateQuarter * t) % 4 + 4) % 4;
            for (int i = 0; i < src.size(); i++) {
                int[] p = src.get(i);
                double rx = p[0] - cx, rz = p[2] - cz;
                double nx = rx, nz = rz;
                for (int k = 0; k < q; k++) { double tmp = nx; nx = -nz; nz = tmp; }
                int x = (int) Math.round(cx + nx + dx * t), z = (int) Math.round(cz + nz + dz * t);
                model.set(x, p[1] + dy * t, z, StateTransforms.rotate(st.get(i), q));
            }
        }
    }

    /**
     * Mirrors existing content across a plane: every cell on the low side (coordinate < c) is copied to 2c - coordinate.
     * c = 10 mirrors around block 10; c = 10.5 mirrors between blocks 10 and 11. region optionally limits the source cells.
     */
    public void mirror(char axis, double c, int[] region) {
        long[] keys = model.keys();
        for (long k : keys) {
            int x = VoxelModel.kx(k), y = VoxelModel.ky(k), z = VoxelModel.kz(k);
            if (region != null && (x < region[0] || y < region[1] || z < region[2] || x > region[3] || y > region[4] || z > region[5])) continue;
            BlockState s = model.getByKey(k);
            if (s == null) continue;
            if (axis == 'x') {
                if (x >= c) continue;
                model.set((int) Math.round(2 * c - x), y, z, StateTransforms.mirror(s, 'x'));
            } else {
                if (z >= c) continue;
                model.set(x, y, (int) Math.round(2 * c - z), StateTransforms.mirror(s, 'z'));
            }
        }
    }
}
