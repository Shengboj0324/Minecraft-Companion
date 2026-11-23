package io.github.shengboj0324.architect.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import static io.github.shengboj0324.architect.core.Ops.bool;
import static io.github.shengboj0324.architect.core.Ops.dir;
import static io.github.shengboj0324.architect.core.Ops.dvec;
import static io.github.shengboj0324.architect.core.Ops.integer;
import static io.github.shengboj0324.architect.core.Ops.num;
import static io.github.shengboj0324.architect.core.Ops.str;
import static io.github.shengboj0324.architect.core.Ops.vec;

/**
 * Large-scale geometry for mega builds and real-world landmarks: implicit shapes from expressions, solids of
 * revolution (domes, spires, towers, cooling-tower curves), extruded polygons (any footprint), lofts between
 * floor sections (tapering and twisting skyscrapers), trusses and polylines (bridges, lattice towers, cables)
 * and curtain-wall facades. All volumetric ops share one voxelizer that can produce a solid or a shell of a
 * given thickness, and one painter that can switch material by expression (bands, gradients, patterns).
 */
final class Shapes {

    /** Hard cap on cells evaluated by one op, so a bad request cannot stall the design thread. */
    static final long MAX_EVAL = 48_000_000L;

    interface Inside { boolean test(int x, int y, int z); }

    private final Canvas c;

    Shapes(Canvas c) { this.c = c; }

    // ------------------------------------------------------------------ ops

    /** {"op":"shape","from":[..],"to":[..],"inside":"expr", "block":..., "hollow":false, "thickness":1, "materials":[...]} */
    void shape(JsonObject o) {
        int[] a = vec(o, "from"), b = vec(o, "to");
        int[] lo = {Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2])};
        int[] hi = {Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])};
        String src = Ops.req(o, "inside");
        Expr.Node f = Expr.compile(src);
        Painter p = painter(o, lo, hi, null, "@wall");
        double[] vars = new double[Expr.VARS.length];
        Inside in = (x, y, z) -> {
            if (x < lo[0] || x > hi[0] || y < lo[1] || y > hi[1] || z < lo[2] || z > hi[2]) return false;
            p.fill(vars, x, y, z);
            return f.eval(vars) != 0;
        };
        voxelize(lo, hi, in, bool(o, "hollow", false), integer(o, "thickness", 1), bool(o, "caps", true), p);
    }

    /** {"op":"revolve","center":[cx,y,cz],"profile":[[h,r],...],"sides":0,"rotate":0,"hollow":true,"thickness":1} */
    void revolve(JsonObject o) {
        double[] ctr = dvec(o, "center");
        double[][] prof = profile(o.get("profile"));
        int sides = integer(o, "sides", 0);
        double rot = Math.toRadians(num(o, "rotate", 0));
        double rmax = 0;
        for (double[] q : prof) rmax = Math.max(rmax, q[1]);
        int y0 = (int) Math.round(ctr[1]);
        int[] lo = {(int) Math.floor(ctr[0] - rmax - 1), y0 + (int) Math.floor(prof[0][0]), (int) Math.floor(ctr[2] - rmax - 1)};
        int[] hi = {(int) Math.ceil(ctr[0] + rmax + 1), y0 + (int) Math.ceil(prof[prof.length - 1][0]), (int) Math.ceil(ctr[2] + rmax + 1)};
        Inside in = (x, y, z) -> {
            double h = y - y0;
            if (h < prof[0][0] || h > prof[prof.length - 1][0]) return false;
            double r = interp(prof, h);
            if (r <= 0) return false;
            double dx = x - ctr[0], dz = z - ctr[2];
            double d = Math.hypot(dx, dz);
            if (sides >= 3) {
                double seg = 2 * Math.PI / sides;
                double t = Math.atan2(dz, dx) - rot;
                t = t - seg * Math.floor(t / seg) - seg / 2;
                r = r * Math.cos(Math.PI / sides) / Math.cos(t);
            }
            return d <= r + 0.35;
        };
        voxelize(lo, hi, in, bool(o, "hollow", false), integer(o, "thickness", 1), bool(o, "caps", true), painter(o, lo, hi, ctr, "@wall"));
    }

    /** {"op":"polygon","points":[[x,z],...],"y":0,"height":10,"hollow":false,"thickness":1,"caps":true} */
    void polygon(JsonObject o) {
        double[][] pts = points2(o.get("points"));
        if (pts.length < 3) throw new IllegalArgumentException("polygon needs at least 3 points [[x,z],...]");
        int y0 = integer(o, "y", 0), h = integer(o, "height", 1);
        if (h < 1) throw new IllegalArgumentException("height must be >= 1");
        double minX = 1e9, maxX = -1e9, minZ = 1e9, maxZ = -1e9;
        for (double[] p : pts) { minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]); minZ = Math.min(minZ, p[1]); maxZ = Math.max(maxZ, p[1]); }
        int[] lo = {(int) Math.floor(minX), y0, (int) Math.floor(minZ)};
        int[] hi = {(int) Math.ceil(maxX), y0 + h - 1, (int) Math.ceil(maxZ)};
        double[] ctr = {(minX + maxX) / 2, y0, (minZ + maxZ) / 2};
        Inside in = (x, y, z) -> y >= lo[1] && y <= hi[1] && inPolygon(pts, x, z);
        voxelize(lo, hi, in, bool(o, "hollow", false), integer(o, "thickness", 1), bool(o, "caps", true), painter(o, lo, hi, ctr, "@wall"));
    }

    /**
     * {"op":"loft","center":[cx,y,cz],"sections":[{"h":0,"w":40,"d":30,"corner":0,"rot":0,"dx":0,"dz":0,"shape":"rect"},...],
     *  "hollow":true,"thickness":1}
     */
    void loft(JsonObject o) {
        double[] ctr = dvec(o, "center");
        JsonElement se = o.get("sections");
        if (se == null || !se.isJsonArray() || se.getAsJsonArray().size() < 1) throw new IllegalArgumentException("loft needs \"sections\": [{h,w,d,...},...]");
        List<double[]> secs = new ArrayList<>();
        List<Boolean> ellipse = new ArrayList<>();
        for (JsonElement e : se.getAsJsonArray()) {
            JsonObject s = e.getAsJsonObject();
            double w = num(s, "w", num(s, "width", 10)), d = num(s, "d", num(s, "depth", w));
            secs.add(new double[]{num(s, "h", 0), w, d, num(s, "corner", 0), Math.toRadians(num(s, "rot", 0)), num(s, "dx", 0), num(s, "dz", 0)});
            ellipse.add("ellipse".equals(str(s, "shape", "rect")) || "round".equals(str(s, "shape", "rect")));
        }
        for (int i = 1; i < secs.size(); i++) if (secs.get(i)[0] < secs.get(i - 1)[0]) throw new IllegalArgumentException("loft sections must be in increasing h order");
        if (secs.size() == 1) { double[] s = secs.get(0).clone(); s[0] += 1; secs.add(s); ellipse.add(ellipse.get(0)); }
        double reach = 0;
        for (double[] s : secs) reach = Math.max(reach, Math.hypot(s[1], s[2]) / 2 + Math.hypot(s[5], s[6]) + 1);
        int y0 = (int) Math.round(ctr[1]);
        double hTop = secs.get(secs.size() - 1)[0];
        int[] lo = {(int) Math.floor(ctr[0] - reach), y0 + (int) Math.floor(secs.get(0)[0]), (int) Math.floor(ctr[2] - reach)};
        int[] hi = {(int) Math.ceil(ctr[0] + reach), y0 + (int) Math.ceil(hTop) - (hTop == Math.floor(hTop) && secs.size() > 1 ? 0 : 0), (int) Math.ceil(ctr[2] + reach)};
        double[] cur = new double[7];
        Inside in = (x, y, z) -> {
            double h = y - y0;
            if (h < secs.get(0)[0] || h > hTop) return false;
            int i = 0;
            while (i < secs.size() - 2 && h > secs.get(i + 1)[0]) i++;
            double[] s0 = secs.get(i), s1 = secs.get(i + 1);
            double t = s1[0] == s0[0] ? 0 : (h - s0[0]) / (s1[0] - s0[0]);
            for (int k = 1; k < 7; k++) cur[k] = s0[k] + (s1[k] - s0[k]) * t;
            double px = x - ctr[0] - cur[5], pz = z - ctr[2] - cur[6];
            double cs = Math.cos(cur[4]), sn = Math.sin(cur[4]);
            double rx = px * cs + pz * sn, rz = -px * sn + pz * cs;
            double hw = (cur[1] - 1) / 2 + 0.01, hd = (cur[2] - 1) / 2 + 0.01;
            if (hw < 0 || hd < 0) return false;
            if (ellipse.get(i)) {
                double ex = rx / (hw + 0.35), ez = rz / (hd + 0.35);
                return ex * ex + ez * ez <= 1;
            }
            double cr = Math.min(cur[3], Math.min(hw, hd));
            double qx = Math.abs(rx) - (hw - cr), qz = Math.abs(rz) - (hd - cr);
            double outside = Math.hypot(Math.max(qx, 0), Math.max(qz, 0)) + Math.min(Math.max(qx, qz), 0);
            return outside - cr <= 0.05;
        };
        voxelize(lo, hi, in, bool(o, "hollow", false), integer(o, "thickness", 1), bool(o, "caps", true), painter(o, lo, hi, ctr, "@wall"));
    }

    /** {"op":"lines","points":[[x,y,z],...],"closed":false,"thickness":1,"block":...} */
    void lines(JsonObject o) {
        double[][] p = points3(o.get("points"));
        if (p.length < 2) throw new IllegalArgumentException("lines needs at least 2 points");
        String spec = str(o, "block", "@trim");
        int t = integer(o, "thickness", 1);
        boolean closed = bool(o, "closed", false);
        for (int i = 0; i + 1 < p.length; i++) seg(p[i], p[i + 1], spec, t);
        if (closed && p.length > 2) seg(p[p.length - 1], p[0], spec, t);
    }

    /** {"op":"truss","a":[[x,y,z],[x,y,z]],"b":[[x,y,z],[x,y,z]],"segments":8,"pattern":"x|n|warren|ladder","block":...} */
    void truss(JsonObject o) {
        double[][] ra = points3(o.get("a")), rb = points3(o.get("b"));
        if (ra.length != 2 || rb.length != 2) throw new IllegalArgumentException("truss needs rails \"a\" and \"b\", each [[x,y,z],[x,y,z]]");
        int n = Math.max(1, integer(o, "segments", 6));
        String spec = str(o, "block", "@trim");
        String chord = str(o, "chord", spec);
        int t = integer(o, "thickness", 1);
        String pattern = str(o, "pattern", "x");
        seg(ra[0], ra[1], chord, t);
        seg(rb[0], rb[1], chord, t);
        for (int i = 0; i <= n; i++) {
            double[] ai = lerp(ra[0], ra[1], (double) i / n), bi = lerp(rb[0], rb[1], (double) i / n);
            if (!pattern.equals("warren")) seg(ai, bi, spec, t);
            if (i == n) break;
            double[] an = lerp(ra[0], ra[1], (double) (i + 1) / n), bn = lerp(rb[0], rb[1], (double) (i + 1) / n);
            switch (pattern) {
                case "x" -> { seg(ai, bn, spec, t); seg(bi, an, spec, t); }
                case "n", "pratt" -> seg(ai, bn, spec, t);
                case "warren" -> { if (i % 2 == 0) seg(ai, bn, spec, t); else seg(bi, an, spec, t); }
                case "ladder" -> { }
                default -> throw new IllegalArgumentException("pattern must be x, n, warren or ladder");
            }
        }
    }

    /**
     * Curtain-wall facade on a flat plane: {"op":"facade_grid","from":[..],"to":[..],"facing":"south","bay":4,"floor":4,
     * "mullion":"@trim","spandrel":"@wall","glass":"@window","mullion_width":1,"spandrel_height":1,"depth":1,"fins":false}
     */
    void facadeGrid(JsonObject o) {
        int[] a = vec(o, "from"), b = vec(o, "to");
        boolean planeX = a[0] == b[0];
        if (!planeX && a[2] != b[2]) throw new IllegalArgumentException("facade_grid: from/to must define a flat facade (same x or same z)");
        Dir facing = dir(o, "facing", planeX ? Dir.EAST : Dir.SOUTH);
        if (facing.isX() != planeX) throw new IllegalArgumentException("facade_grid: facing must point out of the plane (" + (planeX ? "east/west" : "north/south") + ")");
        int bay = Math.max(2, integer(o, "bay", 4)), floor = Math.max(2, integer(o, "floor", 4));
        int mw = Math.max(0, integer(o, "mullion_width", 1)), sh = Math.max(0, integer(o, "spandrel_height", 1));
        int depth = Math.max(0, integer(o, "depth", 1));
        boolean fins = bool(o, "fins", false);
        String mull = str(o, "mullion", "@trim"), span = str(o, "spandrel", "@wall"), glass = str(o, "glass", "@window");
        int lo = planeX ? Math.min(a[2], b[2]) : Math.min(a[0], b[0]), hi = planeX ? Math.max(a[2], b[2]) : Math.max(a[0], b[0]);
        int y0 = Math.min(a[1], b[1]), y1 = Math.max(a[1], b[1]);
        int plane = planeX ? a[0] : a[2];
        Dir in = facing.opposite();
        int inStep = planeX ? in.dx : in.dz, outStep = -inStep;
        long cells = (long) (hi - lo + 1) * (y1 - y0 + 1);
        if (cells > MAX_EVAL / 4) throw new IllegalArgumentException("facade_grid area too large (" + cells + " cells)");
        for (int s = lo; s <= hi; s++) {
            boolean isMull = Math.floorMod(s - lo, bay) < mw || s == hi;
            for (int y = y0; y <= y1; y++) {
                boolean isSpan = Math.floorMod(y - y0, floor) < sh || y == y1;
                if (isMull) {
                    set(planeX, plane, s, y, mull);
                    if (fins) set(planeX, plane + outStep, s, y, mull);
                } else if (isSpan) {
                    set(planeX, plane, s, y, span);
                } else {
                    for (int d = 0; d < depth; d++) set(planeX, plane + inStep * d, s, y, null);
                    set(planeX, plane + inStep * depth, s, y, glass);
                }
            }
        }
    }

    private void set(boolean planeX, int plane, int along, int y, String spec) {
        int x = planeX ? plane : along, z = planeX ? along : plane;
        if (spec == null) c.air(x, y, z); else c.set(x, y, z, spec);
    }

    // ------------------------------------------------------------------ voxelizer

    /**
     * Fills every cell of [lo, hi] for which {@code f} holds, or only its shell when {@code hollow}: cells within
     * {@code thickness} face-steps of an outside cell. With {@code caps} false the shell stays open at the bottom and top.
     */
    void voxelize(int[] lo, int[] hi, Inside f, boolean hollow, int thickness, boolean caps, Painter p) {
        int t = hollow ? Math.max(1, Math.min(8, thickness)) : 0;
        int nx = hi[0] - lo[0] + 1 + 2 * t, ny = hi[1] - lo[1] + 1 + 2 * t, nz = hi[2] - lo[2] + 1 + 2 * t;
        if (nx <= 0 || ny <= 0 || nz <= 0) return;
        long vol = (long) nx * ny * nz;
        if (vol > MAX_EVAL) throw new IllegalArgumentException("region too large to evaluate (" + vol + " cells, max " + MAX_EVAL + "); split it into parts");
        int bx = lo[0] - t, by = lo[1] - t, bz = lo[2] - t;
        BitSet inside = new BitSet((int) vol);
        for (int y = 0; y < ny; y++) for (int z = 0; z < nz; z++) for (int x = 0; x < nx; x++) {
            if (f.test(bx + x, by + y, bz + z)) inside.set((y * nz + z) * nx + x);
        }
        double[] vars = new double[Expr.VARS.length];
        for (int y = t; y < ny - t; y++) for (int z = t; z < nz - t; z++) for (int x = t; x < nx - t; x++) {
            int i = (y * nz + z) * nx + x;
            if (!inside.get(i)) continue;
            if (hollow && !shell(inside, nx, ny, nz, x, y, z, t, caps, lo[1] - by, hi[1] - by)) continue;
            int wx = bx + x, wy = by + y, wz = bz + z;
            String spec = p.spec(vars, wx, wy, wz);
            if (spec == null || spec.equals("none")) continue; // "none" leaves the cell untouched (lattices, perforation)
            if (spec.equals("air")) c.air(wx, wy, wz); else c.set(wx, wy, wz, spec);
        }
    }

    private static boolean shell(BitSet in, int nx, int ny, int nz, int x, int y, int z, int t, boolean caps, int yLo, int yHi) {
        // all 26 neighbours at distance 1, so shells on diagonal and curved faces stay watertight and face-connected
        for (int dy = -1; dy <= 1; dy++) {
            int yy = y + dy;
            if (!caps && (yy > yHi || yy < yLo)) continue;
            for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
                if ((dx | dy | dz) == 0) continue;
                if (!in.get((yy * nz + z + dz) * nx + x + dx)) return true;
            }
        }
        for (int d = 2; d <= t; d++) {
            if (!in.get((y * nz + z) * nx + x + d) || !in.get((y * nz + z) * nx + x - d)) return true;
            if (!in.get((y * nz + z + d) * nx + x) || !in.get((y * nz + z - d) * nx + x)) return true;
            int up = y + d, dn = y - d;
            if (!(up > yHi && !caps) && !in.get((up * nz + z) * nx + x)) return true;
            if (!(dn < yLo && !caps) && !in.get((dn * nz + z) * nx + x)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ painter

    /** Chooses the block for a cell: the first "materials" rule whose "when" expression holds, else "block". */
    static final class Painter {
        final int[] lo, hi;
        final double cx, cy, cz;
        final String block;
        final List<Expr.Node> when = new ArrayList<>();
        final List<String> blocks = new ArrayList<>();

        Painter(int[] lo, int[] hi, double[] ctr, String block) {
            this.lo = lo; this.hi = hi; this.block = block;
            this.cx = ctr != null ? ctr[0] : (lo[0] + hi[0]) / 2.0;
            this.cy = ctr != null ? ctr[1] : lo[1];
            this.cz = ctr != null ? ctr[2] : (lo[2] + hi[2]) / 2.0;
        }

        void fill(double[] v, int x, int y, int z) {
            double sx = hi[0] - lo[0], sy = hi[1] - lo[1], sz = hi[2] - lo[2];
            v[0] = x; v[1] = y; v[2] = z;
            v[3] = sx == 0 ? 0 : (x - lo[0]) / sx; v[4] = sy == 0 ? 0 : (y - lo[1]) / sy; v[5] = sz == 0 ? 0 : (z - lo[2]) / sz;
            v[6] = v[3] * 2 - 1; v[7] = v[4] * 2 - 1; v[8] = v[5] * 2 - 1;
            v[9] = cx; v[10] = cy; v[11] = cz;
            v[12] = sx + 1; v[13] = sy + 1; v[14] = sz + 1;
            double dx = x - cx, dz = z - cz;
            v[15] = Math.hypot(dx, dz);
            double ang = Math.toDegrees(Math.atan2(dx, -dz));
            v[16] = ang < 0 ? ang + 360 : ang;
            v[17] = Math.PI;
        }

        String spec(double[] v, int x, int y, int z) {
            if (when.isEmpty()) return block;
            fill(v, x, y, z);
            for (int i = 0; i < when.size(); i++) if (when.get(i).eval(v) != 0) return blocks.get(i);
            return block;
        }
    }

    private Painter painter(JsonObject o, int[] lo, int[] hi, double[] ctr, String def) {
        Painter p = new Painter(lo, hi, ctr, str(o, "block", def));
        JsonElement m = o.get("materials");
        if (m != null && m.isJsonArray()) {
            for (JsonElement e : m.getAsJsonArray()) {
                JsonObject r = e.getAsJsonObject();
                p.when.add(Expr.compile(str(r, "when", "1")));
                p.blocks.add(Ops.req(r, "block"));
            }
            if (p.when.size() > 16) throw new IllegalArgumentException("at most 16 materials rules");
        }
        return p;
    }

    // ------------------------------------------------------------------ helpers

    private void seg(double[] a, double[] b, String spec, int t) {
        c.line((int) Math.round(a[0]), (int) Math.round(a[1]), (int) Math.round(a[2]), (int) Math.round(b[0]), (int) Math.round(b[1]), (int) Math.round(b[2]), spec, t);
    }

    private static double[] lerp(double[] a, double[] b, double t) {
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    static double interp(double[][] prof, double h) {
        for (int i = 0; i + 1 < prof.length; i++) {
            if (h <= prof[i + 1][0]) {
                double span = prof[i + 1][0] - prof[i][0];
                double t = span == 0 ? 1 : (h - prof[i][0]) / span;
                return prof[i][1] + (prof[i + 1][1] - prof[i][1]) * t;
            }
        }
        return prof[prof.length - 1][1];
    }

    static boolean inPolygon(double[][] pts, double x, double z) {
        boolean in = false;
        for (int i = 0, j = pts.length - 1; i < pts.length; j = i++) {
            double xi = pts[i][0], zi = pts[i][1], xj = pts[j][0], zj = pts[j][1];
            if (segDist(x, z, xi, zi, xj, zj) <= 0.5) return true;
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) in = !in;
        }
        return in;
    }

    private static double segDist(double px, double pz, double ax, double az, double bx, double bz) {
        double vx = bx - ax, vz = bz - az, l2 = vx * vx + vz * vz;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * vx + (pz - az) * vz) / l2));
        return Math.hypot(px - ax - t * vx, pz - az - t * vz);
    }

    private static double[][] profile(JsonElement e) {
        double[][] p = points2(e);
        if (p.length < 2) throw new IllegalArgumentException("profile needs at least 2 points [[height, radius], ...]");
        for (int i = 1; i < p.length; i++) if (p[i][0] < p[i - 1][0]) throw new IllegalArgumentException("profile heights must increase");
        return p;
    }

    private static double[][] points2(JsonElement e) {
        if (e == null || !e.isJsonArray()) throw new IllegalArgumentException("expected a list of [a,b] pairs");
        JsonArray a = e.getAsJsonArray();
        if (a.size() > 4096) throw new IllegalArgumentException("too many points (max 4096)");
        double[][] out = new double[a.size()][];
        for (int i = 0; i < a.size(); i++) {
            JsonArray q = a.get(i).getAsJsonArray();
            out[i] = new double[]{q.get(0).getAsDouble(), q.get(1).getAsDouble()};
        }
        return out;
    }

    private static double[][] points3(JsonElement e) {
        if (e == null || !e.isJsonArray()) throw new IllegalArgumentException("expected a list of [x,y,z] points");
        JsonArray a = e.getAsJsonArray();
        if (a.size() > 4096) throw new IllegalArgumentException("too many points (max 4096)");
        double[][] out = new double[a.size()][];
        for (int i = 0; i < a.size(); i++) {
            JsonArray q = a.get(i).getAsJsonArray();
            out[i] = new double[]{q.get(0).getAsDouble(), q.get(1).getAsDouble(), q.get(2).getAsDouble()};
        }
        return out;
    }
}
