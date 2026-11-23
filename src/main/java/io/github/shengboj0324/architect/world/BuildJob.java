package io.github.shengboj0324.architect.world;

import io.github.shengboj0324.architect.core.BlockCatalog;
import io.github.shengboj0324.architect.core.BlockState;
import io.github.shengboj0324.architect.core.StateTransforms;
import io.github.shengboj0324.architect.core.VoxelModel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * Places a voxel model into the world over many ticks. The footprint is split into 32 x 32 tiles visited in a
 * serpentine order; each tile goes through three phases (clear the site, fill foundations down to the terrain,
 * place the model bottom-up). Before a tile starts, its chunks are loaded asynchronously through the
 * {@link ChunkGate} and the next tile is prefetched, so a 500 x 500 mega build never loads chunks on the main thread
 * and never holds more than a few chunks at once. Each tick stops at a block count or time budget, and every
 * touched block's previous state goes to the journal.
 */
public final class BuildJob {

    public enum Phase { WAIT, CLEAR, FOUNDATION, MODEL, DONE }

    public record Placed(int x, int y, int z, BlockData data) {}

    /**
     * How the site is prepared before the model goes in. {@code clearable} picks which existing blocks the clearing
     * pass removes (everything on a levelled site, only vegetation on a natural one); {@code columns} limits clearing
     * to these column keys ({@link VoxelModel#key} with y=0; null = every column the model uses); clearing runs from
     * {@code headroom} blocks above the model's top down to {@code clearFloor}. Foundations go under columns whose ground layer (model y
     * {@code baseY}) is set, from {@code fillFrom - 1} down to solid terrain.
     */
    public record Prep(boolean clear, Predicate<Block> clearable, LongPredicate columns, int clearFloor, int headroom,
                       boolean fillFoundation, int baseY, int fillFrom, int maxFoundationDepth) {

        /** The classic levelled site: clear everything above y=0 in the used columns, fill under the ground layer. */
        public static Prep level(VoxelModel model, boolean clear, boolean fill, int depth) {
            int[] b = model.bounds();
            int low = b == null ? 0 : b[1];
            return new Prep(clear, x -> true, null, 1, 2, fill && low <= 0, Math.max(0, low), low, depth);
        }
    }

    static final int TILE = 32;
    private static final int YOFF = 1024;

    private final String title;
    private final VoxelModel model;
    private final VoxelModel previous; // when expanding: cells equal to this snapshot are skipped
    private final Placement at;
    private final UndoJournal journal;
    private final Function<BlockState, BlockData> toData;
    private final Prep prep;
    private final ChunkGate gate;
    private final BlockCatalog cat = BlockCatalog.get();

    private final BlockData air;
    private final int[] bounds;
    private final int[] tileX, tileZ;   // model-space origin of each tile, in visiting order
    private final int[] tileStart;      // index into order where each tile's cells begin (length tiles + 1)
    private final long[] order;         // (tile << 21) | (y + YOFF) << 10 | zl << 5 | serpentine xl
    private int tile = -1;
    private Phase phase;
    private int cursor;          // index into order (MODEL), or column index inside the tile (CLEAR / FOUNDATION)
    private int clearY;          // current y inside the CLEAR column scan
    private long placed, total;
    private Placed last;
    private boolean cancelled;
    private ChangeListener listener;

    /** Told about every block the job changes (e.g. to log it to CoreProtect). */
    public interface ChangeListener { void changed(Block b, BlockData before, BlockData after); }

    public BuildJob listener(ChangeListener l) { this.listener = l; return this; }

    private void set(Block b, BlockData data) {
        BlockData before = listener == null ? null : b.getBlockData();
        b.setBlockData(data, false);
        if (listener != null) listener.changed(b, before, data);
    }

    public BuildJob(String title, VoxelModel model, VoxelModel previous, Placement at, UndoJournal journal,
                    Function<BlockState, BlockData> toData, boolean clearSite, boolean fillFoundation, int maxFoundationDepth) {
        this(title, model, previous, at, journal, toData, clearSite, fillFoundation, maxFoundationDepth, ChunkGate.NONE);
    }

    public BuildJob(String title, VoxelModel model, VoxelModel previous, Placement at, UndoJournal journal,
                    Function<BlockState, BlockData> toData, boolean clearSite, boolean fillFoundation, int maxFoundationDepth, ChunkGate gate) {
        this(title, model, previous, at, journal, toData, Prep.level(model, clearSite, fillFoundation, maxFoundationDepth), gate);
    }

    public BuildJob(String title, VoxelModel model, VoxelModel previous, Placement at, UndoJournal journal,
                    Function<BlockState, BlockData> toData, Prep prep, ChunkGate gate) {
        this.title = title;
        this.model = model;
        this.previous = previous;
        this.at = at;
        this.journal = journal;
        this.toData = toData;
        this.prep = prep;
        this.gate = gate == null ? ChunkGate.NONE : gate;
        if (previous != null) {
            // parts removed by the new generation become air in the world
            for (long k : previous.keys()) {
                int x = VoxelModel.kx(k), y = VoxelModel.ky(k), z = VoxelModel.kz(k);
                BlockState st = previous.getByKey(k);
                if (model.get(x, y, z) == null && st != null && !st.isAir()) model.set(x, y, z, BlockState.AIR);
            }
        }
        this.air = toData.apply(BlockState.AIR);
        this.bounds = model.bounds();
        if (bounds == null) {
            tileX = tileZ = new int[0];
            tileStart = new int[]{0};
            order = new long[0];
            phase = Phase.DONE;
            return;
        }
        int tw = (bounds[3] - bounds[0]) / TILE + 1, td = (bounds[5] - bounds[2]) / TILE + 1;
        tileX = new int[tw * td];
        tileZ = new int[tw * td];
        int[] tileIndex = new int[tw * td];
        int n = 0;
        for (int j = 0; j < td; j++) for (int k = 0; k < tw; k++) {
            int i = (j & 1) == 0 ? k : tw - 1 - k;
            tileX[n] = bounds[0] + i * TILE;
            tileZ[n] = bounds[2] + j * TILE;
            tileIndex[j * tw + i] = n++;
        }
        this.order = sortedCells(model, previous, bounds, tw, tileIndex);
        this.tileStart = new int[tileX.length + 1];
        int c = 0;
        for (int t = 0; t <= tileX.length; t++) {
            while (c < order.length && (int) (order[c] >>> 21) < t) c++;
            tileStart[t] = c;
        }
        this.total = order.length;
        this.phase = Phase.WAIT;
        nextTile();
    }

    public String title() { return title; }
    public Phase phase() { return phase; }
    public long placed() { return placed; }
    public long total() { return total; }
    public Placed last() { return last; }
    public UndoJournal journal() { return journal; }
    public Placement placement() { return at; }
    public int tiles() { return tileX.length; }
    public void cancel() { cancelled = true; gate.releaseAll(); }
    public boolean done() { return phase == Phase.DONE || cancelled; }

    public double progress() {
        if (phase == Phase.DONE) return 1;
        if (tileX.length == 0) return 1;
        double perTile = 1.0 / tileX.length;
        double inTile = phase == Phase.MODEL && tileStart[tile + 1] > tileStart[tile]
                ? 0.05 + 0.95 * (cursor - tileStart[tile]) / (double) (tileStart[tile + 1] - tileStart[tile]) : phase == Phase.MODEL ? 1 : 0.02;
        return Math.max(0, Math.min(1, (Math.max(0, tile) + inTile) * perTile));
    }

    private void nextTile() {
        if (tile >= 0) { int[] r = tileRect(tile); gate.release(at.world(), r[0], r[1], r[2], r[3]); }
        tile++;
        if (tile >= tileX.length) { phase = Phase.DONE; gate.releaseAll(); return; }
        phase = Phase.WAIT;
    }

    /** World-space block rectangle {x1, z1, x2, z2} covered by a tile (1 block margin for clearing). */
    private int[] tileRect(int t) {
        int x1 = tileX[t] - 1, z1 = tileZ[t] - 1, x2 = Math.min(bounds[3], tileX[t] + TILE - 1) + 1, z2 = Math.min(bounds[5], tileZ[t] + TILE - 1) + 1;
        int ax = at.wx(x1, z1), az = at.wz(x1, z1), bx = at.wx(x2, z2), bz = at.wz(x2, z2);
        return new int[]{Math.min(ax, bx), Math.min(az, bz), Math.max(ax, bx), Math.max(az, bz)};
    }

    /** Does work until either budget runs out. Returns blocks changed this tick. */
    public int tick(int maxBlocks, double maxMillis) {
        long deadline = System.nanoTime() + (long) (maxMillis * 1_000_000);
        int changed = 0, checks = 0;
        World w = at.world();
        while (!done() && changed < maxBlocks) {
            if ((++checks & 63) == 0 && System.nanoTime() > deadline) break;
            switch (phase) {
                case WAIT -> {
                    int[] r = tileRect(tile);
                    boolean ready = gate.ensure(w, r[0], r[1], r[2], r[3]);
                    if (tile + 1 < tileX.length) { int[] n = tileRect(tile + 1); gate.ensure(w, n[0], n[1], n[2], n[3]); } // prefetch
                    if (!ready) return changed; // chunks are loading asynchronously; try again next tick
                    cursor = 0;
                    clearY = bounds[4] + prep.headroom();
                    phase = prep.clear() ? Phase.CLEAR : prep.fillFoundation() ? Phase.FOUNDATION : Phase.MODEL;
                    if (phase == Phase.MODEL) cursor = tileStart[tile];
                }
                case CLEAR -> {
                    // top-down per column so trees and overhangs above the footprint go first
                    int tw = tileW(), td = tileD();
                    if (cursor >= tw * td) {
                        phase = prep.fillFoundation() ? Phase.FOUNDATION : Phase.MODEL;
                        cursor = phase == Phase.MODEL ? tileStart[tile] : 0;
                        continue;
                    }
                    int x = tileX[tile] + cursor % tw, z = tileZ[tile] + cursor / tw;
                    int y = clearY;
                    if (y < prep.clearFloor()) { cursor++; clearY = bounds[4] + prep.headroom(); continue; }
                    if (y == bounds[4] + prep.headroom() && !clearsColumn(x, z)) { cursor++; continue; } // nothing will stand here
                    clearY--;
                    BlockState mine = model.get(x, y, z);
                    if (mine != null) continue; // the model decides this cell
                    int wy = at.wy(y);
                    if (wy >= w.getMaxHeight()) continue;
                    Block b = w.getBlockAt(at.wx(x, z), wy, at.wz(x, z));
                    if (!b.isEmpty() && air != null && prep.clearable().test(b)) {
                        journal.record(b);
                        set(b, air);
                        changed++;
                    }
                }
                case FOUNDATION -> {
                    int tw = tileW(), td = tileD();
                    if (cursor >= tw * td) { phase = Phase.MODEL; cursor = tileStart[tile]; continue; }
                    int x = tileX[tile] + cursor % tw, z = tileZ[tile] + cursor / tw;
                    cursor++;
                    BlockState base = model.get(x, prep.baseY(), z);
                    if (base == null || base.isAir()) continue;
                    if (previous != null && previous.get(x, prep.fillFrom(), z) != null) continue;
                    BlockData fill = toData.apply(foundationFor(base));
                    if (fill == null) continue;
                    for (int d = 1; d <= prep.maxFoundationDepth(); d++) {
                        Block b = w.getBlockAt(at.wx(x, z), at.wy(prep.fillFrom() - d), at.wz(x, z));
                        if (b.getY() <= w.getMinHeight()) break;
                        if (b.isSolid() && !b.isLiquid()) break;
                        journal.record(b);
                        set(b, fill);
                        changed++;
                    }
                }
                case MODEL -> {
                    if (cursor >= tileStart[tile + 1]) { nextTile(); continue; }
                    long k = order[cursor++];
                    int y = (int) ((k >>> 10) & 0x7FF) - YOFF;
                    int zl = (int) ((k >>> 5) & 31), xs = (int) (k & 31);
                    int xl = (zl & 1) == 0 ? xs : 31 - xs;
                    int x = tileX[tile] + xl, z = tileZ[tile] + zl;
                    BlockState s = model.get(x, y, z);
                    if (s == null) continue;
                    BlockData data = toData.apply(StateTransforms.rotate(s, at.quarter()));
                    if (data == null) continue;
                    int wy = at.wy(y);
                    if (wy < w.getMinHeight() || wy >= w.getMaxHeight()) continue;
                    Block b = w.getBlockAt(at.wx(x, z), wy, at.wz(x, z));
                    if (s.isAir() && b.isEmpty()) continue;
                    journal.record(b);
                    set(b, data);
                    placed++;
                    changed++;
                    last = new Placed(b.getX(), b.getY(), b.getZ(), data);
                }
                case DONE -> { }
            }
        }
        return changed;
    }

    private int tileW() { return Math.min(TILE, bounds[3] - tileX[tile] + 1); }
    private int tileD() { return Math.min(TILE, bounds[5] - tileZ[tile] + 1); }

    private boolean clearsColumn(int x, int z) {
        return prep.columns() == null ? columnUsed(x, z) : prep.columns().test(VoxelModel.key(x, 0, z));
    }

    private boolean columnUsed(int x, int z) {
        for (int y = bounds[1]; y <= bounds[4]; y++) if (model.get(x, y, z) != null) return true;
        return false;
    }

    /** What goes under the ground layer: earth under paths and grass, the block itself under solid masonry. */
    private BlockState foundationFor(BlockState ground) {
        String id = ground.id();
        if (id.equals("grass_block") || id.equals("dirt_path") || id.equals("coarse_dirt") || id.equals("podzol") || id.equals("moss_block")
                || id.equals("rooted_dirt") || id.equals("farmland") || id.equals("mud")) return BlockState.of("dirt");
        if (id.equals("sand") || id.equals("red_sand") || id.equals("gravel") || id.endsWith("concrete_powder")) return BlockState.of("sandstone");
        if (cat.isSolidCube(ground) && !ground.id().contains("glass")) return BlockState.of(ground.id());
        return BlockState.of("stone");
    }

    /** Cells grouped by tile, each tile bottom-up in layers with a serpentine sweep, so the builder visibly works row by row. */
    private static long[] sortedCells(VoxelModel m, VoxelModel previous, int[] bounds, int tilesWide, int[] tileIndex) {
        long[] keys = m.keys();
        long[] out = new long[keys.length];
        int n = 0;
        for (long k : keys) {
            int x = VoxelModel.kx(k), y = VoxelModel.ky(k), z = VoxelModel.kz(k);
            if (previous != null) {
                BlockState was = previous.get(x, y, z);
                BlockState now = m.getByKey(k);
                if (was != null ? was.equals(now) : now == null || now.isAir()) continue;
            }
            if (y + YOFF < 0 || y + YOFF > 0x7FF) continue;
            int ti = (x - bounds[0]) / TILE, tj = (z - bounds[2]) / TILE;
            int xl = (x - bounds[0]) % TILE, zl = (z - bounds[2]) % TILE;
            int xs = (zl & 1) == 0 ? xl : 31 - xl;
            long t = tileIndex[tj * tilesWide + ti];
            out[n++] = (t << 21) | ((long) (y + YOFF) << 10) | ((long) zl << 5) | xs;
        }
        long[] res = Arrays.copyOf(out, n);
        Arrays.sort(res);
        return res;
    }

    /** Converts model states to Bukkit block data with a cache; invalid states map to null and are skipped. */
    public static Function<BlockState, BlockData> converter(Function<String, BlockData> parse) {
        Map<BlockState, BlockData> cache = new HashMap<>();
        return s -> cache.computeIfAbsent(s, k -> {
            try { return parse.apply(k.toNamespaced()); } catch (IllegalArgumentException e) { return null; }
        });
    }
}
