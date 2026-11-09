package com.boil.studio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Set;

/**
 * Draws a document at a moment in time. Strokes are kept as vectors and re-built per "boil frame":
 * each frame displaces the points with a different smooth noise field, which gives the hand-drawn
 * line-boil look. Built paths are cached per frame so steady-state drawing is cheap.
 */
final class Renderer {
    static final class Extra {
        Layer layer;
        Stroke live;
        Set<Item> sel;
        float dx, dy;
        /** Live rotate/scale preview of the selection around (cx, cy). */
        float rot, sc = 1, cx, cy;
        /** Symmetry: bit 1 mirrors across x = symX, bit 2 across y = symY. */
        int sym;
        float symX, symY;
        boolean onion, freezeActive;
    }

    /** Off for renderers running on other threads: stroke caches are owned by the UI thread. */
    boolean useCache = true;
    /** Whether animated effects apply during the current render (off for fills and thumbnails). */
    private boolean fxOn = true;
    /** Line jitter playback; when off, every stroke and fill stays on its first boil frame. */
    boolean boilOn = true;
    /** Camera state for this render; null draws the flat canvas (drawing mode). */
    Camera3D.Key cam;
    /** Fractional timeline frame for the camera render (layer fades); set together with cam. */
    float camFrame;
    private final Camera3D cam3 = new Camera3D();
    private final Matrix camM = new Matrix(), texM = new Matrix();
    private final Camera3D.Key shot = new Camera3D.Key();
    private final Camera3D.LKey lk = new Camera3D.LKey();
    private final Matrix keyM = new Matrix();
    private Bitmap blurBmp;
    private int[] blurBuf;
    private final Path scratch = new Path();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint layerPaint = new Paint();
    private final Matrix fx = new Matrix();
    private final Path tmp = new Path();
    private final Path star = new Path();
    private final PorterDuffXfermode erase = new PorterDuffXfermode(PorterDuff.Mode.DST_OUT);
    private final PorterDuffColorFilter onionPrev = new PorterDuffColorFilter(0xFFE5484D, PorterDuff.Mode.SRC_IN);
    private final PorterDuffColorFilter onionNext = new PorterDuffColorFilter(0xFF3E7BFA, PorterDuff.Mode.SRC_IN);
    private float[] bx = new float[0], by, hw, nx, ny, lx, ly, rx, ry;

    Renderer() {
        star.moveTo(0, -1);
        star.quadTo(0.12f, -0.12f, 1, 0);
        star.quadTo(0.12f, 0.12f, 0, 1);
        star.quadTo(-0.12f, 0.12f, -1, 0);
        star.quadTo(-0.12f, -0.12f, 0, -1);
        star.close();
    }

    // ---------- noise ----------

    static float hash(int s, int i) {
        int h = s * 374761393 + i * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xffffff) / (float) 0x7fffff - 1f;
    }

    static float noise(int seed, float u) {
        int i = (int) Math.floor(u);
        float f = u - i;
        float a = hash(seed, i), b = hash(seed, i + 1);
        f = f * f * (3 - 2 * f);
        return a + (b - a) * f;
    }

    static int boilFrame(float t, float speed, int frames) {
        if (frames <= 1) return 0;
        return ((int) Math.floor(t * speed)) % frames;
    }

    // ---------- document ----------

    /** @param only when non-null, draws just that layer (thumbnails) */
    void render(Canvas c, Doc d, int frame, float t, boolean effects, boolean bg, Extra ex, Layer only) {
        fxOn = effects;
        if (bg) {
            paint.reset();
            paint.setColor(d.bg);
            c.drawRect(0, 0, d.w, d.h, paint);
        }
        // camera state for this frame, including handheld shake
        Camera3D.Key k = null;
        if (cam != null) {
            shot.lerp(cam, cam, 0);
            Camera3D.applyShake(shot, camFrame, d);
            k = shot;
        }
        for (int i = 0; i < d.layers.size(); i++) {
            Layer L = d.layers.get(i);
            if (only != null && L != only) continue;
            if (!L.visible && only == null) continue;
            boolean active = ex != null && ex.layer == L;
            if (active && ex.onion && d.frames > 1) drawOnion(c, d, L, frame, t);
            Cel cel = d.cel(L, frame);
            boolean hasLive = active && ex.live != null && ex.live.n > 0;
            if (cel.items.isEmpty() && !hasLive) continue;
            boolean camOn = k != null && only == null;
            float camAlpha = 1;
            boolean keyed = false;
            if (camOn) {
                camAlpha = cam3.layerMatrix(d, L, k, camM) * Camera3D.fade(L, camFrame);
                if (!L.keys.isEmpty()) {
                    Camera3D.layerAt(L.keys, camFrame, lk);
                    camAlpha *= Math.max(0, Math.min(1, lk.opacity));
                    if (!lk.identity()) {
                        RectF b = cel.bounds();
                        float ax = L.hasPivot ? L.pivotX : b.isEmpty() ? d.w / 2f : b.centerX();
                        float ay = L.hasPivot ? L.pivotY : b.isEmpty() ? d.h / 2f : b.centerY();
                        cam3.layerTransform(lk, ax, ay, keyM, Camera3D.focal(d));
                        keyed = true;
                    }
                }
                if (camAlpha <= 0.002f) continue; // the camera has passed this card, or it is faded out
                float b = Camera3D.blur(d, L, k);
                if (b >= 1.2f) {
                    drawBlurred(c, d, L, cel, t, effects, b, camAlpha, k, keyed);
                    continue;
                }
            }
            int save = c.save();
            if (camOn) c.concat(camM);
            if (keyed) c.concat(keyM);
            boolean freeze = active && ex.freezeActive;
            if (effects && !freeze && L.hasEffect() && effectMatrix(L, cel.bounds(), t, fx)) c.concat(fx);
            c.saveLayerAlpha(null, Math.round(L.opacity * camAlpha * 255), Canvas.ALL_SAVE_FLAG);
            Set<Item> sel = active ? ex.sel : null;
            drawCel(c, cel, t, sel, false);
            if (sel != null && !sel.isEmpty()) {
                c.save();
                c.translate(ex.dx, ex.dy);
                if (ex.rot != 0 || ex.sc != 1) {
                    c.rotate(ex.rot, ex.cx, ex.cy);
                    c.scale(ex.sc, ex.sc, ex.cx, ex.cy);
                }
                drawCel(c, cel, t, sel, true);
                c.restore();
            }
            if (hasLive) {
                drawStroke(c, ex.live, t);
                for (int m = 1; m <= 3; m++) {
                    if ((ex.sym & m) != m) continue;
                    c.save();
                    c.scale((m & 1) != 0 ? -1 : 1, (m & 2) != 0 ? -1 : 1, ex.symX, ex.symY);
                    drawStroke(c, ex.live, t);
                    c.restore();
                }
            }
            c.restoreToCount(save);
        }
        if (k != null && only == null && (k.flash > 0.002f || k.dip > 0.002f)) {
            paint.reset();
            if (k.dip > 0.002f) {
                paint.setColor(0xFF000000);
                paint.setAlpha(Math.round(Math.min(1, k.dip) * 255));
                c.drawRect(0, 0, d.w, d.h, paint);
            }
            if (k.flash > 0.002f) {
                paint.setColor(0xFFFFFFFF);
                paint.setAlpha(Math.round(Math.min(1, k.flash) * 255));
                c.drawRect(0, 0, d.w, d.h, paint);
            }
        }
    }

    /** Depth-of-field: render the layer small, blur it, and draw it back through the camera projection. */
    private void drawBlurred(Canvas c, Doc d, Layer L, Cel cel, float t, boolean effects, float blurPx, float alpha,
                             Camera3D.Key k, boolean keyed) {
        float s = Camera3D.scale(d, L, k) * (keyed ? Math.max(0.05f, lk.scale) : 1);
        float r = blurPx / Math.max(0.05f, s);
        float q = Math.max(0.04f, Math.min(1f, 2.5f / r));
        int tw = Math.max(1, Math.round(d.w * q)), th = Math.max(1, Math.round(d.h * q));
        if (blurBmp == null || blurBmp.getWidth() != tw || blurBmp.getHeight() != th) {
            if (blurBmp != null) blurBmp.recycle();
            blurBmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        }
        if (blurBuf == null || blurBuf.length < tw * th) blurBuf = new int[tw * th];
        blurBmp.eraseColor(0);
        Canvas bc = new Canvas(blurBmp);
        bc.scale(q, q);
        if (keyed) bc.concat(keyM);
        if (effects && L.hasEffect() && effectMatrix(L, cel.bounds(), t, fx)) bc.concat(fx);
        drawCel(bc, cel, t, null, false);
        Camera3D.blurBitmap(blurBmp, Math.max(1, Math.min(14, Math.round(r * q))), blurBuf);
        texM.set(camM);
        texM.preScale(1 / q, 1 / q);
        paint.reset();
        paint.setFilterBitmap(true);
        paint.setAntiAlias(true);
        paint.setAlpha(Math.round(L.opacity * alpha * 255));
        c.drawBitmap(blurBmp, texM, paint);
    }

    private void drawOnion(Canvas c, Doc d, Layer L, int frame, float t) {
        Cel cur = d.cel(L, frame);
        Cel prev = null, next = null;
        for (int f = frame - 1; f >= 0; f--) {
            Cel k = d.cel(L, f);
            if (k != cur) {
                prev = k;
                break;
            }
        }
        for (int f = frame + 1; f < d.frames; f++) {
            Cel k = d.cel(L, f);
            if (k != cur) {
                next = k;
                break;
            }
        }
        if (prev != null && !prev.items.isEmpty()) onionPass(c, prev, t, onionPrev);
        if (next != null && !next.items.isEmpty()) onionPass(c, next, t, onionNext);
    }

    private void onionPass(Canvas c, Cel cel, float t, PorterDuffColorFilter filter) {
        layerPaint.reset();
        layerPaint.setColorFilter(filter);
        layerPaint.setAlpha(64);
        int save = c.saveLayer(null, layerPaint, Canvas.ALL_SAVE_FLAG);
        drawCel(c, cel, t, null, false);
        c.restoreToCount(save);
    }

    /** Fills always sit under strokes of the same cel so line art stays on top of colour. */
    void drawCel(Canvas c, Cel cel, float t, Set<Item> sel, boolean onlySel) {
        drawItems(c, cel.items, t, sel, onlySel);
    }

    /**
     * Draws items in order. Eraser strokes and pictures are boundaries: an eraser only affects what
     * came before it, and a picture covers what came before it. Between two boundaries, fills go under
     * the line art so colour meets the (jittering) lines without gaps.
     */
    private void drawItems(Canvas c, ArrayList<Item> items, float t, Set<Item> sel, boolean onlySel) {
        int n = items.size(), start = 0;
        while (start <= n) {
            int end = start;
            while (end < n && !isBoundary(items.get(end))) end++;
            for (int pass = 0; pass < 2; pass++) {
                for (int i = start; i < end; i++) {
                    Item it = items.get(i);
                    if (!included(it, sel, onlySel)) continue;
                    if (pass == 0 && it instanceof Fill) drawFill(c, (Fill) it, t);
                    else if (pass == 1 && it instanceof Stroke) drawStroke(c, (Stroke) it, t);
                    else if (pass == 1 && it instanceof Group) drawGroup(c, (Group) it, t);
                }
            }
            if (end < n) {
                Item b = items.get(end);
                if (included(b, sel, onlySel)) {
                    if (b instanceof Picture) drawPicture(c, (Picture) b);
                    else drawStroke(c, (Stroke) b, t);
                }
            }
            start = end + 1;
        }
    }

    private static boolean isBoundary(Item it) {
        return it instanceof Picture || (it instanceof Stroke && ((Stroke) it).eraser);
    }

    private static boolean included(Item it, Set<Item> sel, boolean onlySel) {
        return sel == null ? !onlySel : sel.contains(it) == onlySel;
    }

    private final RectF picRect = new RectF();

    void drawPicture(Canvas c, Picture pc) {
        paint.reset();
        paint.setFilterBitmap(true);
        paint.setAntiAlias(true);
        picRect.set(pc.left, pc.top, pc.left + pc.w, pc.top + pc.h);
        if (pc.angle != 0) {
            c.save();
            c.rotate(pc.angle, picRect.centerX(), picRect.centerY());
            c.drawBitmap(pc.bmp, null, picRect, paint);
            c.restore();
        } else {
            c.drawBitmap(pc.bmp, null, picRect, paint);
        }
    }

    private void drawGroup(Canvas c, Group g, float t) {
        int save = c.save();
        if (fxOn && g.fx.hasEffect()) {
            Matrix m = new Matrix();
            if (effectMatrix(g.fx, g.bounds, t, m)) c.concat(m);
        }
        c.saveLayerAlpha(null, Math.round(g.fx.opacity * 255), Canvas.ALL_SAVE_FLAG);
        drawItems(c, g.items, t, null, false);
        c.restoreToCount(save);
    }

    // ---------- layer effects ----------

    static boolean effectMatrix(Layer L, RectF b, float t, Matrix m) {
        if (b.isEmpty()) return false;
        m.reset();
        float f = 0.25f + L.fxSpeed * 2.75f;
        float A = L.fxAmount, R = L.fxRotate;
        float cx = b.centerX(), cy = b.centerY(), base = b.bottom;
        if (L.hasPivot) {
            cx = L.pivotX;
            cy = L.pivotY;
            base = L.pivotY;
        }
        float H = Math.max(40, b.height()), W = Math.max(40, b.width());
        double ph = 2 * Math.PI * f * t;
        switch (L.effect) {
            case Layer.FX_BOUNCE: {
                float s = (float) Math.abs(Math.sin(ph / 2));
                float g = (float) Math.pow(1 - s, 6);
                float sq = g * A * 0.22f - s * A * 0.06f;
                m.postScale(1 + sq, 1 - sq, cx, base);
                m.postRotate((float) (R * 10 * Math.sin(ph)) * s, cx, base);
                m.postTranslate(0, -s * A * H * 0.3f);
                break;
            }
            case Layer.FX_SHAKE:
                m.postRotate((float) ((R * 22 + 3) * Math.sin(ph)), cx, base);
                m.postTranslate((float) (A * W * 0.05f * Math.sin(ph * 2.3)), (float) (A * H * 0.015f * Math.sin(ph * 3.1)));
                break;
            case Layer.FX_FLOAT:
                m.postRotate((float) (R * 10 * Math.sin(ph * 0.5 + 0.6)), cx, cy);
                m.postTranslate((float) (A * W * 0.05f * Math.sin(ph * 0.5)), (float) (A * H * 0.12f * Math.sin(ph)));
                break;
            case Layer.FX_SPIN: {
                float ang = (float) ((t * f * 360 * (0.15f + R)) % 360);
                m.postRotate(ang, cx, cy);
                m.postTranslate((float) (A * W * 0.04f * Math.cos(ph)), (float) (A * H * 0.04f * Math.sin(ph)));
                break;
            }
            default:
                break;
        }
        if (L.fxScale) {
            float sc = 1 + Math.max(A, 0.15f) * 0.2f * (float) Math.sin(ph + 0.8);
            m.postScale(sc, sc, cx, cy);
        }
        return true;
    }

    // ---------- items ----------

    void drawFill(Canvas c, Fill f, float t) {
        int k = boilOn ? boilFrame(t, f.speed, f.frames) : 0;
        float ox = 0, oy = 0;
        if (f.wobble > 0) {
            ox = f.wobble * .5f * hash(f.seed, k * 2);
            oy = f.wobble * .5f * hash(f.seed, k * 2 + 1);
        }
        paint.reset();
        paint.setFilterBitmap(true);
        paint.setColor(f.color);
        if (f.angle != 0) {
            c.save();
            c.rotate(f.angle, f.left + f.w / 2f, f.top + f.h / 2f);
            c.drawBitmap(f.mask, f.left + ox, f.top + oy, paint);
            c.restore();
        } else {
            c.drawBitmap(f.mask, f.left + ox, f.top + oy, paint);
        }
    }

    void drawStroke(Canvas c, Stroke s, float t) {
        if (s.n == 0) return;
        int k = boilOn ? boilFrame(t, s.speed, s.frames) : 0;
        Paint p = paint;
        p.reset();
        p.setAntiAlias(true);
        int a = Math.round(Color.alpha(s.color) * s.alpha);
        if (s.eraser) {
            p.setXfermode(erase);
            p.setColor(0xFF000000);
        } else {
            p.setColor(s.color);
        }
        p.setAlpha(a);
        int style = s.eraser ? Brush.MARKER : s.brush;
        if (boilOn && s.echo && s.frames > 1 && (Brush.doodle(style) || Brush.ribbon(style))) {
            // frame echo: the previous boil frame lingers faintly under the current one
            p.setStyle(Paint.Style.FILL);
            p.setAlpha(Math.round(a * 0.3f));
            c.drawPath(path(s, (k + s.frames - 1) % s.frames, 0), p);
            p.setAlpha(a);
        }
        switch (style) {
            case Brush.FELT:
            case Brush.STICKY:
            case Brush.TRIO:
            case Brush.INK:
            case Brush.PEN:
            case Brush.PAINT:
            case Brush.CALLIG:
            case Brush.SPRAY:
                p.setStyle(Paint.Style.FILL);
                c.drawPath(path(s, k, 0), p);
                break;
            case Brush.SOFT: {
                // airbrush: stacked passes from wide and faint to narrow, giving a soft falloff
                Path path = path(s, k, 0);
                for (int i = 0; i < 6; i++) {
                    line(p, Math.max(0.5f, s.size * (1f - i / 6f)));
                    p.setAlpha(Math.round(a * 0.28f));
                    c.drawPath(path, p);
                }
                break;
            }
            case Brush.CRAYON:
                line(p, Math.max(0.5f, s.size * 0.55f));
                p.setAlpha(Math.round(a * 0.7f));
                c.drawPath(path(s, k, 0), p);
                p.setStyle(Paint.Style.FILL);
                p.setAlpha(a);
                c.drawPath(path(s, k, 1), p);
                break;
            case Brush.PENCIL:
                line(p, s.size);
                c.drawPath(path(s, k, 0), p);
                p.setAlpha(Math.round(a * 0.55f));
                p.setStrokeWidth(Math.max(0.6f, s.size * 0.6f));
                c.drawPath(path(s, k, 1), p);
                break;
            case Brush.NEON: {
                Path path = path(s, k, 0);
                float fl = 0.85f + 0.15f * hash(s.seed, (int) (t * s.speed * 1.5f));
                line(p, s.size * 3.2f);
                p.setAlpha(Math.round(a * 0.10f * fl));
                c.drawPath(path, p);
                p.setStrokeWidth(s.size * 1.9f);
                p.setAlpha(Math.round(a * 0.25f * fl));
                c.drawPath(path, p);
                p.setStrokeWidth(s.size);
                p.setAlpha(Math.round(a * fl));
                c.drawPath(path, p);
                p.setColor(lighten(s.color));
                p.setAlpha(Math.round(a * 0.9f));
                p.setStrokeWidth(s.size * 0.38f);
                c.drawPath(path, p);
                break;
            }
            case Brush.FLOW: {
                line(p, s.size);
                float on = s.size * 2.2f + 2, off = s.size * 1.6f + 2;
                float ph = (t * s.speed * (s.size + 4) * 1.5f) % (on + off);
                p.setPathEffect(new DashPathEffect(new float[]{on, off}, (on + off) - ph));
                c.drawPath(path(s, k, 0), p);
                break;
            }
            case Brush.PULSE: {
                float w = s.size * (0.65f + 0.55f * (0.5f + 0.5f * (float) Math.sin(t * s.speed * 1.6f + (s.seed & 63) * 0.1f)));
                line(p, w);
                c.drawPath(path(s, k, 0), p);
                break;
            }
            case Brush.WAVE:
                wavePath(s, t);
                line(p, s.size);
                c.drawPath(tmp, p);
                break;
            case Brush.SPARKLE:
                sparkle(c, s, k, t, p, a);
                break;
            default: // MARKER, eraser
                line(p, s.size);
                c.drawPath(path(s, k, 0), p);
                break;
        }
    }

    private static void line(Paint p, float w) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(w);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    private static int lighten(int c) {
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        return Color.rgb(r + (255 - r) * 6 / 10, g + (255 - g) * 6 / 10, b + (255 - b) * 6 / 10);
    }

    private void ensure(int n) {
        if (bx.length >= n) return;
        int c = Math.max(n, bx.length * 2);
        bx = new float[c];
        by = new float[c];
        hw = new float[c];
        nx = new float[c];
        ny = new float[c];
        lx = new float[c];
        ly = new float[c];
        rx = new float[c];
        ry = new float[c];
    }

    private Path path(Stroke s, int k, int variant) {
        if (!useCache) {
            build(s, k, variant, scratch);
            return scratch;
        }
        int slots = Math.max(1, s.frames) * 2;
        if (s.cache == null || s.cache.length < slots) s.cache = new Path[slots];
        int idx = k * 2 + variant;
        Path pp = s.cache[idx];
        if (pp != null) return pp;
        pp = new Path();
        build(s, k, variant, pp);
        s.cache[idx] = pp;
        return pp;
    }

    private void displace(Stroke s, int k, int variant) {
        int n = s.n;
        ensure(n);
        float wob = s.wobble;
        int sd = s.seed + variant * 7777 + k * 1013;
        float lam = 16 + s.size * 1.3f;
        for (int i = 0; i < n; i++) {
            if (wob <= 0) {
                bx[i] = s.x[i];
                by[i] = s.y[i];
                continue;
            }
            float u = (s.arc[i] + s.arc0) / lam;
            bx[i] = s.x[i] + wob * noise(sd, u);
            by[i] = s.y[i] + wob * noise(sd + 4111, u + 17.3f);
        }
        if (variant == 1) { // pencil's second strand sits slightly off the first
            float off = Math.max(0.6f, s.size * 0.35f);
            for (int i = 0; i < n; i++) {
                bx[i] += off * noise(s.seed + 31, (s.arc[i] + s.arc0) / 40f);
                by[i] += off * noise(s.seed + 57, (s.arc[i] + s.arc0) / 40f);
            }
        }
    }

    private void build(Stroke s, int k, int variant, Path out) {
        int style = s.eraser ? Brush.MARKER : s.brush;
        if (Brush.doodle(style)) {
            strands(s, k, style, out);
            return;
        }
        displace(s, k, variant);
        int n = s.n;
        if (Brush.ribbon(style)) {
            ribbonWidths(s, style, n);
            outline(out, n);
        } else if (style == Brush.SPRAY) {
            spray(s, k, out);
        } else if (style == Brush.CRAYON && variant == 1) {
            crayonGrain(s, k, out);
        } else {
            center(out, bx, by, n);
        }
    }

    /**
     * Sketchy look from the reference: each strand is a tapered ribbon that jitters on its own,
     * a slow sway plus a fine "spacing"-sized roughness, so edges look torn and lines split apart.
     */
    private void strands(Stroke s, int k, int style, Path out) {
        out.reset();
        int n = s.n;
        ensure(n);
        int count = style == Brush.TRIO ? 3 : style == Brush.STICKY ? 2 : 1;
        float strandW = style == Brush.TRIO ? 0.62f : style == Brush.STICKY ? 0.75f : 1f;
        float total = s.length();
        float wob = s.wobble;
        float lamL = 30 + s.size * 2;
        float lamH = Math.max(1.5f, s.spacing * 2.2f);
        float taper = Math.max(3, s.size * 2);
        float spread = count == 1 ? 0 : s.size * 0.3f;
        for (int j = 0; j < count; j++) {
            int sd = s.seed + k * 1013 + j * 7777;
            float lane = (j - (count - 1) / 2f) * spread;
            for (int i = 0; i < n; i++) {
                int i0 = Math.max(0, i - 2), i1 = Math.min(n - 1, i + 2);
                float tx = s.x[i1] - s.x[i0], ty = s.y[i1] - s.y[i0];
                float l = (float) Math.hypot(tx, ty);
                float ux = l < 1e-4f ? 0 : -ty / l, uy = l < 1e-4f ? 1 : tx / l;
                float a = s.arc[i], ua = a + s.arc0;
                float uL = ua / lamL, uH = ua / lamH;
                float side = lane + s.size * 0.22f * noise(sd + 5, uL) * (count > 1 ? 1 : 0.4f);
                bx[i] = s.x[i] + wob * (0.55f * noise(sd, uL) + 0.22f * noise(sd + 11, uH)) + ux * side;
                by[i] = s.y[i] + wob * (0.55f * noise(sd + 4111, uL + 17.3f) + 0.22f * noise(sd + 13, uH + 5.1f)) + uy * side;
                float tp = Math.min(1f, Math.min(s.cutStart ? 1e9f : a, s.cutEnd ? 1e9f : total - a) / taper);
                float rough = 1 + 0.35f * noise(sd + 23, uH * 1.3f);
                hw[i] = Math.max(0.3f, s.size * 0.5f * strandW * (0.15f + 0.85f * (float) Math.sqrt(tp))
                        * (0.8f + 0.2f * s.p[i]) * rough);
            }
            if (count == 1) {
                outline(out, n);
            } else {
                outline(tmp, n);
                out.addPath(tmp);
            }
        }
    }

    /** Half widths for the outline brushes: pressure, tapered ends, and the calligraphy nib angle. */
    private void ribbonWidths(Stroke s, int style, int n) {
        float total = s.length();
        for (int i = 0; i < n; i++) {
            float pr = s.p[i];
            float ds = s.cutStart ? 1e9f : s.arc[i], de = s.cutEnd ? 1e9f : total - s.arc[i];
            float w;
            if (style == Brush.PEN) {
                float tp = Math.min(1f, Math.min(ds, de) / Math.max(2, s.size * 1.2f));
                w = s.size * 0.5f * (0.6f + 0.4f * pr) * (0.5f + 0.5f * (float) Math.sqrt(tp));
            } else if (style == Brush.PAINT) {
                float tp = Math.min(1f, Math.min(ds, de) / Math.max(6, s.size * 6));
                w = s.size * 0.5f * (0.1f + 0.9f * pr) * (float) Math.sqrt(tp);
            } else if (style == Brush.CALLIG) {
                int i0 = Math.max(0, i - 2), i1 = Math.min(n - 1, i + 2);
                double th = Math.atan2(s.y[i1] - s.y[i0], s.x[i1] - s.x[i0]);
                float nib = (float) Math.abs(Math.sin(th + Math.PI / 4)); // flat nib held at 45°
                float tp = Math.min(1f, Math.min(ds, de) / Math.max(2, s.size * 0.8f));
                w = s.size * 0.5f * (0.12f + 0.88f * nib) * (0.7f + 0.3f * pr) * (0.4f + 0.6f * tp);
            } else {
                float tp = Math.min(1f, Math.min(ds, de) / Math.max(4, s.size * 2.5f));
                w = s.size * 0.5f * (0.2f + 0.8f * pr) * (0.25f + 0.75f * (float) Math.sqrt(tp));
            }
            hw[i] = Math.max(0.35f, w);
        }
    }

    /** Fine dots around the crayon core: the grainy texture (re-scattered per frame when jitter is on). */
    private void crayonGrain(Stroke s, int k, Path out) {
        out.reset();
        int n = s.n;
        float total = s.length();
        float step = Math.max(1f, s.size * 0.16f);
        float r = s.size * 0.5f;
        float dot = Math.max(0.45f, s.size * 0.055f);
        int seg = 0, j = 0;
        for (float d = 0; d <= total; d += step, j++) {
            float px, py;
            if (n == 1) {
                px = bx[0];
                py = by[0];
            } else {
                while (seg < n - 2 && s.arc[seg + 1] < d) seg++;
                float a0 = s.arc[seg], a1 = s.arc[seg + 1];
                float f = a1 > a0 ? (d - a0) / (a1 - a0) : 0;
                px = bx[seg] + (bx[seg + 1] - bx[seg]) * f;
                py = by[seg] + (by[seg + 1] - by[seg]) * f;
            }
            for (int m = 0; m < 4; m++) {
                int h = s.seed * 31 + k * 7919 + j * 4 + m;
                double ang = hash(h, 1) * Math.PI;
                float rad = r * (float) Math.sqrt(Math.abs(hash(h, 2)));
                float dr = dot * (0.5f + Math.abs(hash(h, 3)));
                out.addCircle(px + (float) Math.cos(ang) * rad, py + (float) Math.sin(ang) * rad, dr, Path.Direction.CW);
            }
        }
    }

    private static void center(Path p, float[] x, float[] y, int n) {
        p.reset();
        p.moveTo(x[0], y[0]);
        if (n == 1) {
            p.lineTo(x[0] + 0.01f, y[0]);
            return;
        }
        smoothTo(p, x, y, n);
    }

    /** Quadratic smoothing through midpoints; assumes the path is already at point 0. */
    private static void smoothTo(Path p, float[] x, float[] y, int n) {
        for (int i = 1; i < n - 1; i++) {
            p.quadTo(x[i], y[i], (x[i] + x[i + 1]) * 0.5f, (y[i] + y[i + 1]) * 0.5f);
        }
        p.lineTo(x[n - 1], y[n - 1]);
    }

    private static void smoothBack(Path p, float[] x, float[] y, int n) {
        for (int i = n - 2; i > 0; i--) {
            p.quadTo(x[i], y[i], (x[i] + x[i - 1]) * 0.5f, (y[i] + y[i - 1]) * 0.5f);
        }
        p.lineTo(x[0], y[0]);
    }

    /** Variable-width ribbon with round caps as one closed contour. */
    private void outline(Path out, int n) {
        out.reset();
        if (n == 1) {
            out.addCircle(bx[0], by[0], hw[0], Path.Direction.CW);
            return;
        }
        for (int i = 0; i < n; i++) {
            int i0 = Math.max(0, i - 1), i1 = Math.min(n - 1, i + 1);
            float tx = bx[i1] - bx[i0], ty = by[i1] - by[i0];
            float l = (float) Math.hypot(tx, ty);
            if (l < 1e-4f) {
                tx = 1;
                ty = 0;
            } else {
                tx /= l;
                ty /= l;
            }
            nx[i] = -ty;
            ny[i] = tx;
            lx[i] = bx[i] + nx[i] * hw[i];
            ly[i] = by[i] + ny[i] * hw[i];
            rx[i] = bx[i] - nx[i] * hw[i];
            ry[i] = by[i] - ny[i] * hw[i];
        }
        out.moveTo(lx[0], ly[0]);
        smoothTo(out, lx, ly, n);
        int e = n - 1;
        double a0 = Math.atan2(ny[e], nx[e]);
        for (int j = 1; j <= 8; j++) {
            double a = a0 - Math.PI * j / 8;
            out.lineTo(bx[e] + (float) Math.cos(a) * hw[e], by[e] + (float) Math.sin(a) * hw[e]);
        }
        smoothBack(out, rx, ry, n);
        double a1 = Math.atan2(-ny[0], -nx[0]);
        for (int j = 1; j < 8; j++) {
            double a = a1 - Math.PI * j / 8;
            out.lineTo(bx[0] + (float) Math.cos(a) * hw[0], by[0] + (float) Math.sin(a) * hw[0]);
        }
        out.close();
    }

    private void spray(Stroke s, int k, Path out) {
        out.reset();
        int n = s.n;
        float total = s.length();
        float step = Math.max(1.5f, s.size * 0.22f);
        float r = s.size * 0.5f;
        float dot = Math.max(0.6f, s.size * 0.045f);
        int seg = 0, j = 0;
        for (float d = 0; d <= total; d += step, j++) {
            float px, py;
            if (n == 1) {
                px = bx[0];
                py = by[0];
            } else {
                while (seg < n - 2 && s.arc[seg + 1] < d) seg++;
                float a0 = s.arc[seg], a1 = s.arc[seg + 1];
                float f = a1 > a0 ? (d - a0) / (a1 - a0) : 0;
                px = bx[seg] + (bx[seg + 1] - bx[seg]) * f;
                py = by[seg] + (by[seg + 1] - by[seg]) * f;
            }
            for (int m = 0; m < 3; m++) {
                int h = s.seed * 31 + k * 7919 + j * 3 + m;
                double ang = hash(h, 1) * Math.PI;
                float rad = r * (float) Math.sqrt(Math.abs(hash(h, 2)));
                float dr = dot * (0.6f + 0.8f * Math.abs(hash(h, 3)));
                out.addCircle(px + (float) Math.cos(ang) * rad, py + (float) Math.sin(ang) * rad, dr, Path.Direction.CW);
            }
        }
    }

    private void sparkle(Canvas c, Stroke s, int k, float t, Paint p, int a) {
        line(p, Math.max(1, s.size * 0.12f));
        p.setAlpha(Math.round(a * 0.35f));
        c.drawPath(path(s, k, 0), p);
        p.setStyle(Paint.Style.FILL);
        p.setAlpha(a);
        int n = s.n;
        float total = s.length();
        float sp = Math.max(6, s.size * 1.5f);
        float lam = 16 + s.size * 1.3f;
        int sd = s.seed + k * 1013;
        int seg = 0, j = 0;
        for (float d = 0; d <= total; d += sp, j++) {
            float px, py;
            if (n == 1) {
                px = s.x[0];
                py = s.y[0];
            } else {
                while (seg < n - 2 && s.arc[seg + 1] < d) seg++;
                float a0 = s.arc[seg], a1 = s.arc[seg + 1];
                float f = a1 > a0 ? (d - a0) / (a1 - a0) : 0;
                px = s.x[seg] + (s.x[seg + 1] - s.x[seg]) * f;
                py = s.y[seg] + (s.y[seg + 1] - s.y[seg]) * f;
            }
            if (s.wobble > 0) {
                px += s.wobble * noise(sd, d / lam);
                py += s.wobble * noise(sd + 4111, d / lam + 17.3f);
            }
            float ph = hash(s.seed, j) * 6.283f;
            float tw = 0.5f + 0.5f * (float) Math.sin(t * s.speed * 1.4f + ph);
            float rad = s.size * 0.5f * (0.25f + 0.75f * tw);
            c.save();
            c.translate(px, py);
            c.rotate(t * s.speed * 8 + j * 37);
            c.scale(rad, rad);
            c.drawPath(star, p);
            c.restore();
        }
    }

    private void wavePath(Stroke s, float t) {
        int n = s.n;
        ensure(n);
        float amp = s.size * 0.7f + s.wobble * 1.2f;
        float lam = s.size * 3 + 18;
        float w = t * s.speed * 1.2f;
        for (int i = 0; i < n; i++) {
            int i0 = Math.max(0, i - 2), i1 = Math.min(n - 1, i + 2);
            float tx = s.x[i1] - s.x[i0], ty = s.y[i1] - s.y[i0];
            float l = (float) Math.hypot(tx, ty);
            float ux = l < 1e-4f ? 0 : -ty / l, uy = l < 1e-4f ? 1 : tx / l;
            float off = amp * (float) Math.sin(s.arc[i] / lam * 6.2832f - w);
            bx[i] = s.x[i] + ux * off;
            by[i] = s.y[i] + uy * off;
        }
        center(tmp, bx, by, n);
    }
}
