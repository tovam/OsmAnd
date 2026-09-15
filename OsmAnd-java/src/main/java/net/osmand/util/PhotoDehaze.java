package net.osmand.util;

import java.util.Arrays;
import java.util.concurrent.CancellationException;

/** Bounded single-image dehazing in linear light. Geometry is an optional prior, not weather data.
 * Dark-channel prior: He/Sun/Tang, CVPR 2009. Edge refinement: guided filter, ECCV 2010.
 * Implemented here without native libraries or a learned model. Never modifies input pixels. */
public final class PhotoDehaze {
    private PhotoDehaze() { }
    private static final float[] LINEAR = new float[256];
    private static final int[] SRGB = new int[65536];
    static {
        for (int i = 0; i < 256; i++) {
            double s = i / 255.0;
            LINEAR[i] = (float) (s <= .04045 ? s / 12.92 : Math.pow((s + .055) / 1.055, 2.4));
        }
        for (int i = 0; i < SRGB.length; i++) {
            double v = i / 65535.0;
            SRGB[i] = (int)Math.round(255 * (v <= .0031308 ? v*12.92 : 1.055*Math.pow(v, 1/2.4)-.055));
        }
    }

    public static final class Analysis {
        public final int width, height;
        public final float[] airlight, transmission;
        Analysis(int w, int h, float[] a, float[] t) {
            width = w; height = h; airlight = a; transmission = t;
        }
    }

    /** Pixels are a small, upright preview (at most 384 on the longest edge). */
    public static Analysis analyse(int[] pixels, int width, int height) {
        requireImage(pixels, width, height);
        if (width > 384 || height > 384) throw new IllegalArgumentException("Analysis preview too large");
        int n = pixels.length, radius = Math.max(1, Math.min(width, height) / 32);
        float[] dark = new float[n], guide = new float[n];
        for (int i = 0; i < n; i++) {
            float r = LINEAR[(pixels[i] >> 16) & 255], g = LINEAR[(pixels[i] >> 8) & 255], b = LINEAR[pixels[i] & 255];
            dark[i] = Math.min(r, Math.min(g, b)); guide[i] = .2126f*r + .7152f*g + .0722f*b;
        }
        dark = minimum(dark, width, height, radius);
        float[] sorted = dark.clone(); Arrays.sort(sorted);
        float threshold = sorted[Math.min(n-1, (int)(n*.998))];
        float[] a = new float[3];
        float total = 0;
        for (int i = 0; i < n; i++) if (dark[i] >= threshold) {
            float weight = .01f + guide[i]; total += weight;
            a[0] += weight*LINEAR[(pixels[i] >> 16) & 255];
            a[1] += weight*LINEAR[(pixels[i] >> 8) & 255];
            a[2] += weight*LINEAR[pixels[i] & 255];
        }
        for (int c = 0; c < 3; c++) a[c] = clamp(a[c]/Math.max(.001f,total), .08f, 1f);
        for (int i = 0; i < n; i++) dark[i] = Math.min(LINEAR[(pixels[i] >> 16) & 255]/a[0],
                Math.min(LINEAR[(pixels[i] >> 8) & 255]/a[1], LINEAR[pixels[i] & 255]/a[2]));
        dark = minimum(dark, width, height, radius);
        for (int i = 0; i < n; i++) dark[i] = clamp(1f - .95f*dark[i], .12f, 1f);
        return new Analysis(width, height, a, guided(guide, dark, width, height, Math.max(2,radius)));
    }

    /** Fit a conservative extinction coefficient to the photograph on known terrain only. */
    public static float extinction(Analysis analysis, float[] opticalKm) {
        if (opticalKm == null || opticalKm.length != analysis.transmission.length) return 0f;
        float[] values = new float[opticalKm.length]; int count = 0;
        for (int i = 0; i < values.length; i++) {
            float length = opticalKm[i], t = analysis.transmission[i];
            if (Float.isFinite(length) && length > 2 && t > .15f && t < .97f)
                values[count++] = (float)(-Math.log(t)/length);
        }
        if (count < 8) return 0f;
        Arrays.sort(values, 0, count);
        return clamp(values[count/2], .0001f, .12f);
    }

    /** Same optical depth blending for preview and full-resolution rendering. Unknown depth = NaN. */
    public static float transmission(float imageTransmission, float opticalKm, float extinction, float amount) {
        float strength = clamp(amount, 0f, 1f);
        if (strength == 0) return 1;
        double tau = -Math.log(clamp(imageTransmission, .12f, 1f));
        if (Float.isFinite(opticalKm) && opticalKm >= 0 && extinction > 0)
            tau = .4*tau + .6*extinction*opticalKm;
        return (float)Math.exp(-Math.min(-Math.log(.22), strength*tau));
    }

    public static int[] render(int[] pixels, int width, int height, Analysis analysis,
            float[] opticalKm, float amount) {
        requireImage(pixels, width, height);
        if (!Float.isFinite(amount) || amount <= 0) return pixels.clone();
        float beta = extinction(analysis, opticalKm);
        int[] out = new int[pixels.length];
        for (int y = 0; y < height; y++) {
            cancelled();
            double v = (y+.5)*analysis.height/height-.5;
            for (int x = 0; x < width; x++) {
                double u = (x+.5)*analysis.width/width-.5;
                float t = sample(analysis.transmission, analysis.width, analysis.height, u, v, false);
                float distance = opticalKm == null ? Float.NaN : sample(opticalKm, analysis.width, analysis.height, u, v, true);
                int i = y*width+x;
                out[i] = recover(pixels[i], analysis.airlight, transmission(t, distance, beta, amount));
            }
        }
        return out;
    }

    public static int recover(int pixel, float[] airlight, float transmission) {
        int out = pixel & 0xff000000;
        for (int c = 0; c < 3; c++) {
            float value = LINEAR[(pixel >> (16-c*8)) & 255];
            value = clamp((value-airlight[c])/Math.max(.22f,transmission)+airlight[c], 0, 1);
            out |= SRGB[Math.round(value*65535)] << (16-c*8);
        }
        return out;
    }

    /** No interpolation across unknown geometry/sky boundaries. */
    public static float sample(float[] values, int w, int h, double x, double y, boolean masked) {
        x = Math.max(0, Math.min(w-1, x)); y = Math.max(0, Math.min(h-1, y));
        int x0 = (int)x, y0 = (int)y, x1 = Math.min(w-1,x0+1), y1 = Math.min(h-1,y0+1);
        float a = values[y0*w+x0], b = values[y0*w+x1], c = values[y1*w+x0], d = values[y1*w+x1];
        if (masked && (!Float.isFinite(a) || !Float.isFinite(b) || !Float.isFinite(c) || !Float.isFinite(d)))
            return Float.NaN;
        double dx = x-x0, dy = y-y0;
        return (float)((a*(1-dx)+b*dx)*(1-dy)+(c*(1-dx)+d*dx)*dy);
    }

    private static float[] minimum(float[] input, int w, int h, int radius) {
        float[] horizontal = new float[input.length], output = new float[input.length];
        int[] queue = new int[Math.max(w,h)];
        for (int pass = 0; pass < 2; pass++) {
            int lines = pass == 0 ? h : w, length = pass == 0 ? w : h;
            float[] source = pass == 0 ? input : horizontal, target = pass == 0 ? horizontal : output;
            for (int line = 0; line < lines; line++) {
                cancelled(); int head = 0, tail = 0, right = 0;
                for (int p = 0; p < length; p++) {
                    while (right <= Math.min(length-1,p+radius)) {
                        int index = pass == 0 ? line*w+right : right*w+line;
                        while (tail > head && source[queue[tail-1]] >= source[index]) tail--;
                        queue[tail++] = index; right++;
                    }
                    int minimum = Math.max(0,p-radius);
                    while (head < tail && (pass == 0 ? queue[head]%w : queue[head]/w) < minimum) head++;
                    target[pass == 0 ? line*w+p : p*w+line] = source[queue[head]];
                }
            }
        }
        return output;
    }

    private static float[] mean(float[] values, int w, int h, int radius) {
        double[] sums = new double[(w+1)*(h+1)]; float[] out = new float[values.length];
        for (int y = 0; y < h; y++) {
            cancelled(); double row = 0;
            for (int x = 0; x < w; x++) { row += values[y*w+x]; sums[(y+1)*(w+1)+x+1] = sums[y*(w+1)+x+1]+row; }
        }
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int l = Math.max(0,x-radius), r = Math.min(w,x+radius+1), t = Math.max(0,y-radius), b = Math.min(h,y+radius+1);
            out[y*w+x] = (float)((sums[b*(w+1)+r]-sums[b*(w+1)+l]-sums[t*(w+1)+r]+sums[t*(w+1)+l])/((r-l)*(b-t)));
        }
        return out;
    }

    private static float[] guided(float[] guide, float[] p, int w, int h, int radius) {
        float[] mi = mean(guide,w,h,radius), mp = mean(p,w,h,radius), ii = new float[p.length], ip = new float[p.length];
        for (int i = 0; i < p.length; i++) { ii[i] = guide[i]*guide[i]; ip[i] = guide[i]*p[i]; }
        ii = mean(ii,w,h,radius); ip = mean(ip,w,h,radius);
        for (int i = 0; i < p.length; i++) { ii[i] = (ip[i]-mi[i]*mp[i])/(ii[i]-mi[i]*mi[i]+.001f); ip[i] = mp[i]-ii[i]*mi[i]; }
        ii = mean(ii,w,h,radius); ip = mean(ip,w,h,radius);
        for (int i = 0; i < p.length; i++) p[i] = clamp(ii[i]*guide[i]+ip[i], .12f, 1f);
        return p;
    }

    private static float clamp(float v, float low, float high) { return Math.max(low, Math.min(high,v)); }
    private static void requireImage(int[] p, int w, int h) {
        if (w <= 0 || h <= 0 || (long)w*h != p.length) throw new IllegalArgumentException("Image dimensions");
    }
    private static void cancelled() { if (Thread.currentThread().isInterrupted()) throw new CancellationException(); }
}
