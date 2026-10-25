<template>
  <!--
    系统设置：左侧分区导航 + 右侧内容区。

    <p><b>为什么改成这种结构</b>：原先是四张卡片竖着堆，每张卡内部又各用一套
    版式（el-descriptions / el-table / hint 网格），视觉跳且信息密度低；
    说明文字全部平铺在正文里，滚动一屏全是灰字，真正要改的配置项反而找不着。

    <p>现在：左侧分区固定不随内容滚动，右侧一次只呈现一个主题；
    所有解释性文字收进问号 tooltip（{@code HelpTip}），正文只留「标签 + 值 + 操作」。
  -->
  <div class="settings">
    <!-- ==================== 左侧分区导航 ==================== -->
    <aside class="settings-side">
      <div class="side-head">
        <div class="side-title">系统设置</div>
        <div class="side-sub mono">{{ server.platformId || '—' }}</div>
      </div>

      <nav class="side-nav">
        <button
          v-for="s in SECTIONS"
          :key="s.key"
          type="button"
          class="side-item"
          :class="{ 'is-active': active === s.key }"
          @click="active = s.key"
        >
          <el-icon class="side-icon"><component :is="s.icon" /></el-icon>
          <span class="side-label">{{ s.label }}</span>
          <span v-if="s.key === 'config' && dirtyCount > 0" class="side-badge">
            {{ dirtyCount }}
          </span>
        </button>
      </nav>

      <!-- 底部放接口版本而不是运行时长：后者已在「已运行」读数里，重复没有意义 -->
      <div class="side-foot mono">接口 v{{ server.interfaceVersion || '—' }}</div>
    </aside>

    <!-- ==================== 右侧内容 ==================== -->
    <div class="settings-body">
      <!-- ---------- 运行概览 ---------- -->
      <section v-show="active === 'overview'" class="sec">
        <header class="sec-head">
          <div>
            <h2 class="sec-title">运行概览</h2>
            <p class="sec-sub">服务状态、接入规模与媒体出流端口</p>
          </div>
          <el-button text :icon="Refresh" :loading="loadingServer" @click="loadServer">
            刷新
          </el-button>
        </header>

        <!-- 读数条：设备 / 通道在线情况 / 运行时长 -->
        <div class="kpi-grid">
          <div class="kpi">
            <div class="kpi-label">
              设备
              <HelpTip text="已注册到平台的 GB28181 设备。设备按心跳保活，超过阈值未收到心跳即判离线。" />
            </div>
            <div class="kpi-value">
              {{ server.deviceOnline ?? '—' }}<span class="kpi-unit">/ {{ server.deviceTotal ?? '—' }}</span>
            </div>
            <div class="kpi-foot">在线 / 总数</div>
          </div>
          <div class="kpi">
            <div class="kpi-label">
              通道
              <HelpTip text="设备通过目录查询上报的通道（摄像机）。通道在线状态跟随其所属设备。" />
            </div>
            <div class="kpi-value">
              {{ server.channelOnline ?? '—' }}<span class="kpi-unit">/ {{ server.channelTotal ?? '—' }}</span>
            </div>
            <div class="kpi-foot">在线 / 总数</div>
          </div>
          <div class="kpi">
            <div class="kpi-label">
              已运行
              <HelpTip text="本次进程启动至今的时长。重启后从零开始计时。" />
            </div>
            <div class="kpi-value kpi-value--text">{{ server.runningTime || '—' }}</div>
            <div class="kpi-foot">{{ server.startUpTime || '启动时间未知' }}</div>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">基础信息</span>
          </div>
          <div class="info-list">
            <div v-for="row in basicRows" :key="row.label" class="info-row">
              <span class="info-label">
                {{ row.label }}
                <HelpTip v-if="row.tip" :text="row.tip" />
              </span>
              <span class="info-value mono">
                {{ row.value || '—' }}
                <el-button
                  v-if="row.copy && row.value"
                  text
                  size="small"
                  class="copy-btn"
                  :icon="CopyDocument"
                  @click="copy(row.value)"
                />
              </span>
            </div>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">媒体服务</span>
            <el-tag size="small" effect="plain" type="success">WebSocket-FLV（默认）</el-tag>
          </div>
          <div class="info-list info-list--pairs">
            <div v-for="row in mediaRows" :key="row.label" class="info-row">
              <span class="info-label">
                {{ row.label }}
                <HelpTip :text="row.tip" />
              </span>
              <span class="info-value mono">{{ row.value || '—' }}</span>
            </div>
          </div>
        </div>
      </section>

      <!-- ---------- 服务运维 ---------- -->
      <section v-show="active === 'service'" class="sec">
        <header class="sec-head">
          <div>
            <h2 class="sec-title">服务运维</h2>
            <p class="sec-sub">进程级重启与接口信息</p>
          </div>
        </header>

        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">
              重启服务
              <HelpTip text="优雅停机后由守护进程自动拉起，约 10 秒恢复；登录态已持久化，无需重新登录。若服务是裸 java -jar 启动（无守护进程），重启后不会自动恢复。" />
            </span>
            <el-tag size="small" effect="plain" type="warning">约 10 秒不可用</el-tag>
          </div>
          <div class="action-row">
            <div class="action-desc">
              仅用于应用配置变更后的进程级重启。录像保留、保活间隔等运行期参数
              不需要重启，请到「运行时配置」调整。
            </div>
            <el-button
              type="danger"
              plain
              :icon="RefreshRight"
              :loading="restarting"
              @click="restartServer"
            >
              {{ restarting ? '重启中…' : '重启服务' }}
            </el-button>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">接口信息</span>
          </div>
          <div class="info-list">
            <div v-for="row in apiRows" :key="row.label" class="info-row">
              <span class="info-label">
                {{ row.label }}
                <HelpTip v-if="row.tip" :text="row.tip" />
              </span>
              <span class="info-value mono">{{ row.value || '—' }}</span>
            </div>
          </div>
        </div>
      </section>

      <!-- ---------- 运行时配置 ---------- -->
      <section v-show="active === 'config'" class="sec">
        <header class="sec-head">
          <div>
            <h2 class="sec-title">
              运行时配置
              <HelpTip text="参数保存在数据库中，保存后立即生效且重启后保留。未改过的项使用 application.yml 的值；「恢复默认」会删除库中的覆盖值、回落 yml。端口、路径、数据库连接等影响进程装配的配置不在此处，需改 yml 并重启。" />
            </h2>
            <p class="sec-sub">保存即生效，无需重启</p>
          </div>
          <div class="sec-actions">
            <el-tag v-if="dirtyCount > 0" size="small" effect="plain" type="warning">
              {{ dirtyCount }} 项已自定义
            </el-tag>
            <el-button text :icon="Refresh" :loading="cfgLoading" @click="loadConfig">
              刷新
            </el-button>
          </div>
        </header>

        <div v-for="g in cfgGroups" :key="g.key" class="panel">
          <div class="panel-head">
            <span class="panel-title">{{ g.label }}</span>
            <span class="count-pill">{{ cfgRowsOf(g.key).length }}</span>
          </div>

          <div class="cfg-list">
            <div
              v-for="row in cfgRowsOf(g.key)"
              :key="row.key"
              class="cfg-row"
              :class="{ 'is-overridden': row.overridden }"
            >
              <div class="cfg-name">
                <span class="cfg-label">
                  {{ cfgLabel(row.key) }}
                  <HelpTip :text="row.description" />
                </span>
                <span class="cfg-meta">
                  <!--
                    范围只对数值项有意义；布尔/字符串显示「范围 0 ~ 0」纯属噪音。
                  -->
                  <template v-if="row.type === 'INT' || row.type === 'LONG'">
                    <span class="cfg-range" :class="{ 'is-bad': isInvalid(row) }">
                      范围 {{ fmtNum(row.min) }} ~ {{ fmtNum(row.max) }} {{ cfgUnit(row.key) }}
                    </span>
                    <span class="cfg-sep">·</span>
                  </template>
                  <span class="cfg-default mono">
                    默认 {{ defaultText(row) }}
                  </span>
                </span>
              </div>

              <div class="cfg-field">
                <!--
                  按类型分流渲染：
                  - BOOLEAN → 开关（让人填 "true" 字符串是荒谬的）
                  - 密码项 → 掩码输入
                  - 其余 → 数字/文本输入
                -->
                <el-switch
                  v-if="row.type === 'BOOLEAN'"
                  :model-value="isOn(row)"
                  class="cfg-switch"
                  :loading="cfgSaving === row.key"
                  active-text="开启"
                  inactive-text="关闭"
                  inline-prompt
                  @change="(v) => toggleBool(row, v)"
                />
                <el-input
                  v-else
                  v-model="cfgDraft[row.key]"
                  class="cfg-input"
                  :class="{ 'is-invalid': isInvalid(row) }"
                  :type="isSecret(row.key) ? 'password' : 'text'"
                  :show-password="isSecret(row.key)"
                  :placeholder="row.defaultValue || '留空表示不启用'"
                  @keyup.enter="saveConfig(row)"
                >
                  <template v-if="cfgUnit(row.key)" #append>{{ cfgUnit(row.key) }}</template>
                </el-input>
                <!-- 越界时直接在输入框下方说明，不依赖弹消息 -->
                <div v-if="isInvalid(row)" class="cfg-error">
                  {{ outOfRangeMsg(row) }}
                </div>
              </div>

              <div class="cfg-ops">
                <el-button
                  type="primary"
                  size="small"
                  :disabled="!isDirty(row) || isInvalid(row)"
                  :loading="cfgSaving === row.key"
                  @click="saveConfig(row)"
                >
                  保存
                </el-button>
                <el-button
                  size="small"
                  :disabled="!row.overridden"
                  @click="resetConfig(row)"
                >
                  恢复默认
                </el-button>
              </div>
            </div>
          </div>
        </div>

        <p v-if="!cfgGroups.length && !cfgLoading" class="empty-hint">暂无可调整的运行时配置</p>
      </section>

      <!-- ---------- 对象存储 ---------- -->
      <section v-show="active === 'storage'" class="sec">
        <header class="sec-head">
          <div>
            <h2 class="sec-title">
              对象存储
              <HelpTip text="采用 S3 兼容协议（AWS SDK v2），一套配置覆盖阿里云 OSS、腾讯云 COS、MinIO、七牛 Kodo、AWS S3。需在 application.yml 配好 gb28181.oss.* 并重启；未启用时录像保存在本机磁盘。上传在数据库事务提交后异步触发，失败只记日志并把状态置为「上传失败」，本地文件保留。" />
            </h2>
            <p class="sec-sub">云端录像归档到 S3 兼容存储</p>
          </div>
          <el-tag size="small" effect="plain" type="info">S3 兼容</el-tag>
        </header>

        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">服务商对照</span>
          </div>
          <el-table :data="ossRows" size="small" class="oss-table">
            <el-table-column prop="name" label="服务商" width="140" />
            <el-table-column label="endpoint" min-width="280">
              <template #default="{ row }"><span class="mono">{{ row.endpoint }}</span></template>
            </el-table-column>
            <el-table-column label="关键参数" min-width="190">
              <template #default="{ row }">
                <el-tooltip
                  :content="ossParamDesc(row.noteKey)"
                  placement="top"
                  :disabled="!ossParamDesc(row.noteKey)"
                  popper-class="help-tip-popper"
                >
                  <span class="dotted">{{ row.note }}</span>
                </el-tooltip>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">配置项</span>
          </div>
          <div class="info-list">
            <div v-for="p in ossParams" :key="p.key" class="info-row">
              <span class="info-label mono">{{ p.key }}</span>
              <span class="info-value">{{ p.desc }}</span>
            </div>
          </div>
        </div>
      </section>

      <!-- ---------- 账号安全 ---------- -->
      <section v-show="active === 'security'" class="sec">
        <header class="sec-head">
          <div>
            <h2 class="sec-title">账号安全</h2>
            <p class="sec-sub">修改当前登录账号的密码</p>
          </div>
        </header>

        <div class="panel panel--narrow">
          <div class="panel-head">
            <span class="panel-title">
              修改密码
              <HelpTip text="密码以 BCrypt(MD5(明文)) 存储。修改成功后当前登录态失效，需用新密码重新登录。" />
            </span>
          </div>
          <el-form :model="pwd" label-width="92px" class="pwd-form" @submit.prevent>
            <el-form-item label="当前密码">
              <el-input v-model="pwd.oldpassword" type="password" show-password autocomplete="off" />
            </el-form-item>
            <el-form-item label="新密码">
              <el-input
                v-model="pwd.newpassword"
                type="password"
                show-password
                autocomplete="new-password"
              />
            </el-form-item>
            <el-form-item label="确认新密码">
              <el-input
                v-model="pwd.confirm"
                type="password"
                show-password
                autocomplete="new-password"
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="saving" @click="changePassword">保存</el-button>
              <span class="form-hint">修改后需重新登录</span>
            </el-form-item>
          </el-form>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, markRaw, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Cloudy,
  CopyDocument,
  Lock,
  Monitor,
  Refresh,
  RefreshRight,
  Setting
} from '@element-plus/icons-vue'
import { configApi, systemApi, webrtcApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import HelpTip from '@/components/HelpTip.vue'

const auth = useAuthStore()
const router = useRouter()

/* ==================== 分区导航 ==================== */

/**
 * 分区定义。
 *
 * <p>图标用 {@code markRaw} 包：组件对象一旦被 Vue 转成响应式代理，
 * 渲染时会带一堆无意义的依赖追踪开销（开发环境还会告警）。
 */
const SECTIONS = [
  { key: 'overview', label: '运行概览', icon: markRaw(Monitor) },
  { key: 'service', label: '服务运维', icon: markRaw(RefreshRight) },
  { key: 'config', label: '运行时配置', icon: markRaw(Setting) },
  { key: 'storage', label: '对象存储', icon: markRaw(Cloudy) },
  { key: 'security', label: '账号安全', icon: markRaw(Lock) }
]

/** 当前分区。 */
const active = ref('overview')

/* ==================== 服务器信息 ==================== */

const saving = ref(false)
const restarting = ref(false)
const loadingServer = ref(false)
const pwd = reactive({ oldpassword: '', newpassword: '', confirm: '' })
const server = computed(() => auth.serverInfo || {})
const mediaIp = ref('')

/** 基础信息（概览页）。 */
const basicRows = computed(() => {
  const info = server.value
  return [
    {
      label: '服务名称',
      value: info.server,
      tip: '平台对外声明的服务标识，出现在接口响应与日志中。'
    },
    {
      label: '平台编码',
      value: info.platformId,
      copy: true,
      tip: 'GB/T 28181 平台 20 位编码。设备的 SIP 域、级联上级寻址本平台，都以它为准。'
    },
    {
      label: '接口版本',
      value: info.interfaceVersion,
      tip: 'REST 接口版本号，与本页字段的对应关系一致。'
    },
    {
      label: '启动时间',
      value: info.startUpTime,
      tip: '本次进程启动的时刻。'
    }
  ]
})

/** 媒体服务各端口（概览页）。 */
const mediaRows = computed(() => {
  const info = server.value
  return [
    {
      label: '信令地址',
      value: info.sip,
      tip: 'SIP 监听地址与端口。设备需能访问该地址才能注册。'
    },
    {
      label: '媒体地址',
      value: mediaIp.value,
      tip: 'SDP 中下发给设备的收流地址，必须是设备可达的地址（不能是 127.0.0.1）。多网卡/NAT 环境需在配置中显式指定。'
    },
    {
      label: 'FLV / HLS',
      value: info.mediaHttp,
      tip: 'HTTP-FLV / ws-flv / HLS 出流端口，即浏览器拉流端口。'
    },
    {
      label: 'WebRTC',
      value: info.mediaWebRtc,
      tip: 'WebRTC 出流端口。NAT 环境需指定对外通告 IP，否则 ICE 候选不可达，表现为协商成功但画面 0×0。'
    },
    {
      label: 'RTSP',
      value: info.mediaRtsp,
      tip: '供 VLC / ffplay 等第三方播放器拉流的端口。'
    },
    {
      label: 'RTMP',
      value: info.mediaRtmp,
      tip: '供第三方推流/拉流的 RTMP 端口。'
    }
  ]
})

/** 接口信息（服务运维页）。 */
const apiRows = computed(() => {
  const info = server.value
  return [
    { label: '服务名称', value: info.server },
    { label: '接口版本', value: info.interfaceVersion },
    {
      label: '平台编码',
      value: info.platformId,
      tip: '设备注册时的 SIP 域；级联上级也用该编码寻址本平台。'
    },
    { label: '启动时间', value: info.startUpTime }
  ]
})

const ossRows = [
  { name: '阿里云 OSS', endpoint: 'https://oss-cn-hangzhou.aliyuncs.com', note: 'region=cn-hangzhou', noteKey: 'region' },
  { name: '腾讯云 COS', endpoint: 'https://cos.ap-guangzhou.myqcloud.com', note: 'region=ap-guangzhou', noteKey: 'region' },
  { name: 'MinIO', endpoint: 'http://192.168.1.10:9000', note: 'path-style=true（必须）', noteKey: 'path-style' },
  { name: '七牛云 Kodo', endpoint: 'https://s3-cn-east-1.qiniucs.com', note: 'region=cn-east-1', noteKey: 'region' },
  { name: 'AWS S3', endpoint: '（留空自动推导）', note: 'region=us-east-1', noteKey: 'region' }
]

const ossParams = [
  { key: 'gb28181.oss.enabled', desc: '是否启用，默认 false（录像存本地磁盘）' },
  { key: 'gb28181.oss.endpoint', desc: '服务地址，带 http(s):// 前缀；AWS S3 可留空自动推导' },
  { key: 'gb28181.oss.access-key / secret-key', desc: '访问密钥' },
  { key: 'gb28181.oss.bucket', desc: '存储桶名称' },
  { key: 'gb28181.oss.prefix', desc: '对象键前缀，默认 record，最终键为 <prefix>/<通道编码>/<文件名>' },
  { key: 'gb28181.oss.path-style', desc: 'MinIO / Ceph 等自建存储必须设为 true' },
  { key: 'gb28181.oss.public-read', desc: 'false 生成签名 URL；true 使用永久外链（需桶已开公共读）' },
  { key: 'gb28181.oss.delete-local-after-upload', desc: '上传成功后是否删除本地文件，默认 false（保留便于回放兜底）' }
]

/**
 * 服务商对照表里「关键参数」的 hover 说明。
 *
 * @param {string} key 参数名
 * @returns {string} 说明；无对应说明时返回空串（tooltip 自动禁用）
 */
function ossParamDesc(key) {
  if (key === 'region') {
    return '地域标识，必须与存储桶所在地域一致，否则请求会被拒绝或跨域失败。'
  }
  if (key === 'path-style') {
    return '自建存储（MinIO / Ceph）必须开启：用 path 形式而非子域名形式寻址，否则访问路径会拼错，表现为「文件上传成功但打不开」。'
  }
  return ''
}

/** 复制文本到剪贴板。 */
async function copy(text) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch (e) {
    ElMessage.warning('复制失败，请手动选择')
  }
}

async function loadServer() {
  loadingServer.value = true
  try {
    await auth.loadServerInfo()
    // 媒体地址取自 WebRTC 配置（后端按设备可达地址拼装）
    try {
      const cfg = await webrtcApi.config()
      mediaIp.value = cfg?.mediaIp || ''
    } catch (e) {
      // 拿不到就不显示，不影响其余信息
    }
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loadingServer.value = false
  }
}

/* ==================== 运行时配置 ==================== */

const cfgLoading = ref(false)
const cfgSaving = ref('')
/** 后端返回的全部配置项。 */
const cfgItems = ref([])
/** 编辑中的草稿（key → 值），与 cfgItems 分离，避免未保存就被刷新覆盖。 */
const cfgDraft = reactive({})

/**
 * 分组展示顺序与中文名。
 *
 * <p>顺序固定而不是按后端返回顺序：分类应该稳定，
 * 不然后端加一个配置项就会让分组顺序跳变。
 */
const CFG_GROUPS = [
  { key: 'sip', label: '信令接入' },
  { key: 'device', label: '设备接入与采集' },
  { key: 'record', label: '录像与磁盘' },
  { key: 'stream', label: '流与点播' },
  { key: 'log', label: '日志清理' }
]

/** 实际有配置项的分组（后端未来可能新增分组）。 */
const cfgGroups = computed(() => {
  const present = new Set(cfgItems.value.map((i) => i.group))
  const known = CFG_GROUPS.filter((g) => present.has(g.key))
  const extra = [...present]
    .filter((k) => !CFG_GROUPS.some((g) => g.key === k))
    .map((k) => ({ key: k, label: k }))
  return [...known, ...extra]
})

/** 已自定义（覆盖了 application.yml）的项数。 */
const dirtyCount = computed(() => cfgItems.value.filter((i) => i.overridden).length)

function cfgRowsOf(group) {
  return cfgItems.value.filter((i) => i.group === group)
}

/**
 * 配置项的中文名。
 *
 * <p>后端只给 key 和长说明，标题由前端映射：说明文字会随实现变化，
 * 而「录像保留天数」这种标题应该稳定，便于运维快速定位。
 */
const CFG_LABELS = {
  'sip.password': '设备统一接入密码',
  'device.offline-seconds': '设备离线判定阈值',
  'device.offline-scan-interval-ms': '离线巡检间隔',
  'device.auto-collect': '上线自动采集',
  'device.collect-interval-seconds': '采集节流间隔',
  'device.collect-device-info': '采集设备信息',
  'device.collect-device-status': '采集设备状态',
  'record.retain-days': '录像保留天数',
  'record.disk-max-used-percent': '磁盘使用率上限',
  'record.keep-min-days': '磁盘告急时最少保留',
  'record.max-second': '录像分片时长',
  'record.purge-interval-ms': '录像清理间隔',
  'record.keepalive-interval-ms': '云端录像保活间隔',
  'record.reconcile-interval-ms': '录像对账间隔',
  'record.url-sign-enabled': '录像地址签名校验',
  'record.url-sign-ttl-seconds': '录像地址有效期',
  'log.oplog-retain-days': '操作日志保留天数',
  'log.status-retain-days': '状态日志保留天数',
  'stream.idle-reap-interval-ms': '空闲流回收间隔',
  'stream.none-reader-delay-ms': '无人观看关流等待'
}

function cfgLabel(key) {
  return CFG_LABELS[key] || key
}

/**
 * 输入框右侧的单位后缀。
 *
 * <p>只按 key 后缀映射常见单位，其余留空 —— 乱标单位比不标更容易误导。
 *
 * @param {string} key 配置键
 * @returns {string}
 */
function cfgUnit(key) {
  if (key.endsWith('-ms')) return '毫秒'
  if (key.endsWith('-days')) return '天'
  // 同时匹配 -seconds（复数）与 -second（单数）：record.max-second 是后者
  if (key.endsWith('-seconds') || key.endsWith('-second')) return '秒'
  if (key.endsWith('-percent')) return '%'
  return ''
}

/** 草稿与已保存值是否不同（决定「保存」是否可点）。 */
function isDirty(row) {
  return String(cfgDraft[row.key] ?? '').trim() !== String(row.value ?? '').trim()
}

/**
 * 布尔项当前是否为「开」。
 *
 * <p>后端把 BOOLEAN 归一化成 {@code "true"} / {@code "false"}，
 * 但历史数据或手工写库可能是 {@code 1/0/on/off}，统一在这里兜住。
 *
 * @param {object} row 配置项
 * @returns {boolean}
 */
function isOn(row) {
  const v = String(row.value ?? '').trim().toLowerCase()
  return v === 'true' || v === '1' || v === 'on'
}

/** 是否为需要掩码显示的敏感项。 */
function isSecret(key) {
  return key === 'sip.password'
}

/**
 * 切换布尔项并立即保存。
 *
 * <p>开关型配置没有「改完再点保存」的必要 —— 拨动就是意图。
 * 立即提交能让「设备统一接入密码」这类项的行为更符合直觉。
 *
 * @param {object} row 配置项
 * @param {boolean} on  新状态
 */
async function toggleBool(row, on) {
  cfgDraft[row.key] = on ? 'true' : 'false'
  await saveConfig(row)
}

/**
 * 数值是否越界。
 *
 * <p><b>为什么前端也要校验</b>：后端 `ConfigKeys.Def#validate` 会拒绝越界值
 * 并返回 400，但那意味着「点了保存、等一个来回、才看到报错」。
 * 输入时就能标红，用户不必试错；后端校验仍保留（前端只是体验层，不是安全边界）。
 *
 * <p>判据取自后端下发的 `min` / `max`，不在这里写死 —— 后端调范围时界面自动跟上。
 *
 * @param {object} row 配置项
 * @returns {boolean} 越界或非数字时返回 true
 */
function isInvalid(row) {
  // 只有数值类型才谈得上越界；STRING（密码）和 BOOLEAN 不参与校验，
  // 否则密码 "abc123" 会被 Number() 判成 NaN 而误标红
  if (row.type !== 'INT' && row.type !== 'LONG') return false
  const raw = String(cfgDraft[row.key] ?? '').trim()
  // 空值不算「越界」，由保存时的「不能为空」提示处理
  if (!raw) return false
  const n = Number(raw)
  if (!Number.isFinite(n)) return true
  if (!Number.isInteger(n)) return true
  if (row.min != null && n < row.min) return true
  if (row.max != null && n > row.max) return true
  return false
}

/**
 * 越界原因的提示文案。
 *
 * @param {object} row 配置项
 * @returns {string}
 */
function outOfRangeMsg(row) {
  const raw = String(cfgDraft[row.key] ?? '').trim()
  const n = Number(raw)
  const unit = cfgUnit(row.key)
  if (!Number.isFinite(n)) {
    return '请填数字'
  }
  if ((row.type === 'INT' || row.type === 'LONG') && !Number.isInteger(n)) {
    return '不支持小数'
  }
  if (row.min != null && n < row.min) {
    return `不能小于 ${fmtNum(row.min)} ${unit}`
  }
  if (row.max != null && n > row.max) {
    return `不能大于 ${fmtNum(row.max)} ${unit}`
  }
  return ''
}

/**
 * 大数字加千分位。
 *
 * <p>毫秒类配置动辄 60000 / 86400000，裸数字数零很容易看错一位，
 * 而「少一个零」在这个页面意味着磁盘被填满或录像被秒删。
 *
 * @param {number|string} v
 * @returns {string}
 */
function fmtNum(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return String(v ?? '')
  return n.toLocaleString('en-US')
}

/**
 * 默认值的展示文案。
 *
 * <p>数值要走 {@link fmtNum}：范围那一行是「范围 10,000 ~ 600,000」，
 * 默认值却是「默认 60000」，同一行里两种写法看着像两种口径 ——
 * 而且裸的 600000 正是最容易被读错一位的那种数字。
 * 字符串项的空值写「空」，比一个看不见的空白更明确。
 *
 * @param {object} row 配置项
 * @returns {string}
 */
function defaultText(row) {
  if (row.type === 'STRING') return row.defaultValue || '空'
  if (row.type === 'BOOLEAN') return row.defaultValue
  return fmtNum(row.defaultValue)
}

async function loadConfig() {
  cfgLoading.value = true
  try {
    const list = await configApi.list()
    cfgItems.value = Array.isArray(list) ? list : []
    for (const item of cfgItems.value) {
      cfgDraft[item.key] = item.value
    }
  } catch (e) {
    ElMessage.error(e.message || '加载运行时配置失败')
  } finally {
    cfgLoading.value = false
  }
}

/**
 * 保存单项配置。
 *
 * <p>保存成功后用后端返回的值回填：后端可能对值做规范化
 * （如布尔统一成 true/false），用返回值才不会出现「界面显示 abc、实际生效 true」。
 */
async function saveConfig(row) {
  const value = String(cfgDraft[row.key] ?? '').trim()
  /*
   * 空值是否允许，按类型区分：
   * - STRING（如设备统一接入密码）允许留空 —— 留空本身就是有意义的操作
   *   （关闭鉴权）。一刀切拦掉的话，用户就没法关掉密码。
   * - 数值类型不允许空，否则清理/保活任务会拿到无意义的值。
   */
  if (!value && row.type !== 'STRING') {
    ElMessage.warning('配置值不能为空')
    return
  }
  // 双保险：按钮已置灰，但回车也会触发保存
  if (isInvalid(row)) {
    ElMessage.warning(outOfRangeMsg(row))
    return
  }
  cfgSaving.value = row.key
  try {
    const saved = await configApi.save(row.key, value)
    if (saved) {
      const idx = cfgItems.value.findIndex((i) => i.key === row.key)
      if (idx >= 0) {
        cfgItems.value[idx] = saved
      }
      cfgDraft[row.key] = saved.value
    }
    ElMessage.success(`${cfgLabel(row.key)} 已生效`)
  } catch (e) {
    // 后端会带出具体原因（越界 / 非数字 / 未知 key）
    ElMessage.error(e.message || '保存失败')
  } finally {
    cfgSaving.value = ''
  }
}

/** 恢复默认：删除库里的覆盖值，回落 application.yml。 */
async function resetConfig(row) {
  try {
    await ElMessageBox.confirm(
      `将「${cfgLabel(row.key)}」恢复为默认值 ${row.defaultValue}？\n\n这会删除数据库中的自定义值，改为使用 application.yml 的配置。`,
      '恢复默认',
      { type: 'warning', confirmButtonText: '恢复', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  cfgSaving.value = row.key
  try {
    const saved = await configApi.reset(row.key)
    if (saved) {
      const idx = cfgItems.value.findIndex((i) => i.key === row.key)
      if (idx >= 0) {
        cfgItems.value[idx] = saved
      }
      cfgDraft[row.key] = saved.value
    }
    ElMessage.success(`${cfgLabel(row.key)} 已恢复默认`)
  } catch (e) {
    ElMessage.error(e.message || '恢复失败')
  } finally {
    cfgSaving.value = ''
  }
}

/* ==================== 重启 / 改密 ==================== */

/**
 * 重启平台服务（需服务由守护进程拉起，否则重启后不会自动恢复）。
 *
 * <p>重启后轮询探测恢复：登录态已持久化（sa_token_store），
 * 服务起来后旧 token 依然有效，无需重新登录。
 */
async function restartServer() {
  try {
    await ElMessageBox.confirm(
      '确定重启平台服务？\n\n服务将优雅停机并由守护进程自动拉起（约 10 秒），期间功能暂停。\n若服务没有守护进程（裸 java -jar 启动），重启后不会自动恢复。',
      '重启确认',
      { type: 'warning', confirmButtonText: '重启', cancelButtonText: '取消' }
    )
  } catch (e) {
    return
  }
  restarting.value = true
  try {
    await systemApi.restart(true)
  } catch (e) {
    // 请求可能因服务退出而中断，忽略
  }
  ElMessage.info('服务重启中，正在等待恢复…')

  const startedAt = Date.now()
  const timer = setInterval(async () => {
    try {
      await systemApi.serverinfo()
      clearInterval(timer)
      restarting.value = false
      ElMessage.success('服务已恢复')
      loadServer()
    } catch (e) {
      if (Date.now() - startedAt > 90000) {
        clearInterval(timer)
        restarting.value = false
        ElMessage.warning('等待恢复超时，请检查服务是否已由守护进程拉起')
      }
    }
  }, 2500)
}

async function changePassword() {
  if (!pwd.oldpassword || !pwd.newpassword) {
    ElMessage.warning('请填写完整')
    return
  }
  if (pwd.newpassword !== pwd.confirm) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  saving.value = true
  try {
    await systemApi.modifyPassword({
      oldpassword: pwd.oldpassword,
      newpassword: pwd.newpassword
    })
    ElMessage.success('密码已修改，请重新登录')
    await auth.logout()
    router.push('/login')
  } catch (e) {
    ElMessage.error(e.message || '修改失败')
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadServer()
  loadConfig()
})
</script>

<style scoped>
/* ==================== 整体骨架 ==================== */

/*
 * 左导航 + 右内容。
 *
 * <p>左栏固定宽度且 sticky：设置项再多，导航也始终可见，
 * 不用滚回顶部才能切分区。
 */
.settings {
  display: grid;
  grid-template-columns: 196px minmax(0, 1fr);
  gap: var(--sp-5);
  align-items: start;
}

/* ==================== 左侧导航 ==================== */

.settings-side {
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  padding: var(--sp-3) 0;
}

.side-head {
  padding: 0 var(--sp-3) var(--sp-3);
  border-bottom: 1px solid var(--line-soft);
}

.side-title {
  font-size: var(--fs-lg);
  font-weight: 600;
  letter-spacing: -0.2px;
  color: var(--text-primary);
}

.side-sub {
  margin-top: 3px;
  font-size: var(--fs-xs);
  letter-spacing: -0.2px;
  color: var(--ink-500);
}

.side-nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

/* 分区按钮：默认只有文字色；选中时左侧一道竖线 + 浅底 */
.side-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  height: 38px;
  padding: 0 var(--sp-3);
  border: none;
  border-radius: var(--r-md);
  background: transparent;
  /* ink-600 而非 text-secondary(ink-500)：左侧导航是主要入口，不能发灰 */
  color: var(--ink-600);
  font-family: inherit;
  font-size: var(--fs-sm);
  text-align: left;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.side-item:hover {
  background: var(--bg-sunken);
  color: var(--text-primary);
}

.side-item.is-active {
  background: var(--bg-sunken);
  color: var(--brand-500);
  font-weight: 600;
}

.side-item.is-active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 9px;
  bottom: 9px;
  width: 2px;
  border-radius: 1px;
  background: var(--brand-500);
}

.side-icon {
  font-size: var(--fs-md);
  color: inherit;
}

.side-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 已自定义项数的角标 */
.side-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 17px;
  height: 17px;
  padding: 0 5px;
  border-radius: var(--r-sm);
  background: var(--warning-500);
  color: #fff;
  font-family: var(--mono);
  font-size: var(--fs-xs);
  font-weight: 600;
}

.side-foot {
  padding: var(--sp-2) var(--sp-3) 0;
  border-top: 1px solid var(--line-soft);
  font-size: var(--fs-xs);
  color: var(--ink-500);
}

/* ==================== KPI 读数：只调字重与颜色 ==================== */

/*
 * 字号沿用全局 --fs-xs，不放大。
 * 读数本身已经很大（.kpi-value 27px），标签再放大反而抢主次；
 * 让它「明显」的办法是字重 500 + ink-600，而不是字号。
 */
.kpi-label {
  font-weight: 500;
  color: var(--ink-600);
}

.kpi-foot {
  color: var(--ink-500);
}

/* ==================== 右侧内容 ==================== */

.settings-body {
  min-width: 0;
}

.sec {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}

.sec-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-4);
  padding-bottom: var(--sp-3);
  border-bottom: 1px solid var(--line-soft);
}

.sec-title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  /*
   * 与其他页面标题同一档（--fs-lg）。
   * 全站只保留 --fs-xs/sm/md/lg/xl 五档字号，这边不能自成一套 19/22px，
   * 否则切页时标题大小会跳一下。
   */
  font-size: var(--fs-lg);
  font-weight: 600;
  letter-spacing: -0.2px;
  color: var(--text-primary);
}

.sec-sub {
  margin: 4px 0 0;
  font-size: var(--fs-xs);
  color: var(--ink-500);
}

.sec-actions {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: var(--sp-2);
}

/* ==================== 面板 ==================== */

/*
 * 这里的「面板」不用 el-card：设置项不需要卡片阴影堆叠，
 * 一条发丝线分隔更整齐，也更省垂直空间。
 */
.panel {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: var(--bg-surface);
}

.panel--narrow {
  max-width: 460px;
}

.panel-head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  /* 与全局 .el-card__header 同高：两类容器在页面上是同一层级 */
  height: 42px;
  padding: 0 var(--sp-4);
  border-bottom: 1px solid var(--line-soft);
  background: var(--bg-sunken);
}

.panel-title {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 6px;
  min-width: 0;
  /* 与全局 .card-title 一致：--fs-sm + 600 */
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--text-primary);
}

/* ==================== 键值行 ==================== */

/*
 * 文本型读数（如「0 天 0 小时 24 分」）。
 *
 * <p>全局 .kpi-value 是给纯数字用的 27px 等宽字体 —— 中文时段放进去会被撑得很宽；
 * 这里降到 --fs-lg（与页面标题同号），保持同一行的三个读数视觉重量一致。
 */
.kpi-value--text {
  font-family: var(--sans);
  font-size: var(--fs-lg);
  font-weight: 600;
  letter-spacing: -0.2px;
}

.info-list {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
}

/*
 * 成对键值（如媒体服务的「信令地址 0.0.0.0:45060」）。
 *
 * <p>和上面「标签左、值右贴边」的行不同：这里值紧跟在标签后面、左对齐。
 * 因为这类面板是多列网格，若值右贴边，每格内会拉出一条长空白，
 * 一眼看过去标签和值对应不上（实测三列时尤其明显）。
 */
.info-list--pairs {
  grid-template-columns: repeat(auto-fit, minmax(268px, 1fr));
}

.info-list--pairs .info-row {
  justify-content: flex-start;
  gap: 10px;
  border-bottom: 1px solid var(--line-soft);
}

.info-list--pairs .info-value {
  flex: 1;
  justify-content: flex-start;
  text-align: left;
}

.info-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  min-height: 48px;
  padding: var(--sp-2) var(--sp-4);
  border-bottom: 1px solid var(--line-soft);
}

.info-row:last-child {
  border-bottom: none;
}

/* 标签用 ink-600 + 500：和全局表单标签（.el-form-item__label）同档，
   比 --text-secondary(ink-500) 深一档，一排字段不再糊成一片灰字 */
.info-label {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 4px;
  font-size: var(--fs-sm);
  font-weight: 500;
  color: var(--ink-600);
}

/* 值：13px 但字重 600 + 主色，与标签拉开层次（同号不同重） */
.info-value {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--text-primary);
  text-align: right;
  overflow-wrap: anywhere;
}

/* 复制按钮只在 hover 该行时出现，避免满屏小图标 */
.copy-btn {
  opacity: 0;
  transition: opacity 0.15s;
}

.info-row:hover .copy-btn {
  opacity: 1;
}

.action-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  flex-wrap: wrap;
  padding: var(--sp-4);
}

.action-desc {
  flex: 1;
  min-width: 220px;
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--text-tertiary);
}

/* ==================== 运行时配置行 ==================== */

.cfg-list {
  display: flex;
  flex-direction: column;
}

/*
 * 三栏网格：名称（含范围/默认值） / 输入框 / 操作。
 *
 * <p>用 grid 而不是 flex：三栏要竖向对齐成一列，
 * flex 会被最长的那一项撑开，导致每行输入框的起始位置都不一样。
 *
 * <p>行高给到 72px：配置项是这一页的主要操作对象，挤在一起会让
 * 「改哪个、范围是多少」全都得逐个看，反而更慢。
 */
.cfg-row {
  display: grid;
  grid-template-columns: minmax(200px, 1fr) 240px auto;
  align-items: start;
  gap: var(--sp-2) var(--sp-5);
  padding: var(--sp-3) var(--sp-4);
  border-bottom: 1px solid var(--line-soft);
  transition: background 0.15s;
}

.cfg-row:last-child {
  border-bottom: none;
}

.cfg-row:hover {
  background: var(--bg-sunken);
}

/* 已覆盖 application.yml 的项：左侧一道提示色竖线 */
.cfg-row.is-overridden {
  box-shadow: inset 3px 0 0 var(--warning-500);
}

.cfg-name {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

/*
 * 配置项名称：--fs-sm + 600。
 * 字号与表格正文一致，但字重更重、颜色更深，在一屏十几个条目里能立住；
 * 之前是 --fs-sm 常规字重，和旁边说明文字分不开。
 */
.cfg-label {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: var(--fs-sm);
  font-weight: 600;
  line-height: 1.4;
  color: var(--text-primary);
}

/* 范围与默认值：--fs-xs，但用 ink-600（而非 tertiary 的 ink-400）——
   它们是「要不要改」的判断依据，不该淡到看不见 */
.cfg-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  line-height: 1.5;
  color: var(--ink-600);
}

.cfg-range {
  font-variant-numeric: tabular-nums;
}

/* 当前值越界时，范围提示同步变红（和输入框形成呼应） */
.cfg-range.is-bad {
  color: var(--danger-500);
  font-weight: 600;
}

.cfg-sep {
  color: var(--ink-300);
}

.cfg-default {
  color: var(--ink-500);
}

.cfg-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.cfg-input {
  width: 100%;
}

/* 开关在网格里默认会被拉满宽度，收回到自然宽度并左对齐 */
.cfg-switch {
  height: 32px;
  align-self: flex-start;
}

/* 输入值用等宽 + 600：数字是最需要「一眼读准」的内容。
   字号不写死，继承全局 --el-font-size-base(14px)，与其他页输入框一致 */
.cfg-input :deep(.el-input__inner) {
  font-family: var(--mono);
  font-weight: 600;
  color: var(--text-primary);
}

/* 单位后缀：加深、加粗，避免被当成输入内容的一部分 */
.cfg-input :deep(.el-input-group__append) {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink-600);
}

/* 越界：红框 + 红字 */
.cfg-input.is-invalid :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--danger-500) inset;
}

.cfg-error {
  font-size: var(--fs-xs);
  font-weight: 500;
  line-height: 1.4;
  color: var(--danger-500);
}

.cfg-ops {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding-top: 2px;
}

.empty-hint {
  padding: var(--sp-6);
  text-align: center;
  font-size: var(--fs-sm);
  color: var(--text-secondary);
}

/* ==================== 其它 ==================== */

/* 有 hover 说明的文本：虚线下划线，暗示可悬停 */
.dotted {
  border-bottom: 1px dashed var(--ink-400);
  color: var(--ink-700);
  cursor: help;
}

.oss-table {
  width: 100%;
}

/*
 * OSS 配置项沿用 info-row 但键是技术串（gb28181.oss.enabled），
 * 用小一号等宽；说明文字保持正常正文大小 —— 这些是唯一的解释来源。
 */
.info-label.mono {
  font-family: var(--mono);
  font-weight: 500;
}

.pwd-form {
  padding: var(--sp-4) var(--sp-4) var(--sp-2);
}

.form-hint {
  margin-left: var(--sp-3);
  font-size: var(--fs-xs);
  color: var(--ink-500);
}

/* ==================== 窄屏 ==================== */

@media (max-width: 900px) {
  .settings {
    grid-template-columns: minmax(0, 1fr);
  }

  /* 导航改成横向胶囊条，避免占掉半屏高度 */
  .settings-side {
    position: static;
    padding: var(--sp-2) 0;
  }

  .side-head,
  .side-foot {
    display: none;
  }

  .side-nav {
    flex-direction: row;
    gap: var(--sp-1);
    overflow-x: auto;
    padding-bottom: 2px;
  }

  .side-item {
    flex: 0 0 auto;
    width: auto;
    height: 32px;
    border-radius: var(--r-md);
  }

  /* 横向排列时不画左侧竖线，改用底色区分 */
  .side-item.is-active::before {
    display: none;
  }

  /*
   * 配置行在 ≤900px 时也变单列。
   *
   * <p>断点与上面侧栏一致，而不是更窄的 760px：实测 794px 时
   * 三栏（200 + 240 + 按钮约 150 + 间距）已超出可用宽度，
   * 「恢复默认」按钮被挤出可视区 —— 看不见的按钮等于没有。
   */
  .cfg-row {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--sp-2);
    padding: var(--sp-3) var(--sp-4);
  }

  .cfg-ops {
    justify-content: flex-end;
  }
}
</style>
