# gb28181-platform-starter

**GB28181 流媒体平台 Starter**：把 [`gb28181-sip`](https://central.sonatype.com/artifact/io.github.rurulalanana/gb28181-sip)（信令）
与 [`gb28181-sip-zlm`](https://github.com/rurulalanana/gb28181-sip-zlm)（媒体，内嵌 ZLMediaKit）
装配成一个可运行的 Spring Boot 工程。克隆即可当作你的平台起点：REST API、Web 测试页、
设备模拟器、启动自检都已接好，改配置与包名就能直接用。

- **零真实摄像头依赖**：内置 GB28181 设备模拟器，可用 ffmpeg 造真实测试画面
- **开箱即测**：REST API + Web 测试页（WebRTC 播放）+ 启动自检
- **媒体链路可验证**：`./verify-media-loop.sh` 一条命令验证「注册 → 点播 → RTP 收流 → 解出 H264 轨道」

> 新项目用起来建议：改 `gb28181.platform-id` / `sip-port` / `media-ip`，
> 把 `gb28181.simulator.enabled` 设为 `false` 接入真实设备，
> 然后按需扩展 `PlatformController` 与 `application.yml`。

## 快速开始

```bash
# 前置：把两个模块装进本地仓库（若已在 ~/.m2 可跳过）
cd ../gb28181-sip     && mvn -q clean install -DskipTests
cd ../gb28181-sip-zlm && mvn -q clean install -DskipTests

cd ../gb28181-platform-starter
mvn clean package

# 启动（模拟设备默认开启，且会推 ffmpeg 测试画面）
java -jar target/gb28181-platform-starter-1.0.0.jar \
     --gb28181.simulator.push-test-media=true \
     --gb28181.starter.self-check=true

# 浏览器打开测试页
open http://localhost:8080/
```

> **端口提醒**：Spring Boot 自己用 `8080`，因此媒体 HTTP 服务在 `application.yml` 里改成了 `8081`。
> SIP 为 `5060/UDP`，媒体端口：HTTP 8081 / RTMP 11935 / RTSP 18554 / WebRTC 18000。

## 验证清单

| # | 操作 | 期望 |
|---|---|---|
| 1 | 启动日志 | `平台已启动：SIP 0.0.0.0:5060 / 媒体 http=8081 …`，随后 `模拟设备已创建并注册` |
| 2 | 自检（`--gb28181.starter.self-check=true`） | `[自检] 收流端口=… ssrc=…`，ZLM 日志出现 `onAllTrackReady … H264[640/360/25]` |
| 3 | `./verify-media-loop.sh` | `✅ 媒体闭环通过` |
| 4 | `mvn test` | 4 个集成用例通过（注册 / 点播 / REST / 录像） |
| 5 | 测试页「点播」→ 「WebRTC 播放」 | 浏览器出画面（需与平台网络互通） |

## 测试页

`http://localhost:8080/`

- 左上：WebRTC 播放器（嵌入式模式下浏览器出画面的唯一通路，见下文限制）
- 右侧：平台状态、在线设备、模拟设备、收流会话，点播/回放/录像/推流开关
- **点播** 走「平台主动 INVITE」；**回放** 需先「查录像」拿到 filePath
- 开关「测试推流」控制模拟设备是否用 ffmpeg 推画面

## REST API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/status` | 平台状态（端口、会话数、录像数） |
| GET | `/api/devices` | 平台注册表中的在线设备 |
| GET | `/api/simulators` | 模拟设备列表 |
| POST | `/api/simulators?deviceId=…` | 新增模拟设备 |
| DELETE | `/api/simulators/{id}` | 移除模拟设备 |
| POST | `/api/simulators/{id}/media?enabled=true` | 开关该设备的 ffmpeg 推流 |
| POST | `/api/play/{deviceId}` | 平台点播（标准 9.2） |
| GET | `/api/records/{deviceId}` | 检索设备录像（标准 9.8） |
| POST | `/api/playback/{deviceId}?filePath=…` | 发起回放 |
| POST | `/api/playback/{deviceId}/control?callId=…&command=pause\|seek\|speed` | 回放控制（附录 D） |
| POST | `/api/download/{deviceId}?filePath=…` | 文件下载（标准 9.9） |
| POST | `/api/stop/{deviceId}` | 停止点播并释放端口 |
| GET | `/api/stream/{deviceId}` | 当前会话与播放地址 |
| GET | `/api/sessions` | 全部收流会话 |
| GET | `/api/recordings?channel=&from=&to=` | 平台录像检索 |
| POST/DELETE | `/api/record/{deviceId}` | 开始/停止平台录像 |
| POST | `/api/recordings/{id}/play` | 录像点播 |
| POST | `/api/webrtc/play?stream=&peerId=` | 浏览器 offer → ZLM answer |
| GET | `/api/webrtc/config` | WebRTC 参数 |

## 配置（application.yml / `--gb28181.*`）

| 配置 | 默认 | 说明 |
|---|---|---|
| `platform-id` | `34020000002000000001` | 平台 20 位编码 |
| `sip-host/sip-port` | `0.0.0.0` / `5060` | SIP 信令 |
| `media-ip` | 空=自动探测 | **SDP `c=` 行与播放地址主机，必须是设备可达地址** |
| `http-port` | `8081` | 媒体 HTTP（FLV/HLS；嵌入式模式下路由未注册） |
| `rtmp/rtsp/rtc-port` | `11935/18554/18000` | 各协议端口，`0`=自动选空闲端口，`-1`=关闭 |
| `app` | `rtp` | ZLM 应用名 |
| `auto-record` / `record-root` | `false` / `${user.dir}/record` | 平台录像 |
| `simulator.enabled/count` | `true` / `1` | 内置模拟设备 |
| `simulator.push-test-media` | `false` | 模拟设备用 ffmpeg 推测试画面（需 ffmpeg） |
| `demo.self-check` | `false` | 启动后自动点播第一路在线设备 |

## 已知限制（嵌入式 ZLMediaKit）

1. **HTTP 播放路由未注册**：`/index/api/*`、`/app/…live.flv`、HLS 等由 **MediaServer 可执行文件**注册，C API 嵌入模式下不存在（ZLMediaKit issue #3503）。因此：
   - 浏览器出画面请用 **WebRTC**（本工程实现了 SDP 交换：`POST /api/webrtc/play`）
   - HTTP-FLV / HLS 地址会返回 404
2. **RTSP/RTMP 回拉在嵌入模式下报 `no such stream`**：源已注册（日志可见 `媒体注册:rtsp://…`），但播放器侧查询不到。若生产需要 RTSP/RTMP 拉流，建议改为**独立部署 MediaServer 进程** + 本工程通过其 REST API 驱动（WVP 式架构），或改用 WebRTC。
3. **推流鉴权回调未注册**：注册 `on_mk_media_publish` 会让 ZLM 等待 embedder 调用 auth invoker，而 zlm4j 未映射该函数指针（强行调用会导致 JVM 崩溃），因此不注册，由 ZLM 按默认放行。
4. **jain-sip 是进程级单例**：同一 JVM 不能再起第二个 SIP 服务，所以媒体闭环验证放在独立进程（`MediaLoopVerifier`）。

## 依赖说明

- `gb28181-sip` 依赖 jain-sip，jain-sip 硬依赖 **log4j 1.x API**（含 `org.apache.log4j.Layout`，`log4j-over-slf4j` 不提供），因此**不能排除 log4j**；其日志由 `log4j.properties` 单独收敛，应用日志仍走 Logback。
- `gb28181-sip-zlm` 通过 zlm4j 内嵌 ZLMediaKit，jar 自带 win/linux/mac 各平台原生库。
- 媒体端口配置为 `0` 时表示**自动选择空闲端口**（ZLM 本身把 0 解释为「用默认端口 80/1935/554」，非 root 绑定会失败）。