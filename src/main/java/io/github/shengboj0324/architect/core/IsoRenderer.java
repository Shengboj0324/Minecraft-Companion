package io.github.shengboj0324.architect.core;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Headless isometric renderer for a {@link VoxelModel}. Used (1) to show Claude what it built so it can critique
 * its own design visually, (2) for offline aesthetic regression tests of the procedural modules.
 * Approximates block shapes (slabs, stairs, posts, panes) and colours from block ids.
 */
public final class IsoRenderer {

    private static final Map<String, Color> COLOR_CACHE = new HashMap<>();
    private final BlockCatalog cat = BlockCatalog.get();

    /**
     * Renders the model seen from above at 45 degrees. view: 0 = from north-east (shows the front z=0 and the east side),
     * 1 = from south-east, 2 = from south-west, 3 = from north-west.
     */
    public BufferedImage render(VoxelModel m, int view, int maxW, int maxH) {
        m = forPreview(m, MAX_PREVIEW_CELLS);
        int[] b = m.bounds();
        if (b == null) return new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        // gather cells in view frame where the camera looks from +X,+Z
        List<int[]> cells = new ArrayList<>();
        List<BlockState> states = new ArrayList<>();
        Map<Long, BlockState> occ = new HashMap<>();
        for (long k : m.keys()) {
            BlockState s = m.getByKey(k);
            if (s == null || s.isAir()) continue;
            int x = VoxelModel.kx(k), y = VoxelModel.ky(k), z = VoxelModel.kz(k);
            int[] t = toView(x, z, view, b);
            BlockState vs = StateTransforms.rotate(s, viewQuarter(view));
            cells.add(new int[]{t[0], y, t[1]});
            states.add(vs);
            occ.put(VoxelModel.key(t[0], y, t[1]), vs);
        }
        // cull cells whose three camera-facing neighbours (+x, +y, +z in the view frame) are opaque cubes
        if (cells.size() > 20_000) {
            List<int[]> keptC = new ArrayList<>(cells.size() / 2);
            List<BlockState> keptS = new ArrayList<>(cells.size() / 2);
            for (int i = 0; i < cells.size(); i++) {
                int[] c = cells.get(i);
                if (opaque(occ.get(VoxelModel.key(c[0] + 1, c[1], c[2]))) && opaque(occ.get(VoxelModel.key(c[0], c[1] + 1, c[2])))
                        && opaque(occ.get(VoxelModel.key(c[0], c[1], c[2] + 1)))) continue;
                keptC.add(c);
                keptS.add(states.get(i));
            }
            cells.clear(); cells.addAll(keptC);
            states.clear(); states.addAll(keptS);
        }
        // projected extents
        double minSx = Double.MAX_VALUE, maxSx = -Double.MAX_VALUE, minSy = Double.MAX_VALUE, maxSy = -Double.MAX_VALUE;
        for (int[] c : cells) {
            double sx0 = (c[0] - c[2] - 1), sx1 = (c[0] + 1 - c[2]);
            double sy0 = (c[0] + c[2]) * 0.5 - (c[1] + 1), sy1 = (c[0] + c[2] + 2) * 0.5 - c[1];
            minSx = Math.min(minSx, sx0); maxSx = Math.max(maxSx, sx1); minSy = Math.min(minSy, sy0); maxSy = Math.max(maxSy, sy1);
        }
        double spanX = maxSx - minSx, spanY = maxSy - minSy;
        double unit = Math.max(1.5, Math.min((maxW - 24) / spanX, (maxH - 24) / spanY));
        unit = Math.min(unit, 28);
        int w = (int) Math.ceil(spanX * unit) + 24, h = (int) Math.ceil(spanY * unit) + 24;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new java.awt.GradientPaint(0, 0, new Color(196, 220, 240), 0, h, new Color(236, 242, 246)));
        g.fillRect(0, 0, w, h);
        double ox = 12 - minSx * unit, oy = 12 - minSy * unit;

        // painter's order: far to near
        Integer[] order = new Integer[cells.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, c) -> {
            int[] p = cells.get(a), q = cells.get(c);
            int da = p[0] + p[2], dc = q[0] + q[2];
            if (da != dc) return Integer.compare(da, dc);
            if (p[1] != q[1]) return Integer.compare(p[1], q[1]);
            return Integer.compare(p[0], q[0]);
        });
        Proj pr = new Proj(ox, oy, unit);
        for (int i : order) {
            int[] c = cells.get(i);
            drawBlock(g, pr, c[0], c[1], c[2], states.get(i), occ);
        }
        g.dispose();
        return img;
    }

    /** Above this many cells previews are downsampled so rendering a mega build stays fast. */
    public static final int MAX_PREVIEW_CELLS = 260_000;

    private boolean opaque(BlockState s) {
        return s != null && cat.isSolidCube(s) && cat.kind(s.id()) != BlockCatalog.Kind.GLASS && cat.kind(s.id()) != BlockCatalog.Kind.LEAVES;
    }

    /** Returns the model itself, or a coarser copy (one cell per k^3 block, preferring solid cubes) when it is very large. */
    public static VoxelModel forPreview(VoxelModel m, int maxCells) {
        long n = m.size();
        if (n <= maxCells) return m;
        int k = (int) Math.ceil(Math.cbrt((double) n / maxCells)) + 1;
        return downsample(m, k);
    }

    public static VoxelModel downsample(VoxelModel m, int k) {
        BlockCatalog cat = BlockCatalog.get();
        VoxelModel out = new VoxelModel();
        for (long key : m.keys()) {
            BlockState s = m.getByKey(key);
            if (s == null || s.isAir()) continue;
            int x = VoxelModel.kx(key), y = VoxelModel.ky(key), z = VoxelModel.kz(key);
            int cx = Math.floorDiv(x, k), cy = Math.floorDiv(y, k), cz = Math.floorDiv(z, k);
            BlockState cur = out.get(cx, cy, cz);
            if (cur == null || (!cat.isSolidCube(cur) && cat.isSolidCube(s))) out.set(cx, cy, cz, cat.isSolidCube(s) ? s : BlockState.of(s.id().contains("glass") ? "light_blue_stained_glass" : cat.fullOf(s.id()) == null ? s.id() : cat.fullOf(s.id())));
        }
        return out;
    }

    /** Copy of the cells inside [x1..x2, y1..y2, z1..z2]. */
    public static VoxelModel crop(VoxelModel m, int x1, int y1, int z1, int x2, int y2, int z2) {
        VoxelModel out = new VoxelModel();
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2), ay = Math.min(y1, y2), by = Math.max(y1, y2), az = Math.min(z1, z2), bz = Math.max(z1, z2);
        for (long key : m.keys()) {
            int x = VoxelModel.kx(key), y = VoxelModel.ky(key), z = VoxelModel.kz(key);
            if (x >= ax && x <= bx && y >= ay && y <= by && z >= az && z <= bz) out.set(x, y, z, m.getByKey(key));
        }
        return out;
    }

    public byte[] renderPng(VoxelModel m, int view, int maxW, int maxH) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(render(m, view, maxW, maxH), "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Two opposite views side by side (front-east and back-west) — the image Claude receives for critique. */
    public byte[] renderSheetPng(VoxelModel m, int maxW, int maxH) {
        BufferedImage a = render(m, 0, maxW / 2, maxH), c = render(m, 2, maxW / 2, maxH);
        BufferedImage sheet = new BufferedImage(a.getWidth() + c.getWidth(), Math.max(a.getHeight(), c.getHeight()), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setColor(new Color(236, 242, 246));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g.drawImage(a, 0, 0, null);
        g.drawImage(c, a.getWidth(), 0, null);
        g.setColor(new Color(60, 60, 60));
        g.drawString("front (z=0) + east side", 8, 14);
        g.drawString("back + west side", a.getWidth() + 8, 14);
        g.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(sheet, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static int viewQuarter(int view) {
        // rotate model so the requested corner faces the camera at +X,+Z
        return switch (view & 3) { case 0 -> 1; case 1 -> 0; case 2 -> 3; default -> 2; };
    }

    private static int[] toView(int x, int z, int view, int[] b) {
        int q = viewQuarter(view);
        int rx = x, rz = z;
        for (int i = 0; i < q; i++) { int t = rx; rx = -rz; rz = t; }
        return new int[]{rx, rz};
    }

    private record Proj(double ox, double oy, double u) {
        int sx(double x, double y, double z) { return (int) Math.round(ox + (x - z) * u); }
        int sy(double x, double y, double z) { return (int) Math.round(oy + ((x + z) * 0.5 - y) * u); }
    }

    private void drawBlock(Graphics2D g, Proj p, int x, int y, int z, BlockState s, Map<Long, BlockState> occ) {
        BlockCatalog.Kind k = cat.kind(s.id());
        Color base = colorOf(s.id());
        float alpha = (k == BlockCatalog.Kind.GLASS || k == BlockCatalog.Kind.PANE && s.id().contains("glass")) ? 0.55f : 1f;
        switch (k) {
            case SLAB -> {
                String t = s.prop("type");
                if ("double".equals(t)) box(g, p, x, y, z, 0, 0, 0, 1, 1, 1, base, alpha, occ, true);
                else if ("top".equals(t)) box(g, p, x, y, z, 0, .5, 0, 1, 1, 1, base, alpha, occ, false);
                else box(g, p, x, y, z, 0, 0, 0, 1, .5, 1, base, alpha, occ, false);
            }
            case STAIRS -> {
                boolean top = "top".equals(s.prop("half"));
                Dir f = Dir.parse(s.prop("facing"));
                if (top) box(g, p, x, y, z, 0, .5, 0, 1, 1, 1, base, alpha, occ, false);
                else box(g, p, x, y, z, 0, 0, 0, 1, .5, 1, base, alpha, occ, false);
                double[] hb = halfBox(f);
                if (top) box(g, p, x, y, z, hb[0], 0, hb[1], hb[2], .5, hb[3], base, alpha, occ, false);
                else box(g, p, x, y, z, hb[0], .5, hb[1], hb[2], 1, hb[3], base, alpha, occ, false);
            }
            case FENCE, WALL, PANE -> {
                double r = k == BlockCatalog.Kind.WALL ? .25 : k == BlockCatalog.Kind.PANE ? .06 : .125;
                double hTop = k == BlockCatalog.Kind.WALL ? .85 : 1;
                box(g, p, x, y, z, .5 - r, 0, .5 - r, .5 + r, k == BlockCatalog.Kind.WALL && !"true".equals(s.prop("up")) ? hTop : 1, .5 + r, base, alpha, occ, false);
                double ar = k == BlockCatalog.Kind.PANE ? .06 : k == BlockCatalog.Kind.WALL ? .19 : .07;
                double ay0 = k == BlockCatalog.Kind.PANE ? 0 : k == BlockCatalog.Kind.WALL ? 0 : .4, ay1 = k == BlockCatalog.Kind.PANE ? 1 : k == BlockCatalog.Kind.WALL ? hTop : .85;
                for (Dir d : Dir.values()) {
                    String v = s.prop(d.key());
                    if (v == null || v.equals("false") || v.equals("none")) continue;
                    double x0 = .5 - ar, x1 = .5 + ar, z0 = .5 - ar, z1 = .5 + ar;
                    switch (d) { case NORTH -> z0 = 0; case SOUTH -> z1 = 1; case WEST -> x0 = 0; case EAST -> x1 = 1; }
                    box(g, p, x, y, z, x0, ay0, z0, x1, ay1, z1, base, alpha, occ, false);
                }
            }
            case DOOR, TRAPDOOR -> {
                Dir f = Dir.parse(s.prop("facing"));
                if (k == BlockCatalog.Kind.TRAPDOOR && !"true".equals(s.prop("open"))) {
                    boolean top = "top".equals(s.prop("half"));
                    box(g, p, x, y, z, 0, top ? .82 : 0, 0, 1, top ? 1 : .18, 1, base, alpha, occ, false);
                } else {
                    double[] t = thin(f == null ? Dir.NORTH : f);
                    box(g, p, x, y, z, t[0], 0, t[1], t[2], 1, t[3], base, alpha, occ, false);
                }
            }
            case LIGHT -> {
                if (cat.isSolidCube(s.id())) box(g, p, x, y, z, 0, 0, 0, 1, 1, 1, base, 1f, occ, true);
                else box(g, p, x, y, z, .3, 0, .3, .7, .55, .7, base, 1f, occ, false);
            }
            case PLANT -> box(g, p, x, y, z, .3, 0, .3, .7, .6, .7, base, 1f, occ, false);
            case DECOR -> {
                if (s.id().endsWith("_carpet") || s.id().endsWith("_pressure_plate")) box(g, p, x, y, z, 0, 0, 0, 1, .07, 1, base, 1f, occ, false);
                else if (s.id().endsWith("chain") || s.id().endsWith("_rod")) box(g, p, x, y, z, .45, 0, .45, .55, 1, .55, base, 1f, occ, false);
                else box(g, p, x, y, z, .2, 0, .2, .8, .7, .8, base, 1f, occ, false);
            }
            default -> box(g, p, x, y, z, 0, 0, 0, 1, 1, 1, base, alpha, occ, true);
        }
    }

    private static double[] halfBox(Dir f) {
        if (f == null) f = Dir.NORTH;
        return switch (f) {
            case NORTH -> new double[]{0, 0, 1, .5};
            case SOUTH -> new double[]{0, .5, 1, 1};
            case WEST -> new double[]{0, 0, .5, 1};
            case EAST -> new double[]{.5, 0, 1, 1};
        };
    }

    private static double[] thin(Dir f) {
        return switch (f) {
            case NORTH -> new double[]{0, .82, 1, 1};
            case SOUTH -> new double[]{0, 0, 1, .18};
            case WEST -> new double[]{.82, 0, 1, 1};
            case EAST -> new double[]{0, 0, .18, 1};
        };
    }

    private boolean occludes(Map<Long, BlockState> occ, int x, int y, int z) {
        BlockState s = occ.get(VoxelModel.key(x, y, z));
        return s != null && cat.isSolidCube(s) && cat.kind(s.id()) != BlockCatalog.Kind.GLASS && cat.kind(s.id()) != BlockCatalog.Kind.LEAVES;
    }

    private void box(Graphics2D g, Proj p, int bx, int by, int bz, double x0, double y0, double z0, double x1, double y1, double z1,
                     Color c, float alpha, Map<Long, BlockState> occ, boolean cull) {
        double X0 = bx + x0, X1 = bx + x1, Y0 = by + y0, Y1 = by + y1, Z0 = bz + z0, Z1 = bz + z1;
        if (alpha < 1f) g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
        boolean drawTop = !cull || y1 < 1 || !occludes(occ, bx, by + 1, bz);
        boolean drawEast = !cull || x1 < 1 || !occludes(occ, bx + 1, by, bz);
        boolean drawSouth = !cull || z1 < 1 || !occludes(occ, bx, by, bz + 1);
        if (drawEast) face(g, p, c, 0.78, new double[][]{{X1, Y0, Z0}, {X1, Y1, Z0}, {X1, Y1, Z1}, {X1, Y0, Z1}});
        if (drawSouth) face(g, p, c, 0.62, new double[][]{{X0, Y0, Z1}, {X1, Y0, Z1}, {X1, Y1, Z1}, {X0, Y1, Z1}});
        if (drawTop) face(g, p, c, 1.0, new double[][]{{X0, Y1, Z0}, {X1, Y1, Z0}, {X1, Y1, Z1}, {X0, Y1, Z1}});
        if (alpha < 1f) g.setComposite(AlphaComposite.SrcOver);
    }

    private static void face(Graphics2D g, Proj p, Color c, double shade, double[][] pts) {
        Polygon poly = new Polygon();
        for (double[] q : pts) poly.addPoint(p.sx(q[0], q[1], q[2]), p.sy(q[0], q[1], q[2]));
        g.setColor(new Color(clamp(c.getRed() * shade), clamp(c.getGreen() * shade), clamp(c.getBlue() * shade)));
        g.fillPolygon(poly);
        if (p.u() >= 5) {
            g.setStroke(new BasicStroke((float) Math.max(0.6, p.u() / 14)));
            g.setColor(new Color(clamp(c.getRed() * shade * 0.8), clamp(c.getGreen() * shade * 0.8), clamp(c.getBlue() * shade * 0.8)));
            g.drawPolygon(poly);
        }
    }

    private static int clamp(double v) { return (int) Math.max(0, Math.min(255, v)); }

    // ---------------------------------------------------------------- colours

    private static final String[][] DYES = {
            {"light_blue", "102,170,220"}, {"light_gray", "150,150,145"}, {"white", "232,233,233"}, {"orange", "232,118,30"},
            {"magenta", "185,70,175"}, {"yellow", "245,195,45"}, {"lime", "110,185,30"}, {"pink", "235,140,170"},
            {"gray", "70,75,80"}, {"cyan", "22,135,145"}, {"purple", "120,45,170"}, {"blue", "50,58,155"},
            {"brown", "110,72,42"}, {"green", "80,105,35"}, {"red", "155,38,32"}, {"black", "25,25,30"}};

    private static final String[][] MATS = {
            {"dark_prismarine", "55,95,80"}, {"prismarine", "95,160,145"}, {"red_sandstone", "185,98,40"}, {"red_nether", "90,20,20"},
            {"nether_brick", "48,24,28"}, {"stone_brick", "122,121,122"}, {"mud_brick", "140,104,78"}, {"end_stone", "220,222,160"},
            {"mossy", "102,122,88"}, {"cobbled_deepslate", "78,78,82"}, {"deepslate", "72,72,78"}, {"blackstone", "48,42,50"},
            {"cobblestone", "118,118,118"}, {"smooth_stone", "160,160,160"}, {"andesite", "136,136,137"}, {"diorite", "205,205,205"},
            {"granite", "152,106,88"}, {"calcite", "224,225,221"}, {"tuff", "108,110,100"}, {"basalt", "78,78,84"},
            {"sandstone", "218,204,154"}, {"quartz", "236,231,224"}, {"purpur", "170,126,170"}, {"bricks", "150,86,70"},
            {"brick", "150,86,70"}, {"terracotta", "152,94,67"}, {"oxidized", "82,162,132"}, {"weathered", "105,150,110"},
            {"exposed", "160,120,95"}, {"copper", "192,108,80"}, {"gold", "245,205,60"}, {"iron_bars", "120,120,120"},
            {"iron", "220,220,220"}, {"obsidian", "22,16,32"}, {"amethyst", "140,100,190"}, {"glowstone", "240,205,120"},
            {"sea_lantern", "190,222,212"}, {"froglight", "240,220,170"}, {"shroomlight", "240,150,70"}, {"lantern", "245,180,90"},
            {"torch", "250,200,90"}, {"water", "60,92,200"}, {"lava", "220,90,20"}, {"grass_block", "96,152,62"},
            {"dirt_path", "150,124,70"}, {"coarse_dirt", "118,86,60"}, {"dirt", "130,95,65"}, {"gravel", "132,127,124"},
            {"sand", "220,210,160"}, {"snow", "245,250,250"}, {"hay", "190,160,40"}, {"moss", "90,120,45"}, {"mud", "60,57,60"},
            {"bookshelf", "140,105,65"}, {"stone", "126,126,126"}, {"chain", "60,65,75"}, {"bamboo", "195,175,85"}};

    private static final String[][] LEAVES = {
            {"cherry", "232,165,190"}, {"flowering_azalea", "125,135,85"}, {"azalea", "92,122,48"}, {"spruce", "50,82,52"},
            {"birch", "100,132,62"}, {"dark_oak", "42,82,30"}, {"jungle", "55,125,30"}, {"acacia", "85,112,32"},
            {"mangrove", "65,105,42"}, {"pale_oak", "160,165,150"}, {"oak", "62,112,42"}};

    private static final String[][] WOODS = {
            {"dark_oak", "68,46,24"}, {"pale_oak", "228,222,212"}, {"spruce", "116,86,52"}, {"birch", "196,180,124"}, {"jungle", "160,116,80"},
            {"acacia", "170,92,52"}, {"mangrove", "118,52,46"}, {"cherry", "226,176,166"}, {"crimson", "102,50,72"},
            {"warped", "46,106,100"}, {"oak", "164,134,84"}};

    private static final String[][] BARK = {
            {"dark_oak", "58,42,26"}, {"pale_oak", "90,80,75"}, {"spruce", "60,40,24"}, {"birch", "212,212,204"}, {"jungle", "88,68,28"},
            {"acacia", "104,98,90"}, {"mangrove", "84,66,40"}, {"cherry", "54,30,40"}, {"crimson", "90,26,40"},
            {"warped", "58,58,78"}, {"oak", "102,80,50"}};

    public static Color colorOf(String id) {
        synchronized (COLOR_CACHE) {
            return COLOR_CACHE.computeIfAbsent(id, IsoRenderer::computeColor);
        }
    }

    /** Average colours of real block textures, when an admin supplied a resource pack or client jar (see loadTextures). */
    private static final Map<String, Color> TEXTURES = new java.util.concurrent.ConcurrentHashMap<>();
    /** Textures the game tints by biome (they are grey in the pack), so the built-in colours stay. */
    private static final java.util.Set<String> TINTED = java.util.Set.of("grass_block", "short_grass", "tall_grass", "fern", "large_fern", "vine", "lily_pad",
            "water", "bubble_column", "sugar_cane", "oak_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves", "mangrove_leaves", "birch_leaves", "spruce_leaves");

    /**
     * Reads the average colour of every block texture (assets/minecraft/textures/block/*.png) from a resource pack zip
     * or a Minecraft client jar, so renders use the real colours of every block. Returns how many were read.
     */
    public static int loadTextures(java.nio.file.Path pack) throws IOException {
        int n = 0;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(pack.toFile())) {
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                java.util.zip.ZipEntry e = en.nextElement();
                String name = e.getName();
                if (!name.startsWith("assets/minecraft/textures/block/") || !name.endsWith(".png") || name.indexOf('/', 32) >= 0) continue;
                String key = name.substring(32, name.length() - 4);
                try (java.io.InputStream in = zip.getInputStream(e)) {
                    BufferedImage img = ImageIO.read(in);
                    if (img == null) continue;
                    long r = 0, g = 0, b = 0, w = 0;
                    int h = Math.min(img.getHeight(), img.getWidth()); // animated textures stack frames vertically
                    for (int y = 0; y < h; y++) for (int x = 0; x < img.getWidth(); x++) {
                        int argb = img.getRGB(x, y), al = argb >>> 24;
                        if (al < 16) continue;
                        r += (long) ((argb >> 16) & 255) * al; g += (long) ((argb >> 8) & 255) * al; b += (long) (argb & 255) * al; w += al;
                    }
                    if (w > 0) { TEXTURES.put(key, new Color((int) (r / w), (int) (g / w), (int) (b / w))); n++; }
                }
            }
        }
        synchronized (COLOR_CACHE) { COLOR_CACHE.clear(); }
        return n;
    }

    private static Color textureColor(String id) {
        if (TEXTURES.isEmpty() || TINTED.contains(id)) return null;
        String[] tries = {id, id + "_side", id + "_front", id + "_top"};
        for (String t : tries) { Color c = TEXTURES.get(t); if (c != null) return c; }
        String full = BlockCatalog.get().fullOf(id);
        if (full != null && !full.equals(id)) {
            Color c = textureColor(full);
            if (c != null) return c;
        }
        String base = id.replaceAll("_(stairs|slab|wall|fence|fence_gate|pressure_plate|button|pane|carpet|trapdoor|door)$", "");
        if (!base.equals(id)) {
            for (String t : new String[]{base, base + "s", base + "_planks", base + "_block", base.replace("_brick", "_bricks"), base.replace("_tile", "_tiles")}) {
                Color c = TEXTURES.get(t);
                if (c != null) return c;
            }
        }
        return null;
    }

    private static Color computeColor(String id) {
        Color tex = textureColor(id);
        if (tex != null) return tex;
        if (id.endsWith("_leaves")) {
            for (String[] e : LEAVES) if (id.startsWith(e[0])) return rgb(e[1]);
            return rgb("62,112,42");
        }
        boolean log = id.endsWith("_log") || id.endsWith("_wood");
        boolean stripped = id.startsWith("stripped_");
        boolean woodish = id.contains("planks") || id.endsWith("_stairs") || id.endsWith("_slab") || id.endsWith("_fence") || id.endsWith("_door")
                || id.endsWith("_trapdoor") || id.endsWith("_fence_gate") || log;
        if (woodish) {
            for (String[] e : WOODS) {
                if (id.startsWith(e[0]) || id.startsWith("stripped_" + e[0])) {
                    if (log && !stripped) for (String[] bk : BARK) if (bk[0].equals(e[0])) return rgb(bk[1]);
                    return rgb(e[1]);
                }
            }
        }
        boolean terracotta = id.contains("terracotta");
        boolean glass = id.contains("glass");
        for (String[] d : DYES) {
            if (id.startsWith(d[0] + "_")) {
                Color c = rgb(d[1]);
                if (terracotta) c = new Color((int) (c.getRed() * 0.62 + 60), (int) (c.getGreen() * 0.55 + 40), (int) (c.getBlue() * 0.5 + 30));
                if (glass) c = new Color(Math.min(255, c.getRed() + 30), Math.min(255, c.getGreen() + 30), Math.min(255, c.getBlue() + 30));
                return c;
            }
        }
        if (glass) return rgb("200,226,236");
        for (String[] e : MATS) if (id.contains(e[0])) return rgb(e[1]);
        for (String[] e : WOODS) if (id.startsWith(e[0])) return rgb(e[1]);
        int h = id.hashCode();
        int v = 110 + Math.floorMod(h, 60);
        return new Color(v, v, v);
    }

    private static Color rgb(String s) {
        String[] p = s.split(",");
        return new Color(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
    }
}
