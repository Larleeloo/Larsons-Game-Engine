package com.larsons.engine.watch.life;

import com.larsons.engine.watch.WatchClock;

import java.util.List;

/**
 * One species — a row in the field guide.
 *
 * <p>Immutable, deterministic, and generated: see {@link AnimalRegistry} for
 * how a family, a lineage and an epithet become one of these, and why there
 * are more than a thousand of them rather than a hand-written hundred.
 *
 * @param key         stable identifier, {@code family_lineage_epithet}
 * @param name        what a player sees — <em>Banded Finch</em>
 * @param family      the body plan and the behaviour it inherits
 * @param lineage     the noun in its name; unique across the whole registry
 * @param epithet     the adjective in its name
 * @param bodyLength  nose to tail in metres, what the model is scaled to
 * @param body        the main colour, {@code 0xRRGGBB}
 * @param accent      the second colour — a wing bar, a belly, a flank
 * @param detail      the third — eyes, beak, feet, horns
 * @param diet        what it eats, and so what lure brings it in
 * @param activity    when it is awake
 * @param rarity      how often the world offers it, and how warily it behaves
 * @param biomes      the biome keys it lives in; never empty
 * @param tameable    whether patient feeding can make it a pet
 * @param wariness    how close you can get, 0 (indifferent) – 1 (hopeless)
 * @param speed       metres per second at a normal pace
 * @param perch       how far off the ground it likes to be, in metres
 * @param voice       where its call sits, 0 (a boom) – 1 (a needle)
 */
public record AnimalDef(String key, String name, AnimalFamily family, String lineage,
                        String epithet, double bodyLength, int body, int accent, int detail,
                        Diet diet, Activity activity, Rarity rarity, List<String> biomes,
                        boolean tameable, double wariness, double speed, double perch,
                        double voice) {

    /** Whether this species lives in a biome. */
    public boolean livesIn(String biomeKey) {
        return biomes.contains(biomeKey);
    }

    /**
     * How likely this species is to be the one a patch of ground offers: its
     * rarity, scaled by how awake it is at this hour.
     *
     * <p>Both halves matter. Rarity alone would put the same animals in front
     * of you at four in the morning as at noon, and the hour alone would make
     * a legendary as easy to find as a sparrow at the right time.
     *
     * <p><b>A mutant is the one thing here with a hard edge on its hour.</b>
     * {@link Activity#activityAt} never quite returns zero, on purpose — a
     * nocturnal warbler disturbed at noon does move, and a species that were
     * literally unfindable outside four hours would be a page nobody finishes.
     * Neither argument survives contact with something that hunts you: "the
     * taiga is safe until dark" has to be true, not nearly true, or a player
     * cannot plan a day around it. So a hostile species outside its own hours
     * is not offered at all. See {@link Mutants}.
     */
    public double encounterWeight(WatchClock.Phase phase) {
        if (hostile() && !activity.awakeAt(phase)) return 0;
        return rarity.frequency() * activity.activityAt(phase);
    }

    /**
     * Whether this species hunts people rather than fleeing them.
     *
     * <p>True for exactly the three in {@link Mutants}. Everything that behaves
     * differently about them is behind this one question — see
     * {@link AnimalFamily#hostile()}.
     */
    public boolean hostile() { return family.hostile(); }

    /** How far away this species notices a player who is moving normally. */
    public double flushDistance() {
        return 6 + wariness * 46;
    }

    /** Whether this species leaves the ground. */
    public boolean airborne() { return family.motion().airborne(); }

    /** Whether it lives in the water. */
    public boolean aquatic() { return family.motion() == AnimalFamily.Motion.SWIM; }

    /**
     * The texture key a pack reskins this one species under. Falls back to the
     * family's key, so one file can redress twenty-six families or one file can
     * redress a single bird.
     */
    public String textureKey() { return "watch/animal/" + key; }

    /** A one-line description for the guide's page. */
    public String blurb() {
        // A mutant's page does not say what it eats. Everybody knows.
        if (hostile()) {
            return rarity.label() + " · " + family.plural() + " · abroad "
                    + activity.phrase() + " · dangerous";
        }
        return rarity.label() + " · " + family.plural() + " · seen " + activity.phrase()
                + " · eats " + diet.label().toLowerCase();
    }

    /** Where to look, as a sentence. */
    public String whereToLook() {
        if (biomes.isEmpty()) return "Anywhere.";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < biomes.size(); i++) {
            if (i > 0) sb.append(i == biomes.size() - 1 ? " and " : ", ");
            var biome = com.larsons.engine.watch.world.WatchBiomes.byKey(biomes.get(i));
            sb.append(biome != null ? biome.displayName() : biomes.get(i));
        }
        return sb.toString();
    }

    @Override public String toString() { return name; }
}
