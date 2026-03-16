#version 300 es
precision highp float;

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTexture;
uniform vec2 uResolution;
uniform float uBandCount;
uniform float uAmplitude;
uniform float uVerticalRipple;
uniform float uBrightnessFactor;

const float PI = 3.14159265359;

void main() {
    vec2 uv = vTexCoord;
    float x = uv.x * uResolution.x;
    float y = uv.y * uResolution.y;

    float bandWidth = uResolution.x / uBandCount;
    float bandIndex = floor(x / bandWidth);
    float bandCenter = bandIndex * bandWidth + bandWidth * 0.5;
    float distFromCenter = (x - bandCenter) / bandWidth;

    float symmetricWave = sin(distFromCenter * PI * 2.0) * 0.8
                        + 0.25 * sin(distFromCenter * PI * 4.0);

    float normalizedY = y / uResolution.y;
    float verticalOffset = sin(normalizedY * PI * 4.0) * uVerticalRipple;

    float displacementX = symmetricWave * uAmplitude;
    float displacementY = verticalOffset;

    vec2 srcUV = vec2(
        clamp(uv.x + displacementX / uResolution.x, 0.0, 1.0),
        clamp(uv.y + displacementY / uResolution.y, 0.0, 1.0)
    );

    vec3 color = texture(uTexture, srcUV).rgb;

    float light = 1.0 + uBrightnessFactor * symmetricWave;
    color *= light;

    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
