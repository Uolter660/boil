package com.boil.studio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/** Projects live in app-private storage as a compact binary file plus a PNG thumbnail. */
final class Storage {
    static final class Meta {
        String id, name;
        int w, h, frames;
        long time;
    }

    private Storage() {
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "projects");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static File docFile(Context c, String id) {
        return new File(dir(c), id + ".boil");
    }

    static File audioFile(Context c, String id) {
        return new File(dir(c), id + ".pcm");
    }

    static File refFile(Context c, String id) {
        return new File(dir(c), id + ".ref");
    }

    static File thumbFile(Context c, String id) {
        return new File(dir(c), id + ".png");
    }

    static String newId() {
        return String.valueOf(System.currentTimeMillis());
    }

    static void save(Context c, String id, Doc d) throws IOException {
        File f = docFile(c, id), tmp = new File(dir(c), id + ".tmp");
        DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16));
        try {
            d.write(o);
        } finally {
            o.close();
        }
        if (!tmp.renameTo(f)) throw new IOException("rename failed");
        Bitmap thumb = thumbnail(d, 360);
        FileOutputStream to = new FileOutputStream(thumbFile(c, id));
        try {
            thumb.compress(Bitmap.CompressFormat.PNG, 90, to);
        } finally {
            to.close();
            thumb.recycle();
        }
    }

    static Doc load(Context c, String id) throws IOException {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(docFile(c, id)), 1 << 16));
        try {
            return Doc.read(in);
        } finally {
            in.close();
        }
    }

    static Bitmap thumbnail(Doc d, int max) {
        float s = Math.min(1f, (float) max / Math.max(d.w, d.h));
        int tw = Math.max(1, Math.round(d.w * s)), th = Math.max(1, Math.round(d.h * s));
        Bitmap b = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.scale(s, s);
        new Renderer().render(cv, d, 0, 0, false, true, null, null);
        return b;
    }

    static ArrayList<Meta> list(Context c) {
        ArrayList<Meta> out = new ArrayList<>();
        File[] files = dir(c).listFiles();
        if (files == null) return out;
        for (File f : files) {
            String n = f.getName();
            if (!n.endsWith(".boil")) continue;
            Meta m = new Meta();
            m.id = n.substring(0, n.length() - 5);
            m.time = f.lastModified();
            try {
                DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 512));
                try {
                    String[] h = Doc.readHeader(in);
                    m.name = h[0];
                    m.w = Integer.parseInt(h[1]);
                    m.h = Integer.parseInt(h[2]);
                    m.frames = Integer.parseInt(h[3]);
                } finally {
                    in.close();
                }
            } catch (IOException e) {
                continue;
            }
            out.add(m);
        }
        Collections.sort(out, new Comparator<Meta>() {
            @Override
            public int compare(Meta a, Meta b) {
                return Long.compare(b.time, a.time);
            }
        });
        return out;
    }

    private static void copyFile(File from, File to) throws IOException {
        if (!from.exists()) return;
        FileInputStream in = new FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to);
        try {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
            out.close();
        }
    }

    static void delete(Context c, String id) {
        docFile(c, id).delete();
        thumbFile(c, id).delete();
        refFile(c, id).delete();
        audioFile(c, id).delete();
    }

    static void rename(Context c, String id, String name) throws IOException {
        Doc d = load(c, id);
        d.name = name;
        save(c, id, d);
    }

    static String duplicate(Context c, String id) throws IOException {
        Doc d = load(c, id);
        d.name = d.name + " (копия)";
        String nid = newId();
        copyFile(audioFile(c, id), audioFile(c, nid));
        copyFile(refFile(c, id), refFile(c, nid));
        save(c, nid, d);
        return nid;
    }
}
