package com.boil.studio;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class EditorActivity extends Activity implements CanvasView.Host, RefView.Host {
    private static final int[] PALETTE = {
            0xFF000000, 0xFF3A3A3A, 0xFF6E6E6E, 0xFFA3A3A3, 0xFFD6D6D6, 0xFFFFFFFF, 0xFF2B2118, 0xFFF3E9DC,
            0xFFE5484D, 0xFFF76B15, 0xFFFFC53D, 0xFF46A758, 0xFF12A594, 0xFF3E7BFA, 0xFF8E4EC6, 0xFFE93D82};
    private static final int[] FPS = {4, 6, 8, 12, 15, 24};

    private FrameLayout root, overlay;
    private CanvasView cv;
    private Ui.Icon brushI, eraserI, lassoI, pickerI, fillI, fxI, layersI, undoI, redoI, playI, onionI, boilI, symI, rulerI;
    private Ui.Swatch colorS;
    private Ui.VSlider sizeS, alphaS;
    private LinearLayout frameStrip, selBar;
    private HorizontalScrollView frameScroll;
    private android.widget.ScrollView frameVScroll;
    private FrameLayout barLayer, framesHost;
    private LinearLayout editBar;
    private final java.util.LinkedHashMap<String, View> controls = new java.util.LinkedHashMap<>();
    private final BarFrame[] barFrames = new BarFrame[UiConfig.BARS];
    private final UiConfig ui = new UiConfig();
    private boolean editMode;
    private TextView fpsT, hint;
    private String panel;
    private Runnable panelRefresh;
    private String id;
    private static final int PICK_PICTURE = 11, PICK_REFERENCE = 12;
    private RefView ref;
    private View selAll, selRest;
    private boolean dirty, busy;
    private int prevTool = CanvasView.BRUSH;
    private final ArrayList<Integer> recent = new ArrayList<>();
    private SharedPreferences prefs;
    private final Handler handler = new Handler();
    private final Runnable hideHint = new Runnable() {
        @Override
        public void run() {
            hint.animate().alpha(0).setDuration(200).start();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        prefs = getSharedPreferences("boil", MODE_PRIVATE);
        id = getIntent().getStringExtra("id");
        Doc doc = null;
        if (id != null) {
            try {
                doc = Storage.load(this, id);
            } catch (IOException e) {
                doc = null;
            }
        }
        if (doc == null) {
            doc = new Doc(getIntent().getIntExtra("w", 1080), getIntent().getIntExtra("h", 1080));
            String n = getIntent().getStringExtra("name");
            if (n != null) doc.name = n;
            if (id == null) id = Storage.newId();
        }
        cv = new CanvasView(this);
        cv.host = this;
        loadPrefs();
        cv.setDoc(doc);
        ui.fromJson(prefs.getString("ui", null));
        build();
        layoutBars();
        immersive();
        syncAll();
        File rf = Storage.refFile(this, id);
        if (rf.exists()) {
            Bitmap rb = android.graphics.BitmapFactory.decodeFile(rf.getAbsolutePath());
            if (rb != null) showRef(rb);
        }
    }

    // ---------- layout ----------

    private int dp(float v) {
        return Ui.dp(v);
    }

    private Ui.Icon icon(String name, View.OnClickListener l) {
        Ui.Icon i = new Ui.Icon(this, name);
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(38), dp(38)));
        i.setOnClickListener(l);
        return i;
    }

    private void build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        root.addView(cv, new FrameLayout.LayoutParams(-1, -1));
        barLayer = new FrameLayout(this);
        root.addView(barLayer, new FrameLayout.LayoutParams(-1, -1));
        barLayer.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r - l != or - ol || b - t != ob - ot) {
                    barLayer.post(new Runnable() {
                        @Override
                        public void run() {
                            layoutBars();
                        }
                    });
                }
            }
        });
        createControls();

        // selection actions: whole-layer select, scale, copy, delete
        selBar = Ui.row(this);
        selBar.setBackground(Ui.round(Ui.PANEL, 23, Ui.LINE));
        selBar.setPadding(dp(6), dp(4), dp(6), dp(4));
        TextView all = Ui.button(this, "Весь слой", false);
        all.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!cv.selectAll()) showHint("На этом кадре слой пуст", true);
            }
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, -2);
        ap.rightMargin = dp(4);
        selBar.addView(all, ap);
        selBar.addView(icon("pivot", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startPivot();
            }
        }));
        selAll = all;
        LinearLayout rest = Ui.row(this);
        rest.addView(icon("minus", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cv.scaleSelection(1 / 1.1f);
            }
        }));
        rest.addView(icon("plus", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cv.scaleSelection(1.1f);
            }
        }));
        rest.addView(icon("rotl", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cv.rotateSelection(-1);
            }
        }));
        rest.addView(icon("rotr", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cv.rotateSelection(1);
            }
        }));
        rest.addView(icon("fliph", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cv.flipSelection(true);
            }
        }));
        rest.addView(icon("flipv", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cv.flipSelection(false);
            }
        }));
        rest.addView(icon("copy", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cv.duplicateSelection();
            }
        }));
        rest.addView(icon("trash", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cv.deleteSelection();
            }
        }));
        TextView done = Ui.button(this, "Готово", true);
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cv.clearSelection();
            }
        });
        LinearLayout.LayoutParams dpm = new LinearLayout.LayoutParams(-2, -2);
        dpm.leftMargin = dp(4);
        rest.addView(done, dpm);
        selBar.addView(rest);
        selRest = rest;
        selBar.setVisibility(View.GONE);
        // the bar can be wider than a phone screen, so it scrolls sideways
        HorizontalScrollView selScroll = new HorizontalScrollView(this);
        selScroll.setHorizontalScrollBarEnabled(false);
        selScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        selScroll.addView(selBar);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        sl.bottomMargin = dp(66);
        sl.leftMargin = dp(10);
        sl.rightMargin = dp(10);
        root.addView(selScroll, sl);

        // edit-mode instructions with a Done button
        editBar = Ui.row(this);
        editBar.setBackground(Ui.round(Ui.PANEL, 20, Ui.LINE));
        editBar.setPadding(dp(14), dp(8), dp(8), dp(8));
        TextView et = Ui.text(this, "Тяните панели · касание — повернуть", 13, Ui.TEXT, false);
        editBar.addView(et);
        TextView ed = Ui.button(this, "Готово", true);
        ed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setEditMode(false);
            }
        });
        LinearLayout.LayoutParams edl = new LinearLayout.LayoutParams(-2, -2);
        edl.leftMargin = dp(10);
        editBar.addView(ed, edl);
        editBar.setVisibility(View.GONE);
        root.addView(editBar, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // transient hint
        hint = Ui.text(this, "", 13, Ui.TEXT, true);
        hint.setBackground(Ui.round(Ui.PANEL, 16, Ui.LINE));
        hint.setPadding(dp(14), dp(9), dp(14), dp(9));
        hint.setAlpha(0);
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hl.topMargin = dp(64);
        root.addView(hint, hl);

        // popover layer
        overlay = new FrameLayout(this);
        overlay.setVisibility(View.GONE);
        overlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closePanel();
            }
        });
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
    }

    private TextView selButton(String s, View.OnClickListener l) {
        TextView t = Ui.button(this, s, false);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.rightMargin = dp(6);
        t.setLayoutParams(p);
        return t;
    }

    // ---------- customizable bars ----------

    /** Creates every control once; layoutBars() places them on the bars the user chose. */
    private void createControls() {
        controls.clear();
        controls.put("menu", icon("menu", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePanel("menu", Gravity.TOP | Gravity.LEFT);
            }
        }));
        brushI = icon("brush", toolClick(CanvasView.BRUSH));
        eraserI = icon("eraser", toolClick(CanvasView.ERASER));
        lassoI = icon("lasso", toolClick(CanvasView.LASSO));
        pickerI = icon("picker", toolClick(CanvasView.PICKER));
        fillI = icon("fill", toolClick(CanvasView.FILL));
        fxI = icon("effect", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePanel("fx", Gravity.TOP | Gravity.RIGHT);
            }
        });
        layersI = icon("layers", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePanel("layers", Gravity.TOP | Gravity.RIGHT);
            }
        });
        colorS = new Ui.Swatch(this, cv.color);
        colorS.ring = true;
        colorS.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePanel("color", Gravity.TOP | Gravity.RIGHT);
            }
        });
        controls.put("brush", brushI);
        controls.put("eraser", eraserI);
        controls.put("lasso", lassoI);
        controls.put("picker", pickerI);
        controls.put("fill", fillI);
        controls.put("fx", fxI);
        controls.put("layers", layersI);
        controls.put("color", colorS);

        undoI = icon("undo", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cv.undo();
            }
        });
        redoI = icon("redo", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cv.redo();
            }
        });
        controls.put("undo", undoI);
        controls.put("redo", redoI);
        symI = icon("sym", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cv.sym = (cv.sym + 1) % 4;
                syncGuides();
                showHint(new String[]{"Симметрия выключена", "Симметрия: слева и справа",
                        "Симметрия: сверху и снизу", "Симметрия: в четыре стороны"}[cv.sym]
                        + (cv.sym != 0 ? " · ось можно двигать за кружок" : ""), true);
            }
        });
        rulerI = icon("ruler", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cv.ruler = (cv.ruler + 1) % 3;
                syncGuides();
                showHint(new String[]{"Линейка выключена", "Прямые линии: тяните от начала до конца",
                        "Линейка: линии идут вдоль неё · кружки двигают и наклоняют"}[cv.ruler], true);
            }
        });
        controls.put("symmetry", symI);
        controls.put("ruler", rulerI);
        sizeS = new Ui.VSlider(this, new Ui.OnSlide() {
            @Override
            void change(float v) {
                BrushSettings bs = cv.settings();
                bs.size = vToSize(v);
                cv.previewSize = bs.size;
                showHint("Размер " + fmtSize(bs.size), false);
            }

            @Override
            void end() {
                cv.previewSize = 0;
                handler.postDelayed(hideHint, 600);
            }
        });
        alphaS = new Ui.VSlider(this, new Ui.OnSlide() {
            @Override
            void change(float v) {
                cv.settings().alpha = Math.max(0.02f, v);
                showHint("Непрозрачность " + Math.round(cv.settings().alpha * 100) + "%", false);
            }

            @Override
            void end() {
                handler.postDelayed(hideHint, 600);
            }
        });
        controls.put("size", sizeS);
        controls.put("opacity", alphaS);

        playI = icon("play", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closePanel();
                if (cv.doc.frames < 2) {
                    showHint("Добавьте кадр, чтобы проиграть анимацию", true);
                    return;
                }
                cv.setPlaying(!cv.playing);
                playI.setName(cv.playing ? "pause" : "play");
            }
        });
        controls.put("play", playI);
        frameScroll = new HorizontalScrollView(this);
        frameScroll.setHorizontalScrollBarEnabled(false);
        frameScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        frameVScroll = new android.widget.ScrollView(this);
        frameVScroll.setVerticalScrollBarEnabled(false);
        frameVScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        frameStrip = Ui.row(this);
        framesHost = new FrameLayout(this);
        controls.put("frames", framesHost);
        controls.put("addFrame", icon("plus", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addFrame(false);
            }
        }));
        controls.put("dupFrame", icon("dup", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addFrame(true);
            }
        }));
        controls.put("delFrame", icon("trash", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteFrame();
            }
        }));
        onionI = icon("onion", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cv.onion = !cv.onion;
                onionI.setActive(cv.onion);
                showHint(cv.onion ? "Калька включена" : "Калька выключена", true);
            }
        });
        boilI = icon("boil", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleBoil();
            }
        });
        controls.put("onion", onionI);
        controls.put("boil", boilI);
        fpsT = Ui.text(this, "", 12, Ui.TEXT, true);
        fpsT.setGravity(Gravity.CENTER);
        fpsT.setPadding(dp(6), dp(8), dp(6), dp(8));
        fpsT.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int cur = 0;
                for (int i = 0; i < FPS.length; i++) if (FPS[i] == cv.doc.fps) cur = i;
                cv.doc.fps = FPS[(cur + 1) % FPS.length];
                if (cv.playing) cv.setPlaying(true);
                dirty = true;
                fpsT.setText(cv.doc.fps + " к/с");
            }
        });
        controls.put("fps", fpsT);
    }

    private static void detach(View v) {
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    private int panelColor() {
        return (Math.round(ui.panelAlpha * 255) << 24) | 0x161616;
    }

    /** Rebuilds the four bars from the current UiConfig and places them on screen. */
    private void layoutBars() {
        int sw = barLayer.getWidth() > 0 ? barLayer.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int sh = barLayer.getHeight() > 0 ? barLayer.getHeight() : getResources().getDisplayMetrics().heightPixels;
        int S = dp(ui.iconDp);
        barLayer.removeAllViews();
        for (View v : controls.values()) detach(v);
        detach(frameStrip);
        detach(frameScroll);
        detach(frameVScroll);
        for (int b = 0; b < UiConfig.BARS; b++) {
            barFrames[b] = null;
            ArrayList<String> list = ui.bars.get(b);
            if (list.isEmpty()) continue;
            boolean vert = ui.vertical[b];
            LinearLayout bar = new LinearLayout(this);
            bar.setOrientation(vert ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER);
            bar.setPadding(dp(4), dp(4), dp(4), dp(4));
            int fixed = 0;
            for (String id : list) if (!"frames".equals(id)) fixed += "size".equals(id) || "opacity".equals(id) ? dp(120) : S;
            for (String id : list) {
                View v = controls.get(id);
                LinearLayout.LayoutParams lp;
                if ("size".equals(id) || "opacity".equals(id)) {
                    Ui.VSlider sl = (Ui.VSlider) v;
                    sl.horizontal = !vert;
                    int len = dp("size".equals(id) ? 130 : 100);
                    lp = vert ? new LinearLayout.LayoutParams(S, len) : new LinearLayout.LayoutParams(len, S);
                } else if ("frames".equals(id)) {
                    frameStrip.setOrientation(vert ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
                    ViewGroup sc = vert ? frameVScroll : frameScroll;
                    sc.addView(frameStrip);
                    framesHost.addView(sc, new FrameLayout.LayoutParams(-1, -1));
                    int avail = (vert ? sh : sw) - fixed - dp(40);
                    int len = Math.max(dp(110), Math.min(dp(380), avail));
                    lp = vert ? new LinearLayout.LayoutParams(S, len) : new LinearLayout.LayoutParams(len, S);
                } else if ("fps".equals(id)) {
                    lp = vert ? new LinearLayout.LayoutParams(-2, S) : new LinearLayout.LayoutParams(-2, S);
                } else {
                    lp = new LinearLayout.LayoutParams(S, S);
                }
                bar.addView(v, lp);
            }
            BarFrame f = new BarFrame(b);
            f.setBackground(Ui.round(panelColor(), 23, Ui.LINE));
            f.setClipToOutline(true);
            ViewGroup scroll;
            if (vert) {
                android.widget.ScrollView sv = new android.widget.ScrollView(this);
                sv.setVerticalScrollBarEnabled(false);
                sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
                scroll = sv;
            } else {
                HorizontalScrollView hv = new HorizontalScrollView(this);
                hv.setHorizontalScrollBarEnabled(false);
                hv.setOverScrollMode(View.OVER_SCROLL_NEVER);
                scroll = hv;
            }
            scroll.addView(bar);
            f.addView(scroll, new FrameLayout.LayoutParams(-2, -2));
            if (editMode) f.setForeground(editOutline());
            barLayer.addView(f, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT));
            barFrames[b] = f;
        }
        styleFrames();
        barLayer.post(new Runnable() {
            @Override
            public void run() {
                positionBars();
            }
        });
    }

    private void positionBars() {
        float W = barLayer.getWidth(), H = barLayer.getHeight();
        if (W <= 0) return;
        float m = dp(10);
        for (int b = 0; b < UiConfig.BARS; b++) {
            BarFrame f = barFrames[b];
            if (f == null) continue;
            float w = f.getWidth(), h = f.getHeight(), x, y;
            if (!Float.isNaN(ui.px[b])) {
                x = ui.px[b] * W - w / 2;
                y = ui.py[b] * H - h / 2;
            } else if (b == UiConfig.TOP) {
                x = (W - w) / 2;
                y = m;
            } else if (b == UiConfig.LEFT) {
                x = m;
                y = (H - h) / 2;
            } else if (b == UiConfig.BOTTOM) {
                x = (W - w) / 2;
                y = H - h - m;
            } else {
                x = W - w - m;
                y = (H - h) / 2;
            }
            f.setTranslationX(Math.max(dp(4), Math.min(W - w - dp(4), x)));
            f.setTranslationY(Math.max(dp(4), Math.min(H - h - dp(4), y)));
        }
    }

    private android.graphics.drawable.GradientDrawable editOutline() {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0x22FFFFFF);
        g.setCornerRadius(dp(23));
        g.setStroke(dp(2), 0xFFFFFFFF, dp(6), dp(4));
        return g;
    }

    private void saveUi() {
        prefs.edit().putString("ui", ui.toJson()).apply();
    }

    /** Wrapper of one bar: in edit mode it swallows touches to drag the bar, or turns it on a tap. */
    private final class BarFrame extends FrameLayout {
        final int index;
        private float downX, downY, startX, startY;
        private boolean dragged;

        BarFrame(int index) {
            super(EditorActivity.this);
            this.index = index;
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int pw = barLayer.getWidth() > 0 ? barLayer.getWidth() : getResources().getDisplayMetrics().widthPixels;
            int ph = barLayer.getHeight() > 0 ? barLayer.getHeight() : getResources().getDisplayMetrics().heightPixels;
            super.onMeasure(MeasureSpec.makeMeasureSpec(pw - dp(8), MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(ph - dp(8), MeasureSpec.AT_MOST));
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            return editMode;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!editMode) return super.onTouchEvent(e);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = getTranslationX();
                    startY = getTranslationY();
                    dragged = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (Math.abs(dx) + Math.abs(dy) > dp(8)) dragged = true;
                    float W = barLayer.getWidth(), H = barLayer.getHeight();
                    setTranslationX(Math.max(dp(4), Math.min(W - getWidth() - dp(4), startX + dx)));
                    setTranslationY(Math.max(dp(4), Math.min(H - getHeight() - dp(4), startY + dy)));
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (dragged) {
                        ui.px[index] = (getTranslationX() + getWidth() / 2f) / barLayer.getWidth();
                        ui.py[index] = (getTranslationY() + getHeight() / 2f) / barLayer.getHeight();
                    } else {
                        ui.vertical[index] = !ui.vertical[index];
                        layoutBars();
                    }
                    saveUi();
                    return true;
                default:
                    return true;
            }
        }
    }

    private void setEditMode(boolean on) {
        editMode = on;
        closePanel();
        for (BarFrame f : barFrames) if (f != null) f.setForeground(on ? editOutline() : null);
        editBar.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onDrawing(boolean drawing) {
        if (!ui.fadeWhileDrawing) return;
        barLayer.animate().alpha(drawing ? 0.12f : 1f).setDuration(drawing ? 120 : 220).start();
    }

    private View.OnClickListener toolClick(final int tool) {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTool(tool);
            }
        };
    }

    private void selectTool(int t) {
        if (cv.tool == t) {
            if (t == CanvasView.BRUSH || t == CanvasView.ERASER) togglePanel("brush", Gravity.TOP | Gravity.RIGHT);
            return;
        }
        closePanel();
        cv.clearSelection();
        if (t == CanvasView.PICKER && (cv.tool == CanvasView.BRUSH || cv.tool == CanvasView.ERASER)) prevTool = cv.tool;
        cv.tool = t;
        syncTools();
        syncRail();
        updateSelBar();
        switch (t) {
            case CanvasView.BRUSH:
                showHint(Brush.NAME[cv.brush] + " · нажмите ещё раз для настроек", true);
                break;
            case CanvasView.LASSO:
                showHint("Обведите штрихи или выберите весь слой, затем перетащите", true);
                break;
            case CanvasView.PICKER:
                showHint("Коснитесь холста, чтобы взять цвет", true);
                break;
            case CanvasView.FILL:
                showHint("Коснитесь замкнутой области", true);
                break;
            default:
                break;
        }
    }

    // ---------- sync ----------

    private void syncAll() {
        syncTools();
        syncRail();
        syncUndo();
        rebuildTimeline();
        onionI.setActive(cv.onion);
        boilI.setActive(cv.doc.boil);
        colorS.setColor(cv.color);
        syncGuides();
    }

    private void syncTools() {
        brushI.setActive(cv.tool == CanvasView.BRUSH);
        eraserI.setActive(cv.tool == CanvasView.ERASER);
        lassoI.setActive(cv.tool == CanvasView.LASSO);
        pickerI.setActive(cv.tool == CanvasView.PICKER);
        fillI.setActive(cv.tool == CanvasView.FILL);
        Layer l = cv.activeLayer();
        fxI.setActive(l.hasEffect());
    }

    private void syncRail() {
        BrushSettings bs = cv.settings();
        sizeS.set(sizeToV(bs.size));
        alphaS.set(bs.alpha);
    }

    private void syncUndo() {
        undoI.setDim(cv.history.undo.isEmpty());
        redoI.setDim(cv.history.redo.isEmpty());
    }

    private static float vToSize(float v) {
        return 1 + 199 * (float) Math.pow(v, 2.2);
    }

    private static float sizeToV(float s) {
        return (float) Math.pow(Math.max(0, (s - 1) / 199f), 1 / 2.2);
    }

    private static String fmtSize(float s) {
        return s < 10 ? String.format(Locale.US, "%.1f px", s) : Math.round(s) + " px";
    }

    private void showHint(String s, boolean autoHide) {
        hint.setText(s);
        hint.animate().cancel();
        hint.setAlpha(1);
        handler.removeCallbacks(hideHint);
        if (autoHide) handler.postDelayed(hideHint, 1600);
    }

    // ---------- timeline ----------

    private void rebuildTimeline() {
        frameStrip.removeAllViews();
        final Layer L = cv.activeLayer();
        for (int i = 0; i < cv.doc.frames; i++) {
            final int f = i;
            TextView cell = Ui.text(this, String.valueOf(i + 1), 12, Ui.TEXT, true);
            cell.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(32), dp(32));
            p.setMargins(dp(2), dp(3), dp(2), dp(3));
            cell.setLayoutParams(p);
            cell.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (cv.playing) {
                        cv.setPlaying(false);
                        playI.setName("play");
                    }
                    cv.setFrame(f);
                }
            });
            cell.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    toggleKey(L, f);
                    return true;
                }
            });
            frameStrip.addView(cell);
        }
        styleFrames();
        fpsT.setText(cv.doc.fps + " к/с");
        playI.setDim(cv.doc.frames < 2);
    }

    private void styleFrames() {
        Layer L = cv.activeLayer();
        for (int i = 0; i < frameStrip.getChildCount(); i++) {
            TextView c = (TextView) frameStrip.getChildAt(i);
            boolean key = i < L.cels.size() && L.cels.get(i) != null;
            if (i == cv.frame) {
                c.setBackground(Ui.round(0xFFFFFFFF, 10, 0));
                c.setTextColor(0xFF000000);
            } else if (key) {
                c.setBackground(Ui.round(Ui.CHIP, 10, 0));
                c.setTextColor(Ui.TEXT);
            } else {
                c.setBackground(Ui.round(0x00000000, 10, Ui.LINE));
                c.setTextColor(Ui.FAINT);
            }
        }
        final View cur = frameStrip.getChildAt(cv.frame);
        if (cur != null) {
            frameScroll.post(new Runnable() {
                @Override
                public void run() {
                    if (frameStrip.getOrientation() == LinearLayout.VERTICAL) {
                        int y = cur.getTop() - frameVScroll.getHeight() / 2 + cur.getHeight() / 2;
                        frameVScroll.smoothScrollTo(0, Math.max(0, y));
                    } else {
                        int x = cur.getLeft() - frameScroll.getWidth() / 2 + cur.getWidth() / 2;
                        frameScroll.smoothScrollTo(Math.max(0, x), 0);
                    }
                }
            });
        }
    }

    private void toggleKey(Layer L, int f) {
        if (L.cels.get(f) == null) {
            cv.history.push(cv);
            L.cels.set(f, new Cel());
            showHint("Новый рисунок на кадре " + (f + 1), true);
        } else if (f > 0) {
            cv.history.push(cv);
            L.cels.set(f, null);
            showHint("Кадр " + (f + 1) + " держит предыдущий рисунок", true);
        } else {
            return;
        }
        cv.setFrame(f);
        cv.changed();
    }

    private void addFrame(boolean dup) {
        if (cv.playing) {
            cv.setPlaying(false);
            playI.setName("play");
        }
        cv.history.push(cv);
        cv.doc.insertFrame(cv.frame + 1, cv.activeLayer(), dup);
        cv.setFrame(cv.frame + 1);
        cv.changed();
        showHint(dup ? "Кадр продублирован" : "Новый кадр " + (cv.frame + 1), true);
    }

    private void deleteFrame() {
        if (cv.playing) {
            cv.setPlaying(false);
            playI.setName("play");
        }
        cv.history.push(cv);
        if (cv.doc.frames == 1) {
            Cel c = cv.activeCel();
            c.items.clear();
            c.touch();
            showHint("Слой очищен", true);
        } else {
            cv.doc.deleteFrame(cv.frame);
            cv.setFrame(Math.min(cv.frame, cv.doc.frames - 1));
            showHint("Кадр удалён", true);
        }
        cv.changed();
    }

    // ---------- host ----------

    @Override
    public void onDocChanged() {
        dirty = true;
        syncUndo();
        syncTools();
        rebuildTimeline();
        if (panelRefresh != null) panelRefresh.run();
    }

    @Override
    public void onColorPicked(int color) {
        setColor(color, true);
        cv.tool = prevTool;
        syncTools();
        syncRail();
    }

    @Override
    public void onSelection(boolean has) {
        updateSelBar();
        if (has) showHint("Крутите двумя пальцами или за кружок над рамкой", true);
    }

    private void updateSelBar() {
        boolean has = !cv.sel.isEmpty();
        boolean lasso = cv.tool == CanvasView.LASSO;
        selBar.setVisibility(has || lasso ? View.VISIBLE : View.GONE);
        selRest.setVisibility(has ? View.VISIBLE : View.GONE);
        selAll.setVisibility(has ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onFrame() {
        styleFrames();
        if (panelRefresh != null && "layers".equals(panel)) panelRefresh.run();
    }

    @Override
    public void onPlayStopped() {
        playI.setName("play");
    }

    private void setColor(int c, boolean remember) {
        cv.color = c | 0xFF000000;
        colorS.setColor(cv.color);
        if (remember) {
            recent.remove(Integer.valueOf(cv.color));
            recent.add(0, cv.color);
            while (recent.size() > 8) recent.remove(recent.size() - 1);
        }
    }

    // ---------- panels ----------

    private void togglePanel(String name, int gravity) {
        if (name.equals(panel)) {
            closePanel();
            return;
        }
        closePanel();
        if (cv.playing && !"menu".equals(name)) {
            cv.setPlaying(false);
            playI.setName("play");
        }
        panel = name;
        LinearLayout content = Ui.col(this);
        switch (name) {
            case "brush":
                brushPanel(content);
                break;
            case "color":
                colorPanel(content);
                break;
            case "layers":
                layersPanel(content);
                break;
            case "fx":
                fxPanel(content);
                break;
            case "ui":
                uiPanel(content);
                break;
            default:
                menuPanel(content);
                break;
        }
        int sw = root.getWidth() > 0 ? root.getWidth() : getResources().getDisplayMetrics().widthPixels;
        int sh = root.getHeight() > 0 ? root.getHeight() : getResources().getDisplayMetrics().heightPixels;
        LinearLayout card = Ui.col(this);
        card.setBackground(Ui.round(Ui.PANEL, 22, Ui.LINE));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setClickable(true);
        Ui.MaxScroll sc = new Ui.MaxScroll(this, sh - dp(100));
        sc.addView(content);
        card.addView(sc);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.min(dp(340), sw - dp(20)), -2, gravity);
        lp.topMargin = dp(60);
        lp.leftMargin = dp(10);
        lp.rightMargin = dp(10);
        overlay.addView(card, lp);
        overlay.setVisibility(View.VISIBLE);
        card.setAlpha(0);
        card.setTranslationY(-dp(8));
        card.animate().alpha(1).translationY(0).setDuration(150).start();
    }

    private void syncGuides() {
        symI.setName(cv.sym == 2 ? "symh" : cv.sym == 3 ? "symb" : "sym");
        symI.setActive(cv.sym != 0);
        rulerI.setName(cv.ruler == 1 ? "line" : "ruler");
        rulerI.setActive(cv.ruler != 0);
    }

    private void toggleBoil() {
        cv.doc.boil = !cv.doc.boil;
        boilI.setActive(cv.doc.boil);
        dirty = true;
        showHint(cv.doc.boil ? "Дрожание линий включено" : "Дрожание линий выключено", true);
    }

    /** Next touch on the canvas sets the active layer's base point. */
    private void startPivot() {
        closePanel();
        cv.pivotMode = true;
        showHint("Коснитесь холста, чтобы поставить точку основы", true);
    }

    private void closePanel() {
        cv.showPivot = false;
        panel = null;
        panelRefresh = null;
        overlay.removeAllViews();
        overlay.setVisibility(View.GONE);
    }

    private LinearLayout header(LinearLayout parent, String title, String sub) {
        LinearLayout h = Ui.row(this);
        LinearLayout tc = Ui.col(this);
        tc.addView(Ui.text(this, title, 17, Ui.TEXT, true));
        if (sub != null) {
            TextView s = Ui.text(this, sub, 12, Ui.DIM, false);
            s.setPadding(0, dp(4), 0, 0);
            tc.addView(s);
        }
        h.addView(tc, Ui.lpw(1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(10);
        parent.addView(h, p);
        return h;
    }

    private TextView textAction(String s) {
        TextView t = Ui.text(this, s, 13, Ui.DIM, true);
        t.setPadding(dp(10), dp(8), dp(4), dp(8));
        t.setClickable(true);
        return t;
    }

    private void brushPanel(LinearLayout c) {
        final boolean er = cv.tool == CanvasView.ERASER;
        LinearLayout h = header(c, er ? "Ластик" : "Кисти", er ? "стирает с дрожанием" : null);
        TextView reset = textAction("Сбросить");
        h.addView(reset);

        final ArrayList<Ui.Slider> sliders = new ArrayList<>();
        final Ui.Toggle echo = new Ui.Toggle(this, cv.settings().echo);
        final Runnable bind = new Runnable() {
            @Override
            public void run() {
                BrushSettings bs = cv.settings();
                float shown = bs.speed / 4f;
                sliders.get(0).set((shown - 0.25f) / 5.75f, String.format(Locale.US, "%.1f", shown));
                sliders.get(1).set(bs.wobble / 10f, String.format(Locale.US, "%.1f", bs.wobble));
                sliders.get(2).set((bs.spacing - 1) / 19f, Math.round(bs.spacing) + " px");
                sliders.get(3).set(bs.stabilize, Math.round(bs.stabilize * 100) + "%");
                sliders.get(4).set((bs.frames - 1) / 7f, String.valueOf(bs.frames));
                sliders.get(5).set(sizeToV(bs.size), fmtSize(bs.size));
                sliders.get(6).set(bs.alpha, Math.round(bs.alpha * 100) + "%");
                echo.on = bs.echo;
                echo.invalidate();
                syncRail();
            }
        };

        if (!er) {
            // category rail on the left, brush list with live previews on the right
            LinearLayout split = Ui.row(this);
            split.setGravity(Gravity.TOP);
            final LinearLayout rail = Ui.col(this);
            rail.setGravity(Gravity.CENTER_HORIZONTAL);
            final LinearLayout list = Ui.col(this);
            final ArrayList<Ui.Icon> cats = new ArrayList<>();
            final int[] shownCat = {Brush.category(cv.brush)};
            final Runnable[] fillList = new Runnable[1];
            fillList[0] = new Runnable() {
                @Override
                public void run() {
                    list.removeAllViews();
                    TextView cap = Ui.text(EditorActivity.this, Brush.CAT_NAME[shownCat[0]], 11, Ui.DIM, false);
                    cap.setPadding(dp(4), 0, 0, dp(6));
                    list.addView(cap);
                    for (final int bi : Brush.CAT[shownCat[0]]) {
                        LinearLayout row = Ui.col(EditorActivity.this);
                        row.setPadding(dp(10), dp(8), dp(10), dp(4));
                        row.setClickable(true);
                        row.setBackground(Ui.round(bi == cv.brush ? 0xFF3A3A3A : 0xFF202020, 12, 0));
                        LinearLayout top = Ui.row(EditorActivity.this);
                        top.addView(Ui.text(EditorActivity.this, Brush.NAME[bi], 13, Ui.TEXT, true));
                        TextView hn = Ui.text(EditorActivity.this, "  " + Brush.HINT[bi], 11, Ui.DIM, false);
                        hn.setSingleLine(true);
                        top.addView(hn, Ui.lpw(1));
                        row.addView(top);
                        Ui.BrushPreview pv = new Ui.BrushPreview(EditorActivity.this, bi, cv.brushes[bi], cv.renderer);
                        row.addView(pv, new LinearLayout.LayoutParams(-1, dp(40)));
                        row.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                cv.brush = bi;
                                fillList[0].run();
                                bind.run();
                            }
                        });
                        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                        rp.bottomMargin = dp(6);
                        list.addView(row, rp);
                    }
                }
            };
            for (int ci = 0; ci < Brush.CAT_NAME.length; ci++) {
                final int cat = ci;
                Ui.Icon ic = new Ui.Icon(this, Brush.CAT_ICON[ci]);
                ic.setActive(ci == shownCat[0]);
                ic.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        shownCat[0] = cat;
                        for (int k = 0; k < cats.size(); k++) cats.get(k).setActive(k == cat);
                        fillList[0].run();
                    }
                });
                cats.add(ic);
                rail.addView(ic, new LinearLayout.LayoutParams(dp(36), dp(36)));
            }
            split.addView(rail, new LinearLayout.LayoutParams(dp(40), -2));
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, -2, 1);
            lp2.leftMargin = dp(8);
            split.addView(list, lp2);
            c.addView(split, new LinearLayout.LayoutParams(-1, -2));
            fillList[0].run();
            c.addView(Ui.divider(this, false));
        }

        sliders.add(new Ui.Slider(this, "Скорость", 0, new Ui.OnSlide() {
            @Override
            void change(float v) {
                cv.settings().speed = Math.round((0.25f + v * 5.75f) * 10) / 10f * 4f;
                bind.run();
            }
        }));
        sliders.add(new Ui.Slider(this, "Дрожание", 0, new Ui.OnSlide() {
            @Override
            void change(float v) {
                cv.settings().wobble = Math.round(v * 100) / 10f;
                bind.run();
            }
        }));
        sliders.add(new Ui.Slider(this, "Интервал", 0, new Ui.OnSlide() {
            @Override
            void change(float v) {
                cv.settings().spacing = 1 + Math.round(v * 19);
                bind.run();
            }
        }));
        sliders.add(new Ui.Slider(this, "Стабилизация", 0, new Ui.OnSlide() {
            @Override
            void change(float v) {
                cv.settings().stabilize = v;
                bind.run();
            }
        }));
        sliders.add(new Ui.Slider(this, "Кадров дрожания", 0, new Ui.OnSlide() {
            @Override
            void change(float v) {
                cv.settings().frames = 1 + Math.round(v * 7);
                bind.run();
            }
        }));
        sliders.add(new Ui.Slider(this, "Размер", 0, new Ui.OnSlide() {
            @Override
            void change(float v) {
                cv.settings().size = vToSize(v);
                bind.run();
            }
        }));
        sliders.add(new Ui.Slider(this, "Непрозрачность", 0, new Ui.OnSlide() {
            @Override
            void change(float v) {
                cv.settings().alpha = Math.max(0.02f, v);
                bind.run();
            }
        }));
        for (Ui.Slider s : sliders) c.addView(s, new LinearLayout.LayoutParams(-1, -2));

        if (!er) {
            LinearLayout er2 = Ui.row(this);
            er2.setPadding(0, dp(10), 0, dp(4));
            LinearLayout names = Ui.col(this);
            names.addView(Ui.text(this, "Эхо кадров", 14, Ui.TEXT, false));
            TextView eh = Ui.text(this, "прошлый кадр дрожания остаётся тенью", 11, Ui.DIM, false);
            eh.setPadding(0, dp(3), 0, 0);
            names.addView(eh);
            er2.addView(names, Ui.lpw(1));
            echo.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cv.settings().echo = !cv.settings().echo;
                    bind.run();
                }
            });
            er2.addView(echo);
            c.addView(er2, new LinearLayout.LayoutParams(-1, -2));
        }

        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BrushSettings d = er ? BrushSettings.eraser() : BrushSettings.defaults(cv.brush);
                BrushSettings bs = cv.settings();
                bs.size = d.size;
                bs.alpha = d.alpha;
                bs.wobble = d.wobble;
                bs.speed = d.speed;
                bs.frames = d.frames;
                bs.stabilize = d.stabilize;
                bs.spacing = d.spacing;
                bs.echo = d.echo;
                bind.run();
            }
        });
        bind.run();
    }

    private void colorPanel(LinearLayout c) {
        header(c, "Цвет", null);
        final Ui.Swatch cur = new Ui.Swatch(this, cv.color);
        final EditText hex = new EditText(this);
        final Ui.ColorPicker pk = new Ui.ColorPicker(this, cv.color, new Ui.ColorPicker.Listener() {
            @Override
            public void onColor(int color, boolean done) {
                setColor(color, done);
                cur.setColor(cv.color);
                if (!hex.hasFocus()) hex.setText(hexOf(cv.color));
            }
        });
        c.addView(pk, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout r = Ui.row(this);
        r.addView(cur, new LinearLayout.LayoutParams(dp(40), dp(40)));
        hex.setText(hexOf(cv.color));
        hex.setTextColor(Ui.TEXT);
        hex.setTextSize(15);
        hex.setSingleLine(true);
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        hex.setImeOptions(EditorInfo.IME_ACTION_DONE);
        hex.setBackground(Ui.round(Ui.CHIP, 12, 0));
        hex.setPadding(dp(12), dp(8), dp(12), dp(8));
        hex.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int action, KeyEvent ev) {
                String s = v.getText().toString().trim().replace("#", "");
                try {
                    if (s.length() == 3) s = "" + s.charAt(0) + s.charAt(0) + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2);
                    int col = (int) Long.parseLong(s, 16);
                    setColor(col, true);
                    pk.setColor(cv.color);
                    cur.setColor(cv.color);
                } catch (NumberFormatException e) {
                    showHint("Неверный HEX", true);
                }
                v.clearFocus();
                immersive();
                return false;
            }
        });
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, -2, 1);
        hp.leftMargin = dp(10);
        r.addView(hex, hp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = dp(12);
        c.addView(r, rp);

        label(c, "Палитра");
        swatchGrid(c, toList(PALETTE), pk, cur, hex);
        if (!recent.isEmpty()) {
            label(c, "Недавние");
            swatchGrid(c, new ArrayList<>(recent), pk, cur, hex);
        }
    }

    private static ArrayList<Integer> toList(int[] a) {
        ArrayList<Integer> l = new ArrayList<>();
        for (int v : a) l.add(v);
        return l;
    }

    private static String hexOf(int c) {
        return String.format(Locale.US, "#%06X", c & 0xFFFFFF);
    }

    private void label(LinearLayout c, String s) {
        TextView t = Ui.text(this, s, 12, Ui.DIM, false);
        t.setPadding(0, dp(14), 0, dp(6));
        c.addView(t);
    }

    private void swatchGrid(LinearLayout c, ArrayList<Integer> colors, final Ui.ColorPicker pk, final Ui.Swatch cur, final EditText hex) {
        LinearLayout row = null;
        for (int i = 0; i < colors.size(); i++) {
            if (i % 8 == 0) {
                row = Ui.row(this);
                c.addView(row, new LinearLayout.LayoutParams(-1, dp(36)));
            }
            final int col = colors.get(i);
            Ui.Swatch s = new Ui.Swatch(this, col);
            s.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setColor(col, true);
                    pk.setColor(col);
                    cur.setColor(cv.color);
                    hex.setText(hexOf(cv.color));
                }
            });
            row.addView(s, new LinearLayout.LayoutParams(0, -1, 1));
        }
        if (row != null) for (int i = colors.size() % 8; i > 0 && i < 8; i++) row.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 1));
    }

    private void layersPanel(final LinearLayout c) {
        LinearLayout h = header(c, "Слои", null);
        h.addView(smallIcon("plus", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                layerOp(0);
            }
        }));
        h.addView(smallIcon("dup", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                layerOp(1);
            }
        }));
        h.addView(smallIcon("trash", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                layerOp(2);
            }
        }));
        h.addView(smallIcon("up", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                layerOp(3);
            }
        }));
        h.addView(smallIcon("down", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                layerOp(4);
            }
        }));
        h.addView(smallIcon("merge", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                layerOp(5);
            }
        }));
        final LinearLayout list = Ui.col(this);
        c.addView(list, new LinearLayout.LayoutParams(-1, -2));
        final Ui.Slider op = new Ui.Slider(this, "Непрозрачность слоя", 1, new Ui.OnSlide() {
            @Override
            void start() {
                cv.history.push(cv);
            }

            @Override
            void change(float v) {
                cv.activeLayer().opacity = v;
            }

            @Override
            void end() {
                cv.changed();
            }
        });
        LinearLayout.LayoutParams opl = new LinearLayout.LayoutParams(-1, -2);
        opl.topMargin = dp(6);
        c.addView(op, opl);
        panelRefresh = new Runnable() {
            @Override
            public void run() {
                fillLayers(list);
                Layer l = cv.activeLayer();
                op.set(l.opacity, Math.round(l.opacity * 100) + "%");
            }
        };
        panelRefresh.run();
        op.l = new Ui.OnSlide() {
            @Override
            void start() {
                cv.history.push(cv);
            }

            @Override
            void change(float v) {
                cv.activeLayer().opacity = v;
                op.set(v, Math.round(v * 100) + "%");
            }

            @Override
            void end() {
                cv.changed();
            }
        };
    }

    private Ui.Icon smallIcon(String name, View.OnClickListener l) {
        Ui.Icon i = new Ui.Icon(this, name);
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(34), dp(34)));
        i.setOnClickListener(l);
        return i;
    }

    private void fillLayers(LinearLayout list) {
        list.removeAllViews();
        Doc d = cv.doc;
        int tw = dp(52), th = Math.max(dp(20), Math.round(tw * d.h / (float) d.w));
        if (th > dp(64)) {
            th = dp(64);
            tw = Math.round(th * d.w / (float) d.h);
        }
        for (int i = d.layers.size() - 1; i >= 0; i--) {
            final int li = i;
            final Layer L = d.layers.get(i);
            boolean active = i == cv.layer;
            LinearLayout row = Ui.row(this);
            row.setPadding(dp(8), dp(6), dp(4), dp(6));
            row.setBackground(active ? Ui.round(0xFF262626, 14, 0) : null);
            ImageView thumb = new ImageView(this);
            Bitmap bm = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
            bm.eraseColor((d.bg >>> 24) < 255 ? 0xFFFFFFFF : d.bg);
            Canvas tc = new Canvas(bm);
            tc.scale(tw / (float) d.w, th / (float) d.h);
            cv.renderer.render(tc, d, cv.frame, 0, false, false, null, L);
            thumb.setImageBitmap(bm);
            thumb.setBackground(Ui.round(0xFFFFFFFF, 6, Ui.LINE));
            thumb.setPadding(1, 1, 1, 1);
            thumb.setAlpha(L.visible ? 1f : 0.35f);
            row.addView(thumb, new LinearLayout.LayoutParams(tw, th));
            LinearLayout names = Ui.col(this);
            names.setPadding(dp(12), 0, dp(6), 0);
            names.addView(Ui.text(this, L.name, 14, L.visible ? Ui.TEXT : Ui.DIM, true));
            String sub = (L.hasEffect() ? (L.effect != 0 ? Layer.FX_NAME[L.effect] : "Пульсация") : "Без эффекта")
                    + (L.opacity < 0.995f ? " · " + Math.round(L.opacity * 100) + "%" : "");
            TextView st = Ui.text(this, sub, 11, Ui.DIM, false);
            st.setPadding(0, dp(4), 0, 0);
            names.addView(st);
            row.addView(names, Ui.lpw(1));
            Ui.Icon eye = smallIcon(L.visible ? "eye" : "eyeoff", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cv.history.push(cv);
                    L.visible = !L.visible;
                    cv.changed();
                }
            });
            eye.setDim(!L.visible);
            row.addView(eye);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (cv.layer == li) {
                        renameLayer(L);
                        return;
                    }
                    cv.clearSelection();
                    cv.layer = li;
                    onDocChanged();
                }
            });
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        TextView tip = Ui.text(this, "Нажмите на активный слой, чтобы переименовать. Последняя кнопка сверху объединяет его с нижним.", 11, Ui.FAINT, false);
        tip.setPadding(dp(8), dp(8), 0, 0);
        list.addView(tip);
    }

    private void layerOp(int op) {
        Doc d = cv.doc;
        int i = cv.layer;
        Layer L = cv.activeLayer();
        if (op == 2 && d.layers.size() == 1) {
            showHint("Нужен хотя бы один слой", true);
            return;
        }
        if ((op == 3 && i >= d.layers.size() - 1) || (op == 4 && i == 0)) return;
        if (op == 5) {
            String problem = d.mergeProblem(i);
            if (problem != null) {
                showHint(problem, true);
                return;
            }
        }
        cv.clearSelection();
        cv.history.push(cv);
        switch (op) {
            case 0: {
                Layer n = new Layer(d.uniqueLayerName(), d.frames);
                d.layers.add(i + 1, n);
                cv.layer = i + 1;
                break;
            }
            case 1: {
                Layer n = L.copy();
                n.name = L.name + " копия";
                d.layers.add(i + 1, n);
                cv.layer = i + 1;
                break;
            }
            case 2:
                d.layers.remove(i);
                cv.layer = Math.max(0, i - 1);
                break;
            case 5:
                d.mergeDown(i);
                cv.layer = i - 1;
                showHint("Слой объединён с нижним", true);
                break;
            case 3:
                d.layers.set(i, d.layers.get(i + 1));
                d.layers.set(i + 1, L);
                cv.layer = i + 1;
                break;
            default:
                d.layers.set(i, d.layers.get(i - 1));
                d.layers.set(i - 1, L);
                cv.layer = i - 1;
                break;
        }
        cv.changed();
    }

    private void renameLayer(final Layer L) {
        prompt("Имя слоя", L.name, new Done() {
            @Override
            public void done(String s) {
                cv.history.push(cv);
                L.name = s;
                cv.changed();
            }
        });
    }

    private void fxPanel(LinearLayout c) {
        final Layer L = cv.activeLayer();
        header(c, "Эффект слоя", L.name + " · анимация всего слоя");
        LinearLayout chips = Ui.row(this);
        final ArrayList<Ui.EffectChip> list = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            final int fx = i;
            Ui.EffectChip ch = new Ui.EffectChip(this, i);
            ch.selected = L.effect == i;
            ch.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cv.history.push(cv);
                    L.effect = fx;
                    for (Ui.EffectChip e : list) {
                        e.selected = e.effect == fx;
                        e.invalidate();
                    }
                    cv.changed();
                }
            });
            list.add(ch);
            chips.addView(ch, new LinearLayout.LayoutParams(0, -2, 1));
        }
        c.addView(chips, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout tr = Ui.row(this);
        tr.setPadding(0, dp(14), 0, dp(6));
        tr.addView(Ui.text(this, "Пульсация размера", 14, Ui.TEXT, false), Ui.lpw(1));
        final Ui.Toggle tg = new Ui.Toggle(this, L.fxScale);
        tg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cv.history.push(cv);
                L.fxScale = !L.fxScale;
                tg.on = L.fxScale;
                tg.invalidate();
                cv.changed();
            }
        });
        tr.addView(tg);
        c.addView(tr, new LinearLayout.LayoutParams(-1, -2));

        final Ui.Slider sp = fxSlider(c, "Скорость");
        final Ui.Slider am = fxSlider(c, "Сила");
        final Ui.Slider ro = fxSlider(c, "Поворот");
        sp.l = fxListener(sp, L, 0);
        am.l = fxListener(am, L, 1);
        ro.l = fxListener(ro, L, 2);
        sp.set(L.fxSpeed, Math.round(L.fxSpeed * 100) + "%");
        am.set(L.fxAmount, Math.round(L.fxAmount * 100) + "%");
        ro.set(L.fxRotate, Math.round(L.fxRotate * 100) + "%");

        cv.showPivot = true;
        LinearLayout pr = Ui.row(this);
        pr.setPadding(0, dp(12), 0, dp(4));
        LinearLayout pn = Ui.col(this);
        pn.addView(Ui.text(this, "Точка основы", 14, Ui.TEXT, false));
        TextView ps = Ui.text(this, L.hasPivot ? "центр анимации задан вручную" : "авто: центр рисунка", 11, Ui.DIM, false);
        ps.setPadding(0, dp(3), 0, 0);
        pn.addView(ps);
        pr.addView(pn, Ui.lpw(1));
        TextView setP = Ui.button(this, "Задать", false);
        setP.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startPivot();
            }
        });
        pr.addView(setP);
        if (L.hasPivot) {
            TextView autoP = Ui.button(this, "Авто", false);
            autoP.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cv.clearPivot();
                    togglePanel("fx", Gravity.TOP | Gravity.RIGHT);
                    togglePanel("fx", Gravity.TOP | Gravity.RIGHT);
                }
            });
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, -2);
            al.leftMargin = dp(6);
            pr.addView(autoP, al);
        }
        c.addView(pr, new LinearLayout.LayoutParams(-1, -2));

        TextView rm = Ui.text(this, "Убрать эффект", 14, Ui.TEXT, true);
        rm.setGravity(Gravity.CENTER);
        rm.setPadding(0, dp(12), 0, dp(4));
        rm.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cv.history.push(cv);
                L.effect = Layer.FX_NONE;
                L.fxScale = false;
                tg.on = false;
                tg.invalidate();
                for (Ui.EffectChip e : list) {
                    e.selected = e.effect == 0;
                    e.invalidate();
                }
                cv.changed();
            }
        });
        c.addView(rm, new LinearLayout.LayoutParams(-1, -2));
    }

    private Ui.Slider fxSlider(LinearLayout c, String label) {
        Ui.Slider s = new Ui.Slider(this, label, 0, null);
        c.addView(s, new LinearLayout.LayoutParams(-1, -2));
        return s;
    }

    private Ui.OnSlide fxListener(final Ui.Slider s, final Layer L, final int which) {
        return new Ui.OnSlide() {
            @Override
            void start() {
                cv.history.push(cv);
            }

            @Override
            void change(float v) {
                if (which == 0) L.fxSpeed = v;
                else if (which == 1) L.fxAmount = v;
                else L.fxRotate = v;
                s.set(v, Math.round(v * 100) + "%");
            }

            @Override
            void end() {
                cv.changed();
            }
        };
    }

    /** Full interface customisation: bar placement, order, visibility, orientation, sizes. */
    private void uiPanel(LinearLayout c) {
        header(c, "Интерфейс", "расставьте всё так, как удобно вам");
        LinearLayout actions = Ui.row(this);
        TextView move = Ui.button(this, "Двигать панели", true);
        move.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setEditMode(true);
            }
        });
        actions.addView(move, Ui.lpw(1));
        TextView reset = Ui.button(this, "Сбросить", false);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ui.reset();
                saveUi();
                layoutBars();
                togglePanel("ui", Gravity.TOP | Gravity.LEFT);
                togglePanel("ui", Gravity.TOP | Gravity.LEFT);
                showHint("Интерфейс сброшен", true);
            }
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
        rp.leftMargin = dp(8);
        actions.addView(reset, rp);
        c.addView(actions, new LinearLayout.LayoutParams(-1, -2));

        final Ui.Slider size = new Ui.Slider(this, "Размер кнопок", 0, null);
        size.l = new Ui.OnSlide() {
            @Override
            void change(float v) {
                ui.iconDp = Math.round(30 + v * 22);
                size.set(v, Math.round(ui.iconDp) + " dp");
            }

            @Override
            void end() {
                layoutBars();
                saveUi();
            }
        };
        size.set((ui.iconDp - 30) / 22f, Math.round(ui.iconDp) + " dp");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(8);
        c.addView(size, sp);
        final Ui.Slider alpha = new Ui.Slider(this, "Непрозрачность панелей", 0, null);
        alpha.l = new Ui.OnSlide() {
            @Override
            void change(float v) {
                ui.panelAlpha = 0.3f + v * 0.7f;
                alpha.set(v, Math.round(ui.panelAlpha * 100) + "%");
                for (BarFrame f : barFrames) if (f != null) f.setBackground(Ui.round(panelColor(), 23, Ui.LINE));
            }

            @Override
            void end() {
                saveUi();
            }
        };
        alpha.set((ui.panelAlpha - 0.3f) / 0.7f, Math.round(ui.panelAlpha * 100) + "%");
        c.addView(alpha, new LinearLayout.LayoutParams(-1, -2));
        toggleRow(c, "Прятать панели во время рисования", ui.fadeWhileDrawing, new Done() {
            @Override
            public void done(String s) {
                ui.fadeWhileDrawing = !ui.fadeWhileDrawing;
                saveUi();
            }
        });

        label(c, "Панели · вертикальная или горизонтальная");
        for (int b = 0; b < UiConfig.BARS; b++) {
            final int bi = b;
            toggleRow(c, UiConfig.BAR_NAMES[b] + " панель вертикальная", ui.vertical[b], new Done() {
                @Override
                public void done(String s) {
                    ui.vertical[bi] = !ui.vertical[bi];
                    saveUi();
                    layoutBars();
                }
            });
        }

        label(c, "Кнопки · где находятся и в каком порядке");
        final LinearLayout list = Ui.col(this);
        c.addView(list, new LinearLayout.LayoutParams(-1, -2));
        panelRefresh = new Runnable() {
            @Override
            public void run() {
                fillUiButtons(list);
            }
        };
        panelRefresh.run();
        TextView tip = Ui.text(this, "Нажмите на место кнопки, чтобы перенести её на другую панель или скрыть. Стрелки меняют порядок. Меню скрыть нельзя — через него открываются эти настройки.", 11, Ui.FAINT, false);
        tip.setPadding(dp(4), dp(10), dp(4), dp(2));
        tip.setLineSpacing(dp(2), 1);
        c.addView(tip);
    }

    private void toggleRow(LinearLayout c, String text, boolean on, final Done onToggle) {
        LinearLayout r = Ui.row(this);
        r.setPadding(0, dp(8), 0, dp(8));
        r.addView(Ui.text(this, text, 14, Ui.TEXT, false), Ui.lpw(1));
        final Ui.Toggle t = new Ui.Toggle(this, on);
        t.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                t.on = !t.on;
                t.invalidate();
                onToggle.done(null);
            }
        });
        r.addView(t);
        c.addView(r, new LinearLayout.LayoutParams(-1, -2));
    }

    private void fillUiButtons(LinearLayout list) {
        list.removeAllViews();
        for (int b = -1; b < UiConfig.BARS; b++) {
            ArrayList<String> ids = b < 0 ? ui.hidden : ui.bars.get(b);
            if (ids.isEmpty()) continue;
            TextView cap = Ui.text(this, b < 0 ? "Скрытые" : UiConfig.BAR_NAMES[b] + " панель", 12, Ui.DIM, true);
            cap.setPadding(dp(4), dp(10), 0, dp(4));
            list.addView(cap);
            for (final String id : new ArrayList<>(ids)) {
                LinearLayout row = Ui.row(this);
                row.setPadding(dp(8), dp(2), dp(2), dp(2));
                row.setBackground(Ui.round(0xFF202020, 12, 0));
                row.addView(Ui.text(this, UiConfig.name(id), 14, b < 0 ? Ui.DIM : Ui.TEXT, false), Ui.lpw(1));
                final int cur = b;
                TextView where = Ui.text(this, b < 0 ? "Скрыта" : UiConfig.BAR_SHORT[b], 12, Ui.TEXT, true);
                where.setGravity(Gravity.CENTER);
                where.setPadding(dp(10), dp(6), dp(10), dp(6));
                where.setBackground(Ui.round(Ui.CHIP, 10, Ui.LINE));
                where.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        int next = cur + 1;
                        if (next >= UiConfig.BARS) next = "menu".equals(id) ? 0 : -1;
                        ui.moveTo(id, next);
                        saveUi();
                        layoutBars();
                        if (panelRefresh != null) panelRefresh.run();
                    }
                });
                row.addView(where);
                if (b >= 0) {
                    row.addView(smallIcon("up", new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            ui.shift(id, -1);
                            saveUi();
                            layoutBars();
                            if (panelRefresh != null) panelRefresh.run();
                        }
                    }));
                    row.addView(smallIcon("down", new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            ui.shift(id, 1);
                            saveUi();
                            layoutBars();
                            if (panelRefresh != null) panelRefresh.run();
                        }
                    }));
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.bottomMargin = dp(4);
                list.addView(row, lp);
            }
        }
    }

    private void menuPanel(LinearLayout c) {
        Doc d = cv.doc;
        header(c, d.name, d.w + " × " + d.h + " · " + d.frames + " " + plural(d.frames, "кадр", "кадра", "кадров") + " · " + d.fps + " к/с");
        menuRow(c, "sliders", "Настроить интерфейс", new Runnable() {
            @Override
            public void run() {
                togglePanel("ui", Gravity.TOP | Gravity.LEFT);
            }
        });
        menuRow(c, "grid", "Галерея", new Runnable() {
            @Override
            public void run() {
                saveNow();
                finish();
            }
        });
        menuRow(c, "fit", "Вписать холст", new Runnable() {
            @Override
            public void run() {
                cv.fit();
            }
        });
        menuRow(c, "edit", "Переименовать", new Runnable() {
            @Override
            public void run() {
                prompt("Название", cv.doc.name, new Done() {
                    @Override
                    public void done(String s) {
                        cv.doc.name = s;
                        dirty = true;
                    }
                });
            }
        });
        String bgName = (d.bg >>> 24) < 255 ? "Прозрачный" : d.bg == 0xFF000000 ? "Чёрный" : d.bg == 0xFFF3E9DC ? "Бумага" : "Белый";
        menuRow(c, "paper", "Фон: " + bgName, new Runnable() {
            @Override
            public void run() {
                int bg = cv.doc.bg;
                cv.doc.bg = bg == 0xFFFFFFFF ? 0xFFF3E9DC : bg == 0xFFF3E9DC ? 0xFF000000 : bg == 0xFF000000 ? 0x00FFFFFF : 0xFFFFFFFF;
                dirty = true;
                togglePanel("menu", Gravity.TOP | Gravity.LEFT);
                togglePanel("menu", Gravity.TOP | Gravity.LEFT);
            }
        });
        c.addView(Ui.divider(this, false));
        menuRow(c, "boil", d.boil ? "Выключить дрожание линий" : "Включить дрожание линий", new Runnable() {
            @Override
            public void run() {
                toggleBoil();
            }
        });
        menuRow(c, "image", "Вставить картинку в кадр", new Runnable() {
            @Override
            public void run() {
                pickImage(PICK_PICTURE);
            }
        });
        menuRow(c, "ref", ref == null ? "Референс-картинка" : "Сменить референс", new Runnable() {
            @Override
            public void run() {
                pickImage(PICK_REFERENCE);
            }
        });
        c.addView(Ui.divider(this, false));
        menuRow(c, "export", "Сохранить кадр PNG", new Runnable() {
            @Override
            public void run() {
                exportPng();
            }
        });
        menuRow(c, "film", "Сохранить анимацию GIF", new Runnable() {
            @Override
            public void run() {
                exportGif(false);
            }
        });
        menuRow(c, "share", "Поделиться GIF", new Runnable() {
            @Override
            public void run() {
                exportGif(true);
            }
        });
        menuRow(c, "video", "Сохранить видео MP4", new Runnable() {
            @Override
            public void run() {
                exportMp4(false);
            }
        });
        menuRow(c, "share", "Поделиться MP4", new Runnable() {
            @Override
            public void run() {
                exportMp4(true);
            }
        });
        TextView tip = Ui.text(this, "Жесты: два пальца — масштаб и поворот, касание двумя — отмена, тремя — повтор.", 11, Ui.FAINT, false);
        tip.setPadding(dp(4), dp(12), dp(4), dp(2));
        tip.setLineSpacing(dp(2), 1);
        c.addView(tip);
    }

    private static String plural(int n, String one, String few, String many) {
        int m10 = n % 10, m100 = n % 100;
        if (m10 == 1 && m100 != 11) return one;
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few;
        return many;
    }

    private void menuRow(LinearLayout c, String icon, final String text, final Runnable r) {
        LinearLayout row = Ui.row(this);
        row.setPadding(dp(2), dp(2), dp(2), dp(2));
        row.setClickable(true);
        row.setBackground(Ui.round(0x00000000, 12, 0));
        Ui.Icon i = new Ui.Icon(this, icon);
        i.setClickable(false);
        row.addView(i, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView t = Ui.text(this, text, 15, Ui.TEXT, false);
        t.setPadding(dp(10), 0, 0, 0);
        row.addView(t, Ui.lpw(1));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!text.startsWith("Фон")) closePanel();
                r.run();
            }
        });
        c.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    // ---------- dialogs ----------

    interface Done {
        void done(String s);
    }

    private int dialogTheme() {
        int t = getResources().getIdentifier("BoilDialog", "style", getPackageName());
        return t != 0 ? t : android.R.style.Theme_Material_Dialog_Alert;
    }

    private void prompt(String title, String value, final Done done) {
        final EditText e = new EditText(this);
        e.setText(value);
        e.setSingleLine(true);
        e.setSelectAllOnFocus(true);
        e.setTextColor(Ui.TEXT);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(e);
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle(title)
                .setView(wrap)
                .setPositiveButton("Готово", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String s = e.getText().toString().trim();
                        if (!s.isEmpty()) done.done(s);
                        immersive();
                    }
                })
                .setNegativeButton("Отмена", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        immersive();
                    }
                })
                .show();
    }

    // ---------- export ----------

    private boolean storageReady() {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7);
            showHint("Разрешите доступ к памяти и повторите", true);
            return false;
        }
        return true;
    }

    private String fileName(String ext) {
        String base = cv.doc.name.replaceAll("[^\\p{L}\\p{N}_-]+", "_");
        if (base.isEmpty()) base = "boil";
        return base + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + "." + ext;
    }

    private Uri saveToGallery(byte[] data, String name, String mime) throws IOException {
        boolean video = mime.startsWith("video/");
        Uri collection = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String folder = video ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES;
        ContentResolver cr = getContentResolver();
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put("relative_path", folder + "/Boil");
            v.put("is_pending", 1);
            Uri uri = cr.insert(collection, v);
            if (uri == null) throw new IOException("insert failed");
            OutputStream o = cr.openOutputStream(uri);
            if (o == null) throw new IOException("open failed");
            try {
                o.write(data);
            } finally {
                o.close();
            }
            v.clear();
            v.put("is_pending", 0);
            cr.update(uri, v, null, null);
            return uri;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(folder), "Boil");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("mkdir failed");
        File f = new File(dir, name);
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(data);
        } finally {
            o.close();
        }
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DATA, f.getAbsolutePath());
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        return cr.insert(collection, v);
    }

    private void exportPng() {
        if (busy || !storageReady()) return;
        busy = true;
        final Doc d = cv.doc.copy();
        final int frame = cv.frame;
        final float t = cv.time();
        final String name = fileName("png");
        showHint("Сохранение…", false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    Bitmap bmp = Bitmap.createBitmap(d.w, d.h, Bitmap.Config.ARGB_8888);
                    Renderer r = new Renderer();
                    r.useCache = false;
                    r.boilOn = d.boil;
                    r.render(new Canvas(bmp), d, frame, t, true, true, null, null);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
                    bmp.recycle();
                    saveToGallery(bos.toByteArray(), name, "image/png");
                    msg = "PNG сохранён в Pictures/Boil";
                } catch (Exception e) {
                    msg = "Не удалось сохранить: " + e.getMessage();
                }
                finishExport(msg, null, null);
            }
        }).start();
    }

    private void exportGif(final boolean share) {
        if (busy || !storageReady()) return;
        busy = true;
        final Doc d = cv.doc.copy();
        final String name = fileName("gif");
        showHint("Экспорт GIF…", false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                Uri uri = null;
                try {
                    float s = Math.min(1f, 720f / Math.max(d.w, d.h));
                    int gw = Math.max(1, Math.round(d.w * s)), gh = Math.max(1, Math.round(d.h * s));
                    float loop = d.frames > 1 ? d.frames / (float) d.fps : 0;
                    float dur = loop > 0 ? loop * (float) Math.ceil(2f / loop) : 3f;
                    dur = Math.min(dur, 12f);
                    int delay = 6;
                    int n = Math.max(1, Math.round(dur * 100 / delay));
                    Bitmap bmp = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888);
                    Canvas cc = new Canvas(bmp);
                    int[] px = new int[gw * gh];
                    Renderer r = new Renderer();
                    r.useCache = false;
                    r.boilOn = d.boil;
                    GifWriter.Palette pal = new GifWriter.Palette();
                    int step = Math.max(1, n / 12);
                    for (int i = 0; i < n; i += step) {
                        renderGifFrame(r, d, cc, bmp, px, s, i * delay / 100f);
                        pal.add(px, 3);
                    }
                    pal.build();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    GifWriter g = new GifWriter(bos, gw, gh, pal.colors);
                    byte[] idx = new byte[gw * gh];
                    for (int i = 0; i < n; i++) {
                        renderGifFrame(r, d, cc, bmp, px, s, i * delay / 100f);
                        pal.index(px, idx);
                        g.frame(idx, delay);
                        final int pct = (i + 1) * 100 / n;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                hint.setText("Экспорт GIF… " + pct + "%");
                            }
                        });
                    }
                    g.finish();
                    bmp.recycle();
                    uri = saveToGallery(bos.toByteArray(), name, "image/gif");
                    msg = share ? "GIF готов" : "GIF сохранён в Pictures/Boil";
                } catch (Exception e) {
                    msg = "Не удалось экспортировать: " + e.getMessage();
                    uri = null;
                }
                finishExport(msg, share ? uri : null, "image/gif");
            }
        }).start();
    }

    private void exportMp4(final boolean share) {
        if (busy || !storageReady()) return;
        busy = true;
        final Doc d = cv.doc.copy();
        final String name = fileName("mp4");
        final File tmp = new File(getCacheDir(), "export.mp4");
        showHint("Экспорт видео…", false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                Uri uri = null;
                Mp4Writer mw = null;
                try {
                    // fit into 1080p and round to multiples of 16, which every H.264 encoder accepts
                    float s = Math.min(1f, 1080f / Math.max(d.w, d.h));
                    int vw = Math.max(16, Math.round(d.w * s / 16f) * 16), vh = Math.max(16, Math.round(d.h * s / 16f) * 16);
                    float sx = vw / (float) d.w, sy = vh / (float) d.h;
                    int fps = 30;
                    float loop = d.frames > 1 ? d.frames / (float) d.fps : 0;
                    float dur = loop > 0 ? loop * (float) Math.ceil(6f / loop) : 6f;
                    dur = Math.min(dur, 30f);
                    int n = Math.max(1, Math.round(dur * fps));
                    Bitmap bmp = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888);
                    Canvas cc = new Canvas(bmp);
                    int[] px = new int[vw * vh];
                    Renderer r = new Renderer();
                    r.useCache = false;
                    r.boilOn = d.boil;
                    mw = new Mp4Writer(tmp, vw, vh, fps);
                    for (int i = 0; i < n; i++) {
                        float t = i / (float) fps;
                        int frame = d.frames > 1 ? ((int) (t * d.fps)) % d.frames : 0;
                        bmp.eraseColor(0xFFFFFFFF);
                        cc.save();
                        cc.scale(sx, sy);
                        r.render(cc, d, frame, t, true, true, null, null);
                        cc.restore();
                        bmp.getPixels(px, 0, vw, 0, 0, vw, vh);
                        mw.frame(px);
                        final int pct = (i + 1) * 100 / n;
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                hint.setText("Экспорт видео… " + pct + "%");
                            }
                        });
                    }
                    mw.finish();
                    bmp.recycle();
                    byte[] data = new byte[(int) tmp.length()];
                    java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(tmp));
                    try {
                        in.readFully(data);
                    } finally {
                        in.close();
                    }
                    uri = saveToGallery(data, name, "video/mp4");
                    msg = share ? "Видео готово" : "MP4 сохранён в Movies/Boil";
                } catch (Exception e) {
                    if (mw != null) mw.release();
                    msg = "Не удалось экспортировать видео: " + e.getMessage();
                    uri = null;
                } finally {
                    tmp.delete();
                }
                finishExport(msg, share ? uri : null, "video/mp4");
            }
        }).start();
    }

    private static void renderGifFrame(Renderer r, Doc d, Canvas cc, Bitmap bmp, int[] px, float s, float t) {
        int frame = d.frames > 1 ? ((int) (t * d.fps)) % d.frames : 0;
        bmp.eraseColor(0xFFFFFFFF);
        cc.save();
        cc.scale(s, s);
        r.render(cc, d, frame, t, true, true, null, null);
        cc.restore();
        bmp.getPixels(px, 0, bmp.getWidth(), 0, 0, bmp.getWidth(), bmp.getHeight());
    }

    private void finishExport(final String msg, final Uri shareUri, final String mime) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                busy = false;
                showHint(msg, true);
                if (shareUri != null) {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType(mime);
                    i.putExtra(Intent.EXTRA_STREAM, shareUri);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivity(Intent.createChooser(i, "Поделиться"));
                    } catch (Exception e) {
                        showHint("Нет приложений для отправки", true);
                    }
                }
            }
        });
    }

    // ---------- images ----------

    private void pickImage(int req) {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(i, "Выберите картинку"), req);
        } catch (Exception e) {
            showHint("Нет приложения для выбора картинок", true);
        }
    }

    @Override
    protected void onActivityResult(final int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final Doc d = cv.doc;
        showHint("Загрузка картинки…", false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Bitmap b = Images.load(getContentResolver(), uri, req == PICK_PICTURE ? Math.max(d.w, d.h) : 1280);
                    final byte[] enc = Images.encode(b);
                    if (req == PICK_REFERENCE) {
                        FileOutputStream o = new FileOutputStream(Storage.refFile(EditorActivity.this, id));
                        try {
                            o.write(enc);
                        } finally {
                            o.close();
                        }
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (req == PICK_REFERENCE) {
                                showRef(b);
                                dirty = true;
                                showHint("Тяните, чтобы двигать · угол — размер · × — убрать", true);
                            } else {
                                placePicture(b, enc);
                            }
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showHint("Не удалось открыть картинку: " + e.getMessage(), true);
                        }
                    });
                }
            }
        }).start();
    }

    private void placePicture(Bitmap b, byte[] enc) {
        Doc d = cv.doc;
        float s = Math.min(d.w * 0.8f / b.getWidth(), d.h * 0.8f / b.getHeight());
        float w = b.getWidth() * s, h = b.getHeight() * s;
        Picture pc = new Picture(b, enc, (d.w - w) / 2, (d.h - h) / 2, w, h);
        closePanel();
        cv.tool = CanvasView.LASSO;
        syncTools();
        syncRail();
        cv.insert(pc);
        updateSelBar();
        showHint("Перетащите картинку · − и + меняют размер", true);
    }

    private void showRef(Bitmap b) {
        if (ref != null) root.removeView(ref);
        ref = new RefView(this, b, this);
        root.addView(ref, root.indexOfChild(overlay), new FrameLayout.LayoutParams(dp(200), dp(200), Gravity.TOP | Gravity.LEFT));
        root.post(new Runnable() {
            @Override
            public void run() {
                if (ref != null) ref.place(root.getWidth(), root.getHeight());
            }
        });
    }

    @Override
    public void onRefClose() {
        if (ref != null) root.removeView(ref);
        ref = null;
        Storage.refFile(this, id).delete();
        showHint("Референс убран", true);
    }

    @Override
    public boolean onRefPickMode() {
        return cv.tool == CanvasView.PICKER;
    }

    @Override
    public void onRefPick(int color) {
        setColor(color, false);
    }

    // ---------- lifecycle ----------

    private void saveNow() {
        if (!dirty) return;
        try {
            Storage.save(this, id, cv.doc);
            dirty = false;
        } catch (IOException e) {
            showHint("Ошибка сохранения", true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveNow();
        savePrefs();
    }

    @Override
    public void onBackPressed() {
        if (panel != null) {
            closePanel();
            return;
        }
        if (!cv.sel.isEmpty()) {
            cv.clearSelection();
            return;
        }
        saveNow();
        super.onBackPressed();
    }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) immersive();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void loadPrefs() {
        for (int i = 0; i < Brush.COUNT; i++) cv.brushes[i].decode(prefs.getString("b2_" + i, null));
        cv.eraser.decode(prefs.getString("eraser", null));
        cv.brush = Math.max(0, Math.min(Brush.COUNT - 1, prefs.getInt("b2_brush", Brush.TRIO)));
        cv.color = prefs.getInt("color", 0xFF111111);
        cv.onion = prefs.getBoolean("onion", true);
        String r = prefs.getString("recent", "");
        for (String s : r.split(",")) {
            try {
                if (!s.isEmpty()) recent.add((int) Long.parseLong(s));
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
    }

    private void savePrefs() {
        SharedPreferences.Editor e = prefs.edit();
        for (int i = 0; i < Brush.COUNT; i++) e.putString("b2_" + i, cv.brushes[i].encode());
        e.putString("eraser", cv.eraser.encode());
        e.putInt("b2_brush", cv.brush);
        e.putInt("color", cv.color);
        e.putBoolean("onion", cv.onion);
        StringBuilder sb = new StringBuilder();
        for (int c : recent) sb.append(c).append(',');
        e.putString("recent", sb.toString());
        e.apply();
    }
}
