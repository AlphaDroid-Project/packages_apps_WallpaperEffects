#version 300 es
precision highp float;

in vec2 vTexCoord;
out vec4 fragColor;

uniform sampler2D uTextureSharp;
uniform sampler2D uTextureBlur;

#define MAX_BLOBS 16
uniform vec3 uBlobColors[MAX_BLOBS];
uniform vec2 uBlobPositions[MAX_BLOBS];
uniform float uBlobSizes[MAX_BLOBS];
uniform int uBlobCount;

uniform float uAspectRatio;
uniform float uBlurStrength;
uniform float uDimLevel;
uniform float uEnableNoise;
uniform float uNoiseScale;
uniform float uNoiseStrength;

float random(vec2 st) {
    return fract(sin(dot(st, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    float t = clamp(uBlurStrength, 0.0, 1.0);
    vec2 uv = vTexCoord;
    vec2 aspectUV = vec2(uv.x * uAspectRatio, uv.y);

    // Phase 1: Muddy background from all blobs (wide falloff)
    vec3 cloudSum = vec3(0.0);
    float cloudWeight = 0.0;
    for (int i = 0; i < uBlobCount; i++) {
        vec2 blobAspect = vec2(uBlobPositions[i].x * uAspectRatio, uBlobPositions[i].y);
        float dist = distance(aspectUV, blobAspect);
        float falloff = 1.0 / (1.0 + pow(dist * 3.0, 2.0));
        cloudSum += uBlobColors[i] * falloff;
        cloudWeight += falloff;
    }
    vec3 muddyBg = cloudWeight > 0.0 ? cloudSum / cloudWeight : vec3(0.0);

    // Phase 2: Base background composition
    vec3 sharp = texture(uTextureSharp, uv).rgb;
    vec3 frosted = texture(uTextureBlur, uv).rgb;

    float blurPhase = smoothstep(0.0, 0.2, t);
    vec3 baseBg = mix(sharp, frosted, blurPhase);

    float cloudPhase = smoothstep(0.18, 0.5, t);
    baseBg = mix(baseBg, muddyBg, cloudPhase);

    // Phase 3: Blob layering
    float blobVisibility = smoothstep(0.15, 0.3, t);
    vec3 finalColor = baseBg;
    for (int i = 0; i < uBlobCount; i++) {
        vec2 blobAspect = vec2(uBlobPositions[i].x * uAspectRatio, uBlobPositions[i].y);
        float dist = distance(aspectUV, blobAspect);
        float blobRadius = uBlobSizes[i];
        float blobAlpha = smoothstep(blobRadius, 0.0, dist) * blobVisibility;
        finalColor = mix(finalColor, uBlobColors[i], blobAlpha * 0.6);
    }

    // Phase 4: Post-processing
    finalColor = mix(finalColor, vec3(0.0), uDimLevel * t);

    if (uEnableNoise > 0.5) {
        vec2 grainUV = floor(uv * uNoiseScale);
        float noise = random(grainUV);
        float noiseVis = smoothstep(0.4, 1.0, t);
        finalColor += vec3(noise * uNoiseStrength * noiseVis);
    }

    fragColor = vec4(finalColor, 1.0);
}
