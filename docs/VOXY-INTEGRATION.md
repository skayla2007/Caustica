# Voxy LOD integration for Minecraft 26.2

This branch contains the 10000-block ray-distance version of the integration.
Use the matching [Voxy branch](https://github.com/skayla2007/voxy/tree/codex/lodfix6-10000).
The runtime bridge requires `VoxyLodApi.VERSION == 5`; older Voxy integration
builds are incompatible. Caustica continues to support real chunks without Voxy.

## Responsibility and rendering

Voxy owns stored LOD data, mesh generation, shape, distance selection and projected
pixel detail. Caustica consumes its exported meshes, models, colours and texture
atlas, creates ray-tracing acceleration structures and combines LODs with the
real-chunk scene. Caustica does not generate LOD terrain or replace Voxy's detail rule.

`RtVoxy` adapts the external API and manages resource publication.
`RtVoxyMesh` decodes the exported quads, and `RtVoxyAttributes` packs their
material/UV attributes. Provider selection and consumer-ready coverage are
computed on separate background workers. The render thread publishes complete
results; it does not queue every intermediate camera position.

The bridge preserves a previously complete fine branch until replacement coverage
is ready, including empty leaves. CPU cache eviction can reuse an immutable mesh
still held by the consumer. GPU preparation and in-flight construction have work
and memory budgets; unused warm resources are retained only within available
headroom. These budgets do not cap the final number of active LODs or their detail.

Static terrain TLASes are reused when unchanged. Stable instance slots permit
UPDATE for small changes; larger changes and periodic refreshes use BUILD.
Masked padding references live BLASes so updates retain valid active-instance
status. Entities use a separate acceleration structure.

## Distances

Camera, indirect, reflection/refraction guide and celestial shadow rays have a
10000-block maximum segment length in the world shaders. It is a compiled value,
not a `caustica.toml` option. This is distance along a ray, not a horizontal radius.
The 30000-block experiment is not part of this branch.

Voxy independently controls the data selection radius through
`config/voxy-config.json`:

```json
"section_render_distance": 32.0,
"sub_division_size": 28.0
```

These are fields in the existing JSON object. The integration converts
`section_render_distance` to a horizontal radius by multiplying it by 512 blocks.
Thus 32 supplies a 16384-block radius, while Caustica rays still end at 10000.
`sub_division_size` controls projected pixel detail; keep it consistent when
comparing standalone Voxy and ray-traced results. Only already available LOD
data can be displayed.

## Build on Windows

Install JDK 25, CMake and a C++ compiler compatible with the NGX shim. Set
`JAVA_HOME`, `VULKAN_SDK` and `DLSS_SDK` to your local installations. Shader
compilation needs `slangc` and `spirv-val`; the build searches the Vulkan SDK's
`Bin` directory and then PATH. Obtain NVIDIA SDK dependencies separately under
their existing license terms; see [third-party notices](../THIRD_PARTY_NOTICES.md).

Clone both repositories side by side, naming their directories `Caustica` and
`voxy`, and check out `codex/lodfix6-10000` in both. Run from their parent directory:

```powershell
./voxy/gradlew.bat -p voxy build --console=plain
cmake -S Caustica/native/ngx_shim -B Caustica/build/native/ngx_shim-cmake -DCMAKE_BUILD_TYPE=Release
cmake --build Caustica/build/native/ngx_shim-cmake --config Release
./voxy/gradlew.bat -p Caustica build -PngxVendorConfig=rel --console=plain
```

Voxy must be built first because Caustica compiles against its JAR in
`../voxy/build/libs`. Keep only one matching non-sources Voxy JAR there to avoid
ambiguous provider versions. CMake writes the shim to
`Caustica/build/native/ngx_shim/release`. Gradle bundles it with the DLSS release
runtime libraries and compiles/validates the shader modules. Output mod JARs are
in each project's `build/libs`; do not install `*-sources.jar` files.

Install the matching pair, Fabric Loader and Fabric API for Minecraft 26.2, and
launch with the Vulkan graphics backend. For a development client with both mods:

```powershell
./voxy/gradlew.bat -p Caustica runClient -PvoxyRuntime -PngxVendorConfig=rel --console=plain --args="--graphicsBackend vulkan"
```

## Validation and limitations

The integration has 70 Caustica and 24 Voxy automated tests covering mesh decoding,
attributes, hierarchy coverage, worker coalescing, cache retention, build budgets
and TLAS layout/state. Shader compilation validates 27 SPIR-V modules.

Independent-copy motion tests on an RTX 5070 Laptop 8 GB measured approximately
69/44/52 FPS for stationary/turning/moving natural-map high-altitude views and
55/12/7 FPS for a dense Greenfield city high-altitude view. These used a 2560x1570
display, 853x523 tracing, DLSS RR Ultra Performance, 1 spp, 2 bounces, SPBR-21,
Voxy detail 28, distance setting 32 and real-chunk distance 2. Motion was driven by
development-only game APIs; it was not a keyboard walking benchmark. The fixed
low-altitude diagnostic entered terrain and is excluded from performance claims.

Large dense cities still have severe motion/loading stalls. A measured city scene
used about 7852 MiB of local GPU memory, above the driver-reported budget near
6850 MiB. GPU building, resource turnover and memory pressure require further
profiling; CPU command recording times are not GPU execution timings. The local
test environment also has an existing native error when fully closing the game
after successful saving/disconnection. These results do not establish identical
performance or a clean native shutdown on every mod combination.

Enable `-Dcaustica.voxy.profile=true` for selection/build/memory logs and
`-Dcaustica.rt.frameStats=true` for CPU frame statistics. Benchmark worlds, logs,
test drivers, SDK directories and local planning files are not repository inputs.
