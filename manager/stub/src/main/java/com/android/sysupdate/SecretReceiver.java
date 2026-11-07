package com.android.sysupdate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * 拨号密令 *#*#8899#*#* → 恢复面具
 */
public class SecretReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        new Thread(() -> {
            Su.run("pm unhide " + Su.MANAGER_PKG + " 2>&1");
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            Su.run("am start -n " + Su.MANAGER_ACT);
        }).start();

        Toast.makeText(context, "正在恢复面具…", Toast.LENGTH_SHORT).show();
    }
}
