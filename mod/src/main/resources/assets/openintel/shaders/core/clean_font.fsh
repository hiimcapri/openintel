#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

float coverageAt(vec2 uv, vec2 dx, vec2 dy) {
#ifdef FONT_INTENSITY
    return textureGrad(Sampler0, uv, dx, dy).r;
#else
    return textureGrad(Sampler0, uv, dx, dy).a;
#endif
}

void main() {
    vec2 dx = dFdx(texCoord0);
    vec2 dy = dFdy(texCoord0);
    float coverage = (
        coverageAt(texCoord0 + 0.125 * (-dx - dy), dx, dy) +
        coverageAt(texCoord0 + 0.125 * ( dx - dy), dx, dy) +
        coverageAt(texCoord0 + 0.125 * (-dx + dy), dx, dy) +
        coverageAt(texCoord0 + 0.125 * ( dx + dy), dx, dy)
    ) * 0.25;
    vec4 color = vertexColor * ColorModulator;
    color.a *= coverage;
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color;
}
