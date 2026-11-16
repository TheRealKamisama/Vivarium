package dev.vivarium.codec;

/**
 * Constant tables from the BPTC (BC7) specification (Khronos Data Format Specification, "BPTC Compressed
 * Texture Image Formats"; identical to D3D11 BC7).
 *
 * <p>Pixel order inside a 4x4 block is row-major: {@code i = y * 4 + x}.
 */
public final class Bc7Tables {
    private Bc7Tables() {}

    public static final int[] WEIGHTS2 = {0, 21, 43, 64};
    public static final int[] WEIGHTS3 = {0, 9, 18, 27, 37, 46, 55, 64};
    public static final int[] WEIGHTS4 = {0, 4, 9, 13, 17, 21, 26, 30, 34, 38, 43, 47, 51, 55, 60, 64};

    public static int[] weights(int indexBits) {
        return switch (indexBits) {
            case 2 -> WEIGHTS2;
            case 3 -> WEIGHTS3;
            case 4 -> WEIGHTS4;
            default -> throw new IllegalArgumentException("index bits " + indexBits);
        };
    }

    /** Two-subset partitions as 16-bit masks; bit {@code i} set = pixel {@code i} belongs to subset 1. */
    public static final int[] PARTITION2 = {
        0xCCCC, 0x8888, 0xEEEE, 0xECC8, 0xC880, 0xFEEC, 0xFEC8, 0xEC80,
        0xC800, 0xFFEC, 0xFE80, 0xE800, 0xFFE8, 0xFF00, 0xFFF0, 0xF000,
        0xF710, 0x008E, 0x7100, 0x08CE, 0x008C, 0x7310, 0x3100, 0x8CCE,
        0x088C, 0x3110, 0x6666, 0x366C, 0x17E8, 0x0FF0, 0x718E, 0x399C,
        0xAAAA, 0xF0F0, 0x5A5A, 0x33CC, 0x3C3C, 0x55AA, 0x9696, 0xA55A,
        0x73CE, 0x13C8, 0x324C, 0x3BDC, 0x6996, 0xC33C, 0x9966, 0x0660,
        0x0272, 0x04E4, 0x4E40, 0x2720, 0xC936, 0x936C, 0x39C6, 0x639C,
        0x9336, 0x9CC6, 0x817E, 0xE718, 0xCCF0, 0x0FCC, 0x7744, 0xEE22,
    };

    /** Anchor pixel index of subset 1 for each two-subset partition (subset 0's anchor is always pixel 0). */
    public static final int[] ANCHOR2 = {
        15, 15, 15, 15, 15, 15, 15, 15,
        15, 15, 15, 15, 15, 15, 15, 15,
        15, 2, 8, 2, 2, 8, 8, 15,
        2, 8, 2, 2, 8, 8, 2, 2,
        15, 15, 6, 8, 2, 8, 15, 15,
        2, 8, 2, 2, 2, 15, 15, 6,
        6, 2, 6, 8, 15, 15, 2, 2,
        15, 15, 15, 15, 15, 2, 2, 15,
    };

    public static int subsetOf2(int partition, int pixel) {
        return (PARTITION2[partition] >>> pixel) & 1;
    }

    /** Expands an n-bit endpoint value (pbit already appended, if any) to 8 bits. */
    public static int expand(int v, int bits) {
        if (bits >= 8) return v;
        v <<= (8 - bits);
        return v | (v >>> bits);
    }

    public static int interpolate(int e0, int e1, int weight) {
        return ((64 - weight) * e0 + weight * e1 + 32) >> 6;
    }

    /** One BC7 mode's layout. Only the modes this mod emits or decodes are described (1, 3, 4, 5, 6, 7). */
    public record Mode(
        int id,
        int subsets,
        int partitionBits,
        int rotationBits,
        int indexSelectionBits,
        int colorBits,
        int alphaBits,
        /* 0 = none, 1 = one shared per subset, 2 = one per endpoint */
        int pbitType,
        int indexBits,
        int index2Bits
    ) {
        public boolean hasAlpha() {
            return alphaBits > 0;
        }

        public int endpointBitsColor() {
            return colorBits + (pbitType != 0 ? 1 : 0);
        }

        public int endpointBitsAlpha() {
            // Modes 4/5 store alpha without p-bits; modes 6/7 share the endpoint p-bit with colour.
            return alphaBits + (pbitType != 0 ? 1 : 0);
        }
    }

    public static final Mode[] MODES = new Mode[8];

    static {
        MODES[1] = new Mode(1, 2, 6, 0, 0, 6, 0, 1, 3, 0);
        MODES[3] = new Mode(3, 2, 6, 0, 0, 7, 0, 2, 2, 0);
        MODES[4] = new Mode(4, 1, 0, 2, 1, 5, 6, 0, 2, 3);
        MODES[5] = new Mode(5, 1, 0, 2, 0, 7, 8, 0, 2, 2);
        MODES[6] = new Mode(6, 1, 0, 0, 0, 7, 7, 2, 4, 0);
        MODES[7] = new Mode(7, 2, 6, 0, 0, 5, 5, 2, 2, 0);
    }

    /** A block that every decoder turns into transparent black (mode 6, all endpoints and p-bits zero). */
    public static void writeTransparentBlock(byte[] out, int off) {
        out[off] = 0x40;
        for (int i = 1; i < 16; i++) out[off + i] = 0;
    }
}
