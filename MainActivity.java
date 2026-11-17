package dev.songlines.bridge;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1500);
        }
    };

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(24), dp(16), dp(24));

        TextView title = new TextView(this);
        title.setText("SongLines Bridge");
        title.setTextSize(22);
        col.addView(title);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, dp(12), 0, dp(12));
        col.addView(status);

        addButton(col, "1. Доступ к уведомлениям", this::openListenerSettings);
        addButton(col, "   Если пункт серый: сведения о приложении → ⋮ → «Разрешить ограниченные настройки»", this::openAppDetails);
        addButton(col, "2. Разрешить показ уведомлений", this::askNotifications);
        addButton(col, "3. Автозапуск (Xiaomi)", this::openAutostart);
        addButton(col, "4. Батарея: «Без ограничений»", this::openBattery);
        addButton(col, "Перезапустить службу моста", this::restartService);

        TextView hint = new TextView(this);
        hint.setText("\nЧтобы мост жил в фоне на Xiaomi/HyperOS: включи автозапуск, поставь батарею «Без ограничений», "
                + "закрепи приложение в списке недавних (замок) и не смахивай постоянное уведомление.");
        hint.setTextSize(13);
        col.addView(hint);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private void addButton(LinearLayout col, String label, Runnable r) {
        Button bt = new Button(this);
        bt.setText(label);
        bt.setAllCaps(false);
        bt.setOnClickListener((View v) -> r.run());
        col.addView(bt);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBridge();
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresher);
        super.onPause();
    }

    private void startBridge() {
        try {
            startForegroundService(new Intent(this, BridgeService.class));
        } catch (Throwable ignored) {
        }
    }

    private void restartService() {
        stopService(new Intent(this, BridgeService.class));
        handler.postDelayed(this::startBridge, 400);
    }

    private boolean listenerEnabled() {
        String s = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(getPackageName());
    }

    private boolean notificationsAllowed() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void refresh() {
        String t = BridgeServer.lastTrack;
        status.setText("Доступ к уведомлениям: " + (listenerEnabled() ? "выдан ✓" : "НЕ выдан ✗")
                + "\nПоказ уведомлений: " + (notificationsAllowed() ? "разрешён ✓" : "не разрешён ✗")
                + "\nСервер 127.0.0.1:" + BridgeServer.PORT + ": " + (BridgeServer.serverUp ? "работает ✓" : "не запущен ✗")
                + "\nСейчас: " + (t.isEmpty() ? "ничего не играет" : t));
    }

    private boolean tryStart(Intent i) {
        try {
            startActivity(i);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void openListenerSettings() {
        if (!tryStart(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) openAppDetails();
    }

    private void openAppDetails() {
        tryStart(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationsAllowed()) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        } else {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            if (!tryStart(i)) openAppDetails();
        }
    }

    /** Экран автозапуска MIUI/HyperOS; на других телефонах и если не нашёлся — сведения о приложении. */
    private void openAutostart() {
        Intent i = new Intent().setComponent(new ComponentName("com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"));
        if (!tryStart(i)) openAppDetails();
    }

    private void openBattery() {
        Intent miui = new Intent().setComponent(new ComponentName("com.miui.powerkeeper",
                        "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                .putExtra("package_name", getPackageName())
                .putExtra("package_label", "SongLines Bridge");
        if (tryStart(miui)) return;
        if (tryStart(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return;
        openAppDetails();
    }
}
