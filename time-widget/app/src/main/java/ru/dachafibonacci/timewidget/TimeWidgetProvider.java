package ru.dachafibonacci.timewidget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.provider.AlarmClock;
import android.widget.RemoteViews;

import java.util.Calendar;

public class TimeWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId);
        }
    }

    private static void updateWidget(Context context, AppWidgetManager manager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_time);

        Calendar now = Calendar.getInstance();
        int totalMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int progress;
        if (totalMinutes == 0) {
            progress = 900;
        } else if (totalMinutes < 9 * 60) {
            progress = 0;
        } else {
            progress = Math.min(900, totalMinutes - 9 * 60);
        }
        views.setProgressBar(R.id.day_fill, 900, progress, false);

        Intent alarmIntent = new Intent(AlarmClock.ACTION_SHOW_ALARMS);
        alarmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                1002,
                alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent);
        views.setOnClickPendingIntent(R.id.time_text, pendingIntent);
        views.setOnClickPendingIntent(R.id.date_text, pendingIntent);
        views.setOnClickPendingIntent(R.id.clock_icon, pendingIntent);
        manager.updateAppWidget(appWidgetId, views);
    }
}
