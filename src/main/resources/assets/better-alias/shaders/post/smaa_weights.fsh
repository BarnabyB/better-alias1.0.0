#version 330

// SMAA 1x - pass 2: blending weight calculation.
// Ported from SMAA-MC (https://github.com/Luracasmus/SMAA-MC), MIT licensed:
// Copyright (C) 2013 Jorge Jimenez, Jose I. Echevarria, Belen Masia, Fernando Navarro, Diego Gutierrez
// Copyright (C) 2024-2025 Luracasmus
// Modified for Better Alias (2026). Full licence texts: THIRD_PARTY_NOTICES.md.
//
// EdgesSampler must be bilinear: the searches rely on bilinear fetches to read two edges at once.
// AreaSampler is bilinear, SearchSampler is point sampled.
//
// SubsampleIndices (SMAA.hlsl) pick which of the area texture's 7 stacked sub-textures to read, i.e. where inside the
// pixel this frame's sample sits. SMAA 1x uses all zeros (pixel centre, set in post_effect/smaa.json); SMAA T2x and the
// temporal 4x mode set them per frame to match the camera jitter (BetterAliasSmaaTemporal).

// Max horizontal/vertical search distance in pixels / 2 (max 112)
#define SMAA_SEARCH 112
// Max diagonal search steps, 0 disables diagonal detection (max 20)
#define SMAA_SEARCH_DIAG 20
// Corner rounding in percent, 0 disables corner detection
#define SMAA_CORNER 25

#define AREATEX_SIZE vec2(160.0, 560.0)
// The area texture holds 7 sub-textures stacked vertically, one per subsample offset
#define AREATEX_SUBTEX_SIZE (1.0 / 7.0)

uniform sampler2D EdgesSampler;
uniform sampler2D AreaSampler;
uniform sampler2D SearchSampler;

layout(std140) uniform SmaaConfig {
    // x: vertical edges, y: horizontal edges, z/w: the two diagonal directions
    vec4 SubsampleIndices;
};

out vec4 fragColor;

vec2 pixSize;

#if SMAA_SEARCH_DIAG
vec2 decodeDiagBilinearAccess(vec2 e) {
    e.x *= abs(e.x * 5.0 - 3.75);
    return roundEven(e);
}

vec4 decodeDiagBilinearAccess(vec4 e) {
    e.xz *= abs(e.xz * 5.0 - 3.75);
    return roundEven(e);
}

vec2 areaDiag(vec2 dist, vec2 e, float offset) {
    vec2 texCoord = (20.0 * e + dist) / AREATEX_SIZE + 0.5 / AREATEX_SIZE;
    texCoord.x += 0.5;
    texCoord.y += AREATEX_SUBTEX_SIZE * offset;
    return textureLod(AreaSampler, texCoord, 0.0).rg;
}

vec2 searchDiag1(vec2 coord, vec2 dir, out vec2 end) {
    end = vec2(0.0);
    float w = 1.0;
    int z;
    for (z = -1; z < SMAA_SEARCH_DIAG - 1 && w > 0.9; ++z) {
        coord += dir * pixSize;
        end = textureLod(EdgesSampler, coord, 0.0).rg;
        w = dot(end, vec2(0.5));
    }
    return vec2(float(z), w);
}

vec2 searchDiag2(vec2 coord, vec2 dir, out vec2 end) {
    end = vec2(0.0);
    coord.x += 0.25 * pixSize.x;

    float w = 1.0;
    int z;
    for (z = -1; z < SMAA_SEARCH_DIAG - 1 && w > 0.9; ++z) {
        coord += dir * pixSize;
        end = decodeDiagBilinearAccess(textureLod(EdgesSampler, coord, 0.0).rg);
        w = dot(end, vec2(0.5));
    }
    return vec2(float(z), w);
}

vec2 calculateDiagWeights(ivec2 texel, vec2 coord, bool eX) {
    vec2 weights = vec2(0.0);

    vec4 d;
    vec2 end;
    if (eX) {
        d.xz = searchDiag1(coord, vec2(-1.0, 1.0), end);
        d.x += float(end.y > 0.9);
    } else {
        d.xz = vec2(0.0);
    }

    d.yw = searchDiag1(coord, vec2(1.0, -1.0), end);

    if (d.x + d.y > 2.0) {
        vec4 offsetCoord = vec4(0.25 - d.x, d.x, d.y, -d.y - 0.25) * pixSize.xyxy + coord.xyxy;
        vec4 c = vec4(
                textureLodOffset(EdgesSampler, offsetCoord.xy, 0.0, ivec2(-1, 0)).rg,
                textureLodOffset(EdgesSampler, offsetCoord.zw, 0.0, ivec2(1, 0)).rg
        );
        c.yxwz = decodeDiagBilinearAccess(c);

        weights += areaDiag(d.xy, mix(2.0 * c.xz + c.yw, vec2(0.0), bvec2(step(0.9, d.zw))), SubsampleIndices.z);
    }

    d.xz = searchDiag2(coord, vec2(-1.0), end);

    ivec2 right = min(texel + ivec2(1, 0), textureSize(EdgesSampler, 0) - 1);
    if (texelFetch(EdgesSampler, right, 0).r > 0.0) {
        d.yw = searchDiag2(coord, vec2(1.0), end);
        d.y += float(end.y > 0.9);
    } else {
        d.yw = vec2(0.0);
    }

    if (d.x + d.y > 2.0) {
        vec4 offsetCoord = vec4(-d.xx, d.yy) * pixSize.xyxy + coord.xyxy;
        vec4 c = vec4(
                textureLodOffset(EdgesSampler, offsetCoord.xy, 0.0, ivec2(-1, 0)).g,
                textureLodOffset(EdgesSampler, offsetCoord.xy, 0.0, ivec2(0, -1)).r,
                textureLodOffset(EdgesSampler, offsetCoord.zw, 0.0, ivec2(1, 0)).gr
        );
        weights += areaDiag(d.xy, mix(2.0 * c.xz + c.yw, vec2(0.0), bvec2(step(0.9, d.zw))), SubsampleIndices.w).yx;
    }

    return weights;
}
#endif

vec2 area(vec2 dist, float e1, float e2, float offset) {
    vec2 texCoord = (roundEven(4.0 * vec2(e1, e2)) * 16.0 + dist) / AREATEX_SIZE + 0.5 / AREATEX_SIZE;
    texCoord.y += AREATEX_SUBTEX_SIZE * offset;
    return textureLod(AreaSampler, texCoord, 0.0).rg;
}

float searchLength(vec2 e, float offset) {
    ivec2 texel = ivec2(e * vec2(32.0, -32.0) + vec2(offset * 66.0 + 0.5, 32.5));
    // The texture is the cropped 64x16 non-zero corner of the full table; everything outside is 0
    // and the last row/column are 0, so clamping gives the right result.
    return texelFetch(SearchSampler, min(texel, ivec2(63, 15)), 0).r;
}

float searchXLeft(vec2 coord, float end) {
    vec2 e = vec2(0.0, 1.0);
    while (coord.x > end && e.y > 0.8281 && e.x == 0.0) {
        e = textureLod(EdgesSampler, coord, 0.0).rg;
        coord.x -= 2.0 * pixSize.x;
    }
    return (searchLength(e, 0.0) * (-255.0 / 127.0) + 3.25) * pixSize.x + coord.x;
}

float searchXRight(vec2 coord, float end) {
    vec2 e = vec2(0.0, 1.0);
    while (coord.x < end && e.y > 0.8281 && e.x == 0.0) {
        e = textureLod(EdgesSampler, coord, 0.0).rg;
        coord.x += 2.0 * pixSize.x;
    }
    return (searchLength(e, 0.5) * (255.0 / 127.0) - 3.25) * pixSize.x + coord.x;
}

float searchYUp(vec2 coord, float end) {
    vec2 e = vec2(1.0, 0.0);
    while (coord.y > end && e.x > 0.8281 && e.y == 0.0) {
        e = textureLod(EdgesSampler, coord, 0.0).rg;
        coord.y -= 2.0 * pixSize.y;
    }
    return (searchLength(e.yx, 0.0) * (-255.0 / 127.0) + 3.25) * pixSize.y + coord.y;
}

float searchYDown(vec2 coord, float end) {
    vec2 e = vec2(1.0, 0.0);
    while (coord.y < end && e.x > 0.8281 && e.y == 0.0) {
        e = textureLod(EdgesSampler, coord, 0.0).rg;
        coord.y += 2.0 * pixSize.y;
    }
    return (searchLength(e.yx, 0.5) * (255.0 / 127.0) - 3.25) * pixSize.y + coord.y;
}

#if SMAA_CORNER
vec2 cornerRounding(vec2 d) {
    vec2 leftRight = step(d, d.yx);
    return (1.0 - float(SMAA_CORNER) / 100.0) * leftRight / (leftRight.x + leftRight.y);
}

vec2 detectHorizontalCornerPattern(vec3 coord, vec2 d) {
    vec2 rounding = cornerRounding(d);
    return clamp(1.0 - vec2(
            dot(rounding, vec2(
                    textureLodOffset(EdgesSampler, coord.xy, 0.0, ivec2(0, 1)).r,
                    textureLodOffset(EdgesSampler, coord.zy, 0.0, ivec2(1, 1)).r
            )),
            dot(rounding, vec2(
                    textureLodOffset(EdgesSampler, coord.xy, 0.0, ivec2(0, -2)).r,
                    textureLodOffset(EdgesSampler, coord.zy, 0.0, ivec2(1, -2)).r
            ))
    ), 0.0, 1.0);
}

vec2 detectVerticalCornerPattern(vec3 coord, vec2 d) {
    vec2 rounding = cornerRounding(d);
    return clamp(1.0 - vec2(
            dot(rounding, vec2(
                    textureLodOffset(EdgesSampler, coord.xy, 0.0, ivec2(1, 0)).g,
                    textureLodOffset(EdgesSampler, coord.zy, 0.0, ivec2(1, 1)).g
            )),
            dot(rounding, vec2(
                    textureLodOffset(EdgesSampler, coord.xy, 0.0, ivec2(-2, 0)).g,
                    textureLodOffset(EdgesSampler, coord.zy, 0.0, ivec2(-2, 1)).g
            ))
    ), 0.0, 1.0);
}
#endif

void main() {
    pixSize = 1.0 / vec2(textureSize(EdgesSampler, 0));

    ivec2 texel = ivec2(gl_FragCoord.xy);
    bvec2 e = greaterThanEqual(texelFetch(EdgesSampler, texel, 0).rg, vec2(0.5));

    // Post passes don't clear their output, so every pixel must be written
    vec4 weights = vec4(0.0);

    if (any(e)) {
        vec2 texelCoord = vec2(texel) + 0.5;
        vec2 coord = texelCoord * pixSize;

        vec4 offsets0 = pixSize.xyxy * vec4(-0.250, -0.125, 1.250, -0.125) + coord.xyxy;
        vec4 offsets1 = pixSize.xyxy * vec4(-0.125, -0.250, -0.125, 1.250) + coord.xyxy;
        vec4 offsets2 = pixSize.xxyy * (vec4(-2.0, 2.0, -2.0, 2.0) * float(SMAA_SEARCH)) + vec4(offsets0.xz, offsets1.yw);

        if (e.y) {
            // Edge at north
            #if SMAA_SEARCH_DIAG
            weights.xy = calculateDiagWeights(texel, coord, e.x);

            // Skip the horizontal search if a diagonal was found
            if (weights.x == -weights.y) {
                #endif
                vec3 offsetCoord = vec3(searchXLeft(offsets0.xy, offsets2.x), offsets1.y, searchXRight(offsets0.zw, offsets2.y));

                float e1 = textureLod(EdgesSampler, offsetCoord.xy, 0.0).r;
                float e2 = textureLodOffset(EdgesSampler, offsetCoord.zy, 0.0, ivec2(1, 0)).r;
                vec2 dist = abs(roundEven(offsetCoord.xz / pixSize.x - texelCoord.xx));

                weights.xy = area(sqrt(dist), e1, e2, SubsampleIndices.y);

                #if SMAA_CORNER
                weights.xy *= detectHorizontalCornerPattern(vec3(offsetCoord.x, coord.y, offsetCoord.z), dist);
                #endif
                #if SMAA_SEARCH_DIAG
            } else {
                // Diagonal found, skip the vertical search too
                e.x = false;
            }
            #endif
        }

        if (e.x) {
            // Edge at west
            vec3 offsetCoord = vec3(offsets0.x, searchYUp(offsets1.xy, offsets2.z), searchYDown(offsets1.zw, offsets2.w));

            float e1 = textureLod(EdgesSampler, offsetCoord.xy, 0.0).g;
            float e2 = textureLodOffset(EdgesSampler, offsetCoord.xz, 0.0, ivec2(0, 1)).g;
            vec2 dist = abs(roundEven(offsetCoord.yz / pixSize.y - texelCoord.yy));

            weights.zw = area(sqrt(dist), e1, e2, SubsampleIndices.x);

            #if SMAA_CORNER
            weights.zw *= detectVerticalCornerPattern(vec3(coord.x, offsetCoord.yz), dist);
            #endif
        }
    }

    fragColor = weights;
}
