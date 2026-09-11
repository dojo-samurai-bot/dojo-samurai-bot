package ru.dachafibonacci.timewidget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.util.Calendar;

public class TimeWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        String action = intent.getAction();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || Intent.ACTION_DATE_CHANGED.equals(action)
                || Intent.ACTION_LOCALE_CHANGED.equals(action)) {
            refreshAll(context);
        }
    }

    private static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName provider = new ComponentName(context, TimeWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(provider);
        for (int id : ids) {
            updateWidget(context, manager, id);
        }
    }

    private static void updateWidget(Context context, AppWidgetManager manager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_time);

        Calendar now = Calendar.getInstance();
        int totalMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int progress;
        if (totalMinutes < 9 * 60) {
            progress = 0;
        } else {
            progress = Math.min(900, totalMinutes - 9 * 60);
        }
        int visualProgress = progress > 0 ? Math.max(progress, 45) : 0;
        views.setProgressBar(R.id.day_fill, 900, visualProgress, false);

        Intent proxyIntent = new Intent(context, AlarmProxyActivity.class);
        proxyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                7000 + appWidgetId,
                proxyIntent,
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        int[] clickableIds = new int[] {
                R.id.widget_root,
                R.id.bubble_container,
                R.id.day_fill,
                R.id.content_row,
                R.id.time_text,
                R.id.divider,
                R.id.clock_icon,
                R.id.date_text
        };
        for (int id : clickableIds) {
            views.setOnClickPendingIntent(id, pendingIntent);
        }

        manager.updateAppWidget(appWidgetId, views);
    }
}
