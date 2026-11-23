package io.github.shengboj0324.architect.core;

import java.util.Map;

/**
 * Procedural architectural modules. Every module is drawn with style roles (@wall, @trim, ...) so the same module
 * renders as medieval, gothic, japanese, etc. The AI composes these; templates are curated compositions of them.
 * Unless stated otherwise the front of a module faces north (-Z) and y is ground level (first block above terrain).
 */
public final class Modules {

    private Modules() {}

    // ======================================================================== facade helpers

    /** Position on a rectangular footprint's side. a = coordinate along the side, k = offset outward from the wall plane. */
    static int[] side(Dir s, int x1, int z1, int x2, int z2, int a, int k) {
        return switch (s) {
            case NORTH -> new int[]{a, z1 - k};
            case SOUTH -> new int[]{a, z2 + k};
            case WEST -> new int[]{x1 - k, a};
            case EAST -> new int[]{x2 + k, a};
        };
    }

    static int sideLo(Dir s, int x1, int z1) { return s.isX() ? z1 : x1; }
    static int sideHi(Dir s, int x2, int z2) { return s.isX() ? z2 : x2; }

    /** Splits a side of length n (corner to corner, inclusive) into evenly spaced post positions (offsets from start). */
    static int[] posts(int n, int targetBay) {
        int bays = Math.max(1, Math.round((n - 1) / (float) targetBay));
        int[] p = new int[bays + 1];
        for (int i = 0; i <= bays; i++) p[i] = Math.round(i * (n - 1) / (float) bays);
        return p;
    }

    static Map<String, String> logAxis(char a) { return Map.of("axis", String.valueOf(a)); }

    /** Facing that points from (dx,dz) offset back toward the center, by dominant axis. */
    static Dir toward(double dx, double dz) {
        if (Math.abs(dx) >= Math.abs(dz)) return dx > 0 ? Dir.WEST : Dir.EAST;
        return dz > 0 ? Dir.NORTH : Dir.SOUTH;
    }

    // ======================================================================== timber-framed house

    public static final class HouseOpts {
        public int floors = 2;
        public int floorHeight = 4;
        public Roofs.Type roof = Roofs.Type.GABLE;
        public double pitch = 1;
        public boolean chimney = true;
        public boolean jetty = true;
        public boolean timberFrame = true;
        public int bay = 5;
        public boolean shutters = false;
        public Dir front = Dir.NORTH;
    }

    /** House occupying [x..x+w-1] x [z..z+d-1], foundation at y. Returns the roof's top y. */
    public static int house(Canvas c, int x, int y, int z, int w, int d, HouseOpts o) {
        int x1 = x, x2 = x + w - 1, z1 = z, z2 = z + d - 1;
        int fh = o.floorHeight;
        int top = y + o.floors * fh; // wall plate row

        // foundation slab and interior ground floor
        c.fill(x1, y, z1, x2, y, z2, "@foundation");
        c.fill(x1 + 1, y, z1 + 1, x2 - 1, y, z2 - 1, "@floor");

        for (int f = 0; f < o.floors; f++) {
            int fb = y + f * fh;
            boolean jettied = o.jetty && f > 0 && o.timberFrame;
            int jx1 = x1, jx2 = x2, jz1 = jettied ? z1 - 1 : z1, jz2 = jettied ? z2 + 1 : z2;
            for (Dir s : Dir.values()) {
                int lo = sideLo(s, jx1, jz1), hi = sideHi(s, jx2, jz2);
                int[] pp = posts(hi - lo + 1, o.bay);
                boolean[] isPost = new boolean[hi - lo + 1];
                for (int p : pp) isPost[p] = true;
                char along = s.isX() ? 'z' : 'x';
                for (int i = 0; i <= hi - lo; i++) {
                    int a = lo + i;
                    for (int yy = fb + 1; yy < fb + fh; yy++) {
                        int[] outer = side(s, jx1, jz1, jx2, jz2, a, 0);
                        int[] inner = side(s, jx1, jz1, jx2, jz2, a, -1);
                        boolean corner = i == 0 || i == hi - lo;
                        if (f == 0 && yy == fb + 1) {
                            c.set(outer[0], yy, outer[1], "@foundation"); // stone plinth course
                            if (!corner) c.set(inner[0], yy, inner[1], "@wall");
                        } else if (o.timberFrame && isPost[i]) {
                            c.set(outer[0], yy, outer[1], "@trim", logAxis('y'));
                        } else if (o.timberFrame) {
                            if (!corner) c.set(inner[0], yy, inner[1], "@wall");
                            // diagonal brace in end bays on upper floors: stairs read as carved brackets
                        } else {
                            c.set(outer[0], yy, outer[1], "@wall");
                        }
                    }
                    // beam / wall plate on top of this floor
                    int[] outer = side(s, jx1, jz1, jx2, jz2, a, 0);
                    c.set(outer[0], fb + fh, outer[1], o.timberFrame ? "@trim" : "@accent", o.timberFrame ? logAxis(along) : Map.of());
                }
            }
            // jetty brackets: upside-down stairs under the projecting front/back
            if (jettied) {
                for (int a = x1; a <= x2; a++) {
                    c.set(a, fb, z1 - 1, "@trim:stairs", Canvas.stairs(Dir.SOUTH, true));
                    c.set(a, fb, z2 + 1, "@trim:stairs", Canvas.stairs(Dir.NORTH, true));
                }
                // close side gaps of the jetty
                for (int yy = fb + 1; yy < fb + fh; yy++) {
                    for (int zz : new int[]{z1 - 1, z2 + 1}) {
                        c.set(x1, yy, zz, "@trim", logAxis('y'));
                        c.set(x2, yy, zz, "@trim", logAxis('y'));
                    }
                }
            }
            // interior floor slab for the next storey
            if (f > 0) c.fill(x1 + 1, fb, z1 + 1, x2 - 1, fb, z2 - 1, "@floor");
        }
        c.fill(x1 + 1, top, z1 + 1, x2 - 1, top, z2 - 1, "@floor"); // attic floor / ceiling

        // windows in every bay that is not the door bay
        int doorA = o.front.isX() ? (z1 + z2) / 2 : (x1 + x2) / 2;
        for (int f = 0; f < o.floors; f++) {
            int fb = y + f * fh;
            boolean jettied = o.jetty && f > 0 && o.timberFrame;
            int jz1 = jettied ? z1 - 1 : z1, jz2 = jettied ? z2 + 1 : z2;
            for (Dir s : Dir.values()) {
                int lo = sideLo(s, x1, jz1), hi = sideHi(s, x2, jz2);
                int[] pp = posts(hi - lo + 1, o.bay);
                for (int b = 0; b + 1 < pp.length; b++) {
                    int a0 = lo + pp[b] + 1, a1 = lo + pp[b + 1] - 1;
                    if (a1 < a0) continue;
                    int mid = (a0 + a1) / 2;
                    boolean doorBay = f == 0 && s == o.front && doorA >= a0 && doorA <= a1;
                    if (doorBay) continue;
                    int wy0 = fb + 2, wy1 = fb + fh - 1;
                    if (f == 0) wy0 = fb + 2;
                    int L = a1 - a0 + 1;
                    int wl = (L % 2 == 0) ? 2 : (L >= 7 ? 3 : 1);
                    if (L < 2) wl = 1;
                    int wa0 = a0 + (L - wl) / 2;
                    for (int wa = wa0; wa < wa0 + wl; wa++) {
                        for (int yy = wy0; yy <= wy1; yy++) {
                            int[] in = side(s, x1, jz1, x2, jz2, wa, o.timberFrame ? -1 : 0);
                            c.set(in[0], yy, in[1], "@window");
                        }
                        int[] sill = side(s, x1, jz1, x2, jz2, wa, o.timberFrame ? 0 : 1);
                        c.set(sill[0], wy0 - 1, sill[1], "@trim:stairs", Canvas.stairs(s.opposite(), true));
                    }
                    // shutters
                    if (o.timberFrame && (o.shutters || f == 0) && a1 - a0 + 1 >= wl + 4) {
                        for (int yy = wy0; yy <= wy1; yy++) {
                            int[] l = side(s, x1, jz1, x2, jz2, wa0 - 1, 0), r = side(s, x1, jz1, x2, jz2, wa0 + wl, 0);
                            Map<String, String> tp = Map.of("facing", s.key(), "open", "true", "half", "bottom");
                            c.set(l[0], yy, l[1], "@wall_alt:trapdoor", tp);
                            c.set(r[0], yy, r[1], "@wall_alt:trapdoor", tp);
                        }
                    }
                }
            }
        }

        // front door, step and lanterns
        int[] din = side(o.front, x1, z1, x2, z2, doorA, o.timberFrame ? -1 : 0);
        int[] dout = side(o.front, x1, z1, x2, z2, doorA, 0);
        int[] step = side(o.front, x1, z1, x2, z2, doorA, 1);
        if (o.timberFrame) { c.air(dout[0], y + 1, dout[1]); c.air(dout[0], y + 2, dout[1]); c.air(dout[0], y + 3, dout[1]); }
        c.set(din[0], y + 1, din[1], "@wall_alt:door", Map.of("facing", o.front.opposite().key(), "half", "lower", "hinge", "left"));
        c.set(din[0], y + 2, din[1], "@wall_alt:door", Map.of("facing", o.front.opposite().key(), "half", "upper", "hinge", "left"));
        c.set(din[0], y + 3, din[1], "@trim", logAxis(o.front.isX() ? 'z' : 'x'));
        c.set(step[0], y, step[1], "@foundation:stairs", Canvas.stairs(o.front.opposite(), false));
        for (int sgn : new int[]{-1, 1}) {
            int[] lp = side(o.front, x1, z1, x2, z2, doorA + sgn * 1, 1);
            c.set(lp[0], y + fh - 1, lp[1], "@light", Map.of("hanging", "true"));
            int[] arm = side(o.front, x1, z1, x2, z2, doorA + sgn * 1, 1);
            c.set(arm[0], y + fh, arm[1], "@trim:fence");
        }

        // roof: ridge along the long axis
        Roofs.Spec r = new Roofs.Spec();
        int rz1 = o.jetty && o.timberFrame && o.floors > 1 ? z1 - 1 : z1, rz2 = o.jetty && o.timberFrame && o.floors > 1 ? z2 + 1 : z2;
        r.x1 = x1; r.x2 = x2; r.z1 = rz1; r.z2 = rz2; r.y = top + 1;
        r.type = o.roof;
        r.axis = (x2 - x1) >= (rz2 - rz1) ? 'x' : 'z';
        r.pitch = o.pitch;
        r.trim = "@roof_trim";
        r.gable = "@wall_alt";
        r.overhang = 1;
        Roofs.build(c, r);
        int roofTop = top + 1 + (Math.min(x2 - x1, rz2 - rz1) / 2 + 1) * (int) Math.max(1, Math.round(o.pitch));

        // gable window
        if (o.roof == Roofs.Type.GABLE) {
            if (r.axis == 'x') {
                int cz = (rz1 + rz2) / 2;
                c.set(x1, top + 2, cz, "@window"); c.set(x2, top + 2, cz, "@window");
            } else {
                int cx = (x1 + x2) / 2;
                c.set(cx, top + 2, rz1, "@window"); c.set(cx, top + 2, rz2, "@window");
            }
        }

        // chimney at the back corner, rising above the roof
        if (o.chimney) {
            int chx = x2 - 2, chz = z2 + (o.jetty && o.floors > 1 ? 1 : 0);
            for (int yy = y + 1; yy <= roofTop + 1; yy++) {
                c.set(chx, yy, chz, "@accent");
                c.set(chx - 1, yy, chz, "@accent");
            }
            c.set(chx, roofTop + 2, chz, "campfire", Map.of("lit", "true", "signal_fire", "false", "facing", "north"));
            c.set(chx - 1, roofTop + 2, chz, "@accent:slab", Canvas.slab(false));
        }
        return roofTop;
    }

    // ======================================================================== towers

    public static final class TowerOpts {
        public String roof = "cone";      // cone | spire | crenellated | dome | flat | none
        public int floorEvery = 6;
        public boolean door = true;
        public Dir front = Dir.NORTH;
        public double roofPitch = 2.2;    // cone height = (r+1) * pitch
        public boolean bands = true;
        public boolean plinth = true;
    }

    /** Round tower centered on (cx, cz) with radius r and wall height h above y. Returns the top y. */
    public static int roundTower(Canvas c, double cx, int y, double cz, double r, int h, TowerOpts o) {
        if (o.plinth) {
            c.cylinder(cx, y, cz, r + 1, 2, "@foundation", false, 'y');
            c.ring(cx, y + 2, cz, r + 1, "@foundation:slab");
        } else {
            c.cylinder(cx, y, cz, r, 1, "@foundation", false, 'y');
        }
        c.cylinder(cx, y + 1, cz, r, h - 1, "@wall", true, 'y');
        c.cylinder(cx, y, cz, r - 1, 1, "@floor", false, 'y');
        // interior floors, bands and windows
        for (int fy = y + o.floorEvery; fy < y + h - 2; fy += o.floorEvery) {
            c.cylinder(cx, fy, cz, r - 1, 1, "@floor", false, 'y');
            if (o.bands) {
                for (int[] p : ringCells(cx, cz, r + 1)) c.setAttached(p[0], fy, p[1], "@accent:slab", Canvas.slab(true));
            }
        }
        int level = 0;
        java.util.List<int[]> wallRing = ringCells(cx, cz, r);
        for (int wy = y + 3; wy + 1 < y + h - 1; wy += o.floorEvery, level++) {
            double rot = (level % 2 == 0) ? 0 : Math.PI / 4;
            for (int i = 0; i < 4; i++) {
                double ang = rot + i * Math.PI / 2;
                if (o.door && level == 0 && i == 0) continue;
                // snap to the wall cell whose bearing is closest to the wanted angle
                int[] best = null;
                double bestD = 1e9;
                for (int[] p : wallRing) {
                    double a = Math.atan2(p[0] - cx, -(p[1] - cz));
                    double d = Math.abs(Math.atan2(Math.sin(a - ang), Math.cos(a - ang)));
                    if (d < bestD) { bestD = d; best = p; }
                }
                if (best == null) continue;
                int wx = best[0], wz = best[1];
                c.set(wx, wy, wz, "@window");
                c.set(wx, wy + 1, wz, "@window");
                Dir inward = toward(wx - cx, wz - cz);
                Dir out = inward.opposite();
                if (!c.has(wx + out.dx, wy - 1, wz + out.dz)) c.set(wx + out.dx, wy - 1, wz + out.dz, "@trim:stairs", Canvas.stairs(inward, true));
            }
        }
        if (o.door) {
            int dx = (int) Math.round(cx), dz = (int) Math.round(cz - r);
            if (o.front == Dir.SOUTH) dz = (int) Math.round(cz + r);
            Dir face = o.front;
            for (int k = 0; k <= 1; k++) { c.air(dx, y + 1, dz - face.dz * k); c.air(dx, y + 2, dz - face.dz * k); }
            c.set(dx, y + 1, dz, "@wall_alt:door", Map.of("facing", face.opposite().key(), "half", "lower", "hinge", "left"));
            c.set(dx, y + 2, dz, "@wall_alt:door", Map.of("facing", face.opposite().key(), "half", "upper", "hinge", "left"));
            c.set(dx, y + 3, dz + face.dz, "@accent:stairs", Canvas.stairs(face.opposite(), true));
            c.set(dx, y, dz + face.dz, "@foundation:stairs", Canvas.stairs(face.opposite(), false));
        }
        int top = y + h - 1;
        switch (o.roof) {
            case "crenellated" -> top = crown(c, cx, top, cz, r);
            case "dome" -> {
                c.ring(cx, top + 1, cz, r + 1, "@trim:slab");
                c.ellipsoid(cx, top + 1, cz, r, r * 0.9, r, "@roof:full", true, "top");
                top = top + 1 + (int) Math.round(r * 0.9);
                c.trySet((int) Math.floor(cx), top + 1, (int) Math.floor(cz), "@roof_trim:wall", "@roof_trim:fence");
                c.set((int) Math.floor(cx), top + 2, (int) Math.floor(cz), "lightning_rod");
                top += 2;
            }
            case "flat" -> {
                c.cylinder(cx, top + 1, cz, r, 1, "@roof:full", false, 'y');
                top = top + 1;
            }
            case "none" -> { }
            default -> {
                // conical / spire: ring of upside-down stairs as eave, then the cone
                for (int[] p : ringCells(cx, cz, r + 1)) c.set(p[0], top, p[1], "@roof_trim:stairs", Canvas.stairs(toward(p[0] - cx, p[1] - cz), true));
                boolean spire = "spire".equals(o.roof);
                double pitch = spire ? Math.max(o.roofPitch, 3.2) : o.roofPitch;
                top = cone(c, cx, top + 1, cz, r + 1, (int) Math.round((r + 1) * pitch), spire ? 1.7 : 1.15);
            }
        }
        return top;
    }

    /** Cells forming a 1-thick ring of radius r. */
    static java.util.List<int[]> ringCells(double cx, double cz, double r) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        int R = (int) Math.ceil(r + 1);
        for (int a = -R; a <= R; a++) for (int b = -R; b <= R; b++) {
            int x = (int) Math.floor(cx) + a, z = (int) Math.floor(cz) + b;
            double dx = x - cx, dz = z - cz;
            if (!in(dx, dz, r)) continue;
            if (in(dx + 1, dz, r) && in(dx - 1, dz, r) && in(dx, dz + 1, r) && in(dx, dz - 1, r)
                    && in(dx + 1, dz + 1, r) && in(dx - 1, dz - 1, r) && in(dx + 1, dz - 1, r) && in(dx - 1, dz + 1, r)) continue;
            out.add(new int[]{x, z});
        }
        return out;
    }

    private static boolean in(double dx, double dz, double r) { return dx * dx + dz * dz <= (r + 0.35) * (r + 0.35); }

    /** Conical roof of stairs facing the axis; returns the top y (finial included). */
    public static int cone(Canvas c, double cx, int y, double cz, double r, int height) {
        return cone(c, cx, y, cz, r, height, 1.0);
    }

    /**
     * Conical roof with a profile exponent: 1 is a straight cone, above 1 is concave (a flared eave that tightens into
     * a needle, the classic fantasy/gothic spire).
     */
    public static int cone(Canvas c, double cx, int y, double cz, double r, int height, double curve) {
        height = Math.max(2, height);
        int topY = y;
        for (int i = 0; i < height; i++) {
            double ri = r * Math.pow(1.0 - (double) i / height, curve);
            double rn = r * Math.pow(1.0 - (double) (i + 1) / height, curve);
            if (ri < 0.45) break;
            int R = (int) Math.ceil(ri + 1);
            for (int a = -R; a <= R; a++) for (int b = -R; b <= R; b++) {
                int x = (int) Math.floor(cx) + a, z = (int) Math.floor(cz) + b;
                double dx = x - cx, dz = z - cz;
                if (!in(dx, dz, ri)) continue;
                if (rn >= 0.45 && in(dx, dz, rn)) {
                    // covered by the next layer: back the next layer's rim with full blocks so corner notches never show voids
                    if (!in(dx, dz, rn - 1.2) || (Math.abs(dx) <= 0.5 && Math.abs(dz) <= 0.5)) c.set(x, y + i, z, "@roof:full");
                    continue;
                }
                if (Math.abs(dx) < 0.01 && Math.abs(dz) < 0.01) c.set(x, y + i, z, "@roof:full");
                else c.set(x, y + i, z, "@roof:stairs", Canvas.stairs(toward(dx, dz), false));
            }
            topY = y + i;
        }
        int fx = (int) Math.floor(cx), fz = (int) Math.floor(cz);
        if (cx == Math.floor(cx) && cz == Math.floor(cz)) {
            c.set(fx, topY + 1, fz, "@roof:full");
            c.trySet(fx, topY + 2, fz, "@roof_trim:wall", "@roof_trim:fence", "cobblestone_wall");
            c.set(fx, topY + 3, fz, "lightning_rod");
            return topY + 3;
        }
        return topY;
    }

    /** Machicolated crenellated crown for a round tower whose wall tops out at y. Returns new top. */
    static int crown(Canvas c, double cx, int y, double cz, double r) {
        for (int[] p : ringCells(cx, cz, r + 1)) c.set(p[0], y, p[1], "@wall:stairs", Canvas.stairs(toward(p[0] - cx, p[1] - cz), true));
        c.cylinder(cx, y + 1, cz, r + 1, 1, "@wall", true, 'y');
        c.cylinder(cx, y + 1, cz, r, 1, "@floor:slab", false, 'y');
        java.util.List<int[]> ring = ringCells(cx, cz, r + 1);
        ring.sort((p, q) -> Double.compare(Math.atan2(p[1] - cz, p[0] - cx), Math.atan2(q[1] - cz, q[0] - cx)));
        for (int i = 0; i < ring.size(); i++) {
            if (i % 2 == 0) c.set(ring.get(i)[0], y + 2, ring.get(i)[1], "@wall");
        }
        // reshape inner floor to full blocks under the parapet (keep slab walkway look)
        return y + 2;
    }

    /** Square tower occupying [x..x+s-1]^2. roof: hip | spire | crenellated | pyramid | flat. Returns top y. */
    public static int squareTower(Canvas c, int x, int y, int z, int s, int h, String roof, boolean door, Dir front) {
        int x2 = x + s - 1, z2 = z + s - 1;
        c.fill(x - 1, y, z - 1, x2 + 1, y, z2 + 1, "@foundation");
        c.walls(x - 1, y + 1, z - 1, x2 + 1, y + 1, z2 + 1, "@foundation");
        c.fill(x - 1, y + 2, z - 1, x2 + 1, y + 2, z2 + 1, "@foundation:slab");
        c.walls(x, y + 1, z, x2, y + h - 1, z2, "@wall");
        c.fill(x + 1, y + 1, z + 1, x2 - 1, y + 2, z2 - 1, "@floor");
        c.carve(x + 1, y + 1, z + 1, x2 - 1, y + 2, z2 - 1);
        c.fill(x + 1, y, z + 1, x2 - 1, y, z2 - 1, "@floor");
        // quoins: alternating long/short corner stones
        for (int yy = y + 3; yy < y + h; yy++) {
            boolean longCourse = ((yy - y) / 2) % 2 == 0;
            for (int[] cn : new int[][]{{x, z}, {x2, z}, {x, z2}, {x2, z2}}) {
                c.set(cn[0], yy, cn[1], "@accent");
                if (longCourse) {
                    c.set(cn[0] == x ? x + 1 : x2 - 1, yy, cn[1], "@accent");
                } else {
                    c.set(cn[0], yy, cn[1] == z ? z + 1 : z2 - 1, "@accent");
                }
            }
        }
        int mid = x + s / 2, midz = z + s / 2;
        for (int fy = y + 6; fy < y + h - 2; fy += 6) {
            c.fill(x + 1, fy, z + 1, x2 - 1, fy, z2 - 1, "@floor");
            for (int a = x - 1; a <= x2 + 1; a++) { c.set(a, fy, z - 1, "@accent:stairs", Canvas.stairs(Dir.SOUTH, true)); c.set(a, fy, z2 + 1, "@accent:stairs", Canvas.stairs(Dir.NORTH, true)); }
            for (int a = z; a <= z2; a++) { c.set(x - 1, fy, a, "@accent:stairs", Canvas.stairs(Dir.EAST, true)); c.set(x2 + 1, fy, a, "@accent:stairs", Canvas.stairs(Dir.WEST, true)); }
        }
        for (int wy = y + 3; wy + 1 < y + h - 1; wy += 6) {
            for (Dir d : Dir.values()) {
                if (door && wy == y + 3 && d == front) continue;
                int[] p = side(d, x, z, x2, z2, d.isX() ? midz : mid, 0);
                c.set(p[0], wy, p[1], "@window");
                c.set(p[0], wy + 1, p[1], "@window");
                if (s >= 7) {
                    int[] q = side(d, x, z, x2, z2, (d.isX() ? midz : mid) + ((s % 2 == 0) ? -1 : 0), 0);
                    c.set(q[0], wy, q[1], "@window");
                    c.set(q[0], wy + 1, q[1], "@window");
                }
                int[] sill = side(d, x, z, x2, z2, d.isX() ? midz : mid, 1);
                c.set(sill[0], wy - 1, sill[1], "@trim:stairs", Canvas.stairs(d.opposite(), true));
            }
        }
        if (door) {
            int[] p = side(front, x, z, x2, z2, front.isX() ? midz : mid, 0);
            c.set(p[0], y + 1, p[1], "@wall_alt:door", Map.of("facing", front.opposite().key(), "half", "lower", "hinge", "left"));
            c.set(p[0], y + 2, p[1], "@wall_alt:door", Map.of("facing", front.opposite().key(), "half", "upper", "hinge", "left"));
            int[] st = side(front, x, z, x2, z2, front.isX() ? midz : mid, 2);
            c.set(st[0], y, st[1], "@foundation:stairs", Canvas.stairs(front.opposite(), false));
            int[] pl = side(front, x, z, x2, z2, front.isX() ? midz : mid, 1);
            c.air(pl[0], y + 1, pl[1]); c.air(pl[0], y + 2, pl[1]);
            c.set(pl[0], y + 3, pl[1], "@accent:stairs", Canvas.stairs(front.opposite(), true));
        }
        int top = y + h - 1;
        switch (roof) {
            case "crenellated", "flat" -> {
                for (int a = x - 1; a <= x2 + 1; a++) { c.set(a, top, z - 1, "@wall:stairs", Canvas.stairs(Dir.SOUTH, true)); c.set(a, top, z2 + 1, "@wall:stairs", Canvas.stairs(Dir.NORTH, true)); }
                for (int a = z; a <= z2; a++) { c.set(x - 1, top, a, "@wall:stairs", Canvas.stairs(Dir.EAST, true)); c.set(x2 + 1, top, a, "@wall:stairs", Canvas.stairs(Dir.WEST, true)); }
                c.walls(x - 1, top + 1, z - 1, x2 + 1, top + 1, z2 + 1, "@wall");
                c.fill(x, top + 1, z, x2, top + 1, z2, "@floor");
                if (roof.equals("crenellated")) {
                    for (int a = 0; a < s + 2; a++) {
                        if (a % 2 == 0) {
                            c.set(x - 1 + a, top + 2, z - 1, "@wall"); c.set(x - 1 + a, top + 2, z2 + 1, "@wall");
                            c.set(x - 1, top + 2, z - 1 + a, "@wall"); c.set(x2 + 1, top + 2, z - 1 + a, "@wall");
                        }
                    }
                    top += 2;
                } else top += 1;
            }
            case "spire" -> {
                // gothic: corbelled parapet walk, corner pinnacles, slender spire set back from the parapet
                for (int a = x - 1; a <= x2 + 1; a++) { c.set(a, top, z - 1, "@wall:stairs", Canvas.stairs(Dir.SOUTH, true)); c.set(a, top, z2 + 1, "@wall:stairs", Canvas.stairs(Dir.NORTH, true)); }
                for (int a = z; a <= z2; a++) { c.set(x - 1, top, a, "@wall:stairs", Canvas.stairs(Dir.EAST, true)); c.set(x2 + 1, top, a, "@wall:stairs", Canvas.stairs(Dir.WEST, true)); }
                c.walls(x - 1, top + 1, z - 1, x2 + 1, top + 1, z2 + 1, "@wall");
                c.fill(x, top + 1, z, x2, top + 1, z2, "@floor");
                for (int a = x; a <= x2; a++) { c.trySet(a, top + 2, z - 1, "@wall:wall"); c.trySet(a, top + 2, z2 + 1, "@wall:wall"); }
                for (int a = z; a <= z2; a++) { c.trySet(x - 1, top + 2, a, "@wall:wall"); c.trySet(x2 + 1, top + 2, a, "@wall:wall"); }
                for (int[] cn : new int[][]{{x - 1, z - 1}, {x2 + 1, z - 1}, {x - 1, z2 + 1}, {x2 + 1, z2 + 1}}) {
                    c.set(cn[0], top + 2, cn[1], "@wall");
                    c.set(cn[0], top + 3, cn[1], "@wall");
                    c.trySet(cn[0], top + 4, cn[1], "@trim:wall", "@wall:wall");
                    c.set(cn[0], top + 5, cn[1], "lightning_rod");
                }
                Roofs.Spec r = new Roofs.Spec();
                r.x1 = x + 1; r.z1 = z + 1; r.x2 = x2 - 1; r.z2 = z2 - 1; r.y = top + 2;
                r.type = Roofs.Type.SPIRE; r.overhang = 0; r.trim = "@roof_trim"; r.pitch = 4;
                Roofs.build(c, r);
                top = c.groundAt(x + s / 2, z + s / 2, top + 4 * s + 8) - 1;
            }
            default -> {
                Roofs.Spec r = new Roofs.Spec();
                r.x1 = x; r.z1 = z; r.x2 = x2; r.z2 = z2; r.y = top + 1;
                r.type = Roofs.parseType(roof.equals("hip") ? "hip" : "pyramid");
                r.overhang = 1; r.trim = "@roof_trim"; r.pitch = 1;
                Roofs.build(c, r);
                top = c.groundAt(x + s / 2, z + s / 2, top + 4 * s + 8) - 1;
            }
        }
        return top;
    }

    // ======================================================================== curtain walls

    /**
     * Straight defensive wall from (x1,z1) to (x2,z2) (must share x or z), thickness t, height h above y.
     * outside = the side facing attackers (gets machicolations and buttresses).
     */
    public static void curtainWall(Canvas c, int x1, int z1, int x2, int z2, int y, int h, int t, Dir outside, int buttressEvery) {
        boolean alongX = z1 == z2;
        int lo = alongX ? Math.min(x1, x2) : Math.min(z1, z2), hi = alongX ? Math.max(x1, x2) : Math.max(z1, z2);
        int base = alongX ? z1 : x1;
        // wall body spans base .. base + (t-1)*inward
        Dir in = outside.opposite();
        for (int a = lo; a <= hi; a++) {
            for (int k = 0; k < t; k++) {
                int px = alongX ? a : base + in.dx * k, pz = alongX ? base + in.dz * k : a;
                c.set(px, y, pz, "@foundation");
                c.set(px, y + 1, pz, k == 0 ? "@foundation" : "@wall");
                for (int yy = y + 2; yy < y + h; yy++) c.set(px, yy, pz, "@wall");
            }
            // corbels + overhanging parapet + merlons on the outside
            int ox = alongX ? a : base + outside.dx, oz = alongX ? base + outside.dz : a;
            c.set(ox, y + h - 1, oz, "@wall:stairs", Canvas.stairs(in, true));
            c.set(ox, y + h, oz, "@wall");
            if (((a - lo) % 2) == 0) c.set(ox, y + h + 1, oz, "@wall");
            else c.set(ox, y + h + 1, oz, "@wall:slab", Canvas.slab(false));
            // walkway and inner low wall
            for (int k = 0; k < t; k++) {
                int px = alongX ? a : base + in.dx * k, pz = alongX ? base + in.dz * k : a;
                c.set(px, y + h, pz, "@floor:slab", Canvas.slab(false));
            }
            int ix = alongX ? a : base + in.dx * t, iz = alongX ? base + in.dz * t : a;
            c.trySet(ix, y + h, iz, "@wall:wall", "@foundation:wall", "cobblestone_wall");
            // buttresses
            if (buttressEvery > 0 && (a - lo) % buttressEvery == buttressEvery / 2 && a > lo && a < hi) {
                for (int yy = y; yy < y + h - 3; yy++) {
                    int bx = alongX ? a : base + outside.dx, bz = alongX ? base + outside.dz : a;
                    c.set(bx, yy, bz, yy < y + 2 ? "@foundation" : "@wall");
                    if (yy < y + (h - 3) / 2) {
                        int bx2 = alongX ? a : base + outside.dx * 2, bz2 = alongX ? base + outside.dz * 2 : a;
                        c.set(bx2, yy, bz2, "@foundation");
                    }
                }
                int bx = alongX ? a : base + outside.dx, bz = alongX ? base + outside.dz : a;
                c.set(bx, y + h - 3, bz, "@wall:stairs", Canvas.stairs(in, false));
                int bx2 = alongX ? a : base + outside.dx * 2, bz2 = alongX ? base + outside.dz * 2 : a;
                c.set(bx2, y + (h - 3) / 2, bz2, "@foundation:stairs", Canvas.stairs(in, false));
                // arrow slit
                int sx = alongX ? a : base, sz = alongX ? base : a;
                if (((a - lo) / buttressEvery) % 2 == 1) { c.air(sx, y + h - 4, sz); c.air(sx, y + h - 5, sz); }
            }
        }
    }

    // ======================================================================== gatehouse

    /** Twin-towered gatehouse centered on cx, wall plane at z (front faces north). Passage width 3-5. */
    public static void gatehouse(Canvas c, int cx, int y, int z, int towerSize, int h, int passage) {
        int half = passage / 2;
        int lx = cx - half - towerSize - 2, rx = cx + half + 3;
        squareTower(c, lx, y, z - 2, towerSize, h + 4, "crenellated", false, Dir.NORTH);
        squareTower(c, rx, y, z - 2, towerSize, h + 4, "crenellated", false, Dir.NORTH);
        // gate block between the towers
        int gx1 = cx - half, gx2 = cx + half, gz1 = z - 1, gz2 = z - 2 + towerSize - 1;
        c.fill(gx1 - 2, y, gz1, gx2 + 2, y + h, gz2, "@wall");
        // pointed arch passage through
        c.arch('x', gx1, gx2, y + 1, Math.max(2, h / 2 - 1), gz1, gz2 - gz1 + 1, "pointed", null, 1, true);
        c.arch('x', gx1 - 1, gx2 + 1, y + 1, Math.max(2, h / 2 - 1), gz1 - 1, 1, "pointed", "@accent", 1, false);
        // portcullis behind the arch mouth
        for (int a = gx1; a <= gx2; a++) for (int yy = y + 1; yy < y + h / 2 + 1; yy++) {
            if (!c.has(a, yy, gz1 + 1)) c.set(a, yy, gz1 + 1, "@metal");
        }
        c.fill(gx1, y, gz1, gx2, y, gz2, "@path");
        // parapet on top of the gate block
        for (int a = gx1 - 2; a <= gx2 + 2; a++) {
            c.set(a, y + h, gz1 - 1, "@wall:stairs", Canvas.stairs(Dir.SOUTH, true));
            c.set(a, y + h + 1, gz1 - 1, "@wall");
            if ((a - gx1) % 2 == 0) c.set(a, y + h + 2, gz1 - 1, "@wall");
        }
        // banners/lanterns flanking
        c.set(gx1 - 1, y + h / 2 + 2, gz1 - 2, "@light", Map.of("hanging", "false"));
        c.set(gx2 + 1, y + h / 2 + 2, gz1 - 2, "@light", Map.of("hanging", "false"));
        c.set(gx1 - 1, y + h / 2 + 1, gz1 - 2, "@accent:wall");
        c.set(gx2 + 1, y + h / 2 + 1, gz1 - 2, "@accent:wall");
    }

    // ======================================================================== nature & small structures

    /** Natural-looking tree. kind: oak | spruce | cherry | birch | dark_oak | jungle | palm | azalea. size 1..3. */
    public static void tree(Canvas c, int x, int y, int z, String kind, int size) {
        Materials m = c.materials();
        int g0 = c.groundAt(x, z, y + 8);
        if (g0 > 0) y = g0;
        size = Math.max(1, Math.min(3, size));
        switch (kind) {
            case "spruce" -> {
                int h = 6 + size * 4;
                for (int i = 0; i < h; i++) c.set(x, y + i, z, "spruce_log", logAxis('y'));
                double r = 1.5 + size;
                for (int i = 2; i <= h; i++) {
                    double t = (double) (i - 2) / (h - 2);
                    double rr = r * (1 - t) * (i % 2 == 0 ? 1 : 0.65) + 0.4;
                    leafDisk(c, x, y + i, z, rr, "spruce_leaves", 0.08);
                }
                c.set(x, y + h + 1, z, "spruce_leaves");
            }
            case "palm" -> {
                int h = 6 + size * 2;
                int lean = 0;
                for (int i = 0; i < h; i++) {
                    if (i > h / 2 && i % 3 == 0) {
                        lean++;
                        c.set(x + lean, y + i - 1, z, "jungle_log", logAxis('x')); // elbow keeps the trunk face-connected
                    }
                    c.set(x + lean, y + i, z, "jungle_log", logAxis('y'));
                }
                int tx = x + lean, ty = y + h;
                c.set(tx, ty, z, "jungle_leaves");
                c.set(tx, ty - 1, z, "jungle_log", logAxis('y'));
                int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
                for (int[] d : dirs) {
                    int len = (d[0] != 0 && d[1] != 0) ? 2 + size : 3 + size;
                    int px = tx, py = ty, pz = z;
                    for (int k = 1; k <= len; k++) {
                        int nx = tx + d[0] * k, nz = z + d[1] * k, ny = ty - Math.max(0, (k - 2) / 2);
                        if (nx != px && nz != pz) c.set(nx, py, pz, "jungle_leaves");   // keep diagonal fronds face-connected
                        if (ny != py) c.set(nx, py, nz, "jungle_leaves");                 // and the droop step
                        c.set(nx, ny, nz, "jungle_leaves");
                        px = nx; py = ny; pz = nz;
                    }
                }
            }
            default -> {
                String log = switch (kind) {
                    case "cherry" -> "cherry_log"; case "birch" -> "birch_log"; case "dark_oak" -> "dark_oak_log";
                    case "jungle" -> "jungle_log"; case "azalea" -> "oak_log"; default -> "oak_log";
                };
                String leaves = switch (kind) {
                    case "cherry" -> "cherry_leaves"; case "birch" -> "birch_leaves"; case "dark_oak" -> "dark_oak_leaves";
                    case "jungle" -> "jungle_leaves"; case "azalea" -> "@leaves"; default -> "oak_leaves";
                };
                int trunk = (kind.equals("cherry") ? 3 : 4) + size * 2;
                int tw = kind.equals("dark_oak") || size == 3 ? 2 : 1;
                for (int i = 0; i < trunk; i++) for (int a = 0; a < tw; a++) for (int b = 0; b < tw; b++) c.set(x + a, y + i, z + b, log, logAxis('y'));
                double crownR = 1.9 + size * 0.8 + (kind.equals("cherry") ? 0.6 : 0);
                int nb = 3 + size;
                for (int bi = 0; bi < nb; bi++) {
                    double ang = bi * (Math.PI * 2 / nb) + m.random(x, y, z, bi) * 0.8;
                    int by = y + trunk - 2 - (int) (m.random(x, y, z, 100 + bi) * 2);
                    int len = 1 + size + (kind.equals("cherry") ? 1 : 0);
                    int ex = x + (int) Math.round(Math.cos(ang) * len), ez = z + (int) Math.round(Math.sin(ang) * len);
                    int ey = by + 2 + (int) (m.random(x, y, z, 200 + bi) * (1 + size));
                    c.line(x, by, z, ex, ey, ez, log, 1);
                    blob(c, ex, ey + 1, ez, crownR * 0.72, crownR * (kind.equals("cherry") ? 0.5 : 0.62), leaves, x * 31 + bi);
                }
                blob(c, x, y + trunk + (kind.equals("cherry") ? 0 : 1), z, crownR, crownR * (kind.equals("cherry") ? 0.6 : 0.85), leaves, x * 17 + z);
            }
        }
    }

    private static void leafDisk(Canvas c, int x, int y, int z, double r, String leaves, double holes) {
        int R = (int) Math.ceil(r);
        for (int a = -R; a <= R; a++) for (int b = -R; b <= R; b++) {
            double d = Math.sqrt(a * a + b * b);
            if (d > r + 0.3) continue;
            if (d > r - 0.7 && c.materials().random(x + a, y, z + b, 7) < holes) continue;
            if (!c.has(x + a, y, z + b)) c.set(x + a, y, z + b, leaves);
        }
    }

    private static void blob(Canvas c, int cx, int cy, int cz, double rh, double rv, String leaves, int salt) {
        int R = (int) Math.ceil(rh + 1), V = (int) Math.ceil(rv + 1);
        for (int a = -R; a <= R; a++) for (int b = -V; b <= V; b++) for (int d = -R; d <= R; d++) {
            double n = c.materials().random(cx + a, cy + b, cz + d, salt) * 0.55;
            double e = (a * a) / (rh * rh) + (b * b) / (rv * rv) + (d * d) / (rh * rh);
            if (e > 1.0 + n * 0.5 - 0.15) continue;
            if (e > 0.72 && c.materials().random(cx + a, cy + b, cz + d, salt + 3) < 0.22) continue; // ragged edge
            if (!c.has(cx + a, cy + b, cz + d)) c.set(cx + a, cy + b, cz + d, leaves);
        }
    }

    /** Street lamp: post with a hanging lantern on an arm. */
    public static void lamp(Canvas c, int x, int y, int z, Dir arm) {
        int g0 = c.groundAt(x, z, y + 8);
        if (g0 > 0) y = g0;
        c.set(x, y, z, "@foundation:wall");
        for (int i = 1; i <= 3; i++) c.set(x, y + i, z, "@trim:fence");
        c.set(x, y + 4, z, "@trim:slab", Canvas.slab(false));
        c.set(x + arm.dx, y + 4, z + arm.dz, "@trim:fence");
        c.set(x + arm.dx, y + 3, z + arm.dz, "@light", Map.of("hanging", "true"));
    }

    /** Circular fountain with a tiered centre column. */
    public static void fountain(Canvas c, int cx, int y, int cz, int r) {
        c.cylinder(cx, y, cz, r, 1, "@path", false, 'y');
        c.ring(cx, y + 1, cz, r, "@accent:wall");
        c.cylinder(cx, y, cz, r - 1, 1, "@foundation", false, 'y');
        c.cylinder(cx, y + 1, cz, r - 1, 1, "water", false, 'y');
        c.ring(cx, y + 1, cz, r - 1, "@accent:slab");
        c.cylinder(cx, y + 1, cz, r - 1, 1, "water", false, 'y');
        for (int i = 1; i <= 3; i++) c.set(cx, y + i, cz, "@column");
        for (Dir d : Dir.values()) c.set(cx + d.dx, y + 3, cz + d.dz, "@accent:stairs", Canvas.stairs(d.opposite(), true));
        c.set(cx, y + 4, cz, "water");
        c.set(cx, y + 1, cz, "@column");
    }

    /** Masonry arch bridge along X from x1 to x2 at deck height y+h, width w centered on z. */
    public static void bridge(Canvas c, int x1, int x2, int y, int z, int w, int h) {
        int lo = Math.min(x1, x2), hi = Math.max(x1, x2);
        int zl = z - w / 2, zh = zl + w - 1;
        double mid = (lo + hi) / 2.0, half = (hi - lo) / 2.0;
        for (int x = lo; x <= hi; x++) {
            double off = Math.abs(x - mid);
            int archY = y + (int) Math.round(Math.sqrt(Math.max(0, half * half - off * off)) * (h - 1) / Math.max(1, half));
            int deck = y + h;
            for (int zz = zl - 1; zz <= zh + 1; zz++) {
                boolean edge = zz == zl - 1 || zz == zh + 1;
                for (int yy = archY; yy < deck; yy++) c.set(x, yy, zz, edge ? "@foundation" : "@wall");
                c.set(x, deck, zz, edge ? "@foundation" : "@path");
            }
            // underside voussoir trim
            c.set(x, archY - 1 >= y ? archY - 1 : archY, zl - 1, "@accent");
            c.set(x, archY - 1 >= y ? archY - 1 : archY, zh + 1, "@accent");
            // railings
            boolean post = (x - lo) % 4 == 0;
            c.trySet(x, deck + 1, zl - 1, post ? "@foundation:wall" : "@foundation:wall", "cobblestone_wall");
            c.trySet(x, deck + 1, zh + 1, post ? "@foundation:wall" : "@foundation:wall", "cobblestone_wall");
            if (post && x != lo && x != hi) {
                c.set(x, deck + 2, zl - 1, "@light");
                c.set(x, deck + 2, zh + 1, "@light");
            }
        }
    }

    /** Row of columns along X (or Z) with bases and capitals; height h. */
    public static void colonnade(Canvas c, int x1, int z1, int x2, int z2, int y, int h, int spacing) {
        boolean alongX = z1 == z2;
        int lo = alongX ? Math.min(x1, x2) : Math.min(z1, z2), hi = alongX ? Math.max(x1, x2) : Math.max(z1, z2);
        for (int a = lo; a <= hi; a += Math.max(2, spacing)) {
            int x = alongX ? a : x1, z = alongX ? z1 : a;
            c.set(x, y, z, "@accent");
            for (int yy = y + 1; yy < y + h - 1; yy++) c.set(x, yy, z, "@column", logAxis('y'));
            c.set(x, y + h - 1, z, "@accent");
            for (Dir d : Dir.values()) {
                c.set(x + d.dx, y, z + d.dz, "@accent:stairs", Canvas.stairs(d.opposite(), false));
                c.set(x + d.dx, y + h - 1, z + d.dz, "@accent:stairs", Canvas.stairs(d.opposite(), true));
            }
        }
        // entablature
        for (int a = lo - 1; a <= hi + 1; a++) {
            int x = alongX ? a : x1, z = alongX ? z1 : a;
            c.set(x, y + h, z, "@trim");
            c.set(x, y + h + 1, z, "@wall");
        }
    }

    /** Multi-tier pagoda; base square size s at (x,z). */
    public static int pagoda(Canvas c, int x, int y, int z, int s, int tiers) {
        c.fill(x - 2, y, z - 2, x + s + 1, y, z + s + 1, "@foundation");
        c.fill(x - 1, y + 1, z - 1, x + s, y + 1, z + s, "@foundation:slab[type=bottom]");
        int yy = y + 1;
        int size = s;
        int ox = x, oz = z;
        for (int t = 0; t < tiers && size >= 3; t++) {
            int x2 = ox + size - 1, z2 = oz + size - 1;
            int wallH = t == 0 ? 5 : 4;
            c.fill(ox, yy, oz, x2, yy, z2, "@floor");
            c.walls(ox + 1, yy + 1, oz + 1, x2 - 1, yy + wallH - 1, z2 - 1, "@wall");
            // posts at corners and mid points, beams on top
            int[] pp = posts(size, 3);
            for (Dir d : Dir.values()) for (int p : pp) {
                int[] q = side(d, ox, oz, x2, z2, (d.isX() ? oz : ox) + p, 0);
                for (int k = yy + 1; k < yy + wallH; k++) c.set(q[0], k, q[1], "@column", logAxis('y'));
            }
            c.walls(ox, yy + wallH, oz, x2, yy + wallH, z2, "@trim");
            // veranda railing on ground tier, windows on others
            if (t == 0) {
                for (Dir d : Dir.values()) {
                    int mid = d.isX() ? (oz + z2) / 2 : (ox + x2) / 2;
                    int[] door = side(d, ox + 1, oz + 1, x2 - 1, z2 - 1, mid, 0);
                    c.air(door[0], yy + 1, door[1]); c.air(door[0], yy + 2, door[1]);
                }
            } else {
                for (Dir d : Dir.values()) {
                    int mid = d.isX() ? (oz + z2) / 2 : (ox + x2) / 2;
                    int[] w = side(d, ox + 1, oz + 1, x2 - 1, z2 - 1, mid, 0);
                    c.set(w[0], yy + 2, w[1], "@window");
                }
            }
            Roofs.Spec r = new Roofs.Spec();
            r.x1 = ox; r.z1 = oz; r.x2 = x2; r.z2 = z2; r.y = yy + wallH + 1;
            r.type = Roofs.Type.HIP; r.overhang = 2; r.flare = true; r.trim = null; r.pitch = 1;
            Roofs.build(c, r);
            // the next tier rises from the roof
            int roofRise = 2;
            yy = yy + wallH + 1 + roofRise - 1;
            ox += 1; oz += 1; size -= 2;
            if (size < 3) break;
            c.fill(ox, yy, oz, ox + size - 1, yy, oz + size - 1, "@roof:full");
        }
        int cx = x + s / 2, cz = z + s / 2;
        int top = yy + 4;
        for (int k = yy; k < top + 3; k++) {
            if (!c.has(cx, k, cz) || k > yy + 2) c.set(cx, k, cz, k % 2 == 0 ? "@metal" : "@trim:fence");
        }
        c.set(cx, top + 3, cz, "lightning_rod");
        return top + 3;
    }
}
