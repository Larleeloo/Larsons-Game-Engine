package com.larsons.engine.watch.model;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where an imported model comes from — <b>the drop-in point for everything that
 * is not a box.</b>
 *
 * <p>Asked for {@code characters/ranger}, this looks for
 * {@code characters/ranger.glb}, then {@code .gltf}, then {@code .obj}, first in
 * a {@code watch/models} folder <b>beside the jar</b> and then in the one on the
 * <b>classpath</b>. The first that loads wins, so a player can drop a model into
 * an installed game without building anything and a creator can bundle one
 * inside it — the same rule {@code AnimalModels} and the texture packs already
 * follow, deliberately, because a third rule for a third kind of asset is a
 * thing nobody would remember.
 *
 * <p><b>Nothing here can stop the game starting.</b> A file that is missing,
 * truncated, in a format this does not read, or full of geometry with no
 * triangles in it leaves the caller with its procedural fallback and prints one
 * line to stderr. That is the property that makes it safe to hand this folder to
 * somebody and let them fill it in one model at a time — and it is why every
 * reader in this package returns {@code null} rather than throwing.
 */
public final class SceneModels {

    /** Where models are looked for, beside the jar and on the classpath. */
    public static final String DIRECTORY = "watch/models";

    /**
     * The extensions tried, in order.
     *
     * <p>{@code .glb} first because it is one file — a {@code .gltf} that has
     * lost its {@code .bin} beside it is the single most common way an imported
     * model arrives broken, and a folder people commit to is exactly where that
     * happens.
     */
    private static final String[] EXTENSIONS = {".glb", ".gltf", ".obj"};

    private static final Map<String, SceneModel> CACHE = new LinkedHashMap<>();

    /** Names already reported as missing, so a per-frame lookup warns once. */
    private static final Map<String, Boolean> WARNED = new LinkedHashMap<>();

    private static Path root = Path.of(DIRECTORY);

    private SceneModels() {}

    /** Point the loader at a different folder — what a test and a packaged build set. */
    public static synchronized void setDirectory(Path directory) {
        root = directory;
        CACHE.clear();
        WARNED.clear();
    }

    /** Forget everything, so a newly dropped file is picked up. */
    public static synchronized void invalidate() {
        CACHE.clear();
        WARNED.clear();
    }

    /**
     * The model under a name, or {@code null} when there is not one.
     *
     * <p>Cached on the name <em>and</em> how it was asked for, because the same
     * file read as a creature and as a person binds its bones differently and
     * handing back the first answer to the second question would be a ranger
     * whose arms folded like wings. The size is part of that key too — one
     * family file dresses forty-nine species, and the builds among them do not
     * all stand the same height.
     */
    public static synchronized SceneModel of(String name, ModelRig.Kind kind,
                                             SceneModel.Size size) {
        String key = name + "|" + kind + "|" + size.height();
        SceneModel cached = CACHE.get(key);
        if (cached != null) return cached;
        if (CACHE.containsKey(key)) return null;

        SceneModel model = find(name, kind, size);
        CACHE.put(key, model);
        return model;
    }

    /** Whether anything has been imported under a name. */
    public static boolean has(String name, ModelRig.Kind kind, SceneModel.Size size) {
        return of(name, kind, size) != null;
    }

    private static SceneModel find(String name, ModelRig.Kind kind,
                                   SceneModel.Size size) {
        for (String extension : EXTENSIONS) {
            Path file = root.resolve(name + extension);
            if (!Files.isReadable(file)) continue;
            SceneModel model = bake(read(file), file.toString(), kind, size);
            if (model != null) return model;
        }
        for (String extension : EXTENSIONS) {
            String resource = DIRECTORY + "/" + name + extension;
            SceneModel model = bake(readResource(resource), resource, kind, size);
            if (model != null) return model;
        }
        return null;
    }

    private static SceneModel bake(RawModel raw, String where, ModelRig.Kind kind,
                                   SceneModel.Size size) {
        if (raw == null) return null;
        SceneModel model = SceneModel.bake(raw, kind, size);
        if (model == null) warn(where, "nothing in it this renderer can draw");
        return model;
    }

    private static RawModel read(Path file) {
        try {
            RawModel raw = extensionOf(file.toString()).equals(".obj")
                    ? ObjReader.load(file) : GltfReader.load(file);
            if (raw == null) warn(file.toString(), "no triangles in it");
            return raw;
        } catch (IOException | RuntimeException e) {
            warn(file.toString(), e.toString());
            return null;
        }
    }

    private static RawModel readResource(String resource) {
        try {
            return extensionOf(resource).equals(".obj")
                    ? ObjReader.loadResource(resource) : GltfReader.loadResource(resource);
        } catch (IOException | RuntimeException e) {
            warn(resource, e.toString());
            return null;
        }
    }

    private static String extensionOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot).toLowerCase();
    }

    /** One line per file, once — a lookup that happens per frame must not shout. */
    private static void warn(String what, String why) {
        if (WARNED.putIfAbsent(what, Boolean.TRUE) != null) return;
        System.err.println("watch: could not load model " + what + " (" + why
                + ") — keeping the fallback");
    }
}
