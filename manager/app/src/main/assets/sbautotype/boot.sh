#!/system/bin/sh
# KernelSU / Magisk 开机启动入口（安装在 /data/adb/service.d/sb-autotype.sh）
#
# service.d 的环境 PATH 极简，先补齐；等开机完成后再留 20 秒给桌面稳定，
# 然后交给 start.sh 拉起守护进程。
export PATH=/system/bin:/system/xbin:/vendor/bin:/data/adb/ksu/bin:$PATH

[ -f /data/adb/sb-autotype.paused ] && exit 0
[ -d /data/adb/sb_autotype ] || exit 0

LOG=/sdcard/sb-autotype.log
if ! (: >> "$LOG") 2>/dev/null; then LOG=/data/adb/sb-autotype.log; fi
echo "$(date '+%m-%d %H:%M:%S') [boot] service.d start" >> "$LOG"

i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 180 ]; do
  sleep 1
  i=$((i + 1))
done
sleep 20

sh /data/adb/sb_autotype/start.sh
