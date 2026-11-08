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
            M_MOVE = 5, M_PICK = 6, M_FILL = 7, M_IGNORE = 8, M_PIVOT = 9;
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

    void setDoc(Doc d) {
        doc = d;
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
        extra.dy = selDy;
        extra.onion = onion && !playing;
        extra.freezeActive = live != null || !sel.isEmpty() || mode == M_LASSO;
        renderer.boilOn = doc.boil;
        renderer.render(c, doc, frame, t, true, true, extra, null);
        c.restore();
        overlays(c, t);
        postInvalidateOnAnimation();
    }

    private void overlays(Canvas c, float t) {
        float dash = Ui.dp(5);
        if (lassoActive) {
            tmpPath.set(lasso);
            tmpPath.transform(view);
            ants(c, tmpPath, t, dash);
        }
        if (!sel.isEmpty()) {
            RectF r = selBounds(0);
            r.offset(selDx, selDy);
            view.mapRect(r);
            tmpPath.reset();
            tmpPath.addRect(r, Path.Direction.CW);
            ants(c, tmpPath, t, dash);
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
                if (mode == M_IGNORE) break;
                if (mode == M_LASSO) {
                    lassoActive = false;
                    lasso.reset();
                }
                if (mode == M_MOVE) {
                    selDx = 0;
                    selDy = 0;
                }
                if (mode == M_PICK) endPick(false);
                beginGesture(e, -1);
                break;
            case MotionEvent.ACTION_MOVE:
                if (mode == M_GESTURE) updateGesture(e);
                else toolMove(e);
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (mode == M_GESTURE || mode == M_GEST_END) {
                    beginGesture(e, e.getActionIndex());
                } else if (mode == M_DRAW && e.getActionIndex() == 0) {
                    commitStroke(e);
                    mode = M_IGNORE;
                }
                break;
            case MotionEvent.ACTION_UP:
                if (mode == M_GESTURE || mode == M_GEST_END) {
                    if (!gMoved && e.getEventTime() - downTime < 350) {
                        if (maxPtr == 2) undo();
                        else if (maxPtr == 3) redo();
                    }
                } else {
                    toolUp(e);
                }
                mode = M_NONE;
                break;
            case MotionEvent.ACTION_CANCEL:
                live = null;
                lassoActive = false;
                selDx = selDy = 0;
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
                startStroke(x, y, pressure(e, -1));
                mode = M_DRAW;
                break;
            case LASSO:
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
        live.frames = Math.max(1, bs.frames);
        live.spacing = bs.spacing;
        live.echo = bs.echo && !live.eraser;
        live.seed = rnd.nextInt(1 << 20);
        sx = x;
        sy = y;
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
            if (Math.hypot(sx - lastX, sy - lastY) >= min) {
                live.add(sx, sy, lp);
                lastX = sx;
                lastY = sy;
            }
        }
    }

    private void commitStroke(MotionEvent e) {
        if (live == null) return;
        toDoc(e.getX(), e.getY());
        float k = 1f - settings().stabilize * 0.9f;
        for (int i = 0; i < 4 && Math.hypot(pt[0] - sx, pt[1] - sy) > 1.5f / scale(); i++) {
            sx += (pt[0] - sx) * Math.max(k, 0.5f);
            sy += (pt[1] - sy) * Math.max(k, 0.5f);
            live.add(sx, sy, lp);
        }
        history.push(this);
        Cel cel = activeCel();
        cel.items.add(live);
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

    private void finishLasso() {
        lassoActive = false;
        lasso.close();
        Region clip = new Region(-doc.w, -doc.h, doc.w * 2, doc.h * 2);
        Region r = new Region();
        r.setPath(lasso, clip);
        lasso.reset();
        sel.clear();
        for (Item it : activeCel().items) {
            if (it instanceof Stroke) {
                Stroke s = (Stroke) it;
                int step = Math.max(1, s.n / 40), in = 0, cnt = 0;
                for (int i = 0; i < s.n; i += step) {
                    cnt++;
                    if (r.contains((int) s.x[i], (int) s.y[i])) in++;
                }
                if (in > 0 && in * 10 >= cnt * 6) sel.add(it);
            } else if (r.contains((int) it.bounds.centerX(), (int) it.bounds.centerY())) {
                sel.add(it);
            }
        }
        if (host != null) host.onSelection(!sel.isEmpty());
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
