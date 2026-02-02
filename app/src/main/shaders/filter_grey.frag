#version 450

#extension GL_ARB_separate_shader_objects : enable
#extension GL_ARB_shading_language_420pack : enable

layout (binding = 1) uniform sampler2D tex[3];
layout (location = 0) in vec2 texcoord;
layout (location = 0) out vec4 uFragColor;

void main() {
    float y = texture(tex[0], texcoord).r;
    float u = texture(tex[1], texcoord).r - 0.5;
    float v = texture(tex[2], texcoord).r - 0.5;
    float r = y + 1.403 * v;
    float g = y - 0.344 * u - 0.714 * v;
    float b = y + 1.770 * u;

    // Grayscale
    float gray = 0.299 * r + 0.587 * g + 0.114 * b;

    uFragColor = vec4(gray, gray, gray, 1.0);
}
