package io.github.shengboj0324.architect.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Furnishes a room from vanilla blocks the way a good builder does: pieces stand against the walls facing into the
 * room, beds are two correct halves with the head at the wall, chairs face their tables, doorways and the path in
 * from them stay clear, and nothing is placed where there is not two blocks of headroom. The room is given as the
 * walkable floor's box; only cells inside it that are real floor space are used.
 */
public final class Furniture {

    public static final List<String> TYPES = List.of("bedroom", "living", "dining", "kitchen", "library", "storage", "office", "workshop", "lobby", "bathroom", "lab", "auto");

    private final Canvas c;
    private final Existing ex;
    private final int floorY, x1, z1, x2, z2;
    private final String wood, color;
    private final Set<Long> used = new HashSet<>();
    private final Set<Long> clear = new HashSet<>();
    private final List<int[]> slots = new ArrayList<>(); // {x, z, dirToWall ordinal}
    private int placed;

    private Furniture(Canvas c, Existing ex, int x1, int z1, int x2, int z2, int floorY, String wood, String color) {
        this.c = c;
        this.ex = ex;
        this.floorY = floorY;
        this.x1 = Math.min(x1, x2); this.x2 = Math.max(x1, x2);
        this.z1 = Math.min(z1, z2); this.z2 = Math.max(z1, z2);
        this.wood = wood == null ? "spruce" : wood.toLowerCase(Locale.ROOT).replace("_planks", "");
        this.color = color == null ? "red" : color.toLowerCase(Locale.ROOT);
    }

    /** Furnishes the room; returns blocks placed. {@code type} is one of {@link #TYPES}. */
    public static int furnish(Canvas c, Existing ex, int x1, int z1, int x2, int z2, int floorY, String type, String wood, String color) {
        Furniture f = new Furniture(c, ex, x1, z1, x2, z2, floorY, wood, color);
        f.survey();
        String t = type == null ? "auto" : type.toLowerCase(Locale.ROOT);
        if (t.equals("auto")) t = f.guess();
        switch (t) {
            case "bedroom" -> f.bedroom();
            case "living" -> f.living();
            case "dining" -> f.dining();
            case "kitchen" -> f.kitchen();
            case "library" -> f.library();
            case "storage" -> f.storage();
            case "office" -> f.office();
            case "workshop" -> f.workshop();
            case "lobby" -> f.lobby();
            case "bathroom" -> f.bathroom();
            case "lab" -> f.lab();
            default -> throw new IllegalArgumentException("unknown room type '" + type + "'; use one of " + TYPES);
        }
        return f.placed;
    }

    // ---------------------------------------------------------------- survey

    private boolean floorCell(int x, int z) {
        VoxelModel m = c.model();
        return m.solidAt(x, floorY, z) && free(x, floorY + 1, z) && free(x, floorY + 2, z);
    }

    private boolean free(int x, int y, int z) {
        if (c.model().solidAt(x, y, z)) return false;
        return ex == null || !(ex.fluid(x, y, z) && c.model().get(x, y, z) == null);
    }

    private boolean wallAt(int x, int z) { return c.model().solidAt(x, floorY + 1, z) && c.model().solidAt(x, floorY + 2, z); }

    private void survey() {
        BlockCatalog cat = c.catalog();
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
            if (!floorCell(x, z)) continue;
            for (Dir d : Dir.values()) {
                int nx = x + d.dx, nz = z + d.dz;
                BlockState s = c.model().get(nx, floorY + 1, nz);
                boolean door = s != null && (cat.kind(s.id()) == BlockCatalog.Kind.DOOR || cat.kind(s.id()) == BlockCatalog.Kind.GATE);
                boolean opening = !wallAt(nx, nz) && (nx < x1 || nx > x2 || nz < z1 || nz > z2) && floorCell(nx, nz);
                if (door || opening) { // keep the doorway and two cells into the room clear
                    clear.add(k(x, z));
                    clear.add(k(x - d.dx, z - d.dz));
                    clear.add(k(x - 2 * d.dx, z - 2 * d.dz));
                    clear.add(k(x - d.dx + d.cw().dx, z - d.dz + d.cw().dz));
                    clear.add(k(x - d.dx + d.ccw().dx, z - d.dz + d.ccw().dz));
                }
            }
        }
        double cx = (x1 + x2) / 2.0, cz = (z1 + z2) / 2.0;
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
            if (!floorCell(x, z) || clear.contains(k(x, z))) continue;
            for (Dir d : Dir.values()) if (wallAt(x + d.dx, z + d.dz)) { slots.add(new int[]{x, z, d.ordinal()}); break; }
        }
        slots.sort(Comparator.comparingDouble(s -> Math.atan2(s[1] - cz, s[0] - cx)));
    }

    private String guess() {
        int area = 0;
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) if (floorCell(x, z)) area++;
        if (area < 12) return "storage";
        if (area < 30) return "bedroom";
        if (area < 60) return "living";
        if (area < 120) return "dining";
        return "lobby";
    }

    // ---------------------------------------------------------------- placing

    private static long k(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    private boolean usable(int x, int z) {
        return x >= x1 && x <= x2 && z >= z1 && z <= z2 && floorCell(x, z) && !used.contains(k(x, z)) && !clear.contains(k(x, z));
    }

    private boolean put(int x, int dy, int z, String spec) {
        if (!free(x, floorY + dy, z)) return false;
        long before = c.placedCount();
        try { c.set(x, floorY + dy, z, spec); } catch (IllegalArgumentException e) { return false; }
        if (c.placedCount() == before) return false;
        placed++;
        used.add(k(x, z));
        return true;
    }

    /** Free wall slots (consecutive along the perimeter). */
    private List<int[]> freeSlots() {
        List<int[]> out = new ArrayList<>();
        for (int[] s : slots) if (usable(s[0], s[1])) out.add(s);
        return out;
    }

    private static Dir dir(int[] slot) { return Dir.values()[slot[2]]; }

    /** Places {@code specs} one per free wall slot, skipping one slot between pieces when {@code gap}. Returns pieces placed. */
    private int alongWalls(List<String> specs, boolean gap, int max) {
        int n = 0, i = 0;
        boolean skip = false;
        for (int[] s : freeSlots()) {
            if (n >= max || specs.isEmpty()) break;
            if (!usable(s[0], s[1])) continue;
            if (skip) { skip = false; continue; }
            String spec = specs.get(i % specs.size()).replace("{in}", dir(s).opposite().key()).replace("{wall}", dir(s).key());
            if (put(s[0], 1, s[1], spec)) { n++; i++; skip = gap; }
        }
        return n;
    }

    private boolean bed(int[] s) {
        Dir toWall = dir(s);
        int fx = s[0] - toWall.dx, fz = s[1] - toWall.dz;
        if (!usable(s[0], s[1]) || !usable(fx, fz)) return false;
        String b = color + "_bed[facing=" + toWall.key() + ",part=";
        if (!put(s[0], 1, s[1], b + "head]")) return false;
        if (!put(fx, 1, fz, b + "foot]")) { c.model().set(s[0], floorY + 1, s[1], BlockState.AIR); return false; }
        // a nightstand with a lamp beside the head
        for (Dir side : new Dir[]{toWall.cw(), toWall.ccw()}) {
            int nx = s[0] + side.dx, nz = s[1] + side.dz;
            if (usable(nx, nz) && wallAt(nx + toWall.dx, nz + toWall.dz) && put(nx, 1, nz, "barrel[facing=up]")) { put(nx, 2, nz, "lantern[hanging=false]"); break; }
        }
        return true;
    }

    private void rug(int inset) {
        if (x2 - x1 < 2 * inset + 1 || z2 - z1 < 2 * inset + 1) return;
        for (int x = x1 + inset; x <= x2 - inset; x++) for (int z = z1 + inset; z <= z2 - inset; z++)
            if (usable(x, z)) put(x, 1, z, color + "_carpet");
    }

    private void tableWithChairs(int x, int z, Dir along, int length) {
        List<int[]> top = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            int tx = x + along.dx * i, tz = z + along.dz * i;
            if (!usable(tx, tz)) break;
            top.add(new int[]{tx, tz});
        }
        if (top.isEmpty()) return;
        for (int[] t : top) put(t[0], 1, t[1], wood + "_slab[type=top]");
        for (int[] t : top) for (Dir side : new Dir[]{along.cw(), along.ccw()}) {
            int cx = t[0] + side.dx, cz = t[1] + side.dz;
            if (usable(cx, cz)) put(cx, 1, cz, wood + "_stairs[facing=" + side.key() + ",half=bottom]");
        }
    }

    private void couch(int len) {
        List<int[]> free = freeSlots();
        for (int i = 0; i + len <= free.size(); i++) {
            Dir d = dir(free.get(i));
            boolean straight = true;
            for (int j = 1; j < len && straight; j++) {
                int[] a = free.get(i + j - 1), b = free.get(i + j);
                straight = dir(b) == d && Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) == 1;
            }
            if (!straight) continue;
            for (int j = 0; j < len; j++) put(free.get(i + j)[0], 1, free.get(i + j)[1], wood + "_stairs[facing=" + d.key() + ",half=bottom]");
            int[] mid = free.get(i + len / 2);
            int tx = mid[0] - d.dx * 2, tz = mid[1] - d.dz * 2;
            if (usable(tx, tz)) put(tx, 1, tz, wood + "_slab[type=top]");
            return;
        }
    }

    private void plantsInCorners() {
        int n = 0;
        for (int[] s : freeSlots()) {
            Dir d = dir(s);
            boolean corner = wallAt(s[0] + d.cw().dx, s[1] + d.cw().dz) || wallAt(s[0] + d.ccw().dx, s[1] + d.ccw().dz);
            if (corner && put(s[0], 1, s[1], n % 2 == 0 ? "potted_fern" : "potted_azalea_bush") && ++n >= 4) return;
        }
    }

    // ---------------------------------------------------------------- room types

    private void bedroom() {
        for (int[] s : freeSlots()) if (bed(s)) break;
        alongWalls(List.of("chest[facing={in}]", "bookshelf"), true, 2);
        plantsInCorners();
        rug(2);
    }

    private void living() {
        couch(Math.min(3, Math.max(2, (Math.min(x2 - x1, z2 - z1) - 1) / 2)));
        alongWalls(List.of("bookshelf", "bookshelf", "potted_fern"), false, 6);
        rug(2);
        plantsInCorners();
    }

    private void dining() {
        boolean alongX = x2 - x1 >= z2 - z1;
        int len = Math.max(2, (alongX ? x2 - x1 : z2 - z1) - 3);
        int sx = alongX ? x1 + 2 : (x1 + x2) / 2, sz = alongX ? (z1 + z2) / 2 : z1 + 2;
        tableWithChairs(sx, sz, alongX ? Dir.EAST : Dir.SOUTH, len);
        alongWalls(List.of("barrel[facing=up]", "potted_fern"), true, 4);
    }

    private void kitchen() {
        alongWalls(List.of("smoker[facing={in}]", "crafting_table", "barrel[facing=up]", "water_cauldron[level=3]", "furnace[facing={in}]", wood + "_slab[type=top]",
                "barrel[facing=up]", "composter"), false, 10);
        int cx = (x1 + x2) / 2, cz = (z1 + z2) / 2;
        if (x2 - x1 >= 4 && z2 - z1 >= 4) tableWithChairs(cx, cz, Dir.EAST, 2);
    }

    private void library() {
        for (int[] s : freeSlots()) {
            if (put(s[0], 1, s[1], "bookshelf")) {
                if (free(s[0], floorY + 2, s[1])) c.set(s[0], floorY + 2, s[1], "bookshelf");
                placed++;
            }
        }
        int cx = (x1 + x2) / 2, cz = (z1 + z2) / 2;
        if (usable(cx, cz)) put(cx, 1, cz, "lectern[facing=north]");
        if (x2 - x1 >= 5) tableWithChairs(cx - 1, cz + 2, Dir.EAST, 3);
    }

    private void storage() {
        alongWalls(List.of("chest[facing={in}]", "barrel[facing={in}]"), false, 24);
    }

    private void office() {
        for (int[] s : freeSlots()) {
            Dir d = dir(s);
            if (put(s[0], 1, s[1], wood + "_slab[type=top]")) {
                int cx = s[0] - d.dx, cz = s[1] - d.dz;
                if (usable(cx, cz)) put(cx, 1, cz, wood + "_stairs[facing=" + d.opposite().key() + ",half=bottom]");
                put(s[0], 2, s[1], "lantern[hanging=false]");
                break;
            }
        }
        alongWalls(List.of("bookshelf", "lectern[facing={in}]", "bookshelf", "barrel[facing=up]"), false, 5);
        plantsInCorners();
    }

    private void workshop() {
        alongWalls(List.of("crafting_table", "anvil[facing={wall}]", "grindstone[face=floor,facing={in}]", "smithing_table", "stonecutter[facing={in}]",
                "loom[facing={in}]", "cartography_table", "fletching_table", "barrel[facing=up]"), true, 9);
    }

    private void lobby() {
        int benches = 0;
        List<int[]> free = freeSlots();
        for (int i = 0; i + 1 < free.size() && benches < 6; i++) {
            int[] a = free.get(i), b = free.get(i + 1);
            if (dir(a) != dir(b) || Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]) != 1 || !usable(a[0], a[1]) || !usable(b[0], b[1])) continue;
            put(a[0], 1, a[1], wood + "_stairs[facing=" + dir(a).key() + ",half=bottom]");
            put(b[0], 1, b[1], wood + "_stairs[facing=" + dir(b).key() + ",half=bottom]");
            benches++;
            i += 3;
        }
        plantsInCorners();
        rug(3);
    }

    private void bathroom() {
        alongWalls(List.of("water_cauldron[level=3]", "barrel[facing=up]", "potted_fern"), true, 3);
    }

    private void lab() {
        alongWalls(List.of("brewing_stand", "barrel[facing=up]", "crafting_table", "white_stained_glass", "cauldron", "lectern[facing={in}]"), false, 10);
        int cx = (x1 + x2) / 2, cz = (z1 + z2) / 2;
        if (x2 - x1 >= 4 && z2 - z1 >= 4) tableWithChairs(cx - 1, cz, Dir.EAST, 3);
    }

    /** For tests: the wall slots of a room. */
    static Map<String, Integer> describe(Canvas c, Existing ex, int x1, int z1, int x2, int z2, int floorY) {
        Furniture f = new Furniture(c, ex, x1, z1, x2, z2, floorY, null, null);
        f.survey();
        Map<String, Integer> m = new HashMap<>();
        m.put("slots", f.slots.size());
        m.put("clear", f.clear.size());
        return m;
    }
}
