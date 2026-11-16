package dev.vivarium.codec;

/** Quality measurements between a source image and its decoded BC7 version ({@code 0xAABBGGRR} pixels). */
public final class Metrics {
    private Metrics() {}

    public record Result(double psnr, int maxError, int alphaRuleViolations, int pixels) {
        public boolean alphaExact() {
            return alphaRuleViolations == 0;
        }
    }

    /**
     * PSNR over RGB of visible texels plus alpha of all texels. The alpha rule counts texels where alpha 0 /
     * 255 was not preserved or the cutout threshold was crossed.
     */
    public static Result compare(int[] a, int[] b, int n) {
        double sse = 0;
        long samples = 0;
        int maxErr = 0, violations = 0;
        for (int i = 0; i < n; i++) {
            int p = a[i], q = b[i];
            int pa = p >>> 24, qa = q >>> 24;
            int da = pa - qa;
            sse += da * da;
            samples++;
            maxErr = Math.max(maxErr, Math.abs(da));
            if (pa != 0) {
                for (int s = 0; s < 24; s += 8) {
                    int d = ((p >>> s) & 0xFF) - ((q >>> s) & 0xFF);
                    sse += d * d;
                    samples++;
                    maxErr = Math.max(maxErr, Math.abs(d));
                }
            }
            if ((pa == 0 && qa != 0) || (pa == 255 && qa != 255)
                || (pa != 0 && (pa < Bc7Encoder.CUTOUT_THRESHOLD) != (qa < Bc7Encoder.CUTOUT_THRESHOLD))) {
                violations++;
            }
        }
        double mse = samples == 0 ? 0 : sse / samples;
        double psnr = mse == 0 ? 99.0 : 10 * Math.log10(255.0 * 255.0 / mse);
        return new Result(psnr, maxErr, violations, n);
    }
}
