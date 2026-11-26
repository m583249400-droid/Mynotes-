package mn.live;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** The piano keys played by hand sound straight through Android's own low-delay path, not through the web page's sound engine. */
class Snd {
    private final SoundPool pool;
    private final File dir;
    private final ConcurrentHashMap<String, Integer> ids = new ConcurrentHashMap<String, Integer>();
    private final ConcurrentHashMap<Integer, Float> vols = new ConcurrentHashMap<Integer, Float>();
    private final AtomicInteger ready = new AtomicInteger();
    private final Handler h;

    Snd(Context c) {
        AudioAttributes a = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
        pool = new SoundPool.Builder().setMaxStreams(24).setAudioAttributes(a).build();
        pool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
            public void onLoadComplete(SoundPool p, int id, int status) { if (status == 0) ready.incrementAndGet(); }
        });
        dir = new File(c.getCacheDir(), "snd");
        dir.mkdirs();
        HandlerThread t = new HandlerThread("snd-fade");
        t.start();
        h = new Handler(t.getLooper());
    }

    /** one sample, sent once by the page */
    void load(String key, String b64) {
        if (ids.containsKey(key)) return;
        try {
            File f = new File(dir, key.replaceAll("[^A-Za-z0-9_]", "_") + ".mp3");
            FileOutputStream o = new FileOutputStream(f);
            o.write(Base64.decode(b64, Base64.DEFAULT));
            o.close();
            ids.put(key, pool.load(f.getPath(), 1));
        } catch (Exception e) { /* this sample stays with the page's own sound */ }
    }

    int ready() { return ready.get(); }

    int on(String key, float rate, float vol) {
        Integer id = ids.get(key);
        if (id == null) return 0;
        float v = Math.max(0f, Math.min(1f, vol));
        int s = pool.play(id, v, v, 1, 0, Math.max(0.5f, Math.min(2f, rate)));
        if (s != 0) vols.put(s, v);
        return s;
    }

    /** a short fade, so the note does not end with a click */
    void off(final int s) {
        final Float v0 = vols.remove(s);
        if (v0 == null) { pool.stop(s); return; }
        final int N = 8;
        for (int i = 1; i <= N; i++) {
            final int k = i;
            h.postDelayed(new Runnable() {
                public void run() {
                    if (k == N) { pool.stop(s); return; }
                    float v = v0 * (float) Math.pow(1f - (float) k / N, 2);
                    pool.setVolume(s, v, v);
                }
            }, 18L * i);
        }
    }

    void release() { try { pool.release(); h.getLooper().quit(); } catch (Exception e) { /* closing anyway */ } }
}
