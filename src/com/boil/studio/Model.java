package com.boil.studio;

import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.RectF;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;

/** Brush catalogue: every brush is vector-based and re-rendered every frame, so it can be animated. */
final class Brush {
    static final int INK = 0, FELT = 1, STICKY = 2, PENCIL = 3, MARKER = 4, NEON = 5,
            FLOW = 6, SPARKLE = 7, PULSE = 8, WAVE = 9, SPRAY = 10, TRIO = 11,
            PEN = 12, PAINT = 13, SOFT = 14, CALLIG = 15, CRAYON = 16, COUNT = 17;

    static final String[] NAME = {"Чернила", "Фломастер", "Липкие чернила", "Карандаш", "Маркер",
            "Неон", "Поток", "Искры", "Пульс", "Волна", "Спрей", "Трио",
            "Ручка", "Кисть", "Мягкая кисть", "Каллиграфия", "Мелок"};
    static final String[] HINT = {"нажим · кипение", "рваный край", "две живые пряди", "двойной штрих",
            "полупрозрачный", "свечение", "бегущий пунктир", "мерцающие звёзды", "дышащая толщина",
            "бегущая волна", "живое распыление", "три пряди, как в скетче",
            "ровная чистая линия", "нажим и тонкие концы", "аэрограф, мягкий край", "плоское перо под 45°", "зернистая текстура"};

    static final float[] SIZE = {8, 6, 7, 3, 24, 8, 8, 12, 8, 6, 22, 9, 5, 12, 40, 14, 12};
    static final float[] ALPHA = {1, 1, 1, .9f, .5f, 1, 1, 1, 1, 1, 1, 1, 1, 1, .6f, 1, .9f};
    static final float[] WOBBLE = {2.5f, 2.5f, 3.5f, 1.8f, 2f, 2f, 1.5f, 1.5f, 2f, 2.5f, 1f, 3.5f, 2f, 2f, 2f, 2f, 2f};
    /** Boil changes per second; the UI shows this divided by 4 (so 10.8 reads as 2.7, like the reference). */
    static final float[] SPEED = {8, 10.8f, 10.8f, 10, 6, 8, 6, 4, 3, 3, 10, 10.8f, 8, 8, 8, 8, 8};
    static final int[] FRAMES = {3, 4, 4, 4, 3, 3, 3, 3, 3, 3, 4, 4, 3, 3, 3, 3, 3};
    static final float[] STAB = {.25f, .16f, .16f, .15f, .3f, .3f, .3f, .2f, .3f, .3f, .1f, .16f, .3f, .3f, .2f, .35f, .15f};
    static final float[] SPACING = {6, 3, 3, 6, 6, 6, 6, 6, 6, 6, 6, 3, 6, 6, 6, 6, 6};
    /** Whether line jitter is on by default; the classic brushes start still. */
    static final boolean[] BOIL = {true, true, true, true, true, true, true, true, true, true, true, true,
            false, false, false, false, false};

    /** Brush-panel categories, shown as a rail of icons like in the reference app. */
    static final String[] CAT_NAME = {"Каракули", "Обычные", "Перо", "Эффекты"};
    static final String[] CAT_ICON = {"brush", "tip", "edit", "effect"};
    static final int[][] CAT = {{TRIO, STICKY, FELT}, {PEN, PAINT, SOFT, CALLIG, CRAYON}, {INK, PENCIL, MARKER},
            {NEON, FLOW, SPARKLE, PULSE, WAVE, SPRAY}};

    /** Doodle brushes are drawn as independent jittering strands with tapered ends. */
    static boolean doodle(int b) {
        return b == FELT || b == STICKY || b == TRIO;
    }

    /** Brushes drawn as one variable-width filled outline. */
    static boolean ribbon(int b) {
        return b == INK || b == PEN || b == PAINT || b == CALLIG;
    }

    static int category(int b) {
        for (int c = 0; c < CAT.length; c++) for (int x : CAT[c]) if (x == b) return c;
        return 0;
    }
}

final class BrushSettings {
    float size, alpha, wobble, speed, stabilize, spacing = 3;
    int frames;
    boolean echo;
    /** Line jitter on or off for this brush (the wobble amount is kept for when it is turned back on). */
    boolean boil = true;

    static BrushSettings defaults(int b) {
        BrushSettings s = new BrushSettings();
        s.size = Brush.SIZE[b];
        s.alpha = Brush.ALPHA[b];
        s.wobble = Brush.WOBBLE[b];
        s.speed = Brush.SPEED[b];
        s.frames = Brush.FRAMES[b];
        s.stabilize = Brush.STAB[b];
        s.spacing = Brush.SPACING[b];
        s.boil = Brush.BOIL[b];
        return s;
    }

    static BrushSettings eraser() {
        BrushSettings s = new BrushSettings();
        s.size = 28;
        s.alpha = 1;
        s.wobble = 0;
        s.speed = 8;
        s.frames = 3;
        s.stabilize = .2f;
        s.boil = false;
        return s;
    }

    String encode() {
        return size + "," + alpha + "," + wobble + "," + speed + "," + frames + "," + stabilize + "," + spacing + "," + echo + "," + boil;
    }

    boolean decode(String s) {
        if (s == null) return false;
        String[] v = s.split(",");
        if (v.length != 6 && v.length != 8 && v.length != 9) return false;
        try {
            size = Float.parseFloat(v[0]);
            alpha = Float.parseFloat(v[1]);
            wobble = Float.parseFloat(v[2]);
            speed = Float.parseFloat(v[3]);
            frames = Integer.parseInt(v[4]);
            stabilize = Float.parseFloat(v[5]);
            if (v.length >= 8) {
                spacing = Float.parseFloat(v[6]);
                echo = Boolean.parseBoolean(v[7]);
            }
            if (v.length >= 9) boil = Boolean.parseBoolean(v[8]);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}

/** Anything that lives in a cel. Items are immutable once committed, so snapshots can share them. */
abstract class Item {
    final RectF bounds = new RectF();

    abstract Item moved(float dx, float dy);

    /** Copy scaled by s around (cx, cy). */
    abstract Item scaled(float s, float cx, float cy);

    /** Copy rotated by deg degrees around (cx, cy). */
    abstract Item rotated(float deg, float cx, float cy);

    /** Mirror copy: horizontal flips left-right across x = cx, otherwise top-bottom across y = cy. */
    abstract Item flipped(boolean horizontal, float cx, float cy);

    /** Bounds of a w×h box at (left, top) turned by deg around its own centre. */
    static void boxBounds(RectF out, float left, float top, float w, float h, float deg) {
        float cx = left + w / 2, cy = top + h / 2;
        double r = Math.toRadians(deg);
        float c = (float) Math.abs(Math.cos(r)), s = (float) Math.abs(Math.sin(r));
        float hw = (w * c + h * s) / 2, hh = (w * s + h * c) / 2;
        out.set(cx - hw, cy - hh, cx + hw, cy + hh);
    }

    /** (x, y) turned by deg around (cx, cy), written into out. */
    static void turn(float[] out, float x, float y, float deg, float cx, float cy) {
        double r = Math.toRadians(deg);
        float c = (float) Math.cos(r), s = (float) Math.sin(r);
        float dx = x - cx, dy = y - cy;
        out[0] = cx + dx * c - dy * s;
        out[1] = cy + dx * s + dy * c;
    }
}

/** A bitmap image placed into a cel (photos imported from the device). */
final class Picture extends Item {
    final Bitmap bmp;
    /** Encoded image bytes, kept for saving without re-encoding. */
    final byte[] encoded;
    final float left, top, w, h;
    /** Rotation in degrees around the picture's own centre. */
    final float angle;

    Picture(Bitmap bmp, byte[] encoded, float left, float top, float w, float h) {
        this(bmp, encoded, left, top, w, h, 0);
    }

    Picture(Bitmap bmp, byte[] encoded, float left, float top, float w, float h, float angle) {
        this.bmp = bmp;
        this.encoded = encoded;
        this.left = left;
        this.top = top;
        this.w = w;
        this.h = h;
        this.angle = angle;
        boxBounds(bounds, left, top, w, h, angle);
    }

    @Override
    Item moved(float dx, float dy) {
        return new Picture(bmp, encoded, left + dx, top + dy, w, h, angle);
    }

    @Override
    Item scaled(float s, float cx, float cy) {
        return new Picture(bmp, encoded, cx + (left - cx) * s, cy + (top - cy) * s, w * s, h * s, angle);
    }

    @Override
    Item rotated(float deg, float cx, float cy) {
        float[] c = new float[2];
        turn(c, left + w / 2, top + h / 2, deg, cx, cy);
        return new Picture(bmp, encoded, c[0] - w / 2, c[1] - h / 2, w, h, angle + deg);
    }

    @Override
    Item flipped(boolean horizontal, float cx, float cy) {
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(horizontal ? -1 : 1, horizontal ? 1 : -1);
        Bitmap b = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        float ncx = left + w / 2, ncy = top + h / 2;
        if (horizontal) ncx = 2 * cx - ncx;
        else ncy = 2 * cy - ncy;
        return new Picture(b, Images.encode(b), ncx - w / 2, ncy - h / 2, w, h, -angle);
    }
}

/**
 * Items from a merged layer that keep that layer's look: drawn in their own isolated pass with the
 * layer's opacity and animated effect, so its eraser strokes and fills only touch its own content.
 */
final class Group extends Item {
    final ArrayList<Item> items;
    /** Parameters only (opacity and effect); its cels are unused. */
    final Layer fx;

    Group(ArrayList<Item> items, Layer fx) {
        this.items = items;
        this.fx = fx;
        for (Item it : items) {
            if (it instanceof Stroke && ((Stroke) it).eraser) continue;
            bounds.union(it.bounds);
        }
    }

    @Override
    Item moved(float dx, float dy) {
        ArrayList<Item> m = new ArrayList<>();
        for (Item it : items) m.add(it.moved(dx, dy));
        return new Group(m, fx);
    }

    @Override
    Item scaled(float s, float cx, float cy) {
        ArrayList<Item> m = new ArrayList<>();
        for (Item it : items) m.add(it.scaled(s, cx, cy));
        return new Group(m, fx);
    }

    @Override
    Item rotated(float deg, float cx, float cy) {
        ArrayList<Item> m = new ArrayList<>();
        for (Item it : items) m.add(it.rotated(deg, cx, cy));
        return new Group(m, fx);
    }

    @Override
    Item flipped(boolean horizontal, float cx, float cy) {
        ArrayList<Item> m = new ArrayList<>();
        for (Item it : items) m.add(it.flipped(horizontal, cx, cy));
        return new Group(m, fx);
    }
}

final class Stroke extends Item {
    int brush, color, frames, seed;
    float size, alpha, wobble, speed, spacing = 3;
    boolean eraser, echo;
    float[] x = new float[32], y = new float[32], p = new float[32], arc = new float[32];
    int n;
    /** Renderer-owned path cache, one slot per boil frame and variant. */
    Path[] cache;
    private float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
    /**
     * For a piece cut out of a longer stroke: arc length before this piece (keeps the jitter
     * pattern identical) and whether each end is a cut (no taper there, so the seam is invisible).
     */
    float arc0;
    boolean cutStart, cutEnd;

    void clearPoints() {
        n = 0;
        minX = minY = Float.MAX_VALUE;
        maxX = maxY = -Float.MAX_VALUE;
        bounds.setEmpty();
        cache = null;
    }

    /** Splits into runs of points with the same inside flag; returns pieces, marking which are inside. */
    ArrayList<Stroke> split(boolean[] inside, ArrayList<Boolean> pieceInside) {
        ArrayList<Stroke> out = new ArrayList<>();
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && inside[j + 1] == inside[i]) j++;
            // pieces overlap by one point so the line stays continuous across the cut
            int from = i > 0 ? i - 1 : i;
            Stroke s = copyStyle();
            s.arc0 = arc0 + arc[from];
            s.cutStart = from > 0 || cutStart;
            s.cutEnd = j < n - 1 || cutEnd;
            for (int k = from; k <= j; k++) s.add(x[k], y[k], p[k]);
            out.add(s);
            pieceInside.add(inside[i]);
            i = j + 1;
        }
        return out;
    }

    void add(float px, float py, float pp) {
        if (n == x.length) {
            int c = n * 2;
            x = Arrays.copyOf(x, c);
            y = Arrays.copyOf(y, c);
            p = Arrays.copyOf(p, c);
            arc = Arrays.copyOf(arc, c);
        }
        x[n] = px;
        y[n] = py;
        p[n] = pp;
        arc[n] = n == 0 ? 0 : arc[n - 1] + (float) Math.hypot(px - x[n - 1], py - y[n - 1]);
        n++;
        minX = Math.min(minX, px);
        minY = Math.min(minY, py);
        maxX = Math.max(maxX, px);
        maxY = Math.max(maxY, py);
        float pad = pad();
        bounds.set(minX - pad, minY - pad, maxX + pad, maxY + pad);
        cache = null;
    }

    float pad() {
        float w = size;
        if (brush == Brush.NEON) w *= 1.8f;
        if (brush == Brush.PULSE) w *= 1.3f;
        float extra = brush == Brush.WAVE ? size + wobble * 2 + 6 : 0;
        return w * 0.6f + wobble * 1.5f + extra + 2;
    }

    float length() {
        return n == 0 ? 0 : arc[n - 1];
    }

    Stroke copyStyle() {
        Stroke s = new Stroke();
        s.brush = brush;
        s.color = color;
        s.frames = frames;
        s.seed = seed;
        s.size = size;
        s.alpha = alpha;
        s.wobble = wobble;
        s.speed = speed;
        s.eraser = eraser;
        s.spacing = spacing;
        s.echo = echo;
        s.arc0 = arc0;
        s.cutStart = cutStart;
        s.cutEnd = cutEnd;
        return s;
    }

    @Override
    Item moved(float dx, float dy) {
        Stroke s = copyStyle();
        for (int i = 0; i < n; i++) s.add(x[i] + dx, y[i] + dy, p[i]);
        return s;
    }

    @Override
    Item scaled(float sc, float cx, float cy) {
        Stroke s = copyStyle();
        s.size = Math.max(0.3f, size * sc);
        s.arc0 = arc0 * sc;
        for (int i = 0; i < n; i++) s.add(cx + (x[i] - cx) * sc, cy + (y[i] - cy) * sc, p[i]);
        return s;
    }

    @Override
    Item flipped(boolean horizontal, float cx, float cy) {
        Stroke s = copyStyle();
        for (int i = 0; i < n; i++) s.add(horizontal ? 2 * cx - x[i] : x[i], horizontal ? y[i] : 2 * cy - y[i], p[i]);
        return s;
    }

    @Override
    Item rotated(float deg, float cx, float cy) {
        Stroke s = copyStyle();
        float[] q = new float[2];
        for (int i = 0; i < n; i++) {
            turn(q, x[i], y[i], deg, cx, cy);
            s.add(q[0], q[1], p[i]);
        }
        return s;
    }
}

/** Bucket fill: a binary mask cropped to its bounds, tinted with a colour when drawn. */
final class Fill extends Item {
    final Bitmap mask;
    final byte[] data;
    final int w, h;
    final float left, top;
    int color, frames, seed;
    float wobble, speed;
    /** Rotation in degrees around the mask's own centre. */
    float angle;

    Fill(byte[] data, int w, int h, float left, float top) {
        this(makeMask(data, w, h), data, w, h, left, top);
    }

    private Fill(Bitmap mask, byte[] data, int w, int h, float left, float top) {
        this.mask = mask;
        this.data = data;
        this.w = w;
        this.h = h;
        this.left = left;
        this.top = top;
        bounds.set(left, top, left + w, top + h);
    }

    static Bitmap makeMask(byte[] d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        int rb = b.getRowBytes();
        if (rb == w) {
            b.copyPixelsFromBuffer(ByteBuffer.wrap(d));
        } else {
            byte[] pad = new byte[rb * h];
            for (int r = 0; r < h; r++) System.arraycopy(d, r * w, pad, r * rb, w);
            b.copyPixelsFromBuffer(ByteBuffer.wrap(pad));
        }
        return b;
    }

    @Override
    Item moved(float dx, float dy) {
        Fill f = new Fill(mask, data, w, h, left + dx, top + dy);
        return copyLook(f);
    }

    @Override
    Item scaled(float s, float cx, float cy) {
        int nw = Math.max(1, Math.round(w * s)), nh = Math.max(1, Math.round(h * s));
        byte[] d = new byte[nw * nh];
        for (int y = 0; y < nh; y++) {
            int sy = Math.min(h - 1, (int) (y / s));
            for (int x = 0; x < nw; x++) d[y * nw + x] = data[sy * w + Math.min(w - 1, (int) (x / s))];
        }
        return copyLook(new Fill(d, nw, nh, cx + (left - cx) * s, cy + (top - cy) * s));
    }

    @Override
    Item rotated(float deg, float cx, float cy) {
        float[] c = new float[2];
        turn(c, left + w / 2f, top + h / 2f, deg, cx, cy);
        Fill f = copyLook(new Fill(mask, data, w, h, c[0] - w / 2f, c[1] - h / 2f));
        f.setAngle(angle + deg);
        return f;
    }

    @Override
    Item flipped(boolean horizontal, float cx, float cy) {
        byte[] d = new byte[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sx = horizontal ? w - 1 - x : x, sy = horizontal ? y : h - 1 - y;
                d[y * w + x] = data[sy * w + sx];
            }
        }
        float ncx = left + w / 2f, ncy = top + h / 2f;
        if (horizontal) ncx = 2 * cx - ncx;
        else ncy = 2 * cy - ncy;
        Fill f = copyLook(new Fill(d, w, h, ncx - w / 2f, ncy - h / 2f));
        f.setAngle(-angle);
        return f;
    }

    /** Same fill with a different mask of the same size (used when the lasso cuts a fill). */
    Fill withData(byte[] d) {
        return copyLook(new Fill(d, w, h, left, top));
    }

    void setAngle(float a) {
        angle = a;
        boxBounds(bounds, left, top, w, h, a);
    }

    private Fill copyLook(Fill f) {
        f.setAngle(angle);
        f.color = color;
        f.frames = frames;
        f.seed = seed;
        f.wobble = wobble;
        f.speed = speed;
        return f;
    }
}

final class Cel {
    final ArrayList<Item> items = new ArrayList<>();
    private final RectF b = new RectF();
    private boolean dirty = true;

    void touch() {
        dirty = true;
    }

    RectF bounds() {
        if (dirty) {
            b.setEmpty();
            for (Item it : items) {
                if (it instanceof Stroke && ((Stroke) it).eraser) continue;
                b.union(it.bounds);
            }
            dirty = false;
        }
        return b;
    }

    Cel copy() {
        Cel c = new Cel();
        c.items.addAll(items);
        return c;
    }
}

final class Layer {
    static final int FX_NONE = 0, FX_BOUNCE = 1, FX_SHAKE = 2, FX_FLOAT = 3, FX_SPIN = 4;
    static final String[] FX_NAME = {"Нет", "Прыжок", "Тряска", "Парение", "Вращение"};

    String name;
    boolean visible = true;
    float opacity = 1f;
    int effect;
    boolean fxScale;
    float fxSpeed = .3f, fxAmount = .5f, fxRotate = .4f;
    /** Base point (doc coordinates) for layer effects and lasso transforms; unset means automatic. */
    boolean hasPivot;
    float pivotX, pivotY;
    /** One entry per timeline frame; null means "hold the previous drawing". Index 0 is never null. */
    final ArrayList<Cel> cels = new ArrayList<>();

    Layer(String name, int frames) {
        this.name = name;
        cels.add(new Cel());
        for (int i = 1; i < frames; i++) cels.add(null);
    }

    private Layer() {
    }

    Layer params() {
        Layer l = new Layer();
        l.name = name;
        l.opacity = opacity;
        l.effect = effect;
        l.fxScale = fxScale;
        l.fxSpeed = fxSpeed;
        l.fxAmount = fxAmount;
        l.fxRotate = fxRotate;
        return l;
    }

    boolean hasEffect() {
        return effect != FX_NONE || fxScale;
    }

    Layer copy() {
        Layer l = new Layer();
        l.name = name;
        l.visible = visible;
        l.opacity = opacity;
        l.effect = effect;
        l.fxScale = fxScale;
        l.fxSpeed = fxSpeed;
        l.fxAmount = fxAmount;
        l.fxRotate = fxRotate;
        l.hasPivot = hasPivot;
        l.pivotX = pivotX;
        l.pivotY = pivotY;
        for (Cel c : cels) l.cels.add(c == null ? null : c.copy());
        return l;
    }

    static Layer empty() {
        return new Layer();
    }
}

final class Doc {
    static final int MAGIC = 0x424F494C, VERSION = 6;

    String name = "Без названия";
    int w, h, bg = 0xFFFFFFFF, fps = 8, frames = 1;
    /** Plays the line jitter; when off every stroke shows its first boil frame. */
    boolean boil = true;
    ArrayList<Layer> layers = new ArrayList<>();

    Doc(int w, int h) {
        this.w = w;
        this.h = h;
        layers.add(new Layer("Слой 1", 1));
    }

    private Doc() {
    }

    /** Structural copy that shares the immutable items; safe to hand to a background exporter. */
    Doc copy() {
        Doc d = new Doc();
        d.name = name;
        d.w = w;
        d.h = h;
        d.bg = bg;
        d.fps = fps;
        d.frames = frames;
        d.boil = boil;
        for (Layer l : layers) d.layers.add(l.copy());
        return d;
    }

    /** The drawing shown for a layer at a frame, following holds back to the last key. */
    Cel cel(Layer l, int f) {
        return l.cels.get(keyIndex(l, f));
    }

    int keyIndex(Layer l, int f) {
        for (int i = Math.min(f, l.cels.size() - 1); i >= 0; i--) {
            if (l.cels.get(i) != null) return i;
        }
        return 0;
    }

    void insertFrame(int at, Layer active, boolean duplicate) {
        Cel src = cel(active, at - 1);
        for (Layer l : layers) l.cels.add(at, null);
        active.cels.set(at, duplicate ? src.copy() : new Cel());
        frames++;
    }

    void deleteFrame(int at) {
        if (frames <= 1) return;
        for (Layer l : layers) {
            Cel removed = l.cels.remove(at);
            if (at == 0 && l.cels.get(0) == null) l.cels.set(0, removed != null ? removed : new Cel());
        }
        frames--;
    }

    /** Why the layer at index i can't be merged into the one below, or null if it can. */
    String mergeProblem(int i) {
        if (i <= 0) return "Под этим слоем нет другого слоя";
        if (!layers.get(i).visible || !layers.get(i - 1).visible) return "Сначала включите видимость обоих слоёв";
        return null;
    }

    /**
     * Merges layer i into the layer below, across every timeline frame. The result looks the same as
     * before: anything that depends on per-layer settings is wrapped into a Group carrying them.
     */
    void mergeDown(int i) {
        Layer up = layers.get(i), lo = layers.get(i - 1);
        boolean wrapLo = lo.opacity < 0.999f;
        boolean wrapUp = up.opacity < 0.999f || hasEraserOrFill(up);
        java.util.IdentityHashMap<Cel, ArrayList<Item>> memoLo = new java.util.IdentityHashMap<>(), memoUp = new java.util.IdentityHashMap<>();
        ArrayList<Cel> out = new ArrayList<>();
        for (int f = 0; f < frames; f++) {
            boolean key = f == 0 || (f < up.cels.size() && up.cels.get(f) != null) || (f < lo.cels.size() && lo.cels.get(f) != null);
            if (!key) {
                out.add(null);
                continue;
            }
            Cel c = new Cel();
            c.items.addAll(part(cel(lo, f), lo, wrapLo, memoLo));
            c.items.addAll(part(cel(up, f), up, wrapUp, memoUp));
            out.add(c);
        }
        lo.cels.clear();
        lo.cels.addAll(out);
        if (wrapLo) lo.opacity = 1;
        // the merged layer moves as one piece: keep the lower effect, or take the upper one
        if (!lo.hasEffect() && up.hasEffect()) adoptEffect(lo, up);
        if (!lo.hasPivot && up.hasPivot) {
            lo.hasPivot = true;
            lo.pivotX = up.pivotX;
            lo.pivotY = up.pivotY;
        }
        layers.remove(i);
    }

    private static ArrayList<Item> part(Cel c, Layer l, boolean wrap, java.util.IdentityHashMap<Cel, ArrayList<Item>> memo) {
        if (!wrap || c.items.isEmpty()) return c.items;
        ArrayList<Item> r = memo.get(c);
        if (r == null) {
            r = new ArrayList<>();
            Layer fx = l.params();
            fx.effect = Layer.FX_NONE;
            fx.fxScale = false;
            r.add(new Group(new ArrayList<>(c.items), fx));
            memo.put(c, r);
        }
        return r;
    }

    private static void adoptEffect(Layer to, Layer from) {
        to.effect = from.effect;
        to.fxScale = from.fxScale;
        to.fxSpeed = from.fxSpeed;
        to.fxAmount = from.fxAmount;
        to.fxRotate = from.fxRotate;
    }

    /** Layers merged by v1.3 kept effects on their parts; move such effects onto the whole layer. */
    private void liftGroupEffects() {
        for (Layer l : layers) {
            Layer found = null;
            for (Cel c : l.cels) {
                if (c == null) continue;
                for (Item it : c.items) {
                    if (!(it instanceof Group)) continue;
                    Layer fx = ((Group) it).fx;
                    if (!fx.hasEffect()) continue;
                    if (found == null) found = fx.params();
                    fx.effect = Layer.FX_NONE;
                    fx.fxScale = false;
                }
            }
            if (found != null && !l.hasEffect()) adoptEffect(l, found);
        }
    }

    private static boolean hasEraserOrFill(Layer l) {
        for (Cel c : l.cels) {
            if (c == null) continue;
            for (Item it : c.items) {
                if (it instanceof Fill || it instanceof Picture || (it instanceof Stroke && ((Stroke) it).eraser)) return true;
            }
        }
        return false;
    }

    String uniqueLayerName() {
        for (int i = layers.size() + 1; ; i++) {
            String n = "Слой " + i;
            boolean used = false;
            for (Layer l : layers) if (n.equals(l.name)) used = true;
            if (!used) return n;
        }
    }

    // ---------- serialization ----------

    void write(DataOutputStream o) throws IOException {
        o.writeInt(MAGIC);
        o.writeInt(VERSION);
        o.writeUTF(name);
        o.writeInt(w);
        o.writeInt(h);
        o.writeInt(bg);
        o.writeInt(fps);
        o.writeInt(frames);
        o.writeInt(layers.size());
        for (Layer l : layers) {
            o.writeUTF(l.name);
            o.writeBoolean(l.visible);
            o.writeFloat(l.opacity);
            o.writeInt(l.effect);
            o.writeBoolean(l.fxScale);
            o.writeFloat(l.fxSpeed);
            o.writeFloat(l.fxAmount);
            o.writeFloat(l.fxRotate);
            o.writeBoolean(l.hasPivot);
            o.writeFloat(l.pivotX);
            o.writeFloat(l.pivotY);
            for (int f = 0; f < frames; f++) {
                Cel c = f < l.cels.size() ? l.cels.get(f) : null;
                if (c == null) {
                    o.writeByte(0);
                    continue;
                }
                o.writeByte(1);
                o.writeInt(c.items.size());
                for (Item it : c.items) {
                    writeItem(o, it);
                }
            }
        }
        writeTail(o, this);
    }

    private static void writeTail(DataOutputStream o, Doc d) throws IOException {
        o.writeBoolean(d.boil);
    }

    private static void writeItem(DataOutputStream o, Item it) throws IOException {
        if (it instanceof Stroke) {
            writeStroke(o, (Stroke) it);
        } else if (it instanceof Picture) {
            Picture pc = (Picture) it;
            o.writeByte(4);
            o.writeFloat(pc.left);
            o.writeFloat(pc.top);
            o.writeFloat(pc.w);
            o.writeFloat(pc.h);
            o.writeInt(pc.encoded.length);
            o.write(pc.encoded);
            o.writeFloat(pc.angle);
        } else if (it instanceof Fill) {
            writeFill(o, (Fill) it);
        } else {
            Group g = (Group) it;
            o.writeByte(3);
            o.writeFloat(g.fx.opacity);
            o.writeInt(g.fx.effect);
            o.writeBoolean(g.fx.fxScale);
            o.writeFloat(g.fx.fxSpeed);
            o.writeFloat(g.fx.fxAmount);
            o.writeFloat(g.fx.fxRotate);
            o.writeInt(g.items.size());
            for (Item x : g.items) writeItem(o, x);
        }
    }

    private static Item readItem(DataInputStream in, int ver) throws IOException {
        int type = in.readByte();
        if (type == 1) return readStroke(in, ver);
        if (type == 2) return readFill(in, ver);
        if (type == 4) {
            float l = in.readFloat(), t = in.readFloat(), w = in.readFloat(), h = in.readFloat();
            byte[] enc = new byte[in.readInt()];
            in.readFully(enc);
            float ang = ver >= 5 ? in.readFloat() : 0;
            Bitmap b = android.graphics.BitmapFactory.decodeByteArray(enc, 0, enc.length);
            if (b == null) b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            return new Picture(b, enc, l, t, w, h, ang);
        }
        Layer fx = Layer.empty();
        fx.opacity = in.readFloat();
        fx.effect = in.readInt();
        fx.fxScale = in.readBoolean();
        fx.fxSpeed = in.readFloat();
        fx.fxAmount = in.readFloat();
        fx.fxRotate = in.readFloat();
        int n = in.readInt();
        ArrayList<Item> items = new ArrayList<>();
        for (int i = 0; i < n; i++) items.add(readItem(in, ver));
        return new Group(items, fx);
    }

    private static void writeStroke(DataOutputStream o, Stroke s) throws IOException {
        o.writeByte(1);
        o.writeInt(s.brush);
        o.writeInt(s.color);
        o.writeFloat(s.size);
        o.writeFloat(s.alpha);
        o.writeFloat(s.wobble);
        o.writeFloat(s.speed);
        o.writeInt(s.frames);
        o.writeInt(s.seed);
        o.writeBoolean(s.eraser);
        o.writeFloat(s.spacing);
        o.writeBoolean(s.echo);
        o.writeFloat(s.arc0);
        o.writeBoolean(s.cutStart);
        o.writeBoolean(s.cutEnd);
        o.writeInt(s.n);
        for (int i = 0; i < s.n; i++) {
            o.writeFloat(s.x[i]);
            o.writeFloat(s.y[i]);
            o.writeFloat(s.p[i]);
        }
    }

    private static void writeFill(DataOutputStream o, Fill f) throws IOException {
        o.writeByte(2);
        o.writeInt(f.color);
        o.writeFloat(f.wobble);
        o.writeFloat(f.speed);
        o.writeInt(f.frames);
        o.writeInt(f.seed);
        o.writeFloat(f.left);
        o.writeFloat(f.top);
        o.writeInt(f.w);
        o.writeInt(f.h);
        byte[] d = f.data;
        int i = 0;
        while (i < d.length) {
            byte v = d[i];
            int run = 1;
            while (i + run < d.length && d[i + run] == v && run < 65535) run++;
            o.writeByte(v);
            o.writeChar(run);
            i += run;
        }
        o.writeFloat(f.angle);
    }

    static Doc read(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("not a project");
        int ver = in.readInt();
        if (ver > VERSION) throw new IOException("newer version");
        Doc d = new Doc();
        d.name = in.readUTF();
        d.w = in.readInt();
        d.h = in.readInt();
        d.bg = in.readInt();
        d.fps = in.readInt();
        d.frames = in.readInt();
        int nl = in.readInt();
        for (int li = 0; li < nl; li++) {
            Layer l = Layer.empty();
            l.name = in.readUTF();
            l.visible = in.readBoolean();
            l.opacity = in.readFloat();
            l.effect = in.readInt();
            l.fxScale = in.readBoolean();
            l.fxSpeed = in.readFloat();
            l.fxAmount = in.readFloat();
            l.fxRotate = in.readFloat();
            if (ver >= 5) {
                l.hasPivot = in.readBoolean();
                l.pivotX = in.readFloat();
                l.pivotY = in.readFloat();
            }
            for (int f = 0; f < d.frames; f++) {
                if (in.readByte() == 0) {
                    l.cels.add(f == 0 ? new Cel() : null);
                    continue;
                }
                Cel c = new Cel();
                int ni = in.readInt();
                for (int k = 0; k < ni; k++) {
                    c.items.add(readItem(in, ver));
                }
                l.cels.add(c);
            }
            d.layers.add(l);
        }
        if (ver >= 5) d.boil = in.readBoolean();
        if (d.layers.isEmpty()) d.layers.add(new Layer("Слой 1", d.frames));
        d.liftGroupEffects();
        return d;
    }

    private static Stroke readStroke(DataInputStream in, int ver) throws IOException {
        Stroke s = new Stroke();
        s.brush = in.readInt();
        s.color = in.readInt();
        s.size = in.readFloat();
        s.alpha = in.readFloat();
        s.wobble = in.readFloat();
        s.speed = in.readFloat();
        s.frames = in.readInt();
        s.seed = in.readInt();
        s.eraser = in.readBoolean();
        if (ver >= 2) {
            s.spacing = in.readFloat();
            s.echo = in.readBoolean();
        }
        if (ver >= 6) {
            s.arc0 = in.readFloat();
            s.cutStart = in.readBoolean();
            s.cutEnd = in.readBoolean();
        }
        int n = in.readInt();
        for (int i = 0; i < n; i++) {
            float x = in.readFloat(), y = in.readFloat(), p = in.readFloat();
            s.add(x, y, p);
        }
        return s;
    }

    private static Fill readFill(DataInputStream in, int ver) throws IOException {
        int color = in.readInt();
        float wobble = in.readFloat(), speed = in.readFloat();
        int frames = in.readInt(), seed = in.readInt();
        float left = in.readFloat(), top = in.readFloat();
        int w = in.readInt(), h = in.readInt();
        byte[] d = new byte[w * h];
        int i = 0;
        while (i < d.length) {
            byte v = in.readByte();
            int run = in.readChar();
            Arrays.fill(d, i, Math.min(d.length, i + run), v);
            i += run;
        }
        Fill f = new Fill(d, w, h, left, top);
        f.color = color;
        f.wobble = wobble;
        f.speed = speed;
        f.frames = frames;
        f.seed = seed;
        if (ver >= 5) f.setAngle(in.readFloat());
        return f;
    }

    /** Reads just the header fields used by the gallery. */
    static String[] readHeader(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) throw new IOException("not a project");
        in.readInt();
        String name = in.readUTF();
        int w = in.readInt(), h = in.readInt();
        in.readInt();
        in.readInt();
        int frames = in.readInt();
        return new String[]{name, String.valueOf(w), String.valueOf(h), String.valueOf(frames)};
    }
}
