package io.github.shengboj0324.architect.core;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Automatic architectural critic. Produces a 0-100 score from measurable proxies of build quality
 * (material richness, facade depth, roof articulation, detailing, openings, lighting, skyline) and hard
 * structural checks (floating blocks, unsupported gravity blocks). The AI must reach the configured
 * threshold before a build is placed, which is what makes in-game quality repeatable.
 */
public final class QualityAnalyzer {

    public record Issue(String severity, String message) {
        @Override public String toString() { return "[" + severity + "] " + message; }
    }

    public record Report(int score, Map<String, String> metrics, List<Issue> issues, Map<String, Integer> breakdown) {
        public boolean hasCritical() {
            for (Issue i : issues) if (i.severity.equals("CRITICAL")) return true;
            return false;
        }

        public String format() {
            StringBuilder sb = new StringBuilder();
            sb.append("QUALITY SCORE: ").append(score).append("/100\n");
            sb.append("breakdown: ").append(breakdown).append('\n');
            sb.append("metrics: ").append(metrics).append('\n');
            if (issues.isEmpty()) sb.append("issues: none\n");
            else {
                sb.append("issues:\n");
                for (Issue i : issues) sb.append("  ").append(i).append('\n');
            }
            return sb.toString();
        }
    }

    private final BlockCatalog cat = BlockCatalog.get();

    public Report analyze(VoxelModel m) { return analyze(m, null); }

    /** Scores the model. With {@code ground} (natural site mode) blocks resting on or embedded in the terrain count as supported. */
    public Report analyze(VoxelModel m, Ground ground) {
        Map<String, String> metrics = new LinkedHashMap<>();
        Map<String, Integer> breakdown = new LinkedHashMap<>();
        List<Issue> issues = new ArrayList<>();
        int[] b = m.bounds();
        if (b == null || m.nonAirCount() == 0) {
            issues.add(new Issue("CRITICAL", "model is empty"));
            return new Report(0, metrics, issues, breakdown);
        }
        int W = b[3] - b[0] + 1, H = b[4] - b[1] + 1, D = b[5] - b[2] + 1;
        metrics.put("size", W + "x" + H + "x" + D + " (w x h x d)");

        // ---------------------------------------------------------------- materials & detail
        Map<String, Integer> counts = m.materialCounts();
        long total = 0;
        for (int v : counts.values()) total += v;
        int distinct = counts.size();
        String dominant = null;
        int domCount = 0;
        int detail = 0, lights = 0, glass = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > domCount) { domCount = e.getValue(); dominant = e.getKey(); }
            BlockCatalog.Kind k = cat.kind(e.getKey());
            switch (k) {
                case STAIRS, SLAB, FENCE, WALL, PANE, TRAPDOOR, DOOR, GATE, DECOR, PLANT -> detail += e.getValue();
                case LIGHT -> { detail += e.getValue(); lights += e.getValue(); }
                default -> { }
            }
            if (k == BlockCatalog.Kind.GLASS || k == BlockCatalog.Kind.PANE) glass += e.getValue();
        }
        double domShare = (double) domCount / total;
        double detailRatio = (double) detail / total;
        metrics.put("blocks", Long.toString(total));
        metrics.put("materials", Integer.toString(distinct));
        metrics.put("dominant", dominant + " " + pct(domShare));
        metrics.put("detail_ratio", pct(detailRatio));

        int matScore = (int) Math.round(8 * clamp01((distinct - 2) / 6.0) + 7 * clamp01((0.8 - domShare) / 0.35));
        if (distinct <= 2) issues.add(new Issue("MAJOR", "only " + distinct + " materials; use a palette of 5+ with accent, trim and texture variants"));
        if (domShare > 0.7 && total > 200) issues.add(new Issue("MINOR", dominant + " makes up " + pct(domShare) + " of the build; break it up with texture mixes (@role weights), trims and accents"));
        breakdown.put("materials", matScore);

        int detScore = (int) Math.round(15 * band(detailRatio, 0.06, 0.15, 0.5, 0.7));
        if (detailRatio < 0.08 && total > 150) issues.add(new Issue("MAJOR", "very few detail blocks (" + pct(detailRatio) + "); add stairs/slab trims, sills, window frames, fences, lanterns"));
        breakdown.put("detail", detScore);

        // ---------------------------------------------------------------- facade depth & openings
        int rays = 0, relief = 0, openings = 0;
        for (Dir s : Dir.values()) {
            int[] r = facade(m, b, s);
            rays += r[0]; relief += r[1]; openings += r[2];
        }
        double depthRatio = rays == 0 ? 0 : (double) relief / rays;
        double openRatio = rays == 0 ? 0 : (double) openings / rays;
        metrics.put("facade_relief", pct(depthRatio));
        metrics.put("openings", pct(openRatio));
        int depthScore = (int) Math.round(20 * band(depthRatio, 0.08, 0.25, 0.85, 1.01));
        if (depthRatio < 0.12 && Math.max(W, D) > 6) issues.add(new Issue("MAJOR", "facades are flat (" + pct(depthRatio) + " relief); add depth: recess walls behind pillars/frames, protruding cornices, buttresses, window insets"));
        breakdown.put("facade_depth", depthScore);
        int openScore = (int) Math.round(10 * band(openRatio, 0.015, 0.05, 0.35, 0.6));
        if (openRatio < 0.02 && H > 4 && glass == 0) issues.add(new Issue("MINOR", "no windows or openings visible on the facades"));
        breakdown.put("openings", openScore);

        // ---------------------------------------------------------------- roof / top surface articulation
        int cols = 0, sloped = 0;
        Map<Integer, Integer> topHeights = new HashMap<>();
        int cs = stride((long) W * D, 60_000);
        for (int x = b[0]; x <= b[3]; x += cs) for (int z = b[2]; z <= b[5]; z += cs) {
            for (int y = b[4]; y >= b[1]; y--) {
                BlockState s = m.get(x, y, z);
                if (s == null || s.isAir()) continue;
                BlockCatalog.Kind k = cat.kind(s.id());
                if (k == BlockCatalog.Kind.PLANT || k == BlockCatalog.Kind.LEAVES) break;
                cols++;
                if (k == BlockCatalog.Kind.STAIRS || k == BlockCatalog.Kind.SLAB || k == BlockCatalog.Kind.WALL || k == BlockCatalog.Kind.FENCE || k == BlockCatalog.Kind.LIGHT || k == BlockCatalog.Kind.DECOR) sloped++;
                topHeights.merge(y, 1, Integer::sum);
                break;
            }
        }
        double roofArt = cols == 0 ? 0 : (double) sloped / cols;
        metrics.put("roof_articulation", pct(roofArt));
        int roofScore = (int) Math.round(15 * band(roofArt, 0.15, 0.45, 1.01, 1.02));
        if (roofArt < 0.2 && cols > 30) issues.add(new Issue("MAJOR", "roofs read as flat full blocks from above; use the roof op (gable/hip/spire) or slab/stair edges and parapets"));
        breakdown.put("roof", roofScore);

        // skyline variety
        int levels = 0;
        for (int v : topHeights.values()) if (v >= 2) levels++;
        int skyScore = (int) Math.round(5 * clamp01((levels - 1) / 6.0));
        metrics.put("skyline_levels", Integer.toString(levels));
        breakdown.put("skyline", skyScore);

        // ---------------------------------------------------------------- lighting
        double litPer = lights == 0 ? 0 : (double) total / lights;
        int lightScore = lights == 0 ? 0 : (int) Math.round(5 * clamp01(1.5 - litPer / 600.0));
        if (lights == 0 && total > 300) issues.add(new Issue("MINOR", "no light sources: add lanterns/sea lanterns so mobs do not spawn and the build glows at night"));
        metrics.put("lights", Integer.toString(lights));
        breakdown.put("lighting", Math.max(0, Math.min(5, lightScore)));

        // ---------------------------------------------------------------- structure
        int structScore = 15;
        long floating = floating(m, b, ground, issues);
        int gravity = gravityProblems(m, ground, issues);
        int detached = attachmentProblems(m, ground, issues);
        if (floating > 0) structScore = 0;
        else if (gravity > 0) structScore = 5;
        else if (detached > 0) structScore = 10;
        metrics.put("floating_blocks", Long.toString(floating));
        breakdown.put("structure", structScore);

        int score = 0;
        for (int v : breakdown.values()) score += v;
        return new Report(Math.max(0, Math.min(100, score)), metrics, issues, breakdown);
    }

    /** Casts rays inward from one side. Returns {rays, rays whose hit depth differs from the dominant plane, rays hitting openings}. */
    private int[] facade(VoxelModel m, int[] b, Dir s) {
        int lo = s.isX() ? b[2] : b[0], hi = s.isX() ? b[5] : b[3];
        int depthMax = s.isX() ? (b[3] - b[0]) : (b[5] - b[2]);
        Map<Integer, Integer> depthHist = new HashMap<>();
        List<int[]> hits = new ArrayList<>();
        int open = 0;
        int y0 = Math.max(b[1], 0);
        int stride = stride((long) (hi - lo + 1) * (b[4] - y0 + 1), 60_000);
        for (int a = lo; a <= hi; a += stride) {
            for (int y = y0; y <= b[4]; y += stride) {
                for (int d = 0; d <= depthMax; d++) {
                    int x, z;
                    switch (s) {
                        case NORTH -> { x = a; z = b[2] + d; }
                        case SOUTH -> { x = a; z = b[5] - d; }
                        case WEST -> { x = b[0] + d; z = a; }
                        default -> { x = b[3] - d; z = a; }
                    }
                    BlockState st = m.get(x, y, z);
                    if (st == null || st.isAir()) continue;
                    BlockCatalog.Kind k = cat.kind(st.id());
                    if (k == BlockCatalog.Kind.PLANT || k == BlockCatalog.Kind.LEAVES) break;
                    hits.add(new int[]{d});
                    depthHist.merge(d, 1, Integer::sum);
                    if (k == BlockCatalog.Kind.GLASS || k == BlockCatalog.Kind.PANE || k == BlockCatalog.Kind.DOOR) open++;
                    break;
                }
            }
        }
        if (hits.isEmpty()) return new int[]{0, 0, 0};
        int mode = 0, best = -1;
        for (Map.Entry<Integer, Integer> e : depthHist.entrySet()) if (e.getValue() > best) { best = e.getValue(); mode = e.getKey(); }
        int relief = hits.size() - best;
        return new int[]{hits.size(), relief, open};
    }

    /** Is the cell below (x, y, z) solid ground? Without terrain, the ground layer y=0 rests on the levelled site. */
    private static boolean groundBelow(Ground ground, int x, int y, int z) {
        return ground == null ? y <= 0 : ground.solid(x, y - 1, z);
    }

    /** Flood-fills from ground-touching blocks; anything unreached is floating. */
    private long floating(VoxelModel m, int[] b, Ground ground, List<Issue> issues) {
        LongOpenHashSet solid = new LongOpenHashSet();
        LongArrayFIFOQueue q = new LongArrayFIFOQueue();
        LongOpenHashSet seen = new LongOpenHashSet();
        for (long k : m.keys()) {
            BlockState s = m.getByKey(k);
            if (s == null || s.isAir()) continue;
            solid.add(k);
            int x = VoxelModel.kx(k), y = VoxelModel.ky(k), z = VoxelModel.kz(k);
            boolean grounded = ground != null && ground.anchored(x, y, z)
                    || (ground == null || ground.groundY(x, z) == Ground.UNKNOWN ? y <= Math.max(0, b[1]) : y <= ground.groundY(x, z) + 1);
            if (grounded) { q.enqueue(k); seen.add(k); }
        }
        while (!q.isEmpty()) {
            long k = q.dequeueLong();
            int x = VoxelModel.kx(k), y = VoxelModel.ky(k), z = VoxelModel.kz(k);
            long[] nb = {VoxelModel.key(x + 1, y, z), VoxelModel.key(x - 1, y, z), VoxelModel.key(x, y + 1, z), VoxelModel.key(x, y - 1, z), VoxelModel.key(x, y, z + 1), VoxelModel.key(x, y, z - 1)};
            for (long n : nb) if (solid.contains(n) && seen.add(n)) q.enqueue(n);
        }
        long floating = solid.size() - seen.size();
        if (floating > 0) {
            // report a few sample coordinates
            List<String> samples = new ArrayList<>();
            for (long k : solid) {
                if (!seen.contains(k)) {
                    samples.add("[" + VoxelModel.kx(k) + "," + VoxelModel.ky(k) + "," + VoxelModel.kz(k) + "]=" + m.getByKey(k).id());
                    if (samples.size() >= 6) break;
                }
            }
            issues.add(new Issue("CRITICAL", floating + " floating blocks not connected to the ground, e.g. " + String.join(" ", samples)
                    + ". Connect them (supports, columns, brackets) or remove them"
                    + (ground == null ? "" : "; on this site a block is grounded when it rests on or is embedded in the terrain (y <= ground height + 1 in its column, see survey_terrain)")));
        }
        return floating;
    }

    private int gravityProblems(VoxelModel m, Ground ground, List<Issue> issues) {
        int n = 0;
        String example = null;
        for (long k : m.keys()) {
            BlockState s = m.getByKey(k);
            if (s == null) continue;
            String id = s.id();
            boolean grav = id.equals("sand") || id.equals("red_sand") || id.equals("gravel") || id.endsWith("concrete_powder") || id.endsWith("anvil") || id.equals("suspicious_sand") || id.equals("suspicious_gravel");
            if (!grav) continue;
            int x = VoxelModel.kx(k), y = VoxelModel.ky(k), z = VoxelModel.kz(k);
            BlockState below = m.get(x, y - 1, z);
            if (ground == null ? y <= 0 : below == null && groundBelow(ground, x, y, z)) continue;
            if (below == null || below.isAir() || !cat.isSolidCube(below)) {
                n++;
                if (example == null) example = id + " at [" + x + "," + y + "," + z + "]";
            }
        }
        if (n > 0) issues.add(new Issue("MAJOR", n + " gravity blocks (sand/gravel/concrete powder) have nothing solid below and will fall, e.g. " + example));
        return n;
    }

    /**
     * Blocks that pop off without the right neighbour once anything near them updates: standing lanterns/torches,
     * plants, doors, carpets and the like need a block below; hanging lanterns need one above.
     */
    private int attachmentProblems(VoxelModel m, Ground ground, List<Issue> issues) {
        int n = 0;
        List<String> examples = new ArrayList<>();
        for (long k : m.keys()) {
            BlockState s = m.getByKey(k);
            if (s == null || s.isAir()) continue;
            String id = s.id();
            int x = VoxelModel.kx(k), y = VoxelModel.ky(k), z = VoxelModel.kz(k);
            if (ground == null && y <= 0) continue;
            if (ground != null && m.get(x, y - 1, z) == null && ground.solid(x, y - 1, z) && !"true".equals(s.prop("hanging"))) continue;
            boolean needsBelow, needsAbove = false;
            if (id.equals("lantern") || id.equals("soul_lantern")) {
                needsAbove = "true".equals(s.prop("hanging"));
                needsBelow = !needsAbove;
            } else {
                BlockCatalog.Kind kind = cat.kind(id);
                needsBelow = kind == BlockCatalog.Kind.PLANT
                        || (kind == BlockCatalog.Kind.DOOR && !"upper".equals(s.prop("half")))
                        || id.equals("torch") || id.equals("soul_torch") || id.equals("redstone_torch") || id.endsWith("_carpet")
                        || id.endsWith("_pressure_plate") || id.equals("rail") || id.endsWith("_rail") || id.equals("flower_pot")
                        || id.startsWith("potted_") || id.endsWith("_sapling") || id.endsWith("_candle") || id.equals("candle")
                        || (id.equals("bell") && "floor".equals(s.prop("attachment")));
            }
            BlockState other = needsAbove ? m.get(x, y + 1, z) : needsBelow ? m.get(x, y - 1, z) : null;
            if ((needsAbove || needsBelow) && (other == null || other.isAir() || (needsBelow && cat.kind(id) == BlockCatalog.Kind.PLANT && cat.kind(other.id()) == BlockCatalog.Kind.PLANT && !id.equals("tall_grass")))) {
                if (needsBelow && cat.kind(id) == BlockCatalog.Kind.PLANT && other != null && "upper".equals(s.prop("half"))) continue;
                n++;
                if (examples.size() < 5) examples.add(id + (needsAbove ? " (hanging)" : "") + " at [" + x + "," + y + "," + z + "]");
            }
        }
        if (n > 0) issues.add(new Issue("MAJOR", n + " block(s) lack the support they need and will drop as items when a neighbour updates, e.g. "
                + String.join(", ", examples) + ". Standing lanterns, torches, plants, doors and carpets need a block below; hanging lanterns need one above"));
        return n;
    }

    /** Sampling step so a scan over {@code cells} positions visits about {@code budget} of them (1 = every cell). */
    static int stride(long cells, long budget) {
        return cells <= budget ? 1 : (int) Math.ceil(Math.sqrt((double) cells / budget));
    }

    private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }

    /** 0 below a, ramps to 1 between a..b, 1 until c, ramps down to 0 at d. */
    private static double band(double v, double a, double b, double c, double d) {
        if (v <= a) return 0;
        if (v < b) return (v - a) / (b - a);
        if (v <= c) return 1;
        if (v < d) return 1 - (v - c) / (d - c);
        return 0;
    }

    private static String pct(double v) { return Math.round(v * 1000) / 10.0 + "%"; }
}
