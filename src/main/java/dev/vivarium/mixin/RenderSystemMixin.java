package dev.vivarium.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.vivarium.atlas.GlBc7;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Detects BPTC support on the render thread once the GL context exists, before the first resource reload. */
@Mixin(RenderSystem.class)
abstract class RenderSystemMixin {
    @Inject(method = "initRenderer", at = @At("RETURN"), remap = false)
    private static void vivarium$detect(int debugVerbosity, boolean synchronous, CallbackInfo ci) {
        GlBc7.detect();
    }
}
