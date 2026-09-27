#version 330

// AMD FidelityFX Contrast Adaptive Sharpening (CAS), non-scaling path, with CAS_BETTER_DIAGONALS.
// Used after the TAA resolve to restore the detail that temporal accumulation softens.
//
// Ported from AMD FidelityFX CAS (ffx_cas.h, https://github.com/GPUOpen-Effects/FidelityFX-CAS), MIT licence:
// Copyright (c) 2019-2020 Advanced Micro Devices, Inc. All rights reserved.
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
// documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
// rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
// permit persons to whom the Software is furnished to do so, subject to the following conditions: The above copyright
// notice and this permission notice shall be included in all copies or substantial portions of the Software.
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
// WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
// Modified for Better Alias (2026). Full licence texts: THIRD_PARTY_NOTICES.md.
//
// CAS sharpens each pixel against its 4 neighbours by an amount that shrinks where local contrast is already high,
// so it restores soft detail without the halos and over-sharpened edges of a plain unsharp mask.

uniform sampler2D InSampler; // TAA output: linear colour (RGBA16F)

layout(std140) uniform CasConfig {
    // x: peak = -1 / lerp(8, 5, sharpness)   (CasSetup's const1.x); sharpness 0..1 is set from the settings
    vec4 CasParams;
};

out vec4 fragColor;

vec3 toSrgb(vec3 linear) {
    linear = clamp(linear, 0.0, 1.0);
    return mix(1.055 * pow(linear, vec3(1.0 / 2.4)) - 0.055, 12.92 * linear, lessThanEqual(linear, vec3(0.0031308)));
}

// CAS runs on sRGB-encoded (perceptual) values, and its output goes straight back into the main target.
vec3 load(ivec2 texel, ivec2 size) {
    return toSrgb(texelFetch(InSampler, clamp(texel, ivec2(0), size - 1), 0).rgb);
}

void main() {
    ivec2 size = textureSize(InSampler, 0);
    ivec2 p = ivec2(gl_FragCoord.xy);

    //  a b c
    //  d e f
    //  g h i
    vec3 a = load(p + ivec2(-1, -1), size);
    vec3 b = load(p + ivec2( 0, -1), size);
    vec3 c = load(p + ivec2( 1, -1), size);
    vec3 d = load(p + ivec2(-1,  0), size);
    vec3 e = load(p, size);
    vec3 f = load(p + ivec2( 1,  0), size);
    vec3 g = load(p + ivec2(-1,  1), size);
    vec3 h = load(p + ivec2( 0,  1), size);
    vec3 i = load(p + ivec2( 1,  1), size);

    // Soft min and max (cross + full 3x3); these are 2.0x bigger (the extra multiply is factored out).
    vec3 mn = min(min(min(d, e), min(f, b)), h);
    vec3 mn2 = min(min(min(mn, a), min(c, g)), i);
    mn = mn + mn2;
    vec3 mx = max(max(max(d, e), max(f, b)), h);
    vec3 mx2 = max(max(max(mx, a), max(c, g)), i);
    mx = mx + mx2;

    // Smooth minimum distance to signal limit divided by smooth max.
    vec3 amp = clamp(min(mn, 2.0 - mx) / max(mx, 1e-5), 0.0, 1.0);
    // Shaping amount of sharpening.
    amp = sqrt(amp);

    // Filter shape:
    //  0 w 0
    //  w 1 w
    //  0 w 0
    // The default (non CAS_SLOW) path uses the green channel's weight for all channels, which avoids colour fringing.
    float w = amp.g * CasParams.x;
    float rcpWeight = 1.0 / (1.0 + 4.0 * w);
    vec3 result = clamp((b * w + d * w + f * w + h * w + e) * rcpWeight, 0.0, 1.0);

    fragColor = vec4(result, 1.0);
}
