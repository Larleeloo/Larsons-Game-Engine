package com.larsons.engine.graphics.chunk;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arena packing: the arithmetic that decides where a section's triangles
 * end up in a shared buffer.
 *
 * <p><b>This is the half of multi-draw batching that can go wrong quietly.</b>
 * A first-vertex off by one draws a section with its neighbour's triangles — a
 * hole in the world beside a smear of misplaced ground — and a slot decoded
 * differently from the way it was encoded puts a whole section at another
 * section's coordinates. Neither throws, neither logs, and both look from a
 * distance like a meshing bug. So the packing lives in the core module and is
 * checked here rather than by looking at a screen.
 */
class SectionBatchTest {

    /**
     * <b>A section belongs to exactly one arena and one slot, and the slot
     * decodes back to the offsets it was built from.</b>
     *
     * <p>Over negative coordinates too, which is where a {@code %} that should
     * have been a {@code floorMod} hides: the world extends both ways from its
     * origin, and a section at &minus;1 is not in the same arena as one at 0.
     */
    @Test
    void everySectionLandsInOneArenaAndOneSlotThatDecodesBack() {
        Map<String, String> seen = new HashMap<>();
        for (int sx = -9; sx <= 9; sx++) {
            for (int sy = -9; sy <= 9; sy++) {
                for (int sz = -9; sz <= 9; sz++) {
                    long arena = SectionBatch.arenaOf(sx, sy, sz);
                    int slot = SectionBatch.slotOf(sx, sy, sz);
                    assertTrue(slot >= 0 && slot < SectionBatch.SLOTS,
                            "slot out of range for " + sx + "," + sy + "," + sz + ": " + slot);

                    // No two sections may share an arena *and* a slot.
                    String where = arena + "/" + slot;
                    String previous = seen.put(where, sx + "," + sy + "," + sz);
                    assertEquals(null, previous,
                            "two sections in the same place: " + previous + " and "
                                    + sx + "," + sy + "," + sz);

                    // And the slot has to decode back to where the section sits
                    // inside its arena, which is what the GL side rebuilds its
                    // vertex offsets from.
                    assertEquals(Math.floorMod(sx, SectionBatch.SPAN),
                            SectionBatch.localColumn(slot), "column of " + slot);
                    assertEquals(Math.floorMod(sz, SectionBatch.SPAN),
                            SectionBatch.localRow(slot), "row of " + slot);
                    assertEquals(Math.floorMod(sy, SectionBatch.SPAN),
                            SectionBatch.localHeight(slot), "height of " + slot);
                }
            }
        }
    }

    /**
     * <b>Every section's range holds exactly its own vertices, shifted.</b>
     *
     * <p>The property the whole arrangement rests on: a
     * {@code glMultiDrawArrays} is handed a first and a count per section, and
     * if either is wrong the card draws somebody else's geometry without
     * complaint. Checked vertex by vertex against what went in.
     */
    @Test
    void eachSlotsRangeIsExactlyItsOwnVerticesMovedIntoTheArena() {
        Random random = new Random(90210L);
        SectionBatch batch = new SectionBatch();
        batch.reset();

        int stride = SectionMesh.FLOATS_PER_VERTEX;
        Map<Integer, float[]> given = new HashMap<>();
        Map<Integer, float[]> offsets = new HashMap<>();
        Map<Integer, int[]> tints = new HashMap<>();

        // A ragged mixture on purpose: empty slots, one-triangle slots and big
        // ones, in no particular order, because the packer walks slots in order
        // and a bug that only shows when a slot is skipped is the likely one.
        for (int slot = 0; slot < SectionBatch.SLOTS; slot += 3) {
            int vertices = random.nextInt(4) == 0 ? 0 : 3 * (1 + random.nextInt(30));
            if (vertices == 0) continue;
            float[] data = new float[vertices * stride];
            int[] colours = new int[vertices];
            for (int i = 0; i < data.length; i++) data[i] = random.nextFloat() * 100;
            for (int i = 0; i < colours.length; i++) colours[i] = random.nextInt();
            float[] offset = {random.nextInt(4) * 512f, random.nextInt(4) * 512f,
                    random.nextInt(4) * 512f};
            given.put(slot, data);
            tints.put(slot, colours);
            offsets.put(slot, offset);
            batch.add(slot, data, colours, offset[0], offset[1], offset[2]);
        }

        int totalVertices = 0;
        for (int[] colours : tints.values()) totalVertices += colours.length;
        assertEquals(totalVertices, batch.vertexCount(), "the packer lost or invented vertices");

        float[] packed = batch.vertices();
        int[] packedColours = batch.colours();
        for (Map.Entry<Integer, float[]> e : given.entrySet()) {
            int slot = e.getKey();
            float[] source = e.getValue();
            float[] offset = offsets.get(slot);
            int[] colours = tints.get(slot);
            assertEquals(colours.length, batch.countOf(slot), "wrong count for slot " + slot);
            int first = batch.firstOf(slot);
            assertTrue(first + colours.length <= batch.vertexCount(),
                    "slot " + slot + " runs off the end of the buffer");
            for (int i = 0; i < colours.length; i++) {
                int from = i * stride;
                int to = (first + i) * stride;
                assertEquals(source[from] + offset[0], packed[to], 1e-4,
                        "slot " + slot + " vertex " + i + " x");
                assertEquals(source[from + 1] + offset[1], packed[to + 1], 1e-4,
                        "slot " + slot + " vertex " + i + " y");
                assertEquals(source[from + 2] + offset[2], packed[to + 2], 1e-4,
                        "slot " + slot + " vertex " + i + " z");
                // The texture coordinates must NOT move with the position.
                assertEquals(source[from + 3], packed[to + 3], 1e-6,
                        "slot " + slot + " vertex " + i + " u drifted");
                assertEquals(source[from + 4], packed[to + 4], 1e-6,
                        "slot " + slot + " vertex " + i + " v drifted");
                assertEquals(colours[i], packedColours[first + i],
                        "slot " + slot + " vertex " + i + " colour");
            }
        }

        // A slot nothing was added for contributes no range at all, which is how
        // the draw call knows to skip it.
        for (int slot = 0; slot < SectionBatch.SLOTS; slot++) {
            if (given.containsKey(slot)) continue;
            assertEquals(0, batch.countOf(slot), "slot " + slot + " should be empty");
        }
    }

    /** Ranges never overlap — the other way a first-vertex mistake shows up. */
    @Test
    void noTwoSlotsClaimTheSameVertices() {
        SectionBatch batch = new SectionBatch();
        batch.reset();
        int stride = SectionMesh.FLOATS_PER_VERTEX;
        for (int slot = 0; slot < SectionBatch.SLOTS; slot++) {
            int vertices = 3 * (1 + (slot % 5));
            batch.add(slot, new float[vertices * stride], new int[vertices], 0, 0, 0);
        }
        boolean[] claimed = new boolean[batch.vertexCount()];
        for (int slot = 0; slot < SectionBatch.SLOTS; slot++) {
            for (int i = 0; i < batch.countOf(slot); i++) {
                int at = batch.firstOf(slot) + i;
                assertTrue(at < claimed.length, "slot " + slot + " runs past the end");
                assertTrue(!claimed[at], "vertex " + at + " claimed twice, once by slot " + slot);
                claimed[at] = true;
            }
        }
        for (int i = 0; i < claimed.length; i++) {
            assertTrue(claimed[i], "vertex " + i + " belongs to no slot");
        }
    }

    /** Reset really empties it, so one scratch packer can serve every arena. */
    @Test
    void resetLeavesNothingOfTheLastArenaBehind() {
        SectionBatch batch = new SectionBatch();
        int stride = SectionMesh.FLOATS_PER_VERTEX;
        batch.reset();
        batch.add(7, new float[6 * stride], new int[6], 0, 0, 0);
        assertEquals(6, batch.countOf(7));

        batch.reset();
        assertEquals(0, batch.vertexCount(), "vertices survived a reset");
        for (int slot = 0; slot < SectionBatch.SLOTS; slot++) {
            assertEquals(0, batch.countOf(slot), "slot " + slot + " survived a reset");
        }
    }
}
