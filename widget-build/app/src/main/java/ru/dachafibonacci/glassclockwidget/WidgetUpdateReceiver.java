package ru.dachafibonacci.glassclockwidget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class WidgetUpdateReceiver extends BroadcastReceiver {
    public static final String ACTION_REFRESH_ART = "ru.dachafibonacci.glassclockwidget.REFRESH_ART";

    @Override
    public void onReceive(Context context, Intent intent) {
        GlassClockWidgetProvider.updateAll(context);
    }
}
