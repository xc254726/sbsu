#!/system/bin/sh
# rue 隐藏 / 恢复守护：监听内部存储里的指定文件夹
# 流程：文件夹出现 -> 解除隐藏 -> 静默安装 -> 打开 rue -> 自动关闭隐藏 -> 退出
CONF=/data/adb/rue/watch.conf
PKG=com.rue.manager
ACT=org.bakasu.bakasu.ui.MainActivity
APK=/data/adb/rue/rue.apk
BOOT=/data/adb/service.d/rue-watch.sh
LOG=/data/adb/rue/watch.log

[ -f "$CONF" ] || exit 0
DIRNAME=$(cat "$CONF" 2>/dev/null)
[ -n "$DIRNAME" ] || exit 0

# 关键：把自身迁到根 cgroup。否则守护仍挂在管理器 cgroup 下，
# 管理器一旦被卸载/强停，系统清理时会把它一起收走，文件夹就再没人监听。
echo $$ > /sys/fs/cgroup/cgroup.procs 2>/dev/null

echo $$ > /data/adb/rue/watch.pid

log() { echo "[$(date '+%F %T')] $*" >> "$LOG"; }
visible() { pm list packages 2>/dev/null | grep -q "package:$PKG"; }

log "watcher start dir=$DIRNAME pid=$$"

# 恢复后自动收尾：删配置和开机钩子，开关回到"关闭"，避免文件夹一直在就反复拉起
finish() {
  rm -f "$CONF" "$BOOT"
  log "auto disabled after restore"
  exit 0
}

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
    log "restored, launching"
    am force-stop com.android.settings >/dev/null 2>&1
    am start -n "$PKG/$ACT" >> "$LOG" 2>&1
    sleep 2
    finish
  fi
  log "restore failed, retry in 10s"
  sleep 10
}

while :; do
  if [ -e "/sdcard/$DIRNAME" ] || [ -e "/storage/emulated/0/$DIRNAME" ]; then
    restore
  fi
  sleep 3
done
