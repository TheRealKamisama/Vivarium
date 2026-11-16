package dev.vivarium.atlas;

import com.mojang.blaze3d.platform.NativeImage;
import dev.vivarium.Vivarium;
import dev.vivarium.codec.Bc7Encoder;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nullable;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.system.MemoryUtil;

/**
 * BC7 state of one atlas texture: the slab of per-sprite blocks (RAM), the RGBA copies of the deep mip levels
 * (where 4x4 blocks straddle neighbouring sprites) and the runtime upload paths for animations.
 *
 * <p>Level {@code i <= alignedLevels - 1} is block-aligned for every slot; levels above are "deep".
 */
public final class CompressedAtlas {
    /** Live atlases, for the F3 overlay and the self-test. */
    public static final List<CompressedAtlas> LIVE = new CopyOnWriteArrayList<>();

    final ResourceLocation location;
    final int width, height, mipLevel;
    final int alignedLevels, deepLevels;
    final Map<ResourceLocation, TextureAtlasSprite> regions;
    final List<Bc7SpriteData> sprites = new ArrayList<>();

    ByteBuffer slab;
    /** Per-level atlas blocks, only between assembly and upload (kept by the self-test). */
    @Nullable
    ByteBuffer[] levelBlocks;
    /** RGBA copies of deep levels, index {@code level - alignedLevels}. */
    int[][] deepRgba;
    /** Self-test only: what the atlas should look like, per level. */
    @Nullable
    int[][] expectedRgba;

    private volatile boolean live;
    int textureId = -1;
    private boolean initialUpload;
    private ByteBuffer scratch;
    private final Bc7Encoder.Preset runtimePreset = Bc7Encoder.Preset.FAST;
    private final LinkedHashMap<PatchKey, byte[]> patchCache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<PatchKey, byte[]> e) {
            return size() > 8192;
        }
    };

    // stats
    long vanillaRamBytes, ramBytes, vanillaVramBytes, vramBytes, decodedCopyBytes;
    int keptRgbaSprites;
    long encodeNanos;
    /** Phase timings: wall time of the parallel sprite phase, summed thread time spent in it, level assembly. */
    long spriteWallNanos, spriteThreadNanos, assembleNanos;
    int cacheHits, cacheMisses;

    CompressedAtlas(ResourceLocation location, int width, int height, int mipLevel, Map<ResourceLocation, TextureAtlasSprite> regions) {
        this.location = location;
        this.width = width;
        this.height = height;
        this.mipLevel = mipLevel;
        int alignedTop = Math.min(AtlasPolicy.alignmentMip(mipLevel) - 2, mipLevel);
        this.alignedLevels = alignedTop + 1;
        this.deepLevels = mipLevel + 1 - alignedLevels;
        this.regions = regions;
    }

    public ResourceLocation location() {
        return location;
    }

    public int textureId() {
        return textureId;
    }

    public int mipLevel() {
        return mipLevel;
    }

    public List<Bc7SpriteData> sprites() {
        return sprites;
    }

    @Nullable
    public ByteBuffer levelBlocks(int level) {
        return levelBlocks == null ? null : levelBlocks[level];
    }

    @Nullable
    public int[] expectedRgba(int level) {
        return expectedRgba == null ? null : expectedRgba[level];
    }

    public int levelWidth(int level) {
        return Math.max(1, width >> level);
    }

    public int levelHeight(int level) {
        return Math.max(1, height >> level);
    }

    boolean isLive() {
        return live;
    }

    boolean isInitialUpload() {
        return initialUpload;
    }

    /** Render thread: replaces the texture's storage with the assembled BC7 levels. */
    void upload(int textureId) {
        this.textureId = textureId;
        GlBc7.allocateAndUpload(textureId, mipLevel, width, height, levelBlocks);
        live = true;
        initialUpload = true;
    }

    /** Render thread, end of {@code TextureAtlas.upload}: free RGBA copies, drop assembly buffers. */
    void afterUpload(boolean keepLevelsForSelfTest) {
        initialUpload = false;
        if (!keepLevelsForSelfTest) freeLevelBlocks();
        long ram = slab.capacity();
        for (int[] d : deepRgba) ram += d.length * 4L;
        for (Bc7SpriteData s : sprites) {
            ram += s.transparentMask.length * 8L;
            for (int[] d : s.deep) ram += d.length * 4L;
            SpriteContents c = s.contents;
            if (s.keepRgba) {
                keptRgbaSprites++;
                for (NativeImage img : c.byMipLevel) ram += (long) img.getWidth() * img.getHeight() * 4;
                continue;
            }
            for (NativeImage img : c.byMipLevel) img.close();
            c.byMipLevel = new NativeImage[0];
            s.freed = true;
        }
        ramBytes = ram;
        LIVE.add(this);
        Vivarium.LOGGER.info("Vivarium: {} {}x{}x{} - {} sprites, RAM {} -> {}, VRAM {} -> {}, encode {} ms [sprites {} ms wall / {} ms thread, assemble {} ms] (cache {} hit / {} miss){}",
            location, width, height, mipLevel, sprites.size(), mib(vanillaRamBytes), mib(ramBytes), mib(vanillaVramBytes), mib(vramBytes),
            encodeNanos / 1_000_000, spriteWallNanos / 1_000_000, spriteThreadNanos / 1_000_000, assembleNanos / 1_000_000,
            cacheHits, cacheMisses, keptRgbaSprites > 0 ? ", " + keptRgbaSprites + " sprites keep RGBA" : "");
    }

    private void freeLevelBlocks() {
        if (levelBlocks != null) {
            for (ByteBuffer b : levelBlocks) if (b != null) MemoryUtil.memFree(b);
            levelBlocks = null;
        }
    }

    /** Called when the atlas is cleared (reload) or replaced. */
    void free() {
        live = false;
        LIVE.remove(this);
        freeLevelBlocks();
        if (slab != null) {
            MemoryUtil.memFree(slab);
            slab = null;
        }
        if (scratch != null) {
            MemoryUtil.memFree(scratch);
            scratch = null;
        }
        deepRgba = null;
        expectedRgba = null;
        patchCache.clear();
    }

    /** Called when building failed before upload. */
    void abandon() {
        for (Bc7SpriteData s : sprites) ((Bc7SpriteHolder) s.contents).vivarium$setData(null);
        free();
    }

    void noteDecodedCopy(ResourceLocation sprite, long bytes) {
        decodedCopyBytes += bytes;
        Vivarium.LOGGER.debug("Vivarium: rebuilt RGBA copy of {} for a mod reading sprite pixels", sprite);
    }

    // ------------------------------------------------------------------ runtime uploads (render thread)

    /** Uploads a pre-encoded frame (non-interpolated animation tick). */
    void uploadFrame(Bc7SpriteData s, int frameIndex) {
        for (int i = 0; i < alignedLevels; i++) {
            GlBc7.subUpload(i, s.x >> i, s.y >> i, s.slotW >> i, s.slotH >> i, s.blocks(frameIndex, i));
        }
        for (int i = alignedLevels; i <= mipLevel; i++) {
            patchDeep(i, s, s.deepPixels(frameIndex, i));
        }
    }

    /** Uploads arbitrary RGBA images (interpolated frames, custom sprites) by encoding them now. */
    void uploadRgba(Bc7SpriteData s, NativeImage[] images, int frameX, int frameY) {
        for (int i = 0; i <= mipLevel && i < images.length; i++) {
            NativeImage img = images[i];
            int sw = s.slotW >> i, sh = s.slotH >> i;
            int[] slot = new int[sw * sh];
            SpriteSampler.sampleSlot(img, frameX >> i, frameY >> i, Math.max(1, s.w >> i), Math.max(1, s.h >> i), sw, sh, slot);
            if (i < alignedLevels) {
                byte[] blocks = encodeCached(slot, sw, sh, i);
                GlBc7.subUpload(i, s.x >> i, s.y >> i, sw, sh, direct(blocks));
            } else {
                patchDeep(i, s, slot);
            }
        }
    }

    /** Writes a slot into the deep level copy and re-encodes the 4x4 blocks covering it. */
    private void patchDeep(int level, Bc7SpriteData s, int[] slot) {
        int[] lvl = deepRgba[level - alignedLevels];
        int lw = levelWidth(level), lh = levelHeight(level);
        int ox = s.x >> level, oy = s.y >> level, sw = s.slotW >> level, sh = s.slotH >> level;
        for (int yy = 0; yy < sh && oy + yy < lh; yy++) {
            System.arraycopy(slot, yy * sw, lvl, (oy + yy) * lw + ox, Math.min(sw, lw - ox));
        }
        int bx0 = ox >> 2, by0 = oy >> 2;
        int bx1 = Math.min((ox + sw + 3) >> 2, (lw + 3) >> 2), by1 = Math.min((oy + sh + 3) >> 2, (lh + 3) >> 2);
        int px0 = bx0 * 4, py0 = by0 * 4;
        int rw = Math.min((bx1 - bx0) * 4, lw - px0), rh = Math.min((by1 - by0) * 4, lh - py0);
        int[] region = new int[rw * rh];
        for (int yy = 0; yy < rh; yy++) System.arraycopy(lvl, (py0 + yy) * lw + px0, region, yy * rw, rw);
        byte[] blocks = encodeCached(region, rw, rh, level);
        GlBc7.subUpload(level, px0, py0, rw, rh, direct(blocks));
    }

    private byte[] encodeCached(int[] pixels, int w, int h, int level) {
        PatchKey key = new PatchKey(w, h, pixels);
        byte[] b = patchCache.get(key);
        if (b == null) {
            b = new byte[Bc7Encoder.encodedSize(w, h)];
            Bc7Encoder.forThread(runtimePreset).encodeImage(pixels, 0, w, w, h, b, 0);
            patchCache.put(key, b);
        }
        return b;
    }

    private ByteBuffer direct(byte[] bytes) {
        if (scratch == null || scratch.capacity() < bytes.length) {
            if (scratch != null) MemoryUtil.memFree(scratch);
            scratch = MemoryUtil.memAlloc(Math.max(bytes.length, 4096));
        }
        scratch.clear();
        scratch.put(bytes).flip();
        return scratch;
    }

    private record PatchKey(int w, int h, int[] pixels, int hash) {
        PatchKey(int w, int h, int[] pixels) {
            this(w, h, pixels, 31 * (31 * w + h) + Arrays.hashCode(pixels));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof PatchKey k && k.hash == hash && k.w == w && k.h == h && Arrays.equals(k.pixels, pixels);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    // ------------------------------------------------------------------ stats

    public long vanillaRamBytes() {
        return vanillaRamBytes;
    }

    public long ramBytes() {
        return ramBytes + decodedCopyBytes;
    }

    public long vanillaVramBytes() {
        return vanillaVramBytes;
    }

    public long vramBytes() {
        return vramBytes;
    }

    static String mib(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f MiB", bytes / 1048576.0);
    }
}
