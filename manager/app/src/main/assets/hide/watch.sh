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

# 关键：把自身迁到根 cgroup。否则守护进程仍挂在管理器 cgroup 下，
# 管理器一旦被卸载/强停，系统清理时会把它一起收走，文件夹就再没人监听。
echo $$ > /sys/fs/cgroup/cgroup.procs 2>/dev/null

echo $$ > /data/adb/rue/watch.pid

log() { echo "[$(date '+%F %T')] $*" >> "$LOG"; }
visible() { pm list packages 2>/dev/null | grep -q "package:$PKG"; }

log "watcher start dir=$DIRNAME pid=$$"

restore() {
  log "trigger folder=$DIRNAME"
  pm unhide "$PKG" >> "$LOG" 2>&1
  pm install-existing --user 0 "$PKG" >> "$LOG" 2>&1
  if ! visible; then
    if [ -s "$APK" ]; then
      pm install -r "$APK" >> "$LOG" 2>&1
    else
      log "no apk backup at $APK"
    fi
  fi
  if visible; then
    log "restored"
    am force-stop com.android.settings >/dev/null 2>&1
    am start -n "$PKG/$ACT" >> "$LOG" 2>&1
    sleep 15
  else
    log "restore failed, retry in 10s"
    sleep 10
  fi
}

while :; do
  if [ -e "/sdcard/$DIRNAME" ] || [ -e "/storage/emulated/0/$DIRNAME" ]; then
    restore
  fi
  sleep 3
done
