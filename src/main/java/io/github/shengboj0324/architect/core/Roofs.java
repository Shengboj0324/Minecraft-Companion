package io.github.shengboj0324.architect.core;

import java.util.Locale;

/**
 * Roof generator. All roofs are computed from a per-cell "ring index" (distance to the eave edge) so stairs always
 * face up-slope, ridges get slabs, and stair corners resolve to proper inner/outer shapes via {@link Connectivity}.
 *
 * <p>Footprint (x1..x2, z1..z2) is the WALL footprint; overhang extends past it. {@code y} is the height of the roof
 * course sitting directly on the wall line (walls should top out at y-1); overhanging eaves hang lower.</p>
 */
public final class Roofs {

    public enum Type { GABLE, HIP, PYRAMID, SHED, FLAT, SPIRE }

    public static final class Spec {
        public int x1, z1, x2, z2, y;
        public Type type = Type.GABLE;
        public char axis = 'x';          // ridge direction for gable/shed
        public String material = "@roof";
        public String trim = null;       // gable-edge rafters + ridge cap
        public String gable = "@wall";   // gable triangle infill (null = leave open)
        public int overhang = 1;
        public double pitch = 1;         // 0.5 shallow (slabs), 1 = 45deg, 2 = steep, 3 = very steep
        public boolean flare = false;    // raise hip corners (east-asian eaves)
        public boolean finial = true;    // spire/pyramid tip decoration
        public Dir shedHigh = Dir.SOUTH; // shed roof: side that is high
        public boolean solid = false;    // fill below the roof surface (for very large roofs seen from below this is ugly; default hollow)
    }

    private Roofs() {}

    public static Type parseType(String s) {
        if (s == null) return Type.GABLE;
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "hip", "hipped" -> Type.HIP;
            case "pyramid", "pyramidal" -> Type.PYRAMID;
            case "shed", "lean-to", "skillion", "mono" -> Type.SHED;
            case "flat" -> Type.FLAT;
            case "spire", "steeple" -> Type.SPIRE;
            default -> Type.GABLE;
        };
    }

    public static void build(Canvas c, Spec s) {
        int x1 = Math.min(s.x1, s.x2), x2 = Math.max(s.x1, s.x2), z1 = Math.min(s.z1, s.z2), z2 = Math.max(s.z1, s.z2);
        if (s.type == Type.FLAT) { flat(c, s, x1, z1, x2, z2); return; }
        Type type = s.type;
        double pitch = s.pitch;
        int o = Math.max(0, s.overhang);
        if (type == Type.SPIRE) { pitch = Math.max(pitch, 2.5); type = Type.HIP; }
        if (type == Type.PYRAMID) type = Type.HIP;

        int ax = x1 - o, bx = x2 + o, az = z1 - o, bz = z2 + o;
        boolean along = s.axis == 'x'; // ridge parallel to x => slopes face north/south
        boolean shallow = pitch < 1;
        int stepRise = (int) Math.ceil(Math.max(1, pitch)); // largest rise of any ring (fractional pitches alternate)
        int topY = Integer.MIN_VALUE;

        for (int x = ax; x <= bx; x++) {
            for (int z = az; z <= bz; z++) {
                int dW = x - ax, dE = bx - x, dN = z - az, dS = bz - z;
                int ring;
                Dir facing;
                boolean ridge;
                if (type == Type.SHED) {
                    Dir hi = s.shedHigh;
                    ring = switch (hi) { case SOUTH -> dN; case NORTH -> dS; case EAST -> dW; default -> dE; };
                    facing = hi;
                    ridge = false;
                } else if (type == Type.GABLE) {
                    if (along) { ring = Math.min(dN, dS); facing = dN < dS ? Dir.SOUTH : Dir.NORTH; ridge = dN == dS; }
                    else { ring = Math.min(dW, dE); facing = dW < dE ? Dir.EAST : Dir.WEST; ridge = dW == dE; }
                } else { // HIP
                    int mz = Math.min(dN, dS), mx = Math.min(dW, dE);
                    ring = Math.min(mz, mx);
                    // ridge when the remaining strip is one block wide in the short direction
                    boolean zSmaller = mz <= mx;
                    if (zSmaller) { facing = dN < dS ? Dir.SOUTH : Dir.NORTH; ridge = dN == dS; }
                    else { facing = dW < dE ? Dir.EAST : Dir.WEST; ridge = dW == dE; }
                    if (mz == mx && dN == dS && dW == dE) ridge = true; // apex of odd square
                }
                int y = placeCell(c, s, x, z, ring, o, facing, ridge, Math.max(1, pitch), shallow, isEdge(s, x, ax, bx, z, az, bz, along, type));
                if (y > topY) topY = y;
                if (s.flare && type == Type.HIP && ring == 0 && o > 0 && isCorner(x, z, ax, bx, az, bz)) {
                    c.set(x, y + 1, z, s.material + ":slab", Canvas.slab(false));
                }
            }
        }

        // gable infill on the wall planes, and optional solid core
        if (s.gable != null && (type == Type.GABLE || type == Type.SHED)) {
            for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
                boolean plane = (type == Type.SHED)
                        ? (s.shedHigh.isX() ? (z == z1 || z == z2) : (x == x1 || x == x2))
                        : (along ? (x == x1 || x == x2) : (z == z1 || z == z2));
                if (!plane && !s.solid) continue;
                int surf = lowestRoofY(c, x, z, s.y, s.y + (Math.max(bx - ax, bz - az) + 2) * Math.max(1, stepRise));
                for (int y = s.y; y < surf; y++) c.set(x, y, z, plane ? s.gable : s.material + ":full");
            }
        }
        if (s.finial && type == Type.HIP && (s.type == Type.SPIRE || s.type == Type.PYRAMID) && topY > Integer.MIN_VALUE) {
            int cx = (ax + bx) / 2, cz = (az + bz) / 2;
            if ((bx - ax) % 2 == 0 && (bz - az) % 2 == 0) {
                String trim = s.trim != null ? s.trim : s.material;
                c.trySet(cx, topY + 1, cz, trim + ":wall", trim + ":fence", "cobblestone_wall");
                c.set(cx, topY + 2, cz, "lightning_rod");
            }
        }
    }

    private static boolean isCorner(int x, int z, int ax, int bx, int az, int bz) {
        return (x == ax || x == bx) && (z == az || z == bz);
    }

    private static boolean isEdge(Spec s, int x, int ax, int bx, int z, int az, int bz, boolean along, Type type) {
        if (type != Type.GABLE && type != Type.SHED) return false;
        if (type == Type.SHED) return s.shedHigh.isX() ? (z == az || z == bz) : (x == ax || x == bx);
        return along ? (x == ax || x == bx) : (z == az || z == bz);
    }

    /** Places the roof cell(s) for one column; returns the top y used. */
    private static int placeCell(Canvas c, Spec s, int x, int z, int ring, int o, Dir facing, boolean ridge, double pitch, boolean shallow, boolean edge) {
        String mat = (edge && s.trim != null) ? s.trim : s.material;
        if (shallow) {
            // half-block rise per ring, alternating bottom/top slabs
            int halfSteps = ring - o; // relative to the wall line
            int y = s.y + Math.floorDiv(halfSteps, 2);
            boolean top = Math.floorMod(halfSteps, 2) == 1;
            if (ridge && s.trim != null) mat = s.trim;
            c.set(x, y, z, mat + ":slab", Canvas.slab(top));
            return y;
        }
        // ring k (counted from the wall line) rises floor(k * pitch): pitch 1.5 alternates rises of 1 and 2
        int rk = ring - o;
        int base = s.y + (int) Math.floor(rk * pitch + 1e-9);
        int stepRise = s.y + (int) Math.floor((rk + 1) * pitch + 1e-9) - base;
        int y = base + stepRise - 1;
        // steep roofs: start one block lower so each ring overlaps the previous one face-to-face (no diagonal-only joins)
        for (int k = (ring > 0 && stepRise > 1) ? base - 1 : base; k < y; k++) c.set(x, k, z, mat + ":full");
        if (ridge) {
            String cap = s.trim != null ? s.trim : mat;
            if (stepRise == 1 && ring > 0 && !c.has(x, y - 1, z)) c.set(x, y - 1, z, s.material + ":full");
            if (stepRise > 1) c.set(x, y, z, mat + ":full");
            c.set(x, stepRise > 1 ? y + 1 : y, z, cap + ":slab", Canvas.slab(false));
            return stepRise > 1 ? y + 1 : y;
        }
        c.set(x, y, z, mat + ":stairs", Canvas.stairs(facing, false));
        // back every inner ring so it touches the ring below face-to-face (structural connectivity, and outer-corner
        // notches never reveal the void). Under the eaves use upside-down stairs, which read as rafters/fascia.
        if (ring > 0 && stepRise == 1 && !c.has(x, y - 1, z)) {
            if (ring > o) c.set(x, y - 1, z, s.material + ":full");
            else c.set(x, y - 1, z, mat + ":stairs", Canvas.stairs(facing.opposite(), true));
        }
        return y;
    }

    private static int lowestRoofY(Canvas c, int x, int z, int from, int to) {
        for (int y = from; y <= to; y++) if (c.has(x, y, z)) return y;
        return from;
    }

    private static void flat(Canvas c, Spec s, int x1, int z1, int x2, int z2) {
        int o = Math.max(0, s.overhang);
        for (int x = x1 - o; x <= x2 + o; x++) for (int z = z1 - o; z <= z2 + o; z++) {
            boolean rim = x < x1 || x > x2 || z < z1 || z > z2;
            if (rim) c.set(x, s.y, z, (s.trim != null ? s.trim : s.material) + ":slab", Canvas.slab(false));
            else c.set(x, s.y, z, s.material + ":full");
        }
        // parapet along the wall line (only when no overhang); walls or slabs read as a finished edge
        if (o == 0) {
            String par = s.trim != null ? s.trim : s.material;
            for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
                if (x == x1 || x == x2 || z == z1 || z == z2) {
                    if (!c.trySet(x, s.y + 1, z, par + ":wall")) c.set(x, s.y + 1, z, par + ":slab", Canvas.slab(false));
                }
            }
        }
    }
}
