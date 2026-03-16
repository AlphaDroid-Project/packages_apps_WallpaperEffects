#version 300 es
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform sampler2D uTexture;
uniform vec2 uDirection;
uniform float uRadius;
void main() {
    vec2 texelSize = 1.0 / vec2(textureSize(uTexture, 0));
    vec3 result = vec3(0.0);
    float totalWeight = 0.0;
    for(float i = -uRadius; i <= uRadius; i++) {
        vec2 offset = uDirection * i * texelSize;
        float weight = 1.0 - abs(i) / uRadius;
        result += texture(uTexture, vTexCoord + offset).rgb * weight;
        totalWeight += weight;
    }
    fragColor = vec4(result / totalWeight, 1.0);
}
