#version 450

#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable

layout (binding = 1) uniform sampler2D tex[3]; // Y, U, V
layout (location = 0) in vec2 texcoord;
layout (location = 0) out vec4 uFragColor;

// Match the updated C++ struct
layout (binding = 0) uniform UniformBufferObject
{
    mat4 rotation;
    mat4 scale;
    float blurStrength;
    int isPortrait;
    vec2 padding;
} ubo;

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

    // Vignette-based depth simulation
    // Center of screen (0.5, 0.5) is sharp. Edges are blurred.
    float dist = distance(texcoord, vec2(0.5, 0.5));
    float coc = smoothstep(0.15, 0.55, dist); // Start blurring at 0.15, max at 0.55

    float maxBlurRadius = ubo.blurStrength * 0.003; // Scale factor
    float radius = coc * maxBlurRadius;

    if (radius < 0.0005) {
        uFragColor = vec4(centerColor, 1.0);
        return;
    }

    vec3 accColor = centerColor;
    float totalWeight = 1.0;

    float angle = 0.0;

    // 16 samples for performance balance
    for (int i = 1; i <= 16; i++) {
        angle += GOLDEN_ANGLE;
        float r = radius * sqrt(float(i) / 16.0);
        vec2 offset = vec2(cos(angle), sin(angle)) * r;

        // Correct aspect ratio for circular bokeh (assuming landscape/portrait handling in matrix?)
        // The texture coordinates are already transformed by ubo.scale/rotation in vertex shader.
        // But here we are adding offset in UV space.
        // Without knowing exact aspect ratio in frag shader, circles might look oval.
        // We accept this for now.

        accColor += yuv2rgb(texcoord + offset);
        totalWeight += 1.0;
    }

    uFragColor = vec4(accColor / totalWeight, 1.0);
}
