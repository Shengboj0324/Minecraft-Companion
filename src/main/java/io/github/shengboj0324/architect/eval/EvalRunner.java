package io.github.shengboj0324.architect.eval;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.shengboj0324.architect.ai.AgentListener;
import io.github.shengboj0324.architect.ai.AgentSettings;
import io.github.shengboj0324.architect.ai.ArchitectAgent;
import io.github.shengboj0324.architect.ai.DesignSession;
import io.github.shengboj0324.architect.ai.ExemplarStore;
import io.github.shengboj0324.architect.core.Canvas;
import io.github.shengboj0324.architect.core.Connectivity;
import io.github.shengboj0324.architect.core.IsoRenderer;
import io.github.shengboj0324.architect.core.Materials;
import io.github.shengboj0324.architect.core.QualityAnalyzer;
import io.github.shengboj0324.architect.core.StyleLibrary;
import io.github.shengboj0324.architect.core.Templates;
import io.github.shengboj0324.architect.core.VoxelModel;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The Architect's evaluation suite. Offline it scores every curated template (a regression baseline for the engine);
 * with an API key it runs the benchmark prompts headlessly through the full Claude loop and records score, gate
 * outcome, turns and tokens, plus a render of each result, so prompt or model changes can be compared run to run.
 */
public final class EvalRunner {

    public record Row(String id, String request, boolean passed, int score, int renders, int builds, long blocks, String usage, String note) {}

    private final StyleLibrary styles = StyleLibrary.bundled();
    private final QualityAnalyzer critic = new QualityAnalyzer();

    public List<Row> templates(int minScore) {
        List<Row> rows = new ArrayList<>();
        for (Templates.Info i : Templates.ALL.values()) {
            VoxelModel m = new VoxelModel();
            Templates.build(i.name(), new Canvas(m, new Materials(styles.getOrDefault(i.defaultStyle()), 7)), 1, 3);
            QualityAnalyzer.Report r = critic.analyze(m);
            rows.add(new Row(i.name(), i.description(), !r.hasCritical() && r.score() >= minScore, r.score(), 0, 0, m.nonAirCount(), "-",
                    r.hasCritical() ? "CRITICAL issues" : r.issues().isEmpty() ? "" : r.issues().get(0).toString()));
        }
        return rows;
    }

    public static JsonArray bundledBenchmarks() {
        try (InputStream in = EvalRunner.class.getClassLoader().getResourceAsStream("eval/benchmarks.json")) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
        } catch (IOException | NullPointerException e) {
            throw new IllegalStateException("missing eval/benchmarks.json", e);
        }
    }

    /** Runs benchmarks through Claude. Each one is a full design session; renders are saved to outDir. */
    public List<Row> ai(ArchitectAgent agent, AgentSettings cfg, ExemplarStore exemplars, JsonArray benchmarks, Path outDir, int limit) throws IOException {
        Files.createDirectories(outDir);
        List<Row> rows = new ArrayList<>();
        IsoRenderer iso = new IsoRenderer();
        int n = 0;
        for (JsonElement e : benchmarks) {
            if (limit > 0 && n++ >= limit) break;
            JsonObject b = e.getAsJsonObject();
            String id = b.get("id").getAsString(), request = b.get("request").getAsString();
            DesignSession s = new DesignSession("eval", styles, cfg, b.has("style") ? b.get("style").getAsString() : "medieval");
            String[] fail = {null};
            agent.run(s, request, b.has("site") ? b.get("site").getAsString() : "Flat grassland, plenty of room.",
                    exemplars == null ? null : exemplars.select(request, 2, 6000), new AgentListener() {
                        @Override public void failed(DesignSession d, String reason) { fail[0] = reason; }
                    });
            if (b.has("follow_up") && s.finished()) {
                s.nextGeneration();
                agent.run(s, b.get("follow_up").getAsString(), null, null, new AgentListener() {
                    @Override public void failed(DesignSession d, String reason) { fail[0] = reason; }
                });
            }
            VoxelModel m = s.model();
            Connectivity.apply(m);
            QualityAnalyzer.Report r = m.nonAirCount() == 0 ? null : critic.analyze(m);
            if (r != null) Files.write(outDir.resolve(id + ".png"), iso.renderSheetPng(m, 1400, 700));
            rows.add(new Row(id, request, s.finished() && r != null && !r.hasCritical(), r == null ? 0 : r.score(), s.renders(), s.buildCalls(),
                    m.nonAirCount(), s.usageLine(), fail[0] == null ? "" : fail[0]));
        }
        return rows;
    }

    public static String markdown(String title, List<Row> rows) {
        StringBuilder sb = new StringBuilder("# ").append(title).append(" (").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append(")\n\n");
        sb.append("| id | pass | score | renders | builds | blocks | usage | note |\n|---|---|---|---|---|---|---|---|\n");
        int pass = 0, sum = 0;
        for (Row r : rows) {
            if (r.passed()) pass++;
            sum += r.score();
            sb.append("| ").append(r.id()).append(" | ").append(r.passed() ? "yes" : "NO").append(" | ").append(r.score()).append(" | ").append(r.renders())
                    .append(" | ").append(r.builds()).append(" | ").append(r.blocks()).append(" | ").append(r.usage()).append(" | ").append(r.note().replace('|', '/')).append(" |\n");
        }
        sb.append("\n**").append(pass).append("/").append(rows.size()).append(" passed, mean score ").append(rows.isEmpty() ? 0 : sum / rows.size()).append("**\n");
        return sb.toString();
    }
}
