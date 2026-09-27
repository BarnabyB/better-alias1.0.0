#version 330

// CMAA2 - pass 3: blending (the "simple shapes" half of ProcessCandidatesCS, BlendZs and DeferredColorApply2x2CS).
//
// Ported from Intel's Conservative Morphological Anti-Aliasing 2.0 reference implementation
// (https://github.com/GameTechDev/CMAA2, CMAA2.hlsl), Apache License 2.0:
// Copyright (c) 2018, Intel Corporation
// Licensed under the Apache License, Version 2.0 (licenses/Apache-2.0.txt, http://www.apache.org/licenses/LICENSE-2.0),
// distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND.
// Modified for Better Alias (2026): rewritten from HLSL compute shaders into GLSL fragment passes, as described
// below. Notices: THIRD_PARTY_NOTICES.md.
//
// The reference writes blended colour "samples" into per-pixel lists and averages them afterwards
// (simple-shape samples weigh 0.8, long-edge samples 1.8). Here each pixel gathers the same samples itself:
//   - its own simple-shape sample, if it is a corner pixel;
//   - one long-edge sample for every Z-shape centre (found by cmaa2_shapes) whose line covers it.
// Z-shape lines always run along an edge the covered pixel has, so each pixel only walks along its own edges.
// Pixels with no edges are passed through untouched.
//
// Colours are blended in linear space and written back sRGB-encoded, like the reference's sRGB store path.

// c_symmetryCorrectionOffset
#define CMAA2_SYMMETRY_CORRECTION 0.22
// c_dampeningEffect (0.15 default; 0.11 with CMAA2_EXTRA_SHARPNESS)
#define CMAA2_DAMPENING 0.15
// g_CMAA2_SimpleShapeBlurinessAmount
#define CMAA2_SIMPLE_SHAPE_BLURINESS 0.10
// floor((c_maxLineLength + 1) / 2): the furthest a Z-shape centre can reach along its line
#define CMAA2_MAX_REACH 43

uniform sampler2D ColorSampler;
uniform sampler2D EdgesSampler;
uniform sampler2D ShapesSampler;

out vec4 fragColor;

// Edge channels (see cmaa2_edges.fsh): r = right, g = bottom, b = left, a = top
const vec4 EDGE_RIGHT  = vec4(1.0, 0.0, 0.0, 0.0);
const vec4 EDGE_BOTTOM = vec4(0.0, 1.0, 0.0, 0.0);
const vec4 EDGE_LEFT   = vec4(0.0, 0.0, 1.0, 0.0);
const vec4 EDGE_TOP    = vec4(0.0, 0.0, 0.0, 1.0);

vec3 toLinear(vec3 srgb) {
    return mix(pow((srgb + 0.055) / 1.055, vec3(2.4)), srgb / 12.92, lessThanEqual(srgb, vec3(0.04045)));
}

vec3 toSrgb(vec3 linear) {
    return mix(1.055 * pow(linear, vec3(1.0 / 2.4)) - 0.055, 12.92 * linear, lessThanEqual(linear, vec3(0.0031308)));
}

bool inBounds(ivec2 texel) {
    return all(greaterThanEqual(texel, ivec2(0))) && all(lessThan(texel, textureSize(ColorSampler, 0)));
}

vec4 loadEdges(ivec2 texel) {
    return inBounds(texel) ? step(0.5, texelFetch(EdgesSampler, texel, 0)) : vec4(0.0);
}

// LoadSourceColor, returning linear colour
vec3 loadColor(ivec2 texel) {
    return toLinear(texelFetch(ColorSampler, clamp(texel, ivec2(0), textureSize(ColorSampler, 0) - 1), 0).rgb);
}

// HLSL's % on floats (fmod: result takes the sign of the dividend), which differs from GLSL mod() for negatives
float hlslFmod(float x, float y) {
    return x - y * trunc(x / y);
}

vec4 computeSimpleShapeBlendValues(vec4 edges, vec4 edgesLeft, vec4 edgesRight, vec4 edgesTop, vec4 edgesBottom) {
    // a 3x3 kernel for higher quality handling of L-based shapes (still rather basic and conservative)
    float fromRight = edges.r;
    float fromBelow = edges.g;
    float fromLeft  = edges.b;
    float fromAbove = edges.a;

    float blurCoeff = CMAA2_SIMPLE_SHAPE_BLURINESS;

    float numberOfEdges = dot(edges, vec4(1.0));

    float numberOfEdgesAllAround = dot(edgesLeft.bga + edgesRight.rga + edgesTop.rba + edgesBottom.rgb, vec3(1.0));

    // (ProcessCandidatesCS calls this with dontTestShapeValidity = true, so the shape validity test is skipped)

    // L-like step shape
    if (numberOfEdges == 2.0) {
        blurCoeff *= 0.75;

        float k = 0.9;
        fromRight += k * (edges.g * edgesTop.r    * (1.0 - edgesLeft.g)   + edges.a * edgesBottom.r * (1.0 - edgesLeft.a));
        fromBelow += k * (edges.b * edgesRight.g  * (1.0 - edgesTop.b)    + edges.r * edgesLeft.g   * (1.0 - edgesTop.r));
        fromLeft  += k * (edges.a * edgesBottom.b * (1.0 - edgesRight.a)  + edges.g * edgesTop.b    * (1.0 - edgesRight.g));
        fromAbove += k * (edges.r * edgesLeft.a   * (1.0 - edgesBottom.r) + edges.b * edgesRight.a  * (1.0 - edgesBottom.b));
    }

    // Dampen the blurring effect when lots of neighbouring edges - additionally preserves text and texture detail
    blurCoeff *= clamp(1.30 - numberOfEdgesAllAround / 10.0, 0.0, 1.0);

    return vec4(fromLeft, fromAbove, fromRight, fromBelow) * blurCoeff;
}

// Reads a Z-shape centre written by cmaa2_shapes. Returns false unless there is one of the requested orientation/type.
bool loadZShape(ivec2 texel, bool horizontal, bool invertedZ, out float lineLengthLeft, out float lineLengthRight, out float shapeQualityScore) {
    lineLengthLeft = 0.0;
    lineLengthRight = 0.0;
    shapeQualityScore = 0.0;
    if (!inBounds(texel)) {
        return false;
    }
    vec4 shape = texelFetch(ShapesSampler, texel, 0);
    if (shape.a < 0.5) {
        return false;
    }
    int flags = int(round(shape.b * 255.0));
    if (((flags & 4) != 0) != horizontal || ((flags & 8) != 0) != invertedZ) {
        return false;
    }
    lineLengthLeft = round(shape.r * 255.0);
    lineLengthRight = round(shape.g * 255.0);
    shapeQualityScore = float(flags & 3);
    return true;
}

// The body of BlendZs' loop for a single item: how strongly the pixel at index i along a Z-shape's line (relative to
// its centre) blends towards its neighbour. Returns false if the line doesn't reach index i.
bool zBlendAmount(float lineLengthLeft, float lineLengthRight, float shapeQualityScore, float i, out float lerpK) {
    lerpK = 0.0;

    // ProcessCandidatesCS subtracts the quality score before calling BlendZs
    lineLengthLeft  -= shapeQualityScore;
    lineLengthRight -= shapeQualityScore;

    float leftOdd  = CMAA2_SYMMETRY_CORRECTION * hlslFmod(lineLengthLeft, 2.0);
    float rightOdd = CMAA2_SYMMETRY_CORRECTION * hlslFmod(lineLengthRight, 2.0);

    float dampenEffect = clamp((lineLengthLeft + lineLengthRight - shapeQualityScore) * CMAA2_DAMPENING, 0.0, 1.0);

    float loopFrom = -floor((lineLengthLeft + 1.0) / 2.0) + 1.0;
    float loopTo = floor((lineLengthRight + 1.0) / 2.0);
    if (i < loopFrom || i > loopTo) {
        return false;
    }

    float totalLength = (loopTo - loopFrom) + 1.0 - leftOdd - rightOdd;
    float lerpStep = 1.0 / totalLength;
    float lerpFromK = (0.5 - leftOdd - loopFrom) * lerpStep;

    float secondPart = float(i > 0.0);
    float srcOffset = 1.0 - secondPart * 2.0;

    lerpK = (lerpStep * i + lerpFromK) * srcOffset + secondPart;
    lerpK *= dampenEffect;
    return true;
}

// Gathers the long-edge samples for one line orientation. In the reference a Z-shape centre C blends pixels
// C + stepRight * i for i in [loopFrom, loopTo]:
//   i <= 0 ("left" half) pixels carry the left trace edge and blend with pixel + blendDir;
//   i >  0 ("right" half) pixels carry the right trace edge and blend with pixel - blendDir.
void gatherZShapes(ivec2 pixelPos, vec4 edges, vec3 center, bool horizontal, inout vec4 accum) {
    ivec2 stepRight = horizontal ? ivec2(1, 0) : ivec2(0, -1);

    for (int zType = 0; zType < 2; zType++) {
        bool invertedZ = zType == 1;

        // Same masks and directions as FindZLineLengths / BlendZs
        vec4 maskTraceLeft  = horizontal ? EDGE_TOP : EDGE_LEFT;
        vec4 maskTraceRight = horizontal ? EDGE_BOTTOM : EDGE_RIGHT;
        if (invertedZ) {
            vec4 temp = maskTraceLeft;
            maskTraceLeft = maskTraceRight;
            maskTraceRight = temp;
        }
        ivec2 blendDir = horizontal ? ivec2(0, -1) : ivec2(-1, 0);
        if (invertedZ) {
            blendDir = -blendDir;
        }

        float lineLengthLeft;
        float lineLengthRight;
        float shapeQualityScore;
        float lerpK;

        // This pixel in the left half (i = -k <= 0): the centre is k steps "right" of us, and every pixel from us to
        // the centre carries the left trace edge.
        if (dot(edges, maskTraceLeft) > 0.5) {
            vec3 colorFrom = loadColor(pixelPos + blendDir);
            for (int k = 0; k <= CMAA2_MAX_REACH; k++) {
                ivec2 centerPos = pixelPos + stepRight * k;
                if (k > 0 && dot(loadEdges(centerPos), maskTraceLeft) < 0.5) {
                    break;
                }
                if (loadZShape(centerPos, horizontal, invertedZ, lineLengthLeft, lineLengthRight, shapeQualityScore)
                        && zBlendAmount(lineLengthLeft, lineLengthRight, shapeQualityScore, float(-k), lerpK)) {
                    accum += vec4(mix(center, colorFrom, lerpK) * 1.8, 1.8);
                }
            }
        }

        // This pixel in the right half (i = k >= 1): the centre is k steps "left" of us, and every pixel after the
        // centre up to us carries the right trace edge.
        if (dot(edges, maskTraceRight) > 0.5) {
            vec3 colorFrom = loadColor(pixelPos - blendDir);
            for (int k = 1; k <= CMAA2_MAX_REACH; k++) {
                if (k > 1 && dot(loadEdges(pixelPos - stepRight * (k - 1)), maskTraceRight) < 0.5) {
                    break;
                }
                ivec2 centerPos = pixelPos - stepRight * k;
                if (loadZShape(centerPos, horizontal, invertedZ, lineLengthLeft, lineLengthRight, shapeQualityScore)
                        && zBlendAmount(lineLengthLeft, lineLengthRight, shapeQualityScore, float(k), lerpK)) {
                    accum += vec4(mix(center, colorFrom, lerpK) * 1.8, 1.8);
                }
            }
        }
    }
}

void main() {
    ivec2 pixelPos = ivec2(gl_FragCoord.xy);

    vec4 centerRaw = texelFetch(ColorSampler, pixelPos, 0);
    vec4 edges = loadEdges(pixelPos);

    // Every sample the reference can produce for a pixel requires that pixel to have at least one edge
    if (dot(edges, vec4(1.0)) == 0.0) {
        fragColor = centerRaw;
        return;
    }

    vec3 center = toLinear(centerRaw.rgb);
    // rgb = weighted colour sum, a = total weight (DeferredColorApply2x2CS)
    vec4 accum = vec4(0.0);

    // Simple shapes: only corner pixels are candidates
    bool isCandidate = (edges.x * edges.y + edges.y * edges.z + edges.z * edges.w + edges.w * edges.x) != 0.0;
    if (isCandidate) {
        vec4 edgesLeft   = loadEdges(pixelPos + ivec2(-1,  0));
        vec4 edgesRight  = loadEdges(pixelPos + ivec2( 1,  0));
        vec4 edgesBottom = loadEdges(pixelPos + ivec2( 0,  1));
        vec4 edgesTop    = loadEdges(pixelPos + ivec2( 0, -1));

        vec4 blendVal = computeSimpleShapeBlendValues(edges, edgesLeft, edgesRight, edgesTop, edgesBottom);
        float centerWeight = 1.0 - dot(blendVal, vec4(1.0));

        vec3 outColor = center * centerWeight;
        if (blendVal.x > 0.0) outColor += blendVal.x * loadColor(pixelPos + ivec2(-1,  0)); // from left
        if (blendVal.y > 0.0) outColor += blendVal.y * loadColor(pixelPos + ivec2( 0, -1)); // from above
        if (blendVal.z > 0.0) outColor += blendVal.z * loadColor(pixelPos + ivec2( 1,  0)); // from right
        if (blendVal.w > 0.0) outColor += blendVal.w * loadColor(pixelPos + ivec2( 0,  1)); // from below

        accum += vec4(outColor * 0.8, 0.8);
    }

    // Complex shapes (long edges)
    gatherZShapes(pixelPos, edges, center, true, accum);
    gatherZShapes(pixelPos, edges, center, false, accum);

    fragColor = accum.a > 0.0 ? vec4(toSrgb(accum.rgb / accum.a), centerRaw.a) : centerRaw;
}
