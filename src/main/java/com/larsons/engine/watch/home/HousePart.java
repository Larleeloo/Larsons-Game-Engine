package com.larsons.engine.watch.home;

import com.larsons.engine.watch.world.WatchMaterial;

/**
 * One piece of a house, in the house's own frame.
 *
 * <h2>One list, drawn and collided</h2>
 *
 * <p>The single most important thing about this record is that it is
 * <b>the same list for the eye and for the feet</b>. {@link HouseKit} emits it
 * once per plan; {@code watch.render.HouseModel} draws it and
 * {@link Homestead} collides against it. A house therefore cannot have a wall
 * you can see and walk through, or a floor you stand on that is not there — the
 * two classes of bug that a game with a separate "collision mesh" spends its
 * life fixing, and that no amount of care avoids once the two descriptions are
 * allowed to drift apart.
 *
 * <p>The price of that is one rule, and it is worth writing down: <b>every part
 * is a box</b>. A part may be <em>drawn</em> as something else — a roof slope
 * leans, a ladder is two stiles and a dozen rungs, a post is round — but what it
 * collides as is the box below, and the {@link Shape} is the drawing
 * instruction. Where the two would disagree badly enough to matter, the
 * {@link Role} says so instead: a roof is {@link Role#ROOF}, which does not
 * block anything at all, precisely because a pitched roof's box is a lie about
 * where the roof is and an invisible slab at ridge height across a whole house
 * is a worse lie than none.
 *
 * <h2>The frame</h2>
 *
 * <p>{@code (along, across, up)}: <b>along</b> is out of the front door,
 * <b>across</b> is to the door's right, and <b>up</b> is up, all measured from
 * the middle of the ground floor. It is {@code watch.render.ShopModel}'s frame
 * exactly, for the same reason — a house is placed once and turned once, and
 * three hundred parts written in world coordinates would be three hundred
 * chances to get the same sine wrong.
 *
 * @param shade a multiplier on the material's colour, so that one board is told
 *              from the next and the same three materials read as carpentry
 */
public record HousePart(Role role, Shape shape,
                        double along, double across, double up,
                        double halfAlong, double halfAcross, double halfUp,
                        WatchMaterial material, double shade) {

    /**
     * What a part <em>is</em>, which is what decides how it behaves under
     * somebody's boots.
     *
     * <p>Three questions, and every role answers all three: can you stand on
     * top of it, does it stop you walking into it, and can you climb it. That
     * is the whole of a house's physics, and it is deliberately not more — see
     * {@link Homestead#standOn} and {@link Homestead#solidAt}, which are the two
     * queries the walk actually asks.
     */
    public enum Role {

        /** A floor, a deck, a landing. Stand on it; walk over its edge freely. */
        FLOOR(true, false, false),

        /** One tread of a staircase. A floor with a small top. */
        STAIR(true, false, false),

        /** Timber you cannot walk through. Walls, gable ends, chimney stacks. */
        WALL(true, true, false),

        /**
         * A pane. Solid — a window you can walk through is a hole — but drawn
         * as glass, and the reason it is its own role rather than a
         * {@link WatchMaterial} on a {@code WALL}.
         */
        GLASS(true, true, false),

        /** A corner post, a strut, a railing. Solid, and usually thin. */
        POST(true, true, false),

        /**
         * A roof. <b>Not solid and not standable</b>, because its box is not
         * where the roof is — see the class note. A player under one is inside
         * the house and a player over one is on the ridge in a game that has no
         * business simulating either.
         */
        ROOF(false, false, false),

        /**
         * A ladder. Climbable, and not solid, so that walking into one puts you
         * on it rather than stopping you at it. The whole of how anybody gets
         * into a treehouse.
         */
        LADDER(false, false, true),

        /** A table, a bench, a bed, a shelf. Stand on it; do not get stuck on it. */
        FITTING(true, false, false),

        /** The map board's timber. See {@link HousePlan#board()}. */
        BOARD(true, true, false),

        /** Carving, a doorleaf, a shutter, a hearthstone. Scenery. */
        TRIM(false, false, false);

        private final boolean walkable;
        private final boolean solid;
        private final boolean climbable;

        Role(boolean walkable, boolean solid, boolean climbable) {
            this.walkable = walkable;
            this.solid = solid;
            this.climbable = climbable;
        }

        /** Whether a player can stand on its top face. */
        public boolean walkable() { return walkable; }

        /** Whether it stops a player walking into it. */
        public boolean solid() { return solid; }

        /** Whether a player touching it can go up and down it. */
        public boolean climbable() { return climbable; }

        /**
         * Whether it is a detail — furniture, carving, a shutter, a footing.
         *
         * <p>Read only by the renderer, and only to drop it past the distance
         * at which a table inside a house is a triangle nobody can see. It is a
         * question about the <em>role</em> rather than a flag on each part
         * because "is this the fabric of the building or the stuff in it" is
         * exactly what a role already answers.
         */
        public boolean detail() { return this == TRIM || this == FITTING; }
    }

    /**
     * How the renderer should draw the box.
     *
     * <p>The four {@code PITCH} shapes name the direction a roof slope
     * <em>falls</em>: a {@link #PITCH_FRONT} pitch is high at the back of its
     * box and low at the front, so a gable is a {@code PITCH_FRONT} and a
     * {@code PITCH_BACK} meeting over a ridge. Naming the fall rather than
     * carrying an angle is what keeps a part a box: the box's own top and bottom
     * are the ridge and the eaves, and the shape says which end is which.
     */
    public enum Shape {
        /** As it is: one box, turned with the house. */
        BOX,
        /** Sawn into boards along its long axis — a floor, a deck, a wall face. */
        BOARDS,
        /** Two stiles and a run of rungs, filling the box. */
        LADDER,
        /** A roof slope falling toward the front of the house. */
        PITCH_FRONT,
        /** …toward the back. */
        PITCH_BACK,
        /** …toward the right. */
        PITCH_RIGHT,
        /** …toward the left. */
        PITCH_LEFT;

        /** Whether this is one of the four roof slopes. */
        public boolean pitch() { return ordinal() >= PITCH_FRONT.ordinal(); }
    }

    /** The top of the box, in the house's frame. */
    public double top() { return up + halfUp; }

    /** The bottom of the box, in the house's frame. */
    public double bottom() { return up - halfUp; }

    /**
     * Whether a point in the house's frame is inside the box, with a little
     * room round it.
     *
     * @param pad how far outside the box still counts, in metres — a player's
     *            shoulders, when this is asked about a player
     */
    public boolean contains(double along, double across, double up, double pad) {
        return Math.abs(along - this.along) <= halfAlong + pad
                && Math.abs(across - this.across) <= halfAcross + pad
                && Math.abs(up - this.up) <= halfUp + pad;
    }

    /**
     * Whether a standing body overlaps this box.
     *
     * <p>A circle in plan and a segment in height, which is what a walker is:
     * the circle is the shoulders and the segment is from the boots to the top
     * of the head. Cheaper than a capsule and, for a world made of
     * axis-aligned timber, indistinguishable from one.
     */
    public boolean overlaps(double along, double across, double footZ, double headZ,
                            double radius) {
        if (footZ >= top() || headZ <= bottom()) return false;
        double dAlong = Math.max(0, Math.abs(along - this.along) - halfAlong);
        double dAcross = Math.max(0, Math.abs(across - this.across) - halfAcross);
        return dAlong * dAlong + dAcross * dAcross < radius * radius;
    }
}
