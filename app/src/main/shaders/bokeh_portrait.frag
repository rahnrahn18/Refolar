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

// Golden Angle = 2.39996323 radians (~137.5 degrees)
const float GOLDEN_ANGLE = 2.39996323;

void main() {
    vec3 centerColor = yuv2rgb(texcoord);

    if (ubo.isPortrait == 0) {
        uFragColor = vec4(centerColor, 1.0);
        return;
    }

    // Sample depth mask (AI Prediction or Hardware Depth)
    // 1.0 = Subject (Sharp), 0.0 = Background (Blur)
    float mask = texture(depthTex, texcoord).r;

    // Calculate Circle of Confusion (CoC)
    // Mimic lens characteristics: Objects far from focal plane (0.0 mask) have larger CoC.
    float coc = clamp(1.0 - mask, 0.0, 1.0);

    // Dynamic blur radius based on aperture (blurStrength) and CoC
    // Adjusted coefficient for more pronounced but controlled blur
    float maxBlurRadius = ubo.blurStrength * 0.005;
    float radius = coc * maxBlurRadius;

    // Optimization: If blur is negligible, return center color immediately
    // Increased threshold slightly to avoid micro-blur on edges
    if (radius < 0.0008) {
        uFragColor = vec4(centerColor, 1.0);
        return;
    }

    // Accumulate samples for Cinematic Disk Blur (Bokeh)
    // Uses Golden Angle Spiral distribution to avoid banding artifacts and simulate circular aperture.
    // Inspired by "Circular DoF" techniques (though implemented as single-pass stochastic gather here).

    vec3 accColor = centerColor;
    float totalWeight = 1.0;

    int samples = ubo.sampleCount;
    // Clamp sample count to avoid GPU hangs or poor quality
    if (samples < 4) samples = 4;
    if (samples > 64) samples = 64;

    for (int i = 1; i < 64; i++) {
        if (i >= samples) break;

        float theta = float(i) * GOLDEN_ANGLE;
        // Radius distribution: sqrt(i / N) ensures uniform area sampling of the disk
        float r = sqrt(float(i) / float(samples)) * radius;

        vec2 offset = vec2(cos(theta), sin(theta)) * r;

        // Correct aspect ratio would be ideal, but assuming square pixels/isotropic blur for now.
        // Aspect correction: offset.x *= aspect_ratio;

        vec2 sampleUV = texcoord + offset;
        vec3 sampleColor = yuv2rgb(sampleUV);
        float sampleMask = texture(depthTex, sampleUV).r;

        // Depth-aware rejection:
        // If we are in the background (mask < 0.5), we avoid accumulating sharp foreground pixels (mask > 0.8)
        // to prevent "halo" artifacts where the person bleeds into the blurred background.
        float weight = 1.0;
        if (mask < 0.5 && sampleMask > 0.8) {
            weight = 0.05; // Significant suppression
        }

        // Cinematic Bokeh Highlight Boost
        // Boost bright spots to simulate aperture shapes
        float luma = dot(sampleColor, vec3(0.299, 0.587, 0.114));
        if (luma > 0.7) {
            weight *= (1.0 + pow(luma, 4.0) * 2.0);
        }

        accColor += sampleColor * weight;
        totalWeight += weight;
    }

    uFragColor = vec4(accColor / totalWeight, 1.0);
}
