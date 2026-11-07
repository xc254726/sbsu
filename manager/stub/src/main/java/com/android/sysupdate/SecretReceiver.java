package com.android.sysupdate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

/**
 * 拨号密令 → 恢复面具
 * 实际密令从 /data/local/tmp/sb_hide_code 读取（由 sb 写入）
 */
public class SecretReceiver extends BroadcastReceiver {

    private static final String CODE_FILE = "/data/local/tmp/sb_hide_code";

    private static String readCode() {
        try {
            File f = new File(CODE_FILE);
            if (!f.exists()) {
                return "8899";
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f)));
            String s = r.readLine();
            r.close();
            return s == null ? "" : s.trim();
        } catch (Exception e) {
            return "8899";
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Uri data = intent.getData();
        String host = data == null ? "" : String.valueOf(data.getHost()).trim();
        String want = readCode();
        if (want.isEmpty() || !host.equals(want)) {
            return;
        }

        Toast.makeText(context, "正在恢复面具…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            Su.run("pm unhide " + Su.MANAGER_PKG + " 2>&1");
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            Su.run("am start -n " + Su.MANAGER_ACT);
        }).start();
    }
}