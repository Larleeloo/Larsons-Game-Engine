package com.larsons.engine.watch;

import com.larsons.engine.watch.render.ItemModel;
import com.larsons.engine.watch.render.ItemPortrait;
import com.larsons.engine.watch.render.Mesh;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every item has a model of its own, and every model has a picture.
 *
 * <p>The thing this file is really guarding is the <b>one edit</b> a new item
 * needs. {@link Forage} is a list of forty-odd food items and thirty materials
 * and tools, and it is going to keep growing; each of them is drawn on a
 * feeder, in a hand, on the ground and beside its own row in the satchel. If
 * adding a row to {@code Forage} could produce a thing with no geometry, the
 * failure would be a hole in the world nobody notices until somebody puts a
 * feeder out.
 */
@Timeout(180)
class ItemModelTest {

    /** How many triangles one carried thing may cost. See {@link ItemModel}. */
    private static final int TRIANGLE_BUDGET = 150;

    private static Mesh model(String key) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        ItemModel.item(mesh, key, 0, 0, 0, 1, 0.7);
        return mesh.build();
    }

    @Test
    void everythingYouCanCarryHasSomethingToDraw() {
        List<String> empty = new ArrayList<>();
        for (Forage.Item item : Forage.all()) {
            if (model(item.key()).isEmpty()) empty.add(item.key());
        }
        assertTrue(empty.isEmpty(),
                "these items would be drawn as nothing at all: " + empty);
    }

    /**
     * <b>Per item, not per kind.</b> The first version of {@link ItemModel} drew
     * one shape per {@link Forage.Kind} and tinted it from the key's hash, so
     * every berry in the game was the same three spheres. This is what stops
     * that quietly coming back: within a kind, the models have to actually
     * differ.
     */
    @Test
    void twoItemsOfTheSameKindAreNotTheSameModel() {
        for (Forage.Kind kind : Forage.Kind.values()) {
            List<Forage.Item> of = Forage.ofKind(kind);
            if (of.size() < 2) continue;
            Set<String> shapes = new HashSet<>();
            for (Forage.Item item : of) shapes.add(fingerprint(model(item.key())));
            assertTrue(shapes.size() > 1,
                    "every " + kind + " in the game is the identical model — "
                            + of.size() + " items, " + shapes.size() + " shape");
        }
    }

    /**
     * A shape and its size, to a millimetre — enough to tell a cone from a
     * cluster and not so precise that a tint change counts as a new model.
     */
    private static String fingerprint(Mesh mesh) {
        return mesh.triangleCount() + ":"
                + Math.round((mesh.maxX() - mesh.minX()) * 1000) + ":"
                + Math.round((mesh.maxY() - mesh.minY()) * 1000) + ":"
                + Math.round((mesh.maxZ() - mesh.minZ()) * 1000);
    }

    @Test
    void nothingCarriedCostsMoreThanAHandfulOfTriangles() {
        List<String> heavy = new ArrayList<>();
        for (Forage.Item item : Forage.all()) {
            int count = model(item.key()).triangleCount();
            if (count > TRIANGLE_BUDGET) heavy.add(item.key() + " (" + count + ")");
        }
        assertTrue(heavy.isEmpty(),
                "over the " + TRIANGLE_BUDGET + "-triangle budget: " + heavy
                        + " — a clearing holds dozens of these at once");
    }

    /**
     * Everything is built standing on the point it is given, at about the size
     * of the hand that would be holding it.
     *
     * <p>Both halves matter. One model that sank below its own base would be
     * one item that is buried in the ground it is lying on; one model a metre
     * across would be a berry the size of a bush, on the feeder tray that
     * called it. The rod is the deliberate exception and says so.
     */
    @Test
    void everyModelStandsOnItsPointAndIsAboutTheSizeOfAHand() {
        for (Forage.Item item : Forage.all()) {
            Mesh mesh = model(item.key());
            double tall = mesh.maxZ() - mesh.minZ();
            double wide = Math.max(mesh.maxX() - mesh.minX(), mesh.maxY() - mesh.minY());
            assertTrue(mesh.minZ() > -0.03,
                    item.key() + " reaches " + mesh.minZ()
                            + " m below the ground it is put on");
            assertTrue(wide < 0.6,
                    item.key() + " is " + wide + " m across — that is a bush");
            double ceiling = item.key().equals("rod") ? 1.6 : 0.5;
            assertTrue(tall < ceiling,
                    item.key() + " is " + tall + " m tall");
            assertTrue(tall > 0.01 && wide > 0.01,
                    item.key() + " has no size at all: " + wide + " × " + tall);
        }
    }

    /**
     * <b>A blueberry is blue.</b> The colours used to come out of the key's
     * hash, which guarantees only that two items differ — so snowberry, which
     * is white, was maroon. Most of the catalogue is now a table, and the ones
     * that are not fall back to the hash.
     */
    @Test
    void theColoursAreLookedUpRatherThanHashed() {
        assertFalse(ItemModel.colourOf("blueberry", Forage.Kind.BERRY)
                        == ItemModel.tintOf("blueberry", Forage.Kind.BERRY),
                "blueberry is still taking whatever colour its hash gives it");
        int blue = ItemModel.colourOf("blueberry", Forage.Kind.BERRY);
        assertTrue((blue & 0xFF) > ((blue >> 16) & 0xFF), "a blueberry is not blue");
        int snow = ItemModel.colourOf("snowberry", Forage.Kind.BERRY);
        assertTrue((snow & 0xFF) > 180 && ((snow >> 16) & 0xFF) > 180,
                "a snowberry is not white");
        // And anything the table has not heard of still gets a sane colour
        // rather than black.
        assertEquals(ItemModel.tintOf("no_such_item", Forage.Kind.SEED),
                ItemModel.colourOf("no_such_item", Forage.Kind.SEED));
    }

    /** An item nobody has heard of draws <em>something</em> rather than throwing. */
    @Test
    void anUnknownKeyStillDrawsSomething() {
        Mesh mesh = model("a_thing_from_a_future_version");
        assertFalse(mesh.isEmpty(), "an unknown item drew nothing");
    }

    // --- the pictures in the satchel ------------------------------------------------

    /**
     * Every row of the satchel screen can show what it is carrying, and no two
     * rows show the same picture.
     *
     * <p>The count of distinct pictures is the real assertion: a portrait
     * pipeline that quietly rendered nothing would still return a hundred
     * images, all of them the background colour.
     */
    @Test
    void everyItemHasAPictureAndTheyAreNotAllTheSamePicture() {
        int background = 0x121C17;
        Set<String> pictures = new TreeSet<>();
        List<String> blank = new ArrayList<>();
        for (Forage.Item item : Forage.all()) {
            BufferedImage picture = ItemPortrait.of(item.key(), 48, background);
            assertEquals(48, picture.getWidth());
            assertEquals(48, picture.getHeight());
            if (painted(picture, background) < 24) blank.add(item.key());
            pictures.add(digest(picture));
        }
        assertTrue(blank.isEmpty(),
                "these came out as an empty box in the satchel: " + blank);
        assertTrue(pictures.size() > Forage.all().size() * 0.9,
                "only " + pictures.size() + " distinct pictures for "
                        + Forage.all().size() + " items");
    }

    @Test
    void aPictureIsDrawnOnceAndKept() {
        BufferedImage first = ItemPortrait.of("acorn", 40, 0x000000);
        assertTrue(first == ItemPortrait.of("acorn", 40, 0x000000),
                "the satchel re-renders every row of every frame");
        ItemPortrait.invalidate();
        assertFalse(first == ItemPortrait.of("acorn", 40, 0x000000),
                "a texture pack change cannot clear the pictures");
    }

    /** How many pixels are something other than the background. */
    private static int painted(BufferedImage image, int background) {
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) != (background & 0xFFFFFF)) count++;
            }
        }
        return count;
    }

    private static String digest(BufferedImage image) {
        long hash = 1469598103934665603L;
        for (int y = 0; y < image.getHeight(); y += 2) {
            for (int x = 0; x < image.getWidth(); x += 2) {
                hash = (hash ^ image.getRGB(x, y)) * 1099511628211L;
            }
        }
        return Long.toHexString(hash);
    }
}
