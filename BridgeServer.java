package dev.songlines.bridge;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Крошечный HTTP-сервер на 127.0.0.1:38917 + чтение медиа-сессий.
 * Протокол совпадает с тем, что ждёт мод Songlines:
 * GET /state, GET /cover, POST /cmd/next | /cmd/prev | /cmd/playpause
 * Работает внутри BridgeService (служба переднего плана); если её запустить не вышло — внутри MediaBridgeService.
 */
final class BridgeServer {
    static final int PORT = 38917;
    static volatile boolean serverUp;
    static volatile String lastTrack = "";

    private final Context ctx;
    private final ComponentName listener;
    private volatile boolean running;
    private ServerSocket server;
    private MediaSessionManager msm;
    private String coverKey = "";
    private byte[] coverJpeg;

    BridgeServer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.listener = new ComponentName(this.ctx, MediaBridgeService.class);
    }

    synchronized void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::serve, "songlines-bridge-http");
        t.setDaemon(true);
        t.start();
    }

    synchronized void stop() {
        running = false;
        serverUp = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    private void serve() {
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 8);
            server = s;
            serverUp = true;
            while (running) {
                try (Socket c = s.accept()) {
                    handle(c);
                } catch (Exception e) {
                    if (!running) break;
                }
            }
        } catch (Exception ignored) {
            // порт занят (сервер уже запущен другой службой) и т.п.
        } finally {
            serverUp = false;
            running = false;
        }
    }

    private void handle(Socket c) throws Exception {
        c.setSoTimeout(2000);
        BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.ISO_8859_1));
        String line = in.readLine();
        if (line == null) return;
        String h;
        while ((h = in.readLine()) != null && !h.isEmpty()) {
            // пропускаем заголовки
        }
        String[] p = line.split(" ");
        String method = p[0];
        String path = p.length > 1 ? p[1] : "/";
        OutputStream out = c.getOutputStream();
        if (path.startsWith("/state")) {
            respond(out, 200, "application/json", state().toString().getBytes(StandardCharsets.UTF_8));
        } else if (path.startsWith("/cover")) {
            byte[] b;
            synchronized (this) {
                b = coverJpeg;
            }
            if (b == null) respond(out, 404, "text/plain", new byte[0]);
            else respond(out, 200, "image/jpeg", b);
        } else if (method.equals("POST") && path.startsWith("/cmd/")) {
            command(path.substring(5));
            respond(out, 200, "text/plain", "ok".getBytes(StandardCharsets.UTF_8));
        } else {
            respond(out, 404, "text/plain", new byte[0]);
        }
    }

    private void respond(OutputStream out, int code, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Not Found") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    /** Предпочитаем сессию, которая сейчас играет; иначе самую свежую с метаданными. */
    private MediaController pick() {
        try {
            if (msm == null) msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (msm == null) return null;
            List<MediaController> list = msm.getActiveSessions(listener);
            MediaController best = null;
            for (MediaController c : list) {
                PlaybackState ps = c.getPlaybackState();
                if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) return c;
                if (best == null && c.getMetadata() != null) best = c;
            }
            return best;
        } catch (SecurityException e) {
            return null; // доступ к уведомлениям ещё не выдан
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    private JSONObject state() throws Exception {
        JSONObject o = new JSONObject();
        MediaController c = pick();
        MediaMetadata m = c == null ? null : c.getMetadata();
        if (m == null) {
            o.put("playing", false);
            o.put("title", "");
            o.put("artist", "");
            o.put("album", "");
            o.put("positionMs", 0);
            o.put("durationMs", 0);
            o.put("source", "");
            o.put("coverId", "");
            lastTrack = "";
            return o;
        }
        String title = nz(m.getString(MediaMetadata.METADATA_KEY_TITLE));
        String artist = nz(m.getString(MediaMetadata.METADATA_KEY_ARTIST));
        if (artist.isEmpty()) artist = nz(m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
        String album = nz(m.getString(MediaMetadata.METADATA_KEY_ALBUM));
        PlaybackState ps = c.getPlaybackState();
        boolean playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
        long pos = 0;
        if (ps != null) {
            pos = Math.max(0, ps.getPosition());
            if (playing) {
                long dt = SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime();
                if (dt > 0 && dt < 3_600_000L) pos += (long) (dt * ps.getPlaybackSpeed());
            }
        }
        Bitmap bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (bmp == null) bmp = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (bmp == null) bmp = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        String coverId = bmp == null ? "" : (title + "|" + artist + "|" + album + "|" + bmp.getWidth());
        updateCover(coverId, bmp);
        String source = appLabel(c.getPackageName());
        lastTrack = title + (artist.isEmpty() ? "" : " — " + artist) + " (" + source + ")";
        o.put("playing", playing);
        o.put("title", title);
        o.put("artist", artist);
        o.put("album", album);
        o.put("positionMs", pos);
        o.put("durationMs", Math.max(0, m.getLong(MediaMetadata.METADATA_KEY_DURATION)));
        o.put("source", source);
        o.put("coverId", coverId);
        return o;
    }

    private synchronized void updateCover(String id, Bitmap bmp) {
        if (id.equals(coverKey)) return;
        coverKey = id;
        if (bmp == null) {
            coverJpeg = null;
            return;
        }
        int w = bmp.getWidth(), h = bmp.getHeight();
        float k = Math.min(1f, 512f / Math.max(w, h));
        Bitmap s = k < 1f ? Bitmap.createScaledBitmap(bmp, Math.round(w * k), Math.round(h * k), true) : bmp;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        s.compress(Bitmap.CompressFormat.JPEG, 88, bo);
        coverJpeg = bo.toByteArray();
    }

    private void command(String cmd) {
        MediaController c = pick();
        if (c == null) return;
        MediaController.TransportControls t = c.getTransportControls();
        switch (cmd) {
            case "next":
                t.skipToNext();
                break;
            case "prev":
                t.skipToPrevious();
                break;
            case "playpause": {
                PlaybackState ps = c.getPlaybackState();
                if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) t.pause();
                else t.play();
                break;
            }
            default:
                break;
        }
    }
}
