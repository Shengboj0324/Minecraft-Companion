package io.github.shengboj0324.architect.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Offline knowledge of every block in the targeted Minecraft version (generated from Paper's BlockType registry).
 * Used to classify blocks (stairs, slabs, fences...), derive material families and validate ids without a server.
 */
public final class BlockCatalog {

    public enum Kind { FULL, STAIRS, SLAB, FENCE, PANE, WALL, DOOR, TRAPDOOR, GATE, LEAVES, LOG, GLASS, LIGHT, PLANT, LIQUID, AIR, DECOR }

    private static volatile BlockCatalog instance;

    private final Map<String, String> dataType; // id -> Bukkit BlockData interface name
    private final Set<String> ids;

    private BlockCatalog(Map<String, String> dataType) {
        this.dataType = dataType;
        this.ids = Collections.unmodifiableSet(new TreeSet<>(dataType.keySet()));
    }

    public static BlockCatalog get() {
        BlockCatalog c = instance;
        if (c == null) {
            synchronized (BlockCatalog.class) {
                if (instance == null) instance = load();
                c = instance;
            }
        }
        return c;
    }

    private static BlockCatalog load() {
        Map<String, String> map = new HashMap<>();
        try (InputStream in = BlockCatalog.class.getResourceAsStream("/blocks.tsv")) {
            if (in == null) throw new IllegalStateException("blocks.tsv missing from jar");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                int t = line.indexOf('\t');
                if (t > 0) map.put(line.substring(0, t), line.substring(t + 1).trim());
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read blocks.tsv", e);
        }
        return new BlockCatalog(map);
    }

    public boolean exists(String id) { return dataType.containsKey(id); }

    public Set<String> ids() { return ids; }

    public String dataType(String id) { return dataType.getOrDefault(id, "BlockData"); }

    public Kind kind(String id) {
        if (id.equals("air") || id.equals("cave_air") || id.equals("void_air")) return Kind.AIR;
        String t = dataType(id);
        switch (t) {
            case "Stairs": return Kind.STAIRS;
            case "Slab": return Kind.SLAB;
            case "Wall": return Kind.WALL;
            case "Door": return Kind.DOOR;
            case "TrapDoor": return Kind.TRAPDOOR;
            case "Gate": return Kind.GATE;
            case "Leaves": return Kind.LEAVES;
            case "GlassPane": return Kind.PANE;
            case "Lantern": case "Lightable": case "CopperBulb": case "Candle": return Kind.LIGHT;
            case "Levelled": if (id.equals("water") || id.equals("lava")) return Kind.LIQUID; break;
            case "Fence":
                return (id.endsWith("_pane") || id.endsWith("_bars")) ? Kind.PANE : Kind.FENCE;
            case "Sapling": case "Ageable": case "FlowerBed": return Kind.PLANT;
            default: break;
        }
        if (id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_stem") || id.endsWith("_hyphae") || id.equals("bamboo_block") || id.equals("stripped_bamboo_block")) return Kind.LOG;
        if (id.contains("glass")) return Kind.GLASS;
        if (id.equals("glowstone") || id.equals("sea_lantern") || id.equals("shroomlight") || id.contains("froglight") || id.endsWith("torch") || id.equals("end_rod")) return Kind.LIGHT;
        if (id.endsWith("_carpet") || id.endsWith("_button") || id.endsWith("_pressure_plate") || id.endsWith("_sign") || id.endsWith("_banner")
                || id.endsWith("_head") || id.endsWith("_skull") || id.endsWith("chain") || id.endsWith("_rod") || id.equals("ladder") || id.equals("scaffolding")
                || id.equals("flower_pot") || id.startsWith("potted_") || id.equals("snow") || id.endsWith("_candle") || id.equals("vine") || id.endsWith("_shelf")
                || id.equals("decorated_pot") || id.endsWith("anvil") || id.equals("bell") || id.equals("lectern") || id.equals("cobweb")) return Kind.DECOR;
        if (id.endsWith("_flower") || id.endsWith("_tulip") || id.equals("short_grass") || id.equals("tall_grass") || id.equals("fern") || id.equals("large_fern")
                || id.equals("dandelion") || id.equals("poppy") || id.equals("allium") || id.equals("azure_bluet") || id.equals("oxeye_daisy") || id.equals("cornflower")
                || id.equals("lily_of_the_valley") || id.equals("rose_bush") || id.equals("peony") || id.equals("lilac") || id.equals("sunflower") || id.endsWith("_bush")
                || id.endsWith("_roots") || id.endsWith("_fungus") || id.endsWith("_mushroom") || id.endsWith("_sapling") || id.equals("lily_pad") || id.endsWith("vines")
                || id.equals("sugar_cane") || id.equals("bamboo") || id.endsWith("_propagule") || id.equals("pink_petals") || id.equals("seagrass")) return Kind.PLANT;
        return Kind.FULL;
    }

    /** True for blocks that occupy the full cube and that fences/panes/walls attach to. */
    public boolean isSolidCube(String id) {
        Kind k = kind(id);
        return k == Kind.FULL || k == Kind.LOG || (k == Kind.GLASS && !id.endsWith("_pane")) || k == Kind.LEAVES
                || (k == Kind.LIGHT && (id.equals("glowstone") || id.equals("sea_lantern") || id.equals("shroomlight") || id.contains("froglight") || id.contains("copper_bulb")));
    }

    public boolean isSolidCube(BlockState s) {
        if (s == null) return false;
        if (kind(s.id()) == Kind.SLAB) return "double".equals(s.prop("type"));
        return isSolidCube(s.id());
    }

    // ---------------------------------------------------------------- material families

    /** Strips shape suffixes so "stone_bricks", "stone_brick_stairs" and "stone_brick_slab" share stem "stone_brick". */
    public static String stem(String id) {
        String s = id;
        for (String suf : List.of("_stairs", "_slab", "_wall", "_fence_gate", "_fence", "_planks", "_block")) {
            if (s.endsWith(suf) && s.length() > suf.length()) { s = s.substring(0, s.length() - suf.length()); break; }
        }
        if (s.endsWith("bricks")) s = s.substring(0, s.length() - 1);
        else if (s.endsWith("tiles")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private String firstExisting(String... candidates) {
        for (String c : candidates) if (c != null && exists(c)) return c;
        return null;
    }

    private String woodStem(String id) {
        String s = id;
        if (s.startsWith("stripped_")) s = s.substring("stripped_".length());
        for (String suf : List.of("_log", "_wood", "_stem", "_hyphae", "_leaves")) {
            if (s.endsWith(suf)) return s.substring(0, s.length() - suf.length());
        }
        return null;
    }

    public String stairsOf(String id) {
        if (kind(id) == Kind.STAIRS) return id;
        String st = stem(id);
        String r = firstExisting(st + "_stairs", id + "_stairs");
        if (r == null && woodStem(id) != null) r = firstExisting(woodStem(id) + "_stairs");
        if (r == null && st.startsWith("polished_")) r = firstExisting(st.substring(9) + "_stairs");
        if (r == null && st.startsWith("smooth_")) r = firstExisting(st.substring(7) + "_stairs");
        if (r == null && st.startsWith("chiseled_")) r = firstExisting(st.substring(9) + "_stairs");
        if (r == null && st.startsWith("cracked_")) r = firstExisting(st.substring(8) + "_stairs");
        if (r == null && st.startsWith("mossy_")) r = firstExisting(st + "_stairs");
        return r;
    }

    public String slabOf(String id) {
        if (kind(id) == Kind.SLAB) return id;
        String st = stem(id);
        String r = firstExisting(st + "_slab", id + "_slab");
        if (r == null && woodStem(id) != null) r = firstExisting(woodStem(id) + "_slab");
        if (r == null && st.startsWith("polished_")) r = firstExisting(st.substring(9) + "_slab");
        if (r == null && st.startsWith("chiseled_")) r = firstExisting(st.substring(9) + "_slab");
        if (r == null && st.startsWith("cracked_")) r = firstExisting(st.substring(8) + "_slab");
        return r;
    }

    public String wallOf(String id) {
        if (kind(id) == Kind.WALL) return id;
        String st = stem(id);
        String r = firstExisting(st + "_wall", id + "_wall");
        if (r == null && st.startsWith("polished_")) r = firstExisting(st.substring(9) + "_wall", st.substring(9) + "_brick_wall");
        if (r == null && st.startsWith("chiseled_")) r = firstExisting(st.substring(9) + "_wall");
        if (r == null && st.startsWith("smooth_")) r = firstExisting(st.substring(7) + "_wall");
        if (r == null && st.startsWith("cracked_")) r = firstExisting(st.substring(8) + "_wall");
        return r;
    }

    public String fenceOf(String id) {
        if (kind(id) == Kind.FENCE) return id;
        String st = stem(id);
        String r = firstExisting(st + "_fence");
        if (r == null && woodStem(id) != null) r = firstExisting(woodStem(id) + "_fence");
        return r;
    }

    /** The full-cube member of the family, e.g. spruce_stairs -> spruce_planks, stone_brick_slab -> stone_bricks. */
    public String fullOf(String id) {
        Kind k = kind(id);
        if (k == Kind.FULL || k == Kind.LOG || k == Kind.GLASS) return id;
        String st = stem(id);
        String r = firstExisting(st + "_planks", st + "s", st, st + "_block", st + "_bricks");
        if (r == null && st.equals("petrified_oak")) r = "oak_planks";
        return r;
    }

    public String trapdoorOf(String id) {
        String w = woodStem(id);
        String st = w != null ? w : stem(id);
        return firstExisting(st + "_trapdoor");
    }

    public String doorOf(String id) {
        String w = woodStem(id);
        String st = w != null ? w : stem(id);
        return firstExisting(st + "_door");
    }

    public String logOf(String id) {
        String w = woodStem(id);
        String st = w != null ? w : stem(id);
        return firstExisting(st + "_log", st + "_stem", st + "_block");
    }

    /** Returns a short list of near-miss suggestions for an unknown id (for helpful tool errors). */
    public List<String> suggest(String id, int max) {
        String needle = id.replace("minecraft:", "");
        java.util.List<String> hits = new java.util.ArrayList<>();
        for (String s : ids) if (s.contains(needle) || needle.contains(s)) { hits.add(s); if (hits.size() >= max) return hits; }
        String[] parts = needle.split("_");
        for (String s : ids) {
            int score = 0;
            for (String p : parts) if (p.length() > 2 && s.contains(p)) score++;
            if (score >= Math.max(1, parts.length - 1) && !hits.contains(s)) { hits.add(s); if (hits.size() >= max) break; }
        }
        return hits;
    }
}
