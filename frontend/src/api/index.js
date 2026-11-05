import http, { get, post } from './http'

/**
 * 行为验证码（/api/v1/captcha/*，tianai-captcha）。
 *
 * <p>走项目统一信封，拦截器已拆出 data，这里直接拿到图片对象 / {@code {id}}。
 */
export const captchaApi = {
  /** 本次部署是否要验证码（运维关掉开关后前端就不再弹滑块）。 */
  required: () => get('/captcha/required'),
  gen: (type = 'SLIDER') => post('/captcha/gen', { type }),
  check: (payload) => post('/captcha/check', null, payload)
}

/** 系统与用户接口（/api/v1/*）。 */
export const systemApi = {
  login: (payload) => post('/login', null, payload),
  logout: () => get('/logout'),
  userinfo: () => get('/userinfo'),
  serverinfo: () => get('/getserverinfo'),
  modifyPassword: (payload) => post('/modifypassword', null, payload),
  /** 远程重启（force=true 确认进程有守护会自动拉起）。 */
  restart: (force = true) => post('/restart', { force })
}

/** 系统运行指标接口（`/api/v1/system/*`，概览页轮询）。 */
export const metricsApi = {
  get: () => get('/system/metrics')
}

/** 运行时配置（/api/v1/config/*）：保存即生效，数据库优先、yml 兜底，reset 回落 yml。 */
export const configApi = {
  list: () => get('/config/list'),
  save: (key, value) => post('/config/save', { key, value }),
  reset: (key) => post('/config/reset', { key })
}

/**
 * 接入控制（/api/v1/acl/*）：保存即用不可变规则快照整体替换。
 * 判定先黑后白；白名单为空=不限制来源。
 */
export const aclApi = {
  list: (params) => get('/acl/list', params),
  add: (params) => post('/acl/add', params),
  remove: (id) => post('/acl/remove', { id })
}

/**
 * HTTPS 证书（/api/v1/tls/*）：TLS 在 nginx 终结。install = 校验 → 落盘 → 生成片段 → nginx -t → reload；
 * 校验不过不 reload。私钥只单向写入，任何接口都不读回。
 */
export const tlsApi = {
  status: () => get('/tls/status'),
  // 第三参是 body：PEM 内容动辄几 KB，绝不能塞进 query string
  install: (cert, key) => post('/tls/install', null, { cert, key }, 60000),
  // 改完端口/自动 reload 开关后调它：只重写 nginx 配置，不需要重传私钥
  regenerate: () => post('/tls/regenerate', undefined, undefined, 60000),
  disable: () => post('/tls/disable'),
  gmReadiness: () => get('/tls/gm-readiness'),
  // 走公网 IP 建 TCP 连接：云环境下会命中安全组，能判断端口是否真的放通
  probe: (port) => get('/tls/probe', port ? { port } : undefined)
}

/** 设备与通道接口（`/api/v1/device/*`）。 */
export const deviceApi = {
  list: (params) => get('/device/list', params),
  info: (serial) => get('/device/info', { serial }),
  onlineStats: () => get('/device/onlinestats'),
  channelList: (params) => get('/device/channellist', params),
  channelInfo: (serial, code) => get('/device/channelinfo', { serial, code }),
  channelTree: (params) => get('/device/channeltree', params),
  groupTree: (params) => get('/device/grouptree', params),
  collect: (serial) => post('/device/collect', { serial }),
  fetchCatalog: (params) => post('/device/fetchcatalog', params),
  fetchInfo: (params) => post('/device/fetchinfo', params),
  fetchStatus: (params) => post('/device/fetchstatus', params),
  /** 向设备下发预置位查询（异步；结果用 presetList 取）。 */
  fetchPreset: (params) => post('/device/fetchpreset', params),
  /** 保存预置位自定义名称（备注）；name 传空表示清除。 */
  setPresetName: (params) => post('/device/setpresetname', params),
  /** 读预置位列表；queriedAt 为 null 表示设备还没应答。 */
  presetList: (serial, code) => get('/device/presetlist', { serial, code }),
  remove: (serial) => post('/device/remove', { serial }),
  /**
   * 纳管一台 SDK 直连设备（大华 NetSDK）。
   *
   * <p>与国标设备的差别：国标由设备 REGISTER 推上来，平台从不主动建；
   * SDK 设备不会注册，必须录入地址与凭据。纳管后就是普通 gb_device 行
   * （source=dahua），通道/点播/云台/录像接口照旧可用。
   */
  addDahua: (params) => post('/device/adddahua', params),
  /**
   * 局域网搜索大华设备（SDK 广播发现）。
   *
   * <p>只在同一广播域有效，跨三层收不到应答——这是协议决定的，不是平台缺陷。
   * 结果不落库：发现到设备后仍需用户填账号密码，由 {@link addDahua} 完成纳管。
   */
  discover: (params) => get('/device/discover', params),
  /** 探测 SDK 直连设备并同步通道目录（同步返回，不像国标要等 Catalog 应答）。 */
  refreshSdk: (serial) => post('/device/refreshsdk', { serial }),
  setName: (params) => post('/device/setname', params),
  setInfo: (params) => post('/device/setinfo', params),
  /** 切换流传输模式（UDP/TCP，仅影响后续点播）。 */
  setMediaTransport: (serial, mediaTransport, mode = 'passive') =>
    post('/device/setmediatransport', { serial, media_transport: mediaTransport, media_transport_mode: mode }),
  setChannelName: (params) => post('/device/setchannelname', params),
  /** 自定义通道国标编号（只改 custom_id，不动 channel_id）。 */
  setChannelId: (params) => post('/device/setchannelid', params),
  setChannelPtzType: (params) => post('/device/setchannelptztype', params),
  setChannelPosition: (params) => post('/device/setchannelposition', params),
  setChannelOnDemand: (params) => post('/device/setchannelondemand', params),
  setChannelCloudRecord: (params) => post('/device/setchannelcloudrecord', params),
  statusLog: (params) => get('/device/statuslog', params),
  streamLog: (params) => get('/device/streamlog', params),
  positionLog: (params) => get('/device/positionlog', params)
}

/** 直播流接口（`/api/v1/stream/*`）。 */
export const streamApi = {
  start: (payload) => post('/stream/start', null, payload),
  stop: (params) => post('/stream/stop', params),
  list: (params) => get('/stream/list', params),
  info: (streamid, touch) => get('/stream/info', { streamid, touch }),
  relayStart: (params) => post('/stream/relay/start', params),
  relayList: (params) => get('/stream/relay/list', params),
  relayStop: (params) => post('/stream/relay/stop', params)
}

/** 设备录像（前端录像）接口（`/api/v1/playback/*`）。 */
export const playbackApi = {
  recordList: (params) => get('/playback/recordlist', params),
  start: (payload) => post('/playback/start', null, payload),
  stop: (params) => post('/playback/stop', params),
  control: (params) => post('/playback/control', params),
  /** 录像下载：把回放流录成本地 MP4（三步 start → progress → finish）。 */
  downloadStart: (params) => post('/playback/download/start', params),
  downloadProgress: (params) => get('/playback/download/progress', params),
  downloadFinish: (params) => post('/playback/download/finish', params),
  downloadCancel: (params) => post('/playback/download/cancel', params),
  downloadList: () => get('/playback/download/list'),
  downloadRemove: (params) => post('/playback/download/remove', params),
  streamList: (params) => get('/playback/streamlist', params),
  streamInfo: (streamid) => get('/playback/streaminfo', { streamid })
}

/** 云端录像接口（`/api/v1/cloudrecord/*`）。 */
export const cloudRecordApi = {
  queryChannels: (params) => get('/cloudrecord/querychannels', params),
  queryFlags: (params) => get('/cloudrecord/queryflags', params),
  queryMonthly: (params) => get('/cloudrecord/querymonthly', params),
  queryDaily: (params) => get('/cloudrecord/querydaily', params),
  play: (recordId) => post('/cloudrecord/play', { recordId }),
  stopPlay: (recordId) => post('/cloudrecord/stopplay', { recordId }),
  remove: (params) => post('/cloudrecord/remove', params),
  removeChannel: (params) => post('/cloudrecord/removechannel', params),
  removeDaily: (params) => post('/cloudrecord/removedaily', params),
  setImportant: (params) => post('/cloudrecord/setimportant', params),
  share: (params) => post('/cloudrecord/share', params),
  // 服务端抓拍（60s：级联设备可能几十秒才推流）
  snapGrab: (params) => post('/cloudrecord/snap/grab', params, undefined, 60000),
  download: (serial, code, period) => get(`/cloudrecord/download/${serial}/${code}/${period}`),
  video: (operate, serial, code, starttime, endtime) =>
    get(`/cloudrecord/video/${operate}/${serial}/${code}/${starttime}/${endtime}/video.mp4`),
  // 录像计划（对齐 LiveGBS 的 /cloudrecord/plan/*）
  planList: (params) => get('/cloudrecord/plan/list', params),
  planSave: (params) => post('/cloudrecord/plan/save', params),
  planEnable: (params) => post('/cloudrecord/plan/enable', params),
  planRemove: (params) => post('/cloudrecord/plan/remove', params),
  planChannelList: (params) => get('/cloudrecord/plan/channellist', params),
  planSetChannels: (params) => post('/cloudrecord/plan/setchannels', params)
}

/** 平台实时录像接口（`/api/v1/record/*`）。 */
export const recordApi = {
  start: (params) => post('/record/start', params),
  stop: (params) => post('/record/stop', params),
  list: (params) => get('/record/list', params)
}

/** 设备控制接口（`/api/v1/control/*`）。 */
export const controlApi = {
  /**
   * 该设备支持哪些控制能力（`/device/capabilities`）。
   *
   * <p>渲染按钮前先取一次，用 `capabilities.supports(name)` 决定是否置灰：
   * 不支持的按钮要**可见但禁用并显示原因**，而不是直接隐藏 ——
   * 隐藏会让用户以为找错地方，看不到原因也无从反馈。
   */
  capabilities: (serial) => get('/device/capabilities', { serial }),
  ptz: (params) => post('/control/ptz', params),
  fi: (params) => post('/control/fi', params),
  ptzPrecise: (params) => post('/control/ptzprecise', params),
  dragZoomIn: (params) => post('/control/dragzoomin', params),
  dragZoomOut: (params) => post('/control/dragzoomout', params),
  preset: (params) => post('/control/preset', params),
  homePosition: (params) => post('/control/homeposition', params),
  navigate: (params) => post('/control/navigate', params),
  wiper: (params) => post('/control/wiper', params),
  fillLight: (params) => post('/control/filllight', params),
  teleBoot: (params) => post('/control/teleboot', params),
  record: (params) => post('/control/record', params),
  guard: (params) => post('/control/guard', params),
  iframe: (params) => post('/control/iframe', params),
  formatSdCard: (params) => post('/control/formatsdcard', params),
  alarmNotify: (params) => post('/control/alarmnotify', params),
  alarmReset: (params) => post('/control/alarmreset', params),
  deviceConfig: (params) => post('/control/deviceconfig', params),
  deviceSubscribe: (params) => post('/control/devicesubscribe', params),
  talk: (params) => post('/control/talk', params),
  osdConfig: (params) => post('/control/osdconfig', params),
  snapshotConfig: (params) => post('/control/snapshotconfig', params),
  deviceUpgrade: (params) => post('/control/deviceupgrade', params),
  targetTrack: (params) => post('/control/targettrack', params)
}

/**
 * 大华 SDK 直连设备的扩展能力（`/api/v1/dahua/*`）。
 *
 * <p>相机参数走「能力位 → 动态表单」：前端不硬编码参数名，
 * 按 {@code capabilities} 返回的 group/type/options 渲染控件。
 */
export const dahuaApi = {
  /** 参数能力位：每项带 group / type / options / min / max / available / value。 */
  cameraCapabilities: (params) => get('/dahua/cameraconfig/capabilities', params),
  /** 读全部参数当前值（key → value）。 */
  cameraRead: (params) => get('/dahua/cameraconfig', params),
  /** 写参数：body 是 `{brightness: 60, viewMode: 1}` 这样的键值对。 */
  cameraWrite: (params, values) => post('/dahua/cameraconfig', params, values),
  /** 设备录像计划。 */
  recordPlan: (params) => get('/dahua/record/plan', params),
  setRecordPlan: (params) => post('/dahua/record/plan', params),

  // ---------- 云台扩展：动作（SDK）与配置（设备 CGI）是两套入口 ----------

  /**
   * 云台动作。
   *
   * <p>`action` 取值：
   * cruise-add / cruise-remove / cruise-run、
   * pattern-record / pattern-record-stop / pattern-run / pattern-delete、
   * limit-left / limit-right（线扫左右边界，SDK）、
   * scan-limit-left / scan-limit-right（线扫左右边界，RPC2）、
   * limit-up / limit-down（旋转限制上下限位，RPC2）、
   * line-scan、pan-cruise、reboot。
   */
  ptzAction: (params) => post('/dahua/ptz/action', params),
  /** 云台配置快照：巡航组 / 线扫 / 巡迹 / 速度 / 空闲动作 / 定时任务 / 限位 / 当前运行。 */
  ptzConfig: (params) => get('/dahua/ptz/config', params),
  /** 保存一条巡航线（整条覆盖，body: {route, name, enable, points:[{preset,stay,speed}]}）。 */
  saveTour: (params, tour) => post('/dahua/ptz/tour', params, tour),
  /** 保存云台速度（body: {ptzSpeed, patternSpeed}，取值 0~4）。 */
  savePtzSpeed: (params, speed) => post('/dahua/ptz/speed', params, speed),
  // 当前运行状态（只读 PtzMovement 一项），给「执行」开关轮询用
  ptzRunning: (params) => get('/dahua/ptz/running', params),
  // 水平旋转速度（设备 AutoPan.PanSpeed，1~8）——与上面的 PtzSpeed 是两个独立字段
  saveAutoPan: (params, cfg) => post('/dahua/ptz/autopan', params, cfg),
  /** 保存线扫参数（body: {scanId, speed}，速度 0~7）。 */
  saveScan: (params, body) => post('/dahua/ptz/scan', params, body),
  /** 保存巡迹名称（body: {patternId, name}）。设备没有巡迹停留时间字段。 */
  savePattern: (params, body) => post('/dahua/ptz/pattern', params, body),
  /** 保存旋转限制开关（body: {enable, horizontalEnable}）。 */
  saveLimit: (params, body) => post('/dahua/ptz/limit', params, body),
  /** 保存空闲动作（IdleMotion，body: {enable, function, timerMinutes, presetId/tourId/patternId/scanId}）。 */
  saveIdle: (params, body) => post('/dahua/ptz/idle', params, body),
  /** 保存定时任务（PtzAutoMovement，body 见后端 AutoMovementRequest）。 */
  saveAutoMovement: (params, body) => post('/dahua/ptz/automovement', params, body),
  // 开机动作（/dahua/ptz/poweron）已随后端一并移除：设备没有「上电动作」配置项。

  // ---------- 喊话 / 双工对讲 ----------

  /**
   * 开始对讲。返回 `{talkId, sampleRate, channels, format, wsUrl, owner}`。
   *
   * <p>采样率/格式必须按返回的实现，别硬编码：大华对讲固定 8 kHz/16 bit 单声道。
   */
  talkStart: (params) => post('/dahua/talk/start', params),
  /**
   * 送一帧裸 PCM（8 kHz/16 bit/单声道）。
   *
   * <p>body 直接是 ArrayBuffer：用 octet-stream 而不是 JSON/base64 ——
   * 喊话每秒约 16 KB，base64 会凭空多 33%，且每次都要编解码。
   */
  talkSend: (talkId, arrayBuffer) =>
    http.post('/dahua/talk/send', arrayBuffer, {
      params: { talkId },
      headers: { 'Content-Type': 'application/octet-stream' },
      timeout: 5000
    }),
  talkStop: (talkId) => post('/dahua/talk/stop', { talkId }),
  /** 设备当前是否被占用（`{busy, owner}`），进面板时查一次用于禁用按钮。 */
  talkBusy: (params) => get('/dahua/talk/busy', params),

  // ---------- 主动注册：设备连上来了，但缺账号密码 ----------

  /** 注册表里的设备（`managed=false` 的才需要用户补凭据）。 */
  registerList: () => get('/dahua/register/list'),
  /** 用用户输入的账号密码纳管一台注册设备。 */
  adoptRegister: (params) => post('/dahua/register/adopt', params),
  /** 从注册表移除（不纳管，设备再注册时还会出现）。 */
  ignoreRegister: (params) => post('/dahua/register/ignore', params)
}

/** 报警接口（`/api/v1/alarm/*`）。 */
export const alarmApi = {
  list: (params) => get('/alarm/list', params),
  info: (id) => get('/alarm/info', { id }),
  remove: (id) => post('/alarm/remove', { id }),
  clear: () => post('/alarm/clear')
}

/** 操作日志接口（`/api/v1/oplog/*`）。 */
export const opLogApi = {
  list: (params) => get('/oplog/list', params),
  clear: () => post('/oplog/clear')
}

/**
 * 菜单管理（/api/v1/menu/*）：config 登录可读；写入需 menu:write；sync 幂等补齐种子。
 */
export const menuApi = {
  config: () => get('/menu/config'),
  /** 补写缺失的目录/菜单行；版本一致时是空操作，返回 `{deleted: 新增行数}`。 */
  sync: (payload) => post('/menu/sync', null, payload, 20000),
  /** 新增或修改一个节点：menu_key 为空 = 新增。 */
  save: (payload) => post('/menu/save', null, payload),
  /** 删除一个节点；cascade=true 连同全部下级。 */
  remove: (menuKey, cascade = false) =>
    post('/menu/delete', { menu_key: menuKey, cascade: cascade ? 'true' : 'false' })
}

/** 角色与权限点（/api/v1/role/*）：权限点目录来自后端常量，新增只改后端一处。 */
export const roleApi = {
  list: () => get('/role/list'),
  permissions: () => get('/role/permissions'),
  save: (params) => post('/role/save', params),
  remove: (code) => post('/role/remove', { code }),
  setEnable: (params) => post('/role/setenable', params)
}

/** 级联接口（`/api/v1/cascade/*`）。 */
export const cascadeApi = {
  list: (params) => get('/cascade/list', params),
  info: (id) => get('/cascade/info', { id }),
  save: (payload) => post('/cascade/save', null, payload),
  setEnable: (params) => post('/cascade/setenable', params),
  remove: (id) => post('/cascade/remove', { id }),
  channelList: (params) => get('/cascade/channellist', params),
  saveChannels: (params) => post('/cascade/savechannels', params),
  removeChannels: (params) => post('/cascade/removechannels', params),
  /** 共享全部通道开关。参数名是 shareallchannel（全小写无下划线），别写错。 */
  setShareAllChannel: (params) => post('/cascade/setshareallchannel', params),
  pushCatalog: (params) => post('/cascade/pushcatalog', params)
}

/** 用户管理接口（`/api/v1/user/*`，需 admin）。 */
export const userApi = {
  list: (params) => get('/user/list', params),
  info: (id) => get('/user/info', { id }),
  save: (params) => post('/user/save', params),
  remove: (id) => post('/user/remove', { id }),
  setEnable: (params) => post('/user/setenable', params),
  resetPassword: (params) => post('/user/resetpassword', params),
  setPassword: (params) => post('/user/setpassword', params),
  // API 密钥：自助管理，不需要 user:write。generate 返回明文，仅此一次
  apiKey: () => get('/user/apikey'),
  generateApiKey: () => post('/user/apikey/generate'),
  revokeApiKey: () => post('/user/apikey/revoke')
}

/** WebRTC 接口（`/api/v1/webrtc/*`，免登录）。 */
export const webrtcApi = {
  /**
   * 交换 SDP。后端是 @RequestBody String，期望 body 是纯文本 SDP；
   * 必须显式 application/sdp 并禁止 axios 表单转义（否则 = 与 \r\n 会被破坏）。
   */
  play: (stream, peerId, offerSdp) =>
    http.post('/webrtc/play', offerSdp, {
      params: { stream, peerId },
      headers: { 'Content-Type': 'application/sdp' },
      transformRequest: [(data) => data]
    }),
  config: () => get('/webrtc/config')
}
