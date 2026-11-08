package com.boil.studio;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * User layout of the editor: which controls sit on which of the four floating bars, in what order,
 * each bar's orientation and position, plus button size and panel opacity. Stored as JSON.
 */
final class UiConfig {
    static final String[] IDS = {"menu", "brush", "eraser", "lasso", "picker", "fill", "fx", "layers", "color",
            "undo", "redo", "size", "opacity", "play", "frames", "addFrame", "dupFrame", "delFrame", "onion", "boil", "fps"};
    static final String[] NAMES = {"Меню", "Кисть", "Ластик", "Лассо", "Пипетка", "Заливка", "Эффект слоя", "Слои", "Цвет",
            "Отменить", "Повторить", "Размер кисти", "Непрозрачность кисти", "Воспроизведение", "Лента кадров", "Новый кадр",
            "Дублировать кадр", "Удалить кадр", "Калька", "Дрожание линий", "Скорость кадров"};
    static final String[] BAR_NAMES = {"Верхняя", "Левая", "Нижняя", "Правая"};
    static final String[] BAR_SHORT = {"Верх", "Лево", "Низ", "Право"};
    static final int TOP = 0, LEFT = 1, BOTTOM = 2, RIGHT = 3, BARS = 4;

    final ArrayList<ArrayList<String>> bars = new ArrayList<>();
    final ArrayList<String> hidden = new ArrayList<>();
    final boolean[] vertical = new boolean[BARS];
    /** Bar centre as a fraction of the screen; NaN means the default spot for that bar. */
    final float[] px = new float[BARS], py = new float[BARS];
    float iconDp = 38;
    float panelAlpha = 0.96f;
    boolean fadeWhileDrawing;

    UiConfig() {
        for (int i = 0; i < BARS; i++) bars.add(new ArrayList<String>());
        reset();
    }

    void reset() {
        for (ArrayList<String> b : bars) b.clear();
        hidden.clear();
        add(TOP, "menu", "brush", "eraser", "lasso", "picker", "fill", "fx", "layers", "color");
        add(LEFT, "size", "opacity", "undo", "redo");
        add(BOTTOM, "play", "frames", "addFrame", "dupFrame", "delFrame", "onion", "boil", "fps");
        vertical[TOP] = false;
        vertical[LEFT] = true;
        vertical[BOTTOM] = false;
        vertical[RIGHT] = true;
        for (int i = 0; i < BARS; i++) {
            px[i] = Float.NaN;
            py[i] = Float.NaN;
        }
        iconDp = 38;
        panelAlpha = 0.96f;
        fadeWhileDrawing = false;
    }

    private void add(int bar, String... ids) {
        for (String id : ids) bars.get(bar).add(id);
    }

    static String name(String id) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(id)) return NAMES[i];
        return id;
    }

    /** Bar holding the control, or -1 when hidden. */
    int barOf(String id) {
        for (int b = 0; b < BARS; b++) if (bars.get(b).contains(id)) return b;
        return -1;
    }

    /** Moves the control to another bar (-1 hides it), appending it at the end. */
    void moveTo(String id, int bar) {
        for (ArrayList<String> b : bars) b.remove(id);
        hidden.remove(id);
        if (bar < 0) hidden.add(id);
        else bars.get(bar).add(id);
    }

    /** Shifts the control one place earlier (-1) or later (+1) within its bar. */
    void shift(String id, int dir) {
        int b = barOf(id);
        if (b < 0) return;
        ArrayList<String> l = bars.get(b);
        int i = l.indexOf(id), j = i + dir;
        if (j < 0 || j >= l.size()) return;
        l.set(i, l.get(j));
        l.set(j, id);
    }

    String toJson() {
        try {
            JSONObject o = new JSONObject();
            JSONArray bs = new JSONArray();
            for (ArrayList<String> b : bars) {
                JSONArray a = new JSONArray();
                for (String id : b) a.put(id);
                bs.put(a);
            }
            o.put("bars", bs);
            JSONArray h = new JSONArray();
            for (String id : hidden) h.put(id);
            o.put("hidden", h);
            JSONArray v = new JSONArray(), pos = new JSONArray();
            for (int i = 0; i < BARS; i++) {
                v.put(vertical[i]);
                JSONArray p = new JSONArray();
                if (!Float.isNaN(px[i])) {
                    p.put((double) px[i]);
                    p.put((double) py[i]);
                }
                pos.put(p);
            }
            o.put("vertical", v);
            o.put("pos", pos);
            o.put("icon", (double) iconDp);
            o.put("alpha", (double) panelAlpha);
            o.put("fade", fadeWhileDrawing);
            return o.toString();
        } catch (JSONException e) {
            return "";
        }
    }

    /** Loads a saved layout; unknown ids are dropped and controls added in later versions get their default bar. */
    void fromJson(String s) {
        if (s == null || s.isEmpty()) return;
        UiConfig def = new UiConfig();
        try {
            JSONObject o = new JSONObject(s);
            JSONArray bs = o.getJSONArray("bars");
            for (ArrayList<String> b : bars) b.clear();
            hidden.clear();
            ArrayList<String> seen = new ArrayList<>();
            for (int b = 0; b < BARS && b < bs.length(); b++) {
                JSONArray a = bs.getJSONArray(b);
                for (int i = 0; i < a.length(); i++) {
                    String id = a.getString(i);
                    if (known(id) && !seen.contains(id)) {
                        bars.get(b).add(id);
                        seen.add(id);
                    }
                }
            }
            JSONArray h = o.optJSONArray("hidden");
            if (h != null) {
                for (int i = 0; i < h.length(); i++) {
                    String id = h.getString(i);
                    if (known(id) && !seen.contains(id) && !"menu".equals(id)) {
                        hidden.add(id);
                        seen.add(id);
                    }
                }
            }
            for (String id : IDS) if (!seen.contains(id)) bars.get(Math.max(0, def.barOf(id))).add(id);
            JSONArray v = o.getJSONArray("vertical"), pos = o.getJSONArray("pos");
            for (int i = 0; i < BARS; i++) {
                vertical[i] = v.getBoolean(i);
                JSONArray p = pos.getJSONArray(i);
                px[i] = p.length() == 2 ? (float) p.getDouble(0) : Float.NaN;
                py[i] = p.length() == 2 ? (float) p.getDouble(1) : Float.NaN;
            }
            iconDp = (float) o.optDouble("icon", 38);
            panelAlpha = (float) o.optDouble("alpha", 0.96);
            fadeWhileDrawing = o.optBoolean("fade", false);
        } catch (JSONException e) {
            reset();
        }
    }

    private static boolean known(String id) {
        for (String k : IDS) if (k.equals(id)) return true;
        return false;
    }
}
