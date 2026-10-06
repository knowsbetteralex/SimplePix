package com.alexzab.simplepix;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class CollageView extends View {

    private static final int MAX_HISTORY = 25;
    private static final int DRAG_NONE = 0;
    private static final int DRAG_MOVE = 1;
    private static final int DRAG_RESIZE = 2;

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

        float rotationDegrees() {
            float[] values = new float[9];
            matrix.getValues(values);
            return (float) Math.toDegrees(
                    Math.atan2(values[Matrix.MSKEW_Y], values[Matrix.MSCALE_X]));
        }

        void copyMatrixTo(Layer target) {
            target.matrix.set(matrix);
        }

        float[] transformedCorners() {
            float[] p = {
                    localBounds.left, localBounds.top,
                    localBounds.right, localBounds.top,
                    localBounds.right, localBounds.bottom,
                    localBounds.left, localBounds.bottom
            };
            matrix.mapPoints(p);
            return p;
        }

        float[] transformedCenter() {
            float[] p = {localBounds.centerX(), localBounds.centerY()};
            matrix.mapPoints(p);
            return p;
        }

        RectF transformedBounds() {
            RectF result = new RectF(localBounds);
            matrix.mapRect(result);
            return result;
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
        boolean customCanvas;
        final RectF canvasRect = new RectF();
    }

    private final List<Layer> layers = new ArrayList<>();
    private final Deque<EditorState> undoStack = new ArrayDeque<>();
    private final Deque<EditorState> redoStack = new ArrayDeque<>();

    private Layer selectedLayer;
    private int backgroundColor = Color.rgb(238, 238, 238);
    private float canvasAspectRatio = 0f;
    private boolean customCanvas;
    private final RectF canvasRect = new RectF();

    private boolean eraserMode;
    private float eraserRadiusPx;

    private int dragMode = DRAG_NONE;
    private int resizeHandle = -1;
    private float touchDownX;
    private float touchDownY;
    private float resizePivotX;
    private float resizePivotY;
    private float resizeStartDistance;
    private float resizeStartScale;
    private final Matrix gestureStartMatrix = new Matrix();
    private boolean gestureCheckpointed;

    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint workspacePaint = new Paint();
    private final Paint canvasPaint = new Paint();
    private final Path selectionPath = new Path();

    private final float handleRadiusPx;
    private final float handleHitRadiusPx;

    public CollageView(Context context) {
        this(context, null);
    }

    public CollageView(Context context, AttributeSet attrs) {
        super(context, attrs);

        eraserRadiusPx = dp(34);
        handleRadiusPx = dp(7);
        handleHitRadiusPx = dp(20);

        workspacePaint.setColor(Color.rgb(45, 45, 48));

        selectionPaint.setStyle(Paint.Style.STROKE);
        selectionPaint.setStrokeWidth(dp(1.5f));
        selectionPaint.setColor(Color.WHITE);
        selectionPaint.setShadowLayer(dp(3), 0, 0, Color.BLACK);

        handleFillPaint.setStyle(Paint.Style.FILL);
        handleFillPaint.setColor(Color.WHITE);

        handleStrokePaint.setStyle(Paint.Style.STROKE);
        handleStrokePaint.setStrokeWidth(dp(2));
        handleStrokePaint.setColor(Color.rgb(65, 85, 220));
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

    public boolean hasSelection() {
        return selectedLayer != null;
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

    public float getSelectedRotationDegrees() {
        if (selectedLayer == null) return 0f;
        return normalizeAngle(selectedLayer.rotationDegrees());
    }

    public boolean setSelectedRotationDegrees(float degrees, boolean addUndoCheckpoint) {
        if (selectedLayer == null) return false;

        if (addUndoCheckpoint) checkpoint();

        float current = selectedLayer.rotationDegrees();
        float delta = normalizeAngle(degrees - current);
        float[] center = selectedLayer.transformedCenter();
        selectedLayer.matrix.postRotate(delta, center[0], center[1]);
        invalidate();
        return true;
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
        if (!customCanvas && Math.abs(canvasAspectRatio - aspectRatio) < 0.0001f) return;
        checkpoint();
        customCanvas = false;
        canvasAspectRatio = Math.max(0f, aspectRatio);
        updateCanvasRect(getWidth(), getHeight(), getWidth(), getHeight());
        invalidate();
    }

    public float getCanvasAspectRatio() {
        return canvasAspectRatio;
    }

    public boolean cropCanvasToContent() {
        if (layers.isEmpty() || canvasRect.isEmpty()) return false;

        RectF content = null;
        for (Layer layer : layers) {
            RectF b = layer.transformedBounds();
            if (content == null) content = new RectF(b);
            else content.union(b);
        }

        if (content == null) return false;

        RectF target = new RectF(content);
        if (!target.intersect(canvasRect)) return false;

        float padding = dp(3);
        target.inset(-padding, -padding);

        target.left = Math.max(target.left, canvasRect.left);
        target.top = Math.max(target.top, canvasRect.top);
        target.right = Math.min(target.right, canvasRect.right);
        target.bottom = Math.min(target.bottom, canvasRect.bottom);

        if (target.width() < dp(8) || target.height() < dp(8)) return false;

        if (Math.abs(target.left - canvasRect.left) < 0.5f
                && Math.abs(target.top - canvasRect.top) < 0.5f
                && Math.abs(target.right - canvasRect.right) < 0.5f
                && Math.abs(target.bottom - canvasRect.bottom) < 0.5f) {
            return false;
        }

        checkpoint();
        canvasRect.set(target);
        customCanvas = true;
        canvasAspectRatio = canvasRect.width() / canvasRect.height();
        invalidate();
        return true;
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
        dragMode = DRAG_NONE;
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

        float[] offset = {left, top};
        float[] origin = {0f, 0f};
        layer.matrix.mapPoints(offset);
        layer.matrix.mapPoints(origin);

        Bitmap cropped = Bitmap.createBitmap(layer.bitmap, left, top, newW, newH)
                .copy(Bitmap.Config.ARGB_8888, true);
        layer.bitmap = cropped;
        layer.updateBounds();

        layer.matrix.postTranslate(
                offset[0] - origin[0],
                offset[1] - origin[1]);

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
            drawSelection(canvas);
        }

        canvas.restore();
    }

    private void drawSelection(Canvas canvas) {
        float[] c = selectedLayer.transformedCorners();

        selectionPath.reset();
        selectionPath.moveTo(c[0], c[1]);
        selectionPath.lineTo(c[2], c[3]);
        selectionPath.lineTo(c[4], c[5]);
        selectionPath.lineTo(c[6], c[7]);
        selectionPath.close();
        canvas.drawPath(selectionPath, selectionPaint);

        for (int i = 0; i < 4; i++) {
            float x = c[i * 2];
            float y = c[i * 2 + 1];
            canvas.drawCircle(x, y, handleRadiusPx, handleFillPaint);
            canvas.drawCircle(x, y, handleRadiusPx, handleStrokePaint);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateCanvasRect(w, h, oldw, oldh);
    }

    private void updateCanvasRect(int w, int h, int oldw, int oldh) {
        if (w <= 0 || h <= 0) {
            canvasRect.set(0, 0, Math.max(1, w), Math.max(1, h));
            return;
        }

        if (customCanvas && oldw > 0 && oldh > 0 && !canvasRect.isEmpty()) {
            float sx = w / (float) oldw;
            float sy = h / (float) oldh;
            canvasRect.set(
                    canvasRect.left * sx,
                    canvasRect.top * sy,
                    canvasRect.right * sx,
                    canvasRect.bottom * sy);
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
            return handleEraserTouch(event);
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gestureCheckpointed = false;
                dragMode = DRAG_NONE;
                resizeHandle = -1;

                if (selectedLayer != null) {
                    int handle = findResizeHandle(event.getX(), event.getY());
                    if (handle >= 0) {
                        beginResize(handle, event.getX(), event.getY());
                        return true;
                    }
                }

                if (!canvasRect.contains(event.getX(), event.getY())) {
                    selectedLayer = null;
                    invalidate();
                    return true;
                }

                Layer touched = findTopLayer(event.getX(), event.getY());
                selectedLayer = touched;

                if (selectedLayer != null) {
                    dragMode = DRAG_MOVE;
                    gestureStartMatrix.set(selectedLayer.matrix);
                    touchDownX = event.getX();
                    touchDownY = event.getY();
                }

                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (selectedLayer == null) return true;

                if (dragMode == DRAG_MOVE) {
                    float dx = event.getX() - touchDownX;
                    float dy = event.getY() - touchDownY;

                    if (Math.abs(dx) > 0.5f || Math.abs(dy) > 0.5f) {
                        checkpointGestureIfNeeded();
                        selectedLayer.matrix.set(gestureStartMatrix);
                        selectedLayer.matrix.postTranslate(dx, dy);
                        invalidate();
                    }
                } else if (dragMode == DRAG_RESIZE) {
                    float distance = distance(
                            event.getX(),
                            event.getY(),
                            resizePivotX,
                            resizePivotY);

                    if (resizeStartDistance > 1f && distance > 1f) {
                        float factor = distance / resizeStartDistance;
                        float wantedScale = resizeStartScale * factor;
                        wantedScale = Math.max(0.06f, Math.min(wantedScale, 16f));
                        factor = wantedScale / Math.max(resizeStartScale, 0.0001f);

                        if (Math.abs(factor - 1f) > 0.003f) {
                            checkpointGestureIfNeeded();
                            selectedLayer.matrix.set(gestureStartMatrix);
                            selectedLayer.matrix.postScale(
                                    factor,
                                    factor,
                                    resizePivotX,
                                    resizePivotY);
                            invalidate();
                        }
                    }
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragMode = DRAG_NONE;
                resizeHandle = -1;
                gestureCheckpointed = false;
                return true;

            default:
                return true;
        }
    }

    private boolean handleEraserTouch(MotionEvent event) {
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

    private void beginResize(int handle, float x, float y) {
        resizeHandle = handle;
        dragMode = DRAG_RESIZE;
        gestureStartMatrix.set(selectedLayer.matrix);
        resizeStartScale = selectedLayer.scale();

        float[] c = selectedLayer.transformedCorners();
        int opposite = (handle + 2) % 4;
        resizePivotX = c[opposite * 2];
        resizePivotY = c[opposite * 2 + 1];
        resizeStartDistance = distance(x, y, resizePivotX, resizePivotY);
    }

    private int findResizeHandle(float x, float y) {
        if (selectedLayer == null) return -1;
        float[] c = selectedLayer.transformedCorners();

        for (int i = 0; i < 4; i++) {
            if (distance(x, y, c[i * 2], c[i * 2 + 1]) <= handleHitRadiusPx) {
                return i;
            }
        }
        return -1;
    }

    private Layer findTopLayer(float x, float y) {
        for (int i = layers.size() - 1; i >= 0; i--) {
            Layer layer = layers.get(i);
            if (layer.contains(x, y)) return layer;
        }
        return null;
    }

    private void checkpointGestureIfNeeded() {
        if (!gestureCheckpointed) {
            checkpoint();
            gestureCheckpointed = true;
        }
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
        state.customCanvas = customCanvas;
        state.canvasRect.set(canvasRect);
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
        customCanvas = state.customCanvas;

        if (customCanvas) {
            canvasRect.set(state.canvasRect);
        } else {
            updateCanvasRect(getWidth(), getHeight(), getWidth(), getHeight());
        }

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

    private static float distance(float x1, float y1, float x2, float y2) {
        float dx = x1 - x2;
        float dy = y1 - y2;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float normalizeAngle(float angle) {
        while (angle > 180f) angle -= 360f;
        while (angle < -180f) angle += 360f;
        return angle;
    }
}
