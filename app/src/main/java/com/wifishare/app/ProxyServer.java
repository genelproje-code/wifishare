package com.wifishare.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small HTTP/HTTPS proxy. Devices that join the hotspot send their traffic here and it is
 * forwarded over this phone's own internet connection (its Wi-Fi).
 *
 * Supports:
 *  - CONNECT host:port  (HTTPS and any other TCP tunnel)
 *  - Plain HTTP with absolute URLs (http://host/path)
 *  - GET /proxy.pac      automatic proxy configuration file for clients
 *  - GET /               a short help page
 *
 * Pure Java (no Android APIs) so it can be unit-tested on a normal JVM.
 */
public class ProxyServer {

    public interface Listener {
        void onStatsChanged(int activeDevices, long bytesUp, long bytesDown);
    }

    /** Where outgoing connections are made. On Android this is bound to the Wi-Fi network. */
    public interface Upstream {
        Socket newSocket() throws IOException;
        InetAddress[] resolve(String host) throws IOException;
    }

    public static final Upstream DEFAULT_UPSTREAM = new Upstream() {
        @Override public Socket newSocket() { return new Socket(); }
        @Override public InetAddress[] resolve(String host) throws IOException { return InetAddress.getAllByName(host); }
    };

    private volatile Upstream upstreamNet = DEFAULT_UPSTREAM;
    public void setUpstream(Upstream u) { upstreamNet = u == null ? DEFAULT_UPSTREAM : u; }

    private static final int MAX_HEADER = 64 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int IDLE_TIMEOUT_MS = 5 * 60_000;

    private final String bindHost;
    private final int port;
    private volatile Listener listener;

    private ServerSocket server;
    private ExecutorService pool;
    private volatile boolean running;

    private final Map<String, Integer> clients = new ConcurrentHashMap<>();
    private final AtomicLong bytesUp = new AtomicLong();
    private final AtomicLong bytesDown = new AtomicLong();

    /** @param bindHost address to listen on, or null for all interfaces */
    public ProxyServer(String bindHost, int port) {
        this.bindHost = bindHost;
        this.port = port;
    }

    public void setListener(Listener l) { this.listener = l; }
    public int getPort() { return server != null ? server.getLocalPort() : port; }
    public boolean isRunning() { return running; }
    public int getActiveDevices() { return clients.size(); }
    public long getBytesUp() { return bytesUp.get(); }
    public long getBytesDown() { return bytesDown.get(); }

    public synchronized void start() throws IOException {
        if (running) return;
        server = new ServerSocket();
        server.setReuseAddress(true);
        InetAddress addr = bindHost == null ? null : InetAddress.getByName(bindHost);
        server.bind(new InetSocketAddress(addr, port), 128);
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "proxy-worker");
            t.setDaemon(true);
            return t;
        });
        running = true;
        Thread acceptor = new Thread(this::acceptLoop, "proxy-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        if (pool != null) pool.shutdownNow();
        clients.clear();
        notifyStats();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                pool.execute(() -> handle(s));
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void handle(Socket client) {
        String ip = client.getInetAddress().getHostAddress();
        clients.merge(ip, 1, Integer::sum);
        notifyStats();
        Socket upstream = null;
        try {
            client.setSoTimeout(IDLE_TIMEOUT_MS);
            client.setTcpNoDelay(true);
            InputStream cin = client.getInputStream();
            OutputStream cout = client.getOutputStream();

            byte[] head = readHead(cin);
            if (head == null) return;
            String headStr = new String(head, StandardCharsets.ISO_8859_1);
            int firstEol = headStr.indexOf("\r\n");
            String requestLine = firstEol >= 0 ? headStr.substring(0, firstEol) : headStr;
            String[] parts = requestLine.split(" ");
            if (parts.length < 3) { sendError(cout, 400, "Bad Request"); return; }
            String method = parts[0].toUpperCase(Locale.ROOT);
            String target = parts[1];
            String version = parts[2];

            if (method.equals("CONNECT")) {
                HostPort hp = HostPort.parse(target, 443);
                upstream = connect(hp);
                if (upstream == null) { sendError(cout, 502, "Bad Gateway"); return; }
                cout.write((version + " 200 Connection Established\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                cout.flush();
                tunnel(client, upstream, null);
                return;
            }

            if (!target.contains("://")) {
                serveLocal(cout, target, client.getLocalAddress().getHostAddress());
                return;
            }

            URI uri;
            try { uri = new URI(target); } catch (Exception e) { sendError(cout, 400, "Bad Request"); return; }
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                sendError(cout, 400, "Only http:// URLs or CONNECT are supported");
                return;
            }
            HostPort hp = new HostPort(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 80);
            upstream = connect(hp);
            if (upstream == null) { sendError(cout, 502, "Bad Gateway"); return; }

            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();

            StringBuilder out = new StringBuilder();
            out.append(method).append(' ').append(path).append(' ').append(version).append("\r\n");
            String[] lines = headStr.substring(firstEol + 2).split("\r\n");
            for (String line : lines) {
                if (line.isEmpty()) continue;
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.startsWith("proxy-connection:") || lower.startsWith("connection:")
                        || lower.startsWith("keep-alive:") || lower.startsWith("proxy-authorization:")) continue;
                out.append(line).append("\r\n");
            }
            out.append("Connection: close\r\n\r\n");
            byte[] req = out.toString().getBytes(StandardCharsets.ISO_8859_1);
            upstream.getOutputStream().write(req);
            upstream.getOutputStream().flush();
            bytesUp.addAndGet(req.length);
            tunnel(client, upstream, null);
        } catch (IOException ignored) {
        } finally {
            closeQuietly(upstream);
            closeQuietly(client);
            clients.computeIfPresent(ip, (k, v) -> v <= 1 ? null : v - 1);
            notifyStats();
        }
    }

    private Socket connect(HostPort hp) {
        Upstream up = upstreamNet;
        InetAddress[] addrs;
        try { addrs = up.resolve(hp.host); } catch (IOException e) { return null; }
        for (InetAddress a : addrs) {           // try each address (IPv4/IPv6) until one works
            Socket s = null;
            try {
                s = up.newSocket();
                s.connect(new InetSocketAddress(a, hp.port), CONNECT_TIMEOUT_MS);
                s.setSoTimeout(IDLE_TIMEOUT_MS);
                s.setTcpNoDelay(true);
                return s;
            } catch (IOException e) {
                closeQuietly(s);
            }
        }
        return null;
    }

    /** Copies data in both directions until either side closes. */
    private void tunnel(Socket client, Socket upstream, byte[] ignored) throws IOException {
        Thread up = new Thread(() -> pipe(client, upstream, bytesUp), "proxy-up");
        up.setDaemon(true);
        up.start();
        pipe(upstream, client, bytesDown);
        try { up.join(2_000); } catch (InterruptedException ignored2) { Thread.currentThread().interrupt(); }
    }

    private void pipe(Socket from, Socket to, AtomicLong counter) {
        byte[] buf = new byte[16 * 1024];
        long lastNotify = System.currentTimeMillis();
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
                counter.addAndGet(n);
                long now = System.currentTimeMillis();
                if (now - lastNotify > 1000) { notifyStats(); lastNotify = now; }
            }
        } catch (IOException ignored) {
        } finally {
            try { to.shutdownOutput(); } catch (IOException | UnsupportedOperationException ignored) { }
            try { from.shutdownInput(); } catch (IOException | UnsupportedOperationException ignored) { }
        }
    }

    private void serveLocal(OutputStream out, String path, String myIp) throws IOException {
        int p = getPort();
        if (path.startsWith("/proxy.pac") || path.startsWith("/wpad.dat")) {
            String pac = "function FindProxyForURL(url, host) {\n"
                    + "  if (isPlainHostName(host) || host == \"" + myIp + "\") return \"DIRECT\";\n"
                    + "  return \"PROXY " + myIp + ":" + p + "\";\n"
                    + "}\n";
            sendBody(out, 200, "OK", "application/x-ns-proxy-autoconfig", pac);
        } else {
            String html = "<!doctype html><html><head><meta name=viewport content='width=device-width'>"
                    + "<title>WiFi Share</title></head><body style='font-family:sans-serif;padding:16px'>"
                    + "<h2>WiFi Share is running</h2>"
                    + "<p>Set this device's proxy to <b>Automatic</b> with URL:</p>"
                    + "<p><code>http://" + myIp + ":" + p + "/proxy.pac</code></p>"
                    + "<p>or <b>Manual</b>: host <code>" + myIp + "</code>, port <code>" + p + "</code></p>"
                    + "</body></html>";
            sendBody(out, 200, "OK", "text/html; charset=utf-8", html);
        }
    }

    private static void sendBody(OutputStream out, int code, String msg, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String h = "HTTP/1.1 " + code + " " + msg + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
        out.write(b);
        out.flush();
    }

    private static void sendError(OutputStream out, int code, String msg) {
        try { sendBody(out, code, msg, "text/plain", code + " " + msg + "\n"); } catch (IOException ignored) { }
    }

    /** Reads the request head (up to and including the blank line). */
    private static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(1024);
        int state = 0, b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            if (buf.size() > MAX_HEADER) return null;
            if (b == '\r' && (state == 0 || state == 2)) state++;
            else if (b == '\n' && (state == 1 || state == 3)) { state++; if (state == 4) return buf.toByteArray(); }
            else state = 0;
        }
        return null;
    }

    private void notifyStats() {
        Listener l = listener;
        if (l != null) l.onStatsChanged(clients.size(), bytesUp.get(), bytesDown.get());
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try { s.close(); } catch (IOException ignored) { }
    }

    static final class HostPort {
        final String host; final int port;
        HostPort(String h, int p) { host = h; port = p; }
        static HostPort parse(String s, int defPort) {
            if (s.startsWith("[")) {               // IPv6 literal [::1]:443
                int end = s.indexOf(']');
                String h = s.substring(1, end);
                int p = (end + 2 <= s.length() && s.charAt(end + 1) == ':') ? Integer.parseInt(s.substring(end + 2)) : defPort;
                return new HostPort(h, p);
            }
            int c = s.lastIndexOf(':');
            if (c > 0) {
                try { return new HostPort(s.substring(0, c), Integer.parseInt(s.substring(c + 1))); }
                catch (NumberFormatException ignored) { }
            }
            return new HostPort(s, defPort);
        }
    }
}
