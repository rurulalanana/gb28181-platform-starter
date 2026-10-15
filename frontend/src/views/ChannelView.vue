<template>
  <div class="page">
    <header class="page-header">
      <div class="page-title-group">
        <h2 class="page-title">通道管理</h2>
        <p class="page-subtitle">共 {{ pager.total ?? 0 }} 路通道</p>
      </div>
      <div class="page-actions">
        <el-button size="small" :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon> 刷新
        </el-button>
      </div>
    </header>

    <el-card shadow="never">
      <el-form :inline="true" class="search-form">
        <el-form-item label="搜索">
          <el-input
            v-model="query.q"
            placeholder="通道编码 / 名称"
            clearable
            style="width: 220px"
            @keyup.enter="search"
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
          <el-button type="primary" @click="search">查询</el-button>
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
            <div class="text-muted">{{ row.deviceCustomName || row.deviceName || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 'ON' ? 'success' : 'info'" size="small" effect="light">
              {{ row.status === 'ON' ? '在线' : '离线' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="云台" width="120" align="center">
          <template #default="{ row }">
            <!--
              可直接在列表里改：设备普遍不报或恒报 0（未知），
              而现场实际支持云台时需要在预览弹窗里用云台面板，
              所以把这里做成可编辑入口（改完立刻影响预览弹窗是否显示云台）。
            -->
            <el-select
              v-model="row.ptzType"
              :loading="ptzPending.has(row.id)"
              :disabled="ptzPending.has(row.id)"
              size="small"
              style="width: 104px"
              @change="(val) => changePtzType(row, val)"
            >
              <el-option
                v-for="(label, value) in PTZ_TYPES"
                :key="value"
                :label="label"
                :value="Number(value)"
              />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="云端录像" width="110" align="center">
          <template #default="{ row }">
            <el-switch
              v-model="row.cloudRecord"
              :loading="recordPending.has(row.id)"
              :disabled="recordPending.has(row.id)"
              inline-prompt
              active-text="开"
              inactive-text="关"
              @change="(val) => toggleCloudRecord(row, val)"
            />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="preview(row)">
              <el-icon><VideoPlay /></el-icon> 预览
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

      <TablePager
        :pager="pager"
        unit="个通道"
        @page-change="onPageChange"
        @size-change="onSizeChange"
      />
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
        <!--
          视频外层负责撑满栏高并居中；.video-box 自身保持 16:9，
          因此在宽而矮的弹窗里会上下留白、在窄而高的弹窗里左右留白，
          不会拉伸变形。
        -->
        <div class="preview-stage">
          <div class="video-box">
            <div ref="videoContainer" class="video-container" />
          </div>
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
          <!--
            云台面板只在通道被标为可云台（球机 / 半球 / 遥控枪机）时提供。
            类型未知或固定枪机时不显示 —— 面板里的方向键对固定枪机无效，
            摆在那里只会让人反复点而怀疑平台坏了。
          -->
          <PtzPanel
            v-if="previewTarget && supportsPtz(previewTarget.ptzType)"
            :serial="previewTarget.deviceId"
            :code="previewTarget.id"
            :disabled="!playing"
          />
          <el-empty
            v-else-if="previewTarget"
            description="该通道未标记云台类型，无法云台控制"
            :image-size="60"
          />
        </div>
      </div>
      <template #footer>
        <el-button @click="closePreview">关闭</el-button>
      </template>
    </el-dialog>

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
import { usePagination } from '@/composables/usePagination'
import PtzPanel from '@/components/PtzPanel.vue'
import TablePager from '@/components/TablePager.vue'

const channels = ref([])
const loading = ref(false)
const query = reactive({ q: '', online: null })

/** 正在切换云端录像开关的通道编码集合（用于开关 loading / 防重复点击）。 */
const recordPending = reactive(new Set())

/** 正在修改云台类型的通道编码集合（用于下拉 loading / 防重复点击）。 */
const ptzPending = reactive(new Set())

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

const renameDialog = ref(false)
const renameTarget = ref(null)
const renameValue = ref('')

/**
 * 云台类型：0 未知 / 1 球机 / 2 半球 / 3 固定枪机 / 4 遥控枪机。
 *
 * <p>与后端 {@code gb_channel.ptz_type} 的取值一致。
 */
const PTZ_TYPES = { 0: '未知', 1: '球机', 2: '半球', 3: '固定枪机', 4: '遥控枪机' }

/**
 * 该类型是否支持云台控制。
 *
 * <p>0（未知）与 3（固定枪机）不支持：前者设备没报，后者物理上就不能转。
 */
function supportsPtz(type) {
  return type === 1 || type === 2 || type === 4
}

const { pager, offset, reset, setTotal, onPageChange, onSizeChange } = usePagination({
  pageSize: 20,
  load: () => load()
})

async function load() {
  loading.value = true
  try {
    const params = { start: offset.value, limit: pager.size }
    if (query.q) params.q = query.q
    if (query.online !== null && query.online !== undefined) params.online = query.online
    const data = await deviceApi.channelList(params)
    channels.value = data?.channelList || []
    setTotal(data?.channelCount ?? channels.value.length)
  } catch (e) {
    ElMessage.error(e.message || '加载通道失败')
  } finally {
    loading.value = false
  }
}

/** 条件变化后回到第一页再查，否则会停在越界页上看到空列表。 */
function search() {
  reset()
  load()
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

/**
 * 修改通道的云台类型。
 *
 * <p>{@code v-model} 已先行改值：失败时必须回滚，否则下拉显示与后端不一致。
 * 另外后端已改为「目录同步时设备报 0 不覆盖」，所以这里改的值不会被设备
 * 下一次注册冲回未知。
 */
async function changePtzType(row, type) {
  const previous = row.ptzType
  ptzPending.add(row.id)
  try {
    await deviceApi.setChannelPtzType({
      serial: row.deviceId,
      code: row.id,
      ptz_type: type
    })
    ElMessage.success(`云台类型已改为「${PTZ_TYPES[type] ?? type}」`)
  } catch (e) {
    row.ptzType = previous
    ElMessage.error(e.message || '修改失败')
  } finally {
    ptzPending.delete(row.id)
  }
}

async function toggleCloudRecord(row, enable) {
  // v-model 已先行改值：失败时必须回滚，否则开关状态与后端不一致
  recordPending.add(row.id)
  try {
    // 参数名必须是 cloudrecord（后端 @RequestParam Boolean cloudrecord），
    // 传 enable 会被忽略并落到默认值 false，开关看似生效实则永远关着
    await deviceApi.setChannelCloudRecord({ serial: row.deviceId, code: row.id, cloudrecord: enable })
    ElMessage.success(enable ? '已开启云端录像' : '已关闭云端录像')
  } catch (e) {
    row.cloudRecord = !enable
    ElMessage.error(e.message || '操作失败')
  } finally {
    recordPending.delete(row.id)
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
  /*
   * 本行高度的唯一持有者。
   *
   * 之前左栏高度由宽度决定（.video-box 的 aspect-ratio: 16/9），
   * 右栏由视口决定（max-height: 62vh），两个来源互不相关 ——
   * 1080p 下左栏约 293px、右栏上限约 670px，差近 380px，永远对不上。
   * 现在两栏都拉伸到这里的高度，视频改由栏内居中满足 16:9。
   */
  height: 62vh;
}

/*
 * 左栏是「舞台」：撑满行高，内部把 16:9 的视频按 contain 方式居中。
 *
 * container-type: size 让下面能用 cqw/cqh 表达「舞台自身的宽高」。
 * 纯百分比做不到：只写 height:100% + width:auto 时宽度由 16:9 推出，
 * 超出栏宽被 max-width 夹取后浏览器不会回算高度，画面会被压扁
 * （实测 528×558 的栏里视频变成 0.946 而非 1.778）；
 * 只写 width:100% + max-height 则在「宽而矮」的栏里同样压扁。
 * 用 min(100cqw, 100cqh*16/9) 一次选出受限的那一边，两种情形都正确。
 */
.preview-stage {
  height: 100%;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  container-type: size;
}

.preview-stage .video-box {
  width: min(100cqw, calc(100cqh * 16 / 9));
  aspect-ratio: 16 / 9;
}

.preview-side {
  border-left: 1px solid var(--border-color);
  padding-left: 18px;
  height: 100%;
  overflow-y: auto;
}

/*
 * 窄视口：改为单列堆叠，行高不再由 .preview-wrap 持有（height: auto），
 * 因此两栏都要退回按内容自然高度 —— 否则 height: 100% 在 auto 高度的父级里
 * 会塌成 0，视频和云台面板都会消失。此时舞台也不需要容器尺寸，
 * 直接用全局 .video-box 的「宽 100% + 16:9」。
 */
@media (max-width: 900px) {
  .preview-wrap {
    grid-template-columns: minmax(0, 1fr);
    height: auto;
  }

  .preview-stage {
    height: auto;
    display: block;
    container-type: normal;
  }

  .preview-stage .video-box {
    width: 100%;
  }

  .preview-side {
    height: auto;
    border-left: none;
    padding-left: 0;
    border-top: 1px solid var(--border-color);
    padding-top: 14px;
  }
}

.preview-status {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 4px;
}
</style>
