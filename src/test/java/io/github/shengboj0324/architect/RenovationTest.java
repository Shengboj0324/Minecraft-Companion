package io.github.shengboj0324.architect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.shengboj0324.architect.core.*;
import org.junit.jupiter.api.Test;

/** Renovating an existing, damaged build: analysis, repair ops, protection, furniture, elevators and the change set. */
class RenovationTest {

    static final int W = 21, H = 14, D = 21;

    /**
     * A two-storey stone house standing in the sea: floors at y=1 and y=6, roof at y=11, an interior wall on the
     * ground floor, a door, a window, a chest, a blast hole through the south wall and the upper floor, sea water
     * flooding the ground floor up to y=3, sea bed at y=0 outside.
     */
    static VoxelModel house(Existing ex) {
        VoxelModel m = new VoxelModel(W + 10, H + 10, D + 10);
        BlockState wall = BlockState.of("stone_bricks"), floor = BlockState.of("spruce_planks");
        for (int x = 2; x <= 18; x++) for (int z = 2; z <= 18; z++) {
            m.set(x, 1, z, floor);
            m.set(x, 6, z, floor);
            m.set(x, 11, z, BlockState.of("deepslate_tiles"));
            for (int y = 2; y <= 10; y++) if (x == 2 || x == 18 || z == 2 || z == 18) m.set(x, y, z, wall);
        }
        for (int z = 3; z <= 17; z++) for (int y = 2; y <= 5; y++) m.set(10, y, z, wall); // interior wall
        m.set(10, 2, 10, BlockState.AIR); m.set(10, 3, 10, BlockState.AIR);           // a doorway in it
        for (int z = 14; z <= 16; z++) for (int y = 2; y <= 4; y++) m.set(10, y, z, BlockState.AIR); // a wide 3x3 opening
        m.set(2, 2, 8, BlockState.parse("spruce_door[facing=west,half=lower,hinge=left]"));
        m.set(2, 3, 8, BlockState.parse("spruce_door[facing=west,half=upper,hinge=left]"));
        m.set(18, 8, 9, BlockState.AIR); m.set(18, 9, 9, BlockState.AIR);             // a neat 1x2 window
        m.set(5, 2, 5, BlockState.parse("chest[facing=south]"));
        // blast damage: a sphere carved through the south wall and the upper floor
        for (int x = 11; x <= 17; x++) for (int y = 3; y <= 9; y++) for (int z = 14; z <= 20; z++)
            if ((x - 14) * (x - 14) + (y - 6) * (y - 6) + (z - 18) * (z - 18) <= 9) m.remove(x, y, z);
        // a loose fragment blown off the roof
        m.set(14, 13, 24, wall); m.set(15, 13, 24, wall);
        if (ex != null) {
            m.forEach((x, y, z, s) -> { if (!s.isAir()) ex.markOriginal(x, y, z); });
            ex.protect(5, 2, 5);
            for (int x = 0; x < ex.width(); x++) for (int z = 0; z < ex.depth(); z++) {
                ex.markNatural(x, 0, z, "sand");
                for (int y = 1; y <= 3; y++) {
                    boolean inside = x >= 3 && x <= 17 && z >= 3 && z <= 17;
                    if (m.get(x, y, z) == null && (inside ? y >= 2 : true)) ex.markFluid(x, y, z);
                }
            }
            m.setLocked(ex::isProtectedKey);
        }
        return m;
    }

    static Existing existing() { return new Existing(W + 10, H + 2, D + 10, 0); }

    @Test
    void analysisSeesFloorsRoomsDamageWaterAndDarkness() {
        Existing ex = existing();
        VoxelModel m = house(ex);
        StructureAnalyzer.Report r = new StructureAnalyzer().analyze(m, ex, null);
        assertTrue(r.floors().length >= 2, "two floors expected: " + r.text());
        assertTrue(r.rooms().size() >= 2, "ground floor is split by the interior wall: " + r.text());
        assertFalse(r.holeList().isEmpty(), "the blast hole must be found: " + r.text());
        assertTrue(r.holeList().stream().noneMatch(h -> h.x1() == 18 && h.y1() == 8 && h.z1() == 9 && h.cells() <= 2), "a neat window is not damage");
        assertTrue(r.floodedCells() > 0, "water standing on the ground floor: " + r.text());
        assertTrue(r.spawnable() > 0, "an unlit house has dark spawnable spots: " + r.text());
        assertTrue(r.fragmentCells() >= 2, "the blown-off roof piece is a loose fragment: " + r.text());
        assertTrue(r.text().contains("Palette"));
    }

    @Test
    void repairPatchesDrainsSealsAndLights() {
        Existing ex = existing();
        VoxelModel m = house(ex);
        VoxelModel before = m.copy();
        Canvas c = new Canvas(m, new Materials(StyleLibrary.bundled().get("medieval"), 1));
        c.existing(ex);
        int patched = Renovation.patch(c, ex, null, null);
        assertTrue(patched > 10, "the hole is patched: " + patched);
        assertTrue(m.get(18, 8, 9) == null || m.get(18, 8, 9).isAir(), "the window stays open");
        assertTrue(m.get(10, 2, 10).isAir(), "the doorway in the interior wall stays open");
        assertTrue(m.get(10, 3, 15).isAir(), "the wide 3x3 opening is a designed opening, not damage");
        StructureAnalyzer.Report after = new StructureAnalyzer().analyze(m, ex, null);
        int bigHoles = (int) after.holeList().stream().filter(h -> h.cells() >= 8).count();
        assertEquals(0, bigHoles, "no large holes left: " + after.text());

        int drained = Renovation.drain(c, ex, new int[]{3, 2, 3, 17, 5, 17});
        assertTrue(drained > 50, "the flooded ground floor is drained: " + drained);
        Renovation.sealLeaks(c, ex, "glass");
        assertTrue(Renovation.leaks(m, ex).isEmpty(), "no drained cell touches the sea afterwards");

        int lights = Renovation.lightUp(c, ex, null, 8, null, null);
        assertTrue(lights > 0);
        StructureAnalyzer.Report lit = new StructureAnalyzer().analyze(m, ex, null);
        assertEquals(0, lit.spawnable(), "nothing indoors is dark enough to spawn mobs: " + lit.text());

        // the chest is protected: untouched, and never in the change set
        assertEquals("chest", m.get(5, 2, 5).id());
        c.fill(4, 2, 4, 6, 2, 6, "stone_bricks");
        assertEquals("chest", m.get(5, 2, 5).id(), "ops cannot overwrite a protected block");
        assertTrue(m.lockedHits() > 0);
        VoxelModel changes = Renovation.diff(m, before, ex);
        assertTrue(changes.get(5, 2, 5) == null, "protected cells never appear in the changes");
        assertTrue(changes.size() > 0);
        assertEquals("air", changes.get(6, 3, 6).id(), "drained water becomes air in the world");
        // nothing that stayed the same is placed again
        assertTrue(changes.get(2, 5, 5) == null, "unchanged wall blocks are not re-placed");
    }

    @Test
    void followUpsAboutAnExistingBuildAreRenovations() {
        // the requests that went to a blank new design on the user's server
        String[] existing = {
                "work on the bottom half of the base, from y 160 down to the sea floor",
                "now do the bottom half",
                "fix and reinvent this base: renovate, add walls, floors, lighting, furniture, interior walls, elevators and details at the bottom",
                "continue the renovation on the lower floors",
                "add an elevator to my tower",
                "the underwater part needs lights and furniture",
                "repair the damage",
                "make my base look better" };
        for (String r : existing) assertTrue(RequestIntent.aboutExistingBuild(r), "should renovate: " + r);
        String[] fresh = { "build me a gothic cathedral with flying buttresses", "make a cozy cottage with a garden", "build the Eiffel Tower at 2:3 scale",
                "design a modern skyscraper", "build a castle on that hill" };
        for (String r : fresh) assertFalse(RequestIntent.aboutExistingBuild(r), "should be a new build: " + r);
    }

    @Test
    void aSessionThatCannotWorkStopsAtOnce() {
        io.github.shengboj0324.architect.ai.DesignSession s = new io.github.shengboj0324.architect.ai.DesignSession("tester", StyleLibrary.bundled(),
                io.github.shengboj0324.architect.ai.AgentSettings.defaults(""), "medieval");
        com.google.gson.JsonObject in = new com.google.gson.JsonObject();
        in.addProperty("reason", "Look at your base and ask me to renovate it.");
        new io.github.shengboj0324.architect.ai.ArchitectTools().execute(s, "stop_design", in);
        assertEquals("Look at your base and ask me to renovate it.", s.stopReason());
        assertFalse(s.renovating(), "a new design session is not a renovation");
        // the chat tools make renovate the way to change existing builds
        String build = io.github.shengboj0324.architect.ai.ConversationAgent.definitions().stream().filter(d -> d.name().equals("build")).findFirst().orElseThrow().description();
        String expand = io.github.shengboj0324.architect.ai.ConversationAgent.definitions().stream().filter(d -> d.name().equals("expand")).findFirst().orElseThrow().description();
        assertTrue(build.contains("renovate") && expand.contains("renovate"));
    }

    @Test
    void toolSchemasAreValidJson() {
        // the plugin builds these when it starts; a malformed schema would stop it from enabling
        for (io.github.shengboj0324.architect.ai.ArchitectTools.Def d : io.github.shengboj0324.architect.ai.ArchitectTools.definitions()) assertNotNull(d.schema(), d.name());
        for (io.github.shengboj0324.architect.ai.ArchitectTools.Def d : io.github.shengboj0324.architect.ai.ConversationAgent.definitions()) assertNotNull(d.schema(), d.name());
        assertTrue(io.github.shengboj0324.architect.ai.ArchitectTools.definitions().stream().anyMatch(d -> d.name().equals("view_build")));
        assertTrue(io.github.shengboj0324.architect.ai.ConversationAgent.definitions().stream().anyMatch(d -> d.name().equals("renovate")));
    }

    @Test
    void anchoredExistingBlocksAreNotFloating() {
        Existing ex = existing();
        VoxelModel m = house(ex);
        QualityAnalyzer qa = new QualityAnalyzer();
        assertEquals("0", qa.analyze(m, ex).metrics().get("floating_blocks"), "the build's own loose fragment is anchored, not a new floating block");
        m.set(25, 12, 25, BlockState.of("stone_bricks"));
        assertTrue(Long.parseLong(qa.analyze(m, ex).metrics().get("floating_blocks")) > 0, "a newly added floating block is caught");
    }

    @Test
    void furnitureIsPlacedCorrectly() {
        Existing ex = existing();
        VoxelModel m = house(ex);
        Canvas c = new Canvas(m, new Materials(StyleLibrary.bundled().get("medieval"), 1));
        c.existing(ex);
        Renovation.drain(c, ex, new int[]{3, 2, 3, 17, 5, 17});
        int n = Furniture.furnish(c, ex, 3, 3, 9, 17, 1, "bedroom", "spruce", "blue");
        assertTrue(n >= 3, "bedroom pieces placed: " + n);
        int heads = 0;
        for (int x = 3; x <= 9; x++) for (int z = 3; z <= 17; z++) {
            BlockState s = m.get(x, 2, z);
            if (s == null || !s.id().equals("blue_bed") || !"head".equals(s.prop("part"))) continue;
            heads++;
            Dir f = Dir.parse(s.prop("facing"));
            BlockState foot = m.get(x - f.dx, 2, z - f.dz);
            assertNotNull(foot, "the foot of the bed is behind its head");
            assertEquals("foot", foot.prop("part"));
            assertTrue(m.solidAt(x + f.dx, 2, z + f.dz), "the head is against a wall");
        }
        assertEquals(1, heads, "one bed");
        assertFalse(m.solidAt(3, 2, 8) && !m.get(3, 2, 8).id().contains("carpet"), "the cell inside the door stays clear");
        assertEquals("chest", m.get(5, 2, 5).id(), "the protected chest is still there");
        int dining = Furniture.furnish(c, ex, 11, 3, 17, 13, 1, "dining", "oak", "red");
        assertTrue(dining > 4, "a table with chairs: " + dining);
        StructureAnalyzer.Report r = new StructureAnalyzer().analyze(m, ex, null);
        assertTrue(r.rooms().stream().anyMatch(room -> room.floorY() == 1 && room.furniture() > 0), "the analysis sees the furniture it placed: " + r.text());
    }

    @Test
    void elevatorsServeEveryFloor() {
        VoxelModel m = new VoxelModel();
        Canvas c = new Canvas(m, new Materials(StyleLibrary.bundled().get("modern"), 1));
        Elevators.build(c, "bubble", 5, 5, new int[]{1, 6, 11}, Dir.NORTH, "glass", "spruce", false);
        assertEquals("soul_sand", m.get(5, 1, 5).id());
        for (int y = 2; y <= 12; y++) assertEquals("bubble_column", m.get(5, y, 5).id(), "bubble column at y=" + y);
        assertEquals("false", m.get(5, 6, 5).prop("drag"), "soul sand pushes up");
        for (int f : new int[]{1, 6, 11}) {
            assertEquals("spruce_door", m.get(5, f + 1, 4).id(), "a door at floor " + f);
            assertEquals("upper", m.get(5, f + 2, 4).prop("half"));
        }
        assertTrue(m.solidAt(5, 14, 5), "the shaft is capped");
        VoxelModel l = new VoxelModel();
        Elevators.build(new Canvas(l, new Materials(StyleLibrary.bundled().get("modern"), 1)), "ladder", 3, 3, new int[]{0, 5, 10}, Dir.EAST, "stone", null, false);
        for (int y = 1; y <= 10; y++) assertEquals("ladder", l.get(3, y, 3).id());
        assertEquals("west", l.get(3, 4, 3).prop("facing"), "a ladder faces away from its wall");
        VoxelModel n = new VoxelModel();
        Elevators.build(new Canvas(n, new Materials(StyleLibrary.bundled().get("modern"), 1)), "native", 0, 0, new int[]{0, 7}, Dir.NORTH, null, null, false);
        assertEquals("lodestone", n.get(0, 7, 0).id());
    }

    @Test
    void viewsRenderTheBuildWithHighlights() {
        Existing ex = existing();
        VoxelModel m = house(ex);
        StructureAnalyzer.Report r = new StructureAnalyzer().analyze(m, ex, null);
        VoxelModel v = BuildViews.compose(m, ex, r, BuildViews.Highlight.ALL);
        assertTrue(v.size() > m.size(), "surroundings and markers are added for the view");
        assertEquals("magenta_concrete", v.get(14, 13, 24).id(), "loose fragments are painted");
        BuildViews views = new BuildViews();
        byte[] corners = views.sheet(v, "corners", null, 1200, 900);
        byte[] slice = views.sheet(v, "all", 4, 1200, 900);
        byte[] top = views.sheet(v, "top", null, 800, 800);
        assertTrue(corners.length > 5000 && slice.length > 5000 && top.length > 2000);
        assertEquals((byte) 0x89, corners[0], "PNG");
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("build", "renders");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.write(dir.resolve("renovation-corners.png"), corners);
            java.nio.file.Files.write(dir.resolve("renovation-slice.png"), slice);
            java.nio.file.Files.write(dir.resolve("renovation-top.png"), top);
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
}
