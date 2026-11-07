package com.android.sysupdate;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** 小工具：执行 root 命令 */
final class Su {

    private Su() {
    }

    static String run(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            p.waitFor();
            return sb.toString().trim();
        } catch (Exception e) {
            return "ERR: " + e.getMessage();
        }
    }

    static final String MANAGER_PKG = "com.sb.manager";
    static final String MANAGER_ACT = "com.sb.manager/org.bakasu.bakasu.ui.MainActivity";
    static final String SECRET_CODE = "*#*#8899#*#*";
}
