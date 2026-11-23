package io.github.shengboj0324.architect.world;

import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.Plugin;

/**
 * Makes the Architect respect land protection on multiplayer servers: it only builds or renovates where the
 * requesting player could build themselves. WorldGuard and GriefPrevention are asked directly when present (soft
 * dependencies, called reflectively so the plugin runs without them); with mode "events" (or "auto" without
 * CoreProtect) a cancellable block-break event is also fired as the player at sampled spots, which every protection
 * plugin (Lands, Towny, PlotSquared, Residence, ...) answers. Block changes can be logged to CoreProtect, so staff
 * can inspect and roll back the Architect's work like a player's.
 */
public final class Protection {

    private final Logger log;
    private final String mode;
    private final boolean coreProtectLogging;
    private Object worldGuardPlugin, regionQuery;
    private Method wrapPlayer, testBuild, adaptLocation;
    private Object griefPrevention;
    private Method gpAllowBuild;
    private Object coreProtect;
    private Method cpLogPlacement, cpLogRemoval;

    public Protection(Logger log, String mode, boolean coreProtectLogging) {
        this.log = log;
        this.mode = mode == null ? "auto" : mode;
        this.coreProtectLogging = coreProtectLogging;
        hook();
    }

    private void hook() {
        if (Bukkit.getPluginManager().getPlugin("WorldGuard") != null) {
            try {
                Class<?> wgp = Class.forName("com.sk89q.worldguard.bukkit.WorldGuardPlugin");
                worldGuardPlugin = wgp.getMethod("inst").invoke(null);
                wrapPlayer = wgp.getMethod("wrapPlayer", Player.class);
                Object wg = Class.forName("com.sk89q.worldguard.WorldGuard").getMethod("getInstance").invoke(null);
                Object platform = wg.getClass().getMethod("getPlatform").invoke(wg);
                Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
                regionQuery = container.getClass().getMethod("createQuery").invoke(container);
                Class<?> weLoc = Class.forName("com.sk89q.worldedit.util.Location");
                Class<?> localPlayer = Class.forName("com.sk89q.worldguard.LocalPlayer");
                Class<?> flagArray = Class.forName("[Lcom.sk89q.worldguard.protection.flags.StateFlag;");
                testBuild = regionQuery.getClass().getMethod("testBuild", weLoc, localPlayer, flagArray);
                adaptLocation = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter").getMethod("adapt", Location.class);
                log.info("Land protection: WorldGuard regions are respected.");
            } catch (ReflectiveOperationException | RuntimeException e) {
                worldGuardPlugin = null;
                log.warning("WorldGuard is installed but its API could not be used (" + e + "); relying on the event check.");
            }
        }
        Plugin gp = Bukkit.getPluginManager().getPlugin("GriefPrevention");
        if (gp != null) {
            try {
                griefPrevention = gp;
                gpAllowBuild = gp.getClass().getMethod("allowBuild", Player.class, Location.class);
                log.info("Land protection: GriefPrevention claims are respected.");
            } catch (ReflectiveOperationException | RuntimeException e) {
                griefPrevention = null;
                log.warning("GriefPrevention is installed but its API could not be used (" + e + "); relying on the event check.");
            }
        }
        Plugin cp = Bukkit.getPluginManager().getPlugin("CoreProtect");
        if (cp != null && coreProtectLogging) {
            try {
                Object api = cp.getClass().getMethod("getAPI").invoke(cp);
                if ((boolean) api.getClass().getMethod("isEnabled").invoke(api)) {
                    coreProtect = api;
                    cpLogPlacement = api.getClass().getMethod("logPlacement", String.class, Location.class, org.bukkit.Material.class, BlockData.class);
                    cpLogRemoval = api.getClass().getMethod("logRemoval", String.class, Location.class, org.bukkit.Material.class, BlockData.class);
                    log.info("CoreProtect: the Architect's block changes are logged (user #architect:<player>).");
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                coreProtect = null;
                log.warning("CoreProtect is installed but its API could not be used (" + e + "); changes are not logged there.");
            }
        }
    }

    private boolean eventChecks() {
        return mode.equals("events") || (mode.equals("auto") && Bukkit.getPluginManager().getPlugin("CoreProtect") == null);
    }

    /**
     * Can the player build at every sampled spot? Returns null when allowed, else a short reason naming the first
     * refused spot. Main thread only.
     */
    public String check(Player p, World w, List<int[]> spots) {
        if (mode.equals("off") || p.hasPermission("architect.bypass.protection")) return null;
        for (int[] s : spots) {
            Block b = w.getBlockAt(s[0], s[1], s[2]);
            Location loc = b.getLocation();
            if (worldGuardPlugin != null) {
                try {
                    Object lp = wrapPlayer.invoke(worldGuardPlugin, p);
                    Object flags = java.lang.reflect.Array.newInstance(testBuild.getParameterTypes()[2].getComponentType(), 0);
                    if (!(boolean) testBuild.invoke(regionQuery, adaptLocation.invoke(null, loc), lp, flags)) return "a WorldGuard region at " + where(s);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return "the WorldGuard check failed at " + where(s);
                }
            }
            if (griefPrevention != null) {
                try {
                    Object reason = gpAllowBuild.invoke(griefPrevention, p, loc);
                    if (reason != null) return "a GriefPrevention claim at " + where(s);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return "the GriefPrevention check failed at " + where(s);
                }
            }
            if (eventChecks()) {
                BlockBreakEvent probe = new BlockBreakEvent(b, p);
                probe.setDropItems(false);
                probe.setExpToDrop(0);
                Bukkit.getPluginManager().callEvent(probe);
                if (probe.isCancelled()) return "protected land at " + where(s);
            }
        }
        return null;
    }

    private static String where(int[] s) { return s[0] + " " + s[1] + " " + s[2]; }

    public boolean logging() { return coreProtect != null; }

    /** Logs one block change to CoreProtect (main thread). */
    public void logChange(String player, Block b, BlockData before, BlockData after) {
        if (coreProtect == null) return;
        try {
            String user = "#architect:" + player;
            Location loc = b.getLocation();
            if (before != null && !before.getMaterial().isAir()) cpLogRemoval.invoke(coreProtect, user, loc, before.getMaterial(), before);
            if (after != null && !after.getMaterial().isAir()) cpLogPlacement.invoke(coreProtect, user, loc, after.getMaterial(), after);
        } catch (ReflectiveOperationException | RuntimeException e) {
            coreProtect = null;
            log.warning("CoreProtect logging failed and was turned off: " + e);
        }
    }

    /** Up to {@code n} spots spread over a list of world positions (always including the extremes). */
    public static List<int[]> sample(List<int[]> all, int n) {
        if (all.size() <= n) return all;
        List<int[]> out = new java.util.ArrayList<>(n);
        double step = (all.size() - 1) / (double) (n - 1);
        for (int i = 0; i < n; i++) out.add(all.get((int) Math.round(i * step)));
        return out;
    }
}
