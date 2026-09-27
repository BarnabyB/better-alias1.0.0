#version 330

// AMD FidelityFX FSR 1 - EASU (Edge Adaptive Spatial Upsampling), FsrEasuF, 32-bit path.
// Upscales the world, rendered below the window's resolution, back to full size. RCAS (rcas.fsh, the RCAS
// Sharpening slider) is FSR 1's second pass and runs afterwards at full resolution.
//
// Ported from AMD FidelityFX FSR 1 (ffx_fsr1.h, https://github.com/GPUOpen-Effects/FidelityFX-FSR), MIT licence:
// Copyright (c) 2021 Advanced Micro Devices, Inc. All rights reserved.
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
// documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
// rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
// permit persons to whom the Software is furnished to do so, subject to the following conditions: The above copyright
// notice and this permission notice shall be included in all copies or substantial portions of the Software.
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
// WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
// Modified for Better Alias (2026): the four textureGather calls of the reference are replaced by the same 12 texels
// read with texelFetch (GLSL 3.30 has no textureGather); the approximate reciprocals use the reference's bit tricks.
// Full licence texts: THIRD_PARTY_NOTICES.md.
//
// EASU works on the stored (sRGB-encoded, perceptual) values, as FSR 1 expects.

uniform sampler2D InSampler; // the world at the lower resolution, nearest

layout(std140) uniform EasuConfig {
    // FsrEasuCon's con0: xy = input size / output size, zw = 0.5 * input / output - 0.5
    vec4 Con0;
};

out vec4 fragColor;

// ffx_a.h approximations
float APrxLoRcpF1(float a) {
    return uintBitsToFloat(0x7ef07ebbu - floatBitsToUint(a));
}

float APrxLoRsqF1(float a) {
    return uintBitsToFloat(0x5f347d74u - (floatBitsToUint(a) >> 1u));
}

// Filtering for a given tap for the scalar
void FsrEasuTapF(inout vec3 aC, inout float aW, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c) {
    // Rotate offset by direction
    vec2 v;
    v.x = (off.x * (dir.x)) + (off.y * dir.y);
    v.y = (off.x * (-dir.y)) + (off.y * dir.x);
    // Anisotropy
    v *= len;
    // Compute distance^2, limited to the window
    float d2 = v.x * v.x + v.y * v.y;
    d2 = min(d2, clp);
    // Approximation of lanczos2 without sin() or rcp(), or sqrt() to get x
    float wB = (2.0 / 5.0) * d2 + (-1.0);
    float wA = lob * d2 + (-1.0);
    wB *= wB;
    wA *= wA;
    wB = (25.0 / 16.0) * wB + (-(25.0 / 16.0 - 1.0));
    float w = wB * wA;
    aC += c * w;
    aW += w;
}

// Accumulate direction and length. 'weight' is the bilinear weight of this quadrant.
void FsrEasuSetF(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE) {
    // Direction is the '+' diff; magnitude from the abs average of both sides of 'c'
    float dc = lD - lC;
    float cb = lC - lB;
    float lenX = max(abs(dc), abs(cb));
    lenX = APrxLoRcpF1(lenX);
    float dirX = lD - lB;
    dir.x += dirX * w;
    lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);
    lenX *= lenX;
    len += lenX * w;
    // Repeat for the y axis
    float ec = lE - lC;
    float ca = lC - lA;
    float lenY = max(abs(ec), abs(ca));
    lenY = APrxLoRcpF1(lenY);
    float dirY = lE - lA;
    dir.y += dirY * w;
    lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);
    lenY *= lenY;
    len += lenY * w;
}

vec3 tap(ivec2 texel, ivec2 size) {
    return texelFetch(InSampler, clamp(texel, ivec2(0), size - 1), 0).rgb;
}

float luma2(vec3 c) {
    // Simplest multi-channel approximate luma possible (luma times 2)
    return c.b * 0.5 + (c.r * 0.5 + c.g);
}

void main() {
    ivec2 size = textureSize(InSampler, 0);
    vec2 ip = floor(gl_FragCoord.xy);

    // Position of 'f'
    vec2 pp = ip * Con0.xy + Con0.zw;
    vec2 fp = floor(pp);
    pp -= fp;
    ivec2 f0 = ivec2(fp);

    // 12-tap kernel
    //    b c
    //  e f g h
    //  i j k l
    //    n o
    vec3 b = tap(f0 + ivec2(0, -1), size);
    vec3 c = tap(f0 + ivec2(1, -1), size);
    vec3 e = tap(f0 + ivec2(-1, 0), size);
    vec3 f = tap(f0 + ivec2(0, 0), size);
    vec3 g = tap(f0 + ivec2(1, 0), size);
    vec3 h = tap(f0 + ivec2(2, 0), size);
    vec3 i = tap(f0 + ivec2(-1, 1), size);
    vec3 j = tap(f0 + ivec2(0, 1), size);
    vec3 k = tap(f0 + ivec2(1, 1), size);
    vec3 l = tap(f0 + ivec2(2, 1), size);
    vec3 n = tap(f0 + ivec2(0, 2), size);
    vec3 o = tap(f0 + ivec2(1, 2), size);

    float bL = luma2(b), cL = luma2(c), eL = luma2(e), fL = luma2(f), gL = luma2(g), hL = luma2(h);
    float iL = luma2(i), jL = luma2(j), kL = luma2(k), lL = luma2(l), nL = luma2(n), oL = luma2(o);

    // Accumulate for bilinear interpolation
    vec2 dir = vec2(0.0);
    float len = 0.0;
    FsrEasuSetF(dir, len, (1.0 - pp.x) * (1.0 - pp.y), bL, eL, fL, gL, jL);
    FsrEasuSetF(dir, len, pp.x * (1.0 - pp.y), cL, fL, gL, hL, kL);
    FsrEasuSetF(dir, len, (1.0 - pp.x) * pp.y, fL, iL, jL, kL, nL);
    FsrEasuSetF(dir, len, pp.x * pp.y, gL, jL, kL, lL, oL);

    // Normalize with approximation, and cleanup close to zero
    vec2 dir2 = dir * dir;
    float dirR = dir2.x + dir2.y;
    bool zro = dirR < (1.0 / 32768.0);
    dirR = APrxLoRsqF1(dirR);
    dirR = zro ? 1.0 : dirR;
    dir.x = zro ? 1.0 : dir.x;
    dir *= vec2(dirR);
    // Transform from {0 to 2} to {0 to 1} range, and shape with square
    len = len * 0.5;
    len *= len;
    // Stretch kernel {1.0 vert|horz, to sqrt(2.0) on diagonal}
    float stretch = (dir.x * dir.x + dir.y * dir.y) * APrxLoRcpF1(max(abs(dir.x), abs(dir.y)));
    // Anisotropic length after rotation
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 + (-0.5) * len);
    // Based on the amount of 'edge', the window shifts from +/-{sqrt(2.0) to slightly beyond 2.0}
    float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;
    // Set distance^2 clipping point to the end of the adjustable window
    float clp = APrxLoRcpF1(lob);

    // Accumulation mixed with min/max of the 4 nearest
    vec3 min4 = min(min(f, g), min(j, k));
    vec3 max4 = max(max(f, g), max(j, k));
    vec3 aC = vec3(0.0);
    float aW = 0.0;
    FsrEasuTapF(aC, aW, vec2(0.0, -1.0) - pp, dir, len2, lob, clp, b);
    FsrEasuTapF(aC, aW, vec2(1.0, -1.0) - pp, dir, len2, lob, clp, c);
    FsrEasuTapF(aC, aW, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, i);
    FsrEasuTapF(aC, aW, vec2(0.0, 1.0) - pp, dir, len2, lob, clp, j);
    FsrEasuTapF(aC, aW, vec2(0.0, 0.0) - pp, dir, len2, lob, clp, f);
    FsrEasuTapF(aC, aW, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, e);
    FsrEasuTapF(aC, aW, vec2(1.0, 1.0) - pp, dir, len2, lob, clp, k);
    FsrEasuTapF(aC, aW, vec2(2.0, 1.0) - pp, dir, len2, lob, clp, l);
    FsrEasuTapF(aC, aW, vec2(2.0, 0.0) - pp, dir, len2, lob, clp, h);
    FsrEasuTapF(aC, aW, vec2(1.0, 0.0) - pp, dir, len2, lob, clp, g);
    FsrEasuTapF(aC, aW, vec2(1.0, 2.0) - pp, dir, len2, lob, clp, o);
    FsrEasuTapF(aC, aW, vec2(0.0, 2.0) - pp, dir, len2, lob, clp, n);

    // Normalize and dering
    vec3 pix = min(max4, max(min4, aC * vec3(1.0 / aW)));
    fragColor = vec4(pix, texelFetch(InSampler, clamp(f0, ivec2(0), size - 1), 0).a);
}
