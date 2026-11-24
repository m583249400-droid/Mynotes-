package mn.live;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.nanohttpd.protocols.http.IHTTPSession;
import org.nanohttpd.protocols.http.request.Method;
import org.nanohttpd.protocols.http.response.Response;
import org.nanohttpd.protocols.http.response.Status;
import org.nanohttpd.protocols.websockets.CloseCode;
import org.nanohttpd.protocols.websockets.NanoWSD;
import org.nanohttpd.protocols.websockets.WebSocket;
import org.nanohttpd.protocols.websockets.WebSocketFrame;

/**
 * MyNotes Live: a small relay + file store that runs on the leader's tablet.
 * All logic lives in the web pages; this server only stores files and passes messages.
 *  - static pages from wwwDir ("/" is the musician page, "/app/" the leader app, leader only)
 *  - /b/<key>   stored files (sheet music): anyone reads, only the leader (loopback) writes
 *  - /kv/<name> small JSON documents: anyone reads, only the leader writes
 *  - /ws        messages: leader -> musicians (broadcast / one / retained state), musicians -> leader
 */
public class LiveServer extends NanoWSD {
    private final File www, blobs, kv;
    private final int port;
    private final Map<Integer, Sock> players = new ConcurrentHashMap<>();
    private final Set<Sock> leaders = Collections.newSetFromMap(new ConcurrentHashMap<Sock, Boolean>());
    private final AtomicInteger ids = new AtomicInteger(1);
    private volatile String state = null;
    private Thread reaper;

    public LiveServer(File wwwDir, File dataDir, int port) {
        super(port);
        this.port = port;
        this.www = wwwDir;
        this.blobs = new File(dataDir, "blobs");
        this.kv = new File(dataDir, "kv");
        blobs.mkdirs();
        kv.mkdirs();
    }

    public void startServer() throws IOException {
        start(0, true);
        reaper = new Thread(new Runnable() {
            public void run() {
                while (isAlive()) {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                    long now = System.currentTimeMillis();
                    for (Sock s : players.values()) if (now - s.seen > 20000) s.drop();
                    for (Sock s : leaders) if (now - s.seen > 30000) s.drop();
                }
            }
        }, "mn-live-reaper");
        reaper.setDaemon(true);
        reaper.start();
    }

    // ---------- helpers ----------
    static boolean loopback(IHTTPSession s) {
        String ip = s.getRemoteIpAddress();
        return ip != null && (ip.startsWith("127.") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1"));
    }

    static String safe(String key) {
        if (key == null || key.isEmpty() || key.length() > 300) return null;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '.' || c == '_' || c == '-' || c == '/')) return null;
        }
        if (key.contains("..") || key.startsWith("/") || key.contains("//")) return null;
        return key;
    }

    static String mime(String n) {
        n = n.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html")) return "text/html; charset=utf-8";
        if (n.endsWith(".js") || n.endsWith(".mjs")) return "text/javascript; charset=utf-8";
        if (n.endsWith(".css")) return "text/css; charset=utf-8";
        if (n.endsWith(".json") || n.endsWith(".webmanifest")) return "application/json; charset=utf-8";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".wasm")) return "application/wasm";
        if (n.endsWith(".musicxml") || n.endsWith(".xml")) return "application/xml";
        return "application/octet-stream";
    }

    static Response text(Status st, String s) {
        Response r = Response.newFixedLengthResponse(st, "text/plain; charset=utf-8", s);
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    static Response json(String s) {
        Response r = Response.newFixedLengthResponse(Status.OK, "application/json; charset=utf-8", s.getBytes(StandardCharsets.UTF_8));
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    static Response file(File f, String cache) throws IOException {
        if (!f.isFile()) return text(Status.NOT_FOUND, "not found");
        Response r = Response.newFixedLengthResponse(Status.OK, mime(f.getName()), new FileInputStream(f), f.length());
        r.addHeader("Cache-Control", cache);
        return r;
    }

    static void readBody(IHTTPSession s, File dst) throws IOException {
        long len;
        try { len = Long.parseLong(s.getHeaders().get("content-length")); } catch (Exception e) { throw new IOException("length required"); }
        File tmp = new File(dst.getParentFile(), dst.getName() + ".part" + System.nanoTime());
        dst.getParentFile().mkdirs();
        InputStream in = s.getInputStream();
        OutputStream out = new FileOutputStream(tmp);
        try {
            byte[] buf = new byte[65536];
            long left = len;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new EOFException();
                out.write(buf, 0, n);
                left -= n;
            }
        } catch (IOException e) {
            out.close();
            tmp.delete();
            throw e;
        }
        out.close();
        if (dst.exists()) dst.delete();
        if (!tmp.renameTo(dst)) { tmp.delete(); throw new IOException("rename failed"); }
    }

    static void rm(File f) {
        if (f.isDirectory()) { File[] l = f.listFiles(); if (l != null) for (File c : l) rm(c); }
        f.delete();
    }

    static void list(File base, File f, StringBuilder sb) {
        File[] l = f.listFiles();
        if (l == null) return;
        for (File c : l) {
            if (c.isDirectory()) list(base, c, sb);
            else if (!c.getName().contains(".part")) {
                if (sb.length() > 1) sb.append(',');
                sb.append('"').append(c.getPath().substring(base.getPath().length() + 1).replace(File.separatorChar, '/')).append("\":").append(c.length());
            }
        }
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) { if (c == '"' || c == '\\') b.append('\\'); if (c >= ' ') b.append(c); }
        return b.toString();
    }

    public static List<String> lanAddresses() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) out.add(a.getHostAddress());
            }
        } catch (Exception e) { /* none */ }
        if (out.isEmpty()) {
            try {
                for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (InetAddress a : Collections.list(ni.getInetAddresses()))
                        if (a instanceof Inet4Address) out.add(a.getHostAddress());
                }
            } catch (Exception e) { /* none */ }
        }
        return out;
    }

    // ---------- http ----------
    @Override
    public Response serve(IHTTPSession s) {
        try {
            String uri = s.getUri();
            Method m = s.getMethod();
            boolean me = loopback(s);
            if (uri.equals("/info")) {
                StringBuilder sb = new StringBuilder("{\"app\":\"mynotes-live\",\"v\":1,\"port\":" + port + ",\"leader\":" + me + ",\"players\":" + players.size() + ",\"ips\":[");
                List<String> ips = lanAddresses();
                for (int i = 0; i < ips.size(); i++) sb.append(i > 0 ? "," : "").append('"').append(esc(ips.get(i))).append('"');
                return json(sb.append("]}").toString());
            }
            if (uri.startsWith("/b/") || uri.startsWith("/kv/")) {
                boolean isKv = uri.startsWith("/kv/");
                String raw = uri.substring(isKv ? 4 : 3);
                boolean dir = raw.endsWith("/");
                String key = safe(dir ? raw.substring(0, raw.length() - 1) : raw);
                if (key == null) return text(Status.BAD_REQUEST, "bad key");
                File f = new File(isKv ? kv : blobs, key);
                if (m == Method.GET || m == Method.HEAD) return file(f, isKv ? "no-store" : "public, max-age=31536000, immutable");
                if (!me) return text(Status.FORBIDDEN, "leader only");
                if (m == Method.PUT) { readBody(s, f); return text(Status.OK, "ok"); }
                if (m == Method.DELETE) { rm(f); return text(Status.OK, "ok"); }
                return text(Status.METHOD_NOT_ALLOWED, "method");
            }
            if (uri.equals("/bl")) {
                if (!me) return text(Status.FORBIDDEN, "leader only");
                StringBuilder sb = new StringBuilder("{");
                list(blobs, blobs, sb);
                return json(sb.append('}').toString());
            }
            if (m != Method.GET && m != Method.HEAD) return text(Status.METHOD_NOT_ALLOWED, "method");
            if (uri.equals("/") || uri.equals("/player")) { Response r = text(Status.REDIRECT, ""); r.addHeader("Location", "/player/"); return r; }
            if (uri.equals("/app")) { Response r = text(Status.REDIRECT, ""); r.addHeader("Location", "/app/"); return r; }
            if (uri.endsWith("/")) uri += "index.html";
            if (uri.startsWith("/app/") && !me) return text(Status.FORBIDDEN, "leader only");
            String key = uri.substring(1);
            // file names in the app folder may hold other letters; only block path tricks
            if (key.contains("..") || key.contains("\\") || key.contains("//")) return text(Status.BAD_REQUEST, "bad path");
            return file(new File(www, key), key.endsWith(".html") ? "no-cache" : "public, max-age=3600");
        } catch (Exception e) {
            return text(Status.INTERNAL_ERROR, String.valueOf(e));
        }
    }

    // ---------- messages ----------
    @Override
    protected WebSocket openWebSocket(IHTTPSession hs) {
        boolean lead = loopback(hs) && "c=1".equals(hs.getQueryParameterString());
        return new Sock(hs, lead, hs.getRemoteIpAddress());
    }

    void toLeaders(String msg) { for (Sock l : leaders) l.put(msg); }

    class Sock extends WebSocket {
        final boolean lead;
        final String ip;
        final int id = ids.getAndIncrement();
        volatile long seen = System.currentTimeMillis();

        Sock(IHTTPSession hs, boolean lead, String ip) { super(hs); this.lead = lead; this.ip = ip; }

        void put(String s) {
            try { synchronized (this) { send(s); } } catch (Exception e) { drop(); }
        }

        void drop() {
            try { close(CloseCode.GoingAway, "bye", false); } catch (Exception e) { /* gone */ }
            gone();
        }

        void gone() {
            if (lead) leaders.remove(this);
            else if (players.remove(id) != null) toLeaders("L " + id);
        }

        @Override protected void onOpen() {
            if (lead) {
                leaders.add(this);
                StringBuilder sb = new StringBuilder("W");
                for (Sock p : players.values()) sb.append(' ').append(p.id);
                put(sb.toString());
            } else {
                players.put(id, this);
                put("{\"t\":\"id\",\"id\":" + id + "}");
                String st = state;
                if (st != null) put(st);
                toLeaders("J " + id + " " + ip);
            }
        }

        @Override protected void onClose(CloseCode code, String reason, boolean remote) { gone(); }

        @Override protected void onMessage(WebSocketFrame f) {
            seen = System.currentTimeMillis();
            String t = f.getTextPayload();
            if (t == null) return;
            if (t.equals("p")) { put("p"); return; }
            if (!lead) { toLeaders("M " + id + "\n" + t); return; }
            int nl = t.indexOf('\n');
            if (nl < 0) return;
            String head = t.substring(0, nl), body = t.substring(nl + 1);
            if (head.equals("S")) { state = body; for (Sock p : players.values()) p.put(body); }
            else if (head.equals("B")) { for (Sock p : players.values()) p.put(body); }
            else if (head.startsWith("T ")) {
                try { Sock p = players.get(Integer.parseInt(head.substring(2).trim())); if (p != null) p.put(body); } catch (NumberFormatException e) { /* ignore */ }
            }
        }

        @Override protected void onPong(WebSocketFrame pong) { seen = System.currentTimeMillis(); }

        @Override protected void onException(IOException e) { gone(); }
    }

    /** For trying the server on a computer: java mn.live.LiveServer <wwwDir> <dataDir> [port] */
    public static void main(String[] a) throws Exception {
        int port = a.length > 2 ? Integer.parseInt(a[2]) : 8080;
        LiveServer s = new LiveServer(new File(a[0]), new File(a[1]), port);
        s.startServer();
        System.out.println("MyNotes Live on port " + port + " " + lanAddresses());
        Thread.currentThread().join();
    }
}
