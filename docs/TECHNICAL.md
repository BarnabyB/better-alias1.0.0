# Better Alias - technical notes

How each part of the mod works, for contributors. Paths are relative to `src/main/java/betteralias` and
`src/main/resources/assets/better-alias`.


Anti-aliasing (FXAA 3.11, SMAA 1x, SMAA T2x, a temporal SMAA 4x, CMAA2, TAA with AMD CAS sharpening, and SSAA), plus optional
AMD RCAS sharpening, AMD FSR 1 / NVIDIA NIS upscaling, an edge-detection debug view and a GPU-time readout, for Minecraft
26.2 on Fabric. Settings are available from
Mod Menu (YACL screen) and, when Sodium is installed, from a dedicated "Better Alias" section in Sodium's Video Settings.

## How it works

| Piece | File | Role |
| --- | --- | --- |
| Render hook | `mixin/GameRendererMixin` | Injects into `GameRenderer.render` right after the world, hand and entity outlines are drawn and before vanilla's spectator post effect and the GUI. For the temporal modes (TAA, SMAA T2x/4x) it also jitters the level projection and resolves before the hand (see below). |
| TAA | `BetterAliasTaa` | Camera jitter, history textures and the TAA + CAS render passes (drives blaze3d directly). |
| RCAS | `BetterAliasRcas` | AMD FSR 1 RCAS sharpening on the finished image after any method except TAA; strength from the RCAS Sharpening slider (drives blaze3d directly). |
| SSAA / upscaling | `BetterAliasRenderScale` + `mixin/RenderTargetAccessor` | Swaps a larger (SSAA) or smaller (upscaling) target into the main render target for the duration of `renderLevel`, then averages it down or upscales it with FSR 1 EASU / NIS (drives blaze3d directly). |
| GPU timer | `BetterAliasGpuTimer` | Timestamp queries around Better Alias's own passes, read back a few frames later. |
| F3 line | `BetterAliasDebugScreen` | Registers the `better-alias:performance` debug-screen entry and syncs it with the Performance Readout setting. |
| SMAA T2x / 4x | `BetterAliasSmaaTemporal` | Camera jitter, the SMAA passes with per-frame subsample indices, a ring of recent frames and the temporal resolve (drives blaze3d directly). |
| Renderer | `BetterAliasRenderer` | Looks up the selected post chain from `ShaderManager` and runs it on the main render target. Pauses while an Iris shader pack is active unless "Keep With Shader Packs" is on (then shows a one-time toast per session). |
| Settings | `BetterAliasConfig` | The mode enum, the shader-pack override, getters/setters and `config/better-alias.json` (written on Apply/Save, loaded at startup). No UI-library imports. |
| Mod Menu UI | `BetterAliasModMenu` + `BetterAliasConfigScreen` | YACL screen. |
| Sodium UI | `compat/SodiumOptionsPage` | Sodium config API page, registered via the `sodium:config_api_user` entrypoint in `fabric.mod.json`. |
| Iris | `compat/IrisCompat` | Reflection-only check of `IrisApi.isShaderPackInUse()`, so Iris isn't a build dependency. |
| Effects | `post_effect/*.json`, `shaders/post/*.fsh` | Vanilla post-chain format; each chain writes to an internal target, then blits back to `minecraft:main`. |

### CMAA2
`post_effect/cmaa2.json` is a fragment-shader port of Intel's CMAA2 reference (github.com/GameTechDev/CMAA2,
Apache-2.0), HIGH preset. The reference is compute-only (it scatters blend results to other pixels with atomics),
which post chains can't do, so it is split into three passes that gather instead:

1. `cmaa2_edges` - edge detection with CMAA2's luma, threshold and local contrast adaptation (edges texture: r/g/b/a = right/bottom/left/top).
2. `cmaa2_shapes` - finds the corner pixels that are the centre of a stair step ("Z-shape") and measures how far its line runs.
3. `cmaa2_apply` - each edge pixel blends its own corner ("simple shape") sample plus one sample per Z-shape whose line covers it, weighted 0.8/1.8 as in the reference.

Quality presets only change the edge threshold (LOW 0.15, MEDIUM 0.10, HIGH 0.07, ULTRA 0.05). Post-chain uniforms are
fixed per JSON, so each preset is its own post effect (`cmaa2_low`, `cmaa2_medium`, `cmaa2` = high, `cmaa2_ultra`)
passing `Cmaa2Config.EdgeThreshold` to `cmaa2_edges.fsh`; `BetterAliasConfig.activePostEffect()` picks the right one.

### SMAA T2x and SMAA 4x (temporal)
SMAA T2x (Jimenez et al. 2012) is SMAA 1x plus a two-position sub-pixel camera jitter. Each frame:

1. The level projection is offset by that frame's sample position (same hook and maths as TAA).
2. Before the hand, `BetterAliasSmaaTemporal` runs the normal `smaa_edges` / `smaa_weights` / `smaa_blend` shaders,
   passing the frame's **subsample indices** to `smaa_weights.fsh` (`SmaaConfig.SubsampleIndices`), which pick the
   matching sub-texture of the 160x560 area texture. SMAA 1x passes zeros from `post_effect/smaa.json`.
3. The result goes into a ring of the last 2 (T2x) or 4 (4x) frames, and `smaa_temporal_resolve.fsh` averages this
   frame with the older ones in linear light. Older frames are reprojected from depth and camera movement (vanilla has
   no motion vectors) and clipped to the current 3x3 colour range, so moving mobs and disocclusions fall back to the
   current frame instead of ghosting. Nothing is accumulated beyond the ring, so it stays as sharp as SMAA 1x.

Real SMAA 4x is T2x plus S2x, which needs a 2x MSAA render target; blaze3d 26.2 can't create multisampled textures and
Sodium/Iris draw into the single-sample main target. So the "SMAA 4x (Temporal)" mode uses SMAA 4x's four sample
positions and their subsample indices, one per frame, and averages the last four frames. The index sign convention
was checked against 16x16 supersampled test scenes (the opposite signs roughly halve the improvement). Like TAA, both
modes never run with an Iris shader pack.

### SSAA
Supersampling renders the world at 1.25x, 1.5x, 1.75x, 2x, 2.5x, 3x or 4x the window's width and height (SSAA Scale;
about 1.6 to 16 samples per pixel) and averages it down:

1. `renderLevel` HEAD (`GameRendererMixin`): `BetterAliasRenderScale.beginLevel` points the main `RenderTarget`'s colour/depth
   textures, width and height at a larger target. Everything that draws the world reads that same object - the level
   renderer's frame graph (`importExternal("main", ...)`, whose Fabulous transparency targets are sized from it), the
   sky renderer (which keeps a reference from startup), Sodium's `TerrainRenderPass`, the hand's depth clear - so the
   whole world and the hand render at the higher resolution with no further hooks.
2. `renderLevel` RETURN: the real textures are put back and `ssaa_downsample.fsh` writes the area-weighted average of
   each pixel's footprint (exact 2x2/3x3/4x4 box for whole-number scales) in linear light into the main target. The
   GUI is drawn afterwards at native resolution.

The render size is clamped to the GPU's max texture size (keeping the aspect ratio). If allocation runs out of video
memory, smaller scales are tried with a toast. The line shader sizes lines from `ScreenSize` (the window), so block
outlines keep their on-screen width. Like the temporal modes, SSAA never runs with an Iris shader pack.

### Upscaling (FSR 1, NIS)
Upscaling uses the same target swap as SSAA, the other way round: the world is drawn at the Render Scale (1/1.3, 1/1.5,
1/1.7 or 1/2 of each axis). At `renderLevel` RETURN, while the small target is still swapped in, the selected
post-processing method (FXAA, SMAA, CMAA2) runs on it, so it smooths real pixels instead of upscaled ones; the
temporal modes already resolved before the hand. Then the textures are put back and one pass upscales into the main
target:

- **FSR 1:** `fsr_easu.fsh` is `FsrEasuF` (MIT), 32-bit path. GLSL 3.30 has no `textureGather`, so the 12 taps are
  read with `texelFetch`; `con0` is computed in `BetterAliasRenderScale.easuCon0`. RCAS then runs as usual from the
  RCAS Sharpening slider, completing FSR 1.
- **NIS:** `nis_scaler.fsh` is `NVScaler` from the NVIDIA Image Scaling SDK 1.0.3 (MIT) as a fragment shader: the 6x6
  luma window is a flattened `float[36]`, the 64-phase scaler and USM coefficient tables are `const` arrays, and the
  sharpness-derived constants come from `NVScalerUpdateConfig`, computed in `BetterAliasRenderScale.nisSharpness`
  (SDR path). NIS sharpens itself, so RCAS is skipped with it.

The `postEffectApplied` flag tells `BetterAliasRenderer` not to run the method a second time at full size.

### Debug view and performance readout
**Show Detected Edges** swaps the method's chain for `post_effect/debug/<method>_edges.json`: the method's own edge
pass (SMAA edges, CMAA2 edges at the selected quality's threshold, or `fxaa_edges.fsh`, FXAA 3.11's early-exit test),
then `debug_edges.fsh` dims the frame and paints the edges magenta. It isn't saved.

`BetterAliasGpuTimer` wraps each group of Better Alias passes (temporal resolve, SSAA/upscale pass, post chain + RCAS)
in a pair of timestamps from a `GpuQueryPool` sized for 4 frames x 8 groups. Each frame is read back 3 frames later
(never stalling), converted with `DeviceInfo.timestampPeriod()` and smoothed. `BetterAliasDebugScreen` adds the line
to F3 via `DebugScreenEntries.register` and keeps it in step with Performance Readout both ways (Off = never, In F3
= in overlay, Always = always on): a change to the setting sets the entry's status (which saves F3's options as a
custom profile), and a change made in F3's debug options screen, or picking an F3 preset, updates and saves the
setting. Rendering the world at another size
happens inside vanilla's passes and isn't timed.

### RCAS sharpening
`rcas.fsh` is AMD FidelityFX RCAS from FSR 1 (`FsrRcasF`, MIT), 32-bit path with alpha passthrough and without the
optional denoise. `BetterAliasRenderer` runs it last, after the post-processing method (or after SSAA / SMAA T2x/4x /
FSR 1 upscaling, which finish earlier), so it sharpens the world and hand but not the GUI. The slider maps linearly to `FsrRcasCon`'s
scale: 100% = 1.0 (FSR's maximum, 0 stops), 50% = 0.5 (1 stop); 0% skips the pass. Because the amount changes at
runtime it can't be a post-chain JSON (their uniforms are fixed), so the frame is copied to a scratch texture and
sharpened back into the main target. Not used with TAA, which already sharpens with CAS. With an Iris shader pack it
follows "Keep With Shader Packs", like the post-processing methods. The only change from the reference: the limiter
reciprocals are clamped away from zero, since the reference relies on IEEE infinities for all-black/all-white rings.

### FXAA
`fxaa.fsh` is NVIDIA FXAA 3.11 (BSD licence), PC quality path, preset 39, default subpix/threshold settings (tunable
`#define`s at the top). It needs bilinear filtering, hence `"bilinear": true` on its input in `post_effect/fxaa.json`.

### TAA + CAS
TAA can't be a JSON post chain: it needs per-frame camera matrices, a sub-pixel jitter and a history image, so
`BetterAliasTaa` builds its own two render pipelines and runs them through blaze3d:

1. `renderLevel` start: `new Matrix4f(cameraState.projectionMatrix)` is offset by a Halton(2,3) sub-pixel jitter
   (MixinExtras `@ModifyExpressionValue`). Bobbing/nausea multiply on the right, and it's the matrix Sodium captures for
   terrain, so the whole world is jittered consistently.
2. `renderLevel` at `profiler.popPush("hand")` (depth still holds the world): `taa_resolve.fsh` rebuilds each pixel's
   camera-relative position from depth (reversed-Z; clip range from `DeviceInfo.isZZeroToOne()`), reprojects it with
   last frame's camera, samples the RGBA16F linear history with Catmull-Rom, clips it to the current 3x3 neighbourhood
   (YCoCg variance clipping) and blends 10% of the new frame in. `cas.fsh` (AMD FidelityFX CAS, MIT) then sharpens the
   result back into the main target. The hand and screen overlays are drawn afterwards, so they never ghost.

History resets on resize, camera jumps over 32 blocks, or gaps in world rendering (the same rules apply to SMAA T2x/4x).
TAA never runs with an Iris shader pack (the pack's own jitter would fight ours). No motion vectors exist in vanilla, so moving mobs rely on clipping.

Sodium replaces terrain rendering inside `LevelRenderer`; it does not touch the part of `GameRenderer.render` we hook,
so the pass survives with Sodium installed. Post chains go through blaze3d, so they run on both the OpenGL backend
(needed for Iris) and the Vulkan backend.

### Adding a new mode
1. Add `post_effect/<name>.json` and its shaders (sampler `"sampler_name": "Foo"` is bound as `FooSampler`).
2. Add an entry to `BetterAliasConfig.AntiAliasMode` pointing at `better-alias:<name>` (`changesWorldRendering` = false;
   modes that change how the world is drawn are rendered by their own class instead of a post chain).
3. Add `better-alias.mode.<name>` and `better-alias.options.mode.tooltip.<name>` to `lang/en_us.json`.

Both settings UIs pick up new enum values automatically.

## Building

`./gradlew build` produces `build/libs/better-alias-<version>.jar`. Sodium is compiled against
`libs/sodium-fabric-0.9.2+mc26.2.jar` (`compileOnly`) and also loaded in the dev client (`runtimeOnly`); comment out
the `runtimeOnly` line in `build.gradle` to test without Sodium.
