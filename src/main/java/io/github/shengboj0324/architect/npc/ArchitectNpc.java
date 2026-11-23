package io.github.shengboj0324.architect.npc;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.entity.LookAnchor;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * The Architect in the world: a Mannequin (a player-model entity that ships with Paper, no client mods) driven
 * like a player. Mannequins have no AI of their own, so this class moves it every tick: it walks along A* paths with
 * step-ups and drops (the client animates the legs from the movement), follows a player, goes where it is sent,
 * looks at whoever talks to it, flies to the work face while building, and shows what it says in a speech bubble.
 */
public final class ArchitectNpc {

    public enum Mode { IDLE, FOLLOW, GOTO, STAY }

    private static final double WALK = 0.22, RUN = 0.32, FLY = 0.7;

    private final String name;
    private final String skin;
    private Mannequin entity;
    private TextDisplay bubble;
    private int bubbleTicks;
    private int ticks;
    private Material held;

    private Mode mode = Mode.IDLE;
    private UUID followId;
    private Location goal;
    private UUID lookId;
    private int lookTicks;
    private List<int[]> path;
    private int pathIndex;
    private double segT;
    private Location segFrom;
    private int repathIn;
    private boolean flying;

    public ArchitectNpc(String name, String skin) {
        this.name = name;
        this.skin = skin;
    }

    public boolean alive() { return entity != null && entity.isValid(); }
    public Mannequin entity() { return entity; }
    public Mode mode() { return mode; }
    public String name() { return name; }
    public UUID following() { return mode == Mode.FOLLOW ? followId : null; }

    public void spawn(Location at) {
        if (alive()) {
            if (entity.getWorld() != at.getWorld() || entity.getLocation().distanceSquared(at) > 4) teleportPoof(at);
            return;
        }
        entity = at.getWorld().spawn(at, Mannequin.class, m -> {
            m.customName(Component.text(name, NamedTextColor.GOLD));
            m.setCustomNameVisible(true);
            m.setDescription(Component.text("Master Builder", NamedTextColor.GRAY));
            if (skin != null && !skin.isBlank()) m.setProfile(ResolvableProfile.resolvableProfile().name(skin).build());
            m.setInvulnerable(true);
            m.setPersistent(false);
            m.setImmovable(true);
            m.setGravity(false);
            m.setSilent(true);
            m.getEquipment().setHelmet(new ItemStack(Material.LEATHER_HELMET));
        });
        at.getWorld().spawnParticle(Particle.CLOUD, at.clone().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.02);
    }

    public void despawn() {
        removeBubble();
        if (entity != null) entity.remove();
        entity = null;
        path = null;
    }

    // ------------------------------------------------------------------------------------------------ commands

    public void follow(Player p) {
        ensureNear(p);
        mode = Mode.FOLLOW;
        followId = p.getUniqueId();
        path = null;
        repathIn = 0;
    }

    public void stay() {
        mode = Mode.STAY;
        path = null;
        settle();
    }

    public void goTo(Location where) {
        if (!alive()) spawn(where.clone().add(0, 0.1, 0));
        mode = Mode.GOTO;
        goal = where.clone();
        path = null;
        repathIn = 0;
    }

    /** Turn toward a player for a while (they are talking to us). */
    public void attend(Player p) {
        ensureNear(p);
        lookId = p.getUniqueId();
        lookTicks = 100;
    }

    /** Makes sure the NPC exists close enough to the player to be seen; spawns or teleports it otherwise. */
    public void ensureNear(Player p) {
        Location pl = p.getLocation();
        if (!alive() || entity.getWorld() != pl.getWorld() || entity.getLocation().distanceSquared(pl) > 64 * 64) {
            Location at = pl.clone().add(pl.getDirection().setY(0).normalize().multiply(2.5));
            int[] g = ground(at.getWorld(), at.getBlockX(), at.getBlockY() + 2, at.getBlockZ());
            spawn(g == null ? at : new Location(at.getWorld(), g[0] + 0.5, g[1], g[2] + 0.5));
        }
    }

    public boolean near(Location l, double r) {
        return alive() && entity.getWorld() == l.getWorld() && entity.getLocation().distanceSquared(l) <= r * r;
    }

    /** Shows a speech bubble above the NPC for a few seconds. */
    public void say(String text) {
        if (!alive()) return;
        removeBubble();
        String shown = text.length() > 240 ? text.substring(0, 237) + "..." : text;
        bubble = entity.getWorld().spawn(entity.getLocation().add(0, 2.4, 0), TextDisplay.class, d -> {
            d.text(Component.text(shown, NamedTextColor.WHITE));
            d.setBillboard(Display.Billboard.CENTER);
            d.setLineWidth(180);
            d.setPersistent(false);
            d.setSeeThrough(false);
            d.setShadowed(true);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(150, 20, 20, 30));
            d.setTransformation(new Transformation(new Vector3f(0, 0.55f, 0), new AxisAngle4f(), new Vector3f(0.9f, 0.9f, 0.9f), new AxisAngle4f()));
        });
        entity.addPassenger(bubble);
        bubbleTicks = 60 + Math.min(200, shown.length() * 2);
    }

    private void removeBubble() {
        if (bubble != null) bubble.remove();
        bubble = null;
    }

    // ------------------------------------------------------------------------------------------------ building

    /** Called every tick while building, with the most recent block placed: fly beside the work face like a creative player. */
    public void work(World world, int x, int y, int z, BlockData data, boolean effects) {
        if (!alive() || entity.getWorld() != world) spawn(new Location(world, x + 0.5, y + 2, z + 0.5));
        ticks++;
        flying = true;
        path = null;
        tickBubble();
        Location cur = entity.getLocation();
        Vector target = new Vector(x + 0.5, y + 1.6, z - 1.5);
        Vector delta = target.clone().subtract(cur.toVector());
        double len = delta.length();
        if (len > 48) teleportPoof(target.toLocation(world));
        else if (len > 0.05) {
            Vector step = len > 1.5 ? delta.multiply(1.5 / len) : delta;
            move(cur.clone().add(step));
        }
        entity.lookAt(x + 0.5, y + 0.5, z + 0.5, LookAnchor.EYES);
        Material m = data.getPlacementMaterial();
        if (m != held && m.isItem() && !m.isAir()) {
            entity.getEquipment().setItemInMainHand(new ItemStack(m));
            held = m;
        }
        if (ticks % 3 == 0) entity.swingMainHand();
        if (effects && ticks % 2 == 0) {
            Location at = new Location(world, x + 0.5, y + 0.5, z + 0.5);
            world.spawnParticle(Particle.BLOCK, at, 6, data);
            if (ticks % 4 == 0) world.playSound(at, data.getSoundGroup().getPlaceSound(), SoundCategory.BLOCKS, 0.6f, 1.0f);
        }
    }

    /** Idle flourish while Claude is designing: enchantment particles around the builder. */
    public void thinking() {
        if (!alive()) return;
        if (ticks % 5 == 0) entity.getWorld().spawnParticle(Particle.ENCHANT, entity.getLocation().add(0, 2.2, 0), 12, 0.4, 0.4, 0.4, 0.6);
        if (ticks % 40 == 0) entity.swingMainHand();
    }

    // ------------------------------------------------------------------------------------------------ movement

    /** Called every tick when the NPC is not building. */
    public void tick() {
        if (!alive()) return;
        ticks++;
        tickBubble();
        if (held != null && ticks % 200 == 0) { entity.getEquipment().setItemInMainHand(null); held = null; }
        World w = entity.getWorld();
        Location here = entity.getLocation();
        switch (mode) {
            case FOLLOW -> {
                Player p = followId == null ? null : Bukkit.getPlayer(followId);
                if (p == null || !p.isOnline()) { mode = Mode.IDLE; break; }
                Location pl = p.getLocation();
                if (pl.getWorld() != w || pl.distanceSquared(here) > 72 * 72) {
                    teleportPoof(behind(p));
                    path = null;
                    break;
                }
                double d = Math.hypot(pl.getX() - here.getX(), pl.getZ() - here.getZ());
                if (d > 3.5 && (path == null || --repathIn <= 0)) {
                    plan(pl, 2.2);
                    repathIn = 10;
                }
                if (d <= 2.6) path = null;
            }
            case GOTO -> {
                if (goal == null || goal.getWorld() != w) { mode = Mode.IDLE; break; }
                double d = Math.hypot(goal.getX() - here.getX(), goal.getZ() - here.getZ());
                if (d < 1.2 && path == null) { mode = Mode.STAY; break; }
                if (path == null && --repathIn <= 0) { plan(goal, 0.9); repathIn = 40; }
            }
            case IDLE, STAY -> { }
        }
        if (path != null) step(w);
        else {
            if (flying || ticks % 10 == 0) settle();
            lookAround();
        }
    }

    private void plan(Location target, double reach) {
        World w = entity.getWorld();
        Location here = entity.getLocation();
        int[] start = ground(w, here.getBlockX(), (int) Math.floor(here.getY() + 0.2), here.getBlockZ());
        int[] g = ground(w, target.getBlockX(), (int) Math.floor(target.getY() + 0.2), target.getBlockZ());
        if (g == null) g = new int[]{target.getBlockX(), target.getBlockY(), target.getBlockZ()};
        List<int[]> p = start == null ? null : PathFinder.find(terrain(w), start, g, 3000, reach);
        if (p == null || p.isEmpty()) {
            // no walkable route (gap, wall, water): fly there like a creative player would
            double dist = here.distance(target);
            if (dist > reach + 1) {
                path = List.of(new int[]{g[0], g[1], g[2]});
                flying = true;
            } else path = null;
        } else {
            path = p;
            flying = start == null || here.getY() - start[1] > 0.6;
        }
        pathIndex = 0;
        segT = 0;
        segFrom = here.clone();
    }

    private void step(World w) {
        if (pathIndex >= path.size()) { path = null; flying = false; return; }
        int[] c = path.get(pathIndex);
        Location to = new Location(w, c[0] + 0.5, c[1], c[2] + 0.5);
        double segLen = Math.max(0.01, segFrom.distance(to));
        double far = mode == Mode.FOLLOW ? distanceToFollowed() : 0;
        double speed = flying ? FLY : far > 8 ? RUN : WALK;
        segT = Math.min(1, segT + speed / segLen);
        double x = segFrom.getX() + (to.getX() - segFrom.getX()) * segT;
        double z = segFrom.getZ() + (to.getZ() - segFrom.getZ()) * segT;
        double dy = to.getY() - segFrom.getY();
        double y;
        if (flying) y = segFrom.getY() + dy * segT + Math.sin(Math.PI * segT) * Math.min(3, segLen * 0.2);
        else if (dy > 0.1) y = segFrom.getY() + dy * Math.min(1, segT / 0.45) + Math.sin(Math.PI * Math.min(1, segT / 0.45)) * 0.35; // hop up
        else if (dy < -0.1) y = segT < 0.5 ? segFrom.getY() : segFrom.getY() + dy * Math.min(1, (segT - 0.5) / 0.5 * (segT - 0.5) / 0.5 * 4); // walk off, then fall
        else y = segFrom.getY() + dy * segT;
        float yaw = (float) Math.toDegrees(Math.atan2(-(to.getX() - segFrom.getX()), to.getZ() - segFrom.getZ()));
        Location next = new Location(w, x, y, z, yaw, 0);
        move(next);
        if (flying && ticks % 3 == 0) w.spawnParticle(Particle.CLOUD, next.clone().add(0, 0.1, 0), 1, 0.1, 0, 0.1, 0);
        if (segT >= 1) {
            pathIndex++;
            segT = 0;
            segFrom = to;
            if (pathIndex >= path.size()) { path = null; flying = false; }
        }
    }

    private double distanceToFollowed() {
        Player p = followId == null ? null : Bukkit.getPlayer(followId);
        return p == null || p.getWorld() != entity.getWorld() ? 0 : p.getLocation().distance(entity.getLocation());
    }

    /** Drops to the ground below when hovering (after building or flying). */
    private void settle() {
        if (!alive()) return;
        World w = entity.getWorld();
        Location here = entity.getLocation();
        int[] g = ground(w, here.getBlockX(), (int) Math.floor(here.getY() + 0.2), here.getBlockZ());
        if (g == null) return;
        double gy = g[1];
        if (here.getY() - gy > 0.05) {
            double ny = Math.max(gy, here.getY() - 0.45);
            move(new Location(w, here.getX(), ny, here.getZ(), here.getYaw(), here.getPitch()));
        } else flying = false;
    }

    private void lookAround() {
        Player target = null;
        if (lookTicks > 0) { lookTicks--; target = lookId == null ? null : Bukkit.getPlayer(lookId); }
        if (target == null && mode == Mode.FOLLOW && followId != null) target = Bukkit.getPlayer(followId);
        if (target == null && ticks % 20 == 0) {
            double best = 10 * 10;
            for (Player p : entity.getWorld().getPlayers()) {
                double d = p.getLocation().distanceSquared(entity.getLocation());
                if (d < best) { best = d; target = p; }
            }
            if (target != null) { lookId = target.getUniqueId(); lookTicks = 40; }
        }
        if (target != null && target.getWorld() == entity.getWorld()) {
            Location e = target.getEyeLocation();
            entity.lookAt(e.getX(), e.getY(), e.getZ(), LookAnchor.EYES);
        }
    }

    private void tickBubble() {
        if (bubble != null && --bubbleTicks <= 0) removeBubble();
    }

    private Location behind(Player p) {
        Location l = p.getLocation();
        Location at = l.clone().subtract(l.getDirection().setY(0).normalize().multiply(2.5));
        int[] g = ground(l.getWorld(), at.getBlockX(), at.getBlockY() + 2, at.getBlockZ());
        return g == null ? l : new Location(l.getWorld(), g[0] + 0.5, g[1], g[2] + 0.5);
    }

    private void teleportPoof(Location to) {
        if (!alive()) { spawn(to); return; }
        Location from = entity.getLocation();
        from.getWorld().spawnParticle(Particle.CLOUD, from.clone().add(0, 1, 0), 15, 0.3, 0.6, 0.3, 0.02);
        if (from.getWorld() != to.getWorld()) removeBubble(); // passengers cannot cross worlds
        move(to);
        to.getWorld().spawnParticle(Particle.CLOUD, to.clone().add(0, 1, 0), 15, 0.3, 0.6, 0.3, 0.02);
    }

    /** Teleport; since Paper 1.21.10 passengers (the speech bubble) ride along by default. */
    private void move(Location to) {
        entity.teleport(to);
    }

    // ------------------------------------------------------------------------------------------------ terrain

    static int[] ground(World w, int x, int y, int z) { return PathFinder.ground(terrain(w), x, y, z, 24); }

    static PathFinder.Terrain terrain(World w) {
        return new PathFinder.Terrain() {
            @Override public boolean solid(int x, int y, int z) {
                if (y < w.getMinHeight() || y >= w.getMaxHeight() || !w.isChunkLoaded(x >> 4, z >> 4)) return false;
                Block b = w.getBlockAt(x, y, z);
                return b.isSolid() || b.isLiquid() && b.getType() == Material.WATER && !w.getBlockAt(x, y + 1, z).isLiquid();
            }
            @Override public boolean passable(int x, int y, int z) {
                if (y < w.getMinHeight() || y >= w.getMaxHeight() || !w.isChunkLoaded(x >> 4, z >> 4)) return false;
                Block b = w.getBlockAt(x, y, z);
                if (b.getType() == Material.LAVA || b.getType() == Material.FIRE || b.getType() == Material.POWDER_SNOW) return false;
                return b.isPassable();
            }
        };
    }
}
