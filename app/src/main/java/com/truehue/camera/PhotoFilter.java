package com.truehue.camera;

/** Subtle optical color profiles in linear sRGB. Shared by live viewfinder and RAW development. */
enum PhotoFilter {
    ORIGINAL("original", "原彩", "忠实还原肉眼所见的光影与色彩", "Original color", 0f, 0f, 0f, 0f, false, false),
    MASTER("master", "光影", "哈苏风格立体光影与浓郁色彩，明暗通透有质感", "Master light", .045f, -.065f, 0f, .10f, false, true),
    SOFT("soft", "柔光", "柔和亮部过渡，适合窗光与人像", "Soft light", -.11f, .04f, .09f, 0f, false, false),
    DETAIL("detail", "层次", "强化明暗立体感，适合建筑与木纹", "Defined tones", .045f, -.11f, 0f, .03f, false, false),
    MONO("mono", "黑白", "经典黑白胶片感，纯粹记录光影层次", "Monochrome", .03f, -.13f, 1f, 0f, true, false);

    final String id, label, description, exif;
    final float highlights, shadows, desaturation, vibrance;
    final boolean monochrome, masterLook;

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
                float highlights, float shadows, float desaturation, float vibrance,
                boolean monochrome, boolean masterLook) {
        this.id = id;
        this.label = label;
        this.description = description;
        this.exif = exif;
        this.highlights = highlights;
        this.shadows = shadows;
        this.desaturation = desaturation;
        this.vibrance = vibrance;
        this.monochrome = monochrome;
        this.masterLook = masterLook;
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
            float peak0 = Math.max(r, Math.max(g, b));
            float light = masterLook ? smooth(.20f, .76f, y) : smooth(.25f, .82f, y);
            float shade = masterLook
                    ? smooth(.015f, .055f, y) * (1 - smooth(.075f, .22f, y))
                    : smooth(.025f, .10f, y) * (1 - smooth(.10f, .40f, y));
            float fade = masterLook
                    ? 1f - smooth(.68f, .90f, Math.max(y, peak0))
                    : 1f - smooth(.86f, 1f, y);
            if (desaturation > 0) {
                float mix = desaturation * light * fade;
                r += (y - r) * mix;
                g += (y - g) * mix;
                b += (y - b) * mix;
            }
            float lumBias = 0f;
            float blueDepth = 0f;
            if (masterLook && y > .02f && y < .92f) {
                float pk = Math.max(r, Math.max(g, b));
                float mn = Math.min(r, Math.min(g, b));
                float chroma = pk > 1e-5f ? (pk - mn) / pk : 0f;
                float cMask = smooth(.06f, .24f, chroma) * smooth(.02f, .10f, y) * (1f - smooth(.65f, .92f, y));
                if (cMask > 0f) {
                    float dr = r - y, dg = g - y, db = b - y;
                    float norm = (float) Math.sqrt(dr * dr + dg * dg + db * db) + 1e-5f;
                    float nr = dr / norm, ng = dg / norm, nb = db / norm;
                    float blueW = smooth(.35f, .80f, nb);
                    float warmW = smooth(.35f, .80f, nr) * smooth(.15f, .60f, -nb);
                    float yGreenW = smooth(.25f, .70f, ng) * smooth(-.10f, .35f, nr);
                    lumBias = (-0.040f * blueW + 0.025f * warmW * (1f - smooth(.25f, .55f, y))) * cMask;
                    blueDepth = 0.08f * blueW * cMask;
                    if (yGreenW > 0f) {
                        float shift = 0.045f * yGreenW * cMask;
                        r = Math.max(0f, r - shift * Math.max(0f, r - b) * 0.45f);
                        b = Math.min(1f, b + shift * Math.max(0f, g - b) * 0.18f);
                    }
                }
            }
            if ((vibrance > 0 || blueDepth > 0) && y > .02f && (masterLook || y < .78f)) {
                float pk = Math.max(r, Math.max(g, b));
                float mn = Math.min(r, Math.min(g, b));
                float chroma = pk > 1e-5f ? (pk - mn) / pk : 0f;
                float yGate = masterLook ? Math.max(y, pk) : y;
                float boost = vibrance * smooth(.05f, .25f, chroma) * (1f - smooth(.55f, .88f, chroma))
                        * smooth(.02f, .12f, y) * (1f - smooth(masterLook ? .50f : .48f, masterLook ? .82f : .78f, yGate))
                        + blueDepth;
                r = Math.max(0f, y + (r - y) * (1f + boost));
                g = Math.max(0f, y + (g - y) * (1f + boost));
                b = Math.max(0f, y + (b - y) * (1f + boost));
            }
            float peak = Math.max(r, Math.max(g, b));
            float gain = 1 + highlights * light * fade + shadows * shade + lumBias;
            if (gain > 1 && peak > 0) gain = Math.min(gain, 1 / peak);
            r = Math.max(0f, r * gain);
            g = Math.max(0f, g * gain);
            b = Math.max(0f, b * gain);
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
