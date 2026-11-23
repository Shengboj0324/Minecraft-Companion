package io.github.shengboj0324.architect;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import io.github.shengboj0324.architect.ai.AgentSettings;
import io.github.shengboj0324.architect.ai.ConversationAgent;
import io.github.shengboj0324.architect.ai.LandmarkLibrary;
import io.github.shengboj0324.architect.core.*;
import io.github.shengboj0324.architect.npc.PathFinder;
import io.github.shengboj0324.architect.world.*;
import java.lang.reflect.Proxy;
import java.util.*;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

/** Mega-build engine, landmark data, NPC pathfinding and tiled placement. */
class MegaTest {

    private static VoxelModel run(String opsJson) {
        VoxelModel m = new VoxelModel();
        Canvas c = new Canvas(m, new Materials(StyleLibrary.bundled().get("modern"), 1));
        Ops.Result r = new Ops(c).apply(JsonParser.parseString(opsJson).getAsJsonArray());
        assertEquals(0, r.failed(), r.summary());
        return m;
    }

    @Test
    void expressionsEvaluateWithPrecedenceAndRejectUnknowns() {
        double[] v = new double[Expr.VARS.length];
        v[0] = 3; v[1] = 4;
        assertEquals(11, Expr.compile("x + y * 2").eval(v), 1e-9);
        assertEquals(5, Expr.compile("hypot(x, y)").eval(v), 1e-9);
        assertEquals(1, Expr.compile("x < y && !(x == 4) || false").eval(v), 1e-9);
        assertEquals(81, Expr.compile("x ^ 2 ^ 2").eval(v), 1e-9); // right associative: 3^(2^2)
        assertEquals(-9, Expr.compile("-x^2").eval(v), 1e-9);
        assertEquals(1, Expr.compile("sin(90)").eval(v), 1e-9);
        assertEquals(2, Expr.compile("x > 2 ? mod(-1, 3) : 0").eval(v), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> Expr.compile("foo(1)"));
        assertThrows(IllegalArgumentException.class, () -> Expr.compile("q + 1"));
        assertThrows(IllegalArgumentException.class, () -> Expr.compile("(x + 1"));
    }

    @Test
    void largeScaleOpsMakeWatertightConnectedShapes() {
        VoxelModel dome = run("""
                [{"op":"fill","from":[-25,0,-25],"to":[25,0,25],"block":"stone"},
                 {"op":"revolve","center":[0,1,0],"profile":[[0,20],[8,20],[14,17],[20,11],[24,4],[25,0]],"hollow":true,"block":"white_concrete"},
                 {"op":"loft","center":[60,1,0],"sections":[{"h":0,"w":30,"d":20,"corner":4},{"h":90,"w":12,"d":12,"corner":3,"rot":75}],"hollow":true,"block":"light_blue_stained_glass",
                  "materials":[{"when":"mod(y-1,5)==0","block":"white_concrete"}]},
                 {"op":"fill","from":[40,0,-20],"to":[80,0,20],"block":"stone"},
                 {"op":"polygon","points":[[-60,-10],[-40,-10],[-40,10],[-50,20],[-60,10]],"y":1,"height":12,"hollow":true,"block":"bricks"},
                 {"op":"fill","from":[-62,0,-12],"to":[-38,0,22],"block":"stone"},
                 {"op":"shape","from":[-20,1,40],"to":[20,30,80],"inside":"r < 20 - v*18","block":"sandstone","hollow":true},
                 {"op":"fill","from":[-22,0,38],"to":[22,0,82],"block":"stone"},
                 {"op":"truss","a":[[-30,20,100],[30,20,100]],"b":[[-30,26,100],[30,26,100]],"segments":6,"pattern":"warren","block":"iron_bars"},
                 {"op":"lines","points":[[-30,0,100],[-30,20,100]],"thickness":2,"block":"stone_bricks"},
                 {"op":"lines","points":[[30,0,100],[30,20,100]],"thickness":2,"block":"stone_bricks"},
                 {"op":"fill","from":[0,0,-31],"to":[20,0,-30],"block":"stone"},
                 {"op":"fill","from":[0,1,-30],"to":[20,12,-30],"block":"stone"},
                 {"op":"facade_grid","from":[0,1,-31],"to":[20,12,-31],"facing":"north","bay":4,"floor":4,"depth":0}]""");
        QualityAnalyzer.Report r = new QualityAnalyzer().analyze(dome);
        assertFalse(r.hasCritical(), r.format());
        // dome shell is hollow: the centre of the drum is empty, the wall is not
        assertNull(dome.get(0, 5, 0));
        assertNotNull(dome.get(20, 5, 0));
        // loft twists: the section near the top is rotated, so the top corner moves
        assertNotNull(dome.get(60, 1 + 90, 0));
        // facade grid made glass panes between mullions
        assertTrue(dome.materialCounts().containsKey("glass_pane"));
    }

    @Test
    void noneLeavesCellsEmptyForLattices() {
        VoxelModel m = run("""
                [{"op":"loft","center":[0,0,0],"sections":[{"h":0,"w":11,"d":11},{"h":30,"w":11,"d":11}],"hollow":true,"block":"none",
                  "materials":[{"when":"mod(y,6)<1","block":"stone"},{"when":"mod(y+x,6)<2 || mod(y+z,6)<2","block":"oak_fence"}]}]""");
        long all = 11L * 11 * 31;
        assertTrue(m.size() > 300 && m.size() < all / 2, "lattice should be sparse: " + m.size());
    }

    @Test
    void landmarksAreFoundByNameAndAlias() {
        LandmarkLibrary l = LandmarkLibrary.bundled();
        assertTrue(l.size() >= 25);
        assertEquals("eiffel_tower", l.find("the Eiffel tower").get("id").getAsString());
        assertEquals("colosseum", l.find("coliseum").get("id").getAsString());
        assertEquals("big_ben", l.find("elizabeth tower").get("id").getAsString());
        assertNull(l.find("my house"));
        String d = l.describe("burj khalifa", AgentSettings.defaults("x"));
        assertTrue(d.contains("1:1 does not fit"), d);
        assertTrue(l.describe("parthenon", AgentSettings.defaults("x")).contains("1:1 fits"));
    }

    @Test
    void conversationToolsAreDefined() {
        Set<String> names = new HashSet<>();
        ConversationAgent.definitions().forEach(d -> names.add(d.name()));
        assertTrue(names.containsAll(List.of("build", "template", "expand", "move", "undo", "cancel", "status", "rate", "surroundings", "catalog")));
    }

    @Test
    void pathFinderWalksAroundWallsUpStepsAndDown() {
        Set<String> solid = new HashSet<>();
        for (int x = -20; x <= 20; x++) for (int z = -20; z <= 20; z++) solid.add(x + ",63," + z); // floor
        for (int z = -20; z <= 5; z++) for (int y = 64; y <= 66; y++) solid.add("0," + y + "," + z); // wall with a gap at z > 5
        solid.add("6,64,-3"); solid.add("7,64,-3"); solid.add("7,65,-3");                                    // steps up to y=66
        PathFinder.Terrain t = new PathFinder.Terrain() {
            public boolean solid(int x, int y, int z) { return solid.contains(x + "," + y + "," + z); }
            public boolean passable(int x, int y, int z) { return !solid(x, y, z) && Math.abs(x) <= 20 && Math.abs(z) <= 20; }
        };
        List<int[]> p = PathFinder.find(t, new int[]{-5, 64, -3}, new int[]{5, 64, -3}, 5000, 0.5);
        assertNotNull(p);
        boolean wentAround = p.stream().anyMatch(c -> c[2] > 5);
        assertTrue(wentAround, "path must go around the wall through the gap");
        List<int[]> up = PathFinder.find(t, new int[]{4, 64, -3}, new int[]{7, 66, -3}, 5000, 0.5);
        assertNotNull(up);
        assertEquals(66, up.get(up.size() - 1)[1]);
        List<int[]> down = PathFinder.find(t, new int[]{7, 66, -3}, new int[]{10, 64, -3}, 5000, 0.5);
        assertNotNull(down);
        assertEquals(64, down.get(down.size() - 1)[1]);
    }

    @Test
    void previewsOfMillionBlockModelsAreDownsampled() {
        VoxelModel m = new VoxelModel();
        Canvas c = new Canvas(m, new Materials(StyleLibrary.bundled().get("modern"), 1));
        c.fill(0, 0, 0, 199, 29, 199, "stone"); // 1.2 million cells
        long t0 = System.nanoTime();
        byte[] png = new IsoRenderer().renderSheetPng(m, 1400, 700);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(png.length > 1000);
        assertTrue(ms < 30_000, "render took " + ms + " ms");
        assertTrue(IsoRenderer.forPreview(m, IsoRenderer.MAX_PREVIEW_CELLS).size() <= IsoRenderer.MAX_PREVIEW_CELLS);
        QualityAnalyzer.Report r = new QualityAnalyzer().analyze(m);
        assertNotNull(r);
    }

    /** A gate that refuses each rectangle the first time it is asked, like chunks that are still loading. */
    static final class SlowGate implements ChunkGate {
        final Set<String> asked = new HashSet<>();
        int waits;
        public boolean ensure(World w, int x1, int z1, int x2, int z2) {
            if (asked.add(x1 + "," + z1 + "," + x2 + "," + z2)) { waits++; return false; }
            return true;
        }
        public void release(World w, int x1, int z1, int x2, int z2) { }
        public void releaseAll() { }
    }

    @Test
    void megaBuildsArePlacedTileByTileExactly() {
        PlacementTest.world.clear();
        World w = (World) Proxy.newProxyInstance(MegaTest.class.getClassLoader(), new Class[]{World.class}, (p, mm, a) -> switch (mm.getName()) {
            case "getBlockAt" -> PlacementTest.block((int) a[0], (int) a[1], (int) a[2]);
            case "getMinHeight" -> -64; case "getMaxHeight" -> 320;
            default -> null; });
        for (int x = -120; x <= 120; x++) for (int z = -120; z <= 120; z++) PlacementTest.world.put(PlacementTest.k(x, 63, z), "minecraft:stone");
        Map<String, String> before = new HashMap<>(PlacementTest.world);
        VoxelModel m = run("""
                [{"op":"fill","from":[0,0,0],"to":[99,0,69],"block":"smooth_stone"},
                 {"op":"loft","center":[50,1,35],"sections":[{"h":0,"w":60,"d":40,"corner":8},{"h":40,"w":20,"d":20,"rot":45}],"hollow":true,"block":"white_concrete"},
                 {"op":"revolve","center":[15,1,15],"profile":[[0,6],[20,6],[26,0]],"hollow":true,"block":"bricks"}]""");
        for (int q = 0; q < 4; q += 3) {
            PlacementTest.world.clear();
            PlacementTest.world.putAll(before);
            Placement at = new Placement(w, 10, 63, -20, q);
            UndoJournal j = new UndoJournal(w, "mega").gate(new SlowGate());
            SlowGate gate = new SlowGate();
            BuildJob job = new BuildJob("mega", m.copy(), null, at, j, BuildJob.converter(PlacementTest::data), true, true, 8, gate);
            assertTrue(job.tiles() >= 8, "tiles: " + job.tiles());
            int ticks = 0;
            while (!job.done() && ticks < 100_000) { job.tick(2000, 100); ticks++; }
            assertTrue(job.done());
            assertTrue(gate.waits >= job.tiles(), "every tile waits for its chunks once");
            int bad = 0;
            for (long key : m.keys()) {
                int x = VoxelModel.kx(key), y = VoxelModel.ky(key), z = VoxelModel.kz(key);
                String got = PlacementTest.world.getOrDefault(PlacementTest.k(at.wx(x, z), at.wy(y), at.wz(x, z)), "minecraft:air");
                if (!got.equals(StateTransforms.rotate(m.getByKey(key), q).toNamespaced())) bad++;
            }
            assertEquals(0, bad, "mismatches with rotation " + q);
            int guard = 0;
            while (!j.restoreStep(5000, 100) && guard++ < 100_000) { }
            int diff = 0;
            Set<String> keys = new HashSet<>(PlacementTest.world.keySet());
            keys.addAll(before.keySet());
            for (String kk : keys) if (!PlacementTest.world.getOrDefault(kk, "minecraft:air").equals(before.getOrDefault(kk, "minecraft:air"))) diff++;
            assertEquals(0, diff, "undo differences with rotation " + q);
        }
    }
}
