package com.sb.gate;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * sb 卡密激活页：激活成功后进入管理器主界面
 */
public class GateActivity extends Activity {

    private static final String BASE_URL = "https://sbkey.dpdns.org";
    private static final String SALT = "sb-manager-v1";
    private static final String PREFS = "sb_activation";
    private static final String KEY = "license_key";
    private static final String TARGET = "me.weishu.kernelsu.ui.MainActivity";

    private EditText input;
    private CheckBox remember;
    private Button submit;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        String saved = storedKey(this);
        if (saved != null && !saved.isEmpty()) {
            input.setText(saved);
            remember.setChecked(true);
            status.setText("正在验证…");
            setBusy(true);
            verifyAsync(saved);
        }
    }

    private View buildUi() {
        int pad = dp(24);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(pad, dp(72), pad, pad);
        root.setBackgroundColor(Color.parseColor("#F2F3F7"));

        TextView title = new TextView(this);
        title.setText("卡密激活");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTextColor(Color.parseColor("#1C1C20"));
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("首次使用需要输入卡密");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        sub.setTextColor(Color.parseColor("#82828C"));
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(8);
        root.addView(sub, subLp);

        input = new EditText(this);
        input.setHint("SB-XXXX-XXXX-XXXX-XXXX");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        input.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(14));
        input.setBackground(bg);
        LinearLayout.LayoutParams inLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inLp.topMargin = dp(32);
        root.addView(input, inLp);

        remember = new CheckBox(this);
        remember.setText("记住卡密");
        remember.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        remember.setTextColor(Color.parseColor("#5A5A66"));
        LinearLayout.LayoutParams remLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        remLp.topMargin = dp(12);
        root.addView(remember, remLp);

        submit = new Button(this);
        submit.setText("验证");
        submit.setAllCaps(false);
        submit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        submit.setTextColor(Color.WHITE);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(Color.parseColor("#346EEB"));
        btnBg.setCornerRadius(dp(14));
        submit.setBackground(btnBg);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        btnLp.topMargin = dp(24);
        root.addView(submit, btnLp);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setTextColor(Color.parseColor("#C0392B"));
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = dp(16);
        root.addView(status, stLp);

        submit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String key = input.getText().toString().toUpperCase(Locale.US).replaceAll("[^A-Z0-9-]", "");
                if (key.length() < 8) {
                    status.setText("卡密格式不对");
                    return;
                }
                status.setText("正在激活…");
                setBusy(true);
                activateAsync(key);
            }
        });

        return root;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String storedKey(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp.getString(KEY, null);
    }

    private static String deviceCode(Context c) {
        try {
            String id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (id == null) id = "unknown";
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((SALT + "|" + id).getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                hex.append(String.format(Locale.US, "%02X", d[i] & 0xFF));
            }
            String s = hex.toString();
            return s.substring(0, 4) + "-" + s.substring(4, 8) + "-" + s.substring(8, 12);
        } catch (Exception e) {
            return "0000-0000-0000";
        }
    }

    private JSONObject post(String path, JSONObject body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(BASE_URL + path).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();
            int code = conn.getResponseCode();
            java.io.InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            String text = new String(bos.toByteArray(), "UTF-8");
            if (text.trim().isEmpty()) return null;
            return new JSONObject(text);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void activateAsync(final String key) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                JSONObject body = new JSONObject();
                try {
                    body.put("key", key);
                    body.put("device", deviceCode(GateActivity.this));
                } catch (Exception ignored) {
                }
                final JSONObject resp = post("/activate", body);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setBusy(false);
                        if (resp == null) {
                            status.setText("网络连接失败，请检查网络");
                            return;
                        }
                        if (resp.optBoolean("ok")) {
                            if (remember.isChecked()) {
                                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                        .edit().putString(KEY, key).apply();
                            }
                            status.setText("激活成功");
                            Toast.makeText(GateActivity.this, "激活成功", Toast.LENGTH_SHORT).show();
                            openManager();
                        } else {
                            status.setText(resp.optString("err", "激活失败"));
                        }
                    }
                });
            }
        }).start();
    }

    private void verifyAsync(final String key) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                JSONObject body = new JSONObject();
                try {
                    body.put("key", key);
                    body.put("device", deviceCode(GateActivity.this));
                } catch (Exception ignored) {
                }
                final JSONObject resp = post("/verify", body);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setBusy(false);
                        if (resp == null) {
                            status.setText("网络连接失败，请检查网络");
                            return;
                        }
                        if (resp.optBoolean("ok")) {
                            openManager();
                        } else {
                            status.setText("卡密已失效，请重新输入");
                        }
                    }
                });
            }
        }).start();
    }

    private void setBusy(boolean busy) {
        submit.setEnabled(!busy);
        submit.setAlpha(busy ? 0.6f : 1f);
    }

    private void openManager() {
        try {
            Intent i = new Intent();
            i.setClassName(getPackageName(), TARGET);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
            finish();
        } catch (Exception e) {
            status.setText("启动失败: " + e.getMessage());
        }
    }
}
