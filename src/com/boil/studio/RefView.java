package com.boil.studio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Floating reference image above the canvas: drag the card to move it, drag the corner or pinch to
 * resize, tap × to remove, tap the eye to dim it. With the picker tool, touching it samples a colour.
 */
final class RefView extends View {
    interface Host {
        void onRefClose();

        boolean onRefPickMode();

        void onRefPick(int color);
    }

    private final Bitmap bmp;
    private final Host host;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path clip = new Path();
    private final RectF img = new RectF(), card = new RectF();
    private final float header = Ui.dp(30), corner = Ui.dp(16);
    private boolean dim;

    private static final int NONE = 0, MOVE = 1, RESIZE = 2, CLOSE = 3, DIM = 4, PICK = 5, PINCH = 6;
    private int mode;
    private float downRawX, downRawY, startTx, startTy, startW, pinchDist;

    RefView(Context c, Bitmap bmp, Host host) {
        super(c);
        this.bmp = bmp;
        this.host = host;
    }

    Bitmap bitmap() {
        return bmp;
    }

    /** Places the card at the top-right of the parent with a sensible width. */
    void place(int parentW, int parentH) {
        int w = Math.min(Math.round(parentW * 0.42f), Ui.dp(280));
        setCardWidth(w, parentW, parentH);
        setTranslationX(parentW - w - Ui.dp(12));
        setTranslationY(Ui.dp(70));
    }

    private void setCardWidth(int w, int parentW, int parentH) {
        w = Math.max(Ui.dp(110), Math.min(w, parentW - Ui.dp(16)));
        int h = Math.round(w * bmp.getHeight() / (float) bmp.getWidth() + header);
        if (h > parentH - Ui.dp(16)) {
            h = parentH - Ui.dp(16);
            w = Math.round((h - header) * bmp.getWidth() / (float) bmp.getHeight());
        }
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp == null) lp = new FrameLayout.LayoutParams(w, h);
        lp.width = w;
        lp.height = h;
        setLayoutParams(lp);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        card.set(0, 0, w, h);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Ui.PANEL);
        c.drawRoundRect(card, Ui.dp(16), Ui.dp(16), p);
        img.set(Ui.dp(4), header, w - Ui.dp(4), h - Ui.dp(4));
        clip.reset();
        clip.addRoundRect(img, Ui.dp(12), Ui.dp(12), Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        p.setAlpha(dim ? 110 : 255);
        c.drawBitmap(bmp, null, img, p);
        p.setAlpha(255);
        c.restore();
        // header: grip, eye and close
        p.setColor(Ui.DIM);
        for (int i = 0; i < 3; i++) c.drawCircle(w / 2 - Ui.dp(8) + i * Ui.dp(8), header / 2, Ui.dp(2), p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.dp(1.8f));
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(Ui.TEXT);
        float cx = w - header / 2, cy = header / 2, r = Ui.dp(5);
        c.drawLine(cx - r, cy - r, cx + r, cy + r, p);
        c.drawLine(cx + r, cy - r, cx - r, cy + r, p);
        float ex = header / 2;
        p.setColor(dim ? Ui.FAINT : Ui.TEXT);
        c.drawOval(new RectF(ex - Ui.dp(7), cy - Ui.dp(4.5f), ex + Ui.dp(7), cy + Ui.dp(4.5f)), p);
        c.drawCircle(ex, cy, Ui.dp(2), p);
        // resize corner
        p.setColor(0xCCFFFFFF);
        float bx = w - Ui.dp(8), by = h - Ui.dp(8);
        c.drawLine(bx - Ui.dp(10), by, bx, by - Ui.dp(10), p);
        c.drawLine(bx - Ui.dp(5), by, bx, by - Ui.dp(5), p);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        View parent = (View) getParent();
        int pw = parent.getWidth(), ph = parent.getHeight();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float x = e.getX(), y = e.getY(), w = getWidth(), h = getHeight();
                downRawX = e.getRawX();
                downRawY = e.getRawY();
                startTx = getTranslationX();
                startTy = getTranslationY();
                startW = w;
                if (y < header && x > w - header) mode = CLOSE;
                else if (y < header && x < header) mode = DIM;
                else if (x > w - corner * 2 && y > h - corner * 2) mode = RESIZE;
                else if (y > header && host.onRefPickMode()) {
                    mode = PICK;
                    pick(x, y);
                } else mode = MOVE;
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                if (e.getPointerCount() == 2) {
                    mode = PINCH;
                    pinchDist = Math.max(1, dist(e));
                    startW = getWidth();
                }
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dx = e.getRawX() - downRawX, dy = e.getRawY() - downRawY;
                if (mode == MOVE) {
                    setTranslationX(clamp(startTx + dx, -getWidth() + Ui.dp(60), pw - Ui.dp(60)));
                    setTranslationY(clamp(startTy + dy, 0, ph - Ui.dp(40)));
                } else if (mode == RESIZE) {
                    setCardWidth(Math.round(startW + dx), pw, ph);
                } else if (mode == PINCH && e.getPointerCount() >= 2) {
                    setCardWidth(Math.round(startW * dist(e) / pinchDist), pw, ph);
                } else if (mode == PICK) {
                    pick(e.getX(), e.getY());
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                float moved = Math.abs(e.getRawX() - downRawX) + Math.abs(e.getRawY() - downRawY);
                if (mode == CLOSE && moved < Ui.dp(12)) host.onRefClose();
                else if (mode == DIM && moved < Ui.dp(12)) {
                    dim = !dim;
                    invalidate();
                }
                mode = NONE;
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                mode = NONE;
                return true;
            default:
                return true;
        }
    }

    private void pick(float x, float y) {
        if (!img.contains(x, y)) return;
        int bx = (int) ((x - img.left) / img.width() * bmp.getWidth());
        int by = (int) ((y - img.top) / img.height() * bmp.getHeight());
        bx = Math.max(0, Math.min(bmp.getWidth() - 1, bx));
        by = Math.max(0, Math.min(bmp.getHeight() - 1, by));
        host.onRefPick(bmp.getPixel(bx, by) | 0xFF000000);
    }

    private static float dist(MotionEvent e) {
        return (float) Math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1));
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
