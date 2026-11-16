package dev.vivarium.config;

import dev.vivarium.codec.Bc7Encoder;
import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Client config. Values are read once per atlas stitch; changes apply on the next resource reload (F3+T). */
public final class Config {
    private Config() {}

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.BooleanValue FREE_CPU_IMAGES;
    public static final ModConfigSpec.EnumValue<Bc7Encoder.Preset> QUALITY;
    public static final ModConfigSpec.BooleanValue COMPRESS_MODDED_ATLASES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DENY_ATLASES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ALLOW_ATLASES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> KEEP_RGBA_NAMESPACES;
    public static final ModConfigSpec.BooleanValue CACHE_ENABLED;
    public static final ModConfigSpec.IntValue CACHE_MAX_MB;
    public static final ModConfigSpec.BooleanValue DEBUG_OVERLAY;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        b.push("general");
        ENABLED = b.comment("Master switch. When false the game behaves exactly like vanilla.")
            .define("enabled", true);
        FREE_CPU_IMAGES = b.comment("Free the RGBA copies of sprites after upload and keep BC7 in RAM instead.",
                "Disable if another mod reads sprite pixels directly and breaks.")
            .define("freeCpuImages", true);
        QUALITY = b.comment("BC7 encoder quality for load-time encoding (FAST, NORMAL, SLOW). Results are cached on disk.")
            .defineEnum("quality", Bc7Encoder.Preset.NORMAL);
        b.pop();

        b.push("atlases");
        COMPRESS_MODDED_ATLASES = b.comment("Compress atlases added by mods (unless listed in denyAtlases).")
            .define("compressModdedAtlases", true);
        DENY_ATLASES = b.comment("Atlases never compressed (HUD/GUI pixel art is viewed near 1:1 where artifacts show most).")
            .defineListAllowEmpty("denyAtlases", List.of(
                "minecraft:textures/atlas/gui.png",
                "minecraft:textures/atlas/mob_effects.png",
                "minecraft:textures/atlas/map_decorations.png"), () -> "", o -> o instanceof String);
        ALLOW_ATLASES = b.comment("Atlases always compressed, overriding compressModdedAtlases (denyAtlases still wins).")
            .defineListAllowEmpty("allowAtlases", List.of(), () -> "", o -> o instanceof String);
        KEEP_RGBA_NAMESPACES = b.comment("Sprite namespaces whose RGBA pixels are kept in RAM (compat escape hatch).")
            .defineListAllowEmpty("keepRgbaNamespaces", List.of(), () -> "", o -> o instanceof String);
        b.pop();

        b.push("cache");
        CACHE_ENABLED = b.comment("Cache encoded sprites on disk so later launches skip encoding.")
            .define("enabled", true);
        CACHE_MAX_MB = b.comment("Maximum cache size in MiB (least recently used entries are dropped).")
            .defineInRange("maxMegabytes", 512, 16, 16384);
        b.pop();

        b.push("debug");
        DEBUG_OVERLAY = b.comment("Show RAM/VRAM savings on the F3 screen.")
            .define("f3Overlay", true);
        b.pop();
        SPEC = b.build();
    }

    /** Reads a value, falling back to its default if the config has not been loaded yet. */
    public static <T> T get(ModConfigSpec.ConfigValue<T> v) {
        try {
            return SPEC.isLoaded() ? v.get() : v.getDefault();
        } catch (IllegalStateException e) {
            return v.getDefault();
        }
    }
}
