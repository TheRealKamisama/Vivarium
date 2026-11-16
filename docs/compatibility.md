# Compatibility log

General mechanisms Vivarium relies on instead of per-mod handling:
- Sprites whose RGBA may be read every tick (interpolated animations; Sodium reads `byMipLevel` pointers) keep RGBA.
- `SpriteContents` subclasses (e.g. shader-pack PBR sprites) are never freed.
- Pixel reads after freeing (`getOriginalImage`) get a lazy decode of the BC7 copy.
- Unexpected atlas layout (stitcher replaced, unaligned slots, odd image data) → that atlas stays vanilla RGBA.
- Users can opt out via config (`denyAtlases`, `keepRgbaNamespaces`).

## Issues

Root causes of every issue seen so far, including ones outside Vivarium.

| Symptom | Root cause | Vivarium at fault? | Resolution |
|---|---|---|---|
| `NoClassDefFoundError: .../VertexSerializer` with Sodium 0.8.13 + Iris 1.8.14 in `runSelftest` | Sodium 0.8.x is a jar-in-jar package; on the Gradle dev classpath (`localRuntime`) FML treats it as a library ("Found library file sodium-…") so its inner API jar isn't loaded | No — dev classpath packaging | Test Sodium 0.8.x from a `mods/` folder in a production launch; dev runs use Sodium 0.6.13 |
| Early display "trouble handing off the window, tried for 1 second" (80-mod pack) | FML early loading window must hand its GL context to Minecraft within 1 s; slow APU + many mixins misses it | No — environment | `earlyWindowControl = false` in the run's `config/fml.toml` |
| Create crash: `IllegalStateException: Found unused register callbacks` (Registrate) | Registrate throws on unused register callbacks only when `FMLLoader.isProduction()` is false (dev run); in production it only logs a warning. An addon registers a callback for an entry that is never registered | No — dev-only strictness in Registrate | Test packs in a production launch |
| `ResolutionException: Modules _1._21._1 and minecraft export package net.minecraft.server` | Custom launcher put the vanilla `1.21.1.jar` on the classpath; NeoForge's `-DignoreList=…,${version_name}.jar` only skips it under the child version's name | No — test launcher bug | `launch.py` uses the client jar as `versions/<neoforge-id>/<neoforge-id>.jar`, like the vanilla launcher |
| Self-test FAIL: alpha-rule violations (cutout threshold crossed) on modded atlases, 0 on vanilla | Encoder mode-5 paths (safety net and solid-colour) chose alpha indices by plain nearest value; with alphas like {0, 40, 255} the 2-bit levels are 0/85/170/255 and 40 snapped to 0 (hole). Vanilla textures never reached that path; modded semi-transparent textures do | **Yes** — generic encoder bug | Alpha indices constrained to the source texel's side of the threshold (`bestIndexSameClass`), unit test `alphaRuleHoldsNearCutoutThreshold`, `ENCODER_VERSION` 2 to invalidate caches |
| KubeJS: `Wrapper for class …ProcessingOutput already exists!` (Create Ultimate 2.0.3) | Create Ore Excavation 1.6.8's KubeJS plugin and KubeJS Create 2101.3.1-build.18 both register a type wrapper for Create's `ProcessingOutput` | No — the pack itself is broken | Removed Create Ore Excavation from the local test copy only (no dependants) |
| Intermittent: `NullPointerException: Trying to access unbound value: ResourceKey[minecraft:item / create:cardboard]` from `AllAdvancements.<clinit>` (Create Ultimate 2.0.3) | Create runs `AllAdvancements.register()` from its `RegisterEvent` handler for `TRIGGER_TYPES`; the advancement icons call `asItem()` on Registrate item entries, which fails if the item holder isn't bound yet. Seen once in 5 production launches, in a run with `-Dvivarium.disable=true`; the identical relaunch passed | No — Vivarium registers nothing and was inert in that run | None needed; treat as a flaky pack/Create registration-order issue, retry |
| Cold first launch +4.3 min with a large pack (blocks atlas 8192x4096, 18k sprites); log showed sprite phase wall ≈ thread time (parallelism 1.0) and small atlases "waiting" ~250 s | Each atlas job ran on the reload pool and blocked its worker in `join()` on its own sub-tasks. With more atlases (12) than pool workers (7 on an 8-thread APU) every worker sat in a join and only helped its own local queue, so the 18k sprite tasks ran on one thread | **Yes** — generic threading bug (any pack with many atlases / few cores) | Pipeline made non-blocking (`AtlasCompressionJob.start` chains stages with `thenCompose`/`allOf`, no `join`); per-phase timings added to the atlas log line |
