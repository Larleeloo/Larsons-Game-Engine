package com.larsons.engine.graphics.chunk;

/**
 * Which faces of a section can see which, through the empty space inside it —
 * <b>Minecraft Java's cave culling, and the reason a player underground draws
 * almost nothing.</b>
 *
 * <p><b>The idea.</b> A frustum tells you a section is in front of the camera.
 * It does not tell you the camera is inside a mountain and the section is on
 * the far side of it. The classic answer to that is an occlusion query or a
 * depth prepass, both of which cost a round trip to the GPU; Minecraft's answer
 * is cheaper and entirely on the CPU. When a section is meshed, flood-fill its
 * <em>empty</em> cells and record which of its six faces end up in the same
 * region. That is fifteen bits — one per unordered pair of faces — and it says
 * exactly one thing: <b>a line of sight entering through this face can only
 * leave through those faces.</b>
 *
 * <p>The renderer then walks the world as a graph rather than as a disc: start
 * at the section holding the camera, and step to a neighbour only through a
 * face the section you are in can actually see out of. A cave system reaches
 * the sections the cave reaches. A room reaches the room. Standing on a plain,
 * every section is open and the walk reaches everything the frustum allows,
 * which is correct and costs nothing.
 *
 * <p><b>Solid means solid.</b> A section with no empty cells at all connects
 * nothing to anything, and is where the saving comes from: the middle of a
 * mountain stops the walk dead. A section with no <em>full</em> cells connects
 * everything to everything, which is what {@link #OPEN} is and what the sky is
 * made of.
 */
public final class SectionVisibility {

    /** Faces, in the order the render walk names its directions. */
    public static final int WEST = 0;   // −x
    public static final int EAST = 1;   // +x
    public static final int DOWN = 2;   // −z (the world's height axis)
    public static final int UP = 3;     // +z
    public static final int NORTH = 4;  // −y
    public static final int SOUTH = 5;  // +y

    /** How many faces a section has. */
    public static final int FACES = 6;

    /** Everything sees everything — an empty section, and the sky. */
    public static final SectionVisibility OPEN = new SectionVisibility(0xFFFFFFFF);

    /** Nothing sees anything — a section with no empty cell in it. */
    public static final SectionVisibility SOLID = new SectionVisibility(0);

    /** Bit {@code from * FACES + to} set when a ray may pass between them. */
    private final int pairs;

    private SectionVisibility(int pairs) {
        this.pairs = pairs;
    }

    /** Whether a line of sight entering by {@code from} may leave by {@code to}. */
    public boolean canSee(int from, int to) {
        if (from < 0) return true;    // the section the camera is standing in
        return (pairs & (1 << (from * FACES + to))) != 0;
    }

    /** The raw pair bits, for tests and for a mesh's own equality. */
    public int bits() { return pairs; }

    /**
     * Work out the connectivity of one section from its cells.
     *
     * <p>A flood fill over the cells nothing solid stands in, restarted at
     * every unvisited empty cell, collecting the faces each region touches.
     * Every pair of faces in the same region is connected. {@code SIZE}&sup3;
     * is four thousand cells and this happens once per section per change, on
     * the thread that meshed it.
     *
     * @param opaque {@code SIZE³} flags, true where a cell blocks sight, in
     *               {@code (z * SIZE + y) * SIZE + x} order
     */
    public static SectionVisibility of(boolean[] opaque) {
        int size = SectionMesh.SIZE;
        int cells = size * size * size;
        boolean anyEmpty = false;
        for (boolean solid : opaque) {
            if (!solid) {
                anyEmpty = true;
                break;
            }
        }
        if (!anyEmpty) return SOLID;

        boolean[] seen = new boolean[cells];
        int[] queue = new int[cells];
        int pairs = 0;
        for (int start = 0; start < cells; start++) {
            if (seen[start] || opaque[start]) continue;
            int head = 0, tail = 0;
            queue[tail++] = start;
            seen[start] = true;
            int touched = 0;
            while (head < tail) {
                int at = queue[head++];
                int x = at % size;
                int y = (at / size) % size;
                int z = at / (size * size);
                if (x == 0) touched |= 1 << WEST;
                if (x == size - 1) touched |= 1 << EAST;
                if (y == 0) touched |= 1 << NORTH;
                if (y == size - 1) touched |= 1 << SOUTH;
                if (z == 0) touched |= 1 << DOWN;
                if (z == size - 1) touched |= 1 << UP;
                if (x > 0) tail = push(queue, tail, seen, opaque, at - 1);
                if (x < size - 1) tail = push(queue, tail, seen, opaque, at + 1);
                if (y > 0) tail = push(queue, tail, seen, opaque, at - size);
                if (y < size - 1) tail = push(queue, tail, seen, opaque, at + size);
                if (z > 0) tail = push(queue, tail, seen, opaque, at - size * size);
                if (z < size - 1) tail = push(queue, tail, seen, opaque, at + size * size);
            }
            for (int a = 0; a < FACES; a++) {
                if ((touched & (1 << a)) == 0) continue;
                for (int b = 0; b < FACES; b++) {
                    if ((touched & (1 << b)) != 0) pairs |= 1 << (a * FACES + b);
                }
            }
        }
        return new SectionVisibility(pairs);
    }

    private static int push(int[] queue, int tail, boolean[] seen, boolean[] opaque, int at) {
        if (seen[at] || opaque[at]) return tail;
        seen[at] = true;
        queue[tail] = at;
        return tail + 1;
    }

    /** The face opposite this one, which is where a step into a neighbour arrives. */
    public static int opposite(int face) {
        return switch (face) {
            case WEST -> EAST;
            case EAST -> WEST;
            case DOWN -> UP;
            case UP -> DOWN;
            case NORTH -> SOUTH;
            default -> NORTH;
        };
    }

    /** How this face's direction moves a section index, on {@code {x, y, z}}. */
    public static int step(int face, int axis) {
        return switch (face) {
            case WEST -> axis == 0 ? -1 : 0;
            case EAST -> axis == 0 ? 1 : 0;
            case NORTH -> axis == 1 ? -1 : 0;
            case SOUTH -> axis == 1 ? 1 : 0;
            case DOWN -> axis == 2 ? -1 : 0;
            default -> axis == 2 ? 1 : 0;
        };
    }
}
