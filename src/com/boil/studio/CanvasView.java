package com.boil.studio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;

final class CanvasView extends View {
    static final int BRUSH = 0, ERASER = 1, LASSO = 2, PICKER = 3, FILL = 4;

    interface Host {
        void onDocChanged();

        void onColorPicked(int color);

        void onSelection(boolean has);

        void onFrame();

        void onPlayStopped();

        /** A brush or eraser stroke started (true) or ended (false). */
        void onDrawing(boolean drawing);

        /** Short message for the user (e.g. why a touch did nothing). */
        void onHint(String text);
    }

    Doc doc;
    Host host;
    final Renderer renderer = new Renderer();
    final History history = new History();
    int tool = BRUSH, layer = 0, frame = 0, color = 0xFF111111, brush = Brush.TRIO;
    final BrushSettings[] brushes = new BrushSettings[Brush.COUNT];
    BrushSettings eraser = BrushSettings.eraser();
    boolean playing, onion = true;
    /** Size preview ring shown while the side slider is dragged (doc units, 0 = hidden). */
    float previewSize;

    private final Matrix view = new Matrix(), inv = new Matrix();
    private boolean fitted;
    private final long t0 = SystemClock.uptimeMillis();
    private float playT0;
    private final Renderer.Extra extra = new Renderer.Extra();
    private final Random rnd = new Random();
    private final Paint ui = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint checker = new Paint();
    private final float[] pt = new float[2];
    private final float[] mv = new float[9];

    private static final int M_NONE = 0, M_DRAW = 1, M_GESTURE = 2, M_GEST_END = 3, M_LASSO = 4,
            M_MOVE = 5, M_PICK = 6, M_FILL = 7, M_IGNORE = 8, M_PIVOT = 9, M_ROTATE = 10, M_SCALE = 11, M_SELGEST = 12, M_AXIS = 13, M_RULER = 14;
    /** Symmetry: bit 1 mirrors across the vertical axis x = symX, bit 2 across the horizontal axis y = symY. */
    int sym;
    float symX, symY;
    /** 0 = off, 1 = straight lines, 2 = ruler (lines snap along the ruler's direction). */
    int ruler;
    float rAx, rAy, rBx, rBy;
    private int grab;
    private float startX, startY;
    /** Two-finger selection transform: start centre, distance, angle and finger ids. */
    private float sgCx, sgCy, sgD0, sgA0;
    private int sgIdA, sgIdB;
    /** Live rotate/scale of the selection while a handle is dragged. */
    private float selRot, selScale = 1, rotCx, rotCy, rotA0, scaleD0;
    private final Matrix selM = new Matrix();
    private final float[] hp = new float[4];
    /** Next touch places the active layer's base point. */
    boolean pivotMode;
    private int mode;
    private long downTime;
    private int maxPtr;
    private boolean gMoved;
    private final Matrix gStart = new Matrix();
    private float gCx, gCy, gDist, gAng;
    private int gIdA, gIdB;
    private float downX, downY;

    private Stroke live;
    private float sx, sy, lastX, lastY, lp;

    private final Path lasso = new Path(), tmpPath = new Path();
    private boolean lassoActive;
    private float lassoLX, lassoLY;
    final HashSet<Item> sel = new HashSet<>();
    private float selDx, selDy, mx0, my0;

    private Bitmap pickBmp;
    private float pickSX, pickSY;
    private int pickColor;

    CanvasView(Context c) {
        super(c);
        for (int i = 0; i < Brush.COUNT; i++) brushes[i] = BrushSettings.defaults(i);
        Bitmap cb = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        cb.setPixel(0, 0, 0xFF3A3A3A);
        cb.setPixel(1, 1, 0xFF3A3A3A);
        cb.setPixel(1, 0, 0xFF2A2A2A);
        cb.setPixel(0, 1, 0xFF2A2A2A);
        android.graphics.BitmapShader bs = new android.graphics.BitmapShader(cb, android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT);
        Matrix sm = new Matrix();
        sm.setScale(Ui.dp(8), Ui.dp(8));
        bs.setLocalMatrix(sm);
        checker.setShader(bs);
    }

    // ---------- state ----------

    /** Shows the final framed shot through the 3D camera (drawing is paused while on). */
    boolean cameraView;
    private final Camera3D.Key camKey = new Camera3D.Key();

    /** Timeline position as a fractional frame, so the camera glides between keys while playing. */
    float camFrame(float t) {
        if (playing && doc.frames > 1) return ((t - playT0) * doc.fps) % doc.frames;
        return frame;
    }

    void setDoc(Doc d) {
        doc = d;
        symX = d.w / 2f;
        symY = d.h / 2f;
        rAx = d.w * 0.2f;
        rBx = d.w * 0.8f;
        rAy = rBy = d.h * 0.5f;
        layer = Math.min(layer, d.layers.size() - 1);
        frame = Math.min(frame, d.frames - 1);
        fitted = false;
        if (getWidth() > 0) fit();
    }

    Layer activeLayer() {
        return doc.layers.get(Math.max(0, Math.min(layer, doc.layers.size() - 1)));
    }

    Cel activeCel() {
        return doc.cel(activeLayer(), frame);
    }

    BrushSettings settings() {
        return tool == ERASER ? eraser : brushes[brush];
    }

    float time() {
        return (SystemClock.uptimeMillis() - t0) / 1000f;
    }

    void changed() {
        if (host != null) host.onDocChanged();
    }

    void setFrame(int f) {
        clearSelection();
        frame = Math.max(0, Math.min(doc.frames - 1, f));
        if (host != null) host.onFrame();
    }

    void setPlaying(boolean p) {
        if (p && doc.frames < 2) return;
        clearSelection();
        playing = p;
        playT0 = time() - frame / (float) doc.fps;
        if (!p && host != null) host.onPlayStopped();
    }

    void undo() {
        clearSelection();
        if (history.undo(this)) changed();
    }

    void redo() {
        clearSelection();
        if (history.redo(this)) changed();
    }

    // ---------- view transform ----------

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (!fitted || ow == 0) fit();
        else {
            view.postTranslate((w - ow) / 2f, (h - oh) / 2f);
            view.invert(inv);
        }
    }

    void fit() {
        if (doc == null || getWidth() == 0) return;
        float top = Ui.dp(64), bottom = Ui.dp(76), side = Ui.dp(58);
        float aw = getWidth() - side * 2, ah = getHeight() - top - bottom;
        float s = Math.min(aw / doc.w, ah / doc.h);
        view.reset();
        view.setScale(s, s);
        view.postTranslate(side + (aw - doc.w * s) / 2, top + (ah - doc.h * s) / 2);
        view.invert(inv);
        fitted = true;
    }

    float scale() {
        view.getValues(mv);
        return (float) Math.hypot(mv[Matrix.MSCALE_X], mv[Matrix.MSKEW_Y]);
    }

    private float rotation() {
        view.getValues(mv);
        return (float) Math.toDegrees(Math.atan2(mv[Matrix.MSKEW_Y], mv[Matrix.MSCALE_X]));
    }

    private void toDoc(float x, float y) {
        pt[0] = x;
        pt[1] = y;
        inv.mapPoints(pt);
    }

    // ---------- drawing ----------

    @Override
    protected void onDraw(Canvas c) {
        if (doc == null) return;
        float t = time();
        if (playing && doc.frames > 1) {
            int f = ((int) ((t - playT0) * doc.fps)) % doc.frames;
            if (f != frame) {
                frame = f;
                if (host != null) host.onFrame();
            }
        }
        c.drawColor(Ui.BG);
        c.save();
        c.concat(view);
        if ((doc.bg >>> 24) < 255) c.drawRect(0, 0, doc.w, doc.h, checker);
        c.clipRect(0, 0, doc.w, doc.h);
        extra.layer = activeLayer();
        extra.live = live;
        extra.sel = sel.isEmpty() ? null : sel;
        extra.dx = selDx;
        extra.sym = sym;
        extra.symX = symX;
        extra.symY = symY;
        extra.rot = selRot;
        extra.sc = selScale;
        extra.cx = rotCx;
        extra.cy = rotCy;
        extra.dy = selDy;
        extra.onion = onion && !playing;
        extra.freezeActive = live != null || !sel.isEmpty() || mode == M_LASSO;
        renderer.boilOn = doc.boil;
        if (cameraView) {
            renderer.camFrame = camFrame(t);
            renderer.cam = Camera3D.at(doc.cam, renderer.camFrame, camKey);
            renderer.render(c, doc, frame, t, true, true, null, null);
            renderer.cam = null;
        } else {
            renderer.render(c, doc, frame, t, true, true, extra, null);
        }
        c.restore();
        overlays(c, t);
        postInvalidateOnAnimation();
    }

    /** Symmetry axis handles on screen: vertical axis in hp[0..1], horizontal axis in hp[2..3]. */
    private void axisHandles() {
        toDoc(getWidth() / 2f, getHeight() / 2f);
        float cy = Math.max(0, Math.min(doc.h, pt[1])), cx = Math.max(0, Math.min(doc.w, pt[0]));
        hp[0] = symX;
        hp[1] = cy;
        hp[2] = cx;
        hp[3] = symY;
        view.mapPoints(hp);
    }

    private void rulerHandles() {
        hp[0] = rAx;
        hp[1] = rAy;
        hp[2] = rBx;
        hp[3] = rBy;
        view.mapPoints(hp);
    }

    private void knob(Canvas c, float x, float y) {
        ui.setPathEffect(null);
        ui.setStyle(Paint.Style.FILL);
        ui.setColor(0xFFFFFFFF);
        c.drawCircle(x, y, Ui.dp(11), ui);
        ui.setStyle(Paint.Style.STROKE);
        ui.setStrokeWidth(Ui.dp(1));
        ui.setColor(0xFF000000);
        c.drawCircle(x, y, Ui.dp(11), ui);
        c.drawCircle(x, y, Ui.dp(3), ui);
    }

    private void guides(Canvas c) {
        if (sym != 0 && (tool == BRUSH || tool == ERASER)) {
            float[] q = new float[8];
            q[0] = symX;
            q[1] = 0;
            q[2] = symX;
            q[3] = doc.h;
            q[4] = 0;
            q[5] = symY;
            q[6] = doc.w;
            q[7] = symY;
            view.mapPoints(q);
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(Ui.dp(1.5f));
            ui.setPathEffect(new DashPathEffect(new float[]{Ui.dp(8), Ui.dp(6)}, 0));
            ui.setColor(0xCC3E7BFA);
            if ((sym & 1) != 0) c.drawLine(q[0], q[1], q[2], q[3], ui);
            if ((sym & 2) != 0) c.drawLine(q[4], q[5], q[6], q[7], ui);
            ui.setPathEffect(null);
            axisHandles();
            if ((sym & 1) != 0) knob(c, hp[0], hp[1]);
            if ((sym & 2) != 0) knob(c, hp[2], hp[3]);
        }
        if (ruler == 2 && (tool == BRUSH || tool == ERASER)) {
            rulerHandles();
            float dx = hp[2] - hp[0], dy = hp[3] - hp[1], l = (float) Math.hypot(dx, dy);
            if (l > 1) {
                dx /= l;
                dy /= l;
                float big = 6000;
                ui.setPathEffect(null);
                ui.setStyle(Paint.Style.STROKE);
                ui.setStrokeCap(Paint.Cap.BUTT);
                ui.setStrokeWidth(Ui.dp(22));
                ui.setColor(0x26FFFFFF);
                c.drawLine(hp[0] - dx * big, hp[1] - dy * big, hp[2] + dx * big, hp[3] + dy * big, ui);
                ui.setStrokeWidth(Ui.dp(1));
                ui.setColor(0xCCFFFFFF);
                c.drawLine(hp[0] - dx * big, hp[1] - dy * big, hp[2] + dx * big, hp[3] + dy * big, ui);
                ui.setStrokeCap(Paint.Cap.ROUND);
            }
            knob(c, hp[0], hp[1]);
            knob(c, hp[2], hp[3]);
            if (mode == M_RULER) {
                float ang = (float) Math.toDegrees(Math.atan2(rBy - rAy, rBx - rAx));
                String label = Math.round(ang) + "°";
                ui.setStyle(Paint.Style.FILL);
                ui.setTextSize(Ui.dp(14));
                ui.setTextAlign(Paint.Align.CENTER);
                float lx = grab == 0 ? hp[0] : hp[2], ly = (grab == 0 ? hp[1] : hp[3]) - Ui.dp(26);
                float tw = ui.measureText(label) / 2 + Ui.dp(8);
                ui.setColor(0xE6161616);
                c.drawRoundRect(new RectF(lx - tw, ly - Ui.dp(15), lx + tw, ly + Ui.dp(7)), Ui.dp(8), Ui.dp(8), ui);
                ui.setColor(0xFFFFFFFF);
                c.drawText(label, lx, ly, ui);
            }
        }
    }

    private void overlays(Canvas c, float t) {
        guides(c);
        float dash = Ui.dp(5);
        if (lassoActive) {
            tmpPath.set(lasso);
            tmpPath.transform(view);
            ants(c, tmpPath, t, dash);
        }
        if (!sel.isEmpty()) {
            RectF r = selBounds(0);
            tmpPath.reset();
            tmpPath.addRect(r, Path.Direction.CW);
            selMatrix();
            tmpPath.transform(selM);
            tmpPath.transform(view);
            ants(c, tmpPath, t, dash);
            if (mode != M_MOVE && mode != M_SELGEST) drawHandles(c);
        }
        if (mode == M_PICK) {
            float x = pickSX, y = pickSY - Ui.dp(80);
            ui.setPathEffect(null);
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(pickColor);
            c.drawCircle(x, y, Ui.dp(34), ui);
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(Ui.dp(5));
            ui.setColor(0xFFFFFFFF);
            c.drawCircle(x, y, Ui.dp(36), ui);
            ui.setStrokeWidth(Ui.dp(1));
            ui.setColor(0xFF000000);
            c.drawCircle(x, y, Ui.dp(39), ui);
        }
        Layer al = activeLayer();
        if (al.hasPivot && (tool == LASSO || pivotMode || mode == M_PIVOT || showPivot)) {
            pt[0] = al.pivotX;
            pt[1] = al.pivotY;
            view.mapPoints(pt);
            drawPivot(c, pt[0], pt[1]);
        }
        if (previewSize > 0) {
            float r = Math.max(Ui.dp(2), previewSize * scale() / 2);
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            ui.setPathEffect(null);
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(color);
            c.drawCircle(cx, cy, r, ui);
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(Ui.dp(1.5f));
            ui.setColor(0xFFFFFFFF);
            c.drawCircle(cx, cy, r + Ui.dp(2), ui);
            ui.setColor(0xFF000000);
            c.drawCircle(cx, cy, r + Ui.dp(3.5f), ui);
        }
    }

    /** Doc-space transform of the selection preview: scale and turn around the pivot, then move. */
    private void selMatrix() {
        selM.reset();
        selM.postScale(selScale, selScale, rotCx, rotCy);
        selM.postRotate(selRot, rotCx, rotCy);
        selM.postTranslate(selDx, selDy);
    }

    /** Screen positions of the rotate handle (hp[0..1]) and the scale handle (hp[2..3]). */
    private void handles() {
        RectF b = selBounds(0);
        if (mode != M_ROTATE && mode != M_SCALE && mode != M_SELGEST) {
            rotCx = pivotX(b);
            rotCy = pivotY(b);
        }
        selMatrix();
        hp[0] = b.centerX();
        hp[1] = b.top - Ui.dp(40) / scale();
        hp[2] = b.right;
        hp[3] = b.bottom;
        selM.mapPoints(hp);
        view.mapPoints(hp);
    }

    private void drawHandles(Canvas c) {
        handles();
        RectF b = selBounds(0);
        float[] top = {b.centerX(), b.top};
        selM.mapPoints(top);
        view.mapPoints(top);
        ui.setPathEffect(null);
        ui.setStyle(Paint.Style.STROKE);
        ui.setStrokeWidth(Ui.dp(1.5f));
        ui.setColor(0xFFFFFFFF);
        c.drawLine(top[0], top[1], hp[0], hp[1], ui);
        // rotate handle: filled disc with a small turning arc
        float r = Ui.dp(16);
        ui.setStyle(Paint.Style.FILL);
        ui.setColor(0xFFFFFFFF);
        c.drawCircle(hp[0], hp[1], r, ui);
        ui.setStyle(Paint.Style.STROKE);
        ui.setStrokeWidth(Ui.dp(1));
        ui.setColor(0xFF000000);
        c.drawCircle(hp[0], hp[1], r, ui);
        ui.setStrokeWidth(Ui.dp(1.8f));
        RectF arc = new RectF(hp[0] - Ui.dp(7), hp[1] - Ui.dp(7), hp[0] + Ui.dp(7), hp[1] + Ui.dp(7));
        c.drawArc(arc, -60, 270, false, ui);
        // scale handle: square in the corner
        float q = Ui.dp(9);
        ui.setStyle(Paint.Style.FILL);
        ui.setColor(0xFFFFFFFF);
        c.drawRect(hp[2] - q, hp[3] - q, hp[2] + q, hp[3] + q, ui);
        ui.setStyle(Paint.Style.STROKE);
        ui.setStrokeWidth(Ui.dp(1));
        ui.setColor(0xFF000000);
        c.drawRect(hp[2] - q, hp[3] - q, hp[2] + q, hp[3] + q, ui);
        if (mode == M_ROTATE || mode == M_SCALE) {
            String label = mode == M_ROTATE ? Math.round(selRot) + "°" : Math.round(selScale * 100) + "%";
            ui.setStyle(Paint.Style.FILL);
            ui.setTextSize(Ui.dp(14));
            ui.setTextAlign(Paint.Align.CENTER);
            float lx = mode == M_ROTATE ? hp[0] : hp[2], ly = (mode == M_ROTATE ? hp[1] : hp[3]) - Ui.dp(24);
            float tw = ui.measureText(label) / 2 + Ui.dp(8);
            ui.setColor(0xE6161616);
            c.drawRoundRect(new RectF(lx - tw, ly - Ui.dp(15), lx + tw, ly + Ui.dp(7)), Ui.dp(8), Ui.dp(8), ui);
            ui.setColor(0xFFFFFFFF);
            c.drawText(label, lx, ly, ui);
        }
    }

    /** Shown while the effect panel is open so the base point can be seen. */
    boolean showPivot;

    private void drawPivot(Canvas c, float x, float y) {
        float r = Ui.dp(9);
        ui.setPathEffect(null);
        ui.setStyle(Paint.Style.FILL);
        ui.setColor(0x55000000);
        c.drawCircle(x, y, r + Ui.dp(3), ui);
        ui.setStyle(Paint.Style.STROKE);
        ui.setStrokeWidth(Ui.dp(2));
        ui.setColor(0xFFFFFFFF);
        c.drawCircle(x, y, r, ui);
        c.drawLine(x - r - Ui.dp(6), y, x - Ui.dp(3), y, ui);
        c.drawLine(x + Ui.dp(3), y, x + r + Ui.dp(6), y, ui);
        c.drawLine(x, y - r - Ui.dp(6), x, y - Ui.dp(3), ui);
        c.drawLine(x, y + Ui.dp(3), x, y + r + Ui.dp(6), ui);
        ui.setStyle(Paint.Style.FILL);
        c.drawCircle(x, y, Ui.dp(2.5f), ui);
    }

    /** Whether a screen point grabs the active layer's base point handle. */
    private boolean onPivot(float sx, float sy) {
        Layer al = activeLayer();
        if (!al.hasPivot) return false;
        pt[0] = al.pivotX;
        pt[1] = al.pivotY;
        view.mapPoints(pt);
        return Math.hypot(sx - pt[0], sy - pt[1]) < Ui.dp(26);
    }

    private void ants(Canvas c, Path p, float t, float dash) {
        ui.setStyle(Paint.Style.STROKE);
        ui.setStrokeWidth(Ui.dp(1.5f));
        ui.setPathEffect(null);
        ui.setColor(0xFFFFFFFF);
        c.drawPath(p, ui);
        ui.setColor(0xFF000000);
        ui.setPathEffect(new DashPathEffect(new float[]{dash, dash}, (t * Ui.dp(24)) % (dash * 2)));
        c.drawPath(p, ui);
        ui.setPathEffect(null);
    }

    // ---------- touch ----------

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (doc == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downTime = e.getEventTime();
                maxPtr = 1;
                gMoved = false;
                if (playing) setPlaying(false);
                toolDown(e);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                maxPtr = Math.max(maxPtr, e.getPointerCount());
                if (mode == M_DRAW) {
                    if (e.getEventTime() - downTime < 250 || live == null || live.n < 8) {
                        live = null;
                    } else {
                        break; // a stray palm during a long stroke: keep drawing with the first finger
                    }
                }
                if (mode == M_IGNORE || mode == M_SELGEST) break;
                if (tool == LASSO && !sel.isEmpty() && (mode == M_MOVE || mode == M_ROTATE || mode == M_SCALE)) {
                    beginSelGesture(e);
                    break;
                }
                if (mode == M_LASSO) {
                    lassoActive = false;
                    lasso.reset();
                }
                if (mode == M_MOVE) {
                    selDx = 0;
                    selDy = 0;
                }
                if (mode == M_ROTATE || mode == M_SCALE) {
                    selRot = 0;
                    selScale = 1;
                }
                if (mode == M_PICK) endPick(false);
                beginGesture(e, -1);
                break;
            case MotionEvent.ACTION_MOVE:
                if (mode == M_GESTURE) updateGesture(e);
                else if (mode == M_SELGEST) updateSelGesture(e);
                else toolMove(e);
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (mode == M_SELGEST) {
                    int id = e.getPointerId(e.getActionIndex());
                    if (id == sgIdA || id == sgIdB) commitSelGesture();
                } else if (mode == M_GESTURE || mode == M_GEST_END) {
                    beginGesture(e, e.getActionIndex());
                } else if (mode == M_DRAW && e.getActionIndex() == 0) {
                    commitStroke(e);
                    mode = M_IGNORE;
                }
                break;
            case MotionEvent.ACTION_UP:
                if (mode == M_SELGEST) {
                    commitSelGesture();
                } else if (mode == M_GESTURE || mode == M_GEST_END) {
                    if (!gMoved && e.getEventTime() - downTime < 350) {
                        if (maxPtr == 2) undo();
                        else if (maxPtr == 3) redo();
                    }
                } else {
                    toolUp(e);
                }
                mode = M_NONE;
                if (host != null) host.onDrawing(false);
                break;
            case MotionEvent.ACTION_CANCEL:
                if (host != null) host.onDrawing(false);
                live = null;
                lassoActive = false;
                selDx = selDy = 0;
                selRot = 0;
                selScale = 1;
                if (mode == M_PICK) endPick(false);
                mode = M_NONE;
                break;
            default:
                break;
        }
        return true;
    }

    private void beginGesture(MotionEvent e, int skip) {
        int a = -1, b = -1;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (i == skip) continue;
            if (a < 0) a = i;
            else if (b < 0) {
                b = i;
                break;
            }
        }
        if (b < 0) {
            mode = M_GEST_END;
            return;
        }
        gStart.set(view);
        gCx = (e.getX(a) + e.getX(b)) / 2;
        gCy = (e.getY(a) + e.getY(b)) / 2;
        gDist = Math.max(1, (float) Math.hypot(e.getX(a) - e.getX(b), e.getY(a) - e.getY(b)));
        gAng = (float) Math.atan2(e.getY(b) - e.getY(a), e.getX(b) - e.getX(a));
        gIdA = e.getPointerId(a);
        gIdB = e.getPointerId(b);
        mode = M_GESTURE;
    }

    private void updateGesture(MotionEvent e) {
        int a = e.findPointerIndex(gIdA), b = e.findPointerIndex(gIdB);
        if (a < 0 || b < 0) return;
        float cx = (e.getX(a) + e.getX(b)) / 2, cy = (e.getY(a) + e.getY(b)) / 2;
        float dist = Math.max(1, (float) Math.hypot(e.getX(a) - e.getX(b), e.getY(a) - e.getY(b)));
        float ang = (float) Math.atan2(e.getY(b) - e.getY(a), e.getX(b) - e.getX(a));
        if (!gMoved && Math.abs(cx - gCx) + Math.abs(cy - gCy) + Math.abs(dist - gDist) > Ui.dp(12)) gMoved = true;
        if (!gMoved) return;
        view.set(gStart);
        float base = scale();
        float s = Math.max(0.05f / base, Math.min(60f / base, dist / gDist));
        view.postScale(s, s, gCx, gCy);
        view.postRotate((float) Math.toDegrees(ang - gAng), gCx, gCy);
        view.postTranslate(cx - gCx, cy - gCy);
        float r = rotation();
        float near = Math.round(r / 90f) * 90f;
        if (Math.abs(r - near) < 4) view.postRotate(near - r, cx, cy);
        view.invert(inv);
    }

    private void toolDown(MotionEvent e) {
        downX = e.getX();
        downY = e.getY();
        if (cameraView) {
            mode = M_IGNORE;
            if (host != null) host.onHint("Сейчас вид через камеру — выключите его, чтобы рисовать");
            return;
        }
        if (pivotMode || (tool == LASSO && onPivot(downX, downY))) {
            history.push(this);
            mode = M_PIVOT;
            movePivot(downX, downY);
            return;
        }
        toDoc(downX, downY);
        float x = pt[0], y = pt[1];
        switch (tool) {
            case BRUSH:
            case ERASER:
                if (sym != 0) {
                    axisHandles();
                    if ((sym & 1) != 0 && Math.hypot(downX - hp[0], downY - hp[1]) < Ui.dp(24)) {
                        grab = 0;
                        mode = M_AXIS;
                        break;
                    }
                    if ((sym & 2) != 0 && Math.hypot(downX - hp[2], downY - hp[3]) < Ui.dp(24)) {
                        grab = 1;
                        mode = M_AXIS;
                        break;
                    }
                }
                if (ruler == 2) {
                    rulerHandles();
                    if (Math.hypot(downX - hp[0], downY - hp[1]) < Ui.dp(26)) {
                        grab = 0;
                        mode = M_RULER;
                        break;
                    }
                    if (Math.hypot(downX - hp[2], downY - hp[3]) < Ui.dp(26)) {
                        grab = 1;
                        mode = M_RULER;
                        break;
                    }
                }
                startStroke(x, y, pressure(e, -1));
                mode = M_DRAW;
                if (host != null) host.onDrawing(true);
                break;
            case LASSO:
                if (!sel.isEmpty()) {
                    handles();
                    if (Math.hypot(downX - hp[0], downY - hp[1]) < Ui.dp(30)) {
                        mode = M_ROTATE;
                        rotA0 = (float) Math.atan2(y - rotCy, x - rotCx);
                        selRot = 0;
                        break;
                    }
                    if (Math.hypot(downX - hp[2], downY - hp[3]) < Ui.dp(26)) {
                        mode = M_SCALE;
                        scaleD0 = Math.max(1e-3f, (float) Math.hypot(x - rotCx, y - rotCy));
                        selScale = 1;
                        break;
                    }
                }
                if (!sel.isEmpty() && selBounds(12 / scale()).contains(x, y)) {
                    mode = M_MOVE;
                    mx0 = x;
                    my0 = y;
                } else {
                    clearSelection();
                    lasso.reset();
                    lasso.moveTo(x, y);
                    lassoLX = x;
                    lassoLY = y;
                    lassoActive = true;
                    mode = M_LASSO;
                }
                break;
            case PICKER:
                pickBmp = Bitmap.createBitmap(doc.w, doc.h, Bitmap.Config.ARGB_8888);
                renderer.render(new Canvas(pickBmp), doc, frame, time(), true, true, null, null);
                mode = M_PICK;
                pick(e.getX(), e.getY());
                break;
            case FILL:
                mode = M_FILL;
                break;
            default:
                break;
        }
    }

    private void movePivot(float sx, float sy) {
        toDoc(sx, sy);
        Layer al = activeLayer();
        al.hasPivot = true;
        al.pivotX = pt[0];
        al.pivotY = pt[1];
    }

    void clearPivot() {
        Layer al = activeLayer();
        if (!al.hasPivot) return;
        history.push(this);
        al.hasPivot = false;
        changed();
    }

    private void toolMove(MotionEvent e) {
        switch (mode) {
            case M_AXIS:
                toDoc(e.getX(), e.getY());
                if (grab == 0) symX = Math.max(0, Math.min(doc.w, pt[0]));
                else symY = Math.max(0, Math.min(doc.h, pt[1]));
                break;
            case M_RULER: {
                toDoc(e.getX(), e.getY());
                float ox = grab == 0 ? rBx : rAx, oy = grab == 0 ? rBy : rAy;
                float len = (float) Math.hypot(pt[0] - ox, pt[1] - oy);
                double ang = Math.toDegrees(Math.atan2(pt[1] - oy, pt[0] - ox));
                double snap = Math.round(ang / 15.0) * 15.0; // magnet to 0°, 15°, 30°, 45°…
                if (Math.abs(ang - snap) < 2.5) {
                    pt[0] = ox + len * (float) Math.cos(Math.toRadians(snap));
                    pt[1] = oy + len * (float) Math.sin(Math.toRadians(snap));
                }
                if (grab == 0) {
                    rAx = pt[0];
                    rAy = pt[1];
                } else {
                    rBx = pt[0];
                    rBy = pt[1];
                }
                break;
            }
            case M_PIVOT:
                movePivot(e.getX(), e.getY());
                break;
            case M_DRAW:
                strokeMove(e);
                break;
            case M_LASSO: {
                toDoc(e.getX(), e.getY());
                if (Math.hypot(pt[0] - lassoLX, pt[1] - lassoLY) > 3 / scale()) {
                    lasso.lineTo(pt[0], pt[1]);
                    lassoLX = pt[0];
                    lassoLY = pt[1];
                }
                break;
            }
            case M_MOVE:
                toDoc(e.getX(), e.getY());
                selDx = pt[0] - mx0;
                selDy = pt[1] - my0;
                break;
            case M_ROTATE: {
                toDoc(e.getX(), e.getY());
                float a = (float) Math.toDegrees(Math.atan2(pt[1] - rotCy, pt[0] - rotCx) - rotA0);
                while (a > 180) a -= 360;
                while (a <= -180) a += 360;
                float snap = Math.round(a / 45f) * 45f; // gentle magnet to 0°, 45°, 90°…
                selRot = Math.abs(a - snap) < 2.5f ? snap : a;
                break;
            }
            case M_SCALE: {
                toDoc(e.getX(), e.getY());
                float sc = (float) Math.hypot(pt[0] - rotCx, pt[1] - rotCy) / scaleD0;
                sc = Math.max(0.05f, Math.min(20f, sc));
                selScale = Math.abs(sc - 1) < 0.03f ? 1 : sc;
                break;
            }
            case M_PICK:
                pick(e.getX(), e.getY());
                break;
            default:
                break;
        }
    }

    private void toolUp(MotionEvent e) {
        switch (mode) {
            case M_PIVOT:
                movePivot(e.getX(), e.getY());
                pivotMode = false;
                changed();
                break;
            case M_DRAW:
                strokeMove(e);
                commitStroke(e);
                break;
            case M_LASSO:
                finishLasso();
                break;
            case M_MOVE:
                commitMove();
                break;
            case M_ROTATE: {
                float a = selRot;
                selRot = 0;
                if (Math.abs(a) > 0.05f) rotateSelection(a);
                break;
            }
            case M_SCALE: {
                float sc = selScale;
                selScale = 1;
                if (Math.abs(sc - 1) > 0.001f) scaleSelection(sc);
                break;
            }
            case M_PICK:
                endPick(true);
                break;
            case M_FILL:
                if (Math.hypot(e.getX() - downX, e.getY() - downY) < Ui.dp(16)) {
                    toDoc(e.getX(), e.getY());
                    doFill(pt[0], pt[1]);
                }
                break;
            default:
                break;
        }
    }

    // ---------- strokes ----------

    private float pressure(MotionEvent e, int hist) {
        if (e.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return 1f;
        float p = hist < 0 ? e.getPressure(0) : e.getHistoricalPressure(0, hist);
        return Math.max(0.05f, Math.min(1f, p));
    }

    private void startStroke(float x, float y, float p) {
        BrushSettings bs = settings();
        live = new Stroke();
        live.eraser = tool == ERASER;
        live.brush = live.eraser ? Brush.MARKER : brush;
        live.color = color;
        live.size = bs.size;
        live.alpha = bs.alpha;
        live.wobble = bs.wobble;
        live.speed = bs.speed;
        live.frames = bs.boil ? Math.max(1, bs.frames) : 1;
        if (!bs.boil) live.wobble = 0;
        live.spacing = bs.spacing;
        live.echo = bs.echo && !live.eraser;
        live.seed = rnd.nextInt(1 << 20);
        sx = x;
        sy = y;
        startX = x;
        startY = y;
        lp = p;
        lastX = x;
        lastY = y;
        live.add(x, y, p);
    }

    private void strokeMove(MotionEvent e) {
        if (live == null) return;
        float k = 1f - settings().stabilize * 0.9f;
        float min = 1.2f / scale();
        int hs = e.getHistorySize();
        for (int h = 0; h <= hs; h++) {
            float ex = h < hs ? e.getHistoricalX(0, h) : e.getX(0);
            float ey = h < hs ? e.getHistoricalY(0, h) : e.getY(0);
            float pr = pressure(e, h < hs ? h : -1);
            toDoc(ex, ey);
            sx += (pt[0] - sx) * k;
            sy += (pt[1] - sy) * k;
            lp += (pr - lp) * 0.4f;
            if (ruler == 0 && Math.hypot(sx - lastX, sy - lastY) >= min) {
                live.add(sx, sy, lp);
                lastX = sx;
                lastY = sy;
            }
        }
        if (ruler != 0) shapeLine();
    }

    /** Straight-line and ruler modes: the stroke becomes one straight segment, finely sampled so it still jitters. */
    private void shapeLine() {
        float ax = startX, ay = startY, tx = sx, ty = sy;
        if (ruler == 2) {
            float dx = rBx - rAx, dy = rBy - rAy, l = (float) Math.hypot(dx, dy);
            if (l > 1) {
                dx /= l;
                dy /= l;
                float px = startX - rAx, py = startY - rAy;
                float along = px * dx + py * dy, perp = -px * dy + py * dx;
                if (Math.abs(perp) < Ui.dp(32) / scale()) { // starting on the ruler: draw right on its edge
                    ax = rAx + dx * along;
                    ay = rAy + dy * along;
                }
                float k = (sx - ax) * dx + (sy - ay) * dy;
                tx = ax + dx * k;
                ty = ay + dy * k;
            }
        }
        live.clearPoints();
        float len = (float) Math.hypot(tx - ax, ty - ay);
        int steps = Math.max(1, (int) (len / 2));
        for (int i = 0; i <= steps; i++) {
            float u = i / (float) steps;
            live.add(ax + (tx - ax) * u, ay + (ty - ay) * u, lp);
        }
    }

    private void commitStroke(MotionEvent e) {
        if (live == null) return;
        toDoc(e.getX(), e.getY());
        float k = 1f - settings().stabilize * 0.9f;
        for (int i = 0; ruler == 0 && i < 4 && Math.hypot(pt[0] - sx, pt[1] - sy) > 1.5f / scale(); i++) {
            sx += (pt[0] - sx) * Math.max(k, 0.5f);
            sy += (pt[1] - sy) * Math.max(k, 0.5f);
            live.add(sx, sy, lp);
        }
        history.push(this);
        Cel cel = activeCel();
        cel.items.add(live);
        for (int m = 1; m <= 3; m++) {
            if ((sym & m) != m) continue;
            Item mirror = live;
            if ((m & 1) != 0) mirror = mirror.flipped(true, symX, symY);
            if ((m & 2) != 0) mirror = mirror.flipped(false, symX, symY);
            cel.items.add(mirror);
        }
        cel.touch();
        live = null;
        changed();
    }

    // ---------- lasso ----------

    private RectF selBounds(float pad) {
        RectF r = new RectF();
        for (Item it : sel) r.union(it.bounds);
        r.inset(-pad, -pad);
        return r;
    }

    /**
     * Selects only what is inside the lasso: strokes crossing the outline are cut into pieces (with
     * invisible seams) and fills are split by the outline; pictures and merged groups go by their centre.
     */
    private void finishLasso() {
        lassoActive = false;
        lasso.close();
        Region clip = new Region(-doc.w, -doc.h, doc.w * 2, doc.h * 2);
        Region r = new Region();
        r.setPath(lasso, clip);
        Path cut = new Path(lasso);
        lasso.reset();
        sel.clear();
        Cel cel = activeCel();
        ArrayList<Item> next = new ArrayList<>();
        boolean split = false;
        for (Item it : cel.items) {
            if (it instanceof Stroke) {
                Stroke s = (Stroke) it;
                boolean[] in = new boolean[s.n];
                int cnt = 0;
                for (int i = 0; i < s.n; i++) {
                    in[i] = r.contains((int) s.x[i], (int) s.y[i]);
                    if (in[i]) cnt++;
                }
                if (cnt == 0) {
                    next.add(s);
                } else if (cnt == s.n) {
                    next.add(s);
                    sel.add(s);
                } else {
                    split = true;
                    ArrayList<Boolean> flags = new ArrayList<>();
                    ArrayList<Stroke> pieces = s.split(in, flags);
                    for (int k = 0; k < pieces.size(); k++) {
                        next.add(pieces.get(k));
                        if (flags.get(k)) sel.add(pieces.get(k));
                    }
                }
            } else if (it instanceof Fill && ((Fill) it).angle == 0) {
                Fill f = (Fill) it;
                byte[] m = lassoMask(cut, f);
                byte[] inD = new byte[f.data.length], outD = new byte[f.data.length];
                int ni = 0, no = 0;
                for (int i = 0; i < m.length; i++) {
                    if (f.data[i] == 0) continue;
                    if (m[i] != 0) {
                        inD[i] = f.data[i];
                        ni++;
                    } else {
                        outD[i] = f.data[i];
                        no++;
                    }
                }
                if (ni == 0) {
                    next.add(f);
                } else if (no == 0) {
                    next.add(f);
                    sel.add(f);
                } else {
                    split = true;
                    Fill fin = f.withData(inD);
                    next.add(f.withData(outD));
                    next.add(fin);
                    sel.add(fin);
                }
            } else {
                next.add(it);
                if (r.contains((int) it.bounds.centerX(), (int) it.bounds.centerY())) sel.add(it);
            }
        }
        if (split) {
            history.push(this);
            cel.items.clear();
            cel.items.addAll(next);
            cel.touch();
            changed();
        }
        if (host != null) host.onSelection(!sel.isEmpty());
    }

    /** Lasso coverage over a fill's mask area, one byte per pixel (non-zero = inside). */
    private static byte[] lassoMask(Path cut, Fill f) {
        Bitmap m = Bitmap.createBitmap(f.w, f.h, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(m);
        c.translate(-f.left, -f.top);
        Paint p = new Paint();
        p.setColor(0xFF000000);
        p.setStyle(Paint.Style.FILL);
        c.drawPath(cut, p);
        int rb = m.getRowBytes();
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(rb * f.h);
        m.copyPixelsToBuffer(bb);
        m.recycle();
        byte[] raw = bb.array(), out = new byte[f.w * f.h];
        for (int y = 0; y < f.h; y++) System.arraycopy(raw, y * rb, out, y * f.w, f.w);
        return out;
    }

    /** Mirrors the selection across the layer's base point (or its own centre). */
    void flipSelection(boolean horizontal) {
        if (sel.isEmpty()) return;
        history.push(this);
        RectF b = selBounds(0);
        float cx = pivotX(b), cy = pivotY(b);
        Cel cel = activeCel();
        HashSet<Item> out = new HashSet<>();
        for (int i = 0; i < cel.items.size(); i++) {
            Item it = cel.items.get(i);
            if (sel.contains(it)) {
                Item m = it.flipped(horizontal, cx, cy);
                cel.items.set(i, m);
                out.add(m);
            }
        }
        cel.touch();
        sel.clear();
        sel.addAll(out);
        changed();
    }

    private void commitMove() {
        if (Math.abs(selDx) + Math.abs(selDy) < 0.5f) {
            selDx = selDy = 0;
            return;
        }
        history.push(this);
        Cel cel = activeCel();
        HashSet<Item> moved = new HashSet<>();
        for (int i = 0; i < cel.items.size(); i++) {
            Item it = cel.items.get(i);
            if (sel.contains(it)) {
                Item m = it.moved(selDx, selDy);
                cel.items.set(i, m);
                moved.add(m);
            }
        }
        cel.touch();
        sel.clear();
        sel.addAll(moved);
        selDx = selDy = 0;
        changed();
    }

    void clearSelection() {
        boolean had = !sel.isEmpty();
        sel.clear();
        selDx = selDy = 0;
        selRot = 0;
        selScale = 1;
        lassoActive = false;
        if (had && host != null) host.onSelection(false);
    }

    void deleteSelection() {
        if (sel.isEmpty()) return;
        history.push(this);
        Cel cel = activeCel();
        cel.items.removeAll(sel);
        cel.touch();
        clearSelection();
        changed();
    }

    /** Selects everything on the current layer and frame, so a whole layer can be moved or scaled. */
    boolean selectAll() {
        clearSelection();
        sel.addAll(activeCel().items);
        if (host != null) host.onSelection(!sel.isEmpty());
        return !sel.isEmpty();
    }

    private float pivotX(RectF b) {
        Layer al = activeLayer();
        return al.hasPivot ? al.pivotX : b.centerX();
    }

    private float pivotY(RectF b) {
        Layer al = activeLayer();
        return al.hasPivot ? al.pivotY : b.centerY();
    }

    /** Turns the selection by deg degrees around the layer's base point (or its own centre). */
    void rotateSelection(float deg) {
        if (sel.isEmpty()) return;
        history.push(this);
        RectF b = selBounds(0);
        float cx = pivotX(b), cy = pivotY(b);
        Cel cel = activeCel();
        HashSet<Item> turned = new HashSet<>();
        for (int i = 0; i < cel.items.size(); i++) {
            Item it = cel.items.get(i);
            if (sel.contains(it)) {
                Item m = it.rotated(deg, cx, cy);
                cel.items.set(i, m);
                turned.add(m);
            }
        }
        cel.touch();
        sel.clear();
        sel.addAll(turned);
        changed();
    }

    private void beginSelGesture(MotionEvent e) {
        int a = 0, b = 1;
        sgIdA = e.getPointerId(a);
        sgIdB = e.getPointerId(b);
        selDx = selDy = 0;
        selRot = 0;
        selScale = 1;
        toDoc(e.getX(a), e.getY(a));
        float ax = pt[0], ay = pt[1];
        toDoc(e.getX(b), e.getY(b));
        float bx = pt[0], by = pt[1];
        sgCx = (ax + bx) / 2;
        sgCy = (ay + by) / 2;
        sgD0 = Math.max(1e-3f, (float) Math.hypot(bx - ax, by - ay));
        sgA0 = (float) Math.atan2(by - ay, bx - ax);
        rotCx = sgCx;
        rotCy = sgCy;
        gMoved = true; // never treat this as the two-finger undo tap
        mode = M_SELGEST;
    }

    private void updateSelGesture(MotionEvent e) {
        int a = e.findPointerIndex(sgIdA), b = e.findPointerIndex(sgIdB);
        if (a < 0 || b < 0) return;
        toDoc(e.getX(a), e.getY(a));
        float ax = pt[0], ay = pt[1];
        toDoc(e.getX(b), e.getY(b));
        float bx = pt[0], by = pt[1];
        float deg = (float) Math.toDegrees(Math.atan2(by - ay, bx - ax) - sgA0);
        while (deg > 180) deg -= 360;
        while (deg <= -180) deg += 360;
        float snap = Math.round(deg / 45f) * 45f;
        selRot = Math.abs(deg - snap) < 2.5f ? snap : deg;
        float sc = Math.max(0.05f, Math.min(20f, (float) Math.hypot(bx - ax, by - ay) / sgD0));
        selScale = Math.abs(sc - 1) < 0.03f ? 1 : sc;
        selDx = (ax + bx) / 2 - sgCx;
        selDy = (ay + by) / 2 - sgCy;
    }

    private void commitSelGesture() {
        float rot = selRot, sc = selScale, dx = selDx, dy = selDy;
        selRot = 0;
        selScale = 1;
        selDx = selDy = 0;
        mode = M_GEST_END;
        if (Math.abs(rot) > 0.05f || Math.abs(sc - 1) > 0.001f || Math.abs(dx) + Math.abs(dy) > 0.5f) {
            transformSelection(sc, rot, sgCx, sgCy, dx, dy);
        }
    }

    /** Scales and turns the selection around (cx, cy), then moves it, as one undo step. */
    private void transformSelection(float sc, float rot, float cx, float cy, float dx, float dy) {
        if (sel.isEmpty()) return;
        history.push(this);
        Cel cel = activeCel();
        HashSet<Item> out = new HashSet<>();
        for (int i = 0; i < cel.items.size(); i++) {
            Item it = cel.items.get(i);
            if (!sel.contains(it)) continue;
            Item m = it;
            if (sc != 1) m = m.scaled(sc, cx, cy);
            if (rot != 0) m = m.rotated(rot, cx, cy);
            if (dx != 0 || dy != 0) m = m.moved(dx, dy);
            cel.items.set(i, m);
            out.add(m);
        }
        cel.touch();
        sel.clear();
        sel.addAll(out);
        changed();
    }

    void scaleSelection(float s) {
        if (sel.isEmpty()) return;
        history.push(this);
        RectF b = selBounds(0);
        float cx = pivotX(b), cy = pivotY(b);
        Cel cel = activeCel();
        HashSet<Item> scaled = new HashSet<>();
        for (int i = 0; i < cel.items.size(); i++) {
            Item it = cel.items.get(i);
            if (sel.contains(it)) {
                Item m = it.scaled(s, cx, cy);
                cel.items.set(i, m);
                scaled.add(m);
            }
        }
        cel.touch();
        sel.clear();
        sel.addAll(scaled);
        changed();
    }

    /** Places an item on the current layer and frame and leaves it selected for moving. */
    void insert(Item it) {
        clearSelection();
        history.push(this);
        Cel cel = activeCel();
        cel.items.add(it);
        cel.touch();
        sel.add(it);
        changed();
        if (host != null) host.onSelection(true);
    }

    void duplicateSelection() {
        if (sel.isEmpty()) return;
        history.push(this);
        Cel cel = activeCel();
        float off = 24;
        ArrayList<Item> copies = new ArrayList<>();
        for (Item it : cel.items) if (sel.contains(it)) copies.add(it.moved(off, off));
        cel.items.addAll(copies);
        cel.touch();
        sel.clear();
        sel.addAll(copies);
        changed();
    }

    // ---------- picker & fill ----------

    private void pick(float x, float y) {
        pickSX = x;
        pickSY = y;
        toDoc(x, y);
        int ix = Math.max(0, Math.min(doc.w - 1, (int) pt[0])), iy = Math.max(0, Math.min(doc.h - 1, (int) pt[1]));
        int c = pickBmp != null ? pickBmp.getPixel(ix, iy) : color;
        if ((c >>> 24) < 8) c = (doc.bg >>> 24) < 8 ? 0xFFFFFFFF : doc.bg;
        pickColor = c | 0xFF000000;
    }

    private void endPick(boolean apply) {
        if (pickBmp != null) {
            pickBmp.recycle();
            pickBmp = null;
        }
        mode = M_NONE;
        if (apply && host != null) host.onColorPicked(pickColor);
    }

    private void doFill(float x, float y) {
        BrushSettings bs = brushes[brush];
        int c = (Math.round(bs.alpha * 255) << 24) | (color & 0xFFFFFF);
        Fill f = Filler.flood(renderer, doc, frame, (int) x, (int) y, c, bs, rnd.nextInt(1 << 20));
        if (f == null) return;
        history.push(this);
        Cel cel = activeCel();
        cel.items.add(f);
        cel.touch();
        changed();
    }

    // ---------- history ----------

    static final class History {
        static final class Snap {
            ArrayList<Layer> layers;
            int frames, layer, frame;
        }

        final ArrayList<Snap> undo = new ArrayList<>(), redo = new ArrayList<>();

        private static Snap capture(CanvasView v) {
            Snap s = new Snap();
            s.layers = new ArrayList<>();
            for (Layer l : v.doc.layers) s.layers.add(l.copy());
            s.frames = v.doc.frames;
            s.layer = v.layer;
            s.frame = v.frame;
            return s;
        }

        private static void apply(CanvasView v, Snap s) {
            v.doc.layers = s.layers;
            v.doc.frames = s.frames;
            v.layer = Math.max(0, Math.min(s.layer, s.layers.size() - 1));
            v.frame = Math.max(0, Math.min(s.frame, s.frames - 1));
            for (Layer l : s.layers) for (Cel c : l.cels) if (c != null) c.touch();
        }

        void push(CanvasView v) {
            undo.add(capture(v));
            if (undo.size() > 80) undo.remove(0);
            redo.clear();
        }

        boolean undo(CanvasView v) {
            if (undo.isEmpty()) return false;
            redo.add(capture(v));
            apply(v, undo.remove(undo.size() - 1));
            return true;
        }

        boolean redo(CanvasView v) {
            if (redo.isEmpty()) return false;
            undo.add(capture(v));
            apply(v, redo.remove(redo.size() - 1));
            return true;
        }
    }
}
