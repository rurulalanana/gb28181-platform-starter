# gb28181-platform-starter

**GB28181 流媒体平台 Starter**：把 [`gb28181-sip`](https://central.sonatype.com/artifact/io.github.rurulalanana/gb28181-sip)（信令）
与 [`gb28181-sip-zlm`](https://github.com/rurulalanana/gb28181-sip-zlm)（媒体，内嵌 ZLMediaKit）
装配成一个可运行的 Spring Boot 工程。克隆即可当作你的平台起点：REST API、Vue 3 管理前端、
设备/通道/流/录像/报警入库、云端录像对象存储归档、启动自检都已接好，改配置与包名就能直接用。

- **开箱即用**：REST API + Vue 3 + Element Plus 管理前端（WebRTC 播放）+ 启动自检
- **数据自动入库**：设备注册后自动拉取目录/信息/状态，落 PostgreSQL（MyBatis-Plus）
- **表结构自维护**：启动自动执行 `db/schema.sql`，建表 + 注释 + 初始账号，幂等可重跑
- **云端录像可选归档**：S3 兼容协议，一套配置覆盖阿里云 OSS / 腾讯云 COS / MinIO / 七牛 / AWS S3

> 新项目用起来建议：改 `gb28181.platform-id` / `sip-port` / `media-ip`，
> 然后按需扩展 `web/` 下的 Controller、`frontend/src/views/` 下的页面与 `application.yml`。

## 快速开始

```bash
# 前置：把两个模块装进本地仓库（若已在 ~/.m2 可跳过）
cd ../gb28181-sip     && mvn -q clean install -DskipTests
cd ../gb28181-sip-zlm && mvn -q clean install -DskipTests

cd ../gb28181-platform-starter
mvn clean package

# 启动（设备注册后自动拉取目录并入库）
java -jar target/gb28181-platform-starter-1.0.0.jar \
     --gb28181.starter.self-check=true

# 浏览器打开管理前端（默认账号 admin / admin123）
open http://localhost:8080/
```

> **端口提醒**：Spring Boot 自己用 `8080`，因此媒体 HTTP 服务在 `application.yml` 里改成了 `8081`。
> SIP 为 `5060/UDP`，媒体端口：HTTP 8081 / RTMP 11935 / RTSP 18554 / WebRTC 18000。

## 验证清单

| # | 操作 | 期望 |
|---|---|---|
| 1 | 启动日志 | `平台已启动：SIP 0.0.0.0:5060 / 媒体 http=8081 …`，`数据库表结构初始化完成` |
| 2 | 设备注册后 | 日志 `目录已同步: device=… 通道数=…`，查 `/api/v1/device/channellist` 有数据 |
| 3 | 自检（`--gb28181.starter.self-check=true`） | `[自检] 收流端口=… ssrc=…`，ZLM 日志出现 `onAllTrackReady … H264[1280/720/25]` |
| 4 | 前端「实时预览」→ 选择通道 → 播放 | 浏览器出画面（需与平台网络互通） |

## 管理前端（Vue 3 + Element Plus）

`http://localhost:8080/`（默认账号 `admin` / `admin123`）

| 页面 | 功能 |
|---|---|
| 总览 | 设备/通道统计、服务器信息、在线设备、进行中的直播流 |
| 实时预览 | 1/2/3/4 分屏 WebRTC 播放，先点播再协商 |
| 设备管理 | 设备列表、一键采集、重命名、删除 |
| 通道管理 | 通道列表、单通道预览、云端录像开关、重命名 |
| 设备录像 | 设备侧录像检索（9.8）→ 回放 INVITE → WebRTC，含暂停/倍速/停止控制 |
| 云端录像 | 按月/日查询、日历标记、点播、下载、重要标记、删除（含对象存储状态） |
| 报警管理 | 报警列表、级别筛选、删除/清空 |
| 级联配置 | 上下级平台对接配置、目录推送 |
| 用户管理 | 用户增删改、启用/停用、重置密码（需 admin） |
| 系统设置 | 服务器信息、修改密码、播放与对象存储配置说明 |

### 前端开发

```bash
cd frontend
npm install
npm run dev     # 开发模式（Vite dev server，自动代理 /api 到 localhost:8080）
npm run build   # 构建产物输出到 src/main/resources/static/，随 jar 一起打包
```

> 技术栈：Vue 3 + Vite 6 + Element Plus + Pinia + Vue Router。
> 构建产物直接进后端静态资源目录，**单 jar 部署**；SPA 路由回退由 `config/SpaWebConfiguration` 处理。
> 浏览器播放支持 **WebSocket-FLV**（默认）与 **WebRTC** 两种通路，页面右上角可切换。

## REST API

### 一、平台接口 `/api/v1/*`（对齐青瓜视频 GBS）

统一响应 `{"code":0,"msg":"ok","data":{...}}`，HTTP 状态码恒为 200。
请求参数用 snake_case，**响应字段统一小驼峰（camelCase）**，分页用游标语义（`start` 偏移 + `limit` 页大小，`limit=0` 不分页）。

所有出参都是 `vo` 包下的强类型对象（不再用 `Map<String,Object>` 拼装），
序列化时值为 `null` 的字段会被省略。

除 `/api/v1/login` 与 `/api/v1/webrtc/*` 外，其余接口需在请求头带 `Authorization: <token>`。

| 分类 | 路径 | 说明 |
|---|---|---|
| **系统** | `POST /api/v1/login` | 登录（body：`username`/`password`/`urlTokenOnly`），返回 `token` |
| | `GET /api/v1/logout` | 注销 |
| | `GET /api/v1/userinfo` | 当前用户 |
| | `POST /api/v1/modifypassword` | 改密（改后旧 token 失效） |
| | `GET /api/v1/getserverinfo` | 服务器信息 |
| | `POST /api/v1/restart?force=true` | 重启平台 |
| **设备信息** | `GET /api/v1/device/list` | 设备列表（`deviceCount`/`deviceList`） |
| | `GET /api/v1/device/info` | 单设备 |
| | `GET /api/v1/device/onlinestats` | 在线统计 |
| | `GET /api/v1/device/channellist` | 通道列表（`channelCount`/`channelList`） |
| | `GET /api/v1/device/channelinfo` | 单通道 |
| | `GET /api/v1/device/channeltree` | 通道树 |
| | `GET /api/v1/device/grouptree` | 分组树 |
| | `GET /api/v1/device/positionlog` | 移动轨迹 |
| | `GET /api/v1/device/statuslog` | 状态变更记录 |
| | `GET /api/v1/device/streamlog` | 流历史 |
| | `POST /api/v1/device/collect` | **一键采集设备全量数据（目录+信息+状态）** |
| | `POST /api/v1/device/fetchcatalog` | 抓取目录 |
| | `POST /api/v1/device/fetchinfo` | 抓取设备信息 |
| | `POST /api/v1/device/fetchstatus` | 抓取设备状态 |
| | `POST /api/v1/device/setname` `/setchannelname` `/setchannelid` … | 编辑设备/通道 |
| | `POST /api/v1/device/setpassword` | 记录设备 SIP 密码（仅供运维参考，不参与鉴权） |
| | `POST /api/v1/device/remove` | 删除设备 |
| **实时直播** | `POST /api/v1/stream/start` | 开始直播，返回 `streamId` 与各协议播放地址 |
| | `POST /api/v1/stream/stop` | 停止直播 |
| | `GET /api/v1/stream/list` | 直播流列表 |
| | `GET /api/v1/stream/info` | 单条流信息 |
| | `POST /api/v1/stream/relay/start` `/relay/stop`，`GET /relay/list` | 转推 |
| **前端录像** | `GET /api/v1/playback/recordlist` | 检索设备录像 |
| | `POST /api/v1/playback/start` `/stop` `/control` | 回放及控制 |
| | `GET /api/v1/playback/streamlist` `/streaminfo` | 回放流 |
| **云端录像** | `GET /api/v1/cloudrecord/querychannels` `/queryflags` `/querymonthly` `/querydaily` | 查询 |
| | `GET /api/v1/cloudrecord/download/{serial}/{code}/{period}` | 下载 |
| | `GET /api/v1/cloudrecord/video/{operate}/{serial}/{code}/{starttime}/{endtime}/video.mp4` | 播放 |
| | `POST /api/v1/cloudrecord/remove` `/removechannel` `/removedaily` `/setimportant` | 管理 |
| **实时录像** | `POST /api/v1/record/start` `/stop`，`GET /api/v1/record/list` | 平台侧录像 |
| **设备控制** | `POST /api/v1/control/ptz` `/fi` `/preset` `/homeposition` `/navigate` | 云台 |
| | `POST /api/v1/control/dragzoomin` `/dragzoomout` `/ptzprecise` | 云台扩展 |
| | `POST /api/v1/control/wiper` `/filllight` `/teleboot` `/record` `/guard` `/iframe` | 设备动作 |
| | `POST /api/v1/control/alarmnotify` `/alarmreset` | 报警 |
| | `POST /api/v1/control/deviceconfig` `/devicesubscribe` `/talk` | 配置/订阅/喊话 |
| | `POST /api/v1/control/osdconfig` `/snapshotconfig` `/deviceupgrade` `/targettrack` `/formatsdcard` | 2022 扩展 |
| **设备报警** | `GET /api/v1/alarm/list` `/info`，`POST /api/v1/alarm/remove` `/clear` | 报警管理 |
| **级联配置** | `GET /api/v1/cascade/list` `/info` `/statuslog` `/channellist` | 查询 |
| | `POST /api/v1/cascade/save` `/setenable` `/setshareallchannel` `/remove` | 管理 |
| | `POST /api/v1/cascade/savechannels` `/removechannels` `/sharenode` | 共享通道 |
| | `POST /api/v1/cascade/pushcatalog` `/pushchannelcatalog` | 推送目录 |
| **解码上墙** | `POST /api/v1/decode/start` `/stop`，`GET /api/v1/decode/info` | 解码器控制 |
| **用户管理** | `GET /api/v1/user/list` `/info`，`POST /api/v1/user/save` `/remove` `/setenable` `/resetpassword` `/setpassword` | 需 admin 角色 |
| **WebRTC** | `POST /api/v1/webrtc/play?stream=<通道号>` | **浏览器 offer → answer（出画面走这里）** |
| | `GET /api/v1/webrtc/config` | WebRTC 参数 |

#### 云端录像接口补充（对象存储相关）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/cloudrecord/play?recordId=` | 录像点播（拉入 ZLM 转协议，返回 WebRTC 等播放地址） |
| POST | `/api/v1/cloudrecord/stopplay?recordId=` | 停止录像点播 |
| GET | `/api/v1/cloudrecord/download/{serial}/{code}/{period}` | 下载信息（对象存储时为签名/外链地址） |
| GET | `/api/v1/cloudrecord/file/{recordId}` | 本地录像文件流（对象存储归档的录像不走此接口） |

`CloudRecordVo` / `DownloadVo` 中的 `storageType`（`LOCAL` / `OSS`）与 `uploadState`
（`NONE` / `PENDING` / `UPLOADED` / `FAILED`）反映归档状态。

## 配置（application.yml / `--gb28181.*`）

| 配置 | 默认 | 说明 |
|---|---|---|
| `platform-id` | `34020000002000000001` | 平台 20 位编码 |
| `sip-advertised-ip` | 空（自动推断本机非回环 IP） | **写入 INVITE 的 From/Contact**，NAT 或多网卡时必须显式指定 |
| `sip-host/sip-port` | `0.0.0.0` / `5060` | SIP 信令 |
| `sip-password` | 空 | **平台注册密码（系统级统一密码）**。留空=不鉴权；公网部署务必设置。只校验密码，不校验用户名 |
| `media-ip` | 空=自动探测 | **SDP `c=` 行与播放地址主机，必须是设备可达地址** |
| `http-port` | `8081` | 媒体 HTTP（WebSocket-FLV / HTTP-FLV / HLS） |
| `rtmp/rtsp/rtc-port` | `11935/18554/18000` | 各协议端口，`0`=自动选空闲端口，`-1`=关闭 |
| `app` | `rtp` | ZLM 应用名 |
| `auto-record` / `record-root` | `false` / `${user.dir}/record` | 平台录像 |
| `starter.init-schema` | `true` | 启动时执行 `db/schema.sql`（建表 + 注释 + 初始账号，幂等） |
| `starter.self-check` | `false` | 启动后自动点播第一路在线设备 |
| `starter.device-offline-seconds` | `180` | 心跳超时判定离线的秒数 |
| `starter.offline-scan-interval-ms` | `60000` | 离线巡检间隔 |
| `starter.auto-collect` | `true` | **设备上线后自动下发 Catalog/DeviceInfo/DeviceStatus 采集** |
| `starter.collect-interval-seconds` | `300` | 同一设备两次自动采集的最小间隔（防止设备频繁重启被打爆） |
| `starter.collect-device-info` | `true` | 是否采集设备信息（厂商/型号/固件） |
| `starter.collect-device-status` | `true` | 是否采集设备状态 |
| `starter.channel-backfill-interval-ms` | `120000` | 通道兜底补齐间隔 |

### 对象存储（云端录像归档，`gb28181.oss.*`）

采用 **S3 兼容协议**（AWS SDK v2），一套配置覆盖主流对象存储；
**未配置时录像保存在本地磁盘**（`gb28181.record-root`），不影响其他功能。

| 配置 | 默认 | 说明 |
|---|---|---|
| `oss.enabled` | `false` | 是否启用对象存储 |
| `oss.endpoint` | 空 | 服务地址，带 `http(s)://` 前缀（AWS 官方 S3 可留空自动推导） |
| `oss.region` | `us-east-1` | 区域（MinIO 可填任意非空值） |
| `oss.access-key` / `oss.secret-key` | 空 | 访问密钥（必填） |
| `oss.bucket` | 空 | 存储桶（必填） |
| `oss.prefix` | `record` | 对象键前缀，最终键为 `<prefix>/<通道编码>/<文件名>` |
| `oss.path-style` | `false` | **MinIO / Ceph 等自建存储必须设为 `true`** |
| `oss.public-read` | `false` | `false` 生成签名 URL；`true` 直接拼永久外链（需桶已开公共读） |
| `oss.sign-expire-seconds` | `3600` | 签名 URL 有效期 |
| `oss.delete-local-after-upload` | `false` | 上传成功后是否删除本地文件（默认保留便于回放兜底） |
| `oss.connect-timeout-ms` / `read-timeout-ms` | `10000` / `300000` | 连接与读超时（录像文件较大，读超时默认 5 分钟） |

主流对象存储配置示例：

| 对象存储 | endpoint | 关键参数 |
|---|---|---|
| 阿里云 OSS | `https://oss-cn-hangzhou.aliyuncs.com` | `region=cn-hangzhou` |
| 腾讯云 COS | `https://cos.ap-guangzhou.myqcloud.com` | `region=ap-guangzhou` |
| MinIO | `http://192.168.1.10:9000` | `path-style=true`（必须） |
| 七牛云 Kodo | `https://s3-cn-east-1.qiniucs.com` | `region=cn-east-1` |
| AWS S3 | （留空自动推导） | `region=us-east-1` |

**工作方式**：停止云端录像后**异步**上传 MP4（不阻塞接口，等事务提交后再启动），
上传结果回写 `gb_record.storage_type / object_key / file_url / upload_state`；
失败只记日志并置 `FAILED`，本地文件保留。启用对象存储后：

- `gb_record` 元数据**以数据库为准**（历史条目重启不丢），ZLMediaKit 内存索引仅用于「录制中」条目
- 下载接口返回**签名 URL 或公共读外链**，由浏览器直接访问（不再经平台中转）
- 删除录像时同步删除对象存储中的文件

## 数据库

平台数据落 PostgreSQL，MyBatis-Plus 访问。启动时自动执行 `db/schema.sql`。

该脚本是**全新初始化**脚本，假设库是空的（或在全新库上执行），分三段且整体幂等：

| 段 | 作用 |
|---|---|
| 1. 建表 | 全部 `CREATE TABLE / CREATE INDEX IF NOT EXISTS`，含主键与唯一键 |
| 2. 注释 | 全部 `COMMENT ON ...`，给表和字段加中文说明，方便用 DBeaver/Navicat 看库 |
| 3. 初始化数据 | 初始管理员账号（`ON CONFLICT DO NOTHING`） |

**需要重建时先清空库再启动**，脚本会自动建好全部 14 张表：

```sql
DROP SCHEMA public CASCADE;
CREATE SCHEMA public;
-- 或另起一个空库：CREATE DATABASE gb28181;
```

执行方式是「整份脚本一次发送」（PG 驱动支持多语句），远程库上启动只需一次往返；
若整体失败会自动降级为逐条执行，日志里能直接看到是哪条语句出错。

共 14 张表：`gb_device` `gb_channel` `gb_stream` `gb_record` `gb_device_record` `gb_alarm`
`gb_cascade` `gb_cascade_channel` `gb_device_group` `gb_traffic_log` `gb_status_log`
`gb_position_log` `gb_preset` `gb_user`。

初始管理员：`admin` / `admin123`（存储值 = `BCrypt(MD5('admin123'))`）。

由 DBA 管理表结构时，设 `gb28181.starter.init-schema=false` 关闭自动执行。

## 出参对象（`vo` 包）

所有 REST 出参都是 `io.github.rurulalanana.starter.vo` 下的 POJO，不再用 `Map<String,Object>` 拼装：

| 领域 | 类 |
|---|---|
| 通用 | `DeletedVo` `IdVo` `FetchResultVo` `TokenVo` |
| 设备/通道 | `DeviceVo` `DeviceListVo` `ChannelVo` `ChannelListVo` `OnlineStatsVo` `TreeNodeVo` `TreeVo` |
| 直播/回放 | `StreamVo` `StreamListVo` `StreamStopVo` `PlaybackVo` `RelayVo` `RelayStartVo` `RelayListVo` `RelayStopVo` |
| 录像 | `RecordVo` `RecordListVo` `RecordStartVo` `RecordFileListVo` `CloudChannelVo` `CloudChannelListVo` `CloudRecordVo` `CloudDailyVo` `CloudMonthVo` `VideoRangeVo` `DownloadVo` |
| 级联 | `CascadeVo` `CascadeListVo` `CascadeChannelVo` `CascadeChannelListVo` |
| 其它 | `AlarmVo` `AlarmListVo` `UserVo` `UserListVo` `UserInfoVo` `ServerInfoVo` `StatusLogVo` `StatusLogListVo` `PositionLogVo` `PositionLogListVo` `StreamLogVo` `StreamLogItemVo` `SetPasswordVo` `TalkVo` `SnapshotConfigVo` `DeviceUpgradeVo` `DecodeStartVo` `DecodeInfoVo` `ChannelNavigateVo` `CruiseGroupVo` `CruisePointVo` `WebrtcPlayVo` `WebrtcConfigVo` |
| 原生 `/api/*` | `NativeStatusVo` `NativeDeviceVo` `NativeSessionVo` `NativeSessionItemVo` `NativePlayUrlsVo` `NativeRecordVo` `NativeRecordingVo` `NativeRecordingPlayVo` `NativeWebrtcVo` `NativeWebrtcConfigVo` `NativeOkVo` |

约定：字段名小驼峰；`@JsonInclude(NON_NULL)` 省略空值；集合字段用对应的 `XxxVo` 泛型而不是 `Map`。
少量 `Map` 仍用于「键为动态值」的场景（如 `cloudrecord/queryflags` 的 `YYYYMM → 0/1 串`）。

## 两个关键实现说明

### 1. SIP 注册鉴权（平台密码）

设置 `gb28181.sip-password` 后，设备 REGISTER 需通过 Digest 鉴权：

```
设备 → REGISTER（无凭据）      → 平台回 401 + WWW-Authenticate: Digest realm="…", nonce="…", algorithm=MD5
设备 → REGISTER（带 Digest）   → 密码正确回 200；错误回 403
```

**只校验密码，不校验用户名。** SIP 的 `Authorization.username` 只是查密码用的键，
各厂商填法不一（SIP 域 / 平台编码 / 设备编号），强制校验会让大量设备注册失败。
所以这里只提供**系统级统一密码**，所有设备共用。

**只有 REGISTER 被鉴权**，心跳与 MESSAGE（目录、INVITE 等）不鉴权——这是 GB28181 的标准行为。

> 想按设备区分密码做不到：鉴权时拿到的 username 不保证是设备编号，无法可靠映射。
> 内网部署可以留空不鉴权，靠网络隔离；公网务必设置统一密码。

### 2. 设备数据自动入库

设备 REGISTER 只告诉平台「我在线」。平台在注册（以及心跳自愈）后自动下发一轮查询：

```
Catalog      → 通道目录，落 gb_channel（最重要）
DeviceInfo   → 厂商/型号/固件，落 gb_device
DeviceStatus → 工作状态
```

应答通过 MESSAGE 异步回来，由 `SipEventBridge` 解析落库，因此接口返回后 1~3 秒
再查 `/api/v1/device/channellist` 才能看到数据。想手工重采就调 `/api/v1/device/collect`。

**通道兜底**：不少 IPC/NVR 的 Catalog 应答不含子通道（`SumNum=0`），
此时平台会把**设备自身登记为一条通道**（设备编号即通道编号，可直接点播），
否则前端通道列表永远是空的。

### 3. SIP 注册表自愈（平台重启后设备仍可点播）

`gb28181-sip` 的设备注册表是**纯内存**的，而国标设备不会因为平台重启就重新 REGISTER
（心跳周期通常 60 秒、注册有效期 3600 秒）。重启后会出现很别扭的状态：

- 心跳照常到达，日志里设备显示在线 ✅
- 但注册表里没有该设备，所有下行信令（Catalog / DeviceInfo / INVITE / PTZ）
  全部报「设备未注册或会话已失效」❌

`SipRegistryHealer` 补上了这个缺口：心跳到达而注册表无该设备时，
按心跳携带的地址重建会话，随后自动触发一次数据采集。

## 排障

### 设置了 sip-password 后设备注册不上

设备侧也要配同样的密码。平台日志会给出明确原因：

```
注册认证失败: device=34020000001320000001, user=34020000001320000001
```

出现这条说明密码不匹配（设备用错密码），或者设备压根没响应 401 挑战。
注意 `user=` 后面是设备填的用户名，**平台不校验它**，只用来查密码，不用管它填什么。

### 设备在线但所有操作都报「设备未注册或会话已失效」

这是平台重启后的典型现象（注册表内存态丢失）。正常情况下 `SipRegistryHealer`
会在下一个心跳（≤60 秒）自动修复，日志里能看到：

```
注册表自愈：设备 34020000001310000001 未在注册表中（平台重启后常见），已按心跳地址 … 重建会话
```

若长时间没有这条日志，检查设备心跳是否真的到达（`logging.level.io.github.rurulalanana.gb28181.sip: DEBUG`）。

### 登录返回「用户名或密码错误」但密码确实是 admin123

`gb_user.password` 存的是 `BCrypt(MD5(明文))`，不是 `BCrypt(明文)`。
直接用 `BCrypt(明文)` 写库会校验失败。生成方法：

```java
String md5 = md5Hex("admin123");
String stored = new BCryptPasswordEncoder().encode(md5);
```

### `NoClassDefFoundError: com/aizuda/zlm4j/core/ZLMApi`

`zlm4j` 是 `gb28181-sip-zlm` 的传递依赖，未出现在 classpath。**绝大多数情况是本地 Maven 缓存了"该构件不存在"的结论**——版本刚发布时国内镜像（阿里云等）有几分钟同步延迟，Maven 把 404 记进 `~/.m2/repository/**/*.lastUpdated` 后就不再重试。

按顺序排查：

```bash
# 1) 先确认依赖树里有没有 zlm4j
mvn dependency:tree | grep -E 'zlm4j|gb28181'

# 2) 强制更新缓存里的 404 记录（最常见的解法）
mvn -U clean install

# 3) 还不行，清掉这两个坐标的缓存重下
rm -rf ~/.m2/repository/com/aizuda/zlm4j
rm -rf ~/.m2/repository/io/github/rurulalanana
```

若你的项目只在 pom 里声明了 `gb28181-sip-zlm` 而没显式声明 `zlm4j`，而镜像同步还没追上，可临时显式加上（镜像追上后可去掉）：

```xml
<dependency>
  <groupId>com.aizuda</groupId>
  <artifactId>zlm4j</artifactId>
  <version>1.12.0</version>
</dependency>
```

### 真机点播：设备注册成功但一直不出画面

日志特征：设备注册正常、`已发起点播 INVITE`，随后反复出现
`RtpProcess timeout` 与 `SIP 事务超时: server=false`，ZLM 从未收到 RTP。

按这个顺序查：

1. **平台是否通告了设备可达的地址**（最常见）。INVITE 的 `Contact` 是设备回包的依据，
   若为 `0.0.0.0` 或 `127.0.0.1`，设备无法送达就会静默丢弃。设置 `gb28181.sip-advertised-ip`
   为设备能访问到的本机 IP（与 `media-ip` 通常一致），多网卡时尤其重要。
2. **抓包确认**。Wireshark 过滤 `udp.port == <sip-port>`，看 INVITE 的 From/Contact 主机
   是否可达，以及设备是否发出任何应答（100/200/486/603）。
3. **设备侧是否要求 TCP 收流**。本工程默认 UDP（`tcp_mode=0`）；若设备只肯发 TCP，
   需在 `RtpSessionManager` 建 RTP 服务器时改用 TCP 被动模式。
4. **SDP 的 `y=` SSRC**：平台主动点播时应由平台给出 10 位 SSRC，且与收流端口对应。

### 其它启动期报错

| 现象 | 原因与处理 |
|---|---|
| `PeerUnavailableException: SipStackImpl could not be instantiated` | 排除了 log4j。jain-sip 需要 log4j 1.x 的 `Layout` 等 API，`log4j-over-slf4j` 不提供，**不能排除** |
| `UnsatisfiedLinkError` / 加载 `mk_api` 失败 | 运行平台没有对应原生库；zlm4j 1.12.0 内含 win/linux/mac（x86_64 与 arm64），检查是否被裁剪或运行在不支持的平台 |
| 媒体服务启动失败但无堆栈 | ZLM 的 `mk_http_server_start` 等启动失败**返回 0 而不抛异常**，看日志中是否有 ZLM 原生输出 |

## 已知限制（嵌入式 ZLMediaKit）

1. **管理 API 路由未注册，但播放路由可用**：`/index/api/*`（如 `getMediaList`、`getServerConfig`）由 **MediaServer 可执行文件**注册，C API 嵌入模式下不存在。但播放路由由 ZLM 内置 HTTP 服务直接提供：
   - ✅ **WebSocket-FLV**：`ws://<host>:8081/<设备编码>/<通道编码>.live.flv`（前端默认通路，mpegts.js 播放）
   - ✅ **HTTP-FLV / HLS**：同路径（`.live.flv` / `/hls.m3u8`）
   - ✅ **WebRTC**：`POST /api/v1/webrtc/play` 交换 SDP，延迟最低
   - ❌ `/index/api/*` 管理接口返回 404（流状态改用 MK_EVENTS 回调）
2. **RTSP/RTMP 回拉在嵌入模式下报 `no such stream`**：源已注册（日志可见 `媒体注册:rtsp://…`），但播放器侧查询不到。若生产需要 RTSP/RTMP 拉流，建议改为**独立部署 MediaServer 进程** + 本工程通过其 REST API 驱动（WVP 式架构）。
3. **推流鉴权回调未注册**：注册 `on_mk_media_publish` 会让 ZLM 等待 embedder 调用 auth invoker，而 zlm4j 未映射该函数指针（强行调用会导致 JVM 崩溃），因此不注册，由 ZLM 按默认放行。
4. **jain-sip 是进程级单例**：同一 JVM 不能再起第二个 SIP 服务。要端到端压测或做媒体闭环验证，
   需另起一个进程（不同 JVM）连本平台，不能在同进程内再起一套 SIP。

## 依赖说明

- `gb28181-sip` 依赖 jain-sip，jain-sip 硬依赖 **log4j 1.x API**（含 `org.apache.log4j.Layout`，`log4j-over-slf4j` 不提供），因此**不能排除 log4j**；其日志由 `log4j.properties` 单独收敛，应用日志仍走 Logback。
- `gb28181-sip-zlm` 通过 zlm4j 内嵌 ZLMediaKit，jar 自带 win/linux/mac 各平台原生库。
- 媒体端口配置为 `0` 时表示**自动选择空闲端口**（ZLM 本身把 0 解释为「用默认端口 80/1935/554」，非 root 绑定会失败）。