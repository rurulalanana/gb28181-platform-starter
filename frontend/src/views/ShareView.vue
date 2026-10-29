<template>
  <div class="share-page">
    <div class="share-card">
      <div class="share-head">
        <h2 class="share-title">{{ info?.name || '录像分享' }}</h2>
        <div v-if="info" class="share-meta">
          <span>开始：{{ fmtTs(info.startAt) }}</span>
          <span>时长：{{ info.duration ?? '—' }} 秒</span>
        </div>
      </div>

      <div v-loading="loading" class="share-body">
        <el-alert
          v-if="error"
          :title="error"
          type="error"
          show-icon
          :closable="false"
        />
        <div v-else class="video-box">
          <div ref="videoContainer" class="video-container" />
        </div>
      </div>

      <div v-if="!error" class="share-foot">
        <el-radio-group v-model="proto" size="small" @change="switchProto">
          <el-radio-button value="wsflv">WebSocket-FLV</el-radio-button>
          <el-radio-button value="webrtc">WebRTC</el-radio-button>
        </el-radio-group>
        <el-tag :type="playStateType" size="small">{{ playState }}</el-tag>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useLivePlayer } from '@/composables/useLivePlayer'
import { usePlayerStateType } from '@/composables/usePlayerState'
import { LIVE_DEFAULT_PROTOCOL } from '@/player/resolvePlayUrls'

const route = useRoute()

const info = ref(null)
const loading = ref(false)
const error = ref('')
const proto = ref(LIVE_DEFAULT_PROTOCOL)

const videoContainer = ref(null)
const player = useLivePlayer(videoContainer)
const playState = computed(() => player.state.value)
const playStateType = usePlayerStateType(playState)

function fmtTs(v) {
  if (!v) return '—'
  const t = String(v).trim()
  if (t.length !== 14 || !/^\d{14}$/.test(t)) return t
  return `${t.slice(0, 4)}-${t.slice(4, 6)}-${t.slice(6, 8)} ${t.slice(8, 10)}:${t.slice(10, 12)}:${t.slice(12, 14)}`
}

/** 拉取分享信息并起播。 */
async function load() {
  loading.value = true
  error.value = ''
  try {
    const token = String(route.params.token || route.query.token || '')
    if (!token) {
      throw new Error('分享链接无效')
    }
    const resp = await fetch(`/api/v1/cloudrecord/share/${encodeURIComponent(token)}`)
    const json = await resp.json()
    if (json.code !== 0) {
      throw new Error(json.msg || '分享链接无效或已过期')
    }
    info.value = json.data
    await new Promise((r) => setTimeout(r, 300))
    let stream = ''
    if (json.data.webrtc) {
      const m = json.data.webrtc.match(/^webrtc:\/\/[^/]+\/(.+)$/)
      stream = m ? m[1] : ''
    }
    if (!stream) {
      throw new Error('未获取到播放地址')
    }
    await player.play(stream, {
      wsFlv: json.data.wsFlv || (json.data.flv ? String(json.data.flv).replace(/^http/, 'ws') : ''),
      httpFlv: json.data.flv,
      webrtc: json.data.webrtc,
      hls: json.data.hls
    }, { proto: proto.value })
  } catch (e) {
    error.value = e.message || '加载失败'
  } finally {
    loading.value = false
  }
}

async function switchProto() {
  if (!info.value) return
  await player.switchProtocol(proto.value, {
    wsFlv: info.value.wsFlv || (info.value.flv ? String(info.value.flv).replace(/^http/, 'ws') : ''),
    httpFlv: info.value.flv,
    webrtc: info.value.webrtc,
    hls: info.value.hls
  })
}

onMounted(load)
onUnmounted(() => player.stop())
</script>

<style scoped>
.share-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-app);
  padding: 32px 16px;
}

.share-card {
  width: 100%;
  max-width: 960px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 8px;
  overflow: hidden;
}

.share-head {
  padding: 16px 20px;
  border-bottom: 1px solid var(--line);
}

.share-title {
  margin: 0 0 4px;
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
}

.share-meta {
  display: flex;
  gap: 16px;
  font-size: 12.5px;
  color: var(--text-secondary);
}

.share-body {
  padding: 16px 20px;
}

.share-foot {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 20px 16px;
}
</style>
