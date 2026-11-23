package io.github.shengboj0324.architect.world;

import org.bukkit.World;

/**
 * Keeps the chunks a build is working on loaded without ever loading them synchronously on the main thread.
 * A mega build is placed one tile at a time; the gate is asked for the current tile (and the next, to prefetch)
 * and the job waits a tick when they are not loaded yet.
 */
public interface ChunkGate {

    /** Requests the chunks covering the block rectangle and returns true when all of them are loaded and held. */
    boolean ensure(World w, int x1, int z1, int x2, int z2);

    /** Lets go of the chunks covering the block rectangle (they may unload again). */
    void release(World w, int x1, int z1, int x2, int z2);

    /** Releases everything this gate holds. */
    void releaseAll();

    /** For tests and simulations: everything is always loaded. */
    ChunkGate NONE = new ChunkGate() {
        @Override public boolean ensure(World w, int x1, int z1, int x2, int z2) { return true; }
        @Override public void release(World w, int x1, int z1, int x2, int z2) { }
        @Override public void releaseAll() { }
    };
}
