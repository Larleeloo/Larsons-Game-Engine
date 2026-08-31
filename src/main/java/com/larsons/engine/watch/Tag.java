package com.larsons.engine.watch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A game of tag, played inside a game about watching birds.
 *
 * <h2>Why it has to be voted for</h2>
 *
 * <p>Everything else in this world can be done by one person without asking:
 * you pick a berry, you plant a seed, you point at a finch. Tag cannot. It takes
 * the whole party's afternoon and turns it into something else — the one verb
 * here that <em>imposes</em> on everybody — and a walk where any one of eight
 * people can start a chase whenever they feel like it is a walk where nobody can
 * stand still long enough to see anything.
 *
 * <p>So a suggestion opens a <b>poll</b>, and the round only begins if a strict
 * majority of the people actually on the walk say yes. Not voting is a no; that
 * is deliberate, and it is the whole reason the bar is a majority of the
 * <em>party</em> rather than of the votes cast. Somebody who has gone to make tea
 * does not get counted as agreeing to be chased.
 *
 * <p>The same poll ends it. Pressing the key while a round is running suggests
 * calling it off, which goes to the party on identical terms — one verb, one
 * mechanism, and no way for whoever is losing to stop the game on their own.
 *
 * <h2>The three things that make it a game rather than a chase</h2>
 *
 * <ul>
 *   <li><b>{@value #IT_SPEED}× speed.</b> A walker who is it moves faster than
 *       everybody else, because a chase between two people with identical top
 *       speeds never ends.</li>
 *   <li><b>{@value #FREEZE_SECONDS} seconds of standing still</b>, every time
 *       somebody becomes it — at the start of the round as well as on every tag
 *       after it. That is the count of the playground game and it does the same
 *       two jobs here: it scatters the field, and it makes tagging back the
 *       person who just tagged you impossible.</li>
 *   <li><b>A water gun.</b> Tagging is done at range, with a jet, rather than by
 *       walking into somebody — see {@link com.larsons.engine.watch.life.Hurl},
 *       which is what actually flies. A contact tag in a game whose positions are
 *       only as synchronised as the last snapshot would be a tag that landed on
 *       one screen and missed on another.</li>
 * </ul>
 *
 * <p>The <b>compass</b> the brief asks for is not here, and deliberately: every
 * walker's position is already in every snapshot, so the needle that points at
 * the nearest one is a thing each screen works out for itself. Nothing about it
 * needs to travel, and nothing about it can be got wrong by one machine and not
 * another. See {@code WatchScene.drawQuarry}.
 *
 * <h2>A round and a poll are two different things</h2>
 *
 * <p>They are two booleans rather than one state, because they genuinely
 * overlap: a call-off poll is open <em>while the round is still on</em>, and it
 * has to be, or suggesting an end to the game would pause it for half a minute
 * and hand whoever is it their thirty seconds back.
 *
 * <h2>It is weather</h2>
 *
 * <p>Not saved, for the reason the animals are not saved: a poll that was open
 * when somebody closed the game is not a poll anybody is still thinking about,
 * and a walk reopened three days later with one player mysteriously unable to
 * move for half a minute is a bug however faithfully it restores what was
 * happening. A round lives as long as the session that started it. What
 * <em>is</em> kept is on {@link Bounty}, which is a promise rather than a game in
 * progress.
 */
public final class Tag {

    /** How much faster whoever is it moves than everybody else. */
    public static final double IT_SPEED = 1.3;

    /**
     * How long somebody who has just become it stands still, in seconds.
     *
     * <p>Thirty, as asked, and it is the number the whole thing balances on: a
     * chase at {@value #IT_SPEED}× against a field that has had half a minute to
     * scatter is a game, and the same chase against a field standing where it was
     * tagged is a formality.
     */
    public static final double FREEZE_SECONDS = 30;

    /**
     * How long a suggestion stays open for, in seconds.
     *
     * <p>Long enough to notice one while looking the other way through a
     * spyglass; short enough that a walk is not held up by somebody who has
     * wandered off. The poll closes early the moment everybody has answered, so a
     * party that is paying attention never waits the full half minute.
     */
    public static final double VOTE_SECONDS = 30;

    /** How few people can play. Tag with one person is not a thing. */
    public static final int LEAST_PLAYERS = 2;

    /**
     * What whoever is it is handed, and has taken off them again.
     *
     * <p>An actual item in an actual satchel rather than a flag on the round, so
     * that the hands hold it, the bag lists it and somebody across the clearing
     * can see what is coming. Whether a shot is allowed is still decided by
     * {@link #isIt}, not by what is in the bag — a gun dropped by dying must not
     * be a way to keep tagging people.
     */
    public static final String GUN = "water_gun";

    /**
     * What a jet of water is called on the wire.
     *
     * <p>It travels as an ordinary {@code Hurl} — the one other thing in this
     * game that flies — and this is the species key that tells the host and every
     * client that this particular one is water rather than bone. See
     * {@code WatchGame.flyHurls}, which tags on it instead of wounding.
     */
    public static final String JET = "water_jet";

    /**
     * How fast a jet leaves the barrel, in metres a second.
     *
     * <p>Fast enough to be a gesture rather than a lob, and slow enough to be
     * dodged: under {@code Hurl}'s quarter gravity a flat shot from
     * {@value #BARREL_Z} m up is on the ground about twenty metres out, which is
     * the range this is really setting. A water gun that reached across a valley
     * would make the freeze the only mechanic in the game.
     */
    public static final double JET_SPEED = 21;

    /**
     * How far above the walker's feet the barrel is, in metres.
     *
     * <p>Chest height rather than eye height: a jet that started at the eye would
     * leave from inside the shooter's own head on everybody else's screen.
     */
    public static final double BARREL_Z = 1.35;

    /** …and how far in front of them, so it does not start inside their chest. */
    public static final double BARREL_AHEAD = 0.55;

    /** How long between shots, in seconds. */
    public static final double RELOAD_SECONDS = 0.55;

    /** How a poll ended, and what it did. */
    public enum Outcome {
        /** It did not end; it is still open, or there was not one. */
        NOTHING,
        /** The party said yes and a round has begun. */
        STARTED,
        /** The party said yes and the round is over. */
        STOPPED,
        /** The party said no, or did not say anything in time. */
        REFUSED
    }

    /**
     * What one tick changed.
     *
     * <p>Two fields rather than one enum because both can happen on the same tick
     * and neither may be dropped. A call-off suggested the instant after a tag
     * runs its poll on the same clock as that tag's freeze, so "the party said no"
     * and "they can move again" landing together is the ordinary case rather than
     * a corner of one.
     *
     * @param poll   how a poll ended on this tick, if one did
     * @param thawed whether the freeze ran out on this tick
     */
    public record Tick(Outcome poll, boolean thawed) {

        static final Tick QUIET = new Tick(Outcome.NOTHING, false);

        /** Whether anything at all happened. */
        public boolean quiet() { return poll == Outcome.NOTHING && !thawed; }
    }

    // --- the poll ---------------------------------------------------------------------

    private boolean polling;
    private int proposerId;
    private String proposer = "";

    /** Whether the open poll is to start a round or to call one off. */
    private boolean toStart = true;

    private double voteRemaining;

    /** How everybody answered. Insertion-ordered so the card reads in order. */
    private final Map<Integer, Boolean> votes = new LinkedHashMap<>();

    /** How many people the poll was put to, which is the bar it has to clear. */
    private int electorate;

    // --- the round --------------------------------------------------------------------

    private boolean running;
    private int itId;
    private String it = "";
    private double freeze;

    /** How long the round has been going, in seconds. */
    private double elapsed;

    /** How many people each walker has tagged, by name — the scoreboard. */
    private final Map<String, Integer> tags = new LinkedHashMap<>();

    /** How long until each walker's gun will fire again, in seconds. */
    private final Map<Integer, Double> reload = new LinkedHashMap<>();

    /** Whether a round is on. */
    public boolean running() { return running; }

    /** Whether the party is being asked something. */
    public boolean polling() { return polling; }

    /** Whether the open poll would start a round rather than end one. */
    public boolean toStart() { return toStart; }

    /** Who asked. */
    public String proposer() { return proposer; }

    public int proposerId() { return proposerId; }

    /** How long the poll has left, in seconds. */
    public double voteRemaining() { return Math.max(0, voteRemaining); }

    /** How many said yes. */
    public int yes() { return count(true); }

    /** …and no. */
    public int no() { return count(false); }

    /** How many people the poll was put to. */
    public int electorate() { return electorate; }

    /** Whether one player has already answered. */
    public boolean voted(int playerId) { return votes.containsKey(playerId); }

    /** Whoever is it, or {@code 0}. */
    public int itId() { return itId; }

    /** Their name, or {@code ""}. */
    public String it() { return it; }

    /** Whether a player is it. */
    public boolean isIt(int playerId) { return running && playerId == itId; }

    /** How long whoever is it must stand still for, in seconds. */
    public double freeze() { return Math.max(0, freeze); }

    /** Whether a player may not move — which is only ever whoever is it. */
    public boolean frozen(int playerId) { return isIt(playerId) && freeze > 0; }

    /** How long the round has been going, in seconds. */
    public double elapsed() { return elapsed; }

    /** How many people a walker has tagged this round. */
    public int tagsBy(String name) { return tags.getOrDefault(name, 0); }

    /** The scoreboard, in the order people first tagged somebody. */
    public Map<String, Integer> scoreboard() { return Map.copyOf(tags); }

    /**
     * How fast a walker moves, as a multiple of their ordinary pace.
     *
     * <p>Nothing while frozen — which is what makes the freeze a freeze rather
     * than a suggestion — {@value #IT_SPEED} while it, and one for everybody
     * else. Read by the screen to scale the local walk; the host does not consult
     * it, because a client is the thing that decides where it is standing. What
     * the host does enforce is the <em>freeze</em>, by refusing to take a new
     * position from somebody who is in one. See {@code WatchGame.move}.
     */
    public double speed(int playerId) {
        if (!isIt(playerId)) return 1;
        return freeze > 0 ? 0 : IT_SPEED;
    }

    // --- the poll ---------------------------------------------------------------------

    /**
     * Put a suggestion to the party.
     *
     * <p>Refused while one is already open — a second suggestion during a poll is
     * somebody pressing the key twice — and refused outright on a walk with
     * nobody else on it, because the vote would pass unanimously and the chase
     * would be one person running away from themselves.
     *
     * @param party how many people are on the walk
     * @return whether the poll opened
     */
    public boolean suggest(int playerId, String name, int party) {
        if (polling || party < LEAST_PLAYERS) return false;
        toStart = !running;
        polling = true;
        proposerId = playerId;
        proposer = name == null ? "" : name;
        electorate = party;
        voteRemaining = VOTE_SECONDS;
        votes.clear();
        // Suggesting it is voting for it. Anything else would mean the person who
        // wants the game has to press two keys to want it.
        votes.put(playerId, true);
        return true;
    }

    /**
     * One answer.
     *
     * <p>A vote can be changed while the poll is open — there is no reason to make
     * somebody live with a misread key for half a minute — and the poll is closed
     * by {@link #tick} rather than here, so a walk where everybody answers at once
     * and a walk where one person times out take the identical path through the
     * identical code.
     *
     * @return whether the answer was taken
     */
    public boolean vote(int playerId, boolean yes) {
        if (!polling) return false;
        votes.put(playerId, yes);
        return true;
    }

    /**
     * Whether the poll would pass if it closed now.
     *
     * <p><b>A strict majority of the party, not of the votes cast.</b> An
     * abstention is a no, which is the rule that stops a game of tag being started
     * by two people out of eight while the other six are looking through
     * spyglasses.
     */
    public boolean carries() {
        return yes() * 2 > Math.max(electorate, LEAST_PLAYERS);
    }

    /**
     * Whether the poll has finished being interesting.
     *
     * <p>Three ways, and only the first is the obvious one: everybody has
     * answered, or enough people have said yes that the rest cannot take it
     * away, or enough have not that the rest cannot bring it back. A poll whose
     * answer is already decided is a poll that is only costing the party the
     * remainder of half a minute — and in a game where the question is often
     * "shall we stop running", half a minute is the whole of what is being asked
     * about.
     */
    public boolean settled() {
        if (!polling) return false;
        if (votes.size() >= electorate || carries()) return true;
        int unanswered = Math.max(0, electorate - votes.size());
        return (yes() + unanswered) * 2 <= Math.max(electorate, LEAST_PLAYERS);
    }

    // --- the round --------------------------------------------------------------------

    /**
     * Start a round with somebody it.
     *
     * <p>They freeze immediately, like anybody who has just been tagged: the count
     * of thirty at the start of a game is what gives everybody else somewhere to
     * be by the time it begins.
     */
    public void begin(int playerId, String name) {
        closePoll();
        running = true;
        itId = playerId;
        it = name == null ? "" : name;
        freeze = FREEZE_SECONDS;
        elapsed = 0;
        tags.clear();
        reload.clear();
    }

    /**
     * Somebody has been caught: they are it now, and they stand still.
     *
     * <p>The tag is credited to whoever landed it, which is the only score this
     * game keeps. Refused when the round is not running or the target is already
     * it — a jet cannot tag the person who fired it, and
     * {@code WatchGame.flyHurls} does not even offer it the chance.
     *
     * @return whether it changed hands
     */
    public boolean pass(int toId, String toName, String byName) {
        if (!running || toId == itId) return false;
        if (byName != null && !byName.isBlank()) tags.merge(byName, 1, Integer::sum);
        itId = toId;
        it = toName == null ? "" : toName;
        freeze = FREEZE_SECONDS;
        return true;
    }

    /** Whether a walker's gun has cooled down enough to fire again. */
    public boolean loaded(int playerId) {
        return reload.getOrDefault(playerId, 0.0) <= 0;
    }

    /** Note a shot, so the next one waits {@value #RELOAD_SECONDS} seconds. */
    public void fired(int playerId) { reload.put(playerId, RELOAD_SECONDS); }

    /** Call the round off, and close whatever was being asked about it. */
    public void end() {
        closePoll();
        running = false;
        itId = 0;
        it = "";
        freeze = 0;
        elapsed = 0;
        reload.clear();
    }

    /**
     * Somebody has left the walk.
     *
     * <p>Two things can go wrong when a walker disappears and both leave a round
     * that is not a game: the person who was it is no longer there to chase
     * anybody, and a party of one has nobody left to chase. Either ends it.
     *
     * @param party how many are left
     * @return whether the round ended because of it
     */
    public boolean left(int playerId, int party) {
        if (polling) {
            votes.remove(playerId);
            electorate = Math.max(0, party);
        }
        if (!running) return false;
        if (playerId != itId && party >= LEAST_PLAYERS) return false;
        end();
        return true;
    }

    /**
     * Advance the poll's clock and the freeze, and apply whatever the party
     * decided.
     *
     * <p><b>The outcome is applied here rather than reported for somebody else to
     * apply.</b> Everything a passed poll needs — who asked, and whether it was
     * asking to start or to stop — is on this object, and a caller that had to be
     * handed those back so it could call {@link #begin} itself would be a second
     * place the rule lived.
     */
    public Tick tick(double dt) {
        if (dt <= 0) return Tick.QUIET;
        boolean thawed = false;
        if (running) {
            elapsed += dt;
            if (freeze > 0) {
                freeze = Math.max(0, freeze - dt);
                thawed = freeze <= 0;
            }
            reload.replaceAll((id, left) -> Math.max(0, left - dt));
        }
        if (!polling) return thawed ? new Tick(Outcome.NOTHING, true) : Tick.QUIET;

        voteRemaining -= dt;
        if (!settled() && voteRemaining > 0) {
            return thawed ? new Tick(Outcome.NOTHING, true) : Tick.QUIET;
        }
        if (!carries()) {
            closePoll();
            return new Tick(Outcome.REFUSED, thawed);
        }
        if (toStart) {
            // Whoever asked is it. That is not an arbitrary choice of victim: it
            // is what stops "let's play tag" being a way of making somebody else
            // run around for half an hour.
            begin(proposerId, proposer);
            return new Tick(Outcome.STARTED, thawed);
        }
        end();
        return new Tick(Outcome.STOPPED, thawed);
    }

    /** What the poll is asking, in a few words — the line on the card. */
    public String question() {
        if (!polling) return "";
        return proposer + (toStart ? " suggests a game of tag" : " wants to call the tag off");
    }

    /** Where the tally has got to. */
    public String tally() {
        return yes() + " for · " + no() + " against · " + electorate + " walking";
    }

    /** What the round is, in a line — for the HUD and the log. */
    public String describe() {
        if (running) {
            return it + " is it"
                    + (freeze > 0 ? " — frozen for " + (int) Math.ceil(freeze) + "s" : "");
        }
        return polling ? question() : "No game on";
    }

    private void closePoll() {
        polling = false;
        votes.clear();
        electorate = 0;
        voteRemaining = 0;
        proposerId = 0;
        proposer = "";
    }

    private int count(boolean yes) {
        int n = 0;
        for (Boolean answer : votes.values()) {
            if (answer != null && answer == yes) n++;
        }
        return n;
    }

    // --- the wire ---------------------------------------------------------------------

    /**
     * The whole of it, small enough to ride a snapshot.
     *
     * <p>A dozen short fields at their worst, and nothing at all when the walk is
     * not playing: {@code WatchProto.state} leaves the field out entirely while
     * {@link #idle()}, which is every snapshot of nearly every walk.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (polling) {
            m.put("by", proposerId);
            m.put("byn", proposer);
            m.put("st", toStart);
            m.put("left", Math.round(voteRemaining * 10) / 10.0);
            m.put("of", electorate);
            List<Object> ayes = new ArrayList<>();
            List<Object> noes = new ArrayList<>();
            votes.forEach((id, yes) -> (yes ? ayes : noes).add(id));
            m.put("y", ayes);
            m.put("n", noes);
        }
        if (running) {
            m.put("run", true);
            m.put("it", itId);
            m.put("itn", it);
            m.put("fr", Math.round(freeze * 10) / 10.0);
            m.put("el", Math.round(elapsed));
            if (!tags.isEmpty()) m.put("sc", new LinkedHashMap<String, Object>(tags));
        }
        return m;
    }

    /** Whether there is nothing to send: no round, no poll. */
    public boolean idle() { return !running && !polling; }

    /**
     * Take a state sent by the host — <b>wholesale.</b>
     *
     * <p>Every other loader in this game merges, because every other thing it
     * loads only ever grows. This one replaces, for {@code FieldGuide}'s tally's
     * reason: a poll that has closed and a round that has ended are both
     * <em>absences</em>, and a set that only accumulated could never be told about
     * either.
     */
    public void load(Map<String, Object> m) {
        votes.clear();
        tags.clear();
        polling = m != null && m.containsKey("byn");
        if (polling) {
            proposerId = WatchJson.integer(m, "by", 0);
            proposer = WatchJson.str(m, "byn", "");
            toStart = WatchJson.bool(m, "st", true);
            voteRemaining = WatchJson.num(m, "left", 0);
            electorate = WatchJson.integer(m, "of", 0);
            for (Object id : WatchJson.list(m, "y")) {
                if (id instanceof Number n) votes.put(n.intValue(), true);
            }
            for (Object id : WatchJson.list(m, "n")) {
                if (id instanceof Number n) votes.put(n.intValue(), false);
            }
        } else {
            proposerId = 0;
            proposer = "";
            voteRemaining = 0;
            electorate = 0;
        }
        running = m != null && WatchJson.bool(m, "run", false);
        if (running) {
            itId = WatchJson.integer(m, "it", 0);
            it = WatchJson.str(m, "itn", "");
            freeze = WatchJson.num(m, "fr", 0);
            elapsed = WatchJson.num(m, "el", 0);
            WatchJson.map(m, "sc").forEach((name, value) -> {
                if (value instanceof Number n) tags.put(name, n.intValue());
            });
        } else {
            itId = 0;
            it = "";
            freeze = 0;
            elapsed = 0;
            reload.clear();
        }
    }

    @Override public String toString() { return describe(); }
}
