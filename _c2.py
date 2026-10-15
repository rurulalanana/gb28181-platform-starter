"""提交 gb28181-platform-starter 的 WebRTC 修复（UTF-8 无 BOM）。"""
import subprocess
import sys

MSG = """fix(webrtc): 协商改用 ZLM 实际 app/stream，修复 stream not found

问题
- WebRTC 播放报 `ZLM 生成 answer 失败: stream not found`，
  协商地址 `rtc://__defaultVhost__/34020000001310000001/34020000001310000001`
- 该地址与 ZLM 实际注册的媒体源不一致：本项目开启单端口收流
  （rtp-single-port: true），此时 ZLM 的 app 固定为 rtp、
  stream 为 <设备编码>_<通道编码>，即
  `rtp/34020000001310000001_34020000001310000001`
- WebRtcController 直接把入参当 ZLM 的 app/stream 用，只对独占端口模式成立

改动
- WebRtcController.play：入参仍是业务坐标 <设备编码>/<通道编码>，
  但先取 RtpSession，再用 session.getZlmApp()/getStream()（ZLM 实际坐标）
  去协商与生成播放地址
  * 自动点播后重新取一次会话，避免抢跑
  * 会话尚未建立时退回业务坐标，报错信息更贴近实际
- Gb28181Properties 新增 rtcExternIp，透传给 MediaServerProperties
  （见 gb28181-sip-zlm 的 rtc.externIP 注入）
- Gb28181PlatformConfiguration 接上 rtcExternIp 传递

验证（160.202.254.65:48088）
- 协商由 HTTP 400 / `stream not found` 变为 HTTP 200 / code=0 / SDP 2861 字节
- webrtc 地址由
  `webrtc://160.202.254.65:48000/34020000001310000001/34020000001310000001`
  修正为
  `webrtc://160.202.254.65:48000/rtp/34020000001310000001_34020000001310000001`
- ICE 候选由内网 `172.16.0.148 8000` 修正为公网 `160.202.254.65 48000`
- 剩余问题在运维侧：云安全组未放行 UDP 48000，需放行后才能出画面
"""

path = sys.argv[1]
with open(path, "w", encoding="utf-8", newline="\n") as fh:
    fh.write(MSG)

result = subprocess.run(["git", "commit", "-F", path], capture_output=True)
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
print(result.stdout.decode("utf-8", "replace"))
print(result.stderr.decode("utf-8", "replace"))
sys.exit(result.returncode)
