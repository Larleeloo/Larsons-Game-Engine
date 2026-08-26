package com.larsons.engine.watch.life;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every species there is — <b>one thousand two hundred and seventy-four of
 * them</b>, built once, the same every time.
 *
 * <h2>Why they are generated</h2>
 *
 * <p>The brief asks for upwards of a thousand animals to catalogue. Two ways to
 * get there are obviously wrong. Writing a thousand rows by hand is not
 * possible and would not be maintainable if it were. Rolling a thousand random
 * animals is worse: they come out as noise, no two players can talk about the
 * same bird, and nothing can be saved by name.
 *
 * <p>So the registry is <b>combinatorial and deterministic</b>:
 *
 * <pre>
 *   family  ×  lineage  ×  epithet   →   species
 *    (26)       (7 each)   (7 each)        1274
 * </pre>
 *
 * <ul>
 *   <li>a <b>family</b> ({@link AnimalFamily}) is a body plan and a behaviour —
 *       what it looks like, how it moves, when it is awake, where it lives;</li>
 *   <li>a <b>lineage</b> is the noun in the name (<em>Finch</em>,
 *       <em>Goshawk</em>) and belongs to exactly one family, which is what
 *       makes every name in the game unique;</li>
 *   <li>an <b>epithet</b> is the adjective (<em>Banded</em>, <em>Dusk</em>) and
 *       is what actually differentiates two species of one lineage: it shifts
 *       the palette, the size, the rarity, and often the hour it is out and the
 *       part of the family's range it keeps to.</li>
 * </ul>
 *
 * <p>Each family draws seven epithets from a pool of {@value #EPITHET_COUNT} by
 * striding through it — a stride coprime with the pool size, so the seven are
 * always distinct and two families rarely share a set. The result is a guide
 * where a page is worth reading: <em>Dusk Goshawk</em> is a scarce crepuscular
 * raptor of the high country and <em>Snow Goshawk</em> is a different bird in a
 * different place, and neither of them is a re-skin of the other.
 *
 * <h2>Determinism</h2>
 *
 * <p>Nothing here reads a clock, a seed, or a file. The same build of the game
 * produces the same 1 274 species in the same order with the same keys, which
 * is what lets a save hold a species key, a wire message name one, and two
 * players compare pages.
 */
public final class AnimalRegistry {

    /** Epithets in the shared pool. */
    public static final int EPITHET_COUNT = 48;

    /** How many epithets each family takes from it. */
    public static final int EPITHETS_PER_FAMILY = 7;

    /**
     * How far apart the epithets a family takes are in the pool. Coprime with
     * {@link #EPITHET_COUNT}, so seven strides never repeat an entry.
     */
    private static final int EPITHET_STRIDE = 7;

    private static final String[] EPITHETS = {
            "Banded", "Emerald", "Dusk", "Golden", "Ashen", "Ruby", "Ivory", "Slate",
            "Painted", "Crested", "Speckled", "Azure", "Amber", "Silver", "Hooded",
            "Masked", "Scarlet", "Bronze", "Sable", "Frosted", "Cinnamon", "Violet",
            "Verdant", "Copper", "Pearl", "Shadowed", "Rufous", "Snow", "Ember", "Jade",
            "Lesser", "Greater", "Northern", "Southern", "Coastal", "Highland", "Vagrant",
            "Whistling", "Piping", "Twilight", "Moonlit", "Gilded", "Thorned", "Marbled",
            "Striped", "Blushing", "Iron", "Starlit"
    };

    /**
     * The hue each epithet pulls a palette toward, in degrees, and how much it
     * lightens or darkens it. An epithet that names a colour means it; one that
     * names a place or a habit leaves the palette nearly alone and does its work
     * elsewhere.
     */
    private static final double[] EPITHET_HUE = {
            0, 140, 265, 45, 30, 355, 50, 215, 20, 275, 35, 205, 38, 210, 250, 260,
            5, 28, 270, 195, 22, 285, 120, 25, 40, 255, 15, 200, 12, 155, 0, 0,
            200, 40, 190, 100, 300, 60, 55, 262, 240, 44, 320, 33, 0, 340, 220, 245
    };

    private static final double[] EPITHET_LIGHT = {
            0, 0.05, -0.30, 0.22, -0.10, 0.05, 0.34, -0.14, 0.08, 0.02, 0.06, 0.10,
            0.14, 0.26, -0.22, -0.26, 0.12, -0.02, -0.34, 0.28, -0.04, -0.06, 0.04,
            -0.02, 0.30, -0.38, -0.02, 0.36, -0.16, 0.02, -0.06, 0.06, 0.10, -0.04,
            0.08, 0.02, 0.00, 0.08, 0.10, -0.28, -0.18, 0.20, -0.12, 0.04, 0.00,
            0.14, -0.20, 0.16
    };

    /** How much each epithet scales the family's body length. */
    private static final double[] EPITHET_SIZE = {
            1.00, 0.96, 1.02, 1.06, 0.94, 0.98, 1.04, 1.00, 1.02, 1.08, 0.92, 0.98,
            1.00, 1.03, 0.97, 0.95, 1.01, 1.05, 1.07, 0.93, 0.99, 1.02, 1.00, 1.04,
            0.96, 1.06, 0.98, 0.94, 1.03, 0.99, 0.74, 1.32, 1.05, 0.95, 1.00, 1.08,
            0.90, 0.97, 0.93, 1.02, 0.99, 1.04, 1.06, 1.00, 0.98, 0.96, 1.10, 0.95
    };

    private static final List<AnimalDef> ALL = build();
    private static final Map<String, AnimalDef> BY_KEY = index();
    private static final Map<String, List<AnimalDef>> BY_BIOME = byBiome();

    private AnimalRegistry() {}

    /** Every species, in a stable order. */
    public static List<AnimalDef> all() { return ALL; }

    /** How many species there are. */
    public static int count() { return ALL.size(); }

    /**
     * The species with this key, or {@code null} — including for a null key.
     *
     * <p>Every string reaching this came from a save file or off the wire, and
     * {@code Map.of} throws on a null key rather than missing it. A sighting
     * whose species field did not survive the trip has to read as "a species I
     * do not know", which the callers all handle, and not as an exception on
     * the frame thread.
     */
    public static AnimalDef byKey(String key) {
        return key == null ? null : BY_KEY.get(key);
    }

    /** Every species that lives in a biome, in registry order. */
    public static List<AnimalDef> inBiome(String biomeKey) {
        if (biomeKey == null) return List.of();
        return BY_BIOME.getOrDefault(biomeKey, List.of());
    }

    /** Every species of a family. */
    public static List<AnimalDef> inFamily(AnimalFamily family) {
        List<AnimalDef> out = new ArrayList<>();
        for (AnimalDef d : ALL) {
            if (d.family() == family) out.add(d);
        }
        return out;
    }

    /** Every species that can become a pet. */
    public static List<AnimalDef> tameable() {
        List<AnimalDef> out = new ArrayList<>();
        for (AnimalDef d : ALL) {
            if (d.tameable()) out.add(d);
        }
        return out;
    }

    /** The seven epithets a family draws from the pool. */
    public static List<String> epithetsFor(AnimalFamily family) {
        List<String> out = new ArrayList<>(EPITHETS_PER_FAMILY);
        for (int i = 0; i < EPITHETS_PER_FAMILY; i++) {
            out.add(EPITHETS[epithetIndex(family, i)]);
        }
        return out;
    }

    private static int epithetIndex(AnimalFamily family, int slot) {
        return (family.ordinal() * 5 + slot * EPITHET_STRIDE) % EPITHET_COUNT;
    }

    // --- the build -----------------------------------------------------------------

    private static List<AnimalDef> build() {
        List<AnimalDef> out = new ArrayList<>(1300);
        for (AnimalFamily family : AnimalFamily.values()) {
            List<String> lineages = family.lineages();
            for (int l = 0; l < lineages.size(); l++) {
                for (int e = 0; e < EPITHETS_PER_FAMILY; e++) {
                    out.add(species(family, lineages.get(l), l, epithetIndex(family, e)));
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    private static AnimalDef species(AnimalFamily family, String lineage, int lineageIndex,
                                     int epithetIndex) {
        String epithet = EPITHETS[epithetIndex];
        String key = family.key() + "_" + slug(lineage) + "_" + slug(epithet);
        String name = epithet + " " + lineage;
        long h = hash(key);

        // Size: the family's build, nudged by the lineage and then by the
        // epithet — "Lesser" and "Greater" really are the small and large ones.
        double size = family.bodyLength()
                * (0.86 + 0.28 * ((lineageIndex * 3 + 1) % 7) / 6.0)
                * EPITHET_SIZE[epithetIndex];

        int body = shift(family.baseColour(), EPITHET_HUE[epithetIndex],
                EPITHET_LIGHT[epithetIndex], 0.62);
        int accent = shift(body, EPITHET_HUE[(epithetIndex + 17) % EPITHET_COUNT],
                0.24 + 0.2 * roll(h, 7), 0.5);
        int detail = shift(body, EPITHET_HUE[(epithetIndex + 31) % EPITHET_COUNT],
                -0.34, 0.45);

        Rarity rarity = rarityFor(h);
        Activity activity = activityFor(family, epithetIndex, h);
        Diet diet = dietFor(family, h);
        List<String> biomes = biomesFor(family, h, rarity);

        // Wariness climbs with rarity, which is most of why a rare bird is
        // rare: not that there are few of them, but that they see you first.
        double wariness = Math.min(0.97,
                family.wariness() * rarity.warinessScale() * (0.92 + 0.16 * roll(h, 13)));

        double speed = speedFor(family, size, h);
        double perch = perchFor(family, size, h);
        // Small things call high. A hundred and eighty grams of warbler and two
        // kilos of raven do not sound alike, and the guide says so.
        double voice = Math.max(0.05, Math.min(1, 1.05 - Math.log10(1 + size * 60) / 2.2));

        boolean tameable = rarity != Rarity.LEGENDARY
                && roll(h, 23) < family.tameShare();

        return new AnimalDef(key, name, family, lineage, epithet, size, body, accent, detail,
                diet, activity, rarity, biomes, tameable, wariness, speed, perch, voice);
    }

    /**
     * The rarity tier, from the species' own key.
     *
     * <p>The distribution is the shape a real guide has: most of it is birds
     * you see on a walk, and the last page takes a season. Roughly 45% common,
     * 28% uncommon, 17% scarce, 8% rare, 2% legendary — about twenty-five
     * legendary species in the whole world.
     */
    private static Rarity rarityFor(long h) {
        double roll = roll(h, 3);
        if (roll < 0.45) return Rarity.COMMON;
        if (roll < 0.73) return Rarity.UNCOMMON;
        if (roll < 0.90) return Rarity.SCARCE;
        if (roll < 0.98) return Rarity.RARE;
        return Rarity.LEGENDARY;
    }

    /**
     * When it is out. Mostly its family's habit — a raptor hunts by day — but
     * four epithets in the pool <em>are</em> an hour ({@code Dusk},
     * {@code Twilight}, {@code Moonlit}, {@code Starlit}), and a species called
     * one of those had better be out then.
     */
    private static Activity activityFor(AnimalFamily family, int epithetIndex, long h) {
        String epithet = EPITHETS[epithetIndex];
        switch (epithet) {
            case "Dusk", "Twilight" -> { return Activity.CREPUSCULAR; }
            case "Moonlit", "Starlit", "Shadowed" -> { return Activity.NOCTURNAL; }
            case "Golden", "Gilded" -> { return Activity.DIURNAL; }
            default -> { }
        }
        double roll = roll(h, 29);
        if (roll < 0.62) return family.activity();
        if (roll < 0.78) return Activity.CREPUSCULAR;
        if (roll < 0.92) return Activity.CATHEMERAL;
        return family.activity() == Activity.NOCTURNAL ? Activity.DIURNAL : Activity.NOCTURNAL;
    }

    /** What it eats: its family's staple most of the time, a neighbour's sometimes. */
    private static Diet dietFor(AnimalFamily family, long h) {
        double roll = roll(h, 37);
        if (roll < 0.70) return family.diet();
        if (roll < 0.86) return Diet.OMNIVORE;
        Diet[] all = Diet.values();
        return all[(int) ((h >>> 41) % all.length)];
    }

    /**
     * Which of its family's biomes it actually keeps to.
     *
     * <p>A species in every biome its family occupies is a species you cannot
     * fail to find, so each one takes a slice: a common animal keeps most of
     * the range, a legendary one keeps two places in the world. Always at least
     * one, and the slice starts at a hashed offset so two species of a lineage
     * do not overlap completely.
     */
    private static List<String> biomesFor(AnimalFamily family, long h, Rarity rarity) {
        List<String> pool = family.biomes();
        int most = pool.size();
        int want = switch (rarity) {
            case COMMON -> Math.max(3, most - 1);
            case UNCOMMON -> Math.max(3, most - 2);
            case SCARCE -> Math.max(2, most / 2);
            case RARE -> Math.max(2, most / 3);
            case LEGENDARY -> Math.max(1, most / 4);
        };
        want = Math.min(want, most);
        int start = (int) ((h >>> 47) % most);
        List<String> out = new ArrayList<>(want);
        for (int i = 0; i < want; i++) {
            out.add(pool.get((start + i * 3) % most));
        }
        // The stride can revisit an entry when it and the pool size share a
        // factor; a species listed twice in one biome would be twice as likely
        // to appear there, which is not what the slice is for.
        return List.copyOf(new java.util.LinkedHashSet<>(out));
    }

    private static double speedFor(AnimalFamily family, double size, long h) {
        double base = switch (family.motion()) {
            case SOAR -> 11;
            case FLY -> 8;
            case FLIT -> 5.5;
            case HOVER -> 4.5;
            case HOP -> 3.2;
            case CLIMB -> 2.4;
            case SWIM -> 2.0;
            case WALK -> 3.6;
        };
        // Bigger things are faster over the ground and slower through the air.
        double scale = family.motion().airborne()
                ? 1.15 - Math.min(0.5, size * 0.25)
                : 0.75 + Math.min(0.9, size * 0.45);
        return base * scale * (0.9 + 0.2 * roll(h, 17));
    }

    private static double perchFor(AnimalFamily family, double size, long h) {
        return switch (family.motion()) {
            case SOAR -> 22 + 30 * unit(h);
            case FLY -> 6 + 10 * unit(h);
            case FLIT -> 1.5 + 6 * unit(h);
            case HOVER -> 1.0 + 2.5 * unit(h);
            case CLIMB -> 2.5 + 7 * unit(h);
            case SWIM -> -0.4 - size * 0.5;
            default -> 0;
        };
    }

    // --- helpers -------------------------------------------------------------------

    /**
     * Pull a colour toward a hue, and lighten or darken it.
     *
     * <p>Toward rather than to: a "Scarlet Wren" should still read as a wren.
     * {@code pull} is how far the hue actually moves, so a family keeps its own
     * character across all forty-nine of its species while no two of them are
     * the same colour.
     */
    private static int shift(int rgb, double towardHue, double lighten, double pull) {
        float[] hsb = Color.RGBtoHSB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, null);
        double target = towardHue / 360.0;
        double hue = hsb[0];
        // Round the short way about the colour wheel.
        double delta = target - hue;
        if (delta > 0.5) delta -= 1;
        if (delta < -0.5) delta += 1;
        hue = (hue + delta * pull + 1) % 1;
        double saturation = Math.max(0.06, Math.min(1, hsb[1] * (1 + pull * 0.25)));
        // Lifted, and floored well above black. A palette derived from a
        // family's base colour comes out around half brightness, and the world
        // then multiplies it by a face's shade and by the hour's light — three
        // multiplications below one, which put every animal in the game
        // somewhere between charcoal and soot. An animal has to be identifiable
        // at forty metres in the shade, so the palette starts bright.
        double brightness = Math.max(0.30, Math.min(1, hsb[2] + lighten + 0.16));
        return Color.HSBtoRGB((float) hue, (float) saturation, (float) brightness) & 0xFFFFFF;
    }

    private static Map<String, AnimalDef> index() {
        Map<String, AnimalDef> map = new LinkedHashMap<>();
        for (AnimalDef d : ALL) map.put(d.key(), d);
        return Map.copyOf(map);
    }

    private static Map<String, List<AnimalDef>> byBiome() {
        Map<String, List<AnimalDef>> map = new LinkedHashMap<>();
        for (AnimalDef d : ALL) {
            for (String biome : d.biomes()) {
                map.computeIfAbsent(biome, k -> new ArrayList<>()).add(d);
            }
        }
        Map<String, List<AnimalDef>> frozen = new LinkedHashMap<>();
        map.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        return Map.copyOf(frozen);
    }

    /** A species key's own stable hash — the source of every rolled decision. */
    private static long hash(String key) {
        long h = 0xCBF29CE484222325L;
        for (int i = 0; i < key.length(); i++) {
            h ^= key.charAt(i);
            h *= 0x100000001B3L;
        }
        h = (h ^ (h >>> 33)) * 0xFF51AFD7ED558CCDL;
        h = (h ^ (h >>> 33)) * 0xC4CEB9FE1A85EC53L;
        return h ^ (h >>> 33);
    }


    /**
     * An independent value in {@code [0, 1)} drawn from one hash — the
     * {@code stream}-th of them.
     *
     * <p><b>Not a shift of the hash, which is what this replaced and which
     * silently did not work.</b> {@link #unit} takes the top fifty-three bits,
     * so asking for {@code unit(h >>> 34)} leaves thirty bits above the point
     * and twenty-three zeroes below it: every such "roll" came out under
     * {@code 2}<sup>-23</sup>. Every density test in the generator was
     * therefore passing unconditionally — every candidate cell grew a tree, and
     * every species in the registry came out common and tameable. Re-mixing
     * costs three multiplies and cannot fail this way.
     */
    private static double roll(long h, int stream) {
        long x = h + stream * 0x9E3779B97F4A7C15L;
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        x ^= x >>> 31;
        return (x >>> 11) * 0x1.0p-53;
    }

    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    private static String slug(String text) {
        return text.toLowerCase().replace(' ', '_').replace("-", "");
    }
}
