package com.boil.studio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Monochrome design system: tokens, a tiny SVG-path icon set and custom controls. */
final class Ui {
    static final int BG = 0xFF0B0B0B, SURFACE = 0xFF151515, PANEL = 0xF5161616, LINE = 0xFF2A2A2A,
            CHIP = 0xFF222222, TEXT = 0xFFF2F2F2, DIM = 0xFF8A8A8A, FAINT = 0xFF4A4A4A;
    static float D = 3f;

    private Ui() {
    }

    static void init(Context c) {
        D = c.getResources().getDisplayMetrics().density;
    }

    static int dp(float v) {
        return Math.round(v * D);
    }

    static GradientDrawable round(int color, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(Math.max(1, dp(1)), stroke);
        return g;
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView button(Context c, String s, boolean primary) {
        TextView t = text(c, s, 14, primary ? 0xFF000000 : TEXT, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(10), dp(16), dp(10));
        t.setBackground(round(primary ? 0xFFFFFFFF : CHIP, 12, 0));
        t.setClickable(true);
        return t;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams lpw(float weight) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
    }

    static View space(Context c, int wDp, int hDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(wDp), dp(hDp)));
        return v;
    }

    static View divider(Context c, boolean vertical) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams p = vertical ? lp(Math.max(1, dp(1)), dp(22)) : lp(LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        if (vertical) p.setMargins(dp(4), 0, dp(4), 0);
        else p.setMargins(0, dp(8), 0, dp(8));
        v.setLayoutParams(p);
        return v;
    }

    /** ScrollView that never grows taller than maxHeight. */
    static final class MaxScroll extends ScrollView {
        int maxHeight;

        MaxScroll(Context c, int maxHeight) {
            super(c);
            this.maxHeight = maxHeight;
            setVerticalScrollBarEnabled(false);
            setOverScrollMode(OVER_SCROLL_NEVER);
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            super.onMeasure(wSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
        }
    }

    // ---------- icons ----------

    static final class Icon extends View {
        String name;
        boolean active, dim;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        Icon(Context c, String name) {
            super(c);
            this.name = name;
            setClickable(true);
        }

        void setActive(boolean a) {
            if (active != a) {
                active = a;
                invalidate();
            }
        }

        void setDim(boolean d) {
            if (dim != d) {
                dim = d;
                invalidate();
            }
        }

        void setName(String n) {
            name = n;
            invalidate();
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), s = Math.min(w, h);
            p.setStyle(Paint.Style.FILL);
            if (active) {
                p.setColor(0xFFFFFFFF);
                c.drawCircle(w / 2, h / 2, s / 2 - dp(2), p);
            } else if (isPressed()) {
                p.setColor(0x26FFFFFF);
                c.drawCircle(w / 2, h / 2, s / 2 - dp(2), p);
            }
            int col = active ? 0xFF000000 : (dim ? FAINT : TEXT);
            float sc = s * 0.56f / 24f;
            c.save();
            c.translate(w / 2 - 12 * sc, h / 2 - 12 * sc);
            c.scale(sc, sc);
            drawIcon(c, name, col, p, path);
            c.restore();
        }
    }

    static String svg(String name) {
        switch (name) {
            case "menu": return "M4 7H20M4 12H20M4 17H14";
            case "grid": return "M4 4H10V10H4ZM14 4H20V10H14ZM4 14H10V20H4ZM14 14H20V20H14Z";
            case "brush": return "M15 4L20 9L9 20H4V15ZM12.5 6.5L17.5 11.5";
            case "eraser": return "M10 20L4 14L14 4L20 10L10 20ZM9 9L15 15M10 20H20";
            case "lasso": return "M12 4C17 4 21 6.5 21 9.5C21 12.5 17 15 12 15C7 15 3 12.5 3 9.5C3 6.5 7 4 12 4ZM8 14.5C6.5 16.5 7.5 18.5 9.5 18.5C11 18.5 11 20.5 9.5 21.5";
            case "picker": return "M13.5 8.5L5.5 16.5L5 19L7.5 18.5L15.5 10.5M12 7L17 12M14.5 4.5C15.5 3.5 17.2 3.5 18.2 4.5L19.5 5.8C20.5 6.8 20.5 8.5 19.5 9.5L17 12L12 7Z";
            case "fill": return "M11 3L19 11L12 18L4 10ZM4 10H18.5D20.5 17.5 1.6";
            case "effect": return "M12 3C12.6 9 15 11.4 21 12C15 12.6 12.6 15 12 21C11.4 15 9 12.6 3 12C9 11.4 11.4 9 12 3Z";
            case "layers": return "M12 4L20 8.5L12 13L4 8.5ZM4 12.5L12 17L20 12.5M4 16.5L12 21L20 16.5";
            case "undo": return "M9 6L4 11L9 16M4 11H14C17.3 11 20 13.7 20 17V18";
            case "redo": return "M15 6L20 11L15 16M20 11H10C6.7 11 4 13.7 4 17V18";
            case "play": return "FM8 5L19 12L8 19Z";
            case "pause": return "M8.5 6V18M15.5 6V18";
            case "plus": return "M12 5V19M5 12H19";
            case "copy": return "M8 8H19V19H8ZM5 15V5H15";
            case "trash": return "M4 7H20M9 7V4H15V7M6 7L7 20H17L18 7M10 11V16M14 11V16";
            case "up": return "M6 15L12 9L18 15";
            case "down": return "M6 9L12 15L18 9";
            case "eye": return "M2 12C5 6 19 6 22 12C19 18 5 18 2 12ZO12 12 3";
            case "eyeoff": return "M2 12C5 6 19 6 22 12C19 18 5 18 2 12ZO12 12 3M4 4L20 20";
            case "onion": return "O9 12 5.5O15 12 5.5";
            case "close": return "M6 6L18 18M18 6L6 18";
            case "check": return "M5 12L10 17L19 7";
            case "export": return "M12 15V4M8 8L12 4L16 8M5 13V20H19V13";
            case "fit": return "M4 9V4H9M15 4H20V9M20 15V20H15M9 20H4V15";
            case "image": return "M4 5H20V19H4ZM4 16L9 11L14 16M13 15L16 12L20 16O15.5 8.5 1.5";
            case "minus": return "M5 12H19";
            case "ref": return "M3 5H14V14H3ZM9 14V19H21V9H14";
            case "selall": return "M4 8V4H8M16 4H20V8M20 16V20H16M8 20H4V16M9 9H15V15H9Z";
            case "sliders": return "M4 7H13M19 7H20M4 17H6M12 17H20O16 7 2.5O9 17 2.5";
            case "sym": return "M12 3V21M8 7L4 12L8 17M16 7L20 12L16 17";
            case "symh": return "M3 12H21M7 8L12 4L17 8M7 16L12 20L17 16";
            case "symb": return "M12 3V21M3 12H21M7 5L5 7M17 5L19 7M7 19L5 17M17 19L19 17";
            case "ruler": return "M4 16L16 4L20 8L8 20ZM8 12L10 14M11 9L13 11M14 6L16 8";
            case "line": return "M5 19L19 5D5 19 2D19 5 2";
            case "fliph": return "M12 3V21M9 7L3 17H9ZM15 7L21 17H15Z";
            case "flipv": return "M3 12H21M7 9L17 3V9ZM7 15L17 21V15Z";
            case "boil": return "M3 12C5 8 7 8 9 12C11 16 13 16 15 12C17 8 19 8 21 12";
            case "rotl": return "M8 4H4V8M4 8C6 4.5 9 3.5 12 3.5C16.7 3.5 20.5 7.3 20.5 12C20.5 16.7 16.7 20.5 12 20.5C8.5 20.5 5.5 18.5 4.3 15.5";
            case "rotr": return "M16 4H20V8M20 8C18 4.5 15 3.5 12 3.5C7.3 3.5 3.5 7.3 3.5 12C3.5 16.7 7.3 20.5 12 20.5C15.5 20.5 18.5 18.5 19.7 15.5";
            case "pivot": return "O12 12 4M12 3V8M12 16V21M3 12H8M16 12H21";
            case "merge": return "M6 4H18M6 9H18M12 13V20M8.5 16.5L12 20L15.5 16.5";
            case "video": return "M3 6H15V18H3ZM15 10.5L21 7V17L15 13.5";
            case "film": return "M4 5H20V19H4ZM8 5V19M16 5V19M4 9.5H8M4 14.5H8M16 9.5H20M16 14.5H20";
            case "share": return "O6 12 2.5O18 6 2.5O18 18 2.5M8.2 10.8L15.8 7.2M8.2 13.2L15.8 16.8";
            case "paper": return "M6 3H14L19 8V21H6ZM14 3V8H19";
            case "edit": return "M15 4L20 9L9 20H4V15Z";
            case "back": return "M15 5L8 12L15 19";
            case "dup": return "M8 8H19V19H8ZM5 15V5H15M13.5 11V16M11 13.5H16";
            default: return "O12 12 6";
        }
    }

    static void drawIcon(Canvas c, String name, int color, Paint p, Path path) {
        String s = svg(name);
        boolean fill = s.startsWith("F");
        if (fill) s = s.substring(1);
        p.setColor(color);
        p.setStrokeWidth(1.8f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        path.reset();
        int i = 0, n = s.length();
        float cx = 0, cy = 0;
        float[] a = new float[6];
        while (i < n) {
            char cmd = s.charAt(i++);
            int need;
            switch (cmd) {
                case 'M': case 'L': need = 2; break;
                case 'H': case 'V': need = 1; break;
                case 'Q': need = 4; break;
                case 'C': need = 6; break;
                case 'O': case 'D': need = 3; break;
                case 'Z': need = 0; break;
                default: continue;
            }
            for (int k = 0; k < need; k++) {
                while (i < n && (s.charAt(i) == ' ' || s.charAt(i) == ',')) i++;
                int st = i;
                while (i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == '-')) i++;
                a[k] = Float.parseFloat(s.substring(st, i));
            }
            switch (cmd) {
                case 'M': path.moveTo(cx = a[0], cy = a[1]); break;
                case 'L': path.lineTo(cx = a[0], cy = a[1]); break;
                case 'H': path.lineTo(cx = a[0], cy); break;
                case 'V': path.lineTo(cx, cy = a[0]); break;
                case 'Q': path.quadTo(a[0], a[1], cx = a[2], cy = a[3]); break;
                case 'C': path.cubicTo(a[0], a[1], a[2], a[3], cx = a[4], cy = a[5]); break;
                case 'Z': path.close(); break;
                case 'O': path.addCircle(a[0], a[1], a[2], Path.Direction.CW); break;
                case 'D': {
                    Paint.Style st = p.getStyle();
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(a[0], a[1], a[2], p);
                    p.setStyle(st);
                    break;
                }
                default: break;
            }
        }
        p.setStyle(fill ? Paint.Style.FILL : Paint.Style.STROKE);
        c.drawPath(path, p);
    }

    // ---------- sliders ----------

    abstract static class OnSlide {
        void start() {
        }

        abstract void change(float v);

        void end() {
        }
    }

    /** Horizontal labelled slider, value 0..1. */
    static final class Slider extends View {
        String label, value = "";
        float v;
        OnSlide l;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Slider(Context c, String label, float v, OnSlide l) {
            super(c);
            this.label = label;
            this.v = v;
            this.l = l;
        }

        void set(float v, String text) {
            this.v = Math.max(0, Math.min(1, v));
            this.value = text;
            invalidate();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), dp(48));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            p.setTextSize(dp(12.5f));
            p.setStyle(Paint.Style.FILL);
            p.setColor(DIM);
            p.setTextAlign(Paint.Align.LEFT);
            c.drawText(label, 0, dp(16), p);
            p.setColor(TEXT);
            p.setTextAlign(Paint.Align.RIGHT);
            c.drawText(value, w, dp(16), p);
            float ty = dp(34), r = dp(8), x0 = r, x1 = w - r;
            float kx = x0 + (x1 - x0) * v;
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(3));
            p.setColor(LINE);
            c.drawLine(x0, ty, x1, ty, p);
            p.setColor(0xFFFFFFFF);
            c.drawLine(x0, ty, kx, ty, p);
            c.drawCircle(kx, ty, r, p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float r = dp(8);
            float nv = Math.max(0, Math.min(1, (e.getX() - r) / Math.max(1, getWidth() - 2 * r)));
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    l.start();
                    v = nv;
                    l.change(v);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    v = nv;
                    l.change(v);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    l.end();
                    return true;
                default:
                    return true;
            }
        }
    }

    /** Vertical rail slider used on the left side for size and opacity. */
    /** Rail slider for size and opacity; vertical by default, horizontal when placed on a horizontal bar. */
    static final class VSlider extends View {
        float v;
        boolean horizontal;
        OnSlide l;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        VSlider(Context c, OnSlide l) {
            super(c);
            this.l = l;
        }

        void set(float v) {
            this.v = Math.max(0, Math.min(1, v));
            invalidate();
        }

        /** Track ends along the slider axis: start (value 0) and end (value 1). */
        private float a0() {
            float r = dp(7);
            return horizontal ? r + dp(4) : getHeight() - r - dp(4);
        }

        private float a1() {
            float r = dp(7);
            return horizontal ? getWidth() - r - dp(4) : r + dp(4);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = dp(7);
            float s0 = a0(), s1 = a1(), k = s0 + (s1 - s0) * v;
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(dp(3));
            p.setStyle(Paint.Style.STROKE);
            if (horizontal) {
                float cy = h / 2;
                p.setColor(LINE);
                c.drawLine(s0, cy, s1, cy, p);
                p.setColor(0xFFFFFFFF);
                c.drawLine(s0, cy, k, cy, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(k, cy, r, p);
            } else {
                float cx = w / 2;
                p.setColor(LINE);
                c.drawLine(cx, s0, cx, s1, p);
                p.setColor(0xFFFFFFFF);
                c.drawLine(cx, s0, cx, k, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, k, r, p);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float pos = horizontal ? e.getX() : e.getY();
            float nv = Math.max(0, Math.min(1, (pos - a0()) / (a1() - a0())));
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    l.start();
                    v = nv;
                    l.change(v);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    v = nv;
                    l.change(v);
                    invalidate();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    l.end();
                    return true;
                default:
                    return true;
            }
        }
    }

    static final class Toggle extends View {
        boolean on;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Toggle(Context c, boolean on) {
            super(c);
            this.on = on;
            setClickable(true);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(dp(42), dp(24));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = h / 2;
            p.setColor(on ? 0xFFFFFFFF : LINE);
            c.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
            p.setColor(on ? 0xFF000000 : DIM);
            c.drawCircle(on ? w - r : r, r, r - dp(3), p);
        }
    }

    /** Small round colour swatch. */
    static final class Swatch extends View {
        int color;
        boolean ring;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Swatch(Context c, int color) {
            super(c);
            this.color = color;
            setClickable(true);
        }

        void setColor(int c) {
            color = c;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2 - dp(3);
            p.setStyle(Paint.Style.FILL);
            p.setColor(color | 0xFF000000);
            c.drawCircle(w / 2, h / 2, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(ring ? 2 : 1));
            p.setColor(ring ? 0xFFFFFFFF : 0x55FFFFFF);
            c.drawCircle(w / 2, h / 2, ring ? r + dp(1.5f) : r, p);
        }
    }

    /** Saturation/value square plus hue strip. */
    static final class ColorPicker extends View {
        interface Listener {
            void onColor(int color, boolean done);
        }

        float hue, sat, val;
        Listener l;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF sv = new RectF(), hb = new RectF();
        private int region;
        private static final int[] HUES = {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};

        ColorPicker(Context c, int color, Listener l) {
            super(c);
            this.l = l;
            setColor(color);
        }

        void setColor(int color) {
            float[] hsv = new float[3];
            Color.colorToHSV(color, hsv);
            if (hsv[1] > 0.001f && hsv[2] > 0.001f) hue = hsv[0];
            sat = hsv[1];
            val = hsv[2];
            invalidate();
        }

        int color() {
            return Color.HSVToColor(new float[]{hue, sat, val});
        }

        @Override
        protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            setMeasuredDimension(width, Math.round(width * 0.58f) + dp(40));
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            sv.set(0, 0, w, Math.round(w * 0.58f));
            hb.set(dp(2), sv.bottom + dp(16), w - dp(2), sv.bottom + dp(36));
        }

        @Override
        protected void onDraw(Canvas c) {
            float rr = dp(12);
            int pure = Color.HSVToColor(new float[]{hue, 1, 1});
            p.setStyle(Paint.Style.FILL);
            p.setShader(new LinearGradient(sv.left, 0, sv.right, 0, 0xFFFFFFFF, pure, Shader.TileMode.CLAMP));
            c.drawRoundRect(sv, rr, rr, p);
            p.setShader(new LinearGradient(0, sv.top, 0, sv.bottom, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
            c.drawRoundRect(sv, rr, rr, p);
            p.setShader(new LinearGradient(hb.left, 0, hb.right, 0, HUES, null, Shader.TileMode.CLAMP));
            c.drawRoundRect(hb, hb.height() / 2, hb.height() / 2, p);
            p.setShader(null);
            float kx = sv.left + sat * sv.width(), ky = sv.top + (1 - val) * sv.height();
            knob(c, kx, ky, color());
            knob(c, hb.left + hue / 360f * hb.width(), hb.centerY(), pure);
        }

        private void knob(Canvas c, float x, float y, int color) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            c.drawCircle(x, y, dp(9), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2.5f));
            p.setColor(0xFFFFFFFF);
            c.drawCircle(x, y, dp(9), p);
            p.setStrokeWidth(dp(1));
            p.setColor(0x66000000);
            c.drawCircle(x, y, dp(10.5f), p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                getParent().requestDisallowInterceptTouchEvent(true);
                region = y > sv.bottom + dp(6) ? 2 : 1;
            }
            if (region == 1) {
                sat = Math.max(0, Math.min(1, (x - sv.left) / sv.width()));
                val = 1 - Math.max(0, Math.min(1, (y - sv.top) / sv.height()));
            } else if (region == 2) {
                hue = Math.max(0, Math.min(359.9f, (x - hb.left) / hb.width() * 360f));
            }
            boolean done = a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL;
            if (l != null) l.onColor(color(), done);
            invalidate();
            return true;
        }
    }

    /** Animated tile previewing one layer effect on a small square. */
    static final class EffectChip extends View {
        final int effect;
        boolean selected;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Matrix m = new android.graphics.Matrix();
        private final Layer demo = new Layer("", 1);
        private final RectF box = new RectF();

        EffectChip(Context c, int effect) {
            super(c);
            this.effect = effect;
            demo.effect = effect;
            demo.fxSpeed = .35f;
            demo.fxAmount = .6f;
            demo.fxRotate = .4f;
            setClickable(true);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), dp(64));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.FILL);
            p.setColor(selected ? 0xFFFFFFFF : CHIP);
            c.drawRoundRect(new RectF(dp(2), 0, w - dp(2), h), dp(12), dp(12), p);
            int fg = selected ? 0xFF000000 : TEXT;
            float s = dp(11), cx = w / 2, cy = dp(26);
            box.set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2);
            c.save();
            if (Renderer.effectMatrix(demo, box, SystemClock.uptimeMillis() / 1000f, m)) c.concat(m);
            p.setColor(fg);
            if (effect == Layer.FX_NONE) c.drawCircle(cx, cy, dp(3), p);
            else c.drawRoundRect(box, dp(3), dp(3), p);
            c.restore();
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(dp(10.5f));
            p.setColor(selected ? 0xFF000000 : DIM);
            c.drawText(Layer.FX_NAME[effect], cx, h - dp(9), p);
            if (effect != Layer.FX_NONE) postInvalidateOnAnimation();
        }
    }

    /** Animated sample of a brush, drawn in white for the brush list. */
    static final class BrushPreview extends View {
        final int brush;
        BrushSettings s;
        private final Renderer r;
        private Stroke stroke;
        private String key = "";

        BrushPreview(Context c, int brush, BrushSettings s, Renderer r) {
            super(c);
            this.brush = brush;
            this.s = s;
            this.r = r;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0) return;
            boolean flat = Brush.doodle(brush);
            float size = flat ? Math.min(Math.max(s.size, 4) * 1.6f, h * 0.42f) : Math.min(s.size, h * 0.32f);
            String k = w + "|" + size + "|" + s.wobble + "|" + s.speed + "|" + s.frames + "|" + s.alpha + "|" + s.spacing + "|" + s.echo;
            if (stroke == null || !k.equals(key)) {
                key = k;
                stroke = new Stroke();
                stroke.brush = brush;
                stroke.color = 0xFFFFFFFF;
                stroke.size = size;
                stroke.alpha = Math.max(0.35f, s.alpha);
                stroke.wobble = s.wobble;
                stroke.speed = s.speed;
                stroke.frames = s.frames;
                stroke.seed = brush * 977 + 13;
                stroke.spacing = s.spacing;
                stroke.echo = s.echo;
                float x0 = w * 0.06f, x1 = w * 0.94f, amp = flat ? h * 0.04f : h * 0.2f;
                for (int i = 0; i <= 64; i++) {
                    float u = i / 64f;
                    float wave = flat ? -(float) Math.sin(u * Math.PI) : (float) Math.sin(u * Math.PI * 2);
                    stroke.add(x0 + (x1 - x0) * u, h / 2 + wave * amp, flat ? 1f : (float) Math.sin(u * Math.PI) * 0.8f + 0.2f);
                }
            }
            r.drawStroke(c, stroke, SystemClock.uptimeMillis() / 1000f);
            postInvalidateOnAnimation();
        }
    }
}
