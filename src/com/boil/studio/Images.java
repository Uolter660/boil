package com.boil.studio;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Loads device images: downsampled, upright (JPEG EXIF orientation applied) and ARGB_8888. */
final class Images {
    private Images() {
    }

    static Bitmap load(ContentResolver cr, Uri uri, int maxDim) throws IOException {
        byte[] data = readAll(cr, uri);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new IOException("не удалось прочитать картинку");
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= maxDim) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        d.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, d);
        if (b == null) throw new IOException("не удалось прочитать картинку");

        Matrix m = new Matrix();
        float s = Math.min(1f, maxDim / (float) Math.max(b.getWidth(), b.getHeight()));
        m.postScale(s, s);
        switch (orientation(data)) {
            case 2: m.postScale(-1, 1); break;
            case 3: m.postRotate(180); break;
            case 4: m.postScale(1, -1); break;
            case 5: m.postScale(-1, 1); m.postRotate(270); break;
            case 6: m.postRotate(90); break;
            case 7: m.postScale(-1, 1); m.postRotate(90); break;
            case 8: m.postRotate(270); break;
            default: break;
        }
        if (!m.isIdentity()) {
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (r != b) b.recycle();
            b = r;
        }
        if (b.getConfig() != Bitmap.Config.ARGB_8888) {
            Bitmap r = b.copy(Bitmap.Config.ARGB_8888, false);
            b.recycle();
            b = r;
        }
        return b;
    }

    /** JPEG for opaque images keeps projects small; PNG keeps transparency. */
    static byte[] encode(Bitmap b) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        boolean alpha = b.hasAlpha() && hasTransparentPixels(b);
        b.compress(alpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, 92, bos);
        return bos.toByteArray();
    }

    private static boolean hasTransparentPixels(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int[] row = new int[w];
        for (int y = 0; y < h; y += Math.max(1, h / 64)) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x += Math.max(1, w / 64)) if ((row[x] >>> 24) < 250) return true;
        }
        return false;
    }

    private static byte[] readAll(ContentResolver cr, Uri uri) throws IOException {
        InputStream in = cr.openInputStream(uri);
        if (in == null) throw new IOException("файл недоступен");
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > 60 << 20) throw new IOException("файл слишком большой");
            }
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    /** EXIF orientation tag (1..8) of a JPEG, or 1 when absent. */
    static int orientation(byte[] b) {
        int len = b.length;
        if (len < 4 || (b[0] & 255) != 0xFF || (b[1] & 255) != 0xD8) return 1;
        int i = 2;
        while (i + 4 < len) {
            if ((b[i] & 255) != 0xFF) return 1;
            int marker = b[i + 1] & 255;
            int size = ((b[i + 2] & 255) << 8) | (b[i + 3] & 255);
            if (marker == 0xDA || size < 2) return 1;
            if (marker == 0xE1 && size > 16 && i + 10 < len
                    && b[i + 4] == 'E' && b[i + 5] == 'x' && b[i + 6] == 'i' && b[i + 7] == 'f') {
                int t = i + 10;
                if (t + 8 > len) return 1;
                boolean le = b[t] == 'I';
                int ifd = t + u32(b, t + 4, le);
                if (ifd + 2 > len) return 1;
                int n = u16(b, ifd, le);
                for (int k = 0; k < n; k++) {
                    int e = ifd + 2 + k * 12;
                    if (e + 12 > len) break;
                    if (u16(b, e, le) == 0x0112) {
                        int v = u16(b, e + 8, le);
                        return v >= 1 && v <= 8 ? v : 1;
                    }
                }
                return 1;
            }
            i += 2 + size;
        }
        return 1;
    }

    private static int u16(byte[] b, int i, boolean le) {
        return le ? (b[i] & 255) | (b[i + 1] & 255) << 8 : (b[i] & 255) << 8 | (b[i + 1] & 255);
    }

    private static int u32(byte[] b, int i, boolean le) {
        return le ? u16(b, i, true) | u16(b, i + 2, true) << 16 : u16(b, i, false) << 16 | u16(b, i + 2, false);
    }
}
