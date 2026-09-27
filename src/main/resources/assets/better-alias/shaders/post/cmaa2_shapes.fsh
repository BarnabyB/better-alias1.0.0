#version 330

// CMAA2 - pass 2: long-edge ("Z-shape") detection (the "complex shapes" half of ProcessCandidatesCS,
// plus DetectZsHorizontal and FindZLineLengths).
//
// Ported from Intel's Conservative Morphological Anti-Aliasing 2.0 reference implementation
// (https://github.com/GameTechDev/CMAA2, CMAA2.hlsl), Apache License 2.0:
// Copyright (c) 2018, Intel Corporation
// Licensed under the Apache License, Version 2.0 (licenses/Apache-2.0.txt, http://www.apache.org/licenses/LICENSE-2.0),
// distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
// Modified for Better Alias (2026): rewritten from HLSL compute shaders into GLSL fragment passes, as described
// below. Notices: THIRD_PARTY_NOTICES.md.
//
// In the reference, each Z-shape centre scatters blend items to every pixel along its line (BlendZs). Fragment
// shaders can't scatter, so this pass only finds the centres and measures their lines; the apply pass then has each
// pixel gather from the centres whose lines cover it.
//
// Output (RGBA8) for a pixel that is the centre of a Z-shape that passed the length test, otherwise all zero:
//   r = lineLengthLeft / 255, g = lineLengthRight / 255 (raw lengths, 1..86)
//   b = (shapeQualityScore + 4 * horizontal + 8 * invertedZ) / 255
//   a = 1

// c_maxLineLength
#define CMAA2_MAX_LINE_LENGTH 86.0

uniform sampler2D EdgesSampler;

out vec4 fragColor;

// Edge channels (see cmaa2_edges.fsh): r = right, g = bottom, b = left, a = top
const vec4 EDGE_RIGHT  = vec4(1.0, 0.0, 0.0, 0.0);
const vec4 EDGE_BOTTOM = vec4(0.0, 1.0, 0.0, 0.0);
const vec4 EDGE_LEFT   = vec4(0.0, 0.0, 1.0, 0.0);
const vec4 EDGE_TOP    = vec4(0.0, 0.0, 0.0, 1.0);

// LoadEdge + UnpackEdgesFlt. Out-of-bounds reads return no edges, like an out-of-bounds Load in HLSL.
vec4 loadEdges(ivec2 texel) {
    if (any(lessThan(texel, ivec2(0))) || any(greaterThanEqual(texel, textureSize(EdgesSampler, 0)))) {
        return vec4(0.0);
    }
    return step(0.5, texelFetch(EdgesSampler, texel, 0));
}

void detectZsHorizontal(vec4 edges, vec4 edgesM1P0, vec4 edgesP1P0, vec4 edgesP2P0, out float invertedZScore, out float normalZScore) {
    // Inverted Z case:
    //   __
    //  X|
    // --
    invertedZScore  = edges.r * edges.g * edgesP1P0.a;
    invertedZScore *= 2.0 + (edgesM1P0.g + edgesP2P0.a) - (edges.a + edgesP1P0.g) - 0.7 * (edgesP2P0.g + edgesM1P0.a + edges.b + edgesP1P0.r);

    // Normal Z case:
    // __
    //  X|
    //   --
    normalZScore  = edges.r * edges.a * edgesP1P0.g;
    normalZScore *= 2.0 + (edgesM1P0.a + edgesP2P0.g) - (edges.g + edgesP1P0.a) - 0.7 * (edgesP2P0.a + edgesM1P0.g + edges.b + edgesP1P0.r);
}

// FindZLineLengths: returns vec2(lineLengthLeft, lineLengthRight).
vec2 findZLineLengths(ivec2 screenPos, bool horizontal, bool invertedZShape, ivec2 stepRight) {
    vec4 maskTraceLeft  = horizontal ? EDGE_TOP : EDGE_LEFT;
    vec4 maskTraceRight = horizontal ? EDGE_BOTTOM : EDGE_RIGHT;
    if (invertedZShape) {
        vec4 temp = maskTraceLeft;
        maskTraceLeft = maskTraceRight;
        maskTraceRight = temp;
    }

    bool continueLeft = true;
    bool continueRight = true;
    float lineLengthLeft = 1.0;
    float lineLengthRight = 1.0;

    // The reference loops until its break condition; that always happens within c_maxLineLength steps.
    for (int iteration = 0; iteration < int(CMAA2_MAX_LINE_LENGTH) + 2; iteration++) {
        vec4 edgeLeft  = loadEdges(screenPos - stepRight * int(lineLengthLeft));
        vec4 edgeRight = loadEdges(screenPos + stepRight * (int(lineLengthRight) + 1));

        // stop on encountering 'stopping' edge (as defined by masks)
        continueLeft  = continueLeft  && dot(edgeLeft, maskTraceLeft) > 0.5;
        continueRight = continueRight && dot(edgeRight, maskTraceRight) > 0.5;

        lineLengthLeft  += float(continueLeft);
        lineLengthRight += float(continueRight);

        float maxLR = max(lineLengthRight, lineLengthLeft);

        // both stopped? cause the search end by setting maxLR to max length.
        if (!continueLeft && !continueRight) {
            maxLR = CMAA2_MAX_LINE_LENGTH;
        }

        // either the longer one is ahead of the smaller (already stopped) one by more than a factor of x, or both
        // are stopped - end the search.
        if (maxLR >= min(CMAA2_MAX_LINE_LENGTH, 1.25 * min(lineLengthRight, lineLengthLeft) - 0.25)) {
            break;
        }
    }
    return vec2(lineLengthLeft, lineLengthRight);
}

void main() {
    ivec2 pixelPos = ivec2(gl_FragCoord.xy);

    // Post passes don't clear their output, so every pixel must be written
    fragColor = vec4(0.0);

    vec4 edges = loadEdges(pixelPos);

    // Only "corner" pixels (two perpendicular edges) are shape candidates in CMAA2
    bool isCandidate = (edges.x * edges.y + edges.y * edges.z + edges.z * edges.w + edges.w * edges.x) != 0.0;
    if (!isCandidate) {
        return;
    }

    vec4 edgesLeft   = loadEdges(pixelPos + ivec2(-1,  0));
    vec4 edgesRight  = loadEdges(pixelPos + ivec2( 1,  0));
    vec4 edgesBottom = loadEdges(pixelPos + ivec2( 0,  1));
    vec4 edgesTop    = loadEdges(pixelPos + ivec2( 0, -1));

    float invertedZScore;
    float normalZScore;
    float maxScore;
    bool horizontal = true;
    bool invertedZ = false;

    // horizontal
    {
        vec4 edgesP2P0 = loadEdges(pixelPos + ivec2(2, 0));
        detectZsHorizontal(edges, edgesLeft, edgesRight, edgesP2P0, invertedZScore, normalZScore);
        maxScore = max(invertedZScore, normalZScore);
        if (maxScore > 0.0) {
            invertedZ = invertedZScore > normalZScore;
        }
    }

    // vertical: reuse the horizontal code on data rotated 90 degrees (hence the .argb edge swizzle)
    {
        vec4 edgesP2P0 = loadEdges(pixelPos + ivec2(0, -2));
        detectZsHorizontal(edges.argb, edgesBottom.argb, edgesTop.argb, edgesP2P0.argb, invertedZScore, normalZScore);
        float vertScore = max(invertedZScore, normalZScore);
        if (vertScore > maxScore) {
            maxScore = vertScore;
            horizontal = false;
            invertedZ = invertedZScore > normalZScore;
        }
    }

    if (maxScore > 0.0) {
        // 0 - best quality, 1 - some edges missing but ok, 2 & 3 - dubious but better than nothing
        float shapeQualityScore = floor(clamp(4.0 - maxScore, 0.0, 3.0));

        ivec2 stepRight = horizontal ? ivec2(1, 0) : ivec2(0, -1);
        vec2 lineLengths = findZLineLengths(pixelPos, horizontal, invertedZ, stepRight);

        if ((lineLengths.x - shapeQualityScore) + (lineLengths.y - shapeQualityScore) >= 5.0) {
            float flags = shapeQualityScore + 4.0 * float(horizontal) + 8.0 * float(invertedZ);
            fragColor = vec4(lineLengths.x / 255.0, lineLengths.y / 255.0, flags / 255.0, 1.0);
        }
    }
}
