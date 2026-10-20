<template>
  <div class="page is-fill">

    <!-- 工具条：操作按钮靠右，与其它页保持一致 -->
    <div class="filter-bar">
      <span class="filter-bar-spacer" />
      <el-button size="small" :loading="loading" @click="load">
        <el-icon><Refresh /></el-icon> 刷新
      </el-button>
      <el-button size="small" type="primary" @click="openEdit(null)">
        <el-icon><Plus /></el-icon> 新增级联
      </el-button>
    </div>

    <el-card shadow="never">

      <el-table v-loading="loading" :data="list" stripe table-layout="fixed">
        <el-table-column label="名称" width="132">
          <template #default="{ row }">{{ row.name || '—' }}</template>
        </el-table-column>
        <el-table-column label="平台编码" min-width="190">
          <template #default="{ row }"><span class="mono">{{ row.serial || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="对端地址" width="168">
          <template #default="{ row }">
            <span class="mono">{{ row.host ? row.host + ':' + row.port : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="本地地址" width="168">
          <template #default="{ row }">
            <span class="mono">{{ row.localHost ? row.localHost + ':' + row.localPort : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="80" align="center">
          <template #default="{ row }">
            <el-switch
              :model-value="!!row.enable"
              size="small"
              @change="(v) => toggleEnable(row, v)"
            />
          </template>
        </el-table-column>
        <el-table-column label="在线" width="80" align="center">
          <template #default="{ row }">
            <span class="badge" :class="row.online ? 'is-online' : 'is-offline'">
              {{ row.online ? '在线' : '离线' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="共享全部通道" width="120" align="center">
          <template #default="{ row }">
            <el-icon :color="row.shareAllChannel ? '#67c23a' : '#c0c4cc'" :size="18">
              <CircleCheckFilled v-if="row.shareAllChannel" />
              <CircleCloseFilled v-else />
            </el-icon>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" align="center" fixed="right">
          <template #default="{ row }">
            <el-tooltip content="推送目录">
              <el-button link type="primary" @click="pushCatalog(row)">
                <el-icon><Refresh /></el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip content="编辑">
              <el-button link type="primary" @click="openEdit(row)">
                <el-icon><Edit /></el-icon>
              </el-button>
            </el-tooltip>
            <el-tooltip content="删除">
              <el-button link type="danger" @click="removeOne(row)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </el-tooltip>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-cell">
            暂未配置级联。级联用于与本平台之外的上下级 GB28181 平台互联
          </div>
        </template>
      </el-table>

      <TablePager
        :pager="pager"
        unit="条级联"
        @page-change="onPageChange"
        @size-change="onSizeChange"
      />
    </el-card>

    <!-- 编辑对话框 -->
    <!-- 布局说明：级联表单字段名普遍较长（「上级平台编码」「共享全部通道」等），
         早先用 label-width=130px + 两列 el-col span=12 时，每个输入框只剩约 200px，
         而「上级端口/本地地址/本地端口」三个字段挤在 span=8 里更只剩 130px，
         数字输入框的增减按钮直接把内容压没了。改为单列 + 分组卡片：
         每个字段占满整行宽度，长编码也有足够空间完整显示。 -->
    <el-dialog
      v-model="editDialog"
      :title="form.ID ? '编辑级联' : '新增级联'"
      width="560px"
      class="cascade-dialog"
      top="6vh"
    >
      <el-form :model="form" label-width="120px" label-position="right">
        <!-- 基础信息 -->
        <div class="form-section">
          <div class="form-section-title">基础信息</div>
          <el-form-item label="名称">
            <el-input v-model="form.Name" placeholder="用于在列表中区分，如：市级平台" />
          </el-form-item>
          <el-form-item label="上级平台编码">
            <el-input v-model="form.Serial" class="mono" placeholder="20 位国标编码" />
          </el-form-item>
          <el-form-item label="SIP 域">
            <el-input v-model="form.Realm" class="mono" placeholder="通常与上级平台编码前 10 位一致" />
          </el-form-item>
        </div>

        <!-- 网络配置 -->
        <div class="form-section">
          <div class="form-section-title">网络配置</div>
          <el-form-item label="上级平台地址">
            <el-input v-model="form.Host" class="mono" placeholder="上级平台的 IP 或域名" />
          </el-form-item>
          <el-form-item label="上级端口">
            <el-input-number
              v-model="form.Port"
              :min="1"
              :max="65535"
              controls-position="right"
              class="full-width"
            />
          </el-form-item>
          <el-form-item label="本地地址">
            <el-input v-model="form.LocalHost" class="mono" placeholder="留空则自动探测" />
          </el-form-item>
          <el-form-item label="本地端口">
            <el-input-number
              v-model="form.LocalPort"
              :min="1"
              :max="65535"
              controls-position="right"
              class="full-width"
            />
          </el-form-item>
          <el-form-item label="传输协议">
            <el-select v-model="form.CommandTransport" class="full-width">
              <el-option label="UDP" value="UDP" />
              <el-option label="TCP" value="TCP" />
            </el-select>
          </el-form-item>
        </div>

        <!-- 鉴权（可选） -->
        <div class="form-section">
          <div class="form-section-title">
            鉴权
            <span class="form-section-hint">上级平台要求认证时填写</span>
          </div>
          <el-form-item label="用户名">
            <el-input v-model="form.Username" placeholder="可选" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input v-model="form.Password" type="password" show-password placeholder="可选" />
          </el-form-item>
        </div>

        <!-- 行为开关 -->
        <div class="form-section">
          <div class="form-section-title">行为开关</div>
          <el-form-item label="启用">
            <el-switch v-model="form.Enable" />
          </el-form-item>
          <el-form-item label="共享全部通道">
            <el-switch v-model="form.ShareAllChannel" />
            <span class="form-item-hint">开启后本级所有通道都会同步给上级</span>
          </el-form-item>
          <el-form-item label="允许控制">
            <el-switch v-model="form.AllowControl" />
            <span class="form-item-hint">允许上级对本级通道下发云台等控制指令</span>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { cascadeApi } from '@/api'
import { usePagination } from '@/composables/usePagination'
import TablePager from '@/components/TablePager.vue'

const list = ref([])
const loading = ref(false)
const saving = ref(false)
const editDialog = ref(false)

const emptyForm = () => ({
  ID: null,
  Name: '',
  Serial: '',
  Realm: '',
  Host: '',
  Port: 5060,
  LocalHost: '',
  LocalPort: 5060,
  Username: '',
  Password: '',
  CommandTransport: 'UDP',
  Enable: true,
  ShareAllChannel: false,
  AllowControl: true
})

const form = reactive(emptyForm())

const { pager, offset, setTotal, onPageChange, onSizeChange } = usePagination({
  pageSize: 10,
  load: () => load()
})

async function load() {
  loading.value = true
  try {
    const data = await cascadeApi.list({ start: offset.value, limit: pager.size })
    // 后端 CascadeListVo 的字段名拼成了 casecadeList / casecadeCount（少了 s 后多打 a）。
    // 这里两种拼写都读，避免后端日后修正拼写时前端静默变成空列表。
    const rows = data?.casecadeList || data?.cascadeList || []
    list.value = rows
    setTotal(data?.casecadeCount ?? data?.cascadeCount ?? rows.length)
  } catch (e) {
    ElMessage.error(e.message || '加载级联失败')
  } finally {
    loading.value = false
  }
}

function openEdit(row) {
  Object.assign(form, emptyForm())
  if (row) {
    Object.assign(form, {
      ID: row.id,
      Name: row.name,
      Serial: row.serial,
      Realm: row.realm,
      Host: row.host,
      Port: row.port,
      LocalHost: row.localHost,
      LocalPort: row.localPort,
      Username: row.username,
      CommandTransport: row.commandTransport || 'UDP',
      Enable: !!row.enable,
      ShareAllChannel: !!row.shareAllChannel,
      AllowControl: row.allowControl !== false
    })
  }
  editDialog.value = true
}

async function save() {
  if (!form.Serial || !form.Host) {
    ElMessage.warning('请填写上级平台编码与地址')
    return
  }
  saving.value = true
  try {
    await cascadeApi.save({ ...form })
    ElMessage.success('已保存')
    editDialog.value = false
    load()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function toggleEnable(row, value) {
  try {
    await cascadeApi.setEnable({ id: row.id, enable: value })
    row.enable = value
    ElMessage.success(value ? '已启用' : '已停用')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

async function pushCatalog(row) {
  try {
    await cascadeApi.pushCatalog({ id: row.id })
    ElMessage.success('已推送目录')
  } catch (e) {
    ElMessage.error(e.message || '推送失败')
  }
}

async function removeOne(row) {
  try {
    await ElMessageBox.confirm(`确定删除级联「${row.name || row.serial}」？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch (e) {
    return
  }
  try {
    await cascadeApi.remove(row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

onMounted(load)
</script>

<style scoped>
/* .card-header 由 styles/main.css 统一提供 */

/* 对话框高度自适应：内容较长时可滚动，不撑破视口。
   注意必须同时约束 .el-dialog 本身（它是 flex 容器，body 才能拿到剩余高度），
   只给 __body 加 max-height 在 flex 布局下不生效，底部按钮会被挤出视口。 */
:deep(.cascade-dialog.el-dialog) {
  display: flex;
  flex-direction: column;
  max-height: 88vh;
  margin-bottom: 0;
}

:deep(.cascade-dialog .el-dialog__body) {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding-top: var(--sp-2);
}

/* 分组：每组一个浅色标题 + 分隔线，避免十几个字段糊成一片 */
.form-section {
  padding-bottom: var(--sp-2);
  margin-bottom: var(--sp-2);
  border-bottom: 1px dashed var(--line-soft);
}

.form-section:last-child {
  border-bottom: none;
  margin-bottom: 0;
  padding-bottom: 0;
}

.form-section-title {
  display: flex;
  align-items: baseline;
  gap: var(--sp-2);
  margin: var(--sp-2) 0 var(--sp-3);
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--text-secondary);
  letter-spacing: 0.02em;
}

.form-section-hint {
  font-size: var(--fs-xs);
  font-weight: 400;
  color: var(--text-tertiary);
}

/* 开关类字段右侧的说明文字 */
.form-item-hint {
  margin-left: var(--sp-2);
  font-size: var(--fs-xs);
  color: var(--text-tertiary);
}

/* 让输入框/数字框/下拉框撑满整行可用宽度 */
.full-width {
  width: 100%;
}

:deep(.cascade-dialog .el-input-number.full-width) {
  width: 100%;
}

/* 数字输入框的增减按钮在窄宽度下会挤压数值，这里留足右侧空间 */
:deep(.cascade-dialog .el-input-number .el-input__inner) {
  text-align: left;
  padding-right: 40px;
}

/* 开关行垂直居中对齐，避免与标签错位 */
:deep(.cascade-dialog .el-form-item) {
  margin-bottom: var(--sp-3);
}

:deep(.cascade-dialog .el-form-item:last-child) {
  margin-bottom: 0;
}
</style>
