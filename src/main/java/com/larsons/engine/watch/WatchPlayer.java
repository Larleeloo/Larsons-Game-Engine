package com.larsons.engine.watch;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One person in the party.
 *
 * <h2>Stillness is a stat</h2>
 *
 * <p>The only number here that is not bookkeeping is {@link #stillness()}, and
 * it is the closest thing this game has to a character sheet. It rises while a
 * player holds position and collapses the moment they move; every animal reads
 * it through {@code WatchGame.disturbanceAt}, which scales their distance by
 * it. Stand still for ten seconds and the wood forgets you are there; take
 * three steps and it remembers.
 *
 * <p>That is deliberately the <em>only</em> way to get close to a wary species.
 * There is no camouflage to craft, no skill to level, and no upgrade that
 * widens the radius. There is standing still, and there is food.
 */
public final class WatchPlayer {

    /** How long it takes, in seconds, to go from moving to fully settled. */
    public static final double SETTLE_SECONDS = 9;

    /** How fast stillness is lost while moving, relative to how fast it is gained. */
    private static final double UNSETTLE_RATE = 5;

    /** Metres per second at a walk. */
    public static final double WALK_SPEED = 4.4;

    /** …at a jog, which costs every scrap of stillness. */
    public static final double RUN_SPEED = 8.0;

    /** …and crouched, which barely costs any. */
    public static final double CROUCH_SPEED = 1.5;

    private final int id;
    private final String name;

    /** How long a lungful lasts, in seconds. */
    public static final double BREATH_SECONDS = 42;

    /** How fast a breath comes back at the surface, relative to how fast it goes. */
    private static final double BREATH_RECOVERY = 4;

    /**
     * How long a full health bar takes to come back from empty, in seconds.
     *
     * <p><b>Ninety seconds, and the slowness is the whole design.</b> This is a
     * game about walking somewhere and looking at it; the only thing that can
     * hurt you is one of three {@link com.larsons.engine.watch.life.Mutants},
     * and what a health bar has to produce is the feeling that getting away from
     * one <em>cost</em> something — a minute and a half of sitting in a hollow
     * before the next thing is safe to walk toward.
     *
     * <p>Regenerating at all rather than needing bandages is the other half of
     * that: there is no medicine in this game, no crafting tree ending in a
     * poultice, and no way to fail permanently. You get hurt, you stop, and the
     * wood puts you back together, which is the same bargain
     * {@link #stillness()} makes about everything else here.
     */
    public static final double HEAL_SECONDS = 90;

    /**
     * How long after being hit before it starts coming back, in seconds.
     *
     * <p>Without this the bar refills <em>during</em> a chase, and a mutant that
     * is landing a blow every two seconds is fighting the regeneration rather
     * than the player. Six seconds is longer than any of the three take between
     * blows, so nothing heals while it is still being hit.
     */
    public static final double HEAL_DELAY = 6;

    private double x, y, z;
    private double yaw, pitch;
    private double stillness = 1;
    private boolean crouching;
    private double lastSpeed;

    /** How much air is left, {@code 0}–{@code 1}. */
    private double breath = 1;

    /** How much health is left, {@code 0}–{@code 1}. */
    private double health = 1;

    /** How long since the last blow landed, in seconds. */
    private double sinceHurt = HEAL_DELAY;

    /**
     * How many times this walker has been killed and put back at the spawn.
     *
     * <p><b>A counter rather than a message, and that is the whole of how a
     * respawn reaches the screen.</b> The client is authoritative about where it
     * is standing — it sends a position and the server records it — so the
     * server cannot move somebody by writing a new position into the snapshot:
     * the next {@code move} would simply put them back. A "you have been moved"
     * message would work and would have to be acknowledged, resent when lost,
     * and ignored when duplicated.
     *
     * <p>This is none of that. The number goes up when the host respawns
     * somebody, it rides in every snapshot beside their position, and a client
     * that sees a bigger number than the one it last acted on teleports to the
     * position in that same snapshot. Lost packets do not matter (the next
     * snapshot carries it), duplicates do not matter (the number is unchanged),
     * and a client that joins late is simply already in the right place.
     */
    private int respawns;

    /** Whether the player's head is under the water this tick. */
    private boolean submerged;

    /** The boat being rowed, or {@code 0}. */
    private long boatId;

    /**
     * The magnification of the glass this player has up; {@code 1} for none.
     *
     * <p>Server state rather than a client's business, because it is what
     * decides how far away they may record something — see
     * {@code WatchGame.glass}, which refuses a power to anybody without a
     * spyglass in their satchel. It is also in the snapshot, so everybody else
     * can see somebody with a glass to their eye and follow where they are
     * looking, which is half of watching things with other people.
     */
    private double glassPower = Spyglass.NONE;

    /**
     * Whether this player has typed {@link Debug#CODE}.
     *
     * <p>Kept here rather than on the {@link Satchel} — even though the satchel
     * is where it does its work — because it is a fact about the <em>player</em>
     * and the next power that wants it will not be about items. The satchel's
     * lens is set from it, in one place, by {@link #setDebug}.
     */
    private boolean debug;

    /**
     * The forage key of the light this player is carrying <em>lit</em>, or
     * {@code null} for empty hands.
     *
     * <p><b>Server state, and in the snapshot</b>, for {@link #glassPower}'s two
     * reasons turned up a notch. It is the host that decides whether there is
     * actually a lantern in the satchel to light and whether there is any oil
     * left in it; and everybody else has to see it, because a lantern moving
     * along the far side of a valley is the only way a party keeps track of each
     * other after dark.
     *
     * <p>The item stays in the satchel while it is lit. That is not a
     * simplification — it is what makes putting one down
     * ({@code WatchGame.setDownLight}) and picking it back up the same object
     * arriving and leaving, rather than an item and a separate "am I holding
     * it" flag that can disagree.
     */
    private String lamp;

    /**
     * How many real hours of burning are left in the one in hand.
     *
     * <p>Per player rather than per item, because a satchel counts items rather
     * than holding them: three lanterns in a bag are the number three. What is
     * being tracked here is <em>the one in your hand</em>, and it keeps its
     * remaining oil when it is put out, when it is set down and when it is
     * picked back up — see
     * {@link com.larsons.engine.watch.light.PlacedLight}, which is where the
     * hours go when it leaves the hand.
     *
     * <p>Keeping it across a dousing is the whole reason there is a separate
     * {@link #lampLit} flag rather than a null key meaning "not burning": a
     * lamp that forgot its oil every time it was put out would make the fuel
     * economy a formality, since putting a lantern out and lighting it again is
     * two keypresses.
     */
    private double lampFuel;

    private boolean lampLit;

    private final Satchel satchel = new Satchel();

    /**
     * What they own to wear, and what they have on.
     *
     * <p>Beside the satchel rather than in it, and that is the whole of the
     * design: see {@link Outfit}. A cosmetic is bought out of the party's purse
     * and kept by the person, is never a count of anything, and cannot be
     * dropped — which is three reasons it is not an item.
     */
    private final Outfit outfit = new Outfit();
    private final Fishing rod;

    public WatchPlayer(int id, String name, double x, double y, double z) {
        this.id = id;
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.rod = new Fishing(name.hashCode() * 31L + id);
    }

    public int id() { return id; }

    public String name() { return name; }

    public double x() { return x; }

    public double y() { return y; }

    /** The ground the player is standing on, in metres. */
    public double z() { return z; }

    /** Eye height above that. */
    public double eyeZ() { return z + (crouching ? 1.10 : 1.68); }

    public double yaw() { return yaw; }

    public double pitch() { return pitch; }

    public boolean crouching() { return crouching; }

    /** How settled the player is, {@code 0} (crashing about) – {@code 1} (still). */
    public double stillness() { return stillness; }

    /**
     * How much air is left, {@code 1} full to {@code 0} out.
     *
     * <p><b>Not a health bar, even now that there is one.</b> Running out
     * surfaces you; it does not hurt you. The lake bed is somewhere to look at
     * things, and a game about looking at things should not punish you for
     * looking too long — it should just make you come up for air, which is what
     * a person diving on a reef does anyway. {@link #health()} is the bar that
     * can end a walk, and only three things in the world can spend it.
     */
    public double breath() { return breath; }

    /**
     * How much health is left, {@code 1} whole to {@code 0} dead.
     *
     * <p>The second resource, and the first one that can end a walk. It exists
     * because three things in the world now hunt people — see
     * {@link com.larsons.engine.watch.life.Mutants} — and a chase with no stake
     * in it is a cutscene. It is deliberately <em>not</em> a combat stat: there
     * is nothing to hit back with, no armour to raise it and no potion to
     * refill it. What it measures is how many more seconds you can afford to
     * spend in front of the thing, and the answer is meant to be "not many".
     */
    public double health() { return health; }

    /** Whether they are still on their feet. */
    public boolean alive() { return health > 0; }

    /** How long since something last hit them, in seconds. */
    public double sinceHurt() { return sinceHurt; }

    /** Whether they were hit recently enough that the bar is not yet refilling. */
    public boolean bleeding() { return sinceHurt < HEAL_DELAY; }

    /** How many times they have been killed on this walk. See {@link #respawns}. */
    public int respawns() { return respawns; }

    /**
     * Take a wound.
     *
     * <p>Returns whether this was the blow that did it, so the caller — which
     * is {@code WatchGame}, the only thing allowed to decide what happens next
     * — can drop the satchel and put them back at the spawn without having to
     * compare the bar against zero itself.
     *
     * @param amount a share of a full bar, {@code 0.24} being about a quarter
     * @return {@code true} if they went down
     */
    public boolean wound(double amount) {
        if (amount <= 0 || health <= 0) return false;
        sinceHurt = 0;
        health = Math.max(0, health - amount);
        return health <= 0;
    }

    /**
     * Put them back on their feet at a point — what a respawn is, from here.
     *
     * <p>Bumps {@link #respawns}, which is what tells their own client to
     * teleport. Everything else about them is left alone: the satchel has
     * already been emptied onto the ground by the caller, and their stillness,
     * their breath and their glass are all facts about a person who is now
     * standing somewhere else.
     */
    public void respawnAt(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.health = 1;
        this.sinceHurt = HEAL_DELAY;
        this.breath = 1;
        this.submerged = false;
        this.boatId = 0;
        this.glassPower = Spyglass.NONE;
        // Arriving winded rather than settled: a walk that resumes with the
        // wood already ignoring you loses the minute of standing still that is
        // the price of everything else in this game.
        this.stillness = 0;
        this.lastSpeed = 0;
        this.respawns++;
    }

    /** Whether their head is under water. */
    public boolean submerged() { return submerged; }

    /** Whether the breath has run out and they are being floated up. */
    public boolean outOfBreath() { return breath <= 0; }

    /** Which boat they are in, or {@code 0}. */
    public long boatId() { return boatId; }

    /** Whether they are rowing rather than walking. */
    public boolean inBoat() { return boatId != 0; }

    /** The magnification they are looking through; {@code 1} is the naked eye. */
    public double glassPower() { return glassPower; }

    /** Whether they have a glass up at all. */
    public boolean glassing() { return glassPower > 1.02; }

    /** Raise or lower the glass. Clamped to what a tube in this game can do. */
    public void setGlassPower(double power) {
        double top = Spyglass.POWERS[Spyglass.POWERS.length - 1];
        this.glassPower = Math.max(Spyglass.NONE, Math.min(top, power));
    }

    /**
     * Whether this player is in debug mode.
     *
     * <p>The single gate every debug power is asked through — see
     * {@link Debug.Power}. One of them is already answered without asking,
     * because it is the satchel underneath that is bottomless.
     */
    public boolean debugging() { return debug; }

    /** Turn debug mode on or off, and the satchel's lens with it. */
    public void setDebug(boolean on) {
        this.debug = on;
        satchel.setBottomless(on);
    }

    /** Take the oars of a boat. */
    public void boardBoat(long id) { this.boatId = id; }

    /** Step out of whatever they were in. */
    public void leaveBoat() { this.boatId = 0; }

    /** Say whether the head is under, and spend or recover the breath. */
    public void setSubmerged(boolean under, double dt) {
        this.submerged = under;
        if (dt <= 0) return;
        if (under) {
            breath = Math.max(0, breath - dt / BREATH_SECONDS);
        } else {
            breath = Math.min(1, breath + dt * BREATH_RECOVERY / BREATH_SECONDS);
        }
    }

    /** What they are carrying. */
    /**
     * The light in this player's hand and <em>burning</em>, or {@code null}.
     *
     * <p>This is the one the snapshot carries and the one every renderer hangs
     * a light off, so it answers the question the picture asks — "is there a
     * flame at this person" — rather than the question the satchel asks. For
     * what is in the hand whether or not it is alight, see {@link #lamp()}.
     */
    public String carriedLight() { return lampLit ? lamp : null; }

    /** What is in the hand, lit or not. */
    public String lamp() { return lamp; }

    /** Whether it is actually burning. */
    public boolean lampLit() { return lampLit; }

    /** How many real hours of burning are left in it. */
    public double lampFuel() { return lampFuel; }

    /**
     * Take a light out of the satchel and light it.
     *
     * <p>A non-finite number of hours is taken as zero rather than kept, and
     * that is not defensive noise: a light that never runs out reports its
     * remaining fuel as {@link Double#POSITIVE_INFINITY}, which is a perfectly
     * good answer to "how long has it got" and a terrible thing to put in a
     * snapshot. An eternal lamp's fuel is simply never read.
     */
    public void carryLight(String itemKey, double fuelHours) {
        this.lamp = itemKey;
        this.lampFuel = Double.isFinite(fuelHours) ? Math.max(0, fuelHours) : 0;
        this.lampLit = itemKey != null;
    }

    /** Fill the one in hand, and light it. */
    public void fillLamp(double fuelHours) {
        if (lamp == null) return;
        this.lampFuel = Math.max(0, fuelHours);
        this.lampLit = true;
    }

    /** Light one that is in the hand and out. */
    public boolean relightLamp(boolean eternal) {
        if (lamp == null || lampLit || (!eternal && lampFuel <= 0)) return false;
        lampLit = true;
        return true;
    }

    /** Put it out, keeping whatever is left in it. */
    public void douseLamp() { this.lampLit = false; }

    /** Hands empty — what setting one down, spending one and dying all do. */
    public void dropLamp() {
        this.lamp = null;
        this.lampFuel = 0;
        this.lampLit = false;
    }

    /**
     * Burn the light in hand down by some real hours.
     *
     * <p>Whether a light is eternal — a jar of spores — is told by its
     * {@link com.larsons.engine.watch.light.LightKind} and passed in, rather
     * than known here: the caller is the thing holding the catalogue, and this
     * class stays a bag of state.
     *
     * @return whether it went out on this step
     */
    public boolean burnLamp(double hours, boolean eternal) {
        if (lamp == null || !lampLit || eternal || hours <= 0) return false;
        lampFuel = Math.max(0, lampFuel - hours);
        if (lampFuel > 0) return false;
        lampLit = false;
        return true;
    }

    public Satchel satchel() { return satchel; }

    /** Their wardrobe, and what is on. */
    public Outfit outfit() { return outfit; }

    /** Their rod, and whatever it is doing. */
    public Fishing rod() { return rod; }

    /** The speed of their last move, in metres per second. */
    public double speed() { return lastSpeed; }

    /**
     * Move to a position sent by the client, and work out what that did to
     * their stillness.
     *
     * <p>The server derives the speed from the positions rather than trusting a
     * "am I running" flag, so a client cannot claim to be creeping while
     * covering ten metres a second. It is also the only anti-cheat this game
     * needs: the worst a liar can achieve is being able to approach birds,
     * which is the whole game, so it is worth being right about.
     */
    public void moveTo(double x, double y, double z, double yaw, double pitch,
                       boolean crouching, double dt) {
        double dx = x - this.x, dy = y - this.y, dz = z - this.z;
        double moved = Math.sqrt(dx * dx + dy * dy + dz * dz);
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.crouching = crouching;
        if (dt <= 0) return;
        lastSpeed = moved / dt;
        updateStillness(dt);
    }

    /** Advance stillness and healing for a tick in which no new position arrived. */
    public void tick(double dt) {
        lastSpeed *= Math.max(0, 1 - dt * 4);
        updateStillness(dt);
        mend(dt);
    }

    /**
     * Let the bar come back, once nothing has hit them for a while.
     *
     * <p>Not scaled by stillness, deliberately. Tying healing to holding
     * position would be the obvious flourish and it is the wrong one: standing
     * still is what makes animals come to you, and making it also the way to
     * heal would turn the game's one voluntary, patient verb into a chore you
     * perform after every chase. You heal while walking home.
     */
    private void mend(double dt) {
        if (dt <= 0) return;
        sinceHurt += dt;
        if (health >= 1 || health <= 0 || sinceHurt < HEAL_DELAY) return;
        health = Math.min(1, health + dt / HEAL_SECONDS);
    }

    private void updateStillness(double dt) {
        // Crouching costs a fifth of what walking does, which is what makes
        // crouching worth the speed it costs.
        double effort = lastSpeed / (crouching ? CROUCH_SPEED : WALK_SPEED);
        if (effort < 0.06) {
            stillness = Math.min(1, stillness + dt / SETTLE_SECONDS);
        } else {
            double cost = effort * (crouching ? 0.2 : 1) * UNSETTLE_RATE / SETTLE_SECONDS;
            stillness = Math.max(0, stillness - dt * cost);
        }
    }

    /**
     * How far away this player <em>seems</em> to something at a point.
     *
     * <p>Not the plain distance: a settled player is treated as much further
     * away than they are, and one running is treated as much closer. This is
     * the number the whole approach mechanic is expressed in, and it lives here
     * rather than in the animal because it is a property of the player.
     */
    public double apparentDistanceTo(double px, double py) {
        double dx = px - x, dy = py - y;
        double distance = Math.sqrt(dx * dx + dy * dy);
        // 0.55× at a dead run, 3.2× standing still and crouched.
        double factor = 0.55 + stillness * (crouching ? 2.65 : 1.75);
        return distance * factor;
    }

    // --- persistence ----------------------------------------------------------------

    /** The fields that go in a snapshot, for everybody else to draw. */
    public Map<String, Object> toSnapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("n", name);
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        m.put("yaw", yaw);
        m.put("p", pitch);
        m.put("st", stillness);
        if (crouching) m.put("c", true);
        if (submerged) m.put("uw", true);
        if (breath < 1) m.put("air", breath);
        // Health goes out for the whole party, not only for its owner: seeing
        // that somebody else's bar is a third full is how eight people spread
        // over a valley find out that one of them has met something. It costs
        // one field per player per snapshot and only while they are hurt.
        if (health < 1) m.put("hp", health);
        if (respawns > 0) m.put("rs", respawns);
        if (boatId != 0) m.put("boat", boatId);
        if (glassing()) m.put("gl", glassPower);
        // What is lit in their hand, for everybody's renderer to hang a light
        // off. One short field, and only while something is actually burning.
        if (carriedLight() != null) {
            m.put("lt", carriedLight());
            // …and how much is left in it, in hours, to the second or so. Only
            // the owner's screen draws a gauge from it, but it rides on the
            // ordinary player row rather than on a private message because it
            // is three characters and the row is already going out.
            m.put("lh", Math.round(lampFuel * 1000) / 1000.0);
        }
        // In the snapshot as well as the save: a client has to know its own
        // satchel is bottomless or its build and cooking screens would grey out
        // everything the host would happily let it make.
        if (debug) m.put("dbg", true);
        // What they have on, for everybody's renderer — one short string, and
        // only when they are wearing anything at all. See Outfit.wornLine for
        // why the wardrobe behind it deliberately stays private.
        if (!outfit.bare()) m.put("w", outfit.wornLine());
        return m;
    }

    /** The fields that go in a save — everything of this player's own. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = toSnapshot();
        m.put("bag", satchel.toMap());
        // The wardrobe as well as the outfit. Only in the save and on this
        // player's own `bag` message: what somebody owns and is not wearing is
        // of no use to anybody else's screen, and a snapshot twenty times a
        // second is the wrong place for a list that changes once an evening.
        m.put("fit", outfit.toMap());
        // What is in the hand and how much is left in it. Only in the save:
        // nobody else's renderer needs the number, and a snapshot twenty times
        // a second is the wrong place for a figure that changes by a
        // thousandth of an hour. The key goes in again because the snapshot's
        // one is only there while it is <em>lit</em>, and a lamp put out for
        // the walk home is still a lamp in your hand.
        if (lamp != null) {
            m.put("lamp", lamp);
            m.put("ltf", lampFuel);
        }
        return m;
    }

    /** Restore from a save. */
    public void load(Map<String, Object> m) {
        x = WatchJson.num(m, "x", x);
        y = WatchJson.num(m, "y", y);
        z = WatchJson.num(m, "z", z);
        yaw = WatchJson.num(m, "yaw", yaw);
        pitch = WatchJson.num(m, "p", pitch);
        crouching = WatchJson.bool(m, "c", false);
        submerged = WatchJson.bool(m, "uw", false);
        breath = WatchJson.num(m, "air", 1);
        // A walk reopened is a walk begun: whatever was chasing you last night
        // is not there now, and starting a session on a sliver of health that
        // takes ninety seconds to come back is a punishment for having stopped
        // playing. What does survive is the count of how many times it has
        // happened, because that is a fact about the walk.
        health = 1;
        sinceHurt = HEAL_DELAY;
        respawns = WatchJson.integer(m, "rs", 0);
        boatId = WatchJson.big(m, "boat", 0);
        // A glass is not left up across a save: you put it in the satchel when
        // you stop for the night like everybody else.
        glassPower = Spyglass.NONE;
        // A lantern is the other way about, and deliberately: it burns for
        // hours, it is the thing you were relying on when you stopped playing,
        // and coming back to a dark wood with your lamp mysteriously out is the
        // save file taking something away. It keeps whatever oil it had, and it
        // is still burning if it was.
        lamp = WatchJson.str(m, "lamp", WatchJson.str(m, "lt", null));
        lampFuel = WatchJson.num(m, "ltf", 0);
        lampLit = lamp != null && WatchJson.str(m, "lt", null) != null;
        satchel.load(WatchJson.map(m, "bag"));
        // A walk reopened is a walk in the same coat: what was on when you
        // stopped playing is on when you come back, which is the opposite call
        // from the spyglass above and the same one as the lantern. Nobody puts
        // their hat away for the night.
        outfit.load(WatchJson.map(m, "fit"));
        // …but debug mode does survive: a walk played with everything unlimited
        // is that walk when it is reopened, and the code turns it off as easily
        // as it turned it on.
        setDebug(WatchJson.bool(m, "dbg", false));
    }

    @Override public String toString() {
        return name + " at (" + Math.round(x) + ", " + Math.round(y) + ")";
    }
}
