package dev.vivarium.atlas;

import com.mojang.blaze3d.platform.NativeImage;
import dev.vivarium.Vivarium;
import dev.vivarium.selftest.SelfTest;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;

/** Entry points used by the mixins. */
public final class AtlasHooks {
    private AtlasHooks() {}

    /** The compressed atlas built for these preparations, if every sprite carries its data. */
    @Nullable
    public static CompressedAtlas findPrepared(SpriteLoader.Preparations p, int width, int height, int mip) {
        Map<ResourceLocation, TextureAtlasSprite> regions = p.regions();
        CompressedAtlas ca = null;
        for (TextureAtlasSprite s : regions.values()) {
            Bc7SpriteData d = ((Bc7SpriteHolder) s.contents()).vivarium$data();
            if (d == null) return null;
            if (ca == null) ca = d.atlas;
            else if (ca != d.atlas) return null;
        }
        if (ca == null || ca.regions != regions || ca.width != width || ca.height != height || ca.mipLevel != mip) return null;
        if (ca.sprites.size() != regions.size()) return null;
        return ca;
    }

    /** Uploads the BC7 levels; returns false (after cleaning up) if GL rejected them. */
    public static boolean upload(CompressedAtlas ca, int textureId) {
        try {
            ca.upload(textureId);
            return true;
        } catch (Throwable t) {
            Vivarium.LOGGER.error("Vivarium: uploading {} failed, falling back to RGBA", ca.location, t);
            ca.abandon();
            return false;
        }
    }

    public static void afterUpload(CompressedAtlas ca) {
        ca.afterUpload(SelfTest.ENABLED);
    }

    public static void free(CompressedAtlas ca) {
        ca.free();
    }

    /**
     * Replaces {@code SpriteContents.upload}. Returns true when the upload was handled (vanilla must be skipped).
     */
    public static boolean onSpriteUpload(SpriteContents c, Bc7SpriteData d, int frameX, int frameY, NativeImage[] images) {
        CompressedAtlas ca = d.atlas;
        if (!ca.isLive()) return false;
        if (ca.isInitialUpload()) return true; // first frames are already part of the assembled levels
        if (images == c.byMipLevel) {
            int frame = d.frameIndexFor(frameX, frameY);
            if (frame >= 0) {
                ca.uploadFrame(d, frame);
                return true;
            }
            if (d.freed) return true; // nothing left to upload from
        }
        ca.uploadRgba(d, images, frameX, frameY);
        return true;
    }
}
