"""生成 UTF-8 无 BOM 的 git 提交信息并提交。

PowerShell 5.1 向外部程序传参时会转成 ANSI，中文会变 `?`；用脚本落盘最稳。
"""
import subprocess
import sys

MSG = """fix(目录): 直连摄像机（IPC）设备自身即通道，修复通道数为 0 与树递归溢出

问题
- 设备 34020000001310000001（TSINGSEE EasyGBD，类型码 131 摄像机）注册上来后
  通道列表一条都没有，日志停在「本次目录项=1 通道总数=0」
- 该设备的目录应答只有一条，且其 DeviceID 就等于设备自身编号 ——
  IPC 没有下级通道，设备编号即通道编号。而 saveCatalog 把「自身那条」
  一律当作设备属性更新后 continue，通道一条不建
- 顺带暴露：一旦把自身登记为通道，其 parentId == channelId 会让
  TreeBuilder.buildLevel 自己找自己，无限递归直到 StackOverflowError
  （/device/channeltree 返回 500）

改动
- DeviceService.saveCatalog：区分「本次响应只有自身一条」与「有真实子通道」
  * 有子通道 → 清理可能残留的自身通道
  * 无子通道且库中也没有 → 把自身登记为通道（直连摄像机）
  * 自身通道缺 Status 时用设备状态兜底，否则 isOnline() 判离线、点播被拒
  * ParentID 缺省时挂到所属设备下，避免树里找不到父节点
- DeviceService.countChannels：不再排除自身那条（它就是真通道）
  新增 hasSubChannels / removeSelfChannel 两个辅助方法
- TreeBuilder.buildLevel：带 visited 做环检测，自引用或重复出现即作叶子节点
  不只是修 IPC，设备上报的 ParentID 本身也可能成环

验证（线上 160.202.254.65:48088）
- 目录同步日志由「通道总数=0」变为「通道总数=1」
- gb_channel 出现 34020000001310000001/34020000001310000001，status=ON
- channeltree 由 500 变为 code=0，设备节点 subCount=1、子节点 EasyIPC/ON
- 通道列表 4 条，EasyIPC 显示「在线」；点播成功
  canvas 1280x720、fill 1.000/1.000、pcm_mulaw 音频解码正常
"""

path = sys.argv[1] if len(sys.argv) > 1 else "_cmsg.txt"
with open(path, "w", encoding="utf-8", newline="\n") as fh:
    fh.write(MSG)

result = subprocess.run(["git", "commit", "-F", path], capture_output=True)
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
print(result.stdout.decode("utf-8", "replace"))
print(result.stderr.decode("utf-8", "replace"))
sys.exit(result.returncode)
