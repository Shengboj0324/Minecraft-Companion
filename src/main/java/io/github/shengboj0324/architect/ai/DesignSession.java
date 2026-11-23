package io.github.shengboj0324.architect.ai;

import com.google.gson.JsonArray;
import io.github.shengboj0324.architect.core.Canvas;
import io.github.shengboj0324.architect.core.Ground;
import io.github.shengboj0324.architect.core.Materials;
import io.github.shengboj0324.architect.core.QualityAnalyzer;
import io.github.shengboj0324.architect.core.Style;
import io.github.shengboj0324.architect.core.StyleLibrary;
import io.github.shengboj0324.architect.core.VoxelModel;
import io.github.shengboj0324.architect.core.Existing;
import io.github.shengboj0324.architect.world.SitePlan;
import io.github.shengboj0324.architect.world.StructureCapture;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * One design conversation: the voxel model being designed, its palette, the op log (for exemplars and replay)
 * and the Claude conversation history, which is kept append-only so thinking blocks and the prompt cache stay valid.
 * The model is touched only by the agent thread while designing, then read by the placer once the design is ready.
 */
public final class DesignSession {

    public enum Status { DESIGNING, READY, BUILDING, BUILT, FAILED, CANCELLED }

    private final UUID id = UUID.randomUUID();
    private final String owner;
    private final StyleLibrary styles;
    private final AgentSettings settings;
    private final List<String> requests = new ArrayList<>();
    private final Deque<JsonArray> batches = new ArrayDeque<>();
    /** Conversation history (SDK MessageParam objects); typed loosely so the core stays SDK-free. */
    private final List<Object> history = new ArrayList<>();

    private volatile Status status = Status.DESIGNING;
    private String name = "Untitled";
    private String summary = "";
    private Style baseStyle;
    private Style style;
    private Materials materials;
    private VoxelModel model;
    private Canvas canvas;
    private QualityAnalyzer.Report lastReport;
    private boolean finished;
    private int gateRejections;
    private int renders;
    private int buildCalls;
    private int generation = 1;
    private long inputTokens, outputTokens, cacheReadTokens;
    private int rating;
    private long totalInputLast;
    private SitePlan site; // the surveyed land the design stands on; null when designing without a world (eval)
    private StructureCapture.Result renovation; // the existing build being renovated, or null for a new design
    private int originalScore = -1;

    public DesignSession(String owner, StyleLibrary styles, AgentSettings settings, String styleName) {
        this.owner = owner;
        this.styles = styles;
        this.settings = settings;
        this.baseStyle = styles.getOrDefault(styleName);
        this.style = baseStyle;
        this.materials = new Materials(style, id.getLeastSignificantBits());
        resetModel(64, 48, 64);
    }

    public void resetModel(int w, int h, int d) {
        if (renovation != null) { // a renovation never throws the existing build away; the envelope can only grow
            model.setEnvelope(Math.max(w, model.width()), Math.max(h, model.height()), Math.max(d, model.depth()));
            return;
        }
        model = new VoxelModel(w, h, d);
        model.setMaxBlocks(settings.maxBlocks());
        canvas = new Canvas(model, materials);
        batches.clear();
        lastReport = null;
    }

    public void setEnvelope(int w, int h, int d) { model.setEnvelope(w, h, d); }

    public void setStyle(Style s) {
        baseStyle = s;
        style = s;
        materials.setStyle(s);
    }

    public void overridePalette(Style s) {
        style = s;
        materials.setStyle(s);
    }

    public UUID id() { return id; }
    public String owner() { return owner; }
    public StyleLibrary styles() { return styles; }
    public AgentSettings settings() { return settings; }
    public List<String> requests() { return requests; }
    public String firstRequest() { return requests.isEmpty() ? "" : requests.get(0); }
    public Deque<JsonArray> batches() { return batches; }
    public List<Object> history() { return history; }
    public Status status() { return status; }
    public void status(Status s) { status = s; }
    public String name() { return name; }
    public void name(String n) { name = n; }
    public String summary() { return summary; }
    public void summary(String s) { summary = s; }
    public Style baseStyle() { return baseStyle; }
    public Style style() { return style; }
    public Materials materials() { return materials; }
    public VoxelModel model() { return model; }
    public Canvas canvas() { return canvas; }
    public QualityAnalyzer.Report lastReport() { return lastReport; }
    public void lastReport(QualityAnalyzer.Report r) { lastReport = r; }
    public boolean finished() { return finished; }
    public void finished(boolean f) { finished = f; }
    public int gateRejections() { return gateRejections; }
    public void rejectedAtGate() { gateRejections++; }
    public int renders() { return renders; }
    public void rendered() { renders++; }
    public int buildCalls() { return buildCalls; }
    public void built() { buildCalls++; }
    public int generation() { return generation; }
    public void nextGeneration() { generation++; finished = false; gateRejections = 0; }
    public int rating() { return rating; }
    public void rating(int r) { rating = r; }

    /**
     * Turns this session into a renovation of a captured build: the model is the build as it stands (protected cells
     * locked), and ops see the world around it (water, natural ground) through the canvas.
     */
    public void adopt(StructureCapture.Result r, int originalScore) {
        this.renovation = r;
        this.originalScore = originalScore;
        this.model = r.model();
        model.setMaxBlocks((int) Math.min(Integer.MAX_VALUE, Math.max(settings.maxBlocks(), model.size() + 2_000_000L)));
        this.canvas = new Canvas(model, materials);
        canvas.existing(r.existing());
        batches.clear();
        lastReport = null;
    }

    private io.github.shengboj0324.architect.core.StructureAnalyzer.Report lastInspection;
    public io.github.shengboj0324.architect.core.StructureAnalyzer.Report lastInspection() { return lastInspection; }
    public void lastInspection(io.github.shengboj0324.architect.core.StructureAnalyzer.Report r) { lastInspection = r; }

    public StructureCapture.Result renovation() { return renovation; }
    public boolean renovating() { return renovation != null; }
    public Existing existing() { return renovation == null ? null : renovation.existing(); }
    /** Quality score of the build before the renovation (-1 for new designs). */
    public int originalScore() { return originalScore; }

    public SitePlan site() { return site; }
    public void site(SitePlan s) { site = s; }

    /** Terrain support for the quality critic (natural site mode only), else null. */
    public Ground ground() { return renovation != null ? renovation.existing() : site == null ? null : site.ground(); }

    public long totalInputLast() { return totalInputLast; }
    public void totalInputLast(long t) { totalInputLast = t; }

    public void addUsage(long in, long out, long cacheRead) {
        inputTokens += in;
        outputTokens += out;
        cacheReadTokens += cacheRead;
    }

    private long recordedTokens;
    private volatile String stopReason; // set when Claude stops the design because it cannot be done here

    /** Claude stopped the design (stop_design); the reason is told to the player. */
    public String stopReason() { return stopReason; }
    public void stop(String reason) { stopReason = reason == null || reason.isBlank() ? "the design was stopped" : reason; }

    /** Tokens used since the last call (for per-player budgets). */
    public long takeNewTokens() {
        long total = inputTokens + outputTokens;
        long n = total - recordedTokens;
        recordedTokens = total;
        return n;
    }

    public String usageLine() {
        return "tokens in " + inputTokens + " (cache read " + cacheReadTokens + "), out " + outputTokens;
    }
}
