# TacLight

**English** | [中文](README.md)

A Minecraft mod that adds lights which actually cast cone-shaped light.

TacLight makes flashlights and gun-mounted lights cast a real cone: a defined edge, distance falloff, visible beams in fog and dust, and no leaking through walls. The lights are items in the game, so they move with your hand and with your gun.

When used with an Iris/Oculus shader pack, the light enters the pack's own lighting and volumetric pipeline, so it matches the look of the pack instead of being dragged down by the vanilla light model.

**Current version 0.11.5 (preview).** The core works; the look is still being polished.

## Features

- Handheld flashlight: a real light cone with adjustable inner and outer angle, range, intensity and falloff.
- Gun-mounted light attachment: fits TaCZ guns, emits from the muzzle, follows the gun's direction and third-person pose.
- Volumetric light: visible beams in fog, dust and water.
- Occlusion: no light leaking through walls. A voxel grid decides occlusion, and it errs on the conservative side (blocking too much rather than leaking).
- Multiplayer: other players see your light. The on/off state syncs with the entity, and remote light poses are rebuilt on the receiving client. No extra network packets.
- Shader pack compatibility: lights are injected into the pack you already have at runtime. The mod does not redistribute modified shader packs.

## Tested with

This is the version combination the release was tested against. It is not a hard limit. Other versions will most likely work, they just were not tested. If you hit a problem, go back to this combination first.

| Component | Version | Where | Role |
|---|---|---|---|
| Minecraft Forge | 1.20.1-47.1.3 | launcher | Platform, client and server |
| TacLight | 0.11.5 | `mods/` | This mod |
| Oculus | 1.8.0 | `mods/` | The shader pipeline; the light cone needs it |
| Embeddium | 0.3.31 | `mods/` | Frame rate |
| Shader pack: Complementary Reimagined | r5.9 | `shaderpacks/` | What makes the cone visible; the main supported pack |
| Shader pack: iterationT | 3.2.0 | `shaderpacks/` | A second supported template, pick either this or Complementary |
| Timeless and Classics Zero (TaCZ) | 1.1.8-hotfix | `mods/` | Gun light integration |
| Player Animator | 1.0.2-rc1+1.20 | `mods/` | Required by TaCZ for third-person animation |

A few things worth stating plainly:

- The mod itself only asks for Forge 1.20.1. Other Forge versions were not tested.
- **A shader pack is what makes the cone visible.** This mod ships no pack. With the mod but no pack, the items and the toggles all work, but you will not see a cone or a beam.
- **Do not patch or edit your shader pack.** The mod injects its own GLSL into your pack instance at runtime. You keep your own copy of the original pack, and the mod neither bundles nor distributes a copy of any third-party pack.
- Injection matches on the pack's **contents** (key file hashes plus literal anchors), so the file name does not matter, not even a browser's `(1)` duplicate suffix. Only Complementary Reimagined r5.9 and iterationT 3.2.0 are adapted so far; a modified pack or an unadapted new version may still fail to inject, and chat will say the pack is not on the supported list.

## Installation

1. Install Forge 1.20.1.
2. Put `taclight-0.11.5.jar` into `.minecraft/mods/`.
3. Add Oculus on the client, and Embeddium if you want the frame rate.
4. Put Complementary Reimagined into `.minecraft/shaderpacks/`, zipped or unzipped. The mod ships no pack, so this step is required.
5. In game, pick Complementary Reimagined under `Video Settings -> Shader Packs`.

## Getting the items

- **Flashlight**: craft it at a vanilla crafting table — 2 iron ingots (top and bottom centre) plus 1 copper ingot, 1 glass and 1 redstone dust across the middle row.
- **Gun light attachment**: craft it at TaCZ's Gun Smith Table — 8 iron ingots and 3 redstone dust, the same recipe form TaCZ uses for its own laser attachments. (Without TaCZ this recipe does not exist; the flashlight still works.)

Both start switched off. Press the key once to turn one on.

## Usage

Keys, rebindable under `Options -> Controls -> TacLight`:

| Key | Action |
|---|---|
| `J` | Toggle flashlight |
| `M` | Toggle gun light |

- **Both lights start off.** A freshly crafted flashlight or a newly mounted gun light does not glow on its own; press the key to turn it on.
- The flashlight switch is stored in that flashlight's own item data. Switch to another item and back, and the state is still there.
- The gun light is per gun. Each gun keeps its own state.
- Light parameters live in `config/taclight-client.toml`: radius, intensity, inner and outer cone angle, beam density, gun light multiplier.
- In multiplayer, toggling your light is visible to others on the server. Remote light poses are rebuilt by your own client, with no extra sync traffic.

## Known limitations

Listed honestly, so you do not judge it broken for something it never claimed:

1. **Occlusion for complex block shapes is conservative.** Fences, stairs, snow layers and slabs are handled through a voxel grid. When the light ray grazes the underside of a fence bar, it will decide the spot is blocked rather than let light through. The result can look too dark, and it will not reach per-pixel shape accuracy. This is the ceiling of the current approach, not something more tuning removes.
2. **The cone only exists with Oculus/Iris and a shader pack.** Without a shader pipeline the mod still works, but there is no cone and no beam.
3. **Injection matches on pack contents.** The file name, including duplicate-download suffixes such as `(1)`, does not matter. A pack whose sources were modified, or a new version that is not adapted yet, may still fail to inject; chat reports it when that happens.
4. **Parameters are tuned by hand.** Nothing adapts to the scene automatically.
5. **The repository ships the internal debug surface and the verification harness.** `src/main/java/dev/taclight/debug` and `devonly` are development tools, excluded when the jar is packaged, so they never reach the release artifact. The contract tests under `src/test` have to be run explicitly (see below).
6. Preview status: the core works, the look is still being polished.

## Performance

Measured on a development machine (September 2026, older test bench, 4K, two lights). These are not acceptance figures and they vary with hardware and scene:

- Volumetric occlusion lookup: worst view 38.4 to 65.4 FPS (about +70%), with 0.16% of pixels differing.
- Volumetric temporal reuse: 116.2 to 183.5 FPS, light-side cost 6.24 to 3.08 ms.

Both shipped in this version. Raw data and the exact measurement setup are in the developer documentation.

## Building from source

Requires JDK 17.

```bash
./gradlew jar               # build/libs/taclight-<version>.jar
./gradlew packShaderZip     # packaging task for the old in-house pack, no longer a shipped product
./gradlew build             # full build
./gradlew taclightContracts # contract tests (note: not wired into build, run it explicitly)
```

Download the TaCZ and Oculus jars yourself and put them in `libs/` (this project does not redistribute them, see `THIRD_PARTY.md`). When a jar is missing, the build fails in `checkLocalDeps` and names the missing file instead of throwing a compile error.

## License and credits

- This mod and its shader pack source: **GPL-3.0-or-later** (`LICENSE`, full text in `LICENSE-GPL-3.0.txt`).
- No third-party jar is redistributed. License and redistribution terms for each dependency are in `THIRD_PARTY.md`.
- No modified shader pack is redistributed. Injection happens locally at runtime.
- Upstream ideas that were borrowed, none of them copied verbatim, and the Minecraft/Forge link usage, are listed in `THIRD_PARTY.md`.

## Layout

```
src/main/java/dev/taclight/   Mod source (debug/ and devonly/ are dev tools, stripped from the jar)
src/main/resources/           Resources: mixin config, gun pack, shader patch templates, inlined GLSL
src/test/                     Contract tests (run taclightContracts)
pack/shaders/                 Source of the in-house shader pack. The mod no longer bundles or
                              installs it; only lib/taclight_core.glsl and taclight_math.glsl are
                              kept as the GLSL source for injection
tools/                        Development tools: scenes, camera positions, pixel comparison
docs/开发纪律与路线图.md        Developer notes: branch model, discipline, status, roadmap (Chinese)
CHANGELOG.md                  Change history
THIRD_PARTY.md                Third-party credits and licenses
```

## Documentation

- `CHANGELOG.md`: version history.
- `docs/开发纪律与路线图.md`: development discipline, current status, roadmap and how things are verified (Chinese).
- `THIRD_PARTY.md`: dependencies, licenses and attribution.
