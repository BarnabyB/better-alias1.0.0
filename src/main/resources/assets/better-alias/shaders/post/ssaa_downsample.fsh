#version 330

// SSAA resolve: averages the world, rendered at a higher resolution, down to the window.
//
// Each output pixel takes the area-weighted average of the source texels its footprint covers (a box filter). For the
// whole-number scales that is exactly the average of the 2x2 / 3x3 / 4x4 samples inside it; for 1.5x the texels on the
// footprint's border count for the part that falls inside. Averaging happens in linear light, the way light really
// adds up inside a pixel (the targets hold sRGB-encoded values).

uniform sampler2D InSampler; // the world at the higher resolution, nearest

layout(std140) uniform SsaaConfig {
    vec4 Scale; // xy: source size / output size, per axis
};

out vec4 fragColor;

// Enough taps per axis for a 4x scale whose footprint doesn't start on a texel boundary
#define MAX_TAPS 6

vec3 toLinear(vec3 srgb) {
    return mix(pow((srgb + 0.055) / 1.055, vec3(2.4)), srgb / 12.92, lessThanEqual(srgb, vec3(0.04045)));
}

vec3 toSrgb(vec3 linear) {
    linear = clamp(linear, 0.0, 1.0);
    return mix(1.055 * pow(linear, vec3(1.0 / 2.4)) - 0.055, 12.92 * linear, lessThanEqual(linear, vec3(0.0031308)));
}

void main() {
    ivec2 sourceSize = textureSize(InSampler, 0);
    vec2 scale = Scale.xy;

    // This output pixel's footprint in source texels
    vec2 footprintMin = floor(gl_FragCoord.xy) * scale;
    vec2 footprintMax = footprintMin + scale;
    ivec2 first = ivec2(floor(footprintMin));

    vec4 sum = vec4(0.0);
    float totalWeight = 0.0;
    for (int y = 0; y < MAX_TAPS; y++) {
        float texelY = float(first.y + y);
        float weightY = min(footprintMax.y, texelY + 1.0) - max(footprintMin.y, texelY);
        if (weightY <= 0.0) {
            break;
        }
        for (int x = 0; x < MAX_TAPS; x++) {
            float texelX = float(first.x + x);
            float weightX = min(footprintMax.x, texelX + 1.0) - max(footprintMin.x, texelX);
            if (weightX <= 0.0) {
                break;
            }
            vec4 color = texelFetch(InSampler, clamp(first + ivec2(x, y), ivec2(0), sourceSize - 1), 0);
            float weight = weightX * weightY;
            sum += vec4(toLinear(color.rgb), color.a) * weight;
            totalWeight += weight;
        }
    }
    sum /= max(totalWeight, 1e-6);

    fragColor = vec4(toSrgb(sum.rgb), sum.a);
}
