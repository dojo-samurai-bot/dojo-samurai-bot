package ru.dachafibonacci.glassclockwidget;

import android.provider.AlarmClock;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

public class WidgetActionReceiver extends BroadcastReceiver {
    public static final String ACTION_ALARM = "ru.dachafibonacci.glassclockwidget.OPEN_ALARM";
    public static final String ACTION_PODVAL = "ru.dachafibonacci.glassclockwidget.OPEN_PODVAL";
    public static final String ACTION_STOPWATCH = "ru.dachafibonacci.glassclockwidget.OPEN_STOPWATCH";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_ALARM.equals(action)) {
            openAlarm(context);
        } else if (ACTION_PODVAL.equals(action)) {
            openPodval(context);
        } else if (ACTION_STOPWATCH.equals(action)) {
            openStopwatch(context);
        }
    }

    private void openAlarm(Context context) {
        Intent standard = new Intent(AlarmClock.ACTION_SHOW_ALARMS);
        if (launchIfResolvable(context, standard)) return;
        launchClockMain(context, false);
    }

    private void openPodval(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent launch = pm.getLaunchIntentForPackage("ru.dachafibonacci.podval");
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            safeStart(context, launch);
            return;
        }
        Intent fallback = new Intent(Intent.ACTION_MAIN);
        fallback.addCategory(Intent.CATEGORY_LAUNCHER);
        fallback.setPackage("ru.dachafibonacci.podval");
        safeStart(context, fallback);
    }

    private void openStopwatch(Context context) {
        String[] actions = new String[] {
                "android.intent.action.SHOW_STOPWATCH",
                "com.android.deskclock.action.STOPWATCH",
                "com.google.android.deskclock.action.STOPWATCH",
                "com.vivo.clock.action.STOPWATCH",
                "com.android.BBKClock.action.STOPWATCH"
        };
        for (String action : actions) {
            Intent i = new Intent(action);
            if (launchIfResolvable(context, i)) return;
        }

        ComponentName[] vivoCandidates = new ComponentName[] {
                new ComponentName("com.android.BBKClock", "com.android.BBKClock.StopWatchActivity"),
                new ComponentName("com.android.BBKClock", "com.android.BBKClock.StopwatchActivity"),
                new ComponentName("com.android.BBKClock", "com.android.BBKClock.TimerActivity"),
                new ComponentName("com.android.BBKClock", "com.android.BBKClock.AlarmClock")
        };
        for (ComponentName component : vivoCandidates) {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setComponent(component);
            i.putExtra("deskclock.select.tab", 2);
            i.putExtra("select_tab", 2);
            i.putExtra("selected_tab", 2);
            i.putExtra("tab", "stopwatch");
            if (launchIfResolvable(context, i)) return;
        }

        launchClockMain(context, true);
    }

    private void launchClockMain(Context context, boolean stopwatch) {
        String[] packages = new String[] {
                "com.android.BBKClock",
                "com.vivo.clock",
                "com.google.android.deskclock",
                "com.android.deskclock"
        };
        PackageManager pm = context.getPackageManager();
        for (String pkg : packages) {
            Intent launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) continue;
            if (stopwatch) {
                launch.putExtra("deskclock.select.tab", 2);
                launch.putExtra("select_tab", 2);
                launch.putExtra("selected_tab", 2);
                launch.putExtra("tab", "stopwatch");
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (safeStart(context, launch)) return;
        }
    }

    private boolean launchIfResolvable(Context context, Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PackageManager pm = context.getPackageManager();
        if (intent.resolveActivity(pm) == null) return false;
        return safeStart(context, intent);
    }

    private boolean safeStart(Context context, Intent intent) {
        try {
            context.startActivity(intent);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
