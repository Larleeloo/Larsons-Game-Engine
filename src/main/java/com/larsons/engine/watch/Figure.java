package com.larsons.engine.watch;

import java.util.List;

/**
 * Which person you walk as.
 *
 * <p><b>The one thing about yourself in this game that is not a number.</b>
 * A name is typed, a coat colour is your player id run through a hash, and
 * everything else you can change about a walker is bought off a rail. This is
 * the choice made before any of that: whose body the clothes go on.
 *
 * <h2>Two, and why exactly two</h2>
 *
 * <p>Not because two is a complete account of people — it is obviously not —
 * but because a figure in this game is a modelled body with five animation
 * clips <em>and</em> eighteen garments cut to its own measurements, and the
 * honest number of those is the number somebody has actually built. Adding a
 * third is a row in this file, a {@code .glb} beside the other two, and one
 * run of {@code tools/blender/cosmetics.py}; it is not a code change anywhere
 * else, which is the property this class exists to have.
 *
 * <h2>What a figure decides, and what it deliberately does not</h2>
 *
 * <p>It picks two files and nothing else:
 *
 * <ul>
 *   <li>{@link #model()} — the body, {@code characters/<key>}, drawn in third
 *       person, for everybody else in the clearing, in a boat and in the
 *       water. Five clips each, because five is every state a walker is drawn
 *       in;</li>
 *   <li>{@code cosmetics/<key>/} — the wardrobe cut to it. A worn piece is
 *       never measured and never rescaled, so a collar cut for one chest
 *       stands off another; see
 *       {@link com.larsons.engine.watch.render.CosmeticModel#importedFor}.</li>
 * </ul>
 *
 * <p><b>Nothing else about a walker changes with it.</b> Not the height, not
 * the reach, not the speed, not the eye. Both figures are 1.78 m to the crown
 * and {@code WalkerModel.HEIGHT} is still one number, because the moment a
 * figure were faster or could reach further this would stop being a thing you
 * pick because you like it and start being a thing you pick because it wins —
 * which is the same argument {@link Cosmetics} makes about hats, and it is the
 * same answer.
 *
 * <h2>Where the choice lives</h2>
 *
 * <p>On the {@link WatchPlayer}, beside the outfit and for the same reasons:
 * it belongs to the person rather than to the party, it rides everybody's
 * snapshot row because a body is worn to be seen, and it survives a save. It
 * can be changed from the lobby before setting off and from the pause screen
 * in the middle of a walk, because there is no reason on earth it should need
 * a trip to a shop.
 */
public enum Figure {

    /**
     * The figure this game has drawn since people had bodies at all.
     *
     * <p>Square-shouldered, field coat, campaign hat, a pack on the back and a
     * beard under it. Built by {@code tools/blender/ranger.py} — the same file
     * that builds the ranger outside the trading post, which is why the two of
     * them look like they work for the same outfit.
     */
    WALKER("walker", "Walker", "swept_hair",
            "Square in the shoulder, campaign hat, field coat. The one this "
                    + "game has always drawn."),

    /**
     * The second one.
     *
     * <p>Somebody else doing the same job in the same coat: narrower through
     * the shoulder, a waist cut above the belt rather than at it, more flare in
     * the skirt, a soft felt hat, and a plait over one shoulder. Built by
     * {@code tools/blender/wayfarer.py}.
     *
     * <p><b>A different person rather than a smaller one</b>, which is the
     * usual way a second figure goes wrong: the shoulders come in 30 mm while
     * the hips stay where they are, the head loses 25 mm across while the neck
     * gains 25 mm of length, and the legs take 25 mm off the torso. Scaled
     * down instead, it would have been a child.
     */
    WAYFARER("wayfarer", "Wayfarer", "long_plait",
            "Slighter in the shoulder, soft hat, long coat, hair in a plait.");

    /** What a walker is when nobody has said otherwise, and what a save without
     *  a figure in it loads as — which is every save written before this
     *  existed, and they are all of them walkers. */
    public static final Figure DEFAULT = WALKER;

    private static final List<Figure> ALL = List.of(values());

    private final String key;
    private final String label;
    private final String hair;
    private final String note;

    Figure(String key, String label, String hair, String note) {
        this.key = key;
        this.label = label;
        this.hair = hair;
        this.note = note;
    }

    /**
     * The stable identifier — what a save, the wire and the model folder all
     * spell it.
     *
     * <p>Lower case and never the enum's own {@link #name()}, so that renaming
     * a constant is a refactor rather than a save-breaking change.
     */
    public String key() { return key; }

    /** What a screen calls it. */
    public String label() { return label; }

    /** One line under the row, for a menu that has room for it. */
    public String note() { return note; }

    /**
     * The hairstyle this figure sets off in.
     *
     * <p>The body is bald — hair is a worn piece like everything else, so that
     * it can be changed — and somebody has to say which of the four a walker
     * starts in. It is here rather than in {@link Cosmetics} because it is the
     * one thing about the standard kit that differs between the two of them,
     * and it is a fact about a <em>person</em> rather than about a garment.
     * Everybody owns all four from the start; this only picks the one that is
     * already on.
     */
    public String hair() { return hair; }

    /**
     * The model this figure is drawn from — {@code characters/<key>}.
     *
     * <p>Read by {@code WalkerModel}, and looked for by {@code SceneModels}'s
     * ordinary rules: beside the jar first and then the classpath, {@code .glb}
     * then {@code .gltf} then {@code .obj}. A missing or broken file leaves
     * that figure as the procedural boxes with one line on stderr, which is
     * also what makes it safe for somebody to drop their own body in over
     * either of these.
     */
    public String model() { return "characters/" + key; }

    /** Every figure, in the order a menu offers them. */
    public static List<Figure> all() { return ALL; }

    /** The figure with this key, or {@code null} for anything else. */
    public static Figure byKey(String key) {
        if (key == null) return null;
        for (Figure figure : ALL) {
            if (figure.key.equals(key)) return figure;
        }
        return null;
    }

    /**
     * The figure with this key, or {@link #DEFAULT}.
     *
     * <p>What every reader off a save or off the wire uses. A key this build
     * does not know is a walker rather than a crash or an invisible player:
     * the one guarantee worth having here is that somebody joining from a
     * version with a third figure in it is still <em>drawn</em>.
     */
    public static Figure of(String key) {
        Figure found = byKey(key);
        return found == null ? DEFAULT : found;
    }

    /** The next one round, so a screen can offer this as one key rather than a list. */
    public Figure next() {
        return ALL.get((ordinal() + 1) % ALL.size());
    }

    /** The label, so a {@code ConfigForm} row reads as a person and not as a constant. */
    @Override public String toString() { return label; }
}
