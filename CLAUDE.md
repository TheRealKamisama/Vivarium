# Vivarium

NeoForge 1.21.1 client mod (`vivarium`, package `dev.vivarium`): keeps texture atlases BC7-compressed in RAM and VRAM.
Pure Java, no runtime dependencies.

## Build / run
- `export JAVA_HOME=~/.gradle/jdks/jdk-21.0.13+9`; `./gradlew build` (includes codec tests)
- `./gradlew runSelftest [-Pworld=true] [-Pdisable=true]` → `runs/selftest/vivarium-selftest.txt`
- Game runs: `DISPLAY=:10 XAUTHORITY=/home/cachy/.config/tigervnc/remote-Xauthority`; keep `soundCategory_master:0.0`.
- Changing encoder output → bump `Bc7Encoder.ENCODER_VERSION` (cache key).

## Rules
- No mod-specific code (mod-id checks, per-mod mixins, other mods' class names). Fix the general mechanism.
- Record every compatibility issue's root cause in `docs/compatibility.md`, even when Vivarium isn't at fault.
- Test modpacks in a production launch, not the dev run: `docs/modpack-testing.md`.
