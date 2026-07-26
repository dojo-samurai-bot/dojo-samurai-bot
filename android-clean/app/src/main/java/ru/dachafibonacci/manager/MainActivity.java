package ru.dachafibonacci.manager;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String PREFS = "dacha_data";
    private static final String TASKS = "tasks";
    private SharedPreferences prefs;
    private LinearLayout taskList;
    private TextView taskCounter;
    private TextView financeSummary;
    private final int cream = Color.rgb(247, 243, 234);
    private final int sand = Color.rgb(232, 222, 203);
    private final int green = Color.rgb(74, 104, 82);
    private final int graphite = Color.rgb(46, 49, 45);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        buildUi();
        String action = getIntent().getStringExtra("quick_action");
        if ("task".equals(action)) showAddTask();
        if ("income".equals(action)) showMoneyDialog(true);
        if ("expense".equals(action)) showMoneyDialog(false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String action = intent.getStringExtra("quick_action");
        if ("task".equals(action)) showAddTask();
        if ("income".equals(action)) showMoneyDialog(true);
        if ("expense".equals(action)) showMoneyDialog(false);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(cream);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView title = text("Дача Фибоначчи", 28, true);
        root.addView(title);
        TextView subtitle = text("Оперативная панель", 15, false);
        subtitle.setTextColor(green);
        root.addView(subtitle, margin(-1, -2, 0, 3, 0, 18));

        LinearLayout today = card();
        today.addView(text("Сегодня", 19, true));
        taskCounter = text("", 15, false);
        today.addView(taskCounter, margin(-1, -2, 0, 8, 0, 0));
        root.addView(today);

        LinearLayout money = new LinearLayout(this);
        money.setOrientation(LinearLayout.HORIZONTAL);
        Button income = button("＋ Доход");
        Button expense = button("− Расход");
        income.setOnClickListener(v -> showMoneyDialog(true));
        expense.setOnClickListener(v -> showMoneyDialog(false));
        money.addView(income, new LinearLayout.LayoutParams(0, dp(58), 1));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(0, dp(58), 1);
        ep.setMargins(dp(10), 0, 0, 0);
        money.addView(expense, ep);
        root.addView(money, margin(-1, -2, 0, 14, 0, 0));

        LinearLayout finance = card();
        finance.addView(text("Финансы", 18, true));
        financeSummary = text("", 15, false);
        finance.addView(financeSummary, margin(-1, -2, 0, 7, 0, 0));
        root.addView(finance);

        LinearLayout tasksCard = card();
        LinearLayout taskHeader = new LinearLayout(this);
        taskHeader.setGravity(Gravity.CENTER_VERTICAL);
        taskHeader.addView(text("Дела", 20, true), new LinearLayout.LayoutParams(0, -2, 1));
        Button add = button("＋ Добавить");
        add.setTextSize(14);
        add.setOnClickListener(v -> showAddTask());
        taskHeader.addView(add, new LinearLayout.LayoutParams(dp(125), dp(45)));
        tasksCard.addView(taskHeader);
        taskList = new LinearLayout(this);
        taskList.setOrientation(LinearLayout.VERTICAL);
        tasksCard.addView(taskList, margin(-1, -2, 0, 8, 0, 0));
        root.addView(tasksCard);

        LinearLayout buyCard = card();
        buyCard.addView(text("Что купить", 20, true));
        TextView buyInfo = text("Позиций в списке: " + prefs.getInt("shopping_count", 0), 15, false);
        buyCard.addView(buyInfo, margin(-1, -2, 0, 7, 0, 0));
        Button addBuy = button("＋ Добавить покупку");
        addBuy.setOnClickListener(v -> showAddPurchase(buyInfo));
        buyCard.addView(addBuy, margin(-1, dp(48), 0, 10, 0, 0));
        root.addView(buyCard);

        setContentView(scroll);
        refresh();
    }

    private void refresh() {
        JSONArray tasks = getTasks();
        taskList.removeAllViews();
        int open = 0;
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i);
            if (task == null || task.optBoolean("done")) continue;
            open++;
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = text("○  " + task.optString("title"), 16, false);
            label.setPadding(0, dp(9), dp(8), dp(9));
            row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            Button done = button("Готово");
            done.setTextSize(13);
            done.setOnClickListener(v -> completeTask(index));
            row.addView(done, new LinearLayout.LayoutParams(dp(88), dp(42)));
            taskList.addView(row);
        }
        if (open == 0) taskList.addView(text("На сегодня всё закрыто", 15, false));
        taskCounter.setText(open == 0 ? "Нет незакрытых задач" : "Незакрытых задач: " + open);
        long income = prefs.getLong("income", 0);
        long expense = prefs.getLong("expense", 0);
        financeSummary.setText("Доходы: " + rub(income) + "\nРасходы: " + rub(expense) + "\nРезультат: " + rub(income - expense));
        updateWidget(this);
    }

    private void showAddTask() {
        EditText input = new EditText(this);
        input.setHint("Например: проверить расходники");
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("Новое дело")
                .setView(input)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Добавить", (d, w) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) return;
                    JSONArray tasks = getTasks();
                    JSONObject task = new JSONObject();
                    try {
                        task.put("title", value);
                        task.put("done", false);
                        tasks.put(task);
                        saveTasks(tasks);
                        refresh();
                    } catch (JSONException ignored) { }
                }).show();
    }

    private void completeTask(int index) {
        JSONArray tasks = getTasks();
        JSONObject task = tasks.optJSONObject(index);
        if (task != null) {
            try { task.put("done", true); } catch (JSONException ignored) { }
            saveTasks(tasks);
            refresh();
        }
    }

    private void showMoneyDialog(boolean income) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        box.setPadding(p, 0, p, 0);
        EditText amount = new EditText(this);
        amount.setHint("Сумма, ₽");
        amount.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText note = new EditText(this);
        note.setHint("Комментарий");
        box.addView(amount);
        box.addView(note);
        new AlertDialog.Builder(this)
                .setTitle(income ? "Добавить доход" : "Добавить расход")
                .setView(box)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", (d, w) -> {
                    try {
                        long value = Long.parseLong(amount.getText().toString());
                        String key = income ? "income" : "expense";
                        prefs.edit().putLong(key, prefs.getLong(key, 0) + value).apply();
                        refresh();
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Укажи сумму", Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    private void showAddPurchase(TextView info) {
        EditText input = new EditText(this);
        input.setHint("Что купить");
        new AlertDialog.Builder(this)
                .setTitle("Добавить покупку")
                .setView(input)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Добавить", (d, w) -> {
                    if (input.getText().toString().trim().isEmpty()) return;
                    int count = prefs.getInt("shopping_count", 0) + 1;
                    prefs.edit().putInt("shopping_count", count).apply();
                    info.setText("Позиций в списке: " + count);
                    updateWidget(this);
                }).show();
    }

    private JSONArray getTasks() {
        try { return new JSONArray(prefs.getString(TASKS, "[]")); }
        catch (JSONException e) { return new JSONArray(); }
    }

    private void saveTasks(JSONArray tasks) {
        prefs.edit().putString(TASKS, tasks.toString()).apply();
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(17), dp(16), dp(17), dp(16));
        card.setBackgroundResource(R.drawable.card_background);
        card.setElevation(dp(2));
        return card;
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(graphite);
        if (bold) view.setTypeface(view.getTypeface(), 1);
        return view;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setBackgroundResource(R.drawable.button_background);
        return button;
    }

    private LinearLayout.LayoutParams margin(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private String rub(long value) { return NumberFormat.getIntegerInstance(new Locale("ru", "RU")).format(value) + " ₽"; }

    public static void updateWidget(Context context) {
        Intent intent = new Intent(context, DachaWidgetProvider.class);
        intent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        int[] ids = AppWidgetManager.getInstance(context).getAppWidgetIds(new ComponentName(context, DachaWidgetProvider.class));
        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids);
        context.sendBroadcast(intent);
    }
}
