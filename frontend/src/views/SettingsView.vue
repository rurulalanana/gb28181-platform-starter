<template>
  <div class="page">
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
import { ElMessage } from 'element-plus'
import { systemApi, webrtcApi } from '@/api'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

const saving = ref(false)
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
})
</script>

<style scoped>
.full-height {
  height: 100%;
}

.form-actions {
  display: flex;
  align-items: center;
  margin-top: 8px;
}

.hint-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 16px;
  margin-top: 20px;
}

.hint-item {
  padding: 16px 18px;
  background: #f8f9fb;
  border-radius: 8px;
  border: 1px solid #ebeef5;
}

.hint-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 8px;
}

.hint-desc {
  font-size: 12px;
  color: #909399;
  line-height: 1.7;
}
</style>
