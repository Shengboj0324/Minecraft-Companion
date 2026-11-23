package io.github.shengboj0324.architect.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Text views of a model (plan, height map, elevations) so the model can "see" its build without vision. */
public final class AsciiRenderer {

    private final BlockCatalog cat = BlockCatalog.get();

    public static final String LEGEND = "legend: # solid  L log  / stairs  _ slab  | fence/wall  : pane/bars  o glass  D door  t trapdoor  * light  % leaves  ~ liquid  p plant  + decor  . empty";

    public char glyph(BlockState s) {
        if (s == null || s.isAir()) return '.';
        return switch (cat.kind(s.id())) {
            case STAIRS -> '/';
            case SLAB -> '_';
            case FENCE, WALL, GATE -> '|';
            case PANE -> ':';
            case GLASS -> 'o';
            case DOOR -> 'D';
            case TRAPDOOR -> 't';
            case LIGHT -> '*';
            case LEAVES -> '%';
            case LIQUID -> '~';
            case PLANT -> 'p';
            case DECOR -> '+';
            case LOG -> 'L';
            default -> '#';
        };
    }

    public String render(VoxelModel m, int maxCols) {
        int[] b = m.bounds();
        if (b == null) return "(empty)";
        int W = b[3] - b[0] + 1, H = b[4] - b[1] + 1, D = b[5] - b[2] + 1;
        int step = Math.max(1, (int) Math.ceil(Math.max(W, D) / (double) maxCols));
        int vstep = Math.max(1, (int) Math.ceil(H / (double) Math.max(24, maxCols / 2)));
        StringBuilder sb = new StringBuilder();
        sb.append("bounds x ").append(b[0]).append("..").append(b[3]).append(", y ").append(b[1]).append("..").append(b[4])
                .append(", z ").append(b[2]).append("..").append(b[5]);
        if (step > 1 || vstep > 1) sb.append("  (downsampled: 1 char = ").append(step).append(" blocks horizontally, ").append(vstep).append(" vertically)");
        sb.append('\n').append(LEGEND).append("\n\n");

        sb.append("PLAN (top-down; rows = z from ").append(b[2]).append(" (front/north, top) to ").append(b[5]).append("; columns = x from ").append(b[0]).append(")\n");
        for (int z = b[2]; z <= b[5]; z += step) {
            for (int x = b[0]; x <= b[3]; x += step) sb.append(glyph(top(m, b, x, z)));
            sb.append('\n');
        }
        int hs = H > 36 ? (int) Math.ceil(H / 36.0) : 1;
        sb.append("\nHEIGHT MAP (top block y above ground").append(hs > 1 ? " divided by " + hs : "").append(", 0-9 then a-z = 10-35, Z = 36+)\n");
        for (int z = b[2]; z <= b[5]; z += step) {
            for (int x = b[0]; x <= b[3]; x += step) {
                int h = topY(m, b, x, z);
                if (h > 0) h /= hs;
                sb.append(h < 0 ? '.' : h < 10 ? (char) ('0' + h) : h < 36 ? (char) ('a' + h - 10) : 'Z');
            }
            sb.append('\n');
        }
        sb.append("\nFRONT ELEVATION (seen from the north/front, x left->right, top row = y ").append(b[4]).append(")\n");
        for (int y = b[4]; y >= b[1]; y -= vstep) {
            for (int x = b[0]; x <= b[3]; x += step) sb.append(glyph(firstHit(m, b, x, y, true)));
            sb.append('\n');
        }
        sb.append("\nSIDE ELEVATION (seen from the east, z front(left)->back(right))\n");
        for (int y = b[4]; y >= b[1]; y -= vstep) {
            for (int z = b[2]; z <= b[5]; z += step) sb.append(glyph(firstHit(m, b, z, y, false)));
            sb.append('\n');
        }
        return sb.toString();
    }

    /** Horizontal slice at height y (useful to check floor plans and wall continuity). */
    public String slice(VoxelModel m, int y, int maxCols) {
        int[] b = m.bounds();
        if (b == null) return "(empty)";
        int W = b[3] - b[0] + 1, D = b[5] - b[2] + 1;
        int step = Math.max(1, (int) Math.ceil(Math.max(W, D) / (double) maxCols));
        StringBuilder sb = new StringBuilder("SLICE y=" + y + " (rows z " + b[2] + ".." + b[5] + ", cols x " + b[0] + ".." + b[3] + ")\n");
        for (int z = b[2]; z <= b[5]; z += step) {
            for (int x = b[0]; x <= b[3]; x += step) sb.append(glyph(m.get(x, y, z)));
            sb.append('\n');
        }
        return sb.toString();
    }

    public String materialSummary(VoxelModel m, int max) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(m.materialCounts().entrySet());
        list.sort((a, c) -> c.getValue() - a.getValue());
        StringBuilder sb = new StringBuilder("materials: ");
        for (int i = 0; i < Math.min(max, list.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(list.get(i).getKey()).append(" x").append(list.get(i).getValue());
        }
        if (list.size() > max) sb.append(", ... (").append(list.size() - max).append(" more)");
        return sb.toString();
    }

    private BlockState top(VoxelModel m, int[] b, int x, int z) {
        for (int y = b[4]; y >= b[1]; y--) {
            BlockState s = m.get(x, y, z);
            if (s != null && !s.isAir()) return s;
        }
        return null;
    }

    private int topY(VoxelModel m, int[] b, int x, int z) {
        for (int y = b[4]; y >= b[1]; y--) {
            BlockState s = m.get(x, y, z);
            if (s != null && !s.isAir()) return y;
        }
        return -1;
    }

    private BlockState firstHit(VoxelModel m, int[] b, int a, int y, boolean front) {
        if (front) {
            for (int z = b[2]; z <= b[5]; z++) { BlockState s = m.get(a, y, z); if (s != null && !s.isAir()) return s; }
        } else {
            for (int x = b[3]; x >= b[0]; x--) { BlockState s = m.get(x, y, a); if (s != null && !s.isAir()) return s; }
        }
        return null;
    }
}
