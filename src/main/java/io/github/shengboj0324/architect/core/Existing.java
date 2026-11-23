package io.github.shengboj0324.architect.core;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.BitSet;

/**
 * What already stands in the world around a build being renovated, in model coordinates over a box of
 * {@code width x height x depth} cells starting at (0, {@code minY}, 0): which cells held structure blocks when it
 * was captured, where water stands, which cells are natural ground (sea bed, terrain), and which cells are protected
 * (containers, signs, banners, spawners and other blocks with contents, plus blocks carrying item frames). The design
 * model holds the structure itself; this holds the context the renovation must respect.
 */
public final class Existing implements Ground {

    private final int width, height, depth, minY;
    private final BitSet original, fluid, natural;
    private final LongOpenHashSet protectedCells = new LongOpenHashSet();
    private final int[] groundTop;      // highest natural cell per column, UNKNOWN when none
    private final String[] groundBlock; // the block id there (for renders)

    public Existing(int width, int height, int depth, int minY) {
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.minY = minY;
        int n = width * height * depth;
        original = new BitSet(n);
        fluid = new BitSet(n);
        natural = new BitSet(n);
        groundTop = new int[width * depth];
        java.util.Arrays.fill(groundTop, UNKNOWN);
        groundBlock = new String[width * depth];
    }

    public int width() { return width; }
    public int height() { return height; }
    public int depth() { return depth; }
    public int minY() { return minY; }
    public int maxY() { return minY + height - 1; }

    public boolean inside(int x, int y, int z) {
        return x >= 0 && z >= 0 && x < width && z < depth && y >= minY && y < minY + height;
    }

    private int idx(int x, int y, int z) { return ((y - minY) * depth + z) * width + x; }

    public void markOriginal(int x, int y, int z) { if (inside(x, y, z)) original.set(idx(x, y, z)); }
    public void markFluid(int x, int y, int z) { if (inside(x, y, z)) fluid.set(idx(x, y, z)); }
    public void markNatural(int x, int y, int z) { markNatural(x, y, z, null); }

    /** Marks natural ground; {@code id} (the block) is remembered for the column's surface. */
    public void markNatural(int x, int y, int z, String id) {
        if (!inside(x, y, z)) return;
        natural.set(idx(x, y, z));
        int c = z * width + x;
        if (y > groundTop[c]) { groundTop[c] = y; if (id != null) groundBlock[c] = id; }
    }

    /** The surface block of the natural ground in a column, or null. */
    public String naturalTop(int x, int z) { return x < 0 || z < 0 || x >= width || z >= depth ? null : groundBlock[z * width + x]; }
    public void protect(int x, int y, int z) { protectedCells.add(VoxelModel.key(x, y, z)); }

    /** The cell held a structure block when the build was captured. */
    public boolean original(int x, int y, int z) { return inside(x, y, z) && original.get(idx(x, y, z)); }
    /** Water (or another fluid) stood in the cell when captured. */
    public boolean fluid(int x, int y, int z) { return inside(x, y, z) && fluid.get(idx(x, y, z)); }
    /** Natural ground (terrain or sea bed, not part of the build) when captured. */
    public boolean natural(int x, int y, int z) { return inside(x, y, z) && natural.get(idx(x, y, z)); }
    public boolean isProtected(int x, int y, int z) { return protectedCells.contains(VoxelModel.key(x, y, z)); }
    public boolean isProtectedKey(long key) { return protectedCells.contains(key); }
    public LongOpenHashSet protectedCells() { return protectedCells; }
    public int fluidCount() { return fluid.cardinality(); }
    public int originalCount() { return original.cardinality(); }

    // Ground: natural cells are the land a renovation can rest new blocks on
    @Override public int groundY(int x, int z) { return x < 0 || z < 0 || x >= width || z >= depth ? UNKNOWN : groundTop[z * width + x]; }

    /** Blocks that were already part of the build count as supported, so a damaged build is not rejected for its own loose parts. */
    @Override public boolean anchored(int x, int y, int z) { return original(x, y, z); }

    @Override public int waterDepth(int x, int z) { return 0; }

    @Override public boolean solid(int x, int y, int z) { return natural(x, y, z); }
}
