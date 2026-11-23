package io.github.shengboj0324.architect.world;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/** Loads chunks with Paper's asynchronous chunk API and holds them with plugin chunk tickets while they are in use. */
public final class PaperChunkGate implements ChunkGate {

    private final Plugin plugin;
    private final Map<World, LongOpenHashSet> held = new HashMap<>();
    private final Map<World, LongOpenHashSet> pending = new HashMap<>();

    public PaperChunkGate(Plugin plugin) { this.plugin = plugin; }

    private static long key(int cx, int cz) { return ((long) cx << 32) | (cz & 0xFFFFFFFFL); }

    @Override
    public boolean ensure(World w, int x1, int z1, int x2, int z2) {
        LongOpenHashSet h = held.computeIfAbsent(w, k -> new LongOpenHashSet());
        LongOpenHashSet p = pending.computeIfAbsent(w, k -> new LongOpenHashSet());
        boolean all = true;
        for (int cx = Math.min(x1, x2) >> 4; cx <= Math.max(x1, x2) >> 4; cx++) {
            for (int cz = Math.min(z1, z2) >> 4; cz <= Math.max(z1, z2) >> 4; cz++) {
                long k = key(cx, cz);
                if (h.contains(k)) continue;
                if (w.isChunkLoaded(cx, cz)) {
                    w.addPluginChunkTicket(cx, cz, plugin);
                    h.add(k);
                    p.remove(k);
                    continue;
                }
                all = false;
                if (p.add(k)) {
                    final int fx = cx, fz = cz;
                    w.getChunkAtAsync(fx, fz, true).thenAccept(c -> {
                        if (pending.getOrDefault(w, new LongOpenHashSet()).remove(key(fx, fz))) {
                            w.addPluginChunkTicket(fx, fz, plugin);
                            held.computeIfAbsent(w, k2 -> new LongOpenHashSet()).add(key(fx, fz));
                        }
                    });
                }
            }
        }
        return all;
    }

    @Override
    public void release(World w, int x1, int z1, int x2, int z2) {
        LongOpenHashSet h = held.get(w);
        if (h == null) return;
        for (int cx = Math.min(x1, x2) >> 4; cx <= Math.max(x1, x2) >> 4; cx++) {
            for (int cz = Math.min(z1, z2) >> 4; cz <= Math.max(z1, z2) >> 4; cz++) {
                if (h.remove(key(cx, cz))) w.removePluginChunkTicket(cx, cz, plugin);
            }
        }
    }

    @Override
    public void releaseAll() {
        for (Map.Entry<World, LongOpenHashSet> e : held.entrySet()) {
            for (long k : e.getValue()) e.getKey().removePluginChunkTicket((int) (k >> 32), (int) k, plugin);
        }
        held.clear();
        pending.clear();
    }
}
