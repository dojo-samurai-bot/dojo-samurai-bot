package ru.dachafibonacci.timewidget;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.AlarmClock;
import android.provider.Settings;

public class AlarmProxyActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        openAlarms();
        finish();
    }

    private void openAlarms() {
        // Vivo / iQOO stock Clock. The user's device is Vivo-family, so try it first.
        if (tryStart(new Intent(AlarmClock.ACTION_SHOW_ALARMS)
                .setPackage("com.android.BBKClock"))) {
            return;
        }

        // Standard Android contract used by Clock apps that expose an alarm list.
        if (tryStart(new Intent(AlarmClock.ACTION_SHOW_ALARMS))) {
            return;
        }

        // Vivo Clock launcher activity fallback.
        if (tryStart(new Intent(Intent.ACTION_MAIN)
                .setClassName("com.android.BBKClock", "com.android.BBKClock.Timer"))) {
            return;
        }

        // Last functional fallback: open alarm creation in any compatible Clock app.
        if (tryStart(new Intent(AlarmClock.ACTION_SET_ALARM))) {
            return;
        }

        // Absolute last resort: system alarm-related settings.
        tryStart(new Intent(Settings.ACTION_ALARM_SETTINGS));
    }

    private boolean tryStart(Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            return false;
        }
    }
}
