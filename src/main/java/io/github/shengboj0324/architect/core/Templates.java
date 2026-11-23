package io.github.shengboj0324.architect.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Curated, procedurally generated master builds. They work offline (no API key), serve as quality baselines for the
 * eval suite, and the AI may start from one and then customise it ("base_template").
 * All templates face north (front at low z) and sit on y = 0.
 */
public final class Templates {

    public record Info(String name, String defaultStyle, String description) {}

    public static final Map<String, Info> ALL = new LinkedHashMap<>();

    static {
        add("cottage", "rustic", "Small one-storey cottage with chimney, garden fence, path and tree.");
        add("townhouse", "medieval", "Two-storey timber-framed townhouse with jettied upper floor and steep gable roof.");
        add("longhouse", "nordic", "Viking longhouse with steep roof, crossed gable beams, palisade yard.");
        add("watchtower", "medieval", "Round stone watchtower with conical roof on a rocky plinth.");
        add("wizard_tower", "fantasy", "Tall slender wizard tower with turrets at several heights and spire roofs.");
        add("castle", "medieval", "Walled castle: curtain walls, 4 corner towers, twin-tower gatehouse, keep, courtyard buildings.");
        add("cathedral", "gothic", "Gothic cathedral: twin west towers with spires, nave + aisles, flying buttresses, transept, crossing spire, apse, rose window.");
        add("pagoda", "japanese", "Five-tier pagoda in a gravel garden with cherry trees and stone lanterns.");
        add("temple", "classical", "Greek temple: stepped stylobate, peristyle colonnade, pediment roof.");
        add("palace", "desert", "Desert palace: domed hall on a drum, four minarets, arcaded courtyard with fountain and palms.");
        add("villa", "modern", "Modern villa: cantilevered white volumes, full-height glass, flat roofs, pool terrace.");
        add("village", "medieval", "A village that grows by generations: main street, houses on plots, well, chapel, lamps, trees. Param generations=1..5.");
    }

    private static void add(String n, String s, String d) { ALL.put(n, new Info(n, s, d)); }

    public static List<String> names() { return List.copyOf(ALL.keySet()); }

    /** Builds a template into the canvas. scale 1 = normal, 2 = larger variants where supported. */
    public static void build(String name, Canvas c, int scale, int generations) {
        switch (name) {
            case "cottage" -> cottage(c, 0, 0);
            case "townhouse" -> townhouse(c);
            case "longhouse" -> longhouse(c);
            case "watchtower" -> watchtower(c);
            case "wizard_tower" -> wizardTower(c);
            case "castle" -> castle(c, scale);
            case "cathedral" -> cathedral(c, scale);
            case "pagoda" -> pagodaGarden(c);
            case "temple" -> temple(c, scale);
            case "palace" -> palace(c);
            case "villa" -> villa(c);
            case "village" -> village(c, Math.max(1, generations));
            default -> throw new IllegalArgumentException("unknown template '" + name + "'. Available: " + names());
        }
        Connectivity.apply(c.model());
    }

    // ------------------------------------------------------------------ small buildings

    static void cottage(Canvas c, int ox, int oz) {
        Modules.HouseOpts h = new Modules.HouseOpts();
        h.floors = 1; h.jetty = false; h.bay = 4;
        Modules.house(c, ox + 2, 0, oz + 4, 9, 7, h);
        // garden fence and path
        for (int x = ox; x <= ox + 12; x++) { fenceOrGate(c, x, oz, x == ox + 6); fenceOrGate(c, x, oz + 13, false); }
        for (int z = oz; z <= oz + 13; z++) { c.set(ox, 1, z, "@trim:fence"); c.set(ox + 12, 1, z, "@trim:fence"); }
        for (int z = oz + 1; z <= oz + 2; z++) c.set(ox + 6, 0, z, "@path");
        for (int x = ox + 1; x <= ox + 11; x++) for (int z = oz + 1; z <= oz + 12; z++) if (!c.has(x, 0, z)) c.set(x, 0, z, "grass_block");
        for (int x = ox + 1; x <= ox + 11; x += 2) if (x != ox + 5 && x != ox + 7) c.set(x, 1, oz + 1, "@plant");
        Modules.tree(c, ox + 10, 1, oz + 11, "oak", 1);
    }

    private static void fenceOrGate(Canvas c, int x, int z, boolean gate) {
        if (gate) c.set(x, 1, z, "@trim:fence", Map.of());
        else c.set(x, 1, z, "@trim:fence");
    }

    static void townhouse(Canvas c) {
        Modules.HouseOpts h = new Modules.HouseOpts();
        h.floors = 2; h.jetty = true; h.bay = 5;
        Modules.house(c, 0, 0, 1, 11, 9, h);
        Modules.lamp(c, -2, 0, -1, Dir.EAST);
        Modules.lamp(c, 12, 0, -1, Dir.WEST);
        for (int x = -2; x <= 12; x++) c.set(x, 0, -1, "@path");
    }

    static void longhouse(Canvas c) {
        Modules.HouseOpts h = new Modules.HouseOpts();
        h.floors = 1; h.jetty = false; h.pitch = 2; h.bay = 4; h.floorHeight = 4; h.chimney = false;
        Modules.house(c, 0, 0, 0, 23, 9, h);
        // crossed "horn" beams rising from the ridge at each gable
        int ridge = 0;
        for (int x : new int[]{-1, 23}) {
            int top = c.groundAt(x, 4, 40);
            ridge = Math.max(ridge, top);
            c.set(x, top, 4, "@trim:fence");
            c.set(x, top, 3, "@trim:fence"); c.set(x, top, 5, "@trim:fence");
            c.set(x, top + 1, 3, "@trim:fence"); c.set(x, top + 1, 5, "@trim:fence");
        }
        // smoke hole and palisade yard
        c.set(11, c.groundAt(11, 4, 40), 4, "campfire", Map.of("lit", "true", "signal_fire", "false"));
        for (int x = -4; x <= 26; x++) for (int z : new int[]{-5, 13}) palisade(c, x, z, x == 11 && z == -5);
        for (int z = -5; z <= 13; z++) { palisade(c, -4, z, false); palisade(c, 26, z, false); }
        for (int z = -4; z <= -1; z++) c.set(11, 0, z, "@path");
        Modules.lamp(c, 9, 0, -3, Dir.EAST);
        Modules.lamp(c, 13, 0, -3, Dir.WEST);
    }

    private static void palisade(Canvas c, int x, int z, boolean gap) {
        if (gap) return;
        int h = 3 + (Math.floorMod(x * 7 + z * 13, 3) == 0 ? 1 : 0);
        for (int y = 0; y < h; y++) c.set(x, y, z, "@trim", Modules.logAxis('y'));
        c.set(x, h, z, "@trim:fence");
    }

    static void watchtower(Canvas c) {
        // rocky plinth
        c.ellipsoid(7, 0, 7, 7.5, 3, 7.5, "@foundation", false, "top");
        Modules.TowerOpts t = new Modules.TowerOpts();
        t.roof = "cone"; t.roofPitch = 2.2;
        Modules.roundTower(c, 7, 3, 7, 4, 18, t);
        // stair up the rock to the door
        for (int i = 0; i < 4; i++) for (int w = -1; w <= 1; w++) {
            c.set(7 + w, i, 7 - 8 + i, "@foundation:stairs", Canvas.stairs(Dir.SOUTH, false));
            for (int y = 0; y < i; y++) c.set(7 + w, y, 7 - 8 + i, "@foundation");
        }
        Modules.lamp(c, 4, 3, 1, Dir.EAST);
    }

    static void wizardTower(Canvas c) {
        c.cylinder(10, 0, 10, 8, 1, "@path", false, 'y');
        Modules.TowerOpts t = new Modules.TowerOpts();
        t.roof = "spire"; t.roofPitch = 3.4; t.floorEvery = 7;
        int top = Modules.roundTower(c, 10, 0, 10, 5, 34, t);
        // turrets on corbels at different heights
        int[][] tur = {{4, 15, 12, 2}, {16, 21, 9, 2}, {10, 26, 16, 2}};
        for (int[] q : tur) {
            int tx = q[0], ty = q[1], tz = q[2];
            // corbel: inverted cone of stairs + supports so nothing floats
            for (int k = 0; k < 3; k++) c.cylinder(tx, ty - 3 + k, tz, 0.6 + k * 0.7, 1, "@foundation", false, 'y');
            Modules.TowerOpts tt = new Modules.TowerOpts();
            tt.roof = "spire"; tt.door = false; tt.plinth = false; tt.bands = false; tt.floorEvery = 99; tt.roofPitch = 3.5;
            Modules.roundTower(c, tx, ty, tz, 2.4, 7, tt);
            // join to main tower
            c.line(tx, ty - 3, tz, 10, ty - 6, 10, "@foundation", 2);
        }
        for (Dir d : Dir.values()) Modules.tree(c, 10 + d.dx * 11, 0, 10 + d.dz * 11, "azalea", 1);
        Modules.lamp(c, 7, 0, 2, Dir.EAST);
        Modules.lamp(c, 13, 0, 2, Dir.WEST);
    }

    // ------------------------------------------------------------------ castle

    static void castle(Canvas c, int scale) {
        int S = scale >= 2 ? 64 : 48; // outer wall square
        int h = 10;
        int g = S / 2;
        // courtyard ground
        c.fill(2, 0, 2, S - 2, 0, S - 2, "@path");
        // walls: north split for gatehouse
        Modules.curtainWall(c, 3, 2, g - 6, 2, 0, h, 3, Dir.NORTH, 6);
        Modules.curtainWall(c, g + 6, 2, S - 3, 2, 0, h, 3, Dir.NORTH, 6);
        Modules.curtainWall(c, 3, S - 2, S - 3, S - 2, 0, h, 3, Dir.SOUTH, 6);
        Modules.curtainWall(c, 2, 3, 2, S - 3, 0, h, 3, Dir.WEST, 6);
        Modules.curtainWall(c, S - 2, 3, S - 2, S - 3, 0, h, 3, Dir.EAST, 6);
        Modules.gatehouse(c, g, 0, 2, 5, h + 2, 3);
        // corner towers
        Modules.TowerOpts t = new Modules.TowerOpts();
        t.roof = "cone"; t.door = false; t.roofPitch = 2.0;
        for (int[] p : new int[][]{{2, 2}, {S - 2, 2}, {2, S - 2}, {S - 2, S - 2}}) Modules.roundTower(c, p[0], 0, p[1], 4.5, h + 7, t);
        // keep at the back centre
        int ks = scale >= 2 ? 15 : 13;
        int kx = g - ks / 2, kz = S - ks - 8;
        Modules.squareTower(c, kx, 0, kz, ks, 24, "crenellated", true, Dir.NORTH);
        Modules.TowerOpts kt = new Modules.TowerOpts();
        kt.roof = "spire"; kt.door = false; kt.roofPitch = 3; kt.plinth = false;
        Modules.roundTower(c, kx + ks, 0, kz, 3, 30, kt);
        Modules.roundTower(c, kx, 0, kz, 3, 30, kt);
        // courtyard buildings
        Modules.HouseOpts hh = new Modules.HouseOpts();
        hh.floors = 2; hh.front = Dir.EAST; hh.jetty = false;
        Modules.house(c, 7, 0, 12, 9, 15, hh);
        Modules.HouseOpts hs = new Modules.HouseOpts();
        hs.floors = 1; hs.front = Dir.WEST; hs.jetty = false; hs.chimney = true;
        Modules.house(c, S - 16, 0, 12, 8, 13, hs);
        Modules.fountain(c, g, 0, 18, 3);
        for (int z = 3; z < kz - 1; z++) for (int x = g - 1; x <= g + 1; x++) if (!c.has(x, 1, z)) c.set(x, 0, z, "@foundation");
        Modules.lamp(c, g - 3, 0, 10, Dir.EAST);
        Modules.lamp(c, g + 3, 0, 10, Dir.WEST);
        Modules.tree(c, 9, 0, 33, "oak", 2);
        Modules.tree(c, S - 10, 0, 33, "oak", 2);
    }

    // ------------------------------------------------------------------ cathedral

    static void cathedral(Canvas c, int scale) {
        int cx = 20;
        int L = scale >= 2 ? 64 : 48;            // nave length (z)
        int nh = scale >= 2 ? 22 : 18;          // nave wall height
        int ah = nh / 2;                         // aisle wall height
        int T = (int) (L * 0.62);                // transept start z
        int tw = 11;                             // transept width (z)
        int bay = 4;
        // ground plinth
        c.fill(cx - 13, 0, 0, cx + 13, 0, L + 6, "@foundation");
        c.fill(cx - 19, 0, T - 1, cx + 19, 0, T + tw, "@foundation");
        // nave & aisles shells
        c.walls(cx - 5, 1, 0, cx + 5, nh, L, "@wall");
        c.walls(cx - 10, 1, 9, cx - 5, ah, L, "@wall");
        c.walls(cx + 5, 1, 9, cx + 10, ah, L, "@wall");
        c.fill(cx - 9, 0, 1, cx + 9, 0, L - 1, "@floor");
        // arcades between nave and aisles
        for (int z = 10; z + 2 < L; z += bay) {
            c.arch('z', z, z + 2, 1, 4, cx - 5, 1, "pointed", null, 1, true);
            c.arch('z', z, z + 2, 1, 4, cx + 5, 1, "pointed", null, 1, true);
        }
        // clerestory and aisle windows, buttresses with flying arches
        for (int z = 10; z + 2 < L; z += bay) {
            if (z + 2 >= T - 1 && z <= T + tw) continue;
            for (int y = ah + 3; y <= nh - 3; y++) { c.set(cx - 5, y, z + 1, "@window"); c.set(cx + 5, y, z + 1, "@window"); }
            for (int y = 3; y <= ah - 2; y++) { c.set(cx - 10, y, z + 1, "@window"); c.set(cx + 10, y, z + 1, "@window"); }
            c.set(cx - 10, ah - 1, z + 1, "@window"); c.set(cx + 10, ah - 1, z + 1, "@window");
            c.set(cx - 11, 2, z + 1, "@trim:stairs", Canvas.stairs(Dir.EAST, true));
            c.set(cx + 11, 2, z + 1, "@trim:stairs", Canvas.stairs(Dir.WEST, true));
            int bz = z - 1;
            if (bz < 10) continue;
            for (int sgn : new int[]{-1, 1}) {
                int bx1 = cx + sgn * 11, bx2 = cx + sgn * 12;
                for (int y = 1; y <= ah + 2; y++) c.set(bx1, y, bz, "@wall");
                for (int y = 1; y <= ah - 3; y++) c.set(bx2, y, bz, "@foundation");
                c.set(bx2, ah - 2, bz, "@wall:stairs", Canvas.stairs(sgn < 0 ? Dir.EAST : Dir.WEST, false));
                // pinnacle
                c.set(bx1, ah + 3, bz, "@wall:wall");
                c.set(bx1, ah + 4, bz, "@trim:wall");
                c.set(bx1, ah + 5, bz, "lightning_rod");
                // flying buttress from pinnacle base to nave wall
                c.line(cx + sgn * 10, ah + 2, bz, cx + sgn * 6, nh - 3, bz, "@wall", 1);
                c.line(cx + sgn * 10, ah + 1, bz, cx + sgn * 6, nh - 4, bz, "@wall:slab[type=top]", 1);
            }
        }
        // roofs: steep nave, shed aisles
        Roofs.Spec nr = new Roofs.Spec();
        nr.x1 = cx - 5; nr.x2 = cx + 5; nr.z1 = 0; nr.z2 = L; nr.y = nh + 1; nr.axis = 'z'; nr.pitch = 2; nr.trim = "@roof_trim"; nr.gable = "@wall";
        Roofs.build(c, nr);
        for (int sgn : new int[]{-1, 1}) {
            Roofs.Spec ar = new Roofs.Spec();
            ar.x1 = sgn < 0 ? cx - 10 : cx + 6; ar.x2 = sgn < 0 ? cx - 6 : cx + 10; ar.z1 = 9; ar.z2 = L; ar.y = ah + 1;
            ar.type = Roofs.Type.SHED; ar.shedHigh = sgn < 0 ? Dir.EAST : Dir.WEST; ar.overhang = 1; ar.trim = null; ar.gable = "@wall";
            Roofs.build(c, ar);
        }
        // transept
        c.walls(cx - 18, 1, T, cx + 18, nh, T + tw - 1, "@wall");
        c.carve(cx - 4, 1, T + 1, cx + 4, nh - 1, T + tw - 2);
        Roofs.Spec tr = new Roofs.Spec();
        tr.x1 = cx - 18; tr.x2 = cx + 18; tr.z1 = T; tr.z2 = T + tw - 1; tr.y = nh + 1; tr.axis = 'x'; tr.pitch = 2; tr.trim = "@roof_trim"; tr.gable = "@wall";
        Roofs.build(c, tr);
        for (int sgn : new int[]{-1, 1}) {
            int x = cx + sgn * 18;
            c.cylinder(x, nh - 4, T + tw / 2, 2.6, 1, "@window", false, 'x');
            for (int y = 3; y <= nh - 9; y++) { c.set(x, y, T + tw / 2 - 2, "@window"); c.set(x, y, T + tw / 2 + 2, "@window"); }
            for (int y = 1; y <= nh; y++) { c.set(x + sgn, y, T, "@wall"); c.set(x + sgn, y, T + tw - 1, "@wall"); }
            c.set(x + sgn, nh + 1, T, "@trim:wall"); c.set(x + sgn, nh + 1, T + tw - 1, "@trim:wall");
            c.set(x + sgn, nh + 2, T, "lightning_rod"); c.set(x + sgn, nh + 2, T + tw - 1, "lightning_rod");
        }
        // crossing tower with spire
        int cy = nh + 1;
        c.walls(cx - 5, cy, T, cx + 5, cy + 8, T + tw - 1, "@wall");
        for (int y = cy + 2; y <= cy + 6; y++) for (int k : new int[]{-2, 0, 2}) {
            c.set(cx + k, y, T, "@window"); c.set(cx + k, y, T + tw - 1, "@window");
            c.set(cx - 5, y, T + tw / 2 + k, "@window"); c.set(cx + 5, y, T + tw / 2 + k, "@window");
        }
        Roofs.Spec sp = new Roofs.Spec();
        sp.x1 = cx - 5; sp.x2 = cx + 5; sp.z1 = T; sp.z2 = T + tw - 1; sp.y = cy + 9; sp.type = Roofs.Type.SPIRE; sp.pitch = 3; sp.trim = "@roof_trim";
        Roofs.build(c, sp);
        // apse
        c.cylinder(cx, 1, L, 5, nh - 2, "@wall", true, 'y');
        c.cylinder(cx, 0, L, 5, 1, "@foundation", false, 'y');
        c.carve(cx - 4, 1, L - 3, cx + 4, nh - 3, L);
        for (int y = 4; y <= nh - 6; y++) for (int[] p : new int[][]{{cx - 4, L + 3}, {cx + 4, L + 3}, {cx, L + 5}}) c.set(p[0], y, p[1], "@window");
        Modules.cone(c, cx, nh - 1, L, 6, 9);
        // west front: twin towers, portal, rose window
        Modules.squareTower(c, cx - 14, 0, 0, 9, nh + 12, "spire", false, Dir.NORTH);
        Modules.squareTower(c, cx + 6, 0, 0, 9, nh + 12, "spire", false, Dir.NORTH);
        c.fill(cx - 5, 1, 0, cx + 5, nh, 0, "@wall");
        c.arch('x', cx - 3, cx + 3, 1, 4, -1, 2, "pointed", null, 1, true);
        c.arch('x', cx - 4, cx + 4, 1, 4, -1, 1, "pointed", "@accent", 1, false);
        for (int x = cx - 1; x <= cx + 1; x++) for (int y = 1; y <= 4; y++) c.air(x, y, 0);
        c.set(cx - 1, 1, 1, "dark_oak_door", Map.of("facing", "south", "half", "lower", "hinge", "left"));
        c.set(cx - 1, 2, 1, "dark_oak_door", Map.of("facing", "south", "half", "upper", "hinge", "left"));
        c.set(cx + 1, 1, 1, "dark_oak_door", Map.of("facing", "south", "half", "lower", "hinge", "right"));
        c.set(cx + 1, 2, 1, "dark_oak_door", Map.of("facing", "south", "half", "upper", "hinge", "right"));
        c.set(cx, 1, 1, "@column"); c.set(cx, 2, 1, "@column");
        c.cylinder(cx, nh - 6, 0, 4.5, 1, "@trim", false, 'z');
        c.cylinder(cx, nh - 6, 0, 3.5, 1, "@window", false, 'z');
        c.cylinder(cx, nh - 6, -1, 4.6, 1, "@accent", true, 'z');
        c.set(cx, nh - 6, 0, "@trim");
        // gallery of statues/niches across the facade
        for (int x = cx - 4; x <= cx + 4; x += 2) { c.set(x, nh - 12, -1, "@accent:stairs", Canvas.stairs(Dir.SOUTH, true)); c.set(x, nh - 11, -1, "@accent:wall"); }
        // forecourt
        for (int x = cx - 6; x <= cx + 6; x++) for (int z = -7; z <= -1; z++) c.set(x, 0, z, "@path");
        Modules.lamp(c, cx - 7, 0, -4, Dir.EAST);
        Modules.lamp(c, cx + 7, 0, -4, Dir.WEST);
    }

    // ------------------------------------------------------------------ east asian

    static void pagodaGarden(Canvas c) {
        for (int x = -6; x <= 24; x++) for (int z = -6; z <= 24; z++) c.set(x, 0, z, (x + z) % 7 == 0 ? "@foundation" : "@path");
        Modules.pagoda(c, 3, 0, 3, 13, 5);
        for (int[] p : new int[][]{{-3, -3}, {21, -3}, {-3, 21}, {21, 21}}) Modules.tree(c, p[0], 1, p[1], "cherry", 2);
        // stone lanterns (toro)
        for (int[] p : new int[][]{{5, -4}, {13, -4}}) {
            c.set(p[0], 1, p[1], "@foundation:wall");
            c.set(p[0], 2, p[1], "@foundation:wall");
            c.set(p[0], 3, p[1], "@light");
            c.set(p[0], 4, p[1], "@foundation:slab");
        }
        // torii gate
        for (int x : new int[]{6, 12}) for (int y = 1; y <= 6; y++) c.set(x, y, -6, "@column", Modules.logAxis('y'));
        for (int x = 5; x <= 13; x++) c.set(x, 6, -6, "@accent");
        for (int x = 4; x <= 14; x++) c.set(x, 7, -6, x == 4 || x == 14 ? "@roof_trim:slab" : "@roof_trim");
        c.set(9, 5, -6, "@accent");
        for (int x = 6; x <= 12; x++) c.set(x, 4, -6, "@accent:slab[type=top]");
    }

    // ------------------------------------------------------------------ classical

    static void temple(Canvas c, int scale) {
        int W = scale >= 2 ? 25 : 19, Dp = scale >= 2 ? 37 : 29, colH = scale >= 2 ? 11 : 9;
        // three-step stylobate
        for (int s = 0; s < 3; s++) {
            c.fill(s, s, s, W - 1 - s, s, Dp - 1 - s, "@foundation");
            for (int x = s; x <= W - 1 - s; x++) { c.set(x, s + 1, s, "@foundation:slab"); }
        }
        int y0 = 3;
        c.fill(3, y0, 3, W - 4, y0, Dp - 4, "@floor");
        // cella
        c.walls(7, y0 + 1, 8, W - 8, y0 + colH, Dp - 7, "@wall");
        for (int y = y0 + 1; y <= y0 + 5; y++) for (int x = W / 2 - 1; x <= W / 2 + 1; x++) c.air(x, y, 8);
        for (int y = y0 + 1; y <= y0 + 6; y++) { c.set(W / 2 - 2, y, 7, "@column", Modules.logAxis('y')); c.set(W / 2 + 2, y, 7, "@column", Modules.logAxis('y')); }
        // peristyle
        Modules.colonnade(c, 4, 4, W - 5, 4, y0 + 1, colH, 3);
        Modules.colonnade(c, 4, Dp - 5, W - 5, Dp - 5, y0 + 1, colH, 3);
        Modules.colonnade(c, 4, 7, 4, Dp - 8, y0 + 1, colH, 3);
        Modules.colonnade(c, W - 5, 7, W - 5, Dp - 8, y0 + 1, colH, 3);
        int ent = y0 + 1 + colH;
        c.fill(3, ent, 3, W - 4, ent + 1, Dp - 4, "@wall");
        for (int x = 3; x <= W - 4; x++) { c.set(x, ent + 1, 2, "@accent:stairs", Canvas.stairs(Dir.SOUTH, true)); c.set(x, ent + 1, Dp - 3, "@accent:stairs", Canvas.stairs(Dir.NORTH, true)); }
        // pediment roof (low pitch) with a trimmed tympanum
        Roofs.Spec r = new Roofs.Spec();
        r.x1 = 3; r.x2 = W - 4; r.z1 = 3; r.z2 = Dp - 4; r.y = ent + 2; r.axis = 'z'; r.pitch = 0.5; r.overhang = 1; r.trim = "@roof_trim"; r.gable = "@wall_alt";
        Roofs.build(c, r);
        for (int x = 3; x <= W - 4; x++) { c.set(x, ent + 2, 2, "@trim:slab"); c.set(x, ent + 2, Dp - 3, "@trim:slab"); }
        c.set(W / 2, ent + 4, 2, "@accent");
        // braziers
        c.trySet(1, 1, -1, "@foundation:wall", "@column"); c.set(1, 2, -1, "@light");
        c.trySet(W - 2, 1, -1, "@foundation:wall", "@column"); c.set(W - 2, 2, -1, "@light");
        c.set(1, 0, -1, "@foundation"); c.set(W - 2, 0, -1, "@foundation");
    }

    // ------------------------------------------------------------------ desert

    static void palace(Canvas c) {
        int S = 41, m = S / 2;
        c.fill(0, 0, 0, S - 1, 0, S - 1, "@path");
        // outer walls with crenellations and arcade
        c.walls(0, 1, 0, S - 1, 6, S - 1, "@wall");
        for (int i = 0; i < S; i += 2) {
            for (int[] p : new int[][]{{i, 0}, {i, S - 1}, {0, i}, {S - 1, i}}) c.set(p[0], 7, p[1], "@wall");
        }
        for (int a = 3; a < S - 3; a += 4) {
            c.arch('x', a, a + 2, 1, 2, 1, 1, "round", null, 1, true);
            c.arch('x', a, a + 2, 1, 2, S - 2, 1, "round", null, 1, true);
        }
        // gate
        c.arch('x', m - 2, m + 2, 1, 2, 0, 1, "pointed", null, 1, true);
        c.arch('x', m - 3, m + 3, 1, 3, -1, 1, "pointed", "@accent", 1, false);
        // courtyard arcade (inner gallery)
        for (int a = 4; a <= S - 5; a += 3) {
            for (int[] p : new int[][]{{a, 4}, {a, S - 5}, {4, a}, {S - 5, a}}) for (int y = 1; y <= 4; y++) c.set(p[0], y, p[1], "@column");
        }
        c.walls(4, 5, 4, S - 5, 5, S - 5, "@trim");
        for (int x = 1; x < S - 1; x++) for (int z = 1; z < S - 1; z++) if (x <= 4 || x >= S - 5 || z <= 4 || z >= S - 5) c.set(x, 6, z, "@roof:slab");
        Modules.fountain(c, m, 0, 11, 4);
        // main hall with drum and dome
        int hx = m - 8, hz = m - 2;
        c.fill(hx, 1, hz, hx + 16, 10, hz + 14, "@wall");
        c.carve(hx + 1, 1, hz + 1, hx + 15, 9, hz + 13);
        c.walls(hx - 1, 10, hz - 1, hx + 17, 11, hz + 15, "@trim");
        for (int x = hx + 2; x <= hx + 14; x += 3) { c.arch('x', x, x + 1, 1, 4, hz, 1, "pointed", null, 1, true); }
        c.cylinder(m, 11, hz + 7, 6, 4, "@wall", true, 'y');
        for (int i = 0; i < 8; i++) {
            double ang = i * Math.PI / 4;
            c.set((int) Math.round(m + Math.sin(ang) * 6), 13, (int) Math.round(hz + 7 - Math.cos(ang) * 6), "@window");
        }
        c.ellipsoid(m, 15, hz + 7, 6, 7, 6, "@accent", true, "top");
        c.set(m, 23, hz + 7, "@trim:wall");
        c.set(m, 24, hz + 7, "lightning_rod");
        c.fill(hx, 10, hz, hx + 16, 10, hz + 14, "@roof:full");
        // minarets
        Modules.TowerOpts t = new Modules.TowerOpts();
        t.roof = "dome"; t.door = false; t.bands = true; t.floorEvery = 6;
        for (int[] p : new int[][]{{0, 0}, {S - 1, 0}, {0, S - 1}, {S - 1, S - 1}}) Modules.roundTower(c, p[0], 0, p[1], 2.4, 22, t);
        for (int[] p : new int[][]{{9, 9}, {S - 10, 9}, {9, S - 10}, {S - 10, S - 10}}) Modules.tree(c, p[0], 1, p[1], "palm", 2);
        // lanterns hung in the arcade and flanking the gate
        for (int a = 7; a <= S - 8; a += 6) {
            for (int[] p : new int[][]{{a, 2}, {a, S - 3}, {2, a}, {S - 3, a}}) c.set(p[0], 5, p[1], "@light", Map.of("hanging", "true"));
        }
        c.set(m - 4, 4, -1, "@light"); c.set(m + 4, 4, -1, "@light");
        c.set(m - 4, 3, -1, "@accent"); c.set(m + 4, 3, -1, "@accent");
        c.set(m - 4, 2, -1, "@accent"); c.set(m + 4, 2, -1, "@accent");
        c.set(m - 4, 1, -1, "@accent"); c.set(m + 4, 1, -1, "@accent");
    }

    // ------------------------------------------------------------------ modern

    static void villa(Canvas c) {
        // ground volume
        c.fill(0, 0, 0, 22, 0, 14, "@foundation");
        c.walls(0, 1, 2, 14, 4, 14, "@wall");
        c.fill(1, 0, 3, 13, 0, 13, "@floor");
        for (int x = 2; x <= 12; x++) for (int y = 1; y <= 4; y++) c.set(x, y, 2, x % 4 == 2 ? "@accent" : "glass");
        // cantilevered upper volume
        c.fill(6, 5, 0, 22, 5, 11, "@roof");
        c.walls(6, 6, 0, 22, 9, 11, "@wall");
        for (int x = 8; x <= 20; x++) for (int y = 6; y <= 9; y++) c.set(x, y, 0, x % 4 == 0 ? "@accent" : "light_gray_stained_glass");
        for (int z = 2; z <= 9; z++) for (int y = 6; y <= 8; y++) c.set(22, y, z, z % 3 == 0 ? "@accent" : "glass");
        for (int z = 1; z <= 10; z++) for (int y = 6; y <= 9; y++) if (z % 2 == 0) c.set(6, y, z, "@wall_alt");
        c.fill(5, 10, -1, 23, 10, 12, "@roof:slab");
        c.fill(-1, 5, 1, 5, 5, 15, "@roof:slab");
        // support column for the cantilever and warm-wood feature wall
        for (int y = 1; y <= 4; y++) { c.set(21, y, 1, "@column"); c.set(21, y, 10, "@column"); }
        for (int y = 1; y <= 4; y++) for (int z = 4; z <= 12; z++) c.set(0, y, z, z % 2 == 0 ? "@wall_alt" : "@wall");
        // pool terrace
        c.fill(15, 0, 12, 30, 0, 22, "@path");
        c.fill(17, 0, 14, 28, 0, 20, "water");
        c.fill(16, -1, 13, 29, -1, 21, "@foundation");
        for (int x = 15; x <= 30; x++) { c.set(x, 1, 22, "glass_pane"); }
        for (int z = 12; z <= 22; z++) c.set(30, 1, z, "glass_pane");
        for (int[] p : new int[][]{{2, 0, 0}, {13, 0, 0}, {16, 0, 12}}) c.set(p[0], 1, p[2], "@light");
        c.set(2, 0, 0, "@foundation"); c.set(13, 0, 0, "@foundation");
        Modules.tree(c, 27, 1, 3, "azalea", 1);
        c.set(27, 0, 3, "@foundation");
    }

    // ------------------------------------------------------------------ village (generational)

    static void village(Canvas c, int generations) {
        java.util.Random rnd = new java.util.Random(1337);
        int street = 0; // street runs along z at x = 0..4
        int length = 18 + generations * 22;
        for (int z = -4; z <= length; z++) for (int x = -1; x <= 5; x++) c.set(x, 0, z, (x == -1 || x == 5) ? "@foundation" : "@path");
        // well at the square
        int wx = 2, wz = 6;
        c.cylinder(wx, 0, wz, 2, 1, "@foundation", false, 'y');
        c.ring(wx, 1, wz, 2, "@foundation:wall");
        c.set(wx, 0, wz, "water");
        for (int[] p : new int[][]{{wx - 2, wz}, {wx + 2, wz}}) for (int y = 1; y <= 3; y++) c.set(p[0], y, p[1], "@trim:fence");
        for (int x = wx - 2; x <= wx + 2; x++) c.set(x, 4, wz, "@roof:slab");
        c.set(wx, 3, wz, "@light", Map.of("hanging", "true"));
        // plots: each generation adds two to four houses further down the street
        int z = 14;
        for (int gen = 1; gen <= generations; gen++) {
            int perSide = 2;
            for (int k = 0; k < perSide; k++) {
                for (int side = 0; side < 2; side++) {
                    int w = 8 + rnd.nextInt(3), d = 7 + rnd.nextInt(3);
                    Modules.HouseOpts h = new Modules.HouseOpts();
                    h.floors = 1 + (rnd.nextInt(10) < 4 ? 1 : 0);
                    h.jetty = h.floors > 1 && rnd.nextBoolean();
                    h.roof = rnd.nextInt(5) == 0 ? Roofs.Type.HIP : Roofs.Type.GABLE;
                    h.front = side == 0 ? Dir.EAST : Dir.WEST;
                    h.chimney = rnd.nextBoolean();
                    int hx = side == 0 ? -3 - w : 7;
                    Modules.house(c, hx, 0, z, w, d, h);
                    // front garden plants
                    if (rnd.nextBoolean()) Modules.tree(c, side == 0 ? hx - 3 : hx + w + 2, 0, z + d / 2, rnd.nextBoolean() ? "oak" : "birch", 1);
                }
                z += 11;
            }
            Modules.lamp(c, -2, 0, z - 6, Dir.EAST);
            Modules.lamp(c, 6, 0, z - 6, Dir.WEST);
        }
        // chapel at the head of the street (beyond the last plot)
        int cz = z + 2;
        c.fill(-4, 0, cz, 8, 0, cz + 20, "@foundation");
        c.walls(-3, 1, cz + 6, 7, 9, cz + 20, "@wall");
        Roofs.Spec r = new Roofs.Spec();
        r.x1 = -3; r.x2 = 7; r.z1 = cz + 6; r.z2 = cz + 20; r.y = 10; r.axis = 'z'; r.pitch = 1.0; r.trim = "@roof_trim"; r.gable = "@wall";
        Roofs.build(c, r);
        for (int zz = cz + 8; zz <= cz + 18; zz += 3) for (int y = 3; y <= 6; y++) { c.set(-3, y, zz, "@window"); c.set(7, y, zz, "@window"); }
        Modules.squareTower(c, -1, 0, cz, 7, 18, "spire", true, Dir.NORTH);
        Modules.lamp(c, -2, 0, cz - 2, Dir.EAST);
        Modules.lamp(c, 6, 0, cz - 2, Dir.WEST);
    }
}
