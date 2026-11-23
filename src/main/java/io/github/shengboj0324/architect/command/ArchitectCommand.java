package io.github.shengboj0324.architect.command;

import io.github.shengboj0324.architect.ArchitectPlugin;
import io.github.shengboj0324.architect.ArchitectService;
import io.github.shengboj0324.architect.ai.ArchitectAgent;
import io.github.shengboj0324.architect.ai.ExemplarStore;
import io.github.shengboj0324.architect.core.Style;
import io.github.shengboj0324.architect.core.Templates;
import io.github.shengboj0324.architect.eval.EvalRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import io.github.shengboj0324.architect.PluginSettings;
import io.github.shengboj0324.architect.world.SitePlan;
import io.github.shengboj0324.architect.world.SiteSurvey;
import io.github.shengboj0324.architect.world.TerrainMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/** /architect (alias /arch): every player-facing action. */
public final class ArchitectCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("build", "expand", "template", "confirm", "cancel", "undo", "rate", "status",
            "styles", "templates", "summon", "dismiss", "talk", "follow", "stay", "help", "reload", "eval", "survey", "as", "renovate", "repair", "inspect",
            "pos1", "pos2", "deselect");

    private final ArchitectPlugin plugin;

    public ArchitectCommand(ArchitectPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        ArchitectService svc = plugin.service();
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        String rest = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim() : "";
        switch (sub) {
            case "reload" -> {
                if (!admin(sender)) return true;
                plugin.reload();
                msg(sender, "Reloaded. Claude is " + (plugin.service().aiEnabled() ? "on" : "off (no API key)") + ".");
                return true;
            }
            case "eval" -> { if (admin(sender)) eval(sender, rest); return true; }
            case "survey" -> { if (admin(sender)) survey(sender, args); return true; }
            case "render" -> {
                if (!admin(sender)) return true;
                if (args.length < 4) { msg(sender, "Usage: /architect render <x> <y> <z> [corners|top|all|front|back|east|west|damage]"); return true; }
                World w = sender instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
                try { svc.renderAt(sender, w.getBlockAt(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3])), args.length > 4 ? args[4] : "corners"); }
                catch (NumberFormatException e) { msg(sender, "Usage: /architect render <x> <y> <z> [view]"); }
                return true;
            }
            case "inspect" -> {
                if (args.length >= 4) {
                    if (!admin(sender)) return true;
                    World w = sender instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
                    try { svc.inspectAt(sender, w.getBlockAt(Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]))); }
                    catch (NumberFormatException e) { msg(sender, "Usage: /architect inspect [x y z]"); }
                    return true;
                }
                if (!(sender instanceof Player)) { msg(sender, "From the console give the block: /architect inspect <x> <y> <z>"); return true; }
            }
            case "styles" -> {
                StringBuilder sb = new StringBuilder("Styles: ");
                for (Style s : svc.styles().all()) sb.append(s.name()).append(" (").append(s.description()).append("); ");
                msg(sender, sb.toString());
                return true;
            }
            case "templates" -> { msg(sender, "Templates: " + String.join(", ", Templates.names())); return true; }
            case "help" -> { help(sender, label); return true; }
            default -> { }
        }
        // Paper hands "execute as <player> run architect ..." to us as the console, so admins (and RCON) use "as <player> ...".
        if (sub.equals("as") && !(sender instanceof Player)) {
            if (!admin(sender)) return true;
            Player target = args.length > 2 ? Bukkit.getPlayerExact(args[1]) : null;
            if (target == null) { msg(sender, "Usage: /" + label + " as <online player> <subcommand...>"); return true; }
            return onCommand(target, command, label, Arrays.copyOfRange(args, 2, args.length));
        }
        if (!(sender instanceof Player p)) { msg(sender, "Players only."); return true; }
        switch (sub) {
            case "talk", "say", "ask" -> {
                if (rest.isEmpty()) { msg(p, "Say something: /" + label + " talk <message> (or just start a chat message with my name)"); return true; }
                svc.chat(p, svc.settings().npcName() + " " + rest);
                return true;
            }
            case "follow" -> { svc.npcFollow(p); return true; }
            case "stay" -> { svc.npcStay(p); return true; }
            default -> { }
        }
        if (!p.hasPermission("architect.use")) { msg(p, "You don't have permission to commission builds."); return true; }
        switch (sub) {
            case "build", "design" -> {
                if (rest.isEmpty()) { msg(p, "Tell me what to build: /" + label + " build <description>"); return true; }
                svc.design(p, rest);
            }
            case "expand", "next", "grow" -> {
                if (rest.isEmpty() && svc.aiEnabled()) { msg(p, "Tell me how it should grow: /" + label + " expand <description>"); return true; }
                svc.expand(p, rest);
            }
            case "template" -> {
                if (args.length < 2) { msg(p, "/" + label + " template <" + String.join("|", Templates.names()) + "> [style] [scale] [generations]"); return true; }
                String style = args.length > 2 && svc.styles().get(args[2]) != null ? args[2] : null;
                int scale = args.length > 3 ? parse(args[3], 1, 1, 2) : 1;
                int gens = args.length > 4 ? parse(args[4], 3, 1, 5) : 3;
                svc.template(p, args[1].toLowerCase(Locale.ROOT), style, scale, gens);
            }
            case "renovate", "renovation", "fix", "renew" -> {
                String brief = rest.isEmpty() ? "Renovate this build: repair all damage, then improve it in its own style: walls, floors, lighting, interiors and furniture, and good circulation between floors." : rest;
                svc.renovate(p, brief, true, false);
            }
            case "repair" -> svc.renovate(p, rest.isEmpty() ? "Repair the damage." : rest, true, true);
            case "inspect", "analyze", "analyse" -> svc.inspect(p);
            case "pos1" -> svc.selectCorner(p, 1);
            case "pos2" -> svc.selectCorner(p, 2);
            case "deselect", "unselect" -> svc.clearSelection(p);
            case "confirm" -> svc.confirm(p);
            case "cancel", "stop" -> svc.cancel(p);
            case "undo" -> svc.undo(p);
            case "rate" -> {
                int stars = args.length > 1 ? parse(args[1], -1, 1, 5) : -1;
                if (stars < 1) { msg(p, "/" + label + " rate <1-5>"); return true; }
                svc.rate(p, stars);
            }
            case "status" -> svc.status(p);
            case "summon" -> svc.summonNpc(p);
            case "dismiss" -> svc.dismissNpc();
            default -> help(p, label);
        }
        return true;
    }

    private void eval(CommandSender sender, String rest) {
        ArchitectService svc = plugin.service();
        Path out = plugin.getDataFolder().toPath().resolve("eval");
        if (!rest.startsWith("ai")) {
            List<EvalRunner.Row> rows = new EvalRunner().templates(svc.settings().agent().minScore());
            long pass = rows.stream().filter(EvalRunner.Row::passed).count();
            for (EvalRunner.Row r : rows) msg(sender, r.id() + ": " + r.score() + (r.passed() ? "" : " FAIL " + r.note()));
            msg(sender, pass + "/" + rows.size() + " templates pass. Run '/architect eval ai [n]' to benchmark Claude (uses API credits).");
            return;
        }
        if (!svc.aiEnabled()) { msg(sender, "Set anthropic.api-key first."); return; }
        String[] parts = rest.split("\\s+");
        int limit = parts.length > 1 ? parse(parts[1], 0, 0, 100) : 0;
        msg(sender, "Running the Claude benchmark suite in the background" + (limit > 0 ? " (first " + limit + ")" : "") + "; results go to plugins/" + plugin.getName() + "/eval/.");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ArchitectAgent agent = new ArchitectAgent(svc.settings().agent(),
                        new io.github.shengboj0324.architect.ai.PromptLibrary(svc.styles(), plugin.getDataFolder().toPath().resolve("prompts")), plugin.getLogger());
                Path dir = out.resolve("run-" + System.currentTimeMillis());
                List<EvalRunner.Row> rows = new EvalRunner().ai(agent, svc.settings().agent(), new ExemplarStore(plugin.getDataFolder().toPath().resolve("exemplars")),
                        EvalRunner.bundledBenchmarks(), dir, limit);
                Files.writeString(dir.resolve("results.md"), EvalRunner.markdown("Architect benchmark", rows));
                long pass = rows.stream().filter(EvalRunner.Row::passed).count();
                Bukkit.getScheduler().runTask(plugin, () -> msg(sender, "Benchmark done: " + pass + "/" + rows.size() + " passed. See " + dir));
            } catch (Exception e) {
                plugin.getLogger().warning("Eval failed: " + e);
                Bukkit.getScheduler().runTask(plugin, () -> msg(sender, "Benchmark failed: " + e.getMessage()));
            }
        });
    }

    /**
     * /architect survey [x y z [north|south|east|west [width depth]]]: the terrain report Claude would get for a site,
     * from the sender's position (or the given coordinates in the first world, for the console).
     */
    private void survey(CommandSender sender, String[] args) {
        Location loc;
        BlockFace face = BlockFace.NORTH;
        int i = 1;
        if (args.length >= 4) {
            World w = sender instanceof Player p ? p.getWorld() : Bukkit.getWorlds().get(0);
            try {
                loc = new Location(w, Integer.parseInt(args[1]), Integer.parseInt(args[2]), Integer.parseInt(args[3]));
            } catch (NumberFormatException e) { msg(sender, "Usage: /architect survey [x y z [facing [width depth]]]"); return; }
            i = 4;
        } else if (sender instanceof Player p) {
            loc = p.getLocation();
            face = SiteSurvey.facing(p);
        } else { msg(sender, "From the console give coordinates: /architect survey <x> <y> <z> [facing] [width depth]"); return; }
        if (args.length > i) {
            try { face = BlockFace.valueOf(args[i].toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { msg(sender, "Facing must be north, south, east or west."); return; }
            if (!face.isCartesian() || face.getModY() != 0) { msg(sender, "Facing must be north, south, east or west."); return; }
            i++;
        }
        int width = args.length > i ? parse(args[i], 48, 4, 512) : 48, depth = args.length > i + 1 ? parse(args[i + 1], 48, 4, 512) : 48;
        PluginSettings cfg = plugin.service().settings();
        long t0 = System.nanoTime();
        SitePlan plan = SitePlan.survey(loc, face, false, cfg.distanceFromPlayer(), cfg.terrain().scanRadius(), cfg.terrain().defaultNatural(),
                cfg.terrain().gradeMargin(), cfg.terrain().maxGrade());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        String report = plan.plan(width, 64, depth, null, null, false);
        TerrainMap t = plan.terrain();
        msg(sender, "Surveyed " + (t.maxX() - t.minX() + 1) + " x " + (t.maxZ() - t.minZ() + 1) + " columns in " + ms + " ms (" + Math.round(plan.terrain().coverage() * 100)
                + "% loaded) for a " + width + " x " + depth + " site facing " + face.name().toLowerCase(Locale.ROOT) + " at " + loc.getBlockX() + " " + loc.getBlockY() + " " + loc.getBlockZ() + ":");
        for (String line : (plan.overview() + "\n" + report).split("\n")) sender.sendMessage(Component.text(line, NamedTextColor.GRAY));
    }

    private static void help(CommandSender s, String label) {
        msg(s, "Architect commands:");
        String[][] lines = {
                {"talk <message>", "talk to the Architect (or start any chat message with its name)"},
                {"build <description>", "Claude designs and builds it in front of you"},
                {"expand <description>", "grow your last build (next generation)"},
                {"renovate [what to do]", "fix and renovate the build you look at (or your pos1/pos2 selection)"},
                {"repair", "quick free repair of the build you look at: patch holes, drain water, light it"},
                {"inspect", "what the Architect sees in the build you look at: floors, rooms, damage, dark spots"},
                {"pos1 | pos2 | deselect", "select a build by two corners (the block you look at)"},
                {"template <name> [style] [scale] [generations]", "build a curated master design instantly"},
                {"undo", "remove your last build"},
                {"cancel", "stop the current design or construction"},
                {"rate <1-5>", "teach the Architect what you like"},
                {"follow | stay | status | styles | templates | summon | dismiss", ""}};
        for (String[] l : lines) s.sendMessage(Component.text(" /" + label + " " + l[0], NamedTextColor.YELLOW).append(Component.text(l[1].isEmpty() ? "" : " - " + l[1], NamedTextColor.GRAY)));
    }

    private static boolean admin(CommandSender s) {
        if (s.hasPermission("architect.admin")) return true;
        msg(s, "Admins only.");
        return false;
    }

    private static int parse(String s, int def, int lo, int hi) {
        try { return Math.max(lo, Math.min(hi, Integer.parseInt(s))); } catch (NumberFormatException e) { return def; }
    }

    private static void msg(CommandSender s, String text) {
        s.sendMessage(Component.text("[Architect] ", NamedTextColor.GOLD).append(Component.text(text, NamedTextColor.WHITE)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : SUBS) if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("template")) {
            for (String s : Templates.names()) if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("template")) {
            for (String s : plugin.service().styles().names()) if (s.startsWith(args[2].toLowerCase(Locale.ROOT))) out.add(s);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("rate")) {
            out.addAll(List.of("1", "2", "3", "4", "5"));
        }
        return out;
    }
}
