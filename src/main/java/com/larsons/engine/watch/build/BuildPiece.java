package com.larsons.engine.watch.build;

import com.larsons.engine.watch.Satchel;
import com.larsons.engine.watch.world.WatchMaterial;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ten things you can build out of what you found.
 *
 * <p><b>Ten, and not a block palette.</b> The engine already has a creative
 * mode where you place blocks one at a time; this is a different thing on
 * purpose. A camp here is a handful of large, obviously-shaped pieces — a
 * platform, a wall, a roof, a ladder — because the point of building is to have
 * somewhere to sit still near a feeder, and a game about sitting still should
 * not require an afternoon of block-laying first.
 *
 * <p>Every piece costs <b>foraged</b> materials: branches, bark, reeds, stone,
 * vine, and the planks and rope those become at a bench
 * ({@link com.larsons.engine.watch.Recipes}). Nothing is bought and nothing is
 * mined.
 *
 * <p>{@link #anchors()} is what makes a treehouse a treehouse: a piece that
 * anchors can be placed against a trunk instead of on the ground, and takes its
 * height from where you pointed at the tree.
 */
public enum BuildPiece {

    POST("post", "Post", 0.22, 0.22, 2.4, WatchMaterial.BARK, false,
            "The upright everything else hangs off.",
            "fallen_branch", 2),

    BEAM("beam", "Beam", 2.6, 0.22, 0.22, WatchMaterial.BARK, true,
            "Spans two posts, or a post and a trunk.",
            "fallen_branch", 2, "rope", 1),

    FLOOR("floor", "Floor", 2.6, 2.6, 0.14, WatchMaterial.PLANK, true,
            "Two and a half metres of somewhere to stand.",
            "plank", 4, "rope", 1),

    PLATFORM("platform", "Platform", 3.4, 3.4, 0.14, WatchMaterial.PLANK, true,
            "A wider floor with a rail. What a hide is built on.",
            "plank", 6, "rope", 2, "fallen_branch", 2),

    WALL("wall", "Wall", 2.6, 0.16, 2.2, WatchMaterial.PLANK, true,
            "Keeps the wind off and you out of sight.",
            "plank", 4),

    WINDOW_WALL("window_wall", "Window Wall", 2.6, 0.16, 2.2, WatchMaterial.PLANK, true,
            "A wall with a slot in it. The whole point of a hide.",
            "plank", 4, "rope", 1),

    DOOR("door", "Doorway", 1.2, 0.16, 2.2, WatchMaterial.PLANK, true,
            "A gap with a frame, so a wall is not a box.",
            "plank", 3, "rope", 1),

    ROOF("roof", "Roof", 3.0, 3.0, 0.5, WatchMaterial.THATCH, true,
            "Thatch on a slope. Turns a platform into a camp.",
            "thatch", 4, "fallen_branch", 2),

    LADDER("ladder", "Ladder", 0.6, 0.18, 3.0, WatchMaterial.BARK, true,
            "Up a trunk, or up a post. Three metres a time.",
            "fallen_branch", 3, "rope", 2),

    ROPE_BRIDGE("rope_bridge", "Rope Bridge", 5.0, 1.2, 0.16, WatchMaterial.ROPE, true,
            "Between two platforms. The reason to build a second one.",
            "rope", 4, "plank", 3),

    /**
     * A board to pin maps to. See {@link com.larsons.engine.watch.Cartography}.
     *
     * <p><b>The one piece that is more than its box</b>, and the only reason
     * the enum needed an eleventh entry rather than the maps hanging off an
     * existing wall. A map board is a place in the world that <em>holds
     * something</em>: building one registers a {@code Cartography.Board}
     * twinned with the placement, and walking up to it opens the combined map
     * of everything anybody has pinned to it. A wall cannot do that without
     * every wall in the world being asked whether it is a board.
     *
     * <p>Wide, thin and chest-high, because that is the shape of a thing eight
     * people stand in front of and read. It anchors like the rest, so a board
     * can go up on a platform in a tree.
     *
     * <p>It is {@linkplain com.larsons.engine.watch.Debug.Power#MAPS behind
     * debug mode} with the rest of the map feature — {@code WatchGame.build}
     * refuses it to anybody else, and the build screen does not list it — so
     * the cost below is what it will cost when the gate lifts rather than
     * something anybody can pay today.
     */
    MAP_BOARD("map_board", "Map Board", 3.0, 0.18, 2.0, WatchMaterial.PLANK, true,
            "Pin maps to it. Neighbouring maps join into one larger map.",
            "plank", 6, "rope", 2);

    private final String key;
    private final String name;
    private final double sizeX, sizeY, sizeZ;
    private final WatchMaterial material;
    private final boolean anchors;
    private final String note;
    private final Map<String, Integer> cost;

    BuildPiece(String key, String name, double sizeX, double sizeY, double sizeZ,
               WatchMaterial material, boolean anchors, String note, Object... pairs) {
        this.key = key;
        this.name = name;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.material = material;
        this.anchors = anchors;
        this.note = note;
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        this.cost = Map.copyOf(map);
    }

    /** Stable identifier — what a save and the wire use. */
    public String key() { return key; }

    /** What a player sees it called. */
    public String displayName() { return name; }

    /** Its size in metres, along the piece's own axes before it is turned. */
    public double sizeX() { return sizeX; }

    public double sizeY() { return sizeY; }

    public double sizeZ() { return sizeZ; }

    /** What it is made of, for the mesher. */
    public WatchMaterial material() { return material; }

    /** Whether it can be fixed to a trunk rather than standing on the ground. */
    public boolean anchors() { return anchors; }

    /** One line for the build menu. */
    public String note() { return note; }

    /** What it costs, as forage key → count. */
    public Map<String, Integer> cost() { return cost; }

    /** The cost, spelled out: "4 × Plank, 1 × Rope". */
    public String costLine() {
        StringBuilder sb = new StringBuilder();
        cost.forEach((key, n) -> {
            if (sb.length() > 0) sb.append(", ");
            sb.append(n).append(" × ")
                    .append(com.larsons.engine.watch.Forage.nameOf(key));
        });
        return sb.toString();
    }

    /** Whether a satchel can afford one. */
    public boolean affordable(Satchel satchel) {
        for (Map.Entry<String, Integer> need : cost.entrySet()) {
            if (!satchel.has(need.getKey(), need.getValue())) return false;
        }
        return true;
    }

    /**
     * Spend the materials for one.
     *
     * <p>All or nothing, like every other cost in this game: a piece that
     * cannot be afforded takes nothing, so a player never ends up with three
     * quarters of a wall's worth of materials missing and no wall.
     */
    public boolean pay(Satchel satchel) {
        if (!affordable(satchel)) return false;
        cost.forEach(satchel::take);
        return true;
    }

    /**
     * Whether this piece is only offered in debug mode.
     *
     * <p>One method rather than a flag in every constructor, because there is
     * one such piece and a column of {@code false}s down ten rows would be a
     * column of noise. When a second one appears — or when
     * {@link com.larsons.engine.watch.Debug.Power#MAPS} stops being a debug
     * power — this becomes a set, or nothing.
     */
    public boolean debugOnly() { return this == MAP_BOARD; }

    /** Every piece, in the order the build menu lists them. */
    public static List<BuildPiece> all() { return List.of(values()); }

    /**
     * The pieces one player may build, which is everything except what debug
     * mode is still holding back.
     *
     * <p>What the build screen lists and what its rows are indexed against. The
     * screen must not simply grey the board out: a row that is always refused is
     * a row that teaches a player the build menu lies, and the piece is not
     * being withheld from <em>them</em> — it is not finished.
     */
    public static List<BuildPiece> available(boolean debugging) {
        if (debugging) return all();
        List<BuildPiece> out = new java.util.ArrayList<>();
        for (BuildPiece piece : values()) {
            if (!piece.debugOnly()) out.add(piece);
        }
        return List.copyOf(out);
    }

    /** The piece a saved key means, or {@code null}. */
    public static BuildPiece of(String key) {
        for (BuildPiece p : values()) {
            if (p.key.equals(key)) return p;
        }
        return null;
    }
}
