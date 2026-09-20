package ru.dachafibonacci.glassclockwidget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        WidgetScheduler.schedule(context);
        GlassClockWidgetProvider.updateAll(context);
    }
}
