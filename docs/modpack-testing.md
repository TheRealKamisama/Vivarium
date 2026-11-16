# Testing against real modpacks

Test modpacks in a **production launch**, not the Gradle dev run. The dev environment changes behaviour of other
mods (see `compatibility.md`) and produces false failures.
- Production setup (gitignored, under `.ref/`): NeoForge installer → `.ref/prod/mc`; game dir `.ref/prod/game`
  (put the pack's mods + `build/libs/vivarium-*.jar` in `mods/`); assets reused from `~/.gradle/caches/minecraft/assets`
  (index copied to `.ref/prod/assets/indexes/17.json`, `objects` symlinked).
- Launch: `python3 -I .ref/launch.py .ref/prod/mc neoforge-21.1.250 .ref/prod/game .ref/prod/assets -Dvivarium.selftest=true -Dvivarium.selftest.world=true -Dvivarium.selftest.tag=<name>`
- Mods downloaded from a `.mrpack` are verified against the SHA-1 in `modrinth.index.json`.
- If a pack fails to load, reproduce **without Vivarium** before attributing anything to it.
- A/B: add `-Dvivarium.disable=true` for a run with the mod inert; screenshots land in `<gamedir>/screenshots/`
  as `bc7-<tag>.png` / `vanilla-<tag>.png`; compare with `.ref/ImgDiff.java`.
- Changing encoder output requires bumping `Bc7Encoder.ENCODER_VERSION` (part of the cache key).

## Results: Create Ultimate 2.0.3 (79 of 80 mods, Sodium 0.8.13), Steam Deck APU, 2026-11-14
- Self-test PASS: 12 atlases, 48 levels, GPU decode bit-exact, 0 alpha-rule violations, 405 animated sprites OK.
- Blocks atlas 8192x4096 (18,263 sprites): RAM 190 → 57 MiB, VRAM 170 → 43 MiB. All atlases: RAM ≈ 211 → 63 MiB, VRAM ≈ 206 → 51 MiB.
- Load to title: vanilla 1:14, Vivarium warm cache 1:20, cold cache 2:22 (blocks atlas encode 72 s).
- Screenshot vs `-Dvivarium.disable=true`: no visible texture differences (diffs = mobs, water frame, distant mips).
