package io.github.shengboj0324.architect.ai;

import io.github.shengboj0324.architect.core.Style;
import io.github.shengboj0324.architect.core.StyleLibrary;
import io.github.shengboj0324.architect.core.Templates;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds the system prompt (identical for every session so it stays prompt-cached) and the opening user message
 * (request, site facts, exemplars). A server owner can override prompts/system.md in the plugin folder.
 */
public final class PromptLibrary {

    private final String system;

    public PromptLibrary(StyleLibrary styles, Path overrideDir) {
        String base = null;
        if (overrideDir != null) {
            Path p = overrideDir.resolve("system.md");
            try { if (Files.isRegularFile(p)) base = Files.readString(p); } catch (IOException ignored) { }
        }
        if (base == null) base = bundled("prompts/system.md");
        StringBuilder st = new StringBuilder();
        for (Style s : styles.all()) {
            st.append("## ").append(s.name()).append("\n").append(s.description()).append("\n").append(s.guidance()).append("\nRoles:\n").append(s.describeRoles()).append('\n');
        }
        StringBuilder tp = new StringBuilder("\n# Curated templates (use_template)\n\n");
        for (Templates.Info i : Templates.ALL.values()) tp.append("- `").append(i.name()).append("` (").append(i.defaultStyle()).append("): ").append(i.description()).append('\n');
        this.system = base.replace("{{STYLES}}", st.toString().trim()) + tp;
    }

    public String system() { return system; }

    /** The first user turn of a session. */
    public String opening(DesignSession s, String request, String siteInfo, String exemplars) {
        AgentSettings cfg = s.settings();
        StringBuilder sb = new StringBuilder();
        if (s.renovating()) {
            int[] b = s.model().bounds();
            sb.append("<renovation player=\"").append(s.owner()).append("\">\n").append(request.trim()).append("\n</renovation>\n\n");
            sb.append("The model already holds the player's existing build, captured from the world (").append(s.model().nonAirCount()).append(" blocks");
            if (b != null) sb.append(", x ").append(b[0]).append("..").append(b[3]).append(", y ").append(b[1]).append("..").append(b[4]).append(", z ").append(b[2]).append("..").append(b[5]);
            sb.append("; the envelope leaves room to build outward, and y can go down to -64 for foundations). Protected blocks are locked.\n");
            if (siteInfo != null && !siteInfo.isBlank()) sb.append("<analysis>\n").append(siteInfo.trim()).append("\n</analysis>\n");
            sb.append("\nRenovate it as asked: inspect it (inspect_structure, view_build from several angles and slices), plan, repair, improve, look again, and finish. "
                    + "Keep what is good, keep its style unless asked to reinvent it, and do everything the player asked for.");
            return sb.toString();
        }
        sb.append("<build_request player=\"").append(s.owner()).append("\">\n").append(request.trim()).append("\n</build_request>\n\n");
        sb.append("<server_limits>max envelope ").append(cfg.maxWidth()).append(" x ").append(cfg.maxHeight()).append(" x ").append(cfg.maxDepth())
                .append(", block budget ").append(cfg.maxBlocks()).append(", quality gate minimum score ").append(cfg.minScore()).append("</server_limits>\n");
        if (siteInfo != null && !siteInfo.isBlank()) sb.append("<site>\n").append(siteInfo.trim()).append("\n</site>\n");
        if (exemplars != null && !exemplars.isBlank()) sb.append("\n<exemplars>\nPast builds players rated highly. Learn from their approach; do not copy them unless asked.\n")
                .append(exemplars).append("</exemplars>\n");
        sb.append("\nDesign this build now: plan it, build it, look at it, refine it, and finish it. If the request is vague, choose a fitting style and a size that suits it (a house is about 9-15 blocks wide, a castle 50-90, a cathedral 40-70 long).");
        return sb.toString();
    }

    /** A follow-up turn in the same conversation (expand / modify). */
    public String followUp(DesignSession s, String request) {
        return "<follow_up player=\"" + s.owner() + "\" generation=\"" + s.generation() + "\">\n" + request.trim() + "\n</follow_up>\n"
                + "The previous design is still in the model and already built in the world. Extend or modify it as asked (call set_plan with keep_model:true if you need a larger envelope; it can only grow toward +x, +y and +z, so extend east, south or upward, and never move existing parts), then render, refine and finish again.";
    }

    static String bundled(String path) {
        try (InputStream in = PromptLibrary.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
