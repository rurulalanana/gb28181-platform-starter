<template>
  <div class="ptz-panel">
    <!-- 云台方向盘：按住持续转动，松开停止（全局 pointerup 兜底，见脚本） -->
    <div class="ptz-grid">
      <el-button
        v-for="d in DIRECTIONS"
        :key="d.cmd"
        class="ptz-btn"
        :style="{ gridArea: d.area }"
        :disabled="noPtz"
        :aria-label="d.label"
        @pointerdown="beginMove(d.cmd, $event)"
      >
        <el-icon :size="18" aria-hidden="true"><component :is="d.icon" /></el-icon>
      </el-button>

      <!--
        中心键 = 语音喊话开关。
        原先喊话单独占一个 Tab，340px 侧栏里四个 Tab 挤满一行；挪到方向盘正中
        不占额外高度，开启时按钮转红即可表达状态。
      -->
      <el-button
        :style="{ gridArea: 'c' }"
        class="ptz-btn ptz-talk"
        :class="{ 'is-talking': talking }"
        :type="talking ? 'danger' : 'default'"
        :disabled="noTalk"
        :title="talking ? '停止喊话' : tip('TALK', '开始喊话')"
        :aria-label="talking ? '停止喊话' : '开始喊话'"
        @click="toggleTalk"
      >
        <el-icon :size="18" aria-hidden="true"><Microphone /></el-icon>
      </el-button>
    </div>

    <!--
      变倍 / 光圈 / 聚焦是三条不同指令：变倍 0x10/0x20、光圈 0x44/0x48、聚焦 0x41/0x42
      （标准附录 A.3.1）。早期把光圈映射到变倍，按光圈画面会缩放；三者必须分开。
      变倍按住持续、松开停止，用 pointerdown；光圈/聚焦是单步动作，click 即可。
    -->
    <!--
      变倍（FI 指令的 ZoomIn / ZoomOut）。不按场景隐藏：早期用 mode 只在回放时显示，
      而回放并没有云台面板，等于全平台都出不来。是否生效取决于设备，面板不替用户判断。
    -->
    <div class="ptz-row ptz-zoom-row">
      <el-button-group class="zoom-group">
        <el-button
          size="small"
          :disabled="noFi"
          title="放大（变倍+）"
          aria-label="放大（变倍+）"
          @pointerdown="beginMove('zoomin', $event)"
        >
          <el-icon aria-hidden="true"><Plus /></el-icon>
        </el-button>
        <el-button
          size="small"
          :disabled="noFi"
          title="缩小（变倍−）"
          aria-label="缩小（变倍−）"
          @pointerdown="beginMove('zoomout', $event)"
        >
          <el-icon aria-hidden="true"><Minus /></el-icon>
        </el-button>
      </el-button-group>
    </div>
    <div class="ptz-row ptz-zoom-row">
      <el-button-group class="zoom-group">
        <el-button size="small" :disabled="noFi" title="光圈放大" @click="cmd('fi', { command: 'irisin' }, '光圈 +')">
          光圈+
        </el-button>
        <el-button size="small" :disabled="noFi" title="光圈缩小" @click="cmd('fi', { command: 'irisout' }, '光圈 −')">
          光圈−
        </el-button>
      </el-button-group>
      <el-button-group class="zoom-group">
        <el-button size="small" :disabled="noFi" title="聚焦近" @click="cmd('fi', { command: 'focusnear' }, '聚焦近')">
          聚焦+
        </el-button>
        <el-button size="small" :disabled="noFi" title="聚焦远" @click="cmd('fi', { command: 'focusfar' }, '聚焦远')">
          聚焦−
        </el-button>
      </el-button-group>
    </div>

    <!--
      精简模式（实时预览页）只保留方向 + 变倍/光圈。完整模式用 Tab 不用折叠面板：
      右侧栏仅 ~340px，四组折叠标题占 4×38px，展开后还要滚动；Tab 只占一行 ~40px。
    -->
    <template v-if="!simple">
    <el-tabs v-model="activeSection" class="ptz-tabs">
      <!-- 预置位 -->
      <el-tab-pane label="预置位" name="preset" :disabled="!canPreset">
        <div class="ptz-preset">
          <!--
            列出全部槽位而非只列已有槽位：用户想「存到 6 号」时不用猜哪个编号空着，
            空槽位就是「还能往哪儿存」的直观答案。已有行给调用/设置/删除，空槽只给设置。
          -->
          <div class="preset-head">
            <span class="preset-label">预置位</span>
            <span v-if="usedCount" class="preset-count">已用 {{ usedCount }}</span>
            <el-button
              size="small"
              text
              :loading="presetLoading"
              :disabled="!serial"
              title="向设备查询预置位列表"
              @click="refreshPresets()"
            >
              <el-icon><RefreshRight /></el-icon> 刷新
            </el-button>
          </div>

          <el-table
            :data="presetSlots"
            size="small"
            height="248"
            class="preset-table"
            :row-class-name="presetRowClass"
          >
            <el-table-column label="序号" width="64" align="center">
              <template #default="{ row }">
                <span class="mono">{{ row.presetId }}</span>
              </template>
            </el-table-column>
            <el-table-column label="备注" min-width="110">
              <template #default="{ row }">
                <!-- 只有「设备上存在」的槽位才有名字可改 -->
                <el-input
                  v-if="editingPreset.no === row.presetId"
                  ref="presetNameInput"
                  v-model="editingPreset.value"
                  size="small"
                  placeholder="留空=默认名称"
                  :disabled="editingPreset.saving"
                  @keydown.enter.prevent="commitPresetName(row)"
                  @keydown.esc.stop.prevent="cancelPresetName"
                  @blur="commitPresetName(row)"
                />
                <el-button
                  v-else-if="row.used"
                  link
                  type="primary"
                  class="preset-name-btn"
                  title="点击修改备注"
                  @click="startRenamePreset(row)"
                >
                  {{ row.name }}
                </el-button>
                <span v-else class="preset-name-empty">预置位 {{ row.presetId }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="158" align="center">
              <template #default="{ row }">
                <el-button
                  v-if="row.used"
                  link
                  type="primary"
                  :disabled="noPresetBtn"
                  @click="callPreset(row)"
                >
                  调用
                </el-button>
                <el-button
                  link
                  type="primary"
                  :disabled="noPresetBtn"
                  :title="row.used ? '用当前位置覆盖该预置位' : '把当前位置存到该编号'"
                  @click="savePreset(row)"
                >
                  设置
                </el-button>
                <el-button
                  v-if="row.used"
                  link
                  type="danger"
                  :disabled="noPresetBtn"
                  @click="removePreset(row)"
                >
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <p v-if="!presetQueriedAt" class="preset-hint">
            {{ presetLoading ? '正在向设备查询预置位…' : '设备未应答，点「刷新」重试；空槽位可直接「设置」' }}
          </p>
          <!--
            查询成功后提示行隐藏但位置必须留着：一隐藏整个面板矮一行，
            在预览弹窗里会让左栏黑边高度跟着闪（行高取两栏较大值）。用等高空行占位。
          -->
          <p v-else class="preset-hint preset-hint-placeholder" aria-hidden="true" />
        </div>
      </el-tab-pane>

      <!-- 设备功能 -->
      <el-tab-pane label="设备" name="device" :disabled="!canDevice">
        <!--
          用 grid 而非 flex-wrap：flex-wrap 按按钮自然宽度换行，实测「雨刷开/雨刷关/补光开」
          占满一行后「补光关」会被单独甩到第二行。固定两列等宽后每行严格对齐。
        -->
        <div class="ptz-actions">
          <el-button size="small" :disabled="noAux" :title="tip('AUX_SWITCH', '雨刷')" @click="cmd('wiper', { command: 'on' })">
            <el-icon><Umbrella /></el-icon> 雨刷开
          </el-button>
          <el-button size="small" :disabled="noAux" @click="cmd('wiper', { command: 'off' })">
            雨刷关
          </el-button>
          <el-button size="small" :disabled="noAux" @click="cmd('fillLight', { command: 'on' })">
            <el-icon><Sunny /></el-icon> 补光开
          </el-button>
          <el-button size="small" :disabled="noAux" @click="cmd('fillLight', { command: 'off' })">
            补光关
          </el-button>
          <el-button size="small" :disabled="noRecording" :title="tip('RECORDING', '设备录像')" @click="cmd('record', { command: 'start' })">
            <el-icon><VideoCamera /></el-icon> 设备录像
          </el-button>
          <el-button size="small" :disabled="noRecording" @click="cmd('record', { command: 'stop' })">
            停止录像
          </el-button>
          <el-button size="small" :disabled="noGuard" :title="tip('GUARD', '布防')" @click="cmd('guard', { command: 'setguard' })">
            <el-icon><Lock /></el-icon> 布防
          </el-button>
          <el-button size="small" :disabled="noGuard" @click="cmd('guard', { command: 'resetguard' })">
            撤防
          </el-button>
        </div>

        <!--
          对讲（仅 SDK 直连设备）。
          为什么不做成方向盘中心那个按钮：那个走的是国标 /control/talk（SIP 信令，靠设备
          自己回传音频），而大华直连设备的对讲是 NetSDK 的 StartTalkEx + 浏览器采集，
          两套完全不同的通路。放在「设备」页底部，与相机/云台扩展保持同一套 cam-* 版式。
        -->
        <div v-if="isSdkDevice" class="cam-config talk-group">
          <div class="cam-row">
            <label class="cam-label">对讲</label>
            <div class="cam-control">
              <el-button
                size="small"
                :type="talk.active ? 'danger' : 'primary'"
                :loading="talk.starting"
                :disabled="noDevice || talk.busy"
                @click="toggleDahuaTalk"
              >
                <el-icon><Microphone /></el-icon>
                {{ talk.active ? '结束对讲' : '开始对讲' }}
              </el-button>
              <span class="cam-value">{{ talkHint }}</span>
            </div>
          </div>
          <div class="cam-row">
            <label class="cam-label">收听设备</label>
            <div class="cam-control">
              <el-switch
                v-model="talk.listen"
                :disabled="!talk.active"
                @change="onListenToggle"
              />
              <span class="cam-value">{{ listenHint }}</span>
            </div>
          </div>
          <div class="cam-row">
            <label class="cam-label">麦克风</label>
            <div class="cam-control">
              <el-switch v-model="talk.mic" :disabled="!talk.active" @change="onMicToggle" />
              <span class="cam-value">{{ talk.mic ? '按住说话不用按，持续采集中' : '已静音（只听）' }}</span>
            </div>
          </div>
          <p class="talk-note">
            对讲为 8 kHz 单声道，音质低于普通通话属正常；同一台设备同时只能一人喊话。
          </p>
        </div>
      </el-tab-pane>

      <!-- 相机参数：SDK 直连设备专属能力，国标设备不渲染此 tab -->
      <el-tab-pane v-if="isSdkDevice" label="相机" name="camera" :disabled="!canDevice">
        <CameraConfigPanel :serial="serial" :code="code" :disabled="busyCamera" />
      </el-tab-pane>

      <!-- 高级操作 -->
      <!--
        云台扩展（仅 SDK 直连设备）。

        <p>为什么单独一页：巡航组、巡迹、限位、速度、空闲/定时动作是大华 NetSDK 的能力，
        国标协议里没有对应信令；放进「高级」会让国标设备也看到一堆点了报错的按钮。
        配置类读写走设备 CGI，动作类走 SDK —— 两套后端入口，前端不区分。
      -->
      <el-tab-pane v-if="isSdkDevice" label="云台扩展" name="ptzext" :disabled="!canDevice">
        <div v-loading="ptzExt.loading" class="cam-config">
          <!--
            分组交互与「相机」页保持一致：右栏只有 320px 左右，横向 tab 会被截断，
            所以用会换行的分段按钮 + 固定高度内容区，操作按钮永远贴在底部可见。
            分组按钮上带一个小圆点表示「这一页有未保存的改动」，
            不用逐页点进去才能发现忘了保存。
          -->
          <div class="cam-groups">
            <button
              v-for="g in EXT_GROUPS"
              :key="g.key"
              type="button"
              class="cam-group-chip"
              :class="{ 'is-active': extTab === g.key, 'is-dirty': dirtyMap[g.key] }"
              @click="extTab = g.key"
            >
              {{ g.label }}
            </button>
          </div>

          <div class="cam-pane">
            <!--
              页头整个去掉。
              早期这里是「加粗页名 + 需保存/即时生效徽标」，但：
              ① 页名在左侧分组按钮上已经高亮显示了，再写一遍是重复；
              ② 「需保存」这类徽标对用户没有决策价值 —— 有改动时底部保存按钮会亮，
                 纯动作页本来就没有保存按钮，徽标在说一件按钮已经说清楚的事。
              去掉后 320px 宽的面板能多出两行给真正的表格内容。
            -->

            <!-- 巡航组 -->
            <template v-if="extTab === 'tour'">
              <!--
                版式对齐设备「巡航群組」页：工具栏 + 一张列表，展开行编辑预置点。
                早期是一串「线号下拉 + 名称 + 启用 + 三列输入框」，320px 宽的右栏里
                挤成两行换行，用户找不到重点。列表铺开 + 展开编辑才是设备的样子。
              -->
              <div class="tour-bar">
                <el-button size="small" type="primary" :disabled="noDevice || isRunning" @click="newTour()">
                  新增巡航组
                </el-button>
                <el-button size="small" :disabled="noDevice || isRunning || !configuredTours.length"
                           @click="clearAllTours">
                  清空
                </el-button>
                <!-- 刷新始终保持可点：轮询万一取到陈旧状态，这是用户的手动兑底线 -->
                <el-button size="small" :loading="ptzExt.loading" :disabled="noDevice"
                           @click="loadPtzExt()">
                  刷新
                </el-button>
                <span class="tour-count">
                  已配置 {{ configuredTours.length }} / {{ TOUR_MAX }}
                </span>
              </div>

              <el-table
                :data="configuredTours"
                size="small"
                row-key="route"
                :expand-row-keys="expandedTours"
                class="tour-table"
                @expand-change="onTourExpand"
              >
                <el-table-column type="expand" width="26">
                  <template #default="{ row }">
                    <div class="tour-pts">
                      <div class="tour-pt-head">
                        <span class="tour-pt-col is-idx">#</span>
                        <span class="tour-pt-col is-preset">预置位</span>
                        <span class="tour-pt-col is-stay">停留</span>
                        <span class="tour-pt-col is-speed">速度</span>
                        <span class="tour-pt-col is-del"></span>
                      </div>
                      <div v-for="(pt, i) in row.points" :key="i" class="tour-pt-row">
                        <span class="tour-pt-col is-idx">{{ i + 1 }}</span>
                        <el-select v-model="pt.preset" class="tour-pt-col is-preset"
                                   size="small" filterable>
                          <el-option v-for="o in presetOptions" :key="o.value"
                                     :label="o.label" :value="o.value" />
                        </el-select>
                        <el-input-number v-model="pt.stay" :min="15" :max="3600" :controls="false"
                                         size="small" class="tour-pt-col is-stay"
                                         title="停留时间 15~3600 秒（设备对小于 15 秒的值会改回 15）" />
                        <el-input-number v-model="pt.speed" :min="1" :max="10" :controls="false"
                                         size="small" class="tour-pt-col is-speed" />
                        <el-button class="tour-pt-col is-del" size="small" text type="danger"
                                   title="移除该预置点" aria-label="移除该预置点"
                                   @click="row.points.splice(i, 1)">
                          <el-icon><Delete /></el-icon>
                        </el-button>
                      </div>
                      <div v-if="!row.points.length" class="tour-pt-empty">
                        没有预置点
                      </div>
                      <div class="tour-pt-bar">
                        <el-button size="small" :disabled="noDevice" @click="addPoint(row)">
                          添加预置点
                        </el-button>
                        <el-button size="small" :disabled="noDevice || !row.points.length"
                                   @click="row.points = []">
                          清空
                        </el-button>
                      </div>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="序号" width="44" align="center">
                  <template #default="{ row }">
                    <span class="mono">{{ row.route }}</span>
                  </template>
                </el-table-column>
                <!--
                  名称用 min-width 而不是 width —— 列宽总和小于面板宽度时，
                  Element Plus <b>不会自动拉伸</b>（只有 width 的列不参与分配剩余宽度），
                  表格右侧就会留一大块空白。给一列 min-width 让它吃掉剩余宽度。
                -->
                <el-table-column label="名称" min-width="150">
                  <template #default="{ row }">
                    <!--
                      设备只读这个字段：PtzTour[ch][route].Name 在这台 IPC 上
                      CGI setConfig 直接 400、SDK SetNewDevConfig 回 ok 但值不变
                      （预置点能写，Name 不能）。所以给 disabled + 说明，
                      比让用户改了保存、刷新后又变回去要诚实。
                    -->
                    <el-input v-model="row.name" size="small" disabled
                              title="该型号固件不支持修改巡航组名称（PtzTour.Name 只读），名称随设备出厂值" />
                  </template>
                </el-table-column>
                <el-table-column label="执行" width="58" align="center">
                  <template #default="{ row }">
                    <!--
                      这一列是「运行时启停」，不是配置项：设备「巡航群組」页的「執行」开关
                      点下去发的是 ptz.startTour / ptz.stopTour，与 PtzTour.Enable 无关。

                      开关状态直接来自设备 PtzMovement（见 activeRun），不是本地记的。
                      设备同一时刻只能跑一个动作：任何动作在跑时，
                      其它行的开关都是<b>禁用</b>的（设备界面同样如此）。
                    -->
                    <el-switch
                      :model-value="runningTour === row.route"
                      size="small"
                      :disabled="noDevice || !row.points.length
                        || (isRunning && runningTour !== row.route)"
                      :title="!row.points.length ? '这条线没有预置点，无法执行'
                        : (runningTour === row.route ? '停止巡航'
                        : (isRunning ? `「${runningLabel}」正在运行，请先停止`
                                     : '开始巡航'))"
                      @change="toggleTourRun(row)"
                    />
                  </template>
                </el-table-column>
                <el-table-column label="删除" width="52" align="center">
                  <template #default="{ row }">
                    <el-button link type="danger" size="small"
                               title="清空名称、预置点并停用" aria-label="清空该巡航组"
                               :disabled="noDevice"
                               @click="clearTour(row)">
                      <el-icon><Delete /></el-icon>
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </template>

            <!--
              巡迹（对齐设备「巡跡」页）

              设备列序：序號 / 名稱 / 設置 / 停留時間(S) / 執行 / 刪除。
              其中：
              · 「設置」= 录制的三个动作（开始录 / 结束录 / 删除内容），是即时指令；
              · 「停留時間(S)」设备 CGI 里<b>没有这个字段</b>（AutoPattern 只有 Enable+Name），
                它只是设备前端的状态，所以这里不给可编辑控件，只在有值时显示，
                避免让用户以为设了能存下去；
              · 「執行」是运行时开关（run / stop），不是配置。
            -->
            <template v-else-if="extTab === 'pattern'">
              <div class="tour-bar">
                <el-button size="small" type="primary" :disabled="noDevice || isRunning" @click="newPattern()">
                  新增巡迹
                </el-button>
                <el-button size="small" :disabled="noDevice || isRunning || !configuredPatterns.length"
                           @click="clearAllPatterns">
                  清空
                </el-button>
                <el-button size="small" :loading="ptzExt.loading" :disabled="noDevice"
                           @click="loadPtzExt()">
                  刷新
                </el-button>
                <span class="tour-count">已配置 {{ configuredPatterns.length }} / {{ PATTERN_MAX }}</span>
              </div>

              <!--
                ⚠️ 各列声明宽度之和必须<b>小于面板宽度</b>（约 457px）。
                `table-layout: fixed` 下浏览器不会把 table 压得比列宽之和更窄，
                所以总和一旦超出，`width: 100%` 就失效、右侧留白（早期 466px 时正是如此）。
                当前合计 430，留了余量。改列宽时请一起算。
              -->
              <el-table :data="configuredPatterns" size="small" row-key="patternId"
                        class="tour-table">
                <el-table-column label="序号" width="44" align="center">
                  <template #default="{ row }"><span class="mono">{{ row.patternId }}</span></template>
                </el-table-column>
                <el-table-column label="名称" min-width="130">
                  <template #default="{ row }">
                    <el-input v-model="row.name" size="small" :disabled="noDevice"
                              :placeholder="'巡跡 ' + row.patternId" />
                  </template>
                </el-table-column>
                <el-table-column label="设置" width="66" align="center">
                  <template #default="{ row }">
                    <el-button link size="small" :disabled="noDevice"
                               :title="recordingPattern === row.patternId ? '结束录制' : '开始录制该巡迹'"
                               :type="recordingPattern === row.patternId ? 'danger' : 'primary'"
                               @click="togglePatternRecord(row)">
                      {{ recordingPattern === row.patternId ? '停录' : '录制' }}
                    </el-button>
                  </template>
                </el-table-column>
                <el-table-column label="停留时间(S)" width="90" align="center">
                  <template #default="{ row }">
                    <!--
                      停留时间<b>存在另一个配置项里</b>：PatternPlayDetail[ch][idx]
                      .StartPatternPlayDelay（设备存毫秒，界面一律秒）。
                      出厂默认 15 秒，范围 0~3600（能力位 DurationMin/Max）。

                      别被 AutoPattern 误导：那个配置项确实只有 Enable+Name，
                      早期就因此误判成「设备不落盘停留时间」而把这里做成只读。
                    -->
                    <el-input-number v-model="row.staySeconds" :min="PATTERN_STAY_MIN"
                                     :max="PATTERN_STAY_MAX" :controls="false" size="small"
                                     class="pattern-stay" :disabled="noDevice"
                                     title="播放前停留秒数 0~3600（设备存毫秒，默认 15 秒）" />
                  </template>
                </el-table-column>
                <el-table-column label="执行" width="54" align="center">
                  <template #default="{ row }">
                    <!--
                      按 row.enable 判断能不能跑：设备 AutoPattern.Enable = 「这条录过内容」，
                      只有录过的才能回放。「新增但没录」的行同样是禁用态。
                      状态来自设备 PtzMovement（见 activeRun）；
                      有别的动作在跑时本行禁用，必须先停掉正在跑的那个。
                    -->
                    <el-switch :model-value="runningPattern === row.patternId" size="small"
                               :disabled="noDevice || !isPatternReady(row)
                                 || (isRunning && runningPattern !== row.patternId)"
                               :title="!isPatternReady(row) ? '这条巡迹还没录制内容，无法执行（先在「设置」列录制）'
                                 : (runningPattern === row.patternId ? '停止巡迹'
                                 : (isRunning ? `「${runningLabel}」正在运行，请先停止`
                                              : '运行巡迹'))"
                               @change="togglePatternRun(row)" />
                  </template>
                </el-table-column>
                <el-table-column label="删除" width="46" align="center">
                  <template #default="{ row }">
                    <el-button link type="danger" size="small" title="删除该巡迹的录制内容"
                               aria-label="删除该巡迹"
                               :disabled="noDevice" @click="clearPattern(row)">
                      <el-icon><Delete /></el-icon>
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </template>

            <!--
              线扫（对齐设备「線性掃描」页）

              版式与巡航组一致：工具栏 + 一张整表。
              列序与设备完全相同：序號 / 名稱 / 左邊界 / 右邊界 / 速度 / 執行 / 刪除。
              左/右边界不是输入框而是「把当前位置存为该边界」的动作按钮 ——
              设备上也是两个保存图标，因为边界值来自云台实际位置，手填没有意义。
            -->
            <template v-else-if="extTab === 'scan'">
              <div class="tour-bar">
                <el-button size="small" type="primary" :disabled="noDevice || isRunning" @click="newScan()">
                  新增线扫
                </el-button>
                <el-button size="small" :disabled="noDevice || isRunning || !configuredScans.length"
                           @click="clearAllScans">
                  清空
                </el-button>
                <el-button size="small" :loading="ptzExt.loading" :disabled="noDevice"
                           @click="loadPtzExt()">
                  刷新
                </el-button>
                <span class="tour-count">已配置 {{ configuredScans.length }} / {{ SCAN_MAX }}</span>
              </div>

              <!-- 列宽合计 440 < 面板 457，同「巡迹」页：改列宽记得一起算 -->
              <el-table :data="configuredScans" size="small" row-key="scanId" class="tour-table">
                <el-table-column label="序号" width="44" align="center">
                  <template #default="{ row }"><span class="mono">{{ row.scanId }}</span></template>
                </el-table-column>
                <el-table-column label="名称" min-width="118">
                  <template #default="{ row }">
                    <el-input v-model="row.name" size="small" :disabled="noDevice"
                              :placeholder="'線性掃描 ' + row.scanId" />
                  </template>
                </el-table-column>
                <el-table-column label="左边界" width="56" align="center">
                  <template #default="{ row }">
                    <el-button link size="small" :disabled="noDevice"
                               :type="row.leftSet ? 'success' : 'default'"
                               :title="row.leftSet ? '已设置，点击用当前位置覆盖' : '以当前位置为左边界'"
                               @click="setScanLimit(row, true)">
                      {{ row.leftSet ? '已设' : '设' }}
                    </el-button>
                  </template>
                </el-table-column>
                <el-table-column label="右边界" width="56" align="center">
                  <template #default="{ row }">
                    <el-button link size="small" :disabled="noDevice"
                               :type="row.rightSet ? 'success' : 'default'"
                               :title="row.rightSet ? '已设置，点击用当前位置覆盖' : '以当前位置为右边界'"
                               @click="setScanLimit(row, false)">
                      {{ row.rightSet ? '已设' : '设' }}
                    </el-button>
                  </template>
                </el-table-column>
                <el-table-column label="速度" width="66" align="center">
                  <template #default="{ row }">
                    <el-select v-model="row.speed" size="small" :disabled="noDevice"
                               class="scan-speed">
                      <el-option v-for="n in SCAN_SPEEDS" :key="n" :label="n" :value="n - 1" />
                    </el-select>
                  </template>
                </el-table-column>
                <el-table-column label="执行" width="54" align="center">
                  <template #default="{ row }">
                    <!-- 状态来自设备 PtzMovement；有别的动作在跑时本行禁用，须先停正在跑的那个 -->
                    <el-switch :model-value="runningScan === row.scanId" size="small"
                               :disabled="noDevice || !row.leftSet || !row.rightSet
                                 || (isRunning && runningScan !== row.scanId)"
                               :title="!row.leftSet || !row.rightSet
                                 ? '左右边界都设置后才能执行'
                                 : (runningScan === row.scanId ? '停止线扫'
                                 : (isRunning ? `「${runningLabel}」正在运行，请先停止`
                                              : '开始线扫'))"
                               @change="toggleScanRun(row)" />
                  </template>
                </el-table-column>
                <el-table-column label="删除" width="46" align="center">
                  <template #default="{ row }">
                    <el-button link type="danger" size="small" title="清空该线扫"
                               aria-label="清空该线扫"
                               :disabled="noDevice" @click="clearScan(row)">
                      <el-icon><Delete /></el-icon>
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </template>

            <!--
              水平旋转（对齐设备「水準旋轉」页，逐项核对过）

              设备该页只有两项：
                · 旋轉速度 —— 滑块 1~8，两侧各一个 − / + 按钮，当前值显示在右侧
                · 開 始 / 停 止 —— 同一个按钮切换

              【为什么以前是错的】早期平台把这里写成「只读显示低/中/高 + 去速度页调整」，
              那是凭印象做的。实测设备这两页改的是<b>不同字段</b>：
                · 水準旋轉页 → AutoPan[ch].PanSpeed，范围 1~8（本页）
                · PTZ速度页  → PtzSpeed[ch].PtzSpeed，范围 0~4（低/中/高）
              所以「去速度页调」根本调不到这个值。现在按设备原样做成本页可调。

              设备是拖动即落盘（每次 setConfig），平台做成「改完点保存」以便撤销，
              最终落盘效果一致。
            -->
            <template v-else-if="extTab === 'rotate'">
              <div class="cam-row">
                <label class="cam-label">旋转速度</label>
                <div class="cam-control">
                  <!--
                    设备在旋转期间把滑块置灰并提示「一些設定無法在PTZ水平旋轉期間操作」。
                    所以任何动作在跑时（不只水平旋转）滑块都禁掉。
                  -->
                  <el-button size="small" :disabled="noDevice || isRunning || panSpeed <= AUTO_PAN_MIN"
                             title="降低一档" aria-label="降低旋转速度"
                             @click="panSpeed = Math.max(AUTO_PAN_MIN, panSpeed - 1)">
                    <el-icon><Minus /></el-icon>
                  </el-button>
                  <el-slider v-model="panSpeed" :min="AUTO_PAN_MIN" :max="AUTO_PAN_MAX"
                             :step="1" :show-tooltip="false" :disabled="noDevice || isRunning"
                             class="pan-speed-slider" />
                  <el-button size="small" :disabled="noDevice || isRunning || panSpeed >= AUTO_PAN_MAX"
                             title="提高一档" aria-label="提高旋转速度"
                             @click="panSpeed = Math.min(AUTO_PAN_MAX, panSpeed + 1)">
                    <el-icon><Plus /></el-icon>
                  </el-button>
                  <span class="pan-speed-value mono">{{ panSpeed }}</span>
                </div>
              </div>
              <div class="cam-row">
                <label class="cam-label">运行</label>
                <div class="cam-control">
                  <el-button v-if="!panRunning" size="small" type="primary"
                             :disabled="noDevice || isRunning"
                             :title="isRunning ? `「${runningLabel}」正在运行，请先停止` : '开始水平旋转'"
                             @click="togglePanRun(true)">开始</el-button>
                  <el-button v-else size="small" type="danger" :disabled="noDevice"
                             @click="togglePanRun(false)">停止</el-button>
                  <!-- 设备在旋转期间会把滑块置灰并提示「一些設定無法在PTZ水平旋轉期間操作」 -->
                  <span v-if="panRunning" class="ext-hint">
                    旋转中，部分设置不可操作
                  </span>
                </div>
              </div>
            </template>

            <!--
              旋转限制（对齐设备「PTZ旋轉限制」页）

              设备结构：启用 + 上限(設置/查看) + 下限(設置/查看)。
              「設置」= 以当前位置为边界（即时动作），「查看」= 读回设备存的坐标。
              注意这台 IPC 的 PtzLimit 只暴露 UpLimit，没有 DownLimit ——
              下限位置会一直显示「未设置」，那是设备固件能力，不是平台没实现。
            -->
            <template v-else-if="extTab === 'limit'">
              <div class="cam-row">
                <label class="cam-label">启用</label>
                <div class="cam-control">
                  <el-switch v-model="limitForm.enable" size="small" />
                </div>
              </div>
              <div class="cam-row">
                <label class="cam-label">上限</label>
                <div class="cam-control">
                  <el-button size="small" @click="ptzAct('limit-up', {}, '上限已设为当前位置')">设置</el-button>
                  <!-- 设备有「查看」按钮：读回设备存的坐标（这里直接重读整份配置） -->
                  <el-button size="small" :loading="ptzExt.loading" :disabled="noDevice"
                             @click="viewLimit">查看</el-button>
                  <span class="ext-badge">{{ limitForm.upBoundary }}</span>
                </div>
              </div>
              <div class="cam-row">
                <label class="cam-label">下限</label>
                <div class="cam-control">
                  <el-button size="small" @click="ptzAct('limit-down', {}, '下限已设为当前位置')">设置</el-button>
                  <el-button size="small" :loading="ptzExt.loading" :disabled="noDevice"
                             @click="viewLimit">查看</el-button>
                  <span class="ext-badge" title="该型号 PtzLimit 未暴露 DownLimit 字段，设备不回读下限坐标">
                    {{ limitForm.downBoundary }}
                  </span>
                </div>
              </div>
            </template>

            <!--
              速度（对齐设备「PTZ速度」页，逐项核对过）

              设备该页只有<b>一个</b>三档控件：
                PTZ速度  [低]──[中]──[高]      應 用 / 刷 新
              滑块 aria-valuemin=0 / aria-valuemax=2，与存储字段一一对应：
                滑块 0 = 低  ←→  PtzSpeed=0
                滑块 1 = 中  ←→  PtzSpeed=1
                滑块 2 = 高  ←→  PtzSpeed=2

              【为什么之前回显错】早期平台自创了「0~4 五档、低=0/中=2/高=4」，
              于是设备存 1（中）时被算成「低」，用户看到的就是「设备是中、平台是低」。
              现在一一对应，不再做任何映射。

              【为什么没有「巡迹速度」】设备界面没有这个入口（PatternSpeed 出厂 5
              由固件自己维护）。平台不展示、保存时也不传，后端会沿用设备现值。
            -->
            <template v-else-if="extTab === 'speed'">
              <div class="tour-bar">
                <el-button size="small" :loading="ptzExt.loading" :disabled="noDevice"
                           @click="loadPtzExt()">
                  刷新
                </el-button>
              </div>
              <div class="ext-section">PTZ 速度</div>
              <div class="cam-row">
                <label class="cam-label">速度</label>
                <div class="cam-control">
                  <el-radio-group v-model="speedForm.ptzSpeed" size="small">
                    <el-radio-button :value="0">低</el-radio-button>
                    <el-radio-button :value="1">中</el-radio-button>
                    <el-radio-button :value="2">高</el-radio-button>
                  </el-radio-group>
                </div>
              </div>
            </template>

            <!-- 空闲动作 -->
            <template v-else-if="extTab === 'idle'">
              <div class="tour-bar">
                <el-button size="small" :loading="ptzExt.loading" :disabled="noDevice"
                           @click="loadPtzExt()">
                  刷新
                </el-button>
              </div>
              <div class="ext-section">触发条件</div>
              <div class="cam-row">
                <label class="cam-label">启用</label>
                <div class="cam-control">
                  <el-switch v-model="idleForm.enable" size="small" />
                </div>
              </div>
              <div class="cam-row">
                <label class="cam-label">空闲时间</label>
                <div class="cam-control">
                  <el-input-number v-model="idleForm.timerMinutes" :min="1" :max="60" size="small"
                                   controls-position="right" style="width: 96px" />
                  <span class="ext-unit">分钟（1~60）</span>
                </div>
              </div>

              <div class="ext-section">执行动作</div>
              <div class="cam-row">
                <label class="cam-label">动作</label>
                <div class="cam-control">
                  <el-select v-model="idleForm.function" size="small" style="width: 104px">
                    <el-option label="无" value="None" />
                    <el-option label="预置位" value="Preset" />
                    <el-option label="巡航组" value="Tour" />
                    <el-option label="巡迹" value="Pattern" />
                    <el-option label="线扫" value="Scan" />
                  </el-select>
                  <span class="ext-unit" v-if="idleForm.function !== 'None'">目标</span>
                  <!--
                    目标用下拉，不是数字框 ——— 对齐设备「編號」下拉的 `编号:名称`。
                    只列已配置的项，数字框会让人填出不存在的编号（设备收到只会静默不动）。
                    「无」动作时设备不显示「編號」，这里同样隐藏。
                  -->
                  <el-select v-if="idleForm.function !== 'None'" v-model="idleForm.target"
                             size="small" style="width: 148px" placeholder="选择目标">
                    <el-option v-for="o in targetOptions(idleForm.function, idleForm.target)"
                               :key="o.value" :label="o.label" :value="o.value" />
                  </el-select>
                </div>
              </div>
            </template>

            <!--
              定时任务（对齐设备「已排定工作」页）

              设备结构：工具栏(新增排程任務/清空/刷新) + 表格
              「序號 / 工作動作 / 刪除」，行首有 + 号展开详细规则。
              这里同样：列表只放序号、动作、删除，展开后编辑目标/归位/时间段。
            -->
            <template v-else-if="extTab === 'schedule'">
              <div class="tour-bar">
                <el-button size="small" type="primary" :disabled="noDevice" @click="newAuto()">
                  新增排程任务
                </el-button>
                <el-button size="small" :disabled="noDevice || !configuredAutos.length"
                           @click="clearAllAutos">
                  清空
                </el-button>
                <el-button size="small" :loading="ptzExt.loading" :disabled="noDevice"
                           @click="loadPtzExt()">
                  刷新
                </el-button>
                <span class="tour-count">已配置 {{ configuredAutos.length }} / {{ AUTO_MAX }}</span>
              </div>

              <el-table :data="configuredAutos" size="small" row-key="index"
                        :expand-row-keys="expandedAutos" class="tour-table"
                        @expand-change="onAutoExpand">
                <el-table-column type="expand" width="26">
                  <template #default="{ row }">
                    <div class="tour-pts">
                      <!-- 工作动作已在行内下拉，展开区只放设备「排程任務設定」里的参数 -->
                      <div class="cam-row" v-if="row.function !== 'None'">
                        <label class="cam-label">目标</label>
                        <div class="cam-control">
                          <el-select v-model="row.target" size="small" style="width: 148px"
                                     placeholder="选择目标">
                            <el-option v-for="o in targetOptions(row.function, row.target)"
                                       :key="o.value" :label="o.label" :value="o.value" />
                          </el-select>
                        </div>
                      </div>
                      <div class="cam-row">
                        <label class="cam-label">返回原位</label>
                        <div class="cam-control">
                          <el-input-number v-model="row.homingSeconds" :min="5" :max="86400" size="small"
                                           controls-position="right" style="width: 96px" />
                          <span class="ext-unit">秒</span>
                        </div>
                      </div>
                      <!--
                        时段：<b>逐段带星期</b>。

                        设备存储是 TimeSection[7][6] —— 7 天 × 每天 6 段，
                        每段自己有启用标记。所以：
                          · 周一可以 08:00-12:00，周二可以 19:00-23:00（每天不同）
                          · 同一天也可以有上午 + 下午两段
                        早期平台是「勾选星期 + 一个起止时间」共用一个时段，
                        且只写 TimeSection[d][0]，其余 5 段保留设备旧值 ——
                        于是界面删掉的时段在设备上仍然生效。
                      -->
                      <div class="cam-row is-top">
                        <label class="cam-label">时段</label>
                        <div class="cam-control is-wrap auto-shifts">
                          <div v-for="(rg, ri) in row.ranges" :key="ri" class="auto-shift">
                            <el-select v-model="rg.day" size="small" class="auto-shift-day"
                                       :disabled="noDevice">
                              <el-option v-for="(d, di) in WEEK_LABELS" :key="di"
                                         :label="d" :value="di" />
                            </el-select>
                            <el-time-picker v-model="rg.from" format="HH:mm" value-format="HH:mm"
                                            size="small" placeholder="起" style="width: 84px"
                                            :disabled="noDevice" />
                            <span class="ext-unit">至</span>
                            <el-time-picker v-model="rg.to" format="HH:mm" value-format="HH:mm"
                                            size="small" placeholder="止" style="width: 84px"
                                            :disabled="noDevice" />
                            <el-button link type="danger" size="small" title="删除该时段"
                                       aria-label="删除该时段" :disabled="noDevice"
                                       @click="row.ranges.splice(ri, 1)">
                              <el-icon><Delete /></el-icon>
                            </el-button>
                          </div>
                          <div v-if="!row.ranges.length" class="ext-hint">
                            还没有时段，任务不会触发
                          </div>
                          <el-button size="small" :disabled="noDevice"
                                     :title="'每个星期最多 ' + AUTO_SECTIONS_PER_DAY + ' 段'"
                                     @click="addAutoRange(row)">
                            添加时段
                          </el-button>
                        </div>
                      </div>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="序号" width="44" align="center">
                  <template #default="{ row }"><span class="mono">{{ row.index + 1 }}</span></template>
                </el-table-column>
                <!-- 工作动作用 min-width 吃掉剩余宽度，否则表格右侧留一大块空白 -->
                <el-table-column label="工作动作" min-width="150">
                  <template #default="{ row }">
                    <!--
                      设备「已排定工作」的工作動作<b>就是行内下拉框</b>（截图为证：
                      序号后面直接是一个显示「無」的 combobox），不是只读文本。
                      早期平台做成文本 + 另一个「启用」开关，两项都不对：
                      设备没有启用列，它用「工作動作=無」表示未启用。
                    -->
                    <el-select v-model="row.function" size="small" class="auto-fn"
                               :disabled="noDevice" @change="onAutoFunctionChange(row)">
                      <el-option label="无" value="None" />
                      <el-option label="预置位" value="Preset" />
                      <el-option label="巡航组" value="Tour" />
                      <el-option label="巡迹" value="Pattern" />
                      <el-option label="线扫" value="Scan" />
                    </el-select>
                  </template>
                </el-table-column>
                <el-table-column label="删除" width="52" align="center">
                  <template #default="{ row }">
                    <el-button link type="danger" size="small" title="清空该排程任务"
                               aria-label="清空该排程任务"
                               :disabled="noDevice" @click="clearAuto(row)">
                      <el-icon><Delete /></el-icon>
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </template>

            <!--
              开机动作 已移除：设备「PTZ設置」的 12 个菜单里没有「開機動作」
              （預置點/巡航群組/線性掃描/巡跡/水準旋轉/PTZ速度/閒置動作/充電/
              PTZ旋轉限制/已排定工作/PTZ 維護/協議），设备不支持上电动作。
              平台硬造一页只会让人以为设备上有对应项。
            -->

            <!--
              维护（对齐设备「PTZ 維護」页）
              设备只有两个按钮：PTZ重新啟動 / 預設值。
            -->
            <template v-else>
              <div class="cam-row">
                <label class="cam-label">重启</label>
                <div class="cam-control">
                  <el-popconfirm title="设备会整机重启并离线约 1 分钟，确定执行？"
                                 @confirm="ptzAct('reboot', {}, '重启指令已下发')">
                    <template #reference>
                      <el-button size="small" type="warning" plain>PTZ 重启（整机）</el-button>
                    </template>
                  </el-popconfirm>
                </div>
              </div>
              <div class="cam-row">
                <label class="cam-label">恢复默认</label>
                <div class="cam-control">
                  <el-popconfirm title="会把云台所有配置（预置位/巡航组/线扫/巡迹）恢复出厂，确定执行？"
                                 @confirm="ptzAct('ptz-default', {}, '已下发恢复默认值')">
                    <template #reference>
                      <el-button size="small" type="danger" plain>预设值</el-button>
                    </template>
                  </el-popconfirm>
                </div>
              </div>
            </template>
          </div>

          <!--
            运行中提示条：设备在某个动作运行时会禁用大部分控件并提示
            「一些設定無法在PTZ水平旋轉期間操作」/「…巡跡期間…」/「…巡迴期間…」。
            这里用一个统一提示条说明「谁在跑、要先停谁」，比一串灰按钮好懂。
          -->
          <div v-if="isRunning" class="ext-runbar">
            <el-icon><VideoPlay /></el-icon>
            <span>「{{ runningLabel }}」正在运行，其它动作需先停止它才能启动</span>
          </div>

          <!--
            操作栏固定贴底：与「相机」页的保存按钮同一位置，切分组时不跳动。
            保存按钮只在「有未保存改动」时高亮可点，与相机页的 dirty 语义一致；
            纯动作页不摆一个永远灰着的保存按钮，改放一行说明。
            运行中一律禁保存：设备在运行期间不接受配置写入。
          -->
          <div class="cam-footer">
            <template v-if="extTab === 'tour'">
              <el-button type="primary" size="small"
                         :disabled="noDevice || isRunning || !dirtyMap.tour"
                         @click="saveTour">保存巡航组</el-button>
            </template>
            <template v-else-if="extTab === 'scan'">
              <el-button type="primary" size="small"
                         :disabled="noDevice || isRunning || !dirtyMap.scan"
                         @click="saveScan">保存线扫参数</el-button>
            </template>
            <template v-else-if="extTab === 'pattern'">
              <el-button type="primary" size="small"
                         :disabled="noDevice || isRunning || !dirtyMap.pattern"
                         @click="savePattern">保存巡迹</el-button>
            </template>
            <template v-else-if="extTab === 'speed'">
              <el-button type="primary" size="small"
                         :disabled="noDevice || isRunning || !dirtyMap.speed"
                         @click="saveSpeed">保存速度</el-button>
            </template>
            <template v-else-if="extTab === 'rotate'">
              <!--
                水平旋转页的「旋轉速度」是设备可保存的配置项（AutoPan.PanSpeed），
                不是即时动作。早期这里写的是「该页按钮为即时指令，无需保存」，
                结果改完速度没法存下去 —— 设备上这一页本来就能调、也能存。
              -->
              <el-button type="primary" size="small"
                         :disabled="noDevice || isRunning || !dirtyMap.rotate"
                         @click="saveAutoPan">保存旋转速度</el-button>
            </template>
            <template v-else-if="extTab === 'limit'">
              <el-button type="primary" size="small"
                         :disabled="noDevice || isRunning || !dirtyMap.limit"
                         @click="saveLimit">保存限位开关</el-button>
            </template>
            <template v-else-if="extTab === 'idle'">
              <el-button type="primary" size="small"
                         :disabled="noDevice || isRunning || !dirtyMap.idle"
                         @click="saveIdle">保存空闲动作</el-button>
            </template>
            <template v-else-if="extTab === 'schedule'">
              <el-button type="primary" size="small"
                         :disabled="noDevice || isRunning || !dirtyMap.schedule"
                         @click="saveAuto">保存定时任务</el-button>
            </template>
            <template v-else>
              <span class="ext-hint">该页按钮为即时指令，无需保存</span>
            </template>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="高级" name="advanced" :disabled="!canAdvanced">
        <!--
          目标跟踪（A.2.3.1.14）。三个模式平级，都走同一条 TargetTrack 命令：
          Auto = 设备自行跟踪，Manual = 在画面上框选目标，Stop = 停止。
          框选需要画面上有拖拽图层，所以把「框选跟踪」交给父组件处理（emit），
          本面板不持有播放器 DOM。
        -->
        <div class="ptz-actions">
          <el-button
            size="small"
            :type="tracking ? 'danger' : 'default'"
            :disabled="noTargetTrack"
            :title="tracking ? '停止目标跟踪' : tip('TARGET_TRACK', '让设备自动跟踪（球机智能分析固件支持才生效）')"
            @click="toggleAutoTrack"
          >
            <el-icon><Aim /></el-icon> {{ tracking ? '停止跟踪' : '自动跟踪' }}
          </el-button>
          <el-button
            size="small"
            :disabled="noTargetTrack"
            title="在画面上拖拽框选要跟踪的目标"
            @click="$emit('track')"
          >
            <el-icon><Crop /></el-icon> 框选跟踪
          </el-button>
        </div>
        <div class="ptz-actions">
          <el-button size="small" :disabled="noIFrame"
                     :title="tip('FORCE_I_FRAME', '请求设备立即编一个 I 帧')"
                     @click="cmd('iframe', {}, '已下发关键帧请求')">
            <el-icon><Picture /></el-icon> 强制关键帧
          </el-button>
          <el-popconfirm title="确定重启该设备？" @confirm="cmd('teleBoot', {}, '重启指令已下发')">
            <template #reference>
              <el-button size="small" type="warning" plain :disabled="noReboot"
                         :title="tip('REBOOT', '设备会断线几十秒，预览与录像全部中断')">
                <el-icon><RefreshRight /></el-icon> 远程重启
              </el-button>
            </template>
          </el-popconfirm>
        </div>
        <!--
          破坏性操作单独一行：el-popconfirm 的 class 落在内部按钮上，scoped 的 grid-column
          匹配不稳（实测仍是单列宽度），包一层 div 最省事，也把高风险动作隔开。
        -->
        <div class="ptz-actions-danger">
          <el-popconfirm title="确定格式化设备存储卡？数据将丢失" @confirm="cmd('formatSdCard', { sdCardId: 1 }, '格式化指令已下发')">
            <template #reference>
              <el-button size="small" type="danger" plain :disabled="noFormatSd"
                         :title="tip('FORMAT_SD_CARD', '数据将丢失')">
                <el-icon><Delete /></el-icon> 格式化存储卡
              </el-button>
            </template>
          </el-popconfirm>
        </div>
      </el-tab-pane>
    </el-tabs>
    </template>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { confirmAction } from '@/utils/confirm'
import { controlApi, deviceApi, dahuaApi } from '@/api'
import { useDeviceCapabilities } from '@/composables/useDeviceCapabilities'
import CameraConfigPanel from '@/components/CameraConfigPanel.vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

/**
 * 云台四个独立权限点，与面板的四个区域一一对应。
 *
 * <p>四者平级，不互为隐含：只想让人转方向而不给重启设备的场景是真实存在的。
 * 后端 {@code WritePermissionInterceptor} 对 {@code /api/v1/control/*} 按同样口径分路径拦截，
 * 这里只负责把按钮置灰，避免「点了才 403」。
 */
const canPtz = computed(() => auth.can('channel:ptz'))
const canPreset = computed(() => auth.can('channel:preset'))
const canDevice = computed(() => auth.can('channel:ptz:device'))
const canAdvanced = computed(() => auth.can('channel:ptz:advanced'))

const props = defineProps({
  /** 设备编码 */
  serial: { type: String, default: '' },
  /** 通道编码 */
  code: { type: String, default: '' },
  /** 是否禁用（如设备离线） */
  disabled: { type: Boolean, default: false },
  /**
   * 精简模式：只显示方向盘与变倍/光圈。预置位、雨刷/补光/录像/布防、重启/格式化、
   * 喊话都是运维动作，只在通道管理页提供。
   */
  simple: { type: Boolean, default: false },
  /**
   * 通道所属设备的接入方式：{@code gb28181} / {@code dahua}。
   *
   * <p>「相机参数」tab 是 SDK 直连设备专属能力（大华 NetSDK 的 JSON 配置项），
   * 国标没有对应协议，所以国标设备不渲染该 tab —— 不做一个点了报错的入口。
   */
  source: { type: String, default: 'gb28181' }
})

/** 是否 SDK 直连设备（当前只有大华）。 */
const isSdkDevice = computed(() => !!props.source && props.source !== 'gb28181')
/**
 * 相机参数表单忙碌标记。
 *
 * <p>目前只用于把「参数写一半就切走」的极端情况下的外部禁用说清楚；
 * 面板内部自己也有 busy，这里给 false 即可，保留 prop 是为了以后做全局互斥。
 */
const busyCamera = ref(false)

/**
 * {@code track}：用户点了「框选跟踪」。框选图层要叠在播放器上，
 * 而播放器 DOM 在父组件手里，所以这里只发事件，由父组件打开图层。
 */
defineEmits(['track'])

/**
 * 按钮是否不可用：外部禁用 / 未选通道 叠加对应权限点。
 *
 * <p>{@code !props.serial} 必须算进来：早期模板写过 {@code disabled || !channel}，
 * 而 {@code channel} 不是本组件的 prop，恒为 undefined 使整个面板永久禁用。
 */
const blocked = computed(() => props.disabled || !props.serial)
/** 方向盘 / 变倍 / 光圈聚焦 / 喊话。 */
const inactive = computed(() => blocked.value || !canPtz.value)
/** 预置位 tab。 */
const noPreset = computed(() => blocked.value || !canPreset.value)
/** 设备功能 tab。 */
const noDevice = computed(() => blocked.value || !canDevice.value)
/** 高级操作 tab。 */
const noAdvanced = computed(() => blocked.value || !canAdvanced.value)

/**
 * 设备能力表。
 *
 * <p>同一批按钮在不同协议上可用性不同：大华 SDK 没有强制关键帧、没有看守位、
 * 没有拉框缩放；国标则全都有。渲染前先取一次，把做得到的留着、做不到的置灰并显示原因。
 *
 * <p><b>取不到能力表时一切按可用处理</b>（见 composable），
 * 不会因为一次接口失败把整个面板锁死。
 */
const cap = useDeviceCapabilities(() => props.serial)

/**
 * 按能力细分的禁用条件。
 *
 * <p>为什么不能直接用 {@code inactive}：方向盘是 {@code PTZ}、变倍光圈是
 * {@code FOCUS_IRIS}、喊话是 {@code TALK} —— 三者在大华上并非都可用，
 * 用一个总开关会让它们一起置灰，看不出到底哪项不能用。
 */
const noPtz = computed(() => cap.disabled('PTZ', inactive.value))
const noFi = computed(() => cap.disabled('FOCUS_IRIS', inactive.value))
const noTalk = computed(() => cap.disabled('TALK', inactive.value))
const noPresetBtn = computed(() => cap.disabled('PRESET', noPreset.value))
const noAux = computed(() => cap.disabled('AUX_SWITCH', noDevice.value))
const noRecording = computed(() => cap.disabled('RECORDING', noDevice.value))
const noGuard = computed(() => cap.disabled('GUARD', noDevice.value))
const noReboot = computed(() => cap.disabled('REBOOT', noAdvanced.value))
const noIFrame = computed(() => cap.disabled('FORCE_I_FRAME', noAdvanced.value))
const noFormatSd = computed(() => cap.disabled('FORMAT_SD_CARD', noAdvanced.value))
const noTargetTrack = computed(() => cap.disabled('TARGET_TRACK', noAdvanced.value))

/** 按钮提示：不支持时显示原因，支持时用调用方给的默认提示。 */
const tip = cap.tip

/** 云台速度（0~255）。固定值不提供调节：速度是调试参数，接口仍做 0~255 夹取。 */
const SPEED = 129

const talking = ref(false)

/**
 * 设备侧预置位列表（只含设备上真实存在的）。数据结构见 PresetListVo：
 * 编号以设备为准，name 已由服务端合并平台自定义名称。
 */
const presets = ref([])
/** 最后一次收到设备应答的时间；`null` 表示从未查到过。 */
const presetQueriedAt = ref(null)
/** 查询中（含等待设备应答的轮询阶段）。 */
const presetLoading = ref(false)
/** 上一次查询得到的应答时间，用来判断「轮询到的是不是本次的结果」。 */
let presetBaseline = null
/**
 * 组件是否已销毁：预置位查询是「下发 + 轮询」，期间面板可能被关掉，
 * 不拦会继续请求已不存在的界面，还会在用户离开后弹提示。
 */
let disposed = false

/** 设备上已用的预置位个数。 */
const usedCount = computed(() => presets.value.length)

/**
 * 全部预置位槽位（固定 1~255，含空槽）。国标编号上限就是 255（1 字节），
 * 设备实际支持多少个平台无法事先知道；固定 255 + 表内滚动，任何编号都能直接点，
 * 不会让用户看到第 9 行就以为能存（只支持 8 个的设备会失败）。
 */
const PRESET_MAX = 255

const presetSlots = computed(() => {
  const used = new Map()
  for (const p of presets.value) {
    if (!p || !Number.isFinite(p.presetId)) continue
    used.set(p.presetId, p)
  }
  const rows = []
  for (let i = 1; i <= PRESET_MAX; i++) {
    const p = used.get(i)
    rows.push({
      presetId: i,
      used: !!p,
      // 已用：优先平台自定义名，否则设备名，再否则默认名（后端已合并好）
      name: p ? p.name || `预置位 ${i}` : '',
      status: p?.status || ''
    })
  }
  return rows
})

/** 空槽位行加淡色底，扫一眼就能看出哪些还能存。 */
function presetRowClass({ row }) {
  return row.used ? '' : 'preset-row-empty'
}

/* ---------- 备注（自定义名称）行内编辑 ---------- */
const editingPreset = reactive({ no: 0, value: '', saving: false })
const presetNameInput = ref(null)

async function startRenamePreset(row) {
  if (editingPreset.saving) return
  editingPreset.no = row.presetId
  // 用「默认名」作为空值：用户看到的就是当前显示的那个名字
  editingPreset.value = row.name === `预置位 ${row.presetId}` ? '' : row.name
  await nextTick()
  const el = presetNameInput.value
  const target = Array.isArray(el) ? el[0] : el
  target?.focus?.()
  target?.select?.()
}

function cancelPresetName() {
  editingPreset.no = 0
  editingPreset.value = ''
}

/** 提交备注：回车与失焦都会走到这里，必须幂等（回车后输入框被移除，blur 会重复提交）。 */
async function commitPresetName(row) {
  if (editingPreset.no !== row.presetId || editingPreset.saving) return
  const value = (editingPreset.value || '').trim()
  const original = row.name === `预置位 ${row.presetId}` ? '' : row.name
  if (value === original) {
    cancelPresetName()
    return
  }
  editingPreset.saving = true
  try {
    // 传空字符串 = 清除自定义名，回落到设备名或默认名
    await deviceApi.setPresetName({
      serial: props.serial,
      code: props.code || props.serial,
      preset: row.presetId,
      name: value
    })
    // 本地同步，避免为了一个名字去重新走一遍 10 秒的 SIP 查询
    const hit = presets.value.find((p) => p.presetId === row.presetId)
    if (hit) {
      hit.name = value || `预置位 ${row.presetId}`
      hit.customName = !!value
    }
    ElMessage.success('备注已保存')
    cancelPresetName()
  } catch (e) {
    ElMessage.error(e.message || '保存备注失败')
  } finally {
    editingPreset.saving = false
  }
}

/* ---------- 三个动作 ---------- */

/** 调用预置位。 */
async function callPreset(row) {
  const ok = await cmd('preset', { command: 'goto', preset: row.presetId },
    `已调用预置位 ${row.presetId}`)
  // 预置位是"整幅画面跳变"，最需要立即刷新，成功后再要一次关键帧
  if (ok) requestKeyframe()
}

/**
 * 设置预置位（把当前位置存到该编号）。空槽设置成功后要本地补一条，
 * 否则这一行仍是空槽，用户会以为没成功。名称先占位，刷新可从设备拉回。
 */
async function savePreset(row) {
  const ok = await cmd('preset', { command: 'set', preset: row.presetId },
    row.used ? `已更新预置位 ${row.presetId}` : `已设置预置位 ${row.presetId}`)
  if (!ok) return
  if (!row.used) {
    presets.value = [
      ...presets.value,
      { presetId: row.presetId, name: `预置位 ${row.presetId}`, customName: false }
    ].sort((a, b) => a.presetId - b.presetId)
  }
}

/** 删除预置位（需确认：删了要重新去现场调机位才能恢复）。 */
async function removePreset(row) {
  if (!(await confirmAction(
    `确定删除预置位 ${row.presetId}？删除后需要重新调整机位才能恢复。`,
    '删除预置位',
    { confirmButtonText: '删除' }
  ))) return
  const ok = await cmd('preset', { command: 'remove', preset: row.presetId },
    `已删除预置位 ${row.presetId}`)
  if (ok) {
    presets.value = presets.value.filter((p) => p.presetId !== row.presetId)
  }
}

/** 当前运维功能 Tab。默认预置位：最常用（比点方向键高效），内容只有两行不撑高面板。 */
const activeSection = ref('preset')

/**
 * 无「预置位」权限时自动落到下一个有权限的 tab。
 *
 * <p>el-tabs 的 active 指向一个 disabled 的 pane 时会渲染成空白内容区，
 * 看起来像面板坏了。都没权限时保持原值（全部 tab 都灰，本身就在表达「无权」）。
 *
 * <p>注意「相机」tab 只在 SDK 直连设备上存在，所以判断下一步必须带上
 * {@code isSdkDevice}，否则国标设备会落到一个根本不渲染的 pane 上。
 */
watch(
  [canPreset, canDevice, canAdvanced, isSdkDevice],
  () => {
    if (canPreset.value) return
    if (activeSection.value !== 'preset') return
    if (canDevice.value) activeSection.value = 'device'
    else if (canAdvanced.value) activeSection.value = 'advanced'
    else if (isSdkDevice.value && canDevice.value) activeSection.value = 'camera'
  },
  { immediate: true }
)

/** 方向/变倍按钮当前是否处于「按下持续」状态，避免重复发 stop。 */
const moving = ref(false)

/** 八方向 + 变倍按钮布局。 */
const DIRECTIONS = [
  { cmd: 'upleft', icon: 'TopLeft', area: 'ul', label: '左上' },
  { cmd: 'up', icon: 'Top', area: 'u', label: '上' },
  { cmd: 'upright', icon: 'TopRight', area: 'ur', label: '右上' },
  { cmd: 'left', icon: 'Back', area: 'l', label: '左' },
  { cmd: 'right', icon: 'Right', area: 'r', label: '右' },
  { cmd: 'downleft', icon: 'BottomLeft', area: 'dl', label: '左下' },
  { cmd: 'down', icon: 'Bottom', area: 'd', label: '下' },
  { cmd: 'downright', icon: 'BottomRight', area: 'dr', label: '右下' }
]

/** 云台动作键名 → 接口路径。 */
const ACTIONS = {
  ptz: controlApi.ptz,
  fi: controlApi.fi,
  preset: controlApi.preset,
  wiper: controlApi.wiper,
  fillLight: controlApi.fillLight,
  record: controlApi.record,
  guard: controlApi.guard,
  iframe: controlApi.iframe,
  teleBoot: controlApi.teleBoot,
  formatSdCard: controlApi.formatSdCard,
  talk: controlApi.talk
}

/**
 * 按下开始转动。pointer 事件统一鼠标/触摸；setPointerCapture 让指针移出按钮后
 * 仍归属本元素（停止不依赖它，见全局兜底）。
 */
function beginMove(command, event) {
  if (inactive.value) {
    return
  }
  if (event?.currentTarget?.setPointerCapture && event.pointerId !== undefined) {
    try {
      event.currentTarget.setPointerCapture(event.pointerId)
    } catch (e) {
      // 指针已释放等情况下会抛错，不影响后续下发指令
    }
  }
  moving.value = true
  // 记住这次按住的是"转方向"还是"变倍"，松手时决定要不要请求关键帧
  movingKind = command === 'zoomin' || command === 'zoomout' ? 'zoom' : 'ptz'
  send('ptz', { command, speed: SPEED }, null, true)
}

/** 本次按住的操作类型：{@code 'ptz'} 转方向 / {@code 'zoom'} 变倍 / 空串表示未按住。 */
let movingKind = ''

/**
 * 松开停止。必须判 moving：早期把 stop 直接绑在 mouseleave 上，鼠标划过按钮
 * （从未按下）也会发 stop；且 mouseleave 在禁用按钮上依然触发，面板一禁用反而刷 stop。
 */
function endMove() {
  if (!moving.value) {
    return
  }
  moving.value = false
  const wasZoom = movingKind === 'zoom'
  movingKind = ''
  send('ptz', { command: 'stop' }, null, true)
  /*
   * 只有"转方向"才请求关键帧。
   *
   * 变倍走的是 FI 指令，直播下设备本就不执行（见 mode prop），
   * 画面不会变化，要关键帧纯属浪费一条信令。
   */
  if (!wasZoom) requestKeyframe()
}

/**
 * 上次请求关键帧的时刻（毫秒），用于节流：方向键常被连点，每次松手都请求 I 帧
 * 会让设备每秒编好几个完整帧，码率翻几倍，弱网反而更卡。
 */
let iframeAskedAt = 0

/** 关键帧请求的最小间隔（毫秒）。 */
const IFRAME_MIN_INTERVAL_MS = 1500

/**
 * 请求设备立即编关键帧。
 *
 * 转动/变倍后画面整体改变，但编码器仍按 GOP 走；GOP 4 秒时用户松手后最多干等 4 秒
 * 画面才跳到位。主动要 I 帧让编码器立刻重新起参考点，几百毫秒就刷新。
 * 老设备可能不支持，静默失败。
 */
function requestKeyframe() {
  const now = Date.now()
  if (now - iframeAskedAt < IFRAME_MIN_INTERVAL_MS) return
  iframeAskedAt = now
  send('iframe', {}, null, true)
}

/** 把后端返回的应用到界面。 */
function applyPresetList(vo) {
  if (!vo) return
  presets.value = Array.isArray(vo.items) ? vo.items : []
  presetQueriedAt.value = vo.queriedAt || null
}

/**
 * 取一次本次查询的新结果，有就渲染并返回，否则 null。
 * 先等 500ms 再拉一次：设备会把预置位拆成多帧上报（实测 5 个 = 4 + 1 两帧），
 * SN 变化时后端刚开始累积，立刻渲染只能看到第一帧。
 */
async function takeFreshPresets(baseline, querySerial, queryCode) {
  const vo = await deviceApi.presetList(querySerial, queryCode)
  if (!vo || !vo.queriedAt || vo.queriedAt === baseline) return null
  await new Promise((r) => setTimeout(r, 500))
  if (disposed) return null
  const full = await deviceApi.presetList(querySerial, queryCode)
  applyPresetList(full && (full.items?.length || 0) >= (vo.items?.length || 0) ? full : vo)
  return vo
}

/**
 * 向设备发起预置位查询并等应答。
 *
 * 查询走 SIP MESSAGE，接口返回只代表已发出，设备应答要几百毫秒到几秒。
 * 必须比时间戳基线：只看有无数据会立刻拿到上次缓存并显示成功；
 * 设备忙时实测可拖到 20 秒以上，所以窗口结束后再用更稀的节奏补拉一次。
 *
 * @param {object} [opts]
 * @param {boolean} [opts.silent] 打开面板时自动查询用：成功/超时都不弹提示
 */
async function refreshPresets(opts = {}) {
  const silent = !!opts.silent
  if (!props.serial || presetLoading.value) return
  presetLoading.value = true
  presetBaseline = presetQueriedAt.value
  // 记下本次查询的目标：轮询期间用户可能已经换了通道
  const querySerial = props.serial
  const queryCode = props.code
  try {
    await deviceApi.fetchPreset({ serial: querySerial, code: queryCode })
    /*
     * 20 × 700ms = 14 秒密集轮询。实测该海康球机应答要 3~5 秒，设备忙时更久；
     * 宁可多等，也不要让用户看到「查不到」这种假失败。
     */
    for (let i = 0; i < 20; i++) {
      await new Promise((r) => setTimeout(r, 700))
      if (disposed) return
      if (await takeFreshPresets(presetBaseline, querySerial, queryCode)) {
        if (!silent) {
          ElMessage.success(
            presets.value.length ? `已查到 ${presets.value.length} 个预置位` : '设备上报没有预置位'
          )
        }
        return
      }
    }
    /*
     * 窗口结束：改用更稀的节奏再等一会儿；面板关掉或换了通道就停。
     */
    for (let i = 0; i < 5; i++) {
      await new Promise((r) => setTimeout(r, 3000))
      if (disposed || querySerial !== props.serial || queryCode !== props.code) return
      if (await takeFreshPresets(presetBaseline, querySerial, queryCode)) return
    }
    // 确实没应答：保留旧数据但明确告知，避免用户以为「设备上就是这些」
    if (!silent) {
      ElMessage.warning('设备未应答预置位查询（可能不支持该指令或当前不可达）')
    }
  } catch (e) {
    if (!silent) ElMessage.error(e.message || '预置位查询失败')
  } finally {
    if (!disposed) presetLoading.value = false
  }
}

/**
 * 换通道时重新拉一次，并且总是向设备查一次预置位：
 * 预置位是设备侧数据（现场调机位不经过平台），缓存随时过期，
 * 「打开时看到旧列表」比多等几秒更危险。先渲染缓存，设备应答后覆盖；
 * 自动查询走 silent，不在用户没点按钮时弹提示。
 */
watch(
  () => [props.serial, props.code],
  async ([serial, code]) => {
    presets.value = []
    presetQueriedAt.value = null
    cancelPresetName()
    if (!serial || props.simple) return
    try {
      applyPresetList(await deviceApi.presetList(serial, code))
    } catch (e) {
      // 缓存为空时忽略：下面会向设备查
    }
    // 期间用户已经换了通道：这次自动查询没有意义
    if (serial !== props.serial || code !== props.code) return
    await refreshPresets({ silent: true })
  },
  { immediate: true }
)

/** 标签页切到后台时停止：用户此时看不到画面，继续转动没有意义。 */
function onVisibilityChange() {
  if (document.visibilityState === 'hidden') {
    endMove()
  }
}

/* ==================== 云台扩展（巡航/线扫/巡迹/旋转/速度/空闲/定时） ==================== */

/** 0=周日 … 6=周六（与大华 PtzAutoMovement.TimeSection 的下标一致）。 */
const WEEK_LABELS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六']

const ptzExt = reactive({
  loading: false,
  tours: [],
  scans: [],
  patterns: [],
  speed: { ptzSpeed: 1, patternSpeed: 5 },
  // 水平旋转速度（AutoPan.PanSpeed 1~8）——与上面的 speed 是两个独立字段
  autoPan: { panSpeed: 1 },
  idle: { enable: false, function: 'None', presetId: 1, tourId: 1, patternId: 1, scanId: 1, timerMinutes: 10 },
  autos: [],
  limit: { enable: false, horizontalEnable: false },
  running: { function: 'None', index: 0, taskName: 'Manual' }
})

/**
 * 云台扩展页的分组，与设备「PTZ設置」菜单一一对应：
 * 巡航群組 / 線性掃描 / 巡跡 / 水準旋轉 / PTZ旋轉限制 / PTZ速度 /
 * 閒置動作 / 已排定工作 / PTZ 維護。
 *
 * <p>注意「空闲动作」和「定时任务」在设备上是<b>两份独立配置</b>
 * （IdleMotion 与 PtzAutoMovement），所以这里也拆成两页，不再用单选切换。
 *
 * <p>「旋转限制」原先是塞在「水平旋转」页下半部分的，与设备的两级菜单不符 ——
 * 设备把水準旋轉 和 PTZ旋轉限制 放在<b>平级</b>菜单里。这里拆开对齐。
 *
 * <p>不再带 {@code kind} 字段：页头已去掉「需保存/即时生效」徽标
 * （有改动时底部保存按钮会亮，徽标在说按钮已经说清楚的事）。
 */
const EXT_GROUPS = [
  { key: 'tour', label: '巡航组' },
  { key: 'scan', label: '线扫' },
  { key: 'pattern', label: '巡迹' },
  { key: 'rotate', label: '水平旋转' },
  { key: 'limit', label: '旋转限制' },
  { key: 'speed', label: '速度' },
  { key: 'idle', label: '空闲动作' },
  { key: 'schedule', label: '定时任务' },
  { key: 'maint', label: '维护' }
]
const extTab = ref('tour')

/** 各分组是否有未保存的改动。
 *
 * <p>只比较「可保存的字段」，动作类分组恒为 false。
 * 九个分组是并排的分段按钮，用户改完巡航组直接点「速度」时，
 * 巡航组的改动还留在表单里没下发 —— 不标出来就会以为已经保存过了。
 */
const dirtyMap = computed(() => {
  const map = {}
  map.tour = tourDirty.value
  map.scan = scanRows.value.some(scanRowDirty)
  map.pattern = patternRows.value.some(patternRowDirty)
  map.limit = ptzExt.limit.enable !== limitForm.enable
  // 只比 ptzSpeed：设备界面没有「巡迹速度」入口，patternSpeed 不属用户可改项
  map.speed = ptzExt.speed.ptzSpeed !== speedForm.ptzSpeed
  // 水平旋转速度是独立配置项（AutoPan.PanSpeed），基线存在 ptzExt.autoPan
  map.rotate = (ptzExt.autoPan?.panSpeed ?? AUTO_PAN_MIN) !== panSpeed.value
  const idleTarget = idleTargetOf(ptzExt.idle)
  map.idle = ptzExt.idle.enable !== idleForm.enable
    || (ptzExt.idle.function || 'None') !== idleForm.function
    || ptzExt.idle.timerMinutes !== idleForm.timerMinutes
    || idleTarget !== idleForm.target
  map.schedule = autoRows.value.some(autoRowDirty)
  return map
})

/**
 * 巡航组列表（整表可编辑，对齐设备「巡航群組」页）。
 *
 * <p>为什么从「线号下拉 + 单条编辑」改成整表：设备就是一屏列表，
 * 序号 / 名称 / 执行 / 删除四列，点 + 展开看预置点。单条编辑要先在下拉里
 * 选线号，再在下面改点，改完还得记住刚才选的是第几条 —— 320px 宽的右栏里
 * 这套交互挤成两行换行，看的人抓不住重点。
 *
 * <p>{@code route} 是业务线号（1-based），保存时按它写回设备对应下标。
 */
const tourRows = ref([])
/** 展开中的行（route 列表），用于「新增巡航组」后自动展开。 */
const expandedTours = ref([])
/** 设备 PtzTour 的线号上限（32 条）。 */
const TOUR_MAX = 32

/**
 * 当前正在运行的动作 —— <b>唯一真源</b>，来自设备 {@code PtzMovement}。
 *
 * <p>编码（后端 {@code RunningCfg}，已用设备界面逐项验证）：
 * {@code TaskName=Manual} 表示在跑、{@code Unknown} 表示空闲；
 * {@code Function} 是类型（Tour / Pattern / Scan / AutoPan）；{@code Index} 是
 * <b>0-based</b> 下标，业务编号要 +1。
 *
 * <p>⚠️ 早期这里是四个各自独立的 {@code ref}，只在本地「记住我点过什么」。
 * 后果有两个：刷新/重进页面后开关状态全丢；而且<b>设备上明明在跑，
 * 平台却显示未运行</b>（或反之）。现在一律以设备回读为准。
 *
 * @returns {{type: 'tour'|'pattern'|'scan'|'pan'|null, id: number}}
 */
const activeRun = computed(() => {
  const r = ptzExt.running
  if (!r || !r.running) return { type: null, id: 0 }
  const fn = String(r.function || 'None').toLowerCase()
  const id = Number(r.index ?? 0) + 1 // 设备 0-based → 业务 1-based
  if (fn === 'tour') return { type: 'tour', id }
  if (fn === 'pattern') return { type: 'pattern', id }
  if (fn === 'scan') return { type: 'scan', id }
  if (fn === 'autopan') return { type: 'pan', id: 0 }
  return { type: null, id: 0 }
})

/** 是否有任何动作在跑。设备同一时刻只能跑一个，跑着的时候其它开关要禁用。 */
const isRunning = computed(() => activeRun.value.type !== null)

/** 当前运行的动作名（给禁用提示用）。 */
const runningLabel = computed(() => {
  const { type, id } = activeRun.value
  if (type === 'tour') return `巡航组 ${id}`
  if (type === 'pattern') return `巡迹 ${id}`
  if (type === 'scan') return `线扫 ${id}`
  if (type === 'pan') return '水平旋转'
  return ''
})

/** 正在巡航的线号（0 = 没有在跑）。 */
const runningTour = computed(() => (activeRun.value.type === 'tour' ? activeRun.value.id : 0))

/** 运行状态轮询定时器（3 秒）。 */
let runningTimer = null

/**
 * 只刷新「当前运行状态」。
 *
 * <p>为什么不复用 {@link loadPtzExt}：那个要读 9 个配置项、上百 KB，3 秒一次会把设备压住。
 * 这个只读 {@code PtzMovement} 一项。
 *
 * <p>为什么要轮询：运行状态会自己变 —— 巡航/巡迹跑完会停、别处（设备网页、上级平台）
 * 也能启停。不轮询就只能显示「打开面板那一刻」的快照，用户看到的是过期状态。
 *
 * <p>失败静默：轮询不该弹错误框打断用户，下一轮会重试。
 */
async function refreshRunning() {
  if (!isSdkDevice.value || !props.serial || disposed) return
  try {
    const r = await dahuaApi.ptzRunning(extParams.value)
    if (r) ptzExt.running = r
  } catch (e) {
    // 静默：轮询失败不打断操作
  }
}

/**
 * 一条线是否「已配置」：配过名字、有预置点、或正在跑。
 * 设备恒返回 32 条线，其余都是名称「巡航群組N」+ 0 个点的空线。
 */
function isTourConfigured(t) {
  if (runningTour.value === t.route) return true
  const name = (t.name || '').trim()
  const isDefault = !name || name === '巡航群組' + t.route || name === '巡航组' + t.route
  return !isDefault || (t.points && t.points.length > 0)
}

/**
 * 只显示「已配置」的巡航组。
 *
 * <p>设备返回 32 条空线，全铺出来是一屏噪声，用户要找自己配的那条得滚半天。
 * 设备自己的列表也只显示配过的线。
 */
const configuredTours = computed(() =>
  tourRows.value.filter((t) => t.isNew || isTourConfigured(t))
)

/** 读一次配置后把设备的巡航线灌进可编辑列表（深拷贝，改动不污染快照）。 */
function fillTours(tours) {
  tourRows.value = (tours || []).map((t) => ({
    route: t.route,
    name: t.name || '',
    points: (t.points || []).map((p) => ({ ...p })),
    isNew: false
  }))
}

/**
 * 新增一条空巡航组：把第一个「还没被配置」的线号标记成新建并展开。
 *
 * <p><b>不能 push 新行</b>：设备恒返回 32 条线（{@code tourRows} 里已经有全部 32 个
 * route），再 push 一个同 route 的行就会和已有的行<b>撞 row-key</b>，
 * 表现为「改一次、两行都在变」「提示保存 2 条但设备只动了 1 条」。
 * 正确做法是把已有空行的 {@code isNew} 打开，让它出现在列表里。
 *
 * <p>也不能用「配置过」当占用的唯一判据：线号可用但还没配点的那种行，
 * 本来就该被复用。
 */
function newTour() {
  const target = tourRows.value.find((t) => !t.isNew && !isTourConfigured(t))
  if (!target) {
    ElMessage.warning('巡航组已满（最多 ' + TOUR_MAX + ' 条）')
    return
  }
  target.isNew = true
  target.points = []
  expandedTours.value = [...new Set([...expandedTours.value, target.route])]
}

/**
 * 切换某条线的巡航启停（设备「執行」列）。
 *
 * <p>这是<b>即时动作</b>，不走保存：点下去立刻下发，不做脏标记。
 * 没有预置点的线按钮本来就是灰的，这里再兜一次，避免竞态下发出空线指令。
 */
async function toggleTourRun(row) {
  if (!row.points.length) {
    ElMessage.warning('这条线没有预置点，无法执行')
    return
  }
  /*
   * 设备同一时刻只能跑一个动作：有别的在跑时其它开关是禁用态
   * （设备界面同样如此）。UI 已禁用，这里再兜一道防竞态。
   */
  const mine = activeRun.value.type === 'tour' && activeRun.value.id === row.route
  if (isRunning.value && !mine) {
    ElMessage.warning(`「${runningLabel.value}」正在运行，请先停止它再启动别的动作`)
    return
  }
  const start = !mine
  try {
    await dahuaApi.ptzAction({
      ...extParams.value,
      action: 'cruise-run',
      route: row.route,
      start
    })
    ElMessage.success(start ? `开始巡航：${row.name || '巡航组 ' + row.route}` : '已停止巡航')
    // 开关状态以设备回读为准，不在本地猜
    await loadPtzExt()
  } catch (e) {
    ElMessage.error(e.message || (start ? '启动失败' : '停止失败'))
    await loadPtzExt()
  }
}

/** 清空一条巡航组：名称回默认、预置点清空（与设备「删除」同义）。 */
async function clearTour(row) {
  if (!(await confirmAction(
    `确定清空巡航组 ${row.route}？名称会恢复默认，预置点全部删除。`,
    '清空巡航组',
    { confirmButtonText: '清空' }
  ))) return
  // 正在跑的线要先停：边删预置点边跑，设备会按旧轨迹继续转
  if (runningTour.value === row.route) {
    try {
      await dahuaApi.ptzAction({ ...extParams.value, action: 'cruise-run', route: row.route, start: false })
    } catch (e) {
      // 停不下来也继续清：设备对空线本就不会动
    }
  }
  try {
    await dahuaApi.saveTour(extParams.value, {
      route: row.route,
      name: '',
      enable: false,
      points: []
    })
    ElMessage.success('已清空')
    await loadPtzExt()
  } catch (e) {
    ElMessage.error(e.message || '清空失败')
  }
}

/**
 * 清空全部巡航组（对应设备「巡航群組」页工具栏的「清 空」）。
 *
 * <p>只清预置点，不动名称：设备 {@code PtzTour.Name} 是只读的，
 * 清不掉也别假装清掉了（后端会回读校验报错）。
 */
async function clearAllTours() {
  const targets = configuredTours.value
  if (!targets.length) return
  if (!(await confirmAction(
    `确定清空全部 ${targets.length} 条巡航组？所有预置点会被删除，名称无法清除（设备只读）。`,
    '清空全部巡航组',
    { confirmButtonText: '全部清空' }
  ))) return
  try {
    if (runningTour.value) {
      try {
        await dahuaApi.ptzAction({ ...extParams.value, action: 'cruise-run', route: runningTour.value, start: false })
      } catch (e) { /* 停不下来也继续 */ }
    }
    for (const t of targets) {
      const saved = ptzExt.tours.find((x) => x.route === t.route)
      await dahuaApi.saveTour(extParams.value, {
        route: t.route,
        // 传设备原值：名称只读，传空会被后端回读校验拒掉
        name: saved?.name || '',
        enable: false,
        points: []
      })
    }
    ElMessage.success(`已清空 ${targets.length} 条巡航组的预置点`)
    await loadPtzExt()
  } catch (e) {
    ElMessage.error(e.message || '清空失败')
  }
}

/** 给某条线加一个预置点：默认用设备上第一个预置位。 */
function addPoint(row) {
  const first = presets.value.find((p) => Number.isFinite(Number(p?.presetId)))
  row.points.push({ preset: first ? Number(first.presetId) : 1, stay: 15, speed: 7 })
}
/**
 * 预置点下拉的选项。
 *
 * <p>数据源就是「预置位」页那份设备清单（同一个 {@code presets} ref），
 * 编号以设备为准，名称是平台自定义名或设备名。
 *
 * <p>已保存但设备上已经不存在的编号也补一条 —— 否则 el-select 找不到匹配项
 * 会渲染成空白，用户以为这一行坏了；实际是「配过，设备那边删了」，要显式说出来。
 */
const presetOptions = computed(() => {
  const out = []
  const seen = new Set()
  for (const p of presets.value) {
    const id = Number(p?.presetId)
    if (!Number.isFinite(id)) continue
    seen.add(id)
    out.push({ value: id, label: `#${id} ${p.name || '预置位 ' + id}` })
  }
  for (const row of tourRows.value) {
    for (const pt of row.points || []) {
      const id = Number(pt?.preset)
      if (!Number.isFinite(id) || id <= 0 || seen.has(id)) continue
      seen.add(id)
      out.push({ value: id, label: `#${id}（设备上未找到）` })
    }
  }
  return out.sort((a, b) => a.value - b.value)
})

/**
 * 按动作类型生成「目标」下拉选项 —— 对齐设备「編號」下拉的 `编号:名称` 格式。
 *
 * <p>设备实测（「閒置動作」和「已排定工作」的「編號」都是这种下拉）：
 * <pre>
 *   Preset  → 2:預置點2
 *   Tour    → 1:巡航群組1
 *   Scan    → 1:掃描1 / 2:掃描2
 *   Pattern → 1:大门口巡视轨迹 / 2:巡跡2
 * </pre>
 * <b>只列已配置的项</b>：设备不会把所有空槽位都塞进下拉。
 *
 * <p>为什么必须是下拉而不是数字输入：目标号是「第几条」，用户不可能记住
 * 每条线叫什么、哪条配过。数字框还会让人填出不存在的编号 ——
 * 设备收到不存在的编号只会静默不动，现场很难查。
 *
 * @param fn 动作类型 None/Preset/Tour/Pattern/Scan
 */
function targetOptionsFor(fn) {
  const out = []
  const seen = new Set()
  const push = (value, label) => {
    const v = Number(value)
    if (!Number.isFinite(v) || v <= 0 || seen.has(v)) return
    seen.add(v)
    out.push({ value: v, label: `${v}:${label}` })
  }
  switch (fn) {
    case 'Preset':
      // 预置位是设备上真实存在的编号，取自「预置位」页那份清单
      for (const p of presets.value) {
        push(p?.presetId, p.name || '预置位 ' + p?.presetId)
      }
      break
    case 'Tour':
      for (const t of configuredTours.value) {
        push(t.route, (t.name || '').trim() || '巡航组' + t.route)
      }
      break
    case 'Pattern':
      for (const p of configuredPatterns.value) {
        push(p.patternId, (p.name || '').trim() || '巡迹' + p.patternId)
      }
      break
    case 'Scan':
      for (const s of configuredScans.value) {
        push(s.scanId, (s.name || '').trim() || '线扫' + s.scanId)
      }
      break
    default:
      break
  }
  return out.sort((a, b) => a.value - b.value)
}

/**
 * 目标下拉的选项；已保存但当前列表里没有的编号补一条。
 *
 * <p>补这一条的理由与 {@link presetOptions} 相同：设备那边被删了 / 还没读到，
 * el-select 找不到匹配项会渲染成空白，用户以为这行坏了。
 *
 * <p>{@code None}（无动作）时返回空数组 —— 设备在「無」状态下根本不显示「編號」，
 * 这里也不该塞一个「1:（设备上未找到）」误导人。
 */
function targetOptions(fn, current) {
  if (!fn || fn === 'None') return []
  const opts = targetOptionsFor(fn)
  const cur = Number(current) || 0
  if (cur > 0 && !opts.some((o) => o.value === cur)) {
    opts.push({ value: cur, label: `${cur}:（设备上未找到）` })
    opts.sort((a, b) => a.value - b.value)
  }
  return opts
}

/** 展开/收起：保存展开集合，避免保存后刷新列表把展开状态丢掉。 */
function onTourExpand(row, expanded) {
  const key = row.route
  const has = expandedTours.value.includes(key)
  const nowExpanded = Array.isArray(expanded) ? expanded.includes(row) : !!expanded
  if (nowExpanded && !has) expandedTours.value = [...expandedTours.value, key]
  if (!nowExpanded && has) expandedTours.value = expandedTours.value.filter((k) => k !== key)
}

/** 各条巡航组是否有未保存的改动（按钮上的小圆点用）。 */
const tourDirty = computed(() => tourRows.value.some(tourRowDirty))

/** 某一行是否有改动（保存时只提交改动过的行，未动的行不碰设备）。 */
function tourRowDirty(row) {
  if (row.isNew) return true
  const saved = ptzExt.tours.find((t) => t.route === row.route)
  const savedPoints = saved?.points || []
  /*
   * 不比名称：设备 PtzTour.Name 只读，比了会永远显示「有改动」。
   * 名称列的输入框也是 disabled 的，本来就改不动。
   */
  if (row.points.length !== savedPoints.length) return true
  return row.points.some((p, i) => {
    const b = savedPoints[i]
    return p.preset !== b.preset || p.stay !== b.stay || p.speed !== b.speed
  })
}

/**
 * 某条线扫是否有改动。
 *
 * <p>只比速度：边界不是表单值，只能用「以当前位置设置」的即时动作写入，
 * 存不了也改不了，所以不算进脏判断（否则永远显示「有改动」）。
 */
function scanRowDirty(row) {
  if (row.isNew) return true
  const saved = ptzExt.scans.find((s) => s.scanId === row.scanId)
  if (!saved) return true
  return row.speed !== saved.speed
}
/** 线扫速度下拉的显示值：设备界面是 1~8，接口是 0~7，这里显示 n 存 n-1。 */
const SCAN_SPEEDS = [1, 2, 3, 4, 5, 6, 7, 8]
/**
 * 设备支持的线扫条数上限。
 *
 * <p>实测设备返回 32 条（{@code AutoScan[0][0..31]}），与巡航组同量级；
 * 不要凭「设备界面上只看到 2 条」就当成 8 条。
 */
const SCAN_MAX = 32
/** 线扫行（整表可编辑，对齐设备「線性掃描」页）。 */
const scanRows = ref([])
/** 正在执行的线扫号，0 = 没有。派生自设备状态，见 {@link activeRun}。 */
const runningScan = computed(() => (activeRun.value.type === 'scan' ? activeRun.value.id : 0))

/**
 * 线扫出厂默认速度。
 *
 * <p>设备把没动过的 32 条槽位都返回 {@code speed=4}，这就是出厂值。
 * 用户把某条改成别的值，说明他配过这条，即使边界还没设也要留在列表里
 * ——否则「改完速度一保存就消失」，看起来像保存失败。
 */
const SCAN_DEFAULT_SPEED = 4

/**
 * 只显示「已配置」的线扫。
 *
 * <p>设备恒返回 32 条，其余是没设边界的空线。空线全铺出来是噪声，
 * 设备自己的列表也只显示配过的。判据：设过边界 / 改过速度 / 正在跑 / 刚新增。
 */
const configuredScans = computed(() =>
  scanRows.value.filter((s) => {
    if (s.isNew) return true
    if (runningScan.value === s.scanId) return true
    if (s.leftSet || s.rightSet) return true
    return s.speed !== SCAN_DEFAULT_SPEED
  })
)

/** 把设备返回的线扫灌进表格行。 */
function fillScans(scans) {
  scanRows.value = (scans || []).map((s) => ({
    scanId: s.scanId,
    name: '',
    leftSet: !!s.leftSet,
    rightSet: !!s.rightSet,
    speed: s.speed,
    direction: s.direction || 'Negative',
    isNew: false
  }))
}

/** 巡迹行（整表可编辑，对齐设备「巡跡」页）。 */
const patternRows = ref([])
/** 设备支持的巡迹条数上限（AutoPattern 数组长度实测 32）。 */
const PATTERN_MAX = 32
/** 正在录制的巡迹号，0 = 没在录。（录制状态设备不报，只能本地记） */
const recordingPattern = ref(0)
/** 正在运行的巡迹号，0 = 没在跑。派生自设备状态，见 {@link activeRun}。 */
const runningPattern = computed(() => (activeRun.value.type === 'pattern' ? activeRun.value.id : 0))

/**
 * 一条巡迹是不是「没配过」的默认空条目。
 *
 * <p>设备默认名是「巡跡N」——<b>繁体「跡」</b>（实测 {@code AutoPattern[0][n].Name}
 * 返回 {@code 5de1 8de1 31} = 巡跡1，固件是繁体界面）。简体「巡迹N」和
 * {@code PatternN} 也一并认，避免换固件后判据失效。
 */
function isPatternDefaultName(row) {
  const name = (row.name || '').trim()
  if (!name) return true
  const n = row.patternId
  return name === '巡跡' + n || name === '巡迹' + n || name === 'Pattern' + n
}

/**
 * 只显示「已配置」的巡迹。
 *
 * <p><b>主判据是 {@code enable}</b>：设备 {@code AutoPattern[ch][idx].Enable=true}
 * 表示「这条巡迹录过内容」，设备自己的「巡跡」页列表就按它筛。
 * 实测该字段走 CGI 读是可靠的（空槽 false、录过的 true）。
 *
 * <p>⚠️ 早期这里<b>只按名称判断</b>，于是「录过但没改名字」的巡迹
 * （例如默认名「巡跡2」+ Enable=true）被当成空槽隐藏掉 ——
 * 表现就是「设备上有两条，平台只显示一条」。
 *
 * <p>另外两种也要显示：
 * <ul>
 *   <li>本次会话里刚点「新增巡迹」的行（{@code isNew}）；</li>
 *   <li>改过名字但还没录的行 —— 名称已经真的写进设备了，
 *       不显示会让「新增 + 改名 + 保存」看起来凭空消失。</li>
 * </ul>
 */
const configuredPatterns = computed(() =>
  patternRows.value.filter((p) => {
    if (p.isNew) return true
    if (recordingPattern.value === p.patternId) return true
    if (runningPattern.value === p.patternId) return true
    if (p.enable) return true
    return !isPatternDefaultName(p)
  })
)

/**
 * 巡迹停留秒：出厂默认 15 秒。
 *
 * <p>范围取自设备能力位 {@code PtzPattern:{DurationMin:0, DurationMax:3600}}，
 * 设备输入框也把 99999 钳到 3600。
 */
const PATTERN_STAY_MIN = 0
const PATTERN_STAY_MAX = 3600
const PATTERN_STAY_DEFAULT = 15

/** 把设备返回的巡迹灌进表格行。 */
function fillPatterns(patterns) {
  patternRows.value = (patterns || []).map((p) => ({
    patternId: p.patternId,
    name: p.name || '',
    enable: !!p.enable,
    /*
     * 停留时间来自 PatternPlayDetail（设备存毫秒，后端已换成秒）。
     * 设备对未改过的槽位一律返回 15000ms = 15s，所以拿不到时也用 15。
     */
    staySeconds: p.staySeconds ?? 0,
    isNew: false
  }))
}

/**
 * 这条巡迹能不能跑（设备「執行」列）。
 *
 * <p>判据是 {@code enable} —— 设备只有录过内容的巡迹才能回放。
 * 设备界面上「新增但没录」的那一行，執行开关同样是<b>禁用</b>的。
 */
function isPatternReady(row) {
  if (recordingPattern.value === row.patternId) return true
  if (runningPattern.value === row.patternId) return true
  return row.enable
}

/**
 * 新增一条巡迹：把第一个<b>真正空着</b>的槽位标记成新建。
 *
 * <p><b>必须同时满足 {@code !enable}</b>：{@code enable=true} 表示该槽位已经录过内容。
 * 早期只判「名称是默认值」就挑槽位，结果「录过但没改名」的那条
 * （实测设备上就有：{@code 巡跡2} + {@code Enable=true}）会被当成空槽占掉 ——
 * 用户点「新增巡迹」，实际动的是已有的真实数据，保存后什么都没变，看着就是「添加不行」。
 *
 * <p>与设备一致：设备的「新增巡跡」也只是在前端起一行待录制，落盘要等录制完成。
 */
function newPattern() {
  const target = patternRows.value.find(
    (p) => !p.isNew && !p.enable && isPatternDefaultName(p)
  )
  if (!target) {
    ElMessage.warning('巡迹已满（最多 ' + PATTERN_MAX + ' 条）')
    return
  }
  target.isNew = true
  target.staySeconds = PATTERN_STAY_DEFAULT
  ElMessage.info(
    `已在巡迹 ${target.patternId} 上新建。录制完成后设备才会保存内容，在此之前「执行」不可用。`
  )
}

/**
 * 开始 / 结束录制某条巡迹（设备「設置」列）。
 *
 * <p>录制是<b>即时动作</b>：设备在结束录制时才把轨迹写进该槽位并把 Enable 置 true，
 * 所以这里录完要重新读一次配置。
 */
async function togglePatternRecord(row) {
  const recording = recordingPattern.value !== row.patternId
  try {
    await dahuaApi.ptzAction({
      ...extParams.value,
      action: recording ? 'pattern-record' : 'pattern-record-stop',
      mode: row.patternId
    })
    recordingPattern.value = recording ? row.patternId : 0
    if (recording) {
      ElMessage.success(`开始录制巡迹 ${row.patternId}，操作云台走完路径后点「停录」`)
    } else {
      ElMessage.success('录制已结束')
      await loadPtzExt()
    }
  } catch (e) {
    ElMessage.error(e.message || '录制指令失败')
  }
}

/** 运行 / 停止某条巡迹（设备「執行」列）。 */
async function togglePatternRun(row) {
  if (!isPatternReady(row)) {
    ElMessage.warning('这条巡迹还没录制内容，无法执行')
    return
  }
  const mine = activeRun.value.type === 'pattern' && activeRun.value.id === row.patternId
  if (isRunning.value && !mine) {
    ElMessage.warning(`「${runningLabel.value}」正在运行，请先停止它再启动别的动作`)
    return
  }
  const start = !mine
  try {
    await dahuaApi.ptzAction({
      ...extParams.value,
      action: 'pattern-run',
      mode: row.patternId,
      start
    })
    ElMessage.success(start ? `运行巡迹 ${row.patternId}` : '已停止巡迹')
    await loadPtzExt()
  } catch (e) {
    ElMessage.error(e.message || (start ? '运行失败' : '停止失败'))
    await loadPtzExt()
  }
}

/** 删除某条巡迹的录制内容（设备「刪除」列）。 */
async function clearPattern(row) {
  if (!(await confirmAction(
    `确定删除巡迹 ${row.patternId} 的录制内容？删除后需要重新录制。`,
    '删除巡迹',
    { confirmButtonText: '删除' }
  ))) {
    return
  }
  try {
    if (runningPattern.value === row.patternId) {
      await dahuaApi.ptzAction({ ...extParams.value, action: 'pattern-run', mode: row.patternId, start: false })
    }
    if (recordingPattern.value === row.patternId) {
      await dahuaApi.ptzAction({ ...extParams.value, action: 'pattern-record-stop', mode: row.patternId })
      recordingPattern.value = 0
    }
    await dahuaApi.ptzAction({ ...extParams.value, action: 'pattern-delete', mode: row.patternId })
    ElMessage.success('巡迹内容已删除')
    await loadPtzExt()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

/**
 * 清空全部巡迹（对应设备「巡跡」页工具栏的「清 空」）。
 *
 * <p>逐个调 {@code pattern-delete}（设备就是一条一条删的）。
 * ⚠️ 设备 RPC2 在重负载下会整段失效（返回空响应），所以这里<b>限速 + 失败即停</b>：
 * 中途失败就报出来，不要继续打，否则会把设备的 RPC2 子系统打崩。
 */
async function clearAllPatterns() {
  const targets = configuredPatterns.value
  if (!targets.length) return
  if (!(await confirmAction(
    `确定清空全部 ${targets.length} 条巡迹？录制内容会被删除，需要重新录制。`,
    '清空全部巡迹',
    { confirmButtonText: '全部清空' }
  ))) return
  let done = 0
  try {
    for (const p of targets) {
      if (runningPattern.value === p.patternId) {
        await dahuaApi.ptzAction({ ...extParams.value, action: 'pattern-run', mode: p.patternId, start: false })
      }
      if (recordingPattern.value === p.patternId) {
        await dahuaApi.ptzAction({ ...extParams.value, action: 'pattern-record-stop', mode: p.patternId })
        recordingPattern.value = 0
      }
      await dahuaApi.ptzAction({ ...extParams.value, action: 'pattern-delete', mode: p.patternId })
      done++
    }
    ElMessage.success(`已清空 ${done} 条巡迹`)
  } catch (e) {
    ElMessage.error(`已清空 ${done} 条后失败：` + (e.message || '设备拒绝'))
  } finally {
    await loadPtzExt()
  }
}

/**
 * 某条巡迹是否有改动。
 *
 * <p>比名称和停留时间两项（Enable 由设备自己管，不比）。
 */
function patternRowDirty(row) {
  if (row.isNew) return true
  const saved = ptzExt.patterns.find((p) => p.patternId === row.patternId)
  if ((row.name || '') !== (saved?.name || '')) return true
  return (row.staySeconds ?? 0) !== (saved?.staySeconds ?? 0)
}
/**
 * 保存巡迹（名称 + 停留时间）。
 *
 * <p>名称写 {@code AutoPattern}（CGI），停留时间写 {@code PatternPlayDetail}（RPC2）。
 * 后者必须走 RPC2 —— CGI 对该字段一律 400。
 */
async function savePattern() {
  const changed = patternRows.value.filter(patternRowDirty)
  if (!changed.length) {
    ElMessage.info('没有改动')
    return
  }
  try {
    for (const row of changed) {
      await dahuaApi.savePattern(extParams.value, {
        patternId: row.patternId,
        name: (row.name || '').trim() || 'Pattern' + row.patternId,
        staySeconds: row.staySeconds ?? 0
      })
    }
    ElMessage.success(`已保存 ${changed.length} 条巡迹`)
    await loadPtzExt()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  }
}

/**
 * 新增一条空线扫：把第一个「还没被配置」的编号标记成新建。
 *
 * <p><b>不能 push 新行</b>：{@code scanRows} 里已经有设备返回的全部 32 个 scanId，
 * 再 push 同编号的行会撞 {@code row-key}（保存条数虚高、两行互相干扰）。
 */
function newScan() {
  const target = scanRows.value.find(
    (s) => !s.isNew && !s.leftSet && !s.rightSet && s.speed === SCAN_DEFAULT_SPEED
  )
  if (!target) {
    ElMessage.warning('线扫已满（最多 ' + SCAN_MAX + ' 条）')
    return
  }
  target.isNew = true
  // 用非出厂值，否则保存后 configuredScans 会把它当空槽位过滤掉
  target.speed = SCAN_DEFAULT_SPEED + 1
}

/**
 * 切换某条线扫的启停（设备「執行」列）。
 *
 * <p>与巡航组一样是<b>即时动作</b>，不走保存。左右边界没设全的线按钮本来就是灰的，
 * 这里再兜一次，避免竞态下发出无效指令。
 */
async function toggleScanRun(row) {
  if (!row.leftSet || !row.rightSet) {
    ElMessage.warning('左右边界都设置后才能执行')
    return
  }
  const mine = activeRun.value.type === 'scan' && activeRun.value.id === row.scanId
  if (isRunning.value && !mine) {
    ElMessage.warning(`「${runningLabel.value}」正在运行，请先停止它再启动别的动作`)
    return
  }
  const start = !mine
  try {
    await dahuaApi.ptzAction({
      ...extParams.value,
      action: 'line-scan',
      route: row.scanId,
      start
    })
    ElMessage.success(start ? `开始线扫 ${row.scanId}` : '已停止线扫')
    await loadPtzExt()
  } catch (e) {
    ElMessage.error(e.message || (start ? '启动失败' : '停止失败'))
    await loadPtzExt()
  }
}

/** 清空一条线扫：边界与速度回设备默认（与设备「删除」同义）。 */
async function clearScan(row) {
  if (!(await confirmAction(
    `确定清空线扫 ${row.scanId}？左右边界会一并清除。`,
    '清空线扫',
    { confirmButtonText: '清空' }
  ))) {
    return
  }
  try {
    if (runningScan.value === row.scanId) {
      await dahuaApi.ptzAction({ ...extParams.value, action: 'line-scan', route: row.scanId, start: false })
    }
    /*
     * 设备没有「删除线扫」指令，清空 = 把速度写回默认值并停用左右边界。
     * 边界本身只能用「以当前位置设置」写入，没有清除接口，所以这里把两条都置 false。
     */
    await dahuaApi.saveScan(extParams.value, { scanId: row.scanId, speed: 4 })
    ElMessage.success('已清空线扫 ' + row.scanId)
    await loadPtzExt()
  } catch (e) {
    ElMessage.error(e.message || '清空失败')
  }
}

/** 清空全部线扫（对应设备「線性掃描」页工具栏的「清 空」）。 */
async function clearAllScans() {
  const targets = configuredScans.value
  if (!targets.length) return
  if (!(await confirmAction(
    `确定清空全部 ${targets.length} 条线扫？速度会回默认值。`,
    '清空全部线扫',
    { confirmButtonText: '全部清空' }
  ))) return
  try {
    if (runningScan.value) {
      try {
        await dahuaApi.ptzAction({ ...extParams.value, action: 'line-scan', route: runningScan.value, start: false })
      } catch (e) { /* 停不下来也继续 */ }
    }
    for (const s of targets) {
      await dahuaApi.saveScan(extParams.value, { scanId: s.scanId, speed: SCAN_DEFAULT_SPEED })
    }
    ElMessage.success(`已清空 ${targets.length} 条线扫`)
    await loadPtzExt()
  } catch (e) {
    ElMessage.error(e.message || '清空失败')
  }
}

/**
 * 「查看」限位坐标（设备「PTZ旋轉限制」页的「查 看」按钮）。
 *
 * <p>设备是读一次当前存的边界坐标再显示。这里直接重读整份云台配置 ——
 * 因为限位坐标在 {@code PtzLimit} 里，和开关是同一个配置项，一次读全。
 */
async function viewLimit() {
  await loadPtzExt()
  ElMessage.success(`上限 ${limitForm.upBoundary} / 下限 ${limitForm.downBoundary}`)
}

/** 清空全部定时任务（对应设备「已排定工作」页工具栏的「清 空」）。 */
async function clearAllAutos() {
  const targets = configuredAutos.value
  if (!targets.length) return
  if (!(await confirmAction(
    `确定清空全部 ${targets.length} 条排程任务？`,
    '清空全部排程',
    { confirmButtonText: '全部清空' }
  ))) return
  try {
    for (const a of targets) {
      /*
       * 设备没有「删除排程」接口，清空 = 全部 4 个下标都置 Function=None + Enable=false。
       * 这里按 index 逐个置空（设备 UI 也是这么做的）。
       */
      await dahuaApi.saveAutoMovement(extParams.value, {
        index: a.index, enable: false, function: 'None',
        presetId: null, tourId: null, patternId: null, scanId: null,
        homingSeconds: a.homingSeconds, ranges: []
      })
    }
    ElMessage.success(`已清空 ${targets.length} 条排程任务`)
    await loadPtzExt()
  } catch (e) {
    ElMessage.error(e.message || '清空失败')
  }
}

/** 旋转限制开关 + 设备回读的边界值（只读展示，设置要靠即时动作）。 */
const limitForm = reactive({
  enable: false, horizontalEnable: false,
  upBoundary: '未设置', downBoundary: '未设置'
})
/**
 * PTZ 速度表单（设备「PTZ速度」页只有一个三档控件）。
 *
 * <p><b>{@code ptzSpeed} 与设备滑块一一对应：0=低 / 1=中 / 2=高</b>，
 * 不做任何映射。早期平台自创「0~4 五档、低=0/中=2/高=4」，
 * 导致设备存 1（中）时被显示成「低」。
 *
 * <p>{@code patternSpeed} 只用于回显/比对，<b>不提交</b>：设备界面没有这个入口
 * （出厂 5 由固件维护），后端收到 null 会沿用设备现值。
 */
const speedForm = reactive({ ptzSpeed: 1, patternSpeed: 5 })

/**
 * 水平旋转速度（设备 {@code AutoPan[ch].PanSpeed}）。
 *
 * <p><b>与 {@link speedForm}.ptzSpeed 是两个不同字段</b>，不要合并：
 * 水準旋轉页是 AutoPan，PTZ速度页是 PtzSpeed（0~4）。实测设备分开存。
 *
 * <p><b>范围 1~8，与设备界面完全一致</b>：设备滑块显示 1~8，
 * 内部存 0~7（界面值 = 内部值 + 1）。后端负责这个 ±1 换算，
 * 前端只管界面口径，这样用户看到的数字和设备的 Web 界面一模一样。
 */
const AUTO_PAN_MIN = 1
const AUTO_PAN_MAX = 8
const panSpeed = ref(1)
/** 水平旋转是否在跑。派生自设备状态，见 {@link activeRun}。 */
const panRunning = computed(() => activeRun.value.type === 'pan')

/** 空闲动作（IdleMotion，分钟级）。 */
const idleForm = reactive({ enable: false, function: 'None', target: 1, timerMinutes: 10 })

/**
 * 动作类型变了，目标要落到该类型下真实存在的编号上。
 *
 * <p>目标现在是下拉（只列已配置的项）。如果沿用上一个类型的编号，
 * 下拉找不到匹配项会渲染成空白，保存时还会把一个不存在的编号写进设备 ——
 * 设备收到不存在的编号只会静默不动，现场很难查。
 */
watch(() => idleForm.function, () => {
  const opts = targetOptionsFor(idleForm.function)
  if (!opts.some((o) => o.value === Number(idleForm.target))) {
    idleForm.target = opts.length ? opts[0].value : 0
  }
})

const extParams = computed(() => ({ serial: props.serial, code: props.code }))

/**
 * 读云台配置。
 *
 * <p>失败只提示、不抛：这段配置依赖设备 CGI，型号差异大，
 * 读不到时页面上其余动作（巡航/巡迹按钮）仍应可用。
 */
async function loadPtzExt() {
  if (!isSdkDevice.value || !props.serial) {
    return
  }
  ptzExt.loading = true
  try {
    const data = await dahuaApi.ptzConfig(extParams.value)
    ptzExt.tours = data?.tours || []
    ptzExt.scans = data?.scans || []
    ptzExt.patterns = data?.patterns || []
    ptzExt.speed = data?.speed || { ptzSpeed: 1, patternSpeed: 5 }
    ptzExt.autoPan = data?.autoPan || { panSpeed: 1 }
    ptzExt.idle = data?.idle || ptzExt.idle
    ptzExt.autos = data?.autoMovements || []
    ptzExt.limit = data?.limit || { enable: false, horizontalEnable: false }
    ptzExt.running = data?.running || ptzExt.running
    /*
     * 「执行」列的开关状态不用在这里赋值：它是 computed，直接读
     * ptzExt.running（见 activeRun）。设备 TaskName=Manual 即运行中，
     * Function 说明是哪种动作，Index 是 0-based 下标。
     * 早期这里试图用「本地记住我点过什么」兜底，会有两个问题：
     * 刷新/重进页面状态全丢；设备上在跑却显示未运行。
     */
    speedForm.ptzSpeed = ptzExt.speed.ptzSpeed
    speedForm.patternSpeed = ptzExt.speed.patternSpeed
    // 水平旋转速度是独立配置项（AutoPan.PanSpeed 1~8），不是 speedForm.ptzSpeed
    panSpeed.value = data?.autoPan?.panSpeed ?? AUTO_PAN_MIN
    limitForm.enable = ptzExt.limit.enable
    limitForm.horizontalEnable = ptzExt.limit.horizontalEnable
    limitForm.upBoundary = ptzExt.limit.upBoundary ?? '未设置'
    limitForm.downBoundary = ptzExt.limit.downBoundary ?? '未设置'
    idleForm.enable = ptzExt.idle.enable
    idleForm.function = ptzExt.idle.function || 'None'
    idleForm.timerMinutes = ptzExt.idle.timerMinutes || 10
    idleForm.target = idleTargetOf(ptzExt.idle)
    fillTours(data?.tours)
    fillScans(data?.scans)
    fillPatterns(data?.patterns)
    fillAutos(data?.autoMovements)
  } catch (e) {
    ElMessage.warning('读取云台配置失败：' + (e.message || e))
  } finally {
    ptzExt.loading = false
  }
}

/**
 * 空闲动作的目标号藏在四个字段之一里，按 function 取。
 */
function idleTargetOf(idle) {
  if (!idle) return 1
  switch (idle.function) {
    case 'Preset': return idle.presetId || 1
    case 'Tour': return idle.tourId || 1
    case 'Pattern': return idle.patternId || 1
    case 'Scan': return idle.scanId || 1
    default: return 1
  }
}

/** 定时任务行（整表可编辑，对齐设备「已排定工作」页）。 */
const autoRows = ref([])
/** 设备支持的排程条数上限（能力位 TaskNum=4）。 */
const AUTO_MAX = 4
/** 展开中的排程行（index 列表）。 */
const expandedAutos = ref([])

/**
 * 只显示「已配置」的排程。
 *
 * <p>设备恒返回 4 条，未配置的都是 Function=None + Enable=false。
 * 与巡航组/线扫/巡迹同样的取舍：空条目全铺出来是噪声。
 */
const configuredAutos = computed(() =>
  autoRows.value.filter((a) => {
    if (a.isNew) return true
    // 设备用「工作動作=無」表示未启用，没有独立的启用开关
    return (a.function || 'None') !== 'None'
  })
)

/**
 * 把设备返回的排程灌进表格行。
 *
 * <p>时段格式是 {@code "d[slot]|<值>"}（后端透出 7 天 × 每天 6 段共 42 格），
 * 值形如 {@code "1 08:00:00-12:00:00"}，首位是<b>该段自己的</b>启用标记。
 * 只收 {@code 1} 开头那些段 —— 设备把没用到的槽位都填成
 * {@code "0 00:00:00-23:59:59"}，收进来会让界面出现一堆假的「全天」时段。
 *
 * <p>⚠️ 不要给默认值兜底（例如「没配就给周一到周五 08:00-18:00」）：
 * 那会让 {@link autoRowDirty} 认为空排程也和保存值不同 → 空条目全是脏的 →
 * 「保存定时任务」按钮永远亮着。空就是空，新建时才给默认值。
 */
function fillAutos(autos) {
  autoRows.value = (autos || []).map((a) => ({
    index: a.index,
    enable: !!a.enable,
    function: a.function || 'None',
    target: autoTargetOf(a),
    homingSeconds: a.homingSeconds || 30,
    ranges: parseSavedRanges(a.timeSections),
    isNew: false
  }))
}

/**
 * 从设备回读的时段串里只取「启用」的那些，转成界面用的 {@code {day, from, to}}。
 *
 * <p>格式：{@code "1[0]|1 08:00:00-12:00:00"}（星期[段号]|启用标记 起-止）。
 * 全部 42 格都会传过来，这里按首位过滤，禁用段一律不显示。
 */
function parseSavedRanges(timeSections) {
  const out = []
  for (const s of timeSections || []) {
    const bar = s.indexOf('|')
    if (bar < 0) continue
    const day = Number(s.slice(0, s.indexOf('[')))
    const body = s.slice(bar + 1).trim()
    if (!body.startsWith('1 ')) continue
    const m = body.slice(2).match(/^(\d{2}:\d{2}):\d{2}-(\d{2}:\d{2}):\d{2}$/)
    if (!m) continue
    out.push({ day, from: m[1], to: m[2] })
  }
  return out.sort((x, y) => x.day - y.day || x.from.localeCompare(y.from))
}

/** 每个星期最多几段（设备 TimeSection[7][6]）。 */
const AUTO_SECTIONS_PER_DAY = 6

/** 某一天已经用掉几段。 */
function autoDayCount(row, day) {
  return row.ranges.filter((r) => r.day === day).length
}

/**
 * 给排程加一段时段。
 *
 * <p>优先填「还没满 6 段」的那一天：设备限定每天 6 段，满了会被拒。
 * 默认从 08:00 到 18:00，与设备出厂时间计划的常见值一致。
 */
function addAutoRange(row) {
  const day = [1, 2, 3, 4, 5, 6, 0].find((d) => autoDayCount(row, d) < AUTO_SECTIONS_PER_DAY)
  if (day === undefined) {
    ElMessage.warning(`每个星期最多 ${AUTO_SECTIONS_PER_DAY} 段时段`)
    return
  }
  row.ranges.push({ day, from: '08:00', to: '18:00' })
  row.ranges.sort((x, y) => x.day - y.day || x.from.localeCompare(y.from))
}

/**
 * 新增一条排程：把第一个「工作动作=无」的下标标记成新建。
 *
 * <p><b>不能 push 新行</b>：{@code autoRows} 里已经有设备返回的全部 4 个下标，
 * 再 push 同下标的行会撞 {@code row-key}。症状很隐蔽：
 * 提示「已保存 2 条排程任务」但设备只有 1 条被写，因为两行都指向同一个 index。
 */
function newAuto() {
  const target = autoRows.value.find((a) => !a.isNew && (a.function || 'None') === 'None')
  if (!target) {
    ElMessage.warning('排程任务已满（最多 ' + AUTO_MAX + ' 条）')
    return
  }
  target.isNew = true
  target.function = 'Tour'
  target.target = targetOptionsFor('Tour')[0]?.value || 1
  target.homingSeconds = 30
  // 工作日 08:00-18:00 作为新建默认值（用户可随意改成每天不同时段）
  target.ranges = [1, 2, 3, 4, 5].map((day) => ({ day, from: '08:00', to: '18:00' }))
  expandedAutos.value = [...new Set([...expandedAutos.value, target.index])]
}

/**
 * 保持排程行的展开状态同步。
 *
 * <p>⚠️ {@code expand-change} 的第二个参数在非树形表格里是<b>当前已展开行的数组</b>，
 * 不是布尔值。早期写成 {@code expanded ? 加 : 减}，数组恒为真 → 只会加不会减，
 * 表现为「能展开、收不起来」。必须自己判断这一行在不在数组里。
 */
function onAutoExpand(row, expanded) {
  const has = expandedAutos.value.includes(row.index)
  const nowExpanded = Array.isArray(expanded) ? expanded.includes(row) : !!expanded
  if (nowExpanded && !has) expandedAutos.value = [...expandedAutos.value, row.index]
  if (!nowExpanded && has) expandedAutos.value = expandedAutos.value.filter((i) => i !== row.index)
}

/**
 * 切换动作类型时把目标换成该类型下的第一个有效编号。
 *
 * <p>不能只重置成 1：巡航组/线扫/巡迹的下标从 1 开始但**不保证 1 号存在**
 * （实测线扫 1、2 都有，巡航组只有 1，巡迹只有 1、2）。沿用上一个类型的编号
 * 会指向不存在的项，设备收到只会静默不动。
 */
function onAutoFunctionChange(row) {
  const opts = targetOptionsFor(row.function)
  if (!opts.length) {
    row.target = 0
    return
  }
  // 当前值仍在新列表里就保留，否则取第一个
  if (!opts.some((o) => o.value === Number(row.target))) {
    row.target = opts[0].value
  }
}

/** 清空一条排程：Function=None + Enable=false（设备没有「删除」接口）。 */
async function clearAuto(row) {
  if (!(await confirmAction(
    `确定清空排程任务 ${row.index + 1}？`,
    '清空排程',
    { confirmButtonText: '清空' }
  ))) {
    return
  }
  try {
    await dahuaApi.saveAutoMovement(extParams.value, {
      index: row.index, enable: false, function: 'None',
      presetId: null, tourId: null, patternId: null, scanId: null,
      homingSeconds: row.homingSeconds,
      // 空数组 = 把 42 个时段槽全置成禁用，与设备「清空」一致
      ranges: []
    })
    ElMessage.success('排程已清空')
    await loadPtzExt()
  } catch (e) {
    ElMessage.error(e.message || '清空失败')
  }
}

/**
 * 某条排程是否有改动。
 *
 * <p>比较：启用 / 动作 / 目标 / 归位秒 / <b>时段（逐段比，含星期）</b>。
 * 时段用「归一化字符串」比：先按星期 + 起止排序再拼，顺序不同不算改动。
 */
function autoRowDirty(row) {
  if (row.isNew) return true
  const saved = ptzExt.autos.find((a) => a.index === row.index)
  if (!saved) return true
  return saved.enable !== (row.function !== 'None')
    || (saved.function || 'None') !== row.function
    || autoTargetOf(saved) !== row.target
    || saved.homingSeconds !== row.homingSeconds
    || rangesKeyOfSaved(saved) !== rangesKeyOfRow(row)
}

/** 把「界面上正在编辑的时段」归一化成可比较的字符串。 */
function rangesKeyOfRow(row) {
  return [...row.ranges]
    .map((r) => `${r.day} ${r.from}-${r.to}`)
    .sort()
    .join('|')
}

/** 把「设备回读的时段」归一化成同一格式，便于比较。 */
function rangesKeyOfSaved(saved) {
  return parseSavedRanges(saved.timeSections)
    .map((r) => `${r.day} ${r.from}-${r.to}`)
    .sort()
    .join('|')
}


/** 定时任务的目标号藏在四个字段之一里。 */
function autoTargetOf(a) {
  if (!a) return 1
  switch (a.function) {
    case 'Preset': return a.presetId || 1
    case 'Tour': return a.tourId || 1
    case 'Pattern': return a.patternId || 1
    case 'Scan': return a.scanId || 1
    default: return 1
  }
}

async function ptzAct(action, extra = {}, okMsg) {
  try {
    await dahuaApi.ptzAction({ ...extParams.value, action, ...extra })
    ElMessage.success(okMsg || '指令已下发')
  } catch (e) {
    ElMessage.error(e.message || '指令失败')
  }
}

async function saveTour() {
  // 逐条保存：只写改动过的行，未动的行不碰设备
  const changed = tourRows.value.filter(tourRowDirty)
  if (!changed.length) {
    ElMessage.info('没有改动')
    return
  }
  try {
    for (const row of changed) {
      await dahuaApi.saveTour(extParams.value, {
        route: row.route,
        /*
         * 传设备原值，不传输入框里的值。
         * 设备 PtzTour.Name 只读（CGI 400 / SDK 回 ok 但值不变），
         * 传新名字会让后端回读校验报错，而用户其实只想存预置点。
         */
        name: ptzExt.tours.find((t) => t.route === row.route)?.name || '',
        /*
         * 配置项里的 Enable 与「执行」列无关（那是运行时启停）。
         * 这里恒传 false：让设备上的持久标记保持中性，避免下次读回来
         * 被误解成「正在巡航」。真正的启停走 toggleTourRun。
         */
        enable: false,
        points: row.points
      })
    }
    ElMessage.success(`已保存 ${changed.length} 条巡航组`)
    await loadPtzExt()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  }
}

async function saveSpeed() {
  try {
    /*
     * 只提交 ptzSpeed。patternSpeed 不传（后端按 null 处理 = 沿用设备现值）：
     * 设备界面没有「巡迹速度」这个入口，出厂值 5 由固件自己维护，
     * 平台硬塞一个值只会把设备原本的 5 改掉。
     */
    await dahuaApi.savePtzSpeed(extParams.value, { ptzSpeed: speedForm.ptzSpeed })
    ElMessage.success('速度已保存')
    // 重新读回基线，否则 dirtyMap.speed 一直为 true，保存按钮永远亮着
    await loadPtzExt()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  }
}

/**
 * 开始 / 停止水平旋转。
 *
 * <p>设备「水準旋轉」页的「開 始 / 停 止」发的是 {@code ptz.start} 的
 * {@code AutoPanOn} / {@code AutoPanOff}（同一个按钮切换）。
 * 平台侧走 {@code pan-cruise} 动作，SDK 与 RPC2 两条路都已实现。
 *
 * <p>设备在旋转期间会禁用滑块并提示「一些設定無法在PTZ水平旋轉期間操作」，
 * 这里只做文案提示，不禁用控件 —— 禁用了用户会以为界面卡住。
 */
async function togglePanRun(start) {
  // 互斥：别的动作在跑时不让启动（设备同样禁用「開始」）
  if (start && isRunning.value && activeRun.value.type !== 'pan') {
    ElMessage.warning(`「${runningLabel.value}」正在运行，请先停止它再启动水平旋转`)
    return
  }
  try {
    await dahuaApi.ptzAction({ ...extParams.value, action: 'pan-cruise', start })
    ElMessage.success(start ? '水平旋转已开始' : '水平旋转已停止')
    await loadPtzExt()
  } catch (e) {
    ElMessage.error(e.message || '指令失败')
    await loadPtzExt()
  }
}

/**
 * 保存水平旋转速度。
 *
 * <p>写 {@code AutoPan.PanSpeed}（1~8），与「速度」页的 {@code PtzSpeed}（0~4）
 * 是两个独立字段 —— 设备上也是两页分开的。
 */
async function saveAutoPan() {
  try {
    await dahuaApi.saveAutoPan(extParams.value, { panSpeed: panSpeed.value })
    ElMessage.success('水平旋转速度已保存')
    // 重新读回基线，否则 dirtyMap.rotate 一直为 true，保存按钮永远亮着
    await loadPtzExt()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  }
}

/** 保存线扫速度（左右边界是即时动作，不在保存范围内）。 */
async function saveScan() {
  const changed = scanRows.value.filter(scanRowDirty)
  if (!changed.length) {
    ElMessage.info('没有改动')
    return
  }
  try {
    for (const row of changed) {
      await dahuaApi.saveScan(extParams.value, { scanId: row.scanId, speed: row.speed })
    }
    ElMessage.success(`已保存 ${changed.length} 条线扫`)
    await loadPtzExt()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  }
}

/**
 * 以当前位置设某条线扫的左/右边界（走 RPC2 ptz.setScanLimit）。
 *
 * <p>这是<b>即时动作</b>：设备侧直接读云台当前角度写入，没有「先填值再保存」的形式，
 * 所以设备界面也是两个保存图标而不是输入框。
 */
async function setScanLimit(row, left) {
  try {
    await dahuaApi.ptzAction({
      ...extParams.value,
      action: left ? 'scan-limit-left' : 'scan-limit-right',
      route: row.scanId
    })
    if (left) row.leftSet = true
    else row.rightSet = true
    ElMessage.success((left ? '左' : '右') + '边界已设为当前位置')
    await loadPtzExt()
  } catch (e) {
    ElMessage.error(e.message || '设置边界失败')
  }
}

/** 保存旋转限制开关（边界本身是即时动作）。 */
async function saveLimit() {
  try {
    await dahuaApi.saveLimit(extParams.value, { ...limitForm })
    ElMessage.success('限位开关已保存')
    await loadPtzExt()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  }
}

/**
 * 保存空闲动作（设备 IdleMotion）。
 *
 * <p>与定时任务（PtzAutoMovement）是两份独立配置，各自有独立端点，
 * 不再靠「类型单选 + 共用请求体」区分 —— 那样很容易把字段写进错的配置项。
 */
async function saveIdle() {
  const f = idleForm.function
  try {
    await dahuaApi.saveIdle(extParams.value, {
      enable: idleForm.enable,
      function: f,
      timerMinutes: idleForm.timerMinutes,
      presetId: f === 'Preset' ? idleForm.target : 1,
      tourId: f === 'Tour' ? idleForm.target : 1,
      patternId: f === 'Pattern' ? idleForm.target : 1,
      scanId: f === 'Scan' ? idleForm.target : 1
    })
    ElMessage.success('空闲动作已保存')
    await loadPtzExt()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  }
}

/**
 * 保存定时任务（设备 PtzAutoMovement）。
 *
 * <p>四个目标字段是独立的，必须按 function 分别填：
 * Preset→presetId / Tour→tourId / Pattern→patternId / Scan→scanId。
 * 早先把 Tour 的目标写进 scanId，设备侧就一直读不到巡航组号。
 *
 * <p>时段按 {@code ranges: [{day, from, to}]} 逐段提交，<b>每段自带星期</b>：
 * 设备是 7 天 × 每天 6 段，支持「周一 8-12、周二 19-23」以及同一天多段。
 * 保存前先做客户端校验（结束须晚于开始、每天不超过 6 段），
 * 避免整表提交后后端报错、用户不知道是哪一行的问题。
 */
async function saveAuto() {
  const changed = autoRows.value.filter(autoRowDirty)
  if (!changed.length) {
    ElMessage.info('没有改动')
    return
  }
  // 先整体校验，任何一条不合格就中止，避免写一半
  for (const row of changed) {
    if (row.function === 'None') continue
    if (!row.ranges.length) {
      ElMessage.warning(`排程 ${row.index + 1} 还没有时段，任务不会触发`)
      return
    }
    for (const r of row.ranges) {
      if (!r.from || !r.to) {
        ElMessage.warning(`排程 ${row.index + 1} 有未填完整的时间段`)
        return
      }
      if (r.from >= r.to) {
        ElMessage.warning(`排程 ${row.index + 1} 的时段 ${r.from}~${r.to}：结束时间必须晚于开始时间`)
        return
      }
    }
    for (let d = 0; d < 7; d++) {
      if (autoDayCount(row, d) > AUTO_SECTIONS_PER_DAY) {
        ElMessage.warning(`排程 ${row.index + 1} 的${WEEK_LABELS[d]}超过 ${AUTO_SECTIONS_PER_DAY} 段`)
        return
      }
    }
  }
  try {
    for (const row of changed) {
      const f = row.function
      await dahuaApi.saveAutoMovement(extParams.value, {
        index: row.index,
        // 设备模型：工作動作=無 即未启用（没有独立的启用字段需传）
        enable: row.function !== 'None',
        function: f,
        presetId: f === 'Preset' ? row.target : null,
        tourId: f === 'Tour' ? row.target : null,
        patternId: f === 'Pattern' ? row.target : null,
        scanId: f === 'Scan' ? row.target : null,
        homingSeconds: row.homingSeconds,
        // function=None 时不传时段，避免把「无动作」的旧时段一起写回去
        ranges: f === 'None' ? null : row.ranges.map((r) => ({ day: r.day, from: r.from, to: r.to }))
      })
    }
    ElMessage.success(`已保存 ${changed.length} 条排程任务`)
    await loadPtzExt()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  }
}

onMounted(() => {
  // 全局兜底：指针在按钮外释放、被系统取消、窗口失焦（Alt+Tab）时都要停，
  // 否则云台会一直转到设备自身超时。endMove 内有 moving 守卫。
  window.addEventListener('pointerup', endMove)
  window.addEventListener('pointercancel', endMove)
  window.addEventListener('blur', endMove)
  document.addEventListener('visibilitychange', onVisibilityChange)
  // SDK 设备的云台扩展配置只在面板打开时读一次：设备 CGI 是同步 HTTP，频繁读会拖慢设备
  if (isSdkDevice.value) {
    loadPtzExt()
    // 运行状态是易变的（启停只在下发那刻改变、巡航/巡迹会自己跑完），
    // 所以单独轮询轻量接口（只读 PtzMovement 一项），让「执行」开关不靠本地记忆。
    refreshRunning()
    runningTimer = setInterval(refreshRunning, 3000)
    // 对讲占用状态：别人正在喊话时要禁用按钮，并定期刷新（会话可能被别人结束）
    refreshTalkBusy()
    talkBusyTimer = setInterval(refreshTalkBusy, 15000)
  }
})

onUnmounted(() => {
  disposed = true
  window.removeEventListener('pointerup', endMove)
  window.removeEventListener('pointercancel', endMove)
  window.removeEventListener('blur', endMove)
  document.removeEventListener('visibilitychange', onVisibilityChange)
  // 面板关闭时若仍在转动，补发 stop
  endMove()
  if (runningTimer) {
    clearInterval(runningTimer)
    runningTimer = null
  }
  // 对讲必须显式结束：设备侧同时对讲只能有一路，不释放会让别人一直喊不了话
  if (talkBusyTimer) {
    clearInterval(talkBusyTimer)
    talkBusyTimer = null
  }
  if (talk.active || talk.talkId) {
    stopDahuaTalk(false)
  }
})

/**
 * 下发控制指令。
 *
 * @param action 动作名（见 ACTIONS）
 * @param extra 附加参数
 * @param okMsg 成功提示（null 表示静默）
 * @param silent 是否静默失败（用于 stop 等高频指令）
 */
async function cmd(action, extra = {}, okMsg = '指令已下发', silent = false) {
  return send(action, extra, okMsg, silent)
}

async function send(action, extra = {}, okMsg = '指令已下发', silent = false) {
  if (!props.serial) {
    if (!silent) ElMessage.warning('请先选择通道')
    return false
  }
  const fn = ACTIONS[action]
  if (!fn) {
    if (!silent) ElMessage.error('未实现的控制指令：' + action)
    return false
  }
  try {
    await fn({ serial: props.serial, code: props.code, ...extra })
    if (okMsg && !silent) ElMessage.success(okMsg)
    return true
  } catch (e) {
    if (!silent) ElMessage.error(e.message || '控制指令下发失败')
    return false
  }
}

async function toggleTalk() {
  if (!props.serial) {
    ElMessage.warning('请先选择通道')
    return
  }
  if (talking.value) {
    await controlApi.talk({ serial: props.serial, code: props.code })
    talking.value = false
    ElMessage.success('已停止喊话')
    return
  }
  try {
    await controlApi.talk({ serial: props.serial, code: props.code })
    talking.value = true
    ElMessage.success('已开始喊话')
  } catch (e) {
    ElMessage.error(e.message || '喊话启动失败')
  }
}

/* ==================== 大华直连设备：喊话 / 双工对讲 ====================
 *
 * 与上面 toggleTalk（国标 /control/talk）是两条完全不同的通路，不要合并：
 *   · 国标：平台发 SIP 信令，音频由设备自己回传，平台不管音频；
 *   · 大华：服务端 StartTalkEx 建通道（设备级互斥），浏览器负责采集与播放。
 *
 * 音频口径固定 8 kHz / 16 bit / 单声道裸 PCM（服务端已把设备编码协商成 PCM）。
 * 采集用 ScriptProcessor 而不是 AudioWorklet：worklet 要单独一个模块文件 + 打包路径，
 * 而这里只是 8k 语音，4096 帧的延迟（约 85 ms）完全够用，不值得引入构建复杂度。
 */
const talk = reactive({
  active: false,
  starting: false,
  talkId: '',
  busy: false,
  busyOwner: '',
  listen: true,
  mic: true,
  /** 已收到的下行帧数：用来区分「设备支持双工」和「压根没回传音频」。 */
  downFrames: 0
})

const talkHint = computed(() => {
  if (talk.starting) return '正在建立通道…'
  if (talk.active) return talk.downFrames > 0 ? '对讲中（双向）' : '对讲中…'
  if (talk.busy) return `${talk.busyOwner || '他人'} 正在喊话`
  return '未开始'
})
const listenHint = computed(() => {
  if (!talk.active) return '未连接'
  if (!talk.listen) return '已关闭'
  return talk.downFrames > 0 ? `已接收 ${talk.downFrames} 帧` : '等待设备音频…'
})

/** 播放/采集共用的 AudioContext（懒建，浏览器要求用户手势后才能 resume）。 */
let talkCtx = null
let micStream = null
let micSource = null
let micProcessor = null
let talkWs = null
/** 待发送的 Int16 采样（攒够 200 ms 再 POST，减少请求数）。 */
let pendingSamples = []
let pendingLength = 0
/** 上行发送串行化：保证到达设备的顺序，又不阻塞采集回调。 */
let sendChain = Promise.resolve()
/** 下行播放排期（AudioContext 时间轴），保证连续不叠音。 */
let playCursor = 0
let talkBusyTimer = null

/** 一帧上行字节数：200 ms @ 8 kHz/16 bit 单声道。 */
const TALK_CHUNK_BYTES = 3200

async function refreshTalkBusy() {
  if (!isSdkDevice.value || !props.serial || disposed) return
  try {
    const res = await dahuaApi.talkBusy({ serial: props.serial })
    if (disposed) return
    talk.busy = !!res?.busy
    talk.busyOwner = res?.owner || ''
  } catch (e) {
    // 查询失败不影响手动操作：真正的互斥由后端 /talk/start 兜底
    talk.busy = false
  }
}

async function toggleDahuaTalk() {
  if (!props.serial) {
    ElMessage.warning('请先选择通道')
    return
  }
  if (talk.active) {
    await stopDahuaTalk(true)
    return
  }
  await startDahuaTalk()
}

async function startDahuaTalk() {
  if (talk.starting) return
  talk.starting = true
  try {
    const s = await dahuaApi.talkStart({ serial: props.serial })
    talk.talkId = s.talkId
    talk.active = true
    talk.downFrames = 0
    talk.listen = true
    talk.mic = true
    await startMic(s.sampleRate || 8000)
    connectTalkWs(s.wsUrl)
    ElMessage.success('已开始对讲')
  } catch (e) {
    // 失败要清干净：否则设备通道被占着，用户再点会被自己挡住
    await stopDahuaTalk(false)
    ElMessage.error(e.message || '对讲启动失败')
  } finally {
    talk.starting = false
    refreshTalkBusy()
  }
}

async function stopDahuaTalk(notify) {
  const talkId = talk.talkId
  stopMic()
  closeTalkWs()
  if (talkCtx) {
    try {
      await talkCtx.close()
    } catch (e) {
      /* 忽略 */
    }
    talkCtx = null
  }
  pendingSamples = []
  pendingLength = 0
  playCursor = 0
  talk.active = false
  talk.talkId = ''
  talk.downFrames = 0
  if (talkId) {
    try {
      await dahuaApi.talkStop(talkId)
      if (notify) ElMessage.success('已结束对讲')
    } catch (e) {
      // 停止失败（会话可能已超时释放）不打扰用户
      if (notify) ElMessage.warning(e.message || '结束对讲失败，可能已超时释放')
    }
  }
  refreshTalkBusy()
}

/** 开麦采集：getUserMedia → 重采样到 8 kHz → 攒块 → POST。 */
async function startMic(sampleRate) {
  if (!navigator.mediaDevices?.getUserMedia) {
    throw new Error('浏览器不支持录音（需要 https 或 localhost）')
  }
  micStream = await navigator.mediaDevices.getUserMedia({
    audio: {
      channelCount: 1,
      echoCancellation: true,
      noiseSuppression: true,
      autoGainControl: true
    }
  })
  talkCtx = new AudioContext()
  // 浏览器可能把 AudioContext 起成 suspended（需用户手势），这里显式恢复
  if (talkCtx.state === 'suspended') {
    await talkCtx.resume()
  }
  micSource = talkCtx.createMediaStreamSource(micStream)
  micProcessor = talkCtx.createScriptProcessor(4096, 1, 1)
  micProcessor.onaudioprocess = (ev) => {
    if (!talk.active || !talk.mic) return
    const input = ev.inputBuffer.getChannelData(0)
    const pcm = downsampleToInt16(input, talkCtx.sampleRate, sampleRate)
    if (pcm.length) {
      pendingSamples.push(pcm)
      pendingLength += pcm.length
      if (pendingLength * 2 >= TALK_CHUNK_BYTES) {
        flushMic()
      }
    }
  }
  // ScriptProcessor 必须连到 destination 才会被驱动；用零增益避免本地回授
  const mute = talkCtx.createGain()
  mute.gain.value = 0
  micSource.connect(micProcessor)
  micProcessor.connect(mute)
  mute.connect(talkCtx.destination)
}

function stopMic() {
  if (micProcessor) {
    micProcessor.onaudioprocess = null
    try {
      micProcessor.disconnect()
    } catch (e) {
      /* 忽略 */
    }
    micProcessor = null
  }
  if (micSource) {
    try {
      micSource.disconnect()
    } catch (e) {
      /* 忽略 */
    }
    micSource = null
  }
  if (micStream) {
    micStream.getTracks().forEach((t) => t.stop())
    micStream = null
  }
}

function onMicToggle(val) {
  if (!val) {
    pendingSamples = []
    pendingLength = 0
  }
}

function flushMic() {
  if (!pendingLength || !talk.talkId) return
  const total = pendingLength
  const merged = new Int16Array(total)
  let offset = 0
  for (const chunk of pendingSamples) {
    merged.set(chunk, offset)
    offset += chunk.length
  }
  pendingSamples = []
  pendingLength = 0
  const talkId = talk.talkId
  // 串行发送：顺序不能乱（音频帧有先后），但绝不能 await 阻塞采集回调
  sendChain = sendChain
    .then(() => dahuaApi.talkSend(talkId, merged.buffer))
    .catch((e) => {
      // 单帧失败只丢这一帧：断线时不该刷屏报错，真正的失败由 /talk/stop 体现
      console.debug('对讲音频发送失败', e?.message || e)
    })
}

/**
 * 下行收听：连 WebSocket，收到的二进制帧是**裸 PCM16**（服务端已剥掉 DHAV 帧头）。
 */
function connectTalkWs(wsUrl) {
  if (!wsUrl) return
  closeTalkWs()
  const url = normalizeWsUrl(wsUrl)
  talkWs = new WebSocket(url)
  talkWs.binaryType = 'arraybuffer'
  talkWs.onmessage = (ev) => {
    if (!talk.listen || !(ev.data instanceof ArrayBuffer)) return
    talk.downFrames++
    playPcm(new Int16Array(ev.data))
  }
  talkWs.onerror = () => {
    console.debug('对讲音频通道异常')
  }
}

function closeTalkWs() {
  if (!talkWs) return
  try {
    talkWs.onmessage = null
    talkWs.onerror = null
    talkWs.close()
  } catch (e) {
    /* 忽略 */
  }
  talkWs = null
}

function onListenToggle(val) {
  if (!val) return
  // 从「关闭」切到「开」时把播放队列归零，避免补播一大堆积压的音频
  playCursor = 0
}

/** 把服务端返回的 wsUrl 换成当前页面的主机（nginx 反代后 Host 可能不同）。 */
function normalizeWsUrl(raw) {
  try {
    const u = new URL(raw, window.location.href)
    const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    return `${scheme}//${window.location.host}${u.pathname}${u.search}`
  } catch (e) {
    return raw
  }
}

/** 顺序播放一帧 PCM16 @ 8 kHz。 */
function playPcm(int16) {
  if (!talkCtx || !int16.length) return
  try {
    const buffer = talkCtx.createBuffer(1, int16.length, 8000)
    const channel = buffer.getChannelData(0)
    for (let i = 0; i < int16.length; i++) {
      channel[i] = int16[i] / 32768
    }
    const source = talkCtx.createBufferSource()
    source.buffer = buffer
    source.connect(talkCtx.destination)
    const now = talkCtx.currentTime
    // 落后超过 300 ms 说明网络抖动积压了，直接跳到当前时间（宁可丢帧也不要越来越延迟）
    if (playCursor < now - 0.3) {
      playCursor = now
    }
    if (playCursor < now) {
      playCursor = now + 0.02
    }
    source.start(playCursor)
    playCursor += buffer.duration
  } catch (e) {
    console.debug('对讲音频播放失败', e?.message || e)
  }
}

/**
 * 任意采样率 → 8 kHz Int16 单声道。
 *
 * <p>用「区间平均」而不是直接抽点：直接抽点（每 N 个取 1 个）会把 4 kHz 以上的
 * 成分折叠进语音频带（混叠），听感是刺耳的沙沙声；区间平均是最便宜的
 * 低通+抽取，语音场景够用且不用引第三方重采样库。
 */
function downsampleToInt16(input, inputRate, outputRate) {
  if (inputRate === outputRate) {
    return floatToInt16(input)
  }
  const ratio = inputRate / outputRate
  const outLength = Math.floor(input.length / ratio)
  if (outLength <= 0) return new Int16Array(0)
  const out = new Int16Array(outLength)
  for (let i = 0; i < outLength; i++) {
    const start = Math.floor(i * ratio)
    const end = Math.min(Math.floor((i + 1) * ratio), input.length)
    let sum = 0
    for (let j = start; j < end; j++) {
      sum += input[j]
    }
    let v = sum / Math.max(1, end - start)
    if (v > 1) v = 1
    if (v < -1) v = -1
    out[i] = v < 0 ? v * 0x8000 : v * 0x7fff
  }
  return out
}

function floatToInt16(input) {
  const out = new Int16Array(input.length)
  for (let i = 0; i < input.length; i++) {
    let v = input[i]
    if (v > 1) v = 1
    if (v < -1) v = -1
    out[i] = v < 0 ? v * 0x8000 : v * 0x7fff
  }
  return out
}

/**
 * 自动跟踪开关。
 *
 * <p>状态必须能回落：设备不支持 TargetTrack 时不会报错，也不做任何动作
 * （标准里没有任何回传通道）。所以按「命令发出去了」就置位，
 * 但用户再点一次就发 Stop 复位；不假装自己知道设备真的在跟踪。
 */
const tracking = ref(false)

async function toggleAutoTrack() {
  if (!props.serial) {
    ElMessage.warning('请先选择通道')
    return
  }
  const next = !tracking.value
  try {
    const res = await controlApi.targetTrack({
      serial: props.serial,
      code: props.code || props.serial,
      target_track: next ? 'Auto' : 'Stop'
    })
    tracking.value = next
    reportTrackResult(res, next ? '自动跟踪' : '停止跟踪')
  } catch (e) {
    ElMessage.error(e.message || '目标跟踪指令下发失败')
  }
}

/**
 * 按设备应答类型给提示。三种情况必须分开，不能一律「成功」：
 * OK = 设备受理了；ERROR = 设备拒绝；未答 = 设备没回话（多数设备如此）。
 */
function reportTrackResult(res, action) {
  if (res && res.answered && res.result === 'OK') {
    ElMessage.success(`${action}：设备已受理`)
  } else if (res && res.answered) {
    ElMessage.warning(`${action}：设备拒绝（${res.result || 'ERROR'}）`)
  } else {
    ElMessage.warning(`${action}指令已下发，设备未回应答（以画面是否跟随为准）`)
  }
}
</script>

<style scoped>
.ptz-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
  /*
   * 允许「吃满」父容器：预览弹窗右栏是 flex column 且高度确定时，
   * 这里会撑到底部，把多余的空白留给内容区（而不是留在面板下方）。
   * 父容器不是 flex（如 LiveView 的侧栏）时 flex 属性无效，行为不变。
   */
  flex: 1;
  min-height: 0;
}

/*
 * 方向盘 3×3：按钮 40px、间距 8px，总宽 136px。
 * 44/6 的组合在 300px 栏里会糊成一块，收小按钮反而更疏朗。
 */
.ptz-grid {
  display: grid;
  grid-template-columns: repeat(3, 40px);
  grid-template-rows: repeat(3, 40px);
  grid-template-areas:
    'ul u  ur'
    'l  c  r'
    'dl d  dr';
  gap: 8px;
  justify-content: center;
}

.ptz-btn {
  width: 40px;
  height: 40px;
  padding: 0;
  margin: 0 !important;
}

/*
 * 中心键（语音喊话）与方向键同尺寸；配色交给 el-button 的 type="danger"，
 * 这里只补呼吸光晕提醒麦克风还开着（喊话持续占用设备音频通道）。
 */
.ptz-talk.is-talking {
  animation: talk-pulse 1.6s ease-in-out infinite;
}

/* 默认态方向键都是灰字，麦克风也灰就沉进背景，给点主题色表明可点 */
.ptz-talk:not(.is-talking) :deep(.el-icon) {
  color: var(--accent-ink);
}

.ptz-talk.is-talking :deep(.el-icon) {
  color: #fff;
}

@keyframes talk-pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(245, 108, 108, 0.55); }
  50% { box-shadow: 0 0 0 6px rgba(245, 108, 108, 0); }
}

.ptz-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ptz-row.wrap {
  flex-wrap: wrap;
}

/*
 * 运维动作按钮：固定两列等宽网格。flex-wrap 按自然宽度换行，实测「雨刷开/雨刷关/补光开」
 * 占满一行后「补光关」被甩到第二行；固定两列后每行严格对齐。
 */
.ptz-actions {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

/* 网格里的按钮撑满单元格（否则按内容宽度左对齐，又歪了） */
.ptz-actions > :deep(.el-button),
.ptz-actions > :deep(.el-popconfirm) > .el-button {
  width: 100%;
  margin-left: 0;
}

/* popconfirm 默认 inline-block 会包一层，按钮撑不满单元格，故让它也参与网格 */
.ptz-actions > :deep(.el-popconfirm) {
  display: block;
  min-width: 0;
}

/* 破坏性操作单独一行并撑满，避免与普通按钮同宽造成误点 */
.ptz-actions-danger {
  margin-top: 8px;
}

.ptz-actions-danger :deep(.el-button) {
  width: 100%;
}

/*
 * 变倍组撑满整栏：只剩一组时按自然宽度（约 85px）排会左贴边、右空一大块，
 * flex:1 后与方向盘、下方面板对齐。
 */
.ptz-zoom-row {
  gap: 8px;
}

.ptz-zoom-row .zoom-group {
  flex: 1;
  display: flex;
}

/* button-group 内的按钮撑满各自那一半 */
.ptz-zoom-row .zoom-group :deep(.el-button) {
  flex: 1;
  padding-left: 0;
  padding-right: 0;
}

/* 预置位：编号一行、操作按钮等分一行 */
.ptz-preset {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.preset-label {
  flex: 0 0 auto;
  font-size: var(--fs-sm);
  color: var(--text-secondary);
}

/* 表头：「已用 N」比「共 N 个」有用——用户想知道还有多少空位可存 */
.preset-head {
  display: flex;
  align-items: center;
  gap: 6px;
}

.preset-head .preset-label {
  margin-right: auto;
}

.preset-count {
  font-size: var(--fs-xs);
  color: var(--text-secondary);
}

/*
 * 预置位表限高 248px（≈ 8 行）内部滚动：球机常有 8~255 个预置位，
 * 全展开会把方向盘挤出视口。表头由 el-table 固定。
 */
.preset-table {
  width: 100%;
}

.preset-table :deep(.el-table__cell) {
  padding: 4px 0;
}

/* 序号用等宽数字：1 和 128 右对齐后竖排扫读不会跳 */
.preset-table .mono {
  font-variant-numeric: tabular-nums;
  color: var(--text-secondary);
}

/* 空槽位淡底：一眼区分「能存」和「已存」 */
.preset-table :deep(.preset-row-empty) {
  background: var(--fill-color-lighter, rgba(128, 128, 128, 0.04));
}

/* 备注做成按钮样式（可点即改），但视觉上要像普通文字，别抢操作列的注意力 */
.preset-name-btn {
  justify-content: flex-start !important;
  width: 100%;
  padding: 0 !important;
  font-weight: normal !important;
  overflow: hidden;
}

.preset-name-btn :deep(span) {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.preset-name-empty {
  display: block;
  padding: 0 2px;
  font-size: var(--fs-xs);
  color: var(--text-secondary);
  opacity: 0.7;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 操作列三个链接按钮：默认间距偏大，窄边栏里会换行 */
.preset-table :deep(.el-button + .el-button) {
  margin-left: 6px;
}

/* 按钮文字绝不折行：折了就变两行，行高忽大忽小，扫读很难受 */
.preset-table :deep(.el-button > span) {
  white-space: nowrap;
}

.preset-hint {
  margin: 0;
  font-size: var(--fs-xs);
  color: var(--text-secondary);
  text-align: center;
}

/*
 * 提示行消失后的占位（见模板里的说明）。
 * 高度取一行小字的行高，保证「有提示」与「无提示」两种状态下面板等高。
 */
.preset-hint-placeholder {
  height: var(--fs-xs);
  line-height: var(--fs-xs);
}

/*
 * 运维功能 Tab：标签栏只占一行 34px（四个折叠标题要 152px），
 * 内容区位置固定、切换不改变面板高度。标签用双字短词，四个正好一行放下。
 */
.ptz-tabs {
  border-top: 1px solid var(--border-color);
  padding-top: 6px;
  /*
   * 撑满面板剩余高度：方向盘与变倍/光圈/聚焦是固定高度，
   * 后面的部分该吃掉全部剩余空间，而不是在底部留一条白。
   */
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.ptz-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
  flex: 0 0 auto;
}

/* 四个标签平分宽度，避免逐个按文字宽度计算时右侧空一截 */
.ptz-tabs :deep(.el-tabs__nav) {
  display: flex;
  width: 100%;
}

.ptz-tabs :deep(.el-tabs__item) {
  flex: 1;
  height: 34px;
  line-height: 34px;
  padding: 0 8px;
  text-align: center;
  font-size: var(--fs-sm);
}

/*
 * 内容区吃掉剩余高度并在内部滚动。
 *
 * 早期这里用 min-height: 252px + overflow: visible，是为了「切 Tab 时面板高度不跳变」。
 * 现在改成 flex:1，高度完全由容器决定，天然不跳变，而且参数多的页
 * （相机 43 项）能在内部滚动，不再出现「内容区下面还有一条白」。
 *
 * overflow 从 visible 改为 auto 不影响弹层：el-select / el-popconfirm / el-tooltip
 * 都是 teleport 到 body 的，不会被祖先的滚动容器裁切。
 */
.ptz-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}

/*
 * tab 内容也要显式撑高，否则百分比高度在 auto 高度的父元素上会退化成内容高度，
 * 相机面板里的 flex:1 就不起作用。
 */
.ptz-tabs :deep(.el-tab-pane) {
  height: 100%;
}

/* ==================== 云台扩展页 ==================== */

/*
 * 这套 class 与数值是<b>照抄「相机」页</b>（CameraConfigPanel）的：
 * 分组 chip、行高、标签宽度、虚线分隔、右对齐底栏全部一致。
 * 两个组件各自 scoped，同名 class 不会互相污染；之所以复制而不是抽公共样式，
 * 是因为相机页的注释里记录着「右栏 320px」的实测约束，抽走会让那份约束失去上下文。
 */
.cam-config {
  display: flex;
  flex-direction: column;
  gap: 10px;
  height: 100%;
  min-height: 0;
}

/* 对讲分组：垫在「设备」页按钮下方，用分隔线把它和上方的即时动作区分开 */
.talk-group {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed var(--el-border-color-light);
  height: auto;
}

.talk-note {
  margin: 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}

.cam-groups {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.cam-group-chip {
  position: relative;
  padding: 0 8px;
  height: 24px;
  line-height: 22px;
  font-size: 12px;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-light);
  border: 1px solid transparent;
  border-radius: 4px;
  cursor: pointer;
  transition: color 0.15s, background-color 0.15s, border-color 0.15s;
}

.cam-group-chip:hover {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.cam-group-chip.is-active {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-5);
  font-weight: 600;
}

/*
 * 有未保存改动的分组在右上角打一个点。
 * 九个分组并排，用户改完巡航组直接点「速度」时，巡航组的改动还留在表单里没下发；
 * 不标出来就会以为已经保存过了。用伪元素而不是额外 span，不占按钮宽度。
 */
.cam-group-chip.is-dirty::after {
  content: '';
  position: absolute;
  top: 2px;
  right: 2px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--el-color-warning);
}

.cam-pane {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding-right: 4px;
  box-sizing: border-box;
}

/* ---- 云台扩展页专用：分区标题 / 巡航点表格 ---- */

/* 分区标题：一页里的「基本信息 / 巡航点 / 生效时间」等小节 */
.ext-section {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 12px 0 2px;
  padding-left: 7px;
  font-size: 12px;
  font-weight: 600;
  color: var(--el-text-color-regular);
  border-left: 3px solid var(--el-color-primary-light-5);
  line-height: 1.4;
}

.ext-section:first-child {
  margin-top: 4px;
}

/* ---- 巡航组：工具栏 + 列表 + 展开的预置点表（对齐设备「巡航群組」页）---- */

.tour-bar {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 2px 0 6px;
}

/* 「已配置 1 / 32」：解释为什么列表只有一行 —— 设备恒有 32 条线，空线不显示 */
.tour-count {
  margin-left: auto;
  font-size: 11px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}

/*
 * 运行中提示条。
 *
 * <p>设备在某个动作运行时会把工具栏、滑块、停留时间全部置灰，并弹一句
 * 「一些設定無法在PTZ水平旋轉期間操作」。平台用这条常驻提示说明「谁在跑」，
 * 比一串灰按钮更容易理解为什么点不动。
 */
.ext-runbar {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 4px 0 6px;
  padding: 5px 8px;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.4;
  color: var(--el-color-warning-dark-2);
  background: var(--el-color-warning-light-9);
  border: 1px solid var(--el-color-warning-light-7);
}

.tour-table {
  width: 100%;
}

/*
 * 让内层 table 撑满表格宽度。
 *
 * <p>Element Plus 把列宽之和写成内联样式（`<table style="width: 330px">`），
 * 并且<b>只在挂载那一刻</b>按当时的 `clientWidth` 分配剩余宽度，之后不再重算。
 * 这几张表放在「云台扩展」这个非默认 Tab 里，挂载时该 Tab 还是 `display:none`，
 * `clientWidth` 是 0 → 一律按声明宽度定死 → 表格只占一部分、右侧留一大块空白。
 * （`doLayout()` 实测不生效，必须重新挂载才恢复，所以不能靠 JS 重排。）
 *
 * <p>把内层 table 强制 100%，交给 `table-layout: fixed` 在浏览器层面按比例分配 ——
 * 声明式、与挂载时机无关。
 *
 * <p>⚠️ 这一招的<b>前提是各列声明宽度之和小于面板宽度</b>：
 * fixed 布局下浏览器不会把 table 压得比列宽之和更窄，总和一旦超出就撑不动、
 * 右侧照样留白。所以每张表的列宽在模板里都标了合计值，改列宽时一起算。
 */
.tour-table :deep(.el-table__header),
.tour-table :deep(.el-table__body) {
  width: 100% !important;
}

/*
 * 必须显式写 td 的 height。
 *
 * <p>全局样式里有 {@code .el-table td.el-table__cell { height: var(--row-h) }}（44px），
 * 只给 {@code .el-table__row} 设 28px 压不住 —— 表格是 table-layout，
 * td 自己的 height 说了算，tr 的高度被 td 撑起来（28px 能一屏看到十来条线）。
 */
.tour-table :deep(td.el-table__cell) {
  padding: 2px 0;
  height: 28px;
}

.tour-table :deep(.el-table__cell) {
  padding: 2px 0;
}

/*
 * 单元格左右内边距必须压到 2px。
 *
 * <p>全局样式 {@code .el-table .cell { padding: 0 var(--sp-3) }} 是左右各 12px，
 * 一个 width=44 的「序号」列内容区只剩 44-24=20px，两个汉字（约 28px）放不下，
 * 表头被截成「序...」、「执行」→「执...」、「删除」→「删...」。
 * 这几张是紧凑表格，列宽都按内容算好了，不需要 12px 的呼吸位。
 */
.tour-table :deep(th.el-table__cell .cell),
.tour-table :deep(td.el-table__cell .cell) {
  padding: 0 2px;
}

.tour-table :deep(.el-table__row) {
  height: 28px;
}

.tour-table :deep(.el-input__wrapper) {
  padding: 1px 4px;
  min-height: 20px;
}

.tour-table :deep(.el-input__inner) {
  font-size: 12px;
  height: 20px;
}

.tour-table :deep(.mono) {
  font-variant-numeric: tabular-nums;
  color: var(--text-secondary);
}

/* 只读且设备不存储的值（如巡迹停留时间）用灰字，避免看起来像可编辑 */
.tour-table :deep(.mono.is-muted) {
  color: var(--el-text-color-placeholder);
  cursor: help;
}

/*
 * 线扫的速度下拉挤在 52px 的列里。
 * Element Plus 的 .el-select__wrapper 默认 min-height 24px + 上下 4px padding，
 * 在 28px 行高里会把整行撑到 44px（实测），必须压到 20px 才和其它列齐平。
 */
/*
 * 巡迹停留时间输入框：跟其它紧凑列一样压到 20px 高。
 * el-input-number 默认 min-height 30px，会把 28px 的行撑开。
 */
/*
 * 定时任务行内的工作动作下拉：压到 20px 高，与其它紧凑列齐平。
 */
/*
 * 定时任务「时段」编辑区：一行一段，每段自带星期下拉 + 起止时间 + 删除。
 * 面板只有 480px 宽，用 flex-wrap 让控件在窄屏自动换行，不挤成一坨。
 */
.auto-shifts {
  align-items: flex-start;
}

.auto-shift {
  display: flex;
  align-items: center;
  gap: 4px;
  width: 100%;
  margin-bottom: 4px;
}

.auto-shift-day {
  width: 72px;
  flex: 0 0 auto;
}

.auto-fn {
  width: 100%;
}

.auto-fn :deep(.el-select__wrapper) {
  min-height: 20px;
  height: 20px;
  padding: 0 4px;
}

.auto-fn :deep(.el-select__selected-item) {
  font-size: 12px;
  line-height: 18px;
}

.pattern-stay {
  width: 100%;
}

.pattern-stay :deep(.el-input__wrapper) {
  padding: 1px 2px;
  min-height: 20px;
}

.pattern-stay :deep(.el-input__inner) {
  font-size: 12px;
  height: 20px;
  text-align: center;
}

.scan-speed {
  width: 100%;
}

.scan-speed :deep(.el-select__wrapper) {
  padding-left: 4px;
  padding-right: 2px;
  min-height: 20px;
  height: 20px;
  padding-top: 0;
  padding-bottom: 0;
}

.scan-speed :deep(.el-select__selected-item) {
  font-size: 12px;
  line-height: 18px;
}

/* 下拉箭头缩到 10px，否则 36px 宽的选择器里「8」和箭头会重叠 */
.scan-speed :deep(.el-select__suffix) {
  font-size: 10px;
}

/* 展开区：设备用的是嵌一张小表，这里同样用「表头 + 行」，不再堆输入框 */
.tour-pts {
  padding: 4px 6px 6px 24px;
  background: var(--el-fill-color-lighter);
}

.tour-pt-head {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 0 0 3px;
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

.tour-pt-row {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 2px 0;
}

.tour-pt-row + .tour-pt-row {
  border-top: 1px dashed var(--el-border-color);
}

.tour-pt-col.is-idx {
  flex: 0 0 16px;
  text-align: center;
  font-size: 11px;
  color: var(--el-text-color-secondary);
}

/* 预置位是下拉：剩余宽度全给它，要放得下「#12 大门」这种编号+名称 */
.tour-pt-col.is-preset {
  flex: 1 1 auto;
  min-width: 0;
}

.tour-pt-col.is-stay {
  flex: 0 0 52px;
}

.tour-pt-col.is-speed {
  flex: 0 0 46px;
}

.tour-pt-col.is-del {
  flex: 0 0 22px;
  padding: 0;
}

.tour-pt-row :deep(.el-input-number),
.tour-pt-row :deep(.el-select) {
  width: 100%;
}

/*
 * Element Plus 的 .el-input__wrapper 默认左右各 15px 内边距 —— 52px 的列里
 * 只剩 22px 给数字，两位数就被裁掉，看起来像「没有回显」。必须写到
 * `.el-input-number .el-input__wrapper` 的深度：Element Plus 自带
 * `.el-input-number.is-without-controls .el-input__wrapper` 是同等优先级，
 * 只写 .el-input__wrapper 会被它压过去。
 */
.tour-pt-row :deep(.el-input-number .el-input__wrapper) {
  padding: 1px 2px;
}

.tour-pt-row :deep(.el-input__inner) {
  text-align: center;
  padding: 0;
  font-size: 12px;
}

.tour-pt-row :deep(.el-select__wrapper) {
  padding-left: 5px;
  padding-right: 2px;
  min-height: 22px;
}

.tour-pt-row :deep(.el-select__selected-item) {
  font-size: 12px;
}

.tour-pt-empty {
  padding: 6px 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.tour-pt-bar {
  display: flex;
  align-items: center;
  gap: 4px;
  padding-top: 5px;
}

.cam-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 0;
}

.cam-row + .cam-row {
  border-top: 1px dashed var(--el-border-color-lighter);
}

.cam-label {
  flex: 0 0 88px;
  font-size: 12px;
  color: var(--el-text-color-regular);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cam-control {
  flex: 1;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.cam-slider {
  flex: 1;
  min-width: 0;
}

.cam-value {
  flex: 0 0 30px;
  font-size: 12px;
  text-align: right;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}

/*
 * 水平旋转速度滑块（设备「水準旋轉」页的 −/滑块/+ 那一行）。
 * 滑块本身没有固定宽度，给它 flex:1 吃掉中间空余，两侧按钮才不会被挤到一起。
 */
.pan-speed-slider {
  flex: 1;
  min-width: 80px;
  margin: 0 4px;
}

/* 当前值：与设备一致放在滑块右侧，等宽数字避免 1 和 8 切换时抖动 */
.pan-speed-value {
  flex: 0 0 18px;
  font-size: 12px;
  text-align: center;
  color: var(--el-text-color-regular);
}

.cam-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

/* ---- 相机页没有对应形态的补充（多行控件），字号与间距沿用同一套 ---- */

.cam-row.is-top {
  align-items: flex-start;
}

.cam-row.is-top .cam-label {
  padding-top: 3px;
}

.cam-control.is-column {
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}

.ext-unit {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}

.ext-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}

/* 线扫边界 / 限位边界等状态：未设置用中性色，已设置用成功色，一眼能分辨 */
.ext-badge {
  flex: 0 0 auto;
  padding: 0 6px;
  border-radius: 3px;
  font-size: 12px;
  line-height: 20px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  font-variant-numeric: tabular-nums;
}

.ext-badge.is-on {
  color: var(--el-color-success);
  background: var(--el-color-success-light-9);
}

</style>
