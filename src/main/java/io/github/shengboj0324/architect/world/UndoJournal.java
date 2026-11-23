package io.github.shengboj0324.architect.world;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

/**
 * Remembers the original state of every block a build touched (first touch wins) so it can be restored.
 * Positions are packed into longs and states into a palette, so a multi-million-block mega build stays small.
 */
public final class UndoJournal {

    private final World world;
    private final String title;
    private final LongOpenHashSet seen = new LongOpenHashSet();
    private final LongArrayList positions = new LongArrayList();
    private final IntArrayList states = new IntArrayList();
    private final List<BlockData> palette = new ArrayList<>();
    private final Map<BlockData, Integer> paletteIndex = new HashMap<>();
    private int restoreCursor = -1;
    private ChunkGate gate = ChunkGate.NONE;
    private long lastChunk = Long.MIN_VALUE;

    public UndoJournal(World world, String title) {
        this.world = world;
        this.title = title;
    }

    public World world() { return world; }
    public String title() { return title; }
    public int size() { return positions.size(); }

    /** The gate used while restoring, so undoing a mega build never loads chunks synchronously. */
    public UndoJournal gate(ChunkGate g) { this.gate = g == null ? ChunkGate.NONE : g; return this; }

    static long key(int x, int y, int z) {
        return ((long) (x + 33_554_432) & 0x3FFFFFF) | (((long) (z + 33_554_432) & 0x3FFFFFF) << 26) | (((long) (y + 2048) & 0xFFF) << 52);
    }

    static int kx(long k) { return (int) (k & 0x3FFFFFF) - 33_554_432; }
    static int kz(long k) { return (int) ((k >>> 26) & 0x3FFFFFF) - 33_554_432; }
    static int ky(long k) { return (int) ((k >>> 52) & 0xFFF) - 2048; }

    public void record(Block b) {
        long k = key(b.getX(), b.getY(), b.getZ());
        if (seen.add(k)) {
            positions.add(k);
            BlockData d = b.getBlockData();
            Integer i = paletteIndex.get(d);
            if (i == null) { i = palette.size(); palette.add(d); paletteIndex.put(d, i); }
            states.add((int) i);
        }
    }

    /** Restores up to maxBlocks blocks (newest first). Returns true when everything is restored. */
    public boolean restoreStep(int maxBlocks, double maxMillis) {
        if (restoreCursor < 0) {
            if (positions.isEmpty()) return true;
            restoreCursor = positions.size() - 1;
            seen.clear(); // no longer needed; frees memory during a long restore
        }
        long deadline = System.nanoTime() + (long) (maxMillis * 1_000_000);
        int n = 0;
        while (restoreCursor >= 0 && n < maxBlocks) {
            if ((n & 63) == 63 && System.nanoTime() > deadline) break;
            long k = positions.getLong(restoreCursor);
            int x = kx(k), y = ky(k), z = kz(k);
            long chunk = ((long) (x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL);
            if (chunk != lastChunk) {
                if (!gate.ensure(world, x, z, x, z)) break; // wait for the chunk to load
                if (lastChunk != Long.MIN_VALUE) { int cx = (int) (lastChunk >> 32) << 4, cz = (int) lastChunk << 4; gate.release(world, cx, cz, cx, cz); }
                lastChunk = chunk;
            }
            world.getBlockAt(x, y, z).setBlockData(palette.get(states.getInt(restoreCursor)), false);
            restoreCursor--;
            n++;
        }
        if (restoreCursor < 0) {
            gate.releaseAll();
            return true;
        }
        return false;
    }

    /** Writes the journal (positions and original block states) to a gzip file, so undo survives restarts. */
    public void save(java.nio.file.Path file) throws java.io.IOException {
        java.nio.file.Files.createDirectories(file.getParent());
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.io.BufferedOutputStream(
                new java.util.zip.GZIPOutputStream(java.nio.file.Files.newOutputStream(file))))) {
            out.writeInt(1);
            out.writeUTF(world.getName());
            out.writeUTF(title);
            out.writeInt(palette.size());
            for (BlockData d : palette) out.writeUTF(d.getAsString());
            out.writeInt(positions.size());
            for (int i = 0; i < positions.size(); i++) { out.writeLong(positions.getLong(i)); out.writeInt(states.getInt(i)); }
        }
    }

    /** Reads a journal saved by {@link #save}; null when its world is gone. */
    public static UndoJournal load(java.nio.file.Path file, java.util.function.Function<String, World> worlds,
                                   java.util.function.Function<String, BlockData> parse) throws java.io.IOException {
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(
                new java.util.zip.GZIPInputStream(java.nio.file.Files.newInputStream(file))))) {
            if (in.readInt() != 1) throw new java.io.IOException("unknown undo file version");
            World w = worlds.apply(in.readUTF());
            String title = in.readUTF();
            if (w == null) return null;
            UndoJournal j = new UndoJournal(w, title);
            int pn = in.readInt();
            for (int i = 0; i < pn; i++) { BlockData d = parse.apply(in.readUTF()); j.palette.add(d); j.paletteIndex.put(d, i); }
            int n = in.readInt();
            for (int i = 0; i < n; i++) { long k = in.readLong(); j.positions.add(k); j.seen.add(k); j.states.add(in.readInt()); }
            return j;
        }
    }

    public double restoreProgress() {
        return positions.isEmpty() ? 1 : 1 - (restoreCursor + 1) / (double) positions.size();
    }
}
