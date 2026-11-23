package io.github.shengboj0324.architect.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolUseBlock;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The in-game conversation: players talk to the Architect in chat and Claude answers in character, using companion
 * tools (start a design, follow, undo, ...) that the server executes. One append-only history per player keeps the
 * prompt cache warm; it is trimmed from the front at a plain user turn when it grows long.
 */
public final class ConversationAgent {

    /** Executes a companion tool for a player and returns the text result. Called from the conversation thread. */
    public interface Host {
        String act(UUID player, String tool, JsonObject input);
    }

    public record Settings(String apiKey, String baseUrl, String model, String effort, long maxTokens, int maxHistory) {}

    private static final class Convo {
        final List<MessageParam> history = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final Object lock = new Object();
    }

    private final Settings cfg;
    private final AnthropicClient client;
    private final String system;
    private final List<Tool> tools = new ArrayList<>();
    private final Map<UUID, Convo> convos = new ConcurrentHashMap<>();
    private final Logger log;

    public ConversationAgent(Settings cfg, Logger log) {
        this.cfg = cfg;
        this.log = log;
        AnthropicOkHttpClient.Builder cb = AnthropicOkHttpClient.builder().apiKey(cfg.apiKey()).maxRetries(3).timeout(Duration.ofMinutes(2));
        if (cfg.baseUrl() != null && !cfg.baseUrl().isBlank()) cb.baseUrl(cfg.baseUrl());
        this.client = cb.build();
        this.system = PromptLibrary.bundled("prompts/companion.md");
        for (ArchitectTools.Def d : definitions()) tools.add(ArchitectAgent.toSdk(d));
    }

    /** Something happened that the player should hear about next time they speak (or that Claude should know). */
    public void note(UUID player, String event) {
        Convo c = convos.computeIfAbsent(player, k -> new Convo());
        synchronized (c.events) {
            c.events.add(event);
            while (c.events.size() > 8) c.events.remove(0);
        }
    }

    public void forget(UUID player) { convos.remove(player); }

    /**
     * Handles one chat line from a player. Blocking (run it off the main thread). Every reply line is passed to
     * {@code say}; tools go to the host.
     */
    public void respond(UUID player, String playerName, String text, String context, Host host, Consumer<String> say) {
        Convo c = convos.computeIfAbsent(player, k -> new Convo());
        synchronized (c.lock) {
            StringBuilder u = new StringBuilder();
            synchronized (c.events) {
                if (!c.events.isEmpty()) {
                    u.append("<events>\n");
                    for (String e : c.events) u.append("- ").append(e).append('\n');
                    u.append("</events>\n");
                    c.events.clear();
                }
            }
            if (context != null && !context.isBlank()) u.append("<context>\n").append(context.trim()).append("\n</context>\n");
            u.append("<player name=\"").append(playerName).append("\">").append(text).append("</player>");
            trim(c);
            c.history.add(MessageParam.ofUser(u.toString()));
            try {
                for (int round = 0; round < 6; round++) {
                    Message msg = call(c);
                    StopReason stop = msg.stopReason().orElse(StopReason.END_TURN);
                    if (stop.equals(StopReason.REFUSAL)) {
                        c.history.remove(c.history.size() - 1); // keep the conversation usable
                        say.accept("I'd rather not help with that one. Ask me for a build instead?");
                        return;
                    }
                    c.history.add(msg.toParam());
                    for (ContentBlock b : msg.content()) b.text().ifPresent(t -> { if (!t.text().isBlank()) say.accept(clean(t.text())); });
                    List<ToolUseBlock> uses = new ArrayList<>();
                    for (ContentBlock b : msg.content()) b.toolUse().ifPresent(uses::add);
                    if (uses.isEmpty()) return;
                    List<ContentBlockParam> results = new ArrayList<>();
                    for (ToolUseBlock use : uses) {
                        JsonObject in = ArchitectAgent.parseInput(use);
                        String out;
                        boolean error = false;
                        if (in == null || stop.equals(StopReason.MAX_TOKENS)) { out = "INVALID_JSON: resend the call with valid input."; error = true; }
                        else {
                            try { out = host.act(player, use.name(), in); }
                            catch (RuntimeException e) { out = "failed: " + e.getMessage(); error = true; }
                        }
                        results.add(ArchitectAgent.toResult(use.id(), new ToolOutput(out, null, error)));
                    }
                    c.history.add(MessageParam.ofUserOfContentBlockParams(results));
                }
            } catch (AnthropicServiceException e) {
                log.log(Level.WARNING, "Conversation API error " + e.statusCode(), e);
                rollbackToUser(c);
                say.accept(e.statusCode() == 401 ? "My connection to Claude is not set up (check the API key)." : "My thoughts are clouded right now; try again in a moment.");
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "Conversation failed", e);
                rollbackToUser(c);
                say.accept("Sorry, I lost my train of thought. Say that again?");
            }
        }
    }

    private Message call(Convo c) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(cfg.model())
                .maxTokens(cfg.maxTokens())
                .thinking(ThinkingConfigAdaptive.builder().build())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(cfg.effort())).build())
                .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(system)
                        .cacheControl(CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build()).build()))
                .cacheControl(CacheControlEphemeral.builder().build())
                .messages(new ArrayList<>(c.history));
        for (Tool t : tools) b.addTool(t);
        MessageAccumulator acc = MessageAccumulator.create();
        try (StreamResponse<RawMessageStreamEvent> resp = client.messages().createStreaming(b.build())) {
            resp.stream().forEach(acc::accumulate);
        }
        return acc.message();
    }

    /** Drops the oldest turns once the history is long, cutting at a plain user message so the history stays valid. */
    private void trim(Convo c) {
        if (c.history.size() < cfg.maxHistory()) return;
        int cut = -1;
        for (int i = c.history.size() - cfg.maxHistory() / 2; i < c.history.size(); i++) {
            if (i > 0 && isPlainUser(c.history.get(i))) { cut = i; break; }
        }
        if (cut > 0) c.history.subList(0, cut).clear();
    }

    /** After a failed call, drop everything after (and including) the last plain user message. */
    private static void rollbackToUser(Convo c) {
        for (int i = c.history.size() - 1; i >= 0; i--) {
            if (isPlainUser(c.history.get(i))) { c.history.subList(i, c.history.size()).clear(); return; }
        }
        c.history.clear();
    }

    private static boolean isPlainUser(MessageParam m) {
        return m.role().equals(MessageParam.Role.USER) && m.content().isString();
    }

    private static String clean(String s) {
        return s.replace("**", "").replace("`", "").replaceAll("(?m)^#+\\s*", "").replaceAll("\\s*\\n+\\s*", " ").trim();
    }

    // ------------------------------------------------------------------------------------------------ tools

    public static List<ArchitectTools.Def> definitions() {
        List<ArchitectTools.Def> d = new ArrayList<>();
        d.add(def("build", "Design and build something NEW on open ground for this player. It starts from an empty plan and cannot see or change builds that already "
                        + "exist: for anything about an existing build (fix, renovate, add to it, work on part of it) use renovate. The studio takes one to several minutes.",
                """
                {"type":"object","properties":{
                  "brief":{"type":"string","description":"A complete design brief, 2-5 sentences: type, style, size or scale, key features, materials, site. For landmarks name it exactly."},
                  "where":{"type":"string","enum":["front","look","here"],"description":"front: in front of the player (default); look: centred where they are looking; here: centred on them"}},
                 "required":["brief"]}"""));
        d.add(def("template", "Instantly build one of the curated master designs (see catalog).",
                """
                {"type":"object","properties":{
                  "name":{"type":"string"},"style":{"type":"string"},"scale":{"type":"integer"},"generations":{"type":"integer","description":"village only, 1-5"},
                  "where":{"type":"string","enum":["front","look","here"]}},
                 "required":["name"]}"""));
        d.add(def("expand", "Grow the player's last NEW build (one you designed with build) into its next generation (add wings, districts, the next era). "
                        + "For a build you renovated, or any other existing build, use renovate instead.",
                """
                {"type":"object","properties":{"brief":{"type":"string","description":"What to add or change, as a design brief"}},"required":["brief"]}"""));
        d.add(def("renovate", "Fix, renovate, refurbish, extend or reinvent an EXISTING build in the world, or work on part of one (\"the bottom half\", \"the lower floors\"), "
                        + "including follow-ups after an earlier renovation (each call reads the build fresh from the world): the one the player is looking at, or their pos1/pos2 selection. "
                        + "Your design studio captures it, studies renders and an analysis (floors, rooms, damage, flooding, dark spots), edits it in place and builds only the changes. "
                        + "Containers, signs and item frames are never touched, and it can be undone.",
                """
                {"type":"object","properties":{
                  "brief":{"type":"string","description":"Everything the player wants done, as a full brief: what to repair, what to add or change (walls, floors, lighting, furniture, interior walls, elevators, details), style, how bold"},
                  "target":{"type":"string","enum":["look","selection"],"description":"look: the build the player looks at (default); selection: their pos1/pos2 selection"},
                  "mode":{"type":"string","enum":["full","repair"],"description":"full: designed renovation (default); repair: quick free fix of holes, water and lighting only"}},
                 "required":["brief"]}"""));
        d.add(def("confirm", "Confirm a previewed build or renovation when the player says yes / go ahead.", "{\"type\":\"object\",\"properties\":{}}"));
        d.add(def("inspect", "Look over the build the player is looking at and tell them what you see (floors, rooms, damage, flooding, dark spots, quality).", "{\"type\":\"object\",\"properties\":{}}"));
        d.add(def("move", "Move yourself: follow the player, stay here, come to the player, or walk to the block they are looking at.",
                """
                {"type":"object","properties":{"action":{"type":"string","enum":["follow","stay","come","go_to_look"]}},"required":["action"]}"""));
        d.add(def("undo", "Take down the player's most recent build and restore the land.", "{\"type\":\"object\",\"properties\":{}}"));
        d.add(def("cancel", "Stop the player's design or construction in progress.", "{\"type\":\"object\",\"properties\":{}}"));
        d.add(def("status", "What you are designing or building for this player, and progress.", "{\"type\":\"object\",\"properties\":{}}"));
        d.add(def("rate", "Record the player's rating of their finished build (1-5). 4-5 stars save it as an example you learn from.",
                """
                {"type":"object","properties":{"stars":{"type":"integer","minimum":1,"maximum":5}},"required":["stars"]}"""));
        d.add(def("surroundings", "Terrain, biome, height room and water around the player, and the block they are looking at.", "{\"type\":\"object\",\"properties\":{}}"));
        d.add(def("catalog", "Your templates, styles and researched real-world landmarks.", "{\"type\":\"object\",\"properties\":{}}"));
        return d;
    }

    private static ArchitectTools.Def def(String name, String desc, String schema) {
        return new ArchitectTools.Def(name, desc, JsonParser.parseString(schema).getAsJsonObject());
    }
}
