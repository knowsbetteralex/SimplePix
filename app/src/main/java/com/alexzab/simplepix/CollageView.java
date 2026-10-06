package com.alexzab.simplepix;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class CollageView extends View {

    private abstract static class Layer {
        final Matrix matrix = new Matrix();
        final RectF localBounds = new RectF();

        abstract void draw(Canvas canvas);

        boolean contains(float x, float y) {
            Matrix inverse = new Matrix();
            if (!matrix.invert(inverse)) return false;
            float[] p = {x, y};
            inverse.mapPoints(p);
            return localBounds.contains(p[0], p[1]);
        }

        float scale() {
            float[] values = new float[9];
            matrix.getValues(values);
            return (float) Math.sqrt(
                    values[Matrix.MSCALE_X] * values[Matrix.MSCALE_X]
                            + values[Matrix.MSKEW_Y] * values[Matrix.MSKEW_Y]);
        }
    }

    private static class ImageLayer extends Layer {
        final Bitmap bitmap;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        ImageLayer(Bitmap bitmap) {
            this.bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
            localBounds.set(0, 0, this.bitmap.getWidth(), this.bitmap.getHeight());
        }

        @Override
        void draw(Canvas canvas) {
            canvas.drawBitmap(bitmap, matrix, paint);
        }

        void erase(float viewX, float viewY, float radiusOnScreen) {
            Matrix inverse = new Matrix();
            if (!matrix.invert(inverse)) return;

            float[] p = {viewX, viewY};
            inverse.mapPoints(p);

            float localRadius = radiusOnScreen / Math.max(scale(), 0.01f);
            Canvas bitmapCanvas = new Canvas(bitmap);
            Paint eraser = new Paint(Paint.ANTI_ALIAS_FLAG);
            eraser.setStyle(Paint.Style.FILL);
            eraser.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
            bitmapCanvas.drawCircle(p[0], p[1], localRadius, eraser);
        }
    }

    private static class TextLayer extends Layer {
        final String text;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        TextLayer(String text) {
            this.text = text;
            paint.setColor(Color.WHITE);
            paint.setTextSize(64f);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            paint.setShadowLayer(5f, 0f, 2f, 0x88000000);

            Paint.FontMetrics fm = paint.getFontMetrics();
            float width = Math.max(1f, paint.measureText(text));
            float height = fm.descent - fm.ascent;
            localBounds.set(0, 0, width, height);
        }

        @Override
        void draw(Canvas canvas) {
            canvas.save();
            canvas.concat(matrix);
            Paint.FontMetrics fm = paint.getFontMetrics();
            canvas.drawText(text, 0, -fm.ascent, paint);
            canvas.restore();
        }
    }

    private final List<Layer> layers = new ArrayList<>();
    private Layer selectedLayer;
    private int backgroundColor = Color.rgb(238, 238, 238);

    private float lastX;
    private float lastY;
    private float lastRotation;
    private boolean rotating;
    private boolean eraserMode;
    private final float eraserRadiusPx;

    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaleDetector;

    public CollageView(Context context) {
        this(context, null);
    }

    public CollageView(Context context, AttributeSet attrs) {
        super(context, attrs);

        eraserRadiusPx = 34f * getResources().getDisplayMetrics().density;

        selectionPaint.setStyle(Paint.Style.STROKE);
        selectionPaint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
        selectionPaint.setColor(Color.WHITE);
        selectionPaint.setShadowLayer(4f, 0, 0, Color.BLACK);

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        if (selectedLayer == null || eraserMode) return false;
                        float factor = detector.getScaleFactor();
                        factor = Math.max(0.75f, Math.min(factor, 1.33f));

                        float current = selectedLayer.scale();
                        float wanted = Math.max(0.08f, Math.min(current * factor, 12f));
                        float corrected = wanted / Math.max(current, 0.0001f);

                        selectedLayer.matrix.postScale(
                                corrected,
                                corrected,
                                detector.getFocusX(),
                                detector.getFocusY());
                        invalidate();
                        return true;
                    }
                });
    }

    public void addImage(Bitmap bitmap) {
        if (bitmap == null) return;

        ImageLayer layer = new ImageLayer(bitmap);
        float vw = Math.max(getWidth(), 1);
        float vh = Math.max(getHeight(), 1);
        float scale = Math.min(
                (vw * 0.72f) / layer.bitmap.getWidth(),
                (vh * 0.72f) / layer.bitmap.getHeight());
        scale = Math.max(scale, 0.05f);

        float shownW = layer.bitmap.getWidth() * scale;
        float shownH = layer.bitmap.getHeight() * scale;

        layer.matrix.setScale(scale, scale);
        layer.matrix.postTranslate(
                (vw - shownW) / 2f,
                (vh - shownH) / 2f);

        layers.add(layer);
        selectedLayer = layer;
        eraserMode = false;
        invalidate();
    }

    public void addText(String text) {
        if (text == null || text.trim().isEmpty()) return;

        TextLayer layer = new TextLayer(text.trim());
        float x = Math.max(24f, (getWidth() - layer.localBounds.width()) / 2f);
        float y = Math.max(80f, getHeight() / 2f);
        layer.matrix.postTranslate(x, y);

        layers.add(layer);
        selectedLayer = layer;
        eraserMode = false;
        invalidate();
    }

    public void setBackgroundFill(int color) {
        backgroundColor = color;
        invalidate();
    }

    public boolean isEraserMode() {
        return eraserMode;
    }

    public boolean setEraserMode(boolean enabled) {
        if (enabled && !(selectedLayer instanceof ImageLayer)) {
            eraserMode = false;
            return false;
        }
        eraserMode = enabled;
        invalidate();
        return true;
    }

    public void deleteSelected() {
        if (selectedLayer == null) return;
        layers.remove(selectedLayer);
        selectedLayer = null;
        eraserMode = false;
        invalidate();
    }

    public void bringSelectedForward() {
        if (selectedLayer == null) return;
        int i = layers.indexOf(selectedLayer);
        if (i >= 0 && i < layers.size() - 1) {
            layers.remove(i);
            layers.add(i + 1, selectedLayer);
            invalidate();
        }
    }

    public void sendSelectedBackward() {
        if (selectedLayer == null) return;
        int i = layers.indexOf(selectedLayer);
        if (i > 0) {
            layers.remove(i);
            layers.add(i - 1, selectedLayer);
            invalidate();
        }
    }

    public Bitmap renderToBitmap() {
        int w = Math.max(1, getWidth());
        int h = Math.max(1, getHeight());
        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        drawContent(canvas, false);
        return result;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawContent(canvas, true);
    }

    private void drawContent(Canvas canvas, boolean showSelection) {
        canvas.drawColor(backgroundColor);
        for (Layer layer : layers) {
            layer.draw(canvas);
        }

        if (showSelection && selectedLayer != null) {
            canvas.save();
            canvas.concat(selectedLayer.matrix);
            canvas.drawRect(selectedLayer.localBounds, selectionPaint);
            canvas.restore();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (eraserMode) {
            if (!(selectedLayer instanceof ImageLayer)) {
                eraserMode = false;
                return true;
            }

            if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                    || event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                ((ImageLayer) selectedLayer).erase(
                        event.getX(),
                        event.getY(),
                        eraserRadiusPx);
                invalidate();
            }
            return true;
        }

        scaleDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                selectedLayer = findTopLayer(event.getX(), event.getY());
                lastX = event.getX();
                lastY = event.getY();
                rotating = false;
                invalidate();
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (event.getPointerCount() >= 2 && selectedLayer != null) {
                    lastRotation = angle(event);
                    rotating = true;
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (selectedLayer == null) return true;

                if (event.getPointerCount() == 1 && !scaleDetector.isInProgress()) {
                    float x = event.getX();
                    float y = event.getY();
                    selectedLayer.matrix.postTranslate(x - lastX, y - lastY);
                    lastX = x;
                    lastY = y;
                    invalidate();
                } else if (event.getPointerCount() >= 2 && rotating) {
                    float now = angle(event);
                    float delta = normalizeAngle(now - lastRotation);
                    selectedLayer.matrix.postRotate(
                            delta,
                            midpointX(event),
                            midpointY(event));
                    lastRotation = now;
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                rotating = false;
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                rotating = false;
                return true;

            default:
                return true;
        }
    }

    private Layer findTopLayer(float x, float y) {
        for (int i = layers.size() - 1; i >= 0; i--) {
            Layer layer = layers.get(i);
            if (layer.contains(x, y)) return layer;
        }
        return null;
    }

    private static float angle(MotionEvent event) {
        if (event.getPointerCount() < 2) return 0f;
        float dx = event.getX(1) - event.getX(0);
        float dy = event.getY(1) - event.getY(0);
        return (float) Math.toDegrees(Math.atan2(dy, dx));
    }

    private static float midpointX(MotionEvent event) {
        return (event.getX(0) + event.getX(1)) / 2f;
    }

    private static float midpointY(MotionEvent event) {
        return (event.getY(0) + event.getY(1)) / 2f;
    }

    private static float normalizeAngle(float angle) {
        while (angle > 180f) angle -= 360f;
        while (angle < -180f) angle += 360f;
        return angle;
    }
}
