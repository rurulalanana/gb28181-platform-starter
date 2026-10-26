#!/bin/bash
# ======================================================================
# gb28181 平台服务管理脚本（守护模式）
# ======================================================================
# 用法： ./gb.sh {start|stop|restart|status|jar|log}
#
# 说明：
#   - start 通过 setsid 启动「守护循环」（guard 内部模式），脱离 SSH 会话
#   - 守护循环前台运行 JVM；JVM 退出（崩溃 / OOM / 平台「重启服务」按钮）后
#     3 秒自动拉起，因此平台的"重启服务"功能依赖本脚本运行
#   - ExitOnOutOfMemoryError：OOM 时直接退出，交给守护循环重启，
#     而不是留在半死不活的状态继续处理信令
#   - stop 会先停守护循环再停 JVM（顺序不可颠倒，否则 JVM 会被重新拉起）
#   - jar 打印实际在跑的 jar（路径/大小/MD5），升级后用它确认没跑成旧版
#   - jar 文件名**不固定**：按 gb28181-platform-starter-*.jar 找最新的那个，
#     升级换版本号不需要改本脚本
# ----------------------------------------------------------------------

APP_NAME="gb28181-platform"
APP_DIR="/opt/gb28181"
LOG="$APP_DIR/logs/console.log"
PID_FILE="$APP_DIR/$APP_NAME.pid"
GUARD_PID_FILE="$APP_DIR/$APP_NAME-guard.pid"
GB_SH="$(cd "$(dirname "$0")" && pwd)/$(basename "$0")"

# ----------------------------------------------------------------------
# 定位要启动的 jar。
#
# 【为什么不再写死文件名】原来这里是
#     JAR="$APP_DIR/gb28181-platform-starter-1.0.0.jar"
# 于是每升一次版本号就得同步改服务器上的本脚本 —— 忘了改就是
# 「部署明明成功，重启后跑的还是旧版本」，而且 status 显示一切正常，
# 极难发现。现在按 glob 找，并排除备份/临时/文档包。
#
# 命中多个时取**最新修改时间**的那个：部署流程是「先落 .new 再 mv 成正式名」，
# 正式包的 mtime 一定最新，正好是刚部署的那一个。
# 想强制指定（例如回滚到 .bak）用环境变量：JAR=/opt/gb28181/xxx.jar ./gb.sh start
# ----------------------------------------------------------------------
resolve_jar() {
    if [ -n "$JAR" ] && [ -f "$JAR" ]; then
        return 0
    fi
    local newest=""
    local f
    for f in "$APP_DIR"/gb28181-platform-starter-*.jar; do
        [ -f "$f" ] || continue
        # 排除 sources/javadoc 包与 .bak/.new/.old 之类的备份
        case "$f" in
            *-sources.jar|*-javadoc.jar|*-tests.jar) continue ;;
            *.bak*|*.new|*.old|*.tmp) continue ;;
        esac
        if [ -z "$newest" ] || [ "$f" -nt "$newest" ]; then
            newest="$f"
        fi
    done
    JAR="$newest"
    [ -n "$JAR" ]
}

if ! resolve_jar; then
    echo "未找到可启动的 jar（$APP_DIR/gb28181-platform-starter-*.jar）" >&2
    exit 1
fi

# 服务器上 PostgreSQL 是 5432，profile 里已配好
SPRING_PROFILE="server"

# 数据库密码不写进 application.yml（会被打进 jar 并提交到 Git 仓库），
# 改由 .env 提供（权限 600，仅 root 可读），以环境变量注入。
if [ -f "$APP_DIR/.env" ]; then
    set -a
    . "$APP_DIR/.env"
    set +a
fi

JAVA_OPTS="-Xms256m -Xmx1024m"
JAVA_OPTS="$JAVA_OPTS -XX:+ExitOnOutOfMemoryError"
JAVA_OPTS="$JAVA_OPTS -Dfile.encoding=UTF-8"
JAVA_OPTS="$JAVA_OPTS -Dsun.stdout.encoding=UTF-8"
JAVA_OPTS="$JAVA_OPTS -Dsun.stderr.encoding=UTF-8"

# 日志轮转参数：单文件超过 LOG_MAX_MB 就在启动时归档，最多保留 LOG_KEEP 份。
# 不轮转的话日志会无限增长（实测 21MB/天，9 天 192MB），迟早把根分区打满 ——
# 而根分区满了之后 JVM 连 GC 日志都没地方写，服务会莫名其妙挂掉。
LOG_MAX_MB="${LOG_MAX_MB:-100}"
LOG_KEEP="${LOG_KEEP:-5}"

# ----------------------------------------------------------------------
# 等待进程真正消失。
#
# <p><b>为什么不能只 sleep 固定秒数</b>：kill / kill -9 只是把信号投递给内核，
# 进程真正被回收还要时间（JVM 有几十个 native 线程，还有僵尸回收）。
# 固定 sleep 1 秒后就去检查，很可能撞上「进程还在退出中」——
# 于是看到它还在，就误判成「另有一个进程在跑」。
#
# $1 = pid，$2 = 超时秒数；进程已消失返回 0，超时仍在返回 1。
# ----------------------------------------------------------------------
wait_gone() {
    local pid="$1" timeout="${2:-10}"
    local steps=$(( timeout * 5 ))   # 每 0.2 秒探一次
    local i=0
    while [ "$i" -lt "$steps" ]; do
        kill -0 "$pid" 2>/dev/null || return 0
        sleep 0.2
        i=$(( i + 1 ))
    done
    # 最后一次确认，避免在探测间隙刚好退出而被误判
    kill -0 "$pid" 2>/dev/null && return 1
    return 0
}

# ----------------------------------------------------------------------
# 日志归档：console.log 超过阈值就改名，只保留 LOG_KEEP 份。
#
# <p><b>只能在启动前调用</b>：guard 用 {@code >> $LOG} 长期持有该文件的
# 文件描述符，跑到一半改名的话，旧 fd 仍指向改名后的 inode ——
# 新日志会继续写进 console.log.1，看着像「轮转没生效」。
# ----------------------------------------------------------------------
rotate_log() {
    [ -f "$LOG" ] || return 0
    local size_mb
    size_mb=$(du -m "$LOG" 2>/dev/null | cut -f1)
    [ -z "$size_mb" ] && return 0
    [ "$size_mb" -lt "$LOG_MAX_MB" ] && return 0
    local i="$LOG_KEEP"
    rm -f "$LOG.$i"
    while [ "$i" -gt 1 ]; do
        [ -f "$LOG.$((i - 1))" ] && mv -f "$LOG.$((i - 1))" "$LOG.$i"
        i=$(( i - 1 ))
    done
    mv -f "$LOG" "$LOG.1"
    echo "日志已归档：console.log -> console.log.1（${size_mb}MB，保留 ${LOG_KEEP} 份）"
}

pid_of() {
    if [ -f "$PID_FILE" ]; then
        local p
        p=$(cat "$PID_FILE")
        if [ -n "$p" ] && kill -0 "$p" 2>/dev/null; then
            echo "$p"
            return 0
        fi
    fi
    # PID 文件失效时按「java ... -jar <jar 全路径>」兜底查找。
    #
    # <b>必须限定 java 前缀</b>：只写 pgrep -f "$JAR" 会把任何命令行里
    # 含有该路径的进程都算进来 —— tail -f、less、grep、甚至编辑器的
    # LSP 都可能命中，于是在「进程已停」时误报「还在运行」。
    pgrep -f "java .*-jar $JAR" 2>/dev/null | head -1
}

# ----------------------------------------------------------------------
# 清理上次异常退出遗留的录像临时文件
# ----------------------------------------------------------------------
# ZLM 写入中的分片带 . 前缀，正常关闭时会把 moov 回写、再改名去掉前缀。
# 若进程异常退出（原生库 SIGABRT / OOM / kill -9），这一步来不及做，
# 磁盘上就留下一个大小正常、但没有 moov 的文件 —— 任何播放器都打不开
# （ffmpeg 报 moov atom not found），且无法修复。
#
# 实测一次崩溃丢了 55 分钟 / 319 MB。这类文件永久占磁盘又没有价值，
# 因此启动时清掉；但只清「最后写入超过 30 分钟」的，避免误删
# 正在录制中的分片（它们同样带 . 前缀，但修改时间一直在更新）。
clean_orphan_records() {
    local root="$APP_DIR/record"
    [ -d "$root" ] || return 0
    local n=0 freed=0
    while IFS= read -r -d '' f; do
        local sz age_min
        sz=$(stat -c %s "$f" 2>/dev/null || echo 0)
        # 修改时间距今的分钟数
        age_min=$(( ( $(date +%s) - $(stat -c %Y "$f" 2>/dev/null || echo 0) ) / 60 ))
        [ "$age_min" -lt 30 ] && continue
        # 只有真的没有 moov 才删：极少数情况下 ZLM 已回写 moov
        # 但改名失败，那种文件还能救，不能删
        if tail -c 2000000 "$f" 2>/dev/null | grep -qa "moov"; then
            echo "  保留（含 moov，可修复）: $f ($((sz/1048576))MB)"
            continue
        fi
        rm -f "$f" && {
            n=$((n+1)); freed=$((freed+sz))
            echo "  删除损坏录像: $(basename "$f") ($((sz/1048576))MB, $age_min 分钟前)"
        }
    done < <(find "$root" -name ".*.mp4" -print0 2>/dev/null)
    [ "$n" -gt 0 ] && echo "已清理 $n 个无法播放的录像文件，释放 $((freed/1048576))MB"
    return 0
}

guard_pid_of() {
    if [ -f "$GUARD_PID_FILE" ]; then
        local p
        p=$(cat "$GUARD_PID_FILE")
        if [ -n "$p" ] && kill -0 "$p" 2>/dev/null; then
            echo "$p"
            return 0
        fi
    fi
    # PID 文件失效时按脚本名兜底查找（排除自身 bash -n 检查等场景）。
    # 排除本进程与父进程，避免把自己算成守护。
    pgrep -f "gb.sh guard" 2>/dev/null | grep -v -x -e "$$" -e "$PPID" | head -1
}

case "$1" in
    start)
        g=$(guard_pid_of)
        if [ -n "$g" ]; then
            p=$(pid_of)
            echo "守护进程已在运行 (guard PID=$g, JVM PID=${p:-未启动})"
            exit 1
        fi
        p=$(pid_of)
        if [ -n "$p" ]; then
            echo "JVM 已在运行但没有守护进程 (PID=$p)"
            echo "如需启用守护请先执行: $0 restart"
            exit 1
        fi
        mkdir -p "$APP_DIR/logs"
        # 必须在拉起 guard 之前归档：guard 一旦启动就长期持有 console.log 的 fd
        rotate_log
        # 上次若是崩溃退出，这里会留下无法播放的录像临时文件，先清掉
        clean_orphan_records
        echo "启动 $APP_NAME（守护模式）..."
        cd "$APP_DIR" || exit 1
        setsid nohup "$GB_SH" guard >> "$LOG" 2>&1 &
        echo $! > "$GUARD_PID_FILE"
        sleep 3
        p=$(pid_of)
        if [ -n "$p" ]; then
            echo "已启动 (JVM PID=$p, guard PID=$(cat "$GUARD_PID_FILE"))"
            echo "日志: tail -f $LOG"
        else
            echo "启动失败，请查看日志: tail -50 $LOG"
            exit 1
        fi
        ;;
    guard)
        # 内部模式：守护循环，由 start 通过 setsid 调用，勿手动执行
        echo "[guard] $(date '+%F %T') 守护循环启动"
        trap 'echo "[guard] $(date "+%F %T") 收到停止信号，退出守护"; exit 0' TERM INT
        # 崩溃退避：启动时刻 + 连续失败次数。
        #
        # 原生库崩溃（exit=134 SIGABRT / 139 SIGSEGV）时会瞬间退出，
        # 若固定 3 秒重启，就会出现「起 → 崩 → 起」的高频循环：
        # 实测一天内 29 次 core dumped，每次都要重新点播、重新开录，
        # 录像被切成碎片，日志也被冲爆。
        #
        # 退避策略：连续崩溃按 3/10/30/60/120 秒递增；只要有一次
        # 存活超过 STABLE_SECONDS，就重置为 3 秒。这样：
        #   - 偶发崩溃：立刻恢复，无感；
        #   - 崩溃风暴：逐步降温，避免把 CPU 和磁盘 IO 打满。
        STABLE_SECONDS=120
        backoff=0
        while true; do
            echo "[guard] $(date '+%F %T') 拉起 JVM ..."
            start_ts=$(date +%s)
            java $JAVA_OPTS -jar "$JAR" \
                --spring.profiles.active=$SPRING_PROFILE \
                --server.port="${GB_APP_PORT:-48088}" \
                --spring.datasource.password="${GB_DB_PASSWORD:-}" >> "$LOG" 2>&1 &
            java_pid=$!
            echo "$java_pid" > "$PID_FILE"
            wait "$java_pid"
            code=$?
            ran=$(( $(date +%s) - start_ts ))
            if [ "$ran" -ge "$STABLE_SECONDS" ]; then
                # 稳定运行过：故障已过去，恢复快速重启
                backoff=0
                delay=3
            else
                case "$backoff" in
                    0) delay=3 ;;
                    1) delay=10 ;;
                    2) delay=30 ;;
                    3) delay=60 ;;
                    *) delay=120 ;;
                esac
                backoff=$((backoff + 1))
            fi
            echo "[guard] $(date '+%F %T') JVM 退出 (exit=$code, 存活 ${ran}s)，${delay} 秒后自动拉起"
            sleep "$delay"
        done
        ;;
    stop)
        # ------------------------------------------------------------------
        # 顺序与等待策略是这里的关键，两步都不能省。
        #
        # <b>坑 1：必须先停守护、并确认它真的死了，才能杀 JVM。</b>
        # 守护循环是 {@code while true; java ...; sleep 3}。如果只 kill 守护
        # 后 sleep 1 就去杀 JVM，守护可能正处在 sleep 3 里没收到 TERM，
        # 等它醒来发现 JVM 没了 —— <b>立刻拉起一个新的</b>。
        # 于是 stop 删掉 PID 文件走人，现场却多了一个「孤儿 JVM」，
        # 紧随其后的 start 看到它就报「JVM 已在运行但没有守护进程」并退出，
        # 整个 restart 静默失败（实测就这么丢过一次服务）。
        #
        # <b>坑 2：kill -9 之后也要等它消失。</b>
        # kill 只是投递信号，进程被内核回收还需要时间。不等就往下走，
        # 后面的 start 检查会撞见「还在退出中」的它，同样误判。
        # ------------------------------------------------------------------
        g=$(guard_pid_of)
        if [ -n "$g" ]; then
            echo "停止守护循环 (guard PID=$g) ..."
            kill "$g" 2>/dev/null
            if ! wait_gone "$g" 8; then
                echo "  守护未响应 TERM，强制杀掉"
                kill -9 "$g" 2>/dev/null
                if ! wait_gone "$g" 5; then
                    echo "  错误：守护进程 $g 无法停止，中止操作（避免它继续拉起 JVM）"
                    exit 1
                fi
            fi
        fi
        rm -f "$GUARD_PID_FILE"

        p=$(pid_of)
        if [ -z "$p" ]; then
            echo "未在运行"
            exit 0
        fi
        echo "停止 $APP_NAME (JVM PID=$p) ..."
        kill "$p" 2>/dev/null
        # 等 20 秒就够，不要等 60 秒。
        #
        # 【为什么不是 8 秒】停机收尾要 flush 录像缓冲、回写 moov、把 . 前缀的
        # 临时名改成正式名。8 秒不够，kill -9 会留下没有 moov 的孤儿文件
        # （大小正常但打不开，且无法修复 —— 实测丢过 55 分钟）。
        #
        # 【为什么不是 60 秒】实测应用层关闭只要 4~12 秒
        # （stopAll 现在每路最多等 3 秒），之后卡住的是 ZLM 原生库的静态析构
        # （~EventPoller 等 C++ 析构），那部分只能靠 kill -9，等多久都一样 ——
        # 之前等满 60 秒，进程仍然活着。
        #
        # 20 秒 = 应用层收尾（≤12 秒）+ 8 秒余量。超时即强杀：
        # 此时录像已由 Gb28181MediaPlatform#stop() 停过，剩下交给下次启动的
        # 录像对账（RecordService#reconcileAutoRecordings）+ 孤儿清理。
        if ! wait_gone "$p" 20; then
            echo "  优雅停止超时（20 秒），强制杀掉（原生库析构卡住，属已知问题）"
            kill -9 "$p" 2>/dev/null
            if ! wait_gone "$p" 5; then
                echo "  错误：JVM $p 无法停止"
                exit 1
            fi
        fi
        rm -f "$PID_FILE"
        echo "已停止"
        ;;
    restart)
        # stop 失败（比如杀不掉）就不能继续 start：
        # 硬起会撞上残留进程，start 报「已在运行」后退出，
        # 结果是「老进程半死 + 新进程没起」，比直接失败更难救。
        if ! "$0" stop; then
            echo "restart 中止：停止阶段失败"
            exit 1
        fi
        "$0" start
        ;;
    status)
        g=$(guard_pid_of)
        p=$(pid_of)
        if [ -n "$p" ]; then
            echo "运行中 (JVM PID=$p, guard PID=${g:-无守护})"
            echo
            echo "=== 监听端口 ==="
            # 端口约定见 deploy/nginx-record.conf：
            #   48088 nginx（公网入口，/record 直读 + 其余反代 48092）
            #   48092 Java（GB_APP_PORT，内部）
            #   45060 SIP / 48089 媒体 HTTP / 44560 RTP 单端口收流
            ss -lntup 2>/dev/null | grep -E ":(48088|48092|45060|48089|41935|48554|48000|44560) " || echo "  (无)"
            echo
            echo "=== 运行时长/内存 ==="
            ps -o pid,etime,rss,cmd -p "$p" 2>/dev/null | tail -2
            echo
            echo "=== 日志 ==="
            if [ -f "$LOG" ]; then
                echo "  当前: $(du -h "$LOG" | cut -f1)（归档阈值 ${LOG_MAX_MB}MB，保留 ${LOG_KEEP} 份）"
                ls -1 "$LOG".* 2>/dev/null | sed 's/^/  归档: /' || true
            else
                echo "  (无日志文件)"
            fi
        else
            echo "未在运行 (guard PID=${g:-无})"
            exit 1
        fi
        ;;
    log)
        tail -f "$LOG"
        ;;
    jar|pid)
        # 只打印「实际在跑的是哪个 jar」与它对应的文件信息。
        # 用途：升级后确认没跑成旧版本 —— status 里的 ps 输出太长，
        # 肉眼比对版本号容易看漏，这里一行说清。
        p=$(pid_of)
        if [ -z "$p" ]; then
            echo "未在运行；下次 start 将使用: $JAR"
            exit 1
        fi
        running=$(tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null | sed -E 's/.*-jar +([^ ]+).*/\1/')
        echo "PID      : $p"
        echo "实际运行 : ${running:-（无法解析）}"
        if [ -n "$running" ] && [ -f "$running" ]; then
            echo "大小/时间: $(stat -c '%s bytes  %y' "$running")"
            echo "MD5      : $(md5sum "$running" | cut -d' ' -f1)"
        fi
        if [ -n "$running" ] && [ "$running" != "$JAR" ]; then
            echo "注意     : 与脚本当前解析到的 jar 不一致"
            echo "           脚本解析: $JAR"
        fi
        ;;
    *)
        echo "用法: $0 {start|stop|restart|status|jar|log}"
        exit 1
        ;;
esac
