package ru.dachafibonacci.glassclockwidget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        float d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding((int) (28 * d), (int) (42 * d), (int) (28 * d), (int) (28 * d));
        root.setBackgroundColor(0xFF101713);

        TextView title = new TextView(this);
        title.setText("Стеклянные часы");
        title.setTextColor(0xFFF2FFF7);
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView text = new TextView(this);
        text.setText("Премиальный виджет 4×1. Слева — будильник, по центру — Подвал, справа — секундомер. Жидкость заполняет капсулу с 09:00 до 24:00.");
        text.setTextColor(0xFFBCD0C5);
        text.setTextSize(16);
        text.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textLp.topMargin = (int) (18 * d);
        root.addView(text, textLp);

        Button add = new Button(this);
        add.setText("Добавить виджет");
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (54 * d));
        buttonLp.topMargin = (int) (26 * d);
        root.addView(add, buttonLp);
        add.setOnClickListener(v -> pinWidget());

        Button refresh = new Button(this);
        refresh.setText("Обновить виджет");
        LinearLayout.LayoutParams refreshLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) (54 * d));
        refreshLp.topMargin = (int) (12 * d);
        root.addView(refresh, refreshLp);
        refresh.setOnClickListener(v -> {
            GlassClockWidgetProvider.updateAll(this);
            Toast.makeText(this, "Виджет обновлён", Toast.LENGTH_SHORT).show();
        });

        setContentView(root);
    }

    private void pinWidget() {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        if (android.os.Build.VERSION.SDK_INT >= 26 && manager.isRequestPinAppWidgetSupported()) {
            ComponentName provider = new ComponentName(this, GlassClockWidgetProvider.class);
            boolean requested = manager.requestPinAppWidget(provider, null, null);
            if (!requested) {
                Toast.makeText(this, "Открой список виджетов рабочего стола и выбери «Стеклянные часы».", Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "Открой список виджетов рабочего стола и выбери «Стеклянные часы».", Toast.LENGTH_LONG).show();
        }
    }
}
