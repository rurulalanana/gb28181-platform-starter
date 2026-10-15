# 通道管理预览弹窗：左右两栏等高

Written against: `a80fe3b`

## Evidence chain

- Surface: 通道管理页的实时预览弹窗 — `frontend/src/views/ChannelView.vue:100-133`（路由 `/channels`）
- Problem: 左侧视频区与右侧云台区高度不等，视觉上「一边高一边矮」。左栏高度由**宽度**决定，右栏高度由**视口**决定，两者互不相关，永远对不上。
- Design evidence:
  - `ChannelView.vue:323-327` — `.preview-wrap { display: grid; grid-template-columns: minmax(0, 1fr) 300px; gap: 20px }`
  - `ChannelView.vue:335-337` — `.preview-video { align-self: start }`（左栏只占内容高度，不拉伸）
  - `ChannelView.vue:339-344` — `.preview-side { border-left: 1px solid #ebeef5; padding-left: 18px; max-height: 62vh; overflow-y: auto }`（右栏上限随视口）
  - `frontend/src/styles/main.css:27-32` — `.video-box { background: #000; border-radius: 6px; overflow: hidden; aspect-ratio: 16/9; width: 100%; position: relative }`（左栏高度 = 宽度 × 9/16）
  - 弹窗 `width="880px"`、`top="6vh"`（`ChannelView.vue:104-105`）
  - 算例（1080p 视口）：可用宽 = 880 − 40（弹窗内边距）− 300（右栏）− 20（间距）= 520 → 左栏高 ≈ 520 × 9 ÷ 16 ≈ **293px**；右栏上限 = 1080 × 62% ≈ **670px**（差约 377px）
- Owner: `frontend/src/views/ChannelView.vue` 的 scoped 样式（`.preview-wrap` / `.preview-video` / `.preview-side`）
- Scope and affected surfaces: 仅该弹窗；`.video-box` 的 16:9 契约来自 `styles/main.css`，属全局约定，不改
- Uncertainty: 无（差值由上述两个独立的高度来源直接算出）

## Design decision

让 `.preview-wrap` 成为这一行高度的**唯一**所有者，两栏都拉伸到该高度；`.video-box` 的 16:9 契约由「在栏内居中」来满足，而不是由栏高决定。这样左右两栏必然等高，且视频不会被拉伸变形。

## Reuse

- `62vh` — 该弹窗已有的高度约束（原在 `.preview-side` 上），上移到 `.preview-wrap` 成为整行高度
- `.video-box` / `.video-container` — `frontend/src/styles/main.css:27-47` 的全局视频契约，直接复用不改
- Exemplar: `LiveView.vue:407` 的 `.live-body` 同样是 `grid-template-columns: minmax(0, 1fr) 300px` 的左右分栏，可对照其 `align-items: start` 用法
- 无需新增 primitive

## Changes

1. `frontend/src/views/ChannelView.vue` — `.preview-wrap` 承担整行高度
   - Change:
     ```css
     .preview-wrap {
       display: grid;
       grid-template-columns: minmax(0, 1fr) 300px;
       gap: 20px;
       height: 62vh;
     }
     ```
   - Preserve: 两列宽度定义 `minmax(0, 1fr) 300px`、`gap: 20px`、`@media (max-width: 900px)` 下单列回退
   - Verify: 弹窗内两栏外框上下边界对齐

2. `frontend/src/views/ChannelView.vue` — 左栏改为撑满并居中视频
   - Change: `.preview-video` 由 `align-self: start` 改为撑满栏高、把 `.video-box` 垂直居中：
     ```css
     .preview-video {
       height: 100%;
       display: flex;
       align-items: center;
       justify-content: center;
     }
     ```
     并把内层 `.video-box` 限制为不超过栏高，避免 16:9 在窄栏时溢出：
     ```css
     .preview-video .video-box {
       max-height: 100%;
       width: auto;
       max-width: 100%;
       aspect-ratio: 16 / 9;
     }
     ```
   - Preserve: `.video-box` 的黑底、圆角、`aspect-ratio: 16/9`（不覆盖全局定义，仅在本弹窗内约束尺寸）；`.video-container` 的 `position: absolute; inset: 0` 不动（jessibuca 依赖容器尺寸，见 `styles/main.css:38-46` 的注释）
   - Verify: 视频保持 16:9 不变形；栏高变化时视频垂直居中，不溢出

3. `frontend/src/views/ChannelView.vue` — 右栏去掉自身高度上限，改为撑满并内部滚动
   - Change:
     ```css
     .preview-side {
       border-left: 1px solid #ebeef5;
       padding-left: 18px;
       height: 100%;
       overflow-y: auto;
     }
     ```
   - Preserve: 左侧分隔线 `border-left: 1px solid #ebeef5`、`padding-left: 18px`
   - Verify: 右栏与左栏等高；内容超出时右栏内部滚动，弹窗本身不滚动

4. `frontend/src/views/ChannelView.vue` — 窄视口回退
   - Change: 在已有的 `@media (max-width: 900px)` 分支内，除保留 `grid-template-columns: minmax(0, 1fr)` 外，追加 `height: auto`（单列时不需要等高约束）
   - Preserve: 单列堆叠顺序（视频在上、云台在下）
   - Verify: 窄视口下弹窗按内容自然高度显示，不出现空白或裁切

## Scope

- Inherit: 无（样式为 `scoped`，仅本弹窗）
- Verify: 弹窗内云台面板变长后（见 `ptz-panel-control-consolidation.md` 会缩短，但若日后加项）右栏滚动正常
- Exclude: `styles/main.css` 的 `.video-box` / `.video-container` 全局定义（不改，避免影响 `LiveView`、`PlaybackView`、`CloudRecordView`）；`PlaybackView.vue` 的回放弹窗（结构不同：视频在上、控制条在下，非左右分栏）

## Validation

- Product: 打开通道管理 → 点任一通道「预览」→ 弹窗内左右两栏上下边界对齐，视频居中且不变形，云台面板可完整滚动查看
- Interface:
  - 路由 `/channels` 预览弹窗
  - 视口高度 768p / 1080p / 1440p
  - 视口宽度 >900px（两栏）与 <900px（单列）
  - 通道离线（云台面板禁用）与在线两种状态
  - 云台面板内容最长时（展开喊话状态）右栏滚动
- System: 全局 `.video-box` 的 16:9 契约未被修改；其他三个使用 `.video-box` 的页面表现不变
- Repository: `npm --prefix frontend run build` → 构建成功

## Stop conditions

- Stop if 需要保留左右栏**各自独立**的高度（则等高不成立，本计划不适用）
- Stop if 弹窗改为固定像素高度而非视口比例（则 `62vh` 需替换为该固定值）

## Design documentation

- After acceptance and validation: 建议在 `ChannelView.vue` 的 `.preview-wrap` 注释记录「本行高度由 .preview-wrap 统一持有（62vh），两栏拉伸等高；.video-box 的 16:9 通过栏内居中满足」；仓库当前无设计文档，不新建
