package ru.dachafibonacci.glassclockwidget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.util.TypedValue;
import android.widget.RemoteViews;

public class GlassClockWidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId);
        }
        WidgetScheduler.schedule(context);
    }

    @Override
    public void onEnabled(Context context) {
        WidgetScheduler.schedule(context);
        updateAll(context);
    }

    @Override
    public void onDisabled(Context context) {
        WidgetScheduler.cancel(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager,
                                          int appWidgetId, android.os.Bundle newOptions) {
        updateWidget(context, appWidgetManager, appWidgetId);
    }

    public static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName component = new ComponentName(context, GlassClockWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(component);
        for (int id : ids) {
            updateWidget(context, manager, id);
        }
    }

    private static void updateWidget(Context context, AppWidgetManager manager, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_glass_clock);
        Bitmap artwork = WidgetRenderer.render(context, appWidgetId);
        views.setImageViewBitmap(R.id.widget_art, artwork);

        android.os.Bundle options = manager.getAppWidgetOptions(appWidgetId);
        int minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 54);
        int maxHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeightDp);
        int heightDp = Math.max(40, Math.min(Math.max(minHeightDp, maxHeightDp), 220));

        float timeSp = clamp(heightDp * 0.68f, 30f, 62f);
        float dateSp = clamp(heightDp * 0.19f, 11f, 18f);
        int vPad = Math.max(2, Math.round(heightDp * 0.06f));
        int sidePad = Math.max(10, Math.round(heightDp * 0.18f));
        int dateLeft = Math.max(30, Math.round(heightDp * 0.42f));

        views.setTextViewTextSize(R.id.time_text, TypedValue.COMPLEX_UNIT_SP, timeSp);
        views.setTextViewTextSize(R.id.date_text, TypedValue.COMPLEX_UNIT_SP, dateSp);
        views.setViewPadding(R.id.content_row, sidePad, vPad, sidePad, vPad);
        views.setViewPadding(R.id.date_text, dateLeft, 0, 0, 0);

        views.setOnClickPendingIntent(R.id.zone_left,
                actionPendingIntent(context, WidgetActionReceiver.ACTION_ALARM, appWidgetId * 10 + 1));
        views.setOnClickPendingIntent(R.id.zone_center,
                actionPendingIntent(context, WidgetActionReceiver.ACTION_PODVAL, appWidgetId * 10 + 2));
        views.setOnClickPendingIntent(R.id.zone_right,
                actionPendingIntent(context, WidgetActionReceiver.ACTION_STOPWATCH, appWidgetId * 10 + 3));

        manager.updateAppWidget(appWidgetId, views);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static PendingIntent actionPendingIntent(Context context, String action, int requestCode) {
        Intent intent = new Intent(context, WidgetActionReceiver.class);
        intent.setAction(action);
        return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }
}
