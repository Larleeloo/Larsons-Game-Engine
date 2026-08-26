package com.larsons.engine.watch.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.larsons.engine.watch.world.TreeSpecies.*;
import static com.larsons.engine.watch.world.WatchMaterial.*;

/**
 * The twenty places this world has to stand in.
 *
 * <p>Seven of them were asked for by name — pine forest, deciduous forest,
 * desert, rainforest, palm tropics, mountains, and a fantasy biome with purple
 * leaves. The other thirteen are here because a world of seven biomes is a
 * world you have seen all of in an afternoon, and because a field guide with a
 * thousand animals in it needs somewhere for all of them to live.
 *
 * <p><b>The numbers were chosen so the world reads as varied at a walking
 * pace.</b> The climates spread across the whole temperature and humidity
 * square so no biome dominates; the elevations spread from a marsh at the water
 * line to ridges ninety metres up; and every biome has a ground cover, a tree
 * list and a grass length band distinctive enough that you can tell where you
 * are without looking at the compass.
 *
 * <p><b>The weights are measured, not guessed.</b> A biome's share of the map
 * is not proportional to its weight — it is what is left after every
 * <em>other</em> biome has taken what its own climate band overlaps, and a
 * biome tucked between two popular neighbours can be given a large weight and
 * still never appear. So the weights here were fitted: sample the generator's
 * climate over two seeds and twelve kilometres square, see what each biome
 * actually claims, and move the weights toward an even split. The result is
 * every biome between roughly 2% and 8% of the world, which is what a walk
 * across it should feel like.
 *
 * <p>The three fantasy biomes ({@code amethyst_grove}, {@code mushroom_hollow},
 * {@code crystal_highlands}) are then deliberately pulled back to about a third
 * of an even share. They are also the only three that live on the
 * {@linkplain WatchBiome#strangeness() strangeness} axis, which is what makes
 * them rare rather than merely small — see that method.
 */
public final class WatchBiomes {

    private static final List<WatchBiome> ALL = build();
    private static final Map<String, WatchBiome> BY_KEY = index(ALL);

    private WatchBiomes() {}

    /** Every biome, in the order the field guide lists them. */
    public static List<WatchBiome> all() { return ALL; }

    /** How many there are — twenty. */
    public static int count() { return ALL.size(); }

    /**
     * The biome with this key, or {@code null} — including for a null key.
     *
     * <p>The null guard is not defensive habit. Every caller of this is holding
     * a string that came out of a save file or off the wire ({@code Sighting}
     * printing where an animal was seen, a client loading a party's guide), and
     * {@code Map.of} throws on a null key rather than missing it. A truncated
     * packet would have taken out the guide page.
     */
    public static WatchBiome byKey(String key) {
        return key == null ? null : BY_KEY.get(key);
    }

    /** The biome with this key, or {@link #defaultBiome()} when unknown. */
    public static WatchBiome of(String key) {
        WatchBiome found = byKey(key);
        return found != null ? found : defaultBiome();
    }

    /** What an unreadable save or an unknown climate falls back to. */
    public static WatchBiome defaultBiome() { return ALL.get(1); }

    /**
     * The biome that best fits a climate, and by how much every other biome
     * fits it — the generator blends the top few for height and takes the
     * winner's materials, which is what gives sharp borders over ground that
     * never steps.
     *
     * @return the best-fitting biome; never {@code null}
     */
    public static WatchBiome bestFit(double temperature, double humidity) {
        return bestFit(temperature, humidity, WatchBiome.ORDINARY);
    }

    /** The biome that best fits a full three-axis climate. */
    public static WatchBiome bestFit(double temperature, double humidity, double strange) {
        return ALL.get(bestFitIndex(temperature, humidity, strange));
    }

    /**
     * {@link #bestFit} as an index into {@link #all()}.
     *
     * <p>Chunk generation stores a biome per height sample and asks this
     * question a thousand times per chunk; handing back the index it is about
     * to store saves it looking the answer up again in a twenty-entry list
     * every single time.
     */
    public static int bestFitIndex(double temperature, double humidity, double strange) {
        int best = 1;
        double bestScore = -1;
        for (int i = 0; i < ALL.size(); i++) {
            double score = ALL.get(i).fit(temperature, humidity, strange);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    /** Where a biome sits in {@link #all()}, or {@code -1} if it is not one of ours. */
    public static int indexOf(WatchBiome biome) {
        for (int i = 0; i < ALL.size(); i++) {
            if (ALL.get(i) == biome) return i;
        }
        return -1;
    }

    private static Map<String, WatchBiome> index(List<WatchBiome> biomes) {
        Map<String, WatchBiome> map = new LinkedHashMap<>();
        for (WatchBiome b : biomes) map.put(b.key(), b);
        return Map.copyOf(map);
    }

    private static List<WatchBiome> build() {
        List<WatchBiome> out = new ArrayList<>(20);

        // --- the seven the brief named -------------------------------------------

        out.add(WatchBiome.named("pine_forest", "Pine Forest")
                .blurb("Straight trunks, needle floor, and birdsong you hear long before you see.")
                .climate(30, 58).ground(34, 14).weight(6)
                .materials(GRASS, MOSS, ROCK, GRAVEL, TRAIL_EARTH)
                .sky(0x8AAECE, 0xB2C4D2)
                .trees(0.38, PINE, SPRUCE, FIR, CEDAR)
                .grass(0.55, 0.10, 0.35)
                .forage(0.16, List.of("lingonberry", "juniper"),
                        List.of("pine_seed", "grass_seed"))
                .rocks(0.004)
                .build());

        out.add(WatchBiome.named("deciduous_forest", "Deciduous Woods")
                .blurb("Broad crowns, leaf litter, and a different bird on every branch.")
                .climate(48, 60).ground(24, 10).weight(11)
                .materials(GRASS, LUSH_GRASS, ROCK, SAND, TRAIL_EARTH)
                .sky(0x8FB6DE, 0xBFD2E2)
                .trees(0.30, OAK, MAPLE, BEECH, BIRCH)
                .grass(0.70, 0.15, 0.60)
                .forage(0.26, List.of("blackberry", "elderberry", "thimbleberry"),
                        List.of("acorn", "grass_seed", "thistle_seed"))
                .rocks(0.002)
                .build());

        out.add(WatchBiome.named("desert", "Dune Desert")
                .blurb("Long light, longer shadows, and everything alive waiting for dusk.")
                .climate(88, 8).ground(18, 12).weight(20)
                .materials(SAND, RED_SAND, ROCK, SAND, TRAIL_SAND)
                .sky(0xB9C8D8, 0xE0CFA8)
                .trees(0.014, SAGUARO, JOSHUA)
                .grass(0.10, 0.06, 0.18)
                .forage(0.05, List.of("prickly_pear"), List.of("cactus_seed"))
                .rocks(0.006)
                .build());

        out.add(WatchBiome.named("rainforest", "Rainforest")
                .blurb("Three storeys of green, and the loudest place in the world at dawn.")
                .climate(82, 92).ground(22, 12).weight(20)
                .materials(LUSH_GRASS, MOSS, CLAY, CLAY, TRAIL_EARTH)
                .sky(0x93B7C2, 0xA9C4B4)
                .trees(0.52, MAHOGANY, KAPOK, TreeSpecies.BAMBOO)
                .grass(0.80, 0.25, 1.10)
                .forage(0.34, List.of("guava", "cocoa_pod", "fig"),
                        List.of("kapok_seed", "wild_rice"))
                .rocks(0.001)
                .build());

        out.add(WatchBiome.named("tropics", "Palm Tropics")
                .blurb("White sand, warm shallows, and seabirds arguing over all of it.")
                .climate(78, 70).ground(8, 5).weight(12)
                .materials(SAND, LUSH_GRASS, ROCK, SAND, TRAIL_SAND)
                .sky(0x7FC0E8, 0xD6E7EE)
                .trees(0.20, PALM, COCONUT_PALM)
                .grass(0.35, 0.10, 0.40)
                .forage(0.18, List.of("sea_grape", "guava"),
                        List.of("palm_seed", "grass_seed"))
                .rocks(0.002)
                .build());

        out.add(WatchBiome.named("mountains", "Mountain Ridges")
                .blurb("Thin air, bare stone, and raptors using it better than you are.")
                .climate(18, 42).ground(95, 45).weight(6)
                .materials(ROCK, SNOW, DARK_ROCK, GRAVEL, TRAIL_STONE)
                .sky(0x7FA8D4, 0xC8D6E4)
                .trees(0.10, PINE, FIR)
                .grass(0.20, 0.05, 0.20)
                .forage(0.06, List.of("crowberry", "juniper"),
                        List.of("lupine_seed", "pine_seed"))
                .rocks(0.02)
                .build());

        out.add(WatchBiome.named("amethyst_grove", "Amethyst Grove")
                .blurb("Purple canopy, violet light on the floor, and things that only sing here.")
                .climate(52, 66).strange(88).ground(30, 12).weight(8)
                .materials(MOSS, LUSH_GRASS, CRYSTAL, CRYSTAL, TRAIL_STONE)
                .sky(0x9E8BD0, 0xC3B0E0)
                .trees(0.34, AMETHYST, BIRCH)
                .grass(0.72, 0.18, 0.70)
                .forage(0.24, List.of("nightbell", "dewfruit"),
                        List.of("amethyst_seed", "grass_seed"))
                .rocks(0.005)
                .build());

        // --- and thirteen more ---------------------------------------------------

        out.add(WatchBiome.named("autumn_birchwood", "Autumn Birchwood")
                .blurb("Pale trunks in orange light; the whole wood moves when the wind does.")
                .climate(40, 52).ground(26, 9).weight(10)
                .materials(GRASS, DRY_GRASS, ROCK, SAND, TRAIL_EARTH)
                .sky(0x9CB8D6, 0xD9C6A8)
                .trees(0.32, BIRCH, ASPEN, MAPLE)
                .grass(0.66, 0.12, 0.45)
                .forage(0.22, List.of("thimbleberry", "elderberry"),
                        List.of("birch_seed", "grass_seed"))
                .rocks(0.002)
                .build());

        out.add(WatchBiome.named("boreal_taiga", "Boreal Taiga")
                .blurb("Snow between the spruces most of the year, and tracks in all of it.")
                .climate(16, 50).ground(30, 12).weight(6)
                .materials(SNOW, TUNDRA_GRASS, DARK_ROCK, GRAVEL, TRAIL_EARTH)
                .sky(0x9FBBD6, 0xD3DEE6)
                .trees(0.32, SPRUCE, FIR, PINE)
                .grass(0.30, 0.06, 0.24)
                .forage(0.12, List.of("cloudberry", "crowberry", "lingonberry"),
                        List.of("pine_seed", "sedge_seed"))
                .rocks(0.006)
                .build());

        out.add(WatchBiome.named("alpine_meadow", "Alpine Meadow")
                .blurb("A hanging garden above the treeline, loud with insects for six weeks a year.")
                .climate(26, 46).ground(68, 18).weight(7)
                .materials(LUSH_GRASS, GRASS, ROCK, GRAVEL, TRAIL_STONE)
                .sky(0x79A6D8, 0xC4D6E6)
                .trees(0.05, FIR, CEDAR)
                .grass(0.92, 0.22, 0.75)
                .forage(0.20, List.of("crowberry", "snowberry"),
                        List.of("lupine_seed", "grass_seed"))
                .rocks(0.01)
                .build());

        out.add(WatchBiome.named("savanna", "Acacia Savanna")
                .blurb("Grass to the horizon, one tree at a time, and everything visible for miles.")
                .climate(76, 26).ground(20, 8).weight(10)
                .materials(DRY_GRASS, GRASS, ROCK, SAND, TRAIL_EARTH)
                .sky(0x94B6D0, 0xDCCFA6)
                .trees(0.06, ACACIA, BAOBAB)
                .grass(0.88, 0.30, 1.00)
                .forage(0.10, List.of("baobab_fruit"),
                        List.of("millet", "grass_seed"))
                .rocks(0.003)
                .build());

        out.add(WatchBiome.named("wetland_marsh", "Reed Marsh")
                .blurb("Standing water, standing reeds, and a heron standing in both.")
                .climate(52, 88).ground(3, 3).weight(9)
                .materials(PEAT, MOSS, CLAY, CLAY, TRAIL_BOARDWALK)
                .sky(0x9AB4C0, 0xC2CDC4)
                .trees(0.08, WILLOW, ASPEN)
                .grass(0.90, 0.40, 1.35)
                .forage(0.24, List.of("salmonberry", "elderberry"),
                        List.of("wild_rice", "sedge_seed"))
                .rocks(0.0005)
                .build());

        out.add(WatchBiome.named("mangrove_coast", "Mangrove Coast")
                .blurb("Roots like scaffolding, fish in the shade of them, and crabs everywhere.")
                .climate(74, 84).ground(2, 3).weight(16)
                .materials(CLAY, PEAT, ROCK, SAND, TRAIL_BOARDWALK)
                .sky(0x8CBBCE, 0xC4D6D0)
                .trees(0.30, MANGROVE, PALM)
                .grass(0.55, 0.20, 0.70)
                .forage(0.22, List.of("mangrove_apple", "sea_grape"),
                        List.of("palm_seed", "wild_rice"))
                .rocks(0.001)
                .build());

        out.add(WatchBiome.named("redwood_cathedral", "Redwood Cathedral")
                .blurb("Trunks you cannot see the top of, and a hush that is not quite silence.")
                .climate(44, 76).ground(40, 16).weight(7)
                .materials(MOSS, LUSH_GRASS, DARK_ROCK, GRAVEL, TRAIL_EARTH)
                .sky(0x81A2B6, 0xA8BCB4)
                .trees(0.20, REDWOOD, CEDAR, FIR)
                .grass(0.48, 0.15, 0.55)
                .forage(0.18, List.of("salmonberry", "blackberry"),
                        List.of("redwood_cone", "grass_seed"))
                .rocks(0.004)
                .build());

        out.add(WatchBiome.named("canyon_badlands", "Canyon Badlands")
                .blurb("Banded walls, wind through the gaps, and swifts using the wind.")
                .climate(80, 16).ground(46, 30).weight(13)
                .materials(RED_SAND, CLAY, DARK_ROCK, SAND, TRAIL_SAND)
                .sky(0xA8B8CC, 0xDCB894)
                .trees(0.02, JOSHUA, SAGUARO)
                .grass(0.16, 0.06, 0.24)
                .forage(0.05, List.of("prickly_pear"),
                        List.of("cactus_seed", "thistle_seed"))
                .rocks(0.018)
                .build());

        out.add(WatchBiome.named("tundra_barrens", "Tundra Barrens")
                .blurb("Nothing taller than your boot, and every animal on it visible for a kilometre.")
                .climate(6, 30).ground(22, 10).weight(3)
                .materials(TUNDRA_GRASS, SNOW, DARK_ROCK, GRAVEL, TRAIL_EARTH)
                .sky(0xA6BED6, 0xD8E2E8)
                .trees(0.004, SPRUCE)
                .grass(0.60, 0.05, 0.18)
                .forage(0.14, List.of("cloudberry", "crowberry"),
                        List.of("sedge_seed", "lupine_seed"))
                .rocks(0.012)
                .build());

        out.add(WatchBiome.named("bamboo_thicket", "Bamboo Thicket")
                .blurb("Green corridors that creak overhead; you hear things long before you see them.")
                .climate(62, 74).ground(26, 8).weight(13)
                .materials(LUSH_GRASS, MOSS, ROCK, CLAY, TRAIL_EARTH)
                .sky(0x8DBBC8, 0xB6CFC0)
                .trees(0.70, TreeSpecies.BAMBOO)
                .grass(0.60, 0.20, 0.65)
                .forage(0.20, List.of("fig", "thimbleberry"),
                        List.of("bamboo_seed", "wild_rice"))
                .rocks(0.002)
                .build());

        out.add(WatchBiome.named("mushroom_hollow", "Mushroom Hollow")
                .blurb("A bowl of caps taller than you are, lit from underneath.")
                .climate(46, 82).strange(90).ground(14, 8).weight(8)
                .materials(MOSS, PEAT, DARK_ROCK, CLAY, TRAIL_EARTH)
                .sky(0x88849C, 0xA4A2B4)
                .trees(0.26, GLOWCAP, WILLOW)
                .grass(0.50, 0.12, 0.45)
                .forage(0.30, List.of("nightbell", "dewfruit", "salmonberry"),
                        List.of("spore_pod", "sedge_seed"))
                .rocks(0.004)
                .build());

        out.add(WatchBiome.named("sunflower_prairie", "Sunflower Prairie")
                .blurb("Head-high flowers all facing one way, and seed-eaters in every one of them.")
                .climate(58, 40).ground(18, 6).weight(8)
                .materials(GRASS, DRY_GRASS, ROCK, SAND, TRAIL_EARTH)
                .sky(0x8FC0E4, 0xDCD2A4)
                .trees(0.02, OAK, ASPEN)
                .grass(0.95, 0.35, 1.45)
                .forage(0.16, List.of("blackberry"),
                        List.of("sunflower_seed", "millet", "grass_seed"))
                .rocks(0.001)
                .build());

        out.add(WatchBiome.named("crystal_highlands", "Crystal Highlands")
                .blurb("Facets instead of foliage; the wind through them is nearly a chord.")
                .climate(22, 20).strange(86).ground(78, 34).weight(6)
                .materials(CRYSTAL, DARK_ROCK, DARK_ROCK, GRAVEL, TRAIL_STONE)
                .sky(0x8E9CD8, 0xC0C6E8)
                .trees(0.16, CRYSTAL_PINE)
                .grass(0.18, 0.05, 0.22)
                .forage(0.08, List.of("dewfruit", "snowberry"),
                        List.of("amethyst_seed", "lupine_seed"))
                .rocks(0.016)
                .build());

        return List.copyOf(out);
    }
}
