package com.boil.studio;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/** H.264 MP4 writer on top of the platform encoder; frames are fed as ARGB pixels and converted to YUV. */
final class Mp4Writer {
    private static final long TIMEOUT_US = 10000;

    private final MediaCodec enc;
    private final MediaMuxer mux;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private final int w, h, fps;
    private int track = -1;
    private boolean started, released;
    private long frames;

    /** Width and height must be even; multiples of 16 are the safest for hardware encoders. */
    Mp4Writer(File out, int w, int h, int fps) throws IOException {
        this.w = w;
        this.h = h;
        this.fps = fps;
        MediaFormat f = MediaFormat.createVideoFormat("video/avc", w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        f.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(2_000_000, Math.min(16_000_000, w * h * fps / 5)));
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        enc = MediaCodec.createEncoderByType("video/avc");
        try {
            enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            enc.start();
        } catch (RuntimeException e) {
            enc.release();
            throw new IOException("кодек H.264 недоступен");
        }
        mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    void frame(int[] argb) throws IOException {
        int in = dequeueInput();
        Image img = enc.getInputImage(in);
        if (img == null) throw new IOException("кодек не принимает кадры");
        fill(img, argb);
        enc.queueInputBuffer(in, 0, w * h * 3 / 2, frames * 1_000_000L / fps, 0);
        frames++;
        drain(false);
    }

    void finish() throws IOException {
        try {
            int in = dequeueInput();
            enc.queueInputBuffer(in, 0, 0, frames * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            drain(true);
        } finally {
            release();
        }
    }

    void release() {
        if (released) return;
        released = true;
        try {
            enc.stop();
        } catch (RuntimeException ignored) {
            // already stopped
        }
        enc.release();
        try {
            if (started) mux.stop();
        } catch (RuntimeException ignored) {
            // nothing was written
        }
        mux.release();
    }

    private int dequeueInput() throws IOException {
        for (int tries = 0; tries < 500; tries++) {
            int in = enc.dequeueInputBuffer(TIMEOUT_US);
            if (in >= 0) return in;
            drain(false);
        }
        throw new IOException("кодек не отвечает");
    }

    private void drain(boolean end) throws IOException {
        int idle = 0;
        while (true) {
            int o = enc.dequeueOutputBuffer(info, end ? TIMEOUT_US : 0);
            if (o == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!end) return;
                if (++idle > 300) throw new IOException("кодек не завершил запись");
            } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = mux.addTrack(enc.getOutputFormat());
                mux.start();
                started = true;
            } else if (o >= 0) {
                ByteBuffer b = enc.getOutputBuffer(o);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0 && started && b != null) {
                    b.position(info.offset);
                    b.limit(info.offset + info.size);
                    mux.writeSampleData(track, b, info);
                }
                enc.releaseOutputBuffer(o, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }
    }

    /** BT.601 limited-range RGB → YUV 4:2:0, honouring whatever plane layout the encoder exposes. */
    private void fill(Image img, int[] px) {
        Image.Plane[] p = img.getPlanes();
        ByteBuffer yb = p[0].getBuffer(), ub = p[1].getBuffer(), vb = p[2].getBuffer();
        int yr = p[0].getRowStride(), yp = p[0].getPixelStride();
        int ur = p[1].getRowStride(), up = p[1].getPixelStride();
        int vr = p[2].getRowStride(), vp = p[2].getPixelStride();
        for (int y = 0; y < h; y++) {
            int row = y * w, yo = y * yr;
            boolean chromaRow = (y & 1) == 0;
            int uo = (y >> 1) * ur, vo = (y >> 1) * vr;
            for (int x = 0; x < w; x++) {
                int c = px[row + x];
                int r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                yb.put(yo + x * yp, (byte) (((66 * r + 129 * g + 25 * b + 128) >> 8) + 16));
                if (chromaRow && (x & 1) == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    ub.put(uo + (x >> 1) * up, (byte) u);
                    vb.put(vo + (x >> 1) * vp, (byte) v);
                }
            }
        }
    }
}
