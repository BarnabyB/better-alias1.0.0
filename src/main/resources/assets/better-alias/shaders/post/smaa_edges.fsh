#version 330

// SMAA 1x - pass 1: color edge detection.
// Ported from SMAA-MC (https://github.com/Luracasmus/SMAA-MC), MIT licensed:
// Copyright (C) 2013 Jorge Jimenez, Jose I. Echevarria, Belen Masia, Fernando Navarro, Diego Gutierrez
// Copyright (C) 2024-2025 Luracasmus
// Modified for Better Alias (2026). Full licence texts: THIRD_PARTY_NOTICES.md.
//
// Everything works in texel space (gl_FragCoord / texelFetch), so the result is the same
// regardless of which way up the backend stores the framebuffer.

// Minimum color difference for an edge. Lower = more edges detected.
#define SMAA_THRESHOLD 0.1

uniform sampler2D ColorSampler;

out vec4 fragColor;

// https://en.wikipedia.org/wiki/Color_difference ("redmean")
float redmean(vec3 a, vec3 b) {
    float r = step(0.5, mix(a.r, b.r, 0.5));
    vec3 d = a - b;
    return sqrt(dot(d * d, vec3(2.0 + r, 4.0, 3.0 - r)));
}

vec3 fetchColor(ivec2 texel) {
    // Clamp so edge pixels read their own border instead of undefined out-of-bounds data
    return texelFetch(ColorSampler, clamp(texel, ivec2(0), textureSize(ColorSampler, 0) - 1), 0).rgb;
}

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);

    vec3 color = fetchColor(texel);
    vec3 left = fetchColor(texel + ivec2(-1, 0));
    vec3 top = fetchColor(texel + ivec2(0, -1));

    vec4 delta;
    delta.xy = vec2(redmean(color, left), redmean(color, top));

    bvec2 edges = greaterThanEqual(delta.xy, vec2(SMAA_THRESHOLD));

    // Post passes don't clear their output, so every pixel must be written
    vec2 result = vec2(0.0);

    if (any(edges)) {
        // Local contrast adaptation
        delta.zw = vec2(
                redmean(color, fetchColor(texel + ivec2(1, 0))), // right
                redmean(color, fetchColor(texel + ivec2(0, 1)))  // bottom
        );
        vec2 deltaMax = max(delta.xy, delta.zw);

        delta.zw = vec2(
                redmean(left, fetchColor(texel + ivec2(-2, 0))), // left-left
                redmean(top, fetchColor(texel + ivec2(0, -2)))   // top-top
        );
        deltaMax = max(deltaMax, delta.zw);

        const float localContrastAdaptationFactor = 2.0;
        bvec2 contrast = greaterThanEqual(delta.xy, vec2(max(deltaMax.x, deltaMax.y) / localContrastAdaptationFactor));
        result = vec2(edges.x && contrast.x, edges.y && contrast.y);
    }

    fragColor = vec4(result, 0.0, 1.0);
}
