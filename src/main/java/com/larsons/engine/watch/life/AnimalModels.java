package com.larsons.engine.watch.life;

import com.larsons.engine.watch.model.ModelRig;
import com.larsons.engine.watch.model.SceneModel;
import com.larsons.engine.watch.model.SceneModels;
import com.larsons.engine.watch.render.Mesh;
import com.larsons.engine.watch.world.WatchMaterial;
import com.larsons.engine.watch.world.WatchMaterials;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where a species' model actually comes from — <b>the drop-in point.</b>
 *
 * <p>Asked for a model, this looks for, in order:
 *
 * <ol>
 *   <li>{@code watch/models/<species>.bbmodel} — one species, as boxes;</li>
 *   <li>{@code watch/models/<species>.glb} — one species, as triangles;</li>
 *   <li>the same two for the species' <b>family</b>, dressing all forty-nine;</li>
 *   <li>the procedural placeholder ({@link AnimalModel#of}).</li>
 * </ol>
 *
 * <p>Each is tried <b>next to the jar first and on the classpath second</b>,
 * which is the same rule the engine's texture packs follow: a player can drop a
 * model beside the game without rebuilding it, and a creator can bundle one
 * inside it.
 *
 * <h2>Two kinds of geometry, one caller</h2>
 *
 * <p>A {@code .bbmodel} imports into an {@link AnimalModel} — boxes, painted
 * from the species' own generated skin, so one file can dress forty-nine
 * species in forty-nine colourways. A {@code .glb}, {@code .gltf} or
 * {@code .obj} imports into a
 * {@link com.larsons.engine.watch.model.SceneModel} — free triangles, painted
 * by the <b>file's own materials</b>, because a mesh that is not boxes has no
 * regions for a skin sheet to map onto.
 *
 * <p>That is a real trade and it is worth stating plainly: boxes get the
 * colour system, triangles get the shape. Nothing else differs — both come out
 * the size of the placeholder they replace, both are posed by the same ten
 * {@link AnimState}s, both fall back to the same procedural animation for a
 * clip that is not there (including {@link MutantGait} for the three that need
 * it), and {@link Loaded#draw} is what every caller uses so that none of them
 * has to know which arrived.
 *
 * <p><b>Nothing here can stop the game starting.</b> A file that is missing,
 * malformed, or built out of things this renderer cannot draw leaves the
 * species with its placeholder and a line on stderr. That is what makes it
 * possible to hand this folder to somebody and let them fill it in one animal
 * at a time.
 */
public final class AnimalModels {

    /** Where models are looked for, next to the jar and on the classpath. */
    public static final String DIRECTORY = "watch/models";

    /** The extensions a box model may have. */
    private static final String[] EXTENSIONS = {".bbmodel", ".json"};

    private static final Map<String, Loaded> CACHE = new LinkedHashMap<>();

    /**
     * A species' geometry and where its poses come from.
     *
     * @param geometry the boxes to draw, which is the placeholder's own set
     *                 when {@code scene} is what will actually be drawn — kept
     *                 non-null so that everything measuring an animal (the
     *                 guide's footer, the mutant tests, the picking ray) still
     *                 has boxes to measure
     * @param scene    free-form triangles out of a {@code .glb}, or {@code null}
     */
    public record Loaded(AnimalModel geometry, AnimalModel.PoseSource poses,
                         boolean imported, String source, SceneModel scene) {

        /** Whether this animal is drawn as triangles rather than as boxes. */
        public boolean freeform() { return scene != null; }

        /**
         * Draw the animal, whichever kind of model turned up for it.
         *
         * <p>The one entry point every caller should use. Reaching past it to
         * {@link #geometry()} draws the placeholder's boxes over the top of an
         * imported mesh, which is a bug that looks like a ghost.
         *
         * @param scale extra scale on top of the species' own body length
         */
        public void draw(Mesh.Builder mesh, AnimalDef def, double x, double y, double z,
                         double yaw, AnimState state, double phase, double scale) {
            if (scene == null) {
                geometry.mesh(mesh, def, x, y, z, yaw, state, phase, scale, poses);
                return;
            }
            float[] uv = new float[4];
            WatchMaterials.uv(WatchMaterial.PELT, uv);
            // The model was normalised to the placeholder's height in body
            // lengths, so metres per unit is exactly what the box path
            // multiplies by — and the two come out the same size.
            //
            // `poses` is handed over as the fallback so that the states the
            // artist has not animated are posed by the right table: MutantGait
            // for the three that are six-metre bipeds, the shared animal poses
            // for the other thirteen hundred. Without it an imported wendigo
            // idles like a wren.
            scene.mesh(mesh, x, y, z, yaw, state, phase, def.bodyLength() * scale, uv,
                    0, poses);
        }
    }

    private static Path root = Path.of(DIRECTORY);

    private AnimalModels() {}

    /**
     * Point the loader at a different folder — what a test uses, and what a
     * packaged build sets to the directory beside the jar.
     */
    public static synchronized void setDirectory(Path directory) {
        root = directory;
        CACHE.clear();
        SceneModels.setDirectory(directory);
    }

    /** Forget everything loaded, so a newly dropped model is picked up. */
    public static synchronized void invalidate() {
        CACHE.clear();
        SceneModels.invalidate();
    }

    /**
     * The model to draw a species with: an imported one if there is one, else
     * its family's placeholder.
     */
    public static synchronized Loaded of(AnimalDef def) {
        Loaded cached = CACHE.get(def.key());
        if (cached != null) return cached;

        Loaded loaded = importedFor(def.key(), def);
        if (loaded == null) loaded = importedFor(def.family().key(), def);
        if (loaded == null) {
            loaded = new Loaded(AnimalModel.of(def), posesFor(def), false, "placeholder",
                    null);
        }
        CACHE.put(def.key(), loaded);
        return loaded;
    }

    /**
     * Whatever has been dropped in under one name — boxes first.
     *
     * <p>{@code .bbmodel} wins over {@code .glb} for the same name so that
     * adding a triangle mesh beside an existing box model is a deliberate act
     * (delete the old one) rather than an accident of which extension sorted
     * first.
     */
    private static Loaded importedFor(String name, AnimalDef def) {
        Blockbench.Model boxes = find(name);
        if (boxes != null) {
            return new Loaded(boxes.geometry(), boxes, true, boxes.name(), null);
        }
        // Sized to the placeholder it is replacing, floor to crown. See
        // AnimalModel.height for why that and not the longest horizontal extent.
        AnimalModel placeholder = AnimalModel.of(def);
        SceneModel scene = SceneModels.of(name, ModelRig.Kind.CREATURE,
                SceneModel.Size.height(placeholder.height()));
        if (scene != null) {
            return new Loaded(placeholder, posesFor(def), true, scene.name(), scene);
        }
        return null;
    }

    /**
     * Where a species' poses come from when nothing has been imported for it.
     *
     * <p>The shared animal table for the thirteen hundred, and
     * {@link MutantGait} for the three — because the shared table is a good
     * <em>animal</em> walk and running it on a six-metre biped produces a
     * six-metre biped going for a pleasant walk. See {@code MutantGait} for what
     * is broken in it deliberately.
     *
     * <p>Note where this sits: an imported {@code .bbmodel} still wins, for a
     * mutant exactly as for a wren. Somebody who animates a wendigo by hand gets
     * their clips, not these.
     */
    private static AnimalModel.PoseSource posesFor(AnimalDef def) {
        Mutants.Kind mutant = Mutants.of(def);
        return mutant == null ? AnimalModel.procedural() : MutantGait.of(mutant);
    }

    /** Whether a species is being drawn from an imported model. */
    public static boolean isImported(AnimalDef def) { return of(def).imported(); }

    /** How many species currently have an imported model; for the guide's footer. */
    public static synchronized int importedCount() {
        int n = 0;
        for (Loaded l : CACHE.values()) {
            if (l.imported()) n++;
        }
        return n;
    }

    private static Blockbench.Model find(String name) {
        for (String extension : EXTENSIONS) {
            Path file = root.resolve(name + extension);
            if (Files.isReadable(file)) {
                Blockbench.Model model = read(file);
                if (model != null) return model;
            }
        }
        for (String extension : EXTENSIONS) {
            String resource = DIRECTORY + "/" + name + extension;
            try {
                Blockbench.Model model = Blockbench.loadResource(resource);
                if (model != null) return model;
            } catch (IOException | RuntimeException e) {
                warn(resource, e.toString());
            }
        }
        return null;
    }

    private static Blockbench.Model read(Path file) {
        try {
            Blockbench.Model model = Blockbench.load(file);
            if (model == null) warn(file.toString(), "no boxes in it");
            return model;
        } catch (IOException | RuntimeException e) {
            warn(file.toString(), e.toString());
            return null;
        }
    }

    private static void warn(String what, String why) {
        System.err.println("watch: could not load model " + what + " (" + why
                + ") — keeping the placeholder");
    }
}
