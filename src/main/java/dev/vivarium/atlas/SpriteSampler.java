package dev.vivarium.atlas;

import com.mojang.blaze3d.platform.NativeImage;

/** Copies a sprite frame into a slot-sized buffer, extending edges into the slot padding. */
final class SpriteSampler {
    private SpriteSampler() {}

    /**
     * @param src    pixels of one mip level of the source image ({@code srcW x srcH})
     * @param fx,fy  frame origin in {@code src}
     * @param fw,fh  frame size at this level (at least 1)
     */
    static void sampleSlot(int[] src, int srcW, int srcH, int fx, int fy, int fw, int fh, int slotW, int slotH, int[] out) {
        for (int y = 0; y < slotH; y++) {
            int sy = Math.min(fy + Math.min(y, fh - 1), srcH - 1);
            int row = sy * srcW;
            for (int x = 0; x < slotW; x++) {
                int sx = Math.min(fx + Math.min(x, fw - 1), srcW - 1);
                out[y * slotW + x] = src[row + sx];
            }
        }
    }

    static void sampleSlot(NativeImage src, int fx, int fy, int fw, int fh, int slotW, int slotH, int[] out) {
        int srcW = src.getWidth(), srcH = src.getHeight();
        for (int y = 0; y < slotH; y++) {
            int sy = Math.min(fy + Math.min(y, fh - 1), srcH - 1);
            for (int x = 0; x < slotW; x++) {
                int sx = Math.min(fx + Math.min(x, fw - 1), srcW - 1);
                out[y * slotW + x] = src.getPixelRGBA(sx, sy);
            }
        }
    }

    /** 2x2 box filter with edge clamping, used to replace NeoForge's uninitialised extra mip levels. */
    static int[] boxDown(int[] src, int w, int h, int nw, int nh) {
        int[] out = new int[nw * nh];
        for (int y = 0; y < nh; y++) {
            for (int x = 0; x < nw; x++) {
                int x0 = Math.min(2 * x, w - 1), x1 = Math.min(2 * x + 1, w - 1);
                int y0 = Math.min(2 * y, h - 1), y1 = Math.min(2 * y + 1, h - 1);
                int a = src[y0 * w + x0], b = src[y0 * w + x1], c = src[y1 * w + x0], d = src[y1 * w + x1];
                int r = 0;
                for (int s = 0; s < 32; s += 8) {
                    int v = (((a >>> s) & 0xFF) + ((b >>> s) & 0xFF) + ((c >>> s) & 0xFF) + ((d >>> s) & 0xFF) + 2) >> 2;
                    r |= v << s;
                }
                out[y * nw + x] = r;
            }
        }
        return out;
    }
}
