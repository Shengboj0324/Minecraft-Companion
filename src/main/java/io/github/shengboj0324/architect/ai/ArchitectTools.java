package io.github.shengboj0324.architect.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.shengboj0324.architect.core.AsciiRenderer;
import io.github.shengboj0324.architect.core.BuildViews;
import io.github.shengboj0324.architect.core.Existing;
import io.github.shengboj0324.architect.core.Renovation;
import io.github.shengboj0324.architect.core.StructureAnalyzer;
import io.github.shengboj0324.architect.core.Canvas;
import io.github.shengboj0324.architect.core.Connectivity;
import io.github.shengboj0324.architect.core.IsoRenderer;
import io.github.shengboj0324.architect.core.Materials;
import io.github.shengboj0324.architect.core.Ops;
import io.github.shengboj0324.architect.core.QualityAnalyzer;
import io.github.shengboj0324.architect.core.Style;
import io.github.shengboj0324.architect.core.StyleLibrary;
import io.github.shengboj0324.architect.core.Templates;
import io.github.shengboj0324.architect.core.VoxelModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The design tools Claude calls. They only touch the session's voxel model, never the world, so they are safe to
 * run on the agent thread. Each returns text the model reads (plus a PNG for render_views).
 */
public final class ArchitectTools {

    public record Def(String name, String description, JsonObject schema) {}

    private final AsciiRenderer ascii = new AsciiRenderer();
    private final IsoRenderer iso = new IsoRenderer();
    private final QualityAnalyzer critic = new QualityAnalyzer();
    private final StructureAnalyzer inspector = new StructureAnalyzer();
    private final BuildViews views = new BuildViews();

    public static List<Def> definitions() {
        List<Def> d = new ArrayList<>();
        d.add(new Def("set_plan",
                "Start or re-plan the design: name it, choose the style (palette and conventions) and the envelope in blocks, and how it meets the land. "
                        + "Call this first. Resets the model unless keep_model is true (use keep_model to enlarge the envelope mid-build or to start a new generation). "
                        + "On a real site the result includes a terrain report and elevation map of the envelope in model coordinates.",
                schema("""
                        {"type":"object","properties":{
                          "name":{"type":"string","description":"Short evocative name for the build"},
                          "style":{"type":"string","description":"One of the style names listed in the system prompt"},
                          "width":{"type":"integer","description":"x extent in blocks"},
                          "height":{"type":"integer","description":"y extent in blocks (y=0 is the ground layer)"},
                          "depth":{"type":"integer","description":"z extent in blocks (front at z=0)"},
                          "concept":{"type":"string","description":"2-4 sentences: program, massing, silhouette, palette intent"},
                          "keep_model":{"type":"boolean","description":"Keep existing blocks (default false)"},
                          "terrain":{"type":"string","enum":["level","natural"],"description":"level (default): the server levels a pad at y=0 and grades its edges into the land. natural: the terrain stays and you build onto it (terraces, stilts, cliff-side, piers); blocks resting on the ground count as supported"},
                          "ground_offset":{"type":"integer","description":"Raise (+) or lower (-) the ground layer y=0 relative to the median ground under the envelope, e.g. +6 to sit a level pad near a hilltop. Default 0"}},
                         "required":["name","style","width","height","depth"]}""")));
        d.add(new Def("define_palette",
                "Override style roles for the rest of the session, e.g. {\"roles\":{\"wall\":[[\"stone_bricks\",3],[\"andesite\",1]],\"roof\":\"deepslate_tiles\"}}. "
                        + "Values are a block id, a list of ids, or [[id, weight], ...]. Optionally switch the base style first with \"style\".",
                schema("""
                        {"type":"object","properties":{
                          "style":{"type":"string"},
                          "roles":{"type":"object","description":"role name (without @) -> block id | [ids] | [[id, weight]]"}}}""")));
        d.add(new Def("use_template",
                "Build one of the curated master templates into the model as a base to customise (or as one part of a larger composition, using offset). "
                        + "Templates face north and sit on y=0. The envelope grows to fit if needed.",
                schema("""
                        {"type":"object","properties":{
                          "name":{"type":"string"},
                          "scale":{"type":"integer","description":"1 normal, 2 larger (castle, cathedral, temple)"},
                          "generations":{"type":"integer","description":"village only: 1-5"},
                          "offset":{"type":"array","items":{"type":"integer"},"description":"[dx,dy,dz] where the template's origin goes"}},
                         "required":["name"]}""")));
        d.add(new Def("build",
                "Apply a batch of building operations (see the operations reference) to the model. Returns how many ops applied, errors for any that failed, and a quick score. "
                        + "Batch many ops per call. One call = one undo step.",
                schema("""
                        {"type":"object","properties":{
                          "note":{"type":"string","description":"What this batch does, a few words"},
                          "ops":{"type":"array","items":{"type":"object"},"description":"Operation objects, each with an \\"op\\" field"}},
                         "required":["ops"]}""")));
        d.add(new Def("render_views",
                "Render the current model: text plan, height map, front and side elevations, optional horizontal slices, two isometric images "
                        + "(front-east and back-west) and the full quality report. Use it after massing and after each round of detailing.",
                schema("""
                        {"type":"object","properties":{
                          "slices":{"type":"array","items":{"type":"integer"},"description":"y levels to print as top-down slices (floor plans)"},
                          "image":{"type":"boolean","description":"include the isometric image (default true)"},
                          "region":{"type":"object","description":"zoom in: {\\"from\\":[x,y,z],\\"to\\":[x,y,z]} renders only that box (use it to inspect details of a mega build); the quality report always covers the whole model"}}}""")));
        d.add(new Def("inspect_structure",
                "Analyse the build in the model like a builder walking through it: floors, rooms (size, light, furniture, doors, flooding), how floors connect, "
                        + "damage (holes in walls/floors/roofs with coordinates and the surrounding material, loose fragments, water standing inside), dark spots where mobs spawn, "
                        + "palette and style. Returns the report and an image of the four corners with damage highlighted. Call it first when renovating, and again to check your work.",
                schema("""
                        {"type":"object","properties":{
                          "region":{"type":"object","description":"optional {\\"from\\":[x,y,z],\\"to\\":[x,y,z]} to analyse part of a large build"}}}""")));
        d.add(new Def("view_build",
                "Look at the build from any angle: the four isometric corners, one corner (front/east/back/west), a top-down plan with a coordinate grid, or a cutaway "
                        + "that removes everything above slice_y (a floor plan in 3D). Optionally zoom to a region and highlight damage (red = holes, magenta = loose fragments, "
                        + "blue = water inside), light (yellow = dark spawnable spots) or water. The surroundings (sea bed, water surface) are shown for context.",
                schema("""
                        {"type":"object","properties":{
                          "view":{"type":"string","enum":["corners","front","east","back","west","top","all"]},
                          "slice_y":{"type":"integer","description":"cut away everything above this y"},
                          "region":{"type":"object","description":"{\\"from\\":[x,y,z],\\"to\\":[x,y,z]} close-up"},
                          "highlight":{"type":"string","enum":["none","damage","light","water","all"]}}}""")));
        d.add(new Def("survey_terrain",
                "Read the real terrain at full resolution in model coordinates: ground height per column (relative to y=0), water, trees, surface blocks and biomes. "
                        + "Use it to fit foundations, terraces, stilts, bridges and paths to the land. Up to 48 x 48 cells per call (a step is chosen for larger areas).",
                schema("""
                        {"type":"object","properties":{
                          "from":{"type":"array","items":{"type":"integer"},"description":"[x,z] corner (may be outside the envelope)"},
                          "to":{"type":"array","items":{"type":"integer"},"description":"[x,z] opposite corner"},
                          "step":{"type":"integer","description":"sample every n blocks (default 1)"}},
                         "required":["from","to"]}""")));
        d.add(new Def("lookup_landmark",
                "Look up researched facts about a real-world building or landmark: real dimensions in metres, the features that make it recognisable, "
                        + "a block palette, Minecraft construction notes, and the scale that fits this server. Call it before designing any real building.",
                schema("""
                        {"type":"object","properties":{
                          "name":{"type":"string","description":"The landmark's name, e.g. 'Eiffel Tower' or 'Notre-Dame de Paris'"}},
                         "required":["name"]}""")));
        d.add(new Def("stop_design",
                "Stop this session now, without building, and tell the player why in one or two plain sentences (what to do instead). Use it when the request "
                        + "cannot be done in this session, e.g. the player asks to change a build that already stands in the world but this is a new design with an empty "
                        + "model (only a renovation can see and edit an existing build). Do not use it to give up on something hard.",
                schema("""
                        {"type":"object","properties":{"reason":{"type":"string","description":"Shown to the player"}},"required":["reason"]}""")));
        d.add(new Def("undo_last_batch", "Revert the most recent build/use_template batch.", schema("{\"type\":\"object\",\"properties\":{}}")));
        d.add(new Def("finish",
                "Submit the design for construction. The server runs a quality gate: CRITICAL issues always reject it, a score below the server minimum rejects it "
                        + "until you have tried to fix it. On rejection, fix the listed issues and call finish again.",
                schema("""
                        {"type":"object","properties":{
                          "title":{"type":"string","description":"Final name shown to players"},
                          "summary":{"type":"string","description":"One or two sentences for the player describing the build"}},
                         "required":["title","summary"]}""")));
        return d;
    }

    private static JsonObject schema(String json) { return JsonParser.parseString(json).getAsJsonObject(); }

    /** Executes one tool. Never throws: errors become tool results the model can act on. */
    public ToolOutput execute(DesignSession s, String name, JsonObject in) {
        try {
            return switch (name) {
                case "set_plan" -> setPlan(s, in);
                case "define_palette" -> definePalette(s, in);
                case "use_template" -> useTemplate(s, in);
                case "build" -> build(s, in);
                case "render_views" -> render(s, in);
                case "survey_terrain" -> survey(s, in);
                case "stop_design" -> { s.stop(str(in, "reason", "")); yield ToolOutput.ok("Stopped. The player is told: " + s.stopReason() + " Do not call more tools."); }
                case "inspect_structure" -> inspect(s, in);
                case "view_build" -> view(s, in);
                case "undo_last_batch" -> undo(s);
                case "lookup_landmark" -> ToolOutput.ok(LandmarkLibrary.bundled().describe(str(in, "name", ""), s.settings()));
                case "finish" -> finish(s, in);
                default -> ToolOutput.err("unknown tool '" + name + "'");
            };
        } catch (RuntimeException e) {
            return ToolOutput.err(name + " failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------ tools

    private ToolOutput setPlan(DesignSession s, JsonObject in) {
        AgentSettings cfg = s.settings();
        int w = clamp(intOf(in, "width", 32), 3, cfg.maxWidth());
        int h = clamp(intOf(in, "height", 32), 3, cfg.maxHeight());
        int d = clamp(intOf(in, "depth", 32), 3, cfg.maxDepth());
        String styleName = str(in, "style", s.baseStyle().name());
        Style st = s.styles().get(styleName);
        StringBuilder sb = new StringBuilder();
        if (st == null) {
            sb.append("Unknown style '").append(styleName).append("', kept ").append(s.baseStyle().name()).append(". Styles: ").append(s.styles().names()).append('\n');
            st = s.baseStyle();
        }
        boolean keep = s.renovating() || in.has("keep_model") && in.get("keep_model").getAsBoolean();
        if (keep) s.setEnvelope(w, h, d); else s.resetModel(w, h, d);
        String mode = in.has("terrain") ? str(in, "terrain", "level").toLowerCase(Locale.ROOT) : null;
        if (mode != null && !mode.equals("level") && !mode.equals("natural")) { sb.append("Unknown terrain mode '").append(mode).append("', kept the current one.\n"); mode = null; }
        String siteReport = s.site() == null || s.renovating() ? null
                : s.site().plan(w, h, d, mode, in.has("ground_offset") ? Integer.valueOf(intOf(in, "ground_offset", 0)) : null, keep);
        s.setStyle(st);
        s.name(str(in, "name", s.name()));
        sb.append("Plan set: '").append(s.name()).append("', style ").append(st.name()).append(", envelope ").append(w).append(" x ").append(h).append(" x ").append(d)
                .append(" (x 0..").append(w - 1).append(", y 0..").append(h - 1).append(", z 0..").append(d - 1).append("), block budget ").append(cfg.maxBlocks()).append('.');
        if (w != intOf(in, "width", w) || h != intOf(in, "height", h) || d != intOf(in, "depth", d))
            sb.append(" NOTE: clamped to the server maximum ").append(cfg.maxWidth()).append('x').append(cfg.maxHeight()).append('x').append(cfg.maxDepth()).append('.');
        sb.append(s.renovating() ? " Renovation: the existing build stays in the model (" + s.model().nonAirCount() + " blocks); edit it in place."
                : keep ? " Existing blocks kept (" + s.model().size() + ")." : " Model cleared.");
        sb.append("\nPalette roles:\n").append(st.describeRoles());
        if (siteReport != null) sb.append('\n').append(siteReport);
        return ToolOutput.ok(sb.toString());
    }

    private ToolOutput definePalette(DesignSession s, JsonObject in) {
        if (in.has("style")) {
            Style st = s.styles().get(str(in, "style", ""));
            if (st == null) return ToolOutput.err("unknown style; available: " + s.styles().names());
            s.setStyle(st);
        }
        Style next = s.baseStyle();
        if (in.has("roles") && in.get("roles").isJsonObject()) {
            Map<String, List<Style.Weighted>> over = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : in.getAsJsonObject("roles").entrySet()) {
                String role = e.getKey().startsWith("@") ? e.getKey().substring(1) : e.getKey();
                List<Style.Weighted> list = StyleLibrary.parseWeighted(e.getValue());
                for (Style.Weighted w : list) {
                    String id = w.block().contains("[") ? w.block().substring(0, w.block().indexOf('[')) : w.block();
                    id = id.replace("minecraft:", "");
                    if (!s.materials().catalog().exists(id)) return ToolOutput.err("unknown block '" + w.block() + "' for role " + role + (suggest(s, id)));
                    if (s.settings().bannedBlocks().contains(id)) return ToolOutput.err("block '" + id + "' is not allowed on this server");
                }
                over.put(role, list);
            }
            // keep earlier overrides: start from the current palette
            next = s.style().name().equals(s.baseStyle().name()) ? s.style().withRoles(over) : s.baseStyle().withRoles(over);
        }
        s.overridePalette(next);
        return ToolOutput.ok("Palette now:\n" + next.describeRoles());
    }

    private ToolOutput useTemplate(DesignSession s, JsonObject in) {
        String name = str(in, "name", "").toLowerCase(Locale.ROOT);
        if (!Templates.ALL.containsKey(name)) return ToolOutput.err("unknown template '" + name + "'. Available: " + Templates.names());
        int[] off = {0, 0, 0};
        if (in.has("offset") && in.get("offset").isJsonArray()) {
            JsonArray a = in.getAsJsonArray("offset");
            for (int i = 0; i < 3 && i < a.size(); i++) off[i] = a.get(i).getAsInt();
        }
        VoxelModel tmp = new VoxelModel();
        Templates.build(name, new Canvas(tmp, s.materials()), clamp(intOf(in, "scale", 1), 1, 3), clamp(intOf(in, "generations", 3), 1, 5));
        int[] b = tmp.bounds();
        if (b == null) return ToolOutput.err("template produced nothing");
        VoxelModel m = s.model();
        int needW = Math.max(m.width(), b[3] + off[0] + 1), needH = Math.max(m.height(), b[4] + off[1] + 1), needD = Math.max(m.depth(), b[5] + off[2] + 1);
        String grew = "";
        if (needW > m.width() || needH > m.height() || needD > m.depth()) {
            s.setEnvelope(Math.min(needW, s.settings().maxWidth()), Math.min(needH, s.settings().maxHeight()), Math.min(needD, s.settings().maxDepth()));
            grew = " Envelope grew to " + m.width() + " x " + m.height() + " x " + m.depth() + ".";
        }
        m.resetCounters();
        m.beginBatch();
        try {
            tmp.forEach((x, y, z, st) -> m.set(x + off[0], y + off[1], z + off[2], st));
        } finally {
            m.endBatch();
        }
        JsonArray log = new JsonArray();
        JsonObject op = in.deepCopy();
        op.addProperty("op", "_template");
        log.add(op);
        s.batches().push(log);
        s.built();
        QualityAnalyzer.Report r = critic.analyze(m, s.ground());
        s.lastReport(r);
        return ToolOutput.ok("Template '" + name + "' placed at offset [" + off[0] + "," + off[1] + "," + off[2] + "], bounds x " + (b[0] + off[0]) + ".." + (b[3] + off[0])
                + ", y " + (b[1] + off[1]) + ".." + (b[4] + off[1]) + ", z " + (b[2] + off[2]) + ".." + (b[5] + off[2]) + "." + grew
                + (m.clippedCount() > 0 ? " WARNING " + m.clippedCount() + " blocks clipped by the envelope." : "")
                + "\nQuick check: score " + r.score() + (r.hasCritical() ? " with CRITICAL issues" : "") + ". Render it to see what you are working with, then customise it to the request.");
    }

    private ToolOutput build(DesignSession s, JsonObject in) {
        JsonElement ops = in.get("ops");
        if (ops != null && ops.isJsonPrimitive()) ops = JsonParser.parseString(ops.getAsString()); // tolerate stringified arrays
        if (ops == null || !ops.isJsonArray()) return ToolOutput.err("build needs \"ops\": [ {\"op\": ...}, ... ]");
        JsonArray arr = ops.getAsJsonArray();
        if (arr.isEmpty()) return ToolOutput.err("ops is empty");
        int depth = s.model().undoDepth();
        Ops.Result res = new Ops(s.canvas()).apply(arr);
        if (res.applied() > 0) {
            s.batches().push(arr.deepCopy());
            s.built();
        } else if (s.model().undoDepth() > depth) {
            s.model().undoBatch(); // every op failed: drop any partial changes they left
        }
        QualityAnalyzer.Report r = critic.analyze(s.model(), s.ground());
        s.lastReport(r);
        StringBuilder sb = new StringBuilder(res.summary());
        sb.append("\nQuick check: score ").append(r.score());
        long crit = r.issues().stream().filter(i -> i.severity().equals("CRITICAL")).count();
        if (crit > 0) sb.append(", ").append(crit).append(" CRITICAL issue(s): ").append(r.issues().stream().filter(i -> i.severity().equals("CRITICAL")).findFirst().get().message());
        return new ToolOutput(sb.toString(), null, res.applied() == 0);
    }

    private ToolOutput render(DesignSession s, JsonObject in) {
        VoxelModel m = s.model();
        if (m.nonAirCount() == 0) return ToolOutput.err("the model is empty; build something first");
        Connectivity.apply(m);
        s.rendered();
        StringBuilder sb = new StringBuilder();
        sb.append("RENDER #").append(s.renders()).append(" of '").append(s.name()).append("'\n");
        VoxelModel view = s.renovating() ? BuildViews.compose(m, s.existing(), null, BuildViews.Highlight.NONE) : s.site() == null ? m : s.site().overlay(m);
        if (view != m) sb.append("The land around the build is shown for context (natural surface, water and graded edges); it is not part of the design or the score.\n");
        if (in.has("region") && in.get("region").isJsonObject()) {
            JsonObject rg = in.getAsJsonObject("region");
            int[] a = vec3(rg, "from"), b = vec3(rg, "to");
            view = IsoRenderer.crop(view, a[0], a[1], a[2], b[0], b[1], b[2]);
            if (view.nonAirCount() == 0) return ToolOutput.err("the region [" + a[0] + "," + a[1] + "," + a[2] + "]..[" + b[0] + "," + b[1] + "," + b[2] + "] is empty");
            sb.append("ZOOMED to region [").append(a[0]).append(',').append(a[1]).append(',').append(a[2]).append("]..[").append(b[0]).append(',').append(b[1]).append(',').append(b[2]).append("]\n");
        }
        if (view.size() > IsoRenderer.MAX_PREVIEW_CELLS) sb.append("NOTE: large model (").append(view.size()).append(" cells); images are downsampled. Use \"region\" to inspect details at full resolution.\n");
        sb.append(ascii.render(view, 96)).append('\n');
        if (in.has("slices") && in.get("slices").isJsonArray()) {
            int n = 0;
            for (JsonElement e : in.getAsJsonArray("slices")) {
                if (n++ >= 4) break;
                sb.append(ascii.slice(view, e.getAsInt(), 96)).append('\n');
            }
        }
        sb.append(ascii.materialSummary(m, 14)).append("\n\n");
        QualityAnalyzer.Report r = critic.analyze(m, s.ground());
        s.lastReport(r);
        sb.append(r.format());
        if (s.renovating()) sb.append("Before the renovation this build scored ").append(s.originalScore()).append(". Use inspect_structure for rooms, damage and lighting.\n");
        boolean image = !in.has("image") || in.get("image").getAsBoolean();
        byte[] png = image && s.settings().vision() ? iso.renderSheetPng(view, 1400, 700) : null;
        if (png != null) sb.append("\nThe attached image shows the isometric views. Judge silhouette, proportion, depth and palette, not just the score.");
        return new ToolOutput(sb.toString(), png, false);
    }

    private ToolOutput inspect(DesignSession s, JsonObject in) {
        VoxelModel m = s.model();
        if (m.nonAirCount() == 0) return ToolOutput.err("the model is empty");
        int[] region = null;
        if (in.has("region") && in.get("region").isJsonObject()) {
            int[] a = vec3(in.getAsJsonObject("region"), "from"), b = vec3(in.getAsJsonObject("region"), "to");
            region = new int[]{a[0], a[1], a[2], b[0], b[1], b[2]};
        }
        StructureAnalyzer.Report r = inspector.analyze(m, s.existing(), region);
        s.lastInspection(r);
        QualityAnalyzer.Report q = critic.analyze(m, s.ground());
        String text = r.text() + "Quality score now " + q.score() + "/100" + (s.renovating() ? " (before the renovation: " + s.originalScore() + ")" : "") + ".\n"
                + (s.existing() == null ? "" : "Protected cells (containers, signs, banners, item frames) are locked: ops simply skip them.\n");
        byte[] png = null;
        if (s.settings().vision()) {
            VoxelModel v = BuildViews.compose(m, s.existing(), r, BuildViews.Highlight.ALL);
            if (region != null) v = io.github.shengboj0324.architect.core.IsoRenderer.crop(v, region[0], region[1], region[2], region[3], region[4], region[5]);
            png = views.sheet(v, "corners", null, 1400, 1000);
            text += "The image shows the four corners with red = holes, magenta = loose fragments, blue = water inside, yellow = dark spots.";
        }
        return new ToolOutput(text, png, false);
    }

    private ToolOutput view(DesignSession s, JsonObject in) {
        VoxelModel m = s.model();
        if (m.nonAirCount() == 0) return ToolOutput.err("the model is empty");
        if (!s.settings().vision()) return ToolOutput.err("images are disabled on this server (design.vision: false); use render_views for text views");
        BuildViews.Highlight hl;
        try { hl = BuildViews.Highlight.valueOf(str(in, "highlight", "none").toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { hl = BuildViews.Highlight.NONE; }
        StructureAnalyzer.Report r = hl == BuildViews.Highlight.NONE ? null : inspector.analyze(m, s.existing(), null);
        VoxelModel v = BuildViews.compose(m, s.existing(), r, hl);
        String note = "";
        if (in.has("region") && in.get("region").isJsonObject()) {
            int[] a = vec3(in.getAsJsonObject("region"), "from"), b = vec3(in.getAsJsonObject("region"), "to");
            v = io.github.shengboj0324.architect.core.IsoRenderer.crop(v, a[0], a[1], a[2], b[0], b[1], b[2]);
            if (v.nonAirCount() == 0) return ToolOutput.err("that region is empty");
            note = " Zoomed to [" + a[0] + "," + a[1] + "," + a[2] + "]..[" + b[0] + "," + b[1] + "," + b[2] + "].";
        }
        Integer slice = in.has("slice_y") ? intOf(in, "slice_y", 0) : null;
        String what = str(in, "view", "corners");
        byte[] png = views.sheet(v, what, slice, 1400, 1000);
        s.rendered();
        return new ToolOutput("View: " + what + (slice == null ? "" : ", cut at y=" + slice) + (hl == BuildViews.Highlight.NONE ? "" : ", highlight " + hl.name().toLowerCase(Locale.ROOT)) + "." + note, png, false);
    }

    private ToolOutput survey(DesignSession s, JsonObject in) {
        if (s.site() == null) return ToolOutput.err("no terrain survey for this design (no world site); assume flat ground at y=0");
        int[] a = vec2(in, "from"), b = vec2(in, "to");
        String out = s.site().survey(a[0], a[1], b[0], b[1], clamp(intOf(in, "step", 1), 1, 64));
        return out == null ? ToolOutput.err("call set_plan first: the terrain is reported in the envelope's model coordinates") : ToolOutput.ok(out);
    }

    private ToolOutput undo(DesignSession s) {
        int n = s.model().undoBatch();
        if (n < 0) return ToolOutput.err("nothing to undo");
        if (!s.batches().isEmpty()) s.batches().pop();
        return ToolOutput.ok("Reverted the last batch (" + n + " cells restored). Model now has " + s.model().size() + " cells.");
    }

    private ToolOutput finish(DesignSession s, JsonObject in) {
        VoxelModel m = s.model();
        if (m.nonAirCount() == 0) return ToolOutput.err("the model is empty");
        Connectivity.apply(m);
        StringBuilder auto = new StringBuilder();
        if (s.renovating()) {
            // safety net: the sea never gets in, and nothing is left dark enough for mobs to spawn indoors
            int sealed = Renovation.sealLeaks(s.canvas(), s.existing(), "glass");
            if (sealed > 0) auto.append("Sealed ").append(sealed).append(" spots where drained rooms touched the sea (glass). ");
            int lit = Renovation.lightUp(s.canvas(), s.existing(), null, 1, null, null);
            if (lit > 0) auto.append("Added ").append(lit).append(" light fixtures where mobs could still spawn. ");
            if (sealed + lit > 0) Connectivity.apply(m);
        }
        QualityAnalyzer.Report r = critic.analyze(m, s.ground());
        s.lastReport(r);
        AgentSettings cfg = s.settings();
        int required = s.renovating() ? Math.min(cfg.minScore(), Math.max(40, s.originalScore() + 10)) : cfg.minScore();
        if (r.hasCritical()) {
            s.rejectedAtGate();
            return ToolOutput.err("REJECTED by the quality gate: CRITICAL issues must be fixed.\n" + r.format());
        }
        if (r.score() < required && s.gateRejections() < cfg.maxGateRejections()) {
            s.rejectedAtGate();
            return ToolOutput.err("REJECTED by the quality gate: score " + r.score() + " is below the required " + required + (s.renovating() ? " (the build scored " + s.originalScore() + " before)" : "")
                    + ". Improve the weakest parts of the breakdown, then call finish again.\n" + r.format());
        }
        s.name(str(in, "title", s.name()));
        s.summary(str(in, "summary", ""));
        s.finished(true);
        return ToolOutput.ok(auto + "ACCEPTED with score " + r.score() + ". The server will now build '" + s.name() + "' (" + m.nonAirCount()
                + " blocks). Reply with one short in-character sentence to the player; do not call more tools.");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static String suggest(DesignSession s, String id) {
        List<String> sug = s.materials().catalog().suggest(id, 3);
        return sug.isEmpty() ? "" : "; did you mean " + sug + "?";
    }

    static String str(JsonObject o, String k, String def) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? def : e.getAsString();
    }

    static int intOf(JsonObject o, String k, int def) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? def : (int) Math.round(e.getAsDouble());
    }

    static int[] vec3(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() < 3) throw new IllegalArgumentException("region needs \"" + k + "\": [x,y,z]");
        JsonArray a = e.getAsJsonArray();
        return new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()};
    }

    static int[] vec2(JsonObject o, String k) {
        JsonElement e = o.get(k);
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().size() < 2) throw new IllegalArgumentException("needs \"" + k + "\": [x,z]");
        JsonArray a = e.getAsJsonArray();
        return a.size() >= 3 ? new int[]{a.get(0).getAsInt(), a.get(2).getAsInt()} : new int[]{a.get(0).getAsInt(), a.get(1).getAsInt()};
    }

    static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Quick report used outside the tool loop (offline templates, eval). */
    public QualityAnalyzer.Report analyze(VoxelModel m) { return critic.analyze(m); }

    /** Validator that bans blocks configured as forbidden and (on a server) checks states via Bukkit. */
    public static Materials.StateValidator banning(AgentSettings cfg, Materials.StateValidator next) {
        return st -> {
            if (cfg.bannedBlocks().contains(st.id())) return "block '" + st.id() + "' is not allowed on this server";
            return next == null ? null : next.validate(st);
        };
    }
}
