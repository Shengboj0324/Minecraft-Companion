package io.github.shengboj0324.architect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A named architectural style: weighted block lists per material role plus prose guidance for the AI. */
public final class Style {

    public record Weighted(String block, double weight) {}

    /** Roles every style defines. Generators and the AI refer to them as {@code @role}. */
    public static final List<String> ROLES = List.of(
            "foundation", "wall", "wall_alt", "trim", "accent", "column", "floor", "roof", "roof_trim",
            "window", "light", "path", "plant", "leaves", "metal");

    private final String name;
    private final String description;
    private final String guidance;
    private final Map<String, List<Weighted>> roles;

    public Style(String name, String description, String guidance, Map<String, List<Weighted>> roles) {
        this.name = name;
        this.description = description;
        this.guidance = guidance;
        Map<String, List<Weighted>> copy = new LinkedHashMap<>();
        roles.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        this.roles = Collections.unmodifiableMap(copy);
    }

    public String name() { return name; }
    public String description() { return description; }
    public String guidance() { return guidance; }
    public Map<String, List<Weighted>> roles() { return roles; }

    public List<Weighted> role(String role) { return roles.get(role); }

    public String primary(String role) {
        List<Weighted> w = roles.get(role);
        return w == null || w.isEmpty() ? null : w.get(0).block();
    }

    /** Returns a copy with some roles replaced (used for AI palette overrides). */
    public Style withRoles(Map<String, List<Weighted>> overrides) {
        Map<String, List<Weighted>> m = new LinkedHashMap<>(roles);
        m.putAll(overrides);
        return new Style(name, description, guidance, m);
    }

    public String describeRoles() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<Weighted>> e : roles.entrySet()) {
            List<String> parts = new ArrayList<>();
            for (Weighted w : e.getValue()) parts.add(w.weight() == 1.0 ? w.block() : w.block() + "*" + trim(w.weight()));
            sb.append("  @").append(e.getKey()).append(" = ").append(String.join(", ", parts)).append('\n');
        }
        return sb.toString();
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }
}
