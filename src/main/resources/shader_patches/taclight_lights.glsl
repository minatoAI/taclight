// TACLIGHT_PATCH_BEGIN iterationT-3.2.0 v3-p1
// TacLight spotlight SSBO contract (single source: SpotlightBufferLayout.java)
// Scene-relative coordinates: world minus cameraPosition.
struct TacLightSpot {
    vec4 posRadius;       // xyz = scene-relative position; w = radius (blocks)
    vec4 colorIntensity;  // rgb = linear color; a = intensity
    vec4 dirType;         // xyz = normalized direction (light->target); w = 1.0 spot
    vec4 cone;            // x = cos(outer half-angle); y = cos(inner half-angle)
    vec4 vlParams;        // x anisotropy, y density, z beam strength
    vec4 cookie;          // reserved
};

layout(std430, binding = 7) buffer TacLightBuffer {
    uint lightCount;
    float vlIntensity;
    uint flags;
    uint reserved;
    TacLightSpot lights[];
};

#define TACLIGHT_EPS 1e-4
// Header flag bits (single source: SpotlightBufferLayout.java)
#define TACLIGHT_FLAG_HAS_DATA 1u
#define TACLIGHT_FLAG_DEBUG 2u
// V5 diagnostics: one-pixel timing probe (Java reads back, see LightBuffer.readReserved)
#define TACLIGHT_FLAG_TIMING_PROBE 8u
void taclight_probe_pass(uint pass_bit) {
    atomicOr(reserved, pass_bit); // atomics survive DCE; unguarded run
}

// ---- v0.8.2: filmic-style soft knee for injected radiance ----
// Removes the "flat clamped white" look: e=5 -> 0.93, e=1.5 -> 0.79, e=0.17 -> 0.30.
float taclight_knee(float e) {
    return e / (1.0 + e);
}
#define TACLIGHT_KNEE_GAIN 2.5

// ---- V3-p1: surface spotlight (view-space, aligned with HeldLighting conventions) ----
vec3 taclight_surface(vec3 viewPos, vec3 viewDir, vec3 normal, vec3 albedo,
                      float roughness, vec3 ao, float handMask) {
    vec3 result = vec3(0.0);
    taclight_probe_pass(1u);
    if (lightCount == 0u || handMask > 0.5) return result;
    taclight_probe_pass(2u); // gate passed: lights>0 and world geometry
    bool debug = (flags & TACLIGHT_FLAG_DEBUG) != 0u;
    for (uint i = 0u; i < lightCount; i++) {
        TacLightSpot L = lights[i];
        if (L.dirType.w < 0.5) continue;
        // Iris composite convention: scene-relative pos -> view space = mat3(gbufferModelView) ONLY (R-only).
        vec3 lightView = mat3(gbufferModelView) * L.posRadius.xyz;
        vec3 toFrag = viewPos - lightView;
        float dist = length(toFrag);
        float radius = max(L.posRadius.w, 1.0);
        lights[0].cookie = vec4(viewPos, dist); // DIAG: last writer wins
        if (dist > radius) continue;
        taclight_probe_pass(64u); // dist within radius
        vec3 dirToFrag = toFrag / max(dist, TACLIGHT_EPS);
        vec3 dirView = mat3(gbufferModelView) * normalize(L.dirType.xyz);
        float cosAng = dot(dirToFrag, dirView);
        float spot = smoothstep(L.cone.x, L.cone.y, cosAng);
        if (spot <= 0.0) continue;
        taclight_probe_pass(128u); // inside cone
        float atten = pow(max(1.0 - dist / radius, 0.0), 2.0);
        float visibility = TorchScreenSpaceShadow(viewPos, viewDir, normal, -dirToFrag);
        taclight_probe_pass(512u); // shadow term evaluated
        if (debug) {
            taclight_probe_pass(8u); // debug flag observed ON in GLSL
            // Neon diagnostic: pure tinted cone, no albedo/AO - unmistakable "our pass".
            result += vec3(0.12, 1.0, 0.40)
                    * taclight_knee(L.colorIntensity.a * atten * spot * visibility * TACLIGHT_KNEE_GAIN);
            continue;
        }
        float ndl = max(dot(normal, -dirToFrag), 0.0);
        if (ndl <= 0.0) continue;
        float diffuse = Fd_Burley(normal, -viewDir, -dirToFrag, roughness) * 1.7 + 0.05;
        float e = L.colorIntensity.a * atten * spot * visibility * ndl * diffuse * TACLIGHT_KNEE_GAIN;
        result += normalize(L.colorIntensity.rgb) * taclight_knee(e) * albedo;
        taclight_probe_pass(4u); // at least one light contributed radiance
    }
    return result * (ao * 0.5 + 0.5);
}

// ---- V3-p2: bounded camera-depth visibility for beam samples ----
float taclight_beam_visibility(vec3 lightView, vec3 sampleView) {
    const int taclight_beam_steps = 8;
    float sampleDist = -sampleView.z;
    for (int s = 1; s < taclight_beam_steps; s++) {
        float t = float(s) / float(taclight_beam_steps);
        vec3 p = mix(lightView, sampleView, t);
        if (p.z >= -0.05) continue;
        vec4 clip = gbufferProjection * vec4(p, 1.0);
        vec2 uv = clip.xy / max(clip.w, 1e-4) * 0.5 + 0.5;
        if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) continue;
        float depth = textureLod(depthtex1, uv, 0.0).x;
        if (depth <= 0.0 || depth >= 1.0) continue;
        float blockerDist = LinearDepth_From_ScreenDepth(depth);
        if (blockerDist < sampleDist - 0.5) return 0.0;
    }
    return 1.0;
}

// ---- V3-p2: volumetric beam (additive; 16 steps; density/beam=0 is exact identity) ----
vec3 taclight_beam(vec3 worldStart, vec3 worldEnd, float dither) {
    taclight_probe_pass(16u); // beam call site reached
    vec3 result = vec3(0.0);
    if (lightCount == 0u) return result;
    vec3 sceneStart = worldStart - cameraPosition;
    vec3 sceneEnd = worldEnd - cameraPosition;
    float total = length(sceneEnd - sceneStart);
    if (total < 1.0) return result;
    const int taclight_vl_steps = 16;
    float stepLen = total / float(taclight_vl_steps);
    vec3 rayDir = (sceneEnd - sceneStart) / total;
    float t0 = dither * stepLen;
    for (int s = 0; s < taclight_vl_steps; s++) {
        vec3 p = sceneStart + rayDir * (t0 + float(s) * stepLen);
        for (uint i = 0u; i < lightCount; i++) {
            TacLightSpot L = lights[i];
            if (L.dirType.w < 0.5) continue;
            taclight_probe_pass(8192u); // dirType.w read ok (beam loop)
            if (L.vlParams.y <= 0.0 || L.vlParams.z <= 0.0) continue;
            vec3 toP = p - L.posRadius.xyz;
            float d2 = dot(toP, toP);
            float radius = max(L.posRadius.w, 1.0);
            if (d2 > radius * radius) continue;
            float dist = sqrt(d2);
            vec3 dirToP = toP / max(dist, TACLIGHT_EPS);
            float cosAng = dot(dirToP, normalize(L.dirType.xyz));
            float spot = smoothstep(L.cone.x, L.cone.y, cosAng);
            if (spot <= 0.0) continue;
            float atten = pow(max(1.0 - dist / radius, 0.0), 2.0);
            vec3 pView = mat3(gbufferModelView) * p;
            vec3 lightView = mat3(gbufferModelView) * L.posRadius.xyz;
            float visibility = taclight_beam_visibility(lightView, pView);
            result += normalize(L.colorIntensity.rgb)
                    * (L.colorIntensity.a * atten * spot * visibility
                       * L.vlParams.y * L.vlParams.z * stepLen);
        }
    }
    // soft-knee the accumulated fog energy: near = warm glow, far = faint, never clipped flat
    return (result * 1.5) / (1.0 + result * 1.5);
}

// ---- V3-p2: specular highlight (mirrors TorchSpecularHighlight conventions) ----
void taclight_specular(inout vec3 color, vec3 viewPos, vec3 viewDir, vec3 normal,
                       vec3 albedo, float roughness, float f0, float handMask) {
    taclight_probe_pass(32u); // specular call site reached
    if (lightCount == 0u || handMask > 0.5) return;
    for (uint i = 0u; i < lightCount; i++) {
        TacLightSpot L = lights[i];
        if (L.dirType.w < 0.5) continue;
        vec3 lightView = mat3(gbufferModelView) * L.posRadius.xyz;
        vec3 toFrag = viewPos - lightView;
        float dist = length(toFrag);
        float radius = max(L.posRadius.w, 1.0);
        if (dist > radius) continue;
        vec3 dirToFrag = toFrag / max(dist, TACLIGHT_EPS);
        vec3 dirView = mat3(gbufferModelView) * normalize(L.dirType.xyz);
        float cosAng = dot(dirToFrag, dirView);
        float spot = smoothstep(L.cone.x, L.cone.y, cosAng);
        if (spot <= 0.0) continue;
        float atten = pow(max(1.0 - dist / radius, 0.0), 2.0);
        float ndl = max(dot(normal, -dirToFrag), 0.0);
        if (ndl <= 0.0) continue;
        float visibility = TorchScreenSpaceShadow(viewPos, viewDir, normal, -dirToFrag);
        float spec = SpecularGGX(normal, -viewDir, -dirToFrag, roughness, f0);
        float e = L.colorIntensity.a * atten * spot * visibility * ndl * spec * 2.0;
        color += normalize(L.colorIntensity.rgb) * taclight_knee(e) * albedo;
    }
}
// TACLIGHT_PATCH_END iterationT-3.2.0 v3-p1
