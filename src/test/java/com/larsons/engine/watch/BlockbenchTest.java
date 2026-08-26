package com.larsons.engine.watch;

import com.larsons.engine.watch.life.AnimState;
import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.AnimalModel;
import com.larsons.engine.watch.life.AnimalModels;
import com.larsons.engine.watch.life.AnimalRegistry;
import com.larsons.engine.watch.life.Blockbench;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The import path — <b>the one part of this game an artist has to be able to
 * rely on without reading any of it.</b>
 *
 * <p>{@code resources/watch/models/README.md} makes promises to whoever opens
 * Blockbench: name a bone {@code left_wing} and it will be a wing, name a clip
 * {@code walk} and it will play when the animal walks, model at any size and it
 * will come out the size the species is. Those promises are what is tested
 * here, in the same words the README uses, so that the document and the code
 * cannot drift apart quietly.
 */
class BlockbenchTest {

    /**
     * A minimal but real {@code .bbmodel}: a body, a head, two wings, and a
     * walk cycle that rotates one of them.
     */
    private static String model() {
        return """
        {
          "meta": {"format_version": "4.5", "model_format": "free"},
          "name": "wren",
          "resolution": {"width": 64, "height": 64},
          "elements": [
            {"name": "torso", "uuid": "e-body",
             "from": [-4, 4, -8], "to": [4, 12, 8], "origin": [0, 4, 0]},
            {"name": "skull", "uuid": "e-head",
             "from": [-3, 10, 8], "to": [3, 16, 14], "origin": [0, 12, 8]},
            {"name": "wingL", "uuid": "e-wl",
             "from": [4, 8, -4], "to": [12, 9, 6], "origin": [4, 9, 0]},
            {"name": "wingR", "uuid": "e-wr",
             "from": [-12, 8, -4], "to": [-4, 9, 6], "origin": [-4, 9, 0]}
          ],
          "outliner": [
            {"name": "body", "uuid": "b-body", "children": [
               "e-body",
               {"name": "head", "uuid": "b-head", "children": ["e-head"]},
               {"name": "left_wing", "uuid": "b-wl", "children": ["e-wl"]},
               {"name": "right_wing", "uuid": "b-wr", "children": ["e-wr"]}
            ]}
          ],
          "animations": [
            {"name": "animation.wren.walk", "length": 1.0, "loop": "loop",
             "animators": {
               "b-wl": {"name": "left_wing", "keyframes": [
                  {"channel": "rotation", "time": 0.0, "interpolation": "linear",
                   "data_points": [{"x": 0, "y": 0, "z": 0}]},
                  {"channel": "rotation", "time": 0.5, "interpolation": "linear",
                   "data_points": [{"x": 40, "y": 0, "z": 0}]},
                  {"channel": "rotation", "time": 1.0, "interpolation": "linear",
                   "data_points": [{"x": 0, "y": 0, "z": 0}]}
               ]},
               "b-head": {"name": "head", "keyframes": [
                  {"channel": "position", "time": 0.0, "interpolation": "catmullrom",
                   "data_points": [{"x": 0, "y": 0, "z": 0}]},
                  {"channel": "position", "time": 1.0, "interpolation": "catmullrom",
                   "data_points": [{"x": 0, "y": 0, "z": 8}]}
               ]}
             }}
          ]
        }
        """;
    }

    // --- what it reads -----------------------------------------------------------------

    @Test
    void aModelParsesToItsBoxes() {
        Blockbench.Model parsed = Blockbench.parse(model(), "wren.bbmodel");
        assertNotNull(parsed, "a well-formed model did not parse");
        assertEquals("wren.bbmodel", parsed.name());
        assertEquals(4, parsed.geometry().boxCount(), "one box per element");
    }

    /** The bone-name convention the README calls "the one you have to follow". */
    @Test
    void bonesBindToJointsByName() {
        assertEquals(AnimalModel.Joint.HEAD, Blockbench.jointOf("head"));
        assertEquals(AnimalModel.Joint.HEAD, Blockbench.jointOf("Skull"));
        assertEquals(AnimalModel.Joint.HEAD, Blockbench.jointOf("beak"));
        assertEquals(AnimalModel.Joint.BODY, Blockbench.jointOf("torso"));
        assertEquals(AnimalModel.Joint.TAIL, Blockbench.jointOf("tail_base"));
        assertEquals(AnimalModel.Joint.HORN, Blockbench.jointOf("left antler"));

        // The three spellings of a side that the README promises all work.
        for (String left : List.of("leftWing", "wing_l", "Wing Left", "wing-left")) {
            assertEquals(AnimalModel.Joint.WING_L, Blockbench.jointOf(left), left);
        }
        for (String right : List.of("rightWing", "wing_r", "Wing Right")) {
            assertEquals(AnimalModel.Joint.WING_R, Blockbench.jointOf(right), right);
        }

        assertEquals(AnimalModel.Joint.LEG_FL, Blockbench.jointOf("front_left_leg"));
        assertEquals(AnimalModel.Joint.LEG_BR, Blockbench.jointOf("hind_right_leg"));

        assertNull(Blockbench.jointOf("feathers"),
                "a name that matches nothing must inherit, not guess");
        assertNull(Blockbench.jointOf(null));
    }

    /** A bone whose name matches nothing takes its parent's joint. */
    @Test
    void anUnnamedBoneInheritsFromItsParent() {
        String nested = model().replace("\"name\": \"head\"", "\"name\": \"tuft\"");
        Blockbench.Model parsed = Blockbench.parse(nested, "wren.bbmodel");
        assertNotNull(parsed);
        // 'tuft' matches nothing and sits inside 'body', so its box is body.
        long bodyBoxes = parsed.geometry().parts().stream()
                .filter(p -> p.joint() == AnimalModel.Joint.BODY).count();
        assertEquals(2, bodyBoxes, "the unnamed bone did not inherit the body joint");
    }

    @Test
    void clipNamesMatchTheirStates() {
        assertEquals(AnimState.WALK, AnimState.forClip("walk"));
        assertEquals(AnimState.WALK, AnimState.forClip("Walk"));
        assertEquals(AnimState.WALK, AnimState.forClip("animation.wren.walk"));
        assertEquals(AnimState.WALK, AnimState.forClip("walk_cycle"));
        assertEquals(AnimState.WALK, AnimState.forClip("slow_walk"));
        assertEquals(AnimState.FLY, AnimState.forClip("animation.gull.glide"));
        assertEquals(AnimState.SLEEP, AnimState.forClip("roost"));
        assertEquals(AnimState.TAME, AnimState.forClip("perch"));
        assertNull(AnimState.forClip("wobble"), "an unknown clip is unknown, not a guess");
        assertNull(AnimState.forClip(null));

        // Every state's own advertised names have to resolve back to it, or the
        // README's table is a lie.
        for (AnimState state : AnimState.values()) {
            for (String name : state.clipNames()) {
                assertEquals(state, AnimState.forClip(name), name);
            }
        }
    }

    @Test
    void animationsBecomeClipsOnTheStatesTheyName() {
        Blockbench.Model parsed = Blockbench.parse(model(), "wren.bbmodel");
        assertTrue(parsed.clips().containsKey(AnimState.WALK), "the walk clip was not read");
        Blockbench.Clip walk = parsed.clips().get(AnimState.WALK);
        assertEquals(1.0, walk.length(), 1e-9);
        assertTrue(walk.loop());
        assertEquals(2, walk.channels().size(), "one rotation channel and one position one");
    }

    /**
     * The keyframes are actually sampled, and between them rather than at the
     * nearest one — a clip that snapped would animate as a slide show.
     */
    @Test
    void aRotationChannelIsInterpolatedBetweenItsKeyframes() {
        Blockbench.Model parsed = Blockbench.parse(model(), "wren.bbmodel");

        AnimalModel.Pose start = parsed.poseOf(AnimState.WALK, AnimalModel.Joint.WING_L, 0.0);
        AnimalModel.Pose middle = parsed.poseOf(AnimState.WALK, AnimalModel.Joint.WING_L, 0.5);
        AnimalModel.Pose quarter = parsed.poseOf(AnimState.WALK, AnimalModel.Joint.WING_L, 0.25);

        assertEquals(0.0, start.pitch(), 1e-9, "the clip does not start where it says");
        assertEquals(Math.toRadians(40), middle.pitch(), 1e-9,
                "Blockbench writes degrees and this is not reading them as degrees");
        assertEquals(Math.toRadians(20), quarter.pitch(), 1e-6,
                "linear keyframes are not being interpolated");
    }

    /**
     * Blockbench's position channel is in Blockbench's axes — x right, y up, z
     * front — and this game's are x forward, y right, z up. A limb that slid
     * sideways when the animation meant forward would ship, so it is pinned.
     */
    @Test
    void aPositionChannelIsRotatedIntoTheGamesAxes() {
        Blockbench.Model parsed = Blockbench.parse(model(), "wren.bbmodel");
        AnimalModel.Pose end = parsed.poseOf(AnimState.WALK, AnimalModel.Joint.HEAD, 1.0);

        // 8 pixels of Blockbench +z (its front) is half a block forward.
        assertEquals(8.0 / Blockbench.PIXELS_PER_BLOCK, end.dx(), 1e-9,
                "Blockbench's front did not become this game's forward");
        assertEquals(0.0, end.dy(), 1e-9);
        assertEquals(0.0, end.dz(), 1e-9);
    }

    /** A state the file does not animate falls back rather than freezing. */
    @Test
    void aStateWithNoClipFallsBackToTheProceduralPose() {
        Blockbench.Model parsed = Blockbench.parse(model(), "wren.bbmodel");
        assertFalse(parsed.clips().containsKey(AnimState.FLY), "the fixture has no fly clip");

        AnimalModel.Pose imported =
                parsed.poseOf(AnimState.FLY, AnimalModel.Joint.WING_L, 0.3);
        AnimalModel.Pose procedural =
                AnimalModel.pose(AnimState.FLY, AnimalModel.Joint.WING_L, 0.3);
        assertEquals(procedural, imported,
                "a model with two clips has to be a working animal, not a statue");
    }

    /** Whatever size it was modelled at, it comes out one body length long. */
    @Test
    void aModelIsNormalisedToOneBodyLength() {
        Blockbench.Model small = Blockbench.parse(model(), "small");
        // The same model at four times the scale: every coordinate quadrupled.
        String big = model()
                .replace("[-4, 4, -8]", "[-16, 16, -32]").replace("[4, 12, 8]", "[16, 48, 32]")
                .replace("[-3, 10, 8]", "[-12, 40, 32]").replace("[3, 16, 14]", "[12, 64, 56]")
                .replace("[4, 8, -4]", "[16, 32, -16]").replace("[12, 9, 6]", "[48, 36, 24]")
                .replace("[-12, 8, -4]", "[-48, 32, -16]").replace("[-4, 9, 6]", "[-16, 36, 24]");
        Blockbench.Model large = Blockbench.parse(big, "large");

        assertNotNull(large);
        assertEquals(small.geometry().standHeight(), large.geometry().standHeight(), 1e-9,
                "a model drawn four times larger came out a different animal");
        for (int i = 0; i < small.geometry().parts().size(); i++) {
            assertEquals(small.geometry().parts().get(i).hx(),
                    large.geometry().parts().get(i).hx(), 1e-9, "box " + i);
        }
    }

    // --- refusing to break the game ----------------------------------------------------

    @Test
    void nothingUnreadableIsFatal() {
        assertNull(Blockbench.parse("", "empty"));
        assertNull(Blockbench.parse("not json at all", "junk"));
        assertNull(Blockbench.parse("[1, 2, 3]", "an array"));
        assertNull(Blockbench.parse("{\"elements\": []}", "no boxes"));
        // A mesh-only model: Blockbench's free-form polygon mode, which this
        // renderer cannot draw and must not crash on.
        assertNull(Blockbench.parse("{\"meshes\": [{\"vertices\": {}}]}", "a mesh model"));
    }

    /** Blockbench writes a coordinate as a string after some hand edits. */
    @Test
    void numbersWrittenAsStringsAreStillNumbers() {
        String quoted = model().replace("\"from\": [-4, 4, -8]", "\"from\": [\"-4\", \"4\", \"-8\"]");
        Blockbench.Model parsed = Blockbench.parse(quoted, "quoted");
        assertNotNull(parsed, "a hand-edited file with quoted coordinates did not load");
        assertEquals(4, parsed.geometry().boxCount());
    }

    // --- the folder ---------------------------------------------------------------------

    @Test
    void aFileInTheModelsFolderReplacesASpeciesPlaceholder(@TempDir Path dir)
            throws IOException {
        AnimalDef def = AnimalRegistry.all().get(3);
        Files.writeString(dir.resolve(def.key() + ".bbmodel"), model(), StandardCharsets.UTF_8);

        AnimalModels.setDirectory(dir);
        try {
            AnimalModels.Loaded loaded = AnimalModels.of(def);
            assertTrue(loaded.imported(), "the file in the folder was not picked up");
            assertTrue(AnimalModels.isImported(def));
            assertEquals(4, loaded.geometry().boxCount());
        } finally {
            AnimalModels.setDirectory(Path.of(AnimalModels.DIRECTORY));
        }
    }

    /** A family file dresses everything in the family, which is the bulk route. */
    @Test
    void aFamilyFileCoversEverySpeciesInThatFamily(@TempDir Path dir) throws IOException {
        AnimalDef def = AnimalRegistry.all().get(0);
        Files.writeString(dir.resolve(def.family().key() + ".bbmodel"), model(),
                StandardCharsets.UTF_8);

        AnimalModels.setDirectory(dir);
        try {
            for (AnimalDef member : AnimalRegistry.inFamily(def.family())) {
                assertTrue(AnimalModels.of(member).imported(),
                        member.key() + " did not pick up its family's model");
            }
            // And nothing outside the family did.
            for (AnimalDef other : AnimalRegistry.all()) {
                if (other.family() == def.family()) continue;
                assertFalse(AnimalModels.of(other).imported(),
                        other.key() + " picked up another family's model");
                break;
            }
        } finally {
            AnimalModels.setDirectory(Path.of(AnimalModels.DIRECTORY));
        }
    }

    @Test
    void aBrokenFileLeavesTheSpeciesWithItsPlaceholder(@TempDir Path dir) throws IOException {
        AnimalDef def = AnimalRegistry.all().get(7);
        Files.writeString(dir.resolve(def.key() + ".bbmodel"), "{ this is not a model",
                StandardCharsets.UTF_8);

        AnimalModels.setDirectory(dir);
        try {
            AnimalModels.Loaded loaded = AnimalModels.of(def);
            assertFalse(loaded.imported(), "a broken file was treated as a model");
            assertNotNull(loaded.geometry(), "a broken file left the species with nothing");
            assertTrue(loaded.geometry().boxCount() > 3);
        } finally {
            AnimalModels.setDirectory(Path.of(AnimalModels.DIRECTORY));
        }
    }

    /** The instructions the brief asked to be written to the resources folder. */
    @Test
    void theImportInstructionsAreInTheResourcesFolder() throws IOException {
        Path readme = Path.of("src/main/resources", AnimalModels.DIRECTORY, "README.md");
        assertTrue(Files.exists(readme), "no import instructions at " + readme);
        String text = Files.readString(readme, StandardCharsets.UTF_8);
        for (String promise : List.of("bbmodel", "Blockbench", "left_wing", "pivot")) {
            assertTrue(text.contains(promise),
                    "the instructions never mention '" + promise + "'");
        }
        for (AnimState state : AnimState.values()) {
            assertTrue(text.contains(state.key()),
                    "the instructions do not tell an artist about the '" + state.key()
                            + "' animation");
        }
    }
}
