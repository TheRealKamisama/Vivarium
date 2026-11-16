package dev.vivarium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.vivarium.atlas.AtlasHooks;
import dev.vivarium.atlas.CompressedAtlas;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureAtlas.class)
abstract class TextureAtlasMixin {
    @Unique
    @Nullable
    private CompressedAtlas vivarium$state;
    @Unique
    @Nullable
    private CompressedAtlas vivarium$pending;

    /** Allocate BC7 storage (already filled with every sprite's first frame) instead of empty RGBA8 levels. */
    @WrapOperation(method = "upload", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/platform/TextureUtil;prepareImage(IIII)V"))
    private void vivarium$prepareImage(int textureId, int mipLevel, int width, int height, Operation<Void> original,
                                       @Local(argsOnly = true) SpriteLoader.Preparations preparations) {
        CompressedAtlas ca = AtlasHooks.findPrepared(preparations, width, height, mipLevel);
        if (ca != null && AtlasHooks.upload(ca, textureId)) {
            vivarium$pending = ca;
            return;
        }
        original.call(textureId, mipLevel, width, height);
    }

    @Inject(method = "upload", at = @At("RETURN"))
    private void vivarium$afterUpload(SpriteLoader.Preparations preparations, CallbackInfo ci) {
        CompressedAtlas ca = vivarium$pending;
        if (ca != null) {
            vivarium$pending = null;
            vivarium$state = ca;
            AtlasHooks.afterUpload(ca);
        }
    }

    @Inject(method = "clearTextureData", at = @At("HEAD"))
    private void vivarium$clear(CallbackInfo ci) {
        CompressedAtlas old = vivarium$state;
        if (old != null) {
            vivarium$state = null;
            AtlasHooks.free(old);
        }
    }
}
