package com.larsons.engine.graphics.chunk;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The section table, against a {@link HashMap} that is known to be right.
 *
 * <p><b>Open addressing with removal is where a silent bug lives.</b> Blanking
 * a slot strands anything that probed past it — the entry is still in the array
 * and can never be found again — and the failure is invisible: the table simply
 * reports that a section has no mesh, the walk queues a rebuild, the rebuild
 * lands in a different slot, and the world looks fine while the cache slowly
 * fills with unreachable duplicates. Nothing about that shows up except as
 * memory and stutter a long way from the cause.
 *
 * <p>So the test is a few hundred thousand random operations against an oracle,
 * with keys drawn from a small enough range that collisions and re-inserts are
 * common rather than rare, and every one of them checked.
 */
class SectionTableTest {

    /** Random puts, gets and removes agree with a HashMap, operation for operation. */
    @Test
    void itAgreesWithAMapThroughAnyMixtureOfWork() {
        SectionTable table = new SectionTable();
        Map<Long, SectionMesh> oracle = new HashMap<>();
        Random random = new Random(20260823L);

        for (int step = 0; step < 200_000; step++) {
            // A narrow key range on purpose: probe chains and reuse are the
            // interesting cases, and a wide range would almost never produce
            // either.
            long key = random.nextInt(4000) - 2000;
            int roll = random.nextInt(100);
            if (roll < 55) {
                SectionMesh mesh = SectionMesh.empty((int) key, 0, 0);
                assertSame(oracle.put(key, mesh), table.put(key, mesh),
                        "put returned a different previous value at step " + step);
            } else if (roll < 80) {
                assertSame(oracle.remove(key), table.remove(key),
                        "remove returned a different value at step " + step);
            } else {
                assertSame(oracle.get(key), table.get(key),
                        "get disagreed at step " + step + " for key " + key);
            }
            assertEquals(oracle.size(), table.size(), "size drifted at step " + step);
        }

        // And everything the oracle holds is still reachable at the end — the
        // check that catches a stranded entry rather than a wrong return value.
        for (Map.Entry<Long, SectionMesh> e : oracle.entrySet()) {
            assertSame(e.getValue(), table.get(e.getKey()),
                    "key " + e.getKey() + " was stranded behind a removal");
        }
        assertEquals(oracle.size(), table.keys().length, "keys() lost or invented entries");
    }

    /**
     * Zero is a real key — the section at the world's origin — and must not be
     * mistaken for an empty slot.
     */
    @Test
    void theKeyZeroIsAnOrdinaryKey() {
        SectionTable table = new SectionTable();
        SectionMesh mesh = SectionMesh.empty(0, 0, 0);
        assertNull(table.get(0));
        table.put(0, mesh);
        assertSame(mesh, table.get(0));
        assertEquals(1, table.size());
        assertSame(mesh, table.remove(0));
        assertNull(table.get(0));
        assertEquals(0, table.size());
    }

    /** It grows past its opening capacity without losing anything. */
    @Test
    void itKeepsEverythingThroughAGrowth() {
        SectionTable table = new SectionTable();
        int n = 20_000;
        for (int i = 0; i < n; i++) table.put(i, SectionMesh.empty(i, 0, 0));
        assertEquals(n, table.size());
        for (int i = 0; i < n; i++) {
            assertTrue(table.get(i) != null, "lost key " + i + " in a resize");
        }
        assertNull(table.get(n), "and did not invent one");
    }

    /**
     * A run of colliding keys survives having its middle removed — the case
     * backward-shift deletion exists for, stated directly rather than left to
     * the random walk to stumble on.
     */
    @Test
    void removingFromTheMiddleOfAProbeChainStrandsNothing() {
        SectionTable table = new SectionTable();
        // Whatever the hash does, some of a few thousand keys share a chain;
        // removing every third and asking for the rest is the shape of it.
        int n = 5000;
        for (int i = 0; i < n; i++) table.put(i, SectionMesh.empty(i, 0, 0));
        for (int i = 0; i < n; i += 3) table.remove(i);
        for (int i = 0; i < n; i++) {
            if (i % 3 == 0) assertNull(table.get(i), "key " + i + " should be gone");
            else assertTrue(table.get(i) != null, "key " + i + " was stranded");
        }
    }
}
