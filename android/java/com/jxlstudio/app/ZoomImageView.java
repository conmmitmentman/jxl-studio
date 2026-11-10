package com.jxlstudio.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/** Touch viewer with pinch zoom, pan, double-tap, and an alpha checkerboard. */
public final class ZoomImageView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private Bitmap bitmap;
    private int sourceWidth;
    private float scale = 1, x, y;
    private boolean fitting = true;

    public ZoomImageView(Context context) {
        super(context);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                zoom(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY());
                return true;
            }
        });
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }
            @Override public boolean onScroll(MotionEvent first, MotionEvent current, float dx, float dy) {
                if (bitmap != null && !scaleDetector.isInProgress()) { x -= dx; y -= dy; fitting = false; invalidate(); }
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent event) { if (fitting) actualSize(); else fit(); return true; }
            @Override public boolean onSingleTapUp(MotionEvent event) { performClick(); return true; }
        });
        setFocusable(true);
    }

    public void setImage(Bitmap value, int width, int height) {
        if (bitmap == value) return;
        bitmap = value; sourceWidth = width; fitting = true; fit();
    }
    public void fit() {
        fitting = true;
        if (bitmap != null && getWidth() > 0 && getHeight() > 0) {
            scale = Math.min((float)getWidth() / bitmap.getWidth(), (float)getHeight() / bitmap.getHeight());
            center();
        }
        invalidate();
    }
    public void actualSize() {
        if (bitmap == null) return;
        fitting = false;
        scale = (float)sourceWidth / bitmap.getWidth(); center(); invalidate();
    }
    private void center() {
        x = (getWidth() - bitmap.getWidth() * scale) / 2;
        y = (getHeight() - bitmap.getHeight() * scale) / 2;
    }
    private void zoom(float factor, float focusX, float focusY) {
        if (bitmap == null) return;
        float originalScale = (float)sourceWidth / bitmap.getWidth();
        float next = Math.max(originalScale * .02f, Math.min(originalScale * 8, scale * factor));
        float ratio = next / scale;
        x = focusX - (focusX - x) * ratio; y = focusY - (focusY - y) * ratio;
        scale = next; fitting = false; invalidate();
    }
    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) { if (fitting) fit(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); canvas.drawColor(Color.rgb(10, 16, 24));
        if (bitmap == null) {
            paint.setColor(Color.rgb(156, 174, 192));
            paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(16 * getResources().getDisplayMetrics().scaledDensity);
            canvas.drawText("JPEG XLを、そのまま開く。", getWidth() / 2f, getHeight() / 2f, paint); return;
        }
        canvas.save(); canvas.clipRect(x, y, x + bitmap.getWidth() * scale, y + bitmap.getHeight() * scale);
        int cell = Math.max(12, Math.round(16 * getResources().getDisplayMetrics().density));
        for (int row = 0; row * cell < getHeight(); row++) for (int col = 0; col * cell < getWidth(); col++) {
            paint.setColor(Color.rgb((row + col) % 2 == 0 ? 38 : 56, (row + col) % 2 == 0 ? 38 : 56, (row + col) % 2 == 0 ? 38 : 56));
            canvas.drawRect(col * cell, row * cell, (col + 1) * cell, (row + 1) * cell, paint);
        }
        canvas.translate(x, y); canvas.scale(scale, scale); paint.setColor(Color.WHITE);
        canvas.drawBitmap(bitmap, 0, 0, paint); canvas.restore();
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        getParent().requestDisallowInterceptTouchEvent(true);
        scaleDetector.onTouchEvent(event); gestureDetector.onTouchEvent(event); return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
}
