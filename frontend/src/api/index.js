import http, { get, post } from './http'

/**
 * 系统与用户接口（`/api/v1/*`）。
 */
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
  remove: (serial) => post('/device/remove', { serial }),
  setName: (params) => post('/device/setname', params),
  setInfo: (params) => post('/device/setinfo', params),
  /** 切换流传输模式（UDP/TCP，仅影响后续点播）。 */
  setMediaTransport: (serial, mediaTransport, mode = 'passive') =>
    post('/device/setmediatransport', { serial, media_transport: mediaTransport, media_transport_mode: mode }),
  setChannelName: (params) => post('/device/setchannelname', params),
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
  /**
   * 服务端抓拍：拉流 + ffmpeg 解码，返回快照地址。
   *
   * <p>超时给到 60 秒：级联设备收到 INVITE 后可能几十秒才开始推流，
   * 服务端要等流上线才能解码。
   */
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
  setPassword: (params) => post('/user/setpassword', params)
}

/** WebRTC 接口（`/api/v1/webrtc/*`，免登录）。 */
export const webrtcApi = {
  /**
   * 交换 SDP。
   *
   * 后端是 `@RequestBody String offerSdp`，期望 body 就是**纯文本 SDP**。
   * axios 对字符串 body 默认发 `application/x-www-form-urlencoded`，
   * 且会做表单转义（SDP 里的 `=`、`\r\n` 会被破坏），因此必须显式指定
   * `Content-Type: application/sdp` 并禁止 axios 做任何变换。
   */
  play: (stream, peerId, offerSdp) =>
    http.post('/webrtc/play', offerSdp, {
      params: { stream, peerId },
      headers: { 'Content-Type': 'application/sdp' },
      transformRequest: [(data) => data]
    }),
  config: () => get('/webrtc/config')
}
