@echo off
chcp 65001 >nul
cd /d "%~dp0"
set JAVA_HOME=C:\Program Files\BellSoft\LibericaJDK-21
set PATH=%JAVA_HOME%\bin;%PATH%
if exist local-run.log del local-run.log

rem 本地联调用启动脚本（未入库，纯开发辅助）
rem   48088      网页端口（与线上一致，方便对照 .vscode/mcp.json）
rem   数据库      连的是 160.202.254.65:25432，即线上库；改数据会直接影响线上
rem   主动注册      --dahua.server-mode.enabled=true 才会监听 9500 收大华设备
rem   接口文档      --gb28181.api-docs.protect=false 免口令，便于本地打开 /doc.html
rem   验证码        --gb28181.login.captcha-enabled=false 关掉，浏览器测试不用拖滑块
rem                 【仅限本地】线上必须保持开启，否则等于没有登录防护
rem 排查大华问题时临时加：
rem   --logging.level.io.github.rurulalanana.dahua=DEBUG
rem   （不要开 starter.mapper 的 DEBUG，SQL 会淹掉 ZLM 的 codec 日志）

rem ============================================================
rem WebRTC 候选地址（ZLM 的 rtc.externIP）= auto
rem
rem 为什么必须单独设：ZLM 会把这个地址写进 SDP answer 的 a=candidate，
rem 浏览器只往那个地址发 STUN/SRTP。留空时它回退到 application.yml 的
rem media-ip（那是线上公网 IP 123.57.109.223），本机浏览器根本到不了，
rem 现象是【协商成功但画面全黑】，15 秒后 ZLM 打
rem 「接受rtp/rtcp/datachannel超时」把 transport 关掉。
rem FLV 走 HTTP 回源、与 candidate 无关，所以表现成「FLV 能播、WebRTC 不能」。
rem
rem auto = 自动取「默认路由所在网卡」的 IPv4（不是 ipconfig 里的第一条 ——
rem 装了 VMware/VirtualBox 的机器上第一条往往是 192.168.80.1 这类虚拟网卡，
rem 局域网里别的机器到不了）。要固定成某个 IP 就直接写地址。
rem 线上不配这一项，仍用 media-ip（公网），行为不变。
set "RTC_ARG=--gb28181.rtc-extern-ip=auto"
echo [INFO] WebRTC 候选地址使用 auto（本机主网卡 IP），启动日志会打印实际取值

rem 【口令一律走环境变量】本脚本要入库，写死等于把生产库口令公开发布。
rem 本机临时用：先在当前窗口执行  set GB_DB_PASSWORD=你的口令
rem 长期用：写进 src/main/resources/application-local.yml（已 gitignore）或系统环境变量。
if not defined GB_DB_PASSWORD (
  echo [ERROR] 未设置 GB_DB_PASSWORD 环境变量（数据库口令）
  echo         先执行: set GB_DB_PASSWORD=你的口令
  pause
  goto :eof
)

java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 ^
  -jar target\gb28181-platform-starter-1.0.2.jar ^
  --server.port=48088 ^
  --spring.datasource.password=%GB_DB_PASSWORD% ^
  --dahua.server-mode.enabled=true ^
  --gb28181.api-docs.protect=false ^
  --gb28181.login.captcha-enabled=false ^
  %RTC_ARG% ^
  > local-run.log 2>&1
