package ru.dachafibonacci.manager;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

public class DachaWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) manager.updateAppWidget(id, build(context));
    }

    private RemoteViews build(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("dacha_data", Context.MODE_PRIVATE);
        int open = 0;
        String first = "На сегодня всё закрыто";
        try {
            JSONArray tasks = new JSONArray(prefs.getString("tasks", "[]"));
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.optJSONObject(i);
                if (task != null && !task.optBoolean("done")) {
                    if (open == 0) first = task.optString("title", "Текущее дело");
                    open++;
                }
            }
        } catch (Exception ignored) { }

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.dacha_widget);
        views.setTextViewText(R.id.widget_count, open + " дел");
        views.setTextViewText(R.id.widget_task, first);
        views.setTextViewText(R.id.widget_shop, "Купить: " + prefs.getInt("shopping_count", 0));
        views.setOnClickPendingIntent(R.id.widget_root, openApp(context, "", 10));
        views.setOnClickPendingIntent(R.id.widget_add_task, openApp(context, "task", 11));
        views.setOnClickPendingIntent(R.id.widget_income, openApp(context, "income", 12));
        views.setOnClickPendingIntent(R.id.widget_expense, openApp(context, "expense", 13));
        return views;
    }

    private PendingIntent openApp(Context context, String action, int requestCode) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.putExtra("quick_action", action);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
