package io.github.shengboj0324.architect.core;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * The camera views Claude uses to look at a build: the four isometric corners, a top-down plan, cutaway slices that
 * show one floor's layout, close-ups of a region, and highlight overlays that paint damage, dark spawnable spots,
 * standing water and loose fragments in signal colours. Each view is rendered to a labelled PNG sheet.
 */
public final class BuildViews {

    private final IsoRenderer iso = new IsoRenderer();

    /** What to paint over the build. */
    public enum Highlight { NONE, DAMAGE, LIGHT, WATER, ALL }

    /**
     * The model with its surroundings (the natural ground surface and the water surface from {@code ex}) and the
     * analysis markers for {@code hl}. Holes show as red glass, dark spots as yellow glass, water inside as blue glass,
     * and loose fragments in magenta.
     */
    public static VoxelModel compose(VoxelModel m, Existing ex, StructureAnalyzer.Report r, Highlight hl) {
        VoxelModel out = m.copy();
        out.setEnvelope(0, 0, 0);
        if (ex != null) {
            for (int x = 0; x < ex.width(); x++) for (int z = 0; z < ex.depth(); z++) {
                String surface = ex.naturalTop(x, z);
                int gy = ex.groundY(x, z);
                if (surface != null && gy != Ground.UNKNOWN && out.get(x, gy, z) == null) out.set(x, gy, z, BlockState.of(surface));
                for (int y = ex.maxY(); y >= ex.minY(); y--) {
                    if (!ex.fluid(x, y, z)) continue;
                    if (out.get(x, y, z) == null) out.set(x, y, z, BlockState.of("water"));
                    break; // only the surface
                }
            }
        }
        if (r != null && hl != Highlight.NONE) {
            if (hl == Highlight.DAMAGE || hl == Highlight.ALL) {
                mark(out, r.holes(), "red_stained_glass");
                for (long k : r.fragments()) out.set(VoxelModel.kx(k), VoxelModel.ky(k), VoxelModel.kz(k), BlockState.of("magenta_concrete"));
            }
            if (hl == Highlight.LIGHT || hl == Highlight.ALL) mark(out, r.dark(), "yellow_stained_glass");
            if (hl == Highlight.WATER || hl == Highlight.ALL || hl == Highlight.DAMAGE) mark(out, r.flooded(), "blue_stained_glass");
        }
        return out;
    }

    private static void mark(VoxelModel out, LongOpenHashSet cells, String block) {
        BlockState s = BlockState.of(block);
        for (long k : cells) {
            int x = VoxelModel.kx(k), y = VoxelModel.ky(k), z = VoxelModel.kz(k);
            BlockState cur = out.get(x, y, z);
            if (cur == null || cur.isAir() || cur.id().equals("water")) out.set(x, y, z, s);
        }
    }

    /**
     * Renders the requested views into one PNG sheet. {@code view}: corners (the four isometric corners), front, back,
     * east, west (single corners), top (plan), or all (corners plus plan). {@code sliceY} cuts everything above that
     * height away (a floor plan in 3D) and adds a top-down plan of the slice.
     */
    public byte[] sheet(VoxelModel view, String what, Integer sliceY, int maxW, int maxH) {
        VoxelModel v = view;
        if (sliceY != null) {
            int[] b = v.bounds();
            if (b != null) v = IsoRenderer.crop(v, b[0], b[1], b[2], b[3], sliceY, b[5]);
        }
        List<BufferedImage> imgs = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        String w = what == null ? "corners" : what.toLowerCase(Locale.ROOT);
        String cut = sliceY == null ? "" : " (cut at y=" + sliceY + ")";
        switch (w) {
            case "front" -> { imgs.add(iso.render(v, 0, maxW, maxH)); labels.add("front (north, z=0) + east" + cut); }
            case "east" -> { imgs.add(iso.render(v, 1, maxW, maxH)); labels.add("east + back (south)" + cut); }
            case "back" -> { imgs.add(iso.render(v, 2, maxW, maxH)); labels.add("back (south) + west" + cut); }
            case "west" -> { imgs.add(iso.render(v, 3, maxW, maxH)); labels.add("west + front (north)" + cut); }
            case "top", "plan" -> { imgs.add(top(v, maxW, maxH)); labels.add("top-down plan" + cut + ", north up"); }
            default -> {
                int cw = maxW / 2, ch = maxH / 2;
                String[] names = {"front (north) + east", "east + back (south)", "back (south) + west", "west + front (north)"};
                for (int i = 0; i < 4; i++) { imgs.add(iso.render(v, i, cw, ch)); labels.add(names[i] + cut); }
                if (w.equals("all") || sliceY != null) { imgs.add(top(v, cw, ch)); labels.add("top-down plan" + cut + ", north up"); }
            }
        }
        return png(grid(imgs, labels, imgs.size() == 1 ? 1 : 2));
    }

    /** Orthographic top-down plan: the top block of every column, shaded by height, with outlines where the height steps. */
    public BufferedImage top(VoxelModel m, int maxW, int maxH) {
        int[] b = m.bounds();
        if (b == null) return new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        int W = b[3] - b[0] + 1, D = b[5] - b[2] + 1;
        int scale = Math.max(1, Math.min(16, Math.min((maxW - 20) / W, (maxH - 20) / D)));
        int[] hy = new int[W * D];
        Color[] col = new Color[W * D];
        java.util.Arrays.fill(hy, Integer.MIN_VALUE);
        for (long k : m.keys()) {
            BlockState s = m.getByKey(k);
            if (s == null || s.isAir()) continue;
            int x = VoxelModel.kx(k) - b[0], y = VoxelModel.ky(k), z = VoxelModel.kz(k) - b[2];
            int i = z * W + x;
            if (y > hy[i]) { hy[i] = y; col[i] = IsoRenderer.colorOf(s.id()); }
        }
        BufferedImage img = new BufferedImage(W * scale + 20, D * scale + 20, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(236, 242, 246));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        double span = Math.max(1, b[4] - b[1]);
        for (int z = 0; z < D; z++) for (int x = 0; x < W; x++) {
            int i = z * W + x;
            if (col[i] == null) continue;
            double t = 0.72 + 0.28 * (hy[i] - b[1]) / span;
            Color c = col[i];
            g.setColor(new Color(clamp(c.getRed() * t), clamp(c.getGreen() * t), clamp(c.getBlue() * t)));
            g.fillRect(10 + x * scale, 10 + z * scale, scale, scale);
            if (scale >= 3) {
                g.setColor(new Color(0, 0, 0, 90));
                if (x + 1 < W && hy[i + 1] != hy[i]) g.drawLine(10 + (x + 1) * scale - 1, 10 + z * scale, 10 + (x + 1) * scale - 1, 10 + (z + 1) * scale - 1);
                if (z + 1 < D && hy[i + W] != hy[i]) g.drawLine(10 + x * scale, 10 + (z + 1) * scale - 1, 10 + (x + 1) * scale - 1, 10 + (z + 1) * scale - 1);
            }
        }
        // a coordinate grid every 8 blocks so Claude can read positions off the plan
        g.setColor(new Color(40, 60, 90, 60));
        g.setStroke(new BasicStroke(1));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        for (int x = Math.floorMod(-b[0], 8); x < W; x += 8) {
            g.drawLine(10 + x * scale, 10, 10 + x * scale, 10 + D * scale);
            g.drawString("x" + (x + b[0]), 11 + x * scale, 9);
        }
        for (int z = Math.floorMod(-b[2], 8); z < D; z += 8) {
            g.drawLine(10, 10 + z * scale, 10 + W * scale, 10 + z * scale);
            g.drawString("z" + (z + b[2]), 0, 20 + z * scale);
        }
        g.dispose();
        return img;
    }

    private static int clamp(double v) { return (int) Math.max(0, Math.min(255, v)); }

    private static BufferedImage grid(List<BufferedImage> imgs, List<String> labels, int cols) {
        int rows = (imgs.size() + cols - 1) / cols;
        int cw = 0, ch = 0;
        for (BufferedImage i : imgs) { cw = Math.max(cw, i.getWidth()); ch = Math.max(ch, i.getHeight()); }
        BufferedImage sheet = new BufferedImage(cw * cols, (ch + 18) * rows, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = sheet.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(236, 242, 246));
        g.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        for (int i = 0; i < imgs.size(); i++) {
            int cx = (i % cols) * cw, cy = (i / cols) * (ch + 18);
            g.setColor(new Color(50, 50, 60));
            g.drawString(labels.get(i), cx + 6, cy + 13);
            g.drawImage(imgs.get(i), cx, cy + 18, null);
        }
        g.dispose();
        return sheet;
    }

    static byte[] png(BufferedImage img) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
