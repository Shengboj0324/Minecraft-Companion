package io.github.shengboj0324.architect;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Per-player daily usage (designs started, Claude tokens used), persisted so limits hold across restarts. Servers
 * set limits.designs-per-day and limits.tokens-per-day; players with architect.unlimited are not limited.
 */
final class Usage {

    private record Day(int designs, long tokens) {}

    private final Path file;
    private final Logger log;
    private final Map<String, Day> days = new ConcurrentHashMap<>(); // "uuid@date"

    Usage(Path file, Logger log) {
        this.file = file;
        this.log = log;
        if (Files.isRegularFile(file)) {
            Properties p = new Properties();
            try (var in = Files.newInputStream(file)) {
                p.load(in);
                String today = LocalDate.now().toString();
                for (String k : p.stringPropertyNames()) {
                    if (!k.endsWith("@" + today)) continue; // older days are dropped
                    String[] v = p.getProperty(k).split(",");
                    days.put(k, new Day(Integer.parseInt(v[0]), Long.parseLong(v[1])));
                }
            } catch (IOException | RuntimeException e) {
                log.warning("Could not read " + file + ": " + e);
            }
        }
    }

    private static String key(UUID id) { return id + "@" + LocalDate.now(); }

    /** Null when the player may start another design today, else the reason they may not. */
    String check(UUID id, PluginSettings.Quotas q) {
        Day d = days.getOrDefault(key(id), new Day(0, 0));
        if (q.designsPerDay() > 0 && d.designs() >= q.designsPerDay()) return "You have used all " + q.designsPerDay() + " of today's designs.";
        if (q.tokensPerDay() > 0 && d.tokens() >= q.tokensPerDay()) return "You have used today's design budget.";
        return null;
    }

    void started(UUID id) { days.merge(key(id), new Day(1, 0), (a, b) -> new Day(a.designs() + 1, a.tokens())); save(); }

    void tokens(UUID id, long n) { days.merge(key(id), new Day(0, n), (a, b) -> new Day(a.designs(), a.tokens() + n)); save(); }

    String describe(UUID id) {
        Day d = days.getOrDefault(key(id), new Day(0, 0));
        return d.designs() + " design(s) and " + d.tokens() + " tokens today";
    }

    private synchronized void save() {
        Properties p = new Properties();
        for (Map.Entry<String, Day> e : days.entrySet()) p.setProperty(e.getKey(), e.getValue().designs() + "," + e.getValue().tokens());
        try {
            Files.createDirectories(file.getParent());
            try (var out = Files.newOutputStream(file)) { p.store(out, "ClaudeArchitect daily usage"); }
        } catch (IOException e) {
            log.warning("Could not save " + file + ": " + e);
        }
    }
}
