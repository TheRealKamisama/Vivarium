# Handoff — 2026-11-14 05:10 NZDT

## State
- Vivarium (NeoForge 1.21.1 client mod, BC7 atlases in RAM + VRAM) builds and passes all tests:
  `./gradlew build` (8 codec tests) and the in-game self-test on vanilla, Sodium 0.6.13, Iris 1.8.12 + Sodium,
  and the Create Ultimate 2.0.3 pack (79 mods, Sodium 0.8.13) in a production launch.
- Pack results (Steam Deck APU): atlas RAM 211 → 63 MiB, VRAM 206 → 51 MiB; load to title 1:14 vanilla,
  1:20 warm cache, 2:22 cold cache. Details: `docs/modpack-testing.md`.
- **Nothing is committed** (`git init` done, everything untracked). License (`mod_license`) and `mod_authors` in
  `gradle.properties` are still placeholders.

## Done today
- Renamed to Vivarium (mod id `vivarium`, package `dev.vivarium`, system properties `vivarium.*`).
- Encoder fix: the mode-5 alpha paths could cross the cutout threshold (holes). Now class-constrained;
  test `alphaRuleHoldsNearCutoutThreshold`; `ENCODER_VERSION` = 2.
- Threading fix: the atlas job blocked pool workers in `join()`, so encoding ran on one thread. `AtlasCompressionJob.start`
  is now a non-blocking future chain. Cold blocks-atlas encode went from 256 s to 72 s, and warm from 11.6 s to 2.9 s.
- Per-phase timings in the atlas log line: `[sprites X ms wall / Y ms thread, assemble Z ms]`.
- Production test setup: `.ref/launch.py` plus the NeoForge installer in `.ref/prod/` (gitignored). Removed the dev
  `runModpack` task, because Registrate crashes in dev environments.
- `CLAUDE.md` is kept brief; `docs/compatibility.md` logs the root cause of every issue.

## Next
1. **Cold-start encode time** (~68 s extra on this pack). The NORMAL preset is ~100 µs/block single-thread versus
   ~16 µs for FAST. Options: encode with FAST on a cache miss and re-encode NORMAL in the background into the cache
   for the next launch, or profile and speed up NORMAL (partition ranking, refine passes). Measure with
   `.ref/Harness.java` (`java -cp <out>:build/classes/java/main Harness NORMAL <jars...>`).
2. Commit the work (choose a license first).
3. Still untested: an Iris shaderpack with PBR, F3+T reload loops (leaks), mip level 0 vs 4 in-game, a GPU without BPTC
   (`-Dvivarium.forceUnsupported=true`).

## Gotchas
- JDK: `export JAVA_HOME=~/.gradle/jdks/jdk-21.0.13+9`. Display: `DISPLAY=:10 XAUTHORITY=/home/cachy/.config/tigervnc/remote-Xauthority`.
- Keep `soundCategory_master:0.0` in every run's `options.txt`, because sound plays on the Deck's speakers.
- Test modpacks only in production launches (`docs/modpack-testing.md`). The prod game dir is `.ref/prod/game`; its
  `mods/` holds a copy of the Vivarium jar, so re-copy it after each build. Create Ore Excavation was moved to
  `.ref/prod/disabled/` because the pack is broken with it.
- No mod-specific code in Vivarium. Fix the general mechanism and log it in `docs/compatibility.md`.
- When encoder output changes, bump `Bc7Encoder.ENCODER_VERSION`, which is part of the cache key.
