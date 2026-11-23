package io.github.shengboj0324.architect;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Lets players talk to the Architect in normal chat. Paper fires {@link AsyncChatEvent} off the main thread, so this
 * only decides (cheaply, thread-safely) whether the line is for the Architect and hands it to the main thread.
 */
public final class ChatListener implements Listener {

    private final ArchitectPlugin plugin;

    public ChatListener(ArchitectPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        ArchitectService svc = plugin.service();
        if (svc == null) return;
        Player p = e.getPlayer();
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        if (text.isEmpty() || text.startsWith("/") || !svc.addressed(p.getUniqueId(), text)) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            ArchitectService s = plugin.service();
            if (s != null && p.isOnline()) s.chat(p, text);
        });
    }

    /** Hides lines addressed to the Architect from other players when configured (runs before the message is sent). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void hide(AsyncChatEvent e) {
        ArchitectService svc = plugin.service();
        if (svc == null || !svc.hideAddressed()) return;
        String text = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        if (svc.addressed(e.getPlayer().getUniqueId(), text)) e.viewers().removeIf(v -> v != e.getPlayer() && v instanceof Player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        ArchitectService svc = plugin.service();
        if (svc != null) svc.left(e.getPlayer());
    }
}
