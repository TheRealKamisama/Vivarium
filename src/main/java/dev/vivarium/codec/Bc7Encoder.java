package dev.vivarium.codec;

import dev.vivarium.codec.Bc7Tables.Mode;

/**
 * Pure-Java BC7 encoder. Not thread-safe: use one instance per thread (see {@link #forThread(Preset)}).
 *
 * <p>Strategy (after the ideas in Rich Geldreich's bc7enc and the BPTC spec): solid blocks are encoded
 * exactly with mode 5; other blocks try single-subset modes 6 (RGBA, 4-bit indices) and, when alpha varies,
 * 5 and 4 (separate alpha indices); then two-subset modes 1/3 (opaque) or 7 (alpha) on the best-ranked
 * partitions. Each fit is PCA + least-squares refinement with p-bit search. Every candidate is packed and
 * decoded to measure its real error and to enforce the alpha rule: source alpha 0 must decode to 0 and
 * source alpha 255 to 255, and no texel may cross the cutout threshold, so cutout textures get neither holes
 * nor fringes.
 *
 * <p>Pixels are {@code 0xAABBGGRR}, like {@code NativeImage}.
 */
public final class Bc7Encoder {
    public enum Preset {
        /** Runtime / animation patching: single-subset modes only, one refinement pass. */
        FAST(0, 1, false),
        /** Default for load-time encoding. */
        NORMAL(6, 2, false),
        /** Best quality: more partitions, rotations, endpoint jitter. */
        SLOW(24, 3, true);

        final int partitions;
        final int refinePasses;
        final boolean exhaustive;

        Preset(int partitions, int refinePasses, boolean exhaustive) {
            this.partitions = partitions;
            this.refinePasses = refinePasses;
            this.exhaustive = exhaustive;
        }
    }

    /** Alpha below this is discarded by vanilla cutout shaders ({@code a < 0.1}). */
    public static final int CUTOUT_THRESHOLD = 26;
    public static final int ENCODER_VERSION = 2;

    private static final ThreadLocal<Bc7Encoder[]> PER_THREAD = ThreadLocal.withInitial(() -> new Bc7Encoder[Preset.values().length]);

    public static Bc7Encoder forThread(Preset preset) {
        Bc7Encoder[] arr = PER_THREAD.get();
        Bc7Encoder e = arr[preset.ordinal()];
        if (e == null) arr[preset.ordinal()] = e = new Bc7Encoder(preset);
        return e;
    }

    // ---- quantisation tables: QUANT[rawBits][pbit + 1][target] -> raw endpoint value ----
    private static final int[][][] QUANT = new int[9][3][256];
    /** Exact single-colour encodings for mode 5: per 8-bit value, (e0 << 8 | e1) of 7-bit endpoints at index 1. */
    private static final int[] SOLID5 = new int[256];

    static {
        for (int rb = 4; rb <= 8; rb++) {
            for (int p = -1; p <= 1; p++) {
                int n = 1 << rb;
                int[] table = QUANT[rb][p + 1];
                for (int t = 0; t < 256; t++) {
                    int best = 0, bestErr = Integer.MAX_VALUE;
                    for (int r = 0; r < n; r++) {
                        int v = p < 0 ? Bc7Tables.expand(r, rb) : Bc7Tables.expand((r << 1) | p, rb + 1);
                        int err = Math.abs(v - t);
                        if (err < bestErr) {
                            bestErr = err;
                            best = r;
                        }
                    }
                    table[t] = best;
                }
            }
        }
        for (int c = 0; c < 256; c++) {
            int best = 0, bestErr = Integer.MAX_VALUE;
            outer:
            for (int a = 0; a < 128; a++) {
                int ea = Bc7Tables.expand(a, 7);
                for (int b = 0; b < 128; b++) {
                    int v = Bc7Tables.interpolate(ea, Bc7Tables.expand(b, 7), Bc7Tables.WEIGHTS2[1]);
                    int err = Math.abs(v - c);
                    if (err < bestErr) {
                        bestErr = err;
                        best = (a << 8) | b;
                        if (err == 0) break outer;
                    }
                }
            }
            SOLID5[c] = best;
        }
    }

    private final Preset preset;
    private final Bits128 bits = new Bits128();
    private final Bc7Decoder decoder = new Bc7Decoder();

    // per-block scratch
    private final int[][] px = new int[16][4]; // bled input used for fitting
    private final int[] src = new int[16]; // original input used for error / alpha rule
    private final int[] colorWeight = new int[16];
    private final int[] decoded = new int[16];
    private final byte[] candidateBytes = new byte[16];
    private final Bc7Block cand = new Bc7Block();
    private final Bc7Block best = new Bc7Block();
    private final int[] subsetPixels = new int[16];
    private final float[] lo = new float[4], hi = new float[4];
    private final int[] trialIdx = new int[16];
    private final int[][] trialRaw = new int[2][4];
    private final int[] trialP = new int[2];
    private final int[] bestParts = new int[64];

    public Bc7Encoder(Preset preset) {
        this.preset = preset;
    }

    public Preset preset() {
        return preset;
    }

    /**
     * Encodes one 4x4 block. {@code block} holds 16 pixels in row-major order.
     */
    public void encodeBlock(int[] block, byte[] out, int off) {
        System.arraycopy(block, 0, src, 0, 16);
        boolean anyVisible = false, alphaVaries = false, opaque = true, solid = true;
        int a0 = block[0] >>> 24;
        for (int i = 0; i < 16; i++) {
            int a = block[i] >>> 24;
            if (a != 0) anyVisible = true;
            if (a != a0) alphaVaries = true;
            if (a != 255) opaque = false;
            if (block[i] != block[0]) solid = false;
        }
        if (!anyVisible) {
            Bc7Tables.writeTransparentBlock(out, off);
            return;
        }
        // Bleed: fully transparent texels take the mean colour of the visible ones (no fringes after filtering,
        // and they stop dragging the colour line around).
        long sr = 0, sg = 0, sb = 0;
        int vis = 0;
        for (int i = 0; i < 16; i++) {
            int p = block[i];
            if ((p >>> 24) != 0) {
                sr += p & 0xFF;
                sg += (p >>> 8) & 0xFF;
                sb += (p >>> 16) & 0xFF;
                vis++;
            }
        }
        int mr = (int) ((sr + vis / 2) / vis), mg = (int) ((sg + vis / 2) / vis), mb = (int) ((sb + vis / 2) / vis);
        boolean solidVisible = true;
        int firstVisible = -1;
        for (int i = 0; i < 16; i++) {
            int p = block[i];
            int a = p >>> 24;
            if (a == 0) {
                px[i][0] = mr;
                px[i][1] = mg;
                px[i][2] = mb;
                colorWeight[i] = 1;
            } else {
                px[i][0] = p & 0xFF;
                px[i][1] = (p >>> 8) & 0xFF;
                px[i][2] = (p >>> 16) & 0xFF;
                colorWeight[i] = 16;
                if (firstVisible < 0) firstVisible = i;
                else if ((p & 0xFFFFFF) != (block[firstVisible] & 0xFFFFFF)) solidVisible = false;
            }
            px[i][3] = a;
        }

        long bestErr = Long.MAX_VALUE;
        boolean bestValid = false;

        // 1. Solid colour (possibly with varying alpha): exact via mode 5.
        if (solid || solidVisible) {
            encodeSolidMode5(px[firstVisible][0], px[firstVisible][1], px[firstVisible][2]);
            long e = evaluate();
            boolean v = lastValid;
            if (v && e == 0) {
                best.copyFrom(cand);
                best.pack(out, off, bits);
                return;
            }
            bestErr = e;
            bestValid = v;
            best.copyFrom(cand);
        }

        // 2. Mode 6: single subset RGBA, 4-bit indices.
        fitSingleIndex(6, 0);
        {
            long e = evaluate();
            if (better(e, lastValid, bestErr, bestValid)) {
                bestErr = e;
                bestValid = lastValid;
                best.copyFrom(cand);
            }
        }

        // 3. Separate-alpha modes when alpha varies.
        if (alphaVaries) {
            int rotations = preset.exhaustive ? 4 : 1;
            for (int rot = 0; rot < rotations; rot++) {
                fitDualIndex(5, rot, 0);
                long e = evaluate();
                if (better(e, lastValid, bestErr, bestValid)) {
                    bestErr = e;
                    bestValid = lastValid;
                    best.copyFrom(cand);
                }
                for (int sel = 0; sel < 2; sel++) {
                    fitDualIndex(4, rot, sel);
                    e = evaluate();
                    if (better(e, lastValid, bestErr, bestValid)) {
                        bestErr = e;
                        bestValid = lastValid;
                        best.copyFrom(cand);
                    }
                }
            }
        }

        // 4. Two-subset modes on the best-ranked partitions.
        if (preset.partitions > 0 && bestErr > 0) {
            int n = rankPartitions(opaque ? 3 : 4, preset.partitions);
            for (int k = 0; k < n; k++) {
                int part = bestParts[k];
                if (opaque) {
                    fitTwoSubset(1, part);
                    long e = evaluate();
                    if (better(e, lastValid, bestErr, bestValid)) {
                        bestErr = e;
                        bestValid = lastValid;
                        best.copyFrom(cand);
                    }
                    fitTwoSubset(3, part);
                    e = evaluate();
                    if (better(e, lastValid, bestErr, bestValid)) {
                        bestErr = e;
                        bestValid = lastValid;
                        best.copyFrom(cand);
                    }
                } else {
                    fitTwoSubset(7, part);
                    long e = evaluate();
                    if (better(e, lastValid, bestErr, bestValid)) {
                        bestErr = e;
                        bestValid = lastValid;
                        best.copyFrom(cand);
                    }
                }
                if (bestErr == 0) break;
            }
        }

        // 5. Safety net: if nothing satisfied the alpha rule, mode 5 with exact 8-bit alpha endpoints and
        // class-constrained alpha indices always satisfies it (see bestIndexSameClass).
        if (!bestValid) {
            fitDualIndex(5, 0, 0);
            forceExactAlphaEndpoints();
            long e = evaluate();
            if (lastValid || !bestValid) {
                best.copyFrom(cand);
            }
        }
        best.pack(out, off, bits);
    }

    private static boolean better(long e, boolean valid, long bestErr, boolean bestValid) {
        if (valid != bestValid) return valid;
        return e < bestErr;
    }

    // ------------------------------------------------------------------ evaluation

    private boolean lastValid;

    /** Packs {@link #cand}, decodes it, returns the weighted error and sets {@link #lastValid}. */
    private long evaluate() {
        cand.fixAnchors();
        cand.pack(candidateBytes, 0, bits);
        decoder.decodeBlock(candidateBytes, 0, decoded);
        long err = 0;
        boolean valid = true;
        for (int i = 0; i < 16; i++) {
            int s = src[i], d = decoded[i];
            int sa = s >>> 24, da = d >>> 24;
            int dr = (s & 0xFF) - (d & 0xFF);
            int dg = ((s >>> 8) & 0xFF) - ((d >>> 8) & 0xFF);
            int db = ((s >>> 16) & 0xFF) - ((d >>> 16) & 0xFF);
            int dA = sa - da;
            if (sa == 0) {
                err += 16L * dA * dA;
                if (da != 0) valid = false;
            } else {
                err += 16L * (dr * dr + dg * dg + db * db) + 16L * dA * dA;
                if (sa == 255 && da != 255) valid = false;
                if ((sa < CUTOUT_THRESHOLD) != (da < CUTOUT_THRESHOLD)) valid = false;
            }
        }
        lastValid = valid;
        return err;
    }

    // ------------------------------------------------------------------ solid

    private void encodeSolidMode5(int r, int g, int b) {
        Bc7Block c = cand;
        c.mode = 5;
        c.partition = 0;
        c.rotation = 0;
        c.indexSelection = 0;
        int[] rgb = {r, g, b};
        for (int ch = 0; ch < 3; ch++) {
            int t = SOLID5[rgb[ch]];
            c.raw[0][ch] = t >>> 8;
            c.raw[1][ch] = t & 0xFF;
        }
        int amin = 255, amax = 0;
        for (int i = 0; i < 16; i++) {
            amin = Math.min(amin, src[i] >>> 24);
            amax = Math.max(amax, src[i] >>> 24);
        }
        c.raw[0][3] = amin;
        c.raw[1][3] = amax;
        for (int i = 0; i < 16; i++) {
            c.idx[i] = 1;
            c.idx2[i] = bestIndexSameClass(src[i] >>> 24, amin, amax, Bc7Tables.WEIGHTS2);
        }
    }

    private static int bestIndex1(int v, int e0, int e1, int[] w) {
        int best = 0, bestErr = Integer.MAX_VALUE;
        for (int k = 0; k < w.length; k++) {
            int d = Bc7Tables.interpolate(e0, e1, w[k]) - v;
            int e = d * d;
            if (e < bestErr) {
                bestErr = e;
                best = k;
            }
        }
        return best;
    }

    private void forceExactAlphaEndpoints() {
        int amin = 255, amax = 0;
        for (int i = 0; i < 16; i++) {
            amin = Math.min(amin, src[i] >>> 24);
            amax = Math.max(amax, src[i] >>> 24);
        }
        cand.raw[0][3] = amin;
        cand.raw[1][3] = amax;
        for (int i = 0; i < 16; i++) cand.idx2[i] = bestIndexSameClass(src[i] >>> 24, amin, amax, Bc7Tables.WEIGHTS2);
    }

    /**
     * Nearest interpolated alpha on the same side of the cutout threshold as {@code v}. With exact 8-bit endpoints
     * {@code amin <= v <= amax} such a value always exists (amin itself when {@code v} is below the threshold, amax
     * otherwise), and 0 / 255 map to the endpoints exactly, so the alpha rule is guaranteed.
     */
    private static int bestIndexSameClass(int v, int e0, int e1, int[] w) {
        boolean below = v < CUTOUT_THRESHOLD;
        int best = -1, bestErr = Integer.MAX_VALUE;
        for (int k = 0; k < w.length; k++) {
            int a = Bc7Tables.interpolate(e0, e1, w[k]);
            if ((a < CUTOUT_THRESHOLD) != below) continue;
            if (v == 0 && a != 0 || v == 255 && a != 255) continue;
            int d = a - v, e = d * d;
            if (e < bestErr) {
                bestErr = e;
                best = k;
            }
        }
        return best >= 0 ? best : bestIndex1(v, e0, e1, w);
    }

    // ------------------------------------------------------------------ single index (mode 6 and two-subset modes)

    /** Fits all 16 pixels as one subset with shared indices into {@link #cand}. */
    private void fitSingleIndex(int mode, int unusedPartition) {
        Mode m = Bc7Tables.MODES[mode];
        cand.mode = mode;
        cand.partition = 0;
        cand.rotation = 0;
        cand.indexSelection = 0;
        for (int i = 0; i < 16; i++) subsetPixels[i] = i;
        fitSubset(m, 0, subsetPixels, 16, m.hasAlpha() ? 4 : 3);
    }

    private void fitTwoSubset(int mode, int partition) {
        Mode m = Bc7Tables.MODES[mode];
        cand.mode = mode;
        cand.partition = partition;
        cand.rotation = 0;
        cand.indexSelection = 0;
        for (int s = 0; s < 2; s++) {
            int n = 0;
            for (int i = 0; i < 16; i++) {
                if (Bc7Tables.subsetOf2(partition, i) == s) subsetPixels[n++] = i;
            }
            fitSubset(m, s, subsetPixels, n, m.hasAlpha() ? 4 : 3);
        }
    }

    /**
     * Fits one subset (pixels {@code pix[0..n)}) with {@code nc} channels into endpoints {@code 2s, 2s+1} and the
     * subset's indices of {@link #cand}.
     */
    private void fitSubset(Mode m, int s, int[] pix, int n, int nc) {
        int ib = m.indexBits();
        int[] w = Bc7Tables.weights(ib);
        principalEndpoints(pix, n, nc, lo, hi);

        long bestErr = Long.MAX_VALUE;
        int[][] bestRaw = new int[2][4];
        int[] bestP = new int[2];
        int[] bestIdx = new int[16];

        for (int pass = 0; pass <= preset.refinePasses; pass++) {
            long e = quantizeAndAssign(m, pix, n, nc, w);
            if (e < bestErr) {
                bestErr = e;
                copy2(trialRaw, bestRaw);
                bestP[0] = trialP[0];
                bestP[1] = trialP[1];
                System.arraycopy(trialIdx, 0, bestIdx, 0, 16);
            }
            if (bestErr == 0 || pass == preset.refinePasses) break;
            if (!leastSquares(pix, n, nc, w, bestIdx, lo, hi)) break;
        }

        if (preset.exhaustive && bestErr > 0) {
            bestErr = jitter(m, pix, n, nc, w, bestRaw, bestP, bestIdx, bestErr);
        }

        System.arraycopy(bestRaw[0], 0, cand.raw[2 * s], 0, 4);
        System.arraycopy(bestRaw[1], 0, cand.raw[2 * s + 1], 0, 4);
        if (nc == 3) {
            cand.raw[2 * s][3] = 0;
            cand.raw[2 * s + 1][3] = 0;
        }
        cand.pbits[2 * s] = bestP[0];
        cand.pbits[2 * s + 1] = bestP[1];
        for (int k = 0; k < n; k++) cand.idx[pix[k]] = bestIdx[pix[k]];
    }

    private static void copy2(int[][] a, int[][] b) {
        System.arraycopy(a[0], 0, b[0], 0, 4);
        System.arraycopy(a[1], 0, b[1], 0, 4);
    }

    /**
     * Quantises {@link #lo}/{@link #hi} for every allowed p-bit combination and assigns indices; leaves the
     * best result in {@link #trialRaw}/{@link #trialP}/{@link #trialIdx}.
     */
    private final int[][] qRaw = new int[2][4];
    private final int[] qIdx = new int[16];
    private final int[][] pal = new int[16][4];

    private long quantizeAndAssign(Mode m, int[] pix, int n, int nc, int[] w) {
        int combos = switch (m.pbitType()) {
            case 2 -> 4;
            case 1 -> 2;
            default -> 1;
        };
        long best = Long.MAX_VALUE;
        for (int combo = 0; combo < combos; combo++) {
            int p0, p1;
            if (m.pbitType() == 2) {
                p0 = combo & 1;
                p1 = combo >> 1;
            } else if (m.pbitType() == 1) {
                p0 = p1 = combo;
            } else {
                p0 = p1 = -1;
            }
            for (int c = 0; c < nc; c++) {
                int bitsC = c < 3 ? m.colorBits() : m.alphaBits();
                qRaw[0][c] = QUANT[bitsC][p0 + 1][clamp255(lo[c])];
                qRaw[1][c] = QUANT[bitsC][p1 + 1][clamp255(hi[c])];
            }
            long e = assign(m, pix, n, nc, w, qRaw, p0, p1, qIdx);
            if (e < best) {
                best = e;
                copy2(qRaw, trialRaw);
                trialP[0] = Math.max(p0, 0);
                trialP[1] = Math.max(p1, 0);
                System.arraycopy(qIdx, 0, trialIdx, 0, 16);
            }
        }
        return best;
    }

    /** Builds the palette for the given quantised endpoints and picks the best index per pixel. */
    private long assign(Mode m, int[] pix, int n, int nc, int[] w, int[][] raw, int p0, int p1, int[] outIdx) {
        int cb = m.endpointBitsColor(), ab = m.endpointBitsAlpha();
        int e0r = expandCh(raw[0][0], p0, cb), e1r = expandCh(raw[1][0], p1, cb);
        int e0g = expandCh(raw[0][1], p0, cb), e1g = expandCh(raw[1][1], p1, cb);
        int e0b = expandCh(raw[0][2], p0, cb), e1b = expandCh(raw[1][2], p1, cb);
        int e0a = nc == 4 ? expandCh(raw[0][3], p0, ab) : 255, e1a = nc == 4 ? expandCh(raw[1][3], p1, ab) : 255;
        int levels = w.length;
        for (int k = 0; k < levels; k++) {
            pal[k][0] = Bc7Tables.interpolate(e0r, e1r, w[k]);
            pal[k][1] = Bc7Tables.interpolate(e0g, e1g, w[k]);
            pal[k][2] = Bc7Tables.interpolate(e0b, e1b, w[k]);
            pal[k][3] = Bc7Tables.interpolate(e0a, e1a, w[k]);
        }
        long total = 0;
        for (int q = 0; q < n; q++) {
            int i = pix[q];
            int[] p = px[i];
            int cw = colorWeight[i];
            long bestE = Long.MAX_VALUE;
            int bestK = 0;
            for (int k = 0; k < levels; k++) {
                int dr = pal[k][0] - p[0], dg = pal[k][1] - p[1], db = pal[k][2] - p[2], da = pal[k][3] - p[3];
                long e = (long) cw * (dr * dr + dg * dg + db * db) + 16L * da * da;
                if (e < bestE) {
                    bestE = e;
                    bestK = k;
                }
            }
            outIdx[i] = bestK;
            total += bestE;
        }
        return total;
    }

    private static int expandCh(int raw, int p, int bits) {
        return p < 0 ? Bc7Tables.expand(raw, bits) : Bc7Tables.expand((raw << 1) | p, bits);
    }

    private static int clamp255(float v) {
        int r = Math.round(v);
        return r < 0 ? 0 : Math.min(r, 255);
    }

    /** Greedy +-1 search on every endpoint channel. */
    private long jitter(Mode m, int[] pix, int n, int nc, int[] w, int[][] raw, int[] p, int[] idx, long err) {
        int[][] r = new int[2][4];
        int[] tIdx = new int[16];
        boolean improved = true;
        int rounds = 0;
        while (improved && rounds++ < 4) {
            improved = false;
            for (int e = 0; e < 2; e++) {
                for (int c = 0; c < nc; c++) {
                    int max = (1 << (c < 3 ? m.colorBits() : m.alphaBits())) - 1;
                    for (int d = -1; d <= 1; d += 2) {
                        copy2(raw, r);
                        int v = r[e][c] + d;
                        if (v < 0 || v > max) continue;
                        r[e][c] = v;
                        int pp0 = m.pbitType() == 0 ? -1 : p[0], pp1 = m.pbitType() == 0 ? -1 : p[1];
                        long te = assign(m, pix, n, nc, w, r, pp0, pp1, tIdx);
                        if (te < err) {
                            err = te;
                            copy2(r, raw);
                            for (int q = 0; q < n; q++) idx[pix[q]] = tIdx[pix[q]];
                            improved = true;
                        }
                    }
                }
            }
        }
        return err;
    }

    // ------------------------------------------------------------------ dual index (modes 4 and 5)

    private void fitDualIndex(int mode, int rotation, int indexSel) {
        Mode m = Bc7Tables.MODES[mode];
        cand.mode = mode;
        cand.partition = 0;
        cand.rotation = rotation;
        cand.indexSelection = indexSel;
        // Rotation swaps alpha with one colour channel *after* decoding, so fit the swapped data.
        int rc = rotation - 1; // channel swapped with alpha, or -1
        int[][] saved = null;
        if (rc >= 0) {
            saved = new int[16][];
            for (int i = 0; i < 16; i++) {
                saved[i] = px[i].clone();
                int t = px[i][rc];
                px[i][rc] = px[i][3];
                px[i][3] = t;
            }
        }
        int colorIb = mode == 5 ? 2 : (indexSel == 0 ? 2 : 3);
        int alphaIb = mode == 5 ? 2 : (indexSel == 0 ? 3 : 2);
        int[] wc = Bc7Tables.weights(colorIb), wa = Bc7Tables.weights(alphaIb);
        int[] colorIdx = indexSel == 0 ? cand.idx : cand.idx2;
        int[] alphaIdx = indexSel == 0 ? cand.idx2 : cand.idx;
        if (mode == 5) {
            colorIdx = cand.idx;
            alphaIdx = cand.idx2;
        }

        // Colour: RGB line, 1 subset.
        for (int i = 0; i < 16; i++) subsetPixels[i] = i;
        principalEndpoints(subsetPixels, 16, 3, lo, hi);
        int cbits = m.colorBits();
        long bestErr = Long.MAX_VALUE;
        int[] bestRaw0 = new int[3], bestRaw1 = new int[3];
        int[] tmpIdx = new int[16];
        for (int pass = 0; pass <= preset.refinePasses; pass++) {
            int[] r0 = new int[3], r1 = new int[3];
            for (int c = 0; c < 3; c++) {
                r0[c] = QUANT[cbits][0][clamp255(lo[c])];
                r1[c] = QUANT[cbits][0][clamp255(hi[c])];
            }
            long e = assignColorOnly(r0, r1, cbits, wc, tmpIdx);
            if (e < bestErr) {
                bestErr = e;
                bestRaw0 = r0;
                bestRaw1 = r1;
                System.arraycopy(tmpIdx, 0, colorIdx, 0, 16);
            }
            if (bestErr == 0 || pass == preset.refinePasses) break;
            if (!leastSquares(subsetPixels, 16, 3, wc, colorIdx, lo, hi)) break;
        }
        for (int c = 0; c < 3; c++) {
            cand.raw[0][c] = bestRaw0[c];
            cand.raw[1][c] = bestRaw1[c];
        }

        // Alpha: scalar line through min/max, refined.
        int amin = 255, amax = 0;
        for (int i = 0; i < 16; i++) {
            amin = Math.min(amin, px[i][3]);
            amax = Math.max(amax, px[i][3]);
        }
        int abits = m.alphaBits();
        int qa0 = QUANT[abits][0][amin], qa1 = QUANT[abits][0][amax];
        int ea0 = Bc7Tables.expand(qa0, abits), ea1 = Bc7Tables.expand(qa1, abits);
        for (int i = 0; i < 16; i++) alphaIdx[i] = bestIndex1(px[i][3], ea0, ea1, wa);
        cand.raw[0][3] = qa0;
        cand.raw[1][3] = qa1;

        if (saved != null) {
            for (int i = 0; i < 16; i++) System.arraycopy(saved[i], 0, px[i], 0, 4);
        }
    }

    private long assignColorOnly(int[] r0, int[] r1, int bits, int[] w, int[] outIdx) {
        int[] e0 = new int[3], e1 = new int[3];
        for (int c = 0; c < 3; c++) {
            e0[c] = Bc7Tables.expand(r0[c], bits);
            e1[c] = Bc7Tables.expand(r1[c], bits);
        }
        long total = 0;
        for (int i = 0; i < 16; i++) {
            long bestE = Long.MAX_VALUE;
            int bestK = 0;
            for (int k = 0; k < w.length; k++) {
                long e = 0;
                for (int c = 0; c < 3; c++) {
                    int d = Bc7Tables.interpolate(e0[c], e1[c], w[k]) - px[i][c];
                    e += d * d;
                }
                if (e < bestE) {
                    bestE = e;
                    bestK = k;
                }
            }
            outIdx[i] = bestK;
            total += bestE * colorWeight[i];
        }
        return total;
    }

    // ------------------------------------------------------------------ geometry

    private final float[] mean = new float[4];
    private final float[] axis = new float[4];
    private final float[][] cov = new float[4][4];

    /** PCA line through the pixels; writes the extreme projections as endpoints. */
    private void principalEndpoints(int[] pix, int n, int nc, float[] outLo, float[] outHi) {
        computeCov(pix, n, nc);
        powerIteration(nc, 6);
        float tmin = Float.MAX_VALUE, tmax = -Float.MAX_VALUE;
        for (int q = 0; q < n; q++) {
            int[] p = px[pix[q]];
            float t = 0;
            for (int c = 0; c < nc; c++) t += (p[c] - mean[c]) * axis[c];
            tmin = Math.min(tmin, t);
            tmax = Math.max(tmax, t);
        }
        for (int c = 0; c < nc; c++) {
            outLo[c] = mean[c] + tmin * axis[c];
            outHi[c] = mean[c] + tmax * axis[c];
        }
    }

    private void computeCov(int[] pix, int n, int nc) {
        for (int c = 0; c < 4; c++) {
            mean[c] = 0;
            for (int d = 0; d < 4; d++) cov[c][d] = 0;
        }
        for (int q = 0; q < n; q++) {
            int[] p = px[pix[q]];
            for (int c = 0; c < nc; c++) mean[c] += p[c];
        }
        for (int c = 0; c < nc; c++) mean[c] /= n;
        for (int q = 0; q < n; q++) {
            int[] p = px[pix[q]];
            for (int c = 0; c < nc; c++) {
                float dc = p[c] - mean[c];
                for (int d = c; d < nc; d++) cov[c][d] += dc * (p[d] - mean[d]);
            }
        }
        for (int c = 0; c < nc; c++) for (int d = 0; d < c; d++) cov[c][d] = cov[d][c];
    }

    /** Returns the largest eigenvalue; leaves the unit eigenvector in {@link #axis}. */
    private float powerIteration(int nc, int iters) {
        // Seed with the channel of largest variance (robust for axis-aligned data).
        int bestC = 0;
        for (int c = 1; c < nc; c++) if (cov[c][c] > cov[bestC][bestC]) bestC = c;
        for (int c = 0; c < 4; c++) axis[c] = 0;
        for (int c = 0; c < nc; c++) axis[c] = cov[bestC][c];
        if (cov[bestC][bestC] <= 0f) {
            axis[0] = 1;
            return 0f;
        }
        float lambda = 0f;
        float[] t = new float[4];
        for (int it = 0; it < iters; it++) {
            float len = 0;
            for (int c = 0; c < nc; c++) {
                float v = 0;
                for (int d = 0; d < nc; d++) v += cov[c][d] * axis[d];
                t[c] = v;
                len += v * v;
            }
            len = (float) Math.sqrt(len);
            if (len < 1e-12f) break;
            for (int c = 0; c < nc; c++) axis[c] = t[c] / len;
            lambda = len;
        }
        float len = 0;
        for (int c = 0; c < nc; c++) len += axis[c] * axis[c];
        len = (float) Math.sqrt(len);
        if (len > 0) for (int c = 0; c < nc; c++) axis[c] /= len;
        return lambda;
    }

    /** Least-squares endpoints for fixed indices. Returns false when the system is degenerate. */
    private boolean leastSquares(int[] pix, int n, int nc, int[] w, int[] idx, float[] outLo, float[] outHi) {
        float aa = 0, ab = 0, bb = 0;
        float[] ax = new float[4], bx = new float[4];
        for (int q = 0; q < n; q++) {
            int i = pix[q];
            float s = w[idx[i]] / 64f, r = 1f - s;
            aa += r * r;
            ab += r * s;
            bb += s * s;
            for (int c = 0; c < nc; c++) {
                ax[c] += r * px[i][c];
                bx[c] += s * px[i][c];
            }
        }
        float det = aa * bb - ab * ab;
        if (Math.abs(det) < 1e-6f) return false;
        float inv = 1f / det;
        for (int c = 0; c < nc; c++) {
            outLo[c] = (ax[c] * bb - bx[c] * ab) * inv;
            outHi[c] = (bx[c] * aa - ax[c] * ab) * inv;
        }
        return true;
    }

    /** Ranks the 64 two-subset partitions by line-fit residual; fills {@link #bestParts}, returns count. */
    private int rankPartitions(int nc, int count) {
        float[] score = new float[64];
        int[] a = new int[16], b = new int[16];
        for (int part = 0; part < 64; part++) {
            int na = 0, nb = 0;
            for (int i = 0; i < 16; i++) {
                if (Bc7Tables.subsetOf2(part, i) == 0) a[na++] = i;
                else b[nb++] = i;
            }
            score[part] = residual(a, na, nc) + residual(b, nb, nc);
        }
        Integer[] order = new Integer[64];
        for (int i = 0; i < 64; i++) order[i] = i;
        java.util.Arrays.sort(order, (x, y) -> Float.compare(score[x], score[y]));
        int n = Math.min(count, 64);
        for (int i = 0; i < n; i++) bestParts[i] = order[i];
        return n;
    }

    private float residual(int[] pix, int n, int nc) {
        if (n <= 1) return 0f;
        computeCov(pix, n, nc);
        float trace = 0;
        for (int c = 0; c < nc; c++) trace += cov[c][c];
        float lambda = powerIteration(nc, 3);
        return Math.max(0f, trace - lambda);
    }

    // ------------------------------------------------------------------ images

    /** Size in bytes of the BC7 data for a {@code w x h} image. */
    public static int encodedSize(int w, int h) {
        return ((w + 3) >> 2) * ((h + 3) >> 2) * 16;
    }

    /**
     * Encodes a {@code w x h} region of {@code pixels} (row stride {@code stride}, starting at {@code base}) into
     * {@code out} at {@code outOff}. Partial edge blocks repeat the last row/column.
     */
    public void encodeImage(int[] pixels, int base, int stride, int w, int h, byte[] out, int outOff) {
        int bw = (w + 3) >> 2, bh = (h + 3) >> 2;
        int[] block = new int[16];
        for (int by = 0; by < bh; by++) {
            for (int bx = 0; bx < bw; bx++) {
                for (int y = 0; y < 4; y++) {
                    int yy = Math.min(by * 4 + y, h - 1);
                    for (int x = 0; x < 4; x++) {
                        int xx = Math.min(bx * 4 + x, w - 1);
                        block[y * 4 + x] = pixels[base + yy * stride + xx];
                    }
                }
                encodeBlock(block, out, outOff + (by * bw + bx) * 16);
            }
        }
    }
}
