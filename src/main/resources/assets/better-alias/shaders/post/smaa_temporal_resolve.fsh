#version 330

// SMAA T2x / temporal 4x resolve.
//
// Every frame the level is rendered with a small sub-pixel camera jitter and anti-aliased with SMAA 1x, told where
// inside the pixel that frame's sample sits (subsample indices, see smaa_weights.fsh). The last 2 (T2x) or 4 (4x) SMAA
// outputs are kept, and this pass averages them, so a still image gets 2 or 4 SMAA-filtered samples per pixel
// (Jimenez et al., "SMAA: Enhanced Subpixel Morphological Antialiasing", 2012, section 5 / SMAAResolvePS).
//
// Unlike TAA nothing is accumulated forever: each older frame is read directly and only resampled once, so the image
// stays as sharp as SMAA 1x. Differences from the reference resolve, because Minecraft has no motion vectors:
//   - Older frames are reprojected from the depth buffer and the camera movement (like taa_resolve.fsh), which is
//     exact for the world but not for moving mobs.
//   - Instead of the reference's velocity weighting, every older sample is clipped to the colour range of the current
//     frame's 3x3 neighbourhood, so disocclusions and moving mobs fall back to the current frame instead of ghosting.
// Averaging happens in linear light, as the reference asks (the ring textures hold the usual sRGB-encoded values).
//
// Written for Better Alias (MIT), following the SMAA paper and SMAA.hlsl (MIT, Jimenez et al.; see THIRD_PARTY_NOTICES.md).

uniform sampler2D CurrentSampler;  // this frame's SMAA output (sRGB-encoded), nearest
uniform sampler2D DepthSampler;    // main depth this frame (jittered), nearest
uniform sampler2D History1Sampler; // SMAA output 1 frame ago, bilinear
uniform sampler2D History2Sampler; // 2 frames ago (4x only), bilinear
uniform sampler2D History3Sampler; // 3 frames ago (4x only), bilinear

layout(std140) uniform SmaaTemporalConfig {
    mat4 InvViewProj;        // inverse of this frame's jittered projection * view rotation
    mat4 ViewProjUnjittered; // this frame's projection * view rotation without jitter
    mat4 PrevViewProj1;      // projection * view rotation (no jitter) 1, 2 and 3 frames ago
    mat4 PrevViewProj2;
    mat4 PrevViewProj3;
    vec4 CameraDelta1;       // xyz: camera position now minus 1/2/3 frames ago (blocks); w: 1 if that frame is usable
    vec4 CameraDelta2;
    vec4 CameraDelta3;
    vec4 Params;             // x: 1 if clip depth is [0, 1]
};

in vec2 texCoord;
out vec4 fragColor;

vec3 toLinear(vec3 srgb) {
    return mix(pow((srgb + 0.055) / 1.055, vec3(2.4)), srgb / 12.92, lessThanEqual(srgb, vec3(0.04045)));
}

vec3 toSrgb(vec3 linear) {
    linear = clamp(linear, 0.0, 1.0);
    return mix(1.055 * pow(linear, vec3(1.0 / 2.4)) - 0.055, 12.92 * linear, lessThanEqual(linear, vec3(0.0031308)));
}

vec3 rgbToYCoCg(vec3 c) {
    return vec3(
         0.25 * c.r + 0.5 * c.g + 0.25 * c.b,
         0.5  * c.r             - 0.5  * c.b,
        -0.25 * c.r + 0.5 * c.g - 0.25 * c.b);
}

vec3 yCoCgToRgb(vec3 c) {
    return vec3(c.x + c.y - c.z, c.x + c.z, c.x - c.y - c.z);
}

// 5-tap Catmull-Rom (Jimenez), so reprojected frames don't go soft while the camera moves. Filtered on the stored
// (sRGB-encoded) values, converted to linear afterwards.
vec3 sampleCatmullRom(sampler2D tex, vec2 uv, vec2 texSize) {
    vec2 samplePos = uv * texSize;
    vec2 texPos1 = floor(samplePos - 0.5) + 0.5;
    vec2 f = samplePos - texPos1;

    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);

    vec2 w12 = w1 + w2;
    vec2 offset12 = w2 / w12;

    vec2 texPos0 = (texPos1 - 1.0) / texSize;
    vec2 texPos3 = (texPos1 + 2.0) / texSize;
    vec2 texPos12 = (texPos1 + offset12) / texSize;

    vec3 result = vec3(0.0);
    result += textureLod(tex, vec2(texPos12.x, texPos0.y), 0.0).rgb * w12.x * w0.y;
    result += textureLod(tex, vec2(texPos0.x, texPos12.y), 0.0).rgb * w0.x * w12.y;
    result += textureLod(tex, vec2(texPos12.x, texPos12.y), 0.0).rgb * w12.x * w12.y;
    result += textureLod(tex, vec2(texPos3.x, texPos12.y), 0.0).rgb * w3.x * w12.y;
    result += textureLod(tex, vec2(texPos12.x, texPos3.y), 0.0).rgb * w12.x * w3.y;
    float weightSum = w12.x * w0.y + w0.x * w12.y + w12.x * w12.y + w3.x * w12.y + w12.x * w3.y;
    return toLinear(clamp(result / weightSum, 0.0, 1.0));
}

// Clip towards the centre of the neighbourhood's box (Playdead / INSIDE); keeps the hue better than a per-channel clamp
vec3 clipToBox(vec3 boxMin, vec3 boxMax, vec3 value) {
    vec3 center = 0.5 * (boxMax + boxMin);
    vec3 extents = 0.5 * (boxMax - boxMin) + 1e-5;
    vec3 offset = value - center;
    vec3 unit = abs(offset / extents);
    float maxUnit = max(unit.x, max(unit.y, unit.z));
    return maxUnit > 1.0 ? center + offset / maxUnit : value;
}

// Where this pixel's surface was in an older frame, as a texture coordinate; false if it was off screen.
// position: camera-relative position now; currentClip: that position through this frame's unjittered camera.
bool reproject(vec4 position, vec4 currentClip, vec2 uv, mat4 prevViewProj, vec4 cameraDelta, out vec2 historyUv) {
    historyUv = uv;
    if (cameraDelta.w < 0.5) {
        return false;
    }
    vec4 previousClip = prevViewProj * vec4(position.xyz + cameraDelta.xyz, 1.0);
    if (previousClip.w <= 0.0) {
        return false;
    }
    vec2 velocity = (currentClip.xy / currentClip.w - previousClip.xy / previousClip.w) * 0.5;
    historyUv = uv - velocity;
    return all(greaterThanEqual(historyUv, vec2(0.0))) && all(lessThanEqual(historyUv, vec2(1.0)));
}

void main() {
    ivec2 size = textureSize(CurrentSampler, 0);
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec2 texSize = vec2(size);
    vec2 uv = (vec2(pixel) + 0.5) / texSize;

    vec4 center = texelFetch(CurrentSampler, pixel, 0);
    vec3 current = toLinear(center.rgb);

    // 3x3 neighbourhood of this frame's SMAA output: colour range for clipping, and the closest depth (reversed-Z:
    // larger = closer) so object edges reproject with the object rather than the background behind them.
    vec3 boxMin = vec3(1e9);
    vec3 boxMax = vec3(-1e9);
    float closestDepth = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            ivec2 texel = clamp(pixel + ivec2(x, y), ivec2(0), size - 1);
            vec3 yCoCg = rgbToYCoCg(toLinear(texelFetch(CurrentSampler, texel, 0).rgb));
            boxMin = min(boxMin, yCoCg);
            boxMax = max(boxMax, yCoCg);
            closestDepth = max(closestDepth, texelFetch(DepthSampler, texel, 0).r);
        }
    }

    // Camera-relative position of this pixel, rebuilt from depth
    float ndcZ = Params.x > 0.5 ? closestDepth : closestDepth * 2.0 - 1.0;
    vec4 position = InvViewProj * vec4(uv * 2.0 - 1.0, ndcZ, 1.0);
    position /= position.w;
    vec4 currentClip = ViewProjUnjittered * position;

    // Average this frame with every older frame that can be reprojected, each clipped to this frame's colour range
    vec3 sum = current;
    float count = 1.0;
    vec2 historyUv;
    if (reproject(position, currentClip, uv, PrevViewProj1, CameraDelta1, historyUv)) {
        sum += yCoCgToRgb(clipToBox(boxMin, boxMax, rgbToYCoCg(sampleCatmullRom(History1Sampler, historyUv, texSize))));
        count += 1.0;
    }
    if (reproject(position, currentClip, uv, PrevViewProj2, CameraDelta2, historyUv)) {
        sum += yCoCgToRgb(clipToBox(boxMin, boxMax, rgbToYCoCg(sampleCatmullRom(History2Sampler, historyUv, texSize))));
        count += 1.0;
    }
    if (reproject(position, currentClip, uv, PrevViewProj3, CameraDelta3, historyUv)) {
        sum += yCoCgToRgb(clipToBox(boxMin, boxMax, rgbToYCoCg(sampleCatmullRom(History3Sampler, historyUv, texSize))));
        count += 1.0;
    }

    fragColor = vec4(toSrgb(sum / count), center.a);
}
