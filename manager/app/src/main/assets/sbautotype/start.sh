#!/system/bin/sh
# 启动守护进程（内置版：脚本落在 /data/adb/sb_autotype）
#
# 通过面具启动时，进程初始属于面具的 cgroup，划掉面具可能被一起清掉；
# daemon.sh 会尝试自救。开机由 service.d 拉起时父进程是内核层的 ksud，
# 管理器在不在、卸没卸载都不影响。
export PATH=/system/bin:/system/xbin:/vendor/bin:/data/adb/ksu/bin:$PATH

DIR=${0%/*}
PAT='sb[-_]autotype/[d]aemon\.sh'

LOG=/sdcard/sb-autotype.log
if ! (: >> "$LOG") 2>/dev/null; then LOG=/data/adb/sb-autotype.log; fi

log() { echo "$(date '+%m-%d %H:%M:%S') [start] $*" >> "$LOG"; }

rm -f "$DIR/disable"
rm -f /data/adb/sb-autotype.paused

if pgrep -f "$PAT" >/dev/null 2>&1; then
  log "already running: $(pgrep -f "$PAT" | head -n1)"
  exit 0
fi

nohup setsid sh "$DIR/daemon.sh" </dev/null >/dev/null 2>&1 &
sleep 1

pid=$(pgrep -f "$PAT" 2>/dev/null | head -n1)
if [ -z "$pid" ]; then
  log "start failed"
  exit 1
fi

log "started pid=$pid cgroup=$(cat /proc/$pid/cgroup 2>/dev/null | head -n1)"
