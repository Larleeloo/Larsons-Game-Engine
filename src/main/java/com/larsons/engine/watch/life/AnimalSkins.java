package com.larsons.engine.watch.life;

import com.larsons.engine.graphics.Offscreen;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.graphics.draw.DrawTarget;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/**
 * A skin for every species — <b>Minecraft-shaped, 64×64, and generated.</b>
 *
 * <p>The brief asks for placeholders that are full 3D models "in a Minecraft
 * style, complete with textures", and complete is the operative word: a boxy
 * model painted one flat colour is a prop, not a placeholder. So every one of
 * the {@link AnimalRegistry#count()} species gets a real skin sheet, laid out
 * the way a Minecraft entity skin is — a strip per body region — and painted
 * from that species' own three colours with the markings its epithet implies.
 * A <em>Banded</em> anything has bands; a <em>Speckled</em> anything is
 * speckled; a <em>Masked</em> one has a dark face.
 *
 * <p><b>Overridable, like everything else in this engine.</b> A texture pack
 * that supplies {@code watch/animal/<species>} replaces one species' skin, and
 * {@code watch/animal/<family>} replaces all forty-nine of a family's at once —
 * so a pack author can redress the whole game with twenty-six files or fix one
 * bird with one. The engine ships no image files; these are painted at runtime.
 *
 * <p><b>What the model does with it.</b> Each box of a placeholder model names
 * a region of this sheet and takes its face colour from the average of that
 * region ({@link #regionColour}), which is what makes a pack's art change the
 * animal in the world and not only its portrait in the book. The full sheet is
 * what the field guide's page shows.
 */
public final class AnimalSkins {

    /** Edge of a skin sheet, in pixels. */
    public static final int SIZE = 64;

    /** The regions a model's boxes take their colour from. */
    public enum Region {
        /** The back and flanks — most of the animal. */
        BODY(0, 0, 32, 32),
        /** The face. */
        HEAD(32, 0, 16, 16),
        /** Belly, throat, chest. */
        BELLY(48, 0, 16, 16),
        /** Wings, or the outer face of a fin. */
        WING(32, 16, 32, 16),
        /** Legs and feet. */
        LIMB(0, 32, 16, 32),
        /** Tail. */
        TAIL(16, 32, 16, 32),
        /** Beak, horn, antler, claw — the hard parts. */
        HARD(32, 32, 16, 16),
        /** Eyes and any bright display patch. */
        EYE(48, 32, 16, 16),

        /**
         * The one part of an animal that is <b>lit from inside</b>: a werewolf's
         * eyes, the fire in a wendigo's open chest, the lantern where a
         * mirewraith's face should be.
         *
         * <p><b>It cost nothing to add.</b> The eight original regions look like
         * they tile the sheet and do not quite: {@code HARD} and {@code EYE} stop
         * at {@code y = 48}, leaving a 32×16 strip along the bottom right that
         * nothing has ever painted or read. These two live there, so every one of
         * the thirteen hundred existing species is unchanged to the byte and a
         * pack author's sheet gains two blocks rather than losing any.
         *
         * <p><b>What "glowing" means here, and what it does not.</b> The renderer
         * multiplies every vertex by the hour's light and there is no emissive
         * channel in the mesh format; adding one would mean a field on
         * {@code Mesh}, a branch in the painter's shading and a change to the
         * card's shaders — a great deal of renderer for two eyes. So this glows
         * the way a flat-shaded world can: the brightest, most saturated colour
         * on the creature, set in a {@link #SHADOW} socket, with dimmer shells
         * around it. The hour's light scales the glow and its socket by the same
         * factor, so the <em>contrast</em> survives every hour — and at night,
         * when these three are actually out, ten-to-one against a dark body is
         * the only thing on them the eye finds.
         */
        GLOW(32, 48, 16, 16),

        /**
         * The dark a glow is read against: an eye socket, the inside of an open
         * ribcage, the hollow of an ear.
         *
         * <p>Its own region rather than "some dark colour", because the whole
         * trick above is a <em>ratio</em> between two painted colours. Both are
         * multiplied by the same light, so as long as this one is near black the
         * glow reads as a glow at noon, at dusk and in the dark.
         */
        SHADOW(48, 48, 16, 16);

        final int x, y, w, h;

        Region(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    /** How many skins are kept painted at once. */
    private static final int CACHE_LIMIT = 384;

    private static final Map<String, BufferedImage> CACHE =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) {
                    return size() > CACHE_LIMIT;
                }
            };

    private static final Map<String, int[]> REGION_COLOURS =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, int[]> e) {
                    return size() > CACHE_LIMIT;
                }
            };

    private AnimalSkins() {}

    /**
     * The skin sheet for a species: the pack's, its family's, or the painted
     * one. Cached, because the field guide draws these by the page.
     */
    public static synchronized BufferedImage skin(AnimalDef def) {
        BufferedImage cached = CACHE.get(def.key());
        if (cached != null) return cached;
        BufferedImage image = supplied(def);
        if (image == null) image = paint(def);
        CACHE.put(def.key(), image);
        return image;
    }

    /** Throw the painted skins away — what a texture pack rescan calls. */
    public static synchronized void invalidate() {
        CACHE.clear();
        REGION_COLOURS.clear();
    }

    /**
     * The average colour of one region of a species' skin, {@code 0xRRGGBB}.
     *
     * <p>What a model's boxes are shaded with. Averaging rather than sampling
     * per texel because these boxes are drawn flat-shaded like everything else
     * in this world — and because it means a pack's art reaches the world
     * itself, not only the book.
     */
    public static synchronized int regionColour(AnimalDef def, Region region) {
        int[] colours = REGION_COLOURS.get(def.key());
        if (colours == null) {
            colours = new int[Region.values().length];
            BufferedImage sheet = skin(def);
            for (Region r : Region.values()) {
                colours[r.ordinal()] = average(sheet, r);
            }
            REGION_COLOURS.put(def.key(), colours);
        }
        return colours[region.ordinal()];
    }

    /** A pack's sheet for this species, then for its family, or {@code null}. */
    private static BufferedImage supplied(AnimalDef def) {
        try {
            BufferedImage own = Skins.frame(def.textureKey(), 0);
            if (own != null) return own;
            return Skins.frame(def.family().textureKey(), 0);
        } catch (RuntimeException e) {
            return null; // a broken sheet is a missing sheet, not a crash
        }
    }

    /** Paint a species' sheet from its own three colours and its epithet. */
    private static BufferedImage paint(AnimalDef def) {
        // Pixel art: no antialiasing, or a band on a 64-pixel sheet comes out
        // as a smear rather than as a band.
        try (Offscreen bake = Offscreen.image(SIZE, SIZE, false)) {
            paintInto(bake.target(), def);
            return bake.image();
        }
    }

    private static void paintInto(DrawTarget g, AnimalDef def) {
        Random rng = new Random(def.key().hashCode() * 0x9E3779B97F4A7C15L);

        Color body = new Color(def.body());
        Color accent = new Color(def.accent());
        Color detail = new Color(def.detail());

        fill(g, Region.BODY, body);
        fill(g, Region.HEAD, body);
        fill(g, Region.BELLY, accent);
        fill(g, Region.WING, shade(body, 0.86));
        fill(g, Region.LIMB, detail);
        fill(g, Region.TAIL, shade(body, 0.92));
        fill(g, Region.HARD, detail);
        fill(g, Region.EYE, accent);
        // Whatever is lit from inside, and the dark it is read against. Only the
        // three mutants have a part that names either, so for everything else
        // the colours are never read — but they are painted all the same, so
        // that a creator opening a sheet finds two labelled blocks rather than
        // a corner somebody forgot.
        Mutants.Kind mutant = Mutants.of(def);
        fill(g, Region.GLOW, mutant == null ? shade(accent, 1.4)
                : new Color(mutant.glow()));
        // Near black rather than black: a socket that is pure 0x000000 is
        // multiplied by the hour's light to pure 0x000000, which is a hole in
        // the model rather than a shadow in it.
        fill(g, Region.SHADOW, new Color(0x0E0B0A));

        markings(g, def, body, accent, detail, rng);

        // The eye itself, so a portrait has something to look back with.
        g.fillRect(Region.EYE.x + 4, Region.EYE.y + 5, 6, 6, Color.WHITE);
        g.fillRect(Region.EYE.x + 6, Region.EYE.y + 7, 3, 3, new Color(0x14161C));
    }

    /**
     * The markings an epithet promises.
     *
     * <p>A generated animal with no pattern on it looks generated. These are
     * cheap — bands, spots, a cap, a mask, a bib — and they are what make two
     * species of one lineage read as two species rather than as two colours.
     */
    private static void markings(DrawTarget g, AnimalDef def, Color body, Color accent,
                                 Color detail, Random rng) {
        String epithet = def.epithet();
        Color mark = shade(detail, 0.8);
        switch (epithet) {
            case "Banded", "Striped" -> {
                for (int i = 0; i < 5; i++) {
                    g.fillRect(Region.BODY.x, Region.BODY.y + 3 + i * 6, Region.BODY.w, 2, mark);
                }
                g.fillRect(Region.WING.x, Region.WING.y + 6, Region.WING.w, 3, mark);
            }
            case "Speckled", "Marbled" -> {
                for (int i = 0; i < 46; i++) {
                    g.fillRect(Region.BODY.x + rng.nextInt(Region.BODY.w - 1),
                            Region.BODY.y + rng.nextInt(Region.BODY.h - 1), 2, 2, mark);
                }
            }
            case "Masked", "Hooded" -> {
                g.fillRect(Region.HEAD.x, Region.HEAD.y, Region.HEAD.w, 8, mark);
            }
            case "Crested", "Thorned" -> {
                g.fillRect(Region.HEAD.x + 5, Region.HEAD.y, 6, 5, accent.brighter());
                g.fillRect(Region.HARD.x + 2, Region.HARD.y + 2, 12, 4, mark);
            }
            case "Painted", "Blushing" -> {
                g.fillRect(Region.BODY.x + 6, Region.BODY.y + 8, 12, 12, accent);
                g.fillRect(Region.BODY.x + 9, Region.BODY.y + 11, 6, 6, shade(accent, 1.25));
            }
            case "Gilded", "Golden", "Silver", "Bronze", "Copper", "Iron", "Pearl" -> {
                g.fillRect(Region.WING.x, Region.WING.y, Region.WING.w, 4, shade(body, 1.35));
                g.fillRect(Region.BODY.x, Region.BODY.y, Region.BODY.w, 3, shade(body, 1.35));
            }
            case "Starlit", "Moonlit" -> {
                Color star = new Color(0xF0F4FF);
                for (int i = 0; i < 22; i++) {
                    g.fillRect(Region.BODY.x + rng.nextInt(Region.BODY.w),
                            Region.BODY.y + rng.nextInt(Region.BODY.h), 1, 1, star);
                }
            }
            // The three mutants, whose epithets are theirs alone. They get
            // markings for the same reason every other species does — a flat
            // rectangle looks generated — and the markings are the ones that
            // read at forty metres in the dark, which is the only range and the
            // only light anybody will ever see one at.
            case "Hollow" -> {
                // Frost on the shoulders, and the bone underneath showing
                // through: a starved animal in a hard winter.
                Color rime = new Color(0xE4EEF2);
                g.fillRect(Region.BODY.x, Region.BODY.y, Region.BODY.w, 5, rime);
                for (int i = 0; i < 4; i++) {
                    g.fillRect(Region.BODY.x + 2, Region.BODY.y + 9 + i * 6,
                            Region.BODY.w - 4, 2, rime);
                }
                g.fillRect(Region.HARD.x, Region.HARD.y, Region.HARD.w, Region.HARD.h,
                        new Color(0xD8D2C0));
                g.fillRect(Region.HEAD.x, Region.HEAD.y + 9, Region.HEAD.w, 7,
                        shade(detail, 0.55));
            }
            case "Moonfell" -> {
                // A dark saddle over the shoulders and a pale throat: the
                // markings a wolf has, on something that is not one.
                g.fillRect(Region.BODY.x, Region.BODY.y, Region.BODY.w, 14,
                        shade(body, 0.55));
                g.fillRect(Region.BELLY.x, Region.BELLY.y + 6, Region.BELLY.w, 10,
                        shade(accent, 1.15));
                for (int i = 0; i < 30; i++) {
                    g.fillRect(Region.BODY.x + rng.nextInt(Region.BODY.w - 1),
                            Region.BODY.y + 14 + rng.nextInt(Region.BODY.h - 15),
                            1, 3, shade(body, 0.7));
                }
            }
            case "Drowned" -> {
                // Waterline stains and weed. The bright band across the middle
                // is where it last stood still long enough to grow something.
                for (int i = 0; i < 3; i++) {
                    g.fillRect(Region.BODY.x, Region.BODY.y + 6 + i * 9,
                            Region.BODY.w, 3, shade(accent, 0.8));
                }
                for (int i = 0; i < 34; i++) {
                    g.fillRect(Region.BODY.x + rng.nextInt(Region.BODY.w - 2),
                            Region.BODY.y + rng.nextInt(Region.BODY.h - 3), 2, 4,
                            shade(accent, 1.2));
                }
                g.fillRect(Region.TAIL.x, Region.TAIL.y, Region.TAIL.w, Region.TAIL.h,
                        shade(accent, 0.7));
            }
            default -> {
                // A soft dorsal stripe: enough to keep a plain species from
                // being a solid rectangle, quiet enough not to claim a pattern
                // its name never promised.
                g.fillRect(Region.BODY.x, Region.BODY.y + Region.BODY.h / 2 - 1,
                        Region.BODY.w, 3, shade(body, 0.88));
            }
        }
        // A bib, for anything whose belly colour is worth showing off.
        g.fillRect(Region.HEAD.x + 3, Region.HEAD.y + Region.HEAD.h - 5, 10, 4, accent);
    }

    private static void fill(DrawTarget g, Region region, Color colour) {
        g.fillRect(region.x, region.y, region.w, region.h, colour);
    }

    private static int average(BufferedImage sheet, Region region) {
        // A pack's sheet may be any size; scale the region into it rather than
        // assuming 64×64, so somebody's 256×256 art still works.
        int w = sheet.getWidth(), h = sheet.getHeight();
        int x0 = region.x * w / SIZE, y0 = region.y * h / SIZE;
        int x1 = Math.min(w, (region.x + region.w) * w / SIZE);
        int y1 = Math.min(h, (region.y + region.h) * h / SIZE);
        long r = 0, g = 0, b = 0, n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int argb = sheet.getRGB(x, y);
                if (((argb >>> 24) & 0xFF) < 16) continue; // ignore transparent art
                r += (argb >> 16) & 0xFF;
                g += (argb >> 8) & 0xFF;
                b += argb & 0xFF;
                n++;
            }
        }
        if (n == 0) return 0x808080;
        return (int) ((r / n) << 16 | (g / n) << 8 | (b / n));
    }

    private static Color shade(Color c, double by) {
        return new Color(clamp(c.getRed() * by), clamp(c.getGreen() * by),
                clamp(c.getBlue() * by));
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }
}
