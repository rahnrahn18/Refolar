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

// Simple Bicubic-like sharpening kernel
vec3 getSharpenedColor(vec2 uv) {
    vec2 size = textureSize(tex[0], 0);
    vec2 step = 1.0 / size;

    vec3 center = yuv2rgb(uv);
    vec3 top = yuv2rgb(uv + vec2(0.0, -step.y));
    vec3 bottom = yuv2rgb(uv + vec2(0.0, step.y));
    vec3 left = yuv2rgb(uv + vec2(-step.x, 0.0));
    vec3 right = yuv2rgb(uv + vec2(step.x, 0.0));

    // Unsharp Mask Logic: Result = Original + Amount * (Original - Blurred)
    // Approx Blurred = (top + bottom + left + right) / 4
    // Simplified Sharpen: 5 * Center - (Top + Bottom + Left + Right)

    vec3 sharpened = 5.0 * center - (top + bottom + left + right);
    return clamp(sharpened, 0.0, 1.0);
}

void main() {
    // Apply sharpening to enhance perceived resolution
    vec3 color = getSharpenedColor(texcoord);
    uFragColor = vec4(color, 1.0);
}
