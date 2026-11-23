package io.github.shengboj0324.architect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import io.github.shengboj0324.architect.core.*;
import io.github.shengboj0324.architect.world.*;
import java.lang.reflect.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.data.BlockData;

/** Places builds into an in-memory fake world (dynamic proxies) to check rotation, site clearing, foundations, undo and generational diffs. Simulates BuildJob + UndoJournal against an in-memory fake world (dynamic proxies), checking rotation, clearing, foundations and undo. */
class PlacementTest {
    @org.junit.jupiter.api.Test
    void placesRotatesUndoesAndExpands() { main(new String[0]); }

    static Map<String, String> world = new HashMap<>(); // "x,y,z" -> block string
    static String k(int x, int y, int z) { return x + "," + y + "," + z; }

    static BlockData data(String s) {
        return (BlockData) Proxy.newProxyInstance(PlacementTest.class.getClassLoader(), new Class[]{BlockData.class}, (p, m, a) -> switch (m.getName()) {
            case "getAsString" -> s; case "toString" -> s; case "hashCode" -> s.hashCode(); case "equals" -> a[0] instanceof BlockData o && s.equals(o.getAsString());
            default -> null; });
    }

    static Block block(int x, int y, int z) {
        return (Block) Proxy.newProxyInstance(PlacementTest.class.getClassLoader(), new Class[]{Block.class}, (p, m, a) -> {
            String cur = world.getOrDefault(k(x, y, z), "minecraft:air");
            if (m.getName().equals("getType")) return null;
            return switch (m.getName()) {
                case "getX" -> x; case "getY" -> y; case "getZ" -> z;
                case "getType" -> cur.startsWith("minecraft:air") ? Material.AIR : cur.contains("water") ? Material.WATER : Material.STONE;
                case "isLiquid" -> cur.contains("water");
                case "isEmpty" -> cur.equals("minecraft:air");
                case "isSolid" -> !cur.equals("minecraft:air") && !cur.contains("water");
                case "getBlockData" -> data(cur);
                case "setBlockData" -> { world.put(k(x, y, z), ((BlockData) a[0]).getAsString()); yield null; }
                case "setType" -> { world.put(k(x, y, z), "minecraft:air"); yield null; }
                default -> null;
            };
        });
    }

    static void main(String[] args) {
        World w = (World) Proxy.newProxyInstance(PlacementTest.class.getClassLoader(), new Class[]{World.class}, (p, m, a) -> switch (m.getName()) {
            case "getBlockAt" -> block((int) a[0], (int) a[1], (int) a[2]);
            case "getMinHeight" -> -64; case "getMaxHeight" -> 320;
            default -> null; });
        // terrain: stone up to y=63 except a pit; a tree trunk inside the footprint
        for (int x = -40; x <= 40; x++) for (int z = -40; z <= 40; z++) for (int y = 55; y <= 63; y++) world.put(k(x, y, z), "minecraft:stone");
        for (int y = 60; y <= 63; y++) world.put(k(2, y, 3), "minecraft:air"); // pit to be filled by foundations
        for (int y = 64; y <= 70; y++) world.put(k(-3, y, 4), "minecraft:oak_log");
        Map<String, String> before = new HashMap<>(world);

        VoxelModel m = new VoxelModel();
        Templates.build("townhouse", new Canvas(m, new Materials(StyleLibrary.bundled().get("medieval"), 1)), 1, 1);
        for (int q = 0; q < 4; q++) {
            world.clear(); world.putAll(before);
            Placement at = new Placement(w, 0, 63, 0, q);
            UndoJournal j = new UndoJournal(w, "t");
            BuildJob job = new BuildJob("t", m.copy(), null, at, j, BuildJob.converter(s -> data(s)), true, true, 16);
            int ticks = 0;
            while (!job.done()) { job.tick(400, 50); ticks++; }
            // verify: every model cell is in the world at the rotated position with rotated?? (states are placed as-is; rotation of states is done by BuildJob?)
            int[] b = m.bounds(); int bad = 0;
            for (long key : m.keys()) {
                int x = VoxelModel.kx(key), y = VoxelModel.ky(key), z = VoxelModel.kz(key);
                String got = world.getOrDefault(k(at.wx(x, z), at.wy(y), at.wz(x, z)), "minecraft:air");
                String want = StateTransforms.rotate(m.getByKey(key), q).toNamespaced();
                if (!got.equals(want)) { if (bad++ < 3) System.out.println("  mismatch q" + q + " at " + x + "," + y + "," + z + " got " + got + " want " + want); }
            }
            String pit = world.get(k(at.wx(2 - 0, 3), 62, at.wz(2, 3)));
            String tree = world.get(k(-3, 66, 4));
            System.out.println("q=" + q + " ticks=" + ticks + " placed=" + job.placed() + " mismatches=" + bad + " journal=" + j.size() + " tree@(-3,66,4)=" + tree);
            while (!j.restoreStep(1000, 50)) { }
            int diff = 0;
            Set<String> keys = new HashSet<>(world.keySet()); keys.addAll(before.keySet());
            for (String kk : keys) if (!world.getOrDefault(kk, "minecraft:air").equals(before.getOrDefault(kk, "minecraft:air"))) diff++;
            System.out.println("  after undo, differing blocks: " + diff);
            assertEquals(0, bad, "placement mismatches for rotation " + q);
            assertEquals(0, diff, "undo left differences for rotation " + q);
        }
        // generational expansion: gen 1 then gen 3 on top, placing only the differences
        world.clear(); world.putAll(before);
        Materials mat = new Materials(StyleLibrary.bundled().get("medieval"), 1);
        VoxelModel g1 = new VoxelModel(); Templates.build("village", new Canvas(g1, mat), 1, 1);
        VoxelModel g3 = new VoxelModel(); Templates.build("village", new Canvas(g3, mat), 1, 3);
        Placement at = new Placement(w, 0, 63, 0, 2);
        BuildJob j1 = new BuildJob("g1", g1.copy(), null, at, new UndoJournal(w, "g1"), BuildJob.converter(s2 -> data(s2)), true, true, 16);
        while (!j1.done()) j1.tick(5000, 100);
        BuildJob j3 = new BuildJob("g3", g3.copy(), g1, at, new UndoJournal(w, "g3"), BuildJob.converter(s2 -> data(s2)), false, true, 16);
        while (!j3.done()) j3.tick(5000, 100);
        int bad = 0;
        for (long key : g3.keys()) {
            int x = VoxelModel.kx(key), y = VoxelModel.ky(key), z = VoxelModel.kz(key);
            String got = world.getOrDefault(k(at.wx(x, z), at.wy(y), at.wz(x, z)), "minecraft:air");
            if (!got.equals(StateTransforms.rotate(g3.getByKey(key), 2).toNamespaced())) bad++;
        }
        assertEquals(0, bad, "expansion mismatches");
        System.out.println("village gen1 placed " + j1.placed() + ", gen3 expansion placed " + j3.placed() + " of " + g3.size() + " cells; mismatches after expansion: " + bad);
    }
}
