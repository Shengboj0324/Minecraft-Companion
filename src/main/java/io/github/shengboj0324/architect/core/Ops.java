package io.github.shengboj0324.architect.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Interprets the JSON building operations Claude emits (see prompts/system.md for the reference given to the model).
 * Each op is applied to the canvas; failures are collected per op so the model can fix them on the next turn.
 */
public final class Ops {

    public record Result(int applied, int failed, List<String> errors, long blocksBefore, long blocksAfter, long clipped) {
        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append("applied ").append(applied).append(" op(s)");
            if (failed > 0) sb.append(", ").append(failed).append(" failed");
            sb.append("; model now has ").append(blocksAfter).append(" cells (").append(blocksAfter - blocksBefore >= 0 ? "+" : "").append(blocksAfter - blocksBefore).append(")");
            if (clipped > 0) sb.append("; WARNING ").append(clipped).append(" blocks fell outside the declared envelope and were dropped");
            for (String e : errors) sb.append("\n  - ").append(e);
            return sb.toString();
        }
    }

    private final Canvas c;

    private final Shapes shapes;

    public Ops(Canvas c) { this.c = c; this.shapes = new Shapes(c); }

    public Result apply(JsonArray ops) {
        VoxelModel m = c.model();
        long before = m.size();
        m.resetCounters();
        m.beginBatch();
        int ok = 0, bad = 0;
        List<String> errors = new ArrayList<>();
        try {
            for (int i = 0; i < ops.size(); i++) {
                JsonElement el = ops.get(i);
                String name = "?";
                try {
                    if (!el.isJsonObject()) throw new IllegalArgumentException("op must be an object");
                    JsonObject o = el.getAsJsonObject();
                    name = str(o, "op", null);
                    if (name == null) throw new IllegalArgumentException("missing \"op\"");
                    applyOne(name.toLowerCase(Locale.ROOT), o);
                    ok++;
                } catch (RuntimeException e) {
                    bad++;
                    if (errors.size() < 25) errors.add("op #" + i + " (" + name + "): " + e.getMessage());
                }
            }
        } finally {
            m.endBatch();
        }
        if (m.overBudgetCount() > 0) errors.add("block budget reached: " + m.overBudgetCount() + " blocks were not placed; simplify or reduce scale");
        return new Result(ok, bad, errors, before, m.size(), m.clippedCount());
    }

    private void applyOne(String op, JsonObject o) {
        switch (op) {
            case "fill", "box", "frame", "walls", "hollow" -> {
                int[] a = vec(o, "from"), b = vec(o, "to");
                String spec = str(o, "block", null);
                String shape = op.equals("frame") || op.equals("walls") || op.equals("hollow") ? op
                        : str(o, "shape", bool(o, "hollow", false) ? "hollow" : "solid");
                if (spec == null || spec.equals("air")) { c.carve(a[0], a[1], a[2], b[0], b[1], b[2]); return; }
                withMode(o, () -> {
                    switch (shape) {
                        case "hollow" -> c.hollow(a[0], a[1], a[2], b[0], b[1], b[2], spec);
                        case "walls" -> c.walls(a[0], a[1], a[2], b[0], b[1], b[2], spec);
                        case "frame", "edges", "outline" -> c.frame(a[0], a[1], a[2], b[0], b[1], b[2], spec);
                        default -> c.fill(a[0], a[1], a[2], b[0], b[1], b[2], spec);
                    }
                });
            }
            case "carve", "clear" -> {
                int[] a = vec(o, "from"), b = vec(o, "to");
                c.carve(a[0], a[1], a[2], b[0], b[1], b[2]);
            }
            case "block", "set" -> {
                int[] p = vec(o, "at");
                c.set(p[0], p[1], p[2], req(o, "block"));
            }
            case "blocks" -> {
                JsonArray list = o.getAsJsonArray("list");
                if (list == null) throw new IllegalArgumentException("'blocks' needs \"list\": [[x,y,z,\"block\"], ...]");
                for (JsonElement e : list) {
                    JsonArray t = e.getAsJsonArray();
                    c.set(t.get(0).getAsInt(), t.get(1).getAsInt(), t.get(2).getAsInt(), t.get(3).getAsString());
                }
            }
            case "line" -> {
                int[] a = vec(o, "from"), b = vec(o, "to");
                c.line(a[0], a[1], a[2], b[0], b[1], b[2], req(o, "block"), integer(o, "thickness", 1));
            }
            case "cylinder" -> {
                double[] p = dvec(o, "center");
                withMode(o, () -> c.cylinder(p[0], (int) p[1], p[2], num(o, "radius", 3), integer(o, "height", 1), req(o, "block"),
                        bool(o, "hollow", false), str(o, "axis", "y").charAt(0)));
            }
            case "ring" -> {
                double[] p = dvec(o, "center");
                c.ring(p[0], (int) p[1], p[2], num(o, "radius", 3), req(o, "block"));
            }
            case "sphere", "ellipsoid", "dome" -> {
                double[] p = dvec(o, "center");
                double rx, ry, rz;
                if (o.has("radii")) { double[] r = dvec(o, "radii"); rx = r[0]; ry = r[1]; rz = r[2]; }
                else { rx = ry = rz = num(o, "radius", 3); }
                String half = op.equals("dome") ? "top" : str(o, "half", null);
                withMode(o, () -> c.ellipsoid(p[0], (int) p[1], p[2], rx, ry, rz, req(o, "block"), bool(o, "hollow", op.equals("dome")), half));
            }
            case "cone" -> {
                double[] p = dvec(o, "center");
                c.cone(p[0], (int) p[1], p[2], num(o, "radius", 3), integer(o, "height", 5), req(o, "block"), bool(o, "hollow", true));
            }
            case "cone_roof", "conical_roof" -> {
                double[] p = dvec(o, "center");
                withRoles(o, () -> Modules.cone(c, p[0], (int) p[1], p[2], num(o, "radius", 3), integer(o, "height", 6)));
            }
            case "pyramid" -> {
                int[] a = vec(o, "from"), b = vec(o, "to");
                c.pyramid(a[0], a[1], a[2], b[0], b[2], req(o, "block"), bool(o, "hollow", false));
            }
            case "roof" -> {
                int[] a = vec(o, "from"), b = vec(o, "to");
                Roofs.Spec r = new Roofs.Spec();
                r.x1 = a[0]; r.z1 = a[2]; r.x2 = b[0]; r.z2 = b[2]; r.y = a[1];
                r.type = Roofs.parseType(str(o, "type", "gable"));
                r.axis = str(o, "axis", Math.abs(b[0] - a[0]) >= Math.abs(b[2] - a[2]) ? "x" : "z").charAt(0);
                r.pitch = num(o, "pitch", 1);
                r.overhang = integer(o, "overhang", 1);
                r.material = str(o, "material", "@roof");
                r.trim = str(o, "trim", "@roof_trim");
                if (o.has("gable") && o.get("gable").isJsonNull()) r.gable = null; else r.gable = str(o, "gable", "@wall");
                r.flare = bool(o, "flare", false);
                r.finial = bool(o, "finial", true);
                Dir hi = Dir.parse(str(o, "high_side", "south"));
                if (hi != null) r.shedHigh = hi;
                Roofs.build(c, r);
            }
            case "arch" -> {
                int[] a = vec(o, "from"), b = vec(o, "to");
                char axis = a[2] == b[2] ? 'x' : 'z';
                if (a[0] != b[0] && a[2] != b[2]) throw new IllegalArgumentException("arch 'from' and 'to' must share x or z");
                int a1 = axis == 'x' ? a[0] : a[2], a2 = axis == 'x' ? b[0] : b[2];
                int plane = axis == 'x' ? a[2] : a[0];
                c.arch(axis, a1, a2, a[1], integer(o, "height", 2), plane, integer(o, "depth", 1), str(o, "style", "round"),
                        str(o, "block", null), integer(o, "thickness", 1), bool(o, "carve", true));
            }
            case "windows", "window_row" -> windows(o);
            case "door" -> {
                int[] p = vec(o, "at");
                Dir f = dir(o, "facing", Dir.NORTH);
                String mat = str(o, "block", "@wall_alt");
                String spec = mat.endsWith("_door") || mat.contains(":door") ? mat : mat + ":door";
                c.set(p[0], p[1], p[2], spec, Map.of("facing", f.key(), "half", "lower", "hinge", str(o, "hinge", "left")));
                c.set(p[0], p[1] + 1, p[2], spec, Map.of("facing", f.key(), "half", "upper", "hinge", str(o, "hinge", "left")));
            }
            case "staircase", "stairs" -> {
                int[] p = vec(o, "from");
                Dir d = dir(o, "direction", Dir.NORTH);
                int steps = integer(o, "steps", 4), width = integer(o, "width", 1);
                String mat = str(o, "block", "@foundation");
                boolean solid = bool(o, "solid", true);
                Dir side = d.cw();
                for (int i = 0; i < steps; i++) for (int w = 0; w < width; w++) {
                    int x = p[0] + d.dx * i + side.dx * w, z = p[2] + d.dz * i + side.dz * w, y = p[1] + i;
                    c.set(x, y, z, mat + (mat.contains(":") || mat.endsWith("_stairs") ? "" : ":stairs"), Canvas.stairs(d, false));
                    if (solid) for (int yy = p[1]; yy < y; yy++) c.set(x, yy, z, mat.contains(":") ? mat.substring(0, mat.indexOf(':')) + ":full" : mat + ":full");
                }
            }
            case "spiral_stair" -> {
                double[] p = dvec(o, "center");
                int y0 = (int) p[1], y1 = integer(o, "to_y", y0 + 8);
                double r = num(o, "radius", 2);
                String mat = str(o, "block", "@floor");
                int cx = (int) Math.floor(p[0]), cz = (int) Math.floor(p[2]);
                for (int y = y0; y <= y1; y++) c.set(cx, y, cz, "@column", Map.of("axis", "y"));
                int[][] ring = {{0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}};
                int k = 0;
                for (int y = y0; y <= y1; y++, k++) {
                    int[] q = ring[k % 8];
                    for (int s = 1; s <= Math.max(1, (int) r); s++) {
                        c.set(cx + q[0] * s, y, cz + q[1] * s, mat + ":slab", Canvas.slab(k % 2 == 1));
                    }
                }
            }
            case "replace", "texture" -> {
                int[] a = vec(o, "from"), b = vec(o, "to");
                c.replace(a[0], a[1], a[2], b[0], b[1], b[2], str(o, "find", null), req(o, "block"));
            }
            case "copy", "repeat", "array" -> {
                int[] a = vec(o, "from"), b = vec(o, "to"), off = vec(o, "offset");
                c.copy(a[0], a[1], a[2], b[0], b[1], b[2], off[0], off[1], off[2], integer(o, "times", 1), integer(o, "rotate", 0));
            }
            case "mirror", "symmetry" -> {
                String axis = str(o, "axis", "x");
                double at = num(o, "at", (axis.equals("x") ? c.model().width() - 1 : c.model().depth() - 1) / 2.0);
                int[] region = null;
                if (o.has("from") && o.has("to")) {
                    int[] a = vec(o, "from"), b = vec(o, "to");
                    region = new int[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]), Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])};
                }
                c.mirror(axis.charAt(0), at, region);
            }
            case "module" -> module(o);
            case "patch", "repair", "fill_holes" -> {
                int n = Renovation.patch(c, c.existing(), box(o), str(o, "block", null));
                if (n == 0) throw new IllegalArgumentException("no holes found to patch in that region (render with highlight \"damage\" to see them)");
            }
            case "drain" -> {
                if (c.existing() == null) throw new IllegalArgumentException("drain works on an existing build being renovated");
                int[] b = box(o);
                if (b == null) { int[] mb = c.model().bounds(); b = mb; }
                Renovation.drain(c, c.existing(), b);
                if (bool(o, "seal", true)) Renovation.sealLeaks(c, c.existing(), str(o, "seal_block", "glass"));
            }
            case "seal", "seal_leaks" -> Renovation.sealLeaks(c, c.existing(), str(o, "block", "glass"));
            case "light_up", "lighting", "light" -> Renovation.lightUp(c, c.existing(), box(o), integer(o, "level", 8), str(o, "fixture", null), str(o, "floor_light", null));
            case "furnish", "furniture" -> {
                int[] a = vec(o, "from"), b = vec(o, "to");
                int fy = o.has("floor_y") ? integer(o, "floor_y", a[1]) : Math.min(a[1], b[1]);
                Furniture.furnish(c, c.existing(), a[0], a[2], b[0], b[2], fy, str(o, "room", "auto"), str(o, "wood", null), str(o, "color", null));
            }
            case "elevator", "lift" -> {
                int[] at = vec(o, "at");
                int[] floors;
                if (o.has("floors") && o.get("floors").isJsonArray()) {
                    JsonArray fa = o.getAsJsonArray("floors");
                    floors = new int[fa.size()];
                    for (int i = 0; i < floors.length; i++) floors[i] = fa.get(i).getAsInt();
                } else floors = new int[]{at[1], integer(o, "to_y", at[1])};
                Elevators.build(c, str(o, "type", "bubble").toLowerCase(Locale.ROOT), at[0], at[2], floors, dir(o, "door", Dir.NORTH), str(o, "wall", null),
                        str(o, "wood", null), bool(o, "down", false));
            }
            case "shape", "implicit" -> withMode(o, () -> shapes.shape(o));
            case "revolve", "lathe" -> withMode(o, () -> shapes.revolve(o));
            case "polygon", "prism", "extrude" -> withMode(o, () -> shapes.polygon(o));
            case "loft", "tower_loft" -> withMode(o, () -> shapes.loft(o));
            case "lines", "polyline" -> withMode(o, () -> shapes.lines(o));
            case "truss", "lattice" -> withMode(o, () -> shapes.truss(o));
            case "facade_grid", "curtain_wall_grid" -> withMode(o, () -> shapes.facadeGrid(o));
            case "house", "tower", "round_tower", "square_tower", "curtain_wall", "wall_segment", "gatehouse", "tree", "lamp", "fountain", "bridge", "colonnade", "pagoda" -> {
                o.addProperty("type", op);
                module(o);
            }
            default -> throw new IllegalArgumentException("unknown op '" + op + "'");
        }
    }

    private void module(JsonObject o) {
        String type = req(o, "type").toLowerCase(Locale.ROOT);
        withRoles(o, () -> {
            switch (type) {
                case "house" -> {
                    int[] p = vec(o, "at");
                    Modules.HouseOpts h = new Modules.HouseOpts();
                    h.floors = integer(o, "floors", 2);
                    h.floorHeight = integer(o, "floor_height", 4);
                    h.roof = Roofs.parseType(str(o, "roof", "gable"));
                    h.pitch = num(o, "pitch", 1);
                    h.chimney = bool(o, "chimney", true);
                    h.jetty = bool(o, "jetty", true);
                    h.timberFrame = bool(o, "timber_frame", true);
                    h.shutters = bool(o, "shutters", false);
                    h.front = dir(o, "front", Dir.NORTH);
                    Modules.house(c, p[0], p[1], p[2], integer(o, "width", 9), integer(o, "depth", 7), h);
                }
                case "tower", "round_tower" -> {
                    double[] p = dvec(o, "center");
                    Modules.TowerOpts t = new Modules.TowerOpts();
                    t.roof = str(o, "roof", "cone");
                    t.floorEvery = integer(o, "floor_every", 6);
                    t.door = bool(o, "door", true);
                    t.front = dir(o, "front", Dir.NORTH);
                    t.roofPitch = num(o, "roof_pitch", 2.2);
                    t.bands = bool(o, "bands", true);
                    t.plinth = bool(o, "plinth", true);
                    Modules.roundTower(c, p[0], (int) p[1], p[2], num(o, "radius", 4), integer(o, "height", 16), t);
                }
                case "square_tower" -> {
                    int[] p = vec(o, "at");
                    Modules.squareTower(c, p[0], p[1], p[2], integer(o, "size", 7), integer(o, "height", 16), str(o, "roof", "crenellated"),
                            bool(o, "door", true), dir(o, "front", Dir.NORTH));
                }
                case "curtain_wall", "wall_segment" -> {
                    int[] a = vec(o, "from"), b = vec(o, "to");
                    if (a[0] != b[0] && a[2] != b[2]) throw new IllegalArgumentException("curtain_wall must be axis-aligned (share x or z); split diagonal walls into segments");
                    Modules.curtainWall(c, a[0], a[2], b[0], b[2], a[1], integer(o, "height", 9), integer(o, "thickness", 3),
                            dir(o, "outside", a[2] == b[2] ? Dir.NORTH : Dir.WEST), integer(o, "buttress_every", 6));
                }
                case "gatehouse" -> {
                    int[] p = vec(o, "at");
                    Modules.gatehouse(c, p[0], p[1], p[2], integer(o, "tower_size", 5), integer(o, "height", 9), integer(o, "passage", 3));
                }
                case "tree" -> {
                    int[] p = vec(o, "at");
                    Modules.tree(c, p[0], p[1], p[2], str(o, "kind", "oak"), integer(o, "size", 2));
                }
                case "lamp" -> {
                    int[] p = vec(o, "at");
                    Modules.lamp(c, p[0], p[1], p[2], dir(o, "arm", Dir.EAST));
                }
                case "fountain" -> {
                    int[] p = vec(o, "at");
                    Modules.fountain(c, p[0], p[1], p[2], integer(o, "radius", 4));
                }
                case "bridge" -> {
                    int[] a = vec(o, "from"), b = vec(o, "to");
                    Modules.bridge(c, a[0], b[0], a[1], a[2], integer(o, "width", 5), integer(o, "height", 6));
                }
                case "colonnade" -> {
                    int[] a = vec(o, "from"), b = vec(o, "to");
                    Modules.colonnade(c, a[0], a[2], b[0], b[2], a[1], integer(o, "height", 8), integer(o, "spacing", 3));
                }
                case "pagoda" -> {
                    int[] p = vec(o, "at");
                    Modules.pagoda(c, p[0], p[1], p[2], integer(o, "size", 11), integer(o, "tiers", 4));
                }
                default -> throw new IllegalArgumentException("unknown module type '" + type + "'");
            }
        });
    }

    /** Regularly spaced windows cut into a flat facade region (one coordinate of from/to must be equal). */
    private void windows(JsonObject o) {
        int[] a = vec(o, "from"), b = vec(o, "to");
        boolean planeX = a[0] == b[0]; // facade lies in a YZ plane
        if (!planeX && a[2] != b[2]) throw new IllegalArgumentException("windows: from/to must define a flat facade (same x or same z)");
        Dir facing = dir(o, "facing", planeX ? Dir.EAST : Dir.NORTH);
        int w = integer(o, "width", 1), h = integer(o, "height", 2), sp = integer(o, "spacing", 2);
        int inset = integer(o, "inset", 0);
        String glass = str(o, "block", "@window");
        String frame = str(o, "frame", null);
        boolean sill = bool(o, "sill", true);
        String style = str(o, "style", "rect");
        int lo = planeX ? Math.min(a[2], b[2]) : Math.min(a[0], b[0]), hi = planeX ? Math.max(a[2], b[2]) : Math.max(a[0], b[0]);
        int y0 = Math.min(a[1], b[1]);
        int plane = planeX ? a[0] : a[2];
        int period = w + sp;
        int count = Math.max(1, (hi - lo + 1 + sp) / period);
        int used = count * period - sp;
        int start = lo + (hi - lo + 1 - used) / 2;
        Dir in = facing.opposite();
        for (int n = 0; n < count; n++) {
            int s0 = start + n * period;
            for (int k = 0; k < w; k++) {
                int along = s0 + k;
                int top = h;
                if (style.equals("arched") && w >= 2) top = h; // arch head added below
                for (int yy = 0; yy < top; yy++) {
                    for (int d = 0; d < inset; d++) put(planeX, plane + in.dx * d + (planeX ? 0 : 0), along, y0 + yy, planeX ? in.dx : in.dz, d, null);
                    put(planeX, plane, along, y0 + yy, planeX ? in.dx : in.dz, inset, glass);
                }
                if (sill) {
                    int sx = planeX ? plane + facing.dx : along, sz = planeX ? along : plane + facing.dz;
                    c.set(sx, y0 - 1, sz, "@trim:stairs", Canvas.stairs(in, true));
                }
            }
            if (frame != null) {
                for (int yy = -1; yy <= h; yy++) {
                    for (int side : new int[]{s0 - 1, s0 + w}) {
                        int fx = planeX ? plane + facing.dx : side, fz = planeX ? side : plane + facing.dz;
                        if (yy >= 0 && yy < h) c.set(fx, y0 + yy, fz, frame);
                    }
                }
                for (int k = -1; k <= w; k++) {
                    int along = s0 + k;
                    int fx = planeX ? plane + facing.dx : along, fz = planeX ? along : plane + facing.dz;
                    c.set(fx, y0 + h, fz, frame.contains(":") ? frame : frame + ":slab", Canvas.slab(true));
                }
            }
            if (style.equals("arched") || style.equals("pointed")) {
                // rounded head: glass continues one row, framed by upside-down stairs at the corners
                int yy = y0 + h;
                for (int k = 0; k < w; k++) put(planeX, plane, s0 + k, yy, planeX ? in.dx : in.dz, inset, glass);
                if (w >= 2) {
                    int lx = planeX ? plane : s0, lz = planeX ? s0 : plane;
                    int rx = planeX ? plane : s0 + w - 1, rz = planeX ? s0 + w - 1 : plane;
                    Dir lf = planeX ? Dir.SOUTH : Dir.EAST;
                    c.set(lx, yy, lz, "@wall:stairs", Canvas.stairs(lf.opposite(), true));
                    c.set(rx, yy, rz, "@wall:stairs", Canvas.stairs(lf, true));
                }
                if (style.equals("pointed") && w >= 3) {
                    for (int k = 1; k < w - 1; k++) put(planeX, plane, s0 + k, yy + 1, planeX ? in.dx : in.dz, inset, glass);
                }
            }
        }
    }

    private void put(boolean planeX, int plane, int along, int y, int inStep, int depth, String spec) {
        int x = planeX ? plane + inStep * depth : along;
        int z = planeX ? along : plane + inStep * depth;
        if (spec == null) c.air(x, y, z); else c.set(x, y, z, spec);
    }

    // ---------------------------------------------------------------- roles override per op

    /** Ops may carry "roles": {"wall": "stone_bricks", ...} to locally restyle a module. */
    private void withRoles(JsonObject o, Runnable r) {
        if (!o.has("roles") || !o.get("roles").isJsonObject()) { r.run(); return; }
        Materials mat = c.materials();
        Style prev = mat.style();
        Map<String, List<Style.Weighted>> over = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("roles").entrySet()) over.put(e.getKey(), StyleLibrary.parseWeighted(e.getValue()));
        mat.setStyle(prev.withRoles(over));
        try { r.run(); } finally { mat.setStyle(prev); }
    }

    private void withMode(JsonObject o, Runnable r) {
        Canvas.Mode prev = c.mode();
        if ("keep".equals(str(o, "mode", "replace"))) c.mode(Canvas.Mode.KEEP);
        try { withRoles(o, r); } finally { c.mode(prev); }
    }

    // ---------------------------------------------------------------- json helpers

    static String str(JsonObject o, String k, String def) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? def : e.getAsString();
    }

    static String req(JsonObject o, String k) {
        String s = str(o, k, null);
        if (s == null || s.isBlank()) throw new IllegalArgumentException("missing \"" + k + "\"");
        return s;
    }

    static boolean bool(JsonObject o, String k, boolean def) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? def : e.getAsBoolean();
    }

    static int integer(JsonObject o, String k, int def) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? def : (int) Math.round(e.getAsDouble());
    }

    static double num(JsonObject o, String k, double def) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? def : e.getAsDouble();
    }

    static Dir dir(JsonObject o, String k, Dir def) {
        String s = str(o, k, null);
        if (s == null) return def;
        Dir d = Dir.parse(s);
        if (d == null) throw new IllegalArgumentException("bad direction '" + s + "' for " + k + " (north|south|east|west)");
        return d;
    }

    /** Optional region {"from":[..],"to":[..]} as {x1,y1,z1,x2,y2,z2}, or null for the whole model. */
    static int[] box(JsonObject o) {
        if (!o.has("from") || !o.has("to")) return null;
        int[] a = vec(o, "from"), b = vec(o, "to");
        return new int[]{a[0], a[1], a[2], b[0], b[1], b[2]};
    }

    static int[] vec(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonArray()) throw new IllegalArgumentException("missing \"" + k + "\": [x,y,z]");
        JsonArray a = e.getAsJsonArray();
        if (a.size() < 3) throw new IllegalArgumentException("\"" + k + "\" must be [x,y,z]");
        return new int[]{(int) Math.round(a.get(0).getAsDouble()), (int) Math.round(a.get(1).getAsDouble()), (int) Math.round(a.get(2).getAsDouble())};
    }

    static double[] dvec(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonArray()) throw new IllegalArgumentException("missing \"" + k + "\": [x,y,z]");
        JsonArray a = e.getAsJsonArray();
        if (a.size() < 3) throw new IllegalArgumentException("\"" + k + "\" must be [x,y,z]");
        return new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
    }
}
