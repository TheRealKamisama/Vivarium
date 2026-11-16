package dev.vivarium.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import dev.vivarium.atlas.AtlasHooks;
import dev.vivarium.atlas.Bc7SpriteData;
import dev.vivarium.atlas.Bc7SpriteHolder;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SpriteContents.class)
abstract class SpriteContentsMixin implements Bc7SpriteHolder {
    @Unique
    @Nullable
    private volatile Bc7SpriteData vivarium$data;

    @Override
    public Bc7SpriteData vivarium$data() {
        return vivarium$data;
    }

    @Override
    public void vivarium$setData(@Nullable Bc7SpriteData data) {
        this.vivarium$data = data;
    }

    @Inject(method = "upload", at = @At("HEAD"), cancellable = true)
    private void vivarium$upload(int x, int y, int frameX, int frameY, NativeImage[] atlasData, CallbackInfo ci) {
        Bc7SpriteData d = vivarium$data;
        if (d != null && AtlasHooks.onSpriteUpload((SpriteContents) (Object) this, d, frameX, frameY, atlasData)) {
            ci.cancel();
        }
    }

    @Inject(method = "isTransparent", at = @At("HEAD"), cancellable = true)
    private void vivarium$isTransparent(int frame, int x, int y, CallbackInfoReturnable<Boolean> cir) {
        Bc7SpriteData d = vivarium$data;
        if (d != null) cir.setReturnValue(d.isTransparent(frame, x, y));
    }

    @Inject(method = "getOriginalImage", at = @At("HEAD"), cancellable = true)
    private void vivarium$getOriginalImage(CallbackInfoReturnable<NativeImage> cir) {
        Bc7SpriteData d = vivarium$data;
        if (d != null && d.isFreed()) cir.setReturnValue(d.decodedOriginal());
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void vivarium$close(CallbackInfo ci) {
        Bc7SpriteData d = vivarium$data;
        if (d != null) {
            d.release();
            vivarium$data = null;
        }
    }
}
