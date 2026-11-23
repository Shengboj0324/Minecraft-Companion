package io.github.shengboj0324.architect.world;

import io.github.shengboj0324.architect.core.BlockState;
import io.github.shengboj0324.architect.core.Ground;
import io.github.shengboj0324.architect.core.VoxelModel;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A survey of the natural terrain over a world rectangle: per column the top solid ground block (ignoring trees,
 * plants and water), the water standing on it, the tree canopy above it, the surface block and the biome. It is
 * pure data, filled on the main thread by {@link TerrainScanner} and read anywhere, and it describes the land to
 * Claude in the design's own model coordinates through a site frame (a {@link Placement}).
 */
public final class TerrainMap {

    public static final int UNKNOWN = Ground.UNKNOWN;

    private final int x0, z0, w, d;
    private final int[] top;
    private final short[] water, canopy, surface, biome;
    private final List<String> names = new ArrayList<>();
    private final Map<String, Short> index = new HashMap<>();

    /** An empty survey of the world rectangle [x1..x2] x [z1..z2] (inclusive, either order). */
    public TerrainMap(int x1, int z1, int x2, int z2) {
        this.x0 = Math.min(x1, x2);
        this.z0 = Math.min(z1, z2);
        this.w = Math.abs(x2 - x1) + 1;
        this.d = Math.abs(z2 - z1) + 1;
        int n = w * d;
        top = new int[n];
        Arrays.fill(top, UNKNOWN);
        water = new short[n];
        canopy = new short[n];
        surface = new short[n];
        biome = new short[n];
    }

    public int minX() { return x0; }
    public int minZ() { return z0; }
    public int maxX() { return x0 + w - 1; }
    public int maxZ() { return z0 + d - 1; }

    private int idx(int wx, int wz) {
        int i = wx - x0, j = wz - z0;
        return i < 0 || j < 0 || i >= w || j >= d ? -1 : j * w + i;
    }

    private short name(String s) {
        return index.computeIfAbsent(s == null ? "?" : s, k -> { names.add(k); return (short) (names.size() - 1); });
    }

    /** Records one surveyed column (world coordinates; {@code top} is the world y of the top ground block). */
    public void set(int wx, int wz, int top, int water, int canopy, String surface, String biome) {
        int i = idx(wx, wz);
        if (i < 0) return;
        this.top[i] = top;
        this.water[i] = (short) Math.max(0, Math.min(Short.MAX_VALUE, water));
        this.canopy[i] = (short) Math.max(0, Math.min(Short.MAX_VALUE, canopy));
        this.surface[i] = name(surface);
        this.biome[i] = name(biome);
    }

    public boolean known(int wx, int wz) { int i = idx(wx, wz); return i >= 0 && top[i] != UNKNOWN; }
    public int top(int wx, int wz) { int i = idx(wx, wz); return i < 0 ? UNKNOWN : top[i]; }
    public int water(int wx, int wz) { int i = idx(wx, wz); return i < 0 || top[i] == UNKNOWN ? 0 : water[i]; }
    public int canopy(int wx, int wz) { int i = idx(wx, wz); return i < 0 || top[i] == UNKNOWN ? 0 : canopy[i]; }
    public String surface(int wx, int wz) { int i = idx(wx, wz); return i < 0 || top[i] == UNKNOWN ? null : names.get(surface[i]); }
    public String biome(int wx, int wz) { int i = idx(wx, wz); return i < 0 || top[i] == UNKNOWN ? null : names.get(biome[i]); }

    /** Fraction of the rectangle that was surveyed (unloaded chunks stay unknown). */
    public double coverage() {
        int k = 0;
        for (int t : top) if (t != UNKNOWN) k++;
        return top.length == 0 ? 0 : (double) k / top.length;
    }

    /**
     * World y for a levelled build's ground layer over the model rectangle [x1..x2] x [z1..z2] under {@code frame}:
     * the median of the ground, or of the water surface where water stands (a build on a lake sits on the water, on a
     * filled plinth, not on the lake bed). Returns {@code fallback} when nothing there was surveyed.
     */
    public int reference(Placement frame, int x1, int z1, int x2, int z2, int fallback) {
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2), az = Math.min(z1, z2), bz = Math.max(z1, z2);
        int step = Math.max(1, Math.max(bx - ax + 1, bz - az + 1) / 48);
        int[] hs = new int[((bx - ax) / step + 1) * ((bz - az) / step + 1)];
        int n = 0;
        for (int x = ax; x <= bx; x += step) for (int z = az; z <= bz; z += step) {
            int wx = frame.wx(x, z), wz = frame.wz(x, z);
            if (known(wx, wz) && n < hs.length) hs[n++] = top(wx, wz) + water(wx, wz);
        }
        if (n == 0) return fallback;
        Arrays.sort(hs, 0, n);
        return hs[n / 2];
    }

    /** The terrain in a design's model coordinates. */
    public View view(Placement frame) { return new View(frame); }

    /** Model-coordinate accessor: heights are relative to the frame's y (model y=0 is the build's ground layer). */
    public final class View implements Ground {
        private final Placement frame;

        View(Placement frame) { this.frame = frame; }

        public Placement frame() { return frame; }

        @Override public int groundY(int x, int z) {
            int t = top(frame.wx(x, z), frame.wz(x, z));
            return t == UNKNOWN ? UNKNOWN : t - frame.oy();
        }

        @Override public int waterDepth(int x, int z) { return water(frame.wx(x, z), frame.wz(x, z)); }
        public int canopy(int x, int z) { return TerrainMap.this.canopy(frame.wx(x, z), frame.wz(x, z)); }
        public String surface(int x, int z) { return TerrainMap.this.surface(frame.wx(x, z), frame.wz(x, z)); }
        public String biome(int x, int z) { return TerrainMap.this.biome(frame.wx(x, z), frame.wz(x, z)); }
    }

    // ------------------------------------------------------------------------------------------------ descriptions

    /** Statistics over a set of model columns. */
    private record Stats(int n, int unknown, int min, int max, int p10, int p90, double mean, int water, int maxWater, int trees,
                         Map<String, Integer> biomes, Map<String, Integer> surfaces, double frontBack, double westEast) {}

    private Stats stats(View v, int x1, int z1, int x2, int z2) {
        int step = Math.max(1, Math.max(x2 - x1 + 1, z2 - z1 + 1) / 96);
        List<Integer> hs = new ArrayList<>();
        int unknown = 0, wet = 0, maxWater = 0, trees = 0;
        double front = 0, back = 0, west = 0, east = 0;
        int nf = 0, nb = 0, nw = 0, ne = 0;
        Map<String, Integer> biomes = new HashMap<>(), surfaces = new HashMap<>();
        int mx = (x1 + x2) / 2, mz = (z1 + z2) / 2;
        for (int x = x1; x <= x2; x += step) for (int z = z1; z <= z2; z += step) {
            int g = v.groundY(x, z);
            if (g == UNKNOWN) { unknown++; continue; }
            int wd = v.waterDepth(x, z);
            int level = g + wd;
            hs.add(level);
            if (wd > 0) { wet++; maxWater = Math.max(maxWater, wd); }
            if (v.canopy(x, z) > 2) trees++;
            biomes.merge(pretty(v.biome(x, z)), 1, Integer::sum);
            surfaces.merge(wd > 0 ? "water" : v.surface(x, z), 1, Integer::sum);
            if (z < mz) { front += level; nf++; } else { back += level; nb++; }
            if (x < mx) { west += level; nw++; } else { east += level; ne++; }
        }
        if (hs.isEmpty()) return new Stats(0, unknown, 0, 0, 0, 0, 0, 0, 0, 0, biomes, surfaces, 0, 0);
        hs.sort(Integer::compare);
        double mean = 0;
        for (int h : hs) mean += h;
        mean /= hs.size();
        return new Stats(hs.size(), unknown, hs.get(0), hs.get(hs.size() - 1), hs.get(hs.size() / 10), hs.get(hs.size() * 9 / 10), mean, wet, maxWater, trees,
                biomes, surfaces, nf == 0 || nb == 0 ? 0 : back / nb - front / nf, nw == 0 || ne == 0 ? 0 : east / ne - west / nw);
    }

    /**
     * The site report Claude reads after {@code set_plan}: what the land looks like inside and around the envelope,
     * in model coordinates relative to the build's ground layer, with a coarse elevation map and design advice.
     */
    public String report(Placement frame, int width, int height, int depth, boolean natural, int margin) {
        View v = view(frame);
        int x1 = -margin, z1 = -margin, x2 = width - 1 + margin, z2 = depth - 1 + margin;
        Stats in = stats(v, 0, 0, width - 1, depth - 1);
        StringBuilder sb = new StringBuilder();
        sb.append("SITE (model coordinates; y=0 is the build's ground layer at world y=").append(frame.oy()).append("; site mode ")
                .append(natural ? "NATURAL" : "LEVEL").append(")\n");
        if (in.n == 0) {
            return sb.append("The terrain under the envelope could not be surveyed (unloaded chunks). Treat it as flat ground at y=0.\n").toString();
        }
        int share = (int) Math.round(100.0 * in.n / Math.max(1, in.n + in.unknown));
        sb.append("Inside the envelope (x 0..").append(width - 1).append(", z 0..").append(depth - 1).append("): ground from y=").append(in.min).append(" to y=").append(in.max)
                .append(" (10-90% range ").append(in.p10).append("..").append(in.p90).append(", mean ").append(round1(in.mean)).append(")");
        if (share < 100) sb.append(", ").append(share).append("% surveyed");
        sb.append(".\n");
        sb.append(slope(in)).append('\n');
        sb.append("Water: ").append(pct(in.water, in.n)).append(in.water > 0 ? " (deepest " + in.maxWater + " blocks)" : "")
                .append(". Tree cover: ").append(pct(in.trees, in.n)).append(". Surface: ").append(top(in.surfaces, in.n, 4)).append(".\n");
        sb.append("Biomes: ").append(top(in.biomes, in.n, 4)).append(".\n");
        int relief = in.p90 - in.p10;
        sb.append("Advice: ").append(advice(relief, (double) in.water / in.n, natural)).append('\n');
        String hints = biomeHints(in.biomes.keySet());
        if (!hints.isEmpty()) sb.append(hints);
        sb.append(elevationMap(v, x1, z1, x2, z2, 22, width, depth));
        sb.append("Call survey_terrain for exact heights of any area (1 block resolution).\n");
        return sb.toString();
    }

    private static String slope(Stats s) {
        StringBuilder sb = new StringBuilder("Slope: ");
        boolean any = false;
        if (Math.abs(s.frontBack) >= 1.5) { sb.append("the ground ").append(s.frontBack > 0 ? "rises" : "falls").append(" about ").append(Math.round(Math.abs(s.frontBack) * 2))
                .append(" blocks from the front (low z) to the back (high z)"); any = true; }
        if (Math.abs(s.westEast) >= 1.5) { sb.append(any ? "; " : "").append("it ").append(s.westEast > 0 ? "rises" : "falls").append(" about ").append(Math.round(Math.abs(s.westEast) * 2))
                .append(" blocks from west (low x) to east (high x)"); any = true; }
        if (!any) sb.append("nearly level");
        return sb.append('.').toString();
    }

    private static String advice(int relief, double wet, boolean natural) {
        String mode;
        if (wet > 0.25) mode = "much of the site is water: build piers, a quay, stilt houses or a bridge in NATURAL mode, or in LEVEL mode a raised stone plinth (the server fills foundations down through the water)";
        else if (relief <= 4) mode = "gentle ground: LEVEL mode suits it; the server cuts and fills a pad at y=0 and grades its edges into the land";
        else if (relief <= 12) mode = "noticeable slope (" + relief + " blocks): in LEVEL mode use a terraced plinth, retaining walls and steps; NATURAL mode lets you step the building down the hill or put the low side on stilts";
        else mode = "steep or broken ground (" + relief + " blocks of relief): prefer NATURAL mode and design with the land (terraces, cliff-side halls, towers on the high point, bridges across dips); LEVEL mode would cut or fill a lot of terrain";
        return mode + (natural ? ". In NATURAL mode the terrain stays: only trees and plants in your footprint are cleared, air cells you set carve into it, and blocks resting on the ground count as supported."
                : ". Switch with set_plan terrain:\"natural\" (keep_model:true keeps your work).");
    }

    /** Short design advice for the biomes present. */
    static String biomeHints(Iterable<String> biomes) {
        Map<String, String> hints = new LinkedHashMap<>();
        for (String b : biomes) {
            String s = b.toLowerCase(Locale.ROOT);
            if (s.contains("desert") || s.contains("badlands") || s.contains("savanna"))
                hints.put("arid", "arid land: sandstone, smooth sandstone, terracotta and acacia; flat or low roofs, deep shade, courtyards, palms and cacti; avoid grass and moss");
            if (s.contains("snow") || s.contains("frozen") || s.contains("ice") || s.contains("grove") || s.contains("peaks") || s.contains("jagged"))
                hints.put("cold", "cold land: spruce, stone and deepslate, steep roofs that shed snow, chimneys and warm lighting; snow on the ground and roofs");
            if (s.contains("jungle") || s.contains("bamboo"))
                hints.put("jungle", "jungle: jungle wood, bamboo and mossy stone; raised floors, verandas, broad eaves, vines");
            if (s.contains("swamp") || s.contains("mangrove"))
                hints.put("wet", "wetland: stilt houses and boardwalks of mangrove or dark oak, mud bricks, lily pads; keep floors above the water");
            if (s.contains("ocean") || s.contains("river") || s.contains("beach") || s.contains("shore"))
                hints.put("water", "waterside: piers, docks, sea walls, a lighthouse or boathouse; stone at the waterline, timber above");
            if (s.contains("mountain") || s.contains("hills") || s.contains("windswept") || s.contains("stony") || s.contains("slopes") || s.contains("meadow") || s.contains("cliff"))
                hints.put("high", "highlands: terraces, retaining walls of the local stone, buildings that step with the slope and look out over the view");
            if (s.contains("forest") || s.contains("taiga") || s.contains("birch") || s.contains("cherry") || s.contains("grove"))
                hints.putIfAbsent("forest", "woodland: timber framing in the local wood (" + woodFor(s) + "), keep some trees around the build and frame views through them");
            if (s.contains("mushroom")) hints.put("mushroom", "mushroom fields: whimsical forms, mushroom blocks and mycelium");
            if (s.contains("nether") || s.contains("basalt") || s.contains("crimson") || s.contains("warped") || s.contains("soul"))
                hints.put("nether", "the Nether: blackstone, basalt, nether bricks and the local fungus woods; nothing burns away from lava but avoid wood near it");
            if (s.contains("end")) hints.put("end", "the End: end stone, purpur and obsidian, floating-island silhouettes");
        }
        if (hints.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Fit the place:\n");
        for (String h : hints.values()) sb.append("- ").append(h).append('\n');
        return sb.toString();
    }

    private static String woodFor(String biome) {
        if (biome.contains("taiga") || biome.contains("grove")) return "spruce";
        if (biome.contains("birch")) return "birch";
        if (biome.contains("dark")) return "dark oak";
        if (biome.contains("cherry")) return "cherry";
        return "oak";
    }

    /**
     * Coarse elevation map of a model rectangle: each cell is the ground height (or ~ plus the water surface height)
     * at the cell's centre, relative to y=0. Cells outside the envelope are in brackets so the site edge is visible.
     */
    String elevationMap(View v, int x1, int z1, int x2, int z2, int maxCells, int width, int depth) {
        int step = Math.max(1, (int) Math.ceil(Math.max(x2 - x1 + 1, z2 - z1 + 1) / (double) maxCells));
        StringBuilder sb = new StringBuilder("Elevation map (each cell " + step + "x" + step + " blocks; ground y, ~ = water surface y, T = trees, [ ] = outside the envelope, ? = not surveyed; front z=0 at the top):\n");
        sb.append("  z\\x");
        for (int x = x1; x <= x2; x += step) sb.append(String.format(Locale.ROOT, "%6d", x));
        sb.append('\n');
        for (int z = z1; z <= z2; z += step) {
            sb.append(String.format(Locale.ROOT, "%5d", z));
            for (int x = x1; x <= x2; x += step) {
                int cx = Math.min(x2, x + step / 2), cz = Math.min(z2, z + step / 2);
                int g = v.groundY(cx, cz);
                String cell;
                if (g == UNKNOWN) cell = "?";
                else if (v.waterDepth(cx, cz) > 0) cell = "~" + (g + v.waterDepth(cx, cz));
                else cell = g + (v.canopy(cx, cz) > 2 ? "T" : "");
                boolean outside = cx < 0 || cz < 0 || cx >= width || cz >= depth;
                if (outside) cell = "[" + cell + "]";
                sb.append(String.format(Locale.ROOT, "%6s", cell));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Full-resolution survey of a model rectangle for the survey_terrain tool (at most 48 x 48 cells). */
    public String survey(Placement frame, int x1, int z1, int x2, int z2, int step) {
        View v = view(frame);
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2), az = Math.min(z1, z2), bz = Math.max(z1, z2);
        step = Math.max(step, (int) Math.ceil(Math.max(bx - ax + 1, bz - az + 1) / 48.0));
        Stats s = stats(v, ax, az, bx, bz);
        StringBuilder sb = new StringBuilder("TERRAIN x ").append(ax).append("..").append(bx).append(", z ").append(az).append("..").append(bz)
                .append(" (step ").append(step).append("; model y relative to the ground layer, world y=").append(frame.oy()).append(")\n");
        if (s.n == 0) return sb.append("Not surveyed (unloaded chunks); assume flat ground at y=0.").toString();
        sb.append("ground y ").append(s.min).append("..").append(s.max).append(", water ").append(pct(s.water, s.n)).append(", trees ").append(pct(s.trees, s.n))
                .append(", surface ").append(top(s.surfaces, s.n, 5)).append(", biomes ").append(top(s.biomes, s.n, 3)).append('\n');
        sb.append("Heights (ground y; ~h = water surface at y=h over ground; T = trees above; ? = unknown):\n  z\\x");
        for (int x = ax; x <= bx; x += step) sb.append(String.format(Locale.ROOT, "%6d", x));
        sb.append('\n');
        for (int z = az; z <= bz; z += step) {
            sb.append(String.format(Locale.ROOT, "%5d", z));
            for (int x = ax; x <= bx; x += step) {
                int g = v.groundY(x, z);
                String cell = g == UNKNOWN ? "?" : v.waterDepth(x, z) > 0 ? "~" + (g + v.waterDepth(x, z)) : g + (v.canopy(x, z) > 2 ? "T" : "");
                sb.append(String.format(Locale.ROOT, "%6s", cell));
            }
            sb.append('\n');
        }
        sb.append("A block at y <= ground + 1 in its column rests on (or is embedded in) the ground. Under water, the ground is the lake or sea bed.\n");
        return sb.toString();
    }

    /** The opening site overview: the land around the player before an envelope exists, in world heights. */
    public String overview(Placement frame, int width, int depth) {
        View v = view(frame);
        Stats s = stats(v, 0, 0, width - 1, depth - 1);
        if (s.n == 0) return "Terrain ahead: not surveyed (unloaded chunks).";
        StringBuilder sb = new StringBuilder();
        sb.append("Terrain over a ").append(width).append(" x ").append(depth).append(" area ahead: ground from world y=").append(s.min + frame.oy()).append(" to y=").append(s.max + frame.oy())
                .append(" (10-90% range ").append(s.p10 + frame.oy()).append("..").append(s.p90 + frame.oy()).append("), ").append(pct(s.water, s.n)).append(" water, ")
                .append(pct(s.trees, s.n)).append(" tree cover; surface ").append(top(s.surfaces, s.n, 3)).append("; biomes ").append(top(s.biomes, s.n, 4)).append(".\n");
        sb.append(slope(s)).append('\n');
        String hints = biomeHints(s.biomes.keySet());
        if (!hints.isEmpty()) sb.append(hints);
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------------ renders

    /**
     * The design with the land around it, for render_views only: in level mode the graded pad edges and the natural
     * surface beyond the footprint; in natural mode the top terrain layers and water everywhere the design leaves
     * empty. Terrain cells never count toward the quality report.
     */
    public VoxelModel overlay(VoxelModel design, Placement frame, boolean natural, int margin, int gradeMargin, int maxGrade) {
        VoxelModel out = design.copy();
        out.setEnvelope(0, 0, 0);
        int[] b = design.bounds();
        if (b == null) return out;
        View v = view(frame);
        LongOpenHashSet foot = SiteGrader.footprint(design);
        LongOpenHashSet graded = natural ? new LongOpenHashSet() : SiteGrader.grade(out, v, foot, gradeMargin, maxGrade);
        int floor = b[1] - 12;
        for (int x = b[0] - margin; x <= b[3] + margin; x++) for (int z = b[2] - margin; z <= b[5] + margin; z++) {
            long col = VoxelModel.key(x, 0, z);
            if (!natural && foot.contains(col)) continue; // the levelled pad is the design's own ground layer
            if (graded.contains(col)) continue;           // the grader already shaped this column
            int g = v.groundY(x, z);
            if (g == UNKNOWN || g < floor) continue;
            String sf = v.surface(x, z);
            BlockState top = BlockState.of(SiteGrader.surfaceFor(sf));
            if (out.get(x, g, z) == null) out.set(x, g, z, top);
            if (out.get(x, g - 1, z) == null) out.set(x, g - 1, z, BlockState.of(SiteGrader.subsoilFor(SiteGrader.surfaceFor(sf))));
            int wd = v.waterDepth(x, z);
            if (wd > 0 && out.get(x, g + wd, z) == null) out.set(x, g + wd, z, BlockState.of("water"));
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static String pretty(String biome) { return biome == null ? "?" : biome.replace("minecraft:", "").replace('_', ' '); }

    private static String pct(int a, int n) { return n == 0 ? "0%" : Math.round(100.0 * a / n) + "%"; }

    private static String round1(double v) { return Double.toString(Math.round(v * 10) / 10.0); }

    private static String top(Map<String, Integer> counts, int n, int k) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(k, list.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i).getKey()).append(' ').append(pct(list.get(i).getValue(), n));
        }
        return sb.length() == 0 ? "unknown" : sb.toString();
    }
}
