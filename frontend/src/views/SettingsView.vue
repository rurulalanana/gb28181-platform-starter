<template>
  <div class="page">

    <!-- 工具条：操作按钮靠右，与其它页保持一致 -->
    <div class="filter-bar">
      <span class="filter-bar-spacer" />
      <el-button size="small" @click="loadServer">
        <el-icon><Refresh /></el-icon> 刷新信息
      </el-button>
    </div>

    <el-row :gutter="18">
      <!-- 服务器信息 -->
      <el-col :xs="24" :lg="14">
        <el-card shadow="never" class="full-height">
          <template #header>
            <div class="card-header">
              <span><el-icon><Monitor /></el-icon> 服务器信息</span>
              <el-button link type="primary" @click="loadServer">
                <el-icon><Refresh /></el-icon> 刷新
              </el-button>
            </div>
          </template>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item v-for="row in serverRows" :key="row.label" :label="row.label">
              <span class="mono">{{ row.value }}</span>
            </el-descriptions-item>
          </el-descriptions>

          <!-- 服务管理：远程重启（对齐 LiveGBS 的「版本信息」页） -->
          <div class="server-actions">
            <div class="sa-tip">
              重启为优雅停机，依赖进程守护自动拉起（约 10 秒恢复）；登录态已持久化，无需重新登录。
            </div>
            <el-button
              type="danger"
              plain
              size="small"
              :loading="restarting"
              @click="restartServer"
            >
              <el-icon><RefreshRight /></el-icon>
              {{ restarting ? '重启中…' : '重启服务' }}
            </el-button>
          </div>
        </el-card>
      </el-col>

      <!-- 修改密码 -->
      <el-col :xs="24" :lg="10">
        <el-card shadow="never" class="full-height">
          <template #header>
            <div class="card-header">
              <span><el-icon><Lock /></el-icon> 修改密码</span>
            </div>
          </template>
          <el-form :model="pwd" label-width="100px">
            <el-form-item label="当前密码">
              <el-input v-model="pwd.oldpassword" type="password" show-password />
            </el-form-item>
            <el-form-item label="新密码">
              <el-input v-model="pwd.newpassword" type="password" show-password />
            </el-form-item>
            <el-form-item label="确认新密码">
              <el-input v-model="pwd.confirm" type="password" show-password />
            </el-form-item>
          </el-form>
          <div class="form-actions">
            <el-button type="primary" :loading="saving" @click="changePassword">保存</el-button>
            <span class="muted ml-2">修改后需重新登录</span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 媒体服务 -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span><el-icon><VideoCamera /></el-icon> 媒体服务</span>
        </div>
      </template>

      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="媒体服务地址">
          <span class="mono">{{ mediaIp || '—' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="WebRTC 端口">
          <span class="mono">{{ server.mediaWebRtc ?? '—' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="FLV / HLS 端口">
          <span class="mono">{{ server.mediaHttp ?? '—' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="RTSP 端口">
          <span class="mono">{{ server.mediaRtsp ?? '—' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="RTMP 端口">
          <span class="mono">{{ server.mediaRtmp ?? '—' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="当前播放方式">
          <el-tag size="small" type="success" effect="plain">WebSocket-FLV（默认）</el-tag>
        </el-descriptions-item>
      </el-descriptions>

      <div class="hint-grid">
        <div class="hint-item">
          <div class="hint-title"><el-icon><VideoPlay /></el-icon> 浏览器播放</div>
          <div class="hint-desc">
            实时预览、录像回放均支持 WebSocket-FLV 与 WebRTC 两种方式，可在页面右上角切换。
          </div>
        </div>
        <div class="hint-item">
          <div class="hint-title"><el-icon><Link /></el-icon> 第三方拉流</div>
          <div class="hint-desc">
            RTSP / RTMP 地址可在「实时预览」页查看，用 VLC 或 ffplay 等播放器打开。
          </div>
        </div>
        <div class="hint-item">
          <div class="hint-title"><el-icon><Cloudy /></el-icon> 云端录像</div>
          <div class="hint-desc">
            录制 MP4 文件，支持归档到对象存储；归档后可直接下载，无需占用平台带宽。
          </div>
        </div>
      </div>
    </el-card>

    <!-- 运行时配置：改完立即生效，不需要重启 -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span><el-icon><Setting /></el-icon> 运行时配置</span>
          <div class="card-header-actions">
            <el-tag size="small" type="success" effect="plain">保存后立即生效</el-tag>
            <el-button link type="primary" :loading="cfgLoading" @click="loadConfig">
              <el-icon><Refresh /></el-icon> 刷新
            </el-button>
          </div>
        </div>
      </template>

      <p class="muted mb-3">
        这些参数保存在数据库中，修改后<b>立即生效、无需重启</b>，重启后依然保留。
        语义是「数据库优先、application.yml 兜底」：
        未改过的项用 yml 的值，点「恢复默认」即回落 yml。
        <br />
        注意：端口、路径、数据库连接等影响进程装配的配置仍需改
        <code>application.yml</code> 并重启，故不在此处。
      </p>

      <el-tabs v-model="cfgActiveGroup">
        <el-tab-pane
          v-for="g in cfgGroups"
          :key="g.key"
          :label="g.label"
          :name="g.key"
        >
          <el-table :data="cfgRowsOf(g.key)" size="small" border>
            <el-table-column label="配置项" min-width="220">
              <template #default="{ row }">
                <div class="cfg-key">{{ cfgLabel(row.key) }}</div>
                <div class="cfg-desc">{{ row.description }}</div>
              </template>
            </el-table-column>
            <el-table-column label="当前值" width="200">
              <template #default="{ row }">
                <el-input
                  v-model="cfgDraft[row.key]"
                  size="small"
                  :placeholder="row.defaultValue"
                  @keyup.enter="saveConfig(row)"
                >
                  <template v-if="row.type === 'INT' || row.type === 'LONG'" #append>
                    {{ row.key.endsWith('-ms') ? '毫秒' : (row.key.endsWith('-days') ? '天' : '') }}
                  </template>
                </el-input>
              </template>
            </el-table-column>
            <el-table-column label="默认值" width="110" align="center">
              <template #default="{ row }">
                <span class="mono">{{ row.defaultValue }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" align="center">
              <template #default="{ row }">
                <el-button
                  type="primary"
                  link
                  :loading="cfgSaving === row.key"
                  @click="saveConfig(row)"
                >保存</el-button>
                <el-button
                  link
                  :disabled="!row.overridden"
                  @click="resetConfig(row)"
                >恢复默认</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 对象存储 -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span><el-icon><UploadFilled /></el-icon> 对象存储配置</span>
          <el-tag size="small" type="info" effect="plain">S3 兼容协议</el-tag>
        </div>
      </template>

      <p class="muted mb-3">
        在 <code>application.yml</code> 中配置 <code>gb28181.oss.*</code> 后重启生效；
        未配置时录像保存在平台本地磁盘。
      </p>

      <el-table :data="ossRows" size="small" border>
        <el-table-column prop="name" label="对象存储" width="150" />
        <el-table-column label="endpoint" min-width="300">
          <template #default="{ row }"><span class="mono">{{ row.endpoint }}</span></template>
        </el-table-column>
        <el-table-column prop="note" label="关键参数" min-width="200" />
      </el-table>

      <el-collapse class="mt-3">
        <el-collapse-item title="常用参数说明" name="params">
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item v-for="p in ossParams" :key="p.key" :label="p.key">
              {{ p.desc }}
            </el-descriptions-item>
          </el-descriptions>
        </el-collapse-item>
      </el-collapse>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { configApi, systemApi, webrtcApi } from '@/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

const saving = ref(false)
const restarting = ref(false)
const pwd = reactive({ oldpassword: '', newpassword: '', confirm: '' })
const server = computed(() => auth.serverInfo || {})
const mediaIp = ref('')

const serverRows = computed(() => {
  const info = server.value
  return [
    { label: '服务名称', value: info.server || '—' },
    { label: '接口版本', value: info.interfaceVersion || '—' },
    { label: '平台编码', value: info.platformId || '—' },
    { label: 'SIP 监听', value: info.sip || '—' },
    { label: '设备（总/在线）', value: `${info.deviceTotal ?? '—'} / ${info.deviceOnline ?? '—'}` },
    { label: '通道（总/在线）', value: `${info.channelTotal ?? '—'} / ${info.channelOnline ?? '—'}` },
    { label: '启动时间', value: info.startUpTime || '—' },
    { label: '已运行', value: info.runningTime || '—' }
  ]
})

const ossRows = [
  { name: '阿里云 OSS', endpoint: 'https://oss-cn-hangzhou.aliyuncs.com', note: 'region=cn-hangzhou' },
  { name: '腾讯云 COS', endpoint: 'https://cos.ap-guangzhou.myqcloud.com', note: 'region=ap-guangzhou' },
  { name: 'MinIO', endpoint: 'http://192.168.1.10:9000', note: 'path-style=true（必须）' },
  { name: '七牛云 Kodo', endpoint: 'https://s3-cn-east-1.qiniucs.com', note: 'region=cn-east-1' },
  { name: 'AWS S3', endpoint: '（留空自动推导）', note: 'region=us-east-1' }
]

const ossParams = [
  { key: 'gb28181.oss.enabled', desc: '是否启用对象存储，默认 false（录像保存在本地磁盘）' },
  { key: 'gb28181.oss.endpoint', desc: '服务地址，带 http(s):// 前缀；AWS 官方 S3 可留空自动推导' },
  { key: 'gb28181.oss.access-key / secret-key', desc: '访问密钥' },
  { key: 'gb28181.oss.bucket', desc: '存储桶名称' },
  { key: 'gb28181.oss.prefix', desc: '对象键前缀，默认 record，最终键为 <prefix>/<通道编码>/<文件名>' },
  { key: 'gb28181.oss.path-style', desc: 'MinIO / Ceph 等自建存储必须设为 true' },
  { key: 'gb28181.oss.public-read', desc: 'false 生成签名 URL；true 使用永久外链（需桶已开公共读）' },
  { key: 'gb28181.oss.delete-local-after-upload', desc: '上传成功后是否删除本地文件，默认 false（保留便于回放兜底）' }
]

async function loadServer() {
  try {
    await auth.loadServerInfo()
    // 媒体服务地址从 WebRTC 配置获取（后端按设备可达地址拼装）
    try {
      const cfg = await webrtcApi.config()
      mediaIp.value = cfg?.mediaIp || ''
    } catch (e) {
      // 忽略
    }
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  }
}

// ==================== 运行时配置 ====================

const cfgLoading = ref(false)
const cfgSaving = ref('')
/** 后端返回的全部配置项。 */
const cfgItems = ref([])
/** 编辑中的草稿（key → 值），与 cfgItems 分离，避免未保存就刷新列表。 */
const cfgDraft = reactive({})
const cfgActiveGroup = ref('record')

/**
 * 分组展示顺序与中文名。
 *
 * <p>顺序固定而不是按后端返回顺序：分类应该稳定，
 * 不然后端加一个配置项就会让界面的 tab 顺序跳变。
 */
const CFG_GROUPS = [
  { key: 'record', label: '录像与磁盘' },
  { key: 'stream', label: '流与点播' },
  { key: 'device', label: '设备' },
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
  'record.retain-days': '录像保留天数',
  'record.disk-max-used-percent': '磁盘使用率上限',
  'record.keep-min-days': '磁盘告急时最少保留',
  'record.max-second': '录像分片时长',
  'record.purge-interval-ms': '录像清理间隔',
  'record.keepalive-interval-ms': '云端录像保活间隔',
  'record.reconcile-interval-ms': '录像对账间隔',
  'log.oplog-retain-days': '操作日志保留天数',
  'log.status-retain-days': '状态日志保留天数',
  'stream.idle-reap-interval-ms': '空闲流回收间隔',
  'stream.none-reader-delay-ms': '无人观看关流等待',
  'device.offline-seconds': '设备离线判定阈值'
}

function cfgLabel(key) {
  return CFG_LABELS[key] || key
}

async function loadConfig() {
  cfgLoading.value = true
  try {
    const list = await configApi.list()
    cfgItems.value = Array.isArray(list) ? list : []
    for (const item of cfgItems.value) {
      cfgDraft[item.key] = item.value
    }
    // 默认停在第一个有内容的分组
    const first = cfgGroups.value[0]
    if (first) {
      cfgActiveGroup.value = first.key
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
 * <p>保存成功后立即刷新该项的「当前值」和「已修改」标记：
 * 后端可能对值做规范化（如布尔值统一成 true/false），
 * 用后端返回的值回填才不会出现「界面显示 abc、实际生效 true」。
 */
async function saveConfig(row) {
  const value = String(cfgDraft[row.key] ?? '').trim()
  if (!value) {
    ElMessage.warning('配置值不能为空')
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
    cfgLoading.value = false
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
.full-height {
  height: 100%;
}

/* 卡片头右侧的操作区（标签 + 刷新按钮） */
.card-header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* 运行时配置：项名 + 说明两行排布，说明用弱化色避免抢视觉 */
.cfg-key {
  font-weight: 500;
  line-height: 1.5;
}

.cfg-desc {
  margin-top: 2px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}

.form-actions {
  display: flex;
  align-items: center;
  margin-top: 8px;
}

/* 服务管理：重启按钮区 */
.server-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--line-soft);
}

.server-actions .sa-tip {
  flex: 1;
  min-width: 220px;
  font-size: var(--fs-xs);
  color: var(--text-tertiary);
  line-height: 1.6;
}

.hint-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 16px;
  margin-top: 20px;
}

.hint-item {
  padding: 16px 18px;
  background: var(--bg-sunken);
  border-radius: 8px;
  border: 1px solid var(--border-color);
}

.hint-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink-900);
  margin-bottom: 8px;
}

.hint-desc {
  font-size: var(--fs-xs);
  color: var(--text-secondary);
  line-height: 1.7;
}
</style>
