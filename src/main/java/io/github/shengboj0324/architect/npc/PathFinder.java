package io.github.shengboj0324.architect.npc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* over standable block cells, the way a player walks: 8 directions (no corner cutting), step up one block,
 * drop down up to three. Terrain is read through an interface so the search is testable without a server.
 */
public final class PathFinder {

    public interface Terrain {
        /** A block you can stand on. */
        boolean solid(int x, int y, int z);
        /** A cell a player's body can occupy (air, plants, open gates; not lava, not unloaded). */
        boolean passable(int x, int y, int z);
    }

    private PathFinder() { }

    public static boolean standable(Terrain t, int x, int y, int z) {
        return t.solid(x, y - 1, z) && t.passable(x, y, z) && t.passable(x, y + 1, z);
    }

    /** First standable cell at or below (x, y, z), searching down up to {@code depth}; null if none. */
    public static int[] ground(Terrain t, int x, int y, int z, int depth) {
        for (int d = 0; d <= depth; d++) if (standable(t, x, y - d, z)) return new int[]{x, y - d, z};
        for (int d = 1; d <= 3; d++) if (standable(t, x, y + d, z)) return new int[]{x, y + d, z};
        return null;
    }

    private record Node(int x, int y, int z, double g, double f, Node parent) {}

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF)) | ((long) (z & 0x3FFFFFF) << 26) | ((long) ((y + 2048) & 0xFFF) << 52);
    }

    /**
     * Path from start to within {@code reach} blocks (horizontal) and 2 blocks (vertical) of goal, as a list of cells
     * excluding the start. When the goal cannot be reached within {@code maxNodes} expansions, returns the path to the
     * explored cell closest to the goal if that gets meaningfully closer, else null.
     */
    public static List<int[]> find(Terrain t, int[] start, int[] goal, int maxNodes, double reach) {
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Map<Long, Double> best = new HashMap<>();
        Node s = new Node(start[0], start[1], start[2], 0, h(start[0], start[1], start[2], goal), null);
        open.add(s);
        best.put(key(start[0], start[1], start[2]), 0.0);
        Node closest = s;
        double closestH = s.f;
        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            Node n = open.poll();
            if (n.g > best.getOrDefault(key(n.x, n.y, n.z), Double.MAX_VALUE) + 1e-9) continue;
            expanded++;
            double dh = Math.hypot(n.x - goal[0], n.z - goal[2]);
            if (dh <= reach && Math.abs(n.y - goal[1]) <= 2) return unwind(n);
            double hh = h(n.x, n.y, n.z, goal);
            if (hh < closestH) { closestH = hh; closest = n; }
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                boolean diag = dx != 0 && dz != 0;
                int nx = n.x + dx, nz = n.z + dz;
                // step to the same level, up one, or down up to three
                for (int dy : new int[]{0, 1, -1, -2, -3}) {
                    int ny = n.y + dy;
                    if (!standable(t, nx, ny, nz)) continue;
                    if (dy == 1 && !t.passable(n.x, n.y + 2, n.z)) break; // no headroom to jump
                    if (dy < 0) {
                        boolean clear = true;
                        for (int k = 0; k > dy && clear; k--) clear = t.passable(nx, n.y + k, nz) && t.passable(nx, n.y + k + 1, nz);
                        if (!clear) break;
                    }
                    if (diag) {
                        int top = Math.max(n.y, ny);
                        if (!t.passable(n.x + dx, top, n.z) || !t.passable(n.x + dx, top + 1, n.z)
                                || !t.passable(n.x, top, n.z + dz) || !t.passable(n.x, top + 1, n.z + dz)) break;
                    }
                    double g = n.g + (diag ? 1.4142 : 1.0) + (dy > 0 ? 0.6 : dy < 0 ? 0.2 * -dy : 0);
                    long k = key(nx, ny, nz);
                    if (g < best.getOrDefault(k, Double.MAX_VALUE) - 1e-9) {
                        best.put(k, g);
                        open.add(new Node(nx, ny, nz, g, g + h(nx, ny, nz, goal), n));
                    }
                    break; // the first standable height in this column is the one a player would take
                }
            }
        }
        double startH = h(start[0], start[1], start[2], goal);
        if (closest != s && closestH < startH - 4) return unwind(closest);
        return null;
    }

    private static double h(int x, int y, int z, int[] g) {
        double dx = Math.abs(x - g[0]), dz = Math.abs(z - g[2]);
        return Math.max(dx, dz) + 0.4142 * Math.min(dx, dz) + Math.abs(y - g[1]) * 0.5;
    }

    private static List<int[]> unwind(Node n) {
        List<int[]> out = new ArrayList<>();
        while (n != null && n.parent != null) { out.add(new int[]{n.x, n.y, n.z}); n = n.parent; }
        Collections.reverse(out);
        return out;
    }
}
