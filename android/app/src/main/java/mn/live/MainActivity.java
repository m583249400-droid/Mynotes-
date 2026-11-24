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

/** MyNotes on the leader's tablet: shows the app and runs the small server the musicians connect to. */
public class MainActivity extends Activity {
    private static final int REQ_FILE = 11, REQ_SAVE = 12, REQ_OPEN = 13, REQ_MIC = 14;
    private WebView web;
    private LiveServer server;
    private int port = 8080;
    private File www, data;
    private ValueCallback<Uri[]> fileCb;
    private PermissionRequest micReq;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        www = new File(getFilesDir(), "www");
        data = new File(getFilesDir(), "live");
        data.mkdirs();
        try {
            unpackSite();
            startServer();
        } catch (Exception e) {
            Toast.makeText(this, "MyNotes: " + e, Toast.LENGTH_LONG).show();
        }
        web = new WebView(this);
        setContentView(web);
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
                if (url.startsWith("http://127.0.0.1:" + port + "/")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { /* nothing to open it */ }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest r) {
                boolean audio = false;
                for (String x : r.getResources()) if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(x)) audio = true;
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
        if (b == null) web.loadUrl("http://127.0.0.1:" + port + "/app/");
        else web.restoreState(b);
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
    class Host {
        @JavascriptInterface
        public String version() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public int port() { return port; }

        /** one zip with the whole Live library; the system picker lets the user put it in Drive */
        @JavascriptInterface
        public void exportBackup() {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/zip");
            i.putExtra(Intent.EXTRA_TITLE, "MyNotes-Live-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + ".zip");
            startActivityForResult(i, REQ_SAVE);
        }

        @JavascriptInterface
        public void importBackup() {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_OPEN);
        }

        /** a file the app made (PDF, MIDI, backup) goes to the Downloads folder */
        @JavascriptInterface
        public void saveFile(String name, String b64, String mime) {
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
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
