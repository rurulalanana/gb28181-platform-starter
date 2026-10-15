# 删除「设备控制」页面及其全部入口

Written against: `a80fe3b`

## Evidence chain

- Surface: 设备控制页 `frontend/src/views/ControlView.vue`（路由 `/control`）及其所有入口
- Problem: 该页的功能完全被其他页面覆盖 —— 它提供「选一个通道 → 看实时画面 → 用云台面板控制」，而实时预览页（`/live`）与通道管理预览弹窗（`/channels`）都已提供同样的「画面 + 云台」组合，且前者支持多分屏。保留该页意味着三处维护同一套播放 + 云台逻辑。
- Design evidence:
  - `ControlView.vue:59-85` — 左侧「实时画面」卡片，含 `video-box` + `useVideoPlayer` + 协议切换 + 开始/停止预览
  - `ControlView.vue:90-99` — 右侧「云台与设备控制」卡片，内容为 `<PtzPanel :disabled="!current.deviceOnline">`
  - `LiveView.vue:138` — 实时预览页同样提供 `<PtzPanel>`（播放后经抽屉打开），并支持单/四/六/九分屏
  - `ChannelView.vue:121` — 通道管理预览弹窗同样提供 `<PtzPanel>`
  - 该页独有的仅「设备信息」`el-descriptions`（`ControlView.vue:106-131`），而设备管理页 `DeviceView.vue` 是设备属性的归属地
- Owner: 路由表 `frontend/src/router/index.js`、菜单 `frontend/src/layouts/MainLayout.vue`、总览快捷入口 `frontend/src/views/DashboardView.vue`
- Scope and affected surfaces: `router/index.js`、`MainLayout.vue`、`DashboardView.vue`、删除 `ControlView.vue`
- Uncertainty: 无（用户已明确选择「完全删除：路由 + 菜单 + 页面文件 + 总览快捷入口」）

## Design decision

整体删除设备控制页。云台控制统一收敛到「实时预览」与「通道管理预览弹窗」两个带画面的入口，避免同一套播放器 + 云台面板逻辑在三处重复维护。

## Reuse

- 实时预览页 `frontend/src/views/LiveView.vue` — 承接原页面的「看画面 + 控云台」任务，且支持多分屏
- 通道管理预览弹窗 `ChannelView.vue:108-133` — 承接单通道快速预览 + 云台
- 设备管理页 `frontend/src/views/DeviceView.vue` — 承接设备属性查看

无需新增 primitive。

## Changes

1. `frontend/src/router/index.js` — 删除路由
   - Change: 删除 `/control` 路由对象（`path: 'control'`、`name: 'control'`、`component: () => import('@/views/ControlView.vue')`、`meta: { title: '设备控制', icon: 'mdi-gamepad-variant' }`）
   - Preserve: `path: '/:pathMatch(.*)*', redirect: '/dashboard'` 兜底路由（旧书签会落到总览而非白屏）
   - Verify: 访问 `/control` 跳转到 `/dashboard`

2. `frontend/src/layouts/MainLayout.vue` — 删除菜单项
   - Change: 从 `allItems` 数组中删除 `{ path: '/control', title: '设备控制', icon: 'Aim' }`（`MainLayout.vue:89`）
   - Preserve: `allItems` 其余项及其顺序；`menuItems` 的 admin 过滤逻辑
   - Verify: 侧边栏不再出现「设备控制」

3. `frontend/src/views/DashboardView.vue` — 删除总览快捷入口
   - Change: 从 `quickLinks` 数组中删除 `{ path: '/control', title: '设备控制', desc: '云台、预置位、喊话', icon: 'Aim', color: '#67c23a' }`（`DashboardView.vue:184`）
   - Preserve: `quickLinks` 其余三项（实时预览 / 设备录像 / 云端录像）；`quick-item` 的 `repeat(auto-fit, minmax(220px, 1fr))` 网格自适应
   - Verify: 总览快捷入口剩三项且布局正常

4. `frontend/src/views/ControlView.vue` — 删除文件
   - Change: 删除整个文件
   - Preserve: 无
   - Verify: 全仓搜索 `ControlView` 无残留引用

## Scope

- Inherit: 无
- Verify: 侧边栏项数与总览快捷入口数量同步变化；实时预览页与通道管理预览弹窗的云台功能不受影响
- Exclude: `PtzPanel.vue`（见 `ptz-panel-control-consolidation.md`）；后端 `/control/*` 接口（保留，前端其余页面仍在使用）

## Validation

- Product: 打开总览 → 快捷入口中无「设备控制」；侧边栏无「设备控制」；经实时预览页仍可完成「看画面 + 控云台」
- Interface:
  - 路由 `/dashboard` 快捷入口网格
  - 侧边栏展开态与折叠态
  - 直接访问 `/control` 应跳转到 `/dashboard`
  - 旧书签/深链接 `/control`
- System: 全仓无 `ControlView` 引用；`/control/*` 接口仍被 `PtzPanel` 使用（未误删）
- Repository: `npm --prefix frontend run build` → 构建成功；`grep -rn "ControlView" frontend/src` → 无结果

## Stop conditions

- Stop if 需要保留 `/control` 直达链接能力（用户已选择完全删除；若改为仅隐藏菜单，需保留路由与文件）
- Stop if 该页的「设备信息」`el-descriptions` 有唯一数据来源无法从设备管理页获得

## Design documentation

- After acceptance and validation: 建议在 `router/index.js` 路由表头注释记录「云台控制只在带画面的入口提供（实时预览、通道管理预览弹窗），不单设控制页」；仓库当前无设计文档，不新建
