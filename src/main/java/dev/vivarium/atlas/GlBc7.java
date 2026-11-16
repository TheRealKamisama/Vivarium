package dev.vivarium.atlas;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.vivarium.Vivarium;
import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GLCapabilities;

/** OpenGL helpers for BPTC textures. Every method except {@link #isSupported()} must run on the render thread. */
public final class GlBc7 {
    private GlBc7() {}

    public static final int GL_COMPRESSED_RGBA_BPTC_UNORM = 0x8E8C;

    private static volatile Boolean supported;
    /** Debug switch: {@code -Dvivarium.forceUnsupported=true} pretends the GPU lacks BPTC. */
    private static final boolean FORCE_UNSUPPORTED = Boolean.getBoolean("vivarium.forceUnsupported");

    /** Called on the render thread right after the GL context is initialised (GL capabilities are thread-local). */
    public static void detect() {
        if (supported != null) return;
        boolean ok;
        try {
            GLCapabilities caps = GL.getCapabilities();
            ok = caps.OpenGL42 || caps.GL_ARB_texture_compression_bptc;
        } catch (Throwable t) {
            Vivarium.LOGGER.warn("Vivarium: could not query GL capabilities", t);
            ok = false;
        }
        if (FORCE_UNSUPPORTED) ok = false;
        supported = ok;
        Vivarium.LOGGER.info("Vivarium: BPTC (BC7) textures {}", ok ? "supported" : "NOT supported - mod inactive");
    }

    /** False until {@link #detect()} ran; safe from any thread. */
    public static boolean isSupported() {
        Boolean s = supported;
        return s != null && s;
    }

    private static void resetUnpackState() {
        GlStateManager._glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
    }

    /**
     * Respecifies every level of {@code textureId} as BC7 (same parameters vanilla's
     * {@code TextureUtil.prepareImage} sets), uploading {@code levels[i]} into level {@code i}.
     */
    public static void allocateAndUpload(int textureId, int mipLevel, int width, int height, ByteBuffer[] levels) {
        RenderSystem.assertOnRenderThreadOrInit();
        GlStateManager._bindTexture(textureId);
        resetUnpackState();
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, mipLevel);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MIN_LOD, 0);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LOD, mipLevel);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS, 0.0F);
        for (int i = 0; i <= mipLevel; i++) {
            int w = Math.max(1, width >> i), h = Math.max(1, height >> i);
            GL13.glCompressedTexImage2D(GL11.GL_TEXTURE_2D, i, GL_COMPRESSED_RGBA_BPTC_UNORM, w, h, 0, levels[i]);
        }
        // Vanilla sets filters inside NativeImage.upload, which we skip for compressed atlases.
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER,
            mipLevel > 0 ? GL11.GL_NEAREST_MIPMAP_LINEAR : GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
    }

    /** Sub-uploads BC7 blocks into the currently bound texture. {@code x, y} must be multiples of 4. */
    public static void subUpload(int level, int x, int y, int w, int h, ByteBuffer blocks) {
        resetUnpackState();
        GL13.glCompressedTexSubImage2D(GL11.GL_TEXTURE_2D, level, x, y, w, h, GL_COMPRESSED_RGBA_BPTC_UNORM, blocks);
    }

    /** Reads back one level as RGBA (driver decompresses). Used by the self-test only. */
    public static int[] readLevelRgba(int textureId, int level, int w, int h) {
        GlStateManager._bindTexture(textureId);
        GlStateManager._glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 4);
        int[] out = new int[w * h];
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, level, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
        return out;
    }

    public static int levelInternalFormat(int textureId, int level) {
        GlStateManager._bindTexture(textureId);
        return GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_INTERNAL_FORMAT);
    }

}
