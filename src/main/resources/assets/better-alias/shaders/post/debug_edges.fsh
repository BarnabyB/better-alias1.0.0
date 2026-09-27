#version 330

// Edge debug view: shows the image dimmed and greyed, with every pixel the selected method detected as an edge in
// bright magenta. The edge texture comes from that method's own edge-detection pass.

uniform sampler2D ColorSampler; // the frame
uniform sampler2D EdgesSampler; // the method's edge texture

layout(std140) uniform DebugConfig {
    vec4 ChannelMask; // which channels of the edge texture hold edges (SMAA: rg, CMAA2: rgba, FXAA: r)
};

out vec4 fragColor;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 color = texelFetch(ColorSampler, p, 0);
    vec4 edges = texelFetch(EdgesSampler, p, 0) * ChannelMask;
    bool edge = max(max(edges.r, edges.g), max(edges.b, edges.a)) > 0.5;
    vec3 dimmed = vec3(dot(color.rgb, vec3(0.299, 0.587, 0.114)) * 0.45);
    fragColor = vec4(edge ? vec3(1.0, 0.1, 0.85) : dimmed, color.a);
}
