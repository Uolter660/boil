package com.boil.studio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/** Minimal animated GIF89a writer with a global 256-colour palette and LZW compression. */
final class GifWriter {
    private final OutputStream out;
    private final int w, h;
    private final int[] dict = new int[4096 * 256];
    private final ByteArrayOutputStream data = new ByteArrayOutputStream();
    private int bitBuf, bitCount, codeSize, next;
    private boolean resetPending;

    GifWriter(OutputStream out, int w, int h, int[] palette) throws IOException {
        this.out = out;
        this.w = w;
        this.h = h;
        out.write("GIF89a".getBytes("US-ASCII"));
        short16(w);
        short16(h);
        out.write(0xF7); // global colour table, 8 bits
        out.write(0);
        out.write(0);
        for (int i = 0; i < 256; i++) {
            int c = i < palette.length ? palette[i] : 0;
            out.write((c >> 16) & 255);
            out.write((c >> 8) & 255);
            out.write(c & 255);
        }
        out.write(new byte[]{0x21, (byte) 0xFF, 0x0B});
        out.write("NETSCAPE2.0".getBytes("US-ASCII"));
        out.write(new byte[]{3, 1, 0, 0, 0});
    }

    void frame(byte[] idx, int delayCs) throws IOException {
        out.write(new byte[]{0x21, (byte) 0xF9, 4, 0});
        short16(delayCs);
        out.write(0);
        out.write(0);
        out.write(0x2C);
        short16(0);
        short16(0);
        short16(w);
        short16(h);
        out.write(0);
        out.write(8);
        lzw(idx);
        byte[] d = data.toByteArray();
        data.reset();
        for (int i = 0; i < d.length; i += 255) {
            int len = Math.min(255, d.length - i);
            out.write(len);
            out.write(d, i, len);
        }
        out.write(0);
    }

    void finish() throws IOException {
        out.write(0x3B);
        out.flush();
    }

    private void short16(int v) throws IOException {
        out.write(v & 255);
        out.write((v >> 8) & 255);
    }

    private void lzw(byte[] idx) {
        Arrays.fill(dict, 0);
        bitBuf = 0;
        bitCount = 0;
        codeSize = 9;
        next = 258;
        resetPending = false;
        emit(256);
        int prefix = idx[0] & 255;
        for (int i = 1; i < idx.length; i++) {
            int c = idx[i] & 255;
            int key = prefix * 256 + c;
            int v = dict[key];
            if (v != 0) {
                prefix = v - 1;
                continue;
            }
            emit(prefix);
            if (next < 4096) {
                dict[key] = next + 1;
                next++;
            } else {
                Arrays.fill(dict, 0);
                next = 258;
                resetPending = true;
                emit(256);
            }
            prefix = c;
        }
        emit(prefix);
        emit(257);
        if (bitCount > 0) data.write(bitBuf & 255);
    }

    private void emit(int code) {
        bitBuf |= code << bitCount;
        bitCount += codeSize;
        while (bitCount >= 8) {
            data.write(bitBuf & 255);
            bitBuf >>>= 8;
            bitCount -= 8;
        }
        if (resetPending) {
            codeSize = 9;
            resetPending = false;
        } else if (next > (1 << codeSize) - 1 && codeSize < 12) {
            codeSize++;
        }
    }

    // ---------- palette ----------

    /** Accumulates a 15-bit colour histogram, then picks the most used colours. */
    static final class Palette {
        private final int[] count = new int[32768];
        private final long[] r = new long[32768], g = new long[32768], b = new long[32768];
        private final int[] map = new int[32768];
        int[] colors;

        void add(int[] px, int stride) {
            for (int i = 0; i < px.length; i += stride) {
                int c = px[i];
                int k = key(c);
                count[k]++;
                r[k] += (c >> 16) & 255;
                g[k] += (c >> 8) & 255;
                b[k] += c & 255;
            }
        }

        void build() {
            Integer[] keys = new Integer[32768];
            for (int i = 0; i < keys.length; i++) keys[i] = i;
            Arrays.sort(keys, new java.util.Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b2) {
                    return count[b2] - count[a];
                }
            });
            int n = 0;
            while (n < 256 && count[keys[n]] > 0) n++;
            if (n == 0) n = 1;
            colors = new int[n];
            for (int i = 0; i < n; i++) {
                int k = keys[i], c = Math.max(1, count[k]);
                colors[i] = (int) (r[k] / c) << 16 | (int) (g[k] / c) << 8 | (int) (b[k] / c);
            }
            Arrays.fill(map, -1);
        }

        void index(int[] px, byte[] out) {
            for (int i = 0; i < px.length; i++) {
                int k = key(px[i]);
                int m = map[k];
                if (m < 0) {
                    m = nearest(k);
                    map[k] = m;
                }
                out[i] = (byte) m;
            }
        }

        private int nearest(int k) {
            int kr = ((k >> 10) & 31) * 255 / 31, kg = ((k >> 5) & 31) * 255 / 31, kb = (k & 31) * 255 / 31;
            int best = 0, bd = Integer.MAX_VALUE;
            for (int i = 0; i < colors.length; i++) {
                int c = colors[i];
                int dr = ((c >> 16) & 255) - kr, dg = ((c >> 8) & 255) - kg, db = (c & 255) - kb;
                int d = dr * dr * 3 + dg * dg * 4 + db * db * 2;
                if (d < bd) {
                    bd = d;
                    best = i;
                }
            }
            return best;
        }

        private static int key(int c) {
            return ((c >> 19) & 31) << 10 | ((c >> 11) & 31) << 5 | ((c >> 3) & 31);
        }
    }
}
