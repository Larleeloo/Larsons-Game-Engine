package com.larsons.engine.watch.life;

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
 *   <li>{@code watch/models/<species>.bbmodel} — one species;</li>
 *   <li>{@code watch/models/<family>.bbmodel} — all forty-nine of a family;</li>
 *   <li>the procedural placeholder ({@link AnimalModel#of}).</li>
 * </ol>
 *
 * <p>Each of the first two is tried <b>next to the jar first and on the
 * classpath second</b>, which is the same rule the engine's texture packs
 * follow: a player can drop a model beside the game without rebuilding it, and
 * a creator can bundle one inside it.
 *
 * <p><b>Nothing here can stop the game starting.</b> A file that is missing, is
 * not JSON, is a Blockbench mesh model, or has no elements in it leaves the
 * species with its placeholder and a line on stderr. That is what makes it
 * possible to hand this folder to somebody and let them fill it in one animal
 * at a time.
 */
public final class AnimalModels {

    /** Where models are looked for, next to the jar and on the classpath. */
    public static final String DIRECTORY = "watch/models";

    /** The extensions a model may have. */
    private static final String[] EXTENSIONS = {".bbmodel", ".json"};

    private static final Map<String, Loaded> CACHE = new LinkedHashMap<>();

    /** A species' geometry and where its poses come from. */
    public record Loaded(AnimalModel geometry, AnimalModel.PoseSource poses,
                         boolean imported, String source) {}

    private static Path root = Path.of(DIRECTORY);

    private AnimalModels() {}

    /**
     * Point the loader at a different folder — what a test uses, and what a
     * packaged build sets to the directory beside the jar.
     */
    public static synchronized void setDirectory(Path directory) {
        root = directory;
        CACHE.clear();
    }

    /** Forget everything loaded, so a newly dropped model is picked up. */
    public static synchronized void invalidate() { CACHE.clear(); }

    /**
     * The model to draw a species with: an imported one if there is one, else
     * its family's placeholder.
     */
    public static synchronized Loaded of(AnimalDef def) {
        Loaded cached = CACHE.get(def.key());
        if (cached != null) return cached;

        Blockbench.Model imported = find(def.key());
        if (imported == null) imported = find(def.family().key());

        Loaded loaded = imported != null
                ? new Loaded(imported.geometry(), imported, true, imported.name())
                : new Loaded(AnimalModel.of(def), AnimalModel.procedural(), false,
                        "placeholder");
        CACHE.put(def.key(), loaded);
        return loaded;
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
