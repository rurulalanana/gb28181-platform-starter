#!/bin/bash
# 媒体闭环验证：注册 → 点播 → ffmpeg 推流 → ZLMediaKit 收流并解出轨道
#
# 判据取自 ZLMediaKit 的原生日志（应用内无法直接查询）：
#   允许RTP推流      —— RTP 数据到达平台的收流端口
#   onAllTrackReady  —— 媒体已解复用并识别出编码轨道（H264）
#
# 用法：./verify-media-loop.sh
set -u
cd "$(dirname "$0")"

JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17 2>/dev/null || echo /usr/bin/java)}"
JAVA="$JAVA_HOME/bin/java"
CP="target/test-classes:target/classes"
if [ -f /tmp/demo-cp.txt ]; then
  CP="$CP:$(cat /tmp/demo-cp.txt)"
else
  mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/demo-cp.txt || exit 1
  CP="$CP:$(cat /tmp/demo-cp.txt)"
fi

[ -d target/test-classes ] || mvn -q test-compile || exit 1

LOG=$(mktemp -t gb28181-loop).log
"$JAVA" -cp "$CP" io.github.rurulalanana.demo.MediaLoopVerifier 2>&1 | tee "$LOG"
APP_CODE=${PIPESTATUS[0]}

echo "───────────────────────────────"
if grep -q "允许RTP推流" "$LOG" && grep -q "onAllTrackReady" "$LOG"; then
  echo "✅ 媒体闭环通过：设备注册 → 平台点播 → RTP 收流 → 解出 H264 轨道"
  grep -a "onAllTrackReady" "$LOG" | head -1 | sed 's/^/   /'
  exit 0
else
  echo "❌ 媒体闭环未通过（未在 ZLM 日志中发现「允许RTP推流」或「onAllTrackReady」）"
  echo "   完整日志：$LOG"
  exit 1
fi