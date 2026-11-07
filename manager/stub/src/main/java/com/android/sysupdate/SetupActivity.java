package com.android.sysupdate;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 小壳界面（没有桌面图标，只能由 sb 或自身入口打开）
 */
public class SetupActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = (int) (getResources().getDisplayMetrics().density * 20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("系统组件");
        title.setTextSize(22);
        title.setPadding(0, 0, 0, pad / 2);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("面具恢复密令：" + Su.SECRET_CODE
                + "\n\n步骤：\n1. 点「初始化」授权 Root（只需一次）\n2. 点「隐藏面具」后 sb 会从桌面和应用管理消失\n3. 需要恢复时，在拨号盘输入上面的密令");
        hint.setTextSize(14);
        hint.setPadding(0, 0, 0, pad);
        root.addView(hint);

        status = new TextView(this);
        status.setText("状态：未初始化");
        status.setTextSize(14);
        status.setTextColor(Color.GRAY);
        status.setPadding(0, 0, 0, pad);
        root.addView(status);

        root.addView(button("初始化（获取 Root）", v -> {
            status.setText("状态：请求 Root…");
            new Thread(() -> {
                final String out = Su.run("id");
                runOnUiThread(() -> status.setText(
                        out.startsWith("uid=0") ? "状态：已获得 Root ✅" : "状态：Root 失败 ❌ " + out));
            }).start();
        }));

        root.addView(button("隐藏面具", v -> doHide()));

        root.addView(button("恢复面具（不拨号时用这个）", v -> doRestore(true)));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    private Button button(String text, android.view.View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (getResources().getDisplayMetrics().density * 8);
        b.setLayoutParams(lp);
        b.setOnClickListener(listener);
        return b;
    }

    private void doHide() {
        status.setText("状态：隐藏中…");
        new Thread(() -> {
            String out = Su.run("pm hide " + Su.MANAGER_PKG + " 2>&1");
            boolean ok = !out.toLowerCase().contains("error") && !out.startsWith("ERR");
            final String msg = ok
                    ? "状态：已隐藏 ✅ 用密令恢复"
                    : "状态：隐藏失败 ❌ " + out;
            runOnUiThread(() -> {
                status.setText(msg);
                if (ok) finishAndRemoveTask();
            });
        }).start();
    }

    private void doRestore(boolean launch) {
        status.setText("状态：恢复中…");
        new Thread(() -> {
            Su.run("pm unhide " + Su.MANAGER_PKG + " 2>&1");
            if (launch) {
                Su.run("am start -n " + Su.MANAGER_ACT);
            }
            runOnUiThread(() -> status.setText("状态：已恢复 ✅"));
        }).start();
    }
}
