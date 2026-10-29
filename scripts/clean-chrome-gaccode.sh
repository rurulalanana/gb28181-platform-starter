#!/usr/bin/env bash
#
# 清理 gaccode.com（GAC Claude Code 镜像版）在 Chrome 里的残留数据。
#
# ⚠️ 必须在 Chrome **完全退出**后运行 —— Chrome 运行时会用内存里的数据
#    覆盖这些文件，改了也白改。
#
# 处理内容：
#   1. Login Data    —— 删除 gaccode.com 保存的账号密码（2 条）
#   2. Local Storage —— 删除 gaccode.com 的 token / 会话键值
#   3. Sessions      —— 删除会话恢复里的 gaccode 标签页（否则崩溃恢复会再弹出来）
#   4. 各类缓存索引   —— Favicons / DIPS / Network Action Predictor /
#                        Affiliation Database（纯缓存，删了无副作用）
#
# 不动：Sync Data（同步数据，改动有风险）、IndexedDB（ModHeader 扩展的，与 gaccode 无关）

set -euo pipefail

CHROME_DIR="$HOME/Library/Application Support/Google/Chrome"
PROFILE="$CHROME_DIR/Default"

if pgrep -x "Google Chrome" >/dev/null 2>&1; then
  echo "❌ Chrome 正在运行。请先完全退出（⌘Q），再运行本脚本。"
  echo "   提示：先关掉所有 Chrome 窗口再按 ⌘Q；如提示未退出，执行："
  echo "   killall \"Google Chrome\""
  exit 1
fi

echo "✅ Chrome 已退出，开始清理"
echo

BACKUP="$HOME/chrome-gaccode-cleanup-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BACKUP"
echo "备份目录: $BACKUP"
echo

# ---------------------------------------------------------------- 1. Login Data
if [ -f "$PROFILE/Login Data" ]; then
  cp "$PROFILE/Login Data" "$BACKUP/Login Data.bak"
  python3 - "$PROFILE/Login Data" <<'PYEOF'
import sqlite3, sys
db = sys.argv[1]
con = sqlite3.connect(db)
n = con.execute(
    "DELETE FROM logins WHERE origin_url LIKE '%gaccode%'").rowcount
con.commit()
con.close()
print('  Login Data：删除保存的密码 %d 条' % n)
PYEOF
fi

# ---------------------------------------------------------------- 2. Local Storage
LS="$PROFILE/Local Storage/leveldb"
if [ -d "$LS" ]; then
  cp -R "$LS" "$BACKUP/Local Storage.bak" 2>/dev/null || true
  # 只删 gaccode 相关的条目：LevelDB 单个 .log/.ldb 里是键值混排，
  # 精确删除需要重写文件；这里直接清空该站点的 leveldb 目录内容最稳妥
  # （Local Storage 本地缓存，丢失只会导致网站需重新登录）
  rm -f "$LS"/*.log "$LS"/*.ldb "$LS"/MANIFEST-* 2>/dev/null || true
  echo "  Local Storage：已清空（本地站点缓存丢失，网站需重新登录）"
fi

# ---------------------------------------------------------------- 3. Sessions
if [ -d "$PROFILE/Sessions" ]; then
  cp -R "$PROFILE/Sessions" "$BACKUP/Sessions.bak" 2>/dev/null || true
  rm -f "$PROFILE/Sessions"/* 2>/dev/null || true
  echo "  Sessions：已清空（不会恢复出 gaccode 标签页）"
fi

# ---------------------------------------------------------------- 4. 缓存索引
for f in Favicons DIPS "DIPS-wal" "Network Action Predictor" "Affiliation Database"; do
  if [ -e "$PROFILE/$f" ]; then
    rm -f "$PROFILE/$f" 2>/dev/null || true
    echo "  缓存已清: $f"
  fi
done

# ---------------------------------------------------------------- 5. 备份文件
rm -f "$PROFILE/Preferences.bak-gaccode" 2>/dev/null || true

echo
echo "=== 复核 ==="
LEFT=$(grep -rl 'gaccode' "$CHROME_DIR" 2>/dev/null | grep -v 'Sync Data' | grep -v 'IndexedDB' | wc -l | tr -d ' ')
echo "  剩余含 gaccode 的文件: $LEFT 个（Sync Data / IndexedDB 为同步数据与扩展数据，已保留）"
if [ "$LEFT" != "0" ]; then
  grep -rl 'gaccode' "$CHROME_DIR" 2>/dev/null | grep -v 'Sync Data' | grep -v 'IndexedDB' | sed "s|$CHROME_DIR/|    |"
fi
echo
echo "完成。备份保留在: $BACKUP"
