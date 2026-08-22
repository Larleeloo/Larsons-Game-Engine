package com.larsons.engine.graphics.chunk;

import com.larsons.engine.graphics.Frustum;

import java.util.ArrayList;
import java.util.List;

/**
 * Which sections a frame draws, and in what order — <b>Minecraft Java's chunk
 * graph walk</b>.
 *
 * <p><b>A breadth-first search out from the camera, not a sweep of a disc.</b>
 * The world is treated as a graph whose nodes are sections and whose edges are
 * the faces between them, and a step is allowed only when three things hold:
 *
 * <ul>
 *   <li>the section you are leaving can <em>see</em> from the face you came in
 *       by to the face you are leaving by ({@link SectionVisibility}), which is
 *       what stops the walk at the far wall of a cave;</li>
 *   <li>the step does not double back — once the walk has gone east it never
 *       goes west again, which is what stops it wandering round behind the
 *       player through a corridor and drawing the world twice;</li>
 *   <li>the section arrived at is inside the frustum and inside the render
 *       distance.</li>
 * </ul>
 *
 * <p>Standing on a plain every section is open, nothing is culled that the
 * frustum would not have culled, and the walk costs a queue push per section.
 * Standing in a tunnel it draws the tunnel. That difference — an order of
 * magnitude, underground — is why the walk exists, and no frustum can find it,
 * because the sections behind the wall really are in front of the camera.
 *
 * <h2>What it is not</h2>
 *
 * <p><b>It is conservative, and knowingly so.</b> The connectivity is recorded
 * per <em>face</em>, not per point on a face, so a section split down the
 * middle by a wall reports that its east face can see its north face — which is
 * true of the half of the east face on the northern side and false of the
 * other. A walk that arrives through the southern half is therefore allowed to
 * leave northward, and a wall running clean across the world leaks around
 * itself one section at a time. Minecraft's has the same shape and the same
 * leak, for the same reason: the alternative is tracking where on a face a ray
 * entered, which is a portal renderer and costs more than it saves.
 *
 * <p>What it does catch is the case worth catching — <b>a section with no empty
 * cell in it connects nothing to anything</b> — so being inside a mountain, a
 * cave, a building or a mine draws what you are inside of and stops. A cull is
 * allowed to be too generous; the failure it must not have is dropping
 * something the player can see, and under-claiming can never do that.
 *
 * <p><b>Breadth-first is also the draw order.</b> It comes out sorted by steps
 * from the camera, which is near-to-far, which is what an opaque pass wants: a
 * near section drawn first fills the depth buffer and the fragments of
 * everything behind it are thrown away before they are shaded. The translucent
 * pass wants the reverse and gets it by walking the same list backwards.
 */
public final class SectionRenderList {

    /** How far the walk may travel, in sections, whatever else is true. */
    private static final int MAX_STEPS = 512;

    /** One section to draw: where it is, and its mesh. */
    public record Visible(int sx, int sy, int sz, SectionMesh mesh) {}

    private final List<Visible> visible = new ArrayList<>();
    private final java.util.HashSet<Long> seen = new java.util.HashSet<>();
    private final java.util.ArrayDeque<long[]> queue = new java.util.ArrayDeque<>();

    /** The sections to draw, nearest first. */
    public List<Visible> visible() { return visible; }

    /**
     * Walk the graph.
     *
     * @param sections where meshes come from, and where missing ones are queued
     * @param frustum  the camera's six planes
     * @param tileSize world units per cell
     * @param eyeX     the camera, in world units
     * @param reach    how far to walk, in world units
     * @param minY     the lowest section index the world has (usually 0)
     * @param maxY     one past the highest
     */
    public void build(TerrainSections sections, Frustum frustum, int tileSize,
                      double eyeX, double eyeY, double eyeZ, double reach,
                      int minY, int maxY) {
        visible.clear();
        seen.clear();
        queue.clear();

        double span = SectionMesh.SIZE * (double) tileSize;
        int startX = (int) Math.floor(eyeX / span);
        int startZ = (int) Math.floor(eyeY / span);
        int startY = (int) Math.floor(eyeZ / span);
        startY = Math.max(minY, Math.min(maxY - 1, startY));
        sections.beginFrame(startX, startY, startZ);

        double reachSq = reach * reach;
        // The section the camera is in enters through no face at all, which is
        // what the −1 means: from inside, every face is a way out.
        queue.add(new long[] {startX, startY, startZ, -1});
        seen.add(TerrainSections.key(startX, startY, startZ));

        int steps = 0;
        while (!queue.isEmpty() && steps++ < MAX_STEPS * MAX_STEPS) {
            long[] at = queue.poll();
            int sx = (int) at[0], sy = (int) at[1], sz = (int) at[2];
            int from = (int) at[3];

            SectionMesh mesh = sections.mesh(sx, sy, sz);
            SectionVisibility through = mesh != null
                    ? mesh.visibility()
                    // A section not meshed yet is treated as open. It has to be:
                    // assuming it solid would stop the walk at the edge of what
                    // has been built and the world would fill in one ring per
                    // frame, which is a visible crawl outward every time the
                    // player turns round.
                    : SectionVisibility.OPEN;
            if (mesh != null && !mesh.isEmpty()) {
                visible.add(new Visible(sx, sy, sz, mesh));
            }

            for (int face = 0; face < SectionVisibility.FACES; face++) {
                if (!through.canSee(from, face)) continue;
                // No doubling back: a step out of the face the walk came in by
                // is a step toward the camera.
                if (from >= 0 && face == SectionVisibility.opposite(from)) continue;
                int nx = sx + SectionVisibility.step(face, 0);
                int nz = sz + SectionVisibility.step(face, 1);
                int ny = sy + SectionVisibility.step(face, 2);
                if (ny < minY || ny >= maxY) continue;
                long key = TerrainSections.key(nx, ny, nz);
                if (!seen.add(key)) continue;

                double x0 = nx * span, y0 = nz * span, z0 = ny * span;
                double cx = x0 + span / 2 - eyeX;
                double cy = y0 + span / 2 - eyeY;
                if (cx * cx + cy * cy > reachSq) continue;
                if (!frustum.boxVisible(x0, y0, z0, x0 + span, y0 + span, z0 + span)) continue;
                queue.add(new long[] {nx, ny, nz, SectionVisibility.opposite(face)});
            }
        }
    }
}
