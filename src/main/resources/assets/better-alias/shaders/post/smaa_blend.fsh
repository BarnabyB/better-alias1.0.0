#version 330

// SMAA 1x - pass 3: neighborhood blending.
// Ported from SMAA-MC (https://github.com/Luracasmus/SMAA-MC), MIT licensed:
// Copyright (C) 2013 Jorge Jimenez, Jose I. Echevarria, Belen Masia, Fernando Navarro, Diego Gutierrez
// Copyright (C) 2024-2025 Luracasmus
// Modified for Better Alias (2026). Full licence texts: THIRD_PARTY_NOTICES.md.

// Set to 1 to show the blending weights instead of the image
#define DEBUG_WEIGHTS 0

uniform sampler2D ColorSampler;
uniform sampler2D WeightsSampler;

out vec4 fragColor;

vec3 toLinear(vec3 srgb) {
    return mix(pow((srgb + 0.055) / 1.055, vec3(2.4)), srgb / 12.92, lessThanEqual(srgb, vec3(0.04045)));
}

vec3 toSrgb(vec3 linear) {
    return mix(1.055 * pow(linear, vec3(1.0 / 2.4)) - 0.055, 12.92 * linear, lessThanEqual(linear, vec3(0.0031308)));
}

vec3 fetchLinear(ivec2 texel) {
    return toLinear(texelFetch(ColorSampler, clamp(texel, ivec2(0), textureSize(ColorSampler, 0) - 1), 0).rgb);
}

void main() {
    ivec2 size = textureSize(WeightsSampler, 0);
    ivec2 texel = ivec2(gl_FragCoord.xy);
    vec4 center = texelFetch(ColorSampler, texel, 0);

    // x = right neighbor's left weight, y = bottom neighbor's top weight, zw = own right/top weights
    vec4 a = vec4(
            texelFetch(WeightsSampler, min(texel + ivec2(1, 0), size - 1), 0).w,
            texelFetch(WeightsSampler, min(texel + ivec2(0, 1), size - 1), 0).y,
            texelFetch(WeightsSampler, texel, 0).zx
    );

    #if DEBUG_WEIGHTS
    fragColor = vec4(texelFetch(WeightsSampler, texel, 0).xyz, 1.0);
    return;
    #endif

    if (dot(a, vec4(1.0)) < 1.0e-5) {
        // No edge here, pass the pixel through untouched
        fragColor = center;
        return;
    }

    bool horizontal = max(a.x, a.z) > max(a.y, a.w);
    ivec2 dir = horizontal ? ivec2(1, 0) : ivec2(0, 1);
    vec2 offsets = horizontal ? a.xz : a.yw;
    vec2 weights = offsets / dot(offsets, vec2(1.0));

    // Equivalent to SMAA's two bilinear taps (offset along a single axis), but interpolated in
    // linear space since the main target holds sRGB-encoded values.
    vec3 centerLinear = toLinear(center.rgb);
    vec3 color = weights.x * mix(centerLinear, fetchLinear(texel + dir), offsets.x)
    + weights.y * mix(centerLinear, fetchLinear(texel - dir), offsets.y);

    fragColor = vec4(toSrgb(color), center.a);
}
