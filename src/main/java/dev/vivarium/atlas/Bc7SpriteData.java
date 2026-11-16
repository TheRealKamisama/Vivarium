package dev.vivarium.atlas;

import com.mojang.blaze3d.platform.NativeImage;
import dev.vivarium.codec.Bc7Decoder;
import java.nio.ByteBuffer;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.resources.ResourceLocation;

/**
 * What remains of a sprite once its RGBA images are freed: BC7 blocks of its slot for every used frame at the
 * block-aligned mip levels, small RGBA copies of the deep levels, and a 1-bit alpha mask.
 */
public final class Bc7SpriteData implements CacheAccess {
    final CompressedAtlas atlas;
    final SpriteContents contents;
    final ResourceLocation name;
    /** Position in the atlas (multiple of the slot alignment). */
    final int x, y;
    /** Frame size. */
    final int w, h;
    /** Slot size (frame size rounded up to the alignment). */
    final int slotW, slotH;
    /** Size of the source image (all frames). */
    final int stripW, stripH;
    final int framesPerRow;
    /** frame index -> local frame slot, or -1 when the frame is never shown. */
    final int[] frameSlot;
    final int usedFrames;
    final int firstFrame;
    /** [frameSlot * alignedLevels + level] -> byte offset into the atlas slab. */
    final int[] blockOffset;
    final int[] blockLength;
    /** [frameSlot * deepLevels + (level - firstDeep)] -> slot-padded RGBA. */
    final int[][] deep;
    /** Bit set over the source image: 1 = alpha 0. */
    final long[] transparentMask;
    final boolean keepRgba;

    volatile boolean freed;
    /** Self-test only: source RGBA of the first frame's slot per level. */
    @Nullable
    int[][] selfTestSlots;
    @Nullable
    private NativeImage decodedOriginal;

    Bc7SpriteData(CompressedAtlas atlas, SpriteContents contents, int x, int y, int slotW, int slotH, int stripW, int stripH,
                  int framesPerRow, int[] frameSlot, int usedFrames, int firstFrame, boolean keepRgba) {
        this.atlas = atlas;
        this.contents = contents;
        this.name = contents.name();
        this.x = x;
        this.y = y;
        this.w = contents.width();
        this.h = contents.height();
        this.slotW = slotW;
        this.slotH = slotH;
        this.stripW = stripW;
        this.stripH = stripH;
        this.framesPerRow = framesPerRow;
        this.frameSlot = frameSlot;
        this.usedFrames = usedFrames;
        this.firstFrame = firstFrame;
        this.blockOffset = new int[usedFrames * atlas.alignedLevels];
        this.blockLength = new int[usedFrames * atlas.alignedLevels];
        this.deep = new int[usedFrames * atlas.deepLevels][];
        this.transparentMask = new long[(stripW * stripH + 63) >>> 6];
        this.keepRgba = keepRgba;
    }

    public CompressedAtlas atlas() {
        return atlas;
    }

    public ResourceLocation name() {
        return name;
    }

    public int x() { return x; }

    public int y() { return y; }

    public int width() { return w; }

    public int height() { return h; }

    public int slotWidth() { return slotW; }

    public int slotHeight() { return slotH; }

    public boolean animated() {
        return usedFrames > 1;
    }

    public boolean keepsRgba() {
        return keepRgba;
    }

    /** Frame indices that have pre-encoded blocks. */
    public int[] usedFrameIndices() {
        int[] r = new int[usedFrames];
        for (int f = 0; f < frameSlot.length; f++) if (frameSlot[f] >= 0) r[frameSlot[f]] = f;
        return r;
    }

    /** Level-0 blocks of a frame as a heap array. */
    public byte[] frameBlocksLevel0(int frameIndex) {
        ByteBuffer b = blocks(frameIndex, 0);
        byte[] out = new byte[b.remaining()];
        b.get(out);
        return out;
    }

    @Nullable
    public int[] selfTestSlot(int level) {
        return selfTestSlots == null ? null : selfTestSlots[level];
    }

    /** Vanilla's {@code isTransparent} semantics: true when the texel's alpha is exactly 0. */
    public boolean isTransparent(int frame, int px, int py) {
        int fx = frame % framesPerRow, fy = frame / framesPerRow;
        int sx = px + fx * w, sy = py + fy * h;
        if (sx < 0 || sy < 0 || sx >= stripW || sy >= stripH) return true;
        int bit = sy * stripW + sx;
        return (transparentMask[bit >>> 6] & (1L << (bit & 63))) != 0;
    }

    /** Frame index for a vanilla upload offset into the source image, or -1. */
    int frameIndexFor(int frameX, int frameY) {
        if (frameX % w != 0 || frameY % h != 0) return -1;
        int idx = (frameY / h) * framesPerRow + frameX / w;
        return idx >= 0 && idx < frameSlot.length && frameSlot[idx] >= 0 ? idx : -1;
    }

    ByteBuffer blocks(int frameIndex, int level) {
        int k = frameSlot[frameIndex] * atlas.alignedLevels + level;
        return atlas.slab.slice(blockOffset[k], blockLength[k]);
    }

    int[] deepPixels(int frameIndex, int level) {
        return deep[frameSlot[frameIndex] * atlas.deepLevels + (level - atlas.alignedLevels)];
    }

    /**
     * Rebuilds the source image (all frames, level 0) from BC7 for mods that ask for {@code getOriginalImage()}
     * after the RGBA copy was freed. Unused frames stay transparent. Kept until the sprite is closed.
     */
    public synchronized NativeImage decodedOriginal() {
        if (decodedOriginal == null) {
            NativeImage img = new NativeImage(stripW, stripH, true);
            Bc7Decoder dec = new Bc7Decoder();
            int[] slot = new int[slotW * slotH];
            for (int f = 0; f < frameSlot.length; f++) {
                if (frameSlot[f] < 0) continue;
                ByteBuffer b = blocks(f, 0);
                byte[] bytes = new byte[b.remaining()];
                b.get(bytes);
                dec.decodeImage(bytes, 0, slotW, slotH, slot);
                int ox = (f % framesPerRow) * w, oy = (f / framesPerRow) * h;
                for (int yy = 0; yy < h; yy++) {
                    for (int xx = 0; xx < w; xx++) img.setPixelRGBA(ox + xx, oy + yy, slot[yy * slotW + xx]);
                }
            }
            decodedOriginal = img;
            atlas.noteDecodedCopy(name, (long) stripW * stripH * 4);
        }
        return decodedOriginal;
    }

    public boolean isFreed() {
        return freed;
    }

    public synchronized void release() {
        if (decodedOriginal != null) {
            decodedOriginal.close();
            decodedOriginal = null;
        }
    }

    // ---- CacheAccess (blocks of one sprite are contiguous in the slab)

    @Override
    public int cacheBlockBytes() {
        int n = 0;
        for (int len : blockLength) n += len;
        return n;
    }

    @Override
    public byte[] cacheBlocks() {
        byte[] b = new byte[cacheBlockBytes()];
        if (b.length > 0) atlas.slab.get(blockOffset[0], b);
        return b;
    }

    @Override
    public int cacheDeepCount() {
        return deep.length;
    }

    @Override
    public int cacheDeepLength(int i) {
        int level = atlas.alignedLevels + (i % Math.max(1, atlas.deepLevels));
        return (slotW >> level) * (slotH >> level);
    }

    @Override
    public int[] cacheDeep(int i) {
        return deep[i];
    }

    @Override
    public void cacheApply(byte[] blocks, int[][] deepPixels) {
        if (blocks.length > 0) atlas.slab.put(blockOffset[0], blocks);
        System.arraycopy(deepPixels, 0, deep, 0, deep.length);
    }

    /** Cache key inputs other than pixels: everything that changes the encoded result. */
    int[] cacheHeader(int preset) {
        int[] hdr = new int[11 + frameSlot.length];
        hdr[0] = atlas.mipLevel;
        hdr[1] = atlas.alignedLevels;
        hdr[2] = w;
        hdr[3] = h;
        hdr[4] = slotW;
        hdr[5] = slotH;
        hdr[6] = stripW;
        hdr[7] = stripH;
        hdr[8] = framesPerRow;
        hdr[9] = preset;
        hdr[10] = usedFrames;
        System.arraycopy(frameSlot, 0, hdr, 11, frameSlot.length);
        return hdr;
    }

    long ramBytes() {
        long b = transparentMask.length * 8L;
        for (int len : blockLength) b += len;
        for (int[] d : deep) b += d.length * 4L;
        return b;
    }
}
