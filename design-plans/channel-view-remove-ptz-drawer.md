# 通道管理移除「云台」入口（按钮 + 抽屉）

Written against: `a80fe3b`

## Evidence chain

- Surface: 通道管理页 `frontend/src/views/ChannelView.vue`（路由 `/channels`）
- Problem: 操作列的「云台」按钮打开一个**不带视频画面**的抽屉。云台操作的每一步都需要看着画面判断方向与幅度，无画面的抽屉无法完成该任务；该入口与同页「预览」弹窗内的云台面板功能重复。
- Design evidence:
  - `ChannelView.vue:84-86` — 操作列内 `<el-button link type="primary" :disabled="!row.ptzType" @click="openPtz(row)">…云台</el-button>`
  - `ChannelView.vue:134-142` — `<!-- 云台抽屉（不预览直接控制） -->` 的 `el-drawer`，内容为 `<PtzPanel>`，无视频容器
  - `ChannelView.vue:108-133` — 同页「预览」弹窗已内含视频 + `<PtzPanel>`，是带画面的完整入口
  - `LiveView.vue:138` — 实时预览抽屉同样是「先播放、再控制」，佐证云台需与画面同时可见
- Owner: `frontend/src/views/ChannelView.vue`
- Scope and affected surfaces: `ChannelView.vue` 模板（操作列 + 抽屉）、`<script setup>`（`ptzDrawer` / `ptzTarget` / `openPtz`）、该页 scoped 样式（操作列宽）
- Uncertainty: 「云台」类型列（`label="云台"`，只读展示球机/枪机等类型标签）保留 —— 原需求为「云台**操作**可以去掉」，该列是通道元信息而非操作入口

## Design decision

删除通道管理页的「云台」按钮与对应抽屉，云台控制只保留在能看到画面的两个入口（本页预览弹窗、实时预览抽屉）。类型列保留，用于判断某通道是否支持云台、从而决定是否需要为它打开预览做云台操作。

## Reuse

- 同页「预览」弹窗 — `ChannelView.vue:108-133`，已提供视频 + 云台面板的完整路径
- `PtzPanel.vue` — 共享云台组件，本页预览弹窗继续使用（`ChannelView.vue:121`）

无需新增 primitive。

## Changes

1. `frontend/src/views/ChannelView.vue` — 模板：删除操作列内的「云台」按钮
   - Change: 删除
     ```html
     <el-button link type="primary" :disabled="!row.ptzType" @click="openPtz(row)">
       <el-icon><Aim /></el-icon> 云台
     </el-button>
     ```
   - Preserve: 同列的「预览」与「编辑」两个按钮及其顺序、`fixed="right"`、`align="center"`
   - Verify: 操作列只剩「预览」「编辑」

2. `frontend/src/views/ChannelView.vue` — 模板：删除云台抽屉
   - Change: 删除 `<!-- 云台抽屉（不预览直接控制） -->` 注释及其后的整个 `<el-drawer v-model="ptzDrawer" …>…</el-drawer>` 块
   - Preserve: 紧随其后的「重命名通道」`el-dialog`；预览弹窗的 `el-dialog`
   - Verify: 页面不再渲染该抽屉

3. `frontend/src/views/ChannelView.vue` — 脚本：删除抽屉状态与打开函数
   - Change: 删除 `const ptzDrawer = ref(false)`、`const ptzTarget = ref(null)` 两个声明，以及
     ```js
     function openPtz(row) {
       ptzTarget.value = row
       ptzDrawer.value = true
     }
     ```
   - Preserve: `import PtzPanel from '@/components/PtzPanel.vue'`（预览弹窗仍在用）；`previewTarget` / `previewDialog` / `closePreview` 等预览相关状态
   - Verify: 无未使用变量导致的构建告警；`PtzPanel` 仍被引用

4. `frontend/src/views/ChannelView.vue` — 样式：收窄操作列
   - Change: 操作列 `width="200"` 改为 `width="140"`（`ChannelView.vue:81`）
   - Preserve: 列顺序与 `fixed="right"`、`align="center"`
   - Verify: 「预览」「编辑」两个链接按钮不换行、不被截断

## Scope

- Inherit: 无（仅本页）
- Verify: 本页「预览」弹窗内的云台面板功能不受影响
- Exclude: 「云台」类型列与 `ptzTypeText()`（保留）；`PtzPanel.vue`（见 `ptz-panel-control-consolidation.md`）；`ControlView.vue`（见 `remove-device-control-page.md`）

## Validation

- Product: 打开通道管理 → 选中在线通道 → 点「预览」→ 弹窗内可看到画面并用云台面板控制；表格中不再有「云台」按钮
- Interface:
  - 路由 `/channels` 列表（含在线/离线行，离线行 `ptzType` 可能为空）
  - 窄视口下表格横向滚动时固定列表现正常
- System: 云台入口只剩「带画面」的两处；本页不再存在无画面的控制入口
- Repository: `npm --prefix frontend run build` → 构建成功

## Stop conditions

- Stop if 「云台」类型列也需要一并删除（需求原文为「云台操作」，未含该列；若需删除请另行确认）
- Stop if 存在依赖该抽屉的深链接或外部引用

## Design documentation

- After acceptance and validation: 建议在 `ChannelView.vue` 文件头注释记录「云台控制仅保留在带画面的入口（预览弹窗），列表页不提供无画面的控制抽屉」；仓库当前无设计文档，不新建
