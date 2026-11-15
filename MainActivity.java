package dev.songlines.bridge;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Один экран: статус моста и кнопки для выдачи нужных разрешений. */
public class MainActivity extends Activity {
    private TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            status.setText(buildStatus());
            handler.postDelayed(this, 1000);
        }
    };

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setPadding(0, dp(6), 0, dp(6));
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ScrollView sv = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(40), dp(20), dp(20));
        sv.addView(root);

        root.addView(text("SongLines Bridge", 24));
        root.addView(text("Передаёт моду Songlines, что сейчас играет на телефоне (Lane, Spotify, YouTube Music и др.).", 14));
        status = text("", 15);
        root.addView(status);

        root.addView(button("1. Выдать доступ к уведомлениям", v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))));
        root.addView(text("Если переключатель серый или пишет «ограничено»: нажми кнопку 2, затем ⋮ вверху справа → «Разрешить ограниченные настройки», потом снова кнопка 1.", 13));
        root.addView(button("2. Открыть настройки приложения", v -> {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        }));
        root.addView(button("3. Батарея: убрать ограничения", v ->
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))));
        root.addView(text("Найди SongLines Bridge и выбери «Без ограничений», иначе система может выключать мост.", 13));
        root.addView(button("Перезапустить мост", v -> {
            try {
                NotificationListenerService.requestRebind(new ComponentName(this, MediaBridgeService.class));
            } catch (Exception ignored) {
            }
        }));
        root.addView(text("В моде: меню (M) → Source → Bridge.", 13));
        setContentView(sv);
    }

    private boolean listenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    private String buildStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("Доступ к уведомлениям: ").append(listenerEnabled() ? "есть ✓" : "нет ✗").append("\n");
        sb.append("Сервер 127.0.0.1:").append(MediaBridgeService.PORT).append(": ")
                .append(MediaBridgeService.serverUp ? "работает ✓" : "выключен ✗").append("\n");
        String t = MediaBridgeService.lastTrack;
        sb.append("Сейчас: ").append(t == null || t.isEmpty() ? "ничего не играет" : t);
        return sb.toString();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }
}
