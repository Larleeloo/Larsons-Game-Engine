package com.larsons.engine.gl;

import com.larsons.engine.graphics.EyeCamera;
import com.larsons.engine.graphics.Mat4;
import com.larsons.engine.graphics.MeshPass;

import java.util.List;
import java.util.Locale;

import static org.lwjgl.opengl.GL33C.*;

/**
 * The shader the world is drawn with — one matrix, one texture, and fog.
 *
 * <p><b>As small as a terrain shader can be, on purpose.</b> Everything a
 * fancier one would compute per fragment has already been computed and baked
 * into the vertex colour by {@code SectionMesher}: the face's shade, its
 * ambient occlusion, the block's own tint. That is not a shortcut, it is the
 * arrangement that lets the geometry be uploaded once and never touched again —
 * a lighting model evaluated at draw time is a lighting model that has to be
 * re-uploaded when anything moves.
 *
 * <p><b>The matrix is model-view-projection, per section.</b> Vertices arrive
 * relative to their section's own corner (see {@code SectionMesh}), so the
 * model matrix is a translation and the whole product is built on the CPU in
 * {@code double} and handed down as sixteen floats. A world sixty-five thousand
 * blocks across cannot be expressed in float at block precision, and this is
 * how every renderer of that size avoids having to.
 *
 * <p><b>Alpha is tested, not just blended.</b> A leaf sheet is mostly nothing,
 * and a fragment that is nothing must not write depth — otherwise the gaps in a
 * canopy occlude whatever is behind them. Discarding below a threshold in the
 * opaque pass is what every voxel renderer does with cutout geometry.
 *
 * <h2>The one thing that cannot be baked</h2>
 *
 * <p>Everything above holds for a sun that never moves. It does not hold for a
 * lantern somebody is <em>carrying</em>: that light moves every frame, and a
 * lighting model baked into vertex colours would have to re-mesh a forest
 * because a walker took a step. So the shader grew a second half — a daylight
 * multiplier and up to {@link com.larsons.engine.graphics.MeshPass#MAX_LIGHTS}
 * point lights, as uniforms, applied per fragment. The geometry still never
 * changes; the light does.
 *
 * <p><b>And it needs a normal, which this vertex format does not have.</b>
 * Adding one would be four more bytes on every vertex of every chunk in memory,
 * to carry a number that is the same for all three vertices of a flat-shaded
 * triangle. The fragment shader derives it instead, from the screen-space
 * gradient of the eye-space position: {@code cross(dFdx(p), dFdy(p))} is
 * exactly the plane the triangle lies in, costs two instructions, and is free
 * of the one thing an interpolated normal would give — smoothing, which is
 * precisely what this world does not want.
 *
 * <h2>…and then the sky</h2>
 *
 * <p>A flat multiplier is a complete answer to "what time is it" and no answer
 * at all to "what does it look like out". {@link MeshPass.Sky} is the second
 * half of the seam and this is what a card does with it, all of it per
 * fragment off the normal above:
 *
 * <ul>
 *   <li><b>A sun in the sky the clock actually put it in</b>, warm on the
 *       horizon and pale overhead, against a two-colour ambient — the sky's
 *       own colour on what faces up, a dim bounce of the haze on what faces
 *       down. That pair is one {@code mix} and it is most of the difference
 *       between low-poly ground that reads as carved and low-poly ground that
 *       reads as a flat sheet of green.</li>
 *   <li><b>Shadows</b>, sampled from {@link GlShadowMap} with a nine-tap
 *       kernel and a slope-scaled bias, faded out at the edge of the map so
 *       there is no line across the wood where it ends.</li>
 *   <li><b>Lit air.</b> The point-light loop already knows how far every lamp
 *       is from the fragment; for one more dot product it can also work out
 *       how close the lamp passes to the <em>ray</em>, and how much of that
 *       ray lies inside the lamp's reach. That integral is the broad cone of
 *       glow you see round a fire in a damp wood, and it is what makes a
 *       campfire light a clearing rather than a disc of ground.</li>
 *   <li><b>Fog with a height to it.</b> Distance haze as before, plus an
 *       exponential term whose density falls off above a floor: mist lies in
 *       the valleys, a ridge stands out of it, and the whole bank drifts
 *       slowly rather than sitting still. Tinted toward the sun, so looking
 *       into it at dawn is bright and looking away from it is not.</li>
 *   <li><b>One knob of grade</b> at the end — vibrance, which lifts the
 *       colours that have least and leaves the ones that have plenty, and a
 *       knee that rolls the highlights off so the middle of a lantern's pool
 *       keeps its colour instead of clipping to white.</li>
 * </ul>
 *
 * <h2>…and what everything is made of</h2>
 *
 * <p>All of that describes the <em>light</em>, and answers "how much of it
 * arrives here". None of it answers "and what does this surface do with it",
 * which is the difference between a world of coloured card and a world of
 * things. A second atlas ({@link MeshPass#setSurface}) carries a tangent-space
 * normal, a roughness and a metalness per texel, and with it the shader
 * evaluates a real metal/roughness material — GGX, height-correlated Smith,
 * Schlick — for the sun, for every lamp that touches the mesh, and for the sky
 * itself:
 *
 * <ul>
 *   <li><b>A normal map with no tangents in the vertex format.</b> The same
 *       screen-space gradients that give the face its normal give the frame to
 *       perturb it in, so relief costs nothing per vertex and nothing per
 *       upload. Where a mesh gives all three vertices one texture coordinate —
 *       every animal, plank and leaf in this game — there is no frame to build
 *       and the face's own normal is used, which is the right answer for a
 *       flat-shaded facet.</li>
 *   <li><b>Roughness that fades in with distance.</b> A fragment measures how
 *       many texels of the map it covers; past one, what the screen can no
 *       longer resolve is handed to the roughness instead of being sampled as
 *       noise. That is what a microfacet model is, and it is why a hillside two
 *       hundred metres off is a soft sheen rather than a field of sparks.</li>
 *   <li><b>The sky, reflected.</b> The two-colour ambient sampled a second time
 *       along the reflected ray, weighted by a Fresnel that opens at grazing
 *       angles and closes as a surface roughens, tinted by the fog's own colour
 *       because that is the only absolute colour of the sky this shader is
 *       given. It is what makes a lake read as a lake: dark where you look
 *       into it, bright where you look across it, and pink at dawn.</li>
 *   <li><b>…and it takes what it gives.</b> The diffuse is scaled down by
 *       exactly the fraction the reflection took, and a see-through surface's
 *       alpha is raised by it, because at a grazing angle water is a mirror
 *       rather than a window and a highlight must not be blended away.</li>
 *   <li><b>A slow drift across the ground</b>, in world space, so that a tile
 *       stretched across a two-metre quad is not what the eye finds first. Four
 *       transcendentals, no texture fetch, and every frequency an exact
 *       multiple of one cycle per {@link #DRIFT_PERIOD} metres so the world's
 *       coordinate fold is invisible.</li>
 * </ul>
 *
 * <p>The colour atlas that arrives with a surface atlas is read as a
 * <b>detail</b> map — see {@link MeshPass#DETAIL_GAIN}, which is also the note
 * on what it cost to read it as anything else.
 *
 * <h2>Neutral unless told otherwise</h2>
 *
 * <p>A GLSL uniform starts at zero, so a daylight multiplier nobody set would
 * draw a black world; {@link #use} resets <em>every</em> term above to the
 * identity of its own operation each time the program is bound — full daylight,
 * no sun, no shadow, no weather, no grade. That is what lets
 * {@link GlTerrainPass} — which knows nothing about any of this and has its own
 * lighting baked in by {@code SectionMesher} — share the program and be
 * bit-for-bit unaffected by all of it. The material half switches off the same
 * way and by the same means: with no surface atlas the reflectance is black, so
 * every specular term in the shader multiplies out to nothing and the colour
 * atlas is read as a plain colour again.
 */
final class GlTerrainProgram implements AutoCloseable {

    /** Vertex attribute slots, matched by {@link GlSectionArena}. */
    static final int ATTRIB_POSITION = 0;
    static final int ATTRIB_UV = 1;
    static final int ATTRIB_COLOR = 2;

    /** Texture unit the block atlas is bound to. */
    static final int UNIT_ATLAS = 0;

    /** …and the one the sun's depth map is, when there is one. */
    static final int UNIT_SHADOW = 1;

    /** …and the brightest lamp's, which is a cube. See {@link GlLampShadow}. */
    static final int UNIT_LAMP_SHADOW = 2;

    /** …and the surface atlas: normals, roughness and metalness. */
    static final int UNIT_SURFACE = 3;

    /** How many point lights the fragment shader will walk. */
    private static final int MAX_LIGHTS = MeshPass.MAX_LIGHTS;

    /**
     * How much of a light a surface facing away from it still gets.
     *
     * <p>The same constant the CPU painter uses, and it has to be: the two
     * paths draw the same world, and a wrap term that differed would be a
     * campfire lighting the underside of a branch on one machine and not on
     * another. Read off the seam rather than written out here, so there is one
     * number. See {@link com.larsons.engine.graphics.MeshPass#LIGHT_WRAP}.
     */
    private static final float WRAP = (float) MeshPass.LIGHT_WRAP;

    /**
     * How far the drifting fog repeats, in metres.
     *
     * <p><b>A period rather than a scale, and that is the whole trick.</b> The
     * drift is a function of world position, and world position out here is
     * tens of thousands of metres — far past what a {@code float} holds at the
     * centimetre precision a fragment shader needs. So the position handed up
     * is taken modulo this on the CPU, in {@code double}, where it is exact.
     * Every frequency in the drift is an exact multiple of {@code 2π / } this,
     * which makes the wrap invisible: the pattern that starts again at the seam
     * is the pattern that was already there.
     */
    static final double DRIFT_PERIOD = 512;

    /**
     * The average of a lamp's falloff along the chord its light is scattered
     * from, as a fraction of the falloff where the ray passes closest.
     *
     * <p>The in-scattering term integrates a lamp along the view ray, and the
     * honest integral of {@code (1 − d/r)²} over that chord has no useful
     * closed form. Taking the value at the closest approach and multiplying by
     * the chord's length — which is what the shader would otherwise do — is the
     * peak times the width of a curve that is nowhere near flat, and it roughly
     * doubles the answer. Half is what the integral actually comes to across
     * the range of misses that matter.
     */
    private static final double CHORD_MEAN = 0.5;

    /**
     * How much of a light bounces straight off a non-metal facing you.
     *
     * <p>Four percent, and it is four percent for water, leaves, skin, stone
     * and everything else that is not a metal — the one number in a
     * metal/roughness model that is not a per-material choice, because it falls
     * out of the refractive index of essentially every dielectric there is.
     * What varies between a lake and a lichen is where that four percent goes,
     * which is the roughness.
     */
    private static final double DIELECTRIC = 0.04;

    /**
     * The roughness the shader will not go below, whatever it is handed.
     *
     * <p>A defence rather than a look: {@code WatchMaterials} bakes its own
     * floor into the atlas, but a texture pack may put a texel of pure black in
     * a surface map, and a perfectly smooth surface concentrates a light into a
     * highlight narrower than a pixel — which in motion is not a mirror but a
     * fragment flickering on and off as you walk.
     */
    private static final double MIN_ROUGH = 0.03;

    /**
     * How far the ground's colour drifts over tens of metres.
     *
     * <p>Small on purpose: this is not weather and not a stain, it is the
     * difference between a hillside and a hillside-shaped piece of wallpaper.
     * Under a tenth reads as ground that varies; much over it reads as a stain,
     * and it starts to fight the biome's own colours — which are the thing the
     * guide's pages and the map are drawn from and must stay recognisable.
     */
    private static final double MACRO_AMOUNT = 0.085;

    private static final String VERTEX = """
            #version 330 core
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec2 aUV;
            layout(location = 2) in vec4 aColor;

            uniform mat4 uMvp;
            uniform mat4 uModelView;
            uniform mat4 uLightMvp;   // …and the same vertex, seen from the sun
            uniform vec3 uMeshOrigin; // where this mesh sits, wrapped; see below

            out vec2 vUV;
            out vec4 vColor;
            out vec3 vEye;
            out vec3 vWorld;
            out vec4 vShadow;

            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
                vUV = aUV;
                vColor = aColor;
                // Where this vertex is in the camera's own frame. Its z is the
                // distance in front of the eye, which is what the fog reads —
                // the view matrix's third row is the camera's forward axis. The
                // other two components are what the lighting needs: a point
                // light arrives already in this frame, so a fragment can
                // subtract without a second matrix.
                vEye = (uModelView * vec4(aPos, 1.0)).xyz;
                // Where it is in the world — x and y wrapped to the drift's own
                // period so they stay small and exact, z left alone because a
                // height is a small number and the mist is measured against it.
                vWorld = uMeshOrigin + aPos;
                // …and where it is from the sun, for the shadow lookup. The
                // matrix is the identity until a shadow pass uploads a real
                // one — and while it is, uShadow is zero and the fragment
                // shader never reads this.
                vShadow = uLightMvp * vec4(aPos, 1.0);
            }
            """;

    private static final String FRAGMENT = """
            #version 330 core
            in vec2 vUV;
            in vec4 vColor;
            in vec3 vEye;
            in vec3 vWorld;
            in vec4 vShadow;

            uniform sampler2D uAtlas;
            // …and what the light does to it: rg a tangent-space normal, b
            // roughness, a metalness. See MeshPass.setSurface.
            uniform sampler2D uSurface;
            // A *shadow* sampler: the texture unit does the depth comparison
            // and the filtering, and hands back how much of this fragment is
            // lit. See GlShadowMap's texture setup.
            uniform sampler2DShadow uShadowMap;
            uniform vec4 uFog;        // rgb = colour, a = 1 when fog is on
            uniform vec2 uFogRange;   // x = start, y = end
            uniform float uAlphaCut;  // discard below this; 0 in the blended pass

            uniform float uPbr;       // 0 = no surface atlas; diffuse only
            uniform float uAtlasGain; // what a colour texel means; see DETAIL_GAIN
            uniform vec2 uAtlasTexels;// the atlas's size, for the detail fade

            uniform vec3 uDaylight;   // the hour, as a per-channel multiplier
            uniform int uLightCount;  // lamps that light this mesh's surface …
            uniform int uAirCount;    // … and those, plus the ones lighting the air
            uniform vec4 uLightPos[MAX_LIGHTS];    // xyz eye-space, w = 1 / radius
            uniform vec4 uLightColour[MAX_LIGHTS]; // rgb, a = intensity

            uniform vec3 uSunDir;     // eye space, unit, pointing at the sun
            uniform vec3 uSunColour;  // already scaled by how much sun there is
            uniform vec3 uSkyTint;    // ambient on what faces up …
            uniform vec3 uGroundTint; // … and on what faces down
            uniform vec3 uUpAxis;     // world +Z, in eye space

            uniform float uShadow;     // 0 disables the lookup entirely
            uniform vec2 uShadowTexel; // one texel of the map, in UV
            uniform vec2 uShadowBias;  // x = flat, y = slope-scaled

            // …and the same three for the one lamp that has a shadow of its
            // own, which is always the lamp in slot 0 when there is one.
            uniform samplerCubeShadow uLampShadowMap;
            uniform float uLampShadow; // 0 = this mesh has no lamp shadow
            uniform vec4 uLampDepth;   // x = A, y = B, z = flat bias, w = near
            uniform float uLampSlope;  // …and the slope-scaled bias, in metres
            uniform mat3 uEyeToWorld;  // an eye-space direction, in world axes

            uniform float uHaze;      // weather extinction, per metre
            uniform vec2 uHazeBand;   // x = the height mist pools at, y = its depth
            uniform float uEyeZ;      // the camera's own height, for the integral
            uniform float uScatter;   // how much of a lamp the air carries back
            uniform float uVibrance;  // one knob of grade; 0 is "leave it alone"
            uniform float uTime;      // the drawing clock, for the drift

            const float PI = 3.14159265;

            out vec4 fragColor;

            /**
             * How much of the sun this fragment has lost to something in front
             * of it, 0 (full sun) to 1 (fully shadowed).
             */
            float shadowed(float ndl) {
                if (uShadow <= 0.0) return 0.0;
                // Orthographic, so w is 1 and there is nothing to divide by —
                // which is also why the depth in the map is linear and a bias
                // in metres means the same thing at both ends of it.
                vec3 p = vShadow.xyz * 0.5 + 0.5;
                if (p.z <= 0.0 || p.z >= 1.0) return 0.0;
                vec2 edge = abs(p.xy * 2.0 - 1.0);
                if (max(edge.x, edge.y) >= 1.0) return 0.0;
                // Slope-scaled: a surface the sun rakes across covers many
                // texels of depth in one texel of map, and a flat bias large
                // enough for it would detach every shadow from its own tree.
                float slope = sqrt(max(0.0, 1.0 - ndl * ndl)) / max(0.12, ndl);
                float ref = p.z - (uShadowBias.x + uShadowBias.y * min(slope, 6.0));
                // <b>Four fetches, not nine, and softer for it.</b> Each of
                // these is a hardware comparison against four texels and a
                // bilinear blend of the four answers, so this kernel sees
                // sixteen texels — and the offsets are a half-texel diagonal
                // cross, which puts those four two-by-two footprints corner to
                // corner rather than overlapping.
                //
                // Explicit level throughout: this runs inside a branch on the
                // fragment's own normal, and an implicit level is a derivative
                // asked for in non-uniform control flow. The map has no mipmaps
                // to choose between anyway.
                vec2 step = uShadowTexel;
                float lit = textureLod(uShadowMap,
                                vec3(p.xy + vec2(-step.x, -step.y), ref), 0.0)
                        + textureLod(uShadowMap,
                                vec3(p.xy + vec2(step.x, -step.y), ref), 0.0)
                        + textureLod(uShadowMap,
                                vec3(p.xy + vec2(-step.x, step.y), ref), 0.0)
                        + textureLod(uShadowMap,
                                vec3(p.xy + vec2(step.x, step.y), ref), 0.0);
                // Faded at the rim, so the far edge of the map is not a line
                // ruled across the wood.
                return (1.0 - lit * 0.25)
                        * (1.0 - smoothstep(0.80, 1.0, max(edge.x, edge.y)));
            }

            /**
             * How much of the shadowing lamp reaches this fragment: 1 in the
             * open, 0 behind a trunk.
             *
             * <p><b>A cube map is addressed by a direction, so there is no
             * matrix and no projected coordinate</b> — the vector from the lamp
             * to here <em>is</em> the lookup, and the only work is arriving at
             * a depth to compare against. A perspective projection stored
             * {@code A + B / major}, where {@code major} is the largest
             * component of that vector (which is what picks the face, and what
             * that face's own w came out as), so undoing it is one divide.
             *
             * <p>The bias is applied to the distance, in metres, before the
             * curve rather than after it. That is the one way a perspective
             * shadow map is easier than an orthographic one: the shader is
             * holding a real distance at exactly the moment it needs to nudge
             * it, so a centimetre means a centimetre under the flame and
             * across the clearing alike.
             *
             * <p>Plain {@code texture} rather than {@code textureLod}, unlike
             * the sun's: this is called from a branch on {@code uLampShadow},
             * which is a uniform, so the control flow is uniform across the
             * whole draw and an implicit derivative is well defined.
             */
            float lampLit(vec3 fromLamp, float ndotl) {
                vec3 dir = uEyeToWorld * fromLamp;
                float major = max(abs(dir.x), max(abs(dir.y), abs(dir.z)));
                // Slope-scaled for the sun's reason: a surface the light rakes
                // across covers many texels of depth in one texel of map, and a
                // flat bias big enough for it would lift every shadow off the
                // foot of its own tree.
                float slope = sqrt(max(0.0, 1.0 - ndotl * ndotl)) / max(0.15, ndotl);
                float bias = uLampDepth.z + uLampSlope * min(slope, 6.0);
                float ref = uLampDepth.x
                        + uLampDepth.y / max(major - bias, uLampDepth.w);
                return texture(uLampShadowMap, vec4(dir, ref * 0.5 + 0.5));
            }

            /**
             * How much air the view ray went through, as optical depth.
             *
             * <p>The closed form of an exponential whose density falls off with
             * height: mist pools in the hollows and a ridge stands out of it,
             * for two exponentials and a divide rather than a march.
             */
            float airMass(float through) {
                if (uHaze <= 0.0) return 0.0;
                float scale = max(0.5, uHazeBand.y);
                float here = max(0.0, uEyeZ - uHazeBand.x) / scale;
                float there = max(0.0, vWorld.z - uHazeBand.x) / scale;
                float rise = there - here;
                float mass = abs(rise) < 0.002
                        ? exp(-here)
                        : (exp(-here) - exp(-there)) / rise;
                // Banks of it, drifting. Both frequencies are exact multiples of
                // one cycle per DRIFT_PERIOD metres, which is what makes the
                // wrap in vWorld invisible.
                float drift = 1.0 + 0.34
                        * sin(vWorld.x * DRIFT_A + uTime * 0.11)
                        * sin(vWorld.y * DRIFT_B - uTime * 0.083);
                return uHaze * through * mass * drift;
            }

            /**
             * What one lamp puts into the air between the eye and this
             * fragment.
             *
             * <p>The eye is the origin of this frame, so the nearest the ray
             * passes to the lamp is one dot product, and how much of the ray
             * lies inside the lamp's sphere is the chord at that distance,
             * clipped to the part in front of the fragment. That is the glow
             * round a fire.
             *
             * <p><b>All of it in units of the lamp's own radius</b>, which is
             * what removes the divides: the chord, the span and the falloff are
             * all ratios to it, so scaling every length by 1/radius up front
             * leaves nothing to divide by later.
             */
            vec3 scattered(int i, vec3 ray, float viewLen, float invView, float mass) {
                if (uScatter <= 0.0) return vec3(0.0);
                vec3 at = uLightPos[i].xyz;
                float invR = uLightPos[i].w;
                float along = clamp(dot(at, ray), 0.0, viewLen);
                vec3 offset = at - ray * along;
                float missSq = dot(offset, offset) * invR * invR;
                float reach = max(0.0, 1.0 - sqrt(missSq));
                float chord = sqrt(max(0.0, 1.0 - missSq));
                float a = along * invR;
                float span = max(0.0, min(viewLen * invR, a + chord)
                        - max(0.0, a - chord));
                // Two corrections, and without either of them a fire in fog is
                // a wall of white.
                //
                // CHORD, because `reach` is the falloff where the ray passes
                // closest and the rest of the chord is further out than that;
                // taking the peak over the whole length roughly doubles the
                // answer.
                //
                // And the transmittance, because this light also has to get
                // back to the eye through the same air that is carrying it.
                // Left out, thick fog would scatter light to the camera from
                // any distance for free — which is the one direction the error
                // cannot be tolerated in, since thick fog is exactly when the
                // term is largest. The exponential only when there is air to
                // speak of: `mass` is the same for every lamp on this fragment,
                // so the branch is coherent, and in clear weather it takes
                // sixteen exponentials a fragment down to none.
                float back = mass > 0.002
                        ? exp(-mass * clamp(along * invView, 0.0, 1.0))
                        : 1.0;
                return uLightColour[i].rgb * (uLightColour[i].a * reach * reach
                        * span * CHORD * uScatter * back);
            }

            /**
             * A slow drift over the whole ground, {@code −1} to {@code 1}.
             *
             * <p><b>What stops a two-metre tile reading as a two-metre tile.</b>
             * The Field Guide's atlas is stretched across one quad of the
             * heightfield, so without this the ground is the same stamp
             * repeated to the horizon — the one thing that makes a textured
             * world look worse than an untextured one, because the eye reads
             * the grid rather than the ground.
             *
             * <p>Two sines whose phases are modulated by two more, which is a
             * value noise for four transcendentals and no texture fetch. Every
             * frequency is an exact multiple of one cycle per DRIFT_PERIOD
             * metres, for the reason the fog's drift is: {@code vWorld} is
             * folded into that period on the CPU, and a term that did not share
             * it would put a seam across the world every five hundred metres.
             */
            float macro() {
                return sin(vWorld.x * MACRO_A + sin(vWorld.y * MACRO_B) * 1.7)
                        * sin(vWorld.y * MACRO_C - sin(vWorld.x * MACRO_D) * 1.3);
            }

            /**
             * One specular lobe: GGX, height-correlated Smith, and the
             * Fresnel left to the caller.
             *
             * <p>Returns {@code D · Vis · n·l}, already divided by the
             * {@code 4 n·l n·v} the microfacet model would otherwise carry, so
             * a light's own contribution is this times its colour times its
             * Fresnel and nothing else. The three pieces are the ones every
             * one of these is made of; what is worth saying about this one is
             * that it is written to be <em>cheap enough to run per lamp</em> —
             * no {@code pow} in the distribution, one divide, and the visibility
             * term's own denominator folded into the same divide.
             */
            float lobe(vec3 n, vec3 v, vec3 l, float rough) {
                vec3 h = normalize(v + l);
                float ndh = max(dot(n, h), 0.0);
                float ndv = max(dot(n, v), 1e-4);
                float ndl = max(dot(n, l), 0.0);
                float a = rough * rough;
                float a2 = a * a;
                float d = ndh * ndh * (a2 - 1.0) + 1.0;
                float dist = a2 / max(PI * d * d, 1e-8);
                // Smith, height-correlated, in Hammon's approximate form: the
                // exact one wants two square roots and this is within a percent
                // of it everywhere that is not grazing.
                float vis = 0.5 / max(ndl * (ndv * (1.0 - a) + a)
                        + ndv * (ndl * (1.0 - a) + a), 1e-5);
                return dist * vis * ndl;
            }

            /** Schlick's Fresnel: how much of a light bounces off rather than in. */
            vec3 fresnel(vec3 f0, float cosine) {
                float f = 1.0 - cosine;
                float f2 = f * f;
                return f0 + (1.0 - f0) * (f2 * f2 * f);
            }

            void main() {
                // <b>Every derivative this shader takes, taken here.</b> A
                // derivative is computed across a two-by-two block of fragments,
                // and asking for one after half that block has discarded itself
                // is asking a question the driver is not obliged to answer — so
                // they come before the alpha test, all of them, and the rest of
                // the shader works from the four values.
                vec3 dpx = dFdx(vEye);
                vec3 dpy = dFdy(vEye);
                vec2 dux = dFdx(vUV);
                vec2 duy = dFdy(vUV);

                // The face's own normal, out of the screen-space gradient of the
                // eye-space position. This world is flat shaded, so the plane a
                // fragment lies in *is* its triangle's plane and this is exact
                // rather than an approximation — and it costs no vertex
                // attribute, no re-mesh and no upload.
                //
                // Turned toward the eye afterwards, because grass, leaves and
                // water are single-sided sheets meant to be seen from either
                // face: a lantern behind a blade of grass has to light the side
                // you are looking at.
                vec3 n = normalize(cross(dpx, dpy));
                if (dot(n, vEye) > 0.0) n = -n;
                vec3 face = n;

                // Every block has an atlas cell — a sheetless one shares a
                // white cell — so this is unconditional and a section of mixed
                // blocks is still one draw call. See BlockAtlas.
                //
                // uAtlasGain is one for the voxel world, whose atlas holds
                // colours, and two for the Field Guide's, whose atlas holds
                // each tile divided by its own average — see MeshPass.DETAIL_GAIN
                // for why the second kind exists and what the first cost.
                vec4 texel = texture(uAtlas, vUV);
                vec4 colour = vec4(texel.rgb * uAtlasGain, texel.a) * vColor;
                if (colour.a < uAlphaCut) discard;

                // One reciprocal square root for all three of these. A divide
                // is several times the cost of a multiply on most cards and
                // this loop is about to do a great many of them.
                float eye2 = max(dot(vEye, vEye), 1e-8);
                float invView = inversesqrt(eye2);
                float viewLen = eye2 * invView;
                vec3 ray = vEye * invView;
                vec3 view = -ray;
                // How much air the view ray came through, once, for both the
                // fog and the lamps that are shining into it.
                float mass = airMass(viewLen);

                // <b>What this surface is made of.</b> Everything from here to
                // the closing brace is skipped entirely when no surface atlas
                // has been handed over, which is the voxel world's case: f0
                // stays black, every specular term below multiplies by it, and
                // the frame is the diffuse one this shader drew before any of
                // this existed.
                float rough = 1.0;
                vec3 f0 = vec3(0.0);
                float mirror = 0.0;
                if (uPbr > 0.0) {
                    vec4 surf = texture(uSurface, vUV);
                    // How many texels of the surface map this fragment covers.
                    // Past one the map is noise the screen cannot resolve, so
                    // the relief fades out and what it stops resolving is
                    // handed to the roughness instead — which is what a
                    // microfacet model *is*, and is why a hillside two hundred
                    // metres off is a soft sheen rather than a field of sparks.
                    float texels = max(length(dux * uAtlasTexels),
                            length(duy * uAtlasTexels));
                    float detail = clamp(2.0 - texels, 0.0, 1.0);
                    vec2 slope = surf.rg * 2.0 - 1.0;
                    rough = clamp(surf.b + (1.0 - detail) * 0.45 * length(slope),
                            MIN_ROUGH, 1.0);
                    float metal = surf.a;

                    // A tangent frame from the same four derivatives, which is
                    // the whole reason this vertex format can carry a normal
                    // map at all: a tangent per vertex would be twelve more
                    // bytes on every vertex of every chunk, to carry something
                    // the screen-space gradients already know.
                    //
                    // The guard is not defensive. Most of this world's meshes —
                    // every animal, every plank, every leaf — give all three
                    // vertices of a triangle *one* texture coordinate, because
                    // they are flat-shaded facets that want a single texel of
                    // the atlas. Their UV derivatives are exactly zero, there
                    // is no frame to build, and the geometric normal is the
                    // right answer.
                    vec3 px = cross(dpy, n);
                    vec3 py = cross(n, dpx);
                    vec3 tangent = px * dux.x + py * duy.x;
                    vec3 binormal = px * dux.y + py * duy.y;
                    float frame = max(dot(tangent, tangent), dot(binormal, binormal));
                    if (frame > 1e-14) {
                        vec2 bump = slope * detail;
                        float up = sqrt(max(1e-4, 1.0 - dot(bump, bump)));
                        n = normalize((tangent * bump.x + binormal * bump.y)
                                * inversesqrt(frame) + n * up);
                    }

                    // …and the ground's own long variation over it, so that
                    // the tile repeating every two metres is not what the eye
                    // finds first. See macro().
                    colour.rgb *= 1.0 + MACRO * macro();

                    // A metal reflects with its own colour and has no diffuse;
                    // everything that grew reflects white over a coloured body,
                    // at the four percent every dielectric shares.
                    f0 = mix(vec3(DIELECTRIC), colour.rgb, metal);
                    colour.rgb *= 1.0 - metal;
                }

                // The sky above and the ground below, as one mix on which way
                // the face is turned. Identical tints collapse to exactly the
                // old flat multiplier, which is what GlTerrainPass relies on.
                // Written as an add rather than as mix() on purpose: GLSL
                // defines mix(x, y, a) as x*(1-a) + y*a, which for two equal
                // tints is (1-a) + a and need not come back as exactly one.
                // This form is the identity when they match, which is the
                // promise use() makes to GlTerrainPass.
                float facing = dot(n, uUpAxis) * 0.5 + 0.5;
                vec3 light = uDaylight
                        * (uGroundTint + (uSkyTint - uGroundTint) * facing);
                vec3 gloss = vec3(0.0);
                float ndv = max(dot(n, view), 1e-4);

                // The sun, and whatever is standing between it and here.
                float ndl = dot(n, uSunDir);
                if (ndl > 0.0) {
                    // The bias is asked of the *face*, not of the bump. A depth
                    // map is written from geometry, so how steeply the sun
                    // rakes across what was drawn into it is a question about
                    // the triangle; asking the normal map instead is how a
                    // shadow acquires acne in every hollow of its own texture.
                    // With no surface atlas the two are the same vector.
                    float open = 1.0 - uShadow * shadowed(dot(face, uSunDir));
                    light += uSunColour * (ndl * open);
                    if (uPbr > 0.0) {
                        vec3 h = normalize(view + uSunDir);
                        gloss += uSunColour * fresnel(f0, max(dot(view, h), 0.0))
                                * (open * lobe(n, view, uSunDir, rough));
                    }
                }

                // <b>Two loops over one array, and the split is a cull.</b>
                // The lamps that light this mesh's surface come first and are
                // the ones near it; after them come the lamps that light only
                // the air in front of it — a lantern at your feet does nothing
                // to a hillside a hundred metres off and a great deal to the
                // mist between you and it. Everything past uAirCount is
                // somewhere else in the world entirely and is never read.
                // See LightCull, which decides both on the CPU.
                vec3 air = vec3(0.0);
                for (int i = 0; i < uLightCount; i++) {
                    vec3 at = uLightPos[i].xyz;
                    // The *reciprocal* of the radius, worked out once on the
                    // CPU. Every use of a radius in here is either a division
                    // by it or a comparison against it, and this loop runs
                    // sixteen times for every fragment on the screen.
                    float invR = uLightPos[i].w;
                    vec3 to = at - vEye;
                    float d2 = max(dot(to, to), 1e-8);
                    float invD = inversesqrt(d2);
                    float away = d2 * invD;
                    // Compact falloff: a light is either inside a fragment's
                    // reckoning or costs it nothing. Squared, so the pool under
                    // a lantern has an edge that reads as light rather than as a
                    // circle drawn on the ground.
                    float fall = max(0.0, 1.0 - away * invR);
                    fall *= fall;
                    vec3 toward = to * invD;
                    float ndotl = max(0.0, dot(n, toward));
                    light += uLightColour[i].rgb * (uLightColour[i].a * fall
                            * (WRAP + (1.0 - WRAP) * ndotl));
                    // A flame has a highlight too, and on wet rock beside a
                    // fire it is most of what you see. The branch is on a
                    // uniform, so it is coherent across the whole draw — and
                    // on the path where it is false this loop does not run at
                    // all, because the voxel world sets no lights.
                    if (uPbr > 0.0) {
                        vec3 h = normalize(view + toward);
                        gloss += uLightColour[i].rgb * fresnel(f0, max(dot(view, h), 0.0))
                                * (uLightColour[i].a * fall
                                        * lobe(n, view, toward, rough));
                    }
                    air += scattered(i, ray, viewLen, invView, mass);
                }
                for (int i = uLightCount; i < uAirCount; i++) {
                    air += scattered(i, ray, viewLen, invView, mass);
                }

                // <b>The one lamp with a shadow, taken back out afterwards.</b>
                // Slot 0 is that lamp whenever uLampShadow is non-zero — see
                // GlMeshPass.chooseLights, which puts it there — so the loop
                // above has already added the whole of its contribution and
                // this removes whatever a trunk was standing in the way of.
                //
                // Written as a subtraction rather than as a factor inside the
                // loop because the loop is the hot path of the whole shader and
                // a branch in it is not free: an early `continue` in this same
                // loop was measured at thirty-five percent slower, because it
                // stops the compiler unrolling a body of a dozen instructions.
                // Out here it is a uniform branch, coherent across the entire
                // draw, and it costs the fragments that are not near the fire
                // precisely nothing.
                if (uLampShadow > 0.0) {
                    vec3 to = uLightPos[0].xyz - vEye;
                    float d2 = max(dot(to, to), 1e-8);
                    float invD = inversesqrt(d2);
                    float fall = max(0.0, 1.0 - d2 * invD * uLightPos[0].w);
                    fall *= fall;
                    float ndotl = max(0.0, dot(n, to * invD));
                    vec3 full = uLightColour[0].rgb * (uLightColour[0].a * fall
                            * (WRAP + (1.0 - WRAP) * ndotl));
                    light -= full * (uLampShadow * (1.0 - lampLit(-to, ndotl)));
                }

                // <b>And the sky itself, reflected — the term that makes water
                // water.</b>
                //
                // A lake has no highlight to speak of except at the sun, and it
                // is still the brightest thing in a valley, because what it is
                // showing you is the whole sky. So the same two-colour ambient
                // is sampled a second time along the <em>reflected</em> ray
                // rather than along the normal, and weighted by a Fresnel that
                // opens up at grazing angles — which is why the far side of a
                // lake is bright and the water at your feet is not, and why the
                // edge of a wet stone lights up as you walk past it.
                //
                // Karis's form of the roughness-aware Fresnel: a rough surface
                // cannot mirror the sky whatever the angle, so the term it
                // climbs toward is (1 − roughness) rather than one.
                if (uPbr > 0.0) {
                    vec3 bounce = reflect(-view, n);
                    float above = dot(bounce, uUpAxis) * 0.5 + 0.5;
                    // <b>The fog's colour is the sky's colour</b>, and it is the
                    // only absolute one this shader is given: everything else
                    // about the light arrives as a multiplier around one, which
                    // is what an irradiance is and is no use at all as a thing
                    // to see a reflection of. The haze at the horizon is
                    // literally what a lake at a shallow angle is showing you,
                    // it already has the hour and the weather in it, and it is
                    // pink at dawn — so the water is too. With no fog at all
                    // this falls back to the plain daylight multiplier and the
                    // reflection is white, which is the old behaviour of a term
                    // that did not exist.
                    vec3 sky = mix(uDaylight, uFog.rgb, uFog.a)
                            * (uGroundTint + (uSkyTint - uGroundTint) * above);
                    float grazing = 1.0 - ndv;
                    float g2 = grazing * grazing;
                    vec3 mirrored = f0 + (max(vec3(1.0 - rough), f0) - f0)
                            * (g2 * g2 * grazing);
                    gloss += sky * mirrored;
                    // How much of this fragment is reflection rather than
                    // surface, kept for the alpha below.
                    mirror = max(mirrored.r, max(mirrored.g, mirrored.b));
                }

                // <b>What the reflection takes, the body underneath does not
                // get.</b> Standing at the edge of a lake you cannot see into
                // the far half of it at all — every photon arriving from there
                // bounced off the surface — and a model that adds a mirror on
                // top of a fully lit diffuse instead of in place of it is a
                // model whose water is brighter than the sky it is reflecting.
                // Zero when there is no surface atlas, so the voxel world is
                // untouched.
                colour.rgb *= light * (1.0 - mirror);
                colour.rgb += gloss;

                // <b>A highlight has to survive the blend.</b> Water is drawn
                // in the see-through pass, so everything above is about to be
                // multiplied by an alpha that says how deep the lake is — and
                // a reflection is not something you can see through. At a
                // grazing angle water is a mirror rather than a window, which
                // is exactly what `mirror` measures, so the same term that put
                // the sky on the lake makes the lake opaque where it did.
                // Nothing happens in the opaque pass, where the alpha is
                // already one.
                colour.a = colour.a + (1.0 - colour.a) * mirror;

                // Fog after the light and before the air: haze interpolates, so
                // a lit thing a long way off is still mostly haze — applying
                // them the other way round would let a fire beside the camera
                // brighten the horizon. What the lamps put *in* the haze is
                // added afterwards, because that light did not come off the
                // hillside and must not be faded out with it.
                if (uFog.a > 0.0) {
                    float t = clamp((vEye.z - uFogRange.x)
                                    / max(1.0, uFogRange.y - uFogRange.x), 0.0, 1.0);
                    // Squared, so the near half of the view is barely touched
                    // and the far edge goes all the way — the same curve the
                    // painter uses, and a linear one greys the middle distance
                    // and makes the whole world look like weather.
                    float haze = t * t;
                    haze = 1.0 - (1.0 - haze) * exp(-mass);
                    // Bright toward the sun and not away from it, which is most
                    // of why standing in fog at dawn feels like anything.
                    float toward = max(0.0, dot(ray, uSunDir));
                    vec3 tint = uFog.rgb + uSunColour
                            * (1.6 * pow(toward, 8.0) + 0.28 * toward);
                    colour.rgb = mix(colour.rgb, tint, haze);
                }
                colour.rgb += air;

                if (uVibrance > 0.0) {
                    float grey = dot(colour.rgb, vec3(0.2126, 0.7152, 0.0722));
                    vec3 off = colour.rgb - vec3(grey);
                    // <b>Vibrance rather than saturation, which is the whole
                    // difference between a graded frame and a tinted one.</b>
                    // How much colour a fragment already has decides how much
                    // more it is given: moss, lichen, a lake and a shaded
                    // hillside all sit near the grey axis and come up, while a
                    // fox, a rowan berry and a sunlit petal are already at the
                    // top of the ramp and are left where they are. A flat
                    // saturation lifts both and the second one clips.
                    float have = clamp(length(off) / max(grey, 0.04), 0.0, 1.0);
                    colour.rgb = max(vec3(0.0),
                            vec3(grey) + off * (1.0 + uVibrance * (0.85 - 0.55 * have)));
                    // A knee rather than a clamp: everything above it is
                    // compressed toward one instead of stopping dead there, so
                    // the middle of a lantern's pool keeps the lantern's colour
                    // rather than going white — and so does the sun sitting on
                    // the water, which is the brightest thing this shader can
                    // now produce.
                    float knee = 1.0 - 0.34 * uVibrance;
                    vec3 over = max(colour.rgb - knee, vec3(0.0));
                    colour.rgb = min(colour.rgb, vec3(knee))
                            + over * (1.0 - knee) / ((1.0 - knee) + over);
                }
                fragColor = colour;
            }
            """
            .replace("MAX_LIGHTS", String.valueOf(MAX_LIGHTS))
            .replace("WRAP", String.format(Locale.ROOT, "%.4f", WRAP))
            .replace("DRIFT_A", String.format(Locale.ROOT, "%.8f",
                    3 * 2 * Math.PI / DRIFT_PERIOD))
            .replace("DRIFT_B", String.format(Locale.ROOT, "%.8f",
                    2 * 2 * Math.PI / DRIFT_PERIOD))
            .replace("CHORD", String.format(Locale.ROOT, "%.3f", CHORD_MEAN))
            .replace("DIELECTRIC", String.format(Locale.ROOT, "%.4f", DIELECTRIC))
            .replace("MIN_ROUGH", String.format(Locale.ROOT, "%.4f", MIN_ROUGH))
            .replace("MACRO_A", cycles(21))
            .replace("MACRO_B", cycles(13))
            .replace("MACRO_C", cycles(17))
            .replace("MACRO_D", cycles(29))
            .replace("MACRO", String.format(Locale.ROOT, "%.3f", MACRO_AMOUNT));

    private final int program;
    private final int uMvp;
    private final int uModelView;
    private final int uLightMvp;
    private final int uMeshOrigin;
    private final int uAtlas;
    private final int uSurface;
    private final int uPbr;
    private final int uAtlasGain;
    private final int uAtlasTexels;
    private final int uShadowMap;
    private final int uFog;
    private final int uFogRange;
    private final int uAlphaCut;
    private final int uDaylight;
    private final int uLightCount;
    private final int uAirCount;
    private final int uLightPos;
    private final int uLightColour;
    private final int uSunDir;
    private final int uSunColour;
    private final int uSkyTint;
    private final int uGroundTint;
    private final int uUpAxis;
    private final int uShadow;
    private final int uShadowTexel;
    private final int uShadowBias;
    private final int uLampShadowMap;
    private final int uLampShadow;
    private final int uLampDepth;
    private final int uLampSlope;
    private final int uEyeToWorld;
    private final int uHaze;
    private final int uHazeBand;
    private final int uEyeZ;
    private final int uScatter;
    private final int uVibrance;
    private final int uTime;

    /** Reused, so setting the lights on a frame allocates nothing. */
    private final float[] lightPos = new float[MAX_LIGHTS * 4];
    private final float[] lightColour = new float[MAX_LIGHTS * 4];

    /**
     * The frame's lamps in eye space, in the order the caller gave them.
     *
     * <p>Worked out once a frame and then <em>gathered</em> from, because the
     * subset of lamps that matters is now decided per mesh: a frame of three
     * hundred meshes would otherwise put the same sixteen lamps through the
     * same camera transform three hundred times over.
     */
    private final float[] frameLightPos = new float[MAX_LIGHTS * 4];
    private final float[] frameLightColour = new float[MAX_LIGHTS * 4];
    private int frameLightCount;

    /**
     * Which subset was last uploaded, and how it was split.
     *
     * <p>Most meshes in a frame have the same answer — usually none at all —
     * and a uniform upload that changes nothing is still a uniform upload.
     */
    private long uploadedSubset = Long.MIN_VALUE;
    private final double[] inEyeSpace = new double[3];
    private final float[] identity = identityMatrix();

    /** …and the same trick for the lamp shadow, which is one float per mesh. */
    private float uploadedLampShadow = -1;

    /** Scratch for {@link #setLampShadow}, so a frame allocates nothing. */
    private final float[] eyeToWorld = new float[9];
    private final float[] lampDepth = new float[4];

    private static final float[] IDENTITY_3 = {1, 0, 0, 0, 1, 0, 0, 0, 1};

    GlTerrainProgram() {
        program = link(compile(GL_VERTEX_SHADER, VERTEX), compile(GL_FRAGMENT_SHADER, FRAGMENT));
        uMvp = glGetUniformLocation(program, "uMvp");
        uModelView = glGetUniformLocation(program, "uModelView");
        uLightMvp = glGetUniformLocation(program, "uLightMvp");
        uMeshOrigin = glGetUniformLocation(program, "uMeshOrigin");
        uAtlas = glGetUniformLocation(program, "uAtlas");
        uSurface = glGetUniformLocation(program, "uSurface");
        uPbr = glGetUniformLocation(program, "uPbr");
        uAtlasGain = glGetUniformLocation(program, "uAtlasGain");
        uAtlasTexels = glGetUniformLocation(program, "uAtlasTexels");
        uShadowMap = glGetUniformLocation(program, "uShadowMap");
        uFog = glGetUniformLocation(program, "uFog");
        uFogRange = glGetUniformLocation(program, "uFogRange");
        uAlphaCut = glGetUniformLocation(program, "uAlphaCut");
        uDaylight = glGetUniformLocation(program, "uDaylight");
        uLightCount = glGetUniformLocation(program, "uLightCount");
        uAirCount = glGetUniformLocation(program, "uAirCount");
        // An array's location is the location of its first element, and that is
        // the spelling a driver answers to. `uLightPos` on its own returns −1 on
        // some of them, which is a silent no-op rather than an error.
        uLightPos = glGetUniformLocation(program, "uLightPos[0]");
        uLightColour = glGetUniformLocation(program, "uLightColour[0]");
        uSunDir = glGetUniformLocation(program, "uSunDir");
        uSunColour = glGetUniformLocation(program, "uSunColour");
        uSkyTint = glGetUniformLocation(program, "uSkyTint");
        uGroundTint = glGetUniformLocation(program, "uGroundTint");
        uUpAxis = glGetUniformLocation(program, "uUpAxis");
        uShadow = glGetUniformLocation(program, "uShadow");
        uShadowTexel = glGetUniformLocation(program, "uShadowTexel");
        uShadowBias = glGetUniformLocation(program, "uShadowBias");
        uLampShadowMap = glGetUniformLocation(program, "uLampShadowMap");
        uLampShadow = glGetUniformLocation(program, "uLampShadow");
        uLampDepth = glGetUniformLocation(program, "uLampDepth");
        uLampSlope = glGetUniformLocation(program, "uLampSlope");
        uEyeToWorld = glGetUniformLocation(program, "uEyeToWorld");
        uHaze = glGetUniformLocation(program, "uHaze");
        uHazeBand = glGetUniformLocation(program, "uHazeBand");
        uEyeZ = glGetUniformLocation(program, "uEyeZ");
        uScatter = glGetUniformLocation(program, "uScatter");
        uVibrance = glGetUniformLocation(program, "uVibrance");
        uTime = glGetUniformLocation(program, "uTime");
    }

    /**
     * Whether the driver kept every uniform the lighting block needs.
     *
     * <p>For {@code GlLightingTest}, and worth the method: a uniform array's
     * location is looked up by the spelling {@code uLightPos[0]}, and getting
     * that wrong returns −1, which {@code glUniform4fv} accepts in silence. The
     * failure mode is a world that draws perfectly and is never lit by
     * anything — exactly the kind of thing that looks like "the lights are too
     * dim" for an afternoon.
     */
    boolean lightingUniformsResolved() {
        return uDaylight >= 0 && uLightCount >= 0 && uAirCount >= 0 && uLightPos >= 0
                && uLightColour >= 0;
    }

    /**
     * …and the sky's own, which fail the same silent way.
     *
     * <p>Separate from {@link #lightingUniformsResolved} because they fail for
     * a different reason: a driver is entitled to optimise a uniform away
     * entirely when nothing the shader does with it can affect the output, so
     * this is only meaningful about a shader whose sky terms are reachable —
     * which every one of these is, unconditionally, by construction above.
     */
    boolean skyUniformsResolved() {
        return uSunDir >= 0 && uSunColour >= 0 && uSkyTint >= 0 && uGroundTint >= 0
                && uUpAxis >= 0 && uShadow >= 0 && uHaze >= 0 && uHazeBand >= 0
                && uScatter >= 0 && uVibrance >= 0;
    }

    /**
     * …and the material half's, which fail the same silent way a third time.
     *
     * <p>The failure mode here is the flattest of the three: a world with a
     * surface atlas uploaded, a shader that never reads it, and a picture that
     * is exactly the diffuse one it drew before — no highlight on the water, no
     * relief on the ground, and nothing anywhere saying why. Worse, the atlas
     * beside it is a <em>detail</em> map, so a lost {@code uAtlasGain} does not
     * fail quietly at all: the whole world comes back at half brightness.
     */
    boolean surfaceUniformsResolved() {
        return uSurface >= 0 && uPbr >= 0 && uAtlasGain >= 0 && uAtlasTexels >= 0;
    }

    /**
     * …and the lamp cube map's, which fail the same silent way again.
     *
     * <p>Worth its own method for the same reason the other two are: a
     * {@code samplerCubeShadow} left at its default zero samples texture unit
     * 0, which is the colour atlas — a mismatched sampler whose result is
     * undefined and, on the drivers where it is anything at all, is not a
     * shadow. The failure mode is a night that looks lit and never shadows.
     */
    boolean lampShadowUniformsResolved() {
        return uLampShadowMap >= 0 && uLampShadow >= 0 && uLampDepth >= 0
                && uLampSlope >= 0 && uEyeToWorld >= 0;
    }

    void use() {
        glUseProgram(program);
        glUniform1i(uAtlas, UNIT_ATLAS);
        glUniform1i(uSurface, UNIT_SURFACE);
        glUniform1i(uShadowMap, UNIT_SHADOW);
        glUniform1i(uLampShadowMap, UNIT_LAMP_SHADOW);
        // No surface atlas, and an atlas that means what it says: the voxel
        // world's, whose tiles are colours and whose shading was baked into its
        // vertices by SectionMesher. Every specular term in the shader is
        // multiplied by an f0 this leaves black. See setSurface.
        glUniform1f(uPbr, 0f);
        glUniform1f(uAtlasGain, 1f);
        glUniform2f(uAtlasTexels, 1f, 1f);
        // Neutral until somebody says otherwise. A GLSL uniform starts at zero,
        // so a caller that does not light its world — GlTerrainPass, whose
        // shading is baked in by SectionMesher — would otherwise draw it black.
        //
        // Every term below is the identity of its own operation, not merely a
        // small value: the two ambient tints are *equal*, so their mix is
        // exactly one and multiplies nothing away; the sun is black; the grade
        // is off rather than gentle. A caller that never mentions the sky gets
        // the frame this shader drew before there was one, to the bit.
        glUniform3f(uDaylight, 1f, 1f, 1f);
        glUniform1i(uLightCount, 0);
        glUniform1i(uAirCount, 0);
        glUniform3f(uSunDir, 0f, 0f, 1f);
        glUniform3f(uSunColour, 0f, 0f, 0f);
        glUniform3f(uSkyTint, 1f, 1f, 1f);
        glUniform3f(uGroundTint, 1f, 1f, 1f);
        glUniform3f(uUpAxis, 0f, 0f, 1f);
        glUniform1f(uShadow, 0f);
        glUniform2f(uShadowTexel, 0f, 0f);
        glUniform2f(uShadowBias, 0f, 0f);
        glUniform1f(uLampShadow, 0f);
        uploadedLampShadow = 0f;
        // Not zeros: this curve is divided by, and the near plane it clamps to
        // is what keeps that divide away from one. A caller that never mentions
        // a lamp never reaches it, but a uniform that would produce an infinity
        // if it were reached is not a resting state.
        glUniform4f(uLampDepth, 1f, -1f, 0f, 1f);
        glUniform1f(uLampSlope, 0f);
        glUniformMatrix3fv(uEyeToWorld, false, IDENTITY_3);
        glUniform1f(uHaze, 0f);
        glUniform2f(uHazeBand, 0f, 1f);
        glUniform1f(uEyeZ, 0f);
        glUniform1f(uScatter, 0f);
        glUniform1f(uVibrance, 0f);
        glUniform1f(uTime, 0f);
        glUniformMatrix4fv(uLightMvp, false, identity);
        glUniform3f(uMeshOrigin, 0f, 0f, 0f);
    }

    /**
     * The hour and the lamps, for the frame about to be drawn.
     *
     * <p>Light positions arrive in <b>world</b> coordinates and are put into eye
     * space here, in {@code double}, by the same camera transform every vertex
     * goes through: {@code EyeCamera.toEye} is a rewriting of the view matrix's
     * rows and the two cannot drift. That is why the shader can subtract a
     * light from {@code vEye} directly — both are in the camera's frame, both
     * are small numbers near the origin, and the world's absolute coordinates
     * (which are tens of thousands of metres and do not fit in a float at
     * centimetre precision) never reach the card at all.
     */
    void setLighting(List<MeshPass.Light> lights, EyeCamera eye,
                     float dayR, float dayG, float dayB) {
        glUniform3f(uDaylight, dayR, dayG, dayB);
        frameLightCount = lights == null || eye == null
                ? 0 : Math.min(lights.size(), MAX_LIGHTS);
        for (int i = 0; i < frameLightCount; i++) {
            MeshPass.Light light = lights.get(i);
            eye.toEye(light.x(), light.y(), light.z(), inEyeSpace);
            frameLightPos[i * 4] = (float) inEyeSpace[0];
            frameLightPos[i * 4 + 1] = (float) inEyeSpace[1];
            frameLightPos[i * 4 + 2] = (float) inEyeSpace[2];
            // The reciprocal, not the radius: see the note in the shader. A
            // divide done once here is sixteen divides per fragment saved
            // there.
            frameLightPos[i * 4 + 3] = 1f / Math.max(light.radius(), 1e-4f);
            frameLightColour[i * 4] = light.r();
            frameLightColour[i * 4 + 1] = light.g();
            frameLightColour[i * 4 + 2] = light.b();
            frameLightColour[i * 4 + 3] = light.intensity();
        }
        // Nothing lit until a mesh asks for something: which lamps reach which
        // mesh is now decided per mesh, by setMeshLights.
        uploadedSubset = Long.MIN_VALUE;
        glUniform1i(uLightCount, 0);
        glUniform1i(uAirCount, 0);
    }

    /**
     * The lamps that matter to the mesh about to be drawn.
     *
     * <p><b>Per mesh rather than per frame, which is the whole of what makes a
     * camp affordable.</b> A fragment shader walking a list walks all of it,
     * and a lamp reaches twelve metres out of a view two hundred and sixty
     * deep; sending every frame's lamps to every mesh meant the far hillside
     * paying for the campfire behind you — a little over two milliseconds per
     * lamp per frame at 720p, measured, whatever the lamp was doing. See
     * {@link com.larsons.engine.graphics.LightCull} for how the two lists are
     * chosen.
     *
     * @param order        indices into the frame's lamps, surface-lit first
     * @param surfaceCount how many of them light this mesh's own surface
     * @param airCount     how many entries of {@code order} are used at all
     * @param subset       a value identifying this exact selection, so that a
     *                     run of meshes wanting the same lamps — which is most
     *                     of a frame, and usually means none — costs one upload
     *                     between them rather than one each
     */
    void setMeshLights(int[] order, int surfaceCount, int airCount, long subset) {
        if (subset == uploadedSubset) return;
        uploadedSubset = subset;
        glUniform1i(uLightCount, surfaceCount);
        glUniform1i(uAirCount, airCount);
        if (airCount == 0) return;
        for (int slot = 0; slot < airCount; slot++) {
            System.arraycopy(frameLightPos, order[slot] * 4, lightPos, slot * 4, 4);
            System.arraycopy(frameLightColour, order[slot] * 4, lightColour, slot * 4, 4);
        }
        // The whole array goes up rather than the used prefix: sixty-four
        // floats is nothing to upload, and the entries past uAirCount are never
        // read because both of the shader's loops are bounded by it.
        glUniform4fv(uLightPos, lightPos);
        glUniform4fv(uLightColour, lightColour);
    }

    /**
     * The sun, the air and the grade.
     *
     * <p>The sun's direction and the world's vertical are <b>directions</b> and
     * are rotated into eye space rather than transformed: subtracting the
     * camera from a unit vector would turn "which way is the sun" into "a point
     * forty kilometres north-east", and the whole world would be lit from
     * whichever corner of it the player was standing in. See
     * {@link EyeCamera#toEyeDirection}.
     *
     * @param shadowTexel one texel of the shadow map in UV, or {@code 0} where
     *                    there is no map — which also switches the lookup off
     */
    void setSky(MeshPass.Sky sky, EyeCamera eye, float shadowTexel,
                float flatBias, float slopeBias) {
        if (sky == null) sky = MeshPass.Sky.PLAIN;
        if (eye != null) {
            eye.toEyeDirection(sky.sunX(), sky.sunY(), sky.sunZ(), inEyeSpace);
            normalise(inEyeSpace);
            glUniform3f(uSunDir, (float) inEyeSpace[0], (float) inEyeSpace[1],
                    (float) inEyeSpace[2]);
            eye.toEyeDirection(0, 0, 1, inEyeSpace);
            glUniform3f(uUpAxis, (float) inEyeSpace[0], (float) inEyeSpace[1],
                    (float) inEyeSpace[2]);
            glUniform1f(uEyeZ, (float) eye.z());
        }
        glUniform3f(uSunColour, sky.sunR(), sky.sunG(), sky.sunB());
        glUniform3f(uSkyTint, sky.skyR(), sky.skyG(), sky.skyB());
        glUniform3f(uGroundTint, sky.groundR(), sky.groundG(), sky.groundB());
        glUniform1f(uShadow, shadowTexel > 0 ? sky.shadow() : 0f);
        glUniform2f(uShadowTexel, shadowTexel, shadowTexel);
        glUniform2f(uShadowBias, flatBias, slopeBias);
        glUniform1f(uHaze, sky.haze());
        glUniform2f(uHazeBand, (float) sky.hazeFloor(), sky.hazeDepth());
        glUniform1f(uScatter, sky.scatter());
        glUniform1f(uVibrance, sky.vibrance());
        glUniform1f(uTime, sky.seconds());
    }

    /**
     * The lamp cube map's own arithmetic, once for the frame.
     *
     * <p>Two things the shader cannot work out for itself. The <b>depth
     * curve</b> comes from {@link GlLampShadow}, which owns the projection that
     * wrote it — deriving it twice is how a shadow acquires a bias that grows
     * with distance and nothing says so. And a <b>rotation from eye space back
     * into world axes</b>, because a cube map is addressed by a world
     * direction while every other thing this shader holds is in the camera's
     * frame.
     *
     * <p>That rotation is the transpose of the view's, which is the same thing
     * as its inverse for an orthonormal basis — and a transpose of a rotation
     * is exactly "put its rows in as columns", which is what the loop does.
     * Doing it here rather than sending three more uniforms means there is one
     * place where the handedness can be wrong, and it is checked in
     * {@code GlLightingTest}.
     */
    void setLampShadow(EyeCamera eye, float[] curve, float slopeBias) {
        if (eye == null || curve == null) return;
        Mat4 rotation = Mat4.viewRotation(eye);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                // Row `row` of the view rotation becomes column `row` of this
                // one, and a mat3 uniform is uploaded column by column.
                eyeToWorld[row * 3 + col] = (float) rotation.at(row, col);
            }
        }
        glUniformMatrix3fv(uEyeToWorld, false, eyeToWorld);
        glUniform4f(uLampDepth, curve[0], curve[1], curve[2], curve[3]);
        glUniform1f(uLampSlope, slopeBias);
    }

    /**
     * Whether the mesh about to be drawn is shadowed by the lamp in slot 0, and
     * how strongly.
     *
     * <p>Per mesh, because most meshes in a frame are nowhere near the fire and
     * the only ones that can be shadowed by it are the ones it lights — which
     * is the surface list {@link #setMeshLights} has just been given. Skipped
     * when it has not changed, for that method's reason: a run of meshes with
     * the same answer, which is nearly all of them, costs one upload.
     */
    void setMeshLampShadow(float strength) {
        if (strength == uploadedLampShadow) return;
        uploadedLampShadow = strength;
        glUniform1f(uLampShadow, strength);
    }

    /**
     * Switch the material half of the shader on, for a caller that has a
     * surface atlas to sample.
     *
     * <p>Three things at once, and they are one decision rather than three:
     * the specular lobes light up (they are multiplied by an {@code f0} that is
     * black until the surface atlas says otherwise), the colour atlas starts
     * being read as a detail map (see {@link MeshPass#DETAIL_GAIN}), and the
     * atlas's size goes up so a fragment can tell how much of the normal map it
     * is standing on. All three are properties of the same bake, which is why
     * one call carries them.
     *
     * <p>{@link #use} turns all of it off again, which is what lets
     * {@link GlTerrainPass} share this program and be unaffected to the bit.
     *
     * @param width  the atlas's width in texels, or {@code 0} to switch the
     *               whole block off
     * @param height …and its height
     */
    void setSurface(int width, int height) {
        boolean on = width > 0 && height > 0;
        glUniform1f(uPbr, on ? 1f : 0f);
        glUniform1f(uAtlasGain, on ? MeshPass.DETAIL_GAIN : 1f);
        glUniform2f(uAtlasTexels, on ? width : 1, on ? height : 1);
    }

    void setMatrices(float[] mvp, float[] modelView) {
        glUniformMatrix4fv(uMvp, false, mvp);
        glUniformMatrix4fv(uModelView, false, modelView);
    }

    /**
     * Where this mesh is, for the two things that need a world position: the
     * height the mist is measured against, and the drift.
     *
     * <p>Wrapped to {@link #DRIFT_PERIOD} in x and y by the caller, in
     * {@code double}. Not in z, which is a height and small.
     */
    void setMeshOrigin(double wrappedX, double wrappedY, double z) {
        glUniform3f(uMeshOrigin, (float) wrappedX, (float) wrappedY, (float) z);
    }

    /** The matrix this mesh's vertices reach the sun's depth map through. */
    void setLightMatrix(float[] lightMvp) {
        glUniformMatrix4fv(uLightMvp, false, lightMvp == null ? identity : lightMvp);
    }

    void setFog(int argb, double start, double end, boolean on) {
        glUniform4f(uFog, ((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
                (argb & 0xFF) / 255f, on ? 1f : 0f);
        glUniform2f(uFogRange, (float) start, (float) end);
    }

    void setAlphaCut(float cut) {
        glUniform1f(uAlphaCut, cut);
    }

    /**
     * {@code n} whole cycles per {@link #DRIFT_PERIOD} metres, as a GLSL
     * literal — the only frequencies anything in world space may use here.
     *
     * <p>A world coordinate reaches this shader folded into that period,
     * because {@code float} does not hold tens of thousands of metres at the
     * precision a fragment needs. Anything read off it therefore has to come
     * back to itself at the fold, and "a whole number of cycles" is the whole
     * of that condition.
     */
    private static String cycles(int n) {
        return String.format(Locale.ROOT, "%.8f", n * 2 * Math.PI / DRIFT_PERIOD);
    }

    private static void normalise(double[] v) {
        double length = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (length < 1e-9) {
            v[0] = 0;
            v[1] = 0;
            v[2] = 1;
            return;
        }
        v[0] /= length;
        v[1] /= length;
        v[2] /= length;
    }

    private static float[] identityMatrix() {
        float[] m = new float[16];
        m[0] = m[5] = m[10] = m[15] = 1;
        return m;
    }

    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("terrain shader did not compile: " + log);
        }
        return shader;
    }

    private static int link(int vertex, int fragment) {
        int program = glCreateProgram();
        glAttachShader(program, vertex);
        glAttachShader(program, fragment);
        glBindAttribLocation(program, ATTRIB_POSITION, "aPos");
        glBindAttribLocation(program, ATTRIB_UV, "aUV");
        glBindAttribLocation(program, ATTRIB_COLOR, "aColor");
        glLinkProgram(program);
        int status = glGetProgrami(program, GL_LINK_STATUS);
        glDetachShader(program, vertex);
        glDetachShader(program, fragment);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        if (status == GL_FALSE) {
            String log = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new IllegalStateException("terrain shader did not link: " + log);
        }
        return program;
    }

    @Override
    public void close() {
        glDeleteProgram(program);
    }
}
