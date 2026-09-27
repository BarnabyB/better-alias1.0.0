#version 330

uniform sampler2D DiffuseSampler;

in vec2 texCoord;
out vec4 fragColor;

// --- BALANCED FXAA CONFIGURATION ---
// Restored high sensitivity to catch thin geometry like iron bars
#define FXAA_REDUCE_MIN   (1.0/128.0)
// Restored the wider blur so it can bridge the stair-steps on those bars
#define FXAA_REDUCE_MUL   (1.0/8.0)
// The Goldilocks span. 4.0 breaks bars, 12.0 melts roofs. 8.0 is the sweet spot.
#define FXAA_SPAN_MAX     8.0

// Convert from sRGB to Linear for accurate light math
vec3 toLinear(vec3 srgb) {
    return mix(pow((srgb + 0.055) / 1.055, vec3(2.4)), srgb / 12.92, lessThanEqual(srgb, vec3(0.04045)));
}

// Convert back to sRGB for the final screen output
vec3 toSrgb(vec3 linear) {
    return mix(1.055 * pow(linear, vec3(1.0 / 2.4)) - 0.055, 12.92 * linear, lessThanEqual(linear, vec3(0.0031308)));
}

// Helper to grab corner pixels and immediately fix their color space
vec3 fetchLinear(ivec2 offset) {
    return toLinear(textureOffset(DiffuseSampler, texCoord, offset).rgb);
}

void main() {
    vec2 inverseTexSize = 1.0 / vec2(textureSize(DiffuseSampler, 0));

    // Fetch corners in linear space
    vec3 rgbNW = fetchLinear(ivec2(-1, -1));
    vec3 rgbNE = fetchLinear(ivec2(1, -1));
    vec3 rgbSW = fetchLinear(ivec2(-1, 1));
    vec3 rgbSE = fetchLinear(ivec2(1, 1));
    vec3 rgbM  = toLinear(texture(DiffuseSampler, texCoord).rgb);

    // Calculate perceptual luma (brightness) on the linear colors
    vec3 luma = vec3(0.299, 0.587, 0.114);
    float lumaNW = dot(rgbNW, luma);
    float lumaNE = dot(rgbNE, luma);
    float lumaSW = dot(rgbSW, luma);
    float lumaSE = dot(rgbSE, luma);
    float lumaM  = dot(rgbM,  luma);

    float lumaMin = min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)));
    float lumaMax = max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)));

    // Calculate the direction of the jagged edge
    vec2 dir;
    dir.x = -((lumaNW + lumaNE) - (lumaSW + lumaSE));
    dir.y =  ((lumaNW + lumaSW) - (lumaNE + lumaSE));

    float dirReduce = max((lumaNW + lumaNE + lumaSW + lumaSE) * (0.25 * FXAA_REDUCE_MUL), FXAA_REDUCE_MIN);
    float rcpDirMin = 1.0 / (min(abs(dir.x), abs(dir.y)) + dirReduce);

    // Clamp the blur vector to our balanced max span
    dir = min(vec2(FXAA_SPAN_MAX,  FXAA_SPAN_MAX),
            max(vec2(-FXAA_SPAN_MAX, -FXAA_SPAN_MAX),
                    dir * rcpDirMin)) * inverseTexSize;

    // 4-tap directional blur using standard texture fetches, converting to linear immediately
    vec3 rgbA = (1.0/2.0) * (
    toLinear(texture(DiffuseSampler, texCoord + dir * (1.0/3.0 - 0.5)).rgb) +
    toLinear(texture(DiffuseSampler, texCoord + dir * (2.0/3.0 - 0.5)).rgb)
    );

    vec3 rgbB = rgbA * (1.0/2.0) + (1.0/4.0) * (
    toLinear(texture(DiffuseSampler, texCoord + dir * (0.0/3.0 - 0.5)).rgb) +
    toLinear(texture(DiffuseSampler, texCoord + dir * (3.0/3.0 - 0.5)).rgb)
    );

    float lumaB = dot(rgbB, luma);

    // Revert back to sRGB for the final output
    if ((lumaB < lumaMin) || (lumaB > lumaMax)) {
        fragColor = vec4(toSrgb(rgbA), 1.0);
    } else {
        fragColor = vec4(toSrgb(rgbB), 1.0);
    }
}