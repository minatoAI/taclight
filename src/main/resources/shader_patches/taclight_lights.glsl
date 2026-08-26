// TACLIGHT_PATCH_BEGIN iterationT-3.2.0 v3-p1
// TacLight spotlight SSBO contract (single source: SpotlightBufferLayout.java)
// Scene-relative coordinates: world minus cameraPosition.
struct TacLightSpot {
    vec4 posRadius;       // xyz = scene-relative position; w = radius (blocks)
    vec4 colorIntensity;  // rgb = linear color; a = intensity
    vec4 dirType;         // xyz = normalized direction (light->target); w = 1.0 spot
    vec4 cone;            // x = cos(outer half-angle); y = cos(inner half-angle)
    vec4 vlParams;        // reserved for volumetric stage
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

// Surface spotlight: view-space math aligned with HeldLighting() conventions.
// handMask > 0.5 skips the held viewmodel (matches iterationT's isHand behavior).
vec3 taclight_surface(vec3 viewPos, vec3 viewDir, vec3 normal, vec3 albedo,
                      float roughness, vec3 ao, float handMask) {
    vec3 result = vec3(0.0);
    if (lightCount == 0u || handMask > 0.5) return result;
    for (uint i = 0u; i < lightCount; i++) {
        TacLightSpot L = lights[i];
        if (L.dirType.w < 0.5) continue;
        vec3 lightWorld = L.posRadius.xyz + cameraPosition;
        vec3 lightView = (gbufferModelView * vec4(lightWorld, 1.0)).xyz;
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
        float diffuse = Fd_Burley(normal, -viewDir, -dirToFrag, roughness) * 1.7 + 0.05;
        result += normalize(L.colorIntensity.rgb) * (L.colorIntensity.a * atten * spot * visibility * ndl * diffuse) * albedo;
    }
    return result * (ao * 0.5 + 0.5);
}
// TACLIGHT_PATCH_END iterationT-3.2.0 v3-p1
