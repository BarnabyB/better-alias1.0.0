#version 330

// Edge debug view for FXAA: marks the pixels FXAA 3.11 works on, i.e. the ones that pass its early-exit contrast test
// (same luma, neighbours and thresholds as fxaa.fsh). Output: r = 1 on an edge, 0 elsewhere.

uniform sampler2D ColorSampler;

out vec4 fragColor;

// Keep in sync with fxaa.fsh
#define FXAA_QUALITY_EDGE_THRESHOLD 0.166
#define FXAA_QUALITY_EDGE_THRESHOLD_MIN 0.0833

float luma(ivec2 texel, ivec2 size) {
    return dot(texelFetch(ColorSampler, clamp(texel, ivec2(0), size - 1), 0).rgb, vec3(0.299, 0.587, 0.114));
}

void main() {
    ivec2 size = textureSize(ColorSampler, 0);
    ivec2 p = ivec2(gl_FragCoord.xy);
    float m = luma(p, size);
    float s = luma(p + ivec2(0, 1), size);
    float e = luma(p + ivec2(1, 0), size);
    float n = luma(p + ivec2(0, -1), size);
    float w = luma(p + ivec2(-1, 0), size);
    float rangeMax = max(max(n, w), max(e, max(s, m)));
    float rangeMin = min(min(n, w), min(e, min(s, m)));
    bool edge = rangeMax - rangeMin >= max(FXAA_QUALITY_EDGE_THRESHOLD_MIN, rangeMax * FXAA_QUALITY_EDGE_THRESHOLD);
    fragColor = vec4(edge ? 1.0 : 0.0, 0.0, 0.0, 1.0);
}
