package com.truehue.camera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.media.ExifInterface;
import android.media.Image;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class PhotoStore {
    static final class PhotoMeta {
        final String summary;
        final String look;
        final String modeLabel;
        final boolean ultraHdr;

        PhotoMeta(String summary, String look, String modeLabel, boolean ultraHdr) {
            this.summary = summary;
            this.look = look;
            this.modeLabel = modeLabel;
            this.ultraHdr = ultraHdr;
        }
    }

    private PhotoStore() {}

    private interface WriteFile { void write(OutputStream out) throws IOException; }
    private interface EditExif { void edit(ExifInterface exif); }

    static Uri save(Context context, Bitmap bitmap, long takenAt, TotalCaptureResult shot,
                    float biasEv, boolean natural, boolean baselineCopy, PhotoFilter filter) throws IOException {
        return save(context, bitmap, takenAt, shot, biasEv, natural, baselineCopy, filter, 0);
    }

    static Uri save(Context context, Bitmap bitmap, long takenAt, TotalCaptureResult shot,
                    float biasEv, boolean natural, boolean baselineCopy, PhotoFilter filter,
                    int focal35mm) throws IOException {
        String description = shot != null && !natural
                ? (filter == PhotoFilter.ORIGINAL ? "Baseline 0.5.1" : "Baseline 0.5.1 + " + filter.exif)
                : filter.exif;
        return saveBitmap(context, bitmap, takenAt, shot, biasEv, 0, baselineCopy ? "_051.jpg" : ".jpg",
                shot == null ? "TrueHue Preview" : "TrueHue RAW", description, focal35mm);
    }

    private static Uri saveBitmap(Context context, Bitmap bitmap, long takenAt, TotalCaptureResult shot,
                                  float biasEv, int degrees, String suffix, String software,
                                  String description, int focal35mm) throws IOException {
        EditExif edit = exif -> {
            exif.setAttribute(ExifInterface.TAG_COLOR_SPACE, "1");
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(orientation(degrees)));
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL,
                    new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT).format(new Date(takenAt)));
            exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER);
            exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL);
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, software);
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, description);
            if (focal35mm > 0) {
                exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, Integer.toString(focal35mm));
            }
            if (shot != null) {
                if (Float.isFinite(biasEv)) {
                    exif.setAttribute(ExifInterface.TAG_EXPOSURE_BIAS_VALUE, Math.round(biasEv * 1000) + "/1000");
                }
                Float focal = shot.get(CaptureResult.LENS_FOCAL_LENGTH);
                if (focal != null && focal > 0) {
                    exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, Math.round(focal * 1000) + "/1000");
                }
                writeTag(exif, ExifInterface.TAG_F_NUMBER, shot.get(CaptureResult.LENS_APERTURE));
                writeTag(exif, ExifInterface.TAG_ISO_SPEED_RATINGS, shot.get(CaptureResult.SENSOR_SENSITIVITY));
                Long exposure = shot.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                if (exposure != null) writeTag(exif, ExifInterface.TAG_EXPOSURE_TIME, exposure * 1e-9);
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && bitmap.hasGainmap()) {
            ByteArrayOutputStream encoded = new ByteArrayOutputStream(2 * 1024 * 1024);
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 97, encoded)) {
                throw new IOException("JPEG 编码失败");
            }
            byte[] rawBytes = encoded.toByteArray();
            UltraHdrPatcher.Split split = UltraHdrPatcher.split(rawBytes);
            if (split != null) {
                File tmp = File.createTempFile("th_uhdr_", ".jpg", context.getCacheDir());
                try {
                    Files.write(tmp.toPath(), split.primary);
                    ExifInterface exif = new ExifInterface(tmp.getAbsolutePath());
                    edit.edit(exif);
                    exif.saveAttributes();
                    byte[] editedPrimary = Files.readAllBytes(tmp.toPath());
                    byte[] reassembled = UltraHdrPatcher.reassemble(editedPrimary, split.tail);
                    if (reassembled != null) {
                        return publish(context, takenAt, suffix, stream -> stream.write(reassembled), null);
                    }
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
            return publish(context, takenAt, suffix, stream -> stream.write(rawBytes), null);
        }

        return publish(context, takenAt, suffix, stream -> {
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 97, stream)) throw new IOException("JPEG 编码失败");
        }, edit);
    }

    private static void writeTag(ExifInterface exif, String key, Number value) {
        if (value != null) exif.setAttribute(key, String.valueOf(value));
    }

    static Uri saveDeviceJpeg(Context context, Image image, TotalCaptureResult shot, long time,
                              int degrees, float biasEv, PhotoFilter filter) throws IOException {
        ByteBuffer jpeg = image.getPlanes()[0].getBuffer().duplicate();
        if (filter != PhotoFilter.ORIGINAL) {
            byte[] encoded = new byte[jpeg.remaining()];
            jpeg.get(encoded);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inMutable = true;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
            Bitmap bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.length, options);
            if (bitmap == null) throw new IOException("无法读取原生 JPEG");
            try {
                int width = bitmap.getWidth();
                int[] row = new int[width];
                for (int y = 0; y < bitmap.getHeight(); y++) {
                    bitmap.getPixels(row, 0, width, 0, y, width, 1);
                    for (int x = 0; x < width; x++) row[x] = filter.apply(row[x]);
                    bitmap.setPixels(row, 0, width, 0, y, width, 1);
                }
                return saveBitmap(context, bitmap, time, shot, biasEv, degrees, ".jpg", "TrueHue JPEG", filter.exif, 0);
            } finally {
                bitmap.recycle();
            }
        }
        return publish(context, time, ".jpg", stream -> {
            WritableByteChannel channel = Channels.newChannel(stream);
            while (jpeg.hasRemaining()) channel.write(jpeg);
        }, exif -> {
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(orientation(degrees)));
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, filter.exif);
        });
    }

    static Uri saveDng(Context context, Image image, CameraCharacteristics sensor, TotalCaptureResult shot,
                       long time, int degrees) throws IOException {
        return publish(context, time, ".dng", stream -> {
            try (DngCreator dng = new DngCreator(sensor, shot)) {
                dng.setOrientation(orientation(degrees));
                dng.writeImage(stream, image);
            }
        }, null);
    }

    static PhotoMeta readMeta(Context context, Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            byte[] head = new byte[Math.min(256 * 1024, Math.max(4096, in.available()))];
            int total = 0;
            while (total < head.length) {
                int r = in.read(head, total, head.length - total);
                if (r < 0) break;
                total += r;
            }
            boolean hasGainmap = false;
            if (total > 64) {
                byte[] check = total == head.length ? head : java.util.Arrays.copyOf(head, total);
                hasGainmap = new String(check, java.nio.charset.StandardCharsets.ISO_8859_1).contains("MPF\0");
            }
            try (InputStream exifStream = context.getContentResolver().openInputStream(uri)) {
                if (exifStream == null) return null;
                ExifInterface exif = new ExifInterface(exifStream);
                StringBuilder sb = new StringBuilder();
                String focal35 = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM);
                if (focal35 != null && !focal35.isEmpty()) sb.append(focal35).append("mm");
                double fNum = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0);
                if (fNum > 0) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append(String.format(Locale.ROOT, "f/%.2f", fNum));
                }
                double exp = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0);
                if (exp > 0) {
                    if (sb.length() > 0) sb.append(" · ");
                    if (exp >= 0.5) sb.append(String.format(Locale.ROOT, "%.1fs", exp));
                    else sb.append("1/").append(Math.max(1, Math.round(1.0 / exp))).append("s");
                }
                String iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS);
                if (iso != null && !iso.isEmpty()) {
                    if (sb.length() > 0) sb.append(" · ");
                    sb.append("ISO ").append(iso);
                }
                String software = exif.getAttribute(ExifInterface.TAG_SOFTWARE);
                String desc = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION);
                String mode = "TrueHue RAW".equals(software) ? "RAW"
                        : "TrueHue JPEG".equals(software) ? "JPEG" : "预览";
                String look = lookLabel(desc);
                return new PhotoMeta(sb.toString(), look, mode, hasGainmap);
            }
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static String lookLabel(String desc) {
        if (desc == null || desc.isEmpty()) return "原彩";
        for (PhotoFilter f : PhotoFilter.values()) {
            if (desc.contains(f.exif)) return f.label;
        }
        return "原彩";
    }

    private static int orientation(int degrees) {
        switch (Math.floorMod(degrees, 360)) {
            case 90: return ExifInterface.ORIENTATION_ROTATE_90;
            case 180: return ExifInterface.ORIENTATION_ROTATE_180;
            case 270: return ExifInterface.ORIENTATION_ROTATE_270;
            default: return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static Uri publish(Context context, long takenAt, String suffix, WriteFile write, EditExif edit)
            throws IOException {
        String base = new SimpleDateFormat("'TH_'yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date(takenAt));
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, base + suffix);
        values.put(MediaStore.Images.Media.MIME_TYPE, suffix.equals(".dng") ? "image/x-adobe-dng" : "image/jpeg");
        values.put(MediaStore.Images.Media.DATE_TAKEN, takenAt);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/TrueHue");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        ContentResolver resolver = context.getContentResolver();
        Uri uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (uri == null) throw new IOException("无法创建照片");
        boolean published = false;
        try {
            try (OutputStream stream = resolver.openOutputStream(uri, "w")) {
                if (stream == null) throw new IOException("无法写入照片");
                write.write(stream);
            }
            if (edit != null) {
                try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "rw")) {
                    if (descriptor == null) throw new IOException("无法打开照片");
                    ExifInterface exif = new ExifInterface(descriptor.getFileDescriptor());
                    edit.edit(exif);
                    exif.saveAttributes();
                }
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (resolver.update(uri, values, null, null) != 1) throw new IOException("无法发布照片");
            published = true;
            return uri;
        } finally {
            if (!published) resolver.delete(uri, null, null);
        }
    }

    static Bitmap read(Context context, Uri uri, int maxEdge) throws IOException {
        ImageDecoder.Source source = ImageDecoder.createSource(context.getContentResolver(), uri);
        return ImageDecoder.decodeBitmap(source, (decoder, info, ignored) -> {
            int width = info.getSize().getWidth();
            int height = info.getSize().getHeight();
            float scale = Math.min(1f, (float) maxEdge / Math.max(width, height));
            decoder.setTargetSize(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
        });
    }
}
