#version 330

// FXAA 3.11 - PC quality path (FxaaPixelShader with FXAA_PC == 1), quality preset 39 ("extreme").
//
// Ported from NVIDIA FXAA 3.11 by Timothy Lottes (Fxaa3_11.h), BSD licence:
// Copyright (c) 2014, NVIDIA CORPORATION. All rights reserved.
//
// Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
// following conditions are met:
//  * Redistributions of source code must retain the above copyright notice, this list of conditions and the following
//    disclaimer.
//  * Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
//    following disclaimer in the documentation and/or other materials provided with the distribution.
//  * Neither the name of NVIDIA CORPORATION nor the names of its contributors may be used to endorse or promote
//    products derived from this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT
// NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO
// EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
// CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA,
// OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
// LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
// ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
//
// Modified for Better Alias (2026): ported to a Minecraft post-processing fragment shader (see the port notes below).
// Full licence texts: THIRD_PARTY_NOTICES.md.
//
// Port notes:
//  - FXAA expects non-linear (gamma) colour and relies on bilinear filtering, so DiffuseSampler must be bilinear
//    (see post_effect/fxaa.json) and the main target's sRGB-encoded values are used as-is, like the reference.
//  - The reference reads luma from alpha; Minecraft's alpha isn't luma, so it's computed per fetch with the
//    Rec.601 weights FXAA's documentation uses for its luma pre-pass.
//  - The nested "if (doneNP)" search blocks are written as one loop over the preset's step sizes; the order of
//    samples and steps is the same, including the final step that extends the span without another sample.

// fxaaQualitySubpix: amount of sub-pixel aliasing removal. 1.00 = upper limit (softer), 0.75 = default,
// 0.50 = lower limit (sharper, less sub-pixel removal), 0.25 = almost off, 0.00 = off.
#define FXAA_QUALITY_SUBPIX 0.75
// fxaaQualityEdgeThreshold: minimum local contrast to process. 0.333 = too little (faster), 0.250 = low quality,
// 0.166 = default, 0.125 = high quality, 0.063 = overkill (slower).
#define FXAA_QUALITY_EDGE_THRESHOLD 0.166
// fxaaQualityEdgeThresholdMin: skips processing of dark areas. 0.0833 = default (start of visible unfiltered edges),
// 0.0625 = high quality (faster), 0.0312 = visible limit (slower).
#define FXAA_QUALITY_EDGE_THRESHOLD_MIN 0.0833

// FXAA_QUALITY__PRESET 39: PS = 12
#define FXAA_QUALITY_PS 12
const float FXAA_QUALITY_P[FXAA_QUALITY_PS] = float[FXAA_QUALITY_PS](1.0, 1.0, 1.0, 1.0, 1.0, 1.5, 2.0, 2.0, 2.0, 2.0, 4.0, 8.0);

uniform sampler2D DiffuseSampler;

in vec2 texCoord;
out vec4 fragColor;

float FxaaLuma(vec4 rgba) {
    return dot(rgba.rgb, vec3(0.299, 0.587, 0.114));
}

// FxaaTexTop / FxaaTexOff. A macro so the offset stays a compile-time constant, which GLSL requires for
// textureLodOffset (NVIDIA accepts a variable there; AMD, Intel, Mesa and SPIR-V compilers don't).
#define FxaaTexTop(p) textureLod(DiffuseSampler, p, 0.0)
#define FxaaTexOff(p, o) textureLodOffset(DiffuseSampler, p, 0.0, o)

void main() {
    vec2 fxaaQualityRcpFrame = 1.0 / vec2(textureSize(DiffuseSampler, 0));

    vec2 posM = texCoord;
    vec4 rgbyM = FxaaTexTop(posM);
    float lumaM = FxaaLuma(rgbyM);
    float lumaS = FxaaLuma(FxaaTexOff(posM, ivec2( 0,  1)));
    float lumaE = FxaaLuma(FxaaTexOff(posM, ivec2( 1,  0)));
    float lumaN = FxaaLuma(FxaaTexOff(posM, ivec2( 0, -1)));
    float lumaW = FxaaLuma(FxaaTexOff(posM, ivec2(-1,  0)));

    float maxSM = max(lumaS, lumaM);
    float minSM = min(lumaS, lumaM);
    float maxESM = max(lumaE, maxSM);
    float minESM = min(lumaE, minSM);
    float maxWN = max(lumaN, lumaW);
    float minWN = min(lumaN, lumaW);
    float rangeMax = max(maxWN, maxESM);
    float rangeMin = min(minWN, minESM);
    float rangeMaxScaled = rangeMax * FXAA_QUALITY_EDGE_THRESHOLD;
    float range = rangeMax - rangeMin;
    float rangeMaxClamped = max(FXAA_QUALITY_EDGE_THRESHOLD_MIN, rangeMaxScaled);
    bool earlyExit = range < rangeMaxClamped;

    if (earlyExit) {
        fragColor = rgbyM;
        return;
    }

    float lumaNW = FxaaLuma(FxaaTexOff(posM, ivec2(-1, -1)));
    float lumaSE = FxaaLuma(FxaaTexOff(posM, ivec2( 1,  1)));
    float lumaNE = FxaaLuma(FxaaTexOff(posM, ivec2( 1, -1)));
    float lumaSW = FxaaLuma(FxaaTexOff(posM, ivec2(-1,  1)));

    float lumaNS = lumaN + lumaS;
    float lumaWE = lumaW + lumaE;
    float subpixRcpRange = 1.0 / range;
    float subpixNSWE = lumaNS + lumaWE;
    float edgeHorz1 = (-2.0 * lumaM) + lumaNS;
    float edgeVert1 = (-2.0 * lumaM) + lumaWE;

    float lumaNESE = lumaNE + lumaSE;
    float lumaNWNE = lumaNW + lumaNE;
    float edgeHorz2 = (-2.0 * lumaE) + lumaNESE;
    float edgeVert2 = (-2.0 * lumaN) + lumaNWNE;

    float lumaNWSW = lumaNW + lumaSW;
    float lumaSWSE = lumaSW + lumaSE;
    float edgeHorz4 = (abs(edgeHorz1) * 2.0) + abs(edgeHorz2);
    float edgeVert4 = (abs(edgeVert1) * 2.0) + abs(edgeVert2);
    float edgeHorz3 = (-2.0 * lumaW) + lumaNWSW;
    float edgeVert3 = (-2.0 * lumaS) + lumaSWSE;
    float edgeHorz = abs(edgeHorz3) + edgeHorz4;
    float edgeVert = abs(edgeVert3) + edgeVert4;

    float subpixNWSWNESE = lumaNWSW + lumaNESE;
    float lengthSign = fxaaQualityRcpFrame.x;
    bool horzSpan = edgeHorz >= edgeVert;
    float subpixA = subpixNSWE * 2.0 + subpixNWSWNESE;

    if (!horzSpan) lumaN = lumaW;
    if (!horzSpan) lumaS = lumaE;
    if (horzSpan) lengthSign = fxaaQualityRcpFrame.y;
    float subpixB = (subpixA * (1.0 / 12.0)) - lumaM;

    float gradientN = lumaN - lumaM;
    float gradientS = lumaS - lumaM;
    float lumaNN = lumaN + lumaM;
    float lumaSS = lumaS + lumaM;
    bool pairN = abs(gradientN) >= abs(gradientS);
    float gradient = max(abs(gradientN), abs(gradientS));
    if (pairN) lengthSign = -lengthSign;
    float subpixC = clamp(abs(subpixB) * subpixRcpRange, 0.0, 1.0);

    vec2 posB = posM;
    vec2 offNP;
    offNP.x = (!horzSpan) ? 0.0 : fxaaQualityRcpFrame.x;
    offNP.y = ( horzSpan) ? 0.0 : fxaaQualityRcpFrame.y;
    if (!horzSpan) posB.x += lengthSign * 0.5;
    if ( horzSpan) posB.y += lengthSign * 0.5;

    vec2 posN = posB - offNP * FXAA_QUALITY_P[0];
    vec2 posP = posB + offNP * FXAA_QUALITY_P[0];
    float subpixD = ((-2.0) * subpixC) + 3.0;
    float lumaEndN = FxaaLuma(FxaaTexTop(posN));
    float subpixE = subpixC * subpixC;
    float lumaEndP = FxaaLuma(FxaaTexTop(posP));

    if (!pairN) lumaNN = lumaSS;
    float gradientScaled = gradient * 1.0 / 4.0;
    float lumaMM = lumaM - lumaNN * 0.5;
    float subpixF = subpixD * subpixE;
    bool lumaMLTZero = lumaMM < 0.0;

    lumaEndN -= lumaNN * 0.5;
    lumaEndP -= lumaNN * 0.5;
    bool doneN = abs(lumaEndN) >= gradientScaled;
    bool doneP = abs(lumaEndP) >= gradientScaled;

    // The reference's nested "if (doneNP) { ... }" blocks for FXAA_QUALITY__P1 .. P(PS-1)
    for (int i = 1; i < FXAA_QUALITY_PS; i++) {
        if (doneN && doneP) {
            break;
        }
        if (!doneN) posN -= offNP * FXAA_QUALITY_P[i];
        if (!doneP) posP += offNP * FXAA_QUALITY_P[i];
        if (i == FXAA_QUALITY_PS - 1) {
            break; // the last step extends the span without sampling again
        }
        if (!doneN) lumaEndN = FxaaLuma(FxaaTexTop(posN)) - lumaNN * 0.5;
        if (!doneP) lumaEndP = FxaaLuma(FxaaTexTop(posP)) - lumaNN * 0.5;
        doneN = abs(lumaEndN) >= gradientScaled;
        doneP = abs(lumaEndP) >= gradientScaled;
    }

    float dstN = posM.x - posN.x;
    float dstP = posP.x - posM.x;
    if (!horzSpan) dstN = posM.y - posN.y;
    if (!horzSpan) dstP = posP.y - posM.y;

    bool goodSpanN = (lumaEndN < 0.0) != lumaMLTZero;
    float spanLength = (dstP + dstN);
    bool goodSpanP = (lumaEndP < 0.0) != lumaMLTZero;
    float spanLengthRcp = 1.0 / spanLength;

    bool directionN = dstN < dstP;
    float dstMin = min(dstN, dstP);
    bool goodSpan = directionN ? goodSpanN : goodSpanP;
    float subpixG = subpixF * subpixF;
    float pixelOffset = (dstMin * (-spanLengthRcp)) + 0.5;
    float subpixH = subpixG * FXAA_QUALITY_SUBPIX;

    float pixelOffsetGood = goodSpan ? pixelOffset : 0.0;
    float pixelOffsetSubpix = max(pixelOffsetGood, subpixH);
    if (!horzSpan) posM.x += pixelOffsetSubpix * lengthSign;
    if ( horzSpan) posM.y += pixelOffsetSubpix * lengthSign;

    // The reference returns luma in alpha; keep Minecraft's alpha instead
    fragColor = vec4(FxaaTexTop(posM).rgb, rgbyM.a);
}
