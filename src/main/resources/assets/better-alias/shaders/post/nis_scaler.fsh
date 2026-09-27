#version 330

// NVIDIA Image Scaling (NIS) - NVScaler: upscales the world, rendered below the window's resolution, with a 6-tap
// scaling filter blended with 4 directional filters, plus adaptive sharpening (the NIS Sharpness slider).
//
// Ported from the NVIDIA Image Scaling SDK v1.0.3 (NIS_Scaler.h, NIS_Config.h,
// https://github.com/NVIDIAGameWorks/NVIDIAImageScaling), MIT licence:
// Copyright(c) 2022 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
// documentation files(the "Software"), to deal in the Software without restriction, including without limitation the
// rights to use, copy, modify, merge, publish, distribute, sublicense, and / or sell copies of the Software, and to
// permit persons to whom the Software is furnished to do so, subject to the following conditions : The above copyright
// notice and this permission notice shall be included in all copies or substantial portions of the Software.
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
// WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
// Modified for Better Alias (2026): the compute shader (shared-memory tiles of luma and edge maps) is rewritten as a
// fragment shader that computes the same 6x6 luma window and 2x2 edge maps per output pixel; the coefficient textures
// become constant arrays; 2D arrays are flattened (GLSL 3.30). SDR constants from NVScalerUpdateConfig.
// Full licence texts: THIRD_PARTY_NOTICES.md.

uniform sampler2D InSampler; // the world at the lower resolution, bilinear (texel centres are read exactly)

layout(std140) uniform NisConfig {
    vec4 Sharpness; // kSharpStrengthMin, kSharpStrengthScale, kSharpLimitMin, kSharpLimitScale
    vec4 Scale;     // xy: kScaleX, kScaleY (input size / output size, 0.5..1)
};

out vec4 fragColor;

// NVScalerUpdateConfig, SDR
const float kDetectRatio = 2.0 * 1127.0 / 1024.0;
const float kDetectThres = 64.0 / 1024.0;
const float kMinContrastRatio = 2.0;
const float kRatioNorm = 1.0 / (10.0 - 2.0);
const float kContrastBoost = 1.0;
const float kEps = 1.0 / 255.0;
const float kSharpStartY = 0.45;
const float kSharpScaleY = 1.0 / (0.9 - 0.45);
const int kPhaseCount = 64;

// coef_scale from NIS_Config.h: 64 phases x 6 taps (the last 2 of each row are always 0)
const float NIS_COEF_SCALE[384] = float[384](
    0.0000, 0.0000, 1.0000, 0.0000, 0.0000, 0.0000,
    0.0029, -0.0127, 1.0000, 0.0132, -0.0034, 0.0000,
    0.0063, -0.0249, 0.9985, 0.0269, -0.0068, 0.0000,
    0.0088, -0.0361, 0.9956, 0.0415, -0.0103, 0.0005,
    0.0117, -0.0474, 0.9932, 0.0562, -0.0142, 0.0005,
    0.0142, -0.0576, 0.9897, 0.0713, -0.0181, 0.0005,
    0.0166, -0.0674, 0.9844, 0.0874, -0.0220, 0.0010,
    0.0186, -0.0762, 0.9785, 0.1040, -0.0264, 0.0015,
    0.0205, -0.0850, 0.9727, 0.1206, -0.0308, 0.0020,
    0.0225, -0.0928, 0.9648, 0.1382, -0.0352, 0.0024,
    0.0239, -0.1006, 0.9575, 0.1558, -0.0396, 0.0029,
    0.0254, -0.1074, 0.9487, 0.1738, -0.0439, 0.0034,
    0.0264, -0.1138, 0.9390, 0.1929, -0.0488, 0.0044,
    0.0278, -0.1191, 0.9282, 0.2119, -0.0537, 0.0049,
    0.0288, -0.1245, 0.9170, 0.2310, -0.0581, 0.0059,
    0.0293, -0.1294, 0.9058, 0.2510, -0.0630, 0.0063,
    0.0303, -0.1333, 0.8926, 0.2710, -0.0679, 0.0073,
    0.0308, -0.1367, 0.8789, 0.2915, -0.0728, 0.0083,
    0.0308, -0.1401, 0.8657, 0.3120, -0.0776, 0.0093,
    0.0313, -0.1426, 0.8506, 0.3330, -0.0825, 0.0103,
    0.0313, -0.1445, 0.8354, 0.3540, -0.0874, 0.0112,
    0.0313, -0.1460, 0.8193, 0.3755, -0.0923, 0.0122,
    0.0313, -0.1470, 0.8022, 0.3965, -0.0967, 0.0137,
    0.0308, -0.1479, 0.7856, 0.4185, -0.1016, 0.0146,
    0.0303, -0.1479, 0.7681, 0.4399, -0.1060, 0.0156,
    0.0298, -0.1479, 0.7505, 0.4614, -0.1104, 0.0166,
    0.0293, -0.1470, 0.7314, 0.4829, -0.1147, 0.0181,
    0.0288, -0.1460, 0.7119, 0.5049, -0.1187, 0.0190,
    0.0278, -0.1445, 0.6929, 0.5264, -0.1226, 0.0200,
    0.0273, -0.1431, 0.6724, 0.5479, -0.1260, 0.0215,
    0.0264, -0.1411, 0.6528, 0.5693, -0.1299, 0.0225,
    0.0254, -0.1387, 0.6323, 0.5903, -0.1328, 0.0234,
    0.0244, -0.1357, 0.6113, 0.6113, -0.1357, 0.0244,
    0.0234, -0.1328, 0.5903, 0.6323, -0.1387, 0.0254,
    0.0225, -0.1299, 0.5693, 0.6528, -0.1411, 0.0264,
    0.0215, -0.1260, 0.5479, 0.6724, -0.1431, 0.0273,
    0.0200, -0.1226, 0.5264, 0.6929, -0.1445, 0.0278,
    0.0190, -0.1187, 0.5049, 0.7119, -0.1460, 0.0288,
    0.0181, -0.1147, 0.4829, 0.7314, -0.1470, 0.0293,
    0.0166, -0.1104, 0.4614, 0.7505, -0.1479, 0.0298,
    0.0156, -0.1060, 0.4399, 0.7681, -0.1479, 0.0303,
    0.0146, -0.1016, 0.4185, 0.7856, -0.1479, 0.0308,
    0.0137, -0.0967, 0.3965, 0.8022, -0.1470, 0.0313,
    0.0122, -0.0923, 0.3755, 0.8193, -0.1460, 0.0313,
    0.0112, -0.0874, 0.3540, 0.8354, -0.1445, 0.0313,
    0.0103, -0.0825, 0.3330, 0.8506, -0.1426, 0.0313,
    0.0093, -0.0776, 0.3120, 0.8657, -0.1401, 0.0308,
    0.0083, -0.0728, 0.2915, 0.8789, -0.1367, 0.0308,
    0.0073, -0.0679, 0.2710, 0.8926, -0.1333, 0.0303,
    0.0063, -0.0630, 0.2510, 0.9058, -0.1294, 0.0293,
    0.0059, -0.0581, 0.2310, 0.9170, -0.1245, 0.0288,
    0.0049, -0.0537, 0.2119, 0.9282, -0.1191, 0.0278,
    0.0044, -0.0488, 0.1929, 0.9390, -0.1138, 0.0264,
    0.0034, -0.0439, 0.1738, 0.9487, -0.1074, 0.0254,
    0.0029, -0.0396, 0.1558, 0.9575, -0.1006, 0.0239,
    0.0024, -0.0352, 0.1382, 0.9648, -0.0928, 0.0225,
    0.0020, -0.0308, 0.1206, 0.9727, -0.0850, 0.0205,
    0.0015, -0.0264, 0.1040, 0.9785, -0.0762, 0.0186,
    0.0010, -0.0220, 0.0874, 0.9844, -0.0674, 0.0166,
    0.0005, -0.0181, 0.0713, 0.9897, -0.0576, 0.0142,
    0.0005, -0.0142, 0.0562, 0.9932, -0.0474, 0.0117,
    0.0005, -0.0103, 0.0415, 0.9956, -0.0361, 0.0088,
    0.0000, -0.0068, 0.0269, 0.9985, -0.0249, 0.0063,
    0.0000, -0.0034, 0.0132, 1.0000, -0.0127, 0.0029
);

// coef_usm from NIS_Config.h: 64 phases x 6 taps (the last 2 of each row are always 0)
const float NIS_COEF_USM[384] = float[384](
    0.0000, -0.6001, 1.2002, -0.6001, 0.0000, 0.0000,
    0.0029, -0.6084, 1.1987, -0.5903, -0.0029, 0.0000,
    0.0049, -0.6147, 1.1958, -0.5791, -0.0068, 0.0005,
    0.0073, -0.6196, 1.1890, -0.5659, -0.0103, 0.0000,
    0.0093, -0.6235, 1.1802, -0.5513, -0.0151, 0.0000,
    0.0112, -0.6265, 1.1699, -0.5352, -0.0195, 0.0005,
    0.0122, -0.6270, 1.1582, -0.5181, -0.0259, 0.0005,
    0.0142, -0.6284, 1.1455, -0.5005, -0.0317, 0.0005,
    0.0156, -0.6265, 1.1274, -0.4790, -0.0386, 0.0005,
    0.0166, -0.6235, 1.1089, -0.4570, -0.0454, 0.0010,
    0.0176, -0.6187, 1.0879, -0.4346, -0.0532, 0.0010,
    0.0181, -0.6138, 1.0659, -0.4102, -0.0615, 0.0015,
    0.0190, -0.6069, 1.0405, -0.3843, -0.0698, 0.0015,
    0.0195, -0.6006, 1.0161, -0.3574, -0.0796, 0.0020,
    0.0200, -0.5928, 0.9893, -0.3286, -0.0898, 0.0024,
    0.0200, -0.5820, 0.9580, -0.2988, -0.1001, 0.0029,
    0.0200, -0.5728, 0.9292, -0.2690, -0.1104, 0.0034,
    0.0200, -0.5620, 0.8975, -0.2368, -0.1226, 0.0039,
    0.0205, -0.5498, 0.8643, -0.2046, -0.1343, 0.0044,
    0.0200, -0.5371, 0.8301, -0.1709, -0.1465, 0.0049,
    0.0195, -0.5239, 0.7944, -0.1367, -0.1587, 0.0054,
    0.0195, -0.5107, 0.7598, -0.1021, -0.1724, 0.0059,
    0.0190, -0.4966, 0.7231, -0.0649, -0.1865, 0.0063,
    0.0186, -0.4819, 0.6846, -0.0288, -0.1997, 0.0068,
    0.0186, -0.4668, 0.6460, 0.0093, -0.2144, 0.0073,
    0.0176, -0.4507, 0.6055, 0.0479, -0.2290, 0.0083,
    0.0171, -0.4370, 0.5693, 0.0859, -0.2446, 0.0088,
    0.0161, -0.4199, 0.5283, 0.1255, -0.2598, 0.0098,
    0.0161, -0.4048, 0.4883, 0.1655, -0.2754, 0.0103,
    0.0151, -0.3887, 0.4497, 0.2041, -0.2910, 0.0107,
    0.0142, -0.3711, 0.4072, 0.2446, -0.3066, 0.0117,
    0.0137, -0.3555, 0.3672, 0.2852, -0.3228, 0.0122,
    0.0132, -0.3394, 0.3262, 0.3262, -0.3394, 0.0132,
    0.0122, -0.3228, 0.2852, 0.3672, -0.3555, 0.0137,
    0.0117, -0.3066, 0.2446, 0.4072, -0.3711, 0.0142,
    0.0107, -0.2910, 0.2041, 0.4497, -0.3887, 0.0151,
    0.0103, -0.2754, 0.1655, 0.4883, -0.4048, 0.0161,
    0.0098, -0.2598, 0.1255, 0.5283, -0.4199, 0.0161,
    0.0088, -0.2446, 0.0859, 0.5693, -0.4370, 0.0171,
    0.0083, -0.2290, 0.0479, 0.6055, -0.4507, 0.0176,
    0.0073, -0.2144, 0.0093, 0.6460, -0.4668, 0.0186,
    0.0068, -0.1997, -0.0288, 0.6846, -0.4819, 0.0186,
    0.0063, -0.1865, -0.0649, 0.7231, -0.4966, 0.0190,
    0.0059, -0.1724, -0.1021, 0.7598, -0.5107, 0.0195,
    0.0054, -0.1587, -0.1367, 0.7944, -0.5239, 0.0195,
    0.0049, -0.1465, -0.1709, 0.8301, -0.5371, 0.0200,
    0.0044, -0.1343, -0.2046, 0.8643, -0.5498, 0.0205,
    0.0039, -0.1226, -0.2368, 0.8975, -0.5620, 0.0200,
    0.0034, -0.1104, -0.2690, 0.9292, -0.5728, 0.0200,
    0.0029, -0.1001, -0.2988, 0.9580, -0.5820, 0.0200,
    0.0024, -0.0898, -0.3286, 0.9893, -0.5928, 0.0200,
    0.0020, -0.0796, -0.3574, 1.0161, -0.6006, 0.0195,
    0.0015, -0.0698, -0.3843, 1.0405, -0.6069, 0.0190,
    0.0015, -0.0615, -0.4102, 1.0659, -0.6138, 0.0181,
    0.0010, -0.0532, -0.4346, 1.0879, -0.6187, 0.0176,
    0.0010, -0.0454, -0.4570, 1.1089, -0.6235, 0.0166,
    0.0005, -0.0386, -0.4790, 1.1274, -0.6265, 0.0156,
    0.0005, -0.0317, -0.5005, 1.1455, -0.6284, 0.0142,
    0.0005, -0.0259, -0.5181, 1.1582, -0.6270, 0.0122,
    0.0005, -0.0195, -0.5352, 1.1699, -0.6265, 0.0112,
    0.0000, -0.0151, -0.5513, 1.1802, -0.6235, 0.0093,
    0.0000, -0.0103, -0.5659, 1.1890, -0.6196, 0.0073,
    0.0005, -0.0068, -0.5791, 1.1958, -0.6147, 0.0049,
    0.0000, -0.0029, -0.5903, 1.1987, -0.6084, 0.0029
);

// p is the 6x6 luma window, row-major: P(row, column), row = y
#define P(r, c) p[(r) * 6 + (c)]

float getY(vec3 rgba) {
    return 0.2126 * rgba.x + 0.7152 * rgba.y + 0.0722 * rgba.z;
}

vec4 GetEdgeMap(float p[36], int i, int j) {
    float g_0 = abs(P(0 + i, 0 + j) + P(0 + i, 1 + j) + P(0 + i, 2 + j) - P(2 + i, 0 + j) - P(2 + i, 1 + j) - P(2 + i, 2 + j));
    float g_45 = abs(P(1 + i, 0 + j) + P(0 + i, 0 + j) + P(0 + i, 1 + j) - P(2 + i, 1 + j) - P(2 + i, 2 + j) - P(1 + i, 2 + j));
    float g_90 = abs(P(0 + i, 0 + j) + P(1 + i, 0 + j) + P(2 + i, 0 + j) - P(0 + i, 2 + j) - P(1 + i, 2 + j) - P(2 + i, 2 + j));
    float g_135 = abs(P(1 + i, 0 + j) + P(2 + i, 0 + j) + P(2 + i, 1 + j) - P(0 + i, 1 + j) - P(0 + i, 2 + j) - P(1 + i, 2 + j));

    float g_0_90_max = max(g_0, g_90);
    float g_0_90_min = min(g_0, g_90);
    float g_45_135_max = max(g_45, g_135);
    float g_45_135_min = min(g_45, g_135);

    if (g_0_90_max + g_45_135_max == 0.0) {
        return vec4(0.0);
    }

    float e_0_90 = min(g_0_90_max / (g_0_90_max + g_45_135_max), 1.0);
    float e_45_135 = 1.0 - e_0_90;

    bool c_0_90 = (g_0_90_max > (g_0_90_min * kDetectRatio)) && (g_0_90_max > kDetectThres) && (g_0_90_max > g_45_135_min);
    bool c_45_135 = (g_45_135_max > (g_45_135_min * kDetectRatio)) && (g_45_135_max > kDetectThres) && (g_45_135_max > g_0_90_min);
    bool c_g_0_90 = g_0_90_max == g_0;
    bool c_g_45_135 = g_45_135_max == g_45;

    float f_e_0_90 = (c_0_90 && c_45_135) ? e_0_90 : 1.0;
    float f_e_45_135 = (c_0_90 && c_45_135) ? e_45_135 : 1.0;

    float weight_0 = (c_0_90 && c_g_0_90) ? f_e_0_90 : 0.0;
    float weight_90 = (c_0_90 && !c_g_0_90) ? f_e_0_90 : 0.0;
    float weight_45 = (c_45_135 && c_g_45_135) ? f_e_45_135 : 0.0;
    float weight_135 = (c_45_135 && !c_g_45_135) ? f_e_45_135 : 0.0;

    return vec4(weight_0, weight_90, weight_45, weight_135);
}

float CalcLTI(float p0, float p1, float p2, float p3, float p4, float p5, int phase_index) {
    bool selector = (phase_index <= kPhaseCount / 2);
    float sel = selector ? p0 : p3;
    float a_min = min(min(p1, p2), sel);
    float a_max = max(max(p1, p2), sel);
    sel = selector ? p2 : p5;
    float b_min = min(min(p3, p4), sel);
    float b_max = max(max(p3, p4), sel);

    float a_cont = a_max - a_min;
    float b_cont = b_max - b_min;

    float cont_ratio = max(a_cont, b_cont) / (min(a_cont, b_cont) + kEps);
    return (1.0 - clamp((cont_ratio - kMinContrastRatio) * kRatioNorm, 0.0, 1.0)) * kContrastBoost;
}

float EvalPoly6(float pxl[6], int phase_int) {
    float y = 0.0;
    for (int i = 0; i < 6; ++i) {
        y += NIS_COEF_SCALE[phase_int * 6 + i] * pxl[i];
    }
    float y_usm = 0.0;
    for (int i = 0; i < 6; ++i) {
        y_usm += NIS_COEF_USM[phase_int * 6 + i] * pxl[i];
    }

    // piece-wise ramp based on luma
    float y_scale = 1.0 - clamp((y - kSharpStartY) * kSharpScaleY, 0.0, 1.0);
    // scale the ramp to sharpen as a function of luma
    float y_sharpness = y_scale * Sharpness.y + Sharpness.x;
    y_usm *= y_sharpness;
    // scale the ramp to limit USM as a function of luma
    float y_sharpness_limit = (y_scale * Sharpness.w + Sharpness.z) * y;
    y_usm = min(y_sharpness_limit, max(-y_sharpness_limit, y_usm));
    // reduce ringing
    y_usm *= CalcLTI(pxl[0], pxl[1], pxl[2], pxl[3], pxl[4], pxl[5], phase_int);

    return y + y_usm;
}

float FilterNormal(float p[36], int phase_x_frac_int, int phase_y_frac_int) {
    float h_acc = 0.0;
    for (int j = 0; j < 6; ++j) {
        float v_acc = 0.0;
        for (int i = 0; i < 6; ++i) {
            v_acc += P(i, j) * NIS_COEF_SCALE[phase_y_frac_int * 6 + i];
        }
        h_acc += v_acc * NIS_COEF_SCALE[phase_x_frac_int * 6 + j];
    }
    return h_acc;
}

float AddDirFilters(float p[36], float phase_x_frac, float phase_y_frac, int phase_x_frac_int, int phase_y_frac_int, vec4 w) {
    float f = 0.0;
    if (w.x > 0.0) {
        // 0 deg filter
        float interp0Deg[6];
        for (int i = 0; i < 6; ++i) {
            interp0Deg[i] = mix(P(i, 2), P(i, 3), phase_x_frac);
        }
        f += EvalPoly6(interp0Deg, phase_y_frac_int) * w.x;
    }
    if (w.y > 0.0) {
        // 90 deg filter
        float interp90Deg[6];
        for (int i = 0; i < 6; ++i) {
            interp90Deg[i] = mix(P(2, i), P(3, i), phase_y_frac);
        }
        f += EvalPoly6(interp90Deg, phase_x_frac_int) * w.y;
    }
    if (w.z > 0.0) {
        // 45 deg filter
        float pphase_b45 = 0.5 + 0.5 * (phase_x_frac - phase_y_frac);

        float temp_interp45Deg[7];
        temp_interp45Deg[1] = mix(P(2, 1), P(1, 2), pphase_b45);
        temp_interp45Deg[3] = mix(P(3, 2), P(2, 3), pphase_b45);
        temp_interp45Deg[5] = mix(P(4, 3), P(3, 4), pphase_b45);
        {
            pphase_b45 = pphase_b45 - 0.5;
            float a = (pphase_b45 >= 0.0) ? P(0, 2) : P(2, 0);
            float b = (pphase_b45 >= 0.0) ? P(1, 3) : P(3, 1);
            float c = (pphase_b45 >= 0.0) ? P(2, 4) : P(4, 2);
            float d = (pphase_b45 >= 0.0) ? P(3, 5) : P(5, 3);
            temp_interp45Deg[0] = mix(P(1, 1), a, abs(pphase_b45));
            temp_interp45Deg[2] = mix(P(2, 2), b, abs(pphase_b45));
            temp_interp45Deg[4] = mix(P(3, 3), c, abs(pphase_b45));
            temp_interp45Deg[6] = mix(P(4, 4), d, abs(pphase_b45));
        }

        float interp45Deg[6];
        float pphase_p45 = phase_x_frac + phase_y_frac;
        if (pphase_p45 >= 1.0) {
            for (int i = 0; i < 6; i++) {
                interp45Deg[i] = temp_interp45Deg[i + 1];
            }
            pphase_p45 = pphase_p45 - 1.0;
        } else {
            for (int i = 0; i < 6; i++) {
                interp45Deg[i] = temp_interp45Deg[i];
            }
        }

        f += EvalPoly6(interp45Deg, int(pphase_p45 * 64.0)) * w.z;
    }
    if (w.w > 0.0) {
        // 135 deg filter
        float pphase_b135 = 0.5 * (phase_x_frac + phase_y_frac);

        float temp_interp135Deg[7];
        temp_interp135Deg[1] = mix(P(3, 1), P(4, 2), pphase_b135);
        temp_interp135Deg[3] = mix(P(2, 2), P(3, 3), pphase_b135);
        temp_interp135Deg[5] = mix(P(1, 3), P(2, 4), pphase_b135);
        {
            pphase_b135 = pphase_b135 - 0.5;
            float a = (pphase_b135 >= 0.0) ? P(5, 2) : P(3, 0);
            float b = (pphase_b135 >= 0.0) ? P(4, 3) : P(2, 1);
            float c = (pphase_b135 >= 0.0) ? P(3, 4) : P(1, 2);
            float d = (pphase_b135 >= 0.0) ? P(2, 5) : P(0, 3);
            temp_interp135Deg[0] = mix(P(4, 1), a, abs(pphase_b135));
            temp_interp135Deg[2] = mix(P(3, 2), b, abs(pphase_b135));
            temp_interp135Deg[4] = mix(P(2, 3), c, abs(pphase_b135));
            temp_interp135Deg[6] = mix(P(1, 4), d, abs(pphase_b135));
        }

        float interp135Deg[6];
        float pphase_p135 = 1.0 + (phase_x_frac - phase_y_frac);
        if (pphase_p135 >= 1.0) {
            for (int i = 0; i < 6; ++i) {
                interp135Deg[i] = temp_interp135Deg[i + 1];
            }
            pphase_p135 = pphase_p135 - 1.0;
        } else {
            for (int i = 0; i < 6; ++i) {
                interp135Deg[i] = temp_interp135Deg[i];
            }
        }

        f += EvalPoly6(interp135Deg, int(pphase_p135 * 64.0)) * w.w;
    }
    return f;
}

void main() {
    ivec2 srcSize = textureSize(InSampler, 0);
    vec2 dst = floor(gl_FragCoord.xy);

    // Source position of this output pixel, integer part and discretized phase (per axis)
    vec2 src = (0.5 + dst) * Scale.xy - 0.5;
    vec2 srcFloor = floor(src);
    vec2 frac = src - srcFloor;
    int fx_int = int(frac.x * float(kPhaseCount));
    int fy_int = int(frac.y * float(kPhaseCount));
    ivec2 origin = ivec2(srcFloor);

    // 6x6 luma support: P(i, j) = luma of source texel (x0 + j - 2, y0 + i - 2)
    float p[36];
    for (int i = 0; i < 6; ++i) {
        for (int j = 0; j < 6; ++j) {
            ivec2 texel = clamp(origin + ivec2(j - 2, i - 2), ivec2(0), srcSize - 1);
            P(i, j) = getY(texelFetch(InSampler, texel, 0).rgb);
        }
    }

    // Edge maps of the 2x2 source texels around the sample, interpolated to the sample position
    vec4 h0 = mix(GetEdgeMap(p, 1, 1), GetEdgeMap(p, 1, 2), frac.x);
    vec4 h1 = mix(GetEdgeMap(p, 2, 1), GetEdgeMap(p, 2, 2), frac.x);
    vec4 w = mix(h0, h1, frac.y);

    // Final luma is a weighted product of directional & normal filters
    float baseWeight = 1.0 - w.x - w.y - w.z - w.w;
    float opY = FilterNormal(p, fx_int, fy_int) * baseWeight;
    opY += AddDirFilters(p, frac.x, frac.y, fx_int, fy_int, w);

    // Bilinear tap for chroma, then move its luma to the filtered luma
    vec4 op = textureLod(InSampler, (src + 0.5) / vec2(srcSize), 0.0);
    float y = getY(op.rgb);
    float corr = opY - y;
    op.rgb += corr;

    fragColor = clamp(op, 0.0, 1.0);
}
