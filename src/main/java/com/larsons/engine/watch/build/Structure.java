package com.larsons.engine.watch.build;

import com.larsons.engine.watch.WatchJson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the party has built, and where.
 *
 * <p><b>A flat list, not a scene graph.</b> A camp is a few dozen pieces and a
 * world is a few hundred at most; a spatial index for that is a structure to
 * keep correct in exchange for microseconds nobody was going to notice. What
 * matters is that a piece is small, immutable, and cheap enough to send: a
 * placement is nine numbers and a key, so a whole treehouse fits comfortably in
 * one snapshot.
 *
 * <p><b>Snapping is the only convenience.</b> Placements snap to a
 * {@value #GRID}-metre grid and to eight compass turns, which is enough that
 * two floors laid side by side actually meet, and little enough that nobody has
 * to fight an alignment system. A piece placed against a tree keeps that tree's
 * id, so a treehouse can be found again — and so a felled tree takes its
 * platform with it.
 */
public final class Structure {

    /** Metres a placement snaps to. */
    public static final double GRID = 0.5;

    /** How many turns a placement can take: the eight compass points. */
    public static final int TURNS = 8;

    /** One built thing, standing somewhere. */
    public record Placement(long id, BuildPiece piece, double x, double y, double z,
                            int turn, long treeId, String builtBy, long atMillis) {

        /** Which way it faces, in radians. */
        public double yaw() { return turn * Math.PI * 2 / TURNS; }

        /** Whether it is fixed to a tree rather than standing on the ground. */
        public boolean inTree() { return treeId != 0; }

        /** Its bounding box's half-extents, after the turn. */
        public double halfX() {
            return turn % 2 == 0 ? piece.sizeX() / 2 : piece.sizeY() / 2;
        }

        public double halfY() {
            return turn % 2 == 0 ? piece.sizeY() / 2 : piece.sizeX() / 2;
        }

        public double halfZ() { return piece.sizeZ() / 2; }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("p", piece.key());
            m.put("x", x);
            m.put("y", y);
            m.put("z", z);
            m.put("r", turn);
            if (treeId != 0) m.put("tree", treeId);
            if (builtBy != null) m.put("by", builtBy);
            m.put("t", atMillis);
            return m;
        }

        static Placement fromMap(Map<String, Object> m) {
            BuildPiece piece = BuildPiece.of(WatchJson.str(m, "p", ""));
            if (piece == null) return null;
            return new Placement(WatchJson.big(m, "id", 0), piece,
                    WatchJson.num(m, "x", 0), WatchJson.num(m, "y", 0),
                    WatchJson.num(m, "z", 0), WatchJson.integer(m, "r", 0),
                    WatchJson.big(m, "tree", 0), WatchJson.str(m, "by", null),
                    WatchJson.big(m, "t", 0));
        }
    }

    private final Map<Long, Placement> pieces = new LinkedHashMap<>();
    private long nextId = 1;

    /**
     * Place a piece, snapped to the grid.
     *
     * @param treeId the tree it is fixed to, or {@code 0} for the ground
     */
    public Placement place(BuildPiece piece, double x, double y, double z, int turn,
                           long treeId, String builtBy, long atMillis) {
        Placement placement = new Placement(nextId++, piece, snap(x), snap(y), snap(z),
                Math.floorMod(turn, TURNS), treeId, builtBy, atMillis);
        pieces.put(placement.id(), placement);
        return placement;
    }

    /** Add a placement that arrived from a host or a save, keeping its id. */
    public void adopt(Placement placement) {
        if (placement == null) return;
        pieces.put(placement.id(), placement);
        nextId = Math.max(nextId, placement.id() + 1);
    }

    /** Take one down. */
    public Placement remove(long id) { return pieces.remove(id); }

    /** Everything standing. */
    public List<Placement> all() { return List.copyOf(pieces.values()); }

    public int size() { return pieces.size(); }

    public Placement byId(long id) { return pieces.get(id); }

    /** Everything within a radius of a point — what the mesher asks for. */
    public List<Placement> near(double x, double y, double radius) {
        List<Placement> out = new ArrayList<>();
        double r2 = radius * radius;
        for (Placement p : pieces.values()) {
            double dx = p.x() - x, dy = p.y() - y;
            if (dx * dx + dy * dy <= r2) out.add(p);
        }
        return out;
    }

    /** Everything fixed to one tree — so felling it takes the platform too. */
    public List<Placement> inTree(long treeId) {
        List<Placement> out = new ArrayList<>();
        for (Placement p : pieces.values()) {
            if (p.treeId() == treeId) out.add(p);
        }
        return out;
    }

    /**
     * Whether a piece would overlap something already standing.
     *
     * <p>A box test on the horizontal plane and the vertical separately, which
     * is exactly right for pieces that are all axis-aligned boxes and is one
     * comparison rather than a separating-axis test.
     */
    public boolean blocked(BuildPiece piece, double x, double y, double z, int turn) {
        double hx = turn % 2 == 0 ? piece.sizeX() / 2 : piece.sizeY() / 2;
        double hy = turn % 2 == 0 ? piece.sizeY() / 2 : piece.sizeX() / 2;
        double hz = piece.sizeZ() / 2;
        for (Placement p : pieces.values()) {
            if (Math.abs(p.x() - x) >= hx + p.halfX() - 0.05) continue;
            if (Math.abs(p.y() - y) >= hy + p.halfY() - 0.05) continue;
            if (Math.abs(p.z() - z) >= hz + p.halfZ() - 0.05) continue;
            return true;
        }
        return false;
    }

    /** A coordinate on the build grid. */
    public static double snap(double v) {
        return Math.round(v / GRID) * GRID;
    }

    // --- persistence ----------------------------------------------------------------

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> rows = new ArrayList<>();
        for (Placement p : pieces.values()) rows.add(p.toMap());
        m.put("next", nextId);
        m.put("pieces", rows);
        return m;
    }

    public void load(Map<String, Object> m) {
        pieces.clear();
        nextId = Math.max(1, WatchJson.big(m, "next", 1));
        for (Map<String, Object> row : WatchJson.objects(m, "pieces")) {
            adopt(Placement.fromMap(row));
        }
    }

    /** The whole structure as a list of placement objects — what a snapshot sends. */
    public List<Object> toRows() {
        List<Object> rows = new ArrayList<>();
        for (Placement p : pieces.values()) rows.add(p.toMap());
        return rows;
    }

    /** Replace everything from a host's list. */
    public void loadRows(List<Map<String, Object>> rows) {
        pieces.clear();
        for (Map<String, Object> row : rows) adopt(Placement.fromMap(row));
    }
}
