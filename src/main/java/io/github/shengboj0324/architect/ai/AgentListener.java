package io.github.shengboj0324.architect.ai;

/** Callbacks from the agent thread. Implementations must hop to the main thread before touching the world. */
public interface AgentListener {
    /** Short narration the model wrote for the player. */
    default void say(String text) { }
    /** A tool is about to run (for progress display). */
    default void tool(String name, String note) { }
    /** The design passed the quality gate and is ready to build. */
    default void ready(DesignSession s) { }
    /** The run ended without an accepted design. */
    default void failed(DesignSession s, String reason) { }
}
