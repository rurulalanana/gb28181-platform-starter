<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span><el-icon><List /></el-icon> 通道管理</span>
          <div class="header-actions">
            <el-button size="small" :loading="loading" @click="load">
              <el-icon><Refresh /></el-icon> 刷新
            </el-button>
          </div>
        </div>
      </template>

      <el-form :inline="true" class="search-form">
        <el-form-item label="搜索">
          <el-input
            v-model="query.q"
            placeholder="通道编码 / 名称"
            clearable
            style="width: 220px"
            @keyup.enter="load"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.online" placeholder="全部" clearable style="width: 130px">
            <el-option label="在线" :value="true" />
            <el-option label="离线" :value="false" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="load">查询</el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="channels" stripe>
        <el-table-column prop="id" label="通道编码" min-width="190">
          <template #default="{ row }"><span class="mono">{{ row.id }}</span></template>
        </el-table-column>
        <el-table-column label="名称" min-width="140">
          <template #default="{ row }">{{ row.customName || row.name || '—' }}</template>
        </el-table-column>
        <el-table-column label="所属设备" min-width="200">
          <template #default="{ row }">
            <div class="mono">{{ row.deviceId }}</div>
            <div class="muted">{{ row.deviceCustomName || row.deviceName || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ON' ? 'success' : 'info'" size="small" effect="light">
              {{ row.status === 'ON' ? '在线' : '离线' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="云台" width="100" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.ptzType" type="warning" size="small" effect="plain">
              {{ ptzTypeText(row.ptzType) }}
            </el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="云端录像" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.cloudRecord ? 'success' : 'info'" size="small" effect="plain">
              {{ row.cloudRecord ? '已开启' : '未开启' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="preview(row)">
              <el-icon><VideoPlay /></el-icon> 预览
            </el-button>
            <el-button link type="primary" :disabled="!row.ptzType" @click="openPtz(row)">
              <el-icon><Aim /></el-icon> 云台
            </el-button>
            <el-button
              link
              :type="row.cloudRecord ? 'danger' : 'primary'"
              @click="toggleCloudRecord(row)"
            >
              <el-icon><VideoCamera /></el-icon>
              {{ row.cloudRecord ? '关录像' : '开录像' }}
            </el-button>
            <el-button link type="primary" @click="openRename(row)">
              <el-icon><Edit /></el-icon>
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-cell">暂无通道</div>
        </template>
      </el-table>

      <div class="table-footer">共 {{ total }} 个通道</div>
    </el-card>

    <!-- 预览对话框 -->
    <el-dialog
      v-model="previewDialog"
      :title="'实时预览 · ' + (previewTarget?.customName || previewTarget?.name || previewTarget?.id || '')"
      width="880px"
      top="6vh"
      @close="closePreview"
    >
      <div class="preview-wrap">
        <div class="video-box preview-video">
          <div ref="videoContainer" class="video-container" />
        </div>
        <div class="preview-side">
          <div class="preview-status">
            <el-tag :type="stateType" size="small">{{ state }}</el-tag>
            <el-select v-model="proto" size="small" style="width: 160px" @change="switchProto">
              <el-option label="WebSocket-FLV" value="wsflv" />
              <el-option label="WebRTC（低延迟）" value="webrtc" />
            </el-select>
          </div>
          <el-divider />
          <PtzPanel
            v-if="previewTarget"
            :serial="previewTarget.deviceId"
            :code="previewTarget.id"
            :disabled="!playing"
          />
        </div>
      </div>
      <template #footer>
        <el-button @click="closePreview">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 云台抽屉（不预览直接控制） -->
    <el-drawer
      v-model="ptzDrawer"
      :title="'云台控制 · ' + (ptzTarget?.customName || ptzTarget?.name || ptzTarget?.id || '')"
      size="380px"
    >
      <PtzPanel v-if="ptzTarget" :serial="ptzTarget.deviceId" :code="ptzTarget.id" />
    </el-drawer>

    <!-- 重命名对话框 -->
    <el-dialog v-model="renameDialog" title="重命名通道" width="460px">
      <div class="mono mb-3">{{ renameTarget?.id }}</div>
      <el-input v-model="renameValue" placeholder="自定义名称" />
      <template #footer>
        <el-button @click="renameDialog = false">取消</el-button>
        <el-button type="primary" @click="doRename">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { deviceApi, streamApi, systemApi } from '@/api'
import { resolvePlayUrls, useVideoPlayer } from '@/composables/useVideoPlayer'
import { usePlayerStateType } from '@/composables/usePlayerState'
import PtzPanel from '@/components/PtzPanel.vue'

const channels = ref([])
const total = ref(0)
const loading = ref(false)
const query = reactive({ q: '', online: null })

const previewDialog = ref(false)
const previewTarget = ref(null)
const proto = ref('wsflv')
const mediaHttpPort = ref(null)

/** 播放器容器（jessibuca 在内部创建 canvas，WebRTC 通路创建 video）。 */
const videoContainer = ref(null)
const player = useVideoPlayer(videoContainer)
const playing = computed(() => player.playing.value)
const state = computed(() => player.state.value)
const stateType = usePlayerStateType(state)

const ptzDrawer = ref(false)
const ptzTarget = ref(null)

const renameDialog = ref(false)
const renameTarget = ref(null)
const renameValue = ref('')

const PTZ_TYPES = { 0: '未知', 1: '球机', 2: '半球', 3: '固定枪机', 4: '遥控枪机' }

function ptzTypeText(type) {
  return PTZ_TYPES[type] ?? '—'
}

async function load() {
  loading.value = true
  try {
    const params = { start: 0, limit: 1000 }
    if (query.q) params.q = query.q
    if (query.online !== null && query.online !== undefined) params.online = query.online
    const data = await deviceApi.channelList(params)
    channels.value = data?.channelList || []
    total.value = data?.channelCount ?? channels.value.length
  } catch (e) {
    ElMessage.error(e.message || '加载通道失败')
  } finally {
    loading.value = false
  }
}

async function ensureMediaPort() {
  if (mediaHttpPort.value) return
  try {
    const info = await systemApi.serverinfo()
    mediaHttpPort.value = info?.mediaHttp
  } catch (e) {
    // 忽略
  }
}

/** 打开预览并自动播放。 */
async function preview(row) {
  previewTarget.value = row
  previewDialog.value = true
  await ensureMediaPort()
  await sleep(120)
  await startPlay()
}

async function startPlay() {
  const row = previewTarget.value
  if (!row) return
  const streamId = `${row.deviceId}/${row.id}`
  let info = null
  try {
    // 点播响应已包含全部播放地址
    info = await streamApi.start({ serial: row.deviceId, code: row.id })
  } catch (e) {
    try {
      info = await streamApi.info(streamId, true)
    } catch (e2) {
      ElMessage.error(e.message || '点播失败')
      return
    }
  }
  const urls = resolvePlayUrls(info, mediaHttpPort.value)
  if (!urls.wsFlv && !urls.webrtc) {
    ElMessage.error('未获取到播放地址')
    return
  }
  await player.play(streamId, urls, { proto: proto.value })
}

async function switchProto() {
  const row = previewTarget.value
  if (!row || !playing.value) return
  const streamId = `${row.deviceId}/${row.id}`
  let urls = {}
  try {
    const info = await streamApi.info(streamId, true)
    urls = resolvePlayUrls(info, mediaHttpPort.value)
  } catch (e) {
    // 忽略
  }
  await player.switchProtocol(proto.value, urls)
}

function closePreview() {
  player.stop()
  previewTarget.value = null
}

function openPtz(row) {
  ptzTarget.value = row
  ptzDrawer.value = true
}

async function toggleCloudRecord(row) {
  const enable = !row.cloudRecord
  try {
    await deviceApi.setChannelCloudRecord({ serial: row.deviceId, code: row.id, enable })
    row.cloudRecord = enable
    ElMessage.success(enable ? '已开启云端录像' : '已关闭云端录像')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

function openRename(row) {
  renameTarget.value = row
  renameValue.value = row.customName || ''
  renameDialog.value = true
}

async function doRename() {
  const row = renameTarget.value
  if (!row) return
  try {
    await deviceApi.setChannelName({ serial: row.deviceId, code: row.id, name: renameValue.value })
    row.customName = renameValue.value
    renameDialog.value = false
    ElMessage.success('已保存')
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  }
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms))
}

onMounted(load)
onUnmounted(() => player.stop())
</script>

<style scoped>
.preview-wrap {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 20px;
}

@media (max-width: 900px) {
  .preview-wrap {
    grid-template-columns: minmax(0, 1fr);
  }
}

.preview-video {
  align-self: start;
}

.preview-side {
  border-left: 1px solid #ebeef5;
  padding-left: 18px;
  max-height: 62vh;
  overflow-y: auto;
}

.preview-status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 4px;
}
</style>
