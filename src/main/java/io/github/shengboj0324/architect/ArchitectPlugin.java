package io.github.shengboj0324.architect;

import io.github.shengboj0324.architect.command.ArchitectCommand;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** Entry point: loads config, starts the service and registers /architect. */
public final class ArchitectPlugin extends JavaPlugin {

    private ArchitectService service;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        startService();
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        PluginSettings.Elevator el = service.settings().elevator();
        if (el.enabled()) {
            org.bukkit.Material pad = org.bukkit.Material.matchMaterial(el.block());
            if (pad != null && pad.isBlock()) getServer().getPluginManager().registerEvents(new ElevatorListener(pad, el.maxDistance()), this);
            else getLogger().warning("elevator.block '" + el.block() + "' is not a block; elevator pads are off.");
        }
        ArchitectCommand cmd = new ArchitectCommand(this);
        PluginCommand pc = getCommand("architect");
        if (pc != null) {
            pc.setExecutor(cmd);
            pc.setTabCompleter(cmd);
        }
    }

    @Override
    public void onDisable() {
        if (service != null) service.stop();
    }

    public void reload() {
        if (service != null) service.stop();
        reloadConfig();
        startService();
    }

    private void startService() {
        PluginSettings settings = PluginSettings.from(getConfig());
        service = new ArchitectService(this, settings);
        service.start();
        if (settings.hasApiKey()) {
            getLogger().info("Claude Architect ready: model " + settings.agent().model() + ", effort " + settings.agent().effort()
                    + (settings.agent().fallbacks() ? ", server-side refusal fallbacks on" : "")
                    + (settings.chat().enabled() ? "; chat with it by starting a message with '" + settings.npcName() + "' (model " + settings.chat().model() + ")" : ""));
        } else {
            getLogger().warning("No Claude API key (anthropic.api-key or ANTHROPIC_API_KEY). /architect build will use the curated templates only,"
                    + " and chat understands only simple requests (follow, stay, come, build <template>, undo).");
        }
    }

    public ArchitectService service() { return service; }
}
