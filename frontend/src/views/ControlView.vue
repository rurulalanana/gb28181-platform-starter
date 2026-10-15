<template>
  <div class="page">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span><el-icon><Setting /></el-icon> 设备控制</span>
          <el-button size="small" :loading="loading" @click="loadChannels">
            <el-icon><Refresh /></el-icon> 刷新通道
          </el-button>
        </div>
      </template>

      <el-form :inline="true" class="search-form">
        <el-form-item label="通道">
          <el-select
            v-model="channelKey"
            placeholder="选择要控制的通道"
            filterable
            style="width: 340px"
            @change="onChannelChange"
          >
            <el-option
              v-for="c in channels"
              :key="c.deviceId + '/' + c.id"
              :label="`${c.customName || c.name || c.id}${c.deviceOnline ? '' : '（离线）'}`"
              :value="c.deviceId + '/' + c.id"
            >
              <div class="option-row">
                <span>{{ c.customName || c.name || c.id }}</span>
                <el-tag :type="c.deviceOnline ? 'success' : 'info'" size="small" effect="plain">
                  {{ c.deviceOnline ? '在线' : '离线' }}
                </el-tag>
              </div>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item v-if="current">
          <el-tag size="small" effect="plain" class="mono">{{ current.id }}</el-tag>
          <el-tag
            v-if="current.ptzType"
            size="small"
            type="warning"
            effect="plain"
            class="ml-2"
          >
            {{ ptzTypeText(current.ptzType) }}
          </el-tag>
          <el-tag v-else size="small" type="info" effect="plain" class="ml-2">无云台</el-tag>
        </el-form-item>
      </el-form>
    </el-card>

    <el-empty v-if="!channelKey" description="请先选择一个通道" />

    <template v-else>
      <el-row :gutter="18">
        <!-- 实时画面 -->
        <el-col :xs="24" :lg="14">
          <el-card shadow="never" class="full-height">
            <template #header>
              <div class="card-header">
                <span><el-icon><VideoCamera /></el-icon> 实时画面</span>
                <div class="header-actions">
                  <el-select v-model="proto" size="small" style="width: 160px" @change="switchProto">
                    <el-option label="WebSocket-FLV" value="wsflv" />
                    <el-option label="WebRTC（低延迟）" value="webrtc" />
                  </el-select>
                  <el-tag :type="stateType" size="small">{{ state }}</el-tag>
                </div>
              </div>
            </template>

            <div class="video-box">
              <div ref="videoContainer" class="video-container" />
            </div>

            <div class="video-actions">
              <el-button v-if="!playing" type="primary" :loading="starting" @click="startPlay">
                <el-icon><VideoPlay /></el-icon> 开始预览
              </el-button>
              <el-button v-else type="danger" plain @click="stopPlay">
                <el-icon><VideoPause /></el-icon> 停止预览
              </el-button>
            </div>
          </el-card>
        </el-col>

        <!-- 控制面板 -->
        <el-col :xs="24" :lg="10">
          <el-card shadow="never" class="full-height">
            <template #header>
              <div class="card-header">
                <span><el-icon><Aim /></el-icon> 云台与设备控制</span>
              </div>
            </template>
            <PtzPanel
              v-if="current"
              :serial="current.deviceId"
              :code="current.id"
              :disabled="!current.deviceOnline"
            />
          </el-card>
        </el-col>
      </el-row>

      <!-- 设备信息 -->
      <el-card v-if="deviceInfo" shadow="never">
        <template #header>
          <div class="card-header">
            <span><el-icon><Monitor /></el-icon> 设备信息</span>
          </div>
        </template>
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item label="设备名称">
            {{ deviceInfo.customName || deviceInfo.name || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="设备编码">
            <span class="mono">{{ deviceInfo.id }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="厂商">{{ deviceInfo.manufacturer || '—' }}</el-descriptions-item>
          <el-descriptions-item label="型号">{{ deviceInfo.model || '—' }}</el-descriptions-item>
          <el-descriptions-item label="固件版本">{{ deviceInfo.firmware || '—' }}</el-descriptions-item>
          <el-descriptions-item label="传输协议">{{ deviceInfo.transport || '—' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="deviceInfo.online ? 'success' : 'info'" size="small" effect="light">
              {{ deviceInfo.online ? '在线' : '离线' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="最近心跳">
            <span class="mono">{{ deviceInfo.lastKeepalive || '—' }}</span>
          </el-descriptions-item>
        </el-descriptions>
      </el-card>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { deviceApi, streamApi, systemApi } from '@/api'
import { resolvePlayUrls, useVideoPlayer } from '@/composables/useVideoPlayer'
import { usePlayerStateType } from '@/composables/usePlayerState'
import PtzPanel from '@/components/PtzPanel.vue'

const channels = ref([])
const channelKey = ref('')
const loading = ref(false)
const starting = ref(false)
const proto = ref('wsflv')
const mediaHttpPort = ref(null)
const deviceInfo = ref(null)

/** 播放器容器（jessibuca 在内部创建 canvas，WebRTC 通路创建 video）。 */
const videoContainer = ref(null)
const player = useVideoPlayer(videoContainer)
const playing = computed(() => player.playing.value)
const state = computed(() => player.state.value)
const stateType = usePlayerStateType(state)

const current = computed(() =>
  channels.value.find((c) => `${c.deviceId}/${c.id}` === channelKey.value)
)

const PTZ_TYPES = { 0: '未知', 1: '球机', 2: '半球', 3: '固定枪机', 4: '遥控枪机' }

function ptzTypeText(type) {
  return PTZ_TYPES[type] ?? '—'
}

async function loadChannels() {
  loading.value = true
  try {
    const data = await deviceApi.channelList({ start: 0, limit: 1000 })
    channels.value = data?.channelList || []
    if (!mediaHttpPort.value) {
      try {
        const info = await systemApi.serverinfo()
        mediaHttpPort.value = info?.mediaHttp
      } catch (e) {
        // 忽略
      }
    }
  } catch (e) {
    ElMessage.error(e.message || '加载通道失败')
  } finally {
    loading.value = false
  }
}

async function onChannelChange() {
  player.stop()
  deviceInfo.value = null
  const row = current.value
  if (!row) return
  try {
    deviceInfo.value = await deviceApi.info(row.deviceId)
  } catch (e) {
    // 设备信息获取失败不阻断控制
  }
}

async function startPlay() {
  const row = current.value
  if (!row) return
  starting.value = true
  const streamId = `${row.deviceId}/${row.id}`
  let info = null
  try {
    info = await streamApi.start({ serial: row.deviceId, code: row.id })
  } catch (e) {
    try {
      info = await streamApi.info(streamId, true)
    } catch (e2) {
      starting.value = false
      ElMessage.error(e.message || '点播失败')
      return
    }
  }
  starting.value = false
  const urls = resolvePlayUrls(info, mediaHttpPort.value)
  if (!urls.wsFlv && !urls.webrtc) {
    ElMessage.error('未获取到播放地址')
    return
  }
  await player.play(streamId, urls, { proto: proto.value })
}

async function switchProto() {
  const row = current.value
  if (!row || !playing.value) return
  let urls = {}
  try {
    const info = await streamApi.info(`${row.deviceId}/${row.id}`, true)
    urls = resolvePlayUrls(info, mediaHttpPort.value)
  } catch (e) {
    // 忽略
  }
  await player.switchProtocol(proto.value, urls)
}

function stopPlay() {
  player.stop()
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms))
}

onMounted(loadChannels)
onUnmounted(() => player.stop())
</script>

<style scoped>
.full-height {
  height: 100%;
}

.option-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.video-actions {
  margin-top: 16px;
  display: flex;
  gap: 10px;
}
</style>
