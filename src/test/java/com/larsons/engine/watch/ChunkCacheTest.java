package com.larsons.engine.watch;

import com.larsons.engine.graphics.ChunkMemory;
import com.larsons.engine.watch.world.ChunkStreamer;
import com.larsons.engine.watch.world.TerrainField;
import com.larsons.engine.watch.world.WatchChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That ground which has been built stays built, and that re-meshing it says so.
 *
 * <p>Two things that were quietly wrong, and both of them cost frames rather
 * than correctness — which is why they survived so long:
 *
 * <ul>
 *   <li>A chunk walked away from was <b>dropped</b>, so pacing back and forth
 *       over a chunk boundary regenerated the same ground indefinitely, on the
 *       workers that should have been building the ground ahead.</li>
 *   <li>A chunk re-meshed at a new level of detail kept its old <b>mesh
 *       revision</b>, which is the number a GPU backend compares to decide
 *       whether to re-upload — so on the accelerated path a chunk was drawn for
 *       ever at whatever detail it happened to be built at.</li>
 * </ul>
 */
@Timeout(180)
class ChunkCacheTest {

    /** Wait for the streamer's workers to drain, or give up. */
    private static void settle(ChunkStreamer streamer) {
        settle(streamer, 0, 0);
    }

    /** …with the player standing somewhere in particular while they do. */
    private static void settle(ChunkStreamer streamer, double x, double y) {
        for (int i = 0; i < 400 && streamer.pending() > 0; i++) {
            streamer.update(x, y, 0.05);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Ground walked away from and walked back to is not built twice. */
    @Test
    void groundIsKeptRatherThanRebuilt() {
        try (ChunkStreamer streamer = new ChunkStreamer(new TerrainField(21L), 512)) {
            streamer.setViewRadius(2);
            streamer.setDetailRadius(1);
            streamer.setGrassRadius(0);

            streamer.loadNow(0, 0, 2);
            long builtHere = streamer.generatedCount();
            assertTrue(builtHere > 0, "nothing was generated at all");

            // Walk a long way off, so everything here is evicted.
            double away = WatchChunk.SIZE * 40;
            streamer.update(away, away, 0.05);
            // <b>Let the far ring finish before measuring anything.</b> The one
            // call that evicts the old ring also *queues* the new one, on
            // worker threads — so without this the counts below are being read
            // while a dozen chunks are still landing, and on a machine under
            // load they land in the middle of the measurement rather than
            // after it. That is a race, it fails about one run in five under
            // contention and never once on an idle machine, and neither of the
            // two things this test is about has anything to do with it.
            settle(streamer, away, away);

            // The old ring specifically, rather than the map being empty: the
            // map is not empty, it is full of the ring over there.
            for (int cy = -2; cy <= 2; cy++) {
                for (int cx = -2; cx <= 2; cx++) {
                    assertNull(streamer.chunk(cx, cy),
                            "chunk (" + cx + ", " + cy + ") was not cleared out of "
                                    + "the live map when the player walked away");
                }
            }
            assertTrue(streamer.cachedCount() >= builtHere,
                    "the old ring was thrown away rather than kept: "
                            + streamer.cachedCount() + " cached");

            // …and walk back. Counted from wherever the walk away left the
            // total, because that walk built a ring of its own and the
            // question here is only whether *this* ground is built twice.
            long builtAway = streamer.generatedCount();
            long recalledBefore = streamer.recalledCount();
            streamer.loadNow(0, 0, 2);
            assertEquals(builtAway, streamer.generatedCount(),
                    "walking back regenerated ground that was already built");
            assertTrue(streamer.recalledCount() > recalledBefore,
                    "nothing was recalled from the cache");
            assertNotNull(streamer.chunkAt(0, 0));
        }
    }

    /** The cache has a ceiling, and honours it. */
    @Test
    void theCacheHasACeiling() {
        int budget = 24;
        try (ChunkStreamer streamer = new ChunkStreamer(new TerrainField(5L), budget)) {
            streamer.setViewRadius(1);
            streamer.setDetailRadius(0);
            streamer.setGrassRadius(0);
            // Walk a long line, evicting as we go.
            for (int step = 0; step < 60; step++) {
                double at = step * WatchChunk.SIZE * 8;
                streamer.loadNow(at, 0, 1);
                streamer.update(at, 0, 0.05);
                assertTrue(streamer.cachedCount() <= budget,
                        "the cache grew past its budget: " + streamer.cachedCount());
            }
        }
    }

    /** A cache of nothing still works — it is only ever an optimisation. */
    @Test
    void aZeroBudgetStillPlays() {
        try (ChunkStreamer streamer = new ChunkStreamer(new TerrainField(9L), 0)) {
            streamer.setViewRadius(1);
            streamer.loadNow(0, 0, 1);
            assertNotNull(streamer.chunkAt(0, 0));
            streamer.update(WatchChunk.SIZE * 40, 0, 0.05);
            assertEquals(0, streamer.cachedCount());
            streamer.loadNow(0, 0, 1);
            assertNotNull(streamer.chunkAt(0, 0), "the world stopped working without a cache");
        }
    }

    /** A re-mesh changes the number a backend keys its buffers on. */
    @Test
    void reMeshingBumpsTheMeshRevision() {
        try (ChunkStreamer streamer = new ChunkStreamer(new TerrainField(33L), 64)) {
            streamer.setViewRadius(1);
            streamer.setDetailRadius(0);
            streamer.setGrassRadius(0);
            streamer.loadNow(0, 0, 1);

            WatchChunk chunk = streamer.chunkAt(0, 0);
            assertNotNull(chunk);
            int before = chunk.meshRevision();
            int lodBefore = chunk.lod();
            assertTrue(before > 0, "a meshed chunk has no mesh revision");
            assertEquals(before, chunk.groundMesh().revision(),
                    "the ground mesh does not carry the chunk's mesh revision");

            // The same chunk, meshed again at a different detail.
            int wanted = lodBefore == 0 ? 1 : 0;
            chunk.beginMesh();
            chunk.setMeshes(wanted, chunk.groundMesh(), chunk.waterMesh(),
                    chunk.floraMesh(), chunk.grassMesh());
            assertNotEquals(before, chunk.meshRevision(),
                    "a re-mesh left the revision a backend compares unchanged");
            assertTrue(chunk.meshRevision() > before, "the revision went backwards");
        }
    }

    /** The budget follows the heap, and stays inside its stated bounds. */
    @Test
    void theBudgetFollowsTheHeap() {
        int chunks = ChunkMemory.chunkCacheBudget();
        assertTrue(chunks >= 256, "the floor was not honoured: " + chunks);
        assertTrue(chunks <= 12_288, "the ceiling was not honoured: " + chunks);
        assertTrue(ChunkMemory.gpuBufferBudget() > chunks,
                "the card would force a re-mesh of ground the CPU still holds");
        assertTrue(ChunkMemory.maxHeapBytes() > 0);
        assertTrue(ChunkMemory.describe().contains("chunks cached"));
    }
}
