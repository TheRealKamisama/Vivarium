package dev.vivarium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.vivarium.atlas.AtlasCompressionJob;
import dev.vivarium.atlas.AtlasPolicy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.Stitcher;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SpriteLoader.class)
abstract class SpriteLoaderMixin {
    @Shadow
    @Final
    private ResourceLocation location;

    /**
     * Round sprite slots to at least 4 texels (and to {@code 1 << mip}, as vanilla) so every slot covers whole
     * BC7 blocks. The Stitcher uses this value only for slot rounding; the atlas keeps its real mip level.
     */
    @WrapOperation(method = "stitch", at = @At(value = "NEW", target = "(III)Lnet/minecraft/client/renderer/texture/Stitcher;"))
    private Stitcher<SpriteContents> vivarium$alignSlots(int maxWidth, int maxHeight, int mipLevel, Operation<Stitcher<SpriteContents>> original) {
        int m = AtlasPolicy.shouldCompress(location) ? AtlasPolicy.alignmentMip(mipLevel) : mipLevel;
        return original.call(maxWidth, maxHeight, m);
    }

    /** Chain the BC7 job after mip generation, so {@code readyForUpload} completes only once it is done. */
    @Inject(method = "stitch", at = @At("RETURN"), cancellable = true)
    private void vivarium$compress(List<SpriteContents> contents, int mipLevel, Executor executor,
                                   CallbackInfoReturnable<SpriteLoader.Preparations> cir) {
        if (!AtlasPolicy.shouldCompress(location)) return;
        SpriteLoader.Preparations p = cir.getReturnValue();
        ResourceLocation loc = location;
        CompletableFuture<Void> ready = p.readyForUpload().thenComposeAsync(v -> AtlasCompressionJob.start(loc, p, executor), executor);
        cir.setReturnValue(new SpriteLoader.Preparations(p.width(), p.height(), p.mipLevel(), p.missing(), p.regions(), ready));
    }
}
