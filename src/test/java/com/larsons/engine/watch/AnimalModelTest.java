package com.larsons.engine.watch;

import com.larsons.engine.watch.life.AnimState;
import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.AnimalFamily;
import com.larsons.engine.watch.life.AnimalModel;
import com.larsons.engine.watch.life.AnimalModels;
import com.larsons.engine.watch.life.AnimalRegistry;
import com.larsons.engine.watch.life.AnimalSkins;
import com.larsons.engine.watch.render.Mesh;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.awt.image.BufferedImage;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The placeholders — <b>"full 3D models, Minecraft style, complete with
 * textures"</b>, which is the standard the brief set and the standard this
 * checks against.
 *
 * <p>A boxy model painted one flat colour would satisfy every type in the
 * signature and none of that sentence. So: every species builds boxes, every
 * species has a skin with more than one colour on it, every animation state
 * moves something, and a wing folds when it is not flying.
 */
@Timeout(180)
class AnimalModelTest {

    /** A spread across the registry rather than the first N, which are one family. */
    private static AnimalDef sample(int i) {
        var all = AnimalRegistry.all();
        return all.get((int) ((long) i * 137 % all.size()));
    }

    @Test
    void everySpeciesHasAModelMadeOfBoxes() {
        for (AnimalDef def : AnimalRegistry.all()) {
            AnimalModel model = AnimalModel.of(def);
            assertNotNull(model, def.key());
            assertTrue(model.boxCount() >= 3,
                    def.key() + " is " + model.boxCount() + " boxes — that is a prop");
            // Stand height is how far the back sits above the point the animal
            // is placed at, and a fish is placed at its own centre because it
            // has no feet and nothing to stand on. A duck also swims and is
            // still a bird with legs, so the question is the build, not the
            // motion. Everything that touches the ground has to be lifted off
            // it, or it is buried to the spine.
            if (def.family().build() == AnimalFamily.Build.FISH_LIKE) {
                assertEquals(0.0, model.standHeight(), 0.0,
                        def.key() + " is built as a fish and yet is offset from its "
                                + "own position");
            } else {
                assertTrue(model.standHeight() > 0, def.key() + " stands at zero height");
            }
        }
    }

    @Test
    void everyBuildHasItsOwnShape() {
        Set<Integer> shapes = new HashSet<>();
        for (AnimalFamily.Build build : AnimalFamily.Build.values()) {
            AnimalModel model = AnimalModel.of(build);
            assertNotNull(model, build.toString());
            assertTrue(model.boxCount() >= 3, build + " has only " + model.boxCount() + " boxes");
            shapes.add(model.boxCount());
        }
        assertTrue(shapes.size() > 3,
                "every build is the same number of boxes — the plans are not distinct");
    }

    /**
     * Every state has to actually pose something, or a "sleeping" animal is a
     * standing one and the state machine is decoration.
     */
    @Test
    void everyAnimationStateMovesTheAnimal() {
        AnimalModel.PoseSource poses = AnimalModel.procedural();
        for (AnimState state : AnimState.values()) {
            boolean moved = false;
            for (AnimalModel.Joint joint : AnimalModel.Joint.values()) {
                for (double phase = 0; phase < 1 && !moved; phase += 0.1) {
                    AnimalModel.Pose pose = poses.poseOf(state, joint, phase);
                    assertNotNull(pose, state + "/" + joint);
                    if (Math.abs(pose.pitch()) > 1e-6 || Math.abs(pose.turn()) > 1e-6
                            || Math.abs(pose.roll()) > 1e-6 || Math.abs(pose.dx()) > 1e-6
                            || Math.abs(pose.dy()) > 1e-6 || Math.abs(pose.dz()) > 1e-6) {
                        moved = true;
                    }
                }
            }
            assertTrue(moved, state + " poses every joint at rest — it is not an animation");
        }
    }

    /**
     * A pose is a function of state, joint and phase, and of nothing else — the
     * same three inputs on two clients have to make the same picture.
     */
    @Test
    void posesAreAFunctionOfTheirInputs() {
        AnimalModel.PoseSource poses = AnimalModel.procedural();
        for (AnimState state : AnimState.values()) {
            for (AnimalModel.Joint joint : AnimalModel.Joint.values()) {
                for (double phase = 0; phase < 1; phase += 0.17) {
                    assertEquals(poses.poseOf(state, joint, phase),
                            poses.poseOf(state, joint, phase), state + "/" + joint);
                }
            }
        }
    }

    /**
     * The wings.
     *
     * <p>A plate has no thickness to rotate about, so folding a wing by turning
     * it does nothing you can see — the first version left every bird on the
     * ground with its wings out, permanently, like a cormorant drying. The fold
     * is a reach: a folded wing reaches a fraction as far from the body as an
     * open one.
     */
    @Test
    void aWingIsFoldedWhenTheAnimalIsNotFlying() {
        AnimalModel.PoseSource poses = AnimalModel.procedural();
        double flying = 0, settled = 0;
        for (double phase = 0; phase < 1; phase += 0.05) {
            flying = Math.max(flying,
                    poses.poseOf(AnimState.FLY, AnimalModel.Joint.WING_L, phase).spread());
            settled = Math.max(settled,
                    poses.poseOf(AnimState.IDLE, AnimalModel.Joint.WING_L, phase).spread());
        }
        assertTrue(settled < flying * 0.6, "an idle bird's wings reach " + settled
                + " against " + flying + " in flight — they are never folded");
    }

    // --- skins ------------------------------------------------------------------------

    @Test
    void everySpeciesHasASkinSheetWithAPatternOnIt() {
        for (int i = 0; i < 120; i++) {
            AnimalDef def = sample(i);
            BufferedImage skin = AnimalSkins.skin(def);
            assertNotNull(skin, def.key());
            assertEquals(AnimalSkins.SIZE, skin.getWidth(), def.key());
            assertEquals(AnimalSkins.SIZE, skin.getHeight(), def.key());

            Set<Integer> colours = new HashSet<>();
            for (int y = 0; y < skin.getHeight(); y += 2) {
                for (int x = 0; x < skin.getWidth(); x += 2) colours.add(skin.getRGB(x, y));
            }
            assertTrue(colours.size() >= 4, def.key() + " is painted in only "
                    + colours.size() + " colours — that is not a texture, it is a fill");
        }
    }

    @Test
    void aBoxTakesItsColourFromItsOwnRegionOfTheSkin() {
        for (int i = 0; i < 40; i++) {
            AnimalDef def = sample(i * 3);
            Set<Integer> regionColours = new HashSet<>();
            for (AnimalSkins.Region region : AnimalSkins.Region.values()) {
                int rgb = AnimalSkins.regionColour(def, region);
                assertTrue(rgb >= 0 && rgb <= 0xFFFFFF, def.key() + " " + region);
                regionColours.add(rgb);
            }
            assertTrue(regionColours.size() >= 3, def.key()
                    + " paints every region of itself the same colour");
        }
    }

    // --- meshing ----------------------------------------------------------------------

    @Test
    void everySpeciesMeshesToTrianglesInEveryState() {
        for (int i = 0; i < 60; i++) {
            AnimalDef def = sample(i * 7);
            AnimalModels.Loaded model = AnimalModels.of(def);
            for (AnimState state : AnimState.values()) {
                Mesh.Builder builder = Mesh.builder(0, 0, 0, false, 1);
                model.geometry().mesh(builder, def, 0, 0, 0, 0.4, state, 0.3, 1,
                        model.poses());
                Mesh mesh = builder.build();
                assertFalse(mesh.isEmpty(), def.key() + " meshes to nothing in " + state);
                assertTrue(mesh.triangleCount() >= 18,
                        def.key() + " is " + mesh.triangleCount() + " triangles in " + state);
                assertTrue(Float.isFinite(mesh.minX()) && Float.isFinite(mesh.maxZ()),
                        def.key() + " meshed to a NaN in " + state);
            }
        }
    }

    /** A mesh has to sit where it was asked to sit, and be the size it says. */
    @Test
    void aMeshIsBuiltAroundTheSpotItWasGiven() {
        AnimalDef def = sample(11);
        Mesh.Builder builder = Mesh.builder(0, 0, 0, false, 1);
        AnimalModels.of(def).geometry().mesh(builder, def, 30, -12, 4, 0, AnimState.IDLE,
                0, 1, AnimalModel.procedural());
        Mesh mesh = builder.build();

        assertTrue(mesh.minX() > 30 - 4 && mesh.maxX() < 30 + 4,
                "x spans " + mesh.minX() + ".." + mesh.maxX() + " around 30");
        assertTrue(mesh.minY() > -12 - 4 && mesh.maxY() < -12 + 4,
                "y spans " + mesh.minY() + ".." + mesh.maxY() + " around -12");
        assertTrue(mesh.minZ() >= 4 - 0.1, "the animal is standing below its ground");
    }

    /** Scaling scales, which is what one Blockbench file serving many sizes rests on. */
    @Test
    void scalingAModelScalesIt() {
        AnimalDef def = sample(23);
        AnimalModels.Loaded model = AnimalModels.of(def);
        double[] extent = new double[2];
        for (int i = 0; i < 2; i++) {
            Mesh.Builder builder = Mesh.builder(0, 0, 0, false, 1);
            model.geometry().mesh(builder, def, 0, 0, 0, 0, AnimState.IDLE, 0,
                    i == 0 ? 1 : 3, model.poses());
            Mesh mesh = builder.build();
            extent[i] = mesh.maxX() - mesh.minX();
        }
        assertEquals(3.0, extent[1] / extent[0], 0.01,
                "tripling the scale did not triple the animal");
    }

    // --- the drop-in point -------------------------------------------------------------

    @Test
    void withNoFilesInTheFolderEverySpeciesUsesItsPlaceholder() {
        AnimalModels.setDirectory(java.nio.file.Path.of("build", "no-such-models"));
        try {
            for (int i = 0; i < 20; i++) {
                AnimalDef def = sample(i * 61);
                AnimalModels.Loaded loaded = AnimalModels.of(def);
                assertFalse(loaded.imported(), def.key() + " claims an imported model");
                assertEquals("placeholder", loaded.source());
                assertNotNull(loaded.geometry());
                assertNotNull(loaded.poses());
            }
        } finally {
            AnimalModels.setDirectory(java.nio.file.Path.of(AnimalModels.DIRECTORY));
        }
    }
}
