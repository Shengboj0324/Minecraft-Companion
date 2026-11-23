package io.github.shengboj0324.architect.core;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Resolves material specs to concrete block states at a position.
 * <pre>
 *   "stone_bricks"                     literal block
 *   "oak_stairs[facing=north]"         literal with properties
 *   "@wall"                            style role, weighted + clustered noise texture
 *   "@roof:stairs[half=top]"           role converted to a family shape (stairs|slab|wall|fence|full|trapdoor|door|log)
 *   "spruce:slab"                      literal family shape
 * </pre>
 */
public final class Materials {

    /** Optional server-side validator (Bukkit.createBlockData). Returns an error message or null. */
    public interface StateValidator { String validate(BlockState state); }

    private record Spec(String base, boolean role, String shape, Map<String, String> props) {}

    private final BlockCatalog catalog = BlockCatalog.get();
    private final long seed;
    private Style style;
    private StateValidator validator;
    private final Map<String, Spec> specCache = new HashMap<>();
    private final Map<String, String> validated = new HashMap<>();
    private double textureScale = 2.6;

    public Materials(Style style, long seed) {
        this.style = style;
        this.seed = seed;
    }

    private static final String[] FALLBACK_ROLES = {"trim", "wall", "foundation", "accent", "column", "roof_trim", "wall_alt", "path", "roof"};

    public Style style() { return style; }
    public void setStyle(Style style) { this.style = style; }
    public void setValidator(StateValidator v) { this.validator = v; validated.clear(); }
    public BlockCatalog catalog() { return catalog; }

    /** Resolve a spec at a position. Throws IllegalArgumentException with an actionable message on failure. */
    public BlockState resolve(String spec, int x, int y, int z) {
        Spec p = specCache.get(spec);
        if (p == null) {
            p = parse(spec);
            specCache.put(spec, p);
        }
        String id;
        if (p.role) {
            List<Style.Weighted> list = style.role(p.base);
            if (list == null || list.isEmpty()) throw new IllegalArgumentException("unknown role '@" + p.base + "'. Roles: " + style.roles().keySet());
            String pick = pickWeighted(list, x, y, z, p.base.hashCode());
            id = applyShape(pick, p.shape);
            if (id == null) id = applyShape(list.get(0).block(), p.shape);
            if (id == null) {
                for (Style.Weighted w : list) { id = applyShape(w.block(), p.shape); if (id != null) break; }
            }
            if (id == null) {
                // the role's block has no such shape (e.g. smooth_quartz has no wall): borrow it from a related role, then a neutral default
                for (String other : FALLBACK_ROLES) {
                    List<Style.Weighted> ol = other.equals(p.base) ? null : style.role(other);
                    if (ol == null) continue;
                    for (Style.Weighted w : ol) { id = applyShape(w.block(), p.shape); if (id != null) break; }
                    if (id != null) break;
                }
                if (id == null) id = switch (p.shape) {
                    case "wall" -> "stone_brick_wall";
                    case "fence" -> "spruce_fence";
                    case "slab" -> "stone_brick_slab";
                    case "stairs" -> "stone_brick_stairs";
                    default -> null;
                };
            }
            if (id == null) throw new IllegalArgumentException("role '@" + p.base + "' (" + list.get(0).block() + ") has no '" + p.shape + "' variant; use a literal block instead");
        } else {
            id = applyShape(p.base, p.shape);
            if (id == null) {
                if (!catalog.exists(p.base)) throw unknown(p.base);
                throw new IllegalArgumentException("'" + p.base + "' has no '" + p.shape + "' variant");
            }
        }
        if (!catalog.exists(id)) throw unknown(id);
        BlockState state = p.props.isEmpty() ? BlockState.of(id) : BlockState.of(id, p.props);
        state = autoProps(state);
        if (validator != null) {
            final BlockState check = state;
            String err = validated.computeIfAbsent(check.toString(), k -> {
                String e = validator.validate(check);
                return e == null ? "" : e;
            });
            if (!err.isEmpty()) throw new IllegalArgumentException(err);
        }
        return state;
    }

    /** Convenience: resolve with explicit extra properties merged over the spec's. */
    public BlockState resolve(String spec, int x, int y, int z, Map<String, String> extra) {
        BlockState s = resolve(spec, x, y, z);
        for (Map.Entry<String, String> e : extra.entrySet()) s = s.with(e.getKey(), e.getValue());
        return s;
    }

    private IllegalArgumentException unknown(String id) {
        List<String> sug = catalog.suggest(id, 6);
        return new IllegalArgumentException("unknown block '" + id + "'" + (sug.isEmpty() ? "" : ". Did you mean: " + String.join(", ", sug)));
    }

    private BlockState autoProps(BlockState s) {
        BlockCatalog.Kind k = catalog.kind(s.id());
        if (k == BlockCatalog.Kind.LEAVES && s.prop("persistent") == null) s = s.with("persistent", "true");
        return s;
    }

    private String applyShape(String id, String shape) {
        if (shape == null) return catalog.exists(id) ? id : null;
        return switch (shape) {
            case "stairs" -> catalog.stairsOf(id);
            case "slab" -> catalog.slabOf(id);
            case "wall" -> catalog.wallOf(id) != null ? catalog.wallOf(id) : catalog.fenceOf(id);
            case "fence" -> catalog.fenceOf(id) != null ? catalog.fenceOf(id) : catalog.wallOf(id);
            case "full", "block" -> catalog.fullOf(id);
            case "trapdoor" -> catalog.trapdoorOf(id);
            case "door" -> catalog.doorOf(id);
            case "log" -> catalog.logOf(id);
            default -> throw new IllegalArgumentException("unknown shape ':" + shape + "' (use stairs, slab, wall, fence, full, trapdoor, door, log)");
        };
    }

    private static Spec parse(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("empty block spec");
        String s = raw.trim().toLowerCase(Locale.ROOT);
        Map<String, String> props = new TreeMap<>();
        int b = s.indexOf('[');
        if (b >= 0) {
            if (!s.endsWith("]")) throw new IllegalArgumentException("unclosed '[' in " + raw);
            String body = s.substring(b + 1, s.length() - 1);
            for (String part : body.split(",")) {
                if (part.isBlank()) continue;
                int eq = part.indexOf('=');
                if (eq <= 0) throw new IllegalArgumentException("bad property '" + part + "' in " + raw);
                props.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
            s = s.substring(0, b);
        }
        if (s.startsWith("minecraft:")) s = s.substring(10);
        boolean role = s.startsWith("@");
        if (role) s = s.substring(1);
        String shape = null;
        int c = s.indexOf(':');
        if (c >= 0) {
            shape = s.substring(c + 1);
            s = s.substring(0, c);
        }
        if (s.isEmpty()) throw new IllegalArgumentException("empty block name in " + raw);
        return new Spec(s, role, shape, props);
    }

    // ---------------------------------------------------------------- texture noise

    private String pickWeighted(List<Style.Weighted> list, int x, int y, int z, int salt) {
        if (list.size() == 1) return list.get(0).block();
        double total = 0;
        for (Style.Weighted w : list) total += w.weight();
        double n = 0.65 * valueNoise(x / textureScale, y / textureScale, z / textureScale, salt) + 0.35 * hash01(x, y, z, salt ^ 0x5bd1e995);
        // re-spread the blended noise towards uniform so weights stay meaningful
        n = Math.min(0.999999, Math.max(0, (n - 0.5) * 1.35 + 0.5));
        double t = n * total;
        double acc = 0;
        for (Style.Weighted w : list) {
            acc += w.weight();
            if (t < acc) return w.block();
        }
        return list.get(list.size() - 1).block();
    }

    private double hash01(int x, int y, int z, int salt) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L) ^ salt;
        h ^= h >>> 33; h *= 0xff51afd7ed558ccdL; h ^= h >>> 33; h *= 0xc4ceb9fe1a85ec53L; h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    private double valueNoise(double x, double y, double z, int salt) {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y), z0 = (int) Math.floor(z);
        double fx = smooth(x - x0), fy = smooth(y - y0), fz = smooth(z - z0);
        double c000 = hash01(x0, y0, z0, salt), c100 = hash01(x0 + 1, y0, z0, salt);
        double c010 = hash01(x0, y0 + 1, z0, salt), c110 = hash01(x0 + 1, y0 + 1, z0, salt);
        double c001 = hash01(x0, y0, z0 + 1, salt), c101 = hash01(x0 + 1, y0, z0 + 1, salt);
        double c011 = hash01(x0, y0 + 1, z0 + 1, salt), c111 = hash01(x0 + 1, y0 + 1, z0 + 1, salt);
        double x00 = lerp(c000, c100, fx), x10 = lerp(c010, c110, fx), x01 = lerp(c001, c101, fx), x11 = lerp(c011, c111, fx);
        return lerp(lerp(x00, x10, fy), lerp(x01, x11, fy), fz);
    }

    private static double smooth(double t) { return t * t * (3 - 2 * t); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    /** Deterministic per-position random in [0,1) usable by generators for organic variation. */
    public double random(int x, int y, int z, int salt) { return hash01(x, y, z, salt); }
}
