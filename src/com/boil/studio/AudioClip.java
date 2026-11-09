package com.boil.studio;

import android.content.ContentResolver;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Project sound track. Any audio file — or the audio track of a video — is decoded once to raw
 * 16-bit little-endian PCM stored next to the project; trimming, fades and volume are applied when
 * playing or exporting, so the original is never re-encoded until the final MP4.
 */
final class AudioClip {
    /** Hard limit on how much sound a project keeps (10 minutes). */
    static final int MAX_SECONDS = 600;

    private AudioClip() {
    }

    /** Decodes the first audio track of uri into out; fills the doc's audio description. */
    static void decode(ContentResolver cr, Uri uri, File out, Doc d) throws IOException {
        ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r");
        if (pfd == null) throw new IOException("файл недоступен");
        MediaExtractor ex = new MediaExtractor();
        MediaCodec dec = null;
        BufferedOutputStream os = null;
        try {
            ex.setDataSource(pfd.getFileDescriptor());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0) throw new IOException("в файле нет звука");
            ex.selectTrack(track);
            int rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int inCh = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            dec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            dec.configure(fmt, null, null, 0);
            dec.start();
            os = new BufferedOutputStream(new FileOutputStream(out), 1 << 16);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inDone = false;
            long frames = 0;
            int outCh = Math.min(2, inCh);
            byte[] chunk = new byte[0];
            while (true) {
                if (!inDone) {
                    int ii = dec.dequeueInputBuffer(10000);
                    if (ii >= 0) {
                        ByteBuffer ib = dec.getInputBuffer(ii);
                        int n = ib == null ? -1 : ex.readSampleData(ib, 0);
                        if (n < 0) {
                            dec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inDone = true;
                        } else {
                            dec.queueInputBuffer(ii, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int oi = dec.dequeueOutputBuffer(info, 10000);
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = dec.getOutputFormat();
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    inCh = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    outCh = Math.min(2, inCh);
                } else if (oi >= 0) {
                    ByteBuffer ob = dec.getOutputBuffer(oi);
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        ob.order(ByteOrder.LITTLE_ENDIAN);
                        int samples = info.size / 2, fr = samples / inCh;
                        if (chunk.length < fr * outCh * 2) chunk = new byte[fr * outCh * 2];
                        int w = 0;
                        for (int f = 0; f < fr; f++) {
                            for (int c = 0; c < outCh; c++) {
                                int v;
                                if (inCh > 2) { // downmix extra channels onto left/right
                                    int sum = 0, cnt = 0;
                                    for (int k = c; k < inCh; k += 2) {
                                        sum += ob.getShort(info.offset + (f * inCh + k) * 2);
                                        cnt++;
                                    }
                                    v = sum / cnt;
                                } else {
                                    v = ob.getShort(info.offset + (f * inCh + c) * 2);
                                }
                                chunk[w++] = (byte) v;
                                chunk[w++] = (byte) (v >> 8);
                            }
                        }
                        os.write(chunk, 0, w);
                        frames += fr;
                    }
                    dec.releaseOutputBuffer(oi, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                    if (frames > (long) rate * MAX_SECONDS) break;
                }
            }
            os.flush();
            d.audioRate = rate;
            d.audioChannels = outCh;
            d.audioFrames = frames;
            d.hasAudio = frames > 0;
            if (!d.hasAudio) throw new IOException("звук пустой");
            d.audioStart = 0;
            d.audioEnd = frames / (float) rate;
        } finally {
            if (os != null) os.close();
            if (dec != null) {
                try {
                    dec.stop();
                } catch (RuntimeException ignored) {
                    // already stopped
                }
                dec.release();
            }
            ex.release();
            pfd.close();
        }
    }

    /** Peak level (0..1) per bucket across the whole clip, for the waveform. */
    static float[] peaks(File pcm, Doc d, int buckets) {
        float[] out = new float[buckets];
        if (!d.hasAudio || d.audioFrames <= 0) return out;
        try {
            RandomAccessFile f = new RandomAccessFile(pcm, "r");
            try {
                int ch = d.audioChannels;
                long per = Math.max(1, d.audioFrames / buckets);
                byte[] buf = new byte[(int) Math.min(per * ch * 2, 1 << 20)];
                for (int b = 0; b < buckets; b++) {
                    long start = b * per;
                    if (start >= d.audioFrames) break;
                    int len = (int) Math.min(buf.length, (d.audioFrames - start) * ch * 2);
                    f.seek(start * ch * 2);
                    f.readFully(buf, 0, len);
                    int peak = 0;
                    for (int i = 0; i + 1 < len; i += 8) { // sampled for speed
                        int v = Math.abs((short) ((buf[i] & 255) | buf[i + 1] << 8));
                        if (v > peak) peak = v;
                    }
                    out[b] = peak / 32768f;
                }
            } finally {
                f.close();
            }
        } catch (IOException ignored) {
            // waveform stays flat
        }
        return out;
    }

    /**
     * The trimmed, faded and volume-adjusted sound for a video of the given length, as interleaved
     * 16-bit samples; padded with silence when the trimmed part is shorter.
     */
    static short[] render(File pcm, Doc d, float seconds) throws IOException {
        int ch = d.audioChannels, rate = d.audioRate;
        long total = (long) Math.ceil(seconds * rate);
        short[] out = new short[(int) (total * ch)];
        long from = (long) (d.audioStart * rate), to = Math.min(d.audioFrames, (long) (d.audioEnd * rate));
        long avail = Math.max(0, Math.min(total, to - from));
        RandomAccessFile f = new RandomAccessFile(pcm, "r");
        try {
            f.seek(from * ch * 2);
            byte[] buf = new byte[1 << 16];
            long done = 0;
            while (done < avail) {
                int frames = (int) Math.min(buf.length / (ch * 2), avail - done);
                f.readFully(buf, 0, frames * ch * 2);
                for (int i = 0; i < frames * ch; i++) out[(int) (done * ch) + i] = (short) ((buf[i * 2] & 255) | buf[i * 2 + 1] << 8);
                done += frames;
            }
        } finally {
            f.close();
        }
        float clipLen = avail / (float) rate;
        for (long i = 0; i < avail; i++) {
            float tt = i / (float) rate, g = d.audioVolume;
            if (d.audioFadeIn > 0 && tt < d.audioFadeIn) g *= tt / d.audioFadeIn;
            if (d.audioFadeOut > 0 && tt > clipLen - d.audioFadeOut) g *= Math.max(0, (clipLen - tt) / d.audioFadeOut);
            if (g == 1) continue;
            for (int c = 0; c < ch; c++) {
                int k = (int) (i * ch + c);
                out[k] = (short) Math.max(-32768, Math.min(32767, Math.round(out[k] * g)));
            }
        }
        return out;
    }

    /** Streams the trimmed selection to the speaker in a loop until stopped. */
    static final class Player {
        private volatile boolean running;
        private Thread thread;

        void start(final File pcm, final Doc d, final float offsetSec) {
            stop();
            if (!d.hasAudio) return;
            running = true;
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    play(pcm, d, offsetSec);
                }
            });
            thread.start();
        }

        void stop() {
            running = false;
            if (thread != null) {
                try {
                    thread.join(400);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                thread = null;
            }
        }

        private void play(File pcm, Doc d, float offsetSec) {
            int ch = d.audioChannels, rate = d.audioRate;
            int cfg = ch == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
            int min = AudioTrack.getMinBufferSize(rate, cfg, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) return;
            AudioTrack tr = new AudioTrack(AudioManager.STREAM_MUSIC, rate, cfg, AudioFormat.ENCODING_PCM_16BIT, min * 2, AudioTrack.MODE_STREAM);
            RandomAccessFile f = null;
            try {
                f = new RandomAccessFile(pcm, "r");
                tr.setStereoVolume(Math.min(1f, d.audioVolume), Math.min(1f, d.audioVolume));
                tr.play();
                long from = (long) (d.audioStart * rate), to = Math.min(d.audioFrames, (long) (d.audioEnd * rate));
                if (to <= from) return;
                long pos = from + (long) (offsetSec * rate) % (to - from);
                byte[] buf = new byte[min];
                while (running) {
                    if (pos >= to) pos = from;
                    int frames = (int) Math.min(buf.length / (ch * 2), to - pos);
                    f.seek(pos * ch * 2);
                    f.readFully(buf, 0, frames * ch * 2);
                    tr.write(buf, 0, frames * ch * 2);
                    pos += frames;
                }
            } catch (IOException | IllegalStateException ignored) {
                // playback is best effort
            } finally {
                try {
                    tr.stop();
                } catch (IllegalStateException ignored) {
                    // never started
                }
                tr.release();
                if (f != null) {
                    try {
                        f.close();
                    } catch (IOException ignored) {
                        // nothing to do
                    }
                }
            }
        }
    }
}
