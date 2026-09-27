#version 330

// Temporal anti-aliasing resolve.
//
// Each frame the level is rendered with a different sub-pixel camera jitter (BetterAliasTaa). This pass blends the new,
// jittered frame into an accumulated history, so over a few frames every pixel averages many sub-pixel positions:
//   1. Reconstruct the current frame's anti-aliased colour from its 3x3 neighbourhood, undoing the jitter with a
//      Blackman-Harris-like weighting (Karis, "High Quality Temporal Supersampling", SIGGRAPH 2014).
//   2. Reproject: rebuild each pixel's camera-relative position from the depth buffer, and project it with last
//      frame's camera to find where it was in the history. Minecraft has no motion vectors, so moving entities are
//      handled by step 4 rather than by exact reprojection.
//   3. Sample the history with a 5-tap Catmull-Rom filter (Jimenez) so repeated resampling doesn't blur the image.
//   4. Clip the history colour to the current neighbourhood's colour range (variance clipping in YCoCg, Salvi 2016 /
//      Playdead's clip-towards-centre), which rejects stale history from disocclusions and moving objects (anti-ghosting).
//   5. Blend with inverse-luma weights (Karis) to avoid flicker from bright pixels.
// Accumulates in linear light, like supersampling (the main target's sRGB-encoded values are decoded on read). The
// history target is RGBA16F and stores linear colour; the CAS pass converts back to sRGB. Clipping and the inverse-luma
// weights use a perceptual (sRGB-encoded) luma so dark areas are treated like bright ones.

uniform sampler2D CurrentSampler; // main colour this frame (jittered), nearest
uniform sampler2D DepthSampler;   // main depth this frame (jittered), nearest
uniform sampler2D HistorySampler; // last frame's TAA output (linear colour, RGBA16F), bilinear

layout(std140) uniform TaaConfig {
    mat4 InvViewProj;        // inverse of this frame's jittered projection * view rotation
    mat4 ViewProjUnjittered; // this frame's projection * view rotation without jitter
    mat4 PrevViewProj;       // last frame's projection * view rotation without jitter
    vec4 CameraDelta;        // xyz: camera position this frame minus last frame (blocks); w: 1 if clip depth is [0, 1]
    vec4 Params;             // x: history valid (0/1); y: weight of the current frame; zw: this frame's jitter in pixels
};

in vec2 texCoord;
out vec4 fragColor;

vec3 toLinear(vec3 srgb) {
    return mix(pow((srgb + 0.055) / 1.055, vec3(2.4)), srgb / 12.92, lessThanEqual(srgb, vec3(0.04045)));
}

// Cheap perceptual luma for weighting (sqrt approximates the sRGB curve)
float perceptualLuma(vec3 linear) {
    return sqrt(max(dot(linear, vec3(0.299, 0.587, 0.114)), 0.0));
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

// 5-tap Catmull-Rom history sample (Jorge Jimenez's optimisation of the 16-tap filter; corner taps dropped)
vec3 sampleHistoryCatmullRom(vec2 uv, vec2 texSize) {
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
    result += textureLod(HistorySampler, vec2(texPos12.x, texPos0.y), 0.0).rgb * w12.x * w0.y;
    result += textureLod(HistorySampler, vec2(texPos0.x, texPos12.y), 0.0).rgb * w0.x * w12.y;
    result += textureLod(HistorySampler, vec2(texPos12.x, texPos12.y), 0.0).rgb * w12.x * w12.y;
    result += textureLod(HistorySampler, vec2(texPos3.x, texPos12.y), 0.0).rgb * w3.x * w12.y;
    result += textureLod(HistorySampler, vec2(texPos12.x, texPos3.y), 0.0).rgb * w12.x * w3.y;
    float weightSum = w12.x * w0.y + w0.x * w12.y + w12.x * w12.y + w3.x * w12.y + w12.x * w3.y;
    return max(result / weightSum, vec3(0.0));
}

// Clip the history colour towards the centre of the neighbourhood's box (Playdead / INSIDE)
vec3 clipToBox(vec3 boxMin, vec3 boxMax, vec3 history) {
    vec3 center = 0.5 * (boxMax + boxMin);
    vec3 extents = 0.5 * (boxMax - boxMin) + 1e-5;
    vec3 offset = history - center;
    vec3 unit = abs(offset / extents);
    float maxUnit = max(unit.x, max(unit.y, unit.z));
    return maxUnit > 1.0 ? center + offset / maxUnit : history;
}

void main() {
    ivec2 size = textureSize(CurrentSampler, 0);
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    vec2 uv = (vec2(pixel) + 0.5) / vec2(size);
    vec2 jitter = Params.zw;

    // --- 1. current frame: neighbourhood statistics and unjittered reconstruction ---
    vec3 moment1 = vec3(0.0);
    vec3 moment2 = vec3(0.0);
    vec3 boxMin = vec3(1e9);
    vec3 boxMax = vec3(-1e9);
    vec3 reconstructed = vec3(0.0);
    float reconstructedWeight = 0.0;
    // Reversed-Z: larger depth = closer. The closest depth in the 3x3 is used for reprojection so that edges of
    // near objects reproject with the object, not with the background behind them.
    float closestDepth = 0.0;

    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            ivec2 offset = ivec2(x, y);
            ivec2 texel = clamp(pixel + offset, ivec2(0), size - 1);
            vec3 color = toLinear(texelFetch(CurrentSampler, texel, 0).rgb);
            vec3 yCoCg = rgbToYCoCg(color);
            moment1 += yCoCg;
            moment2 += yCoCg * yCoCg;
            boxMin = min(boxMin, yCoCg);
            boxMax = max(boxMax, yCoCg);

            // This neighbour was rendered with the content shifted by 'jitter', so its sample sits at offset - jitter
            // from our pixel centre. Gaussian fit to Blackman-Harris (radius ~1 px).
            vec2 d = vec2(offset) - jitter;
            float w = exp(-2.29 * dot(d, d));
            reconstructed += color * w;
            reconstructedWeight += w;

            closestDepth = max(closestDepth, texelFetch(DepthSampler, texel, 0).r);
        }
    }
    vec3 current = reconstructed / reconstructedWeight;

    if (Params.x < 0.5) {
        fragColor = vec4(current, 1.0);
        return;
    }

    // --- 2. reprojection from depth ---
    float ndcZ = CameraDelta.w > 0.5 ? closestDepth : closestDepth * 2.0 - 1.0;
    vec4 position = InvViewProj * vec4(uv * 2.0 - 1.0, ndcZ, 1.0);
    position /= position.w;

    vec4 currentClip = ViewProjUnjittered * position;
    vec4 previousClip = PrevViewProj * vec4(position.xyz + CameraDelta.xyz, 1.0);
    if (previousClip.w <= 0.0) {
        fragColor = vec4(current, 1.0);
        return;
    }
    vec2 velocity = (currentClip.xy / currentClip.w - previousClip.xy / previousClip.w) * 0.5;
    vec2 historyUv = uv - velocity;

    if (any(lessThan(historyUv, vec2(0.0))) || any(greaterThan(historyUv, vec2(1.0)))) {
        fragColor = vec4(current, 1.0);
        return;
    }

    // --- 3. history ---
    vec3 history = sampleHistoryCatmullRom(historyUv, vec2(size));

    // --- 4. variance clipping in YCoCg ---
    vec3 mean = moment1 / 9.0;
    vec3 sigma = sqrt(abs(moment2 / 9.0 - mean * mean));
    const float gamma = 1.0;
    vec3 varianceMin = max(boxMin, mean - gamma * sigma);
    vec3 varianceMax = min(boxMax, mean + gamma * sigma);
    vec3 historyYCoCg = clipToBox(varianceMin, varianceMax, rgbToYCoCg(history));
    history = yCoCgToRgb(historyYCoCg);

    // --- 5. blend with inverse-luma weighting ---
    // Faster movement (in pixels) leans a little more on the current frame, since the history has been resampled more.
    float speed = length(velocity * vec2(size));
    float currentWeight = mix(Params.y, min(1.0, Params.y * 2.5), clamp(speed / 16.0, 0.0, 1.0));
    float historyWeight = 1.0 - currentWeight;
    currentWeight /= 1.0 + perceptualLuma(current);
    historyWeight /= 1.0 + perceptualLuma(history);
    vec3 result = (current * currentWeight + history * historyWeight) / (currentWeight + historyWeight);

    fragColor = vec4(max(result, vec3(0.0)), 1.0);
}
