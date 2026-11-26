package mn.live;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.util.Base64;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The app's own sound engine: every note, drum and metronome click of the writing screen is mixed here and sent
 * straight to Android's low-delay output, instead of through the web page's sound engine.
 * The page sends the samples once (PCM), then only "play this at that moment" messages.
 */
class Snd implements Runnable {
    static final int MAXV = 72;

    static final class Smp { float[] d; int sr; }

    static final class Cmd { int type, id, group; Smp s; double rate, vol, la, lb, rel; long frame; }

    static final class Voice {
        boolean on; int id, group; Smp s; double pos, inc, la, lb; float vol, g, relK; long start, stop; boolean loop, rel;
    }

    private final ConcurrentHashMap<String, Smp> smp = new ConcurrentHashMap<String, Smp>();
    private final ConcurrentLinkedQueue<Cmd> q = new ConcurrentLinkedQueue<Cmd>();
    private final AtomicInteger ids = new AtomicInteger();
    private final Voice[] vs = new Voice[MAXV];
    final int sr, block;
    private AudioTrack track;
    private volatile boolean run = true, okInit;
    private volatile long written;
    private volatile double off;
    private volatile boolean offOk;

    Snd(Context c) {
        int r = 48000, b = 256;
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            String a = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE), f = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER);
            if (a != null) r = Integer.parseInt(a);
            if (f != null) b = Integer.parseInt(f);
        } catch (Exception e) { /* the usual values */ }
        sr = r;
        block = Math.max(64, Math.min(1024, b));
        for (int i = 0; i < MAXV; i++) vs[i] = new Voice();
        try {
            int min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack.Builder tb = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Math.max(min, block * 4 * 2));
            if (Build.VERSION.SDK_INT >= 26) tb.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            track = tb.build();
            if (Build.VERSION.SDK_INT >= 24) { try { track.setBufferSizeInFrames(block * 2); } catch (Exception e) { /* keeps its own size */ } }
            track.play();
            okInit = true;
            Thread t = new Thread(this, "mn-sound");
            t.setPriority(Thread.MAX_PRIORITY);
            t.start();
        } catch (Exception e) { okInit = false; }
    }

    boolean ok() { return okInit; }

    int count() { return smp.size(); }

    /** one sample: 16-bit mono PCM, base64 */
    void pcm(String key, int rate, String b64) {
        try {
            byte[] b = Base64.decode(b64, Base64.DEFAULT);
            float[] d = new float[b.length / 2];
            for (int i = 0; i < d.length; i++) d[i] = (short) ((b[2 * i] & 0xff) | (b[2 * i + 1] << 8)) / 32768f;
            Smp s = new Smp();
            s.d = d;
            s.sr = rate;
            smp.put(key, s);
        } catch (Exception e) { /* this sample stays with the page's own sound */ }
    }

    private long frameIn(double ms) {
        long now = offOk ? (long) (System.nanoTime() * (sr / 1e9) + off) : written;
        return now + (long) (Math.max(0, ms) * sr / 1000.0);
    }

    int play(String key, double rate, double vol, double delayMs, int group, double la, double lb) {
        Smp s = smp.get(key);
        if (s == null || !okInit) return 0;
        Cmd c = new Cmd();
        c.type = 1; c.id = ids.incrementAndGet(); c.s = s; c.rate = rate; c.vol = vol; c.group = group; c.la = la; c.lb = lb; c.frame = frameIn(delayMs);
        q.add(c);
        return c.id;
    }

    void stop(int id, double delayMs, double relMs) {
        Cmd c = new Cmd();
        c.type = 2; c.id = id; c.frame = frameIn(delayMs); c.rel = relMs;
        q.add(c);
    }

    /** cut at once everything of these groups (bit 1 = by hand, 2 = playback, 4 = drums and metronome) */
    void kill(int mask) {
        Cmd c = new Cmd();
        c.type = 3; c.group = mask;
        q.add(c);
    }

    void release() { run = false; }

    private float relK(double ms) { return (float) Math.exp(-1.0 / (Math.max(1.0, ms) / 1000.0 * sr)); }

    private void drain() {
        Cmd c;
        while ((c = q.poll()) != null) {
            if (c.type == 1) {
                Voice v = null;
                for (int i = 0; i < MAXV; i++) if (!vs[i].on) { v = vs[i]; break; }
                if (v == null) { v = vs[0]; for (int i = 1; i < MAXV; i++) if (vs[i].start < v.start) v = vs[i]; }
                v.on = true; v.id = c.id; v.group = c.group; v.s = c.s; v.pos = 0; v.inc = c.rate * c.s.sr / sr; v.vol = (float) c.vol; v.g = 1f; v.rel = false; v.relK = 0f;
                v.start = c.frame; v.stop = Long.MAX_VALUE;
                v.loop = c.la >= 0 && c.lb > c.la + 0.01; v.la = c.la * c.s.sr; v.lb = Math.min(c.s.d.length - 2, c.lb * c.s.sr);
                if (v.loop && v.lb <= v.la + 16) v.loop = false;
            } else if (c.type == 2) {
                for (int i = 0; i < MAXV; i++) if (vs[i].on && vs[i].id == c.id) { vs[i].stop = c.frame; vs[i].relK = relK(c.rel); }
            } else {
                for (int i = 0; i < MAXV; i++) {
                    Voice v = vs[i];
                    if (!v.on || ((1 << v.group) & c.group) == 0) continue;
                    if (v.start > written) v.on = false; else { v.stop = written; v.relK = relK(4); }
                }
            }
        }
    }

    /** mixes the next block; returns how many voices are sounding or waiting */
    int render(float[] mix, int n) {
        for (int i = 0; i < n; i++) mix[i] = 0f;
        int live = 0;
        final long w = written;
        for (int k = 0; k < MAXV; k++) {
            Voice v = vs[k];
            if (!v.on) continue;
            live++;
            if (v.start >= w + n) continue;
            final float[] d = v.s.d;
            final int last = d.length - 1;
            int i = v.start > w ? (int) (v.start - w) : 0;
            for (; i < n; i++) {
                if (!v.rel && w + i >= v.stop) v.rel = true;
                if (v.rel) { v.g *= v.relK; if (v.g < 0.0006f) { v.on = false; break; } }
                int p = (int) v.pos;
                if (p >= last) { v.on = false; break; }
                float fr = (float) (v.pos - p);
                mix[i] += (d[p] + (d[p + 1] - d[p]) * fr) * v.vol * v.g;
                v.pos += v.inc;
                if (v.loop && v.pos >= v.lb) v.pos -= (v.lb - v.la);
            }
        }
        return live;
    }

    public void run() {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO); } catch (Throwable e) { /* normal priority */ }
        final float[] mix = new float[block];
        final short[] out = new short[block * 2];
        long idle = 0;
        boolean paused = false;
        while (run) {
            drain();
            int live = render(mix, block);
            if (live == 0) idle += block; else idle = 0;
            if (idle > sr * 20L) {
                // nothing for a while: the output rests until the next sound
                if (!paused) { try { track.pause(); } catch (Exception e) { /* keeps running */ } paused = true; offOk = false; }
                try { Thread.sleep(3); } catch (InterruptedException e) { /* go on */ }
                if (q.isEmpty()) continue;
                try { track.play(); } catch (Exception e) { /* keeps running */ }
                paused = false; idle = 0;
                continue;
            }
            for (int i = 0; i < block; i++) {
                float x = mix[i] * 0.8f;
                if (x > 0.7f) x = 0.7f + (x - 0.7f) / (1f + (x - 0.7f) * 3.3f); else if (x < -0.7f) x = -0.7f + (x + 0.7f) / (1f - (x + 0.7f) * 3.3f);
                short s = (short) (x * 32767f);
                out[2 * i] = s; out[2 * i + 1] = s;
            }
            int r = track.write(out, 0, out.length);
            if (r < 0) { try { Thread.sleep(20); } catch (InterruptedException e) { /* go on */ } continue; }
            written += block;
            double o = written - System.nanoTime() * (sr / 1e9);
            if (!offOk) { off = o; offOk = true; } else off += (o - off) * 0.02;
        }
        try { track.stop(); track.release(); } catch (Exception e) { /* closing anyway */ }
    }
}
