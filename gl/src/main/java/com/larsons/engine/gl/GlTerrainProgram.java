package com.larsons.engine.gl;

import com.larsons.engine.graphics.EyeCamera;
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
 *   <li><b>One knob of grade</b> at the end — saturation, and a knee that
 *       rolls the highlights off so the middle of a lantern's pool keeps its
 *       colour instead of clipping to white.</li>
 * </ul>
 *
 * <h2>Neutral unless told otherwise</h2>
 *
 * <p>A GLSL uniform starts at zero, so a daylight multiplier nobody set would
 * draw a black world; {@link #use} resets <em>every</em> term above to the
 * identity of its own operation each time the program is bound — full daylight,
 * no sun, no shadow, no weather, no grade. That is what lets
 * {@link GlTerrainPass} — which knows nothing about any of this and has its own
 * lighting baked in by {@code SectionMesher} — share the program and be
 * bit-for-bit unaffected by all of it.
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
            // A *shadow* sampler: the texture unit does the depth comparison
            // and the filtering, and hands back how much of this fragment is
            // lit. See GlShadowMap's texture setup.
            uniform sampler2DShadow uShadowMap;
            uniform vec4 uFog;        // rgb = colour, a = 1 when fog is on
            uniform vec2 uFogRange;   // x = start, y = end
            uniform float uAlphaCut;  // discard below this; 0 in the blended pass

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

            uniform float uHaze;      // weather extinction, per metre
            uniform vec2 uHazeBand;   // x = the height mist pools at, y = its depth
            uniform float uEyeZ;      // the camera's own height, for the integral
            uniform float uScatter;   // how much of a lamp the air carries back
            uniform float uVibrance;  // one knob of grade; 0 is "leave it alone"
            uniform float uTime;      // the drawing clock, for the drift

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

            void main() {
                // The face's own normal, out of the screen-space gradient of the
                // eye-space position. This world is flat shaded, so the plane a
                // fragment lies in *is* its triangle's plane and this is exact
                // rather than an approximation — and it costs no vertex
                // attribute, no re-mesh and no upload.
                //
                // Taken before the discard on purpose: a derivative is computed
                // across a two-by-two block of fragments, and asking for one
                // after half that block has thrown itself away is asking a
                // question the driver is not obliged to answer.
                //
                // Turned toward the eye afterwards, because grass, leaves and
                // water are single-sided sheets meant to be seen from either
                // face: a lantern behind a blade of grass has to light the side
                // you are looking at.
                vec3 n = normalize(cross(dFdx(vEye), dFdy(vEye)));
                if (dot(n, vEye) > 0.0) n = -n;

                // Every block has an atlas cell — a sheetless one shares a
                // white cell — so this is unconditional and a section of mixed
                // blocks is still one draw call. See BlockAtlas.
                vec4 colour = texture(uAtlas, vUV) * vColor;
                if (colour.a < uAlphaCut) discard;

                // One reciprocal square root for all three of these. A divide
                // is several times the cost of a multiply on most cards and
                // this loop is about to do a great many of them.
                float eye2 = max(dot(vEye, vEye), 1e-8);
                float invView = inversesqrt(eye2);
                float viewLen = eye2 * invView;
                vec3 ray = vEye * invView;
                // How much air the view ray came through, once, for both the
                // fog and the lamps that are shining into it.
                float mass = airMass(viewLen);

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

                // The sun, and whatever is standing between it and here.
                float ndl = dot(n, uSunDir);
                if (ndl > 0.0) {
                    light += uSunColour * (ndl * (1.0 - uShadow * shadowed(ndl)));
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
                    float ndotl = max(0.0, dot(n, to * invD));
                    light += uLightColour[i].rgb * (uLightColour[i].a * fall
                            * (WRAP + (1.0 - WRAP) * ndotl));
                    air += scattered(i, ray, viewLen, invView, mass);
                }
                for (int i = uLightCount; i < uAirCount; i++) {
                    air += scattered(i, ray, viewLen, invView, mass);
                }
                colour.rgb *= light;

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
                    colour.rgb = max(vec3(0.0),
                            mix(vec3(grey), colour.rgb, 1.0 + 0.55 * uVibrance));
                    // A knee rather than a clamp: everything above it is
                    // compressed toward one instead of stopping dead there, so
                    // the middle of a lantern's pool keeps the lantern's colour
                    // rather than going white.
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
            .replace("CHORD", String.format(Locale.ROOT, "%.3f", CHORD_MEAN));

    private final int program;
    private final int uMvp;
    private final int uModelView;
    private final int uLightMvp;
    private final int uMeshOrigin;
    private final int uAtlas;
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

    GlTerrainProgram() {
        program = link(compile(GL_VERTEX_SHADER, VERTEX), compile(GL_FRAGMENT_SHADER, FRAGMENT));
        uMvp = glGetUniformLocation(program, "uMvp");
        uModelView = glGetUniformLocation(program, "uModelView");
        uLightMvp = glGetUniformLocation(program, "uLightMvp");
        uMeshOrigin = glGetUniformLocation(program, "uMeshOrigin");
        uAtlas = glGetUniformLocation(program, "uAtlas");
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

    void use() {
        glUseProgram(program);
        glUniform1i(uAtlas, UNIT_ATLAS);
        glUniform1i(uShadowMap, UNIT_SHADOW);
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
