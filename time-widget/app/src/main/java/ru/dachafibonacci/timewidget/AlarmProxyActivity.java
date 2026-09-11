package ru.dachafibonacci.timewidget;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.AlarmClock;

public class AlarmProxyActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        openAlarms();
        finish();
    }

    private void openAlarms() {
        // Vivo / iQOO stock Clock first.
        if (tryStart(new Intent(AlarmClock.ACTION_SHOW_ALARMS)
                .setPackage("com.android.BBKClock"))) {
            return;
        }

        // Standard Android contract for an alarm list.
        if (tryStart(new Intent(AlarmClock.ACTION_SHOW_ALARMS))) {
            return;
        }

        // Vivo Clock launcher activity fallback.
        if (tryStart(new Intent(Intent.ACTION_MAIN)
                .setClassName("com.android.BBKClock", "com.android.BBKClock.Timer"))) {
            return;
        }

        // Final functional fallback: open alarm creation in any compatible Clock app.
        tryStart(new Intent(AlarmClock.ACTION_SET_ALARM));
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
