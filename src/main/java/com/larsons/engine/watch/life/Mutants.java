package com.larsons.engine.watch.life;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The three things in the wood that are looking for <em>you</em>.
 *
 * <h2>Why three, and why written out</h2>
 *
 * <p>Every other animal in this game is generated: {@link AnimalRegistry}
 * crosses twenty-six families with a hundred and eighty-two lineages and a pool
 * of epithets and gets thirteen hundred species out of it, and that is the right
 * way to fill a book nobody could write by hand.
 *
 * <p>It is the wrong way to make something frightening. A generated horror is a
 * <em>category</em> of horror, and the moment there are forty-nine wendigos with
 * rolled colours and rolled sizes, meeting one stops being an event and becomes
 * a weather condition. So there are three, they are written out below, and each
 * one has its own body plan, its own three biomes, its own hour and its own way
 * of killing you. A player who has met all three has met all there are, which is
 * exactly the property that makes the first meeting worth anything.
 *
 * <h2>The bargain the rarity makes</h2>
 *
 * <p>{@link Rarity#MYTHIC} is about a twentieth of a legendary. The point is not
 * that a mutant is hard to find — it is that the wood has to be <b>safe for
 * hours</b> before it stops being safe, or the game stops being about walking
 * around looking at birds and becomes about looking over your shoulder. Nothing
 * else here works if a player is never comfortable.
 *
 * <p>Three filters stack, and each of them is doing a different job:
 *
 * <ol>
 *   <li><b>The tier</b> — how often the species table offers one at all;</li>
 *   <li><b>The region</b> — three biomes each, and no two of the three share
 *       one, so a walk in the desert never meets any of them;</li>
 *   <li><b>The hour</b> — {@link AnimalDef#encounterWeight} returns zero for a
 *       hostile species outside its own hours rather than the reduced odds
 *       every ordinary animal keeps. A wendigo at noon is not unlikely; it is
 *       impossible.</li>
 * </ol>
 *
 * <p>And {@code WatchGame.populate} adds a fourth that is not about odds at
 * all: at most one is alive at a time, and it is put down further away than
 * anything else so that you see it before it sees you. Meeting one is meant to
 * be a thing you watch happening.
 *
 * <h2>What is worth writing down</h2>
 *
 * <p>Five hundred points, which is five legendaries, and it is deliberately the
 * best afternoon's work available. The verb of this game is to hold still and
 * look at something; a mutant is the hardest possible test of that verb,
 * because the thing you are holding still to look at is walking toward you.
 */
public final class Mutants {

    /**
     * How a mutant hunts — <b>the three of them differ here more than
     * anywhere.</b>
     *
     * <p>Three creatures with the same pursuit are one creature with three
     * skins. What actually has to differ is the shape of the encounter: how far
     * off it starts, what the player's options are once it has, and what
     * running away costs.
     */
    public enum Power {

        /**
         * <b>It has all night, and it does not need to reach you.</b> Notices
         * from an absurd distance and does not stop coming — but it is the one
         * of the three a sprint outpaces, and that is deliberate: outrunning it
         * is easy and does not help, because it throws things.
         *
         * <p>The counterplay is cover rather than the legs. Put a ridge, a
         * stand of trees or a very long walk between you and it — running in a
         * straight line across open ground is running in front of the thing
         * that is aiming at you.
         */
        STALK,

        /**
         * <b>It runs as fast as you do.</b> Holds a sprinting player exactly
         * level between bursts and gains on every one of them, so a straight
         * line away is a slow loss and there is no pace that wins.
         *
         * <p>The gaps between bursts are still the whole of the fight — they
         * are what a player uses to get behind something — but they buy cover
         * now rather than distance.
         */
        LUNGE,

        /**
         * <b>It was already there.</b> Lies still and low until somebody comes
         * inside its short notice range — it reads as scenery until then, and
         * the sound it makes when it stands up is the point of it.
         *
         * <p>Then it sprints, flat, with no bursts and no let-up, and hits
         * harder than either of the others. It is the plainest of the three and
         * the most frightening for it: no rhythm to read and no gap to use, only
         * the twenty-two metres you had before it stood up.
         */
        AMBUSH
    }

    /**
     * A mutant that throws something — <b>the wendigo, and nothing else.</b>
     *
     * <p>Its own record rather than five more fields on {@link Kind}, and
     * nullable there, because "does this creature have a ranged attack" is a
     * question with a yes-or-no answer and two of the three answer no. A
     * {@code null} here is the whole of what "melee only" means, and
     * {@link Animal} reads it as one branch.
     *
     * @param range        how far it can reach with one, in metres
     * @param minRange     inside this it closes and swings instead — a thing
     *                     with arms that long does not throw at arm's length
     * @param everySeconds how long between throws
     * @param damage       a share of a full health bar, on a hit
     * @param speed        how fast what it throws travels, in metres a second
     */
    public record Ranged(double range, double minRange, double everySeconds,
                         double damage, double speed) {}

    /**
     * One mutant's numbers: what it does to a player and how it gets close
     * enough to do it.
     *
     * <p>Beside the species rather than inside {@link AnimalDef}, because
     * thirteen hundred animals would otherwise carry a fistful of fields about
     * combat that only three of them use. {@link Animal} asks
     * {@link #of(AnimalDef)} once per tick for the one it is, and gets
     * {@code null} for everything that is not a mutant — which is the branch
     * nearly every animal takes.
     *
     * @param def            the species itself, as it appears in the book
     * @param power          how it hunts
     * @param damage         a share of a full health bar, per blow
     * @param reach          how close it has to be to land one, in metres
     * @param strikeSeconds  how long between blows
     * @param notice         how far off it picks a player up, in metres
     * @param lose           how far a player has to get for it to give up
     * @param chase          how much faster than its walk it pursues
     * @param height         how tall it stands, in metres — the model's scale
     * @param ranged         what it throws, or {@code null} for melee only
     * @param glow           what its lit parts burn, {@code 0xRRGGBB} — see
     *                       {@link AnimalSkins.Region#GLOW}
     */
    public record Kind(AnimalDef def, Power power, double damage, double reach,
                       double strikeSeconds, double notice, double lose, double chase,
                       double height, Ranged ranged, int glow) {

        /** The species key, which is what a save and the wire carry. */
        public String key() { return def.key(); }

        /** How many blows it takes to kill somebody from full health. */
        public int blowsToKill() { return (int) Math.ceil(1.0 / Math.max(1e-6, damage)); }

        /** Whether it can hurt somebody it is not standing next to. */
        public boolean hurls() { return ranged != null; }

        /**
         * How fast it pursues, in metres a second — its species' pace times
         * {@link #chase()}.
         *
         * <p>Worth having as a method because it is the number the design is
         * actually stated in: two of the three are meant to hold
         * {@link com.larsons.engine.watch.WatchPlayer#RUN_SPEED}, and reading
         * that off a multiplier and a base speed in two different files is how
         * a tuning pass silently stops meaning what it meant.
         */
        public double pursuitSpeed() { return def.speed() * chase; }

        /** What the guide's page says about the danger, in one line. */
        public String warning() {
            return switch (power) {
                case STALK -> "It does not lose interest, and it does not need to "
                        + "reach you to hurt you.";
                case LUNGE -> "It runs as fast as you can, and faster in bursts.";
                case AMBUSH -> "It lies still until you are close. Then it does not.";
            };
        }
    }

    /**
     * How much taller than a person the smallest of them is.
     *
     * <p>Not decoration: {@code WatchScene} has no way to say "this is
     * dangerous" that a player reads faster than a silhouette that does not fit
     * under a tree. A person's eye is at 1.68 m, so the shortest of these is two
     * and a half times their height and the tallest is well over three.
     */
    public static final double SHORTEST = 4.4;

    private static final List<Kind> ALL = build();

    private static final Map<String, Kind> BY_KEY = index();

    private Mutants() {}

    /** All three, in a stable order. */
    public static List<Kind> all() { return ALL; }

    /** How many mutants there are. Three. */
    public static int count() { return ALL.size(); }

    /** The three species, for the registry to append to the book. */
    public static List<AnimalDef> species() {
        return ALL.stream().map(Kind::def).toList();
    }

    /** The mutant with this species key, or {@code null} for anything else. */
    public static Kind of(String key) {
        return key == null ? null : BY_KEY.get(key);
    }

    /** The mutant a species is, or {@code null} — the question {@link Animal} asks. */
    public static Kind of(AnimalDef def) {
        return def == null ? null : of(def.key());
    }

    /** Whether a species hunts people. */
    public static boolean isMutant(AnimalDef def) {
        return def != null && def.family().hostile();
    }

    // --- the three ---------------------------------------------------------------------

    private static List<Kind> build() {
        return List.of(
                /*
                 * The Hollow Wendigo. Five and a half metres of frost-bleached
                 * bone in the taiga and the tundra, at night, and the only one
                 * of the three that will follow you off its own ground.
                 *
                 * Its damage is the lowest of the three and its patience is
                 * infinite, which is the trade: no single mistake kills you, and
                 * a hundred small ones do.
                 */
                new Kind(species(AnimalFamily.WENDIGO, "Hollow", 5.6,
                                0xB8C0BE, 0xE8F0F4, 0x5A6470, Activity.NOCTURNAL),
                        // 6.6 m/s: faster than a player's walk (4.4) and slower
                        // than their run (8.0) — the only one of the three a
                        // sprint outpaces, and the only one that does not need
                        // to catch you. Sprinting away from a wendigo works and
                        // does not help, because it throws.
                        //
                        // Three seconds between blows, which is the slowest
                        // melee in the game. A creature this fast has to be
                        // survivable once it arrives, and the way to make being
                        // caught survivable rather than fatal is to give the
                        // player whole seconds between blows to get out again.
                        Power.STALK, 0.17, 3.4, 3.0, 78, 190, 1.95, 5.6,
                        // Forty-five metres, no closer than six, one every three
                        // and a half seconds. The minimum matters as much as the
                        // maximum: a thing with arms that long does not throw at
                        // arm's length, so closing to six metres swaps the
                        // ranged attack for the melee one and there is never a
                        // moment where it can do both.
                        new Ranged(45, 6, 3.5, 0.11, 24),
                        // The fire in its open chest, and the same red in its
                        // eyes. Fully saturated and at full brightness on
                        // purpose: the hour's light multiplies it down like
                        // everything else, so all it can ever be is the
                        // brightest thing on a very dark animal.
                        0xFF2E14),

                /*
                 * The Moonfell Werewolf. Four and a half metres of shoulder and
                 * jaw in the temperate woods, at dawn and dusk — the two hours
                 * an ordinary walk is most likely to be out in, which is the
                 * point of putting it there.
                 *
                 * The fastest thing in the game while it is bursting and an
                 * ordinary walking pace between bursts.
                 */
                new Kind(species(AnimalFamily.WEREWOLF, "Moonfell", 4.4,
                                0x5A4C42, 0x7A6650,
                                // Dark, and no darker: AnimalRegistryTest holds
                                // every species' three colours above a
                                // brightness floor, and it is right to. An
                                // animal nobody can make out at forty metres in
                                // the shade is an animal that is not in the
                                // game, and a werewolf's claws and jaw are the
                                // parts a player needs to read.
                                0x332C27, Activity.CREPUSCULAR),
                        // <b>It runs exactly as fast as you can.</b> 3.25 × its
                        // walk is 11.1 m/s in a burst and, through
                        // Animal.LUNGE_REST, 8.0 m/s between them — which is
                        // WatchPlayer.RUN_SPEED to two decimal places. A player
                        // at a flat sprint holds it exactly level and loses
                        // ground on every burst, so the chase is decided by the
                        // ground rather than by the legs: what saves you is a
                        // ridge, a lake, or a stand of trees.
                        //
                        // 2.4 s between blows, up from 1.5. Something that can
                        // hold a sprinting player's pace cannot also swing
                        // quickly, or being caught once is being dead.
                        Power.LUNGE, 0.24, 3.0, 2.4, 70, 120, 3.25, 4.4, null,
                        // Eyes only. A deeper red than the wendigo's, so that a
                        // party who has met both can tell at a glance which pair
                        // of lights is in the treeline.
                        0xE81A10),

                /*
                 * The Drowned Mirewraith. Five metres of bloated silhouette in
                 * the standing water, at night, and it does not come to you —
                 * you walk into it.
                 *
                 * Hits hardest by a long way and is the slowest thing here.
                 * Three blows and you are down, and the answer is simply to be
                 * further away than its arms are long.
                 */
                /*
                 * The pale third colour is doing real work and is not a taste
                 * decision. It is the LIMB and HARD colour — see
                 * AnimalSkins.paintInto — and the first version had it at
                 * 0x1E2A26, near black, on a body that is itself dark green. At
                 * any distance, in the dark, in the biomes this thing lives in,
                 * every one of its four arms disappeared into its own trunk and
                 * the silhouette was a slab. Pale, bloated limbs against a drowned
                 * green body is both what the creature is and the only way its
                 * four arms are visible as four arms.
                 */
                new Kind(species(AnimalFamily.MIREWRAITH, "Drowned", 5.0,
                                0x4E6258, 0x9CC4A6, 0x9AA894, Activity.NOCTURNAL),
                        // Forty metres of notice, which {@link Animal} then cuts
                        // to twenty-two before it will stand up. Twenty-two is
                        // "you are about to walk into it" rather than "it saw
                        // you across the fen", and it is the shortest reaction
                        // window in the game.
                        //
                        // And then 8.0 m/s flat — a dead sprint, held, with no
                        // bursts and no let-up. It is the plainest of the three
                        // and the most frightening for it: there is no rhythm to
                        // read and no gap to use, only the twenty-two metres you
                        // had before it stood up. 3.6 s between blows is the
                        // slowest swing in the game, and it has to be, because
                        // it is also the hardest.
                        Power.AMBUSH, 0.34, 3.8, 3.6, 40, 70, 2.35, 5.0, null,
                        // Not red. The mirewraith's light is the drowned,
                        // lantern-green of the water it stands in — the two red
                        // pairs are the wendigo and the werewolf, and a third
                        // would make the colour stop meaning anything.
                        0x9EF0B4));
    }

    /**
     * One mutant's row in the book.
     *
     * <p>Written out rather than rolled, but assembled through the same record
     * every generated species uses, so the guide, the wire, the skin painter and
     * the model loader cannot tell the difference. A mutant is a page like any
     * other page — which is the whole reason it is worth walking up to one.
     *
     * <p>{@code bodyLength} carries its <em>height</em>. Every plan in
     * {@link AnimalModel} is written in fractions of that number and these three
     * are the only ones that stand upright, so for a biped the nose-to-tail
     * measurement a quadruped means by it is the distance from its feet to the
     * top of its head. See {@code AnimalModel.wendigo}.
     */
    private static AnimalDef species(AnimalFamily family, String epithet, double height,
                                     int body, int accent, int detail, Activity activity) {
        String lineage = family.lineages().get(0);
        String key = family.key() + "_" + lineage.toLowerCase() + "_"
                + epithet.toLowerCase();
        return new AnimalDef(key, epithet + " " + lineage, family, lineage, epithet,
                height, body, accent, detail, Diet.CARRION, activity, Rarity.MYTHIC,
                family.biomes(), false, 0.0,
                // Its ordinary pace. A mutant that is not hunting anything walks
                // its ground at about what a person walks at, so the first thing
                // you see it do is unhurried — and `chase` is what happens after
                // it has seen you.
                3.4, 0, 0.04);
    }

    private static Map<String, Kind> index() {
        Map<String, Kind> map = new LinkedHashMap<>();
        for (Kind kind : ALL) map.put(kind.key(), kind);
        return Map.copyOf(map);
    }
}
