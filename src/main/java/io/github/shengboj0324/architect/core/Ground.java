package io.github.shengboj0324.architect.core;

/**
 * Natural terrain under and around a design, in model coordinates. Used when a design is built onto the land as it
 * is (natural site mode): the quality critic then counts terrain as support, so a cabin on a hillside or a pier
 * standing in a lake is grounded where it touches the real ground.
 */
public interface Ground {

    int UNKNOWN = Integer.MIN_VALUE;

    /** Model y of the top solid terrain block in this column, or {@link #UNKNOWN} when it was not surveyed. */
    int groundY(int x, int z);

    /** Water standing on the ground in this column, in blocks (0 = dry). */
    default int waterDepth(int x, int z) { return 0; }

    /** Does a block in this cell count as supported regardless of what is below it (an existing build's own blocks)? */
    default boolean anchored(int x, int y, int z) { return false; }

    /** Is the cell solid natural ground? Unsurveyed columns use the flat convention (everything below y=0 is ground). */
    default boolean solid(int x, int y, int z) {
        int g = groundY(x, z);
        return g == UNKNOWN ? y < 0 : y <= g;
    }
}
