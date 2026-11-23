package io.github.shengboj0324.architect;

import io.github.shengboj0324.architect.ai.AgentSettings;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.file.FileConfiguration;

/** Typed view of config.yml. */
public record PluginSettings(
        AgentSettings agent,
        int exemplarsPerPrompt,
        int blocksPerTick,
        double maxMsPerTick,
        boolean clearSite,
        boolean fillFoundation,
        int maxFoundationDepth,
        int distanceFromPlayer,
        boolean requireConfirm,
        boolean npcEnabled,
        String npcName,
        String npcSkin,
        boolean narrate,
        int cooldownSeconds,
        int maxConcurrentDesigns,
        int maxUndo,
        Chat chat,
        Terrain terrain,
        Renovate renovate,
        Elevator elevator,
        Quotas quotas,
        double throttleMspt,
        String texturePack) {

    /** In-game conversation settings (chat section). */
    public record Chat(boolean enabled, String model, String effort, long maxTokens, List<String> triggers, int conversationSeconds,
                       double range, boolean hideAddressed, int maxHistory, String permission) {}

    /** Renovating existing builds (renovate section). */
    public record Renovate(boolean enabled, int maxRadius, int maxHeight, int maxBlocks, int chunksPerTick, boolean requireConfirm,
                           int previewSeconds, String protectionMode, boolean coreProtect, boolean verify) {}

    /** The plugin's own elevator pads (elevator section). */
    public record Elevator(boolean enabled, String block, int maxDistance) {}

    /** Per-player daily limits (0 = unlimited; architect.unlimited bypasses). */
    public record Quotas(int designsPerDay, long tokensPerDay) {}

    /** How designs meet natural terrain (terrain section). */
    public record Terrain(int scanRadius, boolean defaultNatural, int gradeMargin, int maxGrade) {}

    public static PluginSettings from(FileConfiguration c) {
        String key = c.getString("anthropic.api-key", "");
        if (key == null || key.isBlank()) key = System.getenv("ANTHROPIC_API_KEY");
        Set<String> banned = new HashSet<>();
        for (String s : c.getStringList("design.banned-blocks")) banned.add(s.toLowerCase(Locale.ROOT).replace("minecraft:", ""));
        AgentSettings a = new AgentSettings(
                key == null ? "" : key.trim(),
                c.getString("anthropic.base-url", ""),
                c.getString("anthropic.model", "claude-opus-5-5"),
                c.getString("anthropic.effort", "high"),
                c.getLong("anthropic.max-tokens", 32000),
                c.getInt("design.max-turns", 40),
                c.getInt("design.min-score", 80),
                c.getInt("design.max-gate-rejections", 2),
                c.getBoolean("design.vision", true),
                c.getBoolean("anthropic.fallbacks", true),
                c.getInt("design.max-width", 512),
                c.getInt("design.max-height", 320),
                c.getInt("design.max-depth", 512),
                c.getInt("design.max-blocks", 3_000_000),
                c.getLong("design.compact-at-input-tokens", 150_000),
                banned.isEmpty() ? AgentSettings.defaults("").bannedBlocks() : banned,
                c.getBoolean("design.web-search", false));
        return new PluginSettings(a,
                c.getInt("design.exemplars-per-prompt", 2),
                c.getInt("placement.blocks-per-tick", 400),
                c.getDouble("placement.max-ms-per-tick", 8.0),
                c.getBoolean("placement.clear-site", true),
                c.getBoolean("placement.fill-foundation", true),
                c.getInt("placement.max-foundation-depth", 16),
                c.getInt("placement.distance-from-player", 4),
                c.getBoolean("placement.require-confirm", false),
                c.getBoolean("npc.enabled", true),
                c.getString("npc.name", "Architect"),
                c.getString("npc.skin", ""),
                c.getBoolean("npc.narrate", true),
                c.getInt("limits.cooldown-seconds", 30),
                c.getInt("limits.max-concurrent-designs", 2),
                c.getInt("limits.max-undo", 5),
                new Chat(c.getBoolean("chat.enabled", true),
                        c.getString("chat.model", "claude-opus-5-5"),
                        c.getString("chat.effort", "low"),
                        c.getLong("chat.max-tokens", 4000),
                        lower(c.getStringList("chat.trigger-words").isEmpty() ? List.of("architect") : c.getStringList("chat.trigger-words")),
                        c.getInt("chat.conversation-seconds", 90),
                        c.getDouble("chat.range", 32),
                        c.getBoolean("chat.hide-addressed-messages", false),
                        c.getInt("chat.max-history-messages", 40),
                        c.getString("chat.permission", "architect.use")),
                new Terrain(Math.max(16, Math.min(256, c.getInt("terrain.scan-radius", 80))),
                        "natural".equalsIgnoreCase(c.getString("terrain.default-mode", "level")),
                        Math.max(0, Math.min(32, c.getInt("terrain.grade-margin", 8))),
                        Math.max(1, c.getInt("terrain.max-grade", 24))),
                new Renovate(c.getBoolean("renovate.enabled", true),
                        Math.max(16, Math.min(512, c.getInt("renovate.max-radius", 160))),
                        Math.max(16, Math.min(384, c.getInt("renovate.max-height", 192))),
                        Math.max(1000, c.getInt("renovate.max-blocks", 2_000_000)),
                        Math.max(1, Math.min(200, c.getInt("renovate.chunks-per-tick", 12))),
                        c.getBoolean("renovate.require-confirm", false),
                        Math.max(0, Math.min(600, c.getInt("renovate.preview-seconds", 60))),
                        c.getString("protection.mode", "auto").toLowerCase(Locale.ROOT),
                        c.getBoolean("protection.coreprotect-logging", true),
                        c.getBoolean("renovate.verify", true)),
                new Elevator(c.getBoolean("elevator.enabled", true), c.getString("elevator.block", "lodestone").toLowerCase(Locale.ROOT).replace("minecraft:", ""),
                        Math.max(3, Math.min(384, c.getInt("elevator.max-distance", 96)))),
                new Quotas(Math.max(0, c.getInt("limits.designs-per-day", 0)), Math.max(0, c.getLong("limits.tokens-per-day", 0))),
                Math.max(0, c.getDouble("placement.throttle-mspt", 40.0)),
                c.getString("render.texture-pack", ""));
    }

    private static List<String> lower(List<String> in) {
        List<String> out = new java.util.ArrayList<>();
        for (String s : in) out.add(s.toLowerCase(Locale.ROOT).trim());
        return out;
    }

    public boolean hasApiKey() { return agent.apiKey() != null && !agent.apiKey().isBlank(); }
}
