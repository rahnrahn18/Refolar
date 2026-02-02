#version 450

#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable

layout (binding = 1) uniform sampler2D tex[3]; // Y, U, V
layout (binding = 2) uniform sampler2D depthTex; // Depth / Mask Texture

layout (binding = 0) uniform UniformBufferObject
{
    mat4 rotation;
    mat4 scale;
    float blurStrength;
    int isPortrait;
    int sampleCount;
    float padding;
} ubo;

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

const float GOLDEN_ANGLE = 2.39996323;

void main() {
    vec3 centerColor = yuv2rgb(texcoord);

    if (ubo.isPortrait == 0) {
        uFragColor = vec4(centerColor, 1.0);
        return;
    }

    // Sample depth mask.
    // Assumes 1.0 = Subject (Sharp), 0.0 = Background (Blur)
    float mask = texture(depthTex, texcoord).r;

    // Invert mask for Circle of Confusion (CoC)
    // 1.0 (Subject) -> 0.0 blur. 0.0 (Background) -> 1.0 blur.
    float coc = clamp(1.0 - mask, 0.0, 1.0);

    // Dynamic blur radius based on strength and CoC
    float maxBlurRadius = ubo.blurStrength * 0.005;
    float radius = coc * maxBlurRadius;

    // Optimization: If blur is negligible, return center color
    if (radius < 0.0005) {
        uFragColor = vec4(centerColor, 1.0);
        return;
    }

    vec3 accColor = centerColor;
    float totalWeight = 1.0;

    int samples = ubo.sampleCount;
    if (samples < 4) samples = 4;
    if (samples > 64) samples = 64; // Hard cap for performance safety

    for (int i = 1; i < 64; i++) {
        if (i >= samples) break;

        float theta = float(i) * GOLDEN_ANGLE;
        // Radius distribution: sqrt(i / N) ensures uniform area sampling
        float r = sqrt(float(i) / float(samples)) * radius;

        vec2 offset = vec2(cos(theta), sin(theta)) * r;

        // Note: Ideally correct for aspect ratio here, but ignoring for simplicity
        // as per previous implementation style.

        accColor += yuv2rgb(texcoord + offset);
        totalWeight += 1.0;
    }

    uFragColor = vec4(accColor / totalWeight, 1.0);
}
