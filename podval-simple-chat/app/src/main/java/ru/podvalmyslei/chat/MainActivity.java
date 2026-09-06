package ru.podvalmyslei.chat;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final String PREFS = "podval_chat_prefs";
    private static final String KEY_MESSAGES = "messages";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_MODEL = "model";
    private static final String KEY_SYSTEM = "system_prompt";

    private static final String DEFAULT_BASE_URL = "http://192.168.0.120:1234/v1";
    private static final String DEFAULT_MODEL = "google/gemma-4-e4b";
    private static final String DEFAULT_SYSTEM = "Ты локальный помощник. Отвечай по-русски, ясно и без лишней воды.";

    private static final int BG = Color.rgb(16, 23, 20);
    private static final int PANEL = Color.rgb(24, 35, 30);
    private static final int PANEL_USER = Color.rgb(48, 53, 40);
    private static final int IVORY = Color.rgb(242, 238, 231);
    private static final int MUTED = Color.rgb(143, 148, 142);
    private static final int BRASS = Color.rgb(185, 154, 108);
    private static final int LINE = Color.rgb(58, 69, 63);
    private static final int OK = Color.rgb(126, 160, 137);
    private static final int WAIT = Color.rgb(182, 138, 85);
    private static final int ERROR = Color.rgb(166, 95, 82);

    private final List<ChatMessage> messages = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private LinearLayout messagesContainer;
    private ScrollView scrollView;
    private EditText input;
    private Button sendButton;
    private TextView statusText;
    private TextView statusDot;
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadMessages();
        configureWindow();
        setContentView(buildUi());
        renderMessages();
        setStatus("готов", OK);
    }

    private void configureWindow() {
        Window w = getWindow();
        w.setStatusBarColor(Color.rgb(12, 18, 15));
        w.setNavigationBarColor(Color.rgb(10, 15, 13));
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(18), dp(14), dp(18), dp(12));

        root.addView(buildHeader(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        dividerLp.setMargins(0, dp(14), 0, dp(8));
        root.addView(divider, dividerLp);

        root.addView(buildTools(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        scrollView.setPadding(0, dp(12), 0, dp(14));

        messagesContainer = new LinearLayout(this);
        messagesContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(messagesContainer, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scrollView, scrollLp);

        root.addView(buildComposer(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        return root;
    }

    private View buildHeader() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);

        TextView eyebrow = text("ЛОКАЛЬНЫЙ ПОМОЩНИК", 9, BRASS);
        eyebrow.setLetterSpacing(0.18f);
        brand.addView(eyebrow);

        TextView title = text("Подвал мыслей", 29, IVORY);
        title.setTypeface(Typeface.create(Typeface.SERIF, Typeface.NORMAL));
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.topMargin = dp(4);
        brand.addView(title, titleLp);

        row.addView(brand, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout status = new LinearLayout(this);
        status.setOrientation(LinearLayout.VERTICAL);
        status.setGravity(Gravity.END);

        LinearLayout statusLine = new LinearLayout(this);
        statusLine.setOrientation(LinearLayout.HORIZONTAL);
        statusLine.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);

        statusDot = text("●", 11, OK);
        statusLine.addView(statusDot);
        statusText = text("готов", 11, IVORY);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stLp.leftMargin = dp(5);
        statusLine.addView(statusText, stLp);
        status.addView(statusLine);

        TextView server = text("LM Studio", 10, MUTED);
        LinearLayout.LayoutParams serverLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        serverLp.topMargin = dp(3);
        status.addView(server, serverLp);

        row.addView(status);
        return row;
    }

    private View buildTools() {
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

        Button settings = pillButton("Настройки");
        settings.setOnClickListener(v -> showSettings());
        tools.addView(settings);

        Button clear = pillButton("Очистить чат");
        clear.setOnClickListener(v -> confirmClear());
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clearLp.leftMargin = dp(8);
        tools.addView(clear, clearLp);

        return tools;
    }

    private View buildComposer() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.BOTTOM);
        box.setPadding(dp(12), dp(8), dp(8), dp(8));
        box.setBackground(roundRect(PANEL, dp(23), LINE, 1));
        box.setElevation(dp(8));

        input = new EditText(this);
        input.setTextColor(IVORY);
        input.setHintTextColor(Color.rgb(105, 113, 107));
        input.setHint("Напиши сообщение…");
        input.setTextSize(15);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding(dp(4), dp(6), dp(6), dp(6));
        input.setMinLines(1);
        input.setMaxLines(5);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentMessage();
                return true;
            }
            return false;
        });
        box.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        sendButton = new Button(this);
        sendButton.setAllCaps(false);
        sendButton.setText("↑");
        sendButton.setTextSize(20);
        sendButton.setTextColor(IVORY);
        sendButton.setGravity(Gravity.CENTER);
        sendButton.setPadding(0, 0, 0, dp(2));
        sendButton.setBackground(roundRect(Color.rgb(44, 68, 56), dp(24), Color.rgb(91, 82, 64), 1));
        sendButton.setOnClickListener(v -> sendCurrentMessage());
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        sendLp.leftMargin = dp(8);
        box.addView(sendButton, sendLp);

        return box;
    }

    private void renderMessages() {
        messagesContainer.removeAllViews();
        if (messages.isEmpty()) {
            addBubble(new ChatMessage("assistant", "Готова. Пиши, и я отправлю сообщение в твою локальную модель LM Studio."));
        } else {
            for (ChatMessage m : messages) addBubble(m);
        }
        scrollBottom();
    }

    private void addBubble(ChatMessage m) {
        boolean user = "user".equals(m.role);

        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setGravity(user ? Gravity.END : Gravity.START);

        TextView who = text(user ? "ТЫ" : "GEMMA", 9, Color.rgb(111, 118, 112));
        who.setLetterSpacing(0.15f);
        wrapper.addView(who);

        TextView bubble = text(m.content, 15, IVORY);
        bubble.setTextIsSelectable(true);
        bubble.setLineSpacing(0f, 1.12f);
        bubble.setPadding(dp(14), dp(11), dp(14), dp(11));
        bubble.setBackground(roundRect(user ? PANEL_USER : PANEL, dp(17), user ? Color.rgb(91, 82, 64) : LINE, 1));
        bubble.setElevation(dp(3));

        LinearLayout.LayoutParams bubbleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bubbleLp.topMargin = dp(5);
        bubbleLp.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(72), dp(460));
        wrapper.addView(bubble, bubbleLp);

        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wrapLp.bottomMargin = dp(16);
        messagesContainer.addView(wrapper, wrapLp);
    }

    private void addTypingBubble() {
        ChatMessage typing = new ChatMessage("assistant", "…");
        addBubble(typing);
        scrollBottom();
    }

    private void sendCurrentMessage() {
        if (busy) return;
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;

        messages.add(new ChatMessage("user", text));
        input.setText("");
        saveMessages();
        renderMessages();
        addTypingBubble();

        busy = true;
        sendButton.setEnabled(false);
        setStatus("думает", WAIT);

        final String baseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL);
        final String model = prefs.getString(KEY_MODEL, DEFAULT_MODEL);
        final String system = prefs.getString(KEY_SYSTEM, DEFAULT_SYSTEM);
        final JSONArray requestMessages = buildRequestMessages(system);

        executor.execute(() -> {
            try {
                String answer = callLmStudio(baseUrl, model, requestMessages);
                runOnUiThread(() -> {
                    messages.add(new ChatMessage("assistant", answer));
                    saveMessages();
                    busy = false;
                    sendButton.setEnabled(true);
                    setStatus("готов", OK);
                    renderMessages();
                });
            } catch (Exception e) {
                String err = friendlyError(e);
                runOnUiThread(() -> {
                    messages.add(new ChatMessage("assistant", err));
                    saveMessages();
                    busy = false;
                    sendButton.setEnabled(true);
                    setStatus("ошибка", ERROR);
                    renderMessages();
                });
            }
        });
    }

    private JSONArray buildRequestMessages(String systemPrompt) {
        JSONArray arr = new JSONArray();
        try {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            arr.put(sys);

            int start = Math.max(0, messages.size() - 30);
            for (int i = start; i < messages.size(); i++) {
                ChatMessage m = messages.get(i);
                JSONObject obj = new JSONObject();
                obj.put("role", m.role);
                obj.put("content", m.content);
                arr.put(obj);
            }
        } catch (JSONException ignored) {}
        return arr;
    }

    private String callLmStudio(String baseUrl, String model, JSONArray requestMessages) throws Exception {
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        URL url = new URL(normalized + "/chat/completions");

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(120000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", requestMessages);
        body.put("temperature", 0.7);
        body.put("stream", false);

        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }

        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String raw = readAll(stream);
        conn.disconnect();

        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + (raw.isEmpty() ? "" : ": " + shorten(raw, 350)));
        }

        JSONObject json = new JSONObject(raw);
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new Exception("LM Studio вернул ответ без choices");
        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
        if (message == null) throw new Exception("LM Studio вернул ответ без message");
        String content = message.optString("content", "").trim();
        if (content.isEmpty()) throw new Exception("Модель вернула пустой текст");
        return content;
    }

    private String friendlyError(Exception e) {
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        StringBuilder sb = new StringBuilder();
        sb.append("Не удалось связаться с LM Studio.\n\n");
        sb.append(detail).append("\n\n");
        sb.append("Проверь:\n");
        sb.append("• LM Studio Server включён;\n");
        sb.append("• телефон и компьютер в одной Wi‑Fi сети;\n");
        sb.append("• адрес сервера: ").append(prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL)).append(";\n");
        sb.append("• VPN на телефоне не перехватывает локальную сеть;\n");
        sb.append("• в браузере телефона открывается /v1/models.");
        return sb.toString();
    }

    private void showSettings() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(18), dp(4), dp(18), 0);

        EditText url = dialogInput("Base URL", prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL));
        EditText model = dialogInput("Модель", prefs.getString(KEY_MODEL, DEFAULT_MODEL));
        EditText system = dialogInput("Системная инструкция", prefs.getString(KEY_SYSTEM, DEFAULT_SYSTEM));
        system.setMinLines(2);

        form.addView(label("Base URL"));
        form.addView(url);
        form.addView(label("Модель"));
        form.addView(model);
        form.addView(label("Системная инструкция"));
        form.addView(system);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Настройки LM Studio")
                .setView(form)
                .setPositiveButton("Сохранить", (d, which) -> {
                    String u = url.getText().toString().trim();
                    String m = model.getText().toString().trim();
                    String s = system.getText().toString().trim();
                    if (u.isEmpty()) u = DEFAULT_BASE_URL;
                    if (m.isEmpty()) m = DEFAULT_MODEL;
                    if (s.isEmpty()) s = DEFAULT_SYSTEM;
                    prefs.edit().putString(KEY_BASE_URL, u).putString(KEY_MODEL, m).putString(KEY_SYSTEM, s).apply();
                    Toast.makeText(this, "Настройки сохранены", Toast.LENGTH_SHORT).show();
                })
                .setNeutralButton("Проверить сервер", null)
                .setNegativeButton("Отмена", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(v -> {
            final String testUrl = url.getText().toString().trim().isEmpty() ? DEFAULT_BASE_URL : url.getText().toString().trim();
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setEnabled(false);
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setText("Проверяю…");
            executor.execute(() -> {
                String result;
                try {
                    result = testModels(testUrl);
                } catch (Exception ex) {
                    result = "Ошибка: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                }
                final String finalResult = result;
                runOnUiThread(() -> {
                    dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setEnabled(true);
                    dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setText("Проверить сервер");
                    new AlertDialog.Builder(this).setTitle("Проверка LM Studio").setMessage(finalResult).setPositiveButton("ОК", null).show();
                });
            });
        }));

        dialog.show();
    }

    private String testModels(String baseUrl) throws Exception {
        String normalized = baseUrl.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        HttpURLConnection conn = (HttpURLConnection) new URL(normalized + "/models").openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12000);
        conn.setRequestMethod("GET");
        int code = conn.getResponseCode();
        String raw = readAll(code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream());
        conn.disconnect();
        if (code >= 200 && code < 300) return "Связь есть. HTTP " + code + "\n\n" + shorten(raw, 500);
        throw new Exception("HTTP " + code + ": " + shorten(raw, 250));
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Очистить чат?")
                .setMessage("История этого чата удалится только с телефона.")
                .setPositiveButton("Очистить", (d, w) -> {
                    messages.clear();
                    saveMessages();
                    renderMessages();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null || cm.getActiveNetwork() == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void loadMessages() {
        messages.clear();
        String raw = prefs.getString(KEY_MESSAGES, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                String role = o.optString("role", "assistant");
                String content = o.optString("content", "");
                if (!content.isEmpty()) messages.add(new ChatMessage(role, content));
            }
        } catch (JSONException ignored) {}
    }

    private void saveMessages() {
        JSONArray arr = new JSONArray();
        try {
            for (ChatMessage m : messages) {
                JSONObject o = new JSONObject();
                o.put("role", m.role);
                o.put("content", m.content);
                arr.put(o);
            }
        } catch (JSONException ignored) {}
        prefs.edit().putString(KEY_MESSAGES, arr.toString()).apply();
    }

    private void scrollBottom() {
        if (scrollView == null) return;
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void setStatus(String value, int color) {
        if (statusText != null) statusText.setText(value);
        if (statusDot != null) statusDot.setTextColor(color);
    }

    private TextView text(String value, float sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setIncludeFontPadding(false);
        return tv;
    }

    private TextView label(String value) {
        TextView v = text(value, 11, Color.DKGRAY);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        v.setLayoutParams(lp);
        return v;
    }

    private EditText dialogInput(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(false);
        e.setTextSize(14);
        e.setPadding(0, dp(5), 0, dp(5));
        return e;
    }

    private Button pillButton(String value) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(value);
        b.setTextSize(11);
        b.setTextColor(Color.rgb(174, 176, 170));
        b.setPadding(dp(13), 0, dp(13), 0);
        b.setMinHeight(dp(36));
        b.setMinimumHeight(0);
        b.setMinimumWidth(0);
        b.setBackground(roundRect(Color.TRANSPARENT, dp(18), LINE, 1));
        return b;
    }

    private GradientDrawable roundRect(int fill, float radius, int stroke, int strokeDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(radius);
        gd.setStroke(dp(strokeDp), stroke);
        return gd;
    }

    private String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
        }
        return sb.toString().trim();
    }

    private String shorten(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private static final class ChatMessage {
        final String role;
        final String content;
        ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
}
