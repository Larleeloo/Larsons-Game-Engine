package com.larsons.engine.watch.render;

import com.larsons.engine.watch.Cosmetics;
import com.larsons.engine.watch.Figure;
import com.larsons.engine.watch.life.AnimState;
import com.larsons.engine.watch.model.ModelRig;
import com.larsons.engine.watch.model.SceneModel;
import com.larsons.engine.watch.model.SceneModels;
import com.larsons.engine.watch.world.WatchMaterial;
import com.larsons.engine.watch.world.WatchMaterials;

import java.util.ArrayList;
import java.util.List;

/**
 * The clothes, drawn on top of the figure that is already there.
 *
 * <h2>Why this is not part of {@link WalkerModel}</h2>
 *
 * <p>A walker is one pose solved four ways — standing, jumping, rowing,
 * swimming — and every line of it is about where a joint ends up. A hat is
 * about none of that: it is a small pile of boxes that wants to know where the
 * top of a head is and nothing else. Kept in {@code WalkerModel} the two would
 * be interleaved, and the next person to retime the gait would be reading a
 * catalogue of hatbands while they did it.
 *
 * <p>So the walker solves its pose and then hands this class the <b>places</b>
 * it found — a {@link Fit} each for the head, the face, the neck, the back,
 * each hand and each boot. That split is what makes the same eighteen
 * descriptions work on a standing figure and on a swimmer whose spine is
 * pointing at the lake bed: nothing here knows which it is drawing on, because
 * a fit carries its own idea of up.
 *
 * <h2>Everything is measured in the part it hangs on</h2>
 *
 * <p>Every offset and every half-extent below is written in {@link Fit#size()}
 * — the half-width of the head, the hand, the boot it is worn on — and never in
 * metres. That is not tidiness: {@code WalkerModel.HEIGHT} is a constant today
 * and a crouching walker is already {@code CROUCH_HEIGHT}, so a mitten written
 * as "five and a half centimetres" is a mitten that comes off the hand the
 * first time anybody scales a figure. Written as {@code 1.28 × size} it stays
 * on.
 *
 * <h2>Two of them wear the wearer's colour</h2>
 *
 * <p>A hood and a cape are the only pieces big enough to hide a walker's
 * outline, and the outline is how a party tells each other apart at two hundred
 * metres ({@link WalkerModel#coatFor}). Those two are drawn in the coat colour
 * they are covering rather than in a colour of their own — see
 * {@link Cosmetics.Piece#tinted()} — so six people in matching oilskins are
 * still six people. Everything else is small enough to be its own colour.
 *
 * <h2>Every one of them can be replaced by a modelled one</h2>
 *
 * <p>Drop {@code watch/models/cosmetics/<figure>/<key>.glb} beside the jar or on
 * the classpath and it is drawn instead of the boxes below — the same drop-in
 * the ranger and the thirteen hundred animals already have ({@link SceneModels}),
 * under the same rules, failing the same soft way. That is what
 * {@link #importedFor} answers and it is the whole of the mechanism.
 *
 * <p><b>Named for the wearer's {@link Figure}</b>, because a worn piece is the
 * one model in this game that is never measured and never rescaled, and a
 * garment that is not rescaled is a garment cut to a body. The wardrobe this
 * game ships is built twice for that reason —
 * {@code tools/blender/cosmetics.py} — and the unqualified
 * {@code cosmetics/<key>.glb} beside it still works, for a piece that really
 * does fit everybody.
 *
 * <p><b>An imported piece is a rigged figure, not a box on an anchor.</b> The
 * descriptions below are written against a {@link Fit} — a point on one body
 * part — because that is the cheapest way to place a box. A modelled piece is
 * the other thing entirely: a cape is authored <em>in place</em> on a reference
 * walker standing at the origin, rigged to the same bone names a character uses
 * ({@code head}, {@code spine}, {@code arm_l}, {@code leg_r}…), and drawn at
 * that walker's feet in that walker's pose. So it follows the joints it is
 * hung on, it can span two of them, and — the point of the whole exercise — an
 * artist can ship a {@code walk} clip and have the cloak swing on its own.
 * {@link SceneModel.Size#AS_PLACED} is what keeps the hat at the height it was
 * modelled at.
 *
 * <p>Two consequences worth knowing before you model one:
 *
 * <ul>
 *   <li>a piece with an imported model is <b>skipped here</b> and drawn by
 *       {@link #overlay} instead — {@link #boxesOnly} is the filter, and the
 *       reason it is a filter rather than a branch inside {@link #wear} is that
 *       the two are drawn at different <em>times</em>: one per joint, one per
 *       figure;</li>
 *   <li>and only where there is a walker to hang it on. A
 *       {@linkplain WalkerModel#swimmer swimmer} and a
 *       {@linkplain WalkerModel#rower rower} are posed by numbers no clip
 *       knows, so they keep the boxes. See {@link #overlay}.</li>
 * </ul>
 */
public final class CosmeticModel {

    private CosmeticModel() {}

    /**
     * A place on a body where something is worn.
     *
     * <p>An origin, a way up, and a size — plus the yaw the boxes are turned
     * about, which is the body's and not the fit's. That last one is the same
     * compromise {@code WalkerModel.head} already makes for a swimmer's hat: a
     * piece <b>stacks</b> along the part it is worn on, so a prone swimmer's
     * veil hangs down their face rather than off the side of it, while the
     * boxes themselves stay square to the world's vertical. Nothing in this
     * catalogue is long enough for the difference to read, and the alternative
     * is a full basis on every box in the file.
     *
     * @param upX  the way the part points; need not be a unit vector
     * @param yaw  which way the wearer faces, in radians
     * @param size the half-width of the part this hangs on, in metres — the
     *             unit every offset below is written in
     */
    public record Fit(double x, double y, double z,
                      double upX, double upY, double upZ,
                      double yaw, double size) {

        /** Straight up, which is where every part of a standing figure points. */
        public static Fit upright(double x, double y, double z, double yaw, double size) {
            return new Fit(x, y, z, 0, 0, 1, yaw, size);
        }

        public Fit {
            double length = Math.sqrt(upX * upX + upY * upY + upZ * upZ);
            if (length < 1e-9) {
                upX = 0;
                upY = 0;
                upZ = 1;
            } else {
                upX /= length;
                upY /= length;
                upZ /= length;
            }
        }

        /**
         * Which way the wearer is looking, as far as it is across the fit's own
         * up — a unit vector.
         *
         * <p>Projected rather than taken flat, because a swimmer lying on their
         * face has a spine pointing along the world's forward: unprojected, the
         * "front of the head" a pair of spectacles is put on would be somewhere
         * inside the skull. Falls back to the wearer's own right when the two
         * are parallel, which is a walker looking straight up or straight down
         * and cannot be told apart by any face they are wearing.
         */
        double[] forward() {
            double fx = Math.sin(yaw), fy = -Math.cos(yaw), fz = 0;
            double along = fx * upX + fy * upY + fz * upZ;
            double px = fx - upX * along, py = fy - upY * along, pz = fz - upZ * along;
            double length = Math.sqrt(px * px + py * py + pz * pz);
            if (length < 1e-6) {
                return new double[]{Math.cos(yaw), Math.sin(yaw), 0};
            }
            return new double[]{px / length, py / length, pz / length};
        }

        /**
         * The wearer's right: up × forward, which is right-handed with both.
         *
         * <p>Takes the forward it is crossed with rather than asking for it,
         * so that placing one box costs one {@link #forward} and not two. It is
         * a sine and a cosine either way, and this is called a few hundred
         * times a frame with a party in the wood.
         */
        double[] right(double[] forward) {
            return new double[]{upY * forward[2] - upZ * forward[1],
                    upZ * forward[0] - upX * forward[2],
                    upX * forward[1] - upY * forward[0]};
        }
    }

    /**
     * Draw whatever of {@code worn} belongs in {@code slot}, at {@code fit}.
     *
     * <p>Called once per slot by whoever is drawing the figure. A slot with
     * nothing in it costs one walk of a list at most six long and emits no
     * triangles — which is the whole of what "optional" means here: a walker
     * wearing nothing is exactly the walker this game drew before any of this
     * existed.
     *
     * @param worn what the wearer has on, as {@code Outfit.wornKeys}
     * @param coat the wearer's own coat colour, for the pieces that take it
     */
    public static void wear(Mesh.Builder mesh, List<String> worn, Cosmetics.Slot slot,
                            Fit fit, int coat) {
        if (worn == null || worn.isEmpty()) return;
        for (String key : worn) {
            Cosmetics.Piece piece = Cosmetics.byKey(key);
            if (piece == null || piece.slot() != slot) continue;
            draw(mesh, piece, fit, coat);
            // One piece to a slot — see Outfit.wear — so there is nothing after
            // this worth looking at.
            return;
        }
    }

    // --- modelled pieces ------------------------------------------------------------

    /** Where an imported piece is filed, under the models folder. */
    public static final String FOLDER = "cosmetics/";

    /**
     * The folder a piece cut to one figure lives in — {@code cosmetics/<key>/}.
     *
     * <p><b>A wardrobe is fitted to a body, and there is more than one body.</b>
     * Every other model in this game is measured and rescaled — one file dresses
     * a hummingbird and an elk. A worn piece is {@link SceneModel.Size#AS_PLACED}:
     * the metre it was modelled at is the metre it is worn at, which is right,
     * and which means a collar cut for a 0.33 m chest stands 40 mm off a 0.28 m
     * one and a hat cut to cover a square crown swallows a round one. So the
     * folder named for the wearer's own {@link Figure} is looked in first.
     */
    private static String fittedTo(Figure figure, String key) {
        return FOLDER + (figure == null ? Figure.DEFAULT : figure).key() + "/" + key;
    }

    /**
     * How the file is read: as a person, at the size and the height it was
     * modelled at.
     *
     * <p>{@link ModelRig.Kind#HUMANOID} because the thing wearing it is one and
     * its bones are named for one — a cape rigged to {@code spine} has to bind
     * to a body and not to a flank. {@link SceneModel.Size#AS_PLACED} because a
     * cosmetic is the one model in this game that is <em>not</em> measured: it
     * was authored on a reference walker, in metres, at the height it belongs
     * at, and both of those are answers rather than accidents.
     */
    private static final SceneModel.Size WORN_SIZE = SceneModel.Size.AS_PLACED;

    /**
     * The modelled version of a piece as this figure wears it, or {@code null}
     * for one that is still boxes.
     *
     * <p>Every rule about where the file may be and what may be wrong with it is
     * {@link SceneModels}'s, unchanged: beside the jar first and then the
     * classpath, {@code .glb} then {@code .gltf} then {@code .obj}, and anything
     * missing, truncated or empty leaves the boxes in place with one line on
     * stderr. Nothing a player drops in this folder can stop the game starting.
     *
     * <p><b>Two places, and they mean different things.</b>
     *
     * <ol>
     *   <li>{@code cosmetics/<figure>/<key>} — cut to that figure's modelled
     *       body, and used <em>only while that body is the one being drawn</em>.
     *       A piece is never rescaled, so one authored on the {@code .glb}
     *       walker — hat brim at 1.59, shoulders at 1.18 — is authored against
     *       that figure's landmarks and not against the procedural boxes
     *       underneath it, whose hat brim is at 1.85. With the boxes showing,
     *       the two sets of numbers must not meet.</li>
     *   <li>{@code cosmetics/<key>} — the drop-in slot that was here before
     *       there was a choice of figure, authored against §16's reference
     *       walker, and used whatever is being drawn. Everything already in it
     *       goes on working exactly as it did, and it stays the right place for
     *       a piece that genuinely fits anybody — a lanyard, a pair of
     *       spectacles.</li>
     * </ol>
     */
    public static SceneModel importedFor(Figure figure, String key) {
        if (key == null) return null;
        if (WalkerModel.imported(figure)) {
            SceneModel fitted = SceneModels.of(fittedTo(figure, key),
                    ModelRig.Kind.HUMANOID, WORN_SIZE);
            if (fitted != null) return fitted;
        }
        return SceneModels.of(FOLDER + key, ModelRig.Kind.HUMANOID, WORN_SIZE);
    }

    /** The same, for the figure this game drew before there was a choice. */
    public static SceneModel importedFor(String key) {
        return importedFor(Figure.DEFAULT, key);
    }

    /** Whether anybody has modelled this piece for this figure. */
    public static boolean modelled(Figure figure, String key) {
        return importedFor(figure, key) != null;
    }

    /** Whether anybody has modelled this piece at all. */
    public static boolean modelled(String key) { return modelled(Figure.DEFAULT, key); }

    /**
     * The worn keys that are still boxes — what a {@link Fit} is handed.
     *
     * <p>Returns the list it was given when none of it is modelled, which is
     * every walker in an installation nobody has dropped a file into: the common
     * path allocates nothing and the uncommon one allocates a list six long.
     */
    public static List<String> boxesOnly(Figure figure, List<String> worn) {
        if (worn == null || worn.isEmpty()) return worn;
        List<String> out = null;
        for (int i = 0; i < worn.size(); i++) {
            if (!modelled(figure, worn.get(i))) {
                if (out != null) out.add(worn.get(i));
                continue;
            }
            if (out == null) out = new ArrayList<>(worn.subList(0, i));
        }
        return out == null ? worn : out;
    }

    /** The same, for the figure this game drew before there was a choice. */
    public static List<String> boxesOnly(List<String> worn) {
        return boxesOnly(Figure.DEFAULT, worn);
    }

    /**
     * Every modelled piece somebody is wearing, drawn over a standing figure.
     *
     * <p>One call per piece, each at the walker's own feet, facing the way they
     * face, at the walker's own scale — so a crouching walker's cape crouches
     * with them — and posed by the walker's own state and gait clock. A piece
     * that ships a clip for that state plays it; one that does not is posed by
     * {@code ModelRig}'s humanoid table, exactly as a half-finished ranger is.
     * That is what makes a model with no animation in it worth committing.
     *
     * <p><b>Only the standing figure calls this.</b> A swimmer is laid along a
     * spine that may point anywhere and a rower is folded onto a thwart, and
     * neither is a pose {@link SceneModel} can be asked for — it takes a yaw and
     * no more. So those two keep the boxes, which is a visible inconsistency
     * (your hat changes shape when you dive) and the honest one: the alternative
     * is a full-length oilskin standing bolt upright in the middle of a lake.
     *
     * @param z      the ground under their feet, in world metres
     * @param height how tall this walker is — {@code WalkerModel.HEIGHT}, or the
     *               crouched one
     * @param speed  how fast they are moving, which is what picks the clip
     * @param phase  the gait clock, in turns, so a walk cycle is in step with
     *               the legs underneath it
     */
    public static void overlay(Mesh.Builder mesh, Figure figure, List<String> worn,
                               double x, double y, double z, double yaw, double height,
                               double speed, double phase, float[] uv) {
        overlay(mesh, figure, worn, x, y, z, yaw, height, stateFor(speed), phase, uv,
                null);
    }

    /**
     * The same, drawn through the motion of the body underneath.
     *
     * <p><b>This is what makes a hat bob with the head it is on.</b> A worn
     * piece and the body are two models with two rigs drawn by two calls, and
     * left to themselves the body plays its authored {@code walk} — root dropped
     * to put the lower boot on the floor, chest leaning, head nodding — while
     * the garment, having no clip, is posed by the procedural stand-in instead.
     * Standing still nobody could see it; at a run the head came 50 mm out from
     * under the hat once a stride. {@link SceneModel.Worn} is the body saying
     * where its joints went, and everything here is carried along with them.
     *
     * <p>The wearer's {@linkplain SceneModel.Lean tip} is inside {@code carry}
     * rather than beside it — a swimmer's cloak lies down with them and this
     * method never has to know what leaning is. See {@link SceneModel#wornAt}.
     *
     * @param carry the wearer's {@link SceneModel#wornAt}, or {@code null} for
     *              the boxes, which have no clips for anything to follow
     */
    public static void overlay(Mesh.Builder mesh, Figure figure, List<String> worn,
                               double x, double y, double z, double yaw, double height,
                               AnimState state, double phase, float[] uv,
                               SceneModel.Worn carry) {
        if (worn == null || worn.isEmpty()) return;
        double scale = height / WalkerModel.HEIGHT;
        for (String key : worn) {
            SceneModel model = importedFor(figure, key);
            if (model == null) continue;
            model.mesh(mesh, x, y, z, yaw + SceneModel.PERSON_TURN, state, phase,
                    scale, uv, 0, null, SceneModel.Lean.UPRIGHT, carry);
        }
    }

    /** The same, for the figure this game drew before there was a choice. */
    public static void overlay(Mesh.Builder mesh, List<String> worn, double x, double y,
                               double z, double yaw, double height, double speed,
                               double phase, float[] uv) {
        overlay(mesh, Figure.DEFAULT, worn, x, y, z, yaw, height, speed, phase, uv);
    }

    /**
     * Which clip a walker at this speed is asking for.
     *
     * <p>Three states, because three is what a person in this game does with
     * their legs and because every one of them has a procedural fallback behind
     * it. Read off the speed rather than passed in: the speed is already the
     * number the gait is driven by, so a cloak's clip and the legs under it
     * cannot disagree about whether this is a walk.
     */
    private static AnimState stateFor(double speed) {
        if (speed >= RUNNING) return AnimState.RUN;
        return speed >= WALKING ? AnimState.WALK : AnimState.IDLE;
    }

    /**
     * How fast counts as walking, and as running, in metres per second.
     *
     * <p>{@code WALKING} is low deliberately — a hair above standing still —
     * because the alternative is a figure whose legs are swinging while their
     * coat is on the idle clip. {@code RUNNING} is between this game's walk
     * (4.4) and its sprint (8.0).
     */
    private static final double WALKING = 0.35, RUNNING = 6.2;

    /**
     * One piece, standing on its own at the origin — what a portrait renders.
     *
     * <p>The same descriptions the figure wears, at the same scale relative to
     * the part they hang on, which is the point: a picture drawn from a second
     * description would eventually be a picture of a hat nobody owns. A
     * {@linkplain #importedFor modelled} piece answers with its own geometry
     * here too, for that reason and not for a different one.
     *
     * @param size what to treat the missing body part as being — pass the
     *             {@linkplain #portraitSize natural size} for its slot
     */
    public static void alone(Mesh.Builder mesh, Figure figure, String key, double x,
                             double y, double z, double yaw, double size, int coat) {
        Cosmetics.Piece piece = Cosmetics.byKey(key);
        if (piece == null) return;
        SceneModel model = importedFor(figure, key);
        if (model != null) {
            // Standing still at the origin, and the frame is found by measuring
            // the triangles — so a hat authored at head height comes back as a
            // picture of a hat rather than a picture of the empty metre and a
            // half under it. See ItemPortrait.
            float[] uv = new float[4];
            WatchMaterials.uv(WatchMaterial.PLANK, uv);
            model.mesh(mesh, x, y, z, yaw + SceneModel.PERSON_TURN, AnimState.IDLE,
                    0, 1, uv);
            return;
        }
        draw(mesh, piece, Fit.upright(x, y, z, yaw, size), coat);
    }

    /** The same, for the figure this game drew before there was a choice. */
    public static void alone(Mesh.Builder mesh, String key, double x, double y, double z,
                             double yaw, double size, int coat) {
        alone(mesh, Figure.DEFAULT, key, x, y, z, yaw, size, coat);
    }

    /**
     * How big the body part under a slot is, in metres — the head's half-width,
     * the hand's, the boot's.
     *
     * <p>Taken from {@code WalkerModel}'s own boxes and public because a
     * portrait has no body to measure and would otherwise have to guess.
     */
    public static double portraitSize(Cosmetics.Slot slot) {
        return switch (slot) {
            case HANDS -> 0.055;
            case FEET -> 0.085;
            default -> 0.115;
        };
    }

    /**
     * How far in front of the neck the front of a chest is, in sizes.
     *
     * <p>{@code WalkerModel}'s chest is 0.22 m from its middle to its face and
     * a neck fit stands on that middle, so this is the number every scarf tail
     * and every lanyard in the file is written against. It is here rather than
     * in each of them because it is a fact about the walker, and the first
     * version of three of these pieces was drawn inside somebody's ribs for
     * want of it.
     */
    private static final double CHEST_OUT = 2.05;

    private static void draw(Mesh.Builder mesh, Cosmetics.Piece piece, Fit fit, int coat) {
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.PLANK, uv);
        // A tinted piece is drawn in the coat it is covering, darkened, so that
        // it still reads as a separate garment lying over one rather than as the
        // coat having grown. See the class note for why only two pieces are.
        int rgb = piece.tinted() ? ShopModel.shade(coat, 0.72) : piece.rgb();
        int trim = piece.trim();

        switch (piece.key()) {
            // --- head ------------------------------------------------------
            case "knitted_beanie" -> {
                box(mesh, fit, 0, 0, -0.55, 1.32, 1.32, 0.68, uv, rgb);
                box(mesh, fit, 0, 0, -1.10, 1.42, 1.42, 0.24, uv, trim);
                blob(mesh, fit, 0, 0, 0.42, 0.34, uv, ShopModel.shade(trim, 1.15));
            }
            case "feathered_band" -> {
                box(mesh, fit, 0, 0, -0.48, 1.30, 1.30, 0.22, uv, rgb);
                // One primary, stuck in at the side and swept back over the
                // crown — a flat strut rather than a box, because the whole of
                // what makes it read as a feather is that it leans.
                strut(mesh, fit, 1.05, -0.10, -0.35, 1.30, -1.45, 1.85,
                        0.17, 0.035, uv, trim);
            }
            case "straw_boater" -> {
                box(mesh, fit, 0, 0, -0.72, 2.55, 2.55, 0.16, uv, rgb);
                box(mesh, fit, 0, 0, 0.18, 1.26, 1.26, 0.78, uv, rgb);
                box(mesh, fit, 0, 0, -0.42, 1.32, 1.32, 0.22, uv, trim);
            }
            case "oilskin_hood" -> {
                // Up over the head rather than thrown back, which is the
                // version that changes a silhouette — and the reason this is
                // one of the two pieces drawn in the wearer's own colour.
                box(mesh, fit, 0, -0.15, -1.90, 1.58, 1.62, 1.62, uv, rgb);
                box(mesh, fit, 0, 1.70, -1.15, 1.42, 0.62, 0.15, uv, trim);
                box(mesh, fit, 0, -1.35, -2.90, 1.45, 0.45, 0.85, uv,
                        ShopModel.shade(rgb, 0.88));
            }
            case "antler_circlet" -> {
                box(mesh, fit, 0, 0, -0.52, 1.32, 1.32, 0.18, uv, trim);
                for (int side = -1; side <= 1; side += 2) {
                    // A beam up and out, a brow tine forward off it and a second
                    // tine back — three struts, which is the fewest that reads
                    // as an antler rather than as a stick.
                    strut(mesh, fit, side * 0.95, 0.05, -0.40,
                            side * 1.95, -0.25, 2.30, 0.13, 0.13, uv, rgb);
                    strut(mesh, fit, side * 1.45, -0.10, 1.05,
                            side * 2.35, 1.10, 1.75, 0.09, 0.09, uv, rgb);
                    strut(mesh, fit, side * 1.95, -0.25, 2.30,
                            side * 2.90, -1.05, 2.95, 0.09, 0.09, uv, rgb);
                }
            }

            // --- face ------------------------------------------------------
            case "wire_spectacles" -> {
                for (int side = -1; side <= 1; side += 2) {
                    box(mesh, fit, side * 0.50, 1.08, 0.10, 0.42, 0.07, 0.36, uv, trim);
                    box(mesh, fit, side * 1.02, 0.20, 0.10, 0.07, 0.90, 0.07, uv, rgb);
                }
                box(mesh, fit, 0, 1.08, 0.10, 0.16, 0.07, 0.08, uv, rgb);
            }
            case "snow_goggles" -> {
                box(mesh, fit, 0, 0.94, 0.18, 1.10, 0.22, 0.46, uv, rgb);
                box(mesh, fit, 0, 1.08, 0.18, 0.98, 0.14, 0.32, uv, trim);
                for (int side = -1; side <= 1; side += 2) {
                    box(mesh, fit, side * 1.02, 0.05, 0.18, 0.10, 1.05, 0.15, uv, rgb);
                }
            }
            case "moth_veil" -> {
                box(mesh, fit, 0, 0, 1.02, 1.24, 1.24, 0.10, uv, trim);
                box(mesh, fit, 0, 1.08, -0.40, 1.02, 0.06, 1.20, uv, rgb);
                for (int side = -1; side <= 1; side += 2) {
                    box(mesh, fit, side * 1.06, 0.10, -0.40, 0.06, 1.02, 1.20, uv, rgb);
                }
            }

            // --- neck ------------------------------------------------------
            // <b>Everything here clears CHEST_OUT.</b> The chest is 0.22 m
            // deep — nearly two sizes — so a scarf tail written at "just in
            // front of the neck" is a scarf tail inside the person wearing it.
            case "wool_scarf" -> {
                box(mesh, fit, 0, 0, 0, 1.55, 2.05, 0.40, uv, rgb);
                box(mesh, fit, 0, CHEST_OUT + 0.15, -0.30, 0.46, 0.34, 0.42, uv, trim);
                // One end left long, which is the only part of a scarf that
                // does anything at all once it is on somebody.
                box(mesh, fit, 0.62, CHEST_OUT + 0.05, -1.80, 0.38, 0.18, 1.55, uv, rgb);
            }
            case "glass_lanyard" -> {
                box(mesh, fit, 0, 0, 0.08, 1.42, 1.92, 0.18, uv, rgb);
                for (int side = -1; side <= 1; side += 2) {
                    box(mesh, fit, side * 0.50, CHEST_OUT, -1.70, 0.16, 0.10, 1.80,
                            uv, rgb);
                }
                blob(mesh, fit, 0, CHEST_OUT + 0.10, -3.45, 0.34, uv, trim);
            }
            case "fur_collar" -> {
                box(mesh, fit, 0, 0, 0.10, 1.90, 2.25, 0.62, uv, rgb);
                box(mesh, fit, 0, 0, -0.56, 1.66, 2.00, 0.22, uv, trim);
            }

            // --- back ------------------------------------------------------
            case "rolled_bedroll" -> {
                strut(mesh, fit, -1.10, 0, 1.38, 1.10, 0, 1.38, 0.42, 0.42, uv, rgb);
                for (int side = -1; side <= 1; side += 2) {
                    box(mesh, fit, side * 0.58, 0, 1.38, 0.10, 0.52, 0.50, uv, trim);
                }
            }
            case "oilskin_cape" -> {
                box(mesh, fit, 0, 0, 1.95, 2.05, 0.78, 0.30, uv, rgb);
                box(mesh, fit, 0, -0.32, -2.50, 2.20, 0.22, 3.55, uv,
                        ShopModel.shade(rgb, 0.92));
                box(mesh, fit, 0, -0.32, 1.30, 2.20, 0.22, 0.26, uv, trim);
            }
            case "heron_cloak" -> {
                box(mesh, fit, 0, -0.10, 1.95, 2.10, 0.82, 0.34, uv,
                        ShopModel.shade(rgb, 0.9));
                box(mesh, fit, 0, -0.32, -2.60, 2.35, 0.22, 3.60, uv, rgb);
                // Courses of feather down the panel — the same trick
                // {@code ShopModel} plays on a roof, and for its reason: lines
                // across a slab read as a made surface and a bare slab reads as
                // a board.
                for (int course = 0; course < 4; course++) {
                    box(mesh, fit, 0, -0.48, 0.30 - course * 1.65, 2.28, 0.14, 0.34, uv,
                            ShopModel.shade(trim, course % 2 == 0 ? 1.0 : 0.88));
                }
            }

            // --- hands -----------------------------------------------------
            case "wool_mittens" -> {
                box(mesh, fit, 0, 0, 0, 1.30, 1.30, 1.30, uv, rgb);
                box(mesh, fit, 0, 0, 1.38, 1.16, 1.16, 0.44, uv, trim);
            }
            case "leather_gloves" -> {
                box(mesh, fit, 0, 0, 0, 1.18, 1.18, 1.18, uv, rgb);
                box(mesh, fit, 0, 0, 1.16, 1.22, 1.22, 0.26, uv, trim);
            }

            // --- feet ------------------------------------------------------
            case "canvas_gaiters" -> {
                box(mesh, fit, 0, 0, 2.55, 0.98, 1.02, 2.30, uv, rgb);
                box(mesh, fit, 0, 0, 4.60, 1.06, 1.10, 0.24, uv, trim);
                box(mesh, fit, 0, 0, 0.45, 1.06, 1.10, 0.22, uv, trim);
            }
            case "river_waders" -> {
                box(mesh, fit, 0, 0, 0.10, 1.14, 1.10, 1.10, uv, rgb);
                box(mesh, fit, 0, 0, 2.70, 1.20, 1.26, 2.50, uv, rgb);
                box(mesh, fit, 0, 0, 5.05, 1.26, 1.32, 0.26, uv, trim);
            }

            // A key with no drawing is not drawn. Cosmetics.byKey has already
            // said it is a real piece, so this is the one case that matters:
            // somebody added a row to the catalogue and not a case here, and
            // CosmeticsTest.everyPieceInTheCatalogueDraws is what tells them.
            default -> { }
        }
    }

    /**
     * A box at an offset in the fit's own frame.
     *
     * @param across how far to the wearer's right, in sizes
     * @param ahead  how far in front of them
     * @param along  how far up the part it is worn on
     * @param hx     half-extent across the wearer, in sizes; {@code hy} fore and
     *               aft, {@code hz} vertical
     */
    private static void box(Mesh.Builder mesh, Fit fit, double across, double ahead,
                            double along, double hx, double hy, double hz,
                            float[] uv, int rgb) {
        double[] p = at(fit, across, ahead, along);
        double s = fit.size();
        Shapes.box(mesh, p[0], p[1], p[2], hx * s, hy * s, hz * s, fit.yaw(), uv, rgb);
    }

    /** A strut between two offsets in the fit's own frame. */
    private static void strut(Mesh.Builder mesh, Fit fit,
                              double across0, double ahead0, double along0,
                              double across1, double ahead1, double along1,
                              double halfWidth, double halfThick, float[] uv, int rgb) {
        double[] a = at(fit, across0, ahead0, along0);
        double[] b = at(fit, across1, ahead1, along1);
        double s = fit.size();
        Shapes.strut(mesh, a[0], a[1], a[2], b[0], b[1], b[2],
                halfWidth * s, halfThick * s, uv, rgb);
    }

    /** A rounded lump at an offset — a bobble, a brass ring. */
    private static void blob(Mesh.Builder mesh, Fit fit, double across, double ahead,
                             double along, double radius, float[] uv, int rgb) {
        double[] p = at(fit, across, ahead, along);
        double s = fit.size();
        Shapes.blob(mesh, p[0], p[1], p[2], radius * s, radius * s, radius * s,
                fit.yaw(), uv, rgb);
    }

    /** An offset in sizes, turned into a point in the world. */
    private static double[] at(Fit fit, double across, double ahead, double along) {
        double[] f = fit.forward();
        double[] r = fit.right(f);
        double s = fit.size();
        return new double[]{
            fit.x() + r[0] * across * s + f[0] * ahead * s + fit.upX() * along * s,
            fit.y() + r[1] * across * s + f[1] * ahead * s + fit.upY() * along * s,
            fit.z() + r[2] * across * s + f[2] * ahead * s + fit.upZ() * along * s
        };
    }
}
