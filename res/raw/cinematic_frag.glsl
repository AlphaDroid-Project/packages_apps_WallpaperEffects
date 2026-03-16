#version 300 es
precision highp float;

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTextureColor;
uniform sampler2D uTextureDepth;
uniform vec2 uOffset;
uniform float uParallaxStrength;
uniform float uVignetteStrength;

const float ZOOM = 1.12;

float smoothDepth(vec2 uv) {
    vec2 ts = 5.0 / vec2(textureSize(uTextureDepth, 0));
    float d  = texture(uTextureDepth, uv).r;
    d += texture(uTextureDepth, clamp(uv + vec2(ts.x, 0.0), 0.0, 1.0)).r;
    d += texture(uTextureDepth, clamp(uv - vec2(ts.x, 0.0), 0.0, 1.0)).r;
    d += texture(uTextureDepth, clamp(uv + vec2(0.0, ts.y), 0.0, 1.0)).r;
    d += texture(uTextureDepth, clamp(uv - vec2(0.0, ts.y), 0.0, 1.0)).r;
    d += texture(uTextureDepth, clamp(uv + vec2(ts.x, ts.y), 0.0, 1.0)).r;
    d += texture(uTextureDepth, clamp(uv - vec2(ts.x, ts.y), 0.0, 1.0)).r;
    d += texture(uTextureDepth, clamp(uv + vec2(ts.x, -ts.y), 0.0, 1.0)).r;
    d += texture(uTextureDepth, clamp(uv - vec2(ts.x, -ts.y), 0.0, 1.0)).r;
    return d / 9.0;
}

void main() {
    float depth = smoothDepth(vTexCoord);

    float softDepth = smoothstep(0.05, 0.85, depth);

    float vertFade = mix(0.45, 1.0, vTexCoord.y);
    float perspCorrect = mix(1.0, vertFade, softDepth * softDepth);

    vec2 displacement = uOffset * softDepth * perspCorrect * uParallaxStrength;

    vec2 baseUV = (vTexCoord - 0.5) / ZOOM + 0.5;
    vec2 uv = clamp(baseUV + displacement, 0.0, 1.0);

    vec3 color = texture(uTextureColor, uv).rgb;

    float depthDim = 0.82 + 0.18 * depth;
    color *= depthDim;

    vec2 vig = vTexCoord - 0.5;
    float vigFactor = 1.0 - dot(vig, vig) * uVignetteStrength;
    color *= clamp(vigFactor, 0.0, 1.0);

    fragColor = vec4(color, 1.0);
}
