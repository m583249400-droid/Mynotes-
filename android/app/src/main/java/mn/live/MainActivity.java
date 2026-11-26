package mn.live;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** MyNotes on a tablet. As the leader it shows the app and runs the small server the musicians connect to;
 *  as a musician it finds the leader on the network and shows his page full screen. */
public class MainActivity extends Activity {
    private static final int REQ_FILE = 11, REQ_SAVE = 12, REQ_OPEN = 13, REQ_MIC = 14;
    private WebView web;
    private LiveServer server;
    private int port = 8080;
    private File www, data;
    private ValueCallback<Uri[]> fileCb;
    private PermissionRequest micReq;
    private SharedPreferences sp;
    private volatile String cur = "";
    static final String JOIN = "file:///android_asset/www/join/index.html";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        www = new File(getFilesDir(), "www");
        data = new File(getFilesDir(), "live");
        data.mkdirs();
        sp = getSharedPreferences("mn", MODE_PRIVATE);
        // a tablet that already holds a library was the leader before this choice existed
        if (sp.getString("role", "").isEmpty() && new File(new File(data, "kv"), "library").isFile()) sp.edit().putString("role", "leader").apply();
        try {
            unpackSite();
            if (!"player".equals(sp.getString("role", ""))) startServer();
        } catch (Exception e) {
            Toast.makeText(this, "MyNotes: " + e, Toast.LENGTH_LONG).show();
        }
        web = new WebView(this);
        // newer Android draws the app under the system bars and no longer shrinks it for the keyboard:
        // the frame makes room for the keyboard itself, so a search box or a text field is never hidden
        final FrameLayout frame = new FrameLayout(this);
        frame.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(frame);
        if (Build.VERSION.SDK_INT >= 30) {
            frame.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                public WindowInsets onApplyWindowInsets(View v, WindowInsets ins) {
                    v.setPadding(0, 0, 0, ins.getInsets(WindowInsets.Type.ime()).bottom);
                    return ins;
                }
            });
        }
        // the back gesture on Android 13 and later
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, new OnBackInvokedCallback() {
                public void onBackInvoked() { js("window.MNBack&&window.MNBack()"); }
            });
        }
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setSupportZoom(false);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Host(), "MNHost");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url.startsWith("http://127.0.0.1:" + port + "/") || url.startsWith(JOIN) || lanPage(url)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { /* nothing to open it */ }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int p) { String u = v.getUrl(); if (u != null) cur = u; }

            @Override
            public void onPermissionRequest(final PermissionRequest r) {
                boolean audio = false, midi = false;
                for (String x : r.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(x)) audio = true;
                    if (PermissionRequest.RESOURCE_MIDI_SYSEX.equals(x)) midi = true;
                }
                // a USB piano keyboard: nothing to ask the user, the tablet itself decides
                if (midi && !audio) { r.grant(new String[]{PermissionRequest.RESOURCE_MIDI_SYSEX}); return; }
                if (!audio) { r.deny(); return; }
                if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    r.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    micReq = r;
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                }
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                if (p != null && p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(i, "בחירת קובץ"), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });
        if (b == null) web.loadUrl(start());
        else web.restoreState(b);
    }

    private String start() {
        String r = sp.getString("role", "");
        if ("leader".equals(r)) return "http://127.0.0.1:" + port + "/app/";
        return r.isEmpty() ? JOIN + "?first=1" : JOIN;
    }

    /** the leader's page on the local network: a private address, any of the leader's ports */
    static boolean lanPage(String url) {
        try {
            Uri u = Uri.parse(url);
            String h = u.getHost();
            if (!"http".equals(u.getScheme()) || h == null) return false;
            if (h.startsWith("10.") || h.startsWith("192.168.")) return true;
            if (h.startsWith("172.")) { int b = Integer.parseInt(h.split("\\.")[1]); return b >= 16 && b <= 31; }
        } catch (Exception e) { /* not one */ }
        return false;
    }

    /** the leader's own pages may use everything; a page from another tablet only the harmless calls */
    boolean local() { return cur.startsWith("http://127.0.0.1:") || cur.startsWith(JOIN); }

    private void load(final String url) { runOnUiThread(new Runnable() { public void run() { web.loadUrl(url); } }); }

    /** look for a leader on every network this tablet is on: the last one first, then each address of the subnet */
    String scan(String last) {
        if (last != null && !last.isEmpty()) { String r = probe(last); if (r != null) return r; }
        List<String> mine = LiveServer.lanAddresses();
        for (int port : new int[]{8080, 8181, 8282, 8383}) {
            final List<String> hits = Collections.synchronizedList(new ArrayList<String>());
            ExecutorService ex = Executors.newFixedThreadPool(64);
            for (String ip : mine) {
                int d = ip.lastIndexOf('.');
                if (d < 0) continue;
                String pre = ip.substring(0, d + 1);
                for (int i = 1; i < 255; i++) {
                    final String host = pre + i;
                    if (host.equals(ip)) continue;
                    final String hp = host + ":" + port;
                    ex.submit(new Runnable() { public void run() { String r = probe(hp); if (r != null) hits.add(r); } });
                }
            }
            ex.shutdown();
            try { ex.awaitTermination(12, TimeUnit.SECONDS); } catch (InterruptedException e) { /* go on */ }
            ex.shutdownNow();
            if (!hits.isEmpty()) {
                StringBuilder b = new StringBuilder();
                for (String h : hits) b.append(b.length() > 0 ? "," : "").append(h);
                return b.toString();
            }
        }
        return "";
    }

    /** "ip:port|players" when a MyNotes leader answers there */
    static String probe(String hp) {
        try {
            int c = hp.lastIndexOf(':');
            Socket so = new Socket();
            so.connect(new InetSocketAddress(hp.substring(0, c), Integer.parseInt(hp.substring(c + 1))), 400);
            so.close();
            HttpURLConnection h = (HttpURLConnection) new URL("http://" + hp + "/info").openConnection();
            h.setConnectTimeout(1000);
            h.setReadTimeout(1500);
            InputStream in = h.getInputStream();
            byte[] buf = new byte[2048];
            int n = in.read(buf);
            in.close();
            h.disconnect();
            String t = n > 0 ? new String(buf, 0, n, "UTF-8") : "";
            if (!t.contains("\"mynotes-live\"")) return null;
            int i = t.indexOf("\"players\":"), pl = 0;
            if (i >= 0) { i += 10; int j = i; while (j < t.length() && Character.isDigit(t.charAt(j))) j++; try { pl = Integer.parseInt(t.substring(i, j)); } catch (Exception e) { /* none */ } }
            return hp + "|" + pl;
        } catch (Exception e) {
            return null;
        }
    }

    private void startServer() throws IOException {
        IOException last = null;
        for (int p : new int[]{8080, 8181, 8282, 8383}) {
            try {
                LiveServer sv = new LiveServer(www, data, p);
                sv.startServer();
                server = sv;
                port = p;
                return;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    /** the web pages travel inside the app; copy them out once per installed version */
    private void unpackSite() throws IOException {
        SharedPreferences sp = getSharedPreferences("mn", MODE_PRIVATE);
        long stamp;
        try { stamp = getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime; } catch (Exception e) { stamp = 1; }
        if (sp.getLong("www", 0) == stamp && new File(www, "app/index.html").isFile()) return;
        LiveServer.rm(www);
        copyAssets("www", www);
        sp.edit().putLong("www", stamp).apply();
    }

    private void copyAssets(String path, File dst) throws IOException {
        AssetManager am = getAssets();
        String[] list = am.list(path);
        if (list == null || list.length == 0) {
            dst.getParentFile().mkdirs();
            InputStream in = am.open(path);
            OutputStream out = new FileOutputStream(dst);
            pipe(in, out);
            in.close();
            out.close();
            return;
        }
        dst.mkdirs();
        for (String n : list) copyAssets(path + "/" + n, new File(dst, n));
    }

    static void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private void js(final String code) {
        runOnUiThread(new Runnable() { public void run() { web.evaluateJavascript(code, null); } });
    }

    private void toast(final String t) {
        runOnUiThread(new Runnable() { public void run() { Toast.makeText(MainActivity.this, t, Toast.LENGTH_LONG).show(); } });
    }

    /** what the pages may ask the tablet to do */
    private Snd snd;
    private synchronized Snd snd() { if (snd == null) snd = new Snd(getApplicationContext()); return snd; }

    class Host {
        /** the writing screen's sound engine: samples in once, then "play this at that moment" */
        @JavascriptInterface
        public int sndInit() { return local() && snd().ok() ? snd().sr : 0; }

        @JavascriptInterface
        public void sndPcm(String key, int rate, String b64) { if (local()) snd().pcm(key, rate, b64); }

        @JavascriptInterface
        public int sndCount() { return snd().count(); }

        @JavascriptInterface
        public int sndPlay(String key, double rate, double vol, double delayMs, int group, double loopA, double loopB) { return snd().play(key, rate, vol, delayMs, group, loopA, loopB); }

        @JavascriptInterface
        public void sndStop(int id, double delayMs, double relMs) { snd().stop(id, delayMs, relMs); }

        @JavascriptInterface
        public void sndKill(int mask) { snd().kill(mask); }

        @JavascriptInterface
        public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public int port() { return port; }

        /** leader or musician; the choice is kept and the right screen opens */
        @JavascriptInterface
        public String role() { return sp.getString("role", ""); }

        @JavascriptInterface
        public void setRole(final String r) {
            if (!"leader".equals(r) && !"player".equals(r)) return;
            sp.edit().putString("role", r).apply();
            runOnUiThread(new Runnable() {
                public void run() {
                    if ("leader".equals(r)) {
                        try { if (server == null) startServer(); } catch (Exception e) { toast("MyNotes: " + e); }
                        web.loadUrl("http://127.0.0.1:" + port + "/app/");
                    } else {
                        if (server != null) { server.stop(); server = null; }
                        web.loadUrl(JOIN + "?settings=1");
                    }
                }
            });
        }

        /** the musician's own settings: instrument, name, the last leader */
        @JavascriptInterface
        public String pref(String k) { return "inst".equals(k) || "name".equals(k) || "leader".equals(k) || "role".equals(k) ? sp.getString(k, "") : ""; }

        @JavascriptInterface
        public void setPref(String k, String v) { if ("inst".equals(k) || "name".equals(k) || "leader".equals(k)) sp.edit().putString(k, v == null ? "" : v).apply(); }

        @JavascriptInterface
        public void findLeader(final String last) {
            new Thread(new Runnable() {
                public void run() {
                    final String r = scan(last).replaceAll("[^0-9.:|,]", "");
                    js("window.MNJoin&&MNJoin.found('" + r + "')");
                }
            }).start();
        }

        /** back to the musician's settings page (from the leader's page on another tablet) */
        @JavascriptInterface
        public void home(String why) { load(JOIN + ("settings".equals(why) ? "?settings=1" : "")); }

        /** one zip with the whole Live library; the system picker lets the user put it in Drive */
        @JavascriptInterface
        public void exportBackup() {
            if (!local()) return;
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            i.putExtra(Intent.EXTRA_TITLE, "MyNotes-Live-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + ".zip");
            startActivityForResult(i, REQ_SAVE);
        }

        @JavascriptInterface
        public void importBackup() {
            if (!local()) return;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_OPEN);
        }

        /** a file the app made (PDF, MIDI, backup) goes to the Downloads folder */
        @JavascriptInterface
        public void saveFile(String name, String b64, String mime) {
            if (!local()) return;
            try {
                byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                OutputStream out;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    cv.put(MediaStore.Downloads.MIME_TYPE, mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    out = getContentResolver().openOutputStream(u);
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    dir.mkdirs();
                    out = new FileOutputStream(new File(dir, name));
                }
                out.write(bytes);
                out.close();
                toast("נשמר בהורדות: " + name);
            } catch (Exception e) {
                toast("השמירה נכשלה");
            }
        }
    }

    @Override
    protected void onActivityResult(int req, int res, final Intent d) {
        if (req == REQ_FILE) {
            Uri[] out = null;
            if (res == RESULT_OK && d != null) {
                if (d.getClipData() != null) {
                    out = new Uri[d.getClipData().getItemCount()];
                    for (int i = 0; i < out.length; i++) out[i] = d.getClipData().getItemAt(i).getUri();
                } else if (d.getData() != null) out = new Uri[]{d.getData()};
            }
            if (fileCb != null) fileCb.onReceiveValue(out);
            fileCb = null;
            return;
        }
        if (res != RESULT_OK || d == null || d.getData() == null) return;
        final Uri u = d.getData();
        if (req == REQ_SAVE) {
            new Thread(new Runnable() {
                public void run() {
                    try {
                        ZipOutputStream z = new ZipOutputStream(new BufferedOutputStream(getContentResolver().openOutputStream(u)));
                        z.setLevel(1);
                        zip(z, data, "");
                        z.close();
                        toast("הגיבוי נשמר");
                    } catch (Exception e) {
                        toast("הגיבוי נכשל");
                    }
                }
            }).start();
        } else if (req == REQ_OPEN) {
            new Thread(new Runnable() {
                public void run() {
                    File tmp = new File(getFilesDir(), "live-restore");
                    try {
                        LiveServer.rm(tmp);
                        tmp.mkdirs();
                        ZipInputStream z = new ZipInputStream(new BufferedInputStream(getContentResolver().openInputStream(u)));
                        ZipEntry e;
                        boolean ok = false;
                        String root = tmp.getCanonicalPath() + File.separator;
                        while ((e = z.getNextEntry()) != null) {
                            File f = new File(tmp, e.getName());
                            if (!f.getCanonicalPath().startsWith(root)) continue;
                            if (e.isDirectory()) { f.mkdirs(); continue; }
                            f.getParentFile().mkdirs();
                            OutputStream o = new FileOutputStream(f);
                            pipe(z, o);
                            o.close();
                            if (e.getName().equals("kv/library")) ok = true;
                        }
                        z.close();
                        if (!ok) { LiveServer.rm(tmp); toast("זה לא קובץ גיבוי של MyNotes Live"); return; }
                        File old = new File(getFilesDir(), "live-old");
                        LiveServer.rm(old);
                        data.renameTo(old);
                        tmp.renameTo(data);
                        LiveServer.rm(old);
                        new FileOutputStream(new File(new File(data, "kv"), "_restored")).close();
                        toast("הספרייה שוחזרה");
                        js("window.MNLiveRestored&&window.MNLiveRestored()");
                    } catch (Exception ex) {
                        LiveServer.rm(tmp);
                        toast("השחזור נכשל");
                    }
                }
            }).start();
        }
    }

    private static void zip(ZipOutputStream z, File f, String name) throws IOException {
        if (f.isDirectory()) {
            File[] l = f.listFiles();
            if (l != null) for (File c : l) zip(z, c, name.isEmpty() ? c.getName() : name + "/" + c.getName());
            return;
        }
        if (name.contains(".part")) return;
        z.putNextEntry(new ZipEntry(name));
        InputStream in = new FileInputStream(f);
        pipe(in, z);
        in.close();
        z.closeEntry();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        if (req == REQ_MIC && micReq != null) {
            if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) micReq.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else micReq.deny();
            micReq = null;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean has) {
        super.onWindowFocusChanged(has);
        if (has) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (web != null) web.saveState(out);
    }

    /** Back never closes the app in the middle of an evening; it only steps aside. */
    @Override
    public void onBackPressed() {
        js("window.MNBack&&window.MNBack()");
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.stop();
        if (snd != null) snd.release();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
