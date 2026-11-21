#!/system/bin/sh
# 开机自启：拉起常驻守护（由 /data/adb/service.d 执行）
sleep 25
[ -f /data/adb/rue/watch.conf ] || exit 0
[ -x /data/adb/rue/supervisor.sh ] || exit 0
pgrep -f /data/adb/rue/supervisor.sh >/dev/null 2>&1 && exit 0
setsid /data/adb/rue/supervisor.sh >/dev/null 2>&1 &
