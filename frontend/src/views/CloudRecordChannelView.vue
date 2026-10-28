<template>
  <div class="page is-fill">
    <el-card shadow="never">
      <!-- 工具条：搜索在左，操作在右 -->
      <div class="filter-bar">
        <el-input
          v-model="query.q"
          placeholder="通道编码 / 名称"
          clearable
          style="width: 260px"
          @keyup.enter="search"
          @clear="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" @click="search">
          <el-icon><Search /></el-icon> 搜索
        </el-button>
        <el-button @click="resetQuery">重置</el-button>
        <span class="filter-bar-spacer" />
        <el-button size="small" @click="goPlans">
          <el-icon><Tickets /></el-icon> 录像计划
        </el-button>
        <el-button size="small" :loading="loading" @click="load">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
      </div>

      <el-table ref="tableRef" v-loading="loading" :data="rows" stripe>
        <el-table-column prop="serial" label="设备国标编号" min-width="190" sortable>
          <template #default="{ row }"><span class="mono">{{ row.serial }}</span></template>
        </el-table-column>
        <el-table-column prop="code" label="通道国标编号" min-width="190" sortable>
          <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
        </el-table-column>
        <el-table-column label="通道名称" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ row.customName || row.name || '—' }}</span>
            <span v-if="row.deviceCustomName || row.deviceName" class="text-dim">
              @{{ row.deviceCustomName || row.deviceName }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="快照" width="120" align="center">
          <template #default="{ row }">
            <!--
              通道快照：有快照显示缩略图（点击看大图），没有则显示「抓取」按钮。
              快照由服务端抓：拉 ZLM 流 → ffmpeg 解 H265 → 存盘，缓存 1 小时。

              <p>点击放大用 el-image 的 preview 能力（内置缩放/旋转/下载），
              比自己套一个 el-dialog 更省事，且移动端手势也支持。
            -->
            <el-image
              v-if="row.snapUrl"
              class="snap-img"
              :src="row.snapUrl"
              :alt="row.name || row.code"
              :preview-src-list="[row.snapUrl]"
              :initial-index="0"
              fit="cover"
              preview-teleported
              hide-on-click-modal
              @error="onSnapError(row)"
            />
            <el-button
              v-else
              link
              type="primary"
              :loading="capturing === `${row.serial}/${row.code}`"
              @click="grabSnap(row)"
            >
              <el-icon><Picture /></el-icon> 抓取
            </el-button>
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="180" sortable>
          <template #default="{ row }"><span class="mono">{{ row.updatedAt || '—' }}</span></template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180" sortable>
          <template #default="{ row }"><span class="mono">{{ row.createdAt || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="190" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">
              <el-icon><VideoPlay /></el-icon> 查看录像
            </el-button>
            <el-button link type="danger" @click="removeChannel(row)">
              <el-icon><Delete /></el-icon> 删除
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty
            :image-size="76"
            description="暂无云端录像通道。可在「通道管理」开启云端录像开关，设备推流后即开始录制"
          />
        </template>
      </el-table>

      <TablePager
        :pager="pager"
        unit="个通道"
        @page-change="onPageChange"
        @size-change="onSizeChange"
      />
    </el-card>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { confirmAction } from '@/utils/confirm'
import { cloudRecordApi } from '@/api'
import { usePagination } from '@/composables/usePagination'
import { useFillTableRows } from '@/composables/useFillTableRows'
import TablePager from '@/components/TablePager.vue'

const router = useRouter()

const rows = ref([])
const loading = ref(false)
const query = reactive({ q: '' })

const tableRef = ref(null)
useFillTableRows(tableRef, () => rows.value.length)

/** 正在抓拍的通道键（`<设备编码>/<通道编码>`）。 */
const capturing = ref('')

const { pager, offset, reset, setTotal, onPageChange, onSizeChange } = usePagination({
  pageSize: 10,
  load: () => load()
})

async function load() {
  loading.value = true
  try {
    const params = { start: offset.value, limit: pager.size }
    if (query.q) params.q = query.q
    const data = await cloudRecordApi.queryChannels(params)
    rows.value = data?.rows || []
    setTotal(data?.total ?? rows.value.length)
    autoSnap()
  } catch (e) {
    ElMessage.error(e.message || '加载云端录像通道失败')
  } finally {
    loading.value = false
  }
}

/**
 * 给还没有快照的通道补抓一帧。
 *
 * <p><b>只在首次进入页面时跑一次</b>（{@code snapAutoDone} 守卫）：
 * 原实现在每次 {@code load()} 都调用，于是翻页、搜索、刷新都会重新抓一遍
 * 之前失败的通道 —— 每次都要点播拉流，设备上行带宽被反复占用，
 * 而且失败的通道通常是因为设备不支持/离线，重试也不会成功。
 *
 * <p>失败的通道记到 {@code snapFailed} 里，本次会话不再重试；
 * 用户想重试可以点「抓取」按钮手动触发。
 *
 * <p>仍然保留错开启动：即使后端已有限流，前端错开能少触发几个「任务繁忙」。
 */
let snapAutoDone = false
const snapFailed = reactive(new Set())

function autoSnap() {
  if (snapAutoDone) return
  snapAutoDone = true
  const todo = rows.value.filter((r) => !r.snapUrl && !snapFailed.has(`${r.serial}/${r.code}`))
  todo.forEach((row, i) => {
    // 错开启动，避免同时拉多路把设备打爆；失败静默，不打扰用户
    setTimeout(() => grabSnap(row, true), 400 + i * 1500)
  })
}

function search() {
  reset()
  load()
}

function resetQuery() {
  query.q = ''
  search()
}

/** 进入通道详情（时间轴 / 列表双视图）。 */
function openDetail(row) {
  router.push({
    path: '/cloud/detail',
    query: { serial: row.serial, code: row.code }
  })
}

/** 进入录像计划列表。 */
function goPlans() {
  router.push('/cloud/plans')
}

/**
 * 抓一帧快照（服务端完成）。
 *
 * @param row    通道行
 * @param silent 静默模式：自动补抓失败时不打扰用户
 */
async function grabSnap(row, silent = false) {
  const key = `${row.serial}/${row.code}`
  if (capturing.value === key) return
  capturing.value = key
  try {
    const data = await cloudRecordApi.snapGrab({ serial: row.serial, code: row.code })
    if (data?.url) {
      row.snapUrl = data.url
      snapFailed.delete(key)
    } else if (!silent) {
      ElMessage.error('抓取快照失败')
    }
  } catch (e) {
    // 自动补抓失败记下来，本次会话不再重试（否则每次翻页都重新拉一遍流）
    snapFailed.add(key)
    if (!silent) ElMessage.error(e.message || '抓取快照失败')
  } finally {
    capturing.value = ''
  }
}

/** 快照图加载失败（文件已过期被清理）→ 清掉地址，允许重新抓。 */
function onSnapError(row) {
  row.snapUrl = ''
}

/** 删除该通道的全部云端录像。 */
async function removeChannel(row) {
  if (!(await confirmAction(
    `确定删除通道「${row.customName || row.name || row.code}」的全部云端录像？文件将同时删除，不可恢复。`,
    '删除确认',
    { confirmButtonText: '删除' }
  ))) return
  try {
    const data = await cloudRecordApi.removeChannel({ serial: row.serial, code: row.code })
    ElMessage.success(`已删除 ${data?.deleted ?? 0} 条录像`)
    load()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

onMounted(load)
</script>

<style scoped>
/*
 * 表格与工具条沿用全局样式（.filter-bar / .el-table），
 * 与「设备管理」「通道管理」保持一致，这里只补快照缩略图。
 */

/* 缩略图：16:9，固定宽度 —— 列表里只看个大概，不抢其他列的宽度 */
.snap-img {
  width: 96px;
  height: 54px;
  border-radius: 4px;
  border: 1px solid var(--line);
  background: #070a10;
  display: block;
  margin: 0 auto;
  /* 可点击看大图 —— 必须给出可点的视觉提示，否则用户不会想到去点 */
  cursor: zoom-in;
  transition: border-color 0.15s ease;
}

.snap-img:hover {
  border-color: var(--el-color-primary);
}
</style>
