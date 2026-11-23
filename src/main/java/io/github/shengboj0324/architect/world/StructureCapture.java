package io.github.shengboj0324.architect.world;

import io.github.shengboj0324.architect.core.BlockState;
import io.github.shengboj0324.architect.core.Existing;
import io.github.shengboj0324.architect.core.VoxelModel;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

/**
 * Captures an existing build from the world into a voxel model, for renovation. Chunks are snapshotted on the main
 * thread a few per tick (unloaded ones are loaded asynchronously first), and everything else runs off the main
 * thread on the snapshots: finding the structure (a flood fill from the targeted block through connected building
 * blocks, bridging small gaps such as blast damage, and ignoring water, terrain and vegetation) or taking a selected
 * box, then reading every cell into the model and the {@link Existing} context. Blocks with contents (containers,
 * signs, banners, spawners, ...) and blocks carrying item frames or paintings are marked protected.
 */
public final class StructureCapture {

    /** Limits for one capture. */
    public record Limits(int maxRadius, int maxHeight, int maxBlocks, int chunksPerTick, int margin) {}

    /** The captured build. Model coordinates are world coordinates minus (ox, oy, oz). */
    public record Result(World world, int ox, int oy, int oz, VoxelModel model, VoxelModel snapshot, Existing existing,
                         int[] box, int structureBlocks, int foreignBlocks, boolean truncated) {
        /** World bounding box of the captured structure {x1,y1,z1,x2,y2,z2}. */
        public int[] worldBox() { return box; }
    }

    private StructureCapture() { }

    /** Captures the build around {@code seed} (auto-detected). Calls back on the main thread. */
    public static void detect(Plugin plugin, Block seed, Limits lim, Consumer<Result> done, Consumer<String> failed) {
        detect(plugin, seed, lim, 4, done, failed);
    }

    /** As {@link #detect}, looking up to {@code search} blocks around the seed for the build (admin tools aim from the console). */
    public static void detect(Plugin plugin, Block seed, Limits lim, int search, Consumer<Result> done, Consumer<String> failed) {
        World w = seed.getWorld();
        int x1 = seed.getX() - lim.maxRadius(), x2 = seed.getX() + lim.maxRadius(), z1 = seed.getZ() - lim.maxRadius(), z2 = seed.getZ() + lim.maxRadius();
        int y1 = Math.max(w.getMinHeight(), seed.getY() - lim.maxHeight()), y2 = Math.min(w.getMaxHeight() - 1, seed.getY() + lim.maxHeight());
        snapshot(plugin, w, x1, z1, x2, z2, lim.chunksPerTick(), snaps -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Source src = new Source(snaps, w.getMinHeight(), w.getMaxHeight());
                int[] s = nearestStructure(src, seed.getX(), seed.getY(), seed.getZ(), search);
                if (s == null) { sync(plugin, () -> failed.accept("There is no build at the spot you are looking at.")); return; }
                Flood f = flood(src, s, x1, y1, z1, x2, y2, z2, lim.maxBlocks());
                Result r = read(w, src, f.box, f.members, lim.margin(), f.truncated);
                sync(plugin, () -> done.accept(protectEntities(r)));
            } catch (RuntimeException e) {
                sync(plugin, () -> failed.accept("Reading the build failed: " + e.getMessage()));
            }
        }));
    }

    /** Captures everything in a selected world box. Calls back on the main thread. */
    public static void select(Plugin plugin, World w, int[] box, Limits lim, Consumer<Result> done, Consumer<String> failed) {
        int x1 = Math.min(box[0], box[3]), y1 = Math.max(w.getMinHeight(), Math.min(box[1], box[4])), z1 = Math.min(box[2], box[5]);
        int x2 = Math.max(box[0], box[3]), y2 = Math.min(w.getMaxHeight() - 1, Math.max(box[1], box[4])), z2 = Math.max(box[2], box[5]);
        long vol = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
        if (x2 - x1 > 2 * lim.maxRadius() || z2 - z1 > 2 * lim.maxRadius() || vol > 200_000_000L) { failed.accept("That selection is larger than this server allows."); return; }
        snapshot(plugin, w, x1 - 1, z1 - 1, x2 + 1, z2 + 1, lim.chunksPerTick(), snaps -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Source src = new Source(snaps, w.getMinHeight(), w.getMaxHeight());
                Result r = read(w, src, new int[]{x1, y1, z1, x2, y2, z2}, null, 0, false);
                if (r.structureBlocks() > lim.maxBlocks()) { sync(plugin, () -> failed.accept("That selection holds " + r.structureBlocks() + " blocks, more than this server allows (" + lim.maxBlocks() + ").")); return; }
                sync(plugin, () -> done.accept(protectEntities(r)));
            } catch (RuntimeException e) {
                sync(plugin, () -> failed.accept("Reading the selection failed: " + e.getMessage()));
            }
        }));
    }

    private static void sync(Plugin plugin, Runnable r) { if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, r); }

    // ------------------------------------------------------------------------------------------------ snapshots

    /** Snapshots every chunk over the block rectangle, a few per tick, loading missing chunks asynchronously. */
    static void snapshot(Plugin plugin, World w, int x1, int z1, int x2, int z2, int perTick, Consumer<Map<Long, ChunkSnapshot>> done) {
        List<int[]> chunks = new ArrayList<>();
        for (int cx = x1 >> 4; cx <= x2 >> 4; cx++) for (int cz = z1 >> 4; cz <= z2 >> 4; cz++) chunks.add(new int[]{cx, cz});
        Map<Long, ChunkSnapshot> snaps = new HashMap<>();
        int[] next = {0};
        int[] pending = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int n = 0;
            while (next[0] < chunks.size() && n < perTick) {
                int[] c = chunks.get(next[0]++);
                n++;
                if (w.isChunkLoaded(c[0], c[1])) snaps.put(key(c[0], c[1]), w.getChunkAt(c[0], c[1]).getChunkSnapshot(false, false, false));
                else {
                    pending[0]++;
                    w.getChunkAtAsync(c[0], c[1], false).thenAccept(ch -> {
                        if (ch != null) snaps.put(key(c[0], c[1]), ch.getChunkSnapshot(false, false, false));
                        pending[0]--;
                    });
                }
            }
            if (next[0] >= chunks.size() && pending[0] == 0) {
                task.cancel();
                done.accept(snaps);
            }
        }, 1L, 1L);
    }

    static long key(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    /** Read-only block access over snapshots (any thread). Missing chunks read as air. */
    static final class Source {
        final Map<Long, ChunkSnapshot> snaps;
        final int minY, maxY;

        Source(Map<Long, ChunkSnapshot> snaps, int minY, int maxY) { this.snaps = snaps; this.minY = minY; this.maxY = maxY; }

        Material type(int x, int y, int z) {
            if (y < minY || y >= maxY) return Material.AIR;
            ChunkSnapshot s = snaps.get(key(x >> 4, z >> 4));
            return s == null ? Material.AIR : s.getBlockType(x & 15, y, z & 15);
        }

        BlockData data(int x, int y, int z) {
            ChunkSnapshot s = snaps.get(key(x >> 4, z >> 4));
            return s == null ? null : s.getBlockData(x & 15, y, z & 15);
        }
    }

    // ------------------------------------------------------------------------------------------------ classification

    private static final Set<Material> NATURAL = EnumSet.noneOf(Material.class);
    private static final Set<Material> FLUID = EnumSet.of(Material.WATER, Material.BUBBLE_COLUMN, Material.LAVA, Material.KELP, Material.KELP_PLANT,
            Material.SEAGRASS, Material.TALL_SEAGRASS);
    private static final Set<Material> PROTECTED = EnumSet.noneOf(Material.class);

    static {
        for (String n : new String[]{"STONE", "DEEPSLATE", "GRANITE", "DIORITE", "ANDESITE", "TUFF", "CALCITE", "DIRT", "GRASS_BLOCK", "COARSE_DIRT", "PODZOL",
                "MYCELIUM", "ROOTED_DIRT", "MUD", "CLAY", "SAND", "RED_SAND", "GRAVEL", "SUSPICIOUS_SAND", "SUSPICIOUS_GRAVEL", "BEDROCK", "NETHERRACK", "END_STONE",
                "BASALT", "SMOOTH_BASALT", "SOUL_SAND", "SOUL_SOIL", "SNOW", "ICE", "PACKED_ICE", "BLUE_ICE", "POWDER_SNOW", "DRIPSTONE_BLOCK", "POINTED_DRIPSTONE",
                "AMETHYST_BLOCK", "BUDDING_AMETHYST", "MOSS_CARPET", "SCULK", "SCULK_VEIN", "MAGMA_BLOCK", "OBSIDIAN", "SEA_PICKLE", "PALE_MOSS_CARPET",
                "SHORT_GRASS", "TALL_GRASS", "FERN", "LARGE_FERN", "DEAD_BUSH", "SWEET_BERRY_BUSH", "LILY_PAD", "SUGAR_CANE", "GLOW_LICHEN", "HANGING_ROOTS",
                "BROWN_MUSHROOM", "RED_MUSHROOM", "SHORT_DRY_GRASS", "TALL_DRY_GRASS", "BUSH", "LEAF_LITTER", "WILDFLOWERS", "FIREFLY_BUSH", "CACTUS_FLOWER"}) {
            Material m = Material.matchMaterial(n);
            if (m != null) NATURAL.add(m);
        }
        for (Material m : Material.values()) {
            if (!m.isBlock() || m.isLegacy()) continue;
            String n = m.name();
            if (n.endsWith("_ORE") || n.contains("CORAL") || n.endsWith("_LEAVES") || n.equals("VINE") || n.endsWith("_SAPLING") || n.endsWith("TULIP")) NATURAL.add(m);
            if (n.endsWith("SHULKER_BOX") || n.endsWith("_SIGN") || n.endsWith("_BANNER") || n.endsWith("_HEAD") || n.endsWith("_SKULL")) PROTECTED.add(m);
        }
        for (String n : new String[]{"CHEST", "TRAPPED_CHEST", "BARREL", "ENDER_CHEST", "HOPPER", "DISPENSER", "DROPPER", "FURNACE", "BLAST_FURNACE", "SMOKER",
                "BREWING_STAND", "LECTERN", "JUKEBOX", "CHISELED_BOOKSHELF", "DECORATED_POT", "CRAFTER", "BEACON", "SPAWNER", "TRIAL_SPAWNER", "VAULT",
                "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK", "STRUCTURE_BLOCK", "JIGSAW", "BEEHIVE", "BEE_NEST", "CAMPFIRE", "SOUL_CAMPFIRE",
                "CONDUIT", "END_PORTAL_FRAME", "END_PORTAL", "NETHER_PORTAL", "RESPAWN_ANCHOR", "SHELF", "COPPER_CHEST", "EXPOSED_COPPER_CHEST", "WEATHERED_COPPER_CHEST",
                "OXIDIZED_COPPER_CHEST", "WAXED_COPPER_CHEST", "WAXED_EXPOSED_COPPER_CHEST", "WAXED_WEATHERED_COPPER_CHEST", "WAXED_OXIDIZED_COPPER_CHEST",
                "CREAKING_HEART", "TEST_BLOCK", "TEST_INSTANCE_BLOCK"}) {
            Material m = Material.matchMaterial(n);
            if (m != null) PROTECTED.add(m);
        }
    }

    static boolean isAir(Material m) { return m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR; }
    static boolean isFluid(Material m) { return FLUID.contains(m); }
    static boolean isNatural(Material m) { return NATURAL.contains(m); }
    /** A block that belongs to a build: not air, water, terrain or vegetation. */
    public static boolean isStructure(Material m) { return !isAir(m) && !isFluid(m) && !isNatural(m); }
    public static boolean isProtected(Material m) { return PROTECTED.contains(m); }

    // ------------------------------------------------------------------------------------------------ detection

    private static int[] nearestStructure(Source src, int sx, int sy, int sz, int search) {
        for (int r = 0; r <= search; r++) for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != r) continue;
            if (isStructure(src.type(sx + dx, sy + dy, sz + dz))) return new int[]{sx + dx, sy + dy, sz + dz};
        }
        return null;
    }

    private record Flood(int[] box, LongOpenHashSet members, boolean truncated) {}

    /**
     * Flood fill through building blocks, stepping across gaps of up to two cells along each axis and diagonally,
     * so walls broken by an explosion still read as one build.
     */
    private static Flood flood(Source src, int[] s, int x1, int y1, int z1, int x2, int y2, int z2, int max) {
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue q = new LongArrayFIFOQueue();
        long sk = UndoJournal.key(s[0], s[1], s[2]);
        seen.add(sk);
        q.enqueue(sk);
        int[] box = {s[0], s[1], s[2], s[0], s[1], s[2]};
        boolean truncated = false;
        List<int[]> steps = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) if (dx != 0 || dy != 0 || dz != 0) steps.add(new int[]{dx, dy, dz});
        for (int d = 2; d <= 3; d++) for (int[] a : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}}) steps.add(new int[]{a[0] * d, a[1] * d, a[2] * d});
        while (!q.isEmpty()) {
            long k = q.dequeueLong();
            int x = UndoJournal.kx(k), y = UndoJournal.ky(k), z = UndoJournal.kz(k);
            box[0] = Math.min(box[0], x); box[1] = Math.min(box[1], y); box[2] = Math.min(box[2], z);
            box[3] = Math.max(box[3], x); box[4] = Math.max(box[4], y); box[5] = Math.max(box[5], z);
            for (int[] st : steps) {
                int nx = x + st[0], ny = y + st[1], nz = z + st[2];
                if (nx < x1 || nx > x2 || ny < y1 || ny > y2 || nz < z1 || nz > z2) continue;
                long nk = UndoJournal.key(nx, ny, nz);
                if (seen.contains(nk) || !isStructure(src.type(nx, ny, nz))) continue;
                if (seen.size() >= max) { truncated = true; continue; }
                seen.add(nk);
                q.enqueue(nk);
            }
        }
        return new Flood(box, seen, truncated);
    }

    // ------------------------------------------------------------------------------------------------ reading

    /** Room left around the build in the model so a renovation can add to it (blocks). */
    static final int GROW = 16;

    /**
     * Reads the box (expanded by {@code margin}) into a model. With {@code members} (auto-detected builds) structure
     * blocks that are not part of the detected build (a neighbour's build inside the box) are kept for context but
     * protected, so the renovation never touches them.
     */
    private static Result read(World w, Source src, int[] box, LongOpenHashSet members, int margin, boolean truncated) {
        int bx1 = box[0] - margin, by1 = Math.max(src.minY, box[1] - margin), bz1 = box[2] - margin;
        int bx2 = box[3] + margin, by2 = Math.min(src.maxY - 1, box[4] + margin + 2), bz2 = box[5] + margin;
        int ox = bx1 - GROW, oy = by1, oz = bz1 - GROW;
        int W = bx2 - bx1 + 1 + 2 * GROW, H = by2 - by1 + 1, D = bz2 - bz1 + 1 + 2 * GROW;
        VoxelModel m = new VoxelModel(W, H + 32, D);
        Existing ex = new Existing(W, H, D, 0);
        int structure = 0, foreign = 0;
        Map<BlockData, BlockState> cache = new HashMap<>();
        for (int y = by1; y <= by2; y++) for (int z = bz1; z <= bz2; z++) for (int x = bx1; x <= bx2; x++) {
            Material t = src.type(x, y, z);
            if (isAir(t)) continue;
            int mx = x - ox, my = y - oy, mz = z - oz;
            if (isFluid(t)) { ex.markFluid(mx, my, mz); continue; }
            if (isNatural(t)) { ex.markNatural(mx, my, mz, t.getKey().getKey()); continue; }
            BlockData d = src.data(x, y, z);
            if (d == null) continue;
            BlockState s = cache.computeIfAbsent(d, dd -> BlockState.parse(dd.getAsString()));
            m.set(mx, my, mz, s);
            ex.markOriginal(mx, my, mz);
            if ("true".equals(s.prop("waterlogged"))) ex.markFluid(mx, my, mz);
            boolean mine = members == null || members.contains(UndoJournal.key(x, y, z)) || near(members, x, y, z);
            if (!mine) { ex.protect(mx, my, mz); foreign++; continue; }
            structure++;
            if (isProtected(t)) {
                ex.protect(mx, my, mz);
                // a wall sign or banner hangs on the block behind it: that block stays too
                if (d instanceof Directional dir && t.name().contains("WALL_")) {
                    BlockFace back = dir.getFacing().getOppositeFace();
                    ex.protect(mx + back.getModX(), my, mz + back.getModZ());
                }
            }
        }
        m.setLocked(ex::isProtectedKey);
        VoxelModel snapshot = m.copy();
        return new Result(w, ox, oy, oz, m, snapshot, ex, new int[]{bx1, by1, bz1, bx2, by2, bz2}, structure, foreign, truncated);
    }

    private static boolean near(LongOpenHashSet members, int x, int y, int z) {
        for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++)
            if (members.contains(UndoJournal.key(x + dx, y + dy, z + dz))) return true;
        return false;
    }

    /** Main thread: item frames, paintings and armor stands in the box keep their blocks. */
    private static Result protectEntities(Result r) {
        int[] b = r.box();
        BoundingBox bb = new BoundingBox(b[0], b[1], b[2], b[3] + 1, b[4] + 1, b[5] + 1);
        for (Entity e : r.world().getNearbyEntities(bb)) {
            if (!(e instanceof Hanging) && !(e instanceof ArmorStand)) continue;
            Block at = e.getLocation().getBlock();
            r.existing().protect(at.getX() - r.ox(), at.getY() - r.oy(), at.getZ() - r.oz());
            if (e instanceof Hanging h) {
                Block support = at.getRelative(h.getAttachedFace());
                r.existing().protect(support.getX() - r.ox(), support.getY() - r.oy(), support.getZ() - r.oz());
            }
        }
        return r;
    }
}
