package io.github.shengboj0324.architect.core;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sparse voxel buffer in build-local coordinates. x = west->east, y = up (0 = ground level), z = north->south.
 * By convention z = 0 is the FRONT of the build (the side facing the player who requested it).
 * Air entries are explicit carves; absent cells are left untouched in the world.
 */
public final class VoxelModel {

    private static final long OFF = 1L << 20;
    private static final long MASK = (1L << 21) - 1;

    private final List<BlockState> palette = new ArrayList<>();
    private final Map<BlockState, Integer> paletteIndex = new HashMap<>();
    private final Long2IntOpenHashMap cells = new Long2IntOpenHashMap();

    private int width, height, depth; // envelope; 0 = unbounded
    private int maxBlocks = Integer.MAX_VALUE;
    private long clipped;
    private long overBudget;
    private java.util.function.LongPredicate locked; // cells no op may change (renovation: containers, signs, ...)
    private long lockedHits;
    private Deque<Long2IntOpenHashMap> journals = new ArrayDeque<>();
    private Long2IntOpenHashMap journal; // key -> previous palette index (-1 = absent)

    public VoxelModel() {
        cells.defaultReturnValue(-1);
    }

    public VoxelModel(int width, int height, int depth) {
        this();
        setEnvelope(width, height, depth);
    }

    public void setEnvelope(int width, int height, int depth) {
        this.width = width;
        this.height = height;
        this.depth = depth;
    }

    public void setMaxBlocks(int maxBlocks) { this.maxBlocks = maxBlocks; }

    public int width() { return width; }
    public int height() { return height; }
    public int depth() { return depth; }

    public static long key(int x, int y, int z) {
        return ((x + OFF) & MASK) << 42 | ((y + OFF) & MASK) << 21 | ((z + OFF) & MASK);
    }

    public static int kx(long k) { return (int) (((k >>> 42) & MASK) - OFF); }
    public static int ky(long k) { return (int) (((k >>> 21) & MASK) - OFF); }
    public static int kz(long k) { return (int) ((k & MASK) - OFF); }

    public boolean inEnvelope(int x, int y, int z) {
        if (width <= 0) return true;
        return x >= 0 && x < width && y >= -64 && y < height && z >= 0 && z < depth;
    }

    private int indexOf(BlockState s) {
        Integer i = paletteIndex.get(s);
        if (i != null) return i;
        int n = palette.size();
        palette.add(s);
        paletteIndex.put(s, n);
        return n;
    }

    /** Sets a block; returns false when clipped by the envelope or block budget. */
    public boolean set(int x, int y, int z, BlockState s) {
        if (!inEnvelope(x, y, z)) { clipped++; return false; }
        long k = key(x, y, z);
        if (locked != null && locked.test(k)) { lockedHits++; return false; }
        int idx = indexOf(s);
        int prev = cells.get(k);
        if (prev == idx) return true;
        if (prev < 0 && cells.size() >= maxBlocks) { overBudget++; return false; }
        if (journal != null && !journal.containsKey(k)) journal.put(k, prev);
        cells.put(k, idx);
        return true;
    }

    public boolean setIfEmpty(int x, int y, int z, BlockState s) {
        BlockState cur = get(x, y, z);
        if (cur != null && !cur.isAir()) return false;
        return set(x, y, z, s);
    }

    public void remove(int x, int y, int z) {
        long k = key(x, y, z);
        if (locked != null && locked.test(k)) { lockedHits++; return; }
        int prev = cells.remove(k);
        if (prev >= 0 && journal != null && !journal.containsKey(k)) journal.put(k, prev);
    }

    public BlockState get(int x, int y, int z) {
        int i = cells.get(key(x, y, z));
        return i < 0 ? null : palette.get(i);
    }

    /** True when a non-air block is present. */
    public boolean solidAt(int x, int y, int z) {
        BlockState s = get(x, y, z);
        return s != null && !s.isAir();
    }

    public int size() { return cells.size(); }

    public long nonAirCount() {
        long n = 0;
        for (Long2IntMap.Entry e : cells.long2IntEntrySet()) if (!palette.get(e.getIntValue()).isAir()) n++;
        return n;
    }

    public long clippedCount() { return clipped; }
    public long overBudgetCount() { return overBudget; }
    public void resetCounters() { clipped = 0; overBudget = 0; lockedHits = 0; }

    /** Cells matching {@code p} (by key) can no longer be changed; attempts are counted, not applied. */
    public void setLocked(java.util.function.LongPredicate p) { this.locked = p; }
    public long lockedHits() { return lockedHits; }

    public interface CellVisitor { void visit(int x, int y, int z, BlockState s); }

    public void forEach(CellVisitor v) {
        for (Long2IntMap.Entry e : cells.long2IntEntrySet()) {
            long k = e.getLongKey();
            v.visit(kx(k), ky(k), kz(k), palette.get(e.getIntValue()));
        }
    }

    /** Snapshot of all cells as an array of keys (stable for iteration while mutating). */
    /**
     * All cell keys, sorted. Sorting matters: inserting keys into another open-addressing hash table in this table's
     * internal order clusters badly (quadratic time on million-cell models), sorted order does not.
     */
    public long[] keys() {
        long[] k = cells.keySet().toLongArray();
        java.util.Arrays.sort(k);
        return k;
    }

    public BlockState getByKey(long k) {
        int i = cells.get(k);
        return i < 0 ? null : palette.get(i);
    }

    /** Content bounds as {minX,minY,minZ,maxX,maxY,maxZ}, or null when empty. */
    public int[] bounds() {
        if (cells.isEmpty()) return null;
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (long k : cells.keySet()) {
            int x = kx(k), y = ky(k), z = kz(k);
            if (x < b[0]) b[0] = x; if (y < b[1]) b[1] = y; if (z < b[2]) b[2] = z;
            if (x > b[3]) b[3] = x; if (y > b[4]) b[4] = y; if (z > b[5]) b[5] = z;
        }
        return b;
    }

    public Map<String, Integer> materialCounts() {
        Map<String, Integer> m = new HashMap<>();
        for (Long2IntMap.Entry e : cells.long2IntEntrySet()) {
            BlockState s = palette.get(e.getIntValue());
            if (!s.isAir()) m.merge(s.id(), 1, Integer::sum);
        }
        return m;
    }

    // ---------------------------------------------------------------- batches (undo inside the design loop)

    public void beginBatch() {
        journal = new Long2IntOpenHashMap();
        journal.defaultReturnValue(-2);
    }

    public void endBatch() {
        if (journal != null && !journal.isEmpty()) {
            journals.push(journal);
            while (journals.size() > 32) journals.removeLast();
        }
        journal = null;
    }

    public int undoDepth() { return journals.size(); }

    /** Reverts the most recent completed batch. Returns number of cells restored, or -1 if nothing to undo. */
    public int undoBatch() {
        Long2IntOpenHashMap j = journals.poll();
        if (j == null) return -1;
        for (Long2IntMap.Entry e : j.long2IntEntrySet()) {
            if (e.getIntValue() < 0) cells.remove(e.getLongKey());
            else cells.put(e.getLongKey(), e.getIntValue());
        }
        return j.size();
    }

    public VoxelModel copy() {
        VoxelModel m = new VoxelModel(width, height, depth);
        m.maxBlocks = maxBlocks;
        m.palette.addAll(palette);
        m.paletteIndex.putAll(paletteIndex);
        m.cells.putAll(cells); // pre-sizes the target, so no clustering
        return m;
    }
}
