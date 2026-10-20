#!/usr/bin/env python3
"""
提交前敏感信息检查。

背景：数据库口令曾被硬编码进 application.yml，并随仓库公开。
现在口令一律走环境变量（GB_DB_PASSWORD），本脚本负责守住这条线。

检查项：
  1. 暂存区里是否出现形如「password: <明文>」的赋值
  2. 是否包含已知的真实口令（即使改了键名也会命中）

用法：作为 git pre-commit 钩子自动执行，也可单独运行：
    python3 scripts/check-secrets.py
"""

import re
import subprocess
import sys
from pathlib import Path

# 已知泄露过的真实凭据：即使换键名也直接判定为泄露
KNOWN_SECRETS = [
    "Li374382471",
]

# 形如 password: 值 / "password": "值" / password=值
ASSIGN_RE = re.compile(
    r"""(?ix)
    \b(pass(word|wd)?|secret|token|api[_-]?key|access[_-]?key|private[_-]?key)
    \s*[:=]\s*
    ["']?
    (?P<val>[^\n]{0,160}?)
    (?=\s*[,;}\]\n]|$)
    """
)

# 明显的占位符/示例值，不算泄露
PLACEHOLDERS = {
    "", "null", "none", "true", "false", "changeme", "changeit",
    "your_password", "yourpassword", "password", "xxx", "todo",
    "example", "test", "admin", "secret", "token",
}

# 这些文件天然包含大量「key=value」形态的压缩代码，误报率高，一律跳过
SKIP_SUFFIX = {
    ".jar", ".class", ".png", ".jpg", ".jpeg", ".gif", ".gz", ".zip",
    ".woff", ".woff2", ".ttf", ".eot", ".ico", ".map",
}
SKIP_PATH_PARTS = ("static/assets", "node_modules", "dist/", "target/")


def is_placeholder(value: str) -> bool:
    v = value.strip().strip("\"'")
    if not v:
        return True
    low = v.lower()
    if low in PLACEHOLDERS:
        return True
    # ${ENV_VAR} 占位
    if v.startswith("${") or v.startswith("$"):
        return True
    # *** / xxx 之类的掩码
    if set(v) <= {"*", "x", "X", "#"}:
        return True
    # 尖括号占位：<token>、<your-password>（多见于文档与 SQL 注释）
    if re.search(r"<[^>\s][^>]*>", v):
        return True
    # 含中文：多半是注释/说明文字（SQL 的 COMMENT ON、文档示例），不是凭据字面量
    if re.search(r"[\u4e00-\u9fff]", v):
        return True
    # 明显是变量名/占位键（全大写下划线，如 DB_PASSWORD、GB_DB_PASSWORD）
    if re.fullmatch(r"[A-Z_][A-Z0-9_]*", v):
        # 例外：AKIAIOSFODNN7EXAMPLE 这类全大写但混有数字的，是 AWS 密钥的典型格式
        if re.fullmatch(r"[A-Z][A-Z0-9]{16,}", v) and re.search(r"[0-9]", v):
            return False
        return True
    # 取值表达式：localStorage.getItem('gb_token') || ''、row.token 等
    if re.search(r"\w+\s*\([^)]*\)", v):
        return True
    if re.search(r"\w+\s*(\|\||&&)\s*", v):
        return True
    # 对象字面量里的键值对：{ id: x, password: newPassword.value }
    # 值是「标识符.属性」形式 → 代码引用，不是凭据字面量
    if re.fullmatch(r"[A-Za-z_$][\w$]*(\.[\w$]+)+(\(\))?", v):
        # 但「驼峰 + 数字」的多段标识符更像真实口令（MyDbPass2024），
        # 而代码里的属性链末段通常是 value / id / name 这类小写单词
        tail = v.split(".")[-1].rstrip("()")
        if tail.islower() or tail in ("value", "id", "name"):
            return True
    # 纯变量引用（单个标识符）：payload.password = form.password
    # 要求小写/下划线开头 —— 驼峰开头的裸标识符（MyDbPass2024）更像口令
    if re.fullmatch(r"[a-z_$][\w$]*", v):
        return True
    return False


def should_skip(rel: str) -> bool:
    p = Path(rel)
    if p.suffix.lower() in SKIP_SUFFIX:
        return True
    norm = rel.replace("\\", "/")
    return any(part in norm for part in SKIP_PATH_PARTS)


def staged_files() -> list[str]:
    try:
        out = subprocess.run(
            ["git", "diff", "--cached", "--name-only", "--diff-filter=ACMR"],
            capture_output=True, text=True, check=True,
        ).stdout
    except subprocess.CalledProcessError:
        return []
    return [f for f in out.splitlines() if f.strip()]


def main() -> int:
    files = staged_files()
    if not files:
        return 0

    # 本脚本自身含「已知口令」字面量，扫自己必然命中，排除掉。
    # 注意：作为钩子运行时 __file__ 指向 .git/hooks/pre-commit，
    # 所以不能只按自身文件名匹配，按路径关键字排除更稳妥。
    self_markers = (Path(__file__).name, "check-secrets", "pre-commit")
    files = [
        f for f in files
        if not any(m in f.replace("\\", "/") for m in self_markers)
    ]

    problems: list[str] = []

    for rel in files:
        path = Path(rel)
        if not path.is_file():
            continue
        if should_skip(rel):
            continue
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue

        for secret in KNOWN_SECRETS:
            if secret in text:
                problems.append(f"{rel}: 命中已知泄露口令（键名可能是任意形式）")

        for i, line in enumerate(text.splitlines(), 1):
            m = ASSIGN_RE.search(line)
            if not m:
                continue
            val = m.group("val")
            if is_placeholder(val):
                continue
            # 注释行、文档示例行不算
            stripped = line.strip()
            if stripped.startswith(("#", "//", "*", "<!--", "--", "/*")):
                continue
            # SQL 的 COMMENT ON ... IS '说明文字'：整行都是注释
            if re.match(r"(?i)comment\s+on\b", stripped):
                continue
            problems.append(f"{rel}:{i}: {stripped[:110]}")

    if problems:
        print("❌ 提交被拦截：检测到疑似硬编码的敏感信息\n")
        for p in problems:
            print(f"   • {p}")
        print("\n处理方式：")
        print("   1. 改成环境变量，例如 password: ${GB_DB_PASSWORD:}")
        print("   2. 本地值写到 application-local.yml（已在 .gitignore 中）")
        print("   3. 模板见 src/main/resources/application.example.yml")
        print("\n确属误报可加白名单注释后重试。")
        return 1

    print(f"✅ 敏感信息检查通过（{len(files)} 个暂存文件）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
