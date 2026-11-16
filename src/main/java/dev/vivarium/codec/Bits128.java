package dev.vivarium.codec;

/** LSB-first 128-bit stream, matching BC7's bit order (bit 0 = least significant bit of byte 0). */
final class Bits128 {
    long lo;
    long hi;
    int pos;

    void reset() {
        lo = 0;
        hi = 0;
        pos = 0;
    }

    void write(int value, int bits) {
        for (int i = 0; i < bits; i++) {
            if (((value >>> i) & 1) != 0) {
                int p = pos + i;
                if (p < 64) lo |= 1L << p;
                else hi |= 1L << (p - 64);
            }
        }
        pos += bits;
    }

    int read(int bits) {
        int v = 0;
        for (int i = 0; i < bits; i++) {
            int p = pos + i;
            long bit = p < 64 ? (lo >>> p) & 1L : (hi >>> (p - 64)) & 1L;
            v |= (int) bit << i;
        }
        pos += bits;
        return v;
    }

    void load(byte[] src, int off) {
        lo = 0;
        hi = 0;
        for (int i = 0; i < 8; i++) {
            lo |= (src[off + i] & 0xFFL) << (8 * i);
            hi |= (src[off + 8 + i] & 0xFFL) << (8 * i);
        }
        pos = 0;
    }

    void store(byte[] dst, int off) {
        for (int i = 0; i < 8; i++) {
            dst[off + i] = (byte) (lo >>> (8 * i));
            dst[off + 8 + i] = (byte) (hi >>> (8 * i));
        }
    }
}
