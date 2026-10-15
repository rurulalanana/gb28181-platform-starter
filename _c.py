"""提交 gb28181-sip-zlm 的 WebRTC 修复（UTF-8 无 BOM）。"""
import subprocess
import sys

MSG = """fix(webrtc): 注入 rtc.externIP/rtc.port，修复 NAT 后 ICE 候选不可达

问题
- 服务器在云厂商 NAT 后（网卡 172.16.0.148，公网 160.202.254.65），
  ZLM 默认把本机网卡地址当 ICE host candidate 下发，
  answer SDP 里是 `172.16.0.148 8000` —— 浏览器从公网连不上，
  表现为 SDP 协商成功（200 + 完整 answer）但 video 一直 0x0、
  ICE 停在 checking、播放器提示「连接中断」
- 候选端口是 8000（ZLM 默认），与实际监听的 rtc.port 48000 也不一致

改动
- MediaServerProperties 新增 rtcExternIp（ZLM 配置项 rtc.externIP）
- ZlmMediaServer.applyProtocolOptions 注入 rtc.externIP 与 rtc.port
  * 必须在 mk_rtc_server_start 之前设置：候选地址在服务启动时确定
  * rtcExternIp 留空时回退 mediaIp（同机部署二者相同）
  * rtc.port 必须显式设置，否则候选端口与监听端口不一致

验证（160.202.254.65）
- 配置日志：ZLM 配置 rtc.externIP=160.202.254.65 / rtc.port=48000
- answer SDP 候选由 `172.16.0.148 8000` 变为 `160.202.254.65 48000`
- m=video 端口由 8000 变为 48000
- 剩余问题在运维侧：云安全组未放行 UDP 48000（TCP 48088/48089/41935/
  48554/44560 均可达，唯独 48000 不可达），需放行后才能出画面
"""

path = sys.argv[1]
with open(path, "w", encoding="utf-8", newline="\n") as fh:
    fh.write(MSG)

result = subprocess.run(["git", "commit", "-F", path], capture_output=True)
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
print(result.stdout.decode("utf-8", "replace"))
print(result.stderr.decode("utf-8", "replace"))
sys.exit(result.returncode)
