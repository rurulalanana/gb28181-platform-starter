<template>
  <!--
    全局下载任务面板：挂在顶栏。
    任何页面都能看到「哪几路在下载、进度多少、完成了点此保存」——
    这是「后台下载」对用户最重要的可见性（否则点了下载切走就不知道结果了）。
  -->
  <el-popover placement="bottom-end" :width="440" trigger="click" popper-class="dl-popover">
    <template #reference>
      <button class="dl-trigger" :class="{ active: store.runningCount > 0 }" title="下载任务">
        <el-icon><Download /></el-icon>
        <span v-if="store.runningCount" class="dl-badge">{{ store.runningCount }}</span>
      </button>
    </template>

    <div class="dl-head">
      <span class="dl-title">下载任务</span>
      <span class="dl-sub">{{ store.runningCount }} 个进行中 / {{ store.done.length }} 个已完成</span>
    </div>

    <div v-if="!store.tasks.length" class="dl-empty">
      暂无下载任务。在「设备录像」里点「下载」即可后台生成录像文件。
    </div>

    <div v-else class="dl-list">
      <div v-for="t in store.tasks" :key="t.taskId" class="dl-item" :class="{ ok: t.ok, fail: t.finished && !t.ok }">
        <div class="dl-row">
          <span class="dl-name">{{ t.channelName || t.channelId }}</span>
          <span class="dl-range mono">{{ shortRange(t) }}</span>
        </div>

        <template v-if="!t.finished">
          <el-progress
            :percentage="t.percent || 0"
            :stroke-width="6"
            :show-text="false"
            class="dl-progress"
          />
          <div class="dl-row dl-meta">
            <span class="mono">
              {{ fmtDuration(t.recordedSecond) }} / {{ fmtDuration(t.totalSecond) }}
            </span>
            <span class="dl-actions">
              <span class="mono dl-pct">{{ t.percent || 0 }}%</span>
              <el-button link type="danger" size="small" @click="store.cancel(t)">取消</el-button>
            </span>
          </div>
        </template>

        <template v-else>
          <div class="dl-row dl-meta">
            <span v-if="t.ok" class="dl-done">
              <el-icon><CircleCheck /></el-icon>
              {{ formatSize(t.fileSize) }}
            </span>
            <span v-else class="dl-fail">{{ t.message || '未产出文件' }}</span>
            <span class="dl-actions">
              <el-button v-if="t.ok" link type="primary" size="small" @click="store.saveTask(t)">
                保存到本地
              </el-button>
              <el-button link size="small" @click="store.remove(t)">移除</el-button>
            </span>
          </div>
        </template>
      </div>
    </div>
  </el-popover>
</template>

<script setup>
import { useDownloadStore } from '@/stores/download'

const store = useDownloadStore()

/** 秒 → {@code H:MM:SS} / {@code M:SS}。 */
function fmtDuration(sec) {
  const n = Math.max(0, Math.round(Number(sec) || 0))
  const h = Math.floor(n / 3600)
  const m = Math.floor((n % 3600) / 60)
  const s = n % 60
  if (h) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
  return `${m}:${String(s).padStart(2, '0')}`
}

/** 文件大小 → {@code 12.3 MB}。 */
function formatSize(size) {
  const n = Number(size) || 0
  if (!n) return '—'
  const units = ['B', 'KB', 'MB', 'GB']
  let v = n
  let i = 0
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024
    i++
  }
  return `${v.toFixed(i === 0 ? 0 : 1)} ${units[i]}`
}

/** 只显示时分，避免面板里一整行长日期挤掉文件名。 */
function shortRange(t) {
  const cut = (v) => (v ? String(v).replace('T', ' ').slice(5, 16) : '')
  if (!t.startAt) return ''
  return t.endAt ? `${cut(t.startAt)} ~ ${cut(t.endAt).slice(6)}` : cut(t.startAt)
}
</script>

<style scoped>
.dl-trigger {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border: 1px solid transparent;
  border-radius: var(--r-md, 6px);
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
}

.dl-trigger:hover,
.dl-trigger.active {
  background: var(--ink-50, #f5f7fa);
  color: var(--accent-ink, #2563eb);
}

.dl-badge {
  position: absolute;
  top: -3px;
  right: -3px;
  min-width: 15px;
  height: 15px;
  padding: 0 4px;
  border-radius: 8px;
  background: var(--accent, #2563eb);
  color: #fff;
  font-size: 10px;
  line-height: 15px;
  text-align: center;
  font-variant-numeric: tabular-nums;
}

.dl-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--line, #e5e7eb);
  margin-bottom: 6px;
}

.dl-title {
  font-weight: 600;
  font-size: var(--fs-sm, 13px);
}

.dl-sub {
  font-size: var(--fs-xs, 12px);
  color: var(--text-tertiary, #9ca3af);
}

.dl-empty {
  padding: 14px 4px;
  color: var(--text-tertiary, #9ca3af);
  font-size: var(--fs-xs, 12px);
  line-height: 1.6;
}

/* 任务多时内部滚动，避免面板把屏幕撑满 */
.dl-list {
  max-height: 380px;
  overflow-y: auto;
}

.dl-item {
  padding: 8px 6px;
  border-radius: 6px;
}

.dl-item + .dl-item {
  border-top: 1px solid var(--line, #f0f0f0);
}

.dl-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.dl-name {
  font-size: var(--fs-sm, 13px);
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dl-range {
  font-size: 11px;
  color: var(--text-tertiary, #9ca3af);
  white-space: nowrap;
}

.dl-progress {
  margin: 6px 0 4px;
}

.dl-meta {
  font-size: 11px;
  color: var(--text-secondary, #6b7280);
}

.dl-actions {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex-shrink: 0;
}

.dl-pct {
  font-variant-numeric: tabular-nums;
}

.dl-done {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--success, #16a34a);
}

.dl-fail {
  color: var(--danger, #dc2626);
}
</style>
