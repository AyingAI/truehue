package com.truehue.camera;

import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Aligns and merges a handheld burst of Bayer RAW frames into a 12-bit-precision mosaic.
 * All shifts remain strictly even-aligned so Bayer CFA parity is preserved without resampling.
 */
final class BurstRawMerger {
    static final class Result {
        final ShortBuffer bayer12;
        final float[] black12;
        final float white12;
        final float effectiveFrames;
        final int frameCount;

        Result(ShortBuffer bayer12, float[] black12, float white12, float effectiveFrames, int frameCount) {
            this.bayer12 = bayer12;
            this.black12 = black12;
            this.white12 = white12;
            this.effectiveFrames = effectiveFrames;
            this.frameCount = frameCount;
        }
    }

    private static final int WEIGHT_UNIT = 16;
    private static final int TILE_4X = 32; // 32 * 4 = 128 px full-res tile

    final int width, height;
    private final int[] channel;
    private final float[] black;
    private final float white, blackMean, range, noiseSlope, noiseOffset;
    private final int w4, h4, w8, h8;
    private short[] ref;
    private float[] refGreen4, refGreen8;
    private int[] accum;
    private short[] weightSum;
    private int frameCount;

    BurstRawMerger(int width, int height, int[] channel, float[] black, float white,
                   float noiseSlope, float noiseOffset) {
        if ((width & 1) != 0 || (height & 1) != 0 || width < 16 || height < 16) {
            throw new IllegalArgumentException("BurstRawMerger 需要偶数尺寸 RAW 图像");
        }
        this.width = width;
        this.height = height;
        this.channel = channel.clone();
        this.black = black.clone();
        this.white = white;
        this.blackMean = 0.25f * (black[0] + black[1] + black[2] + black[3]);
        this.range = Math.max(1f, white - blackMean);
        this.noiseSlope = noiseSlope > 0 ? noiseSlope : 0.0004f;
        this.noiseOffset = noiseOffset > 0 ? noiseOffset : 2e-6f;
        this.w4 = width / 4;
        this.h4 = height / 4;
        this.w8 = w4 / 2;
        this.h8 = h4 / 2;
    }

    static short[] extractActive(ShortBuffer src, int stride, int left, int top, int width, int height) {
        ShortBuffer dup = src.duplicate();
        short[] out = new short[width * height];
        for (int y = 0; y < height; y++) {
            dup.position((top + y) * stride + left);
            dup.get(out, y * width, width);
        }
        return out;
    }

    void setReference(short[] bayer) {
        if (bayer.length != width * height) throw new IllegalArgumentException("RAW 帧尺寸不匹配");
        this.ref = bayer;
        this.refGreen4 = buildGreen4(bayer);
        this.refGreen8 = buildGreen8(refGreen4);
        int n = bayer.length;
        this.accum = new int[n];
        this.weightSum = new short[n];
        for (int i = 0; i < n; i++) {
            accum[i] = (bayer[i] & 0xffff) * WEIGHT_UNIT;
            weightSum[i] = WEIGHT_UNIT;
        }
        this.frameCount = 1;
    }

    void mergeFrame(short[] alt, ExecutorService pool) {
        if (ref == null) {
            setReference(alt);
            return;
        }
        if (alt.length != width * height) return;
        float[] altGreen4 = buildGreen4(alt);
        float[] altGreen8 = buildGreen8(altGreen4);

        // 1. Coarse global shift on 8x green pyramid (+/-20 steps = +/-160 full-res px).
        int[] global8 = searchGlobal8(altGreen8);
        int gx4 = global8[0] * 2;
        int gy4 = global8[1] * 2;

        // 2. Per-tile alignment (4x grid -> full-res even Bayer quad refinement).
        int tileCols = Math.max(1, (w4 + TILE_4X - 1) / TILE_4X);
        int tileRows = Math.max(1, (h4 + TILE_4X - 1) / TILE_4X);
        int[] shiftX = new int[tileCols * tileRows];
        int[] shiftY = new int[tileCols * tileRows];

        runRows(pool, tileRows, ty -> {
            int y4Start = ty * TILE_4X;
            int y4End = Math.min(h4, y4Start + TILE_4X);
            for (int tx = 0; tx < tileCols; tx++) {
                int x4Start = tx * TILE_4X;
                int x4End = Math.min(w4, x4Start + TILE_4X);
                int[] best4 = searchTile4(altGreen4, x4Start, y4Start, x4End, y4End, gx4, gy4);
                int[] fine = refineTileBayer(alt, x4Start * 4, y4Start * 4, x4End * 4, y4End * 4,
                        best4[0] * 4, best4[1] * 4);
                int idx = ty * tileCols + tx;
                shiftX[idx] = fine[0];
                shiftY[idx] = fine[1];
            }
        });

        // 3. Motion-robust temporal fusion in 16x16 blocks (each strip is height=16 aligned to 2).
        int blockRows = (height + 15) / 16;
        runRows(pool, blockRows, by -> {
            int y0 = by * 16;
            int y1 = Math.min(height, y0 + 16);
            int ty = Math.min(tileRows - 1, ((y0 + y1) / 2) / (TILE_4X * 4));
            for (int x0 = 0; x0 < width; x0 += 16) {
                int x1 = Math.min(width, x0 + 16);
                int tx = Math.min(tileCols - 1, ((x0 + x1) / 2) / (TILE_4X * 4));
                int dx = shiftX[ty * tileCols + tx];
                int dy = shiftY[ty * tileCols + tx];
                // Keep dx, dy strictly even so CFA parity stays identical!
                dx &= ~1;
                dy &= ~1;
                if (y0 + dy < 0 || y1 + dy > height || x0 + dx < 0 || x1 + dx > width) continue;

                // Measure 16x16 block residual against expected pair shot/read noise.
                double sqSum = 0;
                double varSum = 0;
                int count = 0;
                for (int y = y0; y < y1; y += 2) {
                    int rRow = y * width;
                    int aRow = (y + dy) * width + dx;
                    for (int x = x0; x < x1; x += 2) {
                        int rv = ref[rRow + x] & 0xffff;
                        int av = alt[aRow + x] & 0xffff;
                        float d = av - rv;
                        sqSum += d * d;
                        varSum += pairNoiseVar(rv);
                        count++;
                    }
                }
                if (count == 0) continue;
                float blockRatio = (float) (sqSum / Math.max(1.0, varSum * 3.0));
                if (blockRatio > 4.0f) continue;

                for (int y = y0; y < y1; y++) {
                    int rRow = y * width;
                    int aRow = (y + dy) * width + dx;
                    for (int x = x0; x < x1; x++) {
                        int idx = rRow + x;
                        int rv = ref[idx] & 0xffff;
                        int av = alt[aRow + x] & 0xffff;
                        float d = av - rv;
                        float pVar = pairNoiseVar(rv);
                        float pixelRatio = (d * d) / (pVar * 6.5f);
                        float score = Math.max(blockRatio, pixelRatio);
                        if (score <= 4.0f) {
                            int w = Math.round((float) WEIGHT_UNIT / (1f + score * score));
                            if (w > 0) {
                                accum[idx] += av * w;
                                weightSum[idx] = (short) Math.min(32000, weightSum[idx] + w);
                            }
                        }
                    }
                }
            }
        });
        frameCount++;
    }

    Result finish() {
        if (ref == null) throw new IllegalStateException("未设置参考帧");
        int n = width * height;
        short[] out = new short[n];
        float scale = Math.min(4f, 65535f / white);
        long totalWeights = 0;
        for (int i = 0; i < n; i++) {
            int w = weightSum[i] & 0xffff;
            totalWeights += w;
            int scaled = Math.round(scale * accum[i] / w);
            out[i] = (short) Math.max(0, Math.min(65535, scaled));
        }
        float[] black12 = {black[0] * scale, black[1] * scale, black[2] * scale, black[3] * scale};
        float effectiveFrames = Math.max(1f, (float) ((double) totalWeights / ((double) n * WEIGHT_UNIT)));
        return new Result(ShortBuffer.wrap(out), black12, white * scale, effectiveFrames, frameCount);
    }

    private float pairNoiseVar(int dn) {
        float norm = Math.max(0f, (dn - blackMean) / range);
        float singleVar = Math.max(12f, (noiseSlope * norm + noiseOffset) * range * range);
        return 2.0f * singleVar;
    }

    private float[] buildGreen4(short[] bayer) {
        float[] out = new float[w4 * h4];
        boolean g00 = channel[0] == 1 || channel[0] == 2;
        boolean g01 = channel[1] == 1 || channel[1] == 2;
        boolean g10 = channel[2] == 1 || channel[2] == 2;
        boolean g11 = channel[3] == 1 || channel[3] == 2;
        for (int y4 = 0; y4 < h4; y4++) {
            int y = y4 * 4;
            for (int x4 = 0; x4 < w4; x4++) {
                int x = x4 * 4;
                float sum = 0;
                int cnt = 0;
                for (int dy = 0; dy < 4; dy++) {
                    int row = (y + dy) * width + x;
                    boolean evenY = (dy & 1) == 0;
                    boolean gEven = evenY ? g00 : g10;
                    boolean gOdd = evenY ? g01 : g11;
                    for (int dx = 0; dx < 4; dx++) {
                        if ((dx & 1) == 0 ? gEven : gOdd) {
                            sum += bayer[row + dx] & 0xffff;
                            cnt++;
                        }
                    }
                }
                out[y4 * w4 + x4] = sum / Math.max(1, cnt);
            }
        }
        return out;
    }

    private float[] buildGreen8(float[] green4) {
        if (w8 < 1 || h8 < 1) return new float[0];
        float[] out = new float[w8 * h8];
        for (int y = 0; y < h8; y++) {
            int y4 = y * 2;
            for (int x = 0; x < w8; x++) {
                int x4 = x * 2;
                out[y * w8 + x] = 0.25f * (green4[y4 * w4 + x4] + green4[y4 * w4 + x4 + 1]
                        + green4[(y4 + 1) * w4 + x4] + green4[(y4 + 1) * w4 + x4 + 1]);
            }
        }
        return out;
    }

    private int[] searchGlobal8(float[] alt8) {
        if (w8 < 16 || h8 < 16) return new int[]{0, 0};
        int marginX = Math.max(4, w8 / 5), marginY = Math.max(4, h8 / 5);
        int maxShift = Math.min(20, Math.min(marginX, marginY) - 1);
        int bestDx = 0, bestDy = 0;
        double bestCost = Double.MAX_VALUE;
        for (int dy = -maxShift; dy <= maxShift; dy++) {
            for (int dx = -maxShift; dx <= maxShift; dx++) {
                double sad = 0;
                int cnt = 0;
                for (int y = marginY; y < h8 - marginY; y += 2) {
                    int rRow = y * w8;
                    int aRow = (y + dy) * w8 + dx;
                    for (int x = marginX; x < w8 - marginX; x += 2) {
                        sad += Math.abs(refGreen8[rRow + x] - alt8[aRow + x]);
                        cnt++;
                    }
                }
                if (cnt > 0 && sad < bestCost) {
                    bestCost = sad;
                    bestDx = dx;
                    bestDy = dy;
                }
            }
        }
        return new int[]{bestDx, bestDy};
    }

    private int[] searchTile4(float[] alt4, int x0, int y0, int x1, int y1, int gx4, int gy4) {
        int bestDx = gx4, bestDy = gy4;
        double bestCost = Double.MAX_VALUE;
        int radius = 5;
        for (int dy = gy4 - radius; dy <= gy4 + radius; dy++) {
            for (int dx = gx4 - radius; dx <= gx4 + radius; dx++) {
                double sad = 0;
                int cnt = 0;
                int ys = Math.max(0, Math.max(y0, -dy));
                int ye = Math.min(y1, h4 - dy);
                int xs = Math.max(0, Math.max(x0, -dx));
                int xe = Math.min(x1, w4 - dx);
                for (int y = ys; y < ye; y++) {
                    int rRow = y * w4;
                    int aRow = (y + dy) * w4 + dx;
                    for (int x = xs; x < xe; x++) {
                        sad += Math.abs(refGreen4[rRow + x] - alt4[aRow + x]);
                        cnt++;
                    }
                }
                if (cnt >= 16) {
                    double mean = sad / cnt;
                    // Small regularization bias toward global shift for flat/low-contrast tiles.
                    double reg = 0.08 * (Math.abs(dx - gx4) + Math.abs(dy - gy4));
                    if (mean + reg < bestCost) {
                        bestCost = mean + reg;
                        bestDx = dx;
                        bestDy = dy;
                    }
                }
            }
        }
        return new int[]{bestDx, bestDy};
    }

    private int[] refineTileBayer(short[] alt, int x0, int y0, int x1, int y1, int baseDx, int baseDy) {
        baseDx &= ~1;
        baseDy &= ~1;
        int gCol = (channel[0] == 1 || channel[0] == 2) ? 0 : 1;
        int bestDx = baseDx, bestDy = baseDy;
        double bestCost = Double.MAX_VALUE;
        for (int qy = -2; qy <= 2; qy++) {
            int dy = baseDy + qy * 2;
            for (int qx = -2; qx <= 2; qx++) {
                int dx = baseDx + qx * 2;
                int ys = Math.max(0, Math.max(y0, -dy));
                int ye = Math.min(y1, height - dy);
                int xs = Math.max(gCol, Math.max(x0 + gCol, -dx + gCol));
                int xe = Math.min(x1, width - dx);
                double sad = 0;
                int cnt = 0;
                for (int y = ys; y < ye; y += 2) {
                    int rRow = y * width;
                    int aRow = (y + dy) * width + dx;
                    for (int x = xs; x < xe; x += 2) {
                        sad += Math.abs((ref[rRow + x] & 0xffff) - (alt[aRow + x] & 0xffff));
                        cnt++;
                    }
                }
                if (cnt >= 16) {
                    double mean = sad / cnt;
                    double reg = 0.1 * (Math.abs(qx) + Math.abs(qy));
                    if (mean + reg < bestCost) {
                        bestCost = mean + reg;
                        bestDx = dx;
                        bestDy = dy;
                    }
                }
            }
        }
        return new int[]{bestDx, bestDy};
    }

    interface IntTask { void run(int index); }

    private static void runRows(ExecutorService pool, int count, IntTask task) {
        if (pool == null || count <= 1) {
            for (int i = 0; i < count; i++) task.run(i);
            return;
        }
        int workers = Math.min(count, 6);
        int chunk = (count + workers - 1) / workers;
        List<Callable<Void>> jobs = new ArrayList<>(workers);
        for (int w = 0; w < workers; w++) {
            int s = w * chunk, e = Math.min(count, s + chunk);
            if (s >= e) break;
            jobs.add(() -> {
                for (int i = s; i < e; i++) task.run(i);
                return null;
            });
        }
        try {
            for (Future<Void> f : pool.invokeAll(jobs)) f.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            for (int i = 0; i < count; i++) task.run(i);
        } catch (ExecutionException ex) {
            throw new RuntimeException(ex.getCause());
        }
    }
}
