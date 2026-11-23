package io.github.shengboj0324.architect;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInputEvent;

/**
 * Elevator pads, as on many survival servers: stand on an elevator block (lodestone by default) and jump to ride up
 * to the next pad in the same column, or sneak to ride down. The Architect builds them with the elevator op
 * (type native) so tall bases get a lift that works for everyone without redstone or water.
 */
final class ElevatorListener implements Listener {

    private final Material pad;
    private final int maxDistance;
    private final Map<UUID, Long> last = new ConcurrentHashMap<>();

    ElevatorListener(Material pad, int maxDistance) {
        this.pad = pad;
        this.maxDistance = maxDistance;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInput(PlayerInputEvent e) {
        boolean up = e.getInput().isJump(), down = e.getInput().isSneak();
        if (!up && !down) return;
        Player p = e.getPlayer();
        if (p.isFlying() || p.isInsideVehicle() || !p.hasPermission("architect.elevator.use")) return;
        Block under = p.getLocation().subtract(0, 0.2, 0).getBlock();
        if (under.getType() != pad) return;
        long now = System.currentTimeMillis();
        if (now - last.getOrDefault(p.getUniqueId(), 0L) < 600) return;
        Block target = null;
        for (int d = 2; d <= maxDistance; d++) {
            Block b = under.getRelative(0, up ? d : -d, 0);
            if (b.getY() < b.getWorld().getMinHeight() || b.getY() >= b.getWorld().getMaxHeight() - 2) break;
            if (b.getType() == pad && b.getRelative(0, 1, 0).isPassable() && b.getRelative(0, 2, 0).isPassable()) { target = b; break; }
        }
        if (target == null) return;
        last.put(p.getUniqueId(), now);
        Location to = target.getLocation().add(0.5, 1, 0.5);
        to.setYaw(p.getLocation().getYaw());
        to.setPitch(p.getLocation().getPitch());
        p.teleportAsync(to);
        p.playSound(to, Sound.BLOCK_BEACON_ACTIVATE, 0.6f, up ? 1.6f : 1.2f);
    }
}
