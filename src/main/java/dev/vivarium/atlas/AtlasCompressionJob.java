package dev.vivarium.atlas;

import com.mojang.blaze3d.platform.NativeImage;
import dev.vivarium.Vivarium;
import dev.vivarium.cache.SpriteCache;
import dev.vivarium.codec.Bc7Encoder;
import dev.vivarium.codec.Bc7Tables;
import dev.vivarium.config.Config;
import dev.vivarium.selftest.SelfTest;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.ClientHooks;
import org.lwjgl.system.MemoryUtil;

/**
 * Background job run after vanilla (and Sodium) generated the mip chains: encodes every sprite's slot into BC7,
 * keeps deep levels as RGBA, assembles the atlas levels for upload. On any failure nothing is attached to the
 * sprites and the vanilla RGBA path runs unchanged.
 */
public final class AtlasCompressionJob {
    private AtlasCompressionJob() {}

    /**
     * Starts compressing the atlas and returns a future that completes (never exceptionally) when it is done.
     * Every stage is chained, never joined: pool threads must not block waiting for other pool tasks, or with
     * more atlases than worker threads all workers end up blocked and the encode runs on a single thread.
     */
    public static CompletableFuture<Void> start(ResourceLocation location, SpriteLoader.Preparations p, Executor executor) {
        CompressedAtlas ca = new CompressedAtlas(location, p.width(), p.height(), p.mipLevel(), p.regions());
        long t0 = System.nanoTime();
        final Bc7Encoder.Preset preset = Config.get(Config.QUALITY);
        CompletableFuture<Void> pipeline;
        try {
            if (!describe(ca, p)) {
                ca.abandon();
                return CompletableFuture.completedFuture(null);
            }
            pipeline = encodeSprites(ca, p.mipLevel(), preset, executor)
                .thenComposeAsync(v -> assemble(ca, p.mipLevel(), preset, executor), executor);
        } catch (Throwable t) {
            pipeline = CompletableFuture.failedFuture(t);
        }
        return pipeline.handle((v, t) -> {
            if (t != null) {
                Vivarium.LOGGER.error("Vivarium: compressing {} failed, keeping vanilla RGBA atlas", location, t);
                ca.abandon();
                return null;
            }
            ca.encodeNanos = System.nanoTime() - t0;
            for (Bc7SpriteData d : ca.sprites) ((Bc7SpriteHolder) d.contents).vivarium$setData(d);
            return null;
        });
    }

    private static int roundUp(int v, int a) {
        return (v + a - 1) / a * a;
    }

    /** Validates the layout, describes every sprite and allocates the slab. False = leave the atlas vanilla. */
    private static boolean describe(CompressedAtlas ca, SpriteLoader.Preparations p) {
        final int mip = p.mipLevel();
        final int align = 1 << AtlasPolicy.alignmentMip(mip);

        // 1. Validate layout and describe every sprite.
        int offset = 0;
        for (TextureAtlasSprite s : p.regions().values()) {
            SpriteContents c = s.contents();
            if (s.getX() % align != 0 || s.getY() % align != 0) {
                Vivarium.LOGGER.warn("Vivarium: {} sprite {} is not {}-aligned (another mod replaced the stitcher?), skipping atlas",
                    ca.location, c.name(), align);
                return false;
            }
            NativeImage img0 = c.getOriginalImage();
            if (img0.format() != NativeImage.Format.RGBA || c.byMipLevel.length < mip + 1) {
                Vivarium.LOGGER.warn("Vivarium: {} sprite {} has unexpected image data, skipping atlas", ca.location, c.name());
                return false;
            }
            int stripW = img0.getWidth(), stripH = img0.getHeight();
            int w = c.width(), h = c.height();
            int framesPerRow = Math.max(1, stripW / w);
            int frameCount = framesPerRow * Math.max(1, stripH / h);
            int[] frameSlot = new int[frameCount];
            java.util.Arrays.fill(frameSlot, -1);
            int used = 0;
            int first = 0;
            boolean interpolated = false;
            SpriteContents.AnimatedTexture anim = c.animatedTexture;
            if (anim == null) {
                frameSlot[0] = used++;
            } else {
                framesPerRow = anim.frameRowSize;
                interpolated = anim.interpolateFrames;
                first = anim.frames.get(0).index;
                for (SpriteContents.FrameInfo fi : anim.frames) {
                    if (fi.index >= 0 && fi.index < frameCount && frameSlot[fi.index] < 0) frameSlot[fi.index] = used++;
                }
            }
            boolean keepRgba = interpolated || c.getClass() != SpriteContents.class || AtlasPolicy.keepRgba(c.name());
            Bc7SpriteData d = new Bc7SpriteData(ca, c, s.getX(), s.getY(), roundUp(w, align), roundUp(h, align),
                stripW, stripH, framesPerRow, frameSlot, used, first, keepRgba);
            for (int k = 0; k < used * ca.alignedLevels; k++) {
                int level = k % ca.alignedLevels;
                int len = Bc7Encoder.encodedSize(d.slotW >> level, d.slotH >> level);
                d.blockOffset[k] = offset;
                d.blockLength[k] = len;
                offset += len;
            }
            ca.sprites.add(d);
            long vanilla = 0;
            for (int i = 0; i <= mip; i++) vanilla += (long) Math.max(1, stripW >> i) * Math.max(1, stripH >> i) * 4;
            ca.vanillaRamBytes += vanilla;
        }
        ca.slab = MemoryUtil.memAlloc(Math.max(offset, 16));
        return true;
    }

    /** Encodes every sprite's aligned levels into the slab, one pool task per sprite. */
    private static CompletableFuture<Void> encodeSprites(CompressedAtlas ca, int mip, Bc7Encoder.Preset preset, Executor executor) {
        AtomicInteger hits = new AtomicInteger(), misses = new AtomicInteger();
        SpriteCache cache = Config.get(Config.CACHE_ENABLED) ? SpriteCache.get() : null;
        AtomicLong threadNanos = new AtomicLong();
        long tSprites = System.nanoTime();
        List<CompletableFuture<Void>> jobs = new ArrayList<>(ca.sprites.size());
        for (Bc7SpriteData d : ca.sprites) {
            jobs.add(CompletableFuture.runAsync(() -> {
                long t = System.nanoTime();
                if (encodeSprite(ca, d, mip, preset, cache)) hits.incrementAndGet();
                else misses.incrementAndGet();
                threadNanos.addAndGet(System.nanoTime() - t);
            }, executor));
        }
        return CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).thenRun(() -> {
            ca.spriteWallNanos = System.nanoTime() - tSprites;
            ca.spriteThreadNanos = threadNanos.get();
            ca.cacheHits = hits.get();
            ca.cacheMisses = misses.get();
            if (cache != null) cache.flushAsync();
        });
    }

    /** Builds every atlas level: aligned levels by memcpy, deep levels encoded atlas-wide in parallel bands. */
    private static CompletableFuture<Void> assemble(CompressedAtlas ca, int mip, Bc7Encoder.Preset preset, Executor executor) {
        long tAssemble = System.nanoTime();
        List<CompletableFuture<Void>> jobs = new ArrayList<>();
        ca.levelBlocks = new ByteBuffer[mip + 1];
        ca.deepRgba = new int[ca.deepLevels][];
        if (SelfTest.ENABLED) ca.expectedRgba = new int[mip + 1][];
        for (int i = 0; i <= mip; i++) {
            int lw = ca.levelWidth(i), lh = ca.levelHeight(i);
            int size = Bc7Encoder.encodedSize(lw, lh);
            ca.vanillaVramBytes += (long) lw * lh * 4;
            ca.vramBytes += size;
            if (i < ca.alignedLevels) {
                ca.levelBlocks[i] = assembleAligned(ca, i, lw, lh, size);
            } else {
                assembleDeep(ca, i, lw, lh, size, preset, executor, jobs);
            }
        }
        return CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).thenRun(() -> {
            if (SelfTest.ENABLED) {
                for (int i = 0; i <= mip; i++) ca.expectedRgba[i] = SelfTest.expectedLevel(ca, i);
            }
            ca.assembleNanos = System.nanoTime() - tAssemble;
        });
    }

    /** Returns true on a cache hit. */
    private static boolean encodeSprite(CompressedAtlas ca, Bc7SpriteData d, int mip, Bc7Encoder.Preset preset, SpriteCache cache) {
        SpriteContents c = d.contents;
        // Normalised source levels (whole frame strip). NeoForge allocates levels beyond the sprite's own
        // maximum without initialising them; replace those with a deterministic box filter.
        int[][] lv = new int[mip + 1][];
        int[] lw = new int[mip + 1], lh = new int[mip + 1];
        int maxValid = ClientHooks.getMaxMipmapLevel(d.stripW, d.stripH);
        lw[0] = d.stripW;
        lh[0] = d.stripH;
        lv[0] = c.byMipLevel[0].getPixelsRGBA();
        for (int i = 1; i <= mip; i++) {
            lw[i] = Math.max(1, lw[i - 1] >> 1);
            lh[i] = Math.max(1, lh[i - 1] >> 1);
            NativeImage img = c.byMipLevel[i];
            if (i <= maxValid && img.getWidth() == lw[i] && img.getHeight() == lh[i]) {
                lv[i] = img.getPixelsRGBA();
            } else {
                lv[i] = SpriteSampler.boxDown(lv[i - 1], lw[i - 1], lh[i - 1], lw[i], lh[i]);
            }
        }
        // Alpha mask (vanilla isTransparent: alpha == 0).
        int[] base = lv[0];
        for (int k = 0; k < base.length; k++) {
            if ((base[k] >>> 24) == 0) d.transparentMask[k >>> 6] |= 1L << (k & 63);
        }

        SpriteCache.Key key = cache != null ? SpriteCache.key(d.cacheHeader(preset.ordinal()), lv) : null;
        if (key != null && cache.load(key, d)) {
            if (SelfTest.ENABLED) captureSelfTestSlots(d, mip, lv, lw, lh);
            return true;
        }

        Bc7Encoder enc = Bc7Encoder.forThread(preset);
        for (int f = 0; f < d.frameSlot.length; f++) {
            int k = d.frameSlot[f];
            if (k < 0) continue;
            int fx = (f % d.framesPerRow) * d.w, fy = (f / d.framesPerRow) * d.h;
            for (int i = 0; i <= mip; i++) {
                int sw = d.slotW >> i, sh = d.slotH >> i;
                int[] slot = new int[sw * sh];
                SpriteSampler.sampleSlot(lv[i], lw[i], lh[i], fx >> i, fy >> i, Math.max(1, d.w >> i), Math.max(1, d.h >> i), sw, sh, slot);
                if (i < ca.alignedLevels) {
                    int idx = k * ca.alignedLevels + i;
                    byte[] out = new byte[d.blockLength[idx]];
                    enc.encodeImage(slot, 0, sw, sw, sh, out, 0);
                    ca.slab.put(d.blockOffset[idx], out);
                } else {
                    d.deep[k * ca.deepLevels + (i - ca.alignedLevels)] = slot;
                }
            }
        }
        if (SelfTest.ENABLED) captureSelfTestSlots(d, mip, lv, lw, lh);
        if (key != null) cache.store(key, d);
        return false;
    }

    private static void captureSelfTestSlots(Bc7SpriteData d, int mip, int[][] lv, int[] lw, int[] lh) {
        int f = d.firstFrame;
        int fx = (f % d.framesPerRow) * d.w, fy = (f / d.framesPerRow) * d.h;
        d.selfTestSlots = new int[mip + 1][];
        for (int i = 0; i <= mip; i++) {
            int sw = d.slotW >> i, sh = d.slotH >> i;
            int[] slot = new int[sw * sh];
            SpriteSampler.sampleSlot(lv[i], lw[i], lh[i], fx >> i, fy >> i, Math.max(1, d.w >> i), Math.max(1, d.h >> i), sw, sh, slot);
            d.selfTestSlots[i] = slot;
        }
    }

    private static ByteBuffer assembleAligned(CompressedAtlas ca, int level, int lw, int lh, int size) {
        ByteBuffer buf = MemoryUtil.memAlloc(size);
        byte[] empty = new byte[16];
        Bc7Tables.writeTransparentBlock(empty, 0);
        for (int o = 0; o < size; o += 16) buf.put(o, empty);
        int rowBlocks = (lw + 3) >> 2;
        for (Bc7SpriteData d : ca.sprites) {
            ByteBuffer src = d.blocks(d.firstFrame, level);
            int bw = (d.slotW >> level) >> 2, bh = (d.slotH >> level) >> 2;
            int bx = (d.x >> level) >> 2, by = (d.y >> level) >> 2;
            for (int r = 0; r < bh; r++) {
                buf.put(((by + r) * rowBlocks + bx) * 16, src, r * bw * 16, bw * 16);
            }
        }
        return buf;
    }

    /** Paints deep level {@code level} from the sprites' deep RGBA and adds its band-encode tasks to {@code out}. */
    private static void assembleDeep(CompressedAtlas ca, int level, int lw, int lh, int size, Bc7Encoder.Preset preset, Executor executor,
                                     List<CompletableFuture<Void>> pending) {
        int[] lvl = new int[lw * lh];
        for (Bc7SpriteData d : ca.sprites) {
            int[] slot = d.deepPixels(d.firstFrame, level);
            int ox = d.x >> level, oy = d.y >> level, sw = d.slotW >> level, sh = d.slotH >> level;
            for (int y = 0; y < sh && oy + y < lh; y++) {
                System.arraycopy(slot, y * sw, lvl, (oy + y) * lw + ox, Math.min(sw, lw - ox));
            }
        }
        ca.deepRgba[level - ca.alignedLevels] = lvl;
        // Encode in horizontal bands of block rows.
        int blockRows = (lh + 3) >> 2, rowBytes = ((lw + 3) >> 2) * 16;
        byte[] out = new byte[size];
        int band = Math.max(1, 4096 / Math.max(1, rowBytes / 16));
        List<CompletableFuture<Void>> jobs = new ArrayList<>();
        for (int r0 = 0; r0 < blockRows; r0 += band) {
            final int start = r0, end = Math.min(blockRows, r0 + band);
            jobs.add(CompletableFuture.runAsync(() -> {
                int py = start * 4, ph = Math.min(lh, end * 4) - py;
                Bc7Encoder.forThread(preset).encodeImage(lvl, py * lw, lw, lw, ph, out, start * rowBytes);
            }, executor));
        }
        pending.add(CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).thenRun(() -> {
            ByteBuffer buf = MemoryUtil.memAlloc(size);
            buf.put(0, out);
            ca.levelBlocks[level] = buf;
        }));
    }
}
