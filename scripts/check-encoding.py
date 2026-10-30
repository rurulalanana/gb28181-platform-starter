"""提交前自检：确认改动文件都是合法 UTF-8、没有替换字符（U+FFFD）。

项目规范明确禁止用 PowerShell 的 Get-Content/Set-Content 改文本文件
（PS 5.1 按 cp936 解码 UTF-8，中文会不可逆地变成 ?）。本脚本在每次提交前
跑一遍，避免把损坏的文件推上去。
"""
import io
import sys

FILES = [
    "pom.xml",
    "src/main/java/io/github/rurulalanana/starter/web/LiveShareController.java",
    "src/main/java/io/github/rurulalanana/starter/vo/LiveShareVo.java",
    "src/main/java/io/github/rurulalanana/starter/config/SaTokenConfiguration.java",
    "src/main/java/io/github/rurulalanana/starter/service/StreamService.java",
    "frontend/src/views/LiveShareView.vue",
    "frontend/src/views/PlaybackView.vue",
    "frontend/src/views/ChannelView.vue",
    "frontend/src/views/DashboardView.vue",
    "frontend/src/player/LivePlayerEngine.js",
    "frontend/src/composables/useLivePlayer.js",
    "frontend/src/router/index.js",
]

failed = 0
for path in FILES:
    try:
        with io.open(path, encoding="utf-8") as f:
            text = f.read()
    except FileNotFoundError:
        print("缺失 %s" % path)
        failed += 1
        continue
    bad = text.count("\ufffd")
    # 也要检查有没有被 cp936 误解码后留下的典型乱码片段
    mojibake = sum(text.count(s) for s in ("锛", "鐨", "浜", "鍒", "瑙嗚"))
    flag = "OK" if bad == 0 and mojibake == 0 else "损坏"
    if bad or mojibake:
        failed += 1
    print("%-4s %-72s 替换字符=%d 乱码片段=%d" % (flag, path, bad, mojibake))

sys.exit(1 if failed else 0)
