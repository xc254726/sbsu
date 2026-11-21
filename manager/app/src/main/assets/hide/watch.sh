#!/system/bin/sh
# rue 隐藏 / 恢复守护：监听内部存储里的指定文件夹，出现就恢复并打开管理器
CONF=/data/adb/rue/watch.conf
PKG=com.rue.manager
ACT=org.bakasu.bakasu.ui.MainActivity
APK=/data/adb/rue/rue.apk
LOG=/data/adb/rue/watch.log

[ -f "$CONF" ] || exit 0
DIRNAME=$(cat "$CONF" 2>/dev/null)
[ -n "$DIRNAME" ] || exit 0

echo $$ > /data/adb/rue/watch.pid
echo "[$(date '+%F %T')] watcher start dir=$DIRNAME" >> "$LOG"

while :; do
  if [ -e "/sdcard/$DIRNAME" ] || [ -e "/storage/emulated/0/$DIRNAME" ]; then
    echo "[$(date '+%F %T')] trigger folder=$DIRNAME" >> "$LOG"
    pm unhide "$PKG" >> "$LOG" 2>&1
    if ! pm list packages 2>/dev/null | grep -q "package:$PKG"; then
      pm install -r "$APK" >> "$LOG" 2>&1
    fi
    am force-stop com.android.settings >/dev/null 2>&1
    am start -n "$PKG/$ACT" >> "$LOG" 2>&1
    sleep 20
  fi
  sleep 3
done
