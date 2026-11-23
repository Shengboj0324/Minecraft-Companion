package io.github.shengboj0324.architect.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Repair operations for an existing build: patching blown-out holes in walls, floors and roofs with the material
 * around them, draining water that stands inside, and a lighting pass that places fixtures until no spot is dark
 * enough for mobs to spawn. They work on the design model like every other op, so they are undoable in the design
 * loop and protected cells are never touched (the model is locked there).
 */
public final class Renovation {

    private Renovation() { }

    /**
     * The changes a renovation makes to the world: blocks that are new or different, and air where a block was removed
     * or water was drained. Protected cells never appear.
     */
    public static VoxelModel diff(VoxelModel now, VoxelModel before, Existing ex) {
        VoxelModel out = new VoxelModel();
        for (long k : now.keys()) {
            if (ex != null && ex.isProtectedKey(k)) continue;
            BlockState s = now.getByKey(k), was = before.getByKey(k);
            int x = VoxelModel.kx(k), y = VoxelModel.ky(k), z = VoxelModel.kz(k);
            if (s.isAir()) { if (was != null || (ex != null && ex.fluid(x, y, z))) out.set(x, y, z, BlockState.AIR); }
            else if (!s.equals(was)) out.set(x, y, z, s);
        }
        for (long k : before.keys()) if (now.getByKey(k) == null && (ex == null || !ex.isProtectedKey(k))) out.set(VoxelModel.kx(k), VoxelModel.ky(k), VoxelModel.kz(k), BlockState.AIR);
        return out;
    }

    /** Fills every irregular hole the analyzer finds in the box (null = whole model). Returns blocks placed. */
    public static int patch(Canvas c, Existing ex, int[] box, String spec) {
        VoxelModel m = c.model();
        StructureAnalyzer.Report r = new StructureAnalyzer().analyze(m, ex, box);
        int placed = 0;
        for (StructureAnalyzer.Hole h : r.holeList()) placed += fillHole(c, h, spec);
        return placed;
    }

    /** Fills one hole by growing the surrounding surface inward, a ring at a time. */
    static int fillHole(Canvas c, StructureAnalyzer.Hole h, String spec) {
        VoxelModel m = c.model();
        boolean floor = h.surface().startsWith("floor");
        boolean wallX = h.surface().contains("x=");
        // the plane the hole lies in: two in-plane axes
        int[] u = floor ? new int[]{1, 0, 0} : wallX ? new int[]{0, 0, 1} : new int[]{1, 0, 0};
        int[] v = floor ? new int[]{0, 0, 1} : new int[]{0, 1, 0};
        String material = spec != null ? spec : h.material();
        int placed = 0;
        for (int pass = 0; pass < 64; pass++) {
            List<int[]> fills = new ArrayList<>();
            for (int x = h.x1() - 1; x <= h.x2() + 1; x++) for (int y = h.y1() - 1; y <= h.y2() + 1; y++) for (int z = h.z1() - 1; z <= h.z2() + 1; z++) {
                if (m.solidAt(x, y, z)) continue;
                int n = 0;
                for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) {
                    if (a == 0 && b == 0) continue;
                    if (m.solidAt(x + a * u[0] + b * v[0], y + a * u[1] + b * v[1], z + a * u[2] + b * v[2])) n++;
                }
                boolean inside = x >= h.x1() && x <= h.x2() && y >= h.y1() && y <= h.y2() && z >= h.z1() && z <= h.z2();
                if (inside ? n >= 3 : n >= 6) fills.add(new int[]{x, y, z});
            }
            if (fills.isEmpty()) break;
            for (int[] f : fills) {
                String mat = material.startsWith("@") ? material : neighbourMaterial(m, f[0], f[1], f[2], material);
                long before = c.placedCount();
                c.set(f[0], f[1], f[2], mat);
                if (c.placedCount() > before) placed++;
            }
        }
        return placed;
    }

    /** The most common full-block neighbour (so a patch in a striped wall picks up the stripe), else the fallback. */
    private static String neighbourMaterial(VoxelModel m, int x, int y, int z, String fallback) {
        BlockCatalog cat = BlockCatalog.get();
        Map<String, Integer> counts = new HashMap<>();
        int[][] nb = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] n : nb) {
            BlockState s = m.get(x + n[0], y + n[1], z + n[2]);
            if (s == null || s.isAir()) continue;
            String id = cat.isSolidCube(s) ? s.id() : cat.fullOf(s.id());
            if (id != null && !id.contains("glass") && Lighting.emission(BlockState.of(id)) == 0 && !StructureAnalyzer.furnitureId(id)) counts.merge(id, 1, Integer::sum);
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(fallback);
    }

    /** Sets every water cell in the box to air. Returns cells drained. Seal the shell first, or the sea flows back in. */
    public static int drain(Canvas c, Existing ex, int[] box) {
        if (ex == null) return 0;
        int x1 = Math.max(0, Math.min(box[0], box[3])), x2 = Math.min(ex.width() - 1, Math.max(box[0], box[3]));
        int y1 = Math.max(ex.minY(), Math.min(box[1], box[4])), y2 = Math.min(ex.maxY(), Math.max(box[1], box[4]));
        int z1 = Math.max(0, Math.min(box[2], box[5])), z2 = Math.min(ex.depth() - 1, Math.max(box[2], box[5]));
        int n = 0;
        VoxelModel m = c.model();
        for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) for (int x = x1; x <= x2; x++) {
            if (!ex.fluid(x, y, z) || m.get(x, y, z) != null) continue;
            if (m.set(x, y, z, BlockState.AIR)) n++;
        }
        return n;
    }

    /**
     * Cells where the build's air meets water that will remain after the renovation: places the sea would pour in.
     * Each entry is {x, y, z} of the water cell next to an interior air cell.
     */
    public static List<int[]> leaks(VoxelModel m, Existing ex) {
        List<int[]> out = new ArrayList<>();
        if (ex == null) return out;
        int[][] nb = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (long k : m.keys()) {
            BlockState s = m.getByKey(k);
            if (s == null || !s.isAir()) continue;
            int x = VoxelModel.kx(k), y = VoxelModel.ky(k), z = VoxelModel.kz(k);
            if (!ex.fluid(x, y, z)) continue; // only drained cells matter: air that was water before
            for (int[] n : nb) {
                int nx = x + n[0], ny = y + n[1], nz = z + n[2];
                if (ex.fluid(nx, ny, nz) && m.get(nx, ny, nz) == null) out.add(new int[]{nx, ny, nz});
            }
        }
        return out;
    }

    /** Seals every leak with the given block (glass keeps an underwater view). Returns blocks placed. */
    public static int sealLeaks(Canvas c, Existing ex, String spec) {
        int n = 0;
        for (int[] l : leaks(c.model(), ex)) {
            if (c.model().get(l[0], l[1], l[2]) != null) continue;
            long before = c.placedCount();
            c.set(l[0], l[1], l[2], spec);
            if (c.placedCount() > before) n++;
        }
        return n;
    }

    /**
     * Places light fixtures in the box until every walkable spot indoors reaches {@code target} block light and every
     * open spot (decks, roofs) is above 0, so nothing can spawn. Fixtures: a lantern hung from the ceiling (on a chain
     * when the ceiling is high), else a light block set into the floor, else a standing lantern. {@code fixture} may
     * override the lantern (e.g. "soul_lantern", "@light"); {@code floorLight} the embedded block.
     */
    public static int lightUp(Canvas c, Existing ex, int[] box, int target, String fixture, String floorLight) {
        VoxelModel m = c.model();
        BlockCatalog cat = c.catalog();
        int[] b = m.bounds();
        if (b == null) return 0;
        int x1 = box == null ? b[0] : Math.max(b[0], Math.min(box[0], box[3])), x2 = box == null ? b[3] : Math.min(b[3], Math.max(box[0], box[3]));
        int y1 = box == null ? b[1] : Math.max(b[1], Math.min(box[1], box[4])), y2 = box == null ? b[4] : Math.min(b[4], Math.max(box[1], box[4]));
        int z1 = box == null ? b[2] : Math.max(b[2], Math.min(box[2], box[5])), z2 = box == null ? b[5] : Math.min(b[5], Math.max(box[2], box[5]));
        Lighting.Field f = Lighting.compute(m, x1, y1, z1, x2, y2 + 2, z2);
        int[] colTop = new int[(x2 - x1 + 1) * (z2 - z1 + 1)];
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
            int t = Integer.MIN_VALUE;
            for (int y = b[4]; y >= b[1]; y--) if (m.solidAt(x, y, z)) { t = y; break; }
            colTop[(z - z1) * (x2 - x1 + 1) + (x - x1)] = t;
        }
        String lantern = fixture == null ? "lantern" : fixture;
        String embedded = floorLight == null ? "sea_lantern" : floorLight;
        int placed = 0;
        // two passes: first fixtures on a regular 6-block grid (so lighting reads as designed, not scattered), then
        // whatever is still dark
        for (int pass = 0; pass < 2; pass++)
        for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) for (int x = x1; x <= x2; x++) {
            if (pass == 0 && (Math.floorMod(x - x1, 6) != 3 || Math.floorMod(z - z1, 6) != 3)) continue;
            if (!spawnSurface(m, cat, ex, x, y, z)) continue;
            int top = colTop[(z - z1) * (x2 - x1 + 1) + (x - x1)];
            boolean indoor = top > y + 2;
            int need = indoor ? target : 1;
            if (f.at(x, y + 1, z) >= need) continue;
            int[] at = placeFixture(c, ex, x, y, z, indoor, lantern, embedded);
            if (at == null) continue;
            placed++;
            spread(m, cat, f, at[0], at[1], at[2], Lighting.emission(m.get(at[0], at[1], at[2])));
        }
        return placed;
    }

    private static boolean spawnSurface(VoxelModel m, BlockCatalog cat, Existing ex, int x, int y, int z) {
        BlockState s = m.get(x, y, z);
        if (s == null || !Lighting.opaque(cat, s) || s.id().contains("glass")) return false;
        if (m.solidAt(x, y + 1, z) || m.solidAt(x, y + 2, z)) return false;
        return ex == null || !(ex.fluid(x, y + 1, z) && m.get(x, y + 1, z) == null);
    }

    /** Places one fixture for the dark spot standing on (x, y, z). Returns where the light source went, or null. */
    private static int[] placeFixture(Canvas c, Existing ex, int x, int y, int z, boolean indoor, String lantern, String embedded) {
        VoxelModel m = c.model();
        if (indoor) {
            int ceil = -1;
            for (int yy = y + 3; yy <= y + 12; yy++) if (m.solidAt(x, yy, z)) { ceil = yy; break; }
            if (ceil > 0 && ceil - y >= 4) { // at least three free cells: hang it above head height
                int ly = Math.max(y + 3, ceil - 2);
                for (int yy = ceil - 1; yy > ly; yy--) if (!setIfFree(c, ex, x, yy, z, "iron_chain")) return null;
                if (setIfFree(c, ex, x, ly, z, lantern + "[hanging=true]")) return new int[]{x, ly, z};
            }
            // low ceiling: set a light into the floor
            BlockState under = m.get(x, y, z);
            if (under != null && !(ex != null && ex.isProtected(x, y, z))) {
                long before = c.placedCount();
                c.set(x, y, z, embedded);
                if (c.placedCount() > before) return new int[]{x, y, z};
            }
            return null;
        }
        return setIfFree(c, ex, x, y + 1, z, lantern + "[hanging=false]") ? new int[]{x, y + 1, z} : null;
    }

    private static boolean setIfFree(Canvas c, Existing ex, int x, int y, int z, String spec) {
        if (c.model().solidAt(x, y, z)) return false;
        if (ex != null && ex.fluid(x, y, z) && c.model().get(x, y, z) == null) return false;
        long before = c.placedCount();
        c.set(x, y, z, spec);
        return c.placedCount() > before;
    }

    /** Raises the light field around a new source (light only grows when a source is added). */
    private static void spread(VoxelModel m, BlockCatalog cat, Lighting.Field f, int sx, int sy, int sz, int level) {
        if (level <= 0 || !f.inside(sx, sy, sz)) return;
        ArrayDeque<int[]> q = new ArrayDeque<>();
        f.light[f.index(sx, sy, sz)] = (byte) Math.max(f.light[f.index(sx, sy, sz)], level);
        q.add(new int[]{sx, sy, sz, level});
        int[][] nb = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!q.isEmpty()) {
            int[] p = q.poll();
            if (p[3] <= 1) continue;
            for (int[] n : nb) {
                int x = p[0] + n[0], y = p[1] + n[1], z = p[2] + n[2];
                if (!f.inside(x, y, z) || Lighting.opaque(cat, m.get(x, y, z))) continue;
                int i = f.index(x, y, z);
                if (f.light[i] >= p[3] - 1) continue;
                f.light[i] = (byte) (p[3] - 1);
                q.add(new int[]{x, y, z, p[3] - 1});
            }
        }
    }
}
