#version 330

// CMAA2 - pass 1: edge detection (port of EdgesColor2x2CS).
//
// Ported from Intel's Conservative Morphological Anti-Aliasing 2.0 reference implementation
// (https://github.com/GameTechDev/CMAA2, CMAA2.hlsl), Apache License 2.0:
// Copyright (c) 2018, Intel Corporation
// Licensed under the Apache License, Version 2.0 (licenses/Apache-2.0.txt, http://www.apache.org/licenses/LICENSE-2.0),
// distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
// Modified for Better Alias (2026): rewritten from HLSL compute shaders into GLSL fragment passes, as described
// below. Notices: THIRD_PARTY_NOTICES.md.
//
// The reference is a set of compute shaders. Minecraft's post chains only run fragment passes, so this port keeps
// CMAA2's maths but reorganises it per pixel: each pixel recomputes its own four edges from its 3x3 neighbourhood
// instead of sharing a 2x2 block's results through group-shared memory; the edges come out the same.
//
// Output (RGBA8, each channel exactly 0 or 1), using CMAA2's edge layout:
//   r = right edge, g = bottom edge, b = left edge, a = top edge
// "Bottom" means texel y + 1, as in the HLSL reference. The algorithm is symmetric, so which way up the backend
// stores the image doesn't matter.

// The edge threshold is the only thing CMAA2's quality presets change (CMAA2_STATIC_QUALITY_PRESET):
// LOW 0.15, MEDIUM 0.10, HIGH 0.07 (reference default), ULTRA 0.05. Each preset has its own post_effect JSON
// (cmaa2_low / cmaa2_medium / cmaa2 (high) / cmaa2_ultra) that passes the value in here.
layout(std140) uniform Cmaa2Config {
    float EdgeThreshold;
};
// g_CMAA2_LocalContrastAdaptationAmount (0.10 default; 0.15 with CMAA2_EXTRA_SHARPNESS)
#define CMAA2_LOCAL_CONTRAST_ADAPTATION 0.10

uniform sampler2D ColorSampler;

out vec4 fragColor;

vec3 toLinear(vec3 srgb) {
    return mix(pow((srgb + 0.055) / 1.055, vec3(2.4)), srgb / 12.92, lessThanEqual(srgb, vec3(0.04045)));
}

// RGBToLumaForEdges: the reference reads linear colour and uses sqrt() as a cheap gamma curve.
// The main target holds sRGB-encoded values, so decode first to match.
float lumaAt(ivec2 texel) {
    vec3 color = texelFetch(ColorSampler, clamp(texel, ivec2(0), textureSize(ColorSampler, 0) - 1), 0).rgb;
    return dot(sqrt(toLinear(color)), vec3(0.299, 0.587, 0.114));
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);

    // 3x3 luma neighbourhood: l<x><y>, M = -1, 0 = 0, P = +1
    float lMM = lumaAt(p + ivec2(-1, -1));
    float l0M = lumaAt(p + ivec2( 0, -1));
    float lPM = lumaAt(p + ivec2( 1, -1));
    float lM0 = lumaAt(p + ivec2(-1,  0));
    float l00 = lumaAt(p);
    float lP0 = lumaAt(p + ivec2( 1,  0));
    float lMP = lumaAt(p + ivec2(-1,  1));
    float l0P = lumaAt(p + ivec2( 0,  1));
    float lPP = lumaAt(p + ivec2( 1,  1));

    // ComputeEdgeLuma: h<x><y> = |L(x,y) - L(x+1,y)| (a pixel's right edge), v<x><y> = |L(x,y) - L(x,y+1)| (bottom edge)
    float hMM = abs(lMM - l0M), h0M = abs(l0M - lPM);
    float hM0 = abs(lM0 - l00), h00 = abs(l00 - lP0);
    float hMP = abs(lMP - l0P), h0P = abs(l0P - lPP);
    float vMM = abs(lMM - lM0), v0M = abs(l0M - l00), vPM = abs(lPM - lP0);
    float vM0 = abs(lM0 - lMP), v00 = abs(l00 - l0P), vP0 = abs(lP0 - lPP);

    // Local contrast adaptation (ComputeLocalContrastV / ComputeLocalContrastH): an edge only counts if it still clears
    // the threshold after subtracting a fraction of the strongest perpendicular edge touching either of its ends.
    // This is what makes CMAA2 "conservative": weak edges next to strong ones (text, fine texture detail) are left alone.
    const float lca = CMAA2_LOCAL_CONTRAST_ADAPTATION;
    // This pixel's right edge and its left neighbour's right edge (= our left edge) are vertical: compare with horizontal edges.
    float right  = h00 - lca * max(max(v0M, v00), max(vPM, vP0));
    float left   = hM0 - lca * max(max(vMM, vM0), max(v0M, v00));
    // This pixel's bottom edge and its top neighbour's bottom edge (= our top edge) are horizontal: compare with vertical edges.
    float bottom = v00 - lca * max(max(hM0, h00), max(hMP, h0P));
    float top    = v0M - lca * max(max(hMM, h0M), max(hM0, h00));

    // Pixels on the screen border see a zero difference against the clamped texel, so they never get an outward edge.
    fragColor = vec4(greaterThan(vec4(right, bottom, left, top), vec4(EdgeThreshold)));
}
