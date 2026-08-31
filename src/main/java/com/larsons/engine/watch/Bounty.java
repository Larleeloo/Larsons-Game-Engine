package com.larsons.engine.watch;

import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.AnimalRegistry;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Eye Spy — <b>one thing a day you can ask the rest of the party to find.</b>
 *
 * <h2>What it is for</h2>
 *
 * <p>The guide answers "what have we found"; the biome page answers "what is
 * still missing here". Neither of them can say <em>go and look for that one</em>,
 * and that sentence is the best thing one person on a walk can say to another. A
 * bounty is that sentence, written down, with a number on it.
 *
 * <p>So a walker names a species and the world puts a price on it: somewhere
 * between {@value #LEAST} and {@value #MOST} points, <b>rolled by the host</b>
 * rather than chosen by the poster. That is the whole of why it is worth
 * pinning up. A reward somebody sets themselves is a reward they can set to a
 * hundred every time, and a board of hundreds is a board with no news on it —
 * whereas a wren that happens to be worth ninety-one is a thing a party will
 * spend an afternoon on.
 *
 * <h2>Once a day, and not by the game's clock</h2>
 *
 * <p>The limit is one posting per walker per <em>real</em> day, measured off the
 * same wall clock everything else here is measured off (see {@link WatchClock}).
 * A game day in this world is a real day, so there is no other clock it could
 * mean — and the point of the limit is that a bounty is a thing you thought
 * about, not a thing you spam. Somebody who has already posted today is told so
 * and told when they may post again.
 *
 * <h2>Whoever posts it cannot claim it</h2>
 *
 * <p>The one rule the brief names outright, and it is what makes the board a
 * board rather than a way of paying yourself: {@link #claim} skips any posting
 * whose poster is the finder. Everything else about a claim is the ordinary
 * arrangement of this game — the points go into the <em>shared</em> ledger,
 * because there is one book and one balance and always has been, and the finder's
 * name goes on the posting so the board says who did it. What a claim buys is
 * the same thing every other point in this game buys: something off a keeper's
 * shelf, for whoever gets to a counter first.
 *
 * <h2>Kept, unlike the tag round</h2>
 *
 * <p>Saved and synced like the grove and the buildings, because a bounty is a
 * promise: a party that logs off with four of them open should log back on to
 * four of them open. They do expire — {@value #LIFE_HOURS} real hours — so a
 * board is a list of things worth doing this week rather than an archive of
 * every animal anybody ever wondered about.
 */
public final class Bounty {

    /** The least a bounty can be worth. */
    public static final int LEAST = 10;

    /** …and the most. */
    public static final int MOST = 100;

    /** How long an unclaimed bounty stands, in real hours. */
    public static final double LIFE_HOURS = 24;

    /** How many species the board offers to choose from. */
    public static final int CHOICES = 12;

    /**
     * How many claims are remembered once they are settled.
     *
     * <p>Enough for the board to have a history worth reading and few enough
     * that a long-running world's save does not carry a year of them. The open
     * postings are never dropped by this; only settled ones age out.
     */
    private static final int SETTLED_LIMIT = 40;

    /**
     * One posting.
     *
     * @param id      what the wire and the save call it
     * @param species the species key somebody has to find
     * @param points  what it pays, rolled by the host
     * @param poster  who put it up — the one walker who may not claim it
     * @param at      when, in wall-clock millis
     * @param finder  who claimed it, or {@code null} while it is open
     * @param foundAt when they did, or {@code 0}
     */
    public record Posting(long id, String species, int points, String poster, long at,
                          String finder, long foundAt) {

        /** Whether it is still there to be claimed. */
        public boolean open() { return finder == null; }

        /** The species, or {@code null} if the registry no longer has it. */
        public AnimalDef def() { return AnimalRegistry.byKey(species); }

        /** What a player sees the quarry called. */
        public String name() {
            AnimalDef def = def();
            return def == null ? species : def.name();
        }

        /** The line the board shows. */
        public String describe() {
            String worth = points + (points == 1 ? " pt" : " pts");
            if (open()) return name() + " — " + worth + "  ·  " + poster + "'s";
            return name() + " — " + worth + "  ·  found by " + finder;
        }

        /** How long it has left, in real hours, or {@code 0} once it is claimed. */
        public double hoursLeft(long now) {
            if (!open()) return 0;
            return Math.max(0, LIFE_HOURS - WatchClock.realHoursBetween(at, now));
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("sp", species);
            m.put("pts", points);
            m.put("by", poster);
            m.put("t", at);
            if (finder != null) {
                m.put("got", finder);
                m.put("gt", foundAt);
            }
            return m;
        }

        static Posting fromMap(Map<String, Object> m) {
            long id = WatchJson.big(m, "id", 0);
            String species = WatchJson.str(m, "sp", null);
            if (id <= 0 || species == null) return null;
            return new Posting(id, species, WatchJson.integer(m, "pts", LEAST),
                    WatchJson.str(m, "by", ""), WatchJson.big(m, "t", 0),
                    WatchJson.str(m, "got", null), WatchJson.big(m, "gt", 0));
        }
    }

    private final Map<Long, Posting> board = new LinkedHashMap<>();

    /** The last day each walker posted on, so the limit can be one a day. */
    private final Map<String, Long> lastPosted = new LinkedHashMap<>();

    private long nextId = 1;

    /**
     * How many times the board has changed.
     *
     * <p>A host's counter, and the whole of how a claim reaches anybody. Three
     * things change this board and none of them is a request the host can answer
     * in place: a posting goes up in one, a bounty is claimed by a sighting that
     * already has a message of its own, and one expires on a tick with no request
     * behind it at all. So the server watches this number instead — the same
     * arrangement {@code WatchServer.announceDeaths} uses on the respawn counter,
     * and for the same reason: {@link WatchGame} reports what happened through its
     * state rather than by knowing who is listening.
     */
    private long version;

    /** How many times the board has changed. See {@link #version}. */
    public long version() { return version; }

    /**
     * Which day a wall-clock moment falls on, counting from the epoch.
     *
     * <p>The <em>local</em> day, in the zone the machine holding this object is
     * in, which online is the host's — the same rule {@link WatchClock} follows
     * for the same reason: one party, one calendar, whoever is hosting it. A
     * number rather than a date so that it can go in a save and come back out of
     * one without a formatter.
     */
    public static long dayOf(long millis) {
        return LocalDate.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
                .toEpochDay();
    }

    /**
     * What a bounty is worth, as the host rolls it.
     *
     * <p>Flat between {@value #LEAST} and {@value #MOST} inclusive, and nothing
     * weights it: not the species' rarity, not how many people are on the walk,
     * not how long the board has been empty. A number a player can predict is a
     * number they can plan around, and the whole appeal of this board is that
     * they cannot.
     */
    public static int roll(Random rng) {
        return LEAST + rng.nextInt(MOST - LEAST + 1);
    }

    /** Whether a walker has already had their posting today. */
    public boolean postedToday(String poster, long now) {
        Long day = lastPosted.get(poster);
        return day != null && day == dayOf(now);
    }

    /** When a walker may post again, as a whole number of hours away. */
    public long hoursUntilNextPosting(String poster, long now) {
        if (!postedToday(poster, now)) return 0;
        LocalDate today = LocalDate.ofInstant(Instant.ofEpochMilli(now),
                ZoneId.systemDefault());
        long midnight = today.plusDays(1).atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        return Math.max(1, Math.round(Math.ceil(WatchClock.realHoursBetween(now, midnight))));
    }

    /** Whether the board already carries an open bounty on a species. */
    public boolean open(String species) {
        for (Posting posting : board.values()) {
            if (posting.open() && posting.species().equals(species)) return true;
        }
        return false;
    }

    /**
     * Pin one up.
     *
     * <p>Refused for four reasons and no others: there is no such species, the
     * walker has already posted today, the board already carries that species,
     * or the points are outside the band. The <em>choice</em> of species is not
     * one of them — a bounty on something nobody has ever seen, in a biome
     * nobody is standing in, is a perfectly good bounty and is arguably the best
     * kind. See {@link #choices} for what a screen offers, which is a
     * convenience rather than a rule.
     *
     * @return the posting, or {@code null} if it was refused
     */
    public Posting post(String species, int points, String poster, long now) {
        if (AnimalRegistry.byKey(species) == null) return null;
        if (points < LEAST || points > MOST) return null;
        if (poster == null || poster.isBlank()) return null;
        if (postedToday(poster, now) || open(species)) return null;
        Posting posting = new Posting(nextId++, species, points, poster, now, null, 0);
        board.put(posting.id(), posting);
        lastPosted.put(poster, dayOf(now));
        version++;
        return posting;
    }

    /**
     * Somebody has found something: settle whatever it was worth.
     *
     * <p>Called on every sighting the world records, which is why it is cheap and
     * why it answers {@code null} for the ordinary case. At most one posting is
     * claimed per sighting — {@link #post} will not put two open bounties on one
     * species, so there is never a second to find.
     *
     * @return the posting that was claimed, or {@code null}
     */
    public Posting claim(String species, String finder, long now) {
        if (species == null || finder == null) return null;
        for (Posting posting : List.copyOf(board.values())) {
            if (!posting.open() || !posting.species().equals(species)) continue;
            // The one rule the whole board rests on.
            if (finder.equals(posting.poster())) continue;
            Posting settled = new Posting(posting.id(), posting.species(),
                    posting.points(), posting.poster(), posting.at(), finder, now);
            board.put(settled.id(), settled);
            trim();
            version++;
            return settled;
        }
        return null;
    }

    /**
     * Take down anything nobody claimed in time.
     *
     * @return what was taken down
     */
    public List<Posting> expire(long now) {
        List<Posting> gone = new ArrayList<>();
        for (Posting posting : List.copyOf(board.values())) {
            if (posting.open() && posting.hoursLeft(now) <= 0) {
                board.remove(posting.id());
                gone.add(posting);
            }
        }
        if (!gone.isEmpty()) version++;
        return gone;
    }

    /** Everything still to be found, newest first. */
    public List<Posting> open() {
        List<Posting> out = new ArrayList<>();
        for (Posting posting : board.values()) {
            if (posting.open()) out.add(posting);
        }
        out.sort(Comparator.comparingLong(Posting::at).reversed());
        return out;
    }

    /** Everything that has been claimed, newest first. */
    public List<Posting> settled() {
        List<Posting> out = new ArrayList<>();
        for (Posting posting : board.values()) {
            if (!posting.open()) out.add(posting);
        }
        out.sort(Comparator.comparingLong(Posting::foundAt).reversed());
        return out;
    }

    /** Every posting there is, in the order they went up. */
    public List<Posting> all() { return List.copyOf(board.values()); }

    /** One posting by id, or {@code null}. */
    public Posting byId(long id) { return board.get(id); }

    /** How many are open. */
    public int openCount() { return open().size(); }

    /**
     * What a screen offers somebody who wants to post one — <b>a shortlist, not
     * the registry.</b>
     *
     * <p>Thirteen hundred species is not a menu. What a walker actually wants to
     * ask for is something that lives where they are standing, so this is the
     * biome's own list with the ones already in the book first: a bounty on an
     * animal somebody in the party has seen is a bounty they can describe, and
     * one on an animal nobody has seen is the harder and better half of the list
     * underneath it. Within each half, commonest first — telling somebody to go
     * and find a legendary is not advice, which is the same argument
     * {@link FieldGuide#missingIn} makes.
     *
     * <p>Anything already on the board is left out, because posting it would be
     * refused, and a menu whose rows are refused is a menu that lies.
     *
     * <p><b>Both ends compute this, neither sends it.</b> It is a pure function of
     * the registry, the shared book and where you are standing — all three of
     * which a client already has — so the screen builds its own list and sends
     * back one species key. See {@code WatchProto.bounty}.
     */
    public List<AnimalDef> choices(FieldGuide guide, String biomeKey, int limit) {
        List<AnimalDef> seen = new ArrayList<>();
        List<AnimalDef> unseen = new ArrayList<>();
        for (AnimalDef def : AnimalRegistry.inBiome(biomeKey)) {
            if (open(def.key())) continue;
            (guide != null && guide.seen(def.key()) ? seen : unseen).add(def);
        }
        Comparator<AnimalDef> order =
                Comparator.comparingDouble((AnimalDef d) -> -d.rarity().frequency())
                        .thenComparing(AnimalDef::name);
        seen.sort(order);
        unseen.sort(order);
        List<AnimalDef> out = new ArrayList<>(seen);
        out.addAll(unseen);
        return out.size() <= limit ? out : new ArrayList<>(out.subList(0, limit));
    }

    /**
     * Forget the oldest settled postings.
     *
     * <p>Open ones are never dropped however many there are: a bounty nobody has
     * claimed is the whole point of the board, and it has {@link #expire} to take
     * it down when its day is up.
     */
    private void trim() {
        List<Posting> settled = settled();
        for (int i = SETTLED_LIMIT; i < settled.size(); i++) {
            board.remove(settled.get(i).id());
        }
    }

    // --- persistence ------------------------------------------------------------------

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> rows = new ArrayList<>();
        for (Posting posting : board.values()) rows.add(posting.toMap());
        m.put("board", rows);
        Map<String, Object> days = new LinkedHashMap<>();
        lastPosted.forEach(days::put);
        m.put("days", days);
        m.put("next", nextId);
        return m;
    }

    /**
     * Take a saved or received board — <b>wholesale.</b>
     *
     * <p>Replaced rather than merged, for {@link Tag#load}'s reason: a bounty that
     * has been claimed and one that has expired are both changes a merge could
     * never carry, and this arrives on the world sync, which is a complete
     * picture by construction.
     */
    public void load(Map<String, Object> m) {
        if (m == null || m.isEmpty()) return;
        board.clear();
        for (Map<String, Object> row : WatchJson.objects(m, "board")) {
            Posting posting = Posting.fromMap(row);
            if (posting != null) board.put(posting.id(), posting);
        }
        lastPosted.clear();
        WatchJson.map(m, "days").forEach((name, value) -> {
            if (value instanceof Number n) lastPosted.put(name, n.longValue());
        });
        nextId = Math.max(WatchJson.big(m, "next", 1), highestId() + 1);
    }

    private long highestId() {
        long top = 0;
        for (Long id : board.keySet()) top = Math.max(top, id);
        return top;
    }

    @Override public String toString() {
        return openCount() + " open, " + settled().size() + " claimed";
    }
}
