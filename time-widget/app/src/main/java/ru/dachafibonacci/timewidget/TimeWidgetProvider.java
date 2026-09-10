package ru.dachafibonacci.timewidget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.widget.RemoteViews;

public class TimeWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId);
        }
    }

    private static void updateWidget(Context context, AppWidgetManager manager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_time);

        Intent settingsIntent = new Intent(Settings.ACTION_DATE_SETTINGS);
        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                1001,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent);
        manager.updateAppWidget(appWidgetId, views);
    }
}
