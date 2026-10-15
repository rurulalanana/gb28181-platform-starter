# 收敛云台面板的控制项：移除速度滑杆与错位放大镜按钮，变倍改为按住式

Written against: `a80fe3b`

## Evidence chain

- Surface: 云台控制面板 `frontend/src/components/PtzPanel.vue`（共享组件）。消费者：
  - 通道管理预览弹窗 — `frontend/src/views/ChannelView.vue:121`
  - 实时预览抽屉 — `frontend/src/views/LiveView.vue:138`
  - 设备控制页 — `frontend/src/views/ControlView.vue:96`（按另一份计划整体删除）
- Problem:
  1. 方向盘下方两个「放大镜」按钮来历不明：它们的 `grid-area` 名称在 `grid-template-areas` 中并不存在，被浏览器自动排到 3×3 网格之外。
  2. 同一面板存在两组语义重复、下发字节完全相同的变倍控件。
  3. 速度滑杆占一整行，但用户不需要在界面上调速度。
- Design evidence:
  - `PtzPanel.vue:22,30` — `style="grid-area: zin"` 与 `style="grid-area: zout"`
  - `PtzPanel.vue` `.ptz-grid` — `grid-template-areas: 'ul u ur' / 'l c r' / 'dl d dr'`，共 9 个区域名，**没有** `zin` / `zout`
  - `ControlService.java:118-129` — `case "zoomin" -> PtzEncoder.ZOOM_IN` / `case "zoomout" -> PtzEncoder.ZOOM_OUT`
  - `ControlService.java:143-144` — `case "focusfar", "irisin" -> PtzEncoder.ZOOM_IN` / `case "focusnear", "irisout" -> PtzEncoder.ZOOM_OUT`
  - 两者最终都调用 `PtzEncoder.direction(DEFAULT_ADDRESS, bits, sp)`，`bits` 相同 → 下发报文逐字节相同
- Owner: `frontend/src/components/PtzPanel.vue`（模板 + `<script setup>` + scoped 样式）
- Scope and affected surfaces: `PtzPanel.vue`；随之影响 `ChannelView.vue` 预览弹窗、`LiveView.vue` 云台抽屉
- Uncertainty: 无

## Design decision

`PtzPanel` 只保留**一组**变倍控件，且与方向键统一为「按住持续、松开停止」的交互；速度作为固定常量由前端下发，接口参数 `speed` 保持不变。理由是：变倍的两处入口下发的是同一个 PTZ 指令，保留两组会让用户无法判断该点哪个；而速度对云台操作是调试参数而非日常操作项，固定值即可。

## Reuse

- `beginMove(command, event)` / `endMove()` — `PtzPanel.vue` 已有的按住式交互，含 `setPointerCapture` 与全局 `pointerup` / `pointercancel` / `blur` / `visibilitychange` 兜底
- `PtzEncoder.ZOOM_IN` / `ZOOM_OUT` — 变倍位定义，勿改
- `Plus` / `Minus` 图标 — Element Plus 图标已在 `frontend/src/main.js:13-14` 全量注册，模板可直接写 `<el-icon><Plus /></el-icon>`
- Exemplar: `PtzPanel.vue` 方向盘按钮的 `@pointerdown="beginMove(...)"` 写法

无需新增 primitive。

## Changes

1. `frontend/src/components/PtzPanel.vue` — 模板：删除方向盘内两个错位的放大镜按钮
   - Change: 删除 `.ptz-grid` 内 `style="grid-area: zin"` 与 `style="grid-area: zout"` 两个 `<el-button>`（含 `beginMove('zoomin'|'zoomout')` 绑定）。删除后 `.ptz-grid` 只剩 8 个方向按钮 + 1 个 `.ptz-center`，恰好对应 9 个 `grid-template-areas`
   - Preserve: `.ptz-grid` 的 `grid-template-areas` 定义、`.ptz-center`、方向按钮的按住式交互
   - Verify: 面板上不再出现网格外游离的放大镜按钮；方向盘为规整 3×3

2. `frontend/src/components/PtzPanel.vue` — 模板：删除速度滑杆
   - Change: 删除 `<!-- 速度 -->` 注释与其后的 `<div class="ptz-speed">…</div>` 整块（含 `el-slider` 与数值显示）
   - Preserve: 其余区块顺序与 `gap: 16px` 的纵向节奏
   - Verify: 面板顶部直接是方向盘，无「速度」行

3. `frontend/src/components/PtzPanel.vue` — 模板：焦点行改为「变倍 +/−」与「光圈 +/−」
   - Change: 把 `<!-- 焦点光圈 -->` 下的第一个 `el-button-group`（近焦 / 远焦 / 停 三个按钮）替换为两个按住式按钮：
     ```html
     <el-button-group>
       <el-button size="small" :disabled="inactive" title="放大" @pointerdown="beginMove('zoomin', $event)">
         <el-icon><Plus /></el-icon>
       </el-button>
       <el-button size="small" :disabled="inactive" title="缩小" @pointerdown="beginMove('zoomout', $event)">
         <el-icon><Minus /></el-icon>
       </el-button>
     </el-button-group>
     ```
     第二个 `el-button-group`（光圈+ / 光圈−）保持原样不动
   - Preserve: 光圈两个按钮的 `@click="cmd('fi', …)"` 行为与文案；`.ptz-row` 布局
   - Verify: 出现一组 +/− 按钮，按住时持续变倍、松开立即停止；光圈按钮行为不变

4. `frontend/src/components/PtzPanel.vue` — 脚本：速度改为常量
   - Change: `const speed = ref(129)` 改为 `const SPEED = 129`；`beginMove` 内 `speed: speed.value` 改为 `speed: SPEED`
   - Preserve: 下发的 `speed` 值仍为 129；接口 `/control/ptz` 的 `speed` 参数契约不变（后端 `ControlService.ptz` 仍接收并做 0~255 夹取）
   - Verify: 抓包或后端日志中 `speed` 仍为 129

5. `frontend/src/components/PtzPanel.vue` — 样式：删除速度相关规则
   - Change: 删除 `.ptz-speed`、`.ptz-speed .label`、`.ptz-speed :deep(.el-slider)`、`.ptz-speed .value` 四条规则
   - Preserve: `.ptz-row`、`.ptz-row.wrap`、`.ptz-section`、`.section-title`、`.ptz-grid`、`.ptz-btn`、`.ptz-center`
   - Verify: `npm run build` 无告警；样式文件中不再有 `.ptz-speed`

## Scope

- Inherit: `ChannelView.vue` 预览弹窗内的云台面板、`LiveView.vue` 云台抽屉
- Verify: `ChannelView.vue` 预览弹窗在面板变短后布局仍正常
- Exclude: 后端 `ControlService.focusIris` / `ptz` 与 `PtzEncoder` 位定义（接口保留，不改）；`ChannelView.vue` 的云台按钮与抽屉（见 `channel-view-remove-ptz-drawer.md`）；`ControlView.vue`（见 `remove-device-control-page.md`）

## Validation

- Product: 打开任一通道预览 → 按住方向键云台持续转动、松开停止；按住 +/− 画面持续变倍、松开停止
- Interface:
  - 路由 `/channels` → 预览弹窗内的云台面板
  - 路由 `/live` → 任一通道播放后点云台按钮打开的抽屉
  - 通道离线（`disabled`）时全部按钮禁用
  - 窄视口（<900px）下弹窗改为单列时面板不溢出
- System: 变倍只有一处入口；面板内不再有速度滑杆；`.ptz-grid` 无游离元素
- Repository: `npm --prefix frontend run build` → 构建成功，无未解析组件或样式错误

## Stop conditions

- Stop if 变倍必须区分「变焦」与「聚焦」两种设备语义（当前证据显示两者下发字节相同，若设备侧实际需要区分，则不能合并）
- Stop if 需要保留界面上可调速度（则改为保留滑杆而非常量）

## Design documentation

- After acceptance and validation: 建议在 `PtzPanel.vue` 文件头注释记录「变倍仅一处入口，且与方向键统一为按住式；速度由前端常量下发」；仓库当前无设计文档（无 `DESIGN.md`），不新建
