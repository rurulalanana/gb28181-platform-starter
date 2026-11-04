<template>
  <!--
    系统设置：左侧分区导航 + 右侧内容区。左侧固定不随内容滚动，一次只呈现一个主题；
    解释性文字收进 HelpTip，正文只留「标签 + 值 + 操作」。
  -->
  <div class="settings">
    <aside class="settings-side">
      <div class="side-head">
        <div class="side-title">系统设置</div>
        <div class="side-sub mono">{{ server.platformId || '—' }}</div>
      </div>

      <nav class="side-nav">
        <button
          v-for="s in SECTIONS"
          :key="s.key"
          type="button"
          class="side-item"
          :class="{ 'is-active': active === s.key }"
          @click="active = s.key"
        >
          <el-icon class="side-icon"><component :is="s.icon" /></el-icon>
          <span class="side-label">{{ s.label }}</span>
          <span v-if="s.key === 'config' && dirtyCount > 0" class="side-badge">
            {{ dirtyCount }}
          </span>
        </button>
      </nav>

      <!-- 底部放接口版本而不是运行时长：后者已在「已运行」读数里，重复没有意义 -->
      <div class="side-foot mono">接口 v{{ server.interfaceVersion || '—' }}</div>
    </aside>

    <div class="settings-body">
      <section v-show="active === 'overview'" class="sec">
        <header class="sec-head">
          <div>
            <h2 class="sec-title">运行概览</h2>
            <p class="sec-sub">服务状态、接入规模与媒体出流端口</p>
          </div>
          <el-button text :icon="Refresh" :loading="loadingServer" @click="loadServer">
            刷新
          </el-button>
        </header>

        <!-- 读数条 -->
        <div class="kpi-grid">
          <div class="kpi">
            <div class="kpi-label">
              设备
              <HelpTip text="已注册到平台的 GB28181 设备。设备按心跳保活，超过阈值未收到心跳即判离线。" />
            </div>
            <div class="kpi-value">
              {{ server.deviceOnline ?? '—' }}<span class="kpi-unit">/ {{ server.deviceTotal ?? '—' }}</span>
            </div>
            <div class="kpi-foot">在线 / 总数</div>
          </div>
          <div class="kpi">
            <div class="kpi-label">
              通道
              <HelpTip text="设备通过目录查询上报的通道（摄像机）。通道在线状态跟随其所属设备。" />
            </div>
            <div class="kpi-value">
              {{ server.channelOnline ?? '—' }}<span class="kpi-unit">/ {{ server.channelTotal ?? '—' }}</span>
            </div>
            <div class="kpi-foot">在线 / 总数</div>
          </div>
          <div class="kpi">
            <div class="kpi-label">
              已运行
              <HelpTip text="本次进程启动至今的时长。重启后从零开始计时。" />
            </div>
            <div class="kpi-value kpi-value--text">{{ server.runningTime || '—' }}</div>
            <div class="kpi-foot">{{ server.startUpTime || '启动时间未知' }}</div>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">基础信息</span>
          </div>
          <div class="info-list">
            <div v-for="row in basicRows" :key="row.label" class="info-row">
              <span class="info-label">
                {{ row.label }}
                <HelpTip v-if="row.tip" :text="row.tip" />
              </span>
              <span class="info-value mono">
                {{ row.value || '—' }}
                <el-button
                  v-if="row.copy && row.value"
                  text
                  size="small"
                  class="copy-btn"
                  :icon="CopyDocument"
                  :aria-label="`复制${row.label}`"
                  title="复制"
                  @click="copy(row.value)"
                />
              </span>
            </div>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">媒体服务</span>
            <!--
              默认协议是 WebRTC（见 resolvePlayUrls 的 LIVE_DEFAULT_PROTOCOL）。
              曾错标为 WebSocket-FLV，排障时会照着去查 FLV 端口。
            -->
            <el-tag size="small" effect="plain" type="success">默认 WebRTC</el-tag>
          </div>
          <div class="info-list info-list--pairs">
            <div v-for="row in mediaRows" :key="row.label" class="info-row">
              <span class="info-label">
                {{ row.label }}
                <HelpTip :text="row.tip" />
              </span>
              <span class="info-value mono">{{ row.value || '—' }}</span>
            </div>
          </div>
        </div>
      </section>

      <section v-show="active === 'service'" class="sec">
        <header class="sec-head">
          <div>
            <h2 class="sec-title">服务运维</h2>
            <p class="sec-sub">进程级重启与接口信息</p>
          </div>
        </header>

        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">
              重启服务
              <HelpTip text="优雅停机后由守护进程自动拉起，约 10 秒恢复；登录态已持久化，无需重新登录。若服务是裸 java -jar 启动（无守护进程），重启后不会自动恢复。" />
            </span>
            <el-tag size="small" effect="plain" type="warning">约 10 秒不可用</el-tag>
          </div>
          <div class="action-row">
            <div class="action-desc">
              仅用于应用配置变更后的进程级重启。录像保留、保活间隔等运行期参数
              不需要重启，请到「运行时配置」调整。
            </div>
            <el-button
              type="danger"
              plain
              :icon="RefreshRight"
              :disabled="!canRestart"
              :loading="restarting"
              @click="restartServer"
            >
              {{ restarting ? '重启中…' : '重启服务' }}
            </el-button>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">接口信息</span>
          </div>
          <div class="info-list">
            <div v-for="row in apiRows" :key="row.label" class="info-row">
              <span class="info-label">
                {{ row.label }}
                <HelpTip v-if="row.tip" :text="row.tip" />
              </span>
              <span class="info-value mono">{{ row.value || '—' }}</span>
            </div>
          </div>
        </div>
      </section>

      <section v-show="active === 'config'" class="sec">
        <header class="sec-head">
          <div>
            <h2 class="sec-title">
              运行时配置
              <HelpTip text="参数保存在数据库中，保存后立即生效且重启后保留。未改过的项使用 application.yml 的值；「恢复默认」会删除库中的覆盖值、回落 yml。端口、路径、数据库连接等影响进程装配的配置不在此处，需改 yml 并重启。" />
            </h2>
            <p class="sec-sub">保存即生效，无需重启</p>
          </div>
          <div class="sec-actions">
            <el-tag v-if="dirtyCount > 0" size="small" effect="plain" type="warning">
              {{ dirtyCount }} 项已自定义
            </el-tag>
            <el-button text :icon="Refresh" :loading="cfgLoading" @click="loadConfig">
              刷新
            </el-button>
          </div>
        </header>

        <div v-for="g in cfgGroups" :key="g.key" class="panel">
          <div class="panel-head">
            <span class="panel-title">{{ g.label }}</span>
            <span class="count-pill">{{ cfgRowsOf(g.key).length }}</span>
          </div>

          <div class="cfg-list">
            <div
              v-for="row in cfgRowsOf(g.key)"
              :key="row.key"
              class="cfg-row"
              :class="{ 'is-overridden': row.overridden }"
            >
              <div class="cfg-name">
                <span class="cfg-label">
                  {{ cfgLabel(row.key) }}
                  <HelpTip :text="row.description" />
                </span>
                <span class="cfg-meta">
                  <template v-if="row.type === 'INT' || row.type === 'LONG'">
                    <span class="cfg-range" :class="{ 'is-bad': isInvalid(row) }">
                      范围 {{ fmtNum(row.min) }} ~ {{ fmtNum(row.max) }} {{ cfgUnit(row.key) }}
                    </span>
                    <span class="cfg-sep">·</span>
                  </template>
                  <span class="cfg-default mono">
                    默认 {{ defaultText(row) }}
                  </span>
                </span>
              </div>

              <div class="cfg-field">
                <!-- BOOLEAN → 开关；密码项 → 掩码；其余 → 数字/文本 -->
                <el-switch
                  v-if="row.type === 'BOOLEAN'"
                  :model-value="isOn(row)"
                  class="cfg-switch"
                  :loading="cfgSaving === row.key"
                  :aria-label="cfgLabel(row.key)"
                  active-text="开启"
                  inactive-text="关闭"
                  inline-prompt
                  @change="(v) => toggleBool(row, v)"
                />
                <el-input
                  v-else
                  v-model="cfgDraft[row.key]"
                  class="cfg-input"
                  :class="{ 'is-invalid': isInvalid(row) }"
                  :type="isSecret(row.key) ? 'password' : 'text'"
                  :show-password="isSecret(row.key)"
                  :placeholder="row.defaultValue || '留空表示不启用'"
                  @keyup.enter="saveConfig(row)"
                >
                  <template v-if="cfgUnit(row.key)" #append>{{ cfgUnit(row.key) }}</template>
                </el-input>
                <div v-if="isInvalid(row)" class="cfg-error">
                  {{ outOfRangeMsg(row) }}
                </div>
              </div>

              <div class="cfg-ops">
                <el-button
                  type="primary"
                  size="small"
                  :disabled="!canConfig || !isDirty(row) || isInvalid(row)"
                  :loading="cfgSaving === row.key"
                  @click="saveConfig(row)"
                >
                  保存
                </el-button>
                <el-button
                  size="small"
                  :disabled="!canConfig || !row.overridden"
                  @click="resetConfig(row)"
                >
                  恢复默认
                </el-button>
              </div>
            </div>
          </div>
        </div>

        <p v-if="!cfgGroups.length && !cfgLoading" class="empty-hint">暂无可调整的运行时配置</p>
      </section>

      <section v-show="active === 'acl'" class="sec">
        <header class="sec-head">
          <div>
            <h2 class="sec-title">
              接入控制
              <HelpTip text="按来源决定谁可以注册。与「设备统一接入密码」是两道独立的闸门：密码回答「你是谁」，名单回答「这个来源许不许进」。检查发生在密码校验之前，所以黑名单里的设备即使密码正确也进不来 —— 这正是密码泄露后仍能拦住已知恶意来源的意义。保存后立即生效，无需重启；已注册的设备不受影响，要等下次注册才生效。" />
            </h2>
            <p class="sec-sub">按设备编码或 IP 限制注册来源</p>
          </div>
          <div class="acl-head-actions">
            <el-button text :icon="Refresh" :loading="aclLoading" @click="loadAcl">刷新</el-button>
            <el-button type="primary" :icon="Plus" :disabled="!canAcl" @click="openAclDialog">添加规则</el-button>
          </div>
        </header>

        <!--
          判定顺序写在界面上而不是只放 tooltip：黑白名单语义容易凭直觉猜错
          （尤其「白名单为空」），猜错就会所有设备掉线或全部放行。
        -->
        <div class="acl-flow">
          <span class="acl-step">
            <span class="acl-step-no">1</span>
            命中黑名单 → 拒绝
          </span>
          <span class="acl-arrow">→</span>
          <span class="acl-step">
            <span class="acl-step-no">2</span>
            有白名单但未命中 → 拒绝
          </span>
          <span class="acl-arrow">→</span>
          <span class="acl-step is-pass">
            <span class="acl-step-no">3</span>
            其余放行
          </span>
        </div>

        <el-alert
          v-if="aclBlackCount === 0 && aclWhiteCount === 0"
          type="info"
          :closable="false"
          show-icon
          class="acl-alert"
          title="当前未设置任何规则，所有来源均可注册"
          description="只靠「设备统一接入密码」把关。密码一旦泄露或被批量写入弱口令，任何人都能注册设备。"
        />
        <el-alert
          v-else-if="aclWhiteCount === 0"
          type="warning"
          :closable="false"
          show-icon
          class="acl-alert"
          :title="`当前为黑名单模式：${aclBlackCount} 条规则，命中的来源被拒绝，其余全部放行`"
          description="白名单为空即「未启用白名单」，不会拦截任何设备。想只允许指定来源注册，请添加白名单条目。"
        />
        <el-alert
          v-else
          type="success"
          :closable="false"
          show-icon
          class="acl-alert"
          :title="`当前为白名单模式：仅 ${aclWhiteCount} 条规则内的来源可以注册`"
          :description="aclBlackCount > 0
            ? `同时有 ${aclBlackCount} 条黑名单规则，黑名单优先：即使命中了白名单，只要也命中黑名单仍会被拒绝。`
            : '未命中白名单的来源一律拒绝。级联上级平台如需注册，也要加进白名单。'"
        />

        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">规则列表</span>
            <el-radio-group v-model="aclFilter" size="small" @change="loadAcl">
              <el-radio-button value="">全部</el-radio-button>
              <el-radio-button value="BLACK">黑名单 {{ aclBlackCount }}</el-radio-button>
              <el-radio-button value="WHITE">白名单 {{ aclWhiteCount }}</el-radio-button>
            </el-radio-group>
          </div>

          <el-table
            v-loading="aclLoading"
            :data="aclRows"
            size="small"
            empty-text="暂无规则"
          >
            <el-table-column label="类型" width="96">
              <template #default="{ row }">
                <el-tag
                  size="small"
                  effect="plain"
                  :type="row.listType === 'WHITE' ? 'success' : 'danger'"
                >
                  {{ row.listType === 'WHITE' ? '白名单' : '黑名单' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="匹配方式" width="120">
              <template #default="{ row }">{{ aclKindLabel(row.kind) }}</template>
            </el-table-column>
            <el-table-column label="匹配值" min-width="220">
              <template #default="{ row }"><span class="mono">{{ row.value }}</span></template>
            </el-table-column>
            <el-table-column label="备注" min-width="200">
              <template #default="{ row }">
                <span class="acl-remark">{{ row.remark || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="添加时间" width="170">
              <template #default="{ row }">
                <span class="mono acl-time">{{ row.createTime || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80" align="right">
              <template #default="{ row }">
                <el-button link type="danger" size="small" :disabled="!canAcl" @click="removeAcl(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </section>

      <section v-show="active === 'tls'" class="sec">
        <header class="sec-head">
          <div>
            <h2 class="sec-title">
              HTTPS 与国密
              <HelpTip text="TLS 在 nginx 终结：本平台的标准部署是 nginx(公网) 反代 java(内网)，录像由 nginx 直读磁盘。因此这里做的是「校验证书 → 落盘 → 生成 nginx 配置片段 → nginx -t 语法校验 → reload」，校验不通过时不会 reload，站点保持原状。私钥只会单向写入，任何接口都不会读回。" />
            </h2>
            <p class="sec-sub">证书安装、续期与国密 35114 就绪度</p>
          </div>
          <el-button text :icon="Refresh" :loading="tlsLoading" @click="loadTls">刷新</el-button>
        </header>

        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">当前状态</span>
            <el-tag
              size="small"
              effect="plain"
              :type="tlsStatus.configured ? 'success' : 'info'"
            >
              {{ tlsStatus.configured ? `HTTPS 已启用 · ${tlsStatus.port}` : '仅 HTTP' }}
            </el-tag>
          </div>

          <div class="info-list">
            <div class="info-row">
              <span class="info-label">状态</span>
              <span class="info-value">
                {{ tlsStatus.message || '—' }}
              </span>
            </div>
            <div class="info-row">
              <span class="info-label">监听端口</span>
              <span class="info-value">
                <span class="mono">{{ tlsStatus.port }}</span>
                <el-button
                  link
                  type="primary"
                  size="small"
                  class="tls-probe-btn"
                  :loading="tlsProbing"
                  @click="probeTls"
                >
                  检测公网可达性
                </el-button>
              </span>
            </div>
            <!--
              单独展示检测结果：本机 curl 通、浏览器打不开时，结论就在这里，
              不写出来运维会先去怀疑证书和 nginx。
            -->
            <div v-if="tlsProbe" class="info-row">
              <span class="info-label">公网检测</span>
              <span class="info-value">
                <el-tag
                  size="small"
                  effect="plain"
                  :type="tlsProbe.reachable === null ? 'info' : (tlsProbe.reachable ? 'success' : 'danger')"
                >
                  {{ tlsProbe.reachable === null ? '无法判断' : (tlsProbe.reachable ? '可访问' : '不可访问') }}
                </el-tag>
                <el-tag
                  size="small"
                  effect="plain"
                  :type="tlsProbe.listening ? 'success' : 'danger'"
                  class="tls-tag"
                >
                  {{ tlsProbe.listening ? '本机已监听' : '本机未监听' }}
                </el-tag>
                <div class="tls-probe-hint">{{ tlsProbe.hint }}</div>
              </span>
            </div>
            <template v-if="tlsStatus.hasCertificate">
              <div class="info-row">
                <span class="info-label">适用域名</span>
                <span class="info-value mono">
                  {{ (tlsStatus.domainNames || []).join(', ') || '（证书未包含域名）' }}
                </span>
              </div>
              <div class="info-row">
                <span class="info-label">颁发者</span>
                <span class="info-value mono info-value--break">{{ tlsStatus.issuer }}</span>
              </div>
              <div class="info-row">
                <span class="info-label">有效期至</span>
                <span class="info-value">
                  <span class="mono">{{ (tlsStatus.notAfter || '').slice(0, 19).replace('T', ' ') }}</span>
                  <el-tag
                    v-if="tlsStatus.expired"
                    size="small"
                    effect="plain"
                    type="danger"
                    class="tls-tag"
                  >已过期</el-tag>
                  <el-tag
                    v-else-if="(tlsStatus.daysRemaining ?? 99) <= 30"
                    size="small"
                    effect="plain"
                    type="warning"
                    class="tls-tag"
                  >剩余 {{ tlsStatus.daysRemaining }} 天，请续期</el-tag>
                  <el-tag
                    v-else
                    size="small"
                    effect="plain"
                    type="success"
                    class="tls-tag"
                  >剩余 {{ tlsStatus.daysRemaining }} 天</el-tag>
                </span>
              </div>
              <div class="info-row">
                <span class="info-label">算法</span>
                <span class="info-value mono">
                  {{ tlsStatus.signatureAlgorithm }} / {{ tlsStatus.keyAlgorithm }}
                  <el-tag v-if="tlsStatus.selfSigned" size="small" effect="plain" type="warning" class="tls-tag">
                    自签名
                  </el-tag>
                </span>
              </div>
              <div class="info-row">
                <span class="info-label">存放目录</span>
                <span class="info-value mono info-value--break">{{ tlsStatus.certDir }}</span>
              </div>
            </template>
          </div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">安装或更新证书</span>
            <span class="panel-hint">支持证书链；私钥不能带口令</span>
          </div>
          <div class="tls-form">
            <div class="tls-field">
              <div class="tls-field-head">
                <span class="cfg-label">证书（PEM）</span>
                <el-button link type="primary" size="small" @click="pickFile('cert')">选择文件</el-button>
              </div>
              <el-input
                v-model="tlsForm.cert"
                type="textarea"
                :rows="5"
                spellcheck="false"
                placeholder="-----BEGIN CERTIFICATE-----&#10;…&#10;-----END CERTIFICATE-----&#10;（如有中间证书，按顺序在其后追加）"
              />
            </div>
            <div class="tls-field">
              <div class="tls-field-head">
                <span class="cfg-label">私钥（PEM）</span>
                <el-button link type="primary" size="small" @click="pickFile('key')">选择文件</el-button>
              </div>
              <el-input
                v-model="tlsForm.key"
                type="textarea"
                :rows="4"
                spellcheck="false"
                placeholder="-----BEGIN PRIVATE KEY-----&#10;…&#10;-----END PRIVATE KEY-----"
              />
            </div>

            <div class="tls-actions">
              <el-button
                type="primary"
                :loading="tlsInstalling"
                :disabled="!canTls || !tlsForm.cert.trim() || !tlsForm.key.trim()"
                @click="installCert"
              >
                校验并启用
              </el-button>
              <!-- 改完端口用它：只重写 nginx 配置，不必再贴一次私钥（私钥经手越多泄漏风险越大） -->
              <el-button
                v-if="tlsStatus.hasCertificate"
                :loading="tlsRegenerating"
                :disabled="!canTls"
                @click="regenerateTls"
              >
                按当前端口重新生成
              </el-button>
              <el-button
                v-if="tlsStatus.configured"
                :loading="tlsDisabling"
                :disabled="!canTls"
                @click="disableTls"
              >
                停用 HTTPS
              </el-button>
              <span class="form-hint">
                校验失败不会 reload，站点保持原状
              </span>
            </div>

            <!-- 国内云服务器常因备案用不了 443，需明确告诉运维端口在哪改 -->
            <el-alert
              v-if="tlsStatus.hasCertificate && !tlsStatus.configured"
              type="info"
              :closable="false"
              show-icon
              class="tls-alert"
              :title="`证书已就绪，点「按当前端口重新生成」即可在 ${tlsStatus.port} 端口启用`"
              description="HTTPS 端口可在「运行时配置 → HTTPS」里改，无需重启应用。若云服务器的安全组没有放通该端口，公网仍然访问不到 —— 需在云控制台放通。"
            />

            <el-alert
              type="info"
              :closable="false"
              show-icon
              class="tls-alert"
              title="上传前会做三项检查"
              description="① 证书可解析且未过期；② 私钥与证书配对（用私钥签一段数据、证书公钥验签，避免「key values mismatch」在 reload 时才暴露）；③ nginx 配置语法通过 nginx -t。任一项失败都不改变现有可用状态。"
            />
          </div>
        </div>

        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">GB/T 35114 国密就绪度</span>
            <el-tag
              size="small"
              effect="plain"
              :type="gm.ready ? 'success' : 'warning'"
            >
              {{ gm.ready ? '具备条件' : '尚缺条件' }}
            </el-tag>
          </div>

          <div class="info-list">
            <div class="info-row">
              <span class="info-label">JDK 国密算法</span>
              <span class="info-value mono">
                {{ (gm.jdkSmAlgorithms || []).length ? gm.jdkSmAlgorithms.join(', ') : '未提供（JDK 标准版不含 SM 系列）' }}
              </span>
            </div>
            <div class="info-row">
              <span class="info-label">BouncyCastle</span>
              <span class="info-value">
                <el-tag size="small" effect="plain" :type="gm.bouncyCastlePresent ? 'success' : 'danger'">
                  {{ gm.bouncyCastlePresent ? '已引入' : '未引入' }}
                </el-tag>
                <span class="tls-note">SM2/SM3/SM4 的 Java 实现依赖</span>
              </span>
            </div>
            <div class="info-row">
              <span class="info-label">OpenSSL</span>
              <span class="info-value mono">
                {{ gm.opensslVersion }}
                <el-tag size="small" effect="plain" :type="gm.opensslSupportsSm2 ? 'success' : 'danger'" class="tls-tag">
                  {{ gm.opensslSupportsSm2 ? '支持 SM2' : '不支持 SM2 曲线' }}
                </el-tag>
              </span>
            </div>
            <div class="info-row">
              <span class="info-label">nginx</span>
              <span class="info-value mono">
                {{ gm.nginxVersion }}
                <el-tag size="small" effect="plain" :type="gm.nginxSupportsGmTls ? 'success' : 'danger'" class="tls-tag">
                  {{ gm.nginxSupportsGmTls ? '支持国密套件' : '不支持国密套件' }}
                </el-tag>
              </span>
            </div>
            <div class="info-row">
              <span class="info-label">设备证书 CA</span>
              <span class="info-value">
                <el-tag size="small" effect="plain" :type="gm.caConfigured ? 'success' : 'danger'">
                  {{ gm.caConfigured ? '已配置' : '未配置' }}
                </el-tag>
              </span>
            </div>
            <div v-if="(gm.missing || []).length" class="info-row">
              <span class="info-label">尚缺</span>
              <span class="info-value">
                <ul class="tls-missing">
                  <li v-for="(m, i) in gm.missing" :key="i">{{ m }}</li>
                </ul>
              </span>
            </div>
          </div>

          <el-alert
            type="warning"
            :closable="false"
            show-icon
            class="tls-alert"
            :title="gm.note"
          />
        </div>
      </section>

      <section v-show="active === 'storage'" class="sec">
        <header class="sec-head">
          <div>
            <h2 class="sec-title">
              对象存储
              <HelpTip text="采用 S3 兼容协议（AWS SDK v2），一套配置覆盖阿里云 OSS、腾讯云 COS、MinIO、七牛 Kodo、AWS S3。需在 application.yml 配好 gb28181.oss.* 并重启；未启用时录像保存在本机磁盘。上传在数据库事务提交后异步触发，失败只记日志并把状态置为「上传失败」，本地文件保留。" />
            </h2>
            <p class="sec-sub">云端录像归档到 S3 兼容存储</p>
          </div>
          <el-tag size="small" effect="plain" type="info">S3 兼容</el-tag>
        </header>

        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">服务商对照</span>
          </div>
          <el-table :data="ossRows" size="small" class="oss-table">
            <el-table-column prop="name" label="服务商" width="140" />
            <el-table-column label="endpoint" min-width="280">
              <template #default="{ row }"><span class="mono">{{ row.endpoint }}</span></template>
            </el-table-column>
            <el-table-column label="关键参数" min-width="190">
              <template #default="{ row }">
                <el-tooltip
                  :content="ossParamDesc(row.noteKey)"
                  placement="top"
                  :disabled="!ossParamDesc(row.noteKey)"
                  popper-class="help-tip-popper"
                >
                  <span class="dotted">{{ row.note }}</span>
                </el-tooltip>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="panel">
          <div class="panel-head">
            <span class="panel-title">配置项</span>
          </div>
          <div class="info-list">
            <div v-for="p in ossParams" :key="p.key" class="info-row">
              <span class="info-label mono">{{ p.key }}</span>
              <span class="info-value">{{ p.desc }}</span>
            </div>
          </div>
        </div>
      </section>

      <section v-show="active === 'security'" class="sec">
        <header class="sec-head">
          <div>
            <h2 class="sec-title">账号安全</h2>
            <p class="sec-sub">修改当前登录账号的密码</p>
          </div>
        </header>

        <div class="panel panel--narrow">
          <div class="panel-head">
            <span class="panel-title">
              修改密码
              <HelpTip text="密码以 BCrypt(MD5(明文)) 存储。修改成功后当前登录态失效，需用新密码重新登录。" />
            </span>
          </div>
          <el-form :model="pwd" label-width="92px" class="pwd-form" @submit.prevent>
            <el-form-item label="当前密码">
              <el-input v-model="pwd.oldpassword" type="password" show-password autocomplete="off" />
            </el-form-item>
            <el-form-item label="新密码">
              <el-input
                v-model="pwd.newpassword"
                type="password"
                show-password
                autocomplete="new-password"
              />
            </el-form-item>
            <el-form-item label="确认新密码">
              <el-input
                v-model="pwd.confirm"
                type="password"
                show-password
                autocomplete="new-password"
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="saving" @click="changePassword">保存</el-button>
              <span class="form-hint">修改后需重新登录</span>
            </el-form-item>
          </el-form>
        </div>

        <!--
          API 密钥：给业务平台 / 脚本 / Postman 用。
          浏览器登录要拖滑块，机器做不了，所以给每个账号一把可撤销的密钥。
          明文只在生成时显示一次 —— 库里只存 SHA-256，服务端也拿不回来。
        -->
        <div class="panel panel--narrow">
          <div class="panel-head">
            <span class="panel-title">
              API 密钥
              <HelpTip text="供业务平台 / 脚本调用。登录时把密钥放进 X-Api-Key 请求头即可，不需要密码与验证码。生成后请立刻复制保存，页面不会再次显示明文。" />
            </span>
          </div>

          <div class="apikey-body">
            <template v-if="!apiKeyState.enabled">
              <p class="apikey-tip">尚未生成。生成后可用于接口对接，随时可撤销。</p>
            </template>
            <template v-else>
              <div class="apikey-row">
                <span class="apikey-label">当前密钥</span>
                <span class="mono">{{ apiKeyState.hint }}••••••••••••••••</span>
              </div>
              <div class="apikey-row">
                <span class="apikey-label">生成时间</span>
                <span>{{ apiKeyState.createdAt || '—' }}</span>
              </div>
            </template>

            <!-- 明文只在这一块出现，刷新/关闭后不再可见 -->
            <div v-if="newApiKey" class="apikey-new">
              <div class="apikey-new-head">请立即复制，关闭后无法再次查看</div>
              <div class="apikey-new-body">
                <input class="apikey-new-input mono" :value="newApiKey" readonly @focus="$event.target.select()" />
                <el-button size="small" @click="copyApiKey">复制</el-button>
              </div>
            </div>

            <div class="apikey-actions">
              <el-button
                :type="apiKeyState.enabled ? 'default' : 'primary'"
                :loading="apiKeyBusy"
                @click="generateApiKey"
              >
                {{ apiKeyState.enabled ? '重新生成' : '生成密钥' }}
              </el-button>
              <el-button v-if="apiKeyState.enabled" :loading="apiKeyBusy" @click="revokeApiKey">
                撤销
              </el-button>
              <span class="form-hint">重新生成或撤销后，旧密钥立即失效</span>
            </div>
          </div>
        </div>
      </section>
    </div>

    <el-dialog
      v-model="aclDialog"
      title="添加接入控制规则"
      width="520px"
      append-to-body
      @closed="resetAclForm"
    >
      <el-form label-position="top" class="acl-form">
        <el-form-item label="名单类型">
          <el-radio-group v-model="aclForm.listType">
            <el-radio-button value="BLACK">黑名单（拒绝命中）</el-radio-button>
            <el-radio-button value="WHITE">白名单（只放行命中）</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="匹配方式">
          <el-select v-model="aclForm.kind" style="width: 100%">
            <el-option label="设备编码（精确 20 位）" value="DEVICE" />
            <el-option label="设备编码前缀（批量）" value="PREFIX" />
            <el-option label="IP 地址（精确）" value="IP" />
            <el-option label="IP 网段（CIDR）" value="CIDR" />
          </el-select>
        </el-form-item>

        <el-form-item label="匹配值">
          <el-input
            v-model="aclForm.value"
            :placeholder="aclPlaceholder"
            spellcheck="false"
            @keyup.enter="submitAcl"
          />
          <div class="form-hint">{{ aclKindHint }}</div>
        </el-form-item>

        <el-form-item label="备注">
          <el-input
            v-model="aclForm.remark"
            placeholder="建议写明原因，例如：设备已报废 / 客户要求屏蔽"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>

        <el-alert
          v-if="aclForm.listType === 'WHITE' && aclWhiteCount === 0"
          type="warning"
          :closable="false"
          show-icon
          title="这将是第一条白名单规则"
          description="一旦存在白名单，未命中白名单的来源将全部被拒绝 —— 包括现在在线的设备。请确认已把需要接入的设备和级联上级平台都加进来。"
        />
      </el-form>

      <template #footer>
        <el-button @click="aclDialog = false">取消</el-button>
        <el-button type="primary" :loading="aclSaving" @click="submitAcl">保存并生效</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, markRaw, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { confirmAction } from '@/utils/confirm'
import {
  Cloudy,
  CopyDocument,
  Filter,
  Key,
  Lock,
  Monitor,
  Plus,
  Refresh,
  RefreshRight,
  Setting
} from '@element-plus/icons-vue'
import { aclApi, configApi, systemApi, tlsApi, userApi, webrtcApi } from '@/api'
import { useAuthStore } from '@/stores/auth'
import HelpTip from '@/components/HelpTip.vue'

const auth = useAuthStore()
const router = useRouter()

/**
 * 改配置 / 装证书 / 重启服务都需要 system:write；system:read 只够看概览。
 * 两个权限点刻意分开：排障时常需「让人看得见状态但动不了」。
 */
const canWrite = computed(() => auth.can('system:write'))
const canConfig = computed(() => auth.can('system:config'))
const canAcl = computed(() => auth.can('system:acl'))
const canTls = computed(() => auth.can('system:tls'))
const canRestart = computed(() => auth.can('system:restart'))

/* ==================== 分区导航 ==================== */

/** 图标用 markRaw 包：组件对象被转成响应式代理会带来无意义的依赖追踪开销（开发环境还告警）。 */
const SECTIONS = [
  { key: 'overview', label: '运行概览', icon: markRaw(Monitor) },
  { key: 'service', label: '服务运维', icon: markRaw(RefreshRight) },
  { key: 'config', label: '运行时配置', icon: markRaw(Setting) },
  { key: 'acl', label: '接入控制', icon: markRaw(Filter) },
  { key: 'tls', label: 'HTTPS 与国密', icon: markRaw(Key) },
  { key: 'storage', label: '对象存储', icon: markRaw(Cloudy) },
  { key: 'security', label: '账号安全', icon: markRaw(Lock) }
]

const active = ref('overview')

const saving = ref(false)
const restarting = ref(false)
const loadingServer = ref(false)
const pwd = reactive({ oldpassword: '', newpassword: '', confirm: '' })
const server = computed(() => auth.serverInfo || {})
const mediaIp = ref('')

const basicRows = computed(() => {
  const info = server.value
  return [
    {
      label: '服务名称',
      value: info.server,
      tip: '平台对外声明的服务标识，出现在接口响应与日志中。'
    },
    {
      label: '平台编码',
      value: info.platformId,
      copy: true,
      tip: 'GB/T 28181 平台 20 位编码。设备的 SIP 域、级联上级寻址本平台，都以它为准。'
    },
    {
      label: '接口版本',
      value: info.interfaceVersion,
      tip: 'REST 接口版本号，与本页字段的对应关系一致。'
    },
    {
      label: '启动时间',
      value: info.startUpTime,
      tip: '本次进程启动的时刻。'
    }
  ]
})

const mediaRows = computed(() => {
  const info = server.value
  return [
    {
      label: '信令地址',
      value: info.sip,
      tip: 'SIP 监听地址与端口。设备需能访问该地址才能注册。'
    },
    {
      label: '媒体地址',
      value: mediaIp.value,
      tip: 'SDP 中下发给设备的收流地址，必须是设备可达的地址（不能是 127.0.0.1）。多网卡/NAT 环境需在配置中显式指定。'
    },
    {
      label: 'FLV / HLS',
      value: info.mediaHttp,
      tip: 'HTTP-FLV / ws-flv / HLS 出流端口，即浏览器拉流端口。'
    },
    {
      label: 'WebRTC',
      value: info.mediaWebRtc,
      tip: 'WebRTC 出流端口。NAT 环境需指定对外通告 IP，否则 ICE 候选不可达，表现为协商成功但画面 0×0。'
    },
    {
      label: 'RTSP',
      value: info.mediaRtsp,
      tip: '供 VLC / ffplay 等第三方播放器拉流的端口。'
    },
    {
      label: 'RTMP',
      value: info.mediaRtmp,
      tip: '供第三方推流/拉流的 RTMP 端口。'
    }
  ]
})

const apiRows = computed(() => {
  const info = server.value
  return [
    { label: '服务名称', value: info.server },
    { label: '接口版本', value: info.interfaceVersion },
    {
      label: '平台编码',
      value: info.platformId,
      tip: '设备注册时的 SIP 域；级联上级也用该编码寻址本平台。'
    },
    { label: '启动时间', value: info.startUpTime }
  ]
})

const ossRows = [
  { name: '阿里云 OSS', endpoint: 'https://oss-cn-hangzhou.aliyuncs.com', note: 'region=cn-hangzhou', noteKey: 'region' },
  { name: '腾讯云 COS', endpoint: 'https://cos.ap-guangzhou.myqcloud.com', note: 'region=ap-guangzhou', noteKey: 'region' },
  { name: 'MinIO', endpoint: 'http://192.168.1.10:9000', note: 'path-style=true（必须）', noteKey: 'path-style' },
  { name: '七牛云 Kodo', endpoint: 'https://s3-cn-east-1.qiniucs.com', note: 'region=cn-east-1', noteKey: 'region' },
  { name: 'AWS S3', endpoint: '（留空自动推导）', note: 'region=us-east-1', noteKey: 'region' }
]

const ossParams = [
  { key: 'gb28181.oss.enabled', desc: '是否启用，默认 false（录像存本地磁盘）' },
  { key: 'gb28181.oss.endpoint', desc: '服务地址，带 http(s):// 前缀；AWS S3 可留空自动推导' },
  { key: 'gb28181.oss.access-key / secret-key', desc: '访问密钥' },
  { key: 'gb28181.oss.bucket', desc: '存储桶名称' },
  { key: 'gb28181.oss.prefix', desc: '对象键前缀，默认 record，最终键为 <prefix>/<通道编码>/<文件名>' },
  { key: 'gb28181.oss.path-style', desc: 'MinIO / Ceph 等自建存储必须设为 true' },
  { key: 'gb28181.oss.public-read', desc: 'false 生成签名 URL；true 使用永久外链（需桶已开公共读）' },
  { key: 'gb28181.oss.delete-local-after-upload', desc: '上传成功后是否删除本地文件，默认 false（保留便于回放兜底）' }
]

/**
 * 服务商对照表里「关键参数」的 hover 说明。
 *
 * @param {string} key 参数名
 * @returns {string} 说明；无对应说明时返回空串（tooltip 自动禁用）
 */
function ossParamDesc(key) {
  if (key === 'region') {
    return '地域标识，必须与存储桶所在地域一致，否则请求会被拒绝或跨域失败。'
  }
  if (key === 'path-style') {
    return '自建存储（MinIO / Ceph）必须开启：用 path 形式而非子域名形式寻址，否则访问路径会拼错，表现为「文件上传成功但打不开」。'
  }
  return ''
}

async function copy(text) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch (e) {
    ElMessage.warning('复制失败，请手动选择')
  }
}

async function loadServer() {
  loadingServer.value = true
  try {
    await auth.loadServerInfo()
    // 媒体地址取自 WebRTC 配置（后端按设备可达地址拼装）
    try {
      const cfg = await webrtcApi.config()
      mediaIp.value = cfg?.mediaIp || ''
    } catch (e) {
      // 拿不到就不显示，不影响其余信息
    }
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loadingServer.value = false
  }
}

const cfgLoading = ref(false)
const cfgSaving = ref('')
const cfgItems = ref([])
/** 编辑中的草稿（key → 值），与 cfgItems 分离，避免未保存就被刷新覆盖。 */
const cfgDraft = reactive({})

/** 分组顺序固定而非按后端返回顺序，否则后端加一个配置项分组顺序就会跳变。 */
const CFG_GROUPS = [
  { key: 'sip', label: '信令接入' },
  { key: 'device', label: '设备接入与采集' },
  { key: 'record', label: '录像与磁盘' },
  { key: 'stream', label: '流与点播' },
  { key: 'log', label: '日志清理' },
  { key: 'data', label: '数据保留' }
]

const cfgGroups = computed(() => {
  const present = new Set(cfgItems.value.map((i) => i.group))
  const known = CFG_GROUPS.filter((g) => present.has(g.key))
  const extra = [...present]
    .filter((k) => !CFG_GROUPS.some((g) => g.key === k))
    .map((k) => ({ key: k, label: k }))
  return [...known, ...extra]
})

/** 已自定义（覆盖了 application.yml）的项数。 */
const dirtyCount = computed(() => cfgItems.value.filter((i) => i.overridden).length)

function cfgRowsOf(group) {
  return cfgItems.value.filter((i) => i.group === group)
}

/** 后端只给 key 和长说明，标题由前端映射：说明会随实现变化，而「录像保留天数」应稳定。 */
const CFG_LABELS = {
  'sip.password': '设备统一接入密码',
  'device.offline-seconds': '设备离线判定阈值',
  'device.offline-scan-interval-ms': '离线巡检间隔',
  'device.auto-collect': '上线自动采集',
  'device.collect-interval-seconds': '采集节流间隔',
  'device.collect-device-info': '采集设备信息',
  'device.collect-device-status': '采集设备状态',
  'record.retain-days': '录像保留天数',
  'record.disk-max-used-percent': '磁盘使用率上限',
  'record.keep-min-days': '磁盘告急时最少保留',
  'record.max-second': '录像分片时长',
  'record.purge-interval-ms': '录像清理间隔',
  'record.keepalive-interval-ms': '云端录像保活间隔',
  'record.reconcile-interval-ms': '录像对账间隔',
  'record.url-sign-enabled': '录像地址签名校验',
  'record.url-sign-ttl-seconds': '录像地址有效期',
  'tls.port': 'HTTPS 监听端口',
  'tls.auto-reload': '自动 reload nginx',
  'log.oplog-retain-days': '操作日志保留天数',
  'log.status-retain-days': '状态日志保留天数',
  'alarm.retain-days': '报警记录保留天数',
  'position.retain-days': '移动位置保留天数',
  'device.record-retain-days': '设备录像索引保留天数',
  'stream.idle-reap-interval-ms': '空闲流回收间隔',
  'stream.none-reader-delay-ms': '无人观看关流等待'
}

function cfgLabel(key) {
  return CFG_LABELS[key] || key
}

/**
 * 输入框右侧的单位后缀。只按 key 后缀映射，其余留空 —— 乱标单位比不标更易误导。
 */
function cfgUnit(key) {
  if (key.endsWith('-ms')) return '毫秒'
  if (key.endsWith('-days')) return '天'
  // 同时匹配 -seconds（复数）与 -second（单数）：record.max-second 是后者
  if (key.endsWith('-seconds') || key.endsWith('-second')) return '秒'
  if (key.endsWith('-percent')) return '%'
  return ''
}

function isDirty(row) {
  return String(cfgDraft[row.key] ?? '').trim() !== String(row.value ?? '').trim()
}

/**
 * 布尔项当前是否为「开」。后端归一化成 "true"/"false"，但历史数据可能是
 * 1/0/on/off，统一在这里兜住。
 */
function isOn(row) {
  const v = String(row.value ?? '').trim().toLowerCase()
  return v === 'true' || v === '1' || v === 'on'
}

function isSecret(key) {
  return key === 'sip.password'
}

/**
 * 切换布尔项并立即保存：开关型配置拨动就是意图，没有「改完再点保存」的必要。
 */
async function toggleBool(row, on) {
  cfgDraft[row.key] = on ? 'true' : 'false'
  await saveConfig(row)
}

/**
 * 数值是否越界。前端校验只为体验（后端 ConfigKeys.Def#validate 会返回 400），
 * 判据取自后端下发的 min/max，不写死。非数字或非整数也算越界。
 */
function isInvalid(row) {
  // 只有数值类型才谈得上越界；STRING/BOOLEAN 不参与，否则密码会被 Number() 判成 NaN 而误标红
  if (row.type !== 'INT' && row.type !== 'LONG') return false
  const raw = String(cfgDraft[row.key] ?? '').trim()
  // 空值不算「越界」，由保存时的「不能为空」提示处理
  if (!raw) return false
  const n = Number(raw)
  if (!Number.isFinite(n)) return true
  if (!Number.isInteger(n)) return true
  if (row.min != null && n < row.min) return true
  if (row.max != null && n > row.max) return true
  return false
}

/**
 * 越界原因的提示文案。
 *
 * @param {object} row 配置项
 * @returns {string}
 */
function outOfRangeMsg(row) {
  const raw = String(cfgDraft[row.key] ?? '').trim()
  const n = Number(raw)
  const unit = cfgUnit(row.key)
  if (!Number.isFinite(n)) {
    return '请填数字'
  }
  if ((row.type === 'INT' || row.type === 'LONG') && !Number.isInteger(n)) {
    return '不支持小数'
  }
  if (row.min != null && n < row.min) {
    return `不能小于 ${fmtNum(row.min)} ${unit}`
  }
  if (row.max != null && n > row.max) {
    return `不能大于 ${fmtNum(row.max)} ${unit}`
  }
  return ''
}

/**
 * 大数字加千分位：毫秒类配置动辄 60000 / 86400000，裸数字很容易看错一位，
 * 而「少一个零」意味着磁盘被填满或录像被秒删。
 */
function fmtNum(v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return String(v ?? '')
  return n.toLocaleString('en-US')
}

/**
 * 默认值展示文案。数值必须走 fmtNum，否则同一行里范围是 10,000~600,000、
 * 默认值却是 60000，像两种口径。字符串空值写「空」比看不见的空白明确。
 */
function defaultText(row) {
  if (row.type === 'STRING') return row.defaultValue || '空'
  if (row.type === 'BOOLEAN') return row.defaultValue
  return fmtNum(row.defaultValue)
}

async function loadConfig() {
  cfgLoading.value = true
  try {
    const list = await configApi.list()
    cfgItems.value = Array.isArray(list) ? list : []
    for (const item of cfgItems.value) {
      cfgDraft[item.key] = item.value
    }
  } catch (e) {
    ElMessage.error(e.message || '加载运行时配置失败')
  } finally {
    cfgLoading.value = false
  }
}

/**
 * 保存单项配置。成功后用后端返回值回填：后端可能规范化值（如布尔统一成
 * true/false），否则会出现「界面显示 abc、实际生效 true」。
 */
async function saveConfig(row) {
  const value = String(cfgDraft[row.key] ?? '').trim()
  // STRING（如设备统一接入密码）允许留空：留空本身就是关闭鉴权；数值类型不允许空
  if (!value && row.type !== 'STRING') {
    ElMessage.warning('配置值不能为空')
    return
  }
  // 双保险：按钮已置灰，但回车也会触发保存
  if (isInvalid(row)) {
    ElMessage.warning(outOfRangeMsg(row))
    return
  }
  cfgSaving.value = row.key
  try {
    const saved = await configApi.save(row.key, value)
    if (saved) {
      const idx = cfgItems.value.findIndex((i) => i.key === row.key)
      if (idx >= 0) {
        cfgItems.value[idx] = saved
      }
      cfgDraft[row.key] = saved.value
    }
    ElMessage.success(`${cfgLabel(row.key)} 已生效`)
  } catch (e) {
    // 后端会带出具体原因（越界 / 非数字 / 未知 key）
    ElMessage.error(e.message || '保存失败')
  } finally {
    cfgSaving.value = ''
  }
}

/** 恢复默认：删除库里的覆盖值，回落 application.yml。 */
async function resetConfig(row) {
  if (!(await confirmAction(
    `将「${cfgLabel(row.key)}」恢复为默认值 ${row.defaultValue}？\n\n这会删除数据库中的自定义值，改为使用 application.yml 的配置。`,
    '恢复默认',
    { confirmButtonText: '恢复' }
  ))) return
  cfgSaving.value = row.key
  try {
    const saved = await configApi.reset(row.key)
    if (saved) {
      const idx = cfgItems.value.findIndex((i) => i.key === row.key)
      if (idx >= 0) {
        cfgItems.value[idx] = saved
      }
      cfgDraft[row.key] = saved.value
    }
    ElMessage.success(`${cfgLabel(row.key)} 已恢复默认`)
  } catch (e) {
    ElMessage.error(e.message || '恢复失败')
  } finally {
    cfgSaving.value = ''
  }
}

/* ==================== 重启 / 改密 ==================== */

/** 重启平台服务（需由守护进程拉起，否则不会自动恢复）。重启后轮询探测：
 * 登录态已持久化（sa_token_store），旧 token 依然有效，无需重新登录。 */
async function restartServer() {
  if (!(await confirmAction(
    '确定重启平台服务？\n\n服务将优雅停机并由守护进程自动拉起（约 10 秒），期间功能暂停。\n若服务没有守护进程（裸 java -jar 启动），重启后不会自动恢复。',
    '重启确认',
    { confirmButtonText: '重启' }
  ))) return
  restarting.value = true
  try {
    await systemApi.restart(true)
  } catch (e) {
    // 请求可能因服务退出而中断，忽略
  }
  ElMessage.info('服务重启中，正在等待恢复…')

  const startedAt = Date.now()
  const timer = setInterval(async () => {
    try {
      await systemApi.serverinfo()
      clearInterval(timer)
      restarting.value = false
      ElMessage.success('服务已恢复')
      loadServer()
    } catch (e) {
      if (Date.now() - startedAt > 90000) {
        clearInterval(timer)
        restarting.value = false
        ElMessage.warning('等待恢复超时，请检查服务是否已由守护进程拉起')
      }
    }
  }, 2500)
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

/* ---------------- API 密钥 ---------------- */

/** 当前密钥状态（enabled / hint / createdAt），不含明文。 */
const apiKeyState = reactive({ enabled: false, hint: '', createdAt: '' })
/** 刚生成的明文，仅本次操作后显示。 */
const newApiKey = ref('')
const apiKeyBusy = ref(false)

async function loadApiKey() {
  try {
    const d = await userApi.apiKey()
    apiKeyState.enabled = !!d?.enabled
    apiKeyState.hint = d?.hint || ''
    apiKeyState.createdAt = d?.createdAt || ''
  } catch (e) {
    // 密钥状态取不到不该影响其它设置项，静默即可
  }
}

async function generateApiKey() {
  const tip = apiKeyState.enabled
    ? '重新生成会让当前密钥立即失效，正在使用它的对接程序会立刻无法登录。继续？'
    : '生成后请立刻复制保存，页面不会再次显示明文。继续？'
  if (!(await confirmAction(tip, 'API 密钥', { confirmButtonText: '生成' }))) {
    return
  }
  apiKeyBusy.value = true
  try {
    const d = await userApi.generateApiKey()
    newApiKey.value = d?.key || ''
    apiKeyState.enabled = !!d?.enabled
    apiKeyState.hint = d?.hint || ''
    apiKeyState.createdAt = d?.createdAt || ''
    ElMessage.success('已生成，请立刻复制')
  } catch (e) {
    ElMessage.error(e.message || '生成失败')
  } finally {
    apiKeyBusy.value = false
  }
}

async function revokeApiKey() {
  if (!(await confirmAction('撤销后使用该密钥的对接程序将立即无法登录。继续？', '撤销 API 密钥',
    { confirmButtonText: '撤销' }))) {
    return
  }
  apiKeyBusy.value = true
  try {
    await userApi.revokeApiKey()
    newApiKey.value = ''
    apiKeyState.enabled = false
    apiKeyState.hint = ''
    apiKeyState.createdAt = ''
    ElMessage.success('已撤销')
  } catch (e) {
    ElMessage.error(e.message || '撤销失败')
  } finally {
    apiKeyBusy.value = false
  }
}

/** 复制密钥：优先 clipboard，非安全上下文下回落 execCommand。 */
async function copyApiKey() {
  const text = newApiKey.value
  if (!text) return
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      ElMessage.success('已复制')
      return
    }
  } catch (e) {
    // 落到下面的兜底
  }
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.cssText = 'position:fixed;top:0;left:0;width:1px;height:1px;opacity:0'
    document.body.appendChild(ta)
    ta.select()
    document.execCommand('copy')
    ta.remove()
    ElMessage.success('已复制')
  } catch (e) {
    ElMessage.error('复制失败，请手动选中复制')
  }
}

const aclLoading = ref(false)
const aclRows = ref([])
/** 筛选：'' 全部 / 'BLACK' / 'WHITE'。 */
const aclFilter = ref('')
/*
 * 两类名单的总条数单独维护，不能从 aclRows 里数：
 * 列表是按筛选条件取的，「白名单 0 条」这个提示需要在任何筛选下都准确。
 */
const aclBlackCount = ref(0)
const aclWhiteCount = ref(0)

const aclDialog = ref(false)
const aclSaving = ref(false)
const aclForm = reactive({ listType: 'BLACK', kind: 'DEVICE', value: '', remark: '' })

function aclKindLabel(kind) {
  switch (kind) {
    case 'PREFIX':
      return '编码前缀'
    case 'IP':
      return 'IP 地址'
    case 'CIDR':
      return 'IP 网段'
    default:
      return '设备编码'
  }
}

/** 匹配方式的输入提示，按当前方式变化（固定一句通用提示反而更容易填错）。 */
const aclPlaceholder = computed(() => {
  switch (aclForm.kind) {
    case 'PREFIX':
      return '如 3402000000132'
    case 'IP':
      return '如 112.74.161.73'
    case 'CIDR':
      return '如 192.168.1.0/24'
    default:
      return '20 位设备编码，如 34020000001320000001'
  }
})

const aclKindHint = computed(() => {
  switch (aclForm.kind) {
    case 'PREFIX':
      return '1~20 位数字，匹配以此开头的全部设备编码 —— 批量拉黑某厂商设备时用。'
    case 'IP':
      return '只匹配这一个 IPv4 地址。设备换网段后会失效。'
    case 'CIDR':
      return '匹配整个网段，如 /24 表示前 24 位相同。设备 IP 会变时优先用它。'
    default:
      return '20 位国标编码，精确匹配单台设备。级联场景填下级平台编码。'
  }
})

async function loadAcl() {
  aclLoading.value = true
  try {
    const data = await aclApi.list(aclFilter.value ? { list_type: aclFilter.value } : undefined)
    aclRows.value = data?.aclList || []
    aclBlackCount.value = Number(data?.blackCount || 0)
    aclWhiteCount.value = Number(data?.whiteCount || 0)
  } catch (e) {
    ElMessage.error(e.message || '加载接入控制规则失败')
  } finally {
    aclLoading.value = false
  }
}

function openAclDialog() {
  // 默认黑名单：它只影响明文列出的来源，不会像白名单那样一开就把
  // 未列出的设备全挡住 —— 误操作代价小得多。
  aclForm.listType = 'BLACK'
  aclForm.kind = 'DEVICE'
  aclForm.value = ''
  aclForm.remark = ''
  aclDialog.value = true
}

function resetAclForm() {
  aclForm.value = ''
  aclForm.remark = ''
}

async function submitAcl() {
  const value = String(aclForm.value || '').trim()
  if (!value) {
    ElMessage.warning('请填写匹配值')
    return
  }
  aclSaving.value = true
  try {
    await aclApi.add({
      list_type: aclForm.listType,
      kind: aclForm.kind,
      value,
      remark: aclForm.remark
    })
    ElMessage.success('规则已生效（已注册的设备不受影响，下次注册时校验）')
    aclDialog.value = false
    await loadAcl()
  } catch (e) {
    // 后端会带出具体原因（编码位数不对 / 网段格式错 / 重复）
    ElMessage.error(e.message || '保存失败')
  } finally {
    aclSaving.value = false
  }
}

async function removeAcl(row) {
  if (!(await confirmAction(
    `删除规则「${aclKindLabel(row.kind)} ${row.value}」？\n\n删除后该来源将立即恢复为可注册（或不再受此规则保护）。`,
    '删除接入控制规则',
    { confirmButtonText: '删除' }
  ))) return
  try {
    await aclApi.remove(row.id)
    ElMessage.success('规则已删除')
    await loadAcl()
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}

const tlsLoading = ref(false)
const tlsInstalling = ref(false)
const tlsRegenerating = ref(false)
const tlsDisabling = ref(false)
const tlsProbing = ref(false)
const tlsProbe = ref(null)
const tlsStatus = ref({})
const tlsForm = reactive({ cert: '', key: '' })
const gm = ref({})

async function loadTls() {
  tlsLoading.value = true
  try {
    const st = await tlsApi.status()
    tlsStatus.value = st || {}
  } catch (e) {
    ElMessage.error(e.message || '加载 HTTPS 状态失败')
  } finally {
    tlsLoading.value = false
  }
}

async function loadGm() {
  try {
    gm.value = (await tlsApi.gmReadiness()) || {}
  } catch (e) {
    // 就绪度自检失败不影响主流程，不打扰用户
    gm.value = {}
  }
}

/**
 * 用文件选择而不是强制粘贴：PEM 证书链往往几千字符，手工复制易漏中间证书
 * 或尾部 END 行，而这类错误在「能解析但链不完整」时最难排查。
 */
function pickFile(which) {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = '.pem,.crt,.cer,.key,.txt'
  input.onchange = () => {
    const file = input.files && input.files[0]
    if (!file) return
    const reader = new FileReader()
    reader.onload = () => {
      tlsForm[which] = String(reader.result || '')
    }
    reader.onerror = () => ElMessage.error('读取文件失败')
    reader.readAsText(file)
  }
  input.click()
}

async function installCert() {
  tlsInstalling.value = true
  try {
    const st = await tlsApi.install(tlsForm.cert, tlsForm.key)
    tlsStatus.value = st || {}
    // 成功后立刻清空：私钥不该在页面内存里多留一秒
    tlsForm.cert = ''
    tlsForm.key = ''
    ElMessage.success('证书已安装，nginx 已重载')
    if (st?.message && /过期|续期/.test(st.message)) {
      ElMessage.warning(st.message)
    }
  } catch (e) {
    // 后端会带出具体原因（不配对 / 已过期 / nginx -t 失败）
    ElMessage.error(e.message || '安装失败')
  } finally {
    tlsInstalling.value = false
  }
}

async function regenerateTls() {
  tlsRegenerating.value = true
  try {
    tlsStatus.value = (await tlsApi.regenerate()) || {}
    ElMessage.success(`HTTPS 配置已按当前设置重新生成（端口 ${tlsStatus.value.port}）`)
  } catch (e) {
    ElMessage.error(e.message || '重新生成失败')
  } finally {
    tlsRegenerating.value = false
  }
}

/**
 * 检测端口能否从公网访问：回答「证书/nginx 都配对了，为什么浏览器打不开」。
 * 本机 curl 永远通，必须走公网地址才能命中云安全组。
 */
async function probeTls() {
  tlsProbing.value = true
  try {
    tlsProbe.value = (await tlsApi.probe(tlsStatus.value.port)) || null
  } catch (e) {
    ElMessage.error(e.message || '检测失败')
  } finally {
    tlsProbing.value = false
  }
}

async function disableTls() {
  if (!(await confirmAction(
    '停用后 HTTPS 端口将不再提供服务，仍在用 https:// 访问的用户会立刻断连。\n\n证书文件会保留，重新启用时无需再次上传。',
    '停用 HTTPS',
    { confirmButtonText: '停用' }
  ))) return
  tlsDisabling.value = true
  try {
    tlsStatus.value = (await tlsApi.disable()) || {}
    ElMessage.success('HTTPS 已停用')
  } catch (e) {
    ElMessage.error(e.message || '停用失败')
  } finally {
    tlsDisabling.value = false
  }
}

onMounted(() => {
  loadServer()
  loadConfig()
  loadAcl()
  loadTls()
  loadGm()
  loadApiKey()
})
</script>

<style scoped>
/*
 * 左导航 + 右内容。左栏固定宽度且 sticky：设置项再多导航也始终可见，
 * 不用滚回顶部才能切分区。
 */
.settings {
  display: grid;
  grid-template-columns: 196px minmax(0, 1fr);
  gap: var(--sp-5);
  align-items: start;
}

.settings-side {
  position: sticky;
  top: 0;
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  padding: var(--sp-3) 0;
}

.side-head {
  padding: 0 var(--sp-3) var(--sp-3);
  border-bottom: 1px solid var(--line-soft);
}

.side-title {
  font-size: var(--fs-lg);
  font-weight: 600;
  letter-spacing: -0.2px;
  color: var(--text-primary);
}

.side-sub {
  margin-top: 3px;
  font-size: var(--fs-xs);
  letter-spacing: -0.2px;
  color: var(--ink-500);
}

.side-nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

/* 分区按钮：默认只有文字色；选中时左侧一道竖线 + 浅底 */
.side-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  height: 38px;
  padding: 0 var(--sp-3);
  border: none;
  border-radius: var(--r-md);
  background: transparent;
  /* ink-600 而非 text-secondary(ink-500)：左侧导航是主要入口，不能发灰 */
  color: var(--ink-600);
  font-family: inherit;
  font-size: var(--fs-sm);
  text-align: left;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.side-item:hover {
  background: var(--bg-sunken);
  color: var(--text-primary);
}

.side-item.is-active {
  background: var(--bg-sunken);
  color: var(--brand-500);
  font-weight: 600;
}

.side-item.is-active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 9px;
  bottom: 9px;
  width: 2px;
  border-radius: 1px;
  background: var(--brand-500);
}

.side-icon {
  font-size: var(--fs-md);
  color: inherit;
}

.side-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 已自定义项数的角标 */
.side-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 17px;
  height: 17px;
  padding: 0 5px;
  border-radius: var(--r-sm);
  background: var(--warning-500);
  color: #fff;
  font-family: var(--mono);
  font-size: var(--fs-xs);
  font-weight: 600;
}

.side-foot {
  padding: var(--sp-2) var(--sp-3) 0;
  border-top: 1px solid var(--line-soft);
  font-size: var(--fs-xs);
  color: var(--ink-500);
}

/*
 * 字号沿用全局 --fs-xs，不放大：读数已经 27px，标签再大会抢主次；
 * 让它「明显」靠字重 500 + ink-600。
 */
.kpi-label {
  font-weight: 500;
  color: var(--ink-600);
}

.kpi-foot {
  color: var(--ink-500);
}

.settings-body {
  min-width: 0;
}

.sec {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4);
}

.sec-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--sp-4);
  padding-bottom: var(--sp-3);
  border-bottom: 1px solid var(--line-soft);
}

.sec-title {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  /* 与其他页面标题同一档（--fs-lg），不自成一套 19/22px，否则切页时标题会跳一下 */
  font-size: var(--fs-lg);
  font-weight: 600;
  letter-spacing: -0.2px;
  color: var(--text-primary);
}

.sec-sub {
  margin: 4px 0 0;
  font-size: var(--fs-xs);
  color: var(--ink-500);
}

.sec-actions {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: var(--sp-2);
}

/* 不用 el-card：设置项不需要卡片阴影堆叠，一条发丝线分隔更整齐也更省垂直空间 */
.panel {
  overflow: hidden;
  border: 1px solid var(--line);
  border-radius: var(--r-lg);
  background: var(--bg-surface);
}

.panel--narrow {
  max-width: 460px;
}

.panel-head {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  /* 与全局 .el-card__header 同高：两类容器在页面上是同一层级 */
  height: 42px;
  padding: 0 var(--sp-4);
  border-bottom: 1px solid var(--line-soft);
  background: var(--bg-sunken);
}

.panel-title {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 6px;
  min-width: 0;
  /* 与全局 .card-title 一致：--fs-sm + 600 */
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--text-primary);
}

/*
 * 文本型读数（如「0 天 0 小时 24 分」）。全局 .kpi-value 是给纯数字的 27px 等宽字体，
 * 中文时段放进去会被撑很宽；降到 --fs-lg 让同一行三个读数重量一致。
 */
.kpi-value--text {
  font-family: var(--sans);
  font-size: var(--fs-lg);
  font-weight: 600;
  letter-spacing: -0.2px;
}

.info-list {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
}

/*
 * 成对键值（如「信令地址 0.0.0.0:45060」）：值紧跟在标签后、左对齐，不右贴边。
 * 多列网格里值右贴边会拉出一条长空白，一瞬看过去标签和值对应不上（三列时尤明显）。
 */
.info-list--pairs {
  grid-template-columns: repeat(auto-fit, minmax(268px, 1fr));
}

.info-list--pairs .info-row {
  justify-content: flex-start;
  gap: 10px;
  border-bottom: 1px solid var(--line-soft);
}

.info-list--pairs .info-value {
  flex: 1;
  justify-content: flex-start;
  text-align: left;
}

.info-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  min-height: 48px;
  padding: var(--sp-2) var(--sp-4);
  border-bottom: 1px solid var(--line-soft);
}

.info-row:last-child {
  border-bottom: none;
}

/* 标签用 ink-600 + 500：和全局表单标签（.el-form-item__label）同档，
   比 --text-secondary(ink-500) 深一档，一排字段不再糊成一片灰字 */
.info-label {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: 4px;
  font-size: var(--fs-sm);
  font-weight: 500;
  color: var(--ink-600);
}

/* 值：13px 但字重 600 + 主色，与标签拉开层次（同号不同重） */
.info-value {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--text-primary);
  text-align: right;
  overflow-wrap: anywhere;
}

/* 复制按钮只在 hover 该行时出现，避免满屏小图标 */
.copy-btn {
  opacity: 0;
  transition: opacity 0.15s;
}

.info-row:hover .copy-btn,
.copy-btn:focus-visible {
  opacity: 1;
}

.action-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-4);
  flex-wrap: wrap;
  padding: var(--sp-4);
}

.action-desc {
  flex: 1;
  min-width: 220px;
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--text-tertiary);
}

.cfg-list {
  display: flex;
  flex-direction: column;
}

/*
 * 三栏网格（名称 / 输入框 / 操作）。用 grid 而不是 flex：flex 会被最长项撑开，
 * 每行输入框的起始位置就不再对齐。行高 72px：配置项是主操作对象，挤在一起反而更慢。
 */
.cfg-row {
  display: grid;
  grid-template-columns: minmax(200px, 1fr) 240px auto;
  align-items: start;
  gap: var(--sp-2) var(--sp-5);
  padding: var(--sp-3) var(--sp-4);
  border-bottom: 1px solid var(--line-soft);
  transition: background 0.15s;
}

.cfg-row:last-child {
  border-bottom: none;
}

.cfg-row:hover {
  background: var(--bg-sunken);
}

/* 已覆盖 application.yml 的项：左侧一道提示色竖线 */
.cfg-row.is-overridden {
  box-shadow: inset 3px 0 0 var(--warning-500);
}

.cfg-name {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

/* --fs-sm + 600：字号与表格正文一致，靠字重和颜色在一屏十几个条目里立住 */
.cfg-label {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: var(--fs-sm);
  font-weight: 600;
  line-height: 1.4;
  color: var(--text-primary);
}

/* 范围与默认值：--fs-xs，但用 ink-600（而非 tertiary 的 ink-400）——
   它们是「要不要改」的判断依据，不该淡到看不见 */
.cfg-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-xs);
  line-height: 1.5;
  color: var(--ink-600);
}

.cfg-range {
  font-variant-numeric: tabular-nums;
}

/* 当前值越界时，范围提示同步变红（和输入框形成呼应） */
.cfg-range.is-bad {
  color: var(--danger-500);
  font-weight: 600;
}

.cfg-sep {
  color: var(--ink-300);
}

.cfg-default {
  color: var(--ink-500);
}

.cfg-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.cfg-input {
  width: 100%;
}

/* 开关在网格里默认会被拉满宽度，收回到自然宽度并左对齐 */
.cfg-switch {
  height: 32px;
  align-self: flex-start;
}

/* 输入值用等宽 + 600：数字是最需要「一眼读准」的内容。
   字号不写死，继承全局 --el-font-size-base(14px)，与其他页输入框一致 */
.cfg-input :deep(.el-input__inner) {
  font-family: var(--mono);
  font-weight: 600;
  color: var(--text-primary);
}

/* 单位后缀：加深、加粗，避免被当成输入内容的一部分 */
.cfg-input :deep(.el-input-group__append) {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--ink-600);
}

/* 越界：红框 + 红字 */
.cfg-input.is-invalid :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--danger-500) inset;
}

.cfg-error {
  font-size: var(--fs-xs);
  font-weight: 500;
  line-height: 1.4;
  color: var(--danger-500);
}

.cfg-ops {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  padding-top: 2px;
}

.empty-hint {
  padding: var(--sp-6);
  text-align: center;
  font-size: var(--fs-sm);
  color: var(--text-secondary);
}

/* 有 hover 说明的文本：虚线下划线，暗示可悬停 */
.dotted {
  border-bottom: 1px dashed var(--ink-400);
  color: var(--ink-700);
  cursor: help;
}

.oss-table {
  width: 100%;
}

/*
 * OSS 配置项沿用 info-row 但键是技术串（gb28181.oss.enabled），
 * 用小一号等宽；说明文字保持正常正文大小 —— 这些是唯一的解释来源。
 */
.info-label.mono {
  font-family: var(--mono);
  font-weight: 500;
}

.pwd-form {
  padding: var(--sp-4) var(--sp-4) var(--sp-2);
}

.form-hint {
  margin-left: var(--sp-3);
  font-size: var(--fs-xs);
  color: var(--ink-500);
}

/* ---------- API 密钥 ---------- */
.apikey-body {
  padding: var(--sp-4);
}

.apikey-tip {
  margin: 0 0 var(--sp-3);
  font-size: var(--fs-sm);
  color: var(--ink-500);
}

.apikey-row {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  margin-bottom: var(--sp-2);
  font-size: var(--fs-sm);
}

.apikey-label {
  flex: none;
  width: 72px;
  color: var(--ink-500);
}

/*
 * 明文区块用醒目底色：它只出现这一次，用户很容易在点完「生成」后
 * 直接关页面，然后再也拿不回来。
 */
.apikey-new {
  margin: var(--sp-3) 0;
  padding: var(--sp-3);
  background: var(--bg-sunken);
  border: 1px solid var(--brand-300, var(--line-soft));
  border-radius: var(--r-md, 6px);
}

.apikey-new-head {
  margin-bottom: var(--sp-2);
  font-size: var(--fs-xs);
  font-weight: 600;
  color: var(--brand-600);
}

.apikey-new-body {
  display: flex;
  gap: var(--sp-2);
}

.apikey-new-input {
  flex: 1;
  min-width: 0;
  height: 30px;
  padding: 0 9px;
  font-size: var(--fs-sm);
  color: var(--text-primary);
  background: var(--white, #fff);
  border: 1px solid var(--line);
  border-radius: var(--r-sm, 4px);
  outline: none;
}

.apikey-actions {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  margin-top: var(--sp-3);
}

.acl-head-actions {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
}

/*
 * 把「先黑后白」和「白名单为空 = 不限制」写在界面上而不是藏进 tooltip：
 * 这两条最容易凭直觉猜错，猜错就是所有设备掉线或全部放行。
 */
.acl-flow {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: var(--sp-2);
  padding: var(--sp-3) var(--sp-4);
  margin-bottom: var(--sp-3);
  background: var(--bg-sunken);
  border: 1px solid var(--line-soft);
  border-radius: var(--r-md);
}

.acl-step {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
  font-size: var(--fs-sm);
  color: var(--text-primary);
}

.acl-step.is-pass {
  color: var(--online-600, var(--online-500));
  font-weight: 600;
}

.acl-step-no {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--ink-200);
  color: var(--ink-700);
  font-size: var(--fs-xs);
  font-weight: 600;
  font-family: var(--mono);
}

.acl-step.is-pass .acl-step-no {
  background: var(--online-500);
  color: #fff;
}

.acl-arrow {
  color: var(--ink-400);
  font-size: var(--fs-sm);
}

/* 三个状态提示条之间留出间距，避免连成一片读不出层次 */
.acl-alert {
  margin-bottom: var(--sp-3);
}

/* 备注可能很长，限两行并省略；完整内容在编辑时可见，列表里不需要撑高行 */
.acl-remark {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-size: var(--fs-sm);
  color: var(--text-secondary);
}

.acl-time {
  font-size: var(--fs-xs);
  color: var(--ink-500);
}

.acl-form :deep(.el-form-item) {
  margin-bottom: var(--sp-4);
}

.acl-form :deep(.el-form-item__label) {
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--text-primary);
  padding-bottom: var(--sp-1);
}

/* 对话框里的匹配值输入用等宽：编码和网段逐位比对，等宽更好认 */
.acl-form :deep(.el-input__inner) {
  font-family: var(--mono);
}

.panel-hint {
  font-size: var(--fs-xs);
  color: var(--ink-500);
}

.tls-form {
  padding: var(--sp-4);
}

.tls-field {
  margin-bottom: var(--sp-4);
}

.tls-field-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--sp-2);
}

/* PEM 用等宽 + 小字号：一行行 64 字符的 Base64，非等宽看不出「行数对不对」（漏 END 是常见错误） */
.tls-field :deep(.el-textarea__inner) {
  font-family: var(--mono);
  font-size: var(--fs-xs);
  line-height: 1.6;
}

.tls-actions {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
}

.tls-alert {
  margin: var(--sp-4) var(--sp-4) 0;
}

.tls-tag {
  margin-left: var(--sp-2);
}

.tls-note {
  margin-left: var(--sp-2);
  font-size: var(--fs-xs);
  color: var(--ink-500);
}

.tls-missing {
  margin: 0;
  padding-left: var(--sp-4);
  font-size: var(--fs-sm);
  color: var(--text-secondary);
}

.tls-missing li {
  margin-bottom: 2px;
}

/* 证书颁发者的 DN 可能很长（含 O/OU/C），允许折行而不是撑出横向滚动 */
.info-value--break {
  word-break: break-all;
}

.tls-probe-btn {
  margin-left: var(--sp-2);
}

/* 检测结论可能是一整句排查建议，单独成行并限制宽度，避免把布局撑开 */
.tls-probe-hint {
  margin-top: var(--sp-1);
  max-width: 620px;
  font-size: var(--fs-xs);
  line-height: 1.7;
  color: var(--text-secondary);
  white-space: normal;
}

@media (max-width: 900px) {
  .settings {
    grid-template-columns: minmax(0, 1fr);
  }

  /* 导航改成横向胶囊条，避免占掉半屏高度 */
  .settings-side {
    position: static;
    padding: var(--sp-2) 0;
  }

  .side-head,
  .side-foot {
    display: none;
  }

  .side-nav {
    flex-direction: row;
    gap: var(--sp-1);
    overflow-x: auto;
    padding-bottom: 2px;
  }

  .side-item {
    flex: 0 0 auto;
    width: auto;
    height: 32px;
    border-radius: var(--r-md);
  }

  /* 横向排列时不画左侧竖线，改用底色区分 */
  .side-item.is-active::before {
    display: none;
  }

  /*
   * 配置行在 ≤900px 时也变单列。
   *
   * <p>断点与上面侧栏一致，而不是更窄的 760px：实测 794px 时
   * 三栏（200 + 240 + 按钮约 150 + 间距）已超出可用宽度，
   * 「恢复默认」按钮被挤出可视区 —— 看不见的按钮等于没有。
   */
  .cfg-row {
    grid-template-columns: minmax(0, 1fr);
    gap: var(--sp-2);
    padding: var(--sp-3) var(--sp-4);
  }

  .cfg-ops {
    justify-content: flex-end;
  }
}
</style>
