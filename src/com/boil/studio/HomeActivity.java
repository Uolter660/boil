package com.boil.studio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

public class HomeActivity extends Activity {
    private static final String[] SIZE_NAMES = {"Квадрат · 1080 × 1080", "Портрет · 1080 × 1350", "Сторис · 1080 × 1920", "Альбом · 1920 × 1080", "Маленький · 720 × 720"};
    private static final int[][] SIZES = {{1080, 1080}, {1080, 1350}, {1080, 1920}, {1920, 1080}, {720, 720}};

    private LinearLayout grid;
    private int cols;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = Ui.col(this);
        col.setPadding(Ui.dp(20), Ui.dp(28), Ui.dp(20), Ui.dp(28));

        LinearLayout head = Ui.row(this);
        LinearLayout titles = Ui.col(this);
        TextView title = Ui.text(this, "Boil", 40, Ui.TEXT, true);
        title.setLetterSpacing(-0.03f);
        titles.addView(title);
        TextView sub = Ui.text(this, "живые линии · покадровая анимация", 13, Ui.DIM, false);
        sub.setPadding(0, Ui.dp(6), 0, 0);
        titles.addView(sub);
        head.addView(titles, Ui.lpw(1));
        TextView create = Ui.button(this, "+  Новый", true);
        create.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newProject();
            }
        });
        head.addView(create);
        col.addView(head, new LinearLayout.LayoutParams(-1, -2));

        grid = Ui.col(this);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, -2);
        gp.topMargin = Ui.dp(24);
        col.addView(grid, gp);
        sv.addView(col);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        grid.removeAllViews();
        int wDp = Math.round(getResources().getDisplayMetrics().widthPixels / Ui.D);
        cols = Math.max(2, (wDp - 40) / 170);
        ArrayList<Storage.Meta> list = Storage.list(this);
        ArrayList<View> cards = new ArrayList<>();
        cards.add(newCard());
        for (Storage.Meta m : list) cards.add(card(m));
        LinearLayout row = null;
        for (int i = 0; i < cards.size(); i++) {
            if (i % cols == 0) {
                row = Ui.row(this);
                row.setGravity(Gravity.TOP);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                rp.bottomMargin = Ui.dp(14);
                grid.addView(row, rp);
            }
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i % cols != cols - 1) cp.rightMargin = Ui.dp(14);
            row.addView(cards.get(i), cp);
        }
        int rest = cards.size() % cols;
        if (rest != 0) {
            for (int i = rest; i < cols; i++) {
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, 1, 1);
                if (i != cols - 1) cp.rightMargin = Ui.dp(14);
                row.addView(new View(this), cp);
            }
        }
        if (list.isEmpty()) {
            TextView t = Ui.text(this, "Здесь появятся ваши рисунки.\nНачните с нового холста — линии оживут сами.", 13, Ui.FAINT, false);
            t.setLineSpacing(Ui.dp(3), 1);
            t.setPadding(0, Ui.dp(8), 0, 0);
            grid.addView(t);
        }
    }

    /** Square frame that keeps its children 1:1. */
    private static final class Square extends FrameLayout {
        Square(android.content.Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(w, w);
        }
    }

    private static final class DashedPlus extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        DashedPlus(android.content.Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = Ui.dp(18);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(1.5f));
            p.setColor(Ui.FAINT);
            p.setPathEffect(new DashPathEffect(new float[]{Ui.dp(6), Ui.dp(5)}, 0));
            c.drawRoundRect(new RectF(Ui.dp(1), Ui.dp(1), w - Ui.dp(1), h - Ui.dp(1)), r, r, p);
            p.setPathEffect(null);
            p.setColor(Ui.TEXT);
            p.setStrokeWidth(Ui.dp(2));
            p.setStrokeCap(Paint.Cap.ROUND);
            float s = Ui.dp(14);
            c.drawLine(w / 2 - s, h / 2, w / 2 + s, h / 2, p);
            c.drawLine(w / 2, h / 2 - s, w / 2, h / 2 + s, p);
        }
    }

    private View newCard() {
        LinearLayout c = Ui.col(this);
        Square sq = new Square(this);
        sq.addView(new DashedPlus(this), new FrameLayout.LayoutParams(-1, -1));
        sq.setClickable(true);
        sq.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                newProject();
            }
        });
        c.addView(sq, new LinearLayout.LayoutParams(-1, -2));
        TextView t = Ui.text(this, "Новый холст", 14, Ui.TEXT, true);
        t.setPadding(Ui.dp(2), Ui.dp(10), 0, 0);
        c.addView(t);
        TextView s = Ui.text(this, "выбрать размер", 12, Ui.DIM, false);
        s.setPadding(Ui.dp(2), Ui.dp(4), 0, 0);
        c.addView(s);
        return c;
    }

    private View card(final Storage.Meta m) {
        LinearLayout c = Ui.col(this);
        Square sq = new Square(this);
        sq.setBackground(Ui.round(Ui.SURFACE, 18, Ui.LINE));
        sq.setClipToOutline(true);
        int pad = Ui.dp(10);
        sq.setPadding(pad, pad, pad, pad);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        File tf = Storage.thumbFile(this, m.id);
        if (tf.exists()) {
            Bitmap bm = BitmapFactory.decodeFile(tf.getAbsolutePath());
            if (bm != null) img.setImageBitmap(bm);
        }
        sq.addView(img, new FrameLayout.LayoutParams(-1, -1));
        sq.setClickable(true);
        sq.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(HomeActivity.this, EditorActivity.class);
                i.putExtra("id", m.id);
                startActivity(i);
            }
        });
        sq.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                options(m);
                return true;
            }
        });
        c.addView(sq, new LinearLayout.LayoutParams(-1, -2));
        TextView t = Ui.text(this, m.name, 14, Ui.TEXT, true);
        t.setSingleLine(true);
        t.setPadding(Ui.dp(2), Ui.dp(10), 0, 0);
        c.addView(t);
        String when = new SimpleDateFormat("d MMM, HH:mm", new Locale("ru")).format(new Date(m.time));
        TextView s = Ui.text(this, when + (m.frames > 1 ? " · " + m.frames + " к." : ""), 12, Ui.DIM, false);
        s.setPadding(Ui.dp(2), Ui.dp(4), 0, 0);
        c.addView(s);
        return c;
    }

    private int dialogTheme() {
        int t = getResources().getIdentifier("BoilDialog", "style", getPackageName());
        return t != 0 ? t : android.R.style.Theme_Material_Dialog_Alert;
    }

    private void newProject() {
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle("Новый холст")
                .setItems(SIZE_NAMES, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Intent i = new Intent(HomeActivity.this, EditorActivity.class);
                        i.putExtra("w", SIZES[which][0]);
                        i.putExtra("h", SIZES[which][1]);
                        i.putExtra("name", "Рисунок " + (Storage.list(HomeActivity.this).size() + 1));
                        startActivity(i);
                    }
                })
                .show();
    }

    private void options(final Storage.Meta m) {
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle(m.name)
                .setItems(new String[]{"Переименовать", "Дублировать", "Удалить"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (which == 0) rename(m);
                        else if (which == 1) {
                            try {
                                Storage.duplicate(HomeActivity.this, m.id);
                            } catch (IOException ignored) {
                                // nothing to duplicate
                            }
                            refresh();
                        } else confirmDelete(m);
                    }
                })
                .show();
    }

    private void rename(final Storage.Meta m) {
        final EditText e = new EditText(this);
        e.setText(m.name);
        e.setSingleLine(true);
        e.setSelectAllOnFocus(true);
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(Ui.dp(20), Ui.dp(8), Ui.dp(20), 0);
        wrap.addView(e);
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle("Название")
                .setView(wrap)
                .setPositiveButton("Готово", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String s = e.getText().toString().trim();
                        if (s.isEmpty()) return;
                        try {
                            Storage.rename(HomeActivity.this, m.id, s);
                        } catch (IOException ignored) {
                            // keep old name
                        }
                        refresh();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void confirmDelete(final Storage.Meta m) {
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle("Удалить «" + m.name + "»?")
                .setMessage("Это действие нельзя отменить.")
                .setPositiveButton("Удалить", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Storage.delete(HomeActivity.this, m.id);
                        refresh();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }
}
