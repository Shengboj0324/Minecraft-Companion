package io.github.shengboj0324.architect.core;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads an existing build the way an experienced builder walking through it would: where the floors are, which
 * rooms there are and how they are lit and furnished, how the floors connect, what is damaged (holes blown through
 * walls, floors and roofs, loose floating fragments, water standing inside), where mobs can spawn, and what the
 * palette and style are. The report is given to Claude together with rendered views, and the marked cells drive the
 * highlight renders and the repair ops.
 */
public final class StructureAnalyzer {

    /** A room: connected walkable floor space under a roof, on one floor. */
    public record Room(int id, int floorY, int x1, int z1, int x2, int z2, int area, int dark, int dim, int furniture, int flooded, int doors) {
        public String box() { return "[" + x1 + "," + (floorY + 1) + "," + z1 + "]..[" + x2 + "," + (floorY + 3) + "," + z2 + "]"; }
    }

    /** A hole: a cluster of missing cells in an otherwise solid wall, floor or roof. */
    public record Hole(String surface, int x1, int y1, int z1, int x2, int y2, int z2, int cells, String material) {
        public String box() { return "[" + x1 + "," + y1 + "," + z1 + "]..[" + x2 + "," + y2 + "," + z2 + "]"; }
    }

    public record Report(String text, LongOpenHashSet holes, LongOpenHashSet flooded, LongOpenHashSet dark, LongOpenHashSet fragments,
                         List<Room> rooms, List<Hole> holeList, int[] floors, int spawnable, int floodedCells, int fragmentCells) {}

    private static final long MAX_VOLUME = 48_000_000L;
    private final BlockCatalog cat = BlockCatalog.get();

    /** Analyzes the whole model (or the box {@code region} = {x1,y1,z1,x2,y2,z2} when not null). {@code ex} may be null. */
    public Report analyze(VoxelModel m, Existing ex, int[] region) {
        int[] b = m.bounds();
        if (b == null) return new Report("The model is empty.", new LongOpenHashSet(), new LongOpenHashSet(), new LongOpenHashSet(), new LongOpenHashSet(),
                List.of(), List.of(), new int[0], 0, 0, 0);
        if (region != null) b = new int[]{Math.max(b[0], Math.min(region[0], region[3])), Math.max(b[1], Math.min(region[1], region[4])), Math.max(b[2], Math.min(region[2], region[5])),
                Math.min(b[3], Math.max(region[0], region[3])), Math.min(b[4], Math.max(region[1], region[4])), Math.min(b[5], Math.max(region[2], region[5]))};
        int x0 = b[0] - 1, y0 = b[1] - 1, z0 = b[2] - 1;
        int W = b[3] - b[0] + 3, H = b[4] - b[1] + 4, D = b[5] - b[2] + 3;
        long vol = (long) W * H * D;
        StringBuilder sb = new StringBuilder();
        sb.append("STRUCTURE ANALYSIS (model coordinates; x east, y up, z south)\n");
        sb.append("Extent: x ").append(b[0]).append("..").append(b[3]).append(", y ").append(b[1]).append("..").append(b[4]).append(", z ").append(b[2]).append("..").append(b[5])
                .append(" (").append(b[3] - b[0] + 1).append(" x ").append(b[4] - b[1] + 1).append(" x ").append(b[5] - b[2] + 1).append("), ").append(m.nonAirCount()).append(" blocks");
        if (ex != null) sb.append(", ").append(ex.protectedCells().size()).append(" protected blocks (containers, signs, banners, item-frame mounts; never changed)");
        sb.append(".\n");
        sb.append(palette(m)).append('\n');
        if (vol > MAX_VOLUME) {
            sb.append("The build is too large to analyse in one pass (").append(vol).append(" cells). Call inspect_structure with a region of up to about 300 x 200 x 300 blocks.\n");
            return new Report(sb.toString(), new LongOpenHashSet(), new LongOpenHashSet(), new LongOpenHashSet(), new LongOpenHashSet(), List.of(), List.of(), new int[0], 0, 0, 0);
        }
        Grid g = new Grid(m, ex, x0, y0, z0, W, H, D);

        // ---------------------------------------------------------------- floors
        int[] walk = new int[H];
        for (int y = 1; y < H - 2; y++) for (int z = 0; z < D; z++) for (int x = 0; x < W; x++)
            if (g.walkable(x, y, z)) walk[y]++;
        int footprint = g.footprint();
        List<Integer> floorList = new ArrayList<>();
        int threshold = Math.max(12, footprint / 20);
        for (int y = 1; y < H - 2; y++) {
            if (walk[y] < threshold) continue;
            if (!floorList.isEmpty() && y - floorList.get(floorList.size() - 1) <= 2) {
                int last = floorList.get(floorList.size() - 1);
                if (walk[y] > walk[last]) floorList.set(floorList.size() - 1, y);
                continue;
            }
            floorList.add(y);
        }
        int[] floors = floorList.stream().mapToInt(i -> i + y0).toArray();
        sb.append("Floors (walking surface y, area): ");
        if (floors.length == 0) sb.append("none found");
        for (int i = 0; i < floors.length; i++) sb.append(i == 0 ? "" : ", ").append("y=").append(floors[i]).append(" (").append(walk[floors[i] - y0]).append(")");
        sb.append(". Footprint about ").append(footprint).append(" columns.\n");

        // ---------------------------------------------------------------- light
        Lighting.Field light = Lighting.compute(m, b[0], b[1], b[2], b[3], b[4] + 2, b[5]);
        LongOpenHashSet dark = new LongOpenHashSet();
        int spawnIn = 0, spawnOut = 0;
        for (int y = 1; y < H - 2; y++) for (int z = 0; z < D; z++) for (int x = 0; x < W; x++) {
            if (!g.spawnable(x, y, z)) continue;
            int wx = x + x0, wy = y + y0 + 1, wz = z + z0;
            if (light.at(wx, wy, wz) > 0) continue;
            if (g.covered(x, y + 1, z)) { spawnIn++; dark.add(VoxelModel.key(wx, wy, wz)); } else spawnOut++;
        }

        // ---------------------------------------------------------------- rooms
        List<Room> rooms = new ArrayList<>();
        int[] seen = new int[W * D];
        int stamp = 0;
        for (int fy : floorList) {
            stamp++;
            int py = fy + 1;
            for (int z = 0; z < D; z++) for (int x = 0; x < W; x++) {
                int c = z * W + x;
                if (seen[c] == stamp || !g.roomCell(x, py, z)) continue;
                IntArrayList cells = new IntArrayList();
                cells.add(c);
                seen[c] = stamp;
                for (int i = 0; i < cells.size(); i++) {
                    int cc = cells.getInt(i), cx = cc % W, cz = cc / W;
                    int[][] nb = {{cx + 1, cz}, {cx - 1, cz}, {cx, cz + 1}, {cx, cz - 1}};
                    for (int[] n : nb) {
                        if (n[0] < 0 || n[1] < 0 || n[0] >= W || n[1] >= D) continue;
                        int nc = n[1] * W + n[0];
                        if (seen[nc] != stamp && g.roomCell(n[0], py, n[1])) { seen[nc] = stamp; cells.add(nc); }
                    }
                }
                if (cells.size() < 6) continue;
                int rx1 = Integer.MAX_VALUE, rz1 = Integer.MAX_VALUE, rx2 = Integer.MIN_VALUE, rz2 = Integer.MIN_VALUE, rd = 0, dim = 0, fur = 0, fl = 0, doors = 0;
                it.unimi.dsi.fastutil.longs.LongOpenHashSet counted = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
                for (int i = 0; i < cells.size(); i++) {
                    int cc = cells.getInt(i), cx = cc % W, cz = cc / W;
                    rx1 = Math.min(rx1, cx); rz1 = Math.min(rz1, cz); rx2 = Math.max(rx2, cx); rz2 = Math.max(rz2, cz);
                    int l = light.at(cx + x0, py + y0, cz + z0);
                    if (l == 0 && g.spawnable(cx, py - 1, cz)) rd++;
                    if (l < 8) dim++;
                    if (g.fluid(cx, py, cz)) fl++;
                    // furniture stands in the room: on the floor cells beside the walkable space, and on the floor itself (carpets)
                    for (int[] n : new int[][]{{cx, cz}, {cx + 1, cz}, {cx - 1, cz}, {cx, cz + 1}, {cx, cz - 1}}) for (int dy = 0; dy <= 1; dy++) {
                        BlockState s = g.state(n[0], py + dy, n[1]);
                        if (s != null && furniture(s) && counted.add(VoxelModel.key(n[0], py + dy, n[1]))) fur++;
                    }
                    for (int[] n : new int[][]{{cx + 1, cz}, {cx - 1, cz}, {cx, cz + 1}, {cx, cz - 1}}) {
                        BlockState s = g.state(n[0], py, n[1]);
                        if (s != null && cat.kind(s.id()) == BlockCatalog.Kind.DOOR) doors++;
                    }
                }
                rooms.add(new Room(rooms.size() + 1, fy + y0, rx1 + x0, rz1 + z0, rx2 + x0, rz2 + z0, cells.size(), rd, dim, fur, fl, doors / 2));
            }
        }
        rooms.sort((a, c) -> c.area() - a.area());

        // ---------------------------------------------------------------- damage: holes in surfaces
        LongOpenHashSet holeCells = new LongOpenHashSet();
        List<Hole> holes = new ArrayList<>();
        for (int axis = 0; axis < 3; axis++) planeHoles(g, axis, holes, holeCells);
        holes.sort((a, c) -> c.cells() - a.cells());

        // ---------------------------------------------------------------- damage: water inside, floating fragments
        LongOpenHashSet flooded = new LongOpenHashSet();
        if (ex != null) for (int y = 1; y < H - 1; y++) for (int z = 0; z < D; z++) for (int x = 0; x < W; x++) {
            if (!g.fluid(x, y, z) || !g.covered(x, y, z)) continue;
            int yy = y - 1;
            while (yy > 0 && g.fluid(x, yy, z)) yy--;
            if (g.present(x, yy, z) && y - yy <= 6) flooded.add(VoxelModel.key(x + x0, y + y0, z + z0));
        }
        LongOpenHashSet fragments = fragments(m, ex);

        // ---------------------------------------------------------------- circulation
        String circulation = circulation(g, floorList, y0);

        // ---------------------------------------------------------------- report
        sb.append("Rooms (largest first; box = air inside, floor y; light: dark = mob-spawnable spots at light 0, dim = below light 8):\n");
        if (rooms.isEmpty()) sb.append("  none found (no roofed floor space)\n");
        int shown = 0;
        for (Room r : rooms) {
            if (shown++ >= 40) { sb.append("  ... ").append(rooms.size() - 40).append(" smaller rooms\n"); break; }
            sb.append("  R").append(r.id()).append(" floor y=").append(r.floorY()).append(' ').append(r.box()).append(" area ").append(r.area())
                    .append(", ").append(r.dark()).append(" dark, ").append(r.dim()).append(" dim, ").append(r.furniture()).append(" furniture, ")
                    .append(r.doors()).append(" doors").append(r.flooded() > 0 ? ", FLOODED " + r.flooded() : "").append(r.furniture() == 0 && r.area() >= 9 ? ", EMPTY" : "").append('\n');
        }
        sb.append("Circulation: ").append(circulation).append('\n');
        sb.append("Damage:\n");
        if (holes.isEmpty()) sb.append("  no holes found in walls, floors or roofs\n");
        shown = 0;
        for (Hole h : holes) {
            if (shown++ >= 30) { sb.append("  ... ").append(holes.size() - 30).append(" smaller holes\n"); break; }
            sb.append("  hole in ").append(h.surface()).append(' ').append(h.box()).append(", ").append(h.cells()).append(" missing blocks, surrounded by ").append(h.material()).append('\n');
        }
        if (!flooded.isEmpty()) sb.append("  water standing inside: ").append(flooded.size()).append(" cells, e.g. ").append(sample(flooded, 4)).append(" (drain with the drain op AFTER sealing the shell)\n");
        if (!fragments.isEmpty()) sb.append("  loose fragments not connected to the main structure: ").append(fragments.size()).append(" blocks, e.g. ").append(sample(fragments, 4)).append(" (rebuild them into the structure or remove them)\n");
        sb.append("Mob spawning: ").append(spawnIn).append(" dark spawnable spots indoors").append(spawnIn > 0 ? " e.g. " + sample(dark, 4) : "").append(", ").append(spawnOut)
                .append(" on open decks and roofs (spawnable at night). The light_up op fixes them with fixtures in the style.\n");
        long empty = rooms.stream().filter(r -> r.furniture() == 0 && r.area() >= 9).count();
        if (empty > 0) sb.append("Empty interiors: ").append(empty).append(" rooms without furniture (furnish op).\n");
        return new Report(sb.toString(), holeCells, flooded, dark, fragments, rooms, holes, floors, spawnIn, flooded.size(), fragments.size());
    }

    /**
     * Holes in one family of planes (axis 0: walls x = c, 2: walls z = c, 1: floors and roofs y = c). In each plane an
     * empty cell is a gap when the surface continues on both sides of it along both in-plane axes; connected gaps
     * are a hole when the surface around them is solid in this plane but open on at least one side of it (so a room's
     * cross-section, which looks the same in every parallel plane, is not mistaken for a hole). Neat rectangular
     * openings with a solid frame (doors, windows, stairwells) are left alone.
     */
    private void planeHoles(Grid g, int axis, List<Hole> holes, LongOpenHashSet holeCells) {
        int planes = axis == 0 ? g.W : axis == 1 ? g.H : g.D;
        int U = axis == 0 ? g.D : g.W, V = axis == 1 ? g.D : g.H;
        final int reachMax = 16;
        for (int c = 1; c < planes - 1; c++) {
            boolean[] occ = new boolean[U * V];
            int count = 0;
            for (int u = 0; u < U; u++) for (int v = 0; v < V; v++) if (presentIn(g, axis, c, u, v)) { occ[v * U + u] = true; count++; }
            if (count < 24) continue;
            // gap cells: empty, with surface within reach on both sides along u and along v
            int[] left = new int[U * V], right = new int[U * V], down = new int[U * V], up = new int[U * V];
            for (int v = 0; v < V; v++) {
                int last = -1;
                for (int u = 0; u < U; u++) { if (occ[v * U + u]) last = u; left[v * U + u] = last; }
                last = -1;
                for (int u = U - 1; u >= 0; u--) { if (occ[v * U + u]) last = u; right[v * U + u] = last; }
            }
            for (int u = 0; u < U; u++) {
                int last = -1;
                for (int v = 0; v < V; v++) { if (occ[v * U + u]) last = v; down[v * U + u] = last; }
                last = -1;
                for (int v = V - 1; v >= 0; v--) { if (occ[v * U + u]) last = v; up[v * U + u] = last; }
            }
            boolean[] gap = new boolean[U * V];
            for (int u = 0; u < U; u++) for (int v = 0; v < V; v++) {
                int i = v * U + u;
                if (occ[i]) continue;
                if (left[i] < 0 || right[i] < 0 || down[i] < 0 || up[i] < 0) continue;
                if (u - left[i] > reachMax || right[i] - u > reachMax || v - down[i] > reachMax || up[i] - v > reachMax) continue;
                gap[i] = true;
            }
            boolean[] seen = new boolean[U * V];
            for (int i0 = 0; i0 < U * V; i0++) {
                if (!gap[i0] || seen[i0]) continue;
                IntArrayList comp = new IntArrayList();
                comp.add(i0);
                seen[i0] = true;
                for (int k = 0; k < comp.size(); k++) {
                    int i = comp.getInt(k), u = i % U, v = i / U;
                    int[][] nb = {{u + 1, v}, {u - 1, v}, {u, v + 1}, {u, v - 1}};
                    for (int[] n : nb) {
                        if (n[0] < 0 || n[1] < 0 || n[0] >= U || n[1] >= V) continue;
                        int ni = n[1] * U + n[0];
                        if (gap[ni] && !seen[ni]) { seen[ni] = true; comp.add(ni); }
                    }
                }
                if (comp.size() < 4 || comp.size() > 4000) continue;
                int u1 = Integer.MAX_VALUE, v1 = Integer.MAX_VALUE, u2 = Integer.MIN_VALUE, v2 = Integer.MIN_VALUE;
                for (int k = 0; k < comp.size(); k++) { int i = comp.getInt(k); u1 = Math.min(u1, i % U); u2 = Math.max(u2, i % U); v1 = Math.min(v1, i / U); v2 = Math.max(v2, i / U); }
                // the frame around the gap: solid in this plane, open on at least one side of it
                int ring = 0, here = 0, before = 0, after = 0;
                java.util.Set<Integer> inComp = new java.util.HashSet<>();
                for (int k = 0; k < comp.size(); k++) inComp.add(comp.getInt(k));
                Map<String, Integer> around = new HashMap<>();
                for (int k = 0; k < comp.size(); k++) {
                    int i = comp.getInt(k), u = i % U, v = i / U;
                    for (int du = -1; du <= 1; du++) for (int dv = -1; dv <= 1; dv++) {
                        int nu = u + du, nv = v + dv;
                        if (nu < 0 || nv < 0 || nu >= U || nv >= V || inComp.contains(nv * U + nu)) continue;
                        ring++;
                        if (occ[nv * U + nu]) {
                            here++;
                            BlockState st = stateIn(g, axis, c, nu, nv);
                            if (st != null && cat.isSolidCube(st)) around.merge(st.id(), 1, Integer::sum);
                        }
                        if (presentIn(g, axis, c - 1, nu, nv)) before++;
                        if (presentIn(g, axis, c + 1, nu, nv)) after++;
                    }
                }
                if (ring == 0 || here < ring * 0.7 || Math.min(before, after) > ring * 0.35) continue;
                int area = (u2 - u1 + 1) * (v2 - v1 + 1);
                boolean rectangular = comp.size() == area;
                if (rectangular && area <= 24 && here == ring) continue; // a neat door, window or stairwell opening
                int[] a = cell(axis, c, u1, v1), b = cell(axis, c, u2, v2);
                for (int k = 0; k < comp.size(); k++) {
                    int i = comp.getInt(k);
                    int[] w = cell(axis, c, i % U, i / U);
                    holeCells.add(VoxelModel.key(w[0] + g.x0, w[1] + g.y0, w[2] + g.z0));
                }
                String surface = axis == 1 ? "floor/roof" : axis == 0 ? "wall (x=" + (c + g.x0) + ")" : "wall (z=" + (c + g.z0) + ")";
                String mat = around.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("?");
                holes.add(new Hole(surface, Math.min(a[0], b[0]) + g.x0, Math.min(a[1], b[1]) + g.y0, Math.min(a[2], b[2]) + g.z0,
                        Math.max(a[0], b[0]) + g.x0, Math.max(a[1], b[1]) + g.y0, Math.max(a[2], b[2]) + g.z0, comp.size(), mat));
            }
        }
    }

    /** Grid cell for (plane c, in-plane u, v) of an axis: walls x = c use (z, y), floors y = c use (x, z), walls z = c use (x, y). */
    private static int[] cell(int axis, int c, int u, int v) {
        return axis == 0 ? new int[]{c, v, u} : axis == 1 ? new int[]{u, c, v} : new int[]{u, v, c};
    }

    private static boolean presentIn(Grid g, int axis, int c, int u, int v) { int[] w = cell(axis, c, u, v); return g.present(w[0], w[1], w[2]); }

    private static BlockState stateIn(Grid g, int axis, int c, int u, int v) { int[] w = cell(axis, c, u, v); return g.state(w[0], w[1], w[2]); }

    /** Material summary and a style guess. */
    private String palette(VoxelModel m) {
        Map<String, Integer> counts = m.materialCounts();
        long total = 0;
        for (int v : counts.values()) total += v;
        List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort((a, c) -> c.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder("Palette: ");
        for (int i = 0; i < Math.min(10, list.size()); i++) sb.append(i == 0 ? "" : ", ").append(list.get(i).getKey()).append(' ').append(Math.round(1000.0 * list.get(i).getValue() / Math.max(1, total)) / 10.0).append('%');
        Map<String, Integer> fam = new HashMap<>();
        for (Map.Entry<String, Integer> e : list) {
            String id = e.getKey();
            String f = id.contains("prismarine") || id.contains("sea_lantern") ? "oceanic"
                    : id.contains("concrete") || id.contains("glass") || id.contains("iron_block") || id.contains("smooth_quartz") || id.contains("light_gray") ? "modern"
                    : id.contains("quartz") || id.contains("calcite") || id.contains("smooth_stone") ? "classical"
                    : id.contains("deepslate") || id.contains("blackstone") || id.contains("basalt") ? "dark gothic"
                    : id.contains("sandstone") || id.contains("terracotta") ? "desert"
                    : id.contains("stone_brick") || id.contains("cobble") || id.contains("andesite") ? "medieval stone"
                    : id.contains("planks") || id.endsWith("_log") || id.contains("wood") ? "timber"
                    : id.contains("copper") ? "industrial copper" : null;
            if (f != null) fam.merge(f, e.getValue(), Integer::sum);
        }
        String style = fam.entrySet().stream().sorted((a, c) -> c.getValue() - a.getValue()).limit(2).map(Map.Entry::getKey).reduce((a, c) -> a + " / " + c).orElse("mixed");
        return sb.append(". Reads as: ").append(style).append('.').toString();
    }

    private String circulation(Grid g, List<Integer> floors, int y0) {
        if (floors.size() < 2) return floors.isEmpty() ? "no floors" : "single floor";
        StringBuilder sb = new StringBuilder();
        boolean all = true;
        for (int i = 0; i + 1 < floors.size(); i++) {
            int a = floors.get(i), c = floors.get(i + 1);
            int stairs = 0, ladders = 0, lifts = 0;
            for (int y = a; y <= c; y++) for (int z = 0; z < g.D; z++) for (int x = 0; x < g.W; x++) {
                BlockState s = g.state(x, y, z);
                if (s == null) continue;
                String id = s.id();
                BlockCatalog.Kind k = cat.kind(id);
                if (k == BlockCatalog.Kind.STAIRS) stairs++;
                else if (id.equals("ladder") || id.equals("scaffolding") || id.equals("vine") || id.endsWith("_vines")) ladders++;
                else if (id.equals("bubble_column") || id.equals("lodestone")) lifts++;
            }
            boolean linked = stairs >= c - a || ladders >= Math.max(2, (c - a) / 2) || lifts > 0;
            all &= linked;
            sb.append(i == 0 ? "" : "; ").append("y=").append(a + y0).append(" to y=").append(c + y0).append(": ").append(linked ? "connected" : "NOT CONNECTED")
                    .append(" (").append(stairs).append(" stair blocks, ").append(ladders).append(" ladders, ").append(lifts > 0 ? "elevator" : "no elevator").append(')');
        }
        return (all ? "" : "some floors cannot be reached; add stairs or an elevator op. ") + sb;
    }

    /** Structure blocks not connected (face to face) to the largest connected part, unless they rest on natural ground. */
    public static LongOpenHashSet fragments(VoxelModel m, Existing ex) {
        long[] keys = m.keys();
        LongOpenHashSet solid = new LongOpenHashSet(keys.length);
        for (long k : keys) { BlockState s = m.getByKey(k); if (s != null && !s.isAir()) solid.add(k); }
        LongOpenHashSet assigned = new LongOpenHashSet(solid.size());
        List<LongOpenHashSet> comps = new ArrayList<>();
        for (long k : keys) {
            if (!solid.contains(k) || assigned.contains(k)) continue;
            LongOpenHashSet comp = new LongOpenHashSet();
            it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue q = new it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue();
            q.enqueue(k); assigned.add(k); comp.add(k);
            while (!q.isEmpty()) {
                long c = q.dequeueLong();
                int x = VoxelModel.kx(c), y = VoxelModel.ky(c), z = VoxelModel.kz(c);
                long[] nb = {VoxelModel.key(x + 1, y, z), VoxelModel.key(x - 1, y, z), VoxelModel.key(x, y + 1, z), VoxelModel.key(x, y - 1, z), VoxelModel.key(x, y, z + 1), VoxelModel.key(x, y, z - 1)};
                for (long n : nb) if (solid.contains(n) && assigned.add(n)) { comp.add(n); q.enqueue(n); }
            }
            comps.add(comp);
        }
        LongOpenHashSet out = new LongOpenHashSet();
        if (comps.size() <= 1) return out;
        comps.sort((a, c) -> c.size() - a.size());
        for (int i = 1; i < comps.size(); i++) {
            LongOpenHashSet c = comps.get(i);
            boolean grounded = false;
            if (ex != null) for (long k : c) {
                int x = VoxelModel.kx(k), y = VoxelModel.ky(k), z = VoxelModel.kz(k);
                if (ex.natural(x, y - 1, z) || ex.natural(x + 1, y, z) || ex.natural(x - 1, y, z) || ex.natural(x, y, z + 1) || ex.natural(x, y, z - 1)) { grounded = true; break; }
            }
            if (!grounded && c.size() < Math.max(2000, comps.get(0).size() / 10)) out.addAll(c);
        }
        return out;
    }

    static boolean furnitureId(String id) {
        return id.endsWith("_bed") || id.equals("chest") || id.equals("barrel") || id.equals("trapped_chest") || id.equals("crafting_table") || id.equals("furnace")
                || id.equals("smoker") || id.equals("blast_furnace") || id.equals("bookshelf") || id.equals("chiseled_bookshelf") || id.equals("lectern")
                || id.equals("cartography_table") || id.equals("fletching_table") || id.equals("smithing_table") || id.equals("loom") || id.equals("stonecutter")
                || id.equals("grindstone") || id.endsWith("anvil") || id.equals("cauldron") || id.equals("water_cauldron") || id.equals("composter") || id.equals("brewing_stand")
                || id.equals("enchanting_table") || id.equals("jukebox") || id.equals("note_block") || id.equals("flower_pot") || id.startsWith("potted_")
                || id.endsWith("_carpet") || id.equals("decorated_pot") || id.endsWith("_banner") || id.endsWith("_sign") || id.endsWith("shulker_box") || id.equals("bell");
    }

    private boolean furniture(BlockState s) {
        if (furnitureId(s.id())) return true;
        BlockCatalog.Kind k = cat.kind(s.id());
        return k == BlockCatalog.Kind.STAIRS || k == BlockCatalog.Kind.FENCE || k == BlockCatalog.Kind.TRAPDOOR;
    }

    private static String sample(LongOpenHashSet set, int n) {
        List<String> out = new ArrayList<>();
        for (long k : set) { out.add("[" + VoxelModel.kx(k) + "," + VoxelModel.ky(k) + "," + VoxelModel.kz(k) + "]"); if (out.size() >= n) break; }
        return String.join(" ", out);
    }

    /** Dense view of the model over the analysis box (grid coordinates are model minus the box origin). */
    private final class Grid {
        final int x0, y0, z0, W, H, D;
        final VoxelModel m;
        final Existing ex;
        final BitSet present, opaque;
        final int[] top; // highest present grid y per column, -1 = none

        Grid(VoxelModel m, Existing ex, int x0, int y0, int z0, int W, int H, int D) {
            this.m = m; this.ex = ex; this.x0 = x0; this.y0 = y0; this.z0 = z0; this.W = W; this.H = H; this.D = D;
            present = new BitSet(W * H * D);
            opaque = new BitSet(W * H * D);
            top = new int[W * D];
            java.util.Arrays.fill(top, -1);
            for (long k : m.keys()) {
                int x = VoxelModel.kx(k) - x0, y = VoxelModel.ky(k) - y0, z = VoxelModel.kz(k) - z0;
                if (x < 0 || y < 0 || z < 0 || x >= W || y >= H || z >= D) continue;
                BlockState s = m.getByKey(k);
                if (s == null || s.isAir()) continue;
                int i = idx(x, y, z);
                present.set(i);
                if (cat.isSolidCube(s) && cat.kind(s.id()) != BlockCatalog.Kind.GLASS && cat.kind(s.id()) != BlockCatalog.Kind.LEAVES) opaque.set(i);
                if (y > top[z * W + x]) top[z * W + x] = y;
            }
        }

        int idx(int x, int y, int z) { return (y * D + z) * W + x; }
        boolean in(int x, int y, int z) { return x >= 0 && y >= 0 && z >= 0 && x < W && y < H && z < D; }
        boolean present(int x, int y, int z) { return in(x, y, z) && present.get(idx(x, y, z)); }
        boolean opaque(int x, int y, int z) { return in(x, y, z) && opaque.get(idx(x, y, z)); }
        BlockState state(int x, int y, int z) { return in(x, y, z) ? m.get(x + x0, y + y0, z + z0) : null; }
        boolean fluid(int x, int y, int z) { return ex != null && ex.fluid(x + x0, y + y0, z + z0) && !present(x, y, z); }
        boolean covered(int x, int y, int z) { return in(x, 0, z) && top[z * W + x] > y; }
        boolean air(int x, int y, int z) { return in(x, y, z) && !present(x, y, z); }

        int footprint() { int n = 0; for (int t : top) if (t >= 0) n++; return n; }

        /** A block you can stand on with two free cells above. */
        boolean walkable(int x, int y, int z) {
            if (!present(x, y, z) || !air(x, y + 1, z) || !air(x, y + 2, z)) return false;
            BlockState s = state(x, y, z);
            BlockCatalog.Kind k = cat.kind(s.id());
            return cat.isSolidCube(s) || k == BlockCatalog.Kind.SLAB || k == BlockCatalog.Kind.STAIRS || k == BlockCatalog.Kind.GLASS;
        }

        /** A hostile mob could spawn standing on (x, y, z): an opaque full block with two empty, dry cells above. */
        boolean spawnable(int x, int y, int z) {
            if (!opaque(x, y, z) || !air(x, y + 1, z) || !air(x, y + 2, z) || fluid(x, y + 1, z)) return false;
            BlockState s = state(x, y, z);
            String id = s.id();
            return !(id.contains("glass") || id.equals("bedrock") || id.endsWith("_leaves") || id.equals("barrier") || id.equals("mycelium"));
        }

        /** Walkable floor space under a roof, the cells rooms are made of. */
        boolean roomCell(int x, int py, int z) {
            if (!(present(x, py - 1, z) && air(x, py, z) && air(x, py + 1, z) && covered(x, py + 1, z))) return false;
            // a doorway (wall on both sides) separates rooms instead of joining them
            return !((present(x - 1, py, z) && present(x + 1, py, z)) || (present(x, py, z - 1) && present(x, py, z + 1)));
        }

    }
}
