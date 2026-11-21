#!/system/bin/sh
# rue 常驻守护：watch.sh 被杀掉（管理器卸载、系统清进程、误杀）后自动拉起
CONF=/data/adb/rue/watch.conf
WATCH=/data/adb/rue/watch.sh

# 同样迁到根 cgroup，避免跟着管理器一起被回收
echo $$ > /sys/fs/cgroup/cgroup.procs 2>/dev/null
echo $$ > /data/adb/rue/supervisor.pid

while :; do
  [ -f "$CONF" ] || exit 0
  if [ -x "$WATCH" ] && ! pgrep -f /data/adb/rue/watch.sh >/dev/null 2>&1; then
    setsid "$WATCH" >/dev/null 2>&1 &
    echo "[$(date '+%F %T')] supervisor restarted watcher" >> /data/adb/rue/watch.log
  fi
  sleep 20
done
