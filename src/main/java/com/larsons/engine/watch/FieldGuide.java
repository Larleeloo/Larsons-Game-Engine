package com.larsons.engine.watch;

import com.larsons.engine.watch.life.AnimalDef;
import com.larsons.engine.watch.life.AnimalFamily;
import com.larsons.engine.watch.life.AnimalRegistry;
import com.larsons.engine.watch.life.Rarity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The book — <b>everything the party has found, and what is still missing.</b>
 *
 * <p>The guide is <em>shared</em>. Eight people walking one world are keeping
 * one record, so a species one of them finds is filled in for everybody, with
 * their name against it. That is the entire reason to travel together rather
 * than separately, and it is why the finder is stored on the sighting rather
 * than the guide being per-player.
 *
 * <p><b>Nothing is ever removed.</b> Not by leaving, not by starting a new
 * world, not by a species disappearing from the registry. A guide is a record
 * of things that happened.
 *
 * <p>What it holds, per species: the first sighting (which is the entry), how
 * many times it has been seen since, and whether one of them was tamed. What it
 * derives: completion by family, by biome and by rarity, which is what turns
 * "1 300 animals" from a number into a set of things to go and do.
 */
public final class FieldGuide {

    private final Map<String, Sighting> first = new LinkedHashMap<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final Map<String, Pet> pets = new LinkedHashMap<>();
    private final List<Sighting> journal = new ArrayList<>();

    /** How many entries of the journal are kept; the guide itself is complete. */
    private static final int JOURNAL_LIMIT = 500;

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
     * Write a sighting into the book.
     *
     * @return {@code true} when this was a <b>discovery</b> — the first time
     *         anybody in the party has seen this species, which is the moment
     *         the whole game is arranged around
     */
    public boolean record(Sighting sighting) {
        if (sighting == null) return false;
        counts.merge(sighting.species(), 1, Integer::sum);
        journal.add(sighting);
        while (journal.size() > JOURNAL_LIMIT) journal.remove(0);
        if (first.containsKey(sighting.species())) return false;
        first.put(sighting.species(), sighting);
        return true;
    }

    /** Whether a species is in the book. */
    public boolean seen(String species) { return first.containsKey(species); }

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

    /** The running total the guide's cover shows. */
    public int points() {
        int score = 0;
        for (String species : first.keySet()) {
            AnimalDef def = AnimalRegistry.byKey(species);
            if (def != null) score += def.rarity().points();
        }
        return score;
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
        return m;
    }

    /** Merge a saved or received guide into this one; nothing is removed. */
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
    }

    @Override public String toString() {
        return discovered() + " of " + total() + " species, " + points() + " points";
    }
}
