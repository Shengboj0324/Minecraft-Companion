package io.github.shengboj0324.architect.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Loads {@link Style}s from JSON (bundled styles.json, optionally overridden by the server admin). */
public final class StyleLibrary {

    private final Map<String, Style> styles = new LinkedHashMap<>();

    public static StyleLibrary bundled() {
        try (InputStream in = StyleLibrary.class.getResourceAsStream("/styles.json")) {
            if (in == null) throw new IllegalStateException("styles.json missing");
            StyleLibrary lib = new StyleLibrary();
            lib.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return lib;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public void load(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            styles.put(e.getKey().toLowerCase(Locale.ROOT), parse(e.getKey(), e.getValue().getAsJsonObject()));
        }
    }

    public static Style parse(String name, JsonObject o) {
        Map<String, List<Style.Weighted>> roles = new LinkedHashMap<>();
        JsonObject r = o.getAsJsonObject("roles");
        for (Map.Entry<String, JsonElement> re : r.entrySet()) roles.put(re.getKey(), parseWeighted(re.getValue()));
        return new Style(name,
                o.has("description") ? o.get("description").getAsString() : "",
                o.has("guidance") ? o.get("guidance").getAsString() : "",
                roles);
    }

    /** Accepts "block", ["a","b"], [["a",3],["b",1]] or [{"block":"a","weight":3}]. */
    public static List<Style.Weighted> parseWeighted(JsonElement el) {
        List<Style.Weighted> out = new ArrayList<>();
        if (el.isJsonPrimitive()) {
            out.add(new Style.Weighted(el.getAsString(), 1));
            return out;
        }
        JsonArray arr = el.getAsJsonArray();
        for (JsonElement item : arr) {
            if (item.isJsonPrimitive()) out.add(new Style.Weighted(item.getAsString(), 1));
            else if (item.isJsonArray()) {
                JsonArray pair = item.getAsJsonArray();
                out.add(new Style.Weighted(pair.get(0).getAsString(), pair.size() > 1 ? pair.get(1).getAsDouble() : 1));
            } else if (item.isJsonObject()) {
                JsonObject obj = item.getAsJsonObject();
                out.add(new Style.Weighted(obj.get("block").getAsString(), obj.has("weight") ? obj.get("weight").getAsDouble() : 1));
            }
        }
        return out;
    }

    public Style get(String name) {
        if (name == null) return null;
        return styles.get(name.toLowerCase(Locale.ROOT));
    }

    public Style getOrDefault(String name) {
        Style s = get(name);
        return s != null ? s : styles.values().iterator().next();
    }

    public Collection<Style> all() { return styles.values(); }

    public List<String> names() { return new ArrayList<>(styles.keySet()); }
}
