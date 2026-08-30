package com.larsons.engine.watch.life;

import java.util.List;

/**
 * The noises the three mutants make — <b>the key list, and nothing that plays
 * it.</b>
 *
 * <p>Which sounds exist is a fact about the creatures, so it lives beside them
 * in {@code watch/life}; <em>playing</em> one needs a listener, a viewport and a
 * mixer, so that lives in {@code WatchSounds} on the client. Keeping the two
 * apart is what lets {@link com.larsons.engine.audio.SoundKeys} enumerate every
 * file a creator can record without the audio system depending on the renderer.
 *
 * <h2>Everything else in this game is silent</h2>
 *
 * <p>Deliberately, and it is worth saying why the exception is here. The Field
 * Guide has thirteen hundred species and no sound at all: a wood full of
 * generated bird calls would be a wood where the calls are wallpaper, and the
 * game's whole proposition is that you find things by looking. Nothing is lost
 * by silence because nothing in it can hurt you.
 *
 * <p>Three things can. And a creature that hunts you is exactly the case where
 * sound is not decoration but <em>information</em>: it is the only channel that
 * works when the thing is behind you, and being behind you is what it is trying
 * to be. So these three have voices and the finches do not.
 *
 * <h2>The files</h2>
 *
 * <p>Drop a WAV or MP3 into the sound pack's {@code watch/} folder named
 * {@code <creature>_<state>} — {@code watch/wendigo_call.wav} — and it plays.
 * A file named for the creature alone ({@code watch/wendigo.wav}) answers for
 * every state it has not been given one of, which is the same progressive rule
 * every mob sound follows. Anything missing is silence, so a pack can be one
 * file or fifteen. See {@code resources/watch/sounds/README.md}.
 */
public final class MutantVoice {

    /**
     * The far-off cry, when one turns up in the world.
     *
     * <p>The most important of the five by a distance. It is the only warning a
     * player gets that the wood has stopped being safe, and it plays at a
     * distance where nothing is visible — see {@code WatchSounds.CALL_REACH},
     * which is deliberately wider than the range at which the creature is drawn
     * at all.
     */
    public static final String CALL = "call";

    /** The moment it picks somebody up and starts coming. */
    public static final String NOTICE = "notice";

    /** A footfall. Something this heavy is audible before it is visible. */
    public static final String STEP = "step";

    /** A swing connecting. */
    public static final String STRIKE = "strike";

    /** The wendigo winding up and letting a shard go. */
    public static final String HURL = "hurl";

    /** …and that shard arriving. */
    public static final String IMPACT = "impact";

    /** The five every one of them has. */
    private static final List<String> COMMON = List.of(CALL, NOTICE, STEP, STRIKE);

    /** …and the two only a thrower needs. */
    private static final List<String> RANGED = List.of(HURL, IMPACT);

    private MutantVoice() {}

    /**
     * The sound states one creature can be asked for.
     *
     * <p>Per creature rather than one list for all three, so that
     * {@code SOUND_KEYS.txt} does not tell a creator to record
     * {@code mirewraith_hurl} for something that has never thrown anything. A
     * key list that asks for files nothing will ever play is a key list people
     * stop trusting.
     */
    public static List<String> statesFor(Mutants.Kind kind) {
        if (kind == null || !kind.hurls()) return COMMON;
        List<String> out = new java.util.ArrayList<>(COMMON);
        out.addAll(RANGED);
        return List.copyOf(out);
    }

    /**
     * The sound key for one creature's one state — {@code watch/wendigo/call}.
     *
     * <p>The creature's <em>family</em> key rather than its species key, because
     * a mutant family holds exactly one species and {@code watch/wendigo_call}
     * is a file name a person can type. See {@link Mutants} on why the three of
     * them are families of one.
     */
    public static String key(Mutants.Kind kind, String state) {
        return "watch/" + kind.def().family().key() + "/" + state;
    }
}
