package com.boil.studio;

import android.graphics.Bitmap;
import android.graphics.Canvas;

/** Scanline flood fill over the visible artwork of the current frame. */
final class Filler {
    private Filler() {
    }

    static Fill flood(Renderer r, Doc d, int frame, int sx, int sy, int color, BrushSettings bs, int seed) {
        int w = d.w, h = d.h;
        if (sx < 0 || sy < 0 || sx >= w || sy >= h) return null;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        r.render(new Canvas(bmp), d, frame, 0, false, false, null, null);
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        bmp.recycle();

        int target = px[sy * w + sx];
        byte[] m = new byte[w * h];
        int[] stack = new int[1024];
        int sp = 0;
        stack[sp++] = sy * w + sx;
        int minX = sx, maxX = sx, minY = sy, maxY = sy;
        while (sp > 0) {
            int idx = stack[--sp];
            if (m[idx] != 0 || !match(px[idx], target)) continue;
            int y = idx / w, x = idx - y * w;
            int xl = x, xr = x;
            while (xl > 0 && m[idx - (x - xl) - 1] == 0 && match(px[idx - (x - xl) - 1], target)) xl--;
            while (xr < w - 1 && m[idx + (xr - x) + 1] == 0 && match(px[idx + (xr - x) + 1], target)) xr++;
            int row = y * w;
            for (int i = xl; i <= xr; i++) m[row + i] = 1;
            if (xl < minX) minX = xl;
            if (xr > maxX) maxX = xr;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
            for (int dy = -1; dy <= 1; dy += 2) {
                int ny = y + dy;
                if (ny < 0 || ny >= h) continue;
                int nrow = ny * w;
                boolean inRun = false;
                for (int i = xl; i <= xr; i++) {
                    int j = nrow + i;
                    boolean ok = m[j] == 0 && match(px[j], target);
                    if (ok && !inRun) {
                        if (sp == stack.length) stack = java.util.Arrays.copyOf(stack, sp * 2);
                        stack[sp++] = j;
                    }
                    inRun = ok;
                }
            }
        }

        // grow under the line art so the colour meets the (boiling) lines without gaps
        int grow = 2 + Math.round(bs.wobble * 0.7f);
        minX = Math.max(0, minX - grow);
        minY = Math.max(0, minY - grow);
        maxX = Math.min(w - 1, maxX + grow);
        maxY = Math.min(h - 1, maxY + grow);
        int cw = maxX - minX + 1, ch = maxY - minY + 1;
        byte[] a = new byte[cw * ch];
        for (int y = 0; y < ch; y++) {
            int src = (y + minY) * w + minX, dst = y * cw;
            for (int x = 0; x < cw; x++) a[dst + x] = m[src + x];
        }
        byte[] b = new byte[cw * ch];
        for (int pass = 0; pass < grow; pass++) {
            for (int y = 0; y < ch; y++) {
                int row = y * cw;
                for (int x = 0; x < cw; x++) {
                    int i = row + x;
                    b[i] = (a[i] != 0
                            || (x > 0 && a[i - 1] != 0) || (x < cw - 1 && a[i + 1] != 0)
                            || (y > 0 && a[i - cw] != 0) || (y < ch - 1 && a[i + cw] != 0)) ? (byte) 1 : 0;
                }
            }
            byte[] t = a;
            a = b;
            b = t;
        }
        for (int i = 0; i < a.length; i++) a[i] = a[i] != 0 ? (byte) 255 : 0;

        Fill f = new Fill(a, cw, ch, minX, minY);
        f.color = color;
        f.wobble = bs.wobble;
        f.speed = bs.speed;
        f.frames = bs.frames;
        f.seed = seed;
        return f;
    }

    private static boolean match(int c, int t) {
        int a1 = c >>> 24, a2 = t >>> 24;
        int da = Math.abs(a1 - a2);
        int dc = Math.abs(((c >> 16) & 255) - ((t >> 16) & 255))
                + Math.abs(((c >> 8) & 255) - ((t >> 8) & 255))
                + Math.abs((c & 255) - (t & 255));
        float dist = da + Math.min(a1, a2) / 255f * dc / 3f;
        return dist <= 90;
    }
}
