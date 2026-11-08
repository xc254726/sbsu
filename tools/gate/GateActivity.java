package com.sb.gate;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

public class GateActivity extends Activity {

    private static final String BASE_URL = "https://sbkey.dpdns.org";
    private static final String SALT = "sb-manager-v1";
    private static final String PREFS = "sb_activation";
    private static final String KEY = "license_key";
    private static final String TARGET = "me.weishu.kernelsu.ui.MainActivity";

    private static final int INK = 0xFF1F1F1F;
    private static final int GREY = 0xFF8A8A8F;
    private static final int BG = 0xFFF7F7F8;

    private EditText input;
    private Switch remember;
    private Button submit;
    private TextView error;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        String saved = storedKey(this);
        if (saved != null && !saved.isEmpty()) {
            input.setText(saved);
            remember.setChecked(true);
            error.setText("");
            setBusy(true);
            verifyAsync(saved);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(BG);
        root.setPadding(dp(26), dp(24), dp(26), dp(24));
        root.setMinimumHeight(getResources().getDisplayMetrics().heightPixels);

        // 圆形头像
        ImageView avatar = new ImageView(this);
        try {
            Bitmap src = ((BitmapDrawable) getPackageManager().getApplicationIcon(getPackageName())).getBitmap();
            avatar.setImageBitmap(circleCrop(src));
        } catch (Exception e) {
            avatar.setBackground(circleDrawable(INK, dp(96)));
        }
        root.addView(avatar, new LinearLayout.LayoutParams(dp(96), dp(96)));

        // 标题
        TextView title = new TextView(this);
        title.setText("sb");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        title.setTextColor(INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(20);
        root.addView(title, tlp);

        // 副标题
        TextView sub = new TextView(this);
        sub.setText("请输入卡密以继续使用");
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setTextColor(GREY);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(10);
        root.addView(sub, slp);

        // 白色卡片
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(roundDrawable(Color.WHITE, dp(26), 0));
        card.setElevation(dp(6));
        card.setPadding(dp(22), dp(22), dp(22), dp(22));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(34);
        root.addView(card, clp);

        TextView label = new TextView(this);
        label.setText("卡密");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTextColor(GREY);
        card.addView(label);

        input = new EditText(this);
        input.setHint("输入卡密");
        input.setHintTextColor(0xFFBDBDC4);
        input.setSingleLine(true);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setTextColor(INK);
        input.setTypeface(Typeface.MONOSPACE);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        input.setBackground(roundDrawable(Color.WHITE, dp(14), 0xFFD8D8DE));
        input.setPadding(dp(14), dp(13), dp(14), dp(13));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(8);
        card.addView(input, ilp);

        error = new TextView(this);
        error.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        error.setTextColor(0xFFD33A3A);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(8);
        card.addView(error, elp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(14);
        card.addView(row, rlp);

        TextView remLabel = new TextView(this);
        remLabel.setText("记住此卡密");
        remLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        remLabel.setTextColor(GREY);
        row.addView(remLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        remember = new Switch(this);
        remember.setChecked(true);
        row.addView(remember);

        submit = new Button(this);
        submit.setText("验证");
        submit.setAllCaps(false);
        submit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        submit.setTextColor(Color.WHITE);
        submit.setBackground(roundDrawable(INK, dp(16), 0));
        submit.setStateListAnimator(null);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        blp.topMargin = dp(16);
        card.addView(submit, blp);

        submit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String key = input.getText().toString().toUpperCase(Locale.US).replaceAll("[^A-Z0-9-]", "");
                if (key.length() < 8) {
                    error.setText("卡密格式不对");
                    return;
                }
                error.setText("");
                setBusy(true);
                activateAsync(key);
            }
        });

        remember.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
            }
        });

        return root;
    }

    private GradientDrawable roundDrawable(int color, int radius, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (strokeColor != 0) {
            d.setStroke(dp(1), strokeColor);
        }
        return d;
    }

    private GradientDrawable circleDrawable(int color, int size) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private Bitmap circleCrop(Bitmap src) {
        int size = Math.min(src.getWidth(), src.getHeight());
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        c.drawCircle(size / 2f, size / 2f, size / 2f, p);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        c.drawBitmap(src, new Rect(0, 0, size, size), new Rect(0, 0, size, size), p);
        return out;
    }

    private static String storedKey(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null);
    }

    private static String deviceCode(Context c) {
        try {
            String id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (id == null) id = "unknown";
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((SALT + "|" + id).getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6; i++) hex.append(String.format(Locale.US, "%02X", d[i] & 0xFF));
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
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
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
                            error.setText("网络连接失败，请检查网络");
                            return;
                        }
                        if (resp.optBoolean("ok")) {
                            if (remember.isChecked()) {
                                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                                        .edit().putString(KEY, key).apply();
                            }
                            openManager();
                        } else {
                            error.setText(resp.optString("err", "激活失败"));
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
                            error.setText("网络连接失败，请检查网络");
                            return;
                        }
                        if (resp.optBoolean("ok")) {
                            openManager();
                        } else {
                            error.setText("卡密已失效，请重新输入");
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
            error.setText("启动失败: " + e.getMessage());
        }
    }
}
