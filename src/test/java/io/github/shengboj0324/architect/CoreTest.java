package io.github.shengboj0324.architect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.shengboj0324.architect.ai.AgentSettings;
import io.github.shengboj0324.architect.ai.ArchitectTools;
import io.github.shengboj0324.architect.ai.DesignSession;
import io.github.shengboj0324.architect.ai.ExemplarStore;
import io.github.shengboj0324.architect.ai.PromptLibrary;
import io.github.shengboj0324.architect.ai.ToolOutput;
import io.github.shengboj0324.architect.core.BlockCatalog;
import io.github.shengboj0324.architect.core.BlockState;
import io.github.shengboj0324.architect.core.Canvas;
import io.github.shengboj0324.architect.core.Connectivity;
import io.github.shengboj0324.architect.core.Materials;
import io.github.shengboj0324.architect.core.Ops;
import io.github.shengboj0324.architect.core.QualityAnalyzer;
import io.github.shengboj0324.architect.core.StateTransforms;
import io.github.shengboj0324.architect.core.Style;
import io.github.shengboj0324.architect.core.StyleLibrary;
import io.github.shengboj0324.architect.core.Templates;
import io.github.shengboj0324.architect.core.VoxelModel;
import io.github.shengboj0324.architect.eval.EvalRunner;
import io.github.shengboj0324.architect.world.Placement;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoreTest {

    private final StyleLibrary styles = StyleLibrary.bundled();

    @Test
    void everyTemplatePassesTheQualityGate() {
        for (EvalRunner.Row r : new EvalRunner().templates(80)) {
            assertTrue(r.passed(), r.id() + " scored " + r.score() + " " + r.note());
        }
    }

    @Test
    void everyStyleBlockExists() {
        BlockCatalog cat = BlockCatalog.get();
        for (Style s : styles.all()) {
            for (String role : Style.ROLES) {
                List<Style.Weighted> list = s.role(role);
                assertTrue(list != null && !list.isEmpty(), s.name() + " lacks role " + role);
                for (Style.Weighted w : list) assertTrue(cat.exists(w.block()), s.name() + "." + role + ": unknown block " + w.block());
            }
        }
    }

    @Test
    void rotationMatchesPlacement() {
        // a north-facing front turned one quarter clockwise faces east, both for coordinates and block states
        int[] north = Placement.rot(0, -1, 1);
        assertEquals(1, north[0]);
        assertEquals(0, north[1]);
        BlockState stair = BlockState.parse("oak_stairs[facing=north,half=bottom]");
        assertEquals("east", StateTransforms.rotate(stair, 1).prop("facing"));
        assertEquals("south", StateTransforms.rotate(stair, 2).prop("facing"));
        assertEquals("west", StateTransforms.rotate(stair, 3).prop("facing"));
        assertEquals("x", StateTransforms.rotate(BlockState.parse("oak_log[axis=z]"), 1).prop("axis"));
    }

    @Test
    void fencesConnect() {
        VoxelModel m = new VoxelModel();
        m.set(0, 1, 0, BlockState.of("oak_fence"));
        m.set(1, 1, 0, BlockState.of("oak_fence"));
        Connectivity.apply(m);
        assertEquals("true", m.get(0, 1, 0).prop("east"));
        assertEquals("true", m.get(1, 1, 0).prop("west"));
    }

    @Test
    void opsReportErrorsWithoutThrowing() {
        VoxelModel m = new VoxelModel(20, 20, 20);
        Canvas c = new Canvas(m, new Materials(styles.getOrDefault("medieval"), 1));
        JsonArray ops = JsonParser.parseString("""
                [{"op":"fill","from":[0,0,0],"to":[9,0,9],"block":"@foundation"},
                 {"op":"walls","from":[0,1,0],"to":[9,4,9],"block":"@wall"},
                 {"op":"roof","from":[0,5,0],"to":[9,5,9],"type":"hip","pitch":1},
                 {"op":"nonsense"},
                 {"op":"block","at":[1,1,1],"block":"not_a_block"}]""").getAsJsonArray();
        Ops.Result r = new Ops(c).apply(ops);
        assertEquals(3, r.applied());
        assertEquals(2, r.failed());
        assertTrue(m.size() > 100);
    }

    @Test
    void bannedBlocksAreRejected() {
        AgentSettings cfg = AgentSettings.defaults("x");
        DesignSession s = new DesignSession("t", styles, cfg, "medieval");
        s.materials().setValidator(ArchitectTools.banning(cfg, null));
        ArchitectTools tools = new ArchitectTools();
        tools.execute(s, "set_plan", JsonParser.parseString("{\"name\":\"t\",\"style\":\"medieval\",\"width\":10,\"height\":10,\"depth\":10}").getAsJsonObject());
        ToolOutput out = tools.execute(s, "build", JsonParser.parseString("{\"ops\":[{\"op\":\"block\",\"at\":[1,0,1],\"block\":\"tnt\"}]}").getAsJsonObject());
        assertTrue(out.error());
        assertTrue(out.text().contains("not allowed"), out.text());
    }

    @Test
    void qualityGateRejectsFloatingBlocksThenAccepts() {
        AgentSettings cfg = AgentSettings.defaults("x");
        DesignSession s = new DesignSession("t", styles, cfg, "medieval");
        ArchitectTools tools = new ArchitectTools();
        tools.execute(s, "use_template", obj("{\"name\":\"cottage\"}"));
        tools.execute(s, "build", obj("{\"ops\":[{\"op\":\"block\",\"at\":[5,30,5],\"block\":\"stone\"}]}"));
        ToolOutput rejected = tools.execute(s, "finish", obj("{\"title\":\"t\",\"summary\":\"s\"}"));
        assertTrue(rejected.error());
        assertTrue(rejected.text().contains("CRITICAL"));
        assertFalse(s.finished());
        tools.execute(s, "undo_last_batch", new JsonObject());
        ToolOutput ok = tools.execute(s, "finish", obj("{\"title\":\"t\",\"summary\":\"s\"}"));
        assertFalse(ok.error(), ok.text());
        assertTrue(s.finished());
    }

    @Test
    void renderReturnsImageAndReport() {
        DesignSession s = new DesignSession("t", styles, AgentSettings.defaults("x"), "medieval");
        ArchitectTools tools = new ArchitectTools();
        tools.execute(s, "use_template", obj("{\"name\":\"townhouse\"}"));
        ToolOutput out = tools.execute(s, "render_views", obj("{\"slices\":[1]}"));
        assertFalse(out.error());
        assertTrue(out.png() != null && out.png().length > 1000);
        assertTrue(out.text().contains("QUALITY SCORE"));
        assertTrue(out.text().contains("SLICE y=1"));
    }

    @Test
    void bundledExemplarsReplayCleanly() {
        ExemplarStore store = new ExemplarStore(null);
        assertTrue(store.all().size() >= 2);
        for (ExemplarStore.Exemplar e : store.all()) {
            VoxelModel m = new VoxelModel();
            Ops.Result r = new Ops(new Canvas(m, new Materials(styles.getOrDefault(e.style()), 3))).apply(e.ops());
            assertEquals(0, r.failed(), e.name() + ": " + r.errors());
            Connectivity.apply(m);
            QualityAnalyzer.Report q = new QualityAnalyzer().analyze(m);
            assertFalse(q.hasCritical(), e.name() + "\n" + q.format());
            assertTrue(q.score() >= 80, e.name() + " " + q.score());
        }
    }

    @Test
    void systemPromptListsStylesAndTemplates() {
        String sys = new PromptLibrary(styles, null).system();
        assertFalse(sys.contains("{{STYLES}}"));
        for (String t : Templates.names()) assertTrue(sys.contains("`" + t + "`"), t);
        for (String st : styles.names()) assertTrue(sys.contains("## " + st), st);
    }

    @Test
    void offlineRequestsMapToTemplates() {
        assertEquals("castle", ArchitectService.guessTemplate("Build me a huge castle on the hill"));
        assertEquals("cathedral", ArchitectService.guessTemplate("a gothic church"));
        assertEquals("wizard_tower", ArchitectService.guessTemplate("an elven mage tower"));
        assertEquals("cottage", ArchitectService.guessTemplate("something nice"));
    }

    private static JsonObject obj(String s) { return JsonParser.parseString(s).getAsJsonObject(); }
}
