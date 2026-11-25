package com.truehue.camera;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Random;

/** Plain JDK regression check covering Bayer, color matrices, burst merging, denoising, tone, Gainmap, and MPF. */
public final class RawDevelopmentCheck {
    private static final float[] I = {1,0,0, 0,1,0, 0,0,1};
    private static final float[] UNIT = {1,1,1,1};
    private static final float[] BLACK = {60,62,64,61};
    private static final int[][] BAYER = {{0,1,2,3}, {1,0,3,2}, {1,3,0,2}, {3,1,2,0}};
    private static final int W = 42, H = 36, PAD = 6;

    private static int encode(double x) {
        return (int) Math.round(255 * (x <= 0.0031308 ? 12.92*x : 1.055*Math.pow(x,1/2.4)-0.055));
    }

    private static short[] sensor(int[] cfa, float[] patch, boolean gradient) {
        short[] raw = new short[(W + PAD) * (H + 3)];
        Arrays.fill(raw, (short) 1023);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int p = 2*(y & 1) + (x & 1);
            float value = patch[cfa[p]] * (gradient ? 0.28f + (x+2*y)*0.005f : 1f);
            raw[(y+1)*(W+PAD)+2+x] = (short) Math.round(BLACK[p] + (1023-BLACK[p])*value);
        }
        return raw;
    }

    private static BayerPipeline frame(short[] raw, int[] cfa, float[] gains, float[] transform) {
        return new BayerPipeline(ShortBuffer.wrap(raw), W+PAD, 2, 1, W,H,cfa,BLACK,1023,gains,transform,null,0,0);
    }

    private static int[] pixels(BayerPipeline pipe, int rotation) {
        return pixels(pipe, rotation, false, 0, 0);
    }

    private static int[] pixels(BayerPipeline pipe, int rotation, boolean natural, int mm, int iso) {
        int w = rotation % 180 == 0 ? pipe.width : pipe.height;
        int[] photo = new int[pipe.width * pipe.height];
        pipe.render(rotation, natural, mm, iso, (data,x,y,width,height) -> {
            for (int row = 0; row < height; row++) System.arraycopy(data,row*width,photo,(y+row)*w+x,width);
        });
        return photo;
    }

    private static void checkNaturalLight() {
        int w = 240, h = 120;
        short[] raw = new short[w * h];
        // Bright sign (0.85) with dark lettering (0.065), shadowed subject (0.065), deep black (0), near-black (0.0055).
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int value = x < 78 ? 8500 : 650;
            if (x >= 34 && x <= 42) value = 650;
            if (x >= 200) value = x < 218 ? 0 : 55;
            raw[y*w+x] = (short) value;
        }
        BayerPipeline scene = new BayerPipeline(ShortBuffer.wrap(raw), w,0,0,w,h,BAYER[0],
                new float[4],10000,UNIT,I,null,0,0);
        int[] old = pixels(scene,0), main = pixels(scene,0,true,23,100);
        int[] tele = pixels(scene,0,true,60,100), ultra = pixels(scene,0,true,17,1250);
        int mid = h/2*w;
        if ((main[mid+140]&255) <= (ultra[mid+140]&255)
                || (tele[mid+140]&255) < (main[mid+140]&255)) {
            throw new AssertionError("Lens/ISO shadow gating");
        }
        if ((main[mid+38]&255) > (ultra[mid+38]&255) + 1
                || main[mid+210] != 0xff000000
                || (main[mid+228]&255) > (old[mid+228]&255)) {
            throw new AssertionError("Sign lettering or deep black anchor violated");
        }
        if ((main[mid+18]&255) < 234) {
            throw new AssertionError("Diffuse white ceiling collapsed: " + (main[mid+18]&255));
        }

        // Neutral ramp + clipped plateau monotonicity.
        for (int y = 12; y < 30; y++) for (int x = 0; x < w; x++)
            raw[y*w+x] = (short)Math.min(10000, 50*x);
        int[] ramp = pixels(scene,0,true,23,100);
        int previous = 0;
        for (int x = 2; x < w - 2; x++) {
            int v = ramp[20*w+x], code = v & 255;
            if (code < previous || code != (v >> 8 & 255) || code != (v >> 16 & 255))
                throw new AssertionError("Non-monotonic or tinted neutral ramp");
            if (x > 210 && code != previous) throw new AssertionError("Detail on a clipped plateau");
            previous = code;
        }
        Arrays.fill(raw, (short)450);
        int[] dim = pixels(scene, 0, true, 23, 800);
        for (int x = 4; x < w - 4; x++) {
            int v = dim[mid + x];
            if ((v & 255) != (v >> 8 & 255) || (v & 255) != (v >> 16 & 255) || (v & 255) > 85) {
                throw new AssertionError("Uniform dim scene over-lifted or tinted");
            }
        }
        // Warm midtone should remain on its original hue axis.
        BayerPipeline skin = frame(sensor(BAYER[0],new float[]{.40f,.27f,.27f,.17f},false),BAYER[0],UNIT,I);
        int warm = pixels(skin,0,true,60,200)[0];
        double r = decode(warm>>16&255), g = decode(warm>>8&255), b = decode(warm&255);
        near((r-g)/(g-b),(.40-.27)/(.27-.17),.14);
    }

    private static void checkFilters() {
        if (PhotoFilter.fromId("soft") != PhotoFilter.SOFT
                || PhotoFilter.fromId("master") != PhotoFilter.MASTER
                || PhotoFilter.fromId("mono") != PhotoFilter.MONO
                || PhotoFilter.fromId(null) != PhotoFilter.ORIGINAL) {
            throw new AssertionError("Filter preference");
        }
        for (PhotoFilter filter : PhotoFilter.values()) {
            int previous = -1;
            for (int i=0; i < 256; i++) {
                int input = 0xff000000 | i*0x010101;
                int v = filter.apply(input), code = v & 255;
                if (code < previous || (v & 0xffffff) != code*0x010101
                        || ((i == 0 || i == 255) && v != input)) throw new AssertionError("Filter neutral ramp " + filter);
                previous = code;
            }
        }
        int warm = 0xffe9c5b6;
        int softened = PhotoFilter.SOFT.apply(warm);
        if ((softened>>8&255) >= (warm>>8&255) || (softened>>16&255) <= (softened>>8&255)
                || (softened>>8&255) <= (softened&255)) throw new AssertionError("Soft highlights and warm hue");
        if ((PhotoFilter.DETAIL.apply(0xff555555)&255) >= 0x55
                || (PhotoFilter.DETAIL.apply(0xffdddddd)&255) <= 0xdd) throw new AssertionError("Defined tones");
        if ((PhotoFilter.MASTER.apply(0xff484848)&255) >= 0x48) throw new AssertionError("Master shadow anchor");
        int mono = PhotoFilter.MONO.apply(0xffcc6633);
        if ((mono >> 16 & 255) != (mono >> 8 & 255) || (mono >> 8 & 255) != (mono & 255)) {
            throw new AssertionError("Monochrome filter retained color");
        }

        BayerPipeline frame = frame(sensor(BAYER[0], new float[]{.60f,.72f,.72f,.46f},false), BAYER[0], UNIT, I);
        int original = pixels(frame,0,true,23,100)[0];
        int[] filtered = new int[1];
        frame.render(0,true,23,100,PhotoFilter.SOFT,(pixels,x,y,w,h) -> filtered[0] = pixels[0]);
        if (filtered[0] == original) throw new AssertionError("Filter missing from RAW output");
    }

    private static void checkHardEdges() {
        int w = 96, h = 96;
        short[] raw = new short[w*h];
        for (boolean vertical : new boolean[]{false,true}) {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
                raw[y*w+x] = (short)((vertical ? x : y) < 48 ? 300 : 7000);
            for (int[] cfa : BAYER) {
                BayerPipeline scene = new BayerPipeline(ShortBuffer.wrap(raw),w,0,0,w,h,cfa,
                        new float[4],10000,UNIT,I,null,0,0);
                int[] image = pixels(scene,0,true,23,300);
                for (int along = 26; along < 29; along++) for (int across = 46; across <= 49; across++) {
                    int x = vertical ? across : along, y = vertical ? along : across;
                    int v = image[y*w+x];
                    near(v>>16&255,v>>8&255,0);
                    near(v>>8&255,v&255,0);
                    int flat = across < 48 ? 43 : 53;
                    near(v&255,image[vertical ? y*w+flat : flat*w+x]&255,1);
                }
            }
        }
    }

    private static void checkDuvGuardAndDualMatrix() {
        // Real Xiaomi 17 23mm main DNG calibration matrices with static ForwardMatrix and green-cast AWB.
        float[] cal1 = {0.99219f,0,0, 0,1,0, 0,0,0.99219f};
        float[] cm1 = {0.85156f,-0.28906f,-0.10938f, -0.375f,1.25f,0.09375f, -0.05469f,0.28906f,0.42188f};
        float[] cm2 = {1.35156f,-0.73438f,-0.10156f, -0.47656f,1.41406f,0.32812f, -0.03125f,0.14844f,0.76562f};
        float[] fmStatic = {0.54688f,0.29688f,0.11719f, 0.17188f,0.92969f,-0.09375f, -0.03125f,-0.4375f,1.29688f};
        float[] neutralGreenCast = {0.50488f, 1.0f, 0.64062f};

        SensorColorMatrix.Result unguarded = SensorColorMatrix.fromDngCalibrated(
                neutralGreenCast, cal1, cal1, cm1, cm2, fmStatic, fmStatic, 21, 17, false, 0, 0);
        SensorColorMatrix.Result guarded = SensorColorMatrix.fromDngCalibrated(
                neutralGreenCast, cal1, cal1, cm1, cm2, fmStatic, fmStatic, 21, 17, true, 0, 0);
        if (unguarded.duv >= -0.005f) throw new AssertionError("Expected negative Duv measurement");
        // Guarding pulls green neutral up (lower R/G, B/G in sensor neutral relative to unguarded).
        if (guarded.neutral[0] >= unguarded.neutral[0] || guarded.neutral[2] >= unguarded.neutral[2]) {
            throw new AssertionError("Planckian Duv guard did not correct negative Duv green cast");
        }
        for (int r = 0; r < 3; r++) {
            float rowSum = guarded.wbMatrix[3*r] + guarded.wbMatrix[3*r+1] + guarded.wbMatrix[3*r+2];
            near(rowSum, 1.0, 0.001);
        }
        SensorColorMatrix.Result warm = SensorColorMatrix.fromDngCalibrated(
                neutralGreenCast, cal1, cal1, cm1, cm2, fmStatic, fmStatic, 21, 17, true, 3200f, 0);
        SensorColorMatrix.Result cool = SensorColorMatrix.fromDngCalibrated(
                neutralGreenCast, cal1, cal1, cm1, cm2, fmStatic, fmStatic, 21, 17, true, 6500f, 0);
        if (warm.neutral[0] <= cool.neutral[0] || warm.neutral[2] >= cool.neutral[2]) {
            throw new AssertionError("Manual Kelvin direction wrong");
        }
        if (!(SensorColorMatrix.previewWarmth(3200) < 0 && SensorColorMatrix.previewWarmth(6500) > 0
                && SensorColorMatrix.previewWarmth(5500) == 0 && SensorColorMatrix.previewWarmth(0) == 0)) {
            throw new AssertionError("Preview Kelvin direction differs from RAW");
        }
    }

    private static void checkFullRangeBurst() {
        int width = 48, height = 48;
        float[] black = {60, 62, 64, 61};
        for (int white : new int[]{1023, 4095, 16383, 32767, 65535}) {
            short[] frame = new short[width * height];
            for (int i = 0; i < frame.length; i++) {
                frame[i] = (short) ((i % width < 24) ? white / 2 : white);
            }
            BurstRawMerger merger = new BurstRawMerger(width, height, BAYER[0], black, white, 0, 0);
            merger.setReference(frame);
            merger.mergeFrame(frame, null);
            BurstRawMerger.Result result = merger.finish();
            for (int x : new int[]{0, 1, 32, 33}) {
                for (int y = 0; y < 2; y++) {
                    int i = y * width + x, site = y * 2 + (x & 1);
                    float input = ((frame[i] & 65535) - black[site]) / (white - black[site]);
                    float output = ((result.bayer12.get(i) & 65535) - result.black12[site])
                            / (result.white12 - result.black12[site]);
                    near(output, input, 1f / white);
                }
            }
        }
    }

    private static void checkFullResolution() {
        int width = 4096, height = 3072;
        short[] raw = new short[width * height];
        Random noise = new Random(42);
        for (int i = 0; i < raw.length; i++) raw[i] = (short) (150 + (i % width) / 6 + noise.nextInt(33));
        BayerPipeline pipe = new BayerPipeline(ShortBuffer.wrap(raw), width, 0, 0, width, height,
                BAYER[0], BLACK, 1023, UNIT, I, null, 0, 0);
        BayerPipeline.Options options = new BayerPipeline.Options();
        options.iso = 800;
        options.noiseSlope = .0005f;
        options.buildGainmap = true;
        options.filter = PhotoFilter.SOFT;
        int[] photo = new int[width * height];
        BayerPipeline.GainmapData gainmap = pipe.render(0, options, (pixels, x, y, w, h) -> {
            for (int row = 0; row < h; row++) System.arraycopy(pixels, row * w, photo, (y + row) * width + x, w);
        });
        if ((photo[width * (height / 2)] & 255) >= (photo[raw.length - 1] & 255)
                || gainmap == null || gainmap.pixels.length != width * height / 16) {
            throw new AssertionError("Incomplete 12 MP development");
        }
    }

    private static void checkBurstRawMerger() {
        int w = 192, h = 160;
        int[] cfa = BAYER[0];
        float[] black = {64, 64, 64, 64};
        float white = 1023f;
        short[] clean = new short[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // High-contrast grid pattern in left half, flat mid-grey in right half.
                int val = (x < 96 && ((x / 16 + y / 16) % 2 == 0)) ? 620 : 320;
                clean[y * w + x] = (short) val;
            }
        }
        Random rng = new Random(42);
        int[][] shifts = {{0, 0}, {4, -6}, {-2, 4}, {6, 2}};
        BurstRawMerger merger = new BurstRawMerger(w, h, cfa, black, white, 0.0008f, 1e-6f);
        double refVar = 0;
        int flatCount = 0;
        for (int f = 0; f < 4; f++) {
            int dx = shifts[f][0], dy = shifts[f][1];
            short[] frame = new short[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int sx = Math.max(0, Math.min(w - 1, x - dx));
                    int sy = Math.max(0, Math.min(h - 1, y - dy));
                    int base = clean[sy * w + sx];
                    // Inject a moving bright ghost patch in frame 2 on flat area (112..144, 48..80).
                    if (f == 2 && x >= 112 && x < 144 && y >= 48 && y < 80) base += 240;
                    int noisy = Math.max(64, Math.min(1023, base + (int) Math.round(rng.nextGaussian() * 14.0)));
                    frame[y * w + x] = (short) noisy;
                    if (f == 0 && x >= 112 && x < 176 && y >= 96 && y < 144) {
                        double d = noisy - 320.0;
                        refVar += d * d;
                        flatCount++;
                    }
                }
            }
            if (f == 0) merger.setReference(frame);
            else merger.mergeFrame(frame, BayerPipeline.POOL);
        }
        BurstRawMerger.Result merged = merger.finish();
        if (merged.frameCount != 4 || merged.effectiveFrames < 2.2f) {
            throw new AssertionError("Burst effective frames too low: " + merged.effectiveFrames);
        }
        double mergedVar = 0;
        double ghostMean = 0;
        int ghostCount = 0;
        for (int y = 96; y < 144; y++) {
            for (int x = 112; x < 176; x++) {
                double val = (merged.bayer12.get(y * w + x) & 0xffff) / 4.0;
                double d = val - 320.0;
                mergedVar += d * d;
            }
        }
        for (int y = 48; y < 80; y++) {
            for (int x = 112; x < 144; x++) {
                ghostMean += (merged.bayer12.get(y * w + x) & 0xffff) / 4.0;
                ghostCount++;
            }
        }
        refVar /= flatCount;
        mergedVar /= flatCount;
        ghostMean /= ghostCount;
        if (mergedVar > refVar / 2.3) {
            throw new AssertionError("Burst noise reduction insufficient: refVar=" + refVar + " mergedVar=" + mergedVar);
        }
        if (Math.abs(ghostMean - 320.0) > 8.0) {
            throw new AssertionError("Moving object ghosted into burst result: mean=" + ghostMean);
        }
    }

    private static void checkChromaDenoisingAndGainmap() {
        int w = 160, h = 120;
        int[] cfa = BAYER[0];
        short[] raw = new short[w * h];
        Random rng = new Random(7);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = 2 * (y & 1) + (x & 1);
                int c = cfa[p];
                float base = x < 80 ? 0.16f : 0.72f;
                float chromaNoise = (c == 0 || c == 3) ? (float) (rng.nextGaussian() * 0.045) : 0f;
                raw[y * w + x] = (short) Math.max(0, Math.min(10000, Math.round(10000f * (base + chromaNoise))));
            }
        }
        BayerPipeline pipe = new BayerPipeline(ShortBuffer.wrap(raw), w, 0, 0, w, h, cfa,
                new float[4], 10000f, UNIT, I, null, 0, 0);

        BayerPipeline.Options offOpt = new BayerPipeline.Options();
        offOpt.natural = true;
        offOpt.iso = 0;
        offOpt.noiseSlope = 0f;
        int[] noDn = new int[w * h];
        pipe.render(0, offOpt, (px, ox, oy, pw, ph) -> {
            for (int r = 0; r < ph; r++) System.arraycopy(px, r * pw, noDn, (oy + r) * w + ox, pw);
        });

        BayerPipeline.Options onOpt = new BayerPipeline.Options();
        onOpt.natural = true;
        onOpt.iso = 800;
        onOpt.noiseSlope = 0.0022f;
        onOpt.buildGainmap = true;
        int[] withDn = new int[w * h];
        BayerPipeline.GainmapData gm = pipe.render(0, onOpt, (px, ox, oy, pw, ph) -> {
            for (int r = 0; r < ph; r++) System.arraycopy(px, r * pw, withDn, (oy + r) * w + ox, pw);
        });
        double varBefore = chromaSpread(noDn, w, 12, 68, 12, 108);
        double varAfter = chromaSpread(withDn, w, 12, 68, 12, 108);
        if (varAfter > varBefore * 0.35) {
            throw new AssertionError("Guided chroma denoise insufficient: before=" + varBefore + " after=" + varAfter);
        }
        if (gm == null || gm.width != w / 4 || gm.height != h / 4 || gm.maxRatio <= 1.5f) {
            throw new AssertionError("Ultra HDR gainmap invalid");
        }
    }

    private static double chromaSpread(int[] argb, int stride, int x0, int x1, int y0, int y1) {
        double sum = 0;
        int n = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int v = argb[y * stride + x];
                int r = (v >> 16) & 255, g = (v >> 8) & 255, b = v & 255;
                sum += (r - g) * (r - g) + (b - g) * (b - g);
                n++;
            }
        }
        return sum / Math.max(1, n);
    }

    private static void checkUltraHdrPatcher() {
        // Construct minimal valid JPEG + APP2 MPF + secondary JPEG tail.
        ByteBuffer buf = ByteBuffer.allocate(256);
        buf.put((byte) 0xff).put((byte) 0xd8); // SOI
        buf.put((byte) 0xff).put((byte) 0xe1).putShort((short) 10).put(new byte[8]); // dummy APP1
        // APP2 MPF segment: length = 2 + 4 ("MPF\0") + 8 (TIFF header) + 2 (count) + 12 (entry) + 4 (nextIFD) + 32 (2 MPEntries) = 64
        buf.put((byte) 0xff).put((byte) 0xe2).putShort((short) 64);
        buf.put(new byte[]{'M', 'P', 'F', 0});
        int tiffBase = buf.position();
        buf.put(new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0}); // LE TIFF header, IFD at +8
        buf.put((byte) 1).put((byte) 0); // 1 IFD entry
        buf.put((byte) 0x02).put((byte) 0xb0); // tag 0xB002 MPEntry
        buf.put((byte) 7).put((byte) 0); // UNDEFINED
        buf.put((byte) 32).put((byte) 0).put((byte) 0).put((byte) 0); // count 32
        buf.put((byte) 26).put((byte) 0).put((byte) 0).put((byte) 0); // value offset from tiffBase = 8 + 2 + 12 + 4 = 26
        buf.putInt(0); // next IFD
        // MPEntry 0 (primary)
        buf.putInt(0).putInt(0).putInt(0).putInt(0);
        // MPEntry 1 (secondary): offset placeholder
        int entry1OffsetField = buf.position() + 8;
        buf.putInt(0).putInt(12).putInt(0).putInt(0);
        // SOS + EOI of primary
        buf.put((byte) 0xff).put((byte) 0xda).putShort((short) 4).putShort((short) 0);
        buf.put((byte) 0xff).put((byte) 0xd9);
        int primaryLen = buf.position();
        // Patch entry 0 size and entry 1 offset in LE
        ByteBuffer le = buf.duplicate().order(java.nio.ByteOrder.LITTLE_ENDIAN);
        le.putInt(entry1OffsetField - 16 - 4, primaryLen);
        le.putInt(entry1OffsetField, primaryLen - tiffBase);
        // Append secondary gainmap JPEG (12 bytes)
        buf.put((byte) 0xff).put((byte) 0xd8).put(new byte[8]).put((byte) 0xff).put((byte) 0xd9);
        byte[] full = Arrays.copyOf(buf.array(), buf.position());

        UltraHdrPatcher.Split split = UltraHdrPatcher.split(full);
        if (split == null || split.primary.length != primaryLen || split.tail.length != 12) {
            throw new AssertionError("UltraHdrPatcher split failed");
        }
        // Grow APP1 in primary by +20 bytes
        byte[] grown = new byte[split.primary.length + 20];
        System.arraycopy(split.primary, 0, grown, 0, 14);
        grown[4] = (byte) 0; grown[5] = (byte) 30; // APP1 len = 10 + 20
        System.arraycopy(split.primary, 14, grown, 34, split.primary.length - 14);
        byte[] rebuilt = UltraHdrPatcher.reassemble(grown, split.tail);
        if (rebuilt == null || rebuilt.length != grown.length + 12) {
            throw new AssertionError("UltraHdrPatcher reassemble failed");
        }
        UltraHdrPatcher.Split resplit = UltraHdrPatcher.split(rebuilt);
        if (resplit == null || resplit.primary.length != grown.length || resplit.tail.length != 12) {
            throw new AssertionError("Rebuilt Ultra HDR MPF offset mismatch");
        }
    }

    private static void near(double value, double expected, double tolerance) {
        if (Math.abs(value-expected) > tolerance || !Double.isFinite(value))
            throw new AssertionError("Expected " + expected + " ±" + tolerance + ", got " + value);
    }

    private static int flatPatch(double r, double g, double b) {
        int[] cfa = BAYER[0];
        double[] levels = {r,g,g,b};
        short[] raw = new short[64];
        for (int y=0; y<8; y++) for (int x=0; x<8; x++) {
            raw[y*8+x] = (short) Math.round(10000 * levels[cfa[2*(y&1)+(x&1)]]);
        }
        return pixels(new BayerPipeline(ShortBuffer.wrap(raw),8,0,0,8,8,cfa,new float[4],
                10000,UNIT,I,null,0,0),0)[0];
    }

    private static void checkTone() {
        double[] levels = {0,0.005,0.04,0.18,0.50,0.75,0.80,1.0};
        int[] expected = {0,11,49,129,199,220,223,231};
        for (int i=0; i<levels.length; i++) {
            int argb = flatPatch(levels[i],levels[i],levels[i]);
            for (int shift : new int[]{0,8,16}) near((argb >> shift) & 255,expected[i],1);
        }
        int previous = 0;
        for (int i=0; i<=512; i++) {
            double level = i / 512.0;
            int code = flatPatch(level,level,level) & 255;
            if (code < previous) throw new AssertionError("Reversed tone gradient at " + level);
            previous = code;
        }
        int warm = flatPatch(0.50,0.26,0.15);
        double red = decode(warm >> 16 & 255), green = decode(warm >> 8 & 255), blue = decode(warm & 255);
        near((red-green)/(green-blue), (.50-.26)/(.26-.15), .09);
        near(red/green, .50/.26, .10);
        if (green <= 0.26) throw new AssertionError("Warm midtone was not lifted");
    }

    private static void checkScenePlacement() {
        int w = 96, h = 72;
        short[] mosaic = new short[w*h];
        for (int y=0; y<h; y++) for (int x=0; x<w; x++) {
            mosaic[y*w+x] = (short) (x < 16 ? 3800 : x < 50 ? 1400 : 500);
        }
        BayerPipeline scene = new BayerPipeline(ShortBuffer.wrap(mosaic),w,0,0,w,h,BAYER[0],
                new float[4],10000,UNIT,I,null,0,0);
        near(scene.measureScene(false, 0, 0).whiteGain,1.68,.01);
        int[] developed = pixels(scene,0);
        if ((developed[4*w+8]&255) < 197 || (developed[4*w+80]&255) > 71)
            throw new AssertionError("Diffuse whites and shadows did not separate");
        for (int y=0; y<h; y++) for (int x=0; x<4; x++) mosaic[y*w+x] = 10000;
        near(scene.measureScene(false, 0, 0).whiteGain,1,.001);
        Arrays.fill(mosaic,(short) 3200);
        near(scene.measureScene(false, 0, 0).whiteGain,1,.001);
        Arrays.fill(mosaic,(short) 170);
        near(scene.measureScene(false, 0, 0).whiteGain,1,.001);
    }

    private static ByteBuffer meterSample(int r, int g, int b, int fraction) {
        ByteBuffer pixels = ByteBuffer.allocate(4 * 1000);
        for (int i=0; i<1000; i++) {
            pixels.put((byte)(i < fraction ? r : 32)).put((byte)(i < fraction ? g : 32))
                    .put((byte)(i < fraction ? b : 32)).put((byte)255);
        }
        return pixels.flip();
    }

    private static void checkMeter() {
        HighlightMeter meter = new HighlightMeter();
        for (int i=0; i<4; i++) if (meter.update(meterSample(255,255,255,3)))
            throw new AssertionError("Isolated reflections reduced exposure");
        if (meter.update(meterSample(250,248,242,18)) || !meter.update(meterSample(250,248,242,18))
                || !meter.update(meterSample(217,211,207,18))) throw new AssertionError("Highlight protection did not hold");
        for (int i=0; i<3; i++) if (meter.update(meterSample(180,170,165,18)) != (i<2))
            throw new AssertionError("Exposure did not return smoothly");
        meter.reset();
        if (meter.update(meterSample(255,40,20,70)) || !meter.update(meterSample(255,40,20,70)))
            throw new AssertionError("Large saturated color was missed");
    }

    private static double decode(int code) {
        double s = code / 255.0;
        return s <= 0.04045 ? s/12.92 : Math.pow((s+0.055)/1.055,2.4);
    }

    public static void main(String[] args) {
        checkTone();
        checkScenePlacement();
        checkMeter();
        checkNaturalLight();
        checkHardEdges();
        checkFilters();
        checkDuvGuardAndDualMatrix();
        checkBurstRawMerger();
        checkFullRangeBurst();
        if (Arrays.asList(args).contains("--full")) checkFullResolution();
        checkChromaDenoisingAndGainmap();
        checkUltraHdrPatcher();
        for (int[] cfa : BAYER) {
            short[] raw = sensor(cfa,new float[]{0.22f,0.3f,0.15f,0.55f},false);
            float[] balance = {1,1,2,1};
            float[] matrix = {0,0,1, 0,1,0, 1,0,0};
            BayerPipeline pipe = frame(raw,cfa,balance,matrix);
            int[] flat = pixels(pipe,0);
            for (int argb : flat) {
                near(argb>>16 & 255, 205, 1);
                near(argb>>8 & 255, 154, 1);
                near(argb & 255, 132, 1);
            }
            ShortBuffer reduced = BayerPipeline.reduce(ShortBuffer.wrap(raw), W+PAD,2,1,W,H,14,12);
            BayerPipeline preview = new BayerPipeline(reduced,14,0,0,14,12,cfa,BLACK,1023,balance,matrix,null,0,0);
            int[] proof = pixels(preview,0);
            for (int y=0; y<12; y++) for (int x=0; x<14; x++) {
                if (proof[y*14+x] != flat[(y&1)*W+(x&1)]) throw new AssertionError("Preview hue at same CFA site");
            }

            raw = sensor(cfa,new float[]{0.4f,0.3f,0.15f,0.25f},true);
            pipe = frame(raw,cfa,balance,matrix);
            int[] image = pixels(pipe,0), cw = pixels(pipe,90), upside = pixels(pipe,180), ccw = pixels(pipe,270);
            for (int y=0; y<H; y++) for (int x=0; x<W; x++) {
                int v = image[y*W+x];
                if (v != cw[x*H+H-1-y] || v != ccw[(W-1-x)*H+y] || v != upside[(H-1-y)*W+W-1-x]) {
                    throw new AssertionError("Strip transposition");
                }
            }
        }
        int[] cfa = BAYER[3];
        float[] map = {1,1,1,1, 1.5f,1.3f,1.4f,1.8f, 1.6f,1.1f,1.2f,1.5f, 1.9f,1.6f,1.8f,2.1f};
        short[] shaded = new short[W*H];
        for (int y=0; y<H; y++) for (int x=0; x<W; x++) {
            int p=2*(y&1)+(x&1), ch=cfa[p];
            float u=(float)x/(W-1), v=(float)y/(H-1);
            float lens=(1-v)*((1-u)*map[ch]+u*map[ch+4])+v*((1-u)*map[ch+8]+u*map[ch+12]);
            shaded[y*W+x]=(short)Math.round(BLACK[p]+(1023-BLACK[p])*0.27f/lens);
        }
        BayerPipeline corrected = new BayerPipeline(ShortBuffer.wrap(shaded),W,0,0,W,H,cfa,BLACK,
                1023,UNIT,I,map,2,2);
        for (int argb : pixels(corrected,0)) for (int bit : new int[]{0,8,16}) near(argb >> bit & 255,158,1);

        float[] neutral = {.5f,1,.7f};
        float[] calibration = {1,0.1f,0, 0,1,0, 0,0.04f,0.98f};
        float[] forward = {.4360747f,.3850649f,.1430804f, .2225045f,.7168786f,.0606169f,
                .0139322f,.0971045f,.7141733f};
        float[] dng=SensorColorMatrix.fromDng(neutral,calibration,calibration,I,I,forward,forward,21,17);
        for (int row=0; row<3; row++) {
            near(dng[3*row]*neutral[0]+dng[3*row+1]*neutral[1]+dng[3*row+2]*neutral[2],1,0.001);
        }
        BayerPipeline bright=frame(sensor(cfa,new float[]{.5f,.25f,.25f,.125f},false),cfa,UNIT,
                new float[]{4,0,0, 0,4,0, 0,0,4});
        int v=pixels(bright,0)[0];
        near(v>>16&255,encode(.928),1);
        near(v>>8&255,encode(.928*.5),1);
        int specular=pixels(frame(sensor(cfa,new float[]{1,1,1,1},false),cfa,
                new float[]{2,1,1,1.7f},I),0)[0];
        near(specular>>16 & 255,specular>>8 & 255,0);
        near(specular>>16 & 255,specular & 255,0);
        System.out.println("PASS: Bayer, CCM, Duv guard, burst merger, chroma denoise, tone, filters, Gainmap, MPF");
    }
}
