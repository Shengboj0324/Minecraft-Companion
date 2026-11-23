package io.github.shengboj0324.architect.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The "training data" of the Architect: designs players rated highly (plus bundled curated ones) are stored as their
 * op logs and shown to the model as few-shot exemplars in later sessions. This is in-context learning: the model
 * itself is not fine-tuned, but each server's Architect gets better at what its players like.
 */
public final class ExemplarStore {

    public record Exemplar(String request, String style, String name, int score, int rating, JsonArray ops, String source) {}

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final String[] BUNDLED = {"exemplars/market_hall.json", "exemplars/mage_tower.json", "exemplars/eiffel_tower.json"};

    private final Path dir;
    private final List<Exemplar> all = new ArrayList<>();

    public ExemplarStore(Path dir) {
        this.dir = dir;
        for (String b : BUNDLED) {
            try (InputStream in = ExemplarStore.class.getClassLoader().getResourceAsStream(b)) {
                if (in != null) all.add(parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject(), "bundled"));
            } catch (IOException | RuntimeException ignored) { }
        }
        if (dir != null && Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                    try { all.add(parse(JsonParser.parseString(Files.readString(p)).getAsJsonObject(), "player")); } catch (IOException | RuntimeException ignored) { }
                });
            } catch (IOException ignored) { }
        }
    }

    public List<Exemplar> all() { return all; }

    private static Exemplar parse(JsonObject o, String source) {
        return new Exemplar(o.get("request").getAsString(), o.get("style").getAsString(), o.get("name").getAsString(),
                o.has("score") ? o.get("score").getAsInt() : 0, o.has("rating") ? o.get("rating").getAsInt() : 5, o.getAsJsonArray("ops"), source);
    }

    /** Saves a finished, highly rated session. Returns the file written, or null when there is nothing to save. */
    public synchronized Path save(DesignSession s) throws IOException {
        if (dir == null || s.batches().isEmpty()) return null;
        JsonArray ops = new JsonArray();
        List<JsonArray> ordered = new ArrayList<>(s.batches());
        java.util.Collections.reverse(ordered); // batches are a stack, newest first
        for (JsonArray b : ordered) for (JsonElement e : b) ops.add(e);
        JsonObject o = new JsonObject();
        o.addProperty("request", String.join(" / then: ", s.requests()));
        o.addProperty("style", s.baseStyle().name());
        o.addProperty("name", s.name());
        o.addProperty("score", s.lastReport() == null ? 0 : s.lastReport().score());
        o.addProperty("rating", s.rating());
        o.addProperty("envelope", s.model().width() + "x" + s.model().height() + "x" + s.model().depth());
        o.add("ops", ops);
        Files.createDirectories(dir);
        Path p = dir.resolve(s.name().replaceAll("[^A-Za-z0-9_-]+", "_").toLowerCase(Locale.ROOT) + "-" + s.id().toString().substring(0, 8) + ".json");
        Files.writeString(p, GSON.toJson(o));
        all.add(parse(o, "player"));
        return p;
    }

    /** Picks up to max exemplars relevant to the request and renders them compactly for the opening message. */
    public String select(String request, int max, int maxChars) {
        Set<String> words = tokens(request);
        List<Exemplar> ranked = new ArrayList<>(all);
        ranked.sort(Comparator.comparingDouble((Exemplar e) -> -relevance(e, words)));
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Exemplar e : ranked) {
            if (n >= max) break;
            if (e.rating() < 4) continue;
            String ops = GSON.toJson(e.ops());
            if (ops.length() > maxChars) ops = ops.substring(0, maxChars) + " ... (truncated)";
            sb.append("<exemplar name=\"").append(e.name()).append("\" style=\"").append(e.style()).append("\" score=\"").append(e.score())
                    .append("\" rating=\"").append(e.rating()).append("/5\">\nrequest: ").append(e.request()).append("\nops: ").append(ops).append("\n</exemplar>\n");
            n++;
        }
        return sb.toString();
    }

    private static double relevance(Exemplar e, Set<String> words) {
        Set<String> t = tokens(e.request() + " " + e.name() + " " + e.style());
        int overlap = 0;
        for (String w : words) if (t.contains(w)) overlap++;
        return overlap * 3 + e.rating() + e.score() / 25.0 + (e.source().equals("player") ? 0.5 : 0);
    }

    private static Set<String> tokens(String s) {
        Set<String> out = new HashSet<>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) if (w.length() > 2) out.add(w);
        return out;
    }
}
