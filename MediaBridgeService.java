package dev.songlines.bridge;

import android.content.ComponentName;
import android.content.Intent;
import android.service.notification.NotificationListenerService;

/**
 * Слушатель уведомлений: именно его разрешение даёт доступ к медиа-сессиям любого плеера.
 * Сервер моста живёт в BridgeService (служба переднего плана). Если её запустить не удалось —
 * сервер поднимается прямо здесь, как раньше.
 */
public class MediaBridgeService extends NotificationListenerService {
    private BridgeServer fallback;

    @Override
    public void onListenerConnected() {
        try {
            startForegroundService(new Intent(this, BridgeService.class));
        } catch (Throwable t) {
            // из фона Android может не разрешить запуск — тогда сервер работает внутри слушателя
            if (fallback == null) fallback = new BridgeServer(this);
            fallback.start();
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (fallback != null) fallback.stop();
        try {
            requestRebind(new ComponentName(this, MediaBridgeService.class)); // попросить систему подключить заново
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        if (fallback != null) fallback.stop();
        super.onDestroy();
    }
}
