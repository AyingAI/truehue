package com.truehue.camera;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Watches uncorrected SDR viewfinder samples, ignoring isolated reflections and hot pixels. */
final class HighlightMeter {
    static final int BINS = 32;
    private boolean protecting;
    private int consecutive;
    private final int[] counts = new int[BINS];
    private final float[] histogram = new float[BINS];
    private float clipFraction;

    boolean update(ByteBuffer rgba) {
        int count = rgba.remaining() / 4;
        int light = 0, color = 0, clipped = 0;
        Arrays.fill(counts, 0);
        // Lower release thresholds accommodate the dimmer preview once AE has responded.
        int lightThreshold = protecting ? 205 : 247;
        int colorThreshold = protecting ? 218 : 252;
        for (int i = 0; i < count; i++) {
            int r = rgba.get() & 255, g = rgba.get() & 255, b = rgba.get() & 255;
            rgba.get();
            int high = Math.max(r, g), low = Math.min(r, g);
            if (Math.max(low, Math.min(high, b)) >= lightThreshold) light++;
            int peak = Math.max(high, b);
            if (peak >= colorThreshold) color++;
            if (peak >= 250) clipped++;
            int lum = (54 * r + 183 * g + 19 * b) >> 8;
            int bin = Math.max(0, Math.min(BINS - 1, lum >> 3));
            counts[bin]++;
        }
        if (count > 0) {
            int maxCount = 1;
            for (int c : counts) if (c > maxCount) maxCount = c;
            float inv = 1f / maxCount;
            for (int i = 0; i < BINS; i++) histogram[i] = counts[i] * inv;
            clipFraction = (float) clipped / count;
        } else {
            Arrays.fill(histogram, 0f);
            clipFraction = 0f;
        }
        boolean brightArea = count > 0 && (light * 100 >= count || color * 25 >= count);
        if (brightArea == protecting) consecutive = 0;
        else if (++consecutive >= (protecting ? 3 : 2)) {
            protecting = brightArea;
            consecutive = 0;
        }
        return protecting;
    }

    float[] histogramCopy() {
        return histogram.clone();
    }

    float clipFraction() {
        return clipFraction;
    }

    void reset() {
        protecting = false;
        consecutive = 0;
        Arrays.fill(histogram, 0f);
        clipFraction = 0f;
    }
}
