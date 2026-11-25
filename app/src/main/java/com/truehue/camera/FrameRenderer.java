package com.truehue.camera;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import static android.opengl.GLES20.*;

/** Renders a latched Camera2 preview buffer with live optical looks, WB preview, and histogram metering. */
public final class FrameRenderer implements GLSurfaceView.Renderer {
    public interface Listener {
        void onInputReady(SurfaceTexture input);
        void onFrameReady();
        void onHighlightMeter(boolean protect);
        default void onHistogram(float[] bins, float clipFraction) {}
        void onPhoto(Bitmap bitmap);
        void onRenderError(String message);
    }

    private static final String VERTEX =
            "attribute vec2 position;\n" +
            "uniform mat4 cameraTransform;\n" +
            "uniform vec2 turn;\n" +
            "uniform float flipY;\n" +
            "varying vec2 uv;\n" +
            "void main() {\n" +
            "  gl_Position = vec4(position, 0.0, 1.0);\n" +
            "  vec2 p = vec2(position.x, position.y * flipY);\n" +
            "  p = mat2(turn.x, turn.y, -turn.y, turn.x) * p;\n" +
            "  uv = (cameraTransform * vec4(0.5 + 0.5 * p, 0.0, 1.0)).xy;\n" +
            "}\n";

    private static final String FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            varying vec2 uv;
            uniform samplerExternalOES cameraImage;
            // grade: (highlights, shadows, desaturation)
            uniform vec3 grade;
            // look: (vibrance, monochrome)
            uniform vec2 look;
            // wbShift: (warmth, tint)
            uniform vec2 wbShift;
            void main() {
              vec3 c = texture2D(cameraImage, uv).rgb;
              bool hasLook = any(notEqual(grade, vec3(0.0))) || any(notEqual(look, vec2(0.0))) || any(notEqual(wbShift, vec2(0.0)));
              if (hasLook) {
                vec3 rgb = mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
                if (any(notEqual(wbShift, vec2(0.0)))) {
                  rgb *= vec3(1.0 + wbShift.x, 1.0 - wbShift.y * 0.14, 1.0 - wbShift.x * 0.90);
                  rgb = clamp(rgb, 0.0, 1.0);
                }
                if (look.y > 0.5) {
                  float m = clamp(dot(rgb, vec3(0.26, 0.64, 0.10)), 0.0, 1.0);
                  float light = smoothstep(0.25, 0.82, m);
                  float shade = smoothstep(0.025, 0.10, m) * (1.0 - smoothstep(0.10, 0.40, m));
                  float fade = 1.0 - smoothstep(0.86, 1.0, m);
                  float gain = 1.0 + grade.x * light * fade + grade.y * shade;
                  if (gain > 1.0 && m > 0.0) gain = min(gain, 1.0 / m);
                  rgb = vec3(clamp(m * gain, 0.0, 1.0));
                } else {
                  float y = dot(rgb, vec3(0.2126, 0.7152, 0.0722));
                  float light = smoothstep(0.25, 0.82, y);
                  float shade = smoothstep(0.025, 0.10, y) * (1.0 - smoothstep(0.10, 0.40, y));
                  float fade = 1.0 - smoothstep(0.86, 1.0, y);
                  if (grade.z > 0.0) {
                    rgb = mix(rgb, vec3(y), grade.z * light * fade);
                  }
                  if (look.x > 0.0 && y > 0.02 && y < 0.78) {
                    float pk = max(rgb.r, max(rgb.g, rgb.b));
                    float mn = min(rgb.r, min(rgb.g, rgb.b));
                    float chroma = pk > 0.0001 ? (pk - mn) / pk : 0.0;
                    float boost = look.x * smoothstep(0.05, 0.25, chroma) * (1.0 - smoothstep(0.55, 0.88, chroma))
                        * smoothstep(0.02, 0.12, y) * (1.0 - smoothstep(0.48, 0.78, y));
                    rgb = clamp(vec3(y) + (rgb - vec3(y)) * (1.0 + boost), 0.0, 1.0);
                  }
                  float peak = max(rgb.r, max(rgb.g, rgb.b));
                  float gain = 1.0 + grade.x * light * fade + grade.y * shade;
                  if (gain > 1.0 && peak > 0.0) gain = min(gain, 1.0 / peak);
                  rgb = clamp(rgb * gain, 0.0, 1.0);
                }
                c = mix(12.92 * rgb, 1.055 * pow(rgb, vec3(1.0/2.4)) - 0.055, step(0.0031308, rgb));
              }
              gl_FragColor = vec4(c, 1.0);
            }
            """;

    private final GLSurfaceView view;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private final AtomicBoolean pendingFrame = new AtomicBoolean();
    private final FloatBuffer quad = ByteBuffer.allocateDirect(8 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer();
    private final float[] transform = new float[16];
    private PhotoFilter filter = PhotoFilter.ORIGINAL;
    private float wbWarmth, wbTint;
    private final HighlightMeter meter = new HighlightMeter();
    private boolean metering, histogramEnabled = true, meterFailed;
    private long lastMeterFrame;

    // All camera texture and OpenGL state below belongs to the GLSurfaceView render thread.
    private SurfaceTexture input;
    private boolean hasFrame;
    private boolean failed;
    private int contextGeneration;
    private int program, externalTexture;
    private int position, cameraTransform, turn, flipY, grade, look, wbShift, cameraImage;
    private int viewWidth, viewHeight, imageWidth, imageHeight;

    public FrameRenderer(GLSurfaceView view, Listener listener) {
        this.view = view;
        this.listener = listener;
        quad.put(new float[]{-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f}).position(0);
    }

    void setFilter(PhotoFilter selected) {
        view.queueEvent(() -> {
            filter = selected;
            view.requestRender();
        });
    }

    void setWhiteBalancePreview(float manualKelvin, float manualTint) {
        float warmth = SensorColorMatrix.previewWarmth(manualKelvin);
        float tint = manualKelvin >= 2000f ? Math.max(-1f, Math.min(1f, manualTint)) : 0f;
        view.queueEvent(() -> {
            wbWarmth = warmth;
            wbTint = tint;
            view.requestRender();
        });
    }

    public void setHighlightMetering(boolean enabled) {
        view.queueEvent(() -> {
            metering = enabled;
            meterFailed = false;
            lastMeterFrame = 0;
            meter.reset();
        });
    }

    public void setHistogramEnabled(boolean enabled) {
        view.queueEvent(() -> {
            histogramEnabled = enabled;
            if (!enabled) meter.reset();
        });
    }

    /** Camera2 includes the sensor's orientation in the SurfaceTexture sampling matrix. */
    public void setGeometry(int width, int height, int clockwiseDegrees) {
        boolean swap = Math.floorMod(clockwiseDegrees, 180) == 90;
        view.queueEvent(() -> {
            imageWidth = swap ? height : width;
            imageHeight = swap ? width : height;
            hasFrame = false;
            view.requestRender();
        });
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        int epoch = generation.incrementAndGet();
        contextGeneration = epoch;
        releaseInput();
        hasFrame = false;
        failed = false;
        externalTexture = 0;
        program = 0;
        try {
            program = makeProgram();
            position = glGetAttribLocation(program, "position");
            cameraTransform = glGetUniformLocation(program, "cameraTransform");
            turn = glGetUniformLocation(program, "turn");
            flipY = glGetUniformLocation(program, "flipY");
            grade = glGetUniformLocation(program, "grade");
            look = glGetUniformLocation(program, "look");
            wbShift = glGetUniformLocation(program, "wbShift");
            cameraImage = glGetUniformLocation(program, "cameraImage");
            glDisable(GL_DITHER);
            int[] texture = new int[1];
            glGenTextures(1, texture, 0);
            externalTexture = texture[0];
            configureTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture);
            checkGl();
            input = new SurfaceTexture(externalTexture);
            SurfaceTexture created = input;
            input.setOnFrameAvailableListener(ignored -> {
                if (generation.get() == epoch && !pendingFrame.getAndSet(true)) {
                    view.requestRender();
                }
            }, main);
            notifyCurrent(epoch, () -> listener.onInputReady(created));
        } catch (RuntimeException e) {
            failed = true;
            report(epoch, "无法初始化相机预览", e);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        viewWidth = width;
        viewHeight = height;
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glClearColor(0f, 0f, 0f, 1f);
        glClear(GL_COLOR_BUFFER_BIT);
        if (failed || input == null || viewWidth == 0 || viewHeight == 0 ||
                imageWidth <= 0 || imageHeight <= 0) return;

        int epoch = contextGeneration;
        if (generation.get() != epoch) return;
        try {
            boolean firstFrame = !hasFrame;
            if (pendingFrame.getAndSet(false)) {
                input.updateTexImage();
                input.getTransformMatrix(transform);
                hasFrame = true;
            }
            if (!hasFrame) return;

            float scale = Math.min((float) viewWidth / imageWidth, (float) viewHeight / imageHeight);
            int width = Math.max(1, Math.round(imageWidth * scale));
            int height = Math.max(1, Math.round(imageHeight * scale));
            glViewport((viewWidth - width) / 2, (viewHeight - height) / 2, width, height);
            draw(filter, wbWarmth, wbTint, 0, false);
            if (firstFrame) {
                checkGl();
                notifyCurrent(epoch, listener::onFrameReady);
            }
            long frame = input.getTimestamp();
            long interval = histogramEnabled ? 220_000_000L : 450_000_000L;
            if ((metering || histogramEnabled) && !meterFailed
                    && (lastMeterFrame == 0 || frame - lastMeterFrame >= interval)) {
                lastMeterFrame = frame;
                try {
                    boolean protect = meter.update(readPixels(48, 36, PhotoFilter.ORIGINAL, false, 0));
                    if (metering) {
                        notifyCurrent(epoch, () -> listener.onHighlightMeter(protect));
                    }
                    if (histogramEnabled) {
                        float[] bins = meter.histogramCopy();
                        float clip = meter.clipFraction();
                        notifyCurrent(epoch, () -> listener.onHistogram(bins, clip));
                    }
                } catch (RuntimeException e) {
                    meterFailed = true;
                    notifyCurrent(epoch, () -> listener.onHighlightMeter(false));
                    Log.w("TrueHue", "Highlight metering unavailable", e);
                }
            }
        } catch (RuntimeException e) {
            failed = true;
            report(epoch, "相机预览中断", e);
        }
    }

    /** Queued before latching any subsequent camera buffer, preserving the last rendered frame. */
    public void shoot(int extraClockwiseDegrees, PhotoFilter selected) {
        int epoch = generation.get();
        view.queueEvent(() -> {
            if (generation.get() != epoch) return;
            if (!hasFrame || failed) {
                notifyCurrent(epoch, () -> listener.onRenderError("等待相机画面后再试"));
                return;
            }
            try {
                boolean swap = Math.floorMod(extraClockwiseDegrees, 180) == 90;
                int width = swap ? imageHeight : imageWidth;
                int height = swap ? imageWidth : imageHeight;
                ByteBuffer pixels = readPixels(width, height, selected, true, extraClockwiseDegrees);
                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmap.copyPixelsFromBuffer(pixels);
                main.post(() -> {
                    if (generation.get() == epoch) listener.onPhoto(bitmap);
                    else bitmap.recycle();
                });
            } catch (RuntimeException | OutOfMemoryError e) {
                report(epoch, "读取照片失败", e);
            }
        });
    }

    private ByteBuffer readPixels(int width, int height, PhotoFilter selected, boolean applyWb, int rotation) {
        int[] framebuffer = new int[1];
        int[] texture = new int[1];
        try {
            glGenFramebuffers(1, framebuffer, 0);
            glGenTextures(1, texture, 0);
            configureTexture(GL_TEXTURE_2D, texture[0]);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, null);
            glBindFramebuffer(GL_FRAMEBUFFER, framebuffer[0]);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture[0], 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("Incomplete framebuffer " + width + "x" + height);
            }
            glViewport(0, 0, width, height);
            draw(selected, applyWb ? wbWarmth : 0f, applyWb ? wbTint : 0f, rotation, true);
            ByteBuffer bytes = ByteBuffer.allocateDirect(Math.multiplyExact(4, Math.multiplyExact(width, height)))
                    .order(ByteOrder.nativeOrder());
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
            checkGl();
            bytes.rewind();
            return bytes;
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glDeleteFramebuffers(1, framebuffer, 0);
            glDeleteTextures(1, texture, 0);
        }
    }

    private void draw(PhotoFilter selected, float warmth, float tint, int clockwise, boolean inverted) {
        glUseProgram(program);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture);
        glUniform1i(cameraImage, 0);
        glUniformMatrix4fv(cameraTransform, 1, false, transform, 0);
        double rad = Math.toRadians(clockwise);
        glUniform2f(turn, (float) Math.cos(rad), (float) Math.sin(rad));
        glUniform1f(flipY, inverted ? -1f : 1f);
        glUniform3f(grade, selected.highlights, selected.shadows, selected.desaturation);
        glUniform2f(look, selected.vibrance, selected.monochrome ? 1f : 0f);
        glUniform2f(wbShift, warmth, tint);
        glEnableVertexAttribArray(position);
        glVertexAttribPointer(position, 2, GL_FLOAT, false, 0, quad);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glDisableVertexAttribArray(position);
    }

    private static void configureTexture(int target, int name) {
        glBindTexture(target, name);
        glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(target, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    private static int makeProgram() {
        int vertex = compile(GL_VERTEX_SHADER, VERTEX);
        int fragment = compile(GL_FRAGMENT_SHADER, FRAGMENT);
        int id = glCreateProgram();
        glAttachShader(id, vertex);
        glAttachShader(id, fragment);
        glLinkProgram(id);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        int[] success = new int[1];
        glGetProgramiv(id, GL_LINK_STATUS, success, 0);
        if (success[0] != GL_TRUE) throw new IllegalStateException(glGetProgramInfoLog(id));
        return id;
    }

    private static int compile(int type, String source) {
        int id = glCreateShader(type);
        glShaderSource(id, source);
        glCompileShader(id);
        int[] success = new int[1];
        glGetShaderiv(id, GL_COMPILE_STATUS, success, 0);
        if (success[0] != GL_TRUE) throw new IllegalStateException(glGetShaderInfoLog(id));
        return id;
    }

    private static void checkGl() {
        int error = glGetError();
        if (error != GL_NO_ERROR) throw new IllegalStateException("OpenGL error 0x" + Integer.toHexString(error));
    }

    private void report(int epoch, String message, Throwable cause) {
        Log.e("TrueHue", message, cause);
        notifyCurrent(epoch, () -> listener.onRenderError(message));
    }

    private void notifyCurrent(int epoch, Runnable action) {
        main.post(() -> {
            if (generation.get() == epoch) action.run();
        });
    }

    private void releaseInput() {
        if (input != null) {
            input.setOnFrameAvailableListener(null);
            input.release();
            input = null;
        }
        pendingFrame.set(false);
    }

    public void release() {
        int epoch = generation.incrementAndGet();
        view.queueEvent(() -> {
            if (generation.get() != epoch) return;
            releaseInput();
            hasFrame = false;
            if (externalTexture != 0) glDeleteTextures(1, new int[]{externalTexture}, 0);
            if (program != 0) glDeleteProgram(program);
            externalTexture = 0;
            program = 0;
        });
    }
}
