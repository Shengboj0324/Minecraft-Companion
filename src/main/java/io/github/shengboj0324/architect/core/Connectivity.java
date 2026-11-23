package io.github.shengboj0324.architect.core;

/**
 * Recomputes neighbour-dependent block states the way vanilla does on placement, because the world placer
 * sets blocks without physics (fast, no item drops). Covers fences, panes/bars, walls and stair corner shapes.
 */
public final class Connectivity {

    private Connectivity() {}

    public static int apply(VoxelModel m) {
        BlockCatalog cat = BlockCatalog.get();
        int changed = 0;
        for (long k : m.keys()) {
            BlockState s = m.getByKey(k);
            if (s == null || s.isAir()) continue;
            BlockCatalog.Kind kind = cat.kind(s.id());
            int x = VoxelModel.kx(k), y = VoxelModel.ky(k), z = VoxelModel.kz(k);
            BlockState n = switch (kind) {
                case FENCE -> fence(m, cat, s, x, y, z);
                case PANE -> pane(m, cat, s, x, y, z);
                case WALL -> wall(m, cat, s, x, y, z);
                case STAIRS -> stairs(m, cat, s, x, y, z);
                default -> s;
            };
            if (n != s && !n.equals(s)) { m.set(x, y, z, n); changed++; }
        }
        return changed;
    }

    private static BlockCatalog.Kind kindAt(VoxelModel m, BlockCatalog cat, int x, int y, int z) {
        BlockState s = m.get(x, y, z);
        return s == null ? BlockCatalog.Kind.AIR : cat.kind(s.id());
    }

    private static BlockState fence(VoxelModel m, BlockCatalog cat, BlockState s, int x, int y, int z) {
        boolean nether = s.id().startsWith("nether_brick");
        for (Dir d : Dir.values()) {
            BlockState o = m.get(x + d.dx, y, z + d.dz);
            boolean c = false;
            if (o != null && !o.isAir()) {
                BlockCatalog.Kind k = cat.kind(o.id());
                if (k == BlockCatalog.Kind.FENCE) c = o.id().startsWith("nether_brick") == nether;
                else if (k == BlockCatalog.Kind.GATE) {
                    Dir f = Dir.parse(o.prop("facing"));
                    c = f != null && f.isX() != d.isX();
                } else c = cat.isSolidCube(o);
            }
            s = s.with(d.key(), Boolean.toString(c));
        }
        return s;
    }

    private static BlockState pane(VoxelModel m, BlockCatalog cat, BlockState s, int x, int y, int z) {
        for (Dir d : Dir.values()) {
            BlockState o = m.get(x + d.dx, y, z + d.dz);
            boolean c = false;
            if (o != null && !o.isAir()) {
                BlockCatalog.Kind k = cat.kind(o.id());
                c = k == BlockCatalog.Kind.PANE || k == BlockCatalog.Kind.WALL || cat.isSolidCube(o);
            }
            s = s.with(d.key(), Boolean.toString(c));
        }
        return s;
    }

    private static BlockState wall(VoxelModel m, BlockCatalog cat, BlockState s, int x, int y, int z) {
        boolean[] con = new boolean[4];
        BlockState above = m.get(x, y + 1, z);
        boolean aboveSolid = above != null && !above.isAir() && (cat.isSolidCube(above) || cat.kind(above.id()) == BlockCatalog.Kind.WALL);
        for (Dir d : Dir.values()) {
            BlockState o = m.get(x + d.dx, y, z + d.dz);
            boolean c = false;
            if (o != null && !o.isAir()) {
                BlockCatalog.Kind k = cat.kind(o.id());
                c = k == BlockCatalog.Kind.WALL || k == BlockCatalog.Kind.PANE || cat.isSolidCube(o)
                        || (k == BlockCatalog.Kind.GATE && Dir.parse(o.prop("facing")) != null && Dir.parse(o.prop("facing")).isX() != d.isX());
            }
            con[d.ordinal()] = c;
            boolean tall = false;
            if (c && aboveSolid) {
                BlockState ao = m.get(x + d.dx, y + 1, z + d.dz);
                tall = cat.isSolidCube(above) || (ao != null && !ao.isAir());
            }
            s = s.with(d.key(), c ? (tall ? "tall" : "low") : "none");
        }
        boolean ns = con[0] && con[2] && !con[1] && !con[3];
        boolean ew = con[1] && con[3] && !con[0] && !con[2];
        boolean up = !(ns || ew);
        if (!up && above != null && !above.isAir()) {
            BlockCatalog.Kind ak = cat.kind(above.id());
            if (ak == BlockCatalog.Kind.LIGHT || ak == BlockCatalog.Kind.DECOR || (ak == BlockCatalog.Kind.WALL && "true".equals(above.prop("up")))) up = true;
        }
        return s.with("up", Boolean.toString(up));
    }

    private static boolean isStairs(BlockCatalog cat, BlockState s) {
        return s != null && cat.kind(s.id()) == BlockCatalog.Kind.STAIRS;
    }

    private static BlockState stairs(VoxelModel m, BlockCatalog cat, BlockState s, int x, int y, int z) {
        Dir facing = Dir.parse(s.prop("facing"));
        if (facing == null) return s;
        String half = s.prop("half") == null ? "bottom" : s.prop("half");
        String shape = "straight";
        BlockState behind = m.get(x + facing.dx, y, z + facing.dz);
        if (isStairs(cat, behind) && half.equals(orBottom(behind.prop("half")))) {
            Dir d = Dir.parse(behind.prop("facing"));
            if (d != null && d.isX() != facing.isX() && canTakeShape(m, cat, s, facing, half, x, y, z, d.opposite())) {
                shape = d == facing.ccw() ? "outer_left" : "outer_right";
            }
        }
        if (shape.equals("straight")) {
            BlockState front = m.get(x - facing.dx, y, z - facing.dz);
            if (isStairs(cat, front) && half.equals(orBottom(front.prop("half")))) {
                Dir d = Dir.parse(front.prop("facing"));
                if (d != null && d.isX() != facing.isX() && canTakeShape(m, cat, s, facing, half, x, y, z, d)) {
                    shape = d == facing.ccw() ? "inner_left" : "inner_right";
                }
            }
        }
        return s.with("shape", shape);
    }

    private static String orBottom(String h) { return h == null ? "bottom" : h; }

    private static boolean canTakeShape(VoxelModel m, BlockCatalog cat, BlockState s, Dir facing, String half, int x, int y, int z, Dir dir) {
        BlockState o = m.get(x + dir.dx, y, z + dir.dz);
        if (!isStairs(cat, o)) return true;
        return Dir.parse(o.prop("facing")) != facing || !half.equals(orBottom(o.prop("half")));
    }
}
