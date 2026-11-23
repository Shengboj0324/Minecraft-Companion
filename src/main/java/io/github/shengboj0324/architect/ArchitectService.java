package io.github.shengboj0324.architect;

import io.github.shengboj0324.architect.ai.AgentListener;
import io.github.shengboj0324.architect.ai.ArchitectAgent;
import io.github.shengboj0324.architect.ai.ArchitectTools;
import io.github.shengboj0324.architect.ai.ConversationAgent;
import io.github.shengboj0324.architect.ai.LandmarkLibrary;
import io.github.shengboj0324.architect.ai.DesignSession;
import io.github.shengboj0324.architect.ai.ExemplarStore;
import io.github.shengboj0324.architect.ai.PromptLibrary;
import io.github.shengboj0324.architect.core.BlockState;
import io.github.shengboj0324.architect.core.Canvas;
import io.github.shengboj0324.architect.core.Existing;
import io.github.shengboj0324.architect.core.IsoRenderer;
import io.github.shengboj0324.architect.core.Renovation;
import io.github.shengboj0324.architect.core.RequestIntent;
import io.github.shengboj0324.architect.core.StructureAnalyzer;
import io.github.shengboj0324.architect.core.Connectivity;
import io.github.shengboj0324.architect.core.QualityAnalyzer;
import io.github.shengboj0324.architect.core.StyleLibrary;
import io.github.shengboj0324.architect.core.Templates;
import io.github.shengboj0324.architect.core.VoxelModel;
import io.github.shengboj0324.architect.npc.ArchitectNpc;
import io.github.shengboj0324.architect.world.BuildJob;
import io.github.shengboj0324.architect.world.PaperChunkGate;
import io.github.shengboj0324.architect.world.Placement;
import io.github.shengboj0324.architect.world.Protection;
import io.github.shengboj0324.architect.world.StructureCapture;
import io.github.shengboj0324.architect.world.SiteGrader;
import io.github.shengboj0324.architect.world.SitePlan;
import io.github.shengboj0324.architect.world.SiteSurvey;
import io.github.shengboj0324.architect.world.TerrainMap;
import io.github.shengboj0324.architect.world.TerrainScanner;
import io.github.shengboj0324.architect.world.UndoJournal;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.logging.Level;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Coordinates everything a player can ask for: Claude design sessions (on a worker thread), placing finished
 * designs (one job at a time, a slice per tick), the builder NPC, undo history, ratings and exemplars.
 */
public final class ArchitectService {

    /** Per-player memory: the last design, where it stands, and how to take it back. */
    static final class PlayerState {
        DesignSession session;
        Placement placement;
        VoxelModel placedSnapshot;
        final Deque<UndoJournal> undo = new ArrayDeque<>();
        long lastRequestMillis;
        boolean awaitingConfirm;
        SiteSurvey.Site site; // where the next build goes (set by chat requests); null = in front of the player when it is placed
        int[] pos1, pos2;     // renovation selection corners
        String selWorld;
        boolean capturing;    // a build is being read for renovation
        Runnable pendingRenovation; // a previewed renovation waiting for /architect confirm
        List<io.papermc.paper.math.Position> preview;
        org.bukkit.World previewWorld;
    }

    private record Active(BuildJob job, UUID owner, BossBar bar, Runnable onDone) {}

    private final ArchitectPlugin plugin;
    private final PluginSettings cfg;
    private final StyleLibrary styles;
    private final PromptLibrary prompts;
    private final ExemplarStore exemplars;
    private final ArchitectAgent agent;
    private final ArchitectTools offlineTools = new ArchitectTools();
    private final ExecutorService workers;
    private final ArchitectNpc npc;
    private final Map<UUID, PlayerState> players = new HashMap<>();
    private final Deque<Active> queue = new ArrayDeque<>();
    private final List<UndoJournal> restoring = new ArrayList<>();
    private final Set<DesignSession> designing = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Function<BlockState, BlockData> toData = BuildJob.converter(Bukkit::createBlockData);
    private final ConversationAgent convo;
    private final ExecutorService chatWorkers;
    private final Map<UUID, Long> talking = new java.util.concurrent.ConcurrentHashMap<>();
    private BukkitTask ticker;
    private final Protection protection;
    private final Usage usage;
    private final QualityAnalyzer critic = new QualityAnalyzer();
    private final Map<UndoJournal, Path> journalFiles = new java.util.concurrent.ConcurrentHashMap<>();

    public ArchitectService(ArchitectPlugin plugin, PluginSettings cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.styles = StyleLibrary.bundled();
        Path data = plugin.getDataFolder().toPath();
        this.prompts = new PromptLibrary(styles, data.resolve("prompts"));
        this.exemplars = new ExemplarStore(data.resolve("exemplars"));
        this.agent = cfg.hasApiKey() ? new ArchitectAgent(cfg.agent(), prompts, plugin.getLogger()) : null;
        AtomicInteger n = new AtomicInteger();
        this.workers = Executors.newFixedThreadPool(Math.max(1, cfg.maxConcurrentDesigns()), r -> {
            Thread t = new Thread(r, "Architect-Designer-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.npc = new ArchitectNpc(cfg.npcName(), cfg.npcSkin());
        PluginSettings.Chat chat = cfg.chat();
        this.convo = cfg.hasApiKey() && chat.enabled()
                ? new ConversationAgent(new ConversationAgent.Settings(cfg.agent().apiKey(), cfg.agent().baseUrl(), chat.model(), chat.effort(), chat.maxTokens(), chat.maxHistory()), plugin.getLogger())
                : null;
        this.protection = new Protection(plugin.getLogger(), cfg.renovate().protectionMode(), cfg.renovate().coreProtect());
        this.usage = new Usage(data.resolve("usage.properties"), plugin.getLogger());
        if (cfg.texturePack() != null && !cfg.texturePack().isBlank()) {
            Path pack = Path.of(cfg.texturePack());
            if (!pack.isAbsolute()) pack = data.resolve(cfg.texturePack());
            try { plugin.getLogger().info("Renders use real block colours: read " + IsoRenderer.loadTextures(pack) + " textures from " + pack.getFileName()); }
            catch (java.io.IOException | RuntimeException e) { plugin.getLogger().warning("Could not read render.texture-pack " + pack + ": " + e); }
        }
        this.chatWorkers = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "Architect-Chat-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    public void start() { ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L); }

    public void stop() {
        if (ticker != null) ticker.cancel();
        for (DesignSession s : designing) s.status(DesignSession.Status.CANCELLED);
        workers.shutdownNow();
        chatWorkers.shutdownNow();
        for (Active a : queue) finishJob(a, false);
        queue.clear();
        npc.despawn();
    }

    public PluginSettings settings() { return cfg; }
    public StyleLibrary styles() { return styles; }
    public boolean aiEnabled() { return agent != null; }

    private PlayerState state(Player p) { return players.computeIfAbsent(p.getUniqueId(), k -> new PlayerState()); }

    // ------------------------------------------------------------------------------------------------ requests

    /** /architect build <request>: Claude designs it (or a template is chosen when no API key is configured). */
    public void design(Player p, String request) { design(p, request, null, false); }

    /** Starts a design. {@code site} fixes where it will stand (null: in front of the player when it is placed). Returns a status line. */
    public String design(Player p, String request, SiteSurvey.Site site, boolean fromChat) {
        PlayerState st = state(p);
        // "fix this base", "now do the bottom half": that is a renovation of an existing build, which a new design cannot see
        if (cfg.renovate().enabled() && p.hasPermission("architect.renovate") && RequestIntent.aboutExistingBuild(request) && structureTarget(p) != null) {
            tell(p, "That sounds like work on the build you're looking at, so I'll renovate it.");
            return renovate(p, request, true, false);
        }
        if (!checkCooldown(p, st)) return "On cooldown; ask again in a few seconds.";
        if (isBusy(p, st)) return "Busy: a design or build for this player is already in progress.";
        if (agent == null) {
            String t = guessTemplate(request);
            tell(p, "No Claude API key is configured, so I'll raise my '" + t + "' design instead. Ask an admin to set anthropic.api-key for custom builds.");
            template(p, t, null, 1, 3, site);
            return "No API key: built the '" + t + "' template instead.";
        }
        if (designing.size() >= cfg.maxConcurrentDesigns()) {
            tell(p, "I'm already drafting " + designing.size() + " designs. Try again shortly.");
            return "The studio is full (" + designing.size() + " designs in progress); try again shortly.";
        }
        String quota = p.hasPermission("architect.unlimited") ? null : usage.check(p.getUniqueId(), cfg.quotas());
        if (quota != null) { tell(p, quota); return quota; }
        usage.started(p.getUniqueId());
        DesignSession s = new DesignSession(p.getName(), styles, cfg.agent(), guessStyle(request));
        attachValidator(s);
        st.session = s;
        st.placement = null;
        st.placedSnapshot = null;
        st.lastRequestMillis = System.currentTimeMillis();
        st.site = site;
        // survey the real land now and fix the site, so Claude designs against the terrain the build will stand on
        SiteSurvey.Site where = site != null ? site : new SiteSurvey.Site(p.getLocation(), SiteSurvey.facing(p), false);
        PluginSettings.Terrain tc = cfg.terrain();
        s.site(SitePlan.survey(where.point(), where.look(), where.centred(), cfg.distanceFromPlayer(), tc.scanRadius(), tc.defaultNatural(), tc.gradeMargin(), tc.maxGrade()));
        String siteInfo = SiteSurvey.describe(p, s.site());
        if (site != null && site.centred()) siteInfo += "\nThe player chose the spot: the build will be centred on the block they pointed at (ground y=" + site.point().getBlockY() + ").";
        String ex = exemplars.select(request, cfg.exemplarsPerPrompt(), 9000);
        if (cfg.npcEnabled()) npc.ensureNear(p);
        if (!fromChat) tell(p, "Let me sketch that out...");
        runAgent(p, s, request, siteInfo, ex);
        return "Design started. It takes one to several minutes depending on size; progress shows in the action bar and the build starts automatically.";
    }

    /** /architect expand <request>: the next generation of the player's last build, placed on top of it. Returns a status line. */
    public String expand(Player p, String request) {
        PlayerState st = state(p);
        // a renovated build is expanded by renovating it again: always from the world as it is now, never a stale model
        if (st.session != null && st.session.renovating()) return renovate(p, request, true, false);
        if (!checkCooldown(p, st)) return "On cooldown; ask again in a few seconds.";
        if (isBusy(p, st)) return "Busy: a design or build for this player is already in progress.";
        if (st.session == null || st.placement == null) { tell(p, "Build something first, then I can expand it."); return "There is no finished build of this player's to expand."; }
        if (agent == null) {
            if ("village".equals(st.session.name().toLowerCase(Locale.ROOT).replace(' ', '_')) || st.session.name().toLowerCase(Locale.ROOT).contains("village")) {
                growVillage(p, st);
                return "The village grew by one generation.";
            }
            tell(p, "Expanding a build needs Claude (set anthropic.api-key). Offline, only the village template can grow.");
            return "Expanding needs an API key.";
        }
        st.session.nextGeneration();
        st.lastRequestMillis = System.currentTimeMillis();
        tell(p, "Generation " + st.session.generation() + ": planning the expansion...");
        runAgent(p, st.session, request, null, null);
        return "Expansion (generation " + st.session.generation() + ") is being designed; it will be built onto the existing build.";
    }

    private void runAgent(Player p, DesignSession s, String request, String site, String ex) {
        UUID id = p.getUniqueId();
        designing.add(s);
        workers.submit(() -> {
            try {
                agent.run(s, request, site, ex, new AgentListener() {
                    @Override public void say(String text) {
                        if (cfg.narrate()) sync(() -> { Player pl = Bukkit.getPlayer(id); if (pl != null) narrate(pl, text); });
                    }
                    @Override public void tool(String name, String note) {
                        String label = switch (name) {
                            case "set_plan" -> "planning";
                            case "build" -> "drafting" + (note == null ? "" : ": " + note);
                            case "render_views" -> "reviewing the drawings";
                            case "use_template" -> "laying out a base";
                            case "finish" -> "final review";
                            default -> name.replace('_', ' ');
                        };
                        sync(() -> { Player pl = Bukkit.getPlayer(id); if (pl != null) pl.sendActionBar(Component.text("Architect: " + label, NamedTextColor.GOLD)); });
                    }
                    @Override public void ready(DesignSession done) {
                        designing.remove(done);
                        if (convo != null) convo.note(id, "Your design '" + done.name() + "' passed review" + (done.lastReport() == null ? "" : " (quality " + done.lastReport().score() + "/100)") + " and construction began. Summary: " + done.summary());
                        sync(() -> onReady(id, done));
                    }
                    @Override public void failed(DesignSession done, String reason) {
                        designing.remove(done);
                        if (convo != null) convo.note(id, "The design failed: " + reason);
                        sync(() -> { Player pl = Bukkit.getPlayer(id); if (pl != null) tell(pl, "I couldn't finish that design: " + reason); });
                    }
                });
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Design session crashed", t);
                designing.remove(s);
            } finally {
                plugin.getLogger().info("Design '" + s.name() + "' for " + s.owner() + ": " + s.usageLine());
                usage.tokens(id, s.takeNewTokens());
            }
        });
    }

    private void onReady(UUID id, DesignSession s) {
        Player p = Bukkit.getPlayer(id);
        if (p == null) return;
        PlayerState st = state(p);
        if (s.renovating()) { placeRenovation(p, st, s); return; }
        QualityAnalyzer.Report r = s.lastReport();
        String score = r == null ? "" : " (quality " + r.score() + "/100)";
        if (cfg.requireConfirm() && st.placement == null) {
            st.awaitingConfirm = true;
            tell(p, "'" + s.name() + "' is ready" + score + ": " + s.summary() + " " + s.model().nonAirCount() + " blocks. Face where you want it and type /architect confirm, or /architect cancel.");
            return;
        }
        place(p, st, s);
    }

    /** /architect confirm */
    public void confirm(Player p) {
        PlayerState st = state(p);
        if (st.pendingRenovation != null) {
            Runnable go = st.pendingRenovation;
            st.pendingRenovation = null;
            go.run();
            return;
        }
        if (!st.awaitingConfirm || st.session == null) { tell(p, "Nothing is waiting for confirmation."); return; }
        st.awaitingConfirm = false;
        place(p, st, st.session);
    }

    /** /architect template <name> [style] [scale] [generations] */
    public void template(Player p, String name, String style, int scale, int generations) { template(p, name, style, scale, generations, null); }

    public String template(Player p, String name, String style, int scale, int generations, SiteSurvey.Site site) {
        PlayerState st = state(p);
        if (isBusy(p, st)) return "Busy: a design or build for this player is already in progress.";
        if (!Templates.ALL.containsKey(name)) { tell(p, "Unknown template. Try: " + String.join(", ", Templates.names())); return "Unknown template; options: " + String.join(", ", Templates.names()); }
        if (style != null && styles.get(style) == null) style = null;
        String styleName = style != null ? style : Templates.ALL.get(name).defaultStyle();
        DesignSession s = new DesignSession(p.getName(), styles, cfg.agent(), styleName);
        attachValidator(s);
        s.resetModel(0, 0, 0); // unbounded: templates may extend past the AI envelope defaults
        s.requests().add("template " + name);
        Templates.build(name, s.canvas(), scale, generations);
        s.name(pretty(name));
        s.summary(Templates.ALL.get(name).description());
        s.lastReport(offlineTools.analyze(s.model()));
        s.finished(true);
        s.status(DesignSession.Status.READY);
        st.session = s;
        st.placement = null;
        st.placedSnapshot = null;
        st.site = site;
        if (cfg.npcEnabled()) npc.ensureNear(p);
        place(p, st, s);
        return "Building the " + pretty(name) + " template now (" + s.model().nonAirCount() + " blocks).";
    }

    private void growVillage(Player p, PlayerState st) {
        DesignSession s = st.session;
        int gen = Math.min(5, s.generation() + 1);
        s.nextGeneration();
        VoxelModel fresh = new VoxelModel(s.model().width(), s.model().height(), s.model().depth());
        Templates.build("village", new io.github.shengboj0324.architect.core.Canvas(fresh, s.materials()), 1, gen);
        fresh.forEach((x, y, z, b) -> s.model().set(x, y, z, b));
        Connectivity.apply(s.model());
        s.lastReport(offlineTools.analyze(s.model()));
        tell(p, "The village grows into generation " + gen + ".");
        place(p, st, s);
    }

    private void place(Player p, PlayerState st, DesignSession s) {
        Connectivity.apply(s.model());
        boolean expansion = st.placement != null && st.placedSnapshot != null;
        SitePlan plan = s.site();
        boolean framed = plan != null && plan.frame() != null;
        PluginSettings.Terrain tc = cfg.terrain();
        Placement at;
        TerrainMap.View land = null;
        if (expansion) at = st.placement;
        else if (framed) {
            at = plan.frame(); // exactly where Claude designed it against the terrain
            land = plan.view();
        } else {
            Placement guess = st.site != null ? SiteSurvey.place(st.site, s.model(), cfg.distanceFromPlayer()) : SiteSurvey.place(p, s.model(), cfg.distanceFromPlayer());
            at = guess;
            int[] b = s.model().bounds();
            if (b != null) {
                TerrainMap t = scanAround(guess, b, tc.gradeMargin() + 2);
                int lift = Math.max(0, b[1]);
                int oy = t.reference(guess, b[0], b[2], b[3], b[5], guess.oy() + lift) - lift;
                oy = Math.max(guess.world().getMinHeight() + 1, Math.min(oy, guess.world().getMaxHeight() - (b[4] - b[1]) - 2));
                at = new Placement(guess.world(), guess.ox(), oy, guess.oz(), guess.quarter());
                land = t.view(at);
            }
        }
        st.site = null;
        boolean natural = framed && plan.natural();
        VoxelModel build = s.model();
        int[] mb = build.bounds();
        BuildJob.Prep prep;
        if (natural) {
            // the land stays: clear only trees and plants in the footprint, never foundations
            // (from well above the build, so a tall tree is not left with floating leaves)
            prep = new BuildJob.Prep(cfg.clearSite(), ArchitectService::clearableInNature, null, mb == null ? 1 : mb[1], 32, false, 0, 0, 0);
        } else {
            prep = BuildJob.Prep.level(build, cfg.clearSite() && !expansion, cfg.fillFoundation(), cfg.maxFoundationDepth());
            if (!expansion && land != null && tc.gradeMargin() > 0) {
                LongOpenHashSet foot = SiteGrader.footprint(build);
                VoxelModel graded = build.copy();
                graded.setEnvelope(0, 0, 0);
                if (!SiteGrader.grade(graded, land, foot, tc.gradeMargin(), tc.maxGrade()).isEmpty()) {
                    // level only the footprint; the graded ring is shaped cell by cell
                    prep = new BuildJob.Prep(prep.clear(), prep.clearable(), foot::contains, prep.clearFloor(), prep.headroom(), prep.fillFoundation(), prep.baseY(), prep.fillFrom(), prep.maxFoundationDepth());
                    build = graded;
                }
            }
        }
        UndoJournal journal = new UndoJournal(at.world(), s.name() + (expansion ? " (generation " + s.generation() + ")" : ""))
                .gate(new PaperChunkGate(plugin));
        BuildJob job = new BuildJob(s.name(), build, expansion ? st.placedSnapshot : null, at, journal, toData, prep, new PaperChunkGate(plugin));
        st.placement = at;
        s.status(DesignSession.Status.BUILDING);
        BossBar bar = BossBar.bossBar(Component.text("Building " + s.name(), NamedTextColor.GOLD), 0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        p.showBossBar(bar);
        UUID id = p.getUniqueId();
        long started = System.currentTimeMillis();
        plugin.getLogger().info("Placing '" + s.name() + "' for " + p.getName() + ": " + s.model().nonAirCount() + " blocks at "
                + at.world().getName() + " " + at.ox() + "," + at.oy() + "," + at.oz() + " (" + (natural ? "natural site" : build != s.model() ? "levelled and graded site" : "levelled site") + ")");
        // the job loads and holds chunks tile by tile through its ChunkGate, so nothing is loaded synchronously
        Active a = new Active(job, id, bar, () -> {
            st.placedSnapshot = s.model().copy();
            st.undo.push(journal);
            while (st.undo.size() > cfg.maxUndo()) st.undo.removeLast();
            saveJournal(id, journal);
            s.status(DesignSession.Status.BUILT);
            plugin.getLogger().info("Built '" + s.name() + "': " + journal.size() + " blocks changed in " + (System.currentTimeMillis() - started) / 1000 + "s");
            if (convo != null) convo.note(id, "Construction of '" + s.name() + "' finished (" + journal.size() + " blocks changed). The player can rate it, expand it, or undo it.");
            Player pl = Bukkit.getPlayer(id);
            if (pl != null) {
                QualityAnalyzer.Report r = s.lastReport();
                tell(pl, "'" + s.name() + "' is complete" + (r == null ? "" : ", quality " + r.score() + "/100") + ". "
                        + "Rate it with /architect rate <1-5>" + (aiEnabled() ? ", grow it with /architect expand <what next>" : "") + ", or /architect undo.");
            }
        });
        queue.addLast(a);
        if (queue.size() > 1) tell(p, "Your build is queued behind " + (queue.size() - 1) + " other build(s).");
    }

    /** Surveys the land under a placement's footprint plus a margin (for builds placed without a design-time survey). */
    private static TerrainMap scanAround(Placement at, int[] b, int margin) {
        int ax = at.wx(b[0] - margin, b[2] - margin), az = at.wz(b[0] - margin, b[2] - margin);
        int bx = at.wx(b[3] + margin, b[5] + margin), bz = at.wz(b[3] + margin, b[5] + margin);
        return TerrainScanner.scan(at.world(), ax, az, bx, bz);
    }

    /** On a natural site only vegetation in the footprint is cleared: trees, plants, snow layers and the like. */
    private static boolean clearableInNature(Block b) {
        return TerrainScanner.isVegetation(b.getType()) || (!b.isSolid() && !b.isLiquid());
    }

    // ------------------------------------------------------------------------------------------------ renovation

    private StructureCapture.Limits captureLimits() {
        PluginSettings.Renovate r = cfg.renovate();
        return new StructureCapture.Limits(r.maxRadius(), r.maxHeight(), r.maxBlocks(), r.chunksPerTick(), 2);
    }

    /** /architect pos1 | pos2: a selection corner at the block the player looks at (or stands on). */
    public void selectCorner(Player p, int which) {
        PlayerState st = state(p);
        org.bukkit.block.Block b = p.getTargetBlockExact(160, org.bukkit.FluidCollisionMode.NEVER);
        if (b == null) b = p.getLocation().getBlock().getRelative(org.bukkit.block.BlockFace.DOWN);
        int[] at = {b.getX(), b.getY(), b.getZ()};
        if (!p.getWorld().getName().equals(st.selWorld)) { st.pos1 = null; st.pos2 = null; }
        st.selWorld = p.getWorld().getName();
        if (which == 1) st.pos1 = at; else st.pos2 = at;
        String size = st.pos1 != null && st.pos2 != null ? " Selection: " + (Math.abs(st.pos1[0] - st.pos2[0]) + 1) + " x " + (Math.abs(st.pos1[1] - st.pos2[1]) + 1)
                + " x " + (Math.abs(st.pos1[2] - st.pos2[2]) + 1) + ". /architect renovate <what to do> works on it." : "";
        tell(p, "Corner " + which + " set at " + at[0] + " " + at[1] + " " + at[2] + "." + size);
    }

    public void clearSelection(Player p) { PlayerState st = state(p); st.pos1 = null; st.pos2 = null; tell(p, "Selection cleared."); }

    /**
     * Renovates an existing build: the one the player is looking at, or their pos1/pos2 selection when
     * {@code useSelection}. {@code quick} runs the free deterministic repair (patch holes, drain, seal, light) without
     * Claude. Returns a status line.
     */
    public String renovate(Player p, String request, boolean useSelection, boolean quick) {
        PlayerState st = state(p);
        if (!cfg.renovate().enabled()) { tell(p, "Renovating builds is turned off on this server."); return "Renovation is disabled on this server."; }
        if (!p.hasPermission("architect.renovate")) { tell(p, "You don't have permission to have builds renovated."); return "The player lacks architect.renovate."; }
        if (!checkCooldown(p, st)) return "On cooldown; ask again in a few seconds.";
        if (isBusy(p, st)) return "Busy: a design or build for this player is already in progress.";
        boolean offline = quick || agent == null;
        if (!offline) {
            String q = p.hasPermission("architect.unlimited") ? null : usage.check(p.getUniqueId(), cfg.quotas());
            if (q != null) { tell(p, q); return q; }
            if (designing.size() >= cfg.maxConcurrentDesigns()) { tell(p, "I'm already drafting " + designing.size() + " designs. Try again shortly."); return "The studio is full; try again shortly."; }
        }
        st.lastRequestMillis = System.currentTimeMillis();
        UUID id = p.getUniqueId();
        java.util.function.Consumer<StructureCapture.Result> ok = r -> startRenovation(id, r, request, offline);
        java.util.function.Consumer<String> fail = msg -> { Player pl = Bukkit.getPlayer(id); if (pl != null) tell(pl, msg); st.capturing = false; };
        if (useSelection && st.pos1 != null && st.pos2 != null && p.getWorld().getName().equals(st.selWorld)) {
            st.capturing = true;
            StructureCapture.select(plugin, p.getWorld(), new int[]{st.pos1[0], st.pos1[1], st.pos1[2], st.pos2[0], st.pos2[1], st.pos2[2]}, captureLimits(), ok, fail);
        } else {
            org.bukkit.block.Block target = structureTarget(p);
            if (target == null) {
                tell(p, "Look at the build you want me to work on (within 160 blocks), or select it with /architect pos1 and pos2.");
                return "The player is not looking at a build; ask them to look at it or select it.";
            }
            st.capturing = true;
            StructureCapture.detect(plugin, target, captureLimits(), ok, fail);
        }
        if (cfg.npcEnabled()) npc.ensureNear(p);
        tell(p, offline ? "Let me look it over and patch it up..." : "Let me take a good look at it...");
        return offline ? "Surveying the build for a quick repair (holes, water, lighting); it is placed automatically."
                : "Surveying the build; then the design studio plans the renovation (a few minutes) and the changes are built in place.";
    }

    /** Main thread: a build was captured. Checks protection, then analyses it and starts the design (off the main thread). */
    private void startRenovation(UUID id, StructureCapture.Result r, String request, boolean offline) {
        Player p = Bukkit.getPlayer(id);
        if (p == null) return;
        PlayerState st = state(p);
        st.capturing = false;
        if (r.structureBlocks() == 0) { tell(p, "I couldn't find any building blocks there."); return; }
        String denied = protection.check(p, r.world(), structureSpots(r, 96));
        if (denied != null) { tell(p, "I can't work on that: " + denied + " is protected and you can't build there."); return; }
        int[] b = r.worldBox();
        tell(p, "I see it: " + r.structureBlocks() + " blocks, " + (b[3] - b[0] + 1) + " x " + (b[4] - b[1] + 1) + " x " + (b[5] - b[2] + 1)
                + (r.truncated() ? " (it is larger than this server's limit, so I took the part around where you looked)" : "")
                + (r.foreignBlocks() > 0 ? "; " + r.foreignBlocks() + " blocks of a neighbouring build stay untouched" : "") + ". Studying it...");
        workers.submit(() -> {
            try {
                StructureAnalyzer.Report report = new StructureAnalyzer().analyze(r.model(), r.existing(), null);
                int before = critic.analyze(r.model(), r.existing()).score();
                DesignSession s = new DesignSession(p.getName(), styles, cfg.agent(), guessStyle(request + " " + report.text()));
                s.adopt(r, before);
                s.requests().add(request);
                s.lastInspection(report);
                if (offline) quickRepair(s, report);
                sync(() -> {
                    Player pl = Bukkit.getPlayer(id);
                    if (pl == null) return;
                    attachValidator(s);
                    PlayerState ps = state(pl);
                    ps.session = s;
                    ps.placement = null;
                    ps.placedSnapshot = null;
                    if (offline) { onReady(id, s); return; }
                    usage.started(id);
                    runAgent(pl, s, request, report.text() + "\nQuality score before the renovation: " + before + "/100.", null);
                });
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Renovation analysis failed", e);
                sync(() -> { Player pl = Bukkit.getPlayer(id); if (pl != null) tell(pl, "I couldn't study that build: " + e.getMessage()); });
            }
        });
    }

    /** /architect inspect x y z (console or admin): reads the build at a block and reports what the Architect sees, with the full analysis in the log. */
    public void inspectAt(org.bukkit.command.CommandSender sender, org.bukkit.block.Block target) {
        long t0 = System.currentTimeMillis();
        StructureCapture.detect(plugin, target, captureLimits(), 32, r -> workers.submit(() -> {
            long t1 = System.currentTimeMillis();
            StructureAnalyzer.Report rep = new StructureAnalyzer().analyze(r.model(), r.existing(), null);
            int score = critic.analyze(r.model(), r.existing()).score();
            long t2 = System.currentTimeMillis();
            int[] b = r.worldBox();
            String msg = r.structureBlocks() + " blocks in " + (b[3] - b[0] + 1) + " x " + (b[4] - b[1] + 1) + " x " + (b[5] - b[2] + 1) + " at " + b[0] + "," + b[1] + "," + b[2]
                    + ", quality " + score + "; " + rep.floors().length + " floors, " + rep.rooms().size() + " rooms, " + rep.holeList().size() + " holes, "
                    + rep.floodedCells() + " flooded cells, " + rep.fragmentCells() + " loose blocks, " + rep.spawnable() + " dark spots, "
                    + r.existing().protectedCells().size() + " protected; capture " + (t1 - t0) + " ms, analysis " + (t2 - t1) + " ms.";
            plugin.getLogger().info("Inspection at " + target.getX() + "," + target.getY() + "," + target.getZ() + ": " + msg + "\n" + rep.text());
            sync(() -> sender.sendMessage(Component.text("[Architect] " + msg, NamedTextColor.WHITE)));
        }), msg -> sender.sendMessage(Component.text("[Architect] " + msg, NamedTextColor.RED)));
    }

    /**
     * /architect render x y z [view] (admin): reads the build at a block and saves the rendered views Claude would see
     * (corners, top, all, or "damage" for the highlighted analysis view) as a PNG in plugins/ClaudeArchitect/renders/.
     */
    public void renderAt(org.bukkit.command.CommandSender sender, org.bukkit.block.Block target, String view) {
        StructureCapture.detect(plugin, target, captureLimits(), 32, r -> workers.submit(() -> {
            try {
                boolean damage = "damage".equalsIgnoreCase(view);
                StructureAnalyzer.Report rep = damage ? new StructureAnalyzer().analyze(r.model(), r.existing(), null) : null;
                io.github.shengboj0324.architect.core.VoxelModel v = io.github.shengboj0324.architect.core.BuildViews.compose(r.model(), r.existing(), rep,
                        damage ? io.github.shengboj0324.architect.core.BuildViews.Highlight.ALL : io.github.shengboj0324.architect.core.BuildViews.Highlight.NONE);
                byte[] png = new io.github.shengboj0324.architect.core.BuildViews().sheet(v, damage ? "corners" : view, null, 1600, 1200);
                Path out = plugin.getDataFolder().toPath().resolve("renders").resolve("render-" + target.getX() + "_" + target.getY() + "_" + target.getZ() + "-" + view + ".png");
                java.nio.file.Files.createDirectories(out.getParent());
                java.nio.file.Files.write(out, png);
                sync(() -> sender.sendMessage(Component.text("[Architect] Saved " + r.structureBlocks() + "-block render to " + out, NamedTextColor.WHITE)));
            } catch (java.io.IOException | RuntimeException e) {
                sync(() -> sender.sendMessage(Component.text("[Architect] Render failed: " + e.getMessage(), NamedTextColor.RED)));
            }
        }), msg -> sender.sendMessage(Component.text("[Architect] " + msg, NamedTextColor.RED)));
    }

    /** /architect inspect: reads the build the player looks at (or the selection) and reports what the Architect sees. */
    public void inspect(Player p) {
        PlayerState st = state(p);
        UUID id = p.getUniqueId();
        java.util.function.Consumer<StructureCapture.Result> ok = r -> workers.submit(() -> {
            StructureAnalyzer.Report rep = new StructureAnalyzer().analyze(r.model(), r.existing(), null);
            int score = critic.analyze(r.model(), r.existing()).score();
            long empty = rep.rooms().stream().filter(x -> x.furniture() == 0 && x.area() >= 9).count();
            int[] b = r.worldBox();
            String msg = r.structureBlocks() + " blocks, " + (b[3] - b[0] + 1) + " x " + (b[4] - b[1] + 1) + " x " + (b[5] - b[2] + 1) + ", quality " + score + "/100. "
                    + rep.floors().length + " floor(s), " + rep.rooms().size() + " room(s)" + (empty > 0 ? " (" + empty + " empty)" : "") + ", "
                    + rep.holeList().size() + " hole(s) in walls/floors/roofs, " + rep.floodedCells() + " flooded cells, " + rep.fragmentCells() + " loose blocks, "
                    + rep.spawnable() + " dark spots indoors where mobs can spawn, " + r.existing().protectedCells().size() + " protected blocks.";
            sync(() -> { Player pl = Bukkit.getPlayer(id); if (pl != null) tell(pl, msg); });
            plugin.getLogger().info("Inspection for " + p.getName() + ":\n" + rep.text());
        });
        java.util.function.Consumer<String> fail = msg -> { Player pl = Bukkit.getPlayer(id); if (pl != null) tell(pl, msg); };
        if (st.pos1 != null && st.pos2 != null && p.getWorld().getName().equals(st.selWorld))
            StructureCapture.select(plugin, p.getWorld(), new int[]{st.pos1[0], st.pos1[1], st.pos1[2], st.pos2[0], st.pos2[1], st.pos2[2]}, captureLimits(), ok, fail);
        else {
            org.bukkit.block.Block target = structureTarget(p);
            if (target == null) { tell(p, "Look at a build (within 160 blocks) or select one with /architect pos1 and pos2."); return; }
            StructureCapture.detect(plugin, target, captureLimits(), ok, fail);
        }
        tell(p, "Looking it over...");
    }

    /**
     * The first building block along the player's line of sight (within 160 blocks), looking through water, air,
     * terrain and vegetation, so aiming at the underwater part of a base through the sea works. Null when there is none.
     */
    org.bukkit.block.Block structureTarget(Player p) {
        org.bukkit.util.BlockIterator it = new org.bukkit.util.BlockIterator(p, 160);
        int natural = 0;
        while (it.hasNext()) {
            org.bukkit.block.Block b = it.next();
            if (StructureCapture.isStructure(b.getType())) return b;
            if (!b.getType().isAir() && !b.isLiquid() && ++natural > 24) break; // deep into solid ground: nothing behind it counts
        }
        return null;
    }

    /** World positions spread over a capture's structure blocks (for protection checks). */
    private static List<int[]> structureSpots(StructureCapture.Result r, int n) {
        long[] keys = r.snapshot().keys();
        List<int[]> all = new ArrayList<>();
        int step = Math.max(1, keys.length / (n * 4));
        for (int i = 0; i < keys.length; i += step) {
            long k = keys[i];
            if (r.existing().isProtectedKey(k)) continue;
            all.add(new int[]{VoxelModel.kx(k) + r.ox(), VoxelModel.ky(k) + r.oy(), VoxelModel.kz(k) + r.oz()});
        }
        return Protection.sample(all, n);
    }

    /** The free repair: patch every hole, drain water standing inside, seal the shell, light every dark spot. */
    private void quickRepair(DesignSession s, StructureAnalyzer.Report report) {
        Canvas c = s.canvas();
        VoxelModel m = s.model();
        m.beginBatch();
        int patched = Renovation.patch(c, s.existing(), null, null);
        int drained = 0;
        for (long k : report.flooded()) if (m.set(VoxelModel.kx(k), VoxelModel.ky(k), VoxelModel.kz(k), BlockState.AIR)) drained++;
        int sealed = Renovation.sealLeaks(c, s.existing(), "glass");
        int lit = Renovation.lightUp(c, s.existing(), null, 8, null, null);
        m.endBatch();
        Connectivity.apply(m);
        s.name("Repairs");
        s.summary("patched " + patched + " blocks of holes, drained " + drained + " flooded cells, sealed " + sealed + " leaks and added " + lit + " lights");
        s.lastReport(critic.analyze(m, s.ground()));
        s.finished(true);
        s.status(DesignSession.Status.READY);
    }

    /** Places a finished renovation: computes the changes off the main thread, checks protection, previews, builds. */
    private void placeRenovation(Player p, PlayerState st, DesignSession s) {
        UUID id = p.getUniqueId();
        workers.submit(() -> {
            VoxelModel changes = Renovation.diff(s.model(), s.renovation().snapshot(), s.existing());
            sync(() -> {
                Player pl = Bukkit.getPlayer(id);
                if (pl == null) return;
                if (changes.size() == 0) { tell(pl, "Everything was already in order; nothing needed changing."); s.status(DesignSession.Status.BUILT); return; }
                StructureCapture.Result r = s.renovation();
                List<int[]> spots = new ArrayList<>();
                long[] keys = changes.keys();
                int step = Math.max(1, keys.length / 400);
                for (int i = 0; i < keys.length; i += step) spots.add(new int[]{VoxelModel.kx(keys[i]) + r.ox(), VoxelModel.ky(keys[i]) + r.oy(), VoxelModel.kz(keys[i]) + r.oz()});
                String denied = protection.check(pl, r.world(), Protection.sample(spots, 128));
                if (denied != null) { tell(pl, "I can't build those changes: " + denied + " is protected for you."); s.status(DesignSession.Status.FAILED); return; }
                PlayerState ps = state(pl);
                if (cfg.renovate().requireConfirm()) {
                    preview(pl, ps, changes, r);
                    ps.pendingRenovation = () -> startRenovationJob(pl, ps, s, changes);
                    tell(pl, "Here is a preview of the changes (" + changes.size() + " blocks, about " + Math.max(1, changes.size() / Math.max(1, cfg.blocksPerTick() * 20)) + " s to build). "
                            + "Type /architect confirm (or tell me to go ahead) to build it, or /architect cancel.");
                    Bukkit.getScheduler().runTaskLater(plugin, () -> { if (ps.pendingRenovation != null) clearPreview(pl, ps); }, cfg.renovate().previewSeconds() * 20L);
                    return;
                }
                startRenovationJob(pl, ps, s, changes);
            });
        });
    }

    private void preview(Player p, PlayerState st, VoxelModel changes, StructureCapture.Result r) {
        Map<io.papermc.paper.math.Position, BlockData> ghost = new HashMap<>();
        Location at = p.getLocation();
        List<long[]> near = new ArrayList<>();
        for (long k : changes.keys()) {
            int x = VoxelModel.kx(k) + r.ox(), y = VoxelModel.ky(k) + r.oy(), z = VoxelModel.kz(k) + r.oz();
            double d = (x - at.getX()) * (x - at.getX()) + (y - at.getY()) * (y - at.getY()) + (z - at.getZ()) * (z - at.getZ());
            near.add(new long[]{k, (long) d});
        }
        near.sort((a, c) -> Long.compare(a[1], c[1]));
        st.preview = new ArrayList<>();
        for (int i = 0; i < Math.min(40_000, near.size()); i++) {
            long k = near.get(i)[0];
            BlockData d = toData.apply(changes.getByKey(k));
            if (d == null) continue;
            io.papermc.paper.math.Position pos = io.papermc.paper.math.Position.block(VoxelModel.kx(k) + r.ox(), VoxelModel.ky(k) + r.oy(), VoxelModel.kz(k) + r.oz());
            ghost.put(pos, d);
            st.preview.add(pos);
        }
        st.previewWorld = r.world();
        p.sendMultiBlockChange(ghost);
    }

    /** Shows the player the real blocks again where a preview was. */
    private void clearPreview(Player p, PlayerState st) {
        if (st.preview == null || st.previewWorld == null) return;
        Map<io.papermc.paper.math.Position, BlockData> real = new HashMap<>();
        for (io.papermc.paper.math.Position pos : st.preview) real.put(pos, st.previewWorld.getBlockAt(pos.blockX(), pos.blockY(), pos.blockZ()).getBlockData());
        if (p.isOnline() && p.getWorld().equals(st.previewWorld)) p.sendMultiBlockChange(real);
        st.preview = null;
        st.pendingRenovation = null;
    }

    private void startRenovationJob(Player p, PlayerState st, DesignSession s, VoxelModel changes) {
        clearPreview(p, st);
        StructureCapture.Result r = s.renovation();
        Placement at = new Placement(r.world(), r.ox(), r.oy(), r.oz(), 0);
        UndoJournal journal = new UndoJournal(r.world(), s.name() + " (renovation)").gate(new PaperChunkGate(plugin));
        BuildJob.Prep none = new BuildJob.Prep(false, b -> false, null, 0, 2, false, 0, 0, 0);
        BuildJob job = new BuildJob(s.name(), changes, null, at, journal, toData, none, new PaperChunkGate(plugin)).listener(changeLogger(p.getName()));
        st.placement = at;
        s.status(DesignSession.Status.BUILDING);
        plugin.getLogger().info("Renovating '" + s.name() + "' for " + p.getName() + ": " + changes.size() + " block changes in " + r.world().getName()
                + " around " + r.worldBox()[0] + "," + r.worldBox()[1] + "," + r.worldBox()[2]);
        BossBar bar = BossBar.bossBar(Component.text("Renovating " + s.name(), NamedTextColor.GOLD), 0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        p.showBossBar(bar);
        UUID id = p.getUniqueId();
        long started = System.currentTimeMillis();
        queue.addLast(new Active(job, id, bar, () -> {
            plugin.getLogger().info("Renovated '" + s.name() + "': " + journal.size() + " blocks changed in " + (System.currentTimeMillis() - started) / 1000 + "s");
            Runnable done = () -> {
                st.undo.push(journal);
                while (st.undo.size() > cfg.maxUndo()) st.undo.removeLast();
                saveJournal(id, journal);
                s.status(DesignSession.Status.BUILT);
                if (convo != null) convo.note(id, "The renovation of '" + s.name() + "' is finished (" + journal.size() + " blocks changed). The player can undo it.");
            };
            if (cfg.renovate().verify()) verifyRenovation(id, s, journal, done);
            else {
                done.run();
                Player pl = Bukkit.getPlayer(id);
                if (pl != null) tell(pl, "'" + s.name() + "' is finished. /architect undo restores everything as it was.");
            }
        }));
        if (queue.size() > 1) tell(p, "Your renovation is queued behind " + (queue.size() - 1) + " other build(s).");
    }

    /**
     * After a renovation is built: reads the build again and fixes what slipped through, so the result is sound
     * whatever happened in between (players building, water flowing): seals spots where drained rooms meet the sea,
     * lights spots where mobs could spawn, and checks that every protected block is still exactly as it was.
     */
    private void verifyRenovation(UUID id, DesignSession s, UndoJournal journal, Runnable done) {
        StructureCapture.Result r = s.renovation();
        StructureCapture.select(plugin, r.world(), r.worldBox(), new StructureCapture.Limits(Integer.MAX_VALUE / 4, 384, Integer.MAX_VALUE, cfg.renovate().chunksPerTick(), 0),
                v -> workers.submit(() -> {
                    Existing was = r.existing(), now = v.existing();
                    v.model().setLocked(k -> now.isProtectedKey(k) || was.isProtectedKey(k)); // also a neighbour's build stays untouched
                    Canvas c = new Canvas(v.model(), s.materials());
                    c.existing(now);
                    int dx = v.ox() - r.ox(), dy = v.oy() - r.oy(), dz = v.oz() - r.oz();
                    int sealed = 0;
                    int[][] nb = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
                    for (int y = now.minY(); y <= now.maxY(); y++) for (int z = 0; z < now.depth(); z++) for (int x = 0; x < now.width(); x++) {
                        if (!now.fluid(x, y, z) || v.model().get(x, y, z) != null) continue;
                        for (int[] n : nb) {
                            int ax = x + n[0], ay = y + n[1], az = z + n[2];
                            boolean airNow = v.model().get(ax, ay, az) == null && !now.fluid(ax, ay, az) && !now.natural(ax, ay, az) && now.inside(ax, ay, az);
                            if (airNow && was.fluid(ax + dx, ay + dy, az + dz)) { c.set(x, y, z, "glass"); sealed++; break; }
                        }
                    }
                    int lit = Renovation.lightUp(c, now, null, 1, null, null);
                    int broken = 0;
                    for (long k : was.protectedCells()) {
                        BlockState before = r.snapshot().getByKey(k);
                        if (before == null) continue;
                        BlockState after = v.snapshot().get(VoxelModel.kx(k) - dx, VoxelModel.ky(k) - dy, VoxelModel.kz(k) - dz);
                        if (after == null || !after.id().equals(before.id())) broken++;
                    }
                    long floating = 0;
                    for (long k : StructureAnalyzer.fragments(v.model(), now)) if (!was.original(VoxelModel.kx(k) + dx, VoxelModel.ky(k) + dy, VoxelModel.kz(k) + dz)) floating++;
                    VoxelModel fixes = new VoxelModel();
                    for (long k : v.model().keys()) {
                        BlockState a = v.model().getByKey(k), b0 = v.snapshot().getByKey(k);
                        if (!a.equals(b0)) fixes.set(VoxelModel.kx(k), VoxelModel.ky(k), VoxelModel.kz(k), a);
                    }
                    int score = critic.analyze(v.model(), now).score();
                    final int fs = sealed, fl = lit, fb = broken;
                    final long ff = floating;
                    sync(() -> {
                        Runnable report = () -> {
                            done.run();
                            Player pl = Bukkit.getPlayer(id);
                            String msg = "'" + s.name() + "' is finished: quality " + s.originalScore() + " -> " + score + "/100. Checked it over: "
                                    + (fs > 0 ? "sealed " + fs + " leaks" : "watertight") + ", " + (fl > 0 ? "lit " + fl + " more dark spots" : "no dark spots")
                                    + ", " + (fb == 0 ? "all " + was.protectedCells().size() + " protected blocks (containers, signs, frames) intact" : fb + " protected blocks CHANGED - tell an admin")
                                    + (ff > 0 ? ", " + ff + " new blocks without support" : "") + ". /architect undo restores everything.";
                            plugin.getLogger().info("Verified '" + s.name() + "': sealed " + fs + ", lit " + fl + ", protected changed " + fb + ", new floating " + ff + ", quality " + s.originalScore() + " -> " + score);
                            if (pl != null) tell(pl, msg);
                        };
                        if (fixes.size() == 0) { report.run(); return; }
                        BuildJob job = new BuildJob(s.name() + " (fixes)", fixes, null, new Placement(v.world(), v.ox(), v.oy(), v.oz(), 0), journal, toData,
                                new BuildJob.Prep(false, b -> false, null, 0, 2, false, 0, 0, 0), new PaperChunkGate(plugin)).listener(changeLogger(s.owner()));
                        BossBar bar = BossBar.bossBar(Component.text("Final checks: " + s.name(), NamedTextColor.GOLD), 0f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
                        Player pl = Bukkit.getPlayer(id);
                        if (pl != null) pl.showBossBar(bar);
                        queue.addFirst(new Active(job, id, bar, report));
                    });
                }),
                msg -> { plugin.getLogger().warning("Renovation check skipped: " + msg); done.run(); });
    }

    private BuildJob.ChangeListener changeLogger(String player) {
        return protection.logging() ? (b, before, after) -> protection.logChange(player, b, before, after) : null;
    }

    // ------------------------------------------------------------------------------------------------ persistent undo

    private Path undoDir(UUID id) { return plugin.getDataFolder().toPath().resolve("undo").resolve(id.toString()); }

    private void saveJournal(UUID id, UndoJournal j) {
        Path file = undoDir(id).resolve(System.currentTimeMillis() + ".undo.gz");
        journalFiles.put(j, file);
        workers.submit(() -> {
            try {
                j.save(file);
                try (var files = java.nio.file.Files.list(file.getParent())) {
                    List<Path> all = files.filter(f -> f.getFileName().toString().endsWith(".undo.gz")).sorted(java.util.Comparator.reverseOrder()).toList();
                    for (int i = cfg.maxUndo(); i < all.size(); i++) java.nio.file.Files.deleteIfExists(all.get(i));
                }
            } catch (java.io.IOException e) {
                plugin.getLogger().warning("Could not save undo data to " + file + ": " + e);
            }
        });
    }

    /** The newest undo saved on disk for a player (after a restart), or null. */
    private UndoJournal loadNewestJournal(UUID id) {
        Path dir = undoDir(id);
        if (!java.nio.file.Files.isDirectory(dir)) return null;
        try (var files = java.nio.file.Files.list(dir)) {
            for (Path f : files.filter(x -> x.getFileName().toString().endsWith(".undo.gz")).sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (journalFiles.containsValue(f)) continue;
                UndoJournal j = UndoJournal.load(f, Bukkit::getWorld, Bukkit::createBlockData);
                if (j == null) continue;
                j.gate(new PaperChunkGate(plugin));
                journalFiles.put(j, f);
                return j;
            }
        } catch (java.io.IOException | RuntimeException e) {
            plugin.getLogger().warning("Could not read saved undo data: " + e);
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------ other commands

    public void undo(Player p) {
        PlayerState st = state(p);
        for (Active a : queue) if (a.owner().equals(p.getUniqueId())) { tell(p, "Wait for the current build to finish (or /architect cancel) first."); return; }
        UndoJournal j = st.undo.poll();
        if (j == null) j = loadNewestJournal(p.getUniqueId()); // saved before a restart
        if (j == null) { tell(p, "There is nothing of yours to undo."); return; }
        restoring.add(j);
        if (st.undo.isEmpty()) { st.placement = null; st.placedSnapshot = null; }
        else if (st.session != null && st.placedSnapshot != null) st.placedSnapshot = null; // an older generation stays; expansion restarts from scratch
        tell(p, "Taking down '" + j.title() + "' (" + j.size() + " blocks restored).");
    }

    public void cancel(Player p) {
        PlayerState st = state(p);
        st.awaitingConfirm = false;
        if (st.pendingRenovation != null) {
            clearPreview(p, st);
            if (st.session != null) st.session.status(DesignSession.Status.CANCELLED);
            tell(p, "Renovation cancelled; nothing was changed.");
            return;
        }
        if (st.session != null && designing.contains(st.session)) {
            st.session.status(DesignSession.Status.CANCELLED);
            tell(p, "Design cancelled.");
            return;
        }
        for (Active a : queue) if (a.owner().equals(p.getUniqueId())) {
            a.job().cancel();
            tell(p, "Construction stopped. Use /architect undo to remove what was built.");
            return;
        }
        tell(p, "Nothing to cancel.");
    }

    public void rate(Player p, int stars) {
        PlayerState st = state(p);
        if (st.session == null || st.session.status() != DesignSession.Status.BUILT) { tell(p, "Rate a build after it is complete."); return; }
        st.session.rating(stars);
        if (stars >= 4) {
            DesignSession s = st.session;
            workers.submit(() -> {
                try {
                    Path saved = exemplars.save(s);
                    if (saved != null) sync(() -> tell(p, "Thank you! I'll remember what made '" + s.name() + "' work for future designs."));
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Could not save exemplar", e);
                }
            });
        } else {
            tell(p, "Noted. Tell me what to change with /architect expand <changes>, or /architect undo.");
        }
        plugin.getLogger().info(p.getName() + " rated '" + st.session.name() + "' " + stars + "/5 (score " + (st.session.lastReport() == null ? "?" : st.session.lastReport().score()) + ")");
    }

    public void status(Player p) {
        PlayerState st = state(p);
        StringBuilder sb = new StringBuilder();
        sb.append("Claude: ").append(agent == null ? "off (no API key)" : cfg.agent().model() + ", effort " + cfg.agent().effort());
        if (st.session != null) sb.append(". Your build: '").append(st.session.name()).append("' ").append(st.session.status().name().toLowerCase(Locale.ROOT))
                .append(st.session.lastReport() == null ? "" : ", quality " + st.session.lastReport().score());
        for (Active a : queue) if (a.owner().equals(p.getUniqueId())) sb.append(", ").append(Math.round(a.job().progress() * 100)).append("% built");
        sb.append(". Queue: ").append(queue.size()).append(", designing: ").append(designing.size()).append('.');
        tell(p, sb.toString());
    }

    public void summonNpc(Player p) { npc.spawn(p.getLocation()); }
    public void npcFollow(Player p) { npc.follow(p); speak(p, "Right behind you."); }
    public void npcStay(Player p) { npc.ensureNear(p); npc.stay(); speak(p, "I'll wait here."); }
    public void dismissNpc() { npc.despawn(); }

    // ------------------------------------------------------------------------------------------------ ticking

    private void tick() {
        // undo restores first (they are what players are waiting on)
        // slow down when the server is struggling, so builds never cost players TPS
        double mspt = cfg.throttleMspt() > 0 ? Bukkit.getServer().getAverageTickTime() : 0;
        double factor = cfg.throttleMspt() <= 0 || mspt < cfg.throttleMspt() ? 1 : mspt < cfg.throttleMspt() * 1.2 ? 0.5 : 0.15;
        int blocks = Math.max(16, (int) (cfg.blocksPerTick() * factor));
        double ms = Math.max(0.5, cfg.maxMsPerTick() * factor);
        for (Iterator<UndoJournal> it = restoring.iterator(); it.hasNext(); ) {
            UndoJournal j = it.next();
            if (j.restoreStep(blocks * 2, ms)) {
                it.remove();
                Path f = journalFiles.remove(j);
                if (f != null) workers.submit(() -> { try { java.nio.file.Files.deleteIfExists(f); } catch (java.io.IOException ignored) { } });
            }
        }
        Active a = queue.peekFirst();
        if (a != null) {
            BuildJob job = a.job();
            job.tick(blocks, ms);
            a.bar().progress((float) Math.max(0, Math.min(1, job.progress())));
            BuildJob.Placed last = job.last();
            if (cfg.npcEnabled() && last != null) npc.work(job.placement().world(), last.x(), last.y(), last.z(), last.data(), true);
            if (job.done()) {
                queue.pollFirst();
                finishJob(a, true);
            }
        } else if (cfg.npcEnabled()) {
            npc.tick();
            if (!designing.isEmpty()) npc.thinking();
        }
    }

    private void finishJob(Active a, boolean run) {
        Player p = Bukkit.getPlayer(a.owner());
        if (p != null) p.hideBossBar(a.bar());
        if (!a.job().done()) a.job().cancel(); // releases the job's chunk tickets
        if (run) a.onDone().run();
    }

    // ------------------------------------------------------------------------------------------------ conversation

    /** Async-safe: is this chat line meant for the Architect? (addressed by name, or a follow-up within the conversation window) */
    public boolean addressed(UUID id, String text) {
        return startsWithTrigger(text) || talking.getOrDefault(id, 0L) > System.currentTimeMillis();
    }

    private boolean startsWithTrigger(String text) {
        String t = text.toLowerCase(Locale.ROOT).trim();
        if (t.startsWith("@")) t = t.substring(1);
        List<String> words = new ArrayList<>(cfg.chat().triggers());
        words.add(cfg.npcName().toLowerCase(Locale.ROOT));
        for (String w : words) {
            if (w.isEmpty() || !t.startsWith(w)) continue;
            if (t.length() == w.length() || !Character.isLetterOrDigit(t.charAt(w.length()))) return true;
        }
        return false;
    }

    public boolean hideAddressed() { return cfg.chat().hideAddressed(); }

    /** A player left: end their conversation (its memory is kept until restart) and stop following them. */
    public void left(Player p) {
        talking.remove(p.getUniqueId());
        if (p.getUniqueId().equals(npc.following())) npc.stay();
    }

    /** Main thread: a player said something to the Architect. */
    public void chat(Player p, String text) {
        if (!cfg.chat().enabled()) return;
        boolean named = startsWithTrigger(text);
        if (!named) {
            // follow-ups without the name only count while the player is near the Architect
            if (!npc.near(p.getLocation(), cfg.chat().range())) { talking.remove(p.getUniqueId()); return; }
        }
        if (!p.hasPermission(cfg.chat().permission())) { tell(p, "You don't have permission to talk to me."); return; }
        talking.put(p.getUniqueId(), System.currentTimeMillis() + cfg.chat().conversationSeconds() * 1000L);
        if (cfg.npcEnabled()) npc.attend(p);
        String line = named ? stripTrigger(text) : text.trim();
        if (line.isEmpty()) line = "(the player calls your name)";
        if (convo == null) { offlineChat(p, line); return; }
        UUID id = p.getUniqueId();
        String name = p.getName();
        String context = chatContext(p);
        final String said = line;
        chatWorkers.submit(() -> convo.respond(id, name, said, context, this::companionActSync, reply -> sync(() -> {
            Player pl = Bukkit.getPlayer(id);
            if (pl != null) speak(pl, reply);
        })));
    }

    private String stripTrigger(String text) {
        String t = text.trim();
        if (t.startsWith("@")) t = t.substring(1);
        List<String> words = new ArrayList<>(cfg.chat().triggers());
        words.add(cfg.npcName().toLowerCase(Locale.ROOT));
        for (String w : words) {
            if (!w.isEmpty() && t.toLowerCase(Locale.ROOT).startsWith(w)) { t = t.substring(w.length()); break; }
        }
        return t.replaceFirst("^[\\s,:;!.-]+", "").trim();
    }

    /** The Architect's reply: chat for everyone near, and a speech bubble over the NPC. */
    private void speak(Player p, String line) {
        String t = line.length() > 300 ? line.substring(0, 297) + "..." : line;
        plugin.getLogger().info("[to " + p.getName() + "] <" + cfg.npcName() + "> " + line);
        Component msg = Component.text("<" + cfg.npcName() + "> ", NamedTextColor.GOLD).append(Component.text(t, NamedTextColor.WHITE));
        Location at = p.getLocation();
        for (Player other : p.getWorld().getPlayers()) if (other.equals(p) || other.getLocation().distanceSquared(at) < 48 * 48) other.sendMessage(msg);
        if (cfg.npcEnabled()) { npc.ensureNear(p); npc.say(t); }
    }

    private String chatContext(Player p) {
        PlayerState st = state(p);
        StringBuilder sb = new StringBuilder();
        Location l = p.getLocation();
        sb.append("player at x=").append(l.getBlockX()).append(" y=").append(l.getBlockY()).append(" z=").append(l.getBlockZ())
                .append(" in ").append(p.getWorld().getEnvironment().name().toLowerCase(Locale.ROOT)).append(", facing ").append(SiteSurvey.facing(p).name().toLowerCase(Locale.ROOT));
        org.bukkit.block.Block look = p.getTargetBlockExact(160);
        if (look != null) sb.append("; looking at ").append(look.getType().getKey().getKey()).append(" ").append((int) Math.round(look.getLocation().distance(l))).append(" blocks away");
        else sb.append("; looking at the sky or something out of range");
        sb.append("\nyou (the NPC): ").append(npc.alive() ? npc.mode().name().toLowerCase(Locale.ROOT) + (npc.near(l, 16) ? ", next to the player" : ", away from the player") : "not spawned");
        sb.append("\nthis player's build: ").append(buildState(p, st));
        sb.append("\nClaude design available: ").append(agent != null ? "yes" : "no (templates only)");
        return sb.toString();
    }

    private String buildState(Player p, PlayerState st) {
        for (Active a : queue) if (a.owner().equals(p.getUniqueId()))
            return "constructing '" + a.job().title() + "', " + Math.round(a.job().progress() * 100) + "% (" + a.job().placed() + " of " + a.job().total() + " blocks)";
        if (st.session == null) return "none yet";
        if (designing.contains(st.session)) return "designing '" + (st.session.name() == null ? "untitled" : st.session.name()) + "' (" + st.session.renders() + " renders so far)";
        return "'" + st.session.name() + "' " + st.session.status().name().toLowerCase(Locale.ROOT)
                + (st.session.lastReport() == null ? "" : ", quality " + st.session.lastReport().score() + "/100")
                + (st.undo.isEmpty() ? "" : ", " + st.undo.size() + " build(s) can be undone");
    }

    /** Called on a chat worker thread: runs the tool on the main thread and waits for its answer. */
    private String companionActSync(UUID id, String tool, com.google.gson.JsonObject in) {
        if (!plugin.isEnabled()) return "The server is shutting down.";
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                Player p = Bukkit.getPlayer(id);
                if (p == null) return "The player left.";
                return companionAct(p, tool, in);
            }).get(20, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return "failed: " + e.getMessage();
        }
    }

    /** Main thread: executes one companion tool for a player. */
    String companionAct(Player p, String tool, com.google.gson.JsonObject in) {
        PlayerState st = state(p);
        boolean canBuild = p.hasPermission("architect.use");
        switch (tool) {
            case "build" -> {
                if (!canBuild) return "This player lacks the architect.use permission, so you cannot build for them.";
                String brief = str(in, "brief", "").trim();
                if (brief.isEmpty()) return "The brief is empty.";
                return design(p, brief, siteFor(p, str(in, "where", "front")), true);
            }
            case "template" -> {
                if (!canBuild) return "This player lacks the architect.use permission.";
                String name = str(in, "name", "cottage").toLowerCase(Locale.ROOT).replace(' ', '_');
                int scale = in.has("scale") ? Math.max(1, Math.min(3, in.get("scale").getAsInt())) : 1;
                int gens = in.has("generations") ? Math.max(1, Math.min(5, in.get("generations").getAsInt())) : 3;
                return template(p, name, str(in, "style", null), scale, gens, siteFor(p, str(in, "where", "front")));
            }
            case "expand" -> {
                if (!canBuild) return "This player lacks the architect.use permission.";
                return expand(p, str(in, "brief", "Add the next generation."));
            }
            case "renovate" -> {
                if (!canBuild) return "This player lacks the architect.use permission.";
                String brief = str(in, "brief", "").trim();
                if (brief.isEmpty()) brief = "Renovate this build: repair all damage and improve it in its own style.";
                return renovate(p, brief, "selection".equals(str(in, "target", "look")), "repair".equals(str(in, "mode", "full")));
            }
            case "confirm" -> {
                if (st.pendingRenovation == null && !st.awaitingConfirm) return "Nothing is waiting for confirmation.";
                confirm(p);
                return "Confirmed; building now.";
            }
            case "inspect" -> { inspect(p); return "Inspecting the build the player is looking at; the findings go to them in chat."; }
            case "move" -> {
                if (!cfg.npcEnabled()) return "The NPC is disabled on this server.";
                String a = str(in, "action", "come");
                switch (a) {
                    case "follow" -> { npc.follow(p); return "You are now following " + p.getName() + "."; }
                    case "stay" -> { npc.ensureNear(p); npc.stay(); return "You stay where you are."; }
                    case "go_to_look" -> {
                        org.bukkit.block.Block b = p.getTargetBlockExact(160);
                        if (b == null) return "The player is not looking at a block within range.";
                        npc.ensureNear(p);
                        npc.goTo(b.getLocation().add(0.5, 1, 0.5));
                        return "Walking to the " + b.getType().getKey().getKey() + " the player is looking at, " + (int) b.getLocation().distance(p.getLocation()) + " blocks away.";
                    }
                    default -> { npc.ensureNear(p); npc.goTo(p.getLocation()); return "Coming to the player."; }
                }
            }
            case "undo" -> { int before = st.undo.size(); undo(p); return st.undo.size() < before ? "Undoing the last build." : "Nothing could be undone (nothing built, or a build is still in progress)."; }
            case "cancel" -> { cancel(p); return "Cancel requested."; }
            case "status" -> { return buildState(p, st) + ". Queue: " + queue.size() + " build(s), " + designing.size() + " design(s) in progress server-wide."; }
            case "rate" -> {
                int stars = in.has("stars") ? Math.max(1, Math.min(5, in.get("stars").getAsInt())) : 5;
                if (st.session == null || st.session.status() != DesignSession.Status.BUILT) return "There is no finished build to rate yet.";
                rate(p, stars);
                return "Recorded " + stars + " star(s)" + (stars >= 4 ? " and saved it as an example to learn from." : ".");
            }
            case "surroundings" -> {
                String d = SiteSurvey.describe(p, 48);
                org.bukkit.block.Block b = p.getTargetBlockExact(160);
                return d + (b == null ? " The player is not looking at any block in range." : " The player is looking at " + b.getType().getKey().getKey() + " at y=" + b.getY() + ", " + (int) b.getLocation().distance(p.getLocation()) + " blocks away.");
            }
            case "catalog" -> {
                return "Templates: " + String.join(", ", Templates.names()) + ". Styles: " + String.join(", ", styles.names())
                        + ". Researched landmarks: " + String.join(", ", LandmarkLibrary.bundled().names()) + ". Anything else can be designed from a description.";
            }
            default -> { return "Unknown tool " + tool; }
        }
    }

    private static String str(com.google.gson.JsonObject o, String k, String def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def;
    }

    /** Where a chat-requested build goes. "look" centres it on the targeted block; otherwise in front of the player as they stand now. */
    private SiteSurvey.Site siteFor(Player p, String where) {
        if ("look".equals(where)) {
            org.bukkit.block.Block b = p.getTargetBlockExact(256);
            if (b != null) return new SiteSurvey.Site(b.getLocation().add(0, 1, 0), SiteSurvey.facing(p), true);
        }
        if ("here".equals(where)) return new SiteSurvey.Site(p.getLocation(), SiteSurvey.facing(p), true);
        return new SiteSurvey.Site(p.getLocation(), SiteSurvey.facing(p), false);
    }

    /** Without an API key the Architect still understands simple requests. */
    private void offlineChat(Player p, String line) {
        String t = line.toLowerCase(Locale.ROOT);
        if (t.contains("follow")) { npc.follow(p); speak(p, "Right behind you."); return; }
        if (t.contains("stay") || t.contains("wait") || t.contains("stop following")) { npc.ensureNear(p); npc.stay(); speak(p, "I'll wait here."); return; }
        if (t.contains("come")) { npc.ensureNear(p); npc.goTo(p.getLocation()); speak(p, "On my way."); return; }
        if (t.contains("undo") || t.contains("take it down") || t.contains("remove it")) { undo(p); return; }
        if (t.contains("cancel")) { cancel(p); return; }
        if (t.contains("status") || t.contains("how is") || t.contains("progress")) { speak(p, "Your build: " + buildState(p, state(p)) + "."); return; }
        if (t.contains("build") || t.contains("make") || t.contains("construct")) {
            String tp = guessTemplate(t);
            speak(p, "Without my design studio (no API key) I can raise my " + pretty(tp) + ". Here it comes!");
            template(p, tp, null, 1, 3, siteFor(p, t.contains("there") ? "look" : "front"));
            return;
        }
        speak(p, "I can build (" + String.join(", ", Templates.names()) + "), follow you, stay, or undo. An admin can connect Claude for custom designs and real conversation.");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private boolean checkCooldown(Player p, PlayerState st) {
        if (p.hasPermission("architect.admin")) return true;
        long wait = st.lastRequestMillis + cfg.cooldownSeconds() * 1000L - System.currentTimeMillis();
        if (wait > 0) { tell(p, "Give me " + (wait / 1000 + 1) + "s to catch my breath."); return false; }
        return true;
    }

    private boolean isBusy(Player p, PlayerState st) {
        if (st.capturing) { tell(p, "I'm still studying the build you showed me."); return true; }
        if (st.pendingRenovation != null) { tell(p, "A renovation is waiting for your /architect confirm (or /architect cancel)."); return true; }
        if (st.session != null && designing.contains(st.session)) { tell(p, "I'm still designing your last request (/architect cancel to stop)."); return true; }
        for (Active a : queue) if (a.owner().equals(p.getUniqueId())) { tell(p, "I'm still building your last request."); return true; }
        return false;
    }

    private void attachValidator(DesignSession s) {
        s.materials().setValidator(ArchitectTools.banning(cfg.agent(), st -> {
            try {
                Bukkit.createBlockData(st.toNamespaced());
                return null;
            } catch (IllegalArgumentException e) {
                return "'" + st + "' is not a valid block state";
            }
        }));
    }

    private void sync(Runnable r) {
        if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, r);
    }

    private void narrate(Player p, String text) {
        String t = text.length() > 220 ? text.substring(0, 217) + "..." : text;
        plugin.getLogger().info("[to " + p.getName() + "] " + text);
        Component msg = Component.text("[" + cfg.npcName() + "] ", NamedTextColor.GOLD).append(Component.text(t, NamedTextColor.YELLOW));
        Location at = p.getLocation();
        for (Player other : p.getWorld().getPlayers()) if (other.equals(p) || other.getLocation().distanceSquared(at) < 48 * 48) other.sendMessage(msg);
    }

    void tell(Player p, String text) {
        plugin.getLogger().info("[to " + p.getName() + "] " + text);
        p.sendMessage(Component.text("[" + cfg.npcName() + "] ", NamedTextColor.GOLD).append(Component.text(text, NamedTextColor.WHITE)));
    }

    static String pretty(String name) {
        String[] parts = name.split("_");
        StringBuilder sb = new StringBuilder();
        for (String s : parts) { if (sb.length() > 0) sb.append(' '); sb.append(Character.toUpperCase(s.charAt(0))).append(s.substring(1)); }
        return sb.toString();
    }

    /** Picks the closest curated template for a free-text request (offline mode). */
    static String guessTemplate(String request) {
        String r = request.toLowerCase(Locale.ROOT);
        String[][] map = {
                {"cathedral", "cathedral"}, {"church", "cathedral"}, {"chapel", "cathedral"}, {"gothic", "cathedral"},
                {"castle", "castle"}, {"fort", "castle"}, {"citadel", "castle"}, {"keep", "castle"},
                {"pagoda", "pagoda"}, {"japan", "pagoda"}, {"shrine", "pagoda"},
                {"temple", "temple"}, {"greek", "temple"}, {"roman", "temple"}, {"parthenon", "temple"},
                {"palace", "palace"}, {"desert", "palace"}, {"mosque", "palace"}, {"sultan", "palace"},
                {"villa", "villa"}, {"modern", "villa"}, {"mansion", "villa"},
                {"village", "village"}, {"town", "village"}, {"settlement", "village"},
                {"wizard", "wizard_tower"}, {"mage", "wizard_tower"}, {"magic", "wizard_tower"},
                {"watch", "watchtower"}, {"lighthouse", "watchtower"}, {"tower", "wizard_tower"},
                {"viking", "longhouse"}, {"longhouse", "longhouse"}, {"nordic", "longhouse"}, {"hall", "longhouse"},
                {"townhouse", "townhouse"}, {"house", "townhouse"}, {"home", "townhouse"}, {"shop", "townhouse"},
                {"cottage", "cottage"}, {"hut", "cottage"}, {"cabin", "cottage"}};
        for (String[] m : map) if (r.contains(m[0])) return m[1];
        return "cottage";
    }

    /** A sensible starting palette from the wording; Claude can switch it with set_plan. */
    String guessStyle(String request) {
        String r = request.toLowerCase(Locale.ROOT);
        for (String s : styles.names()) if (r.contains(s)) return s;
        if (r.contains("elven") || r.contains("wizard") || r.contains("mage") || r.contains("fairy")) return "fantasy";
        if (r.contains("viking")) return "nordic";
        if (r.contains("dwarf")) return "dwarven";
        if (r.contains("greek") || r.contains("roman")) return "classical";
        if (r.contains("church") || r.contains("cathedral")) return "gothic";
        return "medieval";
    }

    Set<String> templateNames() { return new HashSet<>(Templates.names()); }
}
