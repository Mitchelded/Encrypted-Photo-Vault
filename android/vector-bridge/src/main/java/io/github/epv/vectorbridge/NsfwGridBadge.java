package io.github.epv.vectorbridge;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.View;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * EXPERIMENTAL thumbnail annotation in Google Photos.
 *
 * Uses PhotoCellView.draw when it exists, without reading Google account data,
 * cloud APIs, internal media databases or the real file contents.
 *
 * PixelCopy takes a small screenshot of visible thumbnail rectangles. Results
 * and screenshots are held in RAM only. TensorFlow Lite runs on a single
 * background worker, never on the host app's UI/render thread.
 *
 * On unsupported Google Photos versions no badge is drawn.
 */
public final class NsfwGridBadge {
    private static final String PHOTO_CELL =
            "com.google.android.apps.photos.photoadapteritem.PhotoCellView";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService ML = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "EPV-NSFW-Thumbnail");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private static final Map<View, Cell> CELLS = new WeakHashMap<>();
    private static final LinkedHashMap<Long, Float> SCORES = new LinkedHashMap<>(128, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Float> eldest) {
            return size() > 256;
        }
    };
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint LABEL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final int CAPTURE = 160;
    private static final float THRESHOLD = 0.70f;
    private static final long RESCAN_MS = 4800L;
    private static volatile boolean enabled = true;
    private static volatile boolean disabledByError = false;
    private static volatile String moduleApk;
    private static Interpreter interpreter;
    private static ByteBuffer modelBytes;

    private NsfwGridBadge() {}

    private static final class Cell {
        boolean pending;
        float score = -1f;
        long scoredAt;
        long nextCapture;
        int generation;
    }

    public static void install(ClassLoader hostLoader, String modulePath) {
        moduleApk = modulePath;
        if (moduleApk == null || moduleApk.isEmpty()) {
            XposedBridge.log("EPV Badge: Xposed module APK path not available; badges disabled");
            disabledByError = true;
            return;
        }
        try {
            Class<?> cls = XposedHelpers.findClass(PHOTO_CELL, hostLoader);
            XposedHelpers.findAndHookMethod(cls, "draw", Canvas.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (!enabled || disabledByError || !(p.thisObject instanceof View)) return;
                    try {
                        View view = (View) p.thisObject;
                        if (view.getVisibility() != View.VISIBLE || !view.isShown()) return;
                        if (view.getWidth() < dp(view, 78) || view.getHeight() < dp(view, 78)) return;
                        Canvas canvas = (Canvas) p.args[0];
                        onDraw(view, canvas);
                    } catch (Throwable error) {
                        // Xposed hooks must never interrupt Google Photos rendering.
                    }
                }
            });
            // Recycled photo tiles must not keep a badge from their old image.
            XposedBridge.hookAllMethods(cls, "onDetachedFromWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    if (!(p.thisObject instanceof View)) return;
                    synchronized (CELLS) {
                        Cell state = CELLS.remove((View) p.thisObject);
                        if (state != null) state.generation++;
                    }
                }
            });
            XposedBridge.log("EPV Badge: hooked Google Photos PhotoCellView.draw");
        } catch (Throwable error) {
            XposedBridge.log("EPV Badge: PhotoCellView hook unavailable: " + error);
        }
    }

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences("epv_vector_bridge", Context.MODE_PRIVATE)
                .getBoolean("thumbnail_badges", true);
    }

    public static boolean toggle(Context context) {
        enabled = !isEnabled(context);
        context.getSharedPreferences("epv_vector_bridge", Context.MODE_PRIVATE)
                .edit().putBoolean("thumbnail_badges", enabled).apply();
        return enabled;
    }

    public static void restoreSetting(Context context) {
        enabled = isEnabled(context);
    }

    private static void onDraw(View view, Canvas canvas) {
        final long now = SystemClock.uptimeMillis();
        Cell state;
        synchronized (CELLS) {
            state = CELLS.get(view);
            if (state == null) {
                state = new Cell();
                CELLS.put(view, state);
            }
        }
        // Never draw results for long after the captured thumbnail changed.
        if (state.score >= THRESHOLD && now - state.scoredAt < RESCAN_MS + 2200) {
            badge(view, canvas, state.score);
        }
        if (!state.pending && now >= state.nextCapture && IN_FLIGHT.get() < 5) {
            state.pending = true;
            state.nextCapture = now + RESCAN_MS;
            final int generation = ++state.generation;
            Cell captured = state;
            view.postDelayed(() -> capture(view, captured, generation), 250L);
        }
    }

    private static void capture(View view, Cell state, int generation) {
        if (!enabled || !view.isShown() || view.getWidth() == 0 ||
                view.getHeight() == 0 || disabledByError) {
            state.pending = false;
            return;
        }
        Activity activity = activityOf(view.getContext());
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            state.pending = false;
            return;
        }
        // PixelCopy expects window-relative coordinates, not screen-relative.
        int[] xy = new int[2];
        view.getLocationInWindow(xy);
        Rect rect = new Rect(xy[0], xy[1], xy[0] + view.getWidth(), xy[1] + view.getHeight());
        View decor = activity.getWindow().getDecorView();
        rect.intersect(0, 0, decor.getWidth(), decor.getHeight());
        if (rect.width() < dp(view, 70) || rect.height() < dp(view, 70)) {
            state.pending = false;
            return;
        }
        // Limit pixel copies and inferences under aggressive scrolling.
        if (IN_FLIGHT.incrementAndGet() > 5) {
            IN_FLIGHT.decrementAndGet();
            state.pending = false;
            return;
        }
        final Bitmap snapshot = Bitmap.createBitmap(CAPTURE, CAPTURE, Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(activity.getWindow(), rect, snapshot, result -> {
                if (result != PixelCopy.SUCCESS || !enabled || state.generation != generation) {
                    snapshot.recycle();
                    state.pending = false;
                    IN_FLIGHT.decrementAndGet();
                    return;
                }
                ML.execute(() -> {
                    float score = -1f;
                    try {
                        long key = fingerprint(snapshot);
                        synchronized (SCORES) {
                            Float cached = SCORES.get(key);
                            if (cached != null) score = cached;
                        }
                        if (score < 0) {
                            score = predict(snapshot);
                            if (score >= 0) {
                                synchronized (SCORES) { SCORES.put(key, score); }
                            }
                        }
                    } catch (Throwable error) {
                        if (error instanceof LinkageError || error instanceof UnsatisfiedLinkError) {
                            disabledByError = true;
                            XposedBridge.log("EPV Badge: model runtime unavailable: " + error);
                        }
                    } finally {
                        snapshot.recycle();
                        IN_FLIGHT.decrementAndGet();
                        final float resultScore = score;
                        MAIN.post(() -> {
                            state.pending = false;
                            if (state.generation == generation && enabled) {
                                state.score = resultScore;
                                state.scoredAt = SystemClock.uptimeMillis();
                                if (view.isAttachedToWindow()) view.invalidate();
                            }
                        });
                    }
                });
            }, MAIN);
        } catch (Throwable error) {
            state.pending = false;
            IN_FLIGHT.decrementAndGet();
            snapshot.recycle();
        }
    }

    private static long fingerprint(Bitmap src) {
        // 16 x 16 sampled RGB signature; cache is process-local and bounded.
        long hash = 0xcbf29ce484222325L;
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            int pixel = src.getPixel((x * src.getWidth()) / 16, (y * src.getHeight()) / 16);
            hash = (hash ^ (pixel & 0x00ffffff)) * 0x100000001b3L;
        }
        return hash;
    }

    private static float predict(Bitmap snapshot) throws Exception {
        if (interpreter == null) loadModel();
        Bitmap reduced = Bitmap.createScaledBitmap(snapshot, 224, 224, true);
        try {
            ByteBuffer input = ByteBuffer.allocateDirect(224 * 224 * 3 * 4)
                    .order(ByteOrder.nativeOrder());
            int[] rgb = new int[224 * 224];
            reduced.getPixels(rgb, 0, 224, 0, 0, 224, 224);
            for (int pixel : rgb) {
                input.putFloat(((pixel >> 16) & 255) / 255f);
                input.putFloat(((pixel >> 8) & 255) / 255f);
                input.putFloat((pixel & 255) / 255f);
            }
            input.rewind();
            float[][] output = new float[1][5];
            interpreter.run(input, output);
            float score = output[0][1] + output[0][3] + output[0][4];
            return Float.isNaN(score) ? -1f : Math.max(0f, Math.min(1f, score));
        } finally {
            if (reduced != snapshot) reduced.recycle();
        }
    }

    private static void loadModel() throws Exception {
        if (moduleApk == null) throw new IllegalStateException("No module APK path");
        byte[] bytes;
        try (ZipFile apk = new ZipFile(moduleApk)) {
            ZipEntry entry = apk.getEntry("assets/nsfw.tflite");
            if (entry == null) throw new IllegalStateException("Bundled model missing");
            try (InputStream in = apk.getInputStream(entry);
                 ByteArrayOutputStream out = new ByteArrayOutputStream(18_000_000)) {
                byte[] chunk = new byte[32768];
                for (int count; (count = in.read(chunk)) != -1;) out.write(chunk, 0, count);
                bytes = out.toByteArray();
            }
        }
        if (bytes.length < 16_000_000) throw new IllegalStateException("Model incomplete");
        modelBytes = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
        modelBytes.put(bytes);
        modelBytes.rewind();
        interpreter = new Interpreter(modelBytes, new Interpreter.Options().setNumThreads(2));
        if (interpreter.getInputTensor(0).dataType() != DataType.FLOAT32 ||
                interpreter.getOutputTensor(0).dataType() != DataType.FLOAT32 ||
                interpreter.getOutputTensor(0).shape()[1] != 5) {
            interpreter.close();
            interpreter = null;
            throw new IllegalStateException("Unexpected NSFW TFLite model");
        }
        XposedBridge.log("EPV Badge: offline model loaded");
    }

    private static void badge(View view, Canvas canvas, float score) {
        float density = view.getResources().getDisplayMetrics().density;
        String text = "18+ " + Math.round(score * 100) + "%";
        float size = 11 * density;
        LABEL.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LABEL.setTextSize(size);
        LABEL.setColor(Color.WHITE);
        float margin = 5 * density;
        float h = 25 * density;
        float w = LABEL.measureText(text) + 16 * density;
        float right = view.getWidth() - margin;
        float left = right - w;
        PAINT.setColor(0xEF9D2449);
        canvas.drawRoundRect(left, margin, right, margin + h,
                8 * density, 8 * density, PAINT);
        canvas.drawText(text, left + 8 * density, margin + h - 8 * density, LABEL);
    }

    private static Activity activityOf(Context context) {
        for (int i = 0; i < 10; i++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) break;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private static int dp(View v, int x) {
        return Math.round(x * v.getResources().getDisplayMetrics().density);
    }
}
