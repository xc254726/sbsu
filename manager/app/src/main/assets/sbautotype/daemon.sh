#!/system/bin/sh
# sb 自动输入 v1.6
#
# 检测方式：pidof <包名>（扫 /proc，几乎零开销）
#   - 目标进程集合发生变化 → 认为「刚被打开」
#   - 再用一次 dumpsys 确认它确实在前台 → 命中才输入
#   - 前台确认最多试 12 次（约 60 秒），超时就放弃，不会一直查
#
# 规则：$MODDIR/rules.conf，一行一条
#   包名|输入内容|延迟毫秒|脚本路径

# 开机由内核层拉起时 PATH 可能极简，先把标准路径补齐，
# 否则 pidof / dumpsys / input 全部找不到，表现为「打开了没反应」。
export PATH=/system/bin:/system/xbin:/vendor/bin:/data/adb/ksu/bin:$PATH

MODDIR=${0%/*}
RULES="$MODDIR/rules.conf"
STATE=/data/adb/sb-autotype.state
LOCK=/data/adb/sb-autotype.lock

# 日志写到 /sdcard，任何文件管理器、adb 都能直接看；
# 万一 /sdcard 还没挂好（极早期启动），退回 /data/adb。
LOG=/sdcard/sb-autotype.log
if ! (: >> "$LOG") 2>/dev/null; then
  LOG=/data/adb/sb-autotype.log
  : >> "$LOG" 2>/dev/null
fi
DIAG=/sdcard/sb-autotype-diag.txt
: >> "$DIAG" 2>/dev/null || DIAG=/data/adb/sb-autotype-diag.txt

log() { echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG"; }

mkdir -p "$STATE" 2>/dev/null

# ---------- 单实例锁（原子 mkdir，抢不到就退出） ----------
if ! mkdir "$LOCK" 2>/dev/null; then
  old=$(cat "$LOCK/pid" 2>/dev/null)
  if [ -n "$old" ] && kill -0 "$old" 2>/dev/null; then
    log "another instance ($old) alive, exit"
    exit 0
  fi
  rm -rf "$LOCK"
  mkdir "$LOCK" 2>/dev/null || exit 0
fi
echo $$ > "$LOCK/pid"
trap 'rm -rf "$LOCK"' EXIT INT TERM

# 尽量把自己移出「启动者」的 cgroup。
# 从管理器 WebUI 点启动时，守护进程继承的是 App 的 cgroup，
# 面具一被划掉就会被系统按组清掉；开机由 service.sh 启动则不受影响。
for cg in /sys/fs/cgroup/cgroup.procs \
          /sys/fs/cgroup/apps/cgroup.procs \
          /sys/fs/cgroup/system/cgroup.procs; do
  [ -e "$cg" ] || continue
  if echo $$ > "$cg" 2>/dev/null; then
    log "moved out to $cg"
    break
  fi
done

to_sec() {
  ms="$1"
  case "$ms" in ''|*[!0-9]*) ms=1000 ;; esac
  s=$(awk "BEGIN{printf \"%.2f\", $ms/1000}" 2>/dev/null)
  [ -z "$s" ] && s=$((ms / 1000))
  printf '%s' "$s"
}

# 当前前台包名（只在候选出现时调一次，绝不进轮询）
fg_pkg() {
  line=$(dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity')
  [ -z "$line" ] && return 1
  printf '%s' "$line" | grep -oE '[A-Za-z][A-Za-z0-9._]+/[A-Za-z0-9._$]+' | head -n1 | cut -d/ -f1
}

is_foreground() {
  [ "$(fg_pkg)" = "$1" ]
}

# 规则里所有包名 + 脚本状态，一次性写进诊断文件，方便排查
write_diag() {
  {
    echo "===== $(date '+%F %T') pid=$$ ====="
    echo "id  : $(id 2>&1)"
    echo "PATH: $PATH"
    for t in pidof dumpsys input getprop date grep awk sed; do
      echo "tool $t -> $(command -v "$t" 2>/dev/null || echo MISSING)"
    done
    echo "---- rules.conf ($RULES) ----"
    cat "$RULES" 2>&1
    echo "---- 逐条检查 ----"
    while IFS='|' read -r pkg text delay script tx ty; do
      case "$pkg" in ''|\#*) continue ;; esac
      echo "pkg=$pkg"
      echo "  pidof=[$(pidof "$pkg" 2>/dev/null | tr ' ' ',')]"
      if [ -n "$script" ]; then
        if [ -f "$script" ]; then
          echo "  script ok: $(ls -l "$script" 2>&1)"
          echo "  shebang  : $(head -n1 "$script" 2>&1)"
        else
          echo "  script MISSING: $script"
        fi
      fi
    done < "$RULES"
    echo
  } >> "$DIAG" 2>&1
}

fire() {
  pkg="$1"; text="$2"; delay="$3"; script="$4"; tx="$5"; ty="$6"
  [ -z "$delay" ] && delay=1000
  log "hit $pkg delay=${delay}ms"

  if [ -n "$script" ]; then
    if [ -f "$script" ]; then
      log "run script: $script"
      out=$(cd / && sh "$script" 2>&1)
      rc=$?
      log "script exit=$rc out=$(printf '%s' "$out" | head -c 400 | tr '\n' '|')"
      [ "$rc" -ne 0 ] && log "提示: 脚本退出码非 0，看上面的 out 内容"
    else
      log "script NOT FOUND: $script （规则里的路径不对）"
    fi
  fi

  sleep "$(to_sec "$delay")"

  if [ -n "$tx" ] && [ -n "$ty" ]; then
    log "tap $tx $ty"
    input tap "$tx" "$ty" 2>>"$LOG"
    sleep 0.3
  fi

  if [ -n "$text" ]; then
    esc=$(printf '%s' "$text" | tr -d '\r\n' | sed 's/ /%s/g')
    input text "$esc" 2>>"$LOG"
    log "input text: $esc"
  fi
}

# 单次模式：sh daemon.sh once <包名>
if [ "$1" = "once" ]; then
  want="$2"
  [ -z "$want" ] && { echo "用法: sh daemon.sh once <包名>"; exit 1; }
  while IFS='|' read -r pkg text delay script tx ty; do
    case "$pkg" in ''|\#*) continue ;; esac
    [ "$pkg" = "$want" ] || continue
    fire "$pkg" "$text" "$delay" "$script" "$tx" "$ty"
    break
  done < "$RULES"
  exit 0
fi

[ -f "$RULES" ] || { log "no rules.conf, exit"; exit 1; }
command -v pidof >/dev/null 2>&1 || { log "no pidof in PATH($PATH), exit"; exit 1; }
log "daemon up (pid $$) path=$PATH"
write_diag

tick=0
idle=0
while true; do
  sleep 1
  [ -f "$MODDIR/disable" ] && { log "disable file, exit"; exit 0; }
  [ -f /data/adb/sb-autotype.paused ] && { log "paused, exit"; exit 0; }

  # 每轮统计一下有没有生效规则。
  # 没有规则时不要退出 —— 否则在界面里新加一条规则还得手动启动或重启，
  # 空转一轮的开销只是一次 grep。
  active=$(grep -cvE '^[[:space:]]*(#|$)' "$RULES" 2>/dev/null)
  [ -z "$active" ] && active=0
  if [ "$active" -eq 0 ]; then
    if [ "$idle" = "0" ]; then
      log "no active rule, idle (加规则后自动生效，不用重启)"
      idle=1
    fi
    sleep 4
    continue
  fi
  if [ "$idle" = "1" ]; then
    log "rules found ($active), working"
    idle=0
  fi

  # 每 5 分钟留一条心跳，用来判断循环还活着
  tick=$((tick + 1))
  if [ "$tick" -ge 300 ]; then
    tick=0
    log "alive rules=$active"
  fi

  while IFS='|' read -r pkg text delay script tx ty; do
    case "$pkg" in ''|\#*) continue ;; esac

    st="$STATE/$(printf '%s' "$pkg" | tr '. ' '__')"

    # 用「所有 pid 的排序串」当签名：常驻后台的进程一直在，
    # 只要主进程新起一个（打开 App），签名就会变，不会被误判成「已触发过」。
    pids=$(pidof "$pkg" 2>/dev/null | tr ' ' '\n' | sort -n | tr '\n' ',')
    if [ -z "$pids" ]; then
      rm -f "$st.pid" "$st.t" "$st.n"
      continue
    fi

    [ "$pids" = "$(cat "$st.pid" 2>/dev/null)" ] && continue

    # 每 5 秒最多确认一次前台
    now=$(date +%s)
    last=$(cat "$st.t" 2>/dev/null)
    [ -z "$last" ] && last=0
    [ $((now - last)) -lt 5 ] && continue
    echo "$now" > "$st.t"

    n=$(cat "$st.n" 2>/dev/null)
    [ -z "$n" ] && n=0
    n=$((n + 1))
    echo "$n" > "$st.n"
    if [ "$n" -gt 12 ]; then
      echo "$pids" > "$st.pid"
      rm -f "$st.n"
      log "give up $pkg: 60 秒内没到前台 (foreground=$(fg_pkg 2>/dev/null))"
      continue
    fi

    if is_foreground "$pkg"; then
      echo "$pids" > "$st.pid"
      rm -f "$st.n"
      fire "$pkg" "$text" "$delay" "$script" "$tx" "$ty"
    fi
  done < "$RULES"
done
