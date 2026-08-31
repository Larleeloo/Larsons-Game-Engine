package com.larsons.engine.watch.render;

import com.larsons.engine.watch.light.LightKind;
import com.larsons.engine.watch.light.PlacedLight;
import com.larsons.engine.watch.world.WatchMaterial;
import com.larsons.engine.watch.world.WatchMaterials;

/**
 * The things that burn, as solids — <b>the object, so that the light has
 * something to come out of.</b>
 *
 * <p>{@link com.larsons.engine.watch.light.LightField} is what a fire
 * <em>does</em> to the wood around it. This is the fire: a ring of stones, three
 * logs and a flame; a lantern's frame and the flame behind its panes; a torch
 * stood in the ground. Without it a campfire is a pool of light on some grass
 * with nothing in the middle of it, which reads as a rendering fault rather than
 * as a camp.
 *
 * <h2>The flame is white-tiled on purpose</h2>
 *
 * <p>Every solid in this game is drawn as {@code texture × vertexColour} on a
 * card and as the vertex colour alone in the painter, so a flame built out of
 * {@link WatchMaterial#BARK} would be an orange flame on one machine and a
 * wood-grained one on the other. {@link WatchMaterial#PAPER} carries no colour
 * of its own — see the note on that constant — so the multiply is the identity
 * and both paths draw exactly the colour asked for. The map board needed the
 * same property for the same reason.
 *
 * <h2>It is not emissive, and does not need to be</h2>
 *
 * <p>The mesh format has three positions, two texture coordinates and a colour;
 * there is nowhere to say "this triangle ignores the light". It does not have
 * to: a flame sits at the exact centre of its own point light, so the lighting
 * pass hands it the full intensity of the thing it is the source of, and it
 * comes out at its own colour at every hour of the day. A fire at noon and a
 * fire at midnight are the same triangles and read correctly at both, which is
 * what an emissive channel would have bought at the price of a vertex format.
 */
public final class LightModel {

    /** How high a campfire's flame stands, in metres. */
    private static final double FIRE_FLAME = 0.62;

    /** How wide the ring of stones is. */
    private static final double RING = 0.44;

    /** How many stones are in it. */
    private static final int STONES = 7;

    /** A lantern's frame, in metres: half its footprint, and how tall it stands. */
    private static final double LANTERN_HALF = 0.085;

    private static final double LANTERN_TALL = 0.30;

    /** A planted torch's shaft. */
    private static final double TORCH_TALL = 0.95;

    private LightModel() {}

    /**
     * Whatever this light is, wherever it is, in whatever state it is in.
     *
     * <p>One entry point, because the caller — the scene's moving mesh — has a
     * list of {@link PlacedLight}s and no business knowing which of the four
     * kinds needs which shape.
     *
     * @param ox    the mesh's origin, which positions are relative to
     * @param phase the drawing clock, in seconds: what the flame sways on
     */
    public static void light(Mesh.Builder mesh, PlacedLight light, double ox, double oy,
                             double phase) {
        double x = light.x() - ox, y = light.y() - oy, z = light.z();
        double burn = light.burnBrightness();
        switch (light.kind()) {
            case CAMPFIRE -> campfire(mesh, x, y, z, burn, phase);
            case TORCH -> torch(mesh, x, y, z, light.yaw(), burn, phase, 1);
            case LANTERN, SPORE_LANTERN ->
                    lantern(mesh, x, y, z, light.yaw(), light.kind(), burn, phase, 1);
        }
    }

    /**
     * A ring of stones, three logs and a fire.
     *
     * <p>The logs char rather than vanish when it goes out, which is the whole
     * of what a cold hearth has to say: something was here, it is out, and it
     * can be lit again. A campfire that disappeared when it stopped burning
     * would make the four-hour fuel a punishment instead of a chore.
     */
    public static void campfire(Mesh.Builder mesh, double x, double y, double z,
                                double burn, double phase) {
        float[] uv = new float[4];
        WatchMaterials.uv(WatchMaterial.ROCK, uv);
        int stone = WatchMaterials.shade(WatchMaterial.ROCK);
        for (int i = 0; i < STONES; i++) {
            double a = i * Math.PI * 2 / STONES + 0.4;
            // Sized off the index rather than a random, so a fire looks the
            // same every frame — these are rebuilt from scratch each time the
            // moving mesh is, and a rolled size would boil.
            double big = 0.075 + ((i * 37) % 11) * 0.004;
            Shapes.blob(mesh, x + Math.cos(a) * RING, y + Math.sin(a) * RING,
                    z + big * 0.6, big, big * 0.8, big * 0.7, a, uv, stone);
        }

        WatchMaterials.uv(WatchMaterial.BARK, uv);
        // Dark bark while it burns, ash-grey once it is out: a log in a fire is
        // not the colour of a log on the ground, and a log in a fire that has
        // gone out is not the colour of either.
        int wood = WatchMaterials.shade(burn > 0 ? WatchMaterial.DARK_BARK
                : WatchMaterial.ASH);
        for (int i = 0; i < 3; i++) {
            double a = i * Math.PI / 3 + 0.25;
            double dx = Math.cos(a) * 0.34, dy = Math.sin(a) * 0.34;
            Shapes.strut(mesh, x - dx, y - dy, z + 0.06, x + dx, y + dy, z + 0.16,
                    0.045, 0.045, uv, wood);
        }
        if (burn <= 0) {
            // Cold ash in the middle, so an unlit fire is a hearth rather than
            // a stone ring with sticks in it.
            WatchMaterials.uv(WatchMaterial.ASH, uv);
            Shapes.blob(mesh, x, y, z + 0.03, 0.24, 0.24, 0.035, 0, uv,
                    WatchMaterials.shade(WatchMaterial.ASH));
            return;
        }
        flame(mesh, x, y, z + 0.10, FIRE_FLAME * (0.7 + 0.3 * burn), phase,
                LightKind.CAMPFIRE.rgb(), 0.26);
    }

    /**
     * A lantern: a base, four uprights, a cap, a bail, and the flame inside.
     *
     * <p>Open sides rather than modelled glass. A pane is a translucent quad
     * that has to sort against the flame behind it on the painter path and
     * against itself on a card, for a surface nobody can see at the size a
     * lantern is ever drawn — and a solid pane would hide the one part of it
     * that matters.
     *
     * @param scale {@code 1} for one standing in the world; smaller for one in
     *              a hand or a satchel row
     */
    public static void lantern(Mesh.Builder mesh, double x, double y, double z,
                               double yaw, LightKind kind, double burn, double phase,
                               double scale) {
        float[] uv = new float[4];
        double half = LANTERN_HALF * scale, tall = LANTERN_TALL * scale;
        WatchMaterials.uv(WatchMaterial.DARK_BARK, uv);
        int frame = WatchMaterials.shade(WatchMaterial.DARK_BARK);
        Shapes.box(mesh, x, y, z + 0.018 * scale, half, half, 0.018 * scale, yaw, uv,
                frame);
        Shapes.box(mesh, x, y, z + tall - 0.02 * scale, half * 1.1, half * 1.1,
                0.022 * scale, yaw, uv, frame);

        WatchMaterials.uv(WatchMaterial.ROPE, uv);
        int post = WatchMaterials.shade(WatchMaterial.ROPE);
        for (int i = 0; i < 4; i++) {
            double a = yaw + Math.PI / 4 + i * Math.PI / 2;
            Shapes.prism(mesh, x + Math.cos(a) * half, y + Math.sin(a) * half,
                    z + 0.03 * scale, z + tall - 0.02 * scale, 0.011 * scale,
                    0.011 * scale, 4, yaw, uv, post, false);
        }
        // The bail: what a lantern hangs from, and the line that says at fifty
        // metres that the light is a lamp somebody is carrying rather than a
        // fire on the ground.
        Shapes.strut(mesh, x - Math.sin(yaw) * half, y + Math.cos(yaw) * half,
                z + tall, x + Math.sin(yaw) * half, y - Math.cos(yaw) * half,
                z + tall + 0.06 * scale, 0.009 * scale, 0.009 * scale, uv, post);

        if (burn <= 0) {
            // Out: a dark wick where the flame was, so an empty lantern is
            // visibly an empty lantern rather than an unlit shape.
            WatchMaterials.uv(WatchMaterial.ASH, uv);
            Shapes.blob(mesh, x, y, z + tall * 0.4, 0.02 * scale, 0.02 * scale,
                    0.035 * scale, yaw, uv, WatchMaterials.shade(WatchMaterial.ASH));
            return;
        }
        if (kind == LightKind.SPORE_LANTERN) {
            // Not a flame: a jar of spores glows, it does not burn, and a
            // tongue of green fire in a jar would be the wrong picture. A blob
            // that breathes with the same pulse the light does.
            WatchMaterials.uv(WatchMaterial.PAPER, uv);
            double swell = 0.055 * scale * (0.9 + 0.1 * Math.sin(phase * 1.3));
            Shapes.blob(mesh, x, y, z + tall * 0.45, swell, swell, swell * 1.25, yaw,
                    uv, brighten(kind.rgb(), 0.35));
            Shapes.blob(mesh, x, y, z + tall * 0.45, swell * 0.55, swell * 0.55,
                    swell * 0.8, yaw, uv, brighten(kind.rgb(), 0.8));
            return;
        }
        flame(mesh, x, y, z + tall * 0.28, tall * 0.42 * (0.6 + 0.4 * burn), phase,
                kind.rgb(), 0.10 * scale);
    }

    /**
     * A torch: a shaft, a bound head, and a flame off the top of it.
     *
     * @param scale {@code 1} for one stood in the ground; smaller in a hand
     */
    public static void torch(Mesh.Builder mesh, double x, double y, double z,
                             double yaw, double burn, double phase, double scale) {
        float[] uv = new float[4];
        double tall = TORCH_TALL * scale;
        WatchMaterials.uv(WatchMaterial.BARK, uv);
        Shapes.prism(mesh, x, y, z, z + tall, 0.028 * scale, 0.020 * scale, 5, yaw, uv,
                WatchMaterials.shade(WatchMaterial.BARK), false);
        // The bound head: dark pitch-soaked bark before it is lit, and charred
        // once it is. A torch that looked the same in the satchel as it does
        // burning would be a torch nobody could tell they had used.
        WatchMaterials.uv(WatchMaterial.ROPE, uv);
        Shapes.prism(mesh, x, y, z + tall * 0.86, z + tall, 0.040 * scale,
                0.036 * scale, 6, yaw, uv, WatchMaterials.shade(
                        burn > 0 ? WatchMaterial.ASH : WatchMaterial.DARK_BARK), true);
        if (burn <= 0) return;
        flame(mesh, x, y, z + tall, 0.34 * scale * (0.6 + 0.4 * burn), phase,
                LightKind.TORCH.rgb(), 0.075 * scale);
    }

    /**
     * A flame: three tapering cones, leaning with the draught.
     *
     * <p>Three rather than one because a single cone is a traffic cone. What
     * makes it read as fire is that the inside is a different colour from the
     * outside and that the three of them lean by different amounts on the same
     * clock — a shape that is <em>changing</em> at its edges and steady in the
     * middle, which is what a flame does.
     *
     * @param height how tall the outer tongue stands, in metres
     * @param base   how wide it is at the bottom
     */
    public static void flame(Mesh.Builder mesh, double x, double y, double z,
                             double height, double phase, int rgb, double base) {
        float[] uv = new float[4];
        // White tile: the multiply is the identity, so both backends draw the
        // colour asked for. See the class note.
        WatchMaterials.uv(WatchMaterial.PAPER, uv);
        double sway = base * 0.35;
        double leanX = Math.sin(phase * 3.1) * sway;
        double leanY = Math.cos(phase * 2.3) * sway;
        Shapes.cone(mesh, x + leanX * 0.4, y + leanY * 0.4, z,
                z + height, base, 6, phase * 0.6, uv, rgb);
        Shapes.cone(mesh, x + leanX * 0.8, y + leanY * 0.8, z + height * 0.06,
                z + height * 1.18, base * 0.62, 5, -phase * 0.9, uv,
                brighten(rgb, 0.45));
        Shapes.cone(mesh, x + leanX, y + leanY, z + height * 0.10,
                z + height * 1.34, base * 0.30, 4, phase * 1.4, uv,
                brighten(rgb, 0.85));
    }

    /** A colour pushed toward white — the inside of a flame. */
    private static int brighten(int rgb, double towardWhite) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        r = (int) (r + (255 - r) * towardWhite);
        g = (int) (g + (255 - g) * towardWhite);
        b = (int) (b + (255 - b) * towardWhite);
        return (r << 16) | (g << 8) | b;
    }
}
