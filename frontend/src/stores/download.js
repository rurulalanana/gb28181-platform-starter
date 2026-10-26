import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { ElMessage, ElNotification } from 'element-plus'
import { playbackApi } from '@/api'
import { downloadFile } from '@/utils/download'

/**
 * 后台下载任务（全局）。
 *
 * <h3>为什么不放在页面组件里</h3>
 * 录像下载的耗时是「录像段时长 ÷ 倍速」，动辄几分钟到几十分钟。
 * 早先下载状态放在 {@code PlaybackView} 组件内，带来两个问题：
 * <ul>
 *   <li>用户切到别的页面（组件卸载）后，轮询停止、进度丢失；</li>
 *   <li>离开页面时的 {@code playback/stop} 会把回放流停掉，下载直接中断。</li>
 * </ul>
 * 现在任务由服务端持有（{@code DownloadService}），前端只负责定时拉列表，
 * 因此<b>切页面、刷新浏览器都不影响下载</b>，且任意页面都能看到进度。
 *
 * <h3>轮询放在 store 里</h3>
 * store 是单例、不随组件卸载，所以轮询可以持续到整个应用关闭。
 * 未登录或没有任务时停止轮询，避免空转。
 */
export const useDownloadStore = defineStore('download', () => {
  /** 全部任务（进行中在前，已完成在后）。 */
  const tasks = ref([])
  /** 正在下载的任务。 */
  const running = computed(() => tasks.value.filter((t) => !t.finished))
  /** 已完成（成功或失败）的任务。 */
  const done = computed(() => tasks.value.filter((t) => t.finished))
  /** 进行中的任务数（顶栏徽标用）。 */
  const runningCount = computed(() => running.value.length)

  let timer = null
  /** 已经提示过完成的任务，避免每轮轮询重复弹通知。 */
  const notified = new Set()
  /** 是否已经完整拉过一次列表（用于区分「历史任务」与「本次新完成」）。 */
  let startedAtTracked = false

  /** 拉一次任务列表。 */
  async function refresh() {
    try {
      const data = await playbackApi.downloadList()
      tasks.value = Array.isArray(data) ? data : []
      notifyFinished()
    } catch (e) {
      /*
       * 拉取失败（未登录/服务重启）时不弹错误：这是后台轮询，
       * 弹窗会打扰用户。清空列表，等下一次成功拉取自然恢复。
       */
      tasks.value = []
    }
  }

  /** 任务刚从「进行中」变成「完成」时弹一条通知（含一键保存）。 */
  function notifyFinished() {
    for (const t of tasks.value) {
      if (!t.finished || notified.has(t.taskId)) continue
      notified.add(t.taskId)
      // 首次拉取时列表里可能已有历史完成任务，那些不弹（notified 是空集才会全弹）
      if (!startedAtTracked) continue
      if (t.ok) {
        ElNotification.success({
          title: '录像下载完成',
          message: `${t.channelName || t.channelId} · ${t.fileName || ''}`,
          duration: 8000,
          onClick: () => saveTask(t)
        })
      } else {
        ElNotification.warning({
          title: '录像下载未完成',
          message: `${t.channelName || t.channelId}：${t.message || '未知原因'}`,
          duration: 8000
        })
      }
    }
  }

  /** 是否已经完整拉过一次列表（用于区分「历史任务」与「本次新完成」）。 */
  function markTracked() {
    startedAtTracked = true
  }

  /** 开始轮询（应用启动时调用一次即可）。 */
  function startPolling() {
    if (timer) return
    refresh().finally(markTracked)
    timer = setInterval(refresh, 3000)
  }

  /** 停止轮询（登出时调用）。 */
  function stopPolling() {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
    tasks.value = []
    notified.clear()
    startedAtTracked = false
  }

  /**
   * 发起一个后台下载任务。
   *
   * @param {object} opts
   * @param {string} opts.serial    设备编码
   * @param {string} opts.code      通道编码
   * @param {string} opts.filePath  设备侧文件路径（可为空，按时间段定位）
   * @param {string} opts.starttime 段起始时间
   * @param {string} opts.endtime   段结束时间
   * @param {number} opts.duration  段时长（秒）
   * @param {number} opts.speed     下载倍速
   * @param {string} opts.name      通道名（任务列表展示用）
   * @returns {Promise<boolean>} 是否成功启动
   */
  async function start(opts) {
    try {
      /*
       * 先起回放（后端会复用已有会话）：下载必须有一路可录的流。
       * download=true 让后端在 SDP 里带 a=downloadspeed，设备才会快进推流。
       */
      await playbackApi.start({
        serial: opts.serial,
        code: opts.code,
        filePath: opts.filePath,
        starttime: opts.starttime,
        endtime: opts.endtime,
        download: true,
        downloadSpeed: opts.speed
      })
      await playbackApi.downloadStart({
        streamid: `${opts.serial}/${opts.code}_playback`,
        duration: opts.duration,
        speed: opts.speed,
        /*
         * 设备上报的该段大小：后端用它判断「内容已录完」。
         * 设备不会按请求的结束时间停推（实测会一直往后推），
         * 所以时间不可靠，大小才可靠。
         */
        expectedBytes: opts.fileSize || 0,
        name: opts.name,
        starttime: opts.starttime,
        endtime: opts.endtime
      })
      await refresh()
      return true
    } catch (e) {
      ElMessage.error(e.message || '下载启动失败')
      return false
    }
  }

  /** 取消一个进行中的任务。 */
  async function cancel(task) {
    try {
      await playbackApi.downloadCancel({ streamid: task.taskId })
      await refresh()
      ElMessage.success('已取消下载')
    } catch (e) {
      ElMessage.error(e.message || '取消失败')
    }
  }

  /** 从列表移除一条已完成记录（不删磁盘文件）。 */
  async function remove(task) {
    try {
      await playbackApi.downloadRemove({ taskid: task.taskId })
      await refresh()
    } catch (e) {
      ElMessage.error(e.message || '移除失败')
    }
  }

  /** 保存已完成的任务到本地。 */
  function saveTask(task) {
    if (!task?.url) {
      ElMessage.warning('该任务还没有可下载的文件')
      return
    }
    downloadFile(task.url, task.fileName)
  }

  return {
    tasks, running, done, runningCount,
    refresh, startPolling, stopPolling,
    start, cancel, remove, saveTask
  }
})
