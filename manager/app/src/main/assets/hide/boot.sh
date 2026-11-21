#!/system/bin/sh
# 开机自启 watcher（由 /data/adb/service.d 执行）
sleep 25
[ -f /data/adb/rue/watch.conf ] || exit 0
[ -x /data/adb/rue/watch.sh ] || exit 0
pgrep -f /data/adb/rue/watch.sh >/dev/null 2>&1 && exit 0
setsid /data/adb/rue/watch.sh >/dev/null 2>&1 &
