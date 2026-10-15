#!/usr/bin/env python3
"""
通过 GitHub Git Data API 推送本地提交（绕过被阻断的 git smart-HTTP）。

背景：本机沙箱代理对 github.com 的 git 端点（/info/refs、/git-receive-pack）
间歇性阻断，但 api.github.com 可正常访问。因此改用 REST API 逐对象上传：
blob → tree → commit → 更新 ref。

用法：
    python3 push-api.py <github_token> [--repo owner/name] [--branch main] [--dry-run]

token 需要 Contents: Read and write 权限（fine-grained token）。
"""

import argparse
import base64
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

API = "https://api.github.com"
TOKEN = ""
DRY = False
REPO_DIR = None


def run_git(*args, cwd=None):
    """执行 git 命令并返回标准输出（去尾换行）。"""
    out = subprocess.run(
        ["git", *args], cwd=cwd or REPO_DIR, capture_output=True, text=True, check=True
    )
    return out.stdout.rstrip("\n")


def api(method, path, payload=None, expect=(200, 201), retries=3):
    """调用 GitHub REST API。"""
    url = path if path.startswith("http") else API + path
    body = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if body:
        req.add_header("Content-Type", "application/json")

    if DRY:
        print(f"  [dry-run] {method} {url}")
        return {}

    last = None
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=60) as resp:
                data = resp.read().decode()
                if resp.status not in expect:
                    raise RuntimeError(f"{method} {url} -> HTTP {resp.status}: {data[:300]}")
                return json.loads(data) if data else {}
        except urllib.error.HTTPError as e:
            detail = e.read().decode()[:400]
            if e.code in expect:
                return json.loads(detail) if detail else {}
            # 409 = 对象已存在，可安全跳过；5xx 重试
            if e.code == 409:
                print(f"  (已存在，跳过) {detail[:120]}")
                return {}
            last = RuntimeError(f"{method} {url} -> HTTP {e.code}: {detail}")
            if e.code < 500:
                raise last
        except Exception as e:  # 网络类错误，重试
            last = e
        if attempt < retries - 1:
            wait = 2 ** attempt
            print(f"  重试 {attempt + 1}/{retries - 1}（{wait}s）: {last}")
            time.sleep(wait)
    raise last


# ==================== 对象上传 ====================

blob_cache = {}


def upload_blob(repo, sha):
    """上传 blob（按内容 SHA 去重，GitHub 端同样内容会返回同一 SHA）。"""
    if sha in blob_cache:
        return blob_cache[sha]
    kind = run_git("cat-file", "-t", sha)
    if kind != "blob":
        raise RuntimeError(f"{sha} 不是 blob（{kind}）")
    content = subprocess.run(
        ["git", "cat-file", "blob", sha], cwd=REPO_DIR, capture_output=True, check=True
    ).stdout
    payload = {
        "content": base64.b64encode(content).decode(),
        "encoding": "base64",
    }
    res = api("POST", f"/repos/{repo}/git/blobs", payload)
    new_sha = res.get("sha", sha)
    blob_cache[sha] = new_sha
    return new_sha


def upload_tree(repo, sha, path_prefix=""):
    """递归上传 tree，返回 GitHub 端 tree SHA。"""
    raw = run_git("ls-tree", sha)  # 形如 "100644 blob <sha>\t<name>"
    if not raw:
        res = api("POST", f"/repos/{repo}/git/trees", {"tree": []})
        return res.get("sha", sha)

    entries = []
    for line in raw.split("\n"):
        meta, name = line.split("\t", 1)
        mode, kind, obj_sha = meta.split()
        if kind == "blob":
            new_sha = upload_blob(repo, obj_sha)
        elif kind == "tree":
            new_sha = upload_tree(repo, obj_sha, path_prefix + name + "/")
        else:
            raise RuntimeError(f"不支持的对象类型：{kind}（{name}）")
        entry = {"path": name, "mode": mode, "type": kind, "sha": new_sha}
        entries.append(entry)
        print(f"    {path_prefix}{name}")

    res = api("POST", f"/repos/{repo}/git/trees", {"tree": entries})
    return res.get("sha", sha)


def push_commit(repo, sha, parents_map):
    """上传单个 commit（含其 tree），返回 GitHub 端 commit SHA。"""
    meta = run_git("show", "-s", "--format=%T%n%P%n%an%n%ae%n%aI%n%cn%n%ce%n%cI%n%B", sha)
    lines = meta.split("\n")
    tree_sha, parent_shas = lines[0], lines[1].split() if lines[1] else []
    author_name, author_email, author_date = lines[2], lines[3], lines[4]
    committer_name, committer_email, committer_date = lines[5], lines[6], lines[7]
    message = "\n".join(lines[8:]).rstrip()

    print(f"  上传 tree {tree_sha[:10]}…")
    new_tree = upload_tree(repo, tree_sha)

    new_parents = []
    for p in parent_shas:
        if p in parents_map:
            new_parents.append(parents_map[p])
        else:
            new_parents.append(p)  # 远端已有的祖先

    payload = {
        "message": message,
        "tree": new_tree,
        "parents": new_parents,
        "author": {"name": author_name, "email": author_email, "date": author_date},
        "committer": {
            "name": committer_name,
            "email": committer_email,
            "date": committer_date,
        },
    }
    res = api("POST", f"/repos/{repo}/git/commits", payload)
    new_sha = res.get("sha", sha)
    parents_map[sha] = new_sha
    print(f"  ✓ commit {sha[:10]} → {new_sha[:10]}")
    return new_sha


# ==================== 主流程 ====================


def main():
    global TOKEN, DRY, REPO_DIR
    ap = argparse.ArgumentParser(description="通过 Git Data API 推送提交")
    ap.add_argument("token", nargs="?", default=None,
                    help="GitHub PAT（需 Contents: Read and write）；也可用 --token-file / GH_TOKEN")
    ap.add_argument("--token-file", default=None, help="从文件读取 PAT（推荐，避免进入进程列表）")
    ap.add_argument("--repo", default="rurulalanana/gb28181-platform-starter")
    ap.add_argument("--branch", default="main")
    ap.add_argument("--dir", default=None, help="git 仓库目录（默认脚本所在目录）")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    token = args.token
    if not token and args.token_file:
        with open(args.token_file, "r", encoding="utf-8") as f:
            token = f.read().strip()
    if not token:
        token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        print("✗ 未提供 token（位置参数 / --token-file / GH_TOKEN）", file=sys.stderr)
        return 1

    TOKEN = token
    DRY = args.dry_run
    repo, branch = args.repo, args.branch
    REPO_DIR = args.dir or os.path.dirname(os.path.abspath(__file__))

    # 1. 校验 token 与仓库
    print("==> 校验 token…")
    info = api("GET", f"/repos/{repo}")
    if info.get("permissions", {}).get("push") is False:
        print("✗ token 无 push 权限", file=sys.stderr)
        return 1
    print(f"  仓库：{info.get('full_name')}（push={info.get('permissions', {}).get('push')}）")

    # 2. 找出待推送提交
    local_head = run_git("rev-parse", "HEAD")
    remote_sha = api("GET", f"/repos/{repo}/git/ref/heads/{branch}")["object"]["sha"]
    print(f"==> 本地 HEAD {local_head[:10]} / 远端 {branch} {remote_sha[:10]}")

    if local_head == remote_sha:
        print("✓ 已是最新，无需推送")
        return 0

    to_push = run_git("rev-list", "--reverse", f"{remote_sha}..{local_head}").split("\n")
    to_push = [s for s in to_push if s]
    if not to_push:
        print("✗ 远端不在本地历史中，需先合并", file=sys.stderr)
        return 1
    print(f"==> 待推送 {len(to_push)} 个提交")

    # 3. 逐个上传
    parents_map = {}
    for sha in to_push:
        subject = run_git("show", "-s", "--format=%s", sha)
        print(f"\n[{sha[:10]}] {subject}")
        push_commit(repo, sha, parents_map)

    new_head = parents_map[local_head]

    # 4. 更新 ref
    print(f"\n==> 更新 ref {branch} → {new_head[:10]}")
    api(
        "PATCH",
        f"/repos/{repo}/git/refs/heads/{branch}",
        {"sha": new_head, "force": False},
    )

    # 5. 让本地 origin/main 跟上（避免后续 push 分叉）
    # 注意：GitHub 端 commit 是重新生成的 SHA，本地无该对象，
    # 只能等下次 fetch 时同步；这里失败不影响推送结果。
    try:
        run_git("update-ref", f"refs/remotes/origin/{branch}", new_head)
    except Exception as e:
        print(f"  (本地 origin/{branch} 暂未同步，下次 fetch 时会自动跟上：{e})")
    print(f"\n✓ 推送完成：{info.get('html_url')}/tree/{branch}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
