#version 450

#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable

layout (binding = 1) uniform sampler2D tex[3];
layout (location = 0) in vec2 texcoord;
layout (location = 0) out vec4 uFragColor;

vec3 yuv2rgb(vec2 uv_coord) {
    float y = texture(tex[0], uv_coord).r;
    float u = texture(tex[1], uv_coord).r - 0.5;
    float v = texture(tex[2], uv_coord).r - 0.5;
    float r = y + 1.403 * v;
    float g = y - 0.344 * u - 0.714 * v;
    float b = y + 1.770 * u;
    return clamp(vec3(r, g, b), 0.0, 1.0);
}

// Check if a pixel is within human skin tone range in YCrCb space (approximated in RGB)
bool isSkin(vec3 color) {
    float r = color.r;
    float g = color.g;
    float b = color.b;
    return (r > 0.37 && g > 0.15 && b > 0.07 &&
            r > g && r > b &&
            (r - min(g, b)) > 0.05 &&
            abs(r - g) > 0.05);
}

// Simple Bilateral Filter for Skin Smoothing
vec3 bilateralFilter(vec2 uv) {
    vec3 centerColor = yuv2rgb(uv);

    // If not skin, return original detail immediately (preserves hair, eyes, background)
    if (!isSkin(centerColor)) {
        return centerColor;
    }

    float sigmaSpace = 0.004; // Spatial variance
    float sigmaColor = 0.15;  // Color variance tolerance

    vec3 numerator = vec3(0.0);
    float denominator = 0.0;

    vec2 size = textureSize(tex[0], 0);
    vec2 step = 1.0 / size;

    // 5x5 Kernel
    for (int i = -2; i <= 2; i++) {
        for (int j = -2; j <= 2; j++) {
            vec2 offset = vec2(float(i), float(j)) * step;
            vec3 neighborColor = yuv2rgb(uv + offset);

            // Spatial Weight (Gaussian)
            float dist2 = dot(offset, offset);
            float wSpace = exp(-dist2 / (2.0 * sigmaSpace * sigmaSpace));

            // Color Weight (Gaussian intensity difference)
            vec3 diff = neighborColor - centerColor;
            float colorDist2 = dot(diff, diff);
            float wColor = exp(-colorDist2 / (2.0 * sigmaColor * sigmaColor));

            float weight = wSpace * wColor;

            numerator += neighborColor * weight;
            denominator += weight;
        }
    }

    vec3 smoothed = numerator / denominator;

    // Mix original back in slightly to keep some texture (avoid "plastic" look)
    return mix(centerColor, smoothed, 0.7);
}

void main() {
    vec3 color = bilateralFilter(texcoord);
    uFragColor = vec4(color, 1.0);
}
