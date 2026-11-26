package com.truehue.camera;

/** Subtle optical color profiles in linear sRGB. Shared by live viewfinder and RAW development. */
enum PhotoFilter {
    ORIGINAL("original", "原彩", "忠实还原肉眼所见的光影与色彩", "Original color", 0f, 0f, 0f, 0f, false),
    MASTER("master", "光影", "深邃暗部与立体反差，更有电影质感", "Master light", .05f, -.14f, 0f, .06f, false),
    SOFT("soft", "柔光", "柔和亮部过渡，适合窗光与人像", "Soft light", -.11f, .04f, .09f, 0f, false),
    DETAIL("detail", "层次", "强化明暗立体感，适合建筑与木纹", "Defined tones", .045f, -.11f, 0f, .03f, false),
    MONO("mono", "黑白", "经典黑白胶片感，纯粹记录光影层次", "Monochrome", .03f, -.13f, 1f, 0f, true);

    final String id, label, description, exif;
    final float highlights, shadows, desaturation, vibrance;
    final boolean monochrome;

    private static final int[] ENCODE = new int[8193];
    private static final float[] DECODE = new float[256];
    static {
        for (int i = 0; i < ENCODE.length; i++) {
            double v = (double) i / (ENCODE.length - 1);
            double encoded = v <= .0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - .055;
            ENCODE[i] = (int) Math.round(255 * encoded);
        }
        for (int i = 0; i < DECODE.length; i++) {
            double v = i / 255.0;
            DECODE[i] = (float) (v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4));
        }
    }

    PhotoFilter(String id, String label, String description, String exif,
                float highlights, float shadows, float desaturation, float vibrance, boolean monochrome) {
        this.id = id;
        this.label = label;
        this.description = description;
        this.exif = exif;
        this.highlights = highlights;
        this.shadows = shadows;
        this.desaturation = desaturation;
        this.vibrance = vibrance;
        this.monochrome = monochrome;
    }

    static PhotoFilter fromId(String id) {
        for (PhotoFilter filter : values()) if (filter.id.equals(id)) return filter;
        return ORIGINAL;
    }

    int apply(int argb) {
        return encode(DECODE[argb >> 16 & 255], DECODE[argb >> 8 & 255], DECODE[argb & 255]);
    }

    int encode(float r, float g, float b) {
        return encode(r, g, b, false);
    }

    int encode(float r, float g, float b, boolean wideGamut) {
        if (this != ORIGINAL) {
            if (monochrome) {
                float y = Math.max(0f, Math.min(1f, .26f * r + .64f * g + .10f * b));
                float light = smooth(.25f, .82f, y);
                float shade = smooth(.025f, .10f, y) * (1 - smooth(.10f, .40f, y));
                float fade = 1 - smooth(.86f, 1, y);
                float gain = 1 + highlights * light * fade + shadows * shade;
                if (gain > 1 && y > 0) gain = Math.min(gain, 1 / y);
                float m = Math.max(0f, Math.min(1f, y * gain));
                int code = gamma(m);
                return 0xff000000 | code << 16 | code << 8 | code;
            }
            float y = wideGamut ? (0.2290036f * r + 0.6917267f * g + 0.0792697f * b)
                    : (0.2126f * r + 0.7152f * g + 0.0722f * b);
            float light = smooth(.25f, .82f, y);
            float shade = smooth(.025f, .10f, y) * (1 - smooth(.10f, .40f, y));
            float fade = 1 - smooth(.86f, 1, y);
            if (desaturation > 0) {
                float mix = desaturation * light * fade;
                r += (y - r) * mix;
                g += (y - g) * mix;
                b += (y - b) * mix;
            }
            if (vibrance > 0 && y > .02f && y < .78f) {
                float pk = Math.max(r, Math.max(g, b));
                float mn = Math.min(r, Math.min(g, b));
                float chroma = pk > 1e-5f ? (pk - mn) / pk : 0f;
                float boost = vibrance * smooth(.05f, .25f, chroma) * (1f - smooth(.55f, .88f, chroma))
                        * smooth(.02f, .12f, y) * (1f - smooth(.48f, .78f, y));
                r = Math.max(0f, y + (r - y) * (1f + boost));
                g = Math.max(0f, y + (g - y) * (1f + boost));
                b = Math.max(0f, y + (b - y) * (1f + boost));
            }
            float peak = Math.max(r, Math.max(g, b));
            float gain = 1 + highlights * light * fade + shadows * shade;
            if (gain > 1 && peak > 0) gain = Math.min(gain, 1 / peak);
            r *= gain; g *= gain; b *= gain;
        }
        return 0xff000000 | gamma(r) << 16 | gamma(g) << 8 | gamma(b);
    }

    private static float smooth(float lo, float hi, float value) {
        float t = Math.max(0, Math.min(1, (value - lo) / (hi - lo)));
        return t * t * (3 - 2 * t);
    }

    private static int gamma(float x) {
        int i = Math.round(x * (ENCODE.length - 1));
        return ENCODE[Math.max(0, Math.min(ENCODE.length - 1, i))];
    }
}
