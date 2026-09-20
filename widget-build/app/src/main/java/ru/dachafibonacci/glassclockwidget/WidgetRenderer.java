package ru.dachafibonacci.glassclockwidget;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.util.DisplayMetrics;

import java.util.Calendar;

public final class WidgetRenderer {
    private WidgetRenderer() {}

    public static Bitmap render(Context context, int appWidgetId) {
        // Render onto one stable 4×1-ish artboard. The ImageView stretches this bitmap
        // to the actual host bounds, so the glass edge can never extend past the widget.
        return drawWidget(600, 114, progressNow());
    }

    static Bitmap drawWidget(int width, int height, float progress) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        c.drawColor(Color.TRANSPARENT);

        float pad = Math.max(4f, height * 0.045f);
        RectF capsule = new RectF(pad, pad, width - pad, height - pad);
        float radius = capsule.height() * 0.49f;

        drawShadow(c, capsule, radius, height);
        drawGlassBody(c, capsule, radius);
        drawLiquid(c, capsule, radius, progress);
        drawGlassEdges(c, capsule, radius, height);
        float micRadius = Math.min(height * 0.285f, width * 0.055f);
        drawMicCapsule(c, width * 0.615f, height * 0.50f, micRadius);
        drawDivider(c, width * 0.690f, height);
        drawStopwatch(c, width * 0.752f, height * 0.50f, Math.min(height * 0.174f, width * 0.033f));
        drawMicroHighlights(c, capsule, progress, height);

        return bitmap;
    }

    private static void drawShadow(Canvas c, RectF r, float radius, int h) {
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(0x52000000);
        shadow.setMaskFilter(new BlurMaskFilter(h * 0.075f, BlurMaskFilter.Blur.NORMAL));
        RectF sr = new RectF(r.left + h * 0.01f, r.top + h * 0.045f,
                r.right + h * 0.01f, r.bottom + h * 0.045f);
        c.drawRoundRect(sr, radius, radius, shadow);
        shadow.setMaskFilter(null);
    }

    private static void drawGlassBody(Canvas c, RectF r, float radius) {
        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        body.setShader(new LinearGradient(
                r.left, r.top, r.left, r.bottom,
                new int[] {0x52FFFFFF, 0x1AFFFFFF, 0x17315C45, 0x2AFFFFFF},
                new float[] {0f, 0.24f, 0.72f, 1f},
                Shader.TileMode.CLAMP));
        c.drawRoundRect(r, radius, radius, body);

        Paint innerShade = new Paint(Paint.ANTI_ALIAS_FLAG);
        innerShade.setShader(new LinearGradient(
                r.left, r.top, r.right, r.bottom,
                new int[] {0x0CFFFFFF, 0x1E163A2A, 0x09FFFFFF},
                null,
                Shader.TileMode.CLAMP));
        RectF in = new RectF(r.left + 4, r.top + 4, r.right - 4, r.bottom - 4);
        c.drawRoundRect(in, Math.max(0, radius - 4), Math.max(0, radius - 4), innerShade);
    }

    private static void drawLiquid(Canvas c, RectF r, float radius, float progress) {
        progress = clamp(progress, 0f, 1f);
        if (progress <= 0.001f) return;

        float fillRight = r.left + r.width() * progress;
        float edgeWave = Math.max(3f, r.height() * 0.045f);

        int save = c.save();
        Path capsulePath = new Path();
        capsulePath.addRoundRect(r, radius, radius, Path.Direction.CW);
        c.clipPath(capsulePath);

        // The liquid occupies the full height and advances strictly LEFT -> RIGHT.
        Path water = new Path();
        water.moveTo(r.left - 8f, r.top - 8f);
        water.lineTo(fillRight - edgeWave * 0.30f, r.top - 8f);
        water.cubicTo(
                fillRight + edgeWave * 0.45f, r.top + r.height() * 0.22f,
                fillRight - edgeWave * 0.45f, r.top + r.height() * 0.38f,
                fillRight + edgeWave * 0.12f, r.top + r.height() * 0.52f
        );
        water.cubicTo(
                fillRight + edgeWave * 0.50f, r.top + r.height() * 0.66f,
                fillRight - edgeWave * 0.45f, r.top + r.height() * 0.82f,
                fillRight + edgeWave * 0.05f, r.bottom + 8f
        );
        water.lineTo(r.left - 8f, r.bottom + 8f);
        water.close();

        Paint liquid = new Paint(Paint.ANTI_ALIAS_FLAG);
        liquid.setShader(new LinearGradient(
                r.left, r.top, fillRight, r.bottom,
                new int[] {0xAA20C8B0, 0xB72ED9BB, 0x9A53EBCB, 0x8A1DB99E},
                new float[] {0f, 0.40f, 0.74f, 1f},
                Shader.TileMode.CLAMP));
        c.drawPath(water, liquid);

        // Gentle internal depth, but no horizontal "water level".
        Paint depth = new Paint(Paint.ANTI_ALIAS_FLAG);
        depth.setShader(new LinearGradient(
                r.left, r.top, r.left, r.bottom,
                new int[] {0x42FFFFFF, 0x0FFFFFFF, 0x33008C76},
                new float[] {0f, 0.48f, 1f},
                Shader.TileMode.CLAMP));
        c.drawPath(water, depth);

        // Bright vertical meniscus makes the direction unambiguous.
        Paint meniscus = new Paint(Paint.ANTI_ALIAS_FLAG);
        meniscus.setStyle(Paint.Style.STROKE);
        meniscus.setStrokeWidth(Math.max(2f, r.height() * 0.020f));
        meniscus.setStrokeCap(Paint.Cap.ROUND);
        meniscus.setColor(0xD89EFFF0);
        meniscus.setMaskFilter(new BlurMaskFilter(r.height() * 0.014f, BlurMaskFilter.Blur.NORMAL));

        Path edge = new Path();
        edge.moveTo(fillRight - edgeWave * 0.30f, r.top + 2f);
        edge.cubicTo(
                fillRight + edgeWave * 0.45f, r.top + r.height() * 0.22f,
                fillRight - edgeWave * 0.45f, r.top + r.height() * 0.38f,
                fillRight + edgeWave * 0.12f, r.top + r.height() * 0.52f
        );
        edge.cubicTo(
                fillRight + edgeWave * 0.50f, r.top + r.height() * 0.66f,
                fillRight - edgeWave * 0.45f, r.top + r.height() * 0.82f,
                fillRight + edgeWave * 0.05f, r.bottom - 2f
        );
        c.drawPath(edge, meniscus);
        meniscus.setMaskFilter(null);

        // Thin highlights travel inside the filled region only.
        Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
        sheen.setStrokeWidth(Math.max(1f, r.height() * 0.009f));
        sheen.setColor(0x55FFFFFF);
        sheen.setStrokeCap(Paint.Cap.ROUND);
        float sheenEnd = Math.max(r.left + 8f, fillRight - edgeWave * 1.3f);
        c.drawLine(r.left + radius * 0.55f, r.top + r.height() * 0.29f,
                sheenEnd, r.top + r.height() * 0.29f, sheen);
        c.drawLine(r.left + radius * 0.70f, r.top + r.height() * 0.72f,
                sheenEnd, r.top + r.height() * 0.72f, sheen);

        c.restoreToCount(save);
    }

    private static void drawGlassEdges(Canvas c, RectF r, float radius, int h) {
        Paint outer = new Paint(Paint.ANTI_ALIAS_FLAG);
        outer.setStyle(Paint.Style.STROKE);
        outer.setStrokeWidth(Math.max(2f, h * 0.015f));
        outer.setColor(0xD8F4FFF8);
        c.drawRoundRect(r, radius, radius, outer);

        RectF in1 = new RectF(r.left + h * 0.026f, r.top + h * 0.026f,
                r.right - h * 0.026f, r.bottom - h * 0.026f);
        Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
        inner.setStyle(Paint.Style.STROKE);
        inner.setStrokeWidth(Math.max(1f, h * 0.008f));
        inner.setColor(0x72FFFFFF);
        c.drawRoundRect(in1, Math.max(0, radius - h * 0.026f),
                Math.max(0, radius - h * 0.026f), inner);

        Paint top = new Paint(Paint.ANTI_ALIAS_FLAG);
        top.setStyle(Paint.Style.STROKE);
        top.setStrokeCap(Paint.Cap.ROUND);
        top.setStrokeWidth(Math.max(2f, h * 0.018f));
        top.setShader(new LinearGradient(
                r.left + radius * 0.3f, r.top,
                r.right - radius * 0.3f, r.top,
                new int[] {0x00FFFFFF, 0xD9FFFFFF, 0x55FFFFFF, 0xE8FFFFFF, 0x00FFFFFF},
                null,
                Shader.TileMode.CLAMP));
        float y = r.top + h * 0.033f;
        c.drawLine(r.left + radius * 0.52f, y, r.right - radius * 0.52f, y, top);

        Paint bottom = new Paint(Paint.ANTI_ALIAS_FLAG);
        bottom.setStyle(Paint.Style.STROKE);
        bottom.setStrokeWidth(Math.max(1.5f, h * 0.012f));
        bottom.setColor(0x897BFFE4);
        float by = r.bottom - h * 0.034f;
        c.drawLine(r.left + radius * 0.60f, by, r.right - radius * 0.60f, by, bottom);
    }

    private static void drawMicCapsule(Canvas c, float cx, float cy, float radius) {
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(0x55000000);
        shadow.setMaskFilter(new BlurMaskFilter(radius * 0.28f, BlurMaskFilter.Blur.NORMAL));
        c.drawCircle(cx + radius * 0.05f, cy + radius * 0.11f, radius * 1.01f, shadow);
        shadow.setMaskFilter(null);

        Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
        glass.setShader(new RadialGradient(
                cx - radius * 0.32f, cy - radius * 0.38f, radius * 1.25f,
                new int[] {0x91FFFFFF, 0x35FFFFFF, 0x242D5E48, 0x68FFFFFF},
                new float[] {0f, 0.35f, 0.76f, 1f},
                Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, radius, glass);

        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(Math.max(2f, radius * 0.06f));
        border.setColor(0xC9F7FFF8);
        c.drawCircle(cx, cy, radius, border);

        Paint mic = new Paint(Paint.ANTI_ALIAS_FLAG);
        mic.setColor(0xF4FFFFFF);
        mic.setStyle(Paint.Style.STROKE);
        mic.setStrokeCap(Paint.Cap.ROUND);
        mic.setStrokeJoin(Paint.Join.ROUND);
        mic.setStrokeWidth(radius * 0.105f);

        float capsuleW = radius * 0.43f;
        float top = cy - radius * 0.47f;
        float bottom = cy + radius * 0.14f;
        RectF capsule = new RectF(cx - capsuleW * 0.5f, top,
                cx + capsuleW * 0.5f, bottom);
        c.drawRoundRect(capsule, capsuleW * 0.5f, capsuleW * 0.5f, mic);

        Path stem = new Path();
        stem.moveTo(cx - radius * 0.43f, cy + radius * 0.02f);
        stem.cubicTo(cx - radius * 0.40f, cy + radius * 0.40f,
                cx - radius * 0.18f, cy + radius * 0.50f,
                cx, cy + radius * 0.50f);
        stem.cubicTo(cx + radius * 0.18f, cy + radius * 0.50f,
                cx + radius * 0.40f, cy + radius * 0.40f,
                cx + radius * 0.43f, cy + radius * 0.02f);
        c.drawPath(stem, mic);
        c.drawLine(cx, cy + radius * 0.50f, cx, cy + radius * 0.74f, mic);
        c.drawLine(cx - radius * 0.24f, cy + radius * 0.74f,
                cx + radius * 0.24f, cy + radius * 0.74f, mic);
    }

    private static void drawDivider(Canvas c, float x, int h) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStrokeWidth(Math.max(1.5f, h * 0.009f));
        p.setShader(new LinearGradient(
                x, h * 0.26f, x, h * 0.74f,
                new int[] {0x00FFFFFF, 0xB7FFFFFF, 0x00FFFFFF},
                null,
                Shader.TileMode.CLAMP));
        c.drawLine(x, h * 0.27f, x, h * 0.73f, p);
    }

    private static void drawStopwatch(Canvas c, float cx, float cy, float radius) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Math.max(3f, radius * 0.13f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(0xF1FFFFFF);
        c.drawCircle(cx, cy, radius, p);
        c.drawLine(cx, cy, cx, cy - radius * 0.58f, p);
        c.drawLine(cx, cy, cx + radius * 0.45f, cy + radius * 0.17f, p);
    }

    private static void drawMicroHighlights(Canvas c, RectF r, float progress, int h) {
        Paint sparkle = new Paint(Paint.ANTI_ALIAS_FLAG);
        sparkle.setColor(0x88FFFFFF);
        sparkle.setMaskFilter(new BlurMaskFilter(h * 0.018f, BlurMaskFilter.Blur.NORMAL));
        c.drawCircle(r.left + r.width() * 0.17f, r.top + h * 0.055f, h * 0.018f, sparkle);
        c.drawCircle(r.right - r.width() * 0.08f, r.top + h * 0.055f, h * 0.026f, sparkle);
        sparkle.setMaskFilter(null);

        if (progress > 0.08f) {
            float fillRight = r.left + r.width() * clamp(progress, 0f, 1f);
            Paint bubble = new Paint(Paint.ANTI_ALIAS_FLAG);
            bubble.setStyle(Paint.Style.STROKE);
            bubble.setStrokeWidth(Math.max(1f, h * 0.006f));
            bubble.setColor(0xA9BFFFF3);
            float usable = Math.max(1f, fillRight - r.left);
            float[] xs = {0.62f, 0.78f, 0.47f};
            float[] ys = {0.60f, 0.72f, 0.49f};
            float[] rs = {0.028f, 0.018f, 0.013f};
            for (int i = 0; i < xs.length; i++) {
                float x = r.left + usable * xs[i];
                if (x < fillRight - h * 0.04f) {
                    c.drawCircle(x, r.top + r.height() * ys[i], h * rs[i], bubble);
                }
            }
        }
    }

    private static float progressNow() {
        Calendar cal = Calendar.getInstance();
        int minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE);
        if (minutes <= 9 * 60) return 0f;
        return Math.min(1f, (minutes - 9 * 60) / (15f * 60f));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
