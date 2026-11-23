package io.github.shengboj0324.architect.core;

import java.util.ArrayDeque;
import java.util.Map;

/**
 * Block light the way the game computes it: every light source emits its level, which drops by one per block
 * through non-opaque cells. Since 1.18 hostile mobs spawn only where the block light is 0, so a renovation that lifts
 * every walkable spot above 0 (and interiors to a comfortable 8 or more) stops mob spawning inside a base.
 */
public final class Lighting {

    private Lighting() { }

    private static final Map<String, Integer> EMIT = Map.ofEntries(
            Map.entry("lantern", 15), Map.entry("soul_lantern", 10), Map.entry("torch", 14), Map.entry("wall_torch", 14),
            Map.entry("soul_torch", 10), Map.entry("soul_wall_torch", 10), Map.entry("redstone_torch", 7), Map.entry("glowstone", 15),
            Map.entry("sea_lantern", 15), Map.entry("shroomlight", 15), Map.entry("jack_o_lantern", 15), Map.entry("end_rod", 14),
            Map.entry("campfire", 15), Map.entry("soul_campfire", 10), Map.entry("beacon", 15), Map.entry("conduit", 15),
            Map.entry("ochre_froglight", 15), Map.entry("verdant_froglight", 15), Map.entry("pearlescent_froglight", 15),
            Map.entry("crying_obsidian", 10), Map.entry("magma_block", 3), Map.entry("glow_lichen", 7), Map.entry("lava", 15),
            Map.entry("fire", 15), Map.entry("soul_fire", 10), Map.entry("brewing_stand", 1), Map.entry("enchanting_table", 7),
            Map.entry("ender_chest", 7), Map.entry("respawn_anchor", 15), Map.entry("copper_bulb", 15), Map.entry("copper_lantern", 15),
            Map.entry("copper_torch", 14), Map.entry("copper_wall_torch", 14), Map.entry("light", 15), Map.entry("sculk_catalyst", 6),
            Map.entry("amethyst_cluster", 5), Map.entry("glow_berries", 14), Map.entry("cave_vines", 14), Map.entry("cave_vines_plant", 14),
            Map.entry("firefly_bush", 2), Map.entry("creaking_heart", 0));

    /** Light a block emits (0 for most). Candles and sea pickles depend on their count, lamps on being lit. */
    public static int emission(BlockState s) {
        if (s == null || s.isAir()) return 0;
        String id = s.id();
        Integer e = EMIT.get(id);
        if (e != null) {
            if ((id.equals("campfire") || id.equals("soul_campfire")) && "false".equals(s.prop("lit"))) return 0;
            if ((id.equals("cave_vines") || id.equals("cave_vines_plant")) && !"true".equals(s.prop("berries"))) return 0;
            return e;
        }
        if (id.endsWith("copper_bulb") || id.endsWith("copper_lantern")) return "false".equals(s.prop("lit")) ? 0 : 12;
        if (id.endsWith("copper_torch") || id.endsWith("copper_wall_torch")) return 14;
        if (id.equals("redstone_lamp")) return "true".equals(s.prop("lit")) ? 15 : 0;
        if (id.equals("sea_pickle")) return "false".equals(s.prop("waterlogged")) ? 0 : 3 + 3 * Math.max(0, parse(s.prop("pickles"), 1) - 1);
        if (id.endsWith("candle")) return "true".equals(s.prop("lit")) ? 3 * Math.max(1, parse(s.prop("candles"), 1)) : 0;
        return 0;
    }

    /** Does the block stop light (an opaque full cube)? */
    public static boolean opaque(BlockCatalog cat, BlockState s) {
        if (s == null || s.isAir()) return false;
        BlockCatalog.Kind k = cat.kind(s.id());
        if (k == BlockCatalog.Kind.GLASS || k == BlockCatalog.Kind.LEAVES || k == BlockCatalog.Kind.LIQUID) return false;
        return cat.isSolidCube(s) && emission(s) == 0;
    }

    private static int parse(String v, int def) {
        try { return v == null ? def : Integer.parseInt(v); } catch (NumberFormatException e) { return def; }
    }

    /** A box of cells with their block light, indexed (x - x0) + (z - z0) * w + (y - y0) * w * d. */
    public static final class Field {
        public final int x0, y0, z0, w, h, d;
        public final byte[] light;

        Field(int x0, int y0, int z0, int w, int h, int d) {
            this.x0 = x0; this.y0 = y0; this.z0 = z0; this.w = w; this.h = h; this.d = d;
            this.light = new byte[w * h * d];
        }

        public boolean inside(int x, int y, int z) { return x >= x0 && y >= y0 && z >= z0 && x < x0 + w && y < y0 + h && z < z0 + d; }
        public int index(int x, int y, int z) { return (x - x0) + (z - z0) * w + (y - y0) * w * d; }
        public int at(int x, int y, int z) { return inside(x, y, z) ? light[index(x, y, z)] : 0; }
    }

    /**
     * Block light over the box [x1..x2, y1..y2, z1..z2] (sources up to 15 blocks outside the box are included so the
     * edges are right).
     */
    public static Field compute(VoxelModel m, int x1, int y1, int z1, int x2, int y2, int z2) {
        BlockCatalog cat = BlockCatalog.get();
        int pad = 15;
        Field f = new Field(x1 - pad, y1 - pad, z1 - pad, x2 - x1 + 1 + 2 * pad, y2 - y1 + 1 + 2 * pad, z2 - z1 + 1 + 2 * pad);
        boolean[] block = new boolean[f.light.length];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (long k : m.keys()) {
            int x = VoxelModel.kx(k), y = VoxelModel.ky(k), z = VoxelModel.kz(k);
            if (!f.inside(x, y, z)) continue;
            BlockState s = m.getByKey(k);
            int i = f.index(x, y, z);
            if (opaque(cat, s)) block[i] = true;
            int e = emission(s);
            if (e > 0) { f.light[i] = (byte) e; q.add(i); }
        }
        int wd = f.w * f.d;
        while (!q.isEmpty()) {
            int i = q.poll();
            int l = f.light[i];
            if (l <= 1) continue;
            int x = i % f.w, z = (i / f.w) % f.d, y = i / wd;
            int[] nb = {x > 0 ? i - 1 : -1, x < f.w - 1 ? i + 1 : -1, z > 0 ? i - f.w : -1, z < f.d - 1 ? i + f.w : -1, y > 0 ? i - wd : -1, y < f.h - 1 ? i + wd : -1};
            for (int n : nb) {
                if (n < 0 || block[n] || f.light[n] >= l - 1) continue;
                f.light[n] = (byte) (l - 1);
                q.add(n);
            }
        }
        return f;
    }
}
