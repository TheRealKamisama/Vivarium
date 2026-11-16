package dev.vivarium.codec;

import dev.vivarium.codec.Bc7Tables.Mode;

/**
 * Logical contents of one BC7 block (before bit packing). Endpoint {@code e} of subset {@code s} is
 * {@code 2*s + e}. Raw endpoint values exclude the p-bit; {@link #pbits} holds one p-bit per endpoint (for
 * shared-p-bit modes both endpoints of a subset carry the same value).
 */
final class Bc7Block {
    int mode;
    int partition;
    int rotation;
    int indexSelection;
    final int[][] raw = new int[6][4];
    final int[] pbits = new int[6];
    final int[] idx = new int[16];
    final int[] idx2 = new int[16];

    void copyFrom(Bc7Block o) {
        mode = o.mode;
        partition = o.partition;
        rotation = o.rotation;
        indexSelection = o.indexSelection;
        for (int i = 0; i < 6; i++) {
            System.arraycopy(o.raw[i], 0, raw[i], 0, 4);
            pbits[i] = o.pbits[i];
        }
        System.arraycopy(o.idx, 0, idx, 0, 16);
        System.arraycopy(o.idx2, 0, idx2, 0, 16);
    }

    /** Anchor fix-up: an anchor pixel's index must have its MSB clear; swap endpoints and invert indices if not. */
    void fixAnchors() {
        Mode m = Bc7Tables.MODES[mode];
        int ib = m.indexBits();
        int max = (1 << ib) - 1;
        for (int s = 0; s < m.subsets(); s++) {
            int anchor = s == 0 ? 0 : Bc7Tables.ANCHOR2[partition];
            if (idx[anchor] > (max >> 1)) {
                swapEndpoints(s, m, false);
                for (int i = 0; i < 16; i++) {
                    if (subsetOf(i) == s) idx[i] = max - idx[i];
                }
            }
        }
        if (m.index2Bits() > 0) {
            int max2 = (1 << m.index2Bits()) - 1;
            if (idx2[0] > (max2 >> 1)) {
                // Secondary indices drive alpha (mode 5, or mode 4 with indexSelection 0) or colour (mode 4 sel 1).
                boolean secondaryIsAlpha = mode == 5 || indexSelection == 0;
                swapChannels(secondaryIsAlpha);
                for (int i = 0; i < 16; i++) idx2[i] = max2 - idx2[i];
            }
        }
    }

    int subsetOf(int pixel) {
        return Bc7Tables.MODES[mode].subsets() == 1 ? 0 : Bc7Tables.subsetOf2(partition, pixel);
    }

    private void swapEndpoints(int s, Mode m, boolean unused) {
        if (m.index2Bits() > 0) {
            // Single-subset dual-index modes: primary indices drive colour unless mode 4 selects otherwise.
            boolean primaryIsAlpha = mode == 4 && indexSelection == 1;
            swapChannels(primaryIsAlpha);
            return;
        }
        int a = 2 * s, b = 2 * s + 1;
        int[] t = raw[a];
        System.arraycopy(raw[b], 0, tmp, 0, 4);
        System.arraycopy(t, 0, raw[b], 0, 4);
        System.arraycopy(tmp, 0, raw[a], 0, 4);
        int p = pbits[a];
        pbits[a] = pbits[b];
        pbits[b] = p;
    }

    private final int[] tmp = new int[4];

    /** Swap endpoints for only the alpha channel or only the colour channels (dual-index modes). */
    private void swapChannels(boolean alpha) {
        if (alpha) {
            int t = raw[0][3];
            raw[0][3] = raw[1][3];
            raw[1][3] = t;
        } else {
            for (int c = 0; c < 3; c++) {
                int t = raw[0][c];
                raw[0][c] = raw[1][c];
                raw[1][c] = t;
            }
        }
    }

    void pack(byte[] out, int off, Bits128 bits) {
        Mode m = Bc7Tables.MODES[mode];
        bits.reset();
        bits.write(1 << mode, mode + 1);
        if (m.partitionBits() > 0) bits.write(partition, m.partitionBits());
        if (m.rotationBits() > 0) bits.write(rotation, m.rotationBits());
        if (m.indexSelectionBits() > 0) bits.write(indexSelection, 1);
        int endpoints = m.subsets() * 2;
        for (int c = 0; c < 3; c++) {
            for (int e = 0; e < endpoints; e++) bits.write(raw[e][c], m.colorBits());
        }
        if (m.hasAlpha()) {
            for (int e = 0; e < endpoints; e++) bits.write(raw[e][3], m.alphaBits());
        }
        if (m.pbitType() == 2) {
            for (int e = 0; e < endpoints; e++) bits.write(pbits[e], 1);
        } else if (m.pbitType() == 1) {
            for (int s = 0; s < m.subsets(); s++) bits.write(pbits[2 * s], 1);
        }
        writeIndices(bits, idx, m.indexBits(), m);
        if (m.index2Bits() > 0) writeIndices(bits, idx2, m.index2Bits(), m);
        bits.store(out, off);
    }

    private void writeIndices(Bits128 bits, int[] ind, int ib, Mode m) {
        int anchor2 = m.subsets() == 2 ? Bc7Tables.ANCHOR2[partition] : -1;
        for (int i = 0; i < 16; i++) {
            boolean anchor = i == 0 || i == anchor2;
            bits.write(ind[i], anchor ? ib - 1 : ib);
        }
    }
}
