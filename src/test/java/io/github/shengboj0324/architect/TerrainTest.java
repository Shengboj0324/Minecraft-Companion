package io.github.shengboj0324.architect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.shengboj0324.architect.core.*;
import io.github.shengboj0324.architect.world.*;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.Proxy;
import java.util.Map;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

/** Terrain surveys, site frames, grading, terrain-aware quality checks and natural-site placement. */
class TerrainTest {

    /** A hillside: ground at world y 64 + x/4 (rising east), a pond at x 20..25, trees at x 5. */
    static TerrainMap hillside() {
        TerrainMap t = new TerrainMap(-40, -40, 60, 60);
        for (int x = -40; x <= 60; x++) for (int z = -40; z <= 60; z++) {
            boolean pond = x >= 20 && x <= 25 && z >= 0 && z <= 10;
            int top = 64 + Math.floorDiv(x, 4) - (pond ? 3 : 0);
            t.set(x, z, top, pond ? 3 : 0, x == 5 ? 6 : 0, pond ? "sand" : "grass_block", pond ? "river" : "forest");
        }
        return t;
    }

    @Test
    void referenceViewAndReports() {
        TerrainMap t = hillside();
        Placement xz = new Placement(null, 0, 0, 0, 0);
        // median ground (water surface where wet) over x 0..15: 64..67 -> 66
        assertEquals(66, t.reference(xz, 0, 0, 15, 15, -1));
        Placement frame = new Placement(null, 0, 66, 0, 0);
        TerrainMap.View v = t.view(frame);
        assertEquals(-2, v.groundY(0, 0));
        assertEquals(2, v.groundY(16, 0));
        assertEquals(3, v.waterDepth(22, 5));
        assertEquals(Ground.UNKNOWN, v.groundY(500, 0));
        String report = t.report(frame, 32, 20, 24, false, 8);
        assertTrue(report.contains("Elevation map"), report);
        assertTrue(report.contains("rises"), "slope should be described: " + report);
        assertTrue(report.contains("~"), "water should show on the map");
        assertTrue(report.toLowerCase().contains("woodland"), "forest biome hint expected");
        String survey = t.survey(frame, 0, 0, 10, 10, 1);
        assertTrue(survey.contains("Heights"), survey);
        assertNotNull(t.overview(frame, 32, 32));
        // a rotated frame maps model columns through the rotation
        Placement turned = new Placement(null, 10, 66, 10, 1);
        assertEquals(t.top(turned.wx(3, 7), turned.wz(3, 7)) - 66, t.view(turned).groundY(3, 7));
    }

    @Test
    void gradingBlendsPadIntoHill() {
        TerrainMap t = hillside();
        Placement frame = new Placement(null, 0, 66, 0, 0); // pad at 66: ground is -2 at x=0 and +4 at x=24 in model y
        VoxelModel design = new VoxelModel();
        for (int x = 4; x <= 12; x++) for (int z = 4; z <= 12; z++) design.set(x, 0, z, BlockState.of("stone_bricks"));
        LongOpenHashSet foot = SiteGrader.footprint(design);
        VoxelModel out = design.copy();
        out.setEnvelope(0, 0, 0);
        LongOpenHashSet changed = SiteGrader.grade(out, t.view(frame), foot, 8, 24);
        assertFalse(changed.isEmpty());
        for (long k : foot) assertFalse(changed.contains(k), "footprint columns are levelled by the placer, not graded");
        // just east of the pad the natural ground (x=13 -> 64+3=67 -> +1) is cut toward the pad; far away it is natural
        int nearTop = topOf(out, 13, 8), farTop = topOf(out, 20, 8);
        assertTrue(nearTop <= 1, "near the pad the ground eases to the pad height, got " + nearTop);
        assertTrue(farTop == Ground.UNKNOWN || farTop == Math.floorDiv(20, 4) - 2, "beyond the margin the land is untouched, got " + farTop);
        // west of the pad the ground is below the pad and gets filled up toward it with dirt under grass
        assertEquals("grass_block", out.get(3, topOf(out, 3, 8), 8).id());
        // graded cells never overwrite the design
        assertEquals("stone_bricks", out.get(8, 0, 8).id());
        // pond columns are left alone
        for (int x = 20; x <= 25; x++) assertFalse(changed.contains(VoxelModel.key(x, 0, 5)));
    }

    private static int topOf(VoxelModel m, int x, int z) {
        for (int y = 40; y >= -40; y--) { BlockState s = m.get(x, y, z); if (s != null && !s.isAir()) return y; }
        return Ground.UNKNOWN;
    }

    @Test
    void naturalGroundSupportsBlocks() {
        TerrainMap t = hillside();
        Placement frame = new Placement(null, 0, 66, 0, 0);
        Ground g = t.view(frame);
        // a stilt house over the low (west) side: posts down to the ground at y = -2, a deck at y = 3
        VoxelModel m = new VoxelModel();
        for (int x = 0; x <= 6; x++) for (int z = 0; z <= 6; z++) m.set(x, 3, z, BlockState.of("spruce_planks"));
        for (int[] c : new int[][]{{0, 0}, {6, 0}, {0, 6}, {6, 6}}) for (int y = g.groundY(c[0], c[1]) + 1; y < 3; y++) m.set(c[0], y, c[1], BlockState.of("spruce_log"));
        QualityAnalyzer qa = new QualityAnalyzer();
        assertEquals("0", qa.analyze(m, g).metrics().get("floating_blocks"), "posts reaching the terrain carry the deck");
        // a deck whose post stops short of the land floats on the natural site
        VoxelModel shortPosts = new VoxelModel();
        for (int x = 0; x <= 6; x++) for (int z = 0; z <= 6; z++) shortPosts.set(x, 3, z, BlockState.of("spruce_planks"));
        shortPosts.set(0, 2, 0, BlockState.of("spruce_log"));
        assertTrue(Long.parseLong(qa.analyze(shortPosts, g).metrics().get("floating_blocks")) > 0, "a deck that never reaches the ground floats");
        // a lantern standing directly on the terrain is attached
        VoxelModel lamp = new VoxelModel();
        lamp.set(30, g.groundY(30, 30) + 1, 30, BlockState.of("lantern"));
        assertTrue(qa.analyze(lamp, g).issues().stream().noneMatch(i -> i.message().contains("lack the support")));
    }

    @Test
    void overlayShowsTheLand() {
        TerrainMap t = hillside();
        Placement frame = new Placement(null, 0, 66, 0, 0);
        VoxelModel design = new VoxelModel(32, 16, 16);
        design.set(18, 0, 5, BlockState.of("stone_bricks"));
        VoxelModel natural = t.overlay(design, frame, true, 6, 8, 24);
        assertTrue(natural.size() > design.size());
        int g = t.view(frame).groundY(22, 5);
        assertEquals("water", natural.get(22, g + 3, 5).id(), "the pond's surface is drawn");
        assertEquals("sand", natural.get(22, g, 5).id(), "and its bed");
        VoxelModel level = t.overlay(design, frame, false, 6, 8, 24);
        assertEquals("stone_bricks", level.get(18, 0, 5).id(), "the design itself is unchanged in the overlay");
        assertEquals(1, design.size(), "the overlay never touches the design model");
    }

    @Test
    void naturalPrepClearsOnlyVegetation() {
        Map<String, String> world = PlacementTest.world;
        world.clear();
        World w = (World) Proxy.newProxyInstance(TerrainTest.class.getClassLoader(), new Class[]{World.class}, (p, m, a) -> switch (m.getName()) {
            case "getBlockAt" -> PlacementTest.block((int) a[0], (int) a[1], (int) a[2]);
            case "getMinHeight" -> -64; case "getMaxHeight" -> 320;
            default -> null; });
        // a stone knoll rising inside the footprint, with a tree on it
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) for (int y = 60; y <= 64 + (x > 4 ? 2 : 0); y++) world.put(PlacementTest.k(x, y, z), "minecraft:stone");
        for (int y = 67; y <= 71; y++) world.put(PlacementTest.k(6, y, 4), "minecraft:oak_log");
        VoxelModel m = new VoxelModel();
        for (int x = 0; x <= 8; x++) m.set(x, 6, 4, BlockState.of("oak_planks")); // a beam over the knoll
        Placement at = new Placement(w, 0, 64, 0, 0);
        BuildJob.Prep prep = new BuildJob.Prep(true, b -> b.getBlockData().getAsString().contains("log"), null, -4, 32, false, 0, 0, 0);
        BuildJob job = new BuildJob("n", m, null, at, new UndoJournal(w, "n"), BuildJob.converter(PlacementTest::data), prep, ChunkGate.NONE);
        while (!job.done()) job.tick(1000, 50);
        assertEquals("minecraft:stone", world.get(PlacementTest.k(6, 66, 4)), "natural terrain inside the footprint stays");
        assertEquals("minecraft:stone", world.get(PlacementTest.k(2, 64, 4)));
        assertEquals("minecraft:oak_planks", world.get(PlacementTest.k(3, 70, 4)));
        assertEquals("minecraft:air", world.getOrDefault(PlacementTest.k(6, 68, 4), "minecraft:air"), "the tree in the footprint is cleared");
        // the level prep for the same model would have cut the knoll down to the ground layer
        world.clear();
        for (int x = 0; x <= 8; x++) for (int z = 0; z <= 8; z++) for (int y = 60; y <= 66; y++) world.put(PlacementTest.k(x, y, z), "minecraft:stone");
        BuildJob level = new BuildJob("l", m, null, at, new UndoJournal(w, "l"), BuildJob.converter(PlacementTest::data), BuildJob.Prep.level(m, true, false, 0), ChunkGate.NONE);
        while (!level.done()) level.tick(1000, 50);
        String cut = world.getOrDefault(PlacementTest.k(4, 66, 4), "minecraft:air");
        assertEquals("minecraft:air", cut, "a levelled site clears terrain above the ground layer in used columns");
        world.clear();
    }
}
