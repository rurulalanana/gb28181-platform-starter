# 大华 NetSDK 接入评估与验证记录

> 本文所有数字与结论均来自**实测**，不是文档推测。
> 验证环境：`160.202.254.65` / CentOS Stream 8 / glibc 2.28 / Temurin JDK 21.0.6 / x86_64。

## 1. SDK 获取与存放

> **2026-10 起 SDK 已迁到 `sdk-dahua` 仓库**，本仓库不再存放。
> 原生库的裁剪、打包与平台切换见
> `sdk-dahua/docs/原生库打包.md`。
> 本文只保留评估结论与平台侧的接入说明。

两个压缩包（用户提供）：

| 文件 | 大小 |
|---|---|
| `General_NetSDK_ChnEng_JAVA_Linux64_IS_V3.061.0000001.0.R.260826.zip` | 76.09 MB |
| `General_NetSDK_ChnEng_JAVA_Win64_IS_V3.061.0000001.0.R.260826.zip` | 270.36 MB |

**存放位置：`sdk-dahua/sdk/dahua/`，已在 `.gitignore` 中忽略。**

> ⚠️ 本仓库是**公开仓库**。大华 NetSDK 的二进制与源码受厂商许可约束，
> 不得随公开仓库分发，因此 `sdk/` 永不入库，也不要打进公开的 Release 包。
> 新克隆的仓库需要单独获取 SDK 后才能构建大华模块。

## 2. SDK 的实际形态（与直觉不符的三点）

1. **不是 jar，是源码。** 只提供 `.java` + `.so` + 一个 `pom.xml`，
   需要自行编译出 `netsdk` 工件。
2. **是 JNA，不是 JNI。** `NetSDKLib` 是 JNA 接口，依赖 `net.java.dev.jna:jna:5.13.0`。
3. **`netsdk/lib` 依赖 `netsdk/demo`。** `ToolKits.java` 里 `import com.netsdk.demo.module.LoginModule`，
   所以不能只编译 `lib` 包，必须连 demo 一起编译。（只影响编译期，运行时不用 demo。）

包结构（`src/main/java/com/netsdk/`）：

| 包 | 文件数 | 说明 |
|---|---|---|
| `lib` | 3966 | 核心：`NetSDKLib.java`(2.8 MB) + 3127 个 structure + 812 个 enumeration |
| `common` | 9 | 工具 |
| `demo` | 25 | GUI 演示（编译期被 lib 引用，不能删） |

源码合计 7.8 MB，编译后 class 6059 个，**打成的 jar 仅 4.73 MB**。

## 3. 原生库裁剪（关键收益）

原始 `libs/linux64/` 共 9 个库、**186.5 MB**。逐个解析 ELF 依赖后：

| 库 | 大小 | 是否需要 |
|---|---|---|
| `libdhnetsdk.so` | 50.45 MB | ✅ 核心 |
| `libdhconfigsdk.so` | 31.12 MB | ✅ **硬性依赖**（见 §4） |
| `libcrypto.so` | 2.71 MB | ✅ TLS/国密 |
| `libssl.so` | 0.48 MB | ✅ 同上 |
| `libavnetsdk.so` | 29.47 MB | ❌ 仅语音对讲 |
| `libStreamConvertor.so` | 27.95 MB | ❌ Java API 零引用（已 grep 确认） |
| `libImageAlg.so` | 0.06 MB | ❌ `NetSDKLib` 里那行是注释掉的 |
| `libplay.so` | 39.76 MB | ❌ **必须排除**：依赖 `libX11/libGL/libEGL/libasound`，服务器无图形环境 |
| `libRenderEngine.so` | 4.46 MB | ❌ 同上 |

**裁剪后：4 个库，84.8 MB（省下 101.7 MB，54%）。**

### 3.1 兼容性实测

| 项 | 库要求 | 服务器 | 结论 |
|---|---|---|---|
| GLIBC | 最高 `2.7`（`libRenderEngine` 需 2.14） | 2.28 | ✅ |
| GLIBCXX | `libdhnetsdk` 需 `3.4.9` | 3.4.25 | ✅ |
| 架构 | x86-64 | x86_64 | ✅ |

`ldd linux64/libdhnetsdk.so` 全部解析成功，无 `not found`。

## 4. 两个必须知道的坑

### 4.1 `dynamic-lib-load.xml` 是白名单，缺库会**静默失败**

`LibraryLoad.getLoadLibrary()` 会遍历 `dynamic-lib-load.xml` 里当前平台的
全部库逐个提取，**整段在一个 try 里**。原始清单 linux64 下第一项是
`avnetsdk`，如果只带 `dhnetsdk`，第一个库就抛 `FileNotFoundException`
中断循环 → 后面的库一个都不提取 → JNA 拿到不存在的路径。

更隐蔽的是：`getLibraryName()` 用 `DynamicParseUtil.compareLibName()` 以清单
做白名单，**不在清单里的名字会被解析成空串**，最终拼出 `lib.so`。

因此清单必须与随包携带的库**严格对应**。本项目维护了一份裁剪版清单，
放在 `sdk/dahua/build/dynamic-lib-load.xml`（构建时打进 jar 根目录）。

### 4.2 `dhconfigsdk` 不可省略

`NetSDKLib` 的两个静态字段在同一个静态块初始化：

```java
NetSDKLib NETSDK_INSTANCE = Native.load(LibraryLoad.getLoadLibrary("dhnetsdk"), NetSDKLib.class);
NetSDKLib CONFIG_INSTANCE = Native.load(LibraryLoad.getLoadLibrary("dhconfigsdk"), NetSDKLib.class);
```

缺 `dhconfigsdk` 时不会「只用部分功能」，而是**整个 `NetSDKLib` 类初始化失败**，
报 `Unable to load library '.../lib.so'`。

### 4.3 提取逻辑可以绕过

`LibraryLoad` 的做法是把 jar 内 `linux64/*.so` 提取到 `EXTRACT_PATH`
（默认 `java.io.tmpdir`），再交给 JNA。

**不必按它的方式做**：`getLoadLibrary` 最终返回的是
`EXTRACT_PATH/<libName>` 这个**路径字符串**，JNA 只认这个路径。
只要该路径下有可用的库文件，加载就成功 —— 所以把库放在
`extract/` 下（或做符号链接）即可，**避免每次启动复制 84 MB**。

## 5. 已验证的能力

```
java.version  = 21.0.6
os.arch       = amd64
load library: /opt/gb28181/dahua-sdk/extract/libdhnetsdk.so
load library: /opt/gb28181/dahua-sdk/extract/libdhconfigsdk.so
SDK version   = 2283f4a (2.40.63)
CLIENT_Init   = true
RESULT        = OK（原生库加载与初始化成功）
```

## 6. 需求清单与库的对应关系

| 需求 | 需要 NetSDK？ | 依赖库 | 备注 |
|---|---|---|---|
| 周边设备发现 | 是 | dhnetsdk | `CLIENT_StartFind`/`CLIENT_DoFind` |
| 设备主动注册 | 部分 | dhnetsdk | 若设备支持 GB28181，现有 SIP 链路即可 |
| 通道获取 | 否 | — | GB28181 Catalog 已覆盖 |
| 预览 | 否（推荐 RTSP） | dhnetsdk | 两套方案都可行，见 §7 |
| 录像回放 | 否（推荐 RTSP） | dhnetsdk | 同上 |
| 录像下载 | 否（推荐 RTSP） | dhnetsdk | 同上 |
| 云台：基础方向/变倍 | 否 | — | GB28181 `PTZCmd` |
| 云台：预置位 | 否 | — | GB28181，已实测可查 5 个 |
| 云台：巡航组/巡迹/限位/定时任务/重启 | 是 | dhnetsdk/dhconfigsdk | 协议里没有 |
| 抓图（设备直传） | 是 | dhnetsdk | 现有实现走 ffmpeg，而服务器未安装 |
| 语音对讲（单工/双工） | 是 | dhnetsdk + **avnetsdk** | 需加回 29.47 MB |
| 相机图像参数（亮度/对比度/…) | 是 | dhconfigsdk | 已在最小集合内 |
| 报警接收 | 否 | — | GB28181 已接通 |

## 7. 预览/回放建议走 RTSP，而不是 NetSDK

已确认 `zlm4j` 暴露了 `mk_proxy_player_create` / `mk_proxy_player_play`，
可以让嵌入式 ZLM 直接拉一路 RTSP：

```
rtsp://<user>:<pass>@<ip>:554/cam/realmonitor?channel=<N>&subtype=0
```

好处：零 NetSDK 依赖、零 JNI、复用现有 FLV/WS-FLV/HLS 输出与播放器，
也不需要 `libStreamConvertor.so`（PS 解封装由 ZLM 做）。

> 注：嵌入式 ZLM 的 HTTP API（`/index/api/*`）是**关闭**的（实测全部 404），
> 所以必须走 zlm4j 的 JNA 接口，不能走 HTTP。

## 8. 复现步骤

本机已完成的准备（换机器 / 重装系统时需要重做）：

```powershell
# netsdk 已装进本地仓库：D:\maven\repository\com\netsdk\netsdk\3.061\
# 重新安装：
& 'D:\maven\apache-maven-3.6.3\bin\mvn.cmd' install:install-file `
    -Dfile=netsdk-api.jar -DgroupId=com.netsdk -DartifactId=netsdk -Dversion=3.061
```

从零开始的完整流程：

```
# 1) 解包（Linux 版即可，Windows 版仅用于本地调试）
cd sdk/dahua && unzip General_NetSDK_ChnEng_JAVA_Linux64_*.zip -d linux64/

# 2) 编译 netsdk（必须连 demo 一起，否则 ToolLibs 报错）
javac -encoding UTF-8 -cp jna-5.13.0.jar -d build/classes \
      $(find <SDK_ROOT>/src/main/java/com/netsdk -name '*.java')

# 3) 打 jar：class + properties + 裁剪版 dynamic-lib-load.xml
jar cf netsdk-api.jar -C build/jarroot .

# 4) 部署原生库
mkdir -p /opt/gb28181/dahua-sdk/{linux64,extract}
cp <SDK_ROOT>/libs/linux64/{libdhnetsdk,libdhconfigsdk,libcrypto,libssl}.so linux64/
for f in linux64/*.so; do ln -sf "$PWD/$f" "extract/$(basename $f)"; done

# 5) 运行前指定 extract 路径（或在代码里调 LibraryLoad.setExtractPath）
java -cp netsdk-api.jar:jna.jar ... -Dxxx
```

## 9. 已落地的代码结构

模块 `sdk-dahua`（v1.0.0，独立仓库），只暴露**接口**，不暴露大华类型：

```
io.github.rurulalanana.deviceaccess     ← 契约（实现无关，接海康/宇视也用它）
├── DeviceKind               接入类型枚举：GB28181("gb28181") / DAHUA("dahua")
├── ConnectMode              CLIENT 平台连设备 / SERVER 设备注册到平台
├── DeviceContext            连接上下文（deviceId, host, port, user, pass, mode, serial）
├── DeviceAccessProvider     probe / listChannels / liveStreamUri / playbackStreamUri / ptz
├── DeviceRegisterTable      注册表抽象
└── RegisterListener         监听器抽象

io.github.rurulalanana.dahua            ← 大华实现
├── DahuaDeviceAccessProvider  ★ 契约实现（探测/目录/流地址/云台）
├── DahuaRegisterTable       内存表，默认 180s 过期，地址变更打日志
├── DahuaRegisterListener    CLIENT_ListenServer，处理自动注册/序列号/IP 通知/断开
└── internal/                不含业务判断的内部件
    ├── DahuaSdk             SDK 生命周期 + 登录会话（SN / host:port 双索引）
    ├── DahuaNativeLibrary   原生库平台探测与可操作的中文报错
    └── DahuaPtzCommand      方向词 → SDK 指令码（纯逻辑，可单测）
```

**关键设计：平台不依赖大华**

`netsdk` 与 `jna` 在 `sdk-dahua` 里是 **`provided`**，不会传递到 starter。
`DeviceAccessRegistry` 用 `ObjectProvider` **延迟解析** provider ——
因为大华 provider 的静态初始化会加载 `libdhnetsdk.so`，如果构造期注入
`List<DeviceAccessProvider>`，**SDK 没装好会让整个平台起不来**，国标链路一起废掉。

现在的行为：SDK 缺失 → 只有大华设备不可用，国标完全正常。

> **例外：`netsdk` 的 class 由 starter 自己声明 `compile` 依赖**。
> 原生库在 dahua 的 jar 里按 profile 携带，但 Java class 不在 ——
> dahua 里是 `provided` 不传递，而 provider 运行时要调 `com.netsdk.lib.*`。
> 因此 starter 显式声明一份 `com.netsdk:netsdk`，让 fat jar 自包含、单 jar 可部署。
> 两份分工见 `sdk-dahua/docs/原生库打包.md` §5。

### 9.1 平台侧新增的两个端点

| 端点 | 参数 | 作用 |
|---|---|---|
| `POST /api/v1/device/adddahua` | `serial` `host` `port=37777` `username` `password` `name` | 纳管一台 SDK 直连设备 |
| `POST /api/v1/device/refreshsdk` | `serial` | 探测可达性 + 同步通道目录 |

`adddahua` **只写设备行，不立即拉目录** —— 拉目录可能耗时十几秒，
失败也不该回滚"设备已添加"这件事。

### 9.2 复用既有表的做法

`DeviceProvisionService` **不直接写通道表**：把 provider 报的通道转成
`CatalogItem`，交给既有的 `DeviceService.saveCatalog(deviceId, items, true, size, 1)`。
那里已经处理好了新增/更新/幽灵通道清理/设备自身通道。

结果：大华设备与国标设备**在库里长得完全一样**，前端通道列表、点播、云台、
录像接口**一行都不用改**。

设备表新增一列 `username`（`schema.sql` 里含 `ALTER ... IF NOT EXISTS` 迁移）：
国标 Digest 只校验密码，所以这列一直是空的；SDK 登录两个都必填。
`source` 字段复用既有值：`gb28181`（或历史值 `device`）/ `dahua`。

### 9.3 云台命令路由

`ControlService.sendPtz()` 按设备 `source` 分流：

| 设备类型 | 路径 |
|---|---|
| 国标 | 生成 GB28181 十六进制 PTZ 指令，走 SIP MESSAGE |
| 大华 | `provider.ptz(ctx, channelCode, direction, 129)` |

`ptzCmdToDirection(hex)` 读十六进制第 6~8 位，映射到
`ZOOM_IN/ZOOM_OUT/UP/DOWN/LEFT/RIGHT`，组合成 `"upright"` 这样的词，
`0x00` 映射为 `"stop"`。速度固定 129（大华 SDK 的中间值）。

**当前限制**：非云台类控制命令（如预置位、聚焦、光圈、巡航）走 SDK 时会抛
`该设备通过 X 接入，此控制命令暂未适配`，不做静默失败。

### 9.4 配置前缀为什么是 `dahua.*` 而不是 `gb28181.dahua.*`

大华 SDK 直连是**与国标并列的第二条接入通道**，不是国标的一部分 ——
它不经过 SIP、不用 MANSCDP、不需要平台 20 位编码。
挂在 `gb28181` 前缀下会暗示「这是国标的一个子项」，语义上是错的，
也会让人误以为改它会影响国标链路。

同理，将来接海康、宇视时应当是并列的 `hikvision.*` / `uniview.*`。

分开还有个实际好处：`Gb28181Properties` 已有 300 多行、20 多个配置项，
把厂商私有参数混进去会让「哪些是国标标准、哪些是厂商扩展」彻底看不清。

| 配置 | 含义 |
|---|---|
| `dahua.enabled` | 是否启用大华接入（默认 true；原生库缺失本来就会自动降级） |
| `dahua.rtsp-port` | 设备 RTSP 端口，默认 554 |
| `dahua.channel-total` | 设备通道总数；0 = 用登录时上报的 `byChanNum`（推荐） |
| `dahua.server-mode.enabled` | 是否开启主动注册监听，**默认 false** |
| `dahua.server-mode.bind-ip` / `port` | 监听地址与端口，默认 `0.0.0.0:9500` |
| `dahua.server-mode.expire-seconds` | 注册记录失效阈值，默认 180 |

## 10. 尚未解决

- **无大华真机**：已验证「原生库从 jar 解压 → `CLIENT_Init` 成功 →
  SDK 版本 2.40.63」，以及不可达设备的降级（1.5 秒返回、不抛异常）。
  但登录、目录、云台、抓图全部**未联调**。
  厂商私有协议细节必须以真机为准 —— 本项目已多次踩到「文档写了但设备不这么做」
  （npt 定位、对话框 tag、时间格式）。
- **许可条款**：需确认大华允许的分发形式（是否必须随硬件绑定、
  能否在客户现场部署时下发），这决定后续交付方式。
- **对讲是否真需要 avnetsdk**：需真机验证；不需要的话可再省 29.47 MB。
- ~~**`DeviceAccessTest.java` 已丢失**~~：已在 `sdk-dahua/src/test/` 补齐，
  26 个单测覆盖 `DeviceKind.of` 兜底、`ConnectMode.of` 别名、
  `DeviceContext.connectable` 两套判据、云台方向映射、通道号反解、流地址生成。
- ~~**SERVER 模式未接线**~~：已由 `DahuaConfiguration` 接线，
  配置项 `dahua.server-mode.enabled=true` 开启（默认关闭）。
- **预览/回放走 RTSP 未接线**：见 §7，`liveStreamUri()` / `playbackStreamUri()`
  已实现并验证输出格式，但尚未接入 `zlm4j` 的 proxy player 真正拉流。
