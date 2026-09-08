package com.larsons.engine.watch;

import com.larsons.engine.config.GameContext;
import com.larsons.engine.config.GameTypeStore;
import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.graphics.draw.RecordingTarget;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.scene.SceneManager;
import com.larsons.engine.demo.WatchLobbyScene;
import com.larsons.engine.demo.WatchScene;
import com.larsons.engine.ui.ConfigForm;
import com.larsons.engine.watch.life.AnimState;
import com.larsons.engine.watch.model.ModelRig;
import com.larsons.engine.watch.model.SceneModel;
import com.larsons.engine.watch.model.SceneModels;
import com.larsons.engine.watch.net.WatchClient;
import com.larsons.engine.watch.net.WatchServer;
import com.larsons.engine.watch.net.WatchSession;
import com.larsons.engine.watch.render.CosmeticModel;
import com.larsons.engine.watch.render.Mesh;
import com.larsons.engine.watch.render.WalkerModel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JPanel;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two people you can walk as, and the two wardrobes cut to them.
 *
 * <h2>What this file is guarding</h2>
 *
 * <p>A worn cosmetic is the one model in this game that is <b>never measured
 * and never rescaled</b>: the metre an artist put a hat at is the metre it is
 * worn at. That is the right call — it is what lets a cape hang off the
 * shoulders <em>and</em> reach the knee — and it has one consequence that
 * nothing else in the repository would notice going wrong: a wardrobe is cut
 * to a body, so thirty-six files are only correct <em>relative to two other
 * files</em>, and all thirty-eight of them are binaries that do not diff.
 *
 * <p>So the assertions here are mostly about fit. They draw the real figure
 * and the real garment out of {@code src/main/resources} and check that the
 * hat is at head height on the head it was cut for; that the two wardrobes are
 * genuinely two rather than a copy; that a piece stays inside the budget six
 * of them on eight people in one clearing implies. A number moved in
 * {@code tools/blender/figures.py} and re-exported for one figure and not the
 * other fails this rather than reaching a screenshot.
 *
 * <p>The rest is the choice itself: that it survives a save, rides everybody's
 * snapshot row, is offered before the walk starts and in the middle of one,
 * and costs nothing at all.
 *
 * <p><b>This is one of the few test classes that deliberately wants the
 * classpath.</b> Its subject is the art that ships, so it asks the loader for
 * exactly what a player would get — see {@link SceneModels.Sources}, and see
 * {@code CosmeticsTest}, which wants the opposite for the opposite reason.
 */
@Timeout(180)
class PlayerFiguresTest {

    /** How tall both figures stand, and what every window below is read against. */
    private static final double HEIGHT = WalkerModel.HEIGHT;

    private WatchServer server;
    private final List<WatchClient> clients = new ArrayList<>();

    @BeforeEach
    void theShippedArt() {
        // Explicit rather than assumed. Every other class that touches this
        // switch narrows it and puts it back, and one that forgot would leave
        // this file quietly measuring nothing at all.
        SceneModels.setDirectory(Path.of(SceneModels.DIRECTORY));
        SceneModels.setSources(SceneModels.Sources.FOLDER_AND_CLASSPATH);
    }

    @AfterEach
    void tearDown() {
        for (WatchClient client : clients) client.close();
        clients.clear();
        if (server != null) server.stop();
    }

    // --- the catalogue ------------------------------------------------------------------

    /** Two of them, each with a key, a name, a line and a file of its own. */
    @Test
    void everyFigureIsAWholeAnswer() {
        List<Figure> all = Figure.all();
        assertTrue(all.size() >= 2, "one figure is not a choice");
        Set<String> keys = new HashSet<>();
        Set<String> labels = new HashSet<>();
        for (Figure figure : all) {
            assertTrue(keys.add(figure.key()), "two figures answer to " + figure.key());
            assertTrue(labels.add(figure.label()), "two figures called " + figure.label());
            assertFalse(figure.note().isBlank(), figure.key() + " has nothing said about it");
            assertEquals(figure, Figure.byKey(figure.key()));
            assertEquals(figure, Figure.of(figure.key()));
            assertEquals("characters/" + figure.key(), figure.model());
            // The label, so a ConfigForm row reads as a person rather than as
            // an enum constant.
            assertEquals(figure.label(), figure.toString());
        }
        assertTrue(all.contains(Figure.DEFAULT), "the default is not one of them");
        assertNotEquals(Figure.DEFAULT, Figure.DEFAULT.next(),
                "next() must actually go somewhere");
        // Round, so a screen can offer this as one key rather than as a list.
        Figure at = Figure.DEFAULT;
        for (int i = 0; i < all.size(); i++) at = at.next();
        assertEquals(Figure.DEFAULT, at, "cycling the figures does not come back round");
    }

    /**
     * A key this build has never heard of is a walker, not a crash.
     *
     * <p>The one guarantee worth having about somebody joining from a version
     * with a third figure in it: they are still <em>drawn</em>. Everything else
     * about them arrives correctly and their body is the one this game drew
     * before there was a choice.
     */
    @Test
    void anUnknownFigureIsTheOneThisGameAlwaysDrew() {
        assertNull(Figure.byKey("mothman"));
        assertNull(Figure.byKey(null));
        assertEquals(Figure.DEFAULT, Figure.of("mothman"));
        assertEquals(Figure.DEFAULT, Figure.of(null));
        assertEquals(Figure.DEFAULT, Figure.of(""));
    }

    // --- the models that ship -----------------------------------------------------------

    /**
     * Both figures are modelled, both stand the same height, and both carry
     * every clip a walker is ever drawn in.
     *
     * <p><b>Five clips is not a nicety.</b> A state with no clip is posed by
     * {@code ModelRig}'s procedural table, which works per piece about each
     * bone's own pivot rather than down the hierarchy — invisible at an idle's
     * 0.03 radians and, at a run's 0.67, a hand left hovering at the wrist its
     * arm has swung away from. A walker is drawn in exactly these five states,
     * so five clips means the fallback never runs on either figure.
     */
    @Test
    void bothFiguresAreModelledAndCarryEveryClipAWalkerIsDrawnIn() {
        for (Figure figure : Figure.all()) {
            SceneModel model = SceneModels.of(figure.model(), ModelRig.Kind.HUMANOID,
                    SceneModel.Size.height(1));
            assertNotNull(model, figure.key() + " has no model in watch/models");
            assertTrue(WalkerModel.imported(figure),
                    figure.key() + " is not what the renderer would draw");
            for (AnimState state : List.of(AnimState.IDLE, AnimState.WALK, AnimState.RUN,
                    AnimState.SWIM, AnimState.ROW)) {
                assertTrue(model.animates(state),
                        figure.key() + " has no " + state.name().toLowerCase() + " clip");
            }

            Mesh standing = standing(figure);
            assertEquals(0, standing.minZ(), 0.02,
                    figure.key() + " does not stand on the ground");
            assertEquals(HEIGHT, standing.maxZ(), 0.02,
                    figure.key() + " is not " + HEIGHT + " m to the crown");
            // §14's ceiling for a character. Both figures pay the same, which
            // is what stops one of them being the one you pick to be seen from
            // further away.
            assertTrue(standing.triangleCount() <= 1200,
                    figure.key() + " is " + standing.triangleCount() + " triangles, over "
                            + "the 1200 a character is budgeted");
            assertTrue(standing.triangleCount() >= 400,
                    figure.key() + " is " + standing.triangleCount() + " triangles, which "
                            + "is a placeholder rather than a person");
        }
    }

    /**
     * The second figure is a different person, not a smaller one.
     *
     * <p><b>The failure this catches is the usual way a second figure goes
     * wrong.</b> Scaled down, the walker would be a child: same proportions,
     * less of them. So the two are held apart where a build differs — the
     * shoulders come in while the hips stay — and held together where a
     * <em>person</em> does not, which is the height the whole wardrobe is
     * hung from.
     */
    @Test
    void theSecondFigureIsADifferentPersonAndNotASmallerOne() {
        Mesh walker = standing(Figure.WALKER);
        Mesh wayfarer = standing(Figure.WAYFARER);

        assertEquals(walker.maxZ(), wayfarer.maxZ(), 0.02,
                "the two figures are different heights");
        // Across the shoulders, measured where the shoulders are rather than
        // over the whole figure. The band is wide because a body is built out
        // of tapers, which have vertices only at their two ends: a narrow slice
        // of one can contain nothing at all.
        double walkerShoulder = widthAt(walker, 1.05, 1.30);
        double wayfarerShoulder = widthAt(wayfarer, 1.05, 1.30);
        assertTrue(wayfarerShoulder < walkerShoulder - 0.03,
                "the wayfarer's shoulders are " + wayfarerShoulder + " m against the "
                        + "walker's " + walkerShoulder + " — that is the same build");

        // …and **not uniformly narrower**, which is the assertion that actually
        // separates a second figure from a smaller copy of the first. Measured
        // as a ratio at two heights rather than as two widths, so it does not
        // matter what any one band happens to catch: what has to be true is
        // that the shoulders came in further than the feet did.
        double feet = widthAt(wayfarer, -0.01, 0.08) / widthAt(walker, -0.01, 0.08);
        double shoulders = wayfarerShoulder / walkerShoulder;
        assertTrue(shoulders < feet - 0.04,
                "the wayfarer is " + round(shoulders * 100) + "% of the walker across "
                        + "the shoulders and " + round(feet * 100) + "% across the feet "
                        + "— that is one figure scaled down, not two people");
    }

    // --- the wardrobes ------------------------------------------------------------------

    /**
     * Every piece in the catalogue is modelled for every figure.
     *
     * <p>The cheap failure this catches: somebody adds a row to
     * {@code Cosmetics.build}, runs {@code tools/blender/cosmetics.py} for one
     * figure and forgets the other, and half the party is in boxes.
     */
    @Test
    void everyPieceIsModelledForEveryFigure() {
        for (Figure figure : Figure.all()) {
            for (Cosmetics.Piece piece : Cosmetics.all()) {
                assertNotNull(CosmeticModel.importedFor(figure, piece.key()),
                        figure.key() + " has no modelled " + piece.key());
            }
            assertEquals(List.of(), CosmeticModel.boxesOnly(figure, everything()),
                    figure.key() + " is still wearing boxes somewhere");
        }
    }

    /**
     * Two wardrobes, and they are two rather than one copied.
     *
     * <p>If this fails the whole exercise was pointless: a piece that is
     * byte-identical between the figures is a piece that was cut to one of them
     * and posted to the other, which is precisely the thing
     * {@link CosmeticModel#importedFor} exists to avoid.
     */
    @Test
    void aWardrobeIsCutToItsOwnFigure() {
        int different = 0;
        for (Cosmetics.Piece piece : Cosmetics.all()) {
            Mesh one = worn(Figure.WALKER, piece.key());
            Mesh two = worn(Figure.WAYFARER, piece.key());
            if (!bounds(one).equals(bounds(two))) different++;
        }
        // Not all eighteen: a lanyard hangs off a neck and two necks are two
        // necks, but a pair of spectacles could legitimately come out the same
        // if the two faces ever agreed. Most of them is the assertion.
        assertTrue(different >= Cosmetics.all().size() - 2,
                only(different) + " of " + Cosmetics.all().size() + " pieces differ "
                        + "between the figures — the second wardrobe is a copy");
    }

    /**
     * <b>The one that matters.</b> Everything is worn where its slot says it is.
     *
     * <p>A cosmetic is {@code AS_PLACED}: never measured, never moved. So the
     * only thing standing between a modelled hat and a hat floating a foot over
     * somebody's head is that the artist typed the right number, and this is
     * where that is checked — against the figure the game actually draws, for
     * every piece and every figure, in the metres both are written in.
     *
     * <p>The windows are wide on purpose. This is not a test of taste; it is a
     * test that a wardrobe was cut to the figure it is filed under, which is
     * the mistake that actually happens: re-exported against the wrong body's
     * table, every piece lands 100–400 mm out at once. The one place a window
     * alone would not have caught it is the head — §16's reference figure is
     * 1.95 m to the top of its hat and these two are 1.78, so a hat cut to the
     * old table sits <em>above</em> the head rather than on it and any band
     * generous enough for a hood would let it through. That is what the extra
     * assertion about reaching down onto the crown is for.
     */
    @Test
    void everyPieceIsWornWhereItsSlotSaysItIs() {
        // Bottom and top of the band a slot's own geometry has to sit in, in
        // metres up a standing figure. Read off the figures themselves: the
        // head is at 1.45-1.47 and the hat over it reaches 1.78, a hand hangs
        // at 0.59-0.60, a boot cuff tops out at 0.25.
        Map<Cosmetics.Slot, double[]> window = Map.of(
                Cosmetics.Slot.HAIR, new double[]{1.24, 1.72},
                // Wide at the bottom because a hood comes down to the throat,
                // which is a head piece reaching further than a hat does. The
                // real work for this slot is the "down onto the crown" check
                // below, which a band cannot do.
                Cosmetics.Slot.HEAD, new double[]{1.40, 2.02},
                Cosmetics.Slot.FACE, new double[]{1.24, 1.66},
                Cosmetics.Slot.NECK, new double[]{0.92, 1.44},
                Cosmetics.Slot.BODY, new double[]{0.60, 1.30},
                Cosmetics.Slot.BACK, new double[]{0.34, 1.34},
                Cosmetics.Slot.HANDS, new double[]{0.46, 0.74},
                Cosmetics.Slot.LEGS, new double[]{0.14, 0.78},
                Cosmetics.Slot.FEET, new double[]{0.00, 0.50});

        for (Figure figure : Figure.all()) {
            double crown = standing(figure).maxZ();
            for (Cosmetics.Piece piece : Cosmetics.all()) {
                Mesh mesh = worn(figure, piece.key());
                assertFalse(mesh.isEmpty(),
                        figure.key() + "/" + piece.key() + " drew nothing");
                String where = figure.key() + "/" + piece.key();
                double[] band = window.get(piece.slot());
                double middle = (mesh.minZ() + mesh.maxZ()) / 2;
                assertTrue(middle >= band[0] && middle <= band[1],
                        where + " sits at " + round(middle) + " m, which is not "
                                + "anywhere a " + piece.slot().label().toLowerCase()
                                + " piece belongs (" + band[0] + "-" + band[1] + ")");
                // Nothing under the floor and nothing over the hat.
                assertTrue(mesh.minZ() >= -0.03,
                        where + " reaches " + round(mesh.minZ()) + " m, under the ground");
                assertTrue(mesh.maxZ() <= crown + 0.30,
                        where + " reaches " + round(mesh.maxZ()) + " m, well over a head");
                // …and nothing wider than the widest thing on a walker, which
                // is a hat brim at 0.64.
                assertTrue(mesh.maxX() - mesh.minX() <= 0.90,
                        where + " is " + round(mesh.maxX() - mesh.minX()) + " m across");

                // **Worn on the wearer, not hovering over them.** A head piece
                // goes over a hat that is already there, so it has to come down
                // onto the crown; a back piece hangs off the shoulders, so its
                // top is at the yoke and not somewhere above the ears.
                if (piece.slot() == Cosmetics.Slot.HEAD
                        || piece.slot() == Cosmetics.Slot.HAIR) {
                    assertTrue(mesh.minZ() <= crown - 0.08,
                            where + " starts at " + round(mesh.minZ()) + " m on a figure "
                                    + round(crown) + " m tall — it is floating over the "
                                    + "head rather than being worn on it");
                }
                if (piece.slot() == Cosmetics.Slot.BACK) {
                    assertTrue(mesh.maxZ() <= crown - 0.42,
                            where + " reaches " + round(mesh.maxZ()) + " m, which is over "
                                    + "the shoulders it is supposed to hang from");
                }
            }
        }
    }

    /**
     * The pieces worn on a pair of limbs are worn on both of them.
     *
     * <p>Which is the other half of "is it in the right place": a mitten built
     * once and mirrored badly is a mitten on one hand and nothing on the other,
     * and the vertical window above cannot see that at all.
     */
    @Test
    void mittensAndBootsComeInPairs() {
        for (Figure figure : Figure.all()) {
            for (Cosmetics.Piece piece : Cosmetics.all()) {
                if (piece.slot() != Cosmetics.Slot.HANDS
                        && piece.slot() != Cosmetics.Slot.FEET) {
                    continue;
                }
                Mesh mesh = worn(figure, piece.key());
                String where = figure.key() + "/" + piece.key();
                assertTrue(mesh.minX() < -0.04, where + " has nothing on the -x side");
                assertTrue(mesh.maxX() > 0.04, where + " has nothing on the +x side");
                // …and a gap between them, or it is one slab across the middle.
                assertEquals(0, (mesh.minX() + mesh.maxX()) / 2, 0.04,
                        where + " is not centred on the figure");
            }
        }
    }

    /** What is worn on the back is worn on the back. */
    @Test
    void aCapeHangsBehindTheWalkerAndNotThroughThem() {
        for (Figure figure : Figure.all()) {
            for (Cosmetics.Piece piece : Cosmetics.inSlot(Cosmetics.Slot.BACK)) {
                Mesh mesh = worn(figure, piece.key());
                // Forward is -y, so a back piece has to reach well past the
                // middle the other way. The pack it goes over stands at +0.30.
                assertTrue(mesh.maxY() > 0.24,
                        figure.key() + "/" + piece.key() + " reaches only "
                                + round(mesh.maxY()) + " m behind the middle, which is "
                                + "in front of the pack it is worn over");
            }
        }
    }

    /**
     * A whole outfit stays inside what eight people in a clearing can afford.
     *
     * <p>Six pieces on one person and eight people in one wood is the number
     * this budget is written against: a 900-triangle cape is 43,000 triangles
     * of coat in a clearing, on a software rasteriser.
     */
    @Test
    void awardrobeStaysInsideItsBudget() {
        for (Figure figure : Figure.all()) {
            for (Cosmetics.Piece piece : Cosmetics.all()) {
                int count = worn(figure, piece.key()).triangleCount();
                // 250 for a rail piece, and half as much again for the standard
                // kit: a coat with sleeves and a skirt is the biggest garment
                // anybody wears, it is worn by everybody all the time, and it
                // is geometry that used to sit inside the 1200 the body had.
                int ceiling = piece.kit() ? 380 : 250;
                assertTrue(count <= ceiling,
                        figure.key() + "/" + piece.key() + " is " + count
                                + " triangles, over the " + ceiling + " a "
                                + (piece.kit() ? "standard-kit garment" : "piece")
                                + " is budgeted");
                // …and more than the boxes it replaced, which is the whole
                // reason anybody modelled it.
                assertTrue(count >= 40,
                        figure.key() + "/" + piece.key() + " is " + count
                                + " triangles, which is no better than its boxes");
            }
            int outfit = 0;
            for (Cosmetics.Slot slot : Cosmetics.Slot.values()) {
                int dearest = 0;
                for (Cosmetics.Piece piece : Cosmetics.inSlot(slot)) {
                    dearest = Math.max(dearest, worn(figure, piece.key()).triangleCount());
                }
                outfit += dearest;
            }
            // Everything at once, on top of a body that is now 470 triangles
            // rather than 1180 — so the dearest possible walker comes out about
            // where the old fully-modelled one did, which is the number this
            // whole split had to not make worse.
            assertTrue(outfit + standing(figure).triangleCount() <= 2400,
                    figure.key() + " in the dearest of everything is " + outfit
                            + " triangles of clothes over "
                            + standing(figure).triangleCount() + " of body");
        }
    }

    /**
     * A modelled piece really does replace its boxes on a modelled figure.
     *
     * <p>The two halves of the mechanism, held together: what
     * {@link CosmeticModel#overlay} draws is exactly what
     * {@link CosmeticModel#boxesOnly} took off the joints, so a dressed walker
     * is the figure plus the garment and never the figure plus both.
     */
    @Test
    void aDressedFigureIsTheFigurePlusTheGarmentAndNotBoth() {
        for (Figure figure : Figure.all()) {
            Mesh bare = standing(figure);
            Mesh dressed = dressed(figure, everything());
            assertTrue(dressed.triangleCount() > bare.triangleCount(),
                    figure.key() + " wearing everything drew no more than wearing nothing");
            int garments = 0;
            for (String key : everything()) garments += worn(figure, key).triangleCount();
            assertEquals(bare.triangleCount() + garments, dressed.triangleCount(),
                    figure.key() + " drew a garment twice, or drew boxes under a "
                            + "modelled piece");
        }
    }

    /**
     * <b>The clothes move with the body.</b>
     *
     * <p>A worn piece and the figure under it are two models with two rigs drawn
     * by two calls. Left to themselves each animates on its own: the body plays
     * the {@code walk} its artist authored — root dropped to put the lower boot
     * on the floor, chest leaning, head nodding — and the garment, having no
     * clip of its own, was posed by the procedural stand-in instead. Standing
     * still nobody could see it. At a run the head came 50 mm out from under the
     * hat, once a stride, for ever.
     *
     * <p>So this walks a whole stride and compares how far the hat travelled
     * against how far the head under it did. Three things about how it measures
     * are load-bearing:
     *
     * <ul>
     *   <li><b>Differences, not heights.</b> A bobble hat legitimately stands
     *       90 mm proud of a crown; the question is not where it sits but
     *       whether it comes along.</li>
     *   <li><b>Centroids, not extremes.</b> The head nods and rolls as well as
     *       rising, and the <em>highest vertex</em> of a 0.11 m bobble and of a
     *       0.27 m crown swing on different arms — so a max jumps between
     *       vertices and disagrees by millimetres for entirely correct reasons.
     *       A centroid does not move when the mesh does not.</li>
     *   <li><b>Both read off the same drawn figure</b>, the hat from the piece
     *       and the head from the body's own vertices above the shoulders, so
     *       nothing about how either is placed enters into it.</li>
     * </ul>
     *
     * <p>Five millimetres of slack, and it is slack rather than tolerance: the
     * two sets of geometry hang off the same joint at different offsets from
     * it, so a rotation — and the walk turns the head, the chest and the hips —
     * moves their centroids by slightly different amounts, correctly. What the
     * bug looked like is not five millimetres: posed by the stand-in the hat
     * did not rise <em>at all</em> while the head moved 20–55 mm a stride, so
     * what this separates is an order of magnitude wide.
     */
    @Test
    void aHatBobsWithTheHeadUnderIt() {
        for (Figure figure : Figure.all()) {
            String hat = Cosmetics.inSlot(Cosmetics.Slot.HEAD).get(0).key();
            String boots = Cosmetics.inSlot(Cosmetics.Slot.FEET).get(0).key();
            // **The same vertices at every phase, chosen once.** Picked afresh
            // by height each time, the head's own set would shed vertices as
            // the figure bobs down past the cut and its centroid would move by
            // more than the head did — which is a bug in the measurement that
            // looks exactly like a bug in the thing being measured.
            Mesh still = walking(figure, List.of(), 0);
            int[] head = between(still, 1.55, 9);
            int[] feet = between(still, -9, 0.30);
            double restingHead = centroidZ(still, head);
            double restingHat = centroidZ(walkingWorn(figure, hat, 0), null);
            double restingFeet = centroidZ(still, feet);
            double restingBoots = centroidZ(walkingWorn(figure, boots, 0), null);
            double moved = 0;
            for (double phase : new double[]{0.15, 0.25, 0.4, 0.6, 0.75, 0.9}) {
                double nodded = centroidZ(walking(figure, List.of(), phase), head)
                        - restingHead;
                double crown = centroidZ(walkingWorn(figure, hat, phase), null)
                        - restingHat;
                moved = Math.max(moved, Math.abs(nodded));
                assertEquals(nodded, crown, 0.005,
                        figure.key() + " at phase " + phase + ": the head moved "
                                + round(nodded) + " m and the hat on it moved "
                                + round(crown) + " — the clothes are not following "
                                + "the body");
                // …and the boots go with the feet in them, which at the middle
                // of a stride is a good deal of movement to keep up with.
                assertEquals(centroidZ(walking(figure, List.of(), phase), feet)
                                - restingFeet,
                        centroidZ(walkingWorn(figure, boots, phase), null)
                                - restingBoots, 0.004,
                        figure.key() + " at phase " + phase + ": a boot is not "
                                + "walking with the foot inside it");
            }
            // …and the walk actually moves, or the assertions above are vacuous.
            assertTrue(moved > 0.012,
                    figure.key() + "'s walk shifts the head by only " + round(moved)
                            + " m, which is not enough of a bob for the comparison "
                            + "above to mean anything");
        }
    }

    /**
     * A garment follows the body into the water and into a boat as well.
     *
     * <p>Those two poses used to keep their boxes, and the folder README said so
     * at length: a swimmer is laid along a spine that can point anywhere and a
     * rower is folded onto a thwart, and neither is something a garment's own
     * clip could ever know. With the body handing over where its joints actually
     * went there is nothing left to fall back to — and it matters more now than
     * it reads, because a coat is a worn piece too and a swimmer without one is
     * a swimmer in their underwear.
     */
    @Test
    void aSwimmerAndARowerAreDressedToo() {
        String coat = Cosmetics.inSlot(Cosmetics.Slot.BACK).get(0).key();
        for (Figure figure : Figure.all()) {
            Mesh.Builder swimming = Mesh.builder(0, 0, 0, false, 1);
            WalkerModel.swimmer(swimming, figure, 0, 0, 0, 0, 0.1, 1, 0.3, true,
                    0x4A6B33, List.of(coat));
            Mesh.Builder bareSwim = Mesh.builder(0, 0, 0, false, 1);
            WalkerModel.swimmer(bareSwim, figure, 0, 0, 0, 0, 0.1, 1, 0.3, true,
                    0x4A6B33, List.of());
            Mesh dressed = swimming.build();
            Mesh bare = bareSwim.build();
            assertTrue(dressed.triangleCount() > bare.triangleCount(),
                    figure.key() + " swims with nothing on");
            // Laid down with them: a swimmer at this pitch is nearly flat, so
            // the cloak has to be long fore-and-aft and short vertically. Left
            // upright it would stand a metre and a half over the lake.
            assertTrue(dressed.maxZ() < bare.maxZ() + 0.30,
                    figure.key() + "'s cloak stands " + round(dressed.maxZ())
                            + " m over a swimmer whose own top is "
                            + round(bare.maxZ()) + " — it did not lie down");

            Mesh.Builder rowing = Mesh.builder(0, 0, 0, false, 1);
            WalkerModel.rower(rowing, figure, 0, 0, 0, 0, 0.25, 0.2, 0x4A6B33,
                    List.of(coat));
            Mesh.Builder bareRow = Mesh.builder(0, 0, 0, false, 1);
            WalkerModel.rower(bareRow, figure, 0, 0, 0, 0, 0.25, 0.2, 0x4A6B33,
                    List.of());
            assertTrue(rowing.build().triangleCount() > bareRow.build().triangleCount(),
                    figure.key() + " rows with nothing on");
            assertTrue(rowing.build().maxZ() < bareRow.build().maxZ() + 0.25,
                    figure.key() + "'s cloak is standing up in the boat");
        }
    }

    // --- undressing ---------------------------------------------------------------------

    /**
     * <b>A walker with nothing on is in their underwear, not naked and not
     * still in a coat.</b>
     *
     * <p>The coat, the trousers, the boots, the pack, the hat and the hair used
     * to be modelled into {@code characters/<figure>.glb} and could not come
     * off. What is in that file now is a body in a vest and a pair of shorts,
     * and everything else is a piece — which is the whole of what makes the
     * wardrobe screen able to take anything off.
     */
    @Test
    void takingEverythingOffLeavesSomebodyInTheirUnderwear() {
        for (Figure figure : Figure.all()) {
            Mesh body = standing(figure);
            assertEquals(0, body.minZ(), 0.02, figure.key() + " is not standing on the ground");
            assertEquals(HEIGHT, body.maxZ(), 0.02,
                    figure.key() + " is not " + HEIGHT + " m to the crown undressed");
            // A body is cheap, which is the point of splitting the clothes off:
            // what used to be 1180 triangles you could never take off is 500
            // you always pay and 900 you choose.
            assertTrue(body.triangleCount() <= 700,
                    figure.key() + "'s bare body is " + body.triangleCount()
                            + " triangles — there is still clothing modelled into it");
            // …and it is a body rather than an outline.
            assertTrue(body.triangleCount() >= 250,
                    figure.key() + "'s bare body is only " + body.triangleCount()
                            + " triangles");
            // The underwear: something on the torso that is not skin. Read as
            // "more than two colours between the hips and the shoulders",
            // because a bare chest would be one and a shaded one two.
            assertTrue(coloursBetween(body, 0.75, 1.15).size() >= 3,
                    figure.key() + " has nothing on between the hips and the "
                            + "shoulders — the vest went missing");
        }
    }

    /** …and is wearing the standard kit before they take a step. */
    @Test
    void everybodySetsOffInTheStandardKit() {
        for (Figure figure : Figure.all()) {
            WatchGame game = new WatchGame(WatchGame.Config.hosted("Kit", 5L));
            game.join(1, "Kara");
            game.setFigure(1, figure.key());
            assertEquals(figure, game.player(1).figure());
            Outfit outfit = game.player(1).outfit();
            for (Cosmetics.Piece piece : Cosmetics.standardKit()) {
                assertTrue(outfit.owns(piece.key()),
                        figure.key() + " does not own their own " + piece.key());
            }
            for (Cosmetics.Piece piece : Cosmetics.standardKit()) {
                if (piece.slot() == Cosmetics.Slot.HAIR) continue;
                assertEquals(piece.key(), outfit.wornIn(piece.slot()),
                        figure.key() + " set off without their " + piece.key());
            }
            assertNotNull(outfit.wornIn(Cosmetics.Slot.HAIR),
                    figure.key() + " set off bald");
            // Everything the kit covers can come off again, which is the whole
            // reason it is a wardrobe rather than a body.
            for (Cosmetics.Piece piece : Cosmetics.standardKit()) {
                if (outfit.wearing(piece.key())) assertNotNull(game.wear(1, piece.key()));
            }
            assertTrue(outfit.bare(), figure.key() + " could not be undressed");
        }
    }

    /**
     * A save written before the coat came off reopens dressed.
     *
     * <p>Such a save has a wardrobe and an outfit and neither of them mentions
     * trousers, because trousers were part of the figure when it was written.
     * Without {@code Outfit.dressIn} the walk reopens in a vest.
     */
    @Test
    void aWalkSavedBeforeTheCoatCameOffReopensDressed() {
        WatchGame game = new WatchGame(WatchGame.Config.hosted("Old", 9L));
        game.join(1, "Kara");
        WatchPlayer player = game.player(1);
        // A save from before: a bought scarf, worn, and nothing else at all.
        player.load(Map.of("fit", Map.of("own", List.of("wool_scarf"),
                "on", "wool_scarf")));
        Outfit outfit = player.outfit();
        assertTrue(outfit.wearing("wool_scarf"), "the old save lost what it did have");
        assertEquals("wool_scarf", outfit.wornIn(Cosmetics.Slot.NECK),
                "the standard neckerchief pushed a bought scarf off");
        for (Cosmetics.Piece piece : Cosmetics.standardKit()) {
            assertTrue(outfit.owns(piece.key()), piece.key() + " was not granted");
        }
        assertNotNull(outfit.wornIn(Cosmetics.Slot.BODY), "reopened without a coat");
        assertNotNull(outfit.wornIn(Cosmetics.Slot.LEGS), "reopened without trousers");
    }

    // --- colour -------------------------------------------------------------------------

    /**
     * <b>Dyeing a coat changes the coat and leaves its buttons alone.</b>
     *
     * <p>Every garment here is painted out of a tin of five — a main, a main at
     * four fifths, a main at one and a sixth, a trim and a trim darkened — and
     * the three mains are exactly one colour scaled, because that is how the
     * Blender script mixes them. So {@code SceneModel} can recognise "the base
     * colour and its shades" in a finished mesh without the artist labelling
     * anything, and a dyed coat keeps its brass.
     */
    @Test
    void dyeingAPieceMovesItsBaseColourAndNothingElse() {
        for (Figure figure : Figure.all()) {
            for (String key : List.of("field_coat", "heron_cloak", "walking_boots")) {
                SceneModel model = CosmeticModel.importedFor(figure, key);
                assertNotNull(model, figure.key() + " has no " + key);
                int base = model.baseColour();
                assertNotEquals(0, base, key + " has no base colour to dye");

                // **Read as sets and as means, never as one exact value.** The
                // colours on a built mesh have already had the flat shading
                // folded into them — a triangle is lit from its own normal —
                // so the byte in the buffer is the dye times a face's light and
                // asking for the dye back verbatim would be asking the wrong
                // question.
                Mesh made = worn(figure, key, 0);
                Mesh red = worn(figure, key, 0xC03040);
                Mesh blue = worn(figure, key, 0x3040C0);
                assertNotEquals(coloursOf(made), coloursOf(red),
                        figure.key() + "/" + key + " ignored a dye");
                assertEquals(made.triangleCount(), red.triangleCount(),
                        figure.key() + "/" + key + " came back a different garment");

                // Dyed red it is redder than when dyed blue, and bluer the
                // other way about — which is the whole claim, and the one that
                // a shade table read off the wrong channel would fail.
                assertTrue(meanChannel(red, 16) > meanChannel(blue, 16) + 8,
                        figure.key() + "/" + key + " dyed red is no redder than dyed "
                                + "blue");
                assertTrue(meanChannel(blue, 0) > meanChannel(red, 0) + 8,
                        figure.key() + "/" + key + " dyed blue is no bluer than dyed "
                                + "red");

                // …and the trim does not move with it: what is left in both
                // sets is the colours that are nobody's business but the
                // artist's.
                Set<Integer> kept = new HashSet<>(coloursOf(made));
                kept.retainAll(coloursOf(red));
                assertFalse(kept.isEmpty(),
                        figure.key() + "/" + key + " is one flat colour once dyed — "
                                + "its trim, buckles and lining moved with the base");
            }
        }
    }

    /** A dye is worth nothing, survives a save, and rides everybody's row. */
    @Test
    void aDyeSurvivesASaveAndReachesEverybodyElse(@TempDir Path dir) {
        WatchGame game = new WatchGame(WatchGame.Config.hosted("Dye", 11L));
        game.join(1, "Kara");
        int points = game.guide().points();
        assertTrue(game.dye(1, "field_coat", 0x8A3B2E), "the host would not dye a coat");
        assertEquals(points, game.guide().points(), "dyeing a coat cost points");
        assertEquals(0x8A3B2E, game.player(1).outfit().colourOf("field_coat"));
        // Allowed on anything in the catalogue, not only on what is owned:
        // choosing what colour you would dye a cloak is not claiming one.
        assertTrue(game.dye(1, "heron_cloak", 0x203040));
        assertFalse(game.dye(1, "not_a_piece", 0x203040), "dyed something that is not a thing");

        // On the row everybody draws from, and only for somebody who dyed.
        assertEquals("field_coat:8A3B2E,heron_cloak:203040",
                game.player(1).toSnapshot().get("dy"));
        game.join(2, "Sam");
        assertFalse(game.player(2).toSnapshot().containsKey("dy"),
                "an undyed walker put a colour on the wire");

        WatchStore store = new WatchStore(dir.resolve("walks").toString());
        store.save(game);
        WatchGame reopened = new WatchGame(WatchGame.Config.hosted("Dye", 11L));
        assertTrue(store.load(reopened));
        reopened.join(1, "Kara");
        assertEquals(0x8A3B2E, reopened.player(1).outfit().colourOf("field_coat"),
                "a walk reopened forgot what colour the coat was");

        // …and back to how it was made, which is what zero means.
        assertTrue(game.dye(1, "field_coat", 0));
        assertEquals(0, game.player(1).outfit().colourOf("field_coat"));
    }

    /**
     * The wardrobe screen takes things off, puts them on, and dyes them —
     * without going anywhere near a trading post.
     */
    @Test
    void thePauseScreenOpensAWardrobeThatWorks(@TempDir Path dir) {
        try (Walk walk = new Walk(dir)) {
            Outfit outfit = walk.game.player(1).outfit();
            assertNotNull(outfit.wornIn(Cosmetics.Slot.HAIR), "started bald");

            walk.press(KeyEvent.VK_ESCAPE);
            assertEquals("paused", walk.walk.panelName());
            walk.press(KeyEvent.VK_ENTER);
            assertEquals("wardrobe", walk.walk.panelName(),
                    "Enter on the pause screen did not open the wardrobe");

            // The cursor opens on the first slot, which is hair. Right into the
            // list, then Enter takes off whatever is on.
            String hair = outfit.wornIn(Cosmetics.Slot.HAIR);
            walk.press(KeyEvent.VK_RIGHT);
            walk.press(KeyEvent.VK_ENTER);
            assertNull(outfit.wornIn(Cosmetics.Slot.HAIR),
                    "the wardrobe would not take a hairstyle off");
            walk.press(KeyEvent.VK_ENTER);
            assertEquals(hair, outfit.wornIn(Cosmetics.Slot.HAIR),
                    "…or put it back on");

            // Down past the pieces to the colour rows, and right to brighten.
            int owned = Cosmetics.inSlot(Cosmetics.Slot.HAIR).size();
            for (int i = 0; i < owned; i++) walk.press(KeyEvent.VK_DOWN);
            walk.press(KeyEvent.VK_RIGHT);
            assertNotEquals(0, outfit.colourOf(hair),
                    "the colour row did not dye anything");
            // …and the last row puts it back to how it was made.
            for (int i = 0; i < 3; i++) walk.press(KeyEvent.VK_DOWN);
            walk.press(KeyEvent.VK_ENTER);
            assertEquals(0, outfit.colourOf(hair), "\"as made\" did not undo the dye");

            walk.press(KeyEvent.VK_ESCAPE);
            assertEquals("paused", walk.walk.panelName(), "Esc did not go back");
        }
    }

    /**
     * The wardrobe is workable by hand as well as by arrow key.
     *
     * <p>Hovering moves the cursor, clicking does the thing under it, and the
     * colours are three bars you drag — which is what a colour wants and what
     * thirty-two arrow presses per shade is not. The same contract the satchel
     * and the shop already keep, so anybody who has used those has used this.
     *
     * <p>Every row is found by asking the panel where it drew itself rather
     * than by counting pixels here, which is the point of there being a
     * {@code WardrobeBox} at all: a screen whose rows are drawn a few pixels
     * from where they can be clicked is a screen nobody notices is broken until
     * somebody with a different font tries it.
     */
    @Test
    void theWardrobeIsWorkableWithTheMouse(@TempDir Path dir) {
        try (Walk walk = new Walk(dir)) {
            Outfit outfit = walk.game.player(1).outfit();
            walk.press(KeyEvent.VK_ESCAPE);
            walk.press(KeyEvent.VK_ENTER);
            assertEquals("wardrobe", walk.walk.panelName());

            // Click the BODY slot on the left, and the right column follows.
            int body = Cosmetics.Slot.BODY.ordinal();
            walk.click(walk.slotRow(body));
            assertEquals(Cosmetics.Slot.BODY.label(), walk.walk.wardrobeSlot(),
                    "clicking a slot did not select it");

            // …then the coat in it, which takes it off, and again to put it on.
            String coat = outfit.wornIn(Cosmetics.Slot.BODY);
            assertNotNull(coat, "started without a coat");
            int row = ownedRow(outfit, Cosmetics.Slot.BODY, coat);
            walk.click(walk.pieceRow(row));
            assertNull(outfit.wornIn(Cosmetics.Slot.BODY),
                    "clicking the coat did not take it off");
            walk.click(walk.pieceRow(row));
            assertEquals(coat, outfit.wornIn(Cosmetics.Slot.BODY),
                    "…or put it back on");

            // Drag the red bar to the far end, and the coat is dyed by it.
            int[] bar = walk.dyeBar(0);
            walk.drag(bar[0], bar[1], bar[2], bar[1]);
            int dyed = outfit.colourOf(coat);
            assertNotEquals(0, dyed, "dragging the red bar dyed nothing");
            assertTrue(((dyed >> 16) & 0xFF) > 230,
                    "dragging red to the far end left it at "
                            + ((dyed >> 16) & 0xFF));
            // …and back to the near end, which is none of it.
            walk.drag(bar[2], bar[1], bar[0] - 40, bar[1]);
            assertTrue(((outfit.colourOf(coat) >> 16) & 0xFF) < 12,
                    "dragging red back left it at "
                            + ((outfit.colourOf(coat) >> 16) & 0xFF));

            // The cross closes it back to the pause screen.
            walk.click(walk.wardrobeClose());
            assertEquals("paused", walk.walk.panelName(),
                    "the close button did not go back to the pause screen");
        }
    }

    /** Which row of a slot's owned list a key is on. */
    private static int ownedRow(Outfit outfit, Cosmetics.Slot slot, String key) {
        int at = 0;
        for (Cosmetics.Piece piece : Cosmetics.inSlot(slot)) {
            if (!outfit.owns(piece.key())) continue;
            if (piece.key().equals(key)) return at;
            at++;
        }
        throw new IllegalStateException(key + " is not owned");
    }

    // --- the choice ---------------------------------------------------------------------

    /** Changing figure is free, instant, and touches nothing else about a walker. */
    @Test
    void changingFigureCostsNothingAndChangesNothingElse() {
        WatchGame game = new WatchGame(WatchGame.Config.hosted("Figures", 4242L));
        assertNotNull(game.join(1, "Kara"));
        WatchPlayer player = game.player(1);
        assertEquals(Figure.DEFAULT, player.figure(), "a fresh walker is not the default");

        Map<String, Object> before = player.toSnapshot();
        int points = game.guide().points();
        Figure other = Figure.DEFAULT.next();
        assertTrue(game.setFigure(1, other.key()), "the host would not change the figure");
        assertEquals(other, player.figure());
        assertEquals(points, game.guide().points(), "being somebody else cost points");

        Map<String, Object> after = player.toSnapshot();
        for (String key : before.keySet()) {
            assertEquals(before.get(key), after.get(key),
                    "changing figure changed \"" + key + "\" on the player");
        }
        Set<String> added = new HashSet<>(after.keySet());
        added.removeAll(before.keySet());
        assertEquals(Set.of("fg"), added, "a figure put " + added + " on a player row");

        // Nothing else answers, and nothing unknown is accepted.
        assertFalse(game.setFigure(1, other.key()), "setting the same figure said it changed");
        assertFalse(game.setFigure(1, "mothman"), "an unknown figure was accepted");
        assertEquals(other, player.figure());
        assertFalse(game.setFigure(99, Figure.DEFAULT.key()), "a player who is not there");
    }

    /**
     * Only the walkers who are somebody else cost anything on the wire.
     *
     * <p>The same bargain {@link Outfit#wornLine} makes: a party of walkers is
     * exactly the snapshot it always was, and the field appears only for
     * somebody who chose.
     */
    @Test
    void aPartyOfWalkersCostsNothingExtraOnTheWire() {
        WatchGame game = new WatchGame(WatchGame.Config.hosted("Figures", 91L));
        game.join(1, "Kara");
        assertFalse(game.player(1).toSnapshot().containsKey("fg"),
                "the default figure went out on the wire");
        game.setFigure(1, Figure.WAYFARER.key());
        assertEquals(Figure.WAYFARER.key(), game.player(1).toSnapshot().get("fg"));
    }

    /** A walk reopened is a walk in the same body. */
    @Test
    void aFigureSurvivesASave(@TempDir Path dir) {
        WatchGame game = new WatchGame(WatchGame.Config.hosted("Figures", 77L));
        game.join(1, "Kara");
        game.setFigure(1, Figure.WAYFARER.key());

        WatchStore store = new WatchStore(dir.resolve("walks").toString());
        store.save(game);
        WatchGame reopened = new WatchGame(WatchGame.Config.hosted("Figures", 77L));
        assertTrue(store.load(reopened), "the walk did not save");
        reopened.join(1, "Kara");
        assertEquals(Figure.WAYFARER, reopened.player(1).figure(),
                "a walk reopened put somebody else's body on");

        // …and a save from before there were two of them is a walker.
        WatchGame old = new WatchGame(WatchGame.Config.hosted("Figures", 77L));
        old.join(1, "Kara");
        old.player(1).load(new HashMap<>());
        assertEquals(Figure.DEFAULT, old.player(1).figure());
    }

    /**
     * The whole thing over a socket: somebody changes who they are and the
     * friend across the valley sees it.
     *
     * <p>A host verb, for {@link WatchGame#wear}'s reason and not a weaker one:
     * which body somebody is goes out on their row to everybody, so a client
     * that decided for itself would eventually disagree with what the rest of
     * the valley could see.
     */
    @Test
    void changingFigureReachesEverybodyElsesScreen() throws IOException {
        server = new WatchServer(WatchGame.Config.hosted("Figures", 8181L));
        server.start(0);
        WatchClient me = connect("Kara");
        WatchClient friend = connect("Sam");
        until("both to be welcomed", () -> me.ready() && friend.ready());
        until("the host to seat them", () -> server.playerCount() == 2);

        int id = me.view().selfId();
        assertEquals(Figure.DEFAULT, figureOf(friend, id));

        me.sendFigure(Figure.WAYFARER.key());
        until("the friend to see the new figure",
                () -> figureOf(friend, id) == Figure.WAYFARER);
        until("and this client to see it on its own row",
                () -> figureOf(me, id) == Figure.WAYFARER);

        // A key the host does not know leaves them exactly as they were.
        me.sendFigure("mothman");
        me.sendFigure(Figure.WAYFARER.key());
        until("the party to settle", () -> figureOf(friend, id) == Figure.WAYFARER);
        assertEquals(Figure.WAYFARER,
                server.game().player(id).figure(), "an unknown key reached the host's copy");
    }

    // --- looking at yourself --------------------------------------------------------------

    /**
     * <b>The third-person camera goes all the way round.</b>
     *
     * <p>It used to sit behind the walker and nowhere else, which is a fine
     * camera to walk with and useless for the one thing a third-person view in
     * this game is for: seeing what you have on. You cannot check a scarf, a
     * hat's brim or the hang of a cape from directly behind.
     *
     * <p>So the right button swings the camera round, and the assertion is the
     * two things that have to hold at every angle: the camera really is on the
     * other side of the walker, and it is still <b>pointing at them</b> — the
     * orbit turns the look by exactly what it turned the position by, which is
     * what keeps somebody framed instead of sliding out of shot.
     */
    @Test
    void theThirdPersonCameraGoesAllTheWayRound(@TempDir Path dir) {
        try (Walk walk = new Walk(dir)) {
            walk.press(KeyEvent.VK_F5);
            walk.step();
            EyeCamera eye = walk.walk.camera();
            double[] me = {eye.x(), eye.y()};
            double behind = Math.hypot(eye.x() - walk.px(), eye.y() - walk.py());
            assertTrue(behind > 3, "the third-person camera is not standing off at all");
            // Behind: the camera is on the opposite side from the way they face,
            // and forward in this game is -y at a yaw of zero.
            assertTrue(eye.y() > walk.py(),
                    "the camera did not start behind the walker");
            double aimed = pointingAtWalker(walk, eye);
            assertTrue(aimed < 0.35,
                    "the camera does not start pointing at the walker: " + aimed + " rad off");

            // Swing it half a turn with the right button held.
            walk.orbit(180);
            assertTrue(eye.y() < walk.py(),
                    "half a turn of the orbit left the camera behind them");
            assertEquals(behind, Math.hypot(eye.x() - walk.px(), eye.y() - walk.py()),
                    0.6, "the camera changed its distance on the way round");
            assertTrue(pointingAtWalker(walk, eye) < 0.35,
                    "the camera swung round the walker and stopped looking at them");

            // …and all the way, through every quarter, still framed.
            for (int i = 0; i < 8; i++) {
                walk.orbit(45);
                assertTrue(pointingAtWalker(walk, eye) < 0.35,
                        "the camera lost the walker a quarter of the way round");
            }
            assertNotEquals(me[0], eye.x(), "the camera never moved at all");
        }
    }

    /** Walking puts the camera back behind you without anybody asking. */
    @Test
    void walkingOffSwingsTheCameraBackBehindYou(@TempDir Path dir) {
        try (Walk walk = new Walk(dir)) {
            walk.press(KeyEvent.VK_F5);
            walk.orbit(180);
            EyeCamera eye = walk.walk.camera();
            assertTrue(eye.y() < walk.py(), "the orbit did not take");
            // Forward for a second, which is long enough for a 0.45 s recentre.
            walk.hold(KeyEvent.VK_W);
            for (int i = 0; i < 120; i++) walk.step();
            walk.release(KeyEvent.VK_W);
            walk.step();
            assertTrue(eye.y() > walk.py(),
                    "the camera was still in front of somebody walking forwards");
        }
    }

    /**
     * In first person the right button is not a mode — it steers, exactly as
     * the mouse always did, and the camera stays in the walker's head.
     *
     * <p>There is nothing to orbit: the camera <em>is</em> the walker, and
     * swinging it round them would be swinging it round itself. So the orbit is
     * third person's alone and holding the button changes nothing anywhere
     * else, which is what stops it becoming a control somebody has to know
     * about to play normally.
     */
    @Test
    void thereIsNothingToOrbitInFirstPerson(@TempDir Path dir) {
        try (Walk walk = new Walk(dir)) {
            EyeCamera eye = walk.walk.camera();
            walk.step();
            double yaw = eye.yaw();
            walk.orbit(90);
            assertEquals(walk.px(), eye.x(), 0.02, "the first-person camera left the head");
            assertEquals(walk.py(), eye.y(), 0.02, "the first-person camera left the head");
            assertNotEquals(yaw, eye.yaw(),
                    "the right button swallowed the mouse instead of steering with it");
        }
    }

    /** How far off the camera is from pointing at the walker, in radians. */
    private static double pointingAtWalker(Walk walk, EyeCamera eye) {
        double toward = Math.atan2(walk.px() - eye.x(), -(walk.py() - eye.y()));
        double off = Math.abs(toward - eye.yaw()) % (Math.PI * 2);
        return Math.min(off, Math.PI * 2 - off);
    }

    // --- the two screens that offer it ----------------------------------------------------

    /**
     * The pause screen changes who you are, and does it through the host.
     *
     * <p>Left or right, because the pause screen is a panel and panels in this
     * scene read left and right — see {@code WatchScene.updatePaused} for why
     * that is better than a binding nineteen screens out of twenty would
     * ignore.
     */
    @Test
    void thePauseScreenChangesWhoYouAre(@TempDir Path dir) {
        try (Walk walk = new Walk(dir)) {
            assertEquals(Figure.DEFAULT, walk.game.player(1).figure());
            walk.press(KeyEvent.VK_ESCAPE);
            assertEquals("paused", walk.walk.panelName(), "Esc did not pause the walk");

            walk.press(KeyEvent.VK_RIGHT);
            assertEquals(Figure.DEFAULT.next(), walk.game.player(1).figure(),
                    "the pause screen did not change the figure");
            walk.press(KeyEvent.VK_LEFT);
            assertEquals(Figure.DEFAULT, walk.game.player(1).figure(),
                    "it would not go back round");
            // Still paused: this is a setting on the screen, not a way off it.
            assertEquals("paused", walk.walk.panelName());
        }
    }

    /**
     * The lobby offers the choice before the walk starts, on every form that
     * starts one.
     *
     * <p>Deciding who you are after setting off is the wrong order, and a game
     * that only let you change it from a pause menu would be telling you the
     * question was an afterthought.
     */
    @Test
    void theLobbyOffersTheChoiceBeforeYouSetOff(@TempDir Path dir) {
        GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
        WatchStore store = new WatchStore(dir.resolve("walks").toString());
        SceneManager scenes = new SceneManager();
        scenes.setViewport(800, 480);
        WatchLobbyScene lobby = new WatchLobbyScene(ctx, store);
        scenes.register(WatchLobbyScene.NAME, lobby);
        scenes.setScene(WatchLobbyScene.NAME);

        for (String entry : List.of("New Walk", "Host a Walk", "Join a Walk")) {
            lobby.onEnter();
            assertTrue(open(lobby, entry), "the lobby has no \"" + entry + "\"");
            ConfigForm form = lobby.form();
            assertNotNull(form, entry + " opened no form");
            assertTrue(labels(form).contains("Walk as"),
                    entry + " does not ask which figure to walk as — it offers "
                            + labels(form));
        }
    }

    // --- the drift the art cannot notice --------------------------------------------------

    /**
     * The wardrobe is painted out of the catalogue.
     *
     * <p><b>A test on two files that have no other reason to agree.</b>
     * {@code Cosmetics.java} says a wool scarf is {@code A8482F} with a
     * {@code D9C68A} band and the shop draws its row from that; the Blender
     * script that cuts the geometry has its own copy, because Blender cannot
     * read Java. A number that moves in one is a coat whose picture in the shop
     * is a different colour from the coat on the buyer, and nothing else in
     * this repository would ever say so.
     */
    @Test
    void theWardrobeIsPaintedOutOfTheCatalogue() throws IOException {
        String script = Files.readString(Path.of("tools/blender/cosmetics.py"));
        Map<String, int[]> paint = new HashMap<>();
        Matcher m = Pattern.compile(
                "\"([a-z_]+)\":\\s*\\((0x[0-9A-Fa-f]{6}|OILSKIN),\\s*0x([0-9A-Fa-f]{6})\\)")
                .matcher(script);
        while (m.find()) {
            paint.put(m.group(1), new int[]{
                    m.group(2).equals("OILSKIN") ? 0 : Integer.parseInt(m.group(2).substring(2), 16),
                    Integer.parseInt(m.group(3), 16)});
        }
        assertEquals(Cosmetics.all().size(), paint.size(),
                "the Blender script paints " + paint.size() + " pieces and the catalogue "
                        + "has " + Cosmetics.all().size());
        for (Cosmetics.Piece piece : Cosmetics.all()) {
            int[] said = paint.get(piece.key());
            assertNotNull(said, "the Blender script has no colours for " + piece.key());
            assertEquals(piece.trim(), said[1], piece.key() + "'s trim");
            if (piece.tinted()) {
                // The sentinel: the boxes draw these two in the wearer's own
                // coat and a modelled piece is never tinted, so the script
                // gives them a colour of their own and says so by name.
                assertEquals(0, said[0], piece.key() + " is tinted and the script "
                        + "should paint it OILSKIN rather than a literal");
            } else {
                assertEquals(piece.rgb(), said[0], piece.key() + "'s main colour");
            }
        }
    }

    /**
     * The walker's row in the measurement table is the script that built him.
     *
     * <p>{@code figures.py} carries a copy of the walker's landmarks so that
     * {@code cosmetics.py} can cut clothes to them, and {@code ranger.py} — the
     * script that actually built {@code characters/walker.glb} — deliberately
     * stays self-contained, because it is the figure this game has always drawn
     * and a refactor that changed one rounding in it would change what every
     * player looks like. A copy needs a test or it is a lie waiting to happen.
     */
    @Test
    void theWalkerTableAgreesWithTheScriptThatBuiltIt() throws IOException {
        Map<String, Double> said = pythonConstants(
                Files.readString(Path.of("tools/blender/ranger.py")));
        Map<String, Double> table = pythonTable(
                Files.readString(Path.of("tools/blender/figures.py")), "WALKER");
        assertFalse(table.isEmpty(), "figures.py has no WALKER table in it");

        for (Map.Entry<String, String> row : Map.of(
                "sole", "SOLE", "ankle_z", "ANKLE_Z", "knee_z", "KNEE_Z",
                "hip_z", "HIP_Z", "waist_z", "WAIST_Z", "shoulder_z", "SHOULDER_Z",
                "neck_z", "NECK_Z", "head_z", "HEAD_Z", "crown_z", "CROWN_Z",
                "hip_x", "HIP_X").entrySet()) {
            assertEquals(said.get(row.getValue()), table.get(row.getKey()), 1e-9,
                    "figures.py's WALKER." + row.getKey() + " is not ranger.py's "
                            + row.getValue());
        }
        assertEquals(said.get("SHOULDER_X"), table.get("shoulder_x"), 1e-9, "shoulder_x");
        assertEquals(said.get("ELBOW_Z"), table.get("elbow_z"), 1e-9, "elbow_z");
        // The hat is deliberately not in this list any more. `ranger.py`'s
        // BRIM_Z is the hat modelled into the ranger who stands outside the
        // trading post; the player's went into the wardrobe as `walking_hat`
        // and is cut to sit on a head rather than to be part of one, so the two
        // numbers are now about two different hats and holding them together
        // would be holding a garment to an NPC's face.
        // …and the height both figures have to be, or a wardrobe authored in
        // metres arrives at some other number of them. See figures.py.
        assertEquals(HEIGHT, table.get("crown_z"), 1e-9,
                "the walker is not WalkerModel.HEIGHT to the crown");
        Map<String, Double> other = pythonTable(
                Files.readString(Path.of("tools/blender/figures.py")), "WAYFARER");
        assertEquals(HEIGHT, other.get("crown_z"), 1e-9,
                "the wayfarer is not WalkerModel.HEIGHT to the crown");
    }

    // --- the plumbing -------------------------------------------------------------------

    /** One figure, standing still, wearing nothing. */
    private static Mesh standing(Figure figure) {
        return dressed(figure, WalkerModel.WEARING_NOTHING);
    }

    private static Mesh dressed(Figure figure, List<String> worn) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        WalkerModel.walker(mesh, figure, 0, 0, 0, 0, false, 0, 0,
                WalkerModel.Leap.GROUNDED, 0x4A6B33, worn, 0);
        return mesh.build();
    }

    /** …part way through a stride, which is where the clothes have to keep up. */
    private static Mesh walking(Figure figure, List<String> worn, double phase) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        WalkerModel.walker(mesh, figure, 0, 0, 0, 0, false, phase, 4.4,
                WalkerModel.Leap.GROUNDED, 0x4A6B33, worn, 0);
        return mesh.build();
    }

    /**
     * One garment alone, drawn through the motion of a figure part way through
     * a stride — exactly as {@code WalkerModel.walker} draws it.
     *
     * <p>Alone rather than on the body, so what is measured is the garment and
     * not whichever of the two happens to reach furthest.
     */
    private static Mesh walkingWorn(Figure figure, String key, double phase) {
        SceneModel body = SceneModels.of(figure.model(), ModelRig.Kind.HUMANOID,
                SceneModel.Size.height(1));
        assertNotNull(body, figure.key() + " has no model to be worn over");
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        CosmeticModel.overlay(mesh, figure, List.of(key), 0, 0, 0, 0, HEIGHT,
                AnimState.WALK, phase, new float[]{0, 0, 1, 1},
                body.wornAt(AnimState.WALK, phase, HEIGHT, SceneModel.Lean.UPRIGHT));
        return mesh.build();
    }

    /** Which vertices of a mesh lie between two heights. */
    private static int[] between(Mesh mesh, double from, double to) {
        float[] v = mesh.vertices();
        int[] out = new int[mesh.vertexCount()];
        int n = 0;
        for (int i = 0; i < mesh.vertexCount(); i++) {
            double z = v[i * Mesh.FLOATS_PER_VERTEX + 2];
            if (z >= from && z <= to) out[n++] = i;
        }
        assertTrue(n > 0, "nothing at all between " + from + " and " + to + " m");
        return java.util.Arrays.copyOf(out, n);
    }

    /** The mean height of some vertices, or of all of them for {@code null}. */
    private static double centroidZ(Mesh mesh, int[] which) {
        float[] v = mesh.vertices();
        double sum = 0;
        int n = which == null ? mesh.vertexCount() : which.length;
        for (int i = 0; i < n; i++) {
            int at = which == null ? i : which[i];
            sum += v[at * Mesh.FLOATS_PER_VERTEX + 2];
        }
        assertTrue(n > 0, "no vertices to take a centroid of");
        return sum / n;
    }

    /** One garment, on its own, at the feet of a standing figure. */
    private static Mesh worn(Figure figure, String key) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        CosmeticModel.overlay(mesh, figure, List.of(key), 0, 0, 0, 0, HEIGHT, 0, 0,
                new float[]{0, 0, 1, 1});
        return mesh.build();
    }

    /** …dyed, or drawn in the colours its artist gave it for {@code 0}. */
    private static Mesh worn(Figure figure, String key, int dye) {
        Mesh.Builder mesh = Mesh.builder(0, 0, 0, false, 1);
        CosmeticModel.overlay(mesh, figure, List.of(key), 0, 0, 0, 0, HEIGHT,
                AnimState.IDLE, 0, new float[]{0, 0, 1, 1}, null, k -> dye);
        return mesh.build();
    }

    /** Every colour in a mesh. */
    private static Set<Integer> coloursOf(Mesh mesh) {
        Set<Integer> out = new HashSet<>();
        for (int i = 0; i < mesh.vertexCount(); i++) {
            out.add(mesh.colours()[i] & 0xFFFFFF);
        }
        return out;
    }

    /** The mean of one channel over a mesh — {@code 16} red, {@code 0} blue. */
    private static double meanChannel(Mesh mesh, int shift) {
        double sum = 0;
        for (int i = 0; i < mesh.vertexCount(); i++) {
            sum += (mesh.colours()[i] >> shift) & 0xFF;
        }
        return sum / Math.max(1, mesh.vertexCount());
    }

    /** …between two heights, for asking what somebody has on there. */
    private static Set<Integer> coloursBetween(Mesh mesh, double from, double to) {
        Set<Integer> out = new HashSet<>();
        float[] v = mesh.vertices();
        for (int i = 0; i < mesh.vertexCount(); i++) {
            double z = v[i * Mesh.FLOATS_PER_VERTEX + 2];
            if (z >= from && z <= to) out.add(mesh.colours()[i] & 0xFFFFFF);
        }
        return out;
    }

    /** One of everything, which is at most one piece a slot. */
    private static List<String> everything() {
        List<String> out = new ArrayList<>();
        for (Cosmetics.Slot slot : Cosmetics.Slot.values()) {
            out.add(Cosmetics.inSlot(slot).get(0).key());
        }
        return out;
    }

    /** How wide a figure is between two heights, in metres. */
    private static double widthAt(Mesh mesh, double low, double high) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        float[] v = mesh.vertices();
        for (int i = 0; i < mesh.vertexCount(); i++) {
            double x = v[i * Mesh.FLOATS_PER_VERTEX];
            double z = v[i * Mesh.FLOATS_PER_VERTEX + 2];
            if (z < low || z > high) continue;
            min = Math.min(min, x);
            max = Math.max(max, x);
        }
        assertTrue(max > min, "nothing at all between " + low + " and " + high);
        return max - min;
    }

    private static String bounds(Mesh mesh) {
        return round(mesh.minX()) + "," + round(mesh.minY()) + "," + round(mesh.minZ())
                + ";" + round(mesh.maxX()) + "," + round(mesh.maxY()) + ","
                + round(mesh.maxZ()) + ";" + mesh.triangleCount();
    }

    private static double round(double v) { return Math.round(v * 1000) / 1000.0; }

    private static String only(int n) { return n == 0 ? "none" : Integer.toString(n); }

    /** What one client thinks another player's body is. */
    private static Figure figureOf(WatchClient client, int id) {
        for (WatchView.Walker walker : client.view().walkers()) {
            if (walker.id() == id) return walker.figure();
        }
        return null;
    }

    /** Every {@code NAME = number} at the top level of a Python file. */
    private static Map<String, Double> pythonConstants(String text) {
        Map<String, Double> out = new HashMap<>();
        Matcher m = Pattern.compile(
                "(?m)^([A-Z][A-Z0-9_]*) = (-?\\d+(?:\\.\\d+)?)\\s*(?:#.*)?$").matcher(text);
        while (m.find()) out.put(m.group(1), Double.parseDouble(m.group(2)));
        return out;
    }

    /** Every {@code "key": number} inside one top-level {@code NAME = { … }}. */
    private static Map<String, Double> pythonTable(String text, String name) {
        Matcher block = Pattern.compile("(?ms)^" + name + " = \\{(.*?)^\\}").matcher(text);
        Map<String, Double> out = new HashMap<>();
        if (!block.find()) return out;
        Matcher row = Pattern.compile("\"([a-z_]+)\":\\s*(-?\\d+(?:\\.\\d+)?)")
                .matcher(block.group(1));
        while (row.find()) out.put(row.group(1), Double.parseDouble(row.group(2)));
        return out;
    }

    /** Click a menu entry whose label starts with this, and say whether it was there. */
    private static boolean open(WatchLobbyScene lobby, String entry) {
        for (var item : lobby.menu().items()) {
            if (item.text().startsWith(entry)) {
                item.activate();
                return true;
            }
        }
        return false;
    }

    private static Set<String> labels(ConfigForm form) {
        Set<String> out = new HashSet<>();
        for (ConfigForm.Option option : form.options()) out.add(option.label());
        return out;
    }

    private WatchClient connect(String name) throws IOException {
        WatchClient client = WatchClient.connect("127.0.0.1", server.port(), name);
        clients.add(client);
        return client;
    }

    private void until(String what, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            for (WatchClient client : clients) client.pump();
            if (condition.getAsBoolean()) return;
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (WatchClient client : clients) client.pump();
        assertTrue(condition.getAsBoolean(), "timed out waiting for: " + what);
    }

    /** A running solo walk, so the pause screen can be pressed for real. */
    private static final class Walk implements AutoCloseable {

        private final SceneManager scenes = new SceneManager();
        private final InputManager input = new InputManager();
        private final WatchSession session;
        private final WatchGame game;
        private final WatchScene walk;

        Walk(Path dir) {
            GameContext ctx = new GameContext(null, new GameTypeStore(dir.toString()));
            WatchStore store = new WatchStore(dir.resolve("walks").toString());
            walk = new WatchScene(ctx);
            scenes.setViewport(800, 480);
            scenes.register(WatchScene.NAME, walk);
            game = new WatchGame(new WatchGame.Config(31L, "Figures", 1));
            game.join(1, "Kara");
            session = WatchSession.solo(game);
            session.setSelfId(1);
            walk.adopt(session, store);
            scenes.setScene(WatchScene.NAME);
            for (int i = 0; i < 6; i++) step();
        }

        void step() {
            input.newFrame();
            scenes.update(1 / 120.0, input);
            // **And draw.** The camera is placed while rendering rather than
            // while updating — it is a thing the screen does, not a thing the
            // world does — so a harness that only ever updated would be asking
            // an EyeCamera that had never been told where anybody is.
            scenes.render(frame, 0f);
        }

        private final RecordingTarget frame = new RecordingTarget(800, 480);

        void press(int key) {
            hold(key);
            step();
            release(key);
            step();
        }

        void hold(int key) {
            input.keyPressed(new KeyEvent(new JPanel(), KeyEvent.KEY_PRESSED, 0, 0, key,
                    KeyEvent.CHAR_UNDEFINED));
        }

        void release(int key) {
            input.keyReleased(new KeyEvent(new JPanel(), KeyEvent.KEY_RELEASED, 0, 0, key,
                    KeyEvent.CHAR_UNDEFINED));
        }

        double px() { return game.player(1).x(); }

        double py() { return game.player(1).y(); }

        /**
         * Swing the third-person camera round by so many degrees, the way a
         * player does: right button held, mouse dragged sideways.
         *
         * <p>In small steps, because the scene reads the pointer's <em>travel</em>
         * each frame and puts it back to the middle — one enormous jump would
         * be one enormous frame rather than a turn.
         */
        void orbit(double degrees) {
            int fromX = 200, atY = 240;
            // The scene reads how far the hand *travelled*, so each event has to
            // land somewhere new: sending the same coordinate twice is a hand
            // that moved once and then stopped.
            int by = (int) Math.round(Math.toRadians(degrees) / 0.0032 / 24);
            input.mousePressed(mouse(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON3,
                    fromX, atY));
            int x = fromX;
            for (int i = 0; i < 24; i++) {
                x += by;
                input.mouseDragged(mouse(MouseEvent.MOUSE_DRAGGED, MouseEvent.BUTTON3,
                        x, atY));
                step();
            }
            // Released where it ended, so letting go banks no travel of its own
            // and does not turn the walker on the way out.
            input.mouseReleased(mouse(MouseEvent.MOUSE_RELEASED, MouseEvent.BUTTON3,
                    x, atY));
            step();
        }

        int[] slotRow(int row) { return walk.wardrobeSlotRow(row); }

        int[] pieceRow(int row) { return walk.wardrobePieceRow(row); }

        int[] dyeBar(int channel) { return walk.wardrobeDyeBar(channel); }

        int[] wardrobeClose() { return walk.wardrobeCloseButton(); }

        /**
         * Move the pointer there and click, the way a hand does: the panel
         * hovers on the frame the mouse arrives and acts on the frame the
         * button goes down, so those are two frames and not one.
         */
        void click(int[] at) { click(at[0], at[1]); }

        void click(int x, int y) {
            input.mouseMoved(mouse(MouseEvent.MOUSE_MOVED, MouseEvent.NOBUTTON, x, y));
            step();
            input.mousePressed(mouse(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1, x, y));
            step();
            input.mouseReleased(mouse(MouseEvent.MOUSE_RELEASED, MouseEvent.BUTTON1,
                    x, y));
            step();
        }

        /** Press at one point, drag to another, and let go. */
        void drag(int fromX, int fromY, int toX, int toY) {
            input.mouseMoved(mouse(MouseEvent.MOUSE_MOVED, MouseEvent.NOBUTTON,
                    fromX, fromY));
            step();
            input.mousePressed(mouse(MouseEvent.MOUSE_PRESSED, MouseEvent.BUTTON1,
                    fromX, fromY));
            step();
            for (int i = 1; i <= 6; i++) {
                input.mouseDragged(mouse(MouseEvent.MOUSE_DRAGGED, MouseEvent.BUTTON1,
                        fromX + (toX - fromX) * i / 6, fromY + (toY - fromY) * i / 6));
                step();
            }
            input.mouseReleased(mouse(MouseEvent.MOUSE_RELEASED, MouseEvent.BUTTON1,
                    toX, toY));
            step();
        }

        private MouseEvent mouse(int id, int button, int x, int y) {
            int mask = switch (button) {
                case MouseEvent.BUTTON1 -> MouseEvent.BUTTON1_DOWN_MASK;
                case MouseEvent.BUTTON3 -> MouseEvent.BUTTON3_DOWN_MASK;
                default -> 0;
            };
            return new MouseEvent(new JPanel(), id, 0,
                    id == MouseEvent.MOUSE_RELEASED ? 0 : mask, x, y, 1, false, button);
        }

        @Override public void close() {
            walk.adopt(null, null);
            session.close();
        }
    }
}
