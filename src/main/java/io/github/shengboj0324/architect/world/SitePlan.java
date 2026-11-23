package io.github.shengboj0324.architect.world;

import io.github.shengboj0324.architect.core.Ground;
import io.github.shengboj0324.architect.core.VoxelModel;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;

/**
 * Where a design will stand, fixed while it is designed so Claude can design against the real land. The terrain
 * around the site is surveyed when the request comes in (main thread). When Claude plans an envelope, the site frame
 * is fixed: the rotation that faces the requester, the x/z origin (the envelope's front centred a few blocks ahead,
 * or the envelope centred on a chosen point), and the ground layer's world y from the terrain. The finished design
 * is placed with exactly this frame, so the terrain heights Claude saw are where the blocks land.
 */
public final class SitePlan {

    /** Margin (blocks) of terrain shown around the envelope in site reports and renders. */
    public static final int MARGIN = 8;

    private final World world;
    private final int ax, ay, az;
    private final BlockFace look;
    private final boolean centred;
    private final int distance;
    private final TerrainMap terrain;
    private final int gradeMargin, maxGrade;
    private volatile Placement frame;
    private volatile boolean natural;
    private volatile int offset;

    public SitePlan(World world, int ax, int ay, int az, BlockFace look, boolean centred, int distance, TerrainMap terrain, boolean natural,
                    int gradeMargin, int maxGrade) {
        this.world = world;
        this.ax = ax;
        this.ay = ay;
        this.az = az;
        this.look = look;
        this.centred = centred;
        this.distance = distance;
        this.terrain = terrain;
        this.natural = natural;
        this.gradeMargin = gradeMargin;
        this.maxGrade = maxGrade;
    }

    /**
     * Surveys the land for a site (main thread): {@code radius} blocks to either side and far enough ahead for a
     * large envelope, or a square around a centred site. Only loaded chunks are read.
     */
    public static SitePlan survey(Location point, BlockFace look, boolean centred, int distance, int radius, boolean natural, int gradeMargin, int maxGrade) {
        World w = point.getWorld();
        int px = point.getBlockX(), pz = point.getBlockZ();
        int x1, z1, x2, z2;
        if (centred) {
            x1 = px - radius; x2 = px + radius; z1 = pz - radius; z2 = pz + radius;
        } else {
            int fx = look.getModX(), fz = look.getModZ();
            int[] as = {-8, distance + 2 * radius}, bs = {-radius, radius};
            x1 = z1 = Integer.MAX_VALUE;
            x2 = z2 = Integer.MIN_VALUE;
            for (int a : as) for (int b : bs) {
                int x = px + fx * a - fz * b, z = pz + fz * a + fx * b;
                x1 = Math.min(x1, x); x2 = Math.max(x2, x); z1 = Math.min(z1, z); z2 = Math.max(z2, z);
            }
        }
        TerrainMap t = TerrainScanner.scan(w, x1, z1, x2, z2);
        return new SitePlan(w, px, point.getBlockY(), pz, look, centred, distance, t, natural, gradeMargin, maxGrade);
    }

    public World world() { return world; }
    public TerrainMap terrain() { return terrain; }
    public boolean natural() { return natural; }
    public int offset() { return offset; }
    public int gradeMargin() { return gradeMargin; }
    public int maxGrade() { return maxGrade; }

    /** The fixed frame, or null before the first plan. */
    public Placement frame() { return frame; }

    /** The terrain in model coordinates, or null before the first plan. */
    public TerrainMap.View view() { Placement f = frame; return f == null ? null : terrain.view(f); }

    /** Terrain support for the quality critic: only in natural mode, where the land is kept. */
    public Ground ground() { return natural ? view() : null; }

    /** The frame's x/z origin for an envelope, with y left at the anchor. */
    private Placement frameXZ(int width, int depth) {
        int q = Placement.quarterFacing(look.getOppositeFace());
        int ox, oz;
        if (centred) {
            int[] mid = Placement.rot((width - 1) / 2, (depth - 1) / 2, q);
            ox = ax - mid[0];
            oz = az - mid[1];
        } else {
            int tx = ax + look.getModX() * (distance + 1), tz = az + look.getModZ() * (distance + 1);
            int[] front = Placement.rot((width - 1) / 2, -1, q);
            ox = tx - front[0];
            oz = tz - front[1];
        }
        return new Placement(world, ox, ay - 1, oz, q);
    }

    /**
     * Fixes (or updates) the frame for an envelope and returns the site report. The x/z origin is kept when
     * {@code keep} is true and a frame exists, so a growing design never moves; the ground layer's height follows
     * the terrain's median under the envelope plus the offset. {@code mode} ("level" / "natural") and {@code offset}
     * may be null to keep the current choice.
     */
    public synchronized String plan(int width, int height, int depth, String mode, Integer offset, boolean keep) {
        if (mode != null) natural = mode.equalsIgnoreCase("natural");
        int previousOffset = this.offset;
        if (offset != null) this.offset = offset;
        boolean kept = keep && frame != null;
        Placement xz = kept ? frame : frameXZ(width, depth);
        // a kept frame keeps its height (work so far was designed against it); only an explicit offset change moves it
        int oy = kept ? frame.oy() + (this.offset - previousOffset) : terrain.reference(xz, 0, 0, width - 1, depth - 1, ay - 1) + this.offset;
        oy = Math.max(world.getMinHeight() + 1, Math.min(oy, world.getMaxHeight() - height - 2));
        frame = new Placement(world, xz.ox(), oy, xz.oz(), xz.quarter());
        return terrain.report(frame, width, height, depth, natural, MARGIN);
    }

    /** The opening overview of the land ahead (or around a centred site), before an envelope exists. */
    public String overview() {
        Placement xz = frameXZ(64, 64);
        int ref = terrain.reference(xz, 0, 0, 63, 63, ay - 1);
        return terrain.overview(new Placement(world, xz.ox(), ref, xz.oz(), xz.quarter()), 64, 64);
    }

    /** The design shown on its land for render_views (the design itself when no frame exists yet). */
    public VoxelModel overlay(VoxelModel design) {
        Placement f = frame;
        return f == null ? design : terrain.overlay(design, f, natural, MARGIN, gradeMargin, maxGrade);
    }

    /** Full-resolution terrain of a model rectangle (survey_terrain), or null before the first plan. */
    public String survey(int x1, int z1, int x2, int z2, int step) {
        Placement f = frame;
        return f == null ? null : terrain.survey(f, x1, z1, x2, z2, step);
    }
}
