package com.larsons.engine.watch;

import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.AnimalFamily;
import com.larsons.engine.watch.life.AnimalRegistry;
import com.larsons.engine.watch.life.Rarity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The book — <b>everything the party has found, and what is still missing.</b>
 *
 * <p>The guide is <em>shared</em>. Eight people walking one world are keeping
 * one record, so a species one of them finds is filled in for everybody, with
 * their name against it. That is the entire reason to travel together rather
 * than separately, and it is why the finder is stored on the sighting rather
 * than the guide being per-player.
 *
 * <p><b>Nothing is ever removed from the record.</b> Not by leaving, not by
 * starting a new world, not by a species disappearing from the registry, and —
 * see below — not by turning a page. A guide is a record of things that
 * happened.
 *
 * <p>What it holds, per species: the first sighting (which is the entry), how
 * many times it has been seen since, and whether one of them was tamed. What it
 * derives: completion by family, by biome and by rarity, which is what turns
 * "1 300 animals" from a number into a set of things to go and do.
 *
 * <h2>The record and the page are two different things</h2>
 *
 * <p>Points used to be a <em>function of the record</em>: the sum of every
 * entry's rarity, recomputed on every call. That has two consequences and both
 * of them are wrong once anything can be bought with points. A total derived
 * from a list cannot be spent — spending it would mean deleting entries, which
 * is the one thing a guide must never do. And a species you have already found
 * is worth nothing for ever, so a walk's four hundredth hour is a walk where
 * almost nothing on the ground is worth looking at.
 *
 * <p>So the book keeps two things where it kept one:
 *
 * <ul>
 *   <li>the <b>record</b> — {@link #first}, every species ever seen, which is
 *       permanent and is what the guide's pages are drawn from;</li>
 *   <li>the <b>page</b> — {@link #tally}, the species that have already been
 *       scored <em>since the last time a page was turned</em>, which is what
 *       decides whether a sighting earns anything.</li>
 * </ul>
 *
 * <p>A keeper at a trading post {@linkplain #stamp stamps a new page}: the
 * tally is emptied, the old page is closed and filed in {@link #volumes()}, and
 * every animal in the world is worth points again — while the record of having
 * seen them is untouched. Points themselves are now a balance, {@link #earned()}
 * less {@link #spent()}, because a shop takes them.
 */
public final class FieldGuide {

    private final Map<String, Sighting> first = new LinkedHashMap<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final Map<String, Pet> pets = new LinkedHashMap<>();
    private final List<Sighting> journal = new ArrayList<>();

    /**
     * The species already scored on the page that is currently open.
     *
     * <p>Not the same set as {@link #first}, and the whole feature is in the
     * difference: a species leaves this when a page is turned and never leaves
     * that. Insertion-ordered so the page reads in the order it was filled in.
     */
    private final Set<String> tally = new LinkedHashSet<>();

    /** Pages that have been closed, oldest first. */
    private final List<Page> volumes = new ArrayList<>();

    /** Points ever earned, and points handed over a counter. */
    private int earned;

    private int spent;

    /** When the open page was started. */
    private long pageOpenedAt = System.currentTimeMillis();

    /** How many entries of the journal are kept; the guide itself is complete. */
    private static final int JOURNAL_LIMIT = 500;

    /**
     * One closed page of the journal — what a stamp leaves behind.
     *
     * <p>The point of keeping these is that turning a page must not feel like
     * losing anything. What a player gave up is the ability to score those
     * species again; what they get back is a numbered volume with a date, a
     * count and a keeper's name on it, which is a better souvenir than a tally
     * they could not see in the first place.
     *
     * @param index   which volume this was, counting from one
     * @param species how many species were scored on it
     * @param points  what they came to
     * @param keeper  who stamped it, or {@code null}
     * @param place   the biome it was stamped in, or {@code null}
     */
    public record Page(int index, int species, int points, long openedAt, long closedAt,
                       String keeper, String place) {

        /** The line the guide's contents page shows. */
        public String describe() {
            // "Species" is its own plural, which is the one word in this game
            // that does not need the ternary every other count here has.
            return "Vol. " + index + " — " + species + " species, " + points + " pts"
                    + (keeper == null ? "" : "  ·  stamped by " + keeper);
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("i", index);
            m.put("sp", species);
            m.put("pts", points);
            m.put("o", openedAt);
            m.put("c", closedAt);
            if (keeper != null) m.put("by", keeper);
            if (place != null) m.put("at", place);
            return m;
        }

        static Page fromMap(Map<String, Object> m) {
            int index = WatchJson.integer(m, "i", 0);
            if (index <= 0) return null;
            return new Page(index, WatchJson.integer(m, "sp", 0),
                    WatchJson.integer(m, "pts", 0), WatchJson.big(m, "o", 0),
                    WatchJson.big(m, "c", 0), WatchJson.str(m, "by", null),
                    WatchJson.str(m, "at", null));
        }
    }

    /** An animal that came home with somebody. */
    public record Pet(String species, String name, String owner, long tamedAtMillis) {

        public AnimalDef def() { return AnimalRegistry.byKey(species); }

        public String describe() {
            AnimalDef def = def();
            return (name == null || name.isBlank() ? def == null ? species : def.name() : name)
                    + (owner == null ? "" : " · " + owner + "'s");
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sp", species);
            if (name != null) m.put("n", name);
            if (owner != null) m.put("by", owner);
            m.put("t", tamedAtMillis);
            return m;
        }

        static Pet fromMap(Map<String, Object> m) {
            String species = WatchJson.str(m, "sp", null);
            if (species == null) return null;
            return new Pet(species, WatchJson.str(m, "n", null),
                    WatchJson.str(m, "by", null), WatchJson.big(m, "t", 0));
        }
    }

    /**
     * Write a sighting into the book, and credit whatever it was worth.
     *
     * <p>Two things happen here and they are independent, which is the point.
     * The <b>record</b> gains an entry the first time a species is ever seen and
     * never again. The <b>page</b> gains a tally mark — and the points that go
     * with it — the first time a species is seen <em>since the last stamp</em>,
     * which may be the fortieth time it has been seen in all.
     *
     * @return {@code true} when this was a <b>discovery</b> — the first time
     *         anybody in the party has seen this species, which is the moment
     *         the whole game is arranged around. Ask {@link #award} first if you
     *         want to know what it paid; by the time this returns, it has.
     */
    public boolean record(Sighting sighting) {
        if (sighting == null) return false;
        counts.merge(sighting.species(), 1, Integer::sum);
        journal.add(sighting);
        while (journal.size() > JOURNAL_LIMIT) journal.remove(0);
        earned += award(sighting.species());
        tally.add(sighting.species());
        if (first.containsKey(sighting.species())) return false;
        first.put(sighting.species(), sighting);
        return true;
    }

    /**
     * What a sighting of this species would be worth <b>now</b> — its rarity if
     * it is not yet on the open page, and nothing if it is.
     *
     * <p>Asked before recording, so a caller can say what a sighting paid, and
     * asked by the screen so the crosshair can offer "worth 8 pts" over an
     * animal the book already knows about. That second use is the whole reason
     * a stamped page is worth walking to a post for.
     */
    public int award(String species) {
        if (species == null || tally.contains(species)) return 0;
        AnimalDef def = AnimalRegistry.byKey(species);
        return def == null ? 0 : def.rarity().points();
    }

    /**
     * Credit a species that was scored elsewhere — what a client does with a
     * sighting the host has already adjudicated.
     *
     * <p>The award travels on the {@link Spotlight} rather than the whole tally
     * coming back down the wire on every sighting: the host has decided, the
     * message already names the species, and one integer beside it is the entire
     * difference between a client that can add up and one that cannot.
     */
    public void credit(String species, int points) {
        if (species == null || points <= 0 || !tally.add(species)) return;
        earned += points;
    }

    /** Whether a species is in the book — ever, on any page. */
    public boolean seen(String species) { return first.containsKey(species); }

    /** Whether a species has already been scored on the page that is open. */
    public boolean scored(String species) { return tally.contains(species); }

    /** How many species are on the open page. */
    public int tallied() { return tally.size(); }

    /** The species on the open page, in the order they were found. */
    public List<String> page() { return List.copyOf(tally); }

    /** The pages that have been closed, oldest first. */
    public List<Page> volumes() { return List.copyOf(volumes); }

    /** Which volume is open, counting from one. */
    public int volume() { return volumes.size() + 1; }

    /**
     * Turn the page: everything already seen counts again.
     *
     * <p><b>The record is not touched.</b> This empties the tally and files the
     * page it emptied, so the guide still holds every species it ever held and
     * every one of them is worth its rarity again the next time it is spotted.
     *
     * <p>A keeper will not stamp a <b>blank</b> page, and that one rule is the
     * whole of the economy's floor. Without it, walking up to a post with an
     * animal in front of you is spot, stamp, spot, stamp — points out of one
     * chaffinch for as long as you can be bothered to press two keys. With it,
     * a stamp is only worth asking for once you have filled a page, which means
     * the loop is "go and find things, then come back", which is the loop the
     * game already is.
     *
     * @return the page that was closed, or {@code null} if it was blank
     */
    public Page stamp(String keeper, String place, long atMillis) {
        if (tally.isEmpty()) return null;
        int points = 0;
        for (String species : tally) {
            AnimalDef def = AnimalRegistry.byKey(species);
            if (def != null) points += def.rarity().points();
        }
        Page closed = new Page(volumes.size() + 1, tally.size(), points, pageOpenedAt,
                atMillis, keeper, place);
        volumes.add(closed);
        tally.clear();
        pageOpenedAt = atMillis;
        return closed;
    }

    /** The first sighting of a species, or {@code null}. */
    public Sighting firstSighting(String species) { return first.get(species); }

    /** How many times a species has been seen. */
    public int timesSeen(String species) { return counts.getOrDefault(species, 0); }

    /** How many species are in the book. */
    public int discovered() { return first.size(); }

    /** How many there are to find. */
    public int total() { return AnimalRegistry.count(); }

    /** How complete the book is, {@code 0}–{@code 1}. */
    public double completion() {
        return total() == 0 ? 0 : discovered() / (double) total();
    }

    /**
     * The points the party has to spend — the running total the guide's cover
     * shows, and what a trading post will take.
     *
     * <p>A <b>balance</b> rather than a sum over the entries, which is what it
     * used to be. A total recomputed from the record cannot go down, so nothing
     * could ever be bought with it; and it could not go up for an animal already
     * in the book, so the record's own completeness was what made the game stop
     * paying. See the class note.
     */
    public int points() { return earned - spent; }

    /** Everything ever earned, across every page. */
    public int earned() { return earned; }

    /** …and everything handed over a counter. */
    public int spent() { return spent; }

    /** Whether the party can afford something. */
    public boolean affords(int price) { return price >= 0 && points() >= price; }

    /**
     * Hand points over.
     *
     * <p>All or nothing, like every other cost in this game: a purchase that
     * cannot be afforded takes nothing, so nobody ends up out of pocket and
     * empty-handed.
     */
    public boolean spend(int price) {
        if (price < 0 || !affords(price)) return false;
        spent += price;
        return true;
    }

    /** The last few sightings, newest first — what the journal page shows. */
    public List<Sighting> recent(int limit) {
        List<Sighting> out = new ArrayList<>();
        for (int i = journal.size() - 1; i >= 0 && out.size() < limit; i--) {
            out.add(journal.get(i));
        }
        return out;
    }

    /** Every sighting recorded this session, oldest first. */
    public List<Sighting> journal() { return List.copyOf(journal); }

    // --- taming --------------------------------------------------------------------

    /** Take an animal home. */
    public void tame(String species, String name, String owner, long atMillis) {
        pets.put(species + "/" + owner, new Pet(species, name, owner, atMillis));
    }

    /** Every pet the party has between them. */
    public List<Pet> pets() { return List.copyOf(pets.values()); }

    /** The pets belonging to one player. */
    public List<Pet> petsOf(String owner) {
        List<Pet> out = new ArrayList<>();
        for (Pet pet : pets.values()) {
            if (pet.owner() != null && pet.owner().equals(owner)) out.add(pet);
        }
        return out;
    }

    /** Whether somebody has already tamed this species. */
    public boolean tamed(String species) {
        for (Pet pet : pets.values()) {
            if (pet.species().equals(species)) return true;
        }
        return false;
    }

    // --- progress ------------------------------------------------------------------

    /** How many species of each family are in the book, and how many exist. */
    public Map<AnimalFamily, int[]> byFamily() {
        Map<AnimalFamily, int[]> out = new EnumMap<>(AnimalFamily.class);
        for (AnimalDef def : AnimalRegistry.all()) {
            int[] pair = out.computeIfAbsent(def.family(), f -> new int[2]);
            pair[1]++;
            if (seen(def.key())) pair[0]++;
        }
        return out;
    }

    /** The same, per rarity tier — which is where the last few always are. */
    public Map<Rarity, int[]> byRarity() {
        Map<Rarity, int[]> out = new EnumMap<>(Rarity.class);
        for (AnimalDef def : AnimalRegistry.all()) {
            int[] pair = out.computeIfAbsent(def.rarity(), r -> new int[2]);
            pair[1]++;
            if (seen(def.key())) pair[0]++;
        }
        return out;
    }

    /** The same, per biome — what tells a party where to go next. */
    public Map<String, int[]> byBiome() {
        Map<String, int[]> out = new LinkedHashMap<>();
        for (var biome : com.larsons.engine.watch.world.WatchBiomes.all()) {
            int[] pair = new int[2];
            for (AnimalDef def : AnimalRegistry.inBiome(biome.key())) {
                pair[1]++;
                if (seen(def.key())) pair[0]++;
            }
            out.put(biome.key(), pair);
        }
        return out;
    }

    /**
     * A handful of species that are missing and findable where the party is —
     * the "what to look for here" line, and the closest thing the game has to a
     * quest.
     */
    public List<AnimalDef> missingIn(String biomeKey, int limit) {
        List<AnimalDef> out = new ArrayList<>();
        for (AnimalDef def : AnimalRegistry.inBiome(biomeKey)) {
            if (!seen(def.key())) out.add(def);
        }
        // Commonest first: telling somebody to go and find a legendary is not
        // advice.
        out.sort(Comparator.comparingDouble((AnimalDef d) -> -d.rarity().frequency())
                .thenComparing(AnimalDef::name));
        return out.size() <= limit ? out : new ArrayList<>(out.subList(0, limit));
    }

    // --- persistence ----------------------------------------------------------------

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Object> entries = new ArrayList<>();
        for (Sighting s : first.values()) entries.add(s.toMap());
        m.put("entries", entries);
        m.put("counts", new LinkedHashMap<>(counts));
        List<Object> petRows = new ArrayList<>();
        for (Pet pet : pets.values()) petRows.add(pet.toMap());
        m.put("pets", petRows);
        m.putAll(ledger());
        List<Object> pageRows = new ArrayList<>();
        for (Page page : volumes) pageRows.add(page.toMap());
        m.put("pages", pageRows);
        m.put("opened", pageOpenedAt);
        return m;
    }

    /**
     * The economy on its own — what changed when somebody bought something.
     *
     * <p>Its own map because a purchase changes three numbers and nothing else,
     * and re-sending a party's four hundred entries to say that a plank cost six
     * points would be absurd. It merges through {@link #load} like any other
     * guide message.
     */
    public Map<String, Object> ledger() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("earned", earned);
        m.put("spent", spent);
        m.put("tally", new ArrayList<Object>(tally));
        return m;
    }

    /**
     * Merge a saved or received guide into this one.
     *
     * <p><b>Nothing is removed from the record</b> — entries, counts and pets
     * only ever accumulate, which is what makes a single-sighting message a
     * legitimate way to fill one page in for a whole party.
     *
     * <p>The <b>tally</b> is the one field that <em>replaces</em> rather than
     * merging, and it has to: turning a page is an emptying, and a set that only
     * grows could never be told that it had been emptied. That is not an
     * exception to the rule above so much as a consequence of what the tally is
     * — it is the page, not the book.
     */
    public void load(Map<String, Object> m) {
        for (Map<String, Object> row : WatchJson.objects(m, "entries")) {
            Sighting sighting = Sighting.fromMap(row);
            if (sighting != null) first.putIfAbsent(sighting.species(), sighting);
        }
        WatchJson.map(m, "counts").forEach((key, value) -> {
            if (value instanceof Number n) {
                counts.merge(key, n.intValue(), Math::max);
            }
        });
        for (Map<String, Object> row : WatchJson.objects(m, "pets")) {
            Pet pet = Pet.fromMap(row);
            if (pet != null) pets.putIfAbsent(pet.species() + "/" + pet.owner(), pet);
        }
        if (m != null && m.containsKey("pages")) {
            volumes.clear();
            for (Map<String, Object> row : WatchJson.objects(m, "pages")) {
                Page page = Page.fromMap(row);
                if (page != null) volumes.add(page);
            }
        }
        if (m != null && m.containsKey("tally")) {
            tally.clear();
            for (Object key : WatchJson.list(m, "tally")) {
                if (key instanceof String s) tally.add(s);
            }
        }
        // Only when they are there. A single-sighting announce carries an entry
        // and nothing else, and must leave the ledger exactly as it found it.
        if (m != null && m.containsKey("earned")) {
            earned = WatchJson.integer(m, "earned", earned);
            spent = WatchJson.integer(m, "spent", spent);
        }
        pageOpenedAt = WatchJson.big(m, "opened", pageOpenedAt);
    }

    @Override public String toString() {
        return discovered() + " of " + total() + " species, " + points() + " points";
    }
}
