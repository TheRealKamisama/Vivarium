package dev.vivarium.codec;

import dev.vivarium.codec.Bc7Tables.Mode;

/**
 * Spec-exact BC7 decoder for the modes this mod emits (1, 3, 4, 5, 6, 7). Blocks in modes 0 and 2 (three
 * subsets) or reserved modes decode to transparent black; the encoder never produces them.
 *
 * <p>Pixels are packed like {@code NativeImage}: {@code 0xAABBGGRR} (red in the low byte).
 */
public final class Bc7Decoder {
    private final Bits128 bits = new Bits128();
    private final int[][] ep = new int[6][4];

    public void decodeBlock(byte[] src, int off, int[] out16) {
        bits.load(src, off);
        int mode = 0;
        while (mode < 8 && bits.read(1) == 0) mode++;
        Mode m = mode < 8 ? Bc7Tables.MODES[mode] : null;
        if (m == null) {
            java.util.Arrays.fill(out16, 0);
            return;
        }
        int partition = m.partitionBits() > 0 ? bits.read(m.partitionBits()) : 0;
        int rotation = m.rotationBits() > 0 ? bits.read(m.rotationBits()) : 0;
        int indexSel = m.indexSelectionBits() > 0 ? bits.read(1) : 0;
        int endpoints = m.subsets() * 2;
        for (int c = 0; c < 3; c++) {
            for (int e = 0; e < endpoints; e++) ep[e][c] = bits.read(m.colorBits());
        }
        if (m.hasAlpha()) {
            for (int e = 0; e < endpoints; e++) ep[e][3] = bits.read(m.alphaBits());
        }
        int cb = m.colorBits(), ab = m.alphaBits();
        if (m.pbitType() != 0) {
            int[] p = new int[6];
            if (m.pbitType() == 2) {
                for (int e = 0; e < endpoints; e++) p[e] = bits.read(1);
            } else {
                for (int s = 0; s < m.subsets(); s++) {
                    int v = bits.read(1);
                    p[2 * s] = v;
                    p[2 * s + 1] = v;
                }
            }
            for (int e = 0; e < endpoints; e++) {
                for (int c = 0; c < 3; c++) ep[e][c] = (ep[e][c] << 1) | p[e];
                if (m.hasAlpha()) ep[e][3] = (ep[e][3] << 1) | p[e];
            }
            cb++;
            if (m.hasAlpha()) ab++;
        }
        for (int e = 0; e < endpoints; e++) {
            for (int c = 0; c < 3; c++) ep[e][c] = Bc7Tables.expand(ep[e][c], cb);
            ep[e][3] = m.hasAlpha() ? Bc7Tables.expand(ep[e][3], ab) : 255;
        }

        int anchor2 = m.subsets() == 2 ? Bc7Tables.ANCHOR2[partition] : -1;
        int[] idx = readIndices(m.indexBits(), anchor2);
        int[] idx2 = m.index2Bits() > 0 ? readIndices(m.index2Bits(), -1) : null;
        int[] w1 = Bc7Tables.weights(m.indexBits());
        int[] w2 = idx2 != null ? Bc7Tables.weights(m.index2Bits()) : null;

        for (int i = 0; i < 16; i++) {
            int s = m.subsets() == 2 ? Bc7Tables.subsetOf2(partition, i) : 0;
            int[] e0 = ep[2 * s], e1 = ep[2 * s + 1];
            int wc, wa;
            if (idx2 == null) {
                wc = w1[idx[i]];
                wa = wc;
            } else if (indexSel == 0) {
                wc = w1[idx[i]];
                wa = w2[idx2[i]];
            } else {
                wc = w2[idx2[i]];
                wa = w1[idx[i]];
            }
            int r = Bc7Tables.interpolate(e0[0], e1[0], wc);
            int g = Bc7Tables.interpolate(e0[1], e1[1], wc);
            int b = Bc7Tables.interpolate(e0[2], e1[2], wc);
            int a = Bc7Tables.interpolate(e0[3], e1[3], wa);
            switch (rotation) {
                case 1 -> { int t = a; a = r; r = t; }
                case 2 -> { int t = a; a = g; g = t; }
                case 3 -> { int t = a; a = b; b = t; }
                default -> {}
            }
            out16[i] = (a << 24) | (b << 16) | (g << 8) | r;
        }
    }

    private int[] readIndices(int ib, int anchor2) {
        int[] r = new int[16];
        for (int i = 0; i < 16; i++) {
            boolean anchor = i == 0 || i == anchor2;
            r[i] = bits.read(anchor ? ib - 1 : ib);
        }
        return r;
    }

    /**
     * Decodes {@code w x h} pixels starting at block grid origin into {@code dst} (row stride {@code w}).
     * {@code src} holds {@code ceil(w/4) * ceil(h/4)} blocks in row-major order starting at {@code srcOff}.
     */
    public void decodeImage(byte[] src, int srcOff, int w, int h, int[] dst) {
        int bw = (w + 3) >> 2, bh = (h + 3) >> 2;
        int[] px = new int[16];
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                decodeBlock(src, srcOff + (by * bw + bx) * 16, px);
                for (int y = 0; y < 4; y++) {
                    int yy = by * 4 + y;
                    if (yy >= h) break;
                    for (int x = 0; x < 4; x++) {
                        int xx = bx * 4 + x;
                        if (xx >= w) break;
                        dst[yy * w + xx] = px[y * 4 + x];
                    }
                }
            }
        }
    }
}
