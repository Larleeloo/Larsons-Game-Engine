package com.larsons.engine.watch;

import com.larsons.engine.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Everything the Field Guide keeps on disk.
 *
 * <p>One JSON file per world, under {@code resources/watch/worlds/}, named
 * after the world. What is in it is what {@link WatchGame#toMap} writes: the
 * shared book, the grove, the crops, the buildings, the feeders and the party's
 * satchels — <b>and a timestamp</b>, which is the field that makes this game's
 * saves different from most.
 *
 * <p><b>Time passes while the file is closed.</b> A tree planted on Tuesday is
 * a bigger tree on Thursday whether or not anybody logged in between, because
 * {@link WatchGame#load} reads that timestamp and advances every growing thing
 * by the real hours since. A save here is therefore a record of <em>when</em>
 * as much as of what, and losing the stamp would quietly stop the whole
 * cultivation half of the game from working.
 *
 * <p><b>Animals are not saved</b>, deliberately. They are generated near
 * whoever is walking and forgotten when nobody is; a specific chaffinch is not
 * a thing anybody could tell from another one, and the species table is what
 * makes the world consistent between sessions. What <em>is</em> saved is every
 * animal that mattered: the ones in the book, and the ones somebody tamed.
 */
public final class WatchStore {

    /** Where worlds live, next to the other games' saves. */
    public static final String DEFAULT_DIR = "src/main/resources/watch/worlds";

    /** The extension a world file takes. */
    public static final String EXTENSION = ".json";

    private final Path dir;

    public WatchStore() {
        this(DEFAULT_DIR);
    }

    public WatchStore(String dir) {
        this.dir = Path.of(dir);
    }

    /** Where this store keeps its files. */
    public Path directory() { return dir; }

    /** The file a world of this name is kept in. */
    public Path fileFor(String worldName) {
        return dir.resolve(slug(worldName) + EXTENSION);
    }

    /** Whether a world of this name has been saved. */
    public boolean exists(String worldName) {
        return Files.isReadable(fileFor(worldName));
    }

    /**
     * The saved worlds, most recently played first — which is the order a
     * "continue" list wants and the only order anybody scans one in.
     */
    public List<String> list() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> found = files
                    .filter(p -> p.getFileName().toString().endsWith(EXTENSION))
                    .sorted(Comparator.comparingLong(WatchStore::modifiedAt).reversed())
                    .toList();
            for (Path file : found) {
                String name = nameIn(file);
                out.add(name != null ? name : stripExtension(file));
            }
        } catch (IOException e) {
            // A store that cannot be listed is an empty one as far as a menu is
            // concerned; the alternative is a launch screen that will not open.
            return out;
        }
        return out;
    }

    /** A one-line summary for the lobby's list, or {@code null}. */
    public String describe(String worldName) {
        Map<String, Object> saved = readRaw(worldName);
        if (saved == null) return null;
        Map<String, Object> guide = WatchJson.map(saved, "guide");
        int species = WatchJson.objects(guide, "entries").size();
        long when = WatchJson.big(saved, "saved", 0);
        String ago = when <= 0 ? "" : " · " + describeAge(
                WatchClock.realHoursBetween(when, System.currentTimeMillis()));
        return species + (species == 1 ? " species" : " species") + ago;
    }

    /** How long ago, in words. */
    private static String describeAge(double hours) {
        if (hours < 1) return "just now";
        if (hours < 24) return Math.round(hours) + "h ago";
        long days = Math.round(hours / 24);
        return days + (days == 1 ? " day ago" : " days ago");
    }

    /** Write a world out. */
    public void save(WatchGame game) {
        if (game == null) return;
        try {
            Files.createDirectories(dir);
            Path file = fileFor(game.config().worldName());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            // Written beside the real file and moved into place, so a crash
            // halfway through a save leaves the previous world intact rather
            // than half of two.
            Files.writeString(temporary, Json.stringify(game.toMap()),
                    StandardCharsets.UTF_8);
            Files.move(temporary, file,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("could not save world " + game.config().worldName(), e);
        }
    }

    /**
     * Put a saved world back into a game.
     *
     * @return {@code true} if there was one to load
     */
    public boolean load(WatchGame game) {
        Map<String, Object> saved = readRaw(game.config().worldName());
        if (saved == null) return false;
        game.load(saved);
        return true;
    }

    /** The seed a saved world was generated with, or {@code 0}. */
    public long seedOf(String worldName) {
        Map<String, Object> saved = readRaw(worldName);
        return saved == null ? 0 : WatchJson.big(saved, "seed", 0);
    }

    /** Delete a world. */
    public boolean delete(String worldName) {
        try {
            return Files.deleteIfExists(fileFor(worldName));
        } catch (IOException e) {
            return false;
        }
    }

    private Map<String, Object> readRaw(String worldName) {
        Path file = fileFor(worldName);
        if (!Files.isReadable(file)) return null;
        try {
            Object parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            return parsed instanceof Map<?, ?> ? Json.asObject(parsed) : null;
        } catch (IOException | RuntimeException e) {
            // A corrupt world file is one world nobody can continue, not a game
            // that will not start.
            System.err.println("watch: could not read " + file + " (" + e + ")");
            return null;
        }
    }

    /** The world's own name from inside the file, which may differ from the slug. */
    private static String nameIn(Path file) {
        try {
            Object parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
            if (!(parsed instanceof Map<?, ?>)) return null;
            return WatchJson.str(Json.asObject(parsed), "world", null);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static long modifiedAt(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String stripExtension(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(EXTENSION)
                ? name.substring(0, name.length() - EXTENSION.length())
                : name;
    }

    /** A file name a world can safely be kept under. */
    public static String slug(String worldName) {
        if (worldName == null || worldName.isBlank()) return "walk";
        StringBuilder sb = new StringBuilder();
        for (char c : worldName.trim().toLowerCase().toCharArray()) {
            if (Character.isLetterOrDigit(c)) sb.append(c);
            else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') sb.append('_');
        }
        String slug = sb.toString();
        while (slug.endsWith("_")) slug = slug.substring(0, slug.length() - 1);
        return slug.isEmpty() ? "walk" : slug;
    }
}
