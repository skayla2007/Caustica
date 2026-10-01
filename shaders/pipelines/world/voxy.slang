import world_common;
import bindings;

public float4 voxyTexel(TerrainPrim primitive, float2 uv, float lod) {
    uint face = primitive.aux1 & 7u;
    float2 tile = float2(primitive.aux0 & 255u, (primitive.aux0 >> 8) & 255u);
    float2 faceTile = float2(face >> 1, face & 1u);
    // Keep sampling within the face at every mip; adjacent atlas faces have independent content.
    float margin = min(0.5, exp2(lod) / 32.0);
    float2 local = clamp(frac(uv), float2(margin), float2(1.0 - margin));
    int slot = int(primitive.tint.w + 0.5);
    uint width, height;
    entityAlbedoTex[NonUniformResourceIndex(slot)].GetDimensions(width, height);
    float2 atlasUv = (tile * float2(48.0, 32.0) + (faceTile + local) * 16.0) / float2(width, height);
    return entityAlbedoTex[NonUniformResourceIndex(slot)].SampleLevel(atlasUv, lod);
}

public float3 voxyTint(TerrainPrim primitive, float4 texel) {
    uint mode = (primitive.aux1 >> 3) & 3u;
    bool tinted = mode == 2u || (mode == 1u && abs(texel.r - texel.g) < 0.02 && abs(texel.g - texel.b) < 0.02);
    return tinted ? primitive.tint.rgb : float3(1.0);
}
