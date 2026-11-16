package dev.vivarium.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class Bc7CodecTest {
    private static int abgr(int r, int g, int b, int a) {
        return (a << 24) | (b << 16) | (g << 8) | r;
    }

    @Test
    void partitionTablesAreConsistent() {
        for (int p = 0; p < 64; p++) {
            assertEquals(0, Bc7Tables.subsetOf2(p, 0), "pixel 0 must be in subset 0, partition " + p);
            assertEquals(1, Bc7Tables.subsetOf2(p, Bc7Tables.ANCHOR2[p]), "anchor must be in subset 1, partition " + p);
            int ones = Integer.bitCount(Bc7Tables.PARTITION2[p]);
            assertTrue(ones > 0 && ones < 16, "partition " + p + " must have two non-empty subsets");
        }
        // Anchor is the first pixel of subset 1 in scan order for the spec's table? Not required by the spec,
        // but every anchor must be unique-able: check no partition is duplicated.
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int m : Bc7Tables.PARTITION2) assertTrue(seen.add(m), "duplicate partition mask " + Integer.toHexString(m));
    }

    @Test
    void transparentBlockDecodesToZero() {
        byte[] b = new byte[16];
        Bc7Tables.writeTransparentBlock(b, 0);
        int[] out = new int[16];
        new Bc7Decoder().decodeBlock(b, 0, out);
        for (int v : out) assertEquals(0, v);
    }

    @Test
    void solidColoursAreExact() {
        Bc7Encoder enc = new Bc7Encoder(Bc7Encoder.Preset.NORMAL);
        Bc7Decoder dec = new Bc7Decoder();
        Random rnd = new Random(1);
        byte[] out = new byte[16];
        int[] block = new int[16], back = new int[16];
        for (int t = 0; t < 2000; t++) {
            int c = t < 256 ? abgr(t, t, t, 255) : abgr(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256), t % 3 == 0 ? rnd.nextInt(256) : 255);
            java.util.Arrays.fill(block, c);
            if ((c >>> 24) == 0) continue;
            enc.encodeBlock(block, out, 0);
            dec.decodeBlock(out, 0, back);
            assertArrayEquals(block, back, "solid colour " + Integer.toHexString(c));
        }
    }

    @Test
    void cutoutAlphaIsPreservedExactly() {
        for (Bc7Encoder.Preset preset : Bc7Encoder.Preset.values()) {
            Bc7Encoder enc = new Bc7Encoder(preset);
            Bc7Decoder dec = new Bc7Decoder();
            Random rnd = new Random(2);
            byte[] out = new byte[16];
            int[] block = new int[16], back = new int[16];
            for (int t = 0; t < 3000; t++) {
                for (int i = 0; i < 16; i++) {
                    boolean visible = rnd.nextInt(3) != 0;
                    block[i] = abgr(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256), visible ? 255 : 0);
                }
                enc.encodeBlock(block, out, 0);
                dec.decodeBlock(out, 0, back);
                for (int i = 0; i < 16; i++) {
                    assertEquals(block[i] >>> 24, back[i] >>> 24, preset + ": alpha of texel " + i + " in block " + t);
                }
            }
        }
    }

    @Test
    void mixedAlphaKeepsZeroAndOpaqueExact() {
        Bc7Encoder enc = new Bc7Encoder(Bc7Encoder.Preset.NORMAL);
        Bc7Decoder dec = new Bc7Decoder();
        Random rnd = new Random(3);
        byte[] out = new byte[16];
        int[] block = new int[16], back = new int[16];
        for (int t = 0; t < 3000; t++) {
            for (int i = 0; i < 16; i++) {
                int k = rnd.nextInt(4);
                int a = k == 0 ? 0 : k == 1 ? 255 : 40 + rnd.nextInt(200);
                block[i] = abgr(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256), a);
            }
            enc.encodeBlock(block, out, 0);
            dec.decodeBlock(out, 0, back);
            for (int i = 0; i < 16; i++) {
                int sa = block[i] >>> 24, da = back[i] >>> 24;
                if (sa == 0 || sa == 255) assertEquals(sa, da, "alpha " + sa + " must be exact, block " + t);
            }
        }
    }

    /**
     * Adversarial alpha near the cutout threshold (seen in modded textures): the full alpha rule must hold for
     * every preset, including blocks where no regular mode satisfies it and the mode-5 safety net is used.
     */
    @Test
    void alphaRuleHoldsNearCutoutThreshold() {
        int[] palette = {0, 1, 12, 25, 26, 30, 40, 60, 100, 200, 254, 255};
        for (Bc7Encoder.Preset preset : Bc7Encoder.Preset.values()) {
            Bc7Encoder enc = new Bc7Encoder(preset);
            Bc7Decoder dec = new Bc7Decoder();
            Random rnd = new Random(5);
            byte[] out = new byte[16];
            int[] block = new int[16], back = new int[16];
            for (int t = 0; t < 4000; t++) {
                for (int i = 0; i < 16; i++) {
                    int a = palette[rnd.nextInt(palette.length)];
                    block[i] = abgr(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256), a);
                }
                enc.encodeBlock(block, out, 0);
                dec.decodeBlock(out, 0, back);
                Metrics.Result r = Metrics.compare(block, back, 16);
                assertEquals(0, r.alphaRuleViolations(), preset + ": block " + t);
            }
        }
    }

    /** Pixel-art-like content: few colours per block, sharp edges. */
    @Test
    void pixelArtQuality() {
        Random rnd = new Random(4);
        int w = 64, h = 64;
        int[] img = new int[w * h];
        int[] palette = new int[8];
        for (int i = 0; i < palette.length; i++) {
            int base = 60 + rnd.nextInt(120);
            palette[i] = abgr(clamp(base + rnd.nextInt(60) - 30), clamp(base + rnd.nextInt(60) - 30), clamp(base / 2), 255);
        }
        for (int i = 0; i < img.length; i++) img[i] = palette[rnd.nextInt(palette.length)];
        for (Bc7Encoder.Preset preset : Bc7Encoder.Preset.values()) {
            byte[] out = new byte[Bc7Encoder.encodedSize(w, h)];
            long t0 = System.nanoTime();
            new Bc7Encoder(preset).encodeImage(img, 0, w, w, h, out, 0);
            long t1 = System.nanoTime();
            int[] back = new int[w * h];
            new Bc7Decoder().decodeImage(out, 0, w, h, back);
            Metrics.Result r = Metrics.compare(img, back, img.length);
            System.out.printf("%s: PSNR %.2f dB, max err %d, %.1f us/block%n", preset, r.psnr(), r.maxError(), (t1 - t0) / 1000.0 / (w * h / 16));
            assertTrue(r.psnr() > 30, preset + " PSNR " + r.psnr());
        }
    }

    @Test
    void smoothGradientsAreHighQuality() {
        int w = 32, h = 32;
        int[] img = new int[w * h];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img[y * w + x] = abgr(x * 8, y * 8, (x + y) * 4, 255);
        byte[] out = new byte[Bc7Encoder.encodedSize(w, h)];
        new Bc7Encoder(Bc7Encoder.Preset.NORMAL).encodeImage(img, 0, w, w, h, out, 0);
        int[] back = new int[w * h];
        new Bc7Decoder().decodeImage(out, 0, w, h, back);
        Metrics.Result r = Metrics.compare(img, back, img.length);
        assertTrue(r.psnr() > 40, "gradient PSNR " + r.psnr());
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
