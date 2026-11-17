package dev.songlines.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Служба переднего плана с постоянным уведомлением: держит процесс и HTTP-сервер моста живыми,
 * пока игра на Xiaomi/HyperOS в фоне. Закрывать уведомление не нужно.
 */
public class BridgeService extends Service {
    private static final String CHANNEL = "bridge";
    private static final int NOTIF_ID = 1;

    private BridgeServer server;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String shownText = "";

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshNotification();
            handler.postDelayed(this, 4000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "SongLines Bridge", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);

        Notification n = build("Мост работает");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
        server = new BridgeServer(this);
        server.start();
        handler.postDelayed(ticker, 4000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (server != null) server.start(); // если сервер упал, поднять снова
        return START_STICKY;
    }

    private Notification build(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("SongLines Bridge")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void refreshNotification() {
        String t = BridgeServer.lastTrack;
        String text = t.isEmpty() ? (BridgeServer.serverUp ? "Мост работает, ждёт музыку" : "Сервер моста не запущен") : t;
        if (text.equals(shownText)) return;
        shownText = text;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, build(text));
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(ticker);
        if (server != null) server.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
