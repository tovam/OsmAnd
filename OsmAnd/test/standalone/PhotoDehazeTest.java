import java.util.Arrays;
import java.util.concurrent.CancellationException;
import net.osmand.util.PhotoDehaze;

/** Synthetic colours only. No photographs, network, Android runtime, or model downloads. */
public final class PhotoDehazeTest {
    public static void main(String[] args) {
        int[] pixels = scene(64, 48, true);
        int[] original = pixels.clone();
        PhotoDehaze.Analysis analysis = PhotoDehaze.analyse(pixels, 64, 48);
        check(Arrays.equals(original, PhotoDehaze.render(pixels, 64, 48, analysis, null, 0)), "disabled identity");
        int[] corrected = PhotoDehaze.render(pixels, 64, 48, analysis, null, .8f);
        check(Arrays.equals(original, pixels), "no source mutation");
        for (int i = 0; i < pixels.length; i++) check((pixels[i] >>> 24) == (corrected[i] >>> 24), "alpha preserved");
        check(error(corrected, scene(64, 48, false)) < error(pixels, scene(64, 48, false)), "synthetic blue haze reduced");
        System.out.println("PASS synthetic haze reduction, alpha, disabled identity, immutable input");

        float[] a = {.6f, .7f, .9f};
        for (float t : new float[]{.25f, .45f, .7f, 1f}) for (int r = 0; r <= 255; r += 17) {
            int clear = 0xff000000 | r<<16 | ((r+51)%256)<<8 | ((r+102)%256);
            int haze = haze(clear, a, t);
            int recovered = PhotoDehaze.recover(haze, a, t);
            check(error(new int[]{clear}, new int[]{recovered}) < 18, "linear light inverse");
        }
        float near = PhotoDehaze.transmission(.6f, 10, .02f, .7f);
        float far = PhotoDehaze.transmission(.6f, 100, .02f, .7f);
        check(far < near, "same image evidence: stronger correction far away");
        check(PhotoDehaze.transmission(.6f, Float.NaN, .02f, .7f) ==
            PhotoDehaze.transmission(.6f, Float.NaN, 0, .7f), "unknown geometry remains image-only");
        check(PhotoDehaze.transmission(.12f, 1000, 1, 1) >= .2199, "bounded noise gain");
        float[] noDepth = new float[64*48]; Arrays.fill(noDepth, Float.NaN);
        check(Arrays.equals(corrected, PhotoDehaze.render(pixels,64,48,analysis,noDepth,.8f)), "no geometry fallback");
        System.out.println("PASS optical-depth weighting, analytic inverse, fallback and gain limit");

        for (int w : new int[]{1, 2, 8, 31}) for (int h : new int[]{1, 2, 9, 32}) {
            for (int color : new int[]{0xff000000, 0xffffffff, 0xff6688bb}) {
                int[] flat = new int[w*h]; Arrays.fill(flat,color);
                PhotoDehaze.Analysis flatAnalysis = PhotoDehaze.analyse(flat,w,h);
                for (float value : flatAnalysis.transmission) check(Float.isFinite(value) && value >= .12f && value <= 1, "valid tiny mask");
                int[] out = PhotoDehaze.render(flat,w,h,flatAnalysis,null,1);
                check(error(flat,out)<2, "uniform image should not invent detail");
            }
        }
        check(Float.isNaN(PhotoDehaze.sample(new float[]{10,Float.NaN,20,30},2,2,.5,.5,true)), "do not blur terrain into sky");
        boolean cancelled = false;
        Thread.currentThread().interrupt();
        try { PhotoDehaze.render(pixels,64,48,analysis,null,.5f); }
        catch (CancellationException expected) { cancelled = true; }
        finally { Thread.interrupted(); }
        check(cancelled,"worker cancellation");
        System.out.println("PASS 48 tiny/uniform images, masked interpolation and cancellation");
    }
    private static int[] scene(int w, int h, boolean hazy) {
        int[] data = new int[w*h];
        for (int y=0;y<h;y++) for(int x=0;x<w;x++) {
            float[] air = {.5f,.68f,.95f};
            int pixel = y<h/4 ? rgb(air) : ((x/4+y/4)%2 == 0 ? 0xff084820 : 0xff705008);
            data[y*w+x] = hazy ? haze(pixel,air,.45f) : pixel;
        }
        return data;
    }
    private static int haze(int pixel,float[] air,float t) {
        float[] channels=new float[3];
        for(int c=0;c<3;c++) {
            double v=((pixel>>(16-8*c))&255)/255.0;
            double linear=v<=.04045?v/12.92:Math.pow((v+.055)/1.055,2.4);
            channels[c]=(float)(linear*t+air[c]*(1-t));
        }
        return rgb(channels);
    }
    private static int rgb(float[] linear) {
        int out=0xff000000;
        for(int c=0;c<3;c++) {
            double v=linear[c]; double s=v<=.0031308?v*12.92:1.055*Math.pow(v,1/2.4)-.055;
            out|=(int)Math.round(255*s)<<(16-8*c);
        }
        return out;
    }
    private static double error(int[] a,int[] b) {
        double sum=0;
        for(int i=0;i<a.length;i++) for(int c=0;c<3;c++) sum+=Math.abs(((a[i]>>(8*c))&255)-((b[i]>>(8*c))&255));
        return sum/a.length/3;
    }
    private static void check(boolean ok,String message) { if(!ok) throw new AssertionError(message); }
}
