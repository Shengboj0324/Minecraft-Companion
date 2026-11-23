package io.github.shengboj0324.architect.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Researched facts about real-world landmarks (resources/landmarks/landmarks.json) so Claude reproduces them with
 * correct proportions and recognisable features, plus scale advice computed against this server's limits.
 */
public final class LandmarkLibrary {

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().create();
    private static volatile LandmarkLibrary bundled;

    private final List<JsonObject> entries = new ArrayList<>();

    /** Words that appear in many names and say nothing about which landmark is meant. */
    private static final java.util.Set<String> GENERIC = java.util.Set.of("house", "tower", "towers", "building", "bridge", "castle", "great",
            "hall", "temple", "palace", "cathedral", "church", "basilica", "city", "wall", "section", "twin", "leaning", "space", "state");

    LandmarkLibrary(String json) {
        JsonArray a = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("landmarks");
        for (JsonElement e : a) entries.add(e.getAsJsonObject());
    }

    public static LandmarkLibrary bundled() {
        if (bundled == null) bundled = new LandmarkLibrary(PromptLibrary.bundled("landmarks/landmarks.json"));
        return bundled;
    }

    public int size() { return entries.size(); }

    public List<String> names() {
        List<String> out = new ArrayList<>();
        for (JsonObject o : entries) out.add(o.get("name").getAsString());
        return out;
    }

    /** Best match for a free-text name, or null. */
    public JsonObject find(String query) {
        String q = norm(query);
        if (q.isEmpty()) return null;
        JsonObject best = null;
        int bestScore = 0;
        for (JsonObject o : entries) {
            int sc = 0;
            List<String> keys = new ArrayList<>();
            keys.add(o.get("id").getAsString().replace('_', ' '));
            keys.add(o.get("name").getAsString());
            if (o.has("aliases")) for (JsonElement e : o.getAsJsonArray("aliases")) keys.add(e.getAsString());
            for (String k : keys) {
                String n = norm(k);
                if (n.equals(q)) sc = Math.max(sc, 100);
                else if (q.contains(n) || n.contains(q)) sc = Math.max(sc, 60 + Math.min(n.length(), q.length()));
                else {
                    int common = 0;
                    for (String w : n.split(" ")) if (w.length() > 3 && !GENERIC.contains(w) && q.contains(w)) common += w.length();
                    sc = Math.max(sc, common);
                }
            }
            if (sc > bestScore) { bestScore = sc; best = o; }
        }
        return bestScore >= 5 ? best : null;
    }

    /** Text for the lookup_landmark tool. */
    public String describe(String query, AgentSettings cfg) {
        JsonObject o = find(query);
        if (o == null) {
            return "No researched entry for '" + query + "'. Known landmarks: " + String.join(", ", names()) + ".\n"
                    + (cfg.webSearch() ? "Use web_search to find its height, footprint and defining features, then plan the scale."
                    : "Use your own knowledge of its proportions and tell the player the dimensions are approximate.");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(PRETTY.toJson(o)).append("\n\n");
        sb.append(scaleAdvice(o, cfg));
        return sb.toString();
    }

    /** Suggests scales that fit the server envelope, from the largest horizontal and vertical dimensions. */
    static String scaleAdvice(JsonObject o, AgentSettings cfg) {
        JsonObject d = o.getAsJsonObject("dimensions");
        double h = 0, w = 0;
        for (var e : d.entrySet()) {
            String k = e.getKey();
            JsonElement v = e.getValue();
            if (k.contains("complex") || k.contains("spacing") || k.contains("moat") || k.contains("offshore") || k.contains("degrees")) continue;
            double val = 0;
            if (v.isJsonPrimitive()) val = v.getAsDouble();
            else if (v.isJsonArray() && v.getAsJsonArray().size() > 0 && v.getAsJsonArray().get(0).isJsonPrimitive()) {
                for (JsonElement x : v.getAsJsonArray()) val = Math.max(val, x.getAsDouble());
            }
            if (k.contains("height")) h = Math.max(h, val);
            else if (k.contains("length") || k.contains("width") || k.contains("square") || k.contains("footprint") || k.contains("ellipse")
                    || k.contains("side") || k.contains("diameter") || k.contains("gallery") || k.contains("building") || k.contains("stylobate")
                    || k.contains("span") || k.contains("depth") || k.contains("plan")) w = Math.max(w, val);
        }
        int maxH = Math.min(cfg.maxHeight(), 255); // sea level to the overworld build limit
        int maxW = Math.min(cfg.maxWidth(), cfg.maxDepth());
        StringBuilder sb = new StringBuilder("SCALE ADVICE (this server: envelope up to ").append(cfg.maxWidth()).append(" x ").append(cfg.maxHeight()).append(" x ")
                .append(cfg.maxDepth()).append(", block budget ").append(cfg.maxBlocks()).append("; about 255 blocks fit between sea level and the build limit): ");
        if (h <= 0 && w <= 0) return sb.append("no reference dimensions; choose a scale that fits.").toString();
        double fit = 1.0;
        if (h > 0) fit = Math.min(fit, maxH / h);
        if (w > 0) fit = Math.min(fit, maxW / w);
        sb.append("largest height ").append(Math.round(h)).append(" m, largest plan dimension ").append(Math.round(w)).append(" m. ");
        if (fit >= 1) {
            sb.append("1:1 fits (1 block = 1 m).");
            if (h > 0 && h < 30 && w < 80) sb.append(" It is small in game; 2:1 (double size) makes a stronger landmark if the player wants grandeur.");
        } else {
            double[] nice = {0.75, 2.0 / 3, 0.5, 0.4, 1.0 / 3, 0.25, 0.2, 1.0 / 6, 0.125, 0.1};
            double pick = 0.1;
            for (double n : nice) if (n <= fit) { pick = n; break; }
            sb.append("1:1 does not fit. Use about ").append(fmt(pick)).append(" scale (")
                    .append(Math.round(h * pick)).append(" tall, ").append(Math.round(w * pick)).append(" across). Scale every dimension by the same factor.");
            if (w > maxW * 3) sb.append(" Very long structures can also be built as a representative section at a larger scale.");
        }
        return sb.toString();
    }

    private static String fmt(double s) {
        if (Math.abs(s - 2.0 / 3) < 1e-6) return "2:3";
        if (Math.abs(s - 1.0 / 3) < 1e-6) return "1:3";
        if (Math.abs(s - 1.0 / 6) < 1e-6) return "1:6";
        if (s == 0.75) return "3:4";
        if (s == 0.4) return "2:5";
        return "1:" + Math.round(1 / s);
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim()
                .replaceFirst("^the ", "");
    }
}
