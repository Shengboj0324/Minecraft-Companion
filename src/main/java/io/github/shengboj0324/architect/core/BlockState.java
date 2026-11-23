package io.github.shengboj0324.architect.core;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable, Bukkit-free block state: a block id plus sorted properties.
 * Format matches vanilla / {@code Bukkit.createBlockData}: {@code oak_stairs[facing=north,half=bottom]}.
 */
public final class BlockState {

    public static final BlockState AIR = new BlockState("air", Map.of());

    private final String id;
    private final Map<String, String> props;
    private final String text;

    private BlockState(String id, Map<String, String> props) {
        this.id = id;
        this.props = props;
        this.text = format(id, props);
    }

    public static BlockState of(String id) {
        return new BlockState(normalizeId(id), Map.of());
    }

    public static BlockState of(String id, Map<String, String> props) {
        return new BlockState(normalizeId(id), props.isEmpty() ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(props)));
    }

    /** Parses {@code minecraft:id[k=v,...]}; throws IllegalArgumentException on malformed input. */
    public static BlockState parse(String raw) {
        if (raw == null) throw new IllegalArgumentException("block is null");
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) throw new IllegalArgumentException("block is empty");
        int b = s.indexOf('[');
        if (b < 0) return of(s);
        if (!s.endsWith("]")) throw new IllegalArgumentException("unclosed '[' in block state: " + raw);
        String id = s.substring(0, b);
        String body = s.substring(b + 1, s.length() - 1).trim();
        Map<String, String> props = new TreeMap<>();
        if (!body.isEmpty()) {
            for (String part : body.split(",")) {
                int eq = part.indexOf('=');
                if (eq <= 0 || eq == part.length() - 1) throw new IllegalArgumentException("bad property '" + part + "' in " + raw);
                props.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return of(id, props);
    }

    private static String normalizeId(String id) {
        String s = id.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.startsWith("minecraft:")) s = s.substring("minecraft:".length());
        if (s.isEmpty() || !s.matches("[a-z0-9_./-]+")) throw new IllegalArgumentException("invalid block id: " + id);
        return s;
    }

    private static String format(String id, Map<String, String> props) {
        if (props.isEmpty()) return id;
        StringBuilder sb = new StringBuilder(id).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : props.entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }

    public String id() { return id; }

    public Map<String, String> props() { return props; }

    public String prop(String key) { return props.get(key); }

    public boolean isAir() { return id.equals("air") || id.equals("cave_air") || id.equals("void_air"); }

    public BlockState with(String key, String value) {
        Map<String, String> m = new TreeMap<>(props);
        if (value == null) m.remove(key); else m.put(key, value);
        return of(id, m);
    }

    public BlockState withId(String newId) {
        return of(newId, props);
    }

    /** Namespaced form for Bukkit: {@code minecraft:oak_stairs[facing=north]}. */
    public String toNamespaced() { return "minecraft:" + text; }

    @Override public String toString() { return text; }

    @Override public boolean equals(Object o) {
        return o instanceof BlockState other && other.text.equals(text);
    }

    @Override public int hashCode() { return Objects.hashCode(text); }
}
