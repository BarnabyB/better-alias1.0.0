# Better Alias

Anti-aliasing for Minecraft 26.2 (Fabric). Pick from seven methods, from cheap edge smoothing to full supersampling,
with optional AMD RCAS sharpening and FSR 1 / NIS upscaling. Works with Sodium, and pauses automatically when an Iris
shader pack is on.

## Methods

| Method | What it does | Cost |
| --- | --- | --- |
| FXAA | NVIDIA FXAA 3.11. Smooths edges in one pass; slightly softens fine detail. | Lowest |
| SMAA 1x | Finds the shape of each edge and blends along it. Sharper than FXAA. | Low |
| SMAA T2x | SMAA 1x plus a two-position camera jitter, blending the last 2 frames. Less shimmer. | Low |
| SMAA 4x (Temporal) | Blends 4 jittered SMAA frames for 4 samples per pixel. | Low |
| CMAA2 | Intel CMAA2. Fixes only clear stair-step edges, so text and textures stay crisp. Quality: Low to Ultra. | Low |
| TAA | Temporal anti-aliasing with AMD CAS sharpening. Smooths everything, including shimmering textures. | Medium |
| SSAA | Renders the world at 1.25x to 4x resolution and scales it down. The cleanest image. | High to extreme |

**RCAS Sharpening** (0-100%) adds AMD FSR 1's sharpening after any method except TAA, which has its own.

## Upscaling

**Upscaler** renders the world below your screen's resolution and scales it back up, for more FPS. It works together
with any method except SSAA (the method runs on the smaller image first).

| Upscaler | What it does |
| --- | --- |
| FSR 1 | AMD FidelityFX Super Resolution 1: edge-aware upscaling (EASU), then RCAS Sharpening. The cleaner of the two. |
| NIS | NVIDIA Image Scaling: upscaling and sharpening in one pass, with its own sharpness slider. Sharper, can ring. |

**Render Scale:** Ultra Quality (77%), Quality (67%), Balanced (59%) or Performance (50%) of the window's width and
height. The GUI always stays at full resolution.

## Debug tools

- **Show Detected Edges** dims the image and highlights the edges FXAA, SMAA or CMAA2 would smooth, in magenta. Handy
  for comparing methods and CMAA2 quality levels. Turns itself off when you restart the game.
- **Performance Readout** adds a line to the F3 screen (or always on screen) with the method, the resolution the world
  is drawn at and the GPU time of Better Alias's own passes. It can also be changed in F3's debug options
  ("performance [better-alias]"); the two stay in sync. The time doesn't include rendering the world at a different resolution for SSAA or
  upscaling; that cost shows up in your FPS instead.

Real SMAA 4x also needs MSAA, which Minecraft's renderer doesn't have, so the 4x mode gathers its four samples over
time instead. TAA and SMAA T2x/4x don't smooth your hand or held item.

## Settings

- **Mod Menu:** Mods → Better Alias → configure (needs YACL).
- **Sodium:** Video Settings → Better Alias.

Options that don't apply to the selected method are greyed out. Settings are saved to `config/better-alias.json`.

## Shader packs (Iris)

Most shader packs have their own anti-aliasing, so Better Alias pauses while one is on. **Keep With Shader Packs**
lets FXAA, SMAA 1x, CMAA2 and RCAS run on top of a pack anyway. TAA, SMAA T2x/4x, SSAA and upscaling always turn
off with a shader pack, because they change how the world itself is rendered.

## Requirements

- Minecraft 26.2, Fabric Loader 0.19.3+, Java 25
- [Fabric API](https://modrinth.com/mod/fabric-api) and [YACL](https://modrinth.com/mod/yacl)
- Optional: [Mod Menu](https://modrinth.com/mod/modmenu), [Sodium](https://modrinth.com/mod/sodium), [Iris](https://modrinth.com/mod/iris)

## Credits

Better Alias builds on these open-source projects (full licence texts in `THIRD_PARTY_NOTICES.md`):

- **SMAA** by Jorge Jimenez, Jose I. Echevarria, Belen Masia, Fernando Navarro and Diego Gutierrez (MIT), via
  **SMAA-MC** by Luracasmus (MIT)
- **FXAA 3.11** by Timothy Lottes, NVIDIA (BSD 3-Clause)
- **CMAA2** by Intel (Apache 2.0)
- **FidelityFX CAS** and **FidelityFX FSR 1 (EASU and RCAS)** by AMD (MIT)
- **NVIDIA Image Scaling (NIS)** by NVIDIA (MIT)

TAA follows Brian Karis ("High Quality Temporal Supersampling", 2014), Marco Salvi's variance clipping and Playdead's
TAA for INSIDE. How everything works is described in [docs/TECHNICAL.md](docs/TECHNICAL.md).

## Licence

MIT, see `LICENSE`. Third-party code keeps its own licence (see `THIRD_PARTY_NOTICES.md`); both files ship inside
the mod jar.
