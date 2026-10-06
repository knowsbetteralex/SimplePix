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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class CollageView extends View {

    private static final int MAX_HISTORY = 25;

    private abstract static class Layer {
        final Matrix matrix = new Matrix();
        final RectF localBounds = new RectF();

        abstract void draw(Canvas canvas);
        abstract Layer copyForState();

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

        void copyMatrixTo(Layer target) {
            target.matrix.set(matrix);
        }
    }

    private static class ImageLayer extends Layer {
        Bitmap bitmap;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        ImageLayer(Bitmap source, boolean copyPixels) {
            bitmap = copyPixels
                    ? source.copy(Bitmap.Config.ARGB_8888, true)
                    : source;
            updateBounds();
        }

        void updateBounds() {
            localBounds.set(0, 0, bitmap.getWidth(), bitmap.getHeight());
        }

        @Override
        void draw(Canvas canvas) {
            canvas.drawBitmap(bitmap, matrix, paint);
        }

        @Override
        Layer copyForState() {
            ImageLayer copy = new ImageLayer(bitmap, false);
            copyMatrixTo(copy);
            return copy;
        }

        void beginPixelEdit() {
            bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
            updateBounds();
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
        String text;
        int color;
        float sizePx;
        boolean bold;
        boolean italic;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        TextLayer(String text, int color, float sizePx, boolean bold, boolean italic) {
            this.text = text;
            this.color = color;
            this.sizePx = sizePx;
            this.bold = bold;
            this.italic = italic;
            applyStyle();
        }

        void applyStyle() {
            paint.setColor(color);
            paint.setTextSize(sizePx);

            int style = Typeface.NORMAL;
            if (bold && italic) style = Typeface.BOLD_ITALIC;
            else if (bold) style = Typeface.BOLD;
            else if (italic) style = Typeface.ITALIC;

            paint.setTypeface(Typeface.create(Typeface.DEFAULT, style));
            paint.setShadowLayer(5f, 0f, 2f, 0x88000000);
            updateBounds();
        }

        void updateBounds() {
            Paint.FontMetrics fm = paint.getFontMetrics();
            float width = Math.max(1f, paint.measureText(text));
            float height = Math.max(1f, fm.descent - fm.ascent);
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

        @Override
        Layer copyForState() {
            TextLayer copy = new TextLayer(text, color, sizePx, bold, italic);
            copyMatrixTo(copy);
            return copy;
        }
    }

    private static class EditorState {
        final List<Layer> layers = new ArrayList<>();
        int selectedIndex;
        int backgroundColor;
        float canvasAspectRatio;
    }

    private final List<Layer> layers = new ArrayList<>();
    private final Deque<EditorState> undoStack = new ArrayDeque<>();
    private final Deque<EditorState> redoStack = new ArrayDeque<>();

    private Layer selectedLayer;
    private int backgroundColor = Color.rgb(238, 238, 238);
    private float canvasAspectRatio = 0f;
    private final RectF canvasRect = new RectF();

    private float lastX;
    private float lastY;
    private float lastRotation;
    private boolean rotating;
    private boolean eraserMode;
    private boolean gestureCheckpointed;
    private float eraserRadiusPx;

    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint workspacePaint = new Paint();
    private final Paint canvasPaint = new Paint();
    private final ScaleGestureDetector scaleDetector;

    public CollageView(Context context) {
        this(context, null);
    }

    public CollageView(Context context, AttributeSet attrs) {
        super(context, attrs);

        eraserRadiusPx = dp(34);

        workspacePaint.setColor(Color.rgb(45, 45, 48));

        selectionPaint.setStyle(Paint.Style.STROKE);
        selectionPaint.setStrokeWidth(dp(2));
        selectionPaint.setColor(Color.WHITE);
        selectionPaint.setShadowLayer(dp(4), 0, 0, Color.BLACK);

        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        if (selectedLayer == null || eraserMode) return false;
                        checkpointGestureIfNeeded();

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
        checkpoint();

        ImageLayer layer = new ImageLayer(bitmap, true);
        float cw = Math.max(canvasRect.width(), 1f);
        float ch = Math.max(canvasRect.height(), 1f);
        float scale = Math.min(
                (cw * 0.72f) / layer.bitmap.getWidth(),
                (ch * 0.72f) / layer.bitmap.getHeight());
        scale = Math.max(scale, 0.05f);

        float shownW = layer.bitmap.getWidth() * scale;
        float shownH = layer.bitmap.getHeight() * scale;

        layer.matrix.setScale(scale, scale);
        layer.matrix.postTranslate(
                canvasRect.centerX() - shownW / 2f,
                canvasRect.centerY() - shownH / 2f);

        layers.add(layer);
        selectedLayer = layer;
        eraserMode = false;
        invalidate();
    }

    public void addText(String text, int color, float sizeSp, boolean bold, boolean italic) {
        if (text == null || text.trim().isEmpty()) return;
        checkpoint();

        TextLayer layer = new TextLayer(
                text.trim(),
                color,
                sp(sizeSp),
                bold,
                italic);

        float x = canvasRect.centerX() - layer.localBounds.width() / 2f;
        float y = canvasRect.centerY() - layer.localBounds.height() / 2f;
        layer.matrix.postTranslate(x, y);

        layers.add(layer);
        selectedLayer = layer;
        eraserMode = false;
        invalidate();
    }

    public boolean updateSelectedText(
            String text,
            int color,
            float sizeSp,
            boolean bold,
            boolean italic) {
        if (!(selectedLayer instanceof TextLayer)
                || text == null
                || text.trim().isEmpty()) {
            return false;
        }

        checkpoint();
        TextLayer layer = (TextLayer) selectedLayer;
        layer.text = text.trim();
        layer.color = color;
        layer.sizePx = sp(sizeSp);
        layer.bold = bold;
        layer.italic = italic;
        layer.applyStyle();
        invalidate();
        return true;
    }

    public boolean isTextSelected() {
        return selectedLayer instanceof TextLayer;
    }

    public boolean isImageSelected() {
        return selectedLayer instanceof ImageLayer;
    }

    public String getSelectedText() {
        return selectedLayer instanceof TextLayer ? ((TextLayer) selectedLayer).text : "";
    }

    public int getSelectedTextColor() {
        return selectedLayer instanceof TextLayer
                ? ((TextLayer) selectedLayer).color
                : Color.WHITE;
    }

    public float getSelectedTextSizeSp() {
        if (!(selectedLayer instanceof TextLayer)) return 48f;
        return ((TextLayer) selectedLayer).sizePx
                / getResources().getDisplayMetrics().scaledDensity;
    }

    public boolean isSelectedTextBold() {
        return selectedLayer instanceof TextLayer && ((TextLayer) selectedLayer).bold;
    }

    public boolean isSelectedTextItalic() {
        return selectedLayer instanceof TextLayer && ((TextLayer) selectedLayer).italic;
    }

    public void setBackgroundFill(int color) {
        if (backgroundColor == color) return;
        checkpoint();
        backgroundColor = color;
        invalidate();
    }

    public int getBackgroundColorValue() {
        return backgroundColor;
    }

    public void setCanvasAspectRatio(float aspectRatio) {
        if (Math.abs(canvasAspectRatio - aspectRatio) < 0.0001f) return;
        checkpoint();
        canvasAspectRatio = Math.max(0f, aspectRatio);
        updateCanvasRect(getWidth(), getHeight());
        invalidate();
    }

    public float getCanvasAspectRatio() {
        return canvasAspectRatio;
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

    public void setEraserRadiusDp(float radiusDp) {
        eraserRadiusPx = dp(Math.max(4f, Math.min(radiusDp, 120f)));
    }

    public float getEraserRadiusDp() {
        return eraserRadiusPx / getResources().getDisplayMetrics().density;
    }

    public void deleteSelected() {
        if (selectedLayer == null) return;
        checkpoint();
        layers.remove(selectedLayer);
        selectedLayer = null;
        eraserMode = false;
        invalidate();
    }

    public void bringSelectedForward() {
        if (selectedLayer == null) return;
        int i = layers.indexOf(selectedLayer);
        if (i >= 0 && i < layers.size() - 1) {
            checkpoint();
            layers.remove(i);
            layers.add(i + 1, selectedLayer);
            invalidate();
        }
    }

    public void sendSelectedBackward() {
        if (selectedLayer == null) return;
        int i = layers.indexOf(selectedLayer);
        if (i > 0) {
            checkpoint();
            layers.remove(i);
            layers.add(i - 1, selectedLayer);
            invalidate();
        }
    }

    public boolean cropSelected(float targetAspectRatio) {
        if (!(selectedLayer instanceof ImageLayer)) return false;

        ImageLayer layer = (ImageLayer) selectedLayer;
        int w = layer.bitmap.getWidth();
        int h = layer.bitmap.getHeight();

        int left;
        int top;
        int newW;
        int newH;

        if (targetAspectRatio <= 0f) {
            left = Math.max(1, Math.round(w * 0.05f));
            top = Math.max(1, Math.round(h * 0.05f));
            newW = Math.max(1, w - left * 2);
            newH = Math.max(1, h - top * 2);
        } else {
            float current = w / (float) h;
            if (current > targetAspectRatio) {
                newH = h;
                newW = Math.max(1, Math.round(h * targetAspectRatio));
                left = (w - newW) / 2;
                top = 0;
            } else {
                newW = w;
                newH = Math.max(1, Math.round(w / targetAspectRatio));
                left = 0;
                top = (h - newH) / 2;
            }
        }

        if (newW >= w && newH >= h) return false;

        checkpoint();

        float[] croppedOrigin = {left, top};
        float[] oldOrigin = {0f, 0f};
        layer.matrix.mapPoints(croppedOrigin);
        layer.matrix.mapPoints(oldOrigin);

        Bitmap cropped = Bitmap.createBitmap(layer.bitmap, left, top, newW, newH)
                .copy(Bitmap.Config.ARGB_8888, true);
        layer.bitmap = cropped;
        layer.updateBounds();

        layer.matrix.postTranslate(
                croppedOrigin[0] - oldOrigin[0],
                croppedOrigin[1] - oldOrigin[1]);

        invalidate();
        return true;
    }

    public boolean undo() {
        if (undoStack.isEmpty()) return false;
        pushRedo(captureState());
        restoreState(undoStack.removeFirst());
        eraserMode = false;
        invalidate();
        return true;
    }

    public boolean redo() {
        if (redoStack.isEmpty()) return false;
        pushUndoWithoutClearingRedo(captureState());
        restoreState(redoStack.removeFirst());
        eraserMode = false;
        invalidate();
        return true;
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    public Bitmap renderToBitmap() {
        int w = Math.max(1, Math.round(canvasRect.width()));
        int h = Math.max(1, Math.round(canvasRect.height()));

        Bitmap result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(result);
        canvas.translate(-canvasRect.left, -canvasRect.top);
        drawCanvas(canvas, false);
        return result;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), workspacePaint);
        drawCanvas(canvas, true);
    }

    private void drawCanvas(Canvas canvas, boolean showSelection) {
        canvas.save();
        canvas.clipRect(canvasRect);

        canvasPaint.setColor(backgroundColor);
        canvas.drawRect(canvasRect, canvasPaint);

        for (Layer layer : layers) {
            layer.draw(canvas);
        }

        if (showSelection && selectedLayer != null) {
            canvas.save();
            canvas.concat(selectedLayer.matrix);
            canvas.drawRect(selectedLayer.localBounds, selectionPaint);
            canvas.restore();
        }

        canvas.restore();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateCanvasRect(w, h);
    }

    private void updateCanvasRect(int w, int h) {
        if (w <= 0 || h <= 0) {
            canvasRect.set(0, 0, Math.max(1, w), Math.max(1, h));
            return;
        }

        float margin = dp(8);
        float availableW = Math.max(1f, w - margin * 2f);
        float availableH = Math.max(1f, h - margin * 2f);

        if (canvasAspectRatio <= 0f) {
            canvasRect.set(margin, margin, w - margin, h - margin);
            return;
        }

        float availableRatio = availableW / availableH;
        float cw;
        float ch;

        if (availableRatio > canvasAspectRatio) {
            ch = availableH;
            cw = ch * canvasAspectRatio;
        } else {
            cw = availableW;
            ch = cw / canvasAspectRatio;
        }

        float left = (w - cw) / 2f;
        float top = (h - ch) / 2f;
        canvasRect.set(left, top, left + cw, top + ch);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (eraserMode) {
            if (!(selectedLayer instanceof ImageLayer)) {
                eraserMode = false;
                return true;
            }

            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (!canvasRect.contains(event.getX(), event.getY())) return true;
                checkpoint();
                ((ImageLayer) selectedLayer).beginPixelEdit();
                ((ImageLayer) selectedLayer).erase(
                        event.getX(),
                        event.getY(),
                        eraserRadiusPx);
                invalidate();
                return true;
            }

            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                ((ImageLayer) selectedLayer).erase(
                        event.getX(),
                        event.getY(),
                        eraserRadiusPx);
                invalidate();
                return true;
            }

            return true;
        }

        scaleDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gestureCheckpointed = false;
                rotating = false;

                if (!canvasRect.contains(event.getX(), event.getY())) {
                    selectedLayer = null;
                    invalidate();
                    return true;
                }

                selectedLayer = findTopLayer(event.getX(), event.getY());
                lastX = event.getX();
                lastY = event.getY();
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
                    float dx = x - lastX;
                    float dy = y - lastY;

                    if (Math.abs(dx) > 0.1f || Math.abs(dy) > 0.1f) {
                        checkpointGestureIfNeeded();
                        selectedLayer.matrix.postTranslate(dx, dy);
                    }

                    lastX = x;
                    lastY = y;
                    invalidate();
                } else if (event.getPointerCount() >= 2 && rotating) {
                    float now = angle(event);
                    float delta = normalizeAngle(now - lastRotation);

                    if (Math.abs(delta) > 0.05f) {
                        checkpointGestureIfNeeded();
                        selectedLayer.matrix.postRotate(
                                delta,
                                midpointX(event),
                                midpointY(event));
                    }

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
                gestureCheckpointed = false;
                return true;

            default:
                return true;
        }
    }

    private void checkpointGestureIfNeeded() {
        if (!gestureCheckpointed) {
            checkpoint();
            gestureCheckpointed = true;
        }
    }

    private Layer findTopLayer(float x, float y) {
        for (int i = layers.size() - 1; i >= 0; i--) {
            Layer layer = layers.get(i);
            if (layer.contains(x, y)) return layer;
        }
        return null;
    }

    private void checkpoint() {
        pushUndoWithoutClearingRedo(captureState());
        redoStack.clear();
    }

    private void pushUndoWithoutClearingRedo(EditorState state) {
        undoStack.addFirst(state);
        while (undoStack.size() > MAX_HISTORY) {
            undoStack.removeLast();
        }
    }

    private void pushRedo(EditorState state) {
        redoStack.addFirst(state);
        while (redoStack.size() > MAX_HISTORY) {
            redoStack.removeLast();
        }
    }

    private EditorState captureState() {
        EditorState state = new EditorState();
        state.backgroundColor = backgroundColor;
        state.canvasAspectRatio = canvasAspectRatio;
        state.selectedIndex = selectedLayer == null ? -1 : layers.indexOf(selectedLayer);

        for (Layer layer : layers) {
            state.layers.add(layer.copyForState());
        }
        return state;
    }

    private void restoreState(EditorState state) {
        layers.clear();

        for (Layer layer : state.layers) {
            layers.add(layer.copyForState());
        }

        backgroundColor = state.backgroundColor;
        canvasAspectRatio = state.canvasAspectRatio;
        updateCanvasRect(getWidth(), getHeight());

        if (state.selectedIndex >= 0 && state.selectedIndex < layers.size()) {
            selectedLayer = layers.get(state.selectedIndex);
        } else {
            selectedLayer = null;
        }
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
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
