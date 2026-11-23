package io.github.shengboj0324.architect.ai;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.Base64ImageSource;
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
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.Usage;
import com.anthropic.models.messages.WebSearchTool20260209;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.shengboj0324.architect.core.AsciiRenderer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Claude tool loop. Each turn streams one response, runs the design tools it asked for, and sends the results
 * back (renders include an image so Claude can critique its own work visually) until the design passes the
 * quality gate via {@code finish}. History is append-only so thinking blocks and the prompt cache stay valid.
 */
public final class ArchitectAgent {

    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";
    private static final Gson GSON = new Gson();

    private final AgentSettings cfg;
    private final AnthropicClient client;
    private final PromptLibrary prompts;
    private final ArchitectTools tools = new ArchitectTools();
    private final List<Tool> toolDefs = new ArrayList<>();
    private final Logger log;
    private volatile boolean fallbacks;

    public ArchitectAgent(AgentSettings cfg, PromptLibrary prompts, Logger log) {
        this.cfg = cfg;
        this.prompts = prompts;
        this.log = log;
        this.fallbacks = cfg.fallbacks();
        AnthropicOkHttpClient.Builder cb = AnthropicOkHttpClient.builder()
                .apiKey(cfg.apiKey())
                .maxRetries(4)
                .timeout(Duration.ofMinutes(10));
        if (cfg.baseUrl() != null && !cfg.baseUrl().isBlank()) cb.baseUrl(cfg.baseUrl());
        this.client = cb.build();
        for (ArchitectTools.Def d : ArchitectTools.definitions()) toolDefs.add(toSdk(d));
    }

    public ArchitectTools tools() { return tools; }

    /** Starts a new design (opening message) or continues one (follow-up), and runs the loop to completion. Blocking. */
    public void run(DesignSession s, String request, String siteInfo, String exemplars, AgentListener l) {
        boolean first = s.history().isEmpty();
        s.requests().add(request);
        String text = first ? prompts.opening(s, request, siteInfo, exemplars) : prompts.followUp(s, request);
        s.history().add(userText(text));
        s.status(DesignSession.Status.DESIGNING);
        int nudges = 0, closingTurns = 0;
        boolean announced = false;
        try {
            for (int turn = 0; turn < cfg.maxTurns(); turn++) {
                if (s.status() == DesignSession.Status.CANCELLED) { l.failed(s, "cancelled"); return; }
                Message msg = call(s);
                Usage u = msg.usage();
                long cacheRead = u.cacheReadInputTokens().orElse(0L), cacheWrite = u.cacheCreationInputTokens().orElse(0L);
                s.addUsage(u.inputTokens() + cacheRead + cacheWrite, u.outputTokens(), cacheRead);
                StopReason stop = msg.stopReason().orElse(StopReason.END_TURN);

                if (stop.equals(StopReason.REFUSAL)) {
                    String why = msg.stopDetails().flatMap(d -> d.explanation()).orElse("the request was declined");
                    l.failed(s, "Claude declined this request: " + why);
                    s.status(DesignSession.Status.FAILED);
                    return;
                }
                s.history().add(msg.toParam());
                for (ContentBlock b : msg.content()) b.text().ifPresent(t -> { if (!t.text().isBlank()) l.say(t.text().trim()); });

                List<ToolUseBlock> uses = new ArrayList<>();
                for (ContentBlock b : msg.content()) b.toolUse().ifPresent(uses::add);

                if (uses.isEmpty() && stop.equals(StopReason.PAUSE_TURN)) continue; // a server tool (web search) paused the turn: resume it
                if (uses.isEmpty()) {
                    if (announced) return;
                    if (s.finished()) break;
                    if (stop.equals(StopReason.MAX_TOKENS)) {
                        s.history().add(userText("Your reply was cut off by the output limit. Continue, and keep each build call under about 60 ops."));
                        continue;
                    }
                    if (++nudges > 2) break;
                    s.history().add(userText("The design is not submitted yet. Continue building and call finish when it is ready (or explain in one sentence why you cannot)."));
                    continue;
                }

                List<ContentBlockParam> results = new ArrayList<>();
                for (ToolUseBlock use : uses) {
                    ToolOutput out;
                    if (stop.equals(StopReason.MAX_TOKENS) && use == uses.get(uses.size() - 1)) {
                        out = ToolOutput.err("This call was cut off by the output limit, so it was not run. Send smaller batches (under about 60 ops).");
                    } else {
                        JsonObject input = parseInput(use);
                        if (input == null) out = ToolOutput.err("INVALID_JSON: the tool input could not be parsed. Resend the call with valid JSON.");
                        else {
                            l.tool(use.name(), input.has("note") && input.get("note").isJsonPrimitive() ? input.get("note").getAsString() : null);
                            out = announced ? ToolOutput.err("The design was already submitted and is being built; no more tools this round.")
                                    : tools.execute(s, use.name(), input);
                        }
                    }
                    results.add(toResult(use.id(), out));
                }
                s.history().add(MessageParam.ofUserOfContentBlockParams(results));
                if (s.stopReason() != null) { s.status(DesignSession.Status.FAILED); l.failed(s, s.stopReason()); return; }
                // a session that still has no blocks after many turns is stuck (for example it cannot see the build it was asked to change)
                if (turn >= 9 && !s.renovating() && s.model().nonAirCount() == 0) {
                    s.status(DesignSession.Status.FAILED);
                    l.failed(s, "I couldn't start that design. If you meant a build that already exists, look at it and ask me to renovate it (or use /architect renovate).");
                    return;
                }

                if (s.finished() && !announced) {
                    // Hand the design to the builder right away; the next turn only collects the closing line.
                    announced = true;
                    s.status(DesignSession.Status.READY);
                    l.ready(s);
                    closingTurns = 0;
                }
                if (announced && ++closingTurns > 1) return;
                if (s.totalInputLast() > cfg.compactAtInputTokens()) compact(s);
            }
            if (announced) return;
            if (s.finished()) {
                s.status(DesignSession.Status.READY);
                l.ready(s);
            } else {
                s.status(DesignSession.Status.FAILED);
                l.failed(s, "the design did not reach the quality gate within " + cfg.maxTurns() + " turns");
            }
        } catch (AnthropicServiceException e) {
            log.log(Level.WARNING, "Claude API error " + e.statusCode(), e);
            if (announced) return;
            s.status(DesignSession.Status.FAILED);
            l.failed(s, "Claude API error " + e.statusCode() + ": " + e.getMessage());
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "Architect agent failed", e);
            if (announced) return;
            s.status(DesignSession.Status.FAILED);
            l.failed(s, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------------------------------------ API call

    private Message call(DesignSession s) {
        try {
            return stream(s, fallbacks);
        } catch (AnthropicServiceException e) {
            // Fallbacks are a beta; if this account or endpoint rejects them, carry on without.
            if (fallbacks && e.statusCode() == 400 && String.valueOf(e.getMessage()).toLowerCase().contains("fallback")) {
                log.warning("Server-side fallbacks were rejected by the API; continuing without them.");
                fallbacks = false;
                return stream(s, false);
            }
            throw e;
        }
    }

    private Message stream(DesignSession s, boolean withFallbacks) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(cfg.model())
                .maxTokens(cfg.maxTokens())
                .thinking(ThinkingConfigAdaptive.builder().build())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(cfg.effort())).build())
                .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(prompts.system())
                        .cacheControl(CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_1H).build()).build()))
                .cacheControl(CacheControlEphemeral.builder().build()); // automatic caching of the growing conversation
        for (Tool t : toolDefs) b.addTool(t);
        if (cfg.webSearch()) b.addTool(WebSearchTool20260209.builder().maxUses(5).build());
        List<MessageParam> msgs = new ArrayList<>();
        for (Object o : s.history()) msgs.add((MessageParam) o);
        b.messages(msgs);
        if (withFallbacks) {
            b.putAdditionalHeader("anthropic-beta", FALLBACK_BETA);
            b.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        MessageAccumulator acc = MessageAccumulator.create();
        try (StreamResponse<RawMessageStreamEvent> resp = client.messages().createStreaming(b.build())) {
            resp.stream().forEach(acc::accumulate);
        }
        Message m = acc.message();
        Usage u = m.usage();
        s.totalInputLast(u.inputTokens() + u.cacheReadInputTokens().orElse(0L) + u.cacheCreationInputTokens().orElse(0L));
        return m;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** Simple compaction: replace the whole history with a fresh state summary (no thinking blocks are replayed). */
    private void compact(DesignSession s) {
        AsciiRenderer ascii = new AsciiRenderer();
        StringBuilder sb = new StringBuilder();
        sb.append("<context_reset>The conversation was compacted to save context. The design continues; nothing in the model was lost.</context_reset>\n");
        sb.append("<requests>\n");
        for (String r : s.requests()) sb.append("- ").append(r).append('\n');
        sb.append("</requests>\n");
        sb.append("Plan: '").append(s.name()).append("', style ").append(s.baseStyle().name()).append(", envelope ").append(s.model().width()).append(" x ")
                .append(s.model().height()).append(" x ").append(s.model().depth()).append(", ").append(s.batches().size()).append(" batches applied so far, generation ").append(s.generation()).append(".\n");
        sb.append("Palette:\n").append(s.style().describeRoles());
        sb.append("\nCurrent model:\n").append(ascii.render(s.model(), 96));
        if (s.lastReport() != null) sb.append('\n').append(s.lastReport().format());
        sb.append("\nContinue refining (render_views to see it again), then finish.");
        s.history().clear();
        s.history().add(userText(sb.toString()));
        log.info("Compacted design conversation for " + s.name());
    }

    private static MessageParam userText(String text) {
        return MessageParam.ofUser(text);
    }

    static ContentBlockParam toResult(String id, ToolOutput out) {
        ToolResultBlockParam.Builder r = ToolResultBlockParam.builder().toolUseId(id).isError(out.error());
        if (out.png() != null) {
            r.contentOfBlocks(List.of(
                    ToolResultBlockParam.Content.Block.ofText(out.text()),
                    ToolResultBlockParam.Content.Block.ofImage(Base64ImageSource.builder()
                            .data(Base64.getEncoder().encodeToString(out.png()))
                            .mediaType(Base64ImageSource.MediaType.IMAGE_PNG).build())));
        } else {
            r.content(out.text());
        }
        return ContentBlockParam.ofToolResult(r.build());
    }

    /** Converts the SDK's raw tool input to Gson, validating it is a JSON object (eager streaming can truncate). */
    static JsonObject parseInput(ToolUseBlock use) {
        try {
            String json = ObjectMappers.jsonMapper().writeValueAsString(use._input());
            JsonElement e = JsonParser.parseString(json);
            return e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static Tool toSdk(ArchitectTools.Def d) {
        JsonObject schema = d.schema();
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        if (schema.has("properties")) {
            for (Map.Entry<String, JsonElement> e : schema.getAsJsonObject("properties").entrySet()) {
                props.putAdditionalProperty(e.getKey(), JsonValue.from(GSON.fromJson(e.getValue(), Map.class)));
            }
        }
        Tool.InputSchema.Builder in = Tool.InputSchema.builder().properties(props.build());
        if (schema.has("required")) {
            List<String> req = new ArrayList<>();
            schema.getAsJsonArray("required").forEach(x -> req.add(x.getAsString()));
            in.required(req);
        }
        return Tool.builder().name(d.name()).description(d.description()).inputSchema(in.build()).eagerInputStreaming(true).build();
    }
}
