package com.larsons.engine.watch;

import com.larsons.engine.watch.life.Animal;
import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.AnimalRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Eye Spy: the bounty board, and the four rules that keep it worth reading.
 *
 * <ol>
 *   <li><b>The world prices it, not the poster.</b> Somewhere between ten and a
 *       hundred points, rolled by the host — a reward somebody sets themselves is
 *       a reward they set to a hundred every time.</li>
 *   <li><b>One a day, per walker</b>, on the same real clock everything else in
 *       this game runs on.</li>
 *   <li><b>Whoever asked cannot answer.</b> The one rule the whole board rests
 *       on: a bounty settles for anybody but the person who pinned it up.</li>
 *   <li><b>It is a promise, so it is kept.</b> Saved with the world, unlike the
 *       tag round beside it — including the day limit, which would otherwise be
 *       reset by closing the game.</li>
 * </ol>
 */
@Timeout(180)
class BountyTest {

    private static final long NOON = 1_756_000_000_000L;

    private static final long A_DAY = 24 * 3600_000L;

    /** A walk with room for a party. */
    private static WatchGame party() {
        return new WatchGame(WatchGame.Config.hosted("Eye Spy", 8675309L));
    }

    /** A species that is definitely in the registry, by position rather than by name. */
    private static AnimalDef species(int at) {
        return AnimalRegistry.all().get(at % AnimalRegistry.count());
    }

    // --- the price ---------------------------------------------------------------------

    /**
     * Flat, and inside the band the brief names, and genuinely varying.
     *
     * <p>The last of those three is the one worth asserting: a roll that always
     * came back with the same number would satisfy the other two and would make
     * the whole board a fixed price list.
     */
    @Test
    void aBountyIsWorthBetweenTenAndAHundred() {
        Random rng = new Random(4);
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            int points = Bounty.roll(rng);
            assertTrue(points >= Bounty.LEAST && points <= Bounty.MOST,
                    "a bounty was rolled at " + points);
            seen.add(points);
        }
        assertTrue(seen.size() > 40, "the roll barely varies: " + seen.size() + " values");
        assertTrue(seen.contains(Bounty.LEAST) || seen.contains(Bounty.LEAST + 1),
                "the bottom of the band is never reached");
    }

    // --- one a day ---------------------------------------------------------------------

    @Test
    void aWalkerMayPinUpOneBountyADay() {
        Bounty board = new Bounty();
        assertFalse(board.postedToday("Kara", NOON));

        assertNotNull(board.post(species(3).key(), 40, "Kara", NOON),
                "the first bounty of the day was refused");
        assertTrue(board.postedToday("Kara", NOON));
        assertNull(board.post(species(9).key(), 40, "Kara", NOON + 3600_000L),
                "a second bounty went up on the same day");

        // Somebody else's day is their own.
        assertNotNull(board.post(species(9).key(), 40, "Larson", NOON),
                "one walker's posting used up another's");

        // …and tomorrow is tomorrow.
        assertNotNull(board.post(species(11).key(), 40, "Kara", NOON + A_DAY),
                "the allowance never came round again");
    }

    @Test
    void theBoardWillNotCarryTwoBountiesOnOneAnimal() {
        Bounty board = new Bounty();
        String key = species(21).key();
        assertNotNull(board.post(key, 30, "Kara", NOON));
        assertTrue(board.open(key));
        assertNull(board.post(key, 90, "Larson", NOON),
                "the same animal was asked for twice at once");

        // Once it has been found, it can be asked for again.
        assertNotNull(board.claim(key, "Larson", NOON + 60_000));
        assertFalse(board.open(key));
        assertNotNull(board.post(key, 90, "Larson", NOON),
                "a settled bounty still blocks its own species");
    }

    // --- claiming ----------------------------------------------------------------------

    @Test
    void whoeverAskedForItCannotBeTheOneWhoFindsIt() {
        Bounty board = new Bounty();
        String key = species(31).key();
        board.post(key, 70, "Kara", NOON);

        assertNull(board.claim(key, "Kara", NOON + 1000),
                "somebody claimed their own bounty");
        assertTrue(board.open(key), "their own claim took it off the board anyway");

        Bounty.Posting claimed = board.claim(key, "Larson", NOON + 2000);
        assertNotNull(claimed, "nobody else could claim it either");
        assertEquals("Larson", claimed.finder());
        assertEquals(70, claimed.points());
        assertFalse(claimed.open());
    }

    @Test
    void anUnclaimedBountyComesDownAfterADay() {
        Bounty board = new Bounty();
        String key = species(41).key();
        board.post(key, 55, "Kara", NOON);

        assertTrue(board.expire(NOON + A_DAY / 2).isEmpty(), "it came down too early");
        assertEquals(1, board.openCount());

        List<Bounty.Posting> gone = board.expire(NOON + A_DAY + 60_000);
        assertEquals(1, gone.size(), "it never came down");
        assertEquals(0, board.openCount());
    }

    // --- in the world ------------------------------------------------------------------

    /**
     * The whole loop through the simulation: one walker asks for an animal,
     * another sees one, and the party's balance moves.
     *
     * <p>The animal is summoned rather than waited for. Everything about a
     * bounty being claimed is in {@code WatchGame.spot}, and a test that walked
     * about hoping the right species turned up would be a test that failed by
     * seed.
     */
    @Test
    void spottingAnAnimalSomebodyAskedForPaysTheParty() {
        WatchGame game = party();
        game.join(1, "Larson");
        game.join(2, "Kara");
        game.debug(1, Debug.CODE);

        AnimalDef quarry = species(57);
        String said = game.postBounty(2, quarry.key());
        assertNotNull(said, "the bounty was not pinned up: " + said);
        Bounty.Posting posting = game.bounties().open().get(0);
        assertEquals("Kara", posting.poster());
        assertTrue(posting.points() >= Bounty.LEAST && posting.points() <= Bounty.MOST);

        int before = game.guide().points();
        Animal beast = game.summon(1, quarry.key());
        assertNotNull(beast, "the animal was never put down");
        assertNotNull(game.spot(1, beast.id()), "the sighting was refused");

        assertEquals(0, game.bounties().openCount(), "the bounty is still on the board");
        Bounty.Posting settled = game.bounties().settled().get(0);
        assertEquals("Larson", settled.finder());
        assertTrue(game.guide().points() >= before + posting.points(),
                "the party was not paid: " + before + " → " + game.guide().points());
    }

    /**
     * …and the poster spotting it themselves pays nobody, and leaves the bounty
     * up for somebody who can claim it.
     */
    @Test
    void thePosterSpottingItThemselvesChangesNothing() {
        WatchGame game = party();
        game.join(1, "Larson");
        game.join(2, "Kara");
        game.debug(1, Debug.CODE);

        AnimalDef quarry = species(73);
        game.postBounty(1, quarry.key());

        Animal beast = game.summon(1, quarry.key());
        game.spot(1, beast.id());

        assertEquals(1, game.bounties().openCount(),
                "the poster took their own bounty off the board");
        assertTrue(game.bounties().settled().isEmpty(), "the poster paid themselves");

        // …and it is still there for somebody who can have it.
        Animal second = game.summon(1, quarry.key());
        assertNotNull(game.spot(2, second.id()));
        assertEquals(0, game.bounties().openCount(),
                "the bounty was not still there for the other walker");
    }

    @Test
    void aRefusedPostingSaysWhyRatherThanFailingSilently() {
        WatchGame game = party();
        game.join(1, "Larson");
        game.join(2, "Kara");

        assertNotNull(game.postBounty(1, species(5).key()));
        String second = game.postBounty(1, species(6).key());
        assertNotNull(second, "a refused posting said nothing at all");
        assertTrue(second.toLowerCase().contains("one bounty a day"),
                "the refusal does not explain itself: " + second);
        assertEquals(1, game.bounties().openCount(), "the second one went up anyway");

        assertNull(game.postBounty(1, "no_such_animal"),
                "a bounty went up on an animal that does not exist");
    }

    /**
     * A walk with one seat in it cannot pin one up at all, because the one
     * walker on it is the one walker who may not claim it. A <em>hosted</em>
     * walk somebody happens to be alone on this afternoon is a different thing,
     * and can.
     */
    @Test
    void aWalkForOneCannotPinUpABountyNobodyCouldClaim() {
        WatchGame alone = new WatchGame(WatchGame.Config.solo("Alone"));
        alone.join(1, "Larson");
        String said = alone.postBounty(1, species(17).key());
        assertNotNull(said, "the refusal said nothing at all");
        assertEquals(0, alone.bounties().openCount(),
                "a bounty went up that could never be claimed");

        WatchGame hosted = party();
        hosted.join(1, "Larson");
        assertNotNull(hosted.postBounty(1, species(17).key()));
        assertEquals(1, hosted.bounties().openCount(),
                "a hosted walk was refused because nobody had arrived yet");
    }

    // --- what a screen offers ----------------------------------------------------------

    /**
     * The shortlist is the biome's own, with what is already in the book first —
     * and never a species the board is already carrying, because posting that
     * would be refused and a menu whose rows are refused is a menu that lies.
     */
    @Test
    void theBoardOffersWhatLivesWhereYouAreStanding() {
        WatchGame game = party();
        game.join(1, "Larson");
        List<AnimalDef> offered = game.bountyChoices(1);
        assertFalse(offered.isEmpty(), "there is nothing at all to ask for");
        assertTrue(offered.size() <= Bounty.CHOICES,
                "the shortlist is not short: " + offered.size());

        String first = offered.get(0).key();
        game.postBounty(1, first);
        List<AnimalDef> after = game.bountyChoices(1);
        for (AnimalDef def : after) {
            assertFalse(def.key().equals(first),
                    "the board still offers something it is already carrying");
        }
    }

    @Test
    void whatIsAlreadyInTheBookIsOfferedFirst() {
        WatchGame game = party();
        game.join(1, "Larson");
        List<AnimalDef> before = game.bountyChoices(1);
        // Write the last row of the shortlist into the book, and it should come
        // to the front: a bounty on something somebody can describe is a bounty
        // somebody can go and look for.
        AnimalDef known = before.get(before.size() - 1);
        game.guide().record(new Sighting(known.key(), NOON, 0.4, "pine_forest",
                "Larson", 0, 0, true));

        List<AnimalDef> after = game.bountyChoices(1);
        assertEquals(known.key(), after.get(0).key(),
                "a species in the book was not offered first");
    }

    // --- kept ---------------------------------------------------------------------------

    @Test
    void theBoardAndTheDayLimitBothSurviveASave() {
        WatchGame game = party();
        game.join(1, "Larson");
        game.postBounty(1, species(13).key());
        assertEquals(1, game.bounties().openCount());
        Map<String, Object> saved = game.toMap();

        WatchGame reopened = party();
        reopened.load(saved);
        reopened.join(1, "Larson");
        assertEquals(1, reopened.bounties().openCount(),
                "the board was empty when the walk was reopened");
        assertEquals(game.bounties().open().get(0).species(),
                reopened.bounties().open().get(0).species());
        assertNotNull(reopened.postBounty(1, species(14).key()));
        assertEquals(1, reopened.bounties().openCount(),
                "closing the game gave Larson a second bounty for the day");
    }

    @Test
    void aBoardSurvivesTheWireExactly() {
        Bounty board = new Bounty();
        board.post(species(2).key(), 45, "Kara", NOON);
        board.post(species(4).key(), 80, "Larson", NOON);
        board.claim(species(4).key(), "Kara", NOON + 5000);

        Bounty copy = new Bounty();
        copy.load(board.toMap());
        assertEquals(1, copy.openCount());
        assertEquals(1, copy.settled().size());
        assertEquals("Kara", copy.settled().get(0).finder());
        assertTrue(copy.postedToday("Kara", NOON), "the day limit did not travel");
        // A fresh posting must not collide with an id the board already has.
        Bounty.Posting fresh = copy.post(species(6).key(), 20, "Bram", NOON);
        assertNotNull(fresh);
        assertEquals(fresh.id(), copy.byId(fresh.id()).id());
        assertEquals(2, copy.openCount());
    }
}
