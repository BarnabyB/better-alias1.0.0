#version 330

// AMD FidelityFX RCAS (Robust Contrast Adaptive Sharpening), the sharpening pass of FSR 1 (FsrRcasF, 32-bit path,
// with FSR_RCAS_PASSTHROUGH_ALPHA; FSR_RCAS_DENOISE, which is off by default, is left out). Runs on the final image
// after anti-aliasing; strength from the RCAS slider.
//
// Ported from AMD FidelityFX FSR 1 (ffx_fsr1.h, https://github.com/GPUOpen-Effects/FidelityFX-FSR), MIT licence:
// Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved.
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
// documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
// rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
// permit persons to whom the Software is furnished to do so, subject to the following conditions: The above copyright
// notice and this permission notice shall be included in all copies or substantial portions of the Software.
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
// WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
// Modified for Better Alias (2026). Full licence texts: THIRD_PARTY_NOTICES.md.
//
// RCAS sharpens each pixel against its 4 neighbours with a negative lobe, and picks the strongest lobe that can't push
// any colour channel past what its neighbours allow (0..1), so it never clips or rings. Unlike CAS it has no fixed
// kernel shape to tune, which makes it well behaved at any strength.

uniform sampler2D InSampler; // the finished frame (sRGB-encoded, like the main target), nearest

layout(std140) uniform RcasConfig {
    // x: sharpness scale, FsrRcasCon's con.x = exp2(-stops): 1.0 = maximum, 0.5 = one stop less, ...
    vec4 RcasParams;
};

out vec4 fragColor;

#define FSR_RCAS_LIMIT (0.25 - (1.0 / 16.0))

vec4 load(ivec2 texel, ivec2 size) {
    return texelFetch(InSampler, clamp(texel, ivec2(0), size - 1), 0);
}

float min3(float a, float b, float c) {
    return min(a, min(b, c));
}

float max3(float a, float b, float c) {
    return max(a, max(b, c));
}

vec3 min3(vec3 a, vec3 b, vec3 c) {
    return min(a, min(b, c));
}

vec3 max3(vec3 a, vec3 b, vec3 c) {
    return max(a, max(b, c));
}

void main() {
    ivec2 size = textureSize(InSampler, 0);
    ivec2 sp = ivec2(gl_FragCoord.xy);

    // Minimal 3x3 neighbourhood:
    //    b
    //  d e f
    //    h
    vec3 b = load(sp + ivec2(0, -1), size).rgb;
    vec3 d = load(sp + ivec2(-1, 0), size).rgb;
    vec4 ee = load(sp, size);
    vec3 e = ee.rgb;
    vec3 f = load(sp + ivec2(1, 0), size).rgb;
    vec3 h = load(sp + ivec2(0, 1), size).rgb;

    // Min and max of the ring
    vec3 mn4 = min(min3(b, d, f), h);
    vec3 mx4 = max(max3(b, d, f), h);

    // Immediate constants for the peak range
    const vec2 peakC = vec2(1.0, -1.0 * 4.0);

    // Limiters: the largest negative lobe that keeps every channel inside 0..1. The reference relies on IEEE
    // infinities for an all-black or all-white ring; the tiny clamps keep that case well defined on every GPU.
    vec3 hitMin = min(mn4, e) / max(4.0 * mx4, vec3(1e-6));
    vec3 hitMax = (peakC.x - max(mx4, e)) / min(4.0 * mn4 + peakC.y, vec3(-1e-6));
    vec3 lobeRGB = max(-hitMin, hitMax);
    float lobe = max(-FSR_RCAS_LIMIT, min(max3(lobeRGB.r, lobeRGB.g, lobeRGB.b), 0.0)) * RcasParams.x;

    // Resolve
    float rcpL = 1.0 / (4.0 * lobe + 1.0);
    vec3 pix = (lobe * b + lobe * d + lobe * h + lobe * f + e) * rcpL;

    fragColor = vec4(clamp(pix, 0.0, 1.0), ee.a);
}
