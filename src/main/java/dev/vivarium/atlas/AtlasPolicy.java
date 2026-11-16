package dev.vivarium.atlas;

import dev.vivarium.config.Config;
import net.minecraft.resources.ResourceLocation;

/** Decides which atlases and sprites are compressed. */
public final class AtlasPolicy {
    private AtlasPolicy() {}

    /** Atlases are always laid out with slots aligned to at least {@code 1 << MIN_ALIGN_MIP} = 4 texels. */
    public static final int MIN_ALIGN_MIP = 2;
    /** Debug: {@code -Dvivarium.disable=true} behaves as if the mod were absent (for A/B comparisons). */
    private static final boolean FORCE_DISABLED = Boolean.getBoolean("vivarium.disable");

    public static boolean shouldCompress(ResourceLocation atlas) {
        if (FORCE_DISABLED || !Config.get(Config.ENABLED) || !GlBc7.isSupported()) return false;
        String id = atlas.toString();
        if (Config.get(Config.DENY_ATLASES).contains(id)) return false;
        if (Config.get(Config.ALLOW_ATLASES).contains(id)) return true;
        return atlas.getNamespace().equals("minecraft") || Config.get(Config.COMPRESS_MODDED_ATLASES);
    }

    /** Mip level passed to the Stitcher for slot rounding (never changes the atlas' real mip level). */
    public static int alignmentMip(int mip) {
        return Math.max(mip, MIN_ALIGN_MIP);
    }

    public static boolean keepRgba(ResourceLocation sprite) {
        return !Config.get(Config.FREE_CPU_IMAGES) || Config.get(Config.KEEP_RGBA_NAMESPACES).contains(sprite.getNamespace());
    }
}
