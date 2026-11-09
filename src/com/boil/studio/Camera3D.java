package com.boil.studio;

import android.graphics.Bitmap;
import android.graphics.Camera;
import android.graphics.Matrix;

import java.util.ArrayList;

/**
 * Virtual camera over a 3D stack of layers. Each layer is a flat card at its own depth; the camera
 * looks at the canvas plane from one focal length F away and can dolly forward, pan, roll and turn
 * in 3D. Layers far from the focus distance (or right in front of the lens) are blurred.
 */
final class Camera3D {
    /** Camera state at a timeline frame. Depth-like values are in units of the focal length F. */
    static final class Key {
        int frame;
        float x, y, dolly, roll, yaw, pitch, focus, blur;
        /** Lens zoom (1 = normal); combined with dolly it gives the "dolly zoom" look. */
        float zoom = 1;
        /** Curve from this key to the next, one of the EASE_* values. */
        int ease;
        /** Handheld shake amount, and white flash / black dip overlays (0..1). */
        float shake, flash, dip;

        Key copy() {
            Key k = new Key();
            k.frame = frame;
            k.x = x;
            k.y = y;
            k.dolly = dolly;
            k.roll = roll;
            k.yaw = yaw;
            k.pitch = pitch;
            k.focus = focus;
            k.blur = blur;
            k.zoom = zoom;
            k.ease = ease;
            k.shake = shake;
            k.flash = flash;
            k.dip = dip;
            return k;
        }

        void lerp(Key a, Key b, float u) {
            x = a.x + (b.x - a.x) * u;
            y = a.y + (b.y - a.y) * u;
            dolly = a.dolly + (b.dolly - a.dolly) * u;
            roll = a.roll + (b.roll - a.roll) * u;
            yaw = a.yaw + (b.yaw - a.yaw) * u;
            pitch = a.pitch + (b.pitch - a.pitch) * u;
            focus = a.focus + (b.focus - a.focus) * u;
            blur = a.blur + (b.blur - a.blur) * u;
            zoom = a.zoom + (b.zoom - a.zoom) * u;
            shake = a.shake + (b.shake - a.shake) * u;
            flash = a.flash + (b.flash - a.flash) * u;
            dip = a.dip + (b.dip - a.dip) * u;
        }
    }

    static final int EASE_SMOOTH = 0, EASE_OUT = 1, EASE_LINEAR = 2, EASE_IN = 3, EASE_BACK = 4, EASE_BOUNCE = 5, EASE_ELASTIC = 6;
    static final String[] EASE_NAME = {"Плавно", "Резкий старт", "Линейно", "Разгон", "Пружина", "Отскок", "Резина"};

    /** Motion curves, as in motion-graphics tools; may overshoot past 1 for back and elastic. */
    static float ease(int type, float u) {
        switch (type) {
            case EASE_OUT: {
                float r = 1 - u;
                return 1 - r * r * r * r;
            }
            case EASE_LINEAR:
                return u;
            case EASE_IN:
                return u * u * u;
            case EASE_BACK: {
                float c1 = 1.70158f, c3 = c1 + 1, v = u - 1;
                return 1 + c3 * v * v * v + c1 * v * v;
            }
            case EASE_BOUNCE: {
                float n = 7.5625f, dd = 2.75f, x = u;
                if (x < 1 / dd) return n * x * x;
                if (x < 2 / dd) {
                    x -= 1.5f / dd;
                    return n * x * x + 0.75f;
                }
                if (x < 2.5f / dd) {
                    x -= 2.25f / dd;
                    return n * x * x + 0.9375f;
                }
                x -= 2.625f / dd;
                return n * x * x + 0.984375f;
            }
            case EASE_ELASTIC:
                if (u <= 0) return 0;
                if (u >= 1) return 1;
                return (float) (Math.pow(2, -10 * u) * Math.sin((u * 10 - 0.75) * (2 * Math.PI / 3)) + 1);
            default:
                return u * u * (3 - 2 * u);
        }
    }

    /** Adds procedural handheld shake to a camera state. */
    static void applyShake(Key k, float frame, Doc d) {
        if (k.shake <= 0) return;
        float u = frame * 1.7f;
        k.x += k.shake * d.w * 0.035f * Renderer.noise(91, u);
        k.y += k.shake * d.h * 0.035f * Renderer.noise(173, u + 9.1f);
        k.roll += k.shake * 4f * Renderer.noise(251, u * 0.8f + 3.3f);
    }

    /** Animated transform of one layer at a timeline frame (after-effects-style layer keys). */
    static final class LKey {
        int frame, ease;
        float x, y, scale = 1, rot, opacity = 1, rotX, rotY;

        LKey copy() {
            LKey k = new LKey();
            k.frame = frame;
            k.ease = ease;
            k.x = x;
            k.y = y;
            k.scale = scale;
            k.rot = rot;
            k.opacity = opacity;
            k.rotX = rotX;
            k.rotY = rotY;
            return k;
        }

        void lerp(LKey a, LKey b, float u) {
            x = a.x + (b.x - a.x) * u;
            y = a.y + (b.y - a.y) * u;
            scale = a.scale + (b.scale - a.scale) * u;
            rot = a.rot + (b.rot - a.rot) * u;
            opacity = a.opacity + (b.opacity - a.opacity) * u;
            rotX = a.rotX + (b.rotX - a.rotX) * u;
            rotY = a.rotY + (b.rotY - a.rotY) * u;
        }

        boolean identity() {
            return x == 0 && y == 0 && scale == 1 && rot == 0 && opacity >= 0.999f && rotX == 0 && rotY == 0;
        }
    }

    static LKey layerAt(ArrayList<LKey> keys, float frame, LKey out) {
        LKey k = out != null ? out : new LKey();
        LKey id = new LKey();
        if (keys.isEmpty()) {
            k.lerp(id, id, 0);
            return k;
        }
        LKey first = keys.get(0), last = keys.get(keys.size() - 1);
        if (frame <= first.frame) {
            k.lerp(first, first, 0);
            return k;
        }
        if (frame >= last.frame) {
            k.lerp(last, last, 0);
            return k;
        }
        for (int i = 0; i < keys.size() - 1; i++) {
            LKey a = keys.get(i), b = keys.get(i + 1);
            if (frame >= a.frame && frame <= b.frame) {
                float u = b.frame == a.frame ? 1 : (frame - a.frame) / (float) (b.frame - a.frame);
                k.lerp(a, b, ease(a.ease, u));
                return k;
            }
        }
        k.lerp(last, last, 0);
        return k;
    }

    static LKey layerKeyAt(ArrayList<LKey> keys, int frame) {
        for (LKey k : keys) if (k.frame == frame) return k;
        return null;
    }

    static LKey ensureLayerKey(ArrayList<LKey> keys, int frame) {
        LKey k = layerKeyAt(keys, frame);
        if (k != null) return k;
        k = layerAt(keys, frame, null);
        k.frame = frame;
        int i = 0;
        while (i < keys.size() && keys.get(i).frame < frame) i++;
        keys.add(i, k);
        return k;
    }

    /** Fills m with a layer's keyed transform around its anchor (base point, or drawing centre). */
    void layerTransform(LKey k, float ax, float ay, Matrix m, float F) {
        m.reset();
        m.postTranslate(-ax, -ay);
        m.postScale(k.scale, k.scale);
        m.postRotate(k.rot);
        if (k.rotX != 0 || k.rotY != 0) {
            cam3.save();
            cam3.setLocation(0, 0, -F * 1.6f / 72f);
            cam3.rotateX(k.rotX);
            cam3.rotateY(k.rotY);
            cam3.getMatrix(tmp);
            cam3.restore();
            m.postConcat(tmp);
        }
        m.postTranslate(ax + k.x, ay + k.y);
    }

    /** Planes closer than this (in F) are behind the lens and not drawn. */
    static final float NEAR = 0.06f;

    private final Camera cam3 = new Camera();
    private final Matrix tmp = new Matrix();

    /** Smoothly eased camera state at a (fractional) frame; identity when there are no keys. */
    static Key at(ArrayList<Key> keys, float frame, Key out) {
        Key k = out != null ? out : new Key();
        if (keys.isEmpty()) {
            k.lerp(new Key(), new Key(), 0);
            return k;
        }
        Key first = keys.get(0), last = keys.get(keys.size() - 1);
        if (frame <= first.frame) {
            k.lerp(first, first, 0);
            return k;
        }
        if (frame >= last.frame) {
            k.lerp(last, last, 0);
            return k;
        }
        for (int i = 0; i < keys.size() - 1; i++) {
            Key a = keys.get(i), b = keys.get(i + 1);
            if (frame >= a.frame && frame <= b.frame) {
                float u = b.frame == a.frame ? 1 : (frame - a.frame) / (float) (b.frame - a.frame);
                u = ease(a.ease, u);
                k.lerp(a, b, u);
                return k;
            }
        }
        k.lerp(last, last, 0);
        return k;
    }

    static Key keyAt(ArrayList<Key> keys, int frame) {
        for (Key k : keys) if (k.frame == frame) return k;
        return null;
    }

    /** Returns the key at the frame, creating it from the interpolated state if missing (kept sorted). */
    static Key ensureKey(ArrayList<Key> keys, int frame) {
        Key k = keyAt(keys, frame);
        if (k != null) return k;
        k = at(keys, frame, null);
        k.frame = frame;
        int i = 0;
        while (i < keys.size() && keys.get(i).frame < frame) i++;
        keys.add(i, k);
        return k;
    }

    static float focal(Doc d) {
        return Math.max(d.w, d.h);
    }

    /** Distance from the lens to a layer's card, in doc pixels. */
    static float distance(Doc d, Layer l, Key k) {
        float F = focal(d);
        return F + l.depth * F - k.dolly * F;
    }

    /**
     * On-screen scale of a layer. Each card is pre-scaled by its resting distance, so with the camera
     * at rest every layer looks exactly as drawn; depth only shows once the camera moves.
     */
    static float scale(Doc d, Layer l, Key k) {
        float F = focal(d), dist = distance(d, l, k);
        float rest = Math.max(NEAR * F, F + l.depth * F);
        return rest / dist * k.zoom;
    }

    /**
     * Fills m with the doc → frame projection of a layer and returns its visibility (0 = behind the
     * camera, fading to 1 as it moves away from the lens).
     */
    float layerMatrix(Doc d, Layer l, Key k, Matrix m) {
        float F = focal(d), dist = distance(d, l, k), near = NEAR * F;
        if (dist <= near) return 0;
        // panning slides each card in proportion to its on-screen scale: nearer (bigger) cards move further
        float s = scale(d, l, k), parallax = s, cx = d.w / 2f, cy = d.h / 2f;
        m.reset();
        m.postTranslate(-cx, -cy);
        m.postScale(s, s);
        m.postTranslate(-k.x * parallax, -k.y * parallax); // nearer cards slide further when panning
        m.postRotate(k.roll);
        m.postTranslate(cx, cy);
        if (k.yaw != 0 || k.pitch != 0) {
            cam3.save();
            cam3.setLocation(0, 0, -F * 1.6f / 72f);
            cam3.rotateY(k.yaw);
            cam3.rotateX(k.pitch);
            cam3.getMatrix(tmp);
            cam3.restore();
            tmp.preTranslate(-cx, -cy);
            tmp.postTranslate(cx, cy);
            m.postConcat(tmp);
        }
        return Math.min(1f, (dist - near) / (near * 3));
    }

    /** On-screen blur radius (frame pixels) for a layer: depth of field plus a lens-close blur. */
    static float blur(Doc d, Layer l, Key k) {
        float F = focal(d), dist = distance(d, l, k);
        float focusDist = F + k.focus * F - k.dolly * F;
        float defocus = Math.abs(dist - focusDist) / F;
        float close = dist < 0.5f * F ? (0.5f * F - dist) / (0.5f * F) : 0;
        return k.blur * 36f * defocus + 80f * close; // strong lens-close blur as the camera passes a card
    }

    static boolean active(Doc d) {
        if (!d.cam.isEmpty()) return true;
        for (Layer l : d.layers) if (l.depth != 0 || l.appearAt >= 0 || l.vanishAt >= 0 || !l.keys.isEmpty()) return true;
        return false;
    }

    /** Opacity multiplier of a layer at a fractional frame from its appear / vanish settings. */
    static float fade(Layer l, float frame) {
        float a = 1;
        if (l.appearAt >= 0) a *= clamp01((frame - l.appearAt) / Math.max(0.5f, l.appearLen));
        if (l.vanishAt >= 0) a *= 1 - clamp01((frame - l.vanishAt) / Math.max(0.5f, l.vanishLen));
        return a;
    }

    private static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }

    /** In-place box blur (three passes ≈ gaussian) on premultiplied pixels. */
    static void blurBitmap(Bitmap b, int radius, int[] buf) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = buf != null && buf.length >= w * h ? buf : new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        for (int i = 0; i < w * h; i++) { // premultiply so transparent edges don't darken
            int c = px[i], a = c >>> 24;
            px[i] = a << 24 | ((c >> 16 & 255) * a / 255) << 16 | ((c >> 8 & 255) * a / 255) << 8 | (c & 255) * a / 255;
        }
        int[] tmpRow = new int[Math.max(w, h)];
        for (int pass = 0; pass < 3; pass++) {
            for (int y = 0; y < h; y++) boxLine(px, y * w, 1, w, radius, tmpRow);
            for (int x = 0; x < w; x++) boxLine(px, x, w, h, radius, tmpRow);
        }
        for (int i = 0; i < w * h; i++) {
            int c = px[i], a = c >>> 24;
            if (a == 0) {
                px[i] = 0;
                continue;
            }
            px[i] = a << 24 | Math.min(255, (c >> 16 & 255) * 255 / a) << 16 | Math.min(255, (c >> 8 & 255) * 255 / a) << 8 | Math.min(255, (c & 255) * 255 / a);
        }
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void boxLine(int[] px, int start, int step, int len, int r, int[] out) {
        int sa = 0, sr = 0, sg = 0, sb = 0, win = r * 2 + 1;
        for (int i = -r; i <= r; i++) {
            int c = px[start + Math.max(0, Math.min(len - 1, i)) * step];
            sa += c >>> 24;
            sr += c >> 16 & 255;
            sg += c >> 8 & 255;
            sb += c & 255;
        }
        for (int i = 0; i < len; i++) {
            out[i] = (sa / win) << 24 | (sr / win) << 16 | (sg / win) << 8 | (sb / win);
            int cOut = px[start + Math.max(0, i - r) * step], cIn = px[start + Math.min(len - 1, i + r + 1) * step];
            sa += (cIn >>> 24) - (cOut >>> 24);
            sr += (cIn >> 16 & 255) - (cOut >> 16 & 255);
            sg += (cIn >> 8 & 255) - (cOut >> 8 & 255);
            sb += (cIn & 255) - (cOut & 255);
        }
        for (int i = 0; i < len; i++) px[start + i * step] = out[i];
    }
}
