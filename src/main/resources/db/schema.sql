-- ============================================================
--  GB28181 平台数据库初始化脚本（PostgreSQL）
--  对齐青瓜视频 GBS 的功能域：设备 / 通道 / 流 / 录像 / 报警 / 级联 / 用户
--
--  定位：**全新初始化**。假设库是空的（或在全新库上执行）。
--  需要重建时先清空再启动即可，例如：
--      DROP SCHEMA public CASCADE; CREATE SCHEMA public;
--      -- 或： CREATE DATABASE gb28181;  （另起一个空库）
--
--  幂等性：全部语句都带 IF NOT EXISTS / ON CONFLICT DO NOTHING，
--  重复执行不会报错、不会丢数据，因此应用每次启动都会跑一遍：
--    第 1 段  建表（含主键、唯一键、索引）
--    第 2 段  注释（表与字段的中文说明，便于用客户端工具看库）
--    第 3 段  初始化数据（初始管理员账号）
--
--  若由 DBA 管理表结构，设 gb28181.starter.init-schema=false 关闭自动执行。
-- ============================================================


-- ============================================================
--  第 1 段：建表
-- ============================================================

-- -------------------- 设备 --------------------

CREATE TABLE IF NOT EXISTS gb_device (
    id                 BIGSERIAL PRIMARY KEY,
    device_id          VARCHAR(20)  NOT NULL,
    name               VARCHAR(128),
    custom_name        VARCHAR(128),
    manufacturer       VARCHAR(64),
    model              VARCHAR(64),
    firmware           VARCHAR(64),
    channel_count      INTEGER      DEFAULT 0,
    civil_code         VARCHAR(16),
    address            VARCHAR(255),
    parent_id          VARCHAR(20),
    longitude          NUMERIC(12,6) DEFAULT 0,
    latitude           NUMERIC(12,6) DEFAULT 0,
    ptz_type           SMALLINT     DEFAULT 0,
    status             VARCHAR(16)  DEFAULT 'OFF',
    online             BOOLEAN      DEFAULT FALSE,
    remote_ip          VARCHAR(64),
    remote_port        INTEGER,
    transport          VARCHAR(8)   DEFAULT 'UDP',
    media_transport    VARCHAR(8)   DEFAULT 'UDP',
    media_transport_mode VARCHAR(8) DEFAULT 'passive',
    stream_index       VARCHAR(32)  DEFAULT 'auto',
    charset            VARCHAR(16)  DEFAULT 'GB2312',
    password           VARCHAR(64),
    catalog_interval   INTEGER      DEFAULT 3600,
    subscribe_interval INTEGER      DEFAULT 0,
    catalog_subscribe  BOOLEAN      DEFAULT FALSE,
    alarm_subscribe    BOOLEAN      DEFAULT FALSE,
    position_subscribe BOOLEAN      DEFAULT FALSE,
    ptz_subscribe      BOOLEAN      DEFAULT FALSE,
    record_center      BOOLEAN      DEFAULT FALSE,
    register_time      TIMESTAMP,
    keepalive_time     TIMESTAMP,
    update_time        TIMESTAMP,
    create_time        TIMESTAMP    DEFAULT NOW(),
    source             VARCHAR(16)  DEFAULT 'device',
    extra              TEXT,
    UNIQUE (device_id)
);
CREATE INDEX IF NOT EXISTS idx_gb_device_status ON gb_device (status);
CREATE INDEX IF NOT EXISTS idx_gb_device_online ON gb_device (online);

-- -------------------- 通道 --------------------

CREATE TABLE IF NOT EXISTS gb_channel (
    id                BIGSERIAL PRIMARY KEY,
    channel_id        VARCHAR(20)  NOT NULL,
    device_id         VARCHAR(20)  NOT NULL,
    name              VARCHAR(128),
    custom_name       VARCHAR(128),
    custom_id         VARCHAR(20),
    channel_no        INTEGER      DEFAULT 0,
    manufacturer      VARCHAR(64),
    model             VARCHAR(64),
    owner             VARCHAR(64),
    civil_code        VARCHAR(16),
    block             VARCHAR(64),
    address           VARCHAR(255),
    parental          SMALLINT     DEFAULT 0,
    parent_id         VARCHAR(20),
    safety_way        SMALLINT     DEFAULT 0,
    register_way      SMALLINT     DEFAULT 1,
    secrecy           SMALLINT     DEFAULT 0,
    ip_address        VARCHAR(64),
    port              INTEGER,
    password          VARCHAR(64),
    ptz_type          SMALLINT     DEFAULT 0,
    position_type     SMALLINT     DEFAULT 0,
    room_type         SMALLINT     DEFAULT 0,
    use_type          SMALLINT     DEFAULT 1,
    supply_light      SMALLINT     DEFAULT 0,
    direction_type    SMALLINT     DEFAULT 0,
    resolution        VARCHAR(64),
    stream_number_list VARCHAR(64),
    download_speed    VARCHAR(32),
    firmware          VARCHAR(64),
    serial_number     VARCHAR(64),
    longitude         NUMERIC(12,6) DEFAULT 0,
    latitude          NUMERIC(12,6) DEFAULT 0,
    altitude          NUMERIC(12,2) DEFAULT 0,
    speed             NUMERIC(12,2) DEFAULT 0,
    direction         NUMERIC(12,2) DEFAULT 0,
    battery_level     INTEGER      DEFAULT 0,
    signal_level      INTEGER      DEFAULT 0,
    business_group_id VARCHAR(20),
    status            VARCHAR(16)  DEFAULT 'OFF',
    stream_id         VARCHAR(96),
    on_demand         BOOLEAN      DEFAULT TRUE,
    cloud_record      BOOLEAN      DEFAULT FALSE,
    audio             BOOLEAN      DEFAULT FALSE,
    shared            BOOLEAN      DEFAULT FALSE,
    update_time       TIMESTAMP,
    create_time       TIMESTAMP    DEFAULT NOW(),
    -- 通道编码只在单设备内唯一（GB/T 28181 只保证设备编码全局唯一），
    -- 故唯一键必须是 (设备编码, 通道编码) 组合
    UNIQUE (device_id, channel_id)
);
CREATE INDEX IF NOT EXISTS idx_gb_channel_device ON gb_channel (device_id);
CREATE INDEX IF NOT EXISTS idx_gb_channel_channel ON gb_channel (channel_id);
CREATE INDEX IF NOT EXISTS idx_gb_channel_status ON gb_channel (status);
CREATE INDEX IF NOT EXISTS idx_gb_channel_parent ON gb_channel (parent_id);

-- 回填通道序号：GB/T 28181—2016 6.1 规定 20 位国标编码末 3 位为通道序号
-- （如 ...0001 → 1、...0102 → 102），与 LiveGBS 等平台的显示一致。
-- 目录应答里没有独立的「通道号」字段，早先这一列恒为 0。
-- 每次启动回填一次：老通道不会等到设备重新注册才更新
-- （级联下级平台的目录推送是增量的，可能很久不推）。
UPDATE gb_channel
SET channel_no = COALESCE(NULLIF(regexp_replace(right(channel_id, 3), '\D', '', 'g'), '')::int, 0)
WHERE channel_no = 0
  AND channel_id ~ '[0-9]{3}$';

-- -------------------- 直播流 --------------------

CREATE TABLE IF NOT EXISTS gb_stream (
    id           BIGSERIAL PRIMARY KEY,
    stream_key   VARCHAR(96)  NOT NULL,
    channel_id   VARCHAR(20)  NOT NULL,
    device_id    VARCHAR(20),
    stream_name  VARCHAR(96),
    app          VARCHAR(32),
    ssrc         VARCHAR(16),
    rtp_port     INTEGER,
    stream_type  VARCHAR(16)  DEFAULT 'live',
    state        VARCHAR(16)  DEFAULT 'NEGOTIATING',
    call_id      VARCHAR(128),
    http_flv     VARCHAR(255),
    ws_flv       VARCHAR(255),
    hls          VARCHAR(255),
    webrtc       VARCHAR(255),
    rtsp         VARCHAR(255),
    rtmp         VARCHAR(255),
    transport    VARCHAR(8)   DEFAULT 'UDP',
    audio        BOOLEAN      DEFAULT FALSE,
    on_demand    BOOLEAN      DEFAULT TRUE,
    cloud_record BOOLEAN      DEFAULT FALSE,
    viewers      INTEGER      DEFAULT 0,
    start_time   TIMESTAMP    DEFAULT NOW(),
    update_time  TIMESTAMP,
    UNIQUE (stream_key)
);
CREATE INDEX IF NOT EXISTS idx_gb_stream_channel ON gb_stream (channel_id);

-- -------------------- 录像（平台端 / 云端） --------------------

CREATE TABLE IF NOT EXISTS gb_record (
    id           BIGSERIAL PRIMARY KEY,
    record_id    VARCHAR(64)  NOT NULL,
    channel_id   VARCHAR(20)  NOT NULL,
    device_id    VARCHAR(20),
    name         VARCHAR(255),
    file_path    VARCHAR(512),
    file_name    VARCHAR(255),
    file_size    BIGINT       DEFAULT 0,
    start_time   TIMESTAMP,
    end_time     TIMESTAMP,
    duration     INTEGER      DEFAULT 0,
    important    BOOLEAN      DEFAULT FALSE,
    cloud_record BOOLEAN      DEFAULT FALSE,
    -- 对象存储（OSS/S3 兼容）相关：storage_type=LOCAL 时仅用 file_path；
    -- storage_type=OSS 时文件已上传，object_key 是桶内键、file_url 是外链/签名地址
    storage_type VARCHAR(16)  DEFAULT 'LOCAL',
    object_key   VARCHAR(512),
    file_url     VARCHAR(1024),
    upload_state VARCHAR(16)  DEFAULT 'NONE',
    upload_time  TIMESTAMP,
    create_time  TIMESTAMP    DEFAULT NOW(),
    UNIQUE (record_id)
);
CREATE INDEX IF NOT EXISTS idx_gb_record_channel ON gb_record (channel_id, start_time DESC);
-- 同一录像文件只能有一条记录。
-- 归档（recordId 取自 ZLM 索引）与对账（recordId 随机生成）可能并发跑到
-- 同一文件：实测两条插入间隔仅 206 微秒，各自的存在性检查都通过了，
-- 于是出现指向同一 file_path 的重复行。用部分唯一索引从库层面兜住。
CREATE UNIQUE INDEX IF NOT EXISTS uk_gb_record_file_path
    ON gb_record (file_path) WHERE file_path IS NOT NULL;

-- -------------------- 录像计划（云端录像的时段/星期规则） --------------------

-- 计划定义：名称 + 每周七天的录像时段 + 启用开关。
-- plan 存 JSON 字符串，结构与 LiveGBS 兼容：
--   [[{"s1":"00:00","e1":"24:00"}], [], [{"s3":"09:00","e3":"09:30"}], [], [], [], []]
-- 外层 7 个数组依次是周一~周日；每项是该天的时段列表（一天可多段）。
CREATE TABLE IF NOT EXISTS gb_record_plan (
    id          BIGSERIAL PRIMARY KEY,
    plan_id     VARCHAR(64)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    plan        TEXT         DEFAULT '[[],[],[],[],[],[],[]]',
    enable      BOOLEAN      DEFAULT TRUE,
    create_time TIMESTAMP    DEFAULT NOW(),
    update_time TIMESTAMP    DEFAULT NOW(),
    UNIQUE (plan_id)
);

-- 计划与通道的关联：一个计划可挂多个通道，一个通道也可挂多个计划。
CREATE TABLE IF NOT EXISTS gb_record_plan_channel (
    id         BIGSERIAL PRIMARY KEY,
    plan_id    VARCHAR(64)  NOT NULL,
    device_id  VARCHAR(20),
    channel_id VARCHAR(20)  NOT NULL,
    create_time TIMESTAMP   DEFAULT NOW(),
    UNIQUE (plan_id, channel_id)
);
CREATE INDEX IF NOT EXISTS idx_gb_record_plan_channel_plan
    ON gb_record_plan_channel (plan_id);
CREATE INDEX IF NOT EXISTS idx_gb_record_plan_channel_channel
    ON gb_record_plan_channel (channel_id);

-- -------------------- 设备录像目录（RecordInfo） --------------------

CREATE TABLE IF NOT EXISTS gb_device_record (
    id            BIGSERIAL PRIMARY KEY,
    channel_id    VARCHAR(20)  NOT NULL,
    device_id     VARCHAR(20),
    name          VARCHAR(255),
    file_path     VARCHAR(512),
    address       VARCHAR(255),
    start_time    TIMESTAMP,
    end_time      TIMESTAMP,
    duration      INTEGER      DEFAULT 0,
    size          BIGINT       DEFAULT 0,
    type          VARCHAR(16),
    secrecy       SMALLINT     DEFAULT 0,
    stream_number INTEGER      DEFAULT 0,
    update_time   TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_gb_device_record_ch ON gb_device_record (channel_id, start_time DESC);

-- -------------------- 报警 --------------------

CREATE TABLE IF NOT EXISTS gb_alarm (
    id                BIGSERIAL PRIMARY KEY,
    alarm_id          VARCHAR(64)  NOT NULL,
    device_id         VARCHAR(20),
    channel_id        VARCHAR(20),
    method            INTEGER,
    method_name       VARCHAR(64),
    priority          INTEGER      DEFAULT 0,
    priority_name     VARCHAR(64),
    alarm_type        INTEGER,
    alarm_type_name   VARCHAR(64),
    event_type        INTEGER,
    alarm_description TEXT,
    start_time        TIMESTAMP,
    end_time          TIMESTAMP,
    state             VARCHAR(16)  DEFAULT 'ACTIVE',
    longitude         NUMERIC(12,6) DEFAULT 0,
    latitude          NUMERIC(12,6) DEFAULT 0,
    video_path        VARCHAR(512),
    snap_path         VARCHAR(512),
    create_time       TIMESTAMP    DEFAULT NOW(),
    UNIQUE (alarm_id)
);
CREATE INDEX IF NOT EXISTS idx_gb_alarm_device ON gb_alarm (device_id, start_time DESC);
CREATE INDEX IF NOT EXISTS idx_gb_alarm_channel ON gb_alarm (channel_id, start_time DESC);

-- -------------------- 级联配置 --------------------

CREATE TABLE IF NOT EXISTS gb_cascade (
    id                  BIGSERIAL PRIMARY KEY,
    cascade_id          VARCHAR(64)  NOT NULL,
    name                VARCHAR(128),
    parent_id           VARCHAR(20)  NOT NULL,
    parent_name         VARCHAR(128),
    parent_ip           VARCHAR(64),
    parent_port         INTEGER      DEFAULT 5060,
    parent_domain       VARCHAR(16),
    parent_password     VARCHAR(64),
    parent_protocol     VARCHAR(8)   DEFAULT 'UDP',
    local_id            VARCHAR(20),
    local_ip            VARCHAR(64),
    local_port          INTEGER      DEFAULT 5060,
    username            VARCHAR(64),
    password            VARCHAR(64),
    keepalive_interval  INTEGER      DEFAULT 60,
    keepalive_max_count INTEGER      DEFAULT 3,
    register_interval   INTEGER      DEFAULT 60,
    register_timeout    INTEGER      DEFAULT 3600,
    catalog_group_size  INTEGER      DEFAULT 1,
    control_priority    INTEGER      DEFAULT 0,
    load_limit          INTEGER      DEFAULT 0,
    charset             VARCHAR(16)  DEFAULT 'GB2312',
    allow_control       BOOLEAN      DEFAULT TRUE,
    share_all_channel   BOOLEAN      DEFAULT FALSE,
    stream_keepalive    BOOLEAN      DEFAULT FALSE,
    enabled             BOOLEAN      DEFAULT TRUE,
    online              BOOLEAN      DEFAULT FALSE,
    expire              INTEGER      DEFAULT 3600,
    remark              VARCHAR(255),
    create_time         TIMESTAMP    DEFAULT NOW(),
    update_time         TIMESTAMP,
    UNIQUE (cascade_id)
);
CREATE INDEX IF NOT EXISTS idx_gb_cascade_parent ON gb_cascade (parent_id);

CREATE TABLE IF NOT EXISTS gb_cascade_channel (
    id          BIGSERIAL PRIMARY KEY,
    cascade_id  VARCHAR(64)  NOT NULL,
    parent_id   VARCHAR(20)  NOT NULL,
    device_id   VARCHAR(20),
    channel_id  VARCHAR(20)  NOT NULL,
    shared      BOOLEAN      DEFAULT FALSE,
    pushed      BOOLEAN      DEFAULT FALSE,
    push_time   TIMESTAMP,
    create_time TIMESTAMP    DEFAULT NOW(),
    UNIQUE (cascade_id, device_id, channel_id)
);
CREATE INDEX IF NOT EXISTS idx_gb_cascade_channel_p ON gb_cascade_channel (parent_id);

-- 老库升级：补 device_id 列。
-- 唯一约束从 (cascade_id, channel_id) 改成 (cascade_id, device_id, channel_id)：
-- 通道编码只在单设备内唯一，多设备共存时同一个编码会出现在十几台设备上，
-- 老约束让「共享 A 设备的通道」与「共享 B 设备的同号通道」互相覆盖。
ALTER TABLE gb_cascade_channel ADD COLUMN IF NOT EXISTS device_id VARCHAR(20);

-- 老库升级：替换唯一约束（带存在性判断，重复执行安全）。
-- 若不替换，第二台设备的同号通道根本插不进 gb_cascade_channel，
-- 表现为「勾了这一路，另一台设备的同号通道却变成未共享」。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'gb_cascade_channel_cascade_id_channel_id_key'
          AND conrelid = 'gb_cascade_channel'::regclass
    ) THEN
        ALTER TABLE gb_cascade_channel
            DROP CONSTRAINT gb_cascade_channel_cascade_id_channel_id_key;
    END IF;

    -- 老数据 device_id 为空：按 gb_channel 反查补上。
    -- 补不上的（通道已删）保留 NULL —— 唯一约束对 NULL 不做冲突判定，
    -- 不会因此插入失败，但这类脏记录也不会再匹配到任何通道。
    UPDATE gb_cascade_channel sc
       SET device_id = c.device_id
      FROM gb_channel c
     WHERE sc.device_id IS NULL
       AND c.channel_id = sc.channel_id;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'gb_cascade_channel_cascade_device_channel_key'
          AND conrelid = 'gb_cascade_channel'::regclass
    ) THEN
        ALTER TABLE gb_cascade_channel
            ADD CONSTRAINT gb_cascade_channel_cascade_device_channel_key
            UNIQUE (cascade_id, device_id, channel_id);
    END IF;
END $$;

-- -------------------- 设备分组 --------------------

CREATE TABLE IF NOT EXISTS gb_device_group (
    id          BIGSERIAL PRIMARY KEY,
    group_code  VARCHAR(64)  NOT NULL,
    group_name  VARCHAR(128) NOT NULL,
    parent_code VARCHAR(64),
    sort_no     INTEGER      DEFAULT 0,
    create_time TIMESTAMP    DEFAULT NOW(),
    UNIQUE (group_code)
);

-- -------------------- 运行时配置（可在界面上改，无需重启） --------------------
--
-- 语义：<b>数据库优先，application.yml 兜底</b>。
-- 表里没有某个 key 时用 yml 里配置的默认值；用户一旦在界面保存，
-- 就写入本表，之后启动也从这里读 —— 覆盖 yml。
--
-- 只存「改了不该重启」的运维参数（保留天数、间隔、阈值、开关）。
-- 端口、路径、数据库连接等影响进程装配的配置仍归 application.yml，
-- 改它们必须重启，放进本表只会造成「界面显示改了但实际没生效」的误解。
CREATE TABLE IF NOT EXISTS gb_config (
    id          BIGSERIAL PRIMARY KEY,
    config_key  VARCHAR(64)  NOT NULL,
    config_value VARCHAR(512),
    -- 分组（前端按此分区展示：record / log / task / stream / device / media）
    config_group VARCHAR(32)  DEFAULT 'other',
    -- 数据类型：INT / LONG / BOOLEAN / STRING，决定前端控件与后端校验
    value_type  VARCHAR(16)  DEFAULT 'STRING',
    -- 界面上显示的说明（同时也是「这个值影响什么」的文档）
    description VARCHAR(512),
    update_time TIMESTAMP    DEFAULT NOW(),
    UNIQUE (config_key)
);

-- -------------------- 状态变更日志 --------------------

CREATE TABLE IF NOT EXISTS gb_status_log (
    id          BIGSERIAL PRIMARY KEY,
    device_id   VARCHAR(20),
    channel_id  VARCHAR(20),
    old_status  VARCHAR(16),
    new_status  VARCHAR(16),
    description VARCHAR(128),
    reason      VARCHAR(128),
    create_time TIMESTAMP DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_gb_status_log_dev ON gb_status_log (device_id, create_time DESC);

-- -------------------- 移动位置记录（轨迹） --------------------

CREATE TABLE IF NOT EXISTS gb_position_log (
    id          BIGSERIAL PRIMARY KEY,
    device_id   VARCHAR(20),
    channel_id  VARCHAR(20),
    method      VARCHAR(32),
    longitude   NUMERIC(12,6) DEFAULT 0,
    latitude    NUMERIC(12,6) DEFAULT 0,
    altitude    NUMERIC(12,2) DEFAULT 0,
    speed       NUMERIC(12,2) DEFAULT 0,
    direction   NUMERIC(12,2) DEFAULT 0,
    create_time TIMESTAMP     DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_gb_position_log_ch ON gb_position_log (channel_id, create_time DESC);

-- -------------------- 预置位名称自定义 --------------------

CREATE TABLE IF NOT EXISTS gb_preset (
    id          BIGSERIAL PRIMARY KEY,
    channel_id  VARCHAR(20) NOT NULL,
    preset_id   INTEGER     NOT NULL,
    name        VARCHAR(128),
    update_time TIMESTAMP,
    UNIQUE (channel_id, preset_id)
);

-- -------------------- 用户 --------------------

CREATE TABLE IF NOT EXISTS gb_user (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(64)  NOT NULL,
    password        VARCHAR(128) NOT NULL,
    nickname        VARCHAR(64),
    role            VARCHAR(32)  DEFAULT 'operator',
    phone_number    VARCHAR(32),
    email           VARCHAR(128),
    description     VARCHAR(255),
    enable          BOOLEAN      DEFAULT TRUE,
    has_all_channel BOOLEAN      DEFAULT TRUE,
    creator         VARCHAR(64)  DEFAULT 'system',
    lock            BOOLEAN      DEFAULT FALSE,
    last_login_at   TIMESTAMP,
    create_time     TIMESTAMP    DEFAULT NOW(),
    update_time     TIMESTAMP,
    UNIQUE (username)
);

-- -------------------- sa_token_store --------------------
-- Sa-Token 会话存储（登录 token 持久化）：由 SaTokenJdbcDao 读写，
-- 服务重启后登录态不丢失。值为 JDK 序列化的对象（String / SaSession 等）。
CREATE TABLE IF NOT EXISTS sa_token_store (
    k          VARCHAR(512) PRIMARY KEY,
    obj        BYTEA,
    expire_at  TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_sa_token_store_expire ON sa_token_store (expire_at);

-- -------------------- gb_operation_log --------------------
-- 操作日志（管理接口调用记录）：记录调用方式/资源路径/远端地址/结果/用时/操作人，
-- 由 OperationLogInterceptor 写入，定期清理（默认保留 7 天）。
CREATE TABLE IF NOT EXISTS gb_operation_log (
    id          BIGSERIAL PRIMARY KEY,
    method      VARCHAR(32),
    resource    VARCHAR(512),
    remote_addr VARCHAR(64),
    result      VARCHAR(128),
    duration_ms INTEGER,
    operator    VARCHAR(64),
    create_time TIMESTAMP DEFAULT now()
);

-- 老库升级：补充「操作人」列（CREATE TABLE IF NOT EXISTS 不会修改已存在的表）
ALTER TABLE gb_operation_log ADD COLUMN IF NOT EXISTS operator VARCHAR(64);
-- 请求参数（脱敏 + 截断）：审计要回答「谁把什么改成了什么」，只记 URL 不够
ALTER TABLE gb_operation_log ADD COLUMN IF NOT EXISTS params VARCHAR(1024);

-- 老库升级：补充流传输的 TCP 被动/主动模式列
-- CREATE TABLE IF NOT EXISTS 不会给已存在的表加列，缺这列时 MyBatis 查询会直接报
-- "column media_transport_mode does not exist"，整个设备列表都查不出来。
ALTER TABLE gb_device ADD COLUMN IF NOT EXISTS media_transport_mode VARCHAR(8) DEFAULT 'passive';
-- 老库升级：补充码流索引列（点播时选择主/子码流）
ALTER TABLE gb_device ADD COLUMN IF NOT EXISTS stream_index VARCHAR(32) DEFAULT 'auto';

-- 老库升级：级联上行推流的传输方式（auto 跟随上级 SDP / tcp / udp）
-- 上级 INVITE 声明 UDP 但它那侧 UDP 入站被防火墙拦时，改成 tcp 即可，
-- 否则表现是「本端日志一切正常、上级一直报 none rtp data receive」。
ALTER TABLE gb_cascade ADD COLUMN IF NOT EXISTS media_transport VARCHAR(8) DEFAULT 'auto';

-- 老库升级：补充通道的码流编号列表（设备上报该通道支持哪几路码流）
ALTER TABLE gb_channel ADD COLUMN IF NOT EXISTS stream_number_list VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_gb_operation_log_time ON gb_operation_log (create_time DESC);


-- ============================================================
--  第 1.5 段：结构迁移（老库升级用；全新库上为空操作）
--
--  CREATE TABLE IF NOT EXISTS 不会修改已存在的表，故唯一键变更需显式处理。
--  下面用 DO 块先判断旧约束是否存在，存在才替换，重复执行安全。
--
--  变更内容：gb_channel 的唯一键由 (channel_id) 改为 (device_id, channel_id)。
--  原因：GB/T 28181 只保证**设备编码**全局唯一，**通道编码**只在单设备内唯一，
--  不同设备可以出现相同通道编码。旧约束会让第二台设备的同号通道插不进库。
-- ============================================================

DO $$
BEGIN
    -- 旧唯一键名（PostgreSQL 默认命名：<表>_<列>_key）
    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'gb_channel_channel_id_key'
          AND conrelid = 'gb_channel'::regclass
    ) THEN
        -- 先清掉与即将建立的组合键重复的旧数据（同设备同通道只保留 id 最小的一条）
        DELETE FROM gb_channel a
        USING gb_channel b
        WHERE a.id > b.id
          AND a.device_id = b.device_id
          AND a.channel_id = b.channel_id;
        ALTER TABLE gb_channel DROP CONSTRAINT gb_channel_channel_id_key;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'gb_channel_device_id_channel_id_key'
          AND conrelid = 'gb_channel'::regclass
    ) THEN
        ALTER TABLE gb_channel
            ADD CONSTRAINT gb_channel_device_id_channel_id_key
            UNIQUE (device_id, channel_id);
    END IF;
END $$;

-- ------------------------------------------------------------
--  迁移：gb_record 增加对象存储（OSS）字段。
--  schema.sql 是「全新初始化 + 幂等补丁」脚本，CREATE TABLE IF NOT EXISTS
--  不会给已存在的表加列，故对老库用 ALTER TABLE ... ADD COLUMN IF NOT EXISTS 补齐。
-- ------------------------------------------------------------

ALTER TABLE gb_record ADD COLUMN IF NOT EXISTS storage_type VARCHAR(16) DEFAULT 'LOCAL';
ALTER TABLE gb_record ADD COLUMN IF NOT EXISTS object_key   VARCHAR(512);
ALTER TABLE gb_record ADD COLUMN IF NOT EXISTS file_url     VARCHAR(1024);
ALTER TABLE gb_record ADD COLUMN IF NOT EXISTS upload_state VARCHAR(16) DEFAULT 'NONE';
ALTER TABLE gb_record ADD COLUMN IF NOT EXISTS upload_time  TIMESTAMP;


-- ============================================================
--  第 2 段：注释（表与字段的中文说明，便于用 DBeaver/Navicat 等工具看库）
--  COMMENT ON 在 PostgreSQL 中是「覆盖写」，重复执行安全
-- ============================================================

-- -------------------- gb_device --------------------
COMMENT ON TABLE  gb_device IS '设备：GB28181 目录中的设备节点，含 IPC、NVR、下级平台';
COMMENT ON COLUMN gb_device.id                 IS '自增主键';
COMMENT ON COLUMN gb_device.device_id          IS '设备国标编码（20 位，唯一）';
COMMENT ON COLUMN gb_device.name               IS '设备名称（来自目录/DeviceInfo）';
COMMENT ON COLUMN gb_device.custom_name        IS '平台侧自定义名称，展示时优先于 name';
COMMENT ON COLUMN gb_device.manufacturer       IS '厂商';
COMMENT ON COLUMN gb_device.model              IS '型号';
COMMENT ON COLUMN gb_device.firmware           IS '固件版本';
COMMENT ON COLUMN gb_device.channel_count      IS '通道数量';
COMMENT ON COLUMN gb_device.civil_code         IS '行政区划编码（民用编码前 8 位）';
COMMENT ON COLUMN gb_device.address            IS '安装地址';
COMMENT ON COLUMN gb_device.parent_id          IS '父节点编码（所属上级设备/平台）';
COMMENT ON COLUMN gb_device.longitude          IS '经度（WGS84）';
COMMENT ON COLUMN gb_device.latitude           IS '纬度（WGS84）';
COMMENT ON COLUMN gb_device.ptz_type           IS '云台类型：0 未知 1 球机 2 半球 3 固定枪机 4 遥控枪机';
COMMENT ON COLUMN gb_device.status             IS '状态：ON 在线 / OFF 离线';
COMMENT ON COLUMN gb_device.online             IS '是否在线（由注册与心跳维护）';
COMMENT ON COLUMN gb_device.remote_ip          IS '设备 SIP 来源 IP（最近一次注册/心跳）';
COMMENT ON COLUMN gb_device.remote_port        IS '设备 SIP 来源端口';
COMMENT ON COLUMN gb_device.transport          IS 'SIP 传输协议：UDP / TCP';
COMMENT ON COLUMN gb_device.media_transport    IS '媒体传输协议：UDP 被动收流 / TCP 主动推流';
COMMENT ON COLUMN gb_device.media_transport_mode IS 'TCP 模式：passive 被动收流 / active 主动推流；UDP 时忽略';
COMMENT ON COLUMN gb_device.charset            IS '字符集：GB2312 / UTF-8';
COMMENT ON COLUMN gb_device.password           IS '设备 SIP 密码（平台侧留存，供运维参考）';
COMMENT ON COLUMN gb_device.catalog_interval   IS '目录订阅周期（秒）';
COMMENT ON COLUMN gb_device.subscribe_interval IS '订阅间隔（秒），0 表示不订阅';
COMMENT ON COLUMN gb_device.catalog_subscribe  IS '是否订阅目录变更';
COMMENT ON COLUMN gb_device.alarm_subscribe    IS '是否订阅报警';
COMMENT ON COLUMN gb_device.position_subscribe IS '是否订阅移动位置';
COMMENT ON COLUMN gb_device.ptz_subscribe      IS '是否订阅云台位置';
COMMENT ON COLUMN gb_device.record_center      IS '是否具备录像中心能力';
COMMENT ON COLUMN gb_device.register_time      IS '最近一次注册时间';
COMMENT ON COLUMN gb_device.keepalive_time     IS '最近一次心跳时间（离线判定依据）';
COMMENT ON COLUMN gb_device.update_time        IS '更新时间';
COMMENT ON COLUMN gb_device.create_time        IS '首次入库时间';
COMMENT ON COLUMN gb_device.source             IS '数据来源：device 设备上报 / manual 手工添加';
COMMENT ON COLUMN gb_device.extra              IS '扩展字段（JSON 等自由格式）';

-- -------------------- gb_channel --------------------
COMMENT ON TABLE  gb_channel IS '通道：设备下的视频通道，可直接点播；直连相机会有与设备同编号的通道';
COMMENT ON COLUMN gb_channel.id                 IS '自增主键';
COMMENT ON COLUMN gb_channel.channel_id         IS '通道国标编码（20 位；仅单设备内唯一，跨设备可重复）';
COMMENT ON COLUMN gb_channel.device_id          IS '所属设备国标编码（全局唯一，与 channel_id 组成唯一键）';
COMMENT ON COLUMN gb_channel.name               IS '通道名称（来自目录）';
COMMENT ON COLUMN gb_channel.custom_name        IS '平台侧自定义名称，展示时优先于 name';
COMMENT ON COLUMN gb_channel.custom_id          IS '平台侧自定义编号（可改，不影响与设备交互）';
COMMENT ON COLUMN gb_channel.channel_no         IS '通道序号';
COMMENT ON COLUMN gb_channel.manufacturer       IS '厂商';
COMMENT ON COLUMN gb_channel.model              IS '型号';
COMMENT ON COLUMN gb_channel.owner              IS '归属单位';
COMMENT ON COLUMN gb_channel.civil_code         IS '行政区划编码';
COMMENT ON COLUMN gb_channel.block              IS '警区/街区';
COMMENT ON COLUMN gb_channel.address            IS '安装地址';
COMMENT ON COLUMN gb_channel.parental           IS '是否有子节点：0 无 1 有';
COMMENT ON COLUMN gb_channel.parent_id          IS '父节点编码';
COMMENT ON COLUMN gb_channel.safety_way         IS '安全方式';
COMMENT ON COLUMN gb_channel.register_way       IS '注册方式：1 符合 GB28181';
COMMENT ON COLUMN gb_channel.secrecy            IS '保密属性：0 不涉密 1 涉密';
COMMENT ON COLUMN gb_channel.ip_address         IS '通道自身 IP（设备上报时携带）';
COMMENT ON COLUMN gb_channel.port               IS '通道自身端口';
COMMENT ON COLUMN gb_channel.password           IS '通道访问密码';
COMMENT ON COLUMN gb_channel.ptz_type           IS '云台类型：0 未知 1 球机 2 半球 3 固定枪机 4 遥控枪机';
COMMENT ON COLUMN gb_channel.position_type      IS '位置类型：0 未知 1 室外 2 室内';
COMMENT ON COLUMN gb_channel.room_type          IS '房间类型：0 未知 1 办公室 2 教室 3 机房 4 走廊 5 大厅';
COMMENT ON COLUMN gb_channel.use_type           IS '用途：0 未知 1 实时点播 2 历史回放 3 音视频复合';
COMMENT ON COLUMN gb_channel.supply_light       IS '补光：0 无 1 有';
COMMENT ON COLUMN gb_channel.direction_type     IS '方向类型：0 未知 1 东 2 西 3 南 4 北 5 东北 6 西北 7 东南 8 西南';
COMMENT ON COLUMN gb_channel.resolution         IS '分辨率';
COMMENT ON COLUMN gb_channel.download_speed     IS '下载速度';
COMMENT ON COLUMN gb_channel.firmware           IS '固件版本';
COMMENT ON COLUMN gb_channel.serial_number      IS '设备序列号';
COMMENT ON COLUMN gb_channel.longitude          IS '经度（WGS84）';
COMMENT ON COLUMN gb_channel.latitude           IS '纬度（WGS84）';
COMMENT ON COLUMN gb_channel.altitude           IS '海拔（米）';
COMMENT ON COLUMN gb_channel.speed              IS '速度（km/h，移动设备用）';
COMMENT ON COLUMN gb_channel.direction          IS '方向（度，移动设备用）';
COMMENT ON COLUMN gb_channel.battery_level      IS '电量百分比（移动设备用）';
COMMENT ON COLUMN gb_channel.signal_level       IS '信号强度（移动设备用）';
COMMENT ON COLUMN gb_channel.business_group_id  IS '业务分组编码';
COMMENT ON COLUMN gb_channel.status             IS '状态：ON 在线 / OFF 离线';
COMMENT ON COLUMN gb_channel.stream_id          IS '当前直播流标识（点播后写入，停止后清空）';
COMMENT ON COLUMN gb_channel.on_demand          IS '是否按需点播（无人观看时自动关流）';
COMMENT ON COLUMN gb_channel.cloud_record       IS '是否开启云端录像';
COMMENT ON COLUMN gb_channel.audio              IS '是否启用音频';
COMMENT ON COLUMN gb_channel.shared             IS '是否已共享给上级平台（级联）';
COMMENT ON COLUMN gb_channel.update_time        IS '更新时间';
COMMENT ON COLUMN gb_channel.create_time        IS '首次入库时间';

-- -------------------- gb_stream --------------------
COMMENT ON TABLE  gb_stream IS '直播流：一次点播对应一条记录，含各协议播放地址';
COMMENT ON COLUMN gb_stream.id           IS '自增主键';
COMMENT ON COLUMN gb_stream.stream_key   IS '流标识：<设备编码>/<通道编码>，与 ZLM 的 app/stream 一致，唯一';
COMMENT ON COLUMN gb_stream.channel_id   IS '通道国标编码';
COMMENT ON COLUMN gb_stream.device_id    IS '所属设备国标编码';
COMMENT ON COLUMN gb_stream.stream_name  IS 'ZLM 流名';
COMMENT ON COLUMN gb_stream.app          IS 'ZLM 应用名（= 设备编码）';
COMMENT ON COLUMN gb_stream.ssrc         IS 'RTP SSRC（16 进制字符串）';
COMMENT ON COLUMN gb_stream.rtp_port     IS '平台收流端口';
COMMENT ON COLUMN gb_stream.stream_type  IS '流类型：live 实时 / playback 回放';
COMMENT ON COLUMN gb_stream.state        IS '状态：NEGOTIATING 协商中 / ONLINE 在线 / CLOSED 已关闭';
COMMENT ON COLUMN gb_stream.call_id      IS 'SIP Call-ID（停止流时按它发 BYE）';
COMMENT ON COLUMN gb_stream.http_flv     IS 'HTTP-FLV 播放地址';
COMMENT ON COLUMN gb_stream.ws_flv       IS 'WebSocket-FLV 播放地址';
COMMENT ON COLUMN gb_stream.hls          IS 'HLS（m3u8）播放地址';
COMMENT ON COLUMN gb_stream.webrtc       IS 'WebRTC 播放地址（嵌入式模式下浏览器出画面走这里）';
COMMENT ON COLUMN gb_stream.rtsp         IS 'RTSP 播放地址';
COMMENT ON COLUMN gb_stream.rtmp         IS 'RTMP 播放地址';
COMMENT ON COLUMN gb_stream.transport    IS '媒体传输协议：UDP / TCP';
COMMENT ON COLUMN gb_stream.audio        IS '是否携带音频';
COMMENT ON COLUMN gb_stream.on_demand    IS '是否按需点播';
COMMENT ON COLUMN gb_stream.cloud_record IS '是否录制到云端';
COMMENT ON COLUMN gb_stream.viewers      IS '观看人数（嵌入式模式下无统计，恒为 0）';
COMMENT ON COLUMN gb_stream.start_time   IS '开流时间';
COMMENT ON COLUMN gb_stream.update_time  IS '更新时间';

-- -------------------- gb_record --------------------
COMMENT ON TABLE  gb_record IS '录像：平台侧/云端录制产生的文件索引';
COMMENT ON COLUMN gb_record.id           IS '自增主键';
COMMENT ON COLUMN gb_record.record_id    IS '录像唯一标识';
COMMENT ON COLUMN gb_record.channel_id   IS '通道国标编码';
COMMENT ON COLUMN gb_record.device_id    IS '所属设备国标编码';
COMMENT ON COLUMN gb_record.name         IS '录像名称';
COMMENT ON COLUMN gb_record.file_path    IS '文件路径（绝对路径或相对录像根目录）';
COMMENT ON COLUMN gb_record.file_name    IS '文件名';
COMMENT ON COLUMN gb_record.file_size    IS '文件大小（字节）';
COMMENT ON COLUMN gb_record.start_time   IS '录像起始时间';
COMMENT ON COLUMN gb_record.end_time     IS '录像结束时间';
COMMENT ON COLUMN gb_record.duration     IS '时长（秒）';
COMMENT ON COLUMN gb_record.important    IS '是否标记为重要录像（重要录像不参与自动清理）';
COMMENT ON COLUMN gb_record.cloud_record IS '是否为云端录像';
COMMENT ON COLUMN gb_record.storage_type IS '存储类型：LOCAL 本地磁盘 / OSS 对象存储';
COMMENT ON COLUMN gb_record.object_key   IS '对象存储键（storage_type=OSS 时有效）';
COMMENT ON COLUMN gb_record.file_url     IS '对象存储外链或签名地址（storage_type=OSS 时有效）';
COMMENT ON COLUMN gb_record.upload_state IS '上传状态：NONE 无需上传 / PENDING 待上传 / UPLOADED 已上传 / FAILED 上传失败';
COMMENT ON COLUMN gb_record.upload_time  IS '上传完成时间';
COMMENT ON COLUMN gb_record.create_time  IS '入库时间';

-- -------------------- gb_device_record --------------------
COMMENT ON TABLE  gb_device_record IS '设备录像目录：设备对 RecordInfo 查询的应答缓存';
COMMENT ON COLUMN gb_device_record.id            IS '自增主键';
COMMENT ON COLUMN gb_device_record.channel_id    IS '通道国标编码';
COMMENT ON COLUMN gb_device_record.device_id     IS '所属设备国标编码';
COMMENT ON COLUMN gb_device_record.name          IS '录像名称';
COMMENT ON COLUMN gb_device_record.file_path     IS '设备侧文件路径（回放/下载时原样回传）';
COMMENT ON COLUMN gb_device_record.address       IS '录像存储地址（设备上报）';
COMMENT ON COLUMN gb_device_record.start_time    IS '录像起始时间';
COMMENT ON COLUMN gb_device_record.end_time      IS '录像结束时间';
COMMENT ON COLUMN gb_device_record.duration      IS '时长（秒）';
COMMENT ON COLUMN gb_device_record.size          IS '文件大小（字节）';
COMMENT ON COLUMN gb_device_record.type          IS '录像类型：time 定时 / manual 手动 / alarm 报警';
COMMENT ON COLUMN gb_device_record.secrecy       IS '保密属性：0 不涉密 1 涉密';
COMMENT ON COLUMN gb_device_record.stream_number IS '码流编号：0 主码流 1 子码流';
COMMENT ON COLUMN gb_device_record.update_time   IS '更新时间';

-- -------------------- gb_alarm --------------------
COMMENT ON TABLE  gb_alarm IS '报警：设备主动上报的报警通知与复位记录';
COMMENT ON COLUMN gb_alarm.id                IS '自增主键';
COMMENT ON COLUMN gb_alarm.alarm_id          IS '报警唯一标识（用于去重与复位关联）';
COMMENT ON COLUMN gb_alarm.device_id         IS '报警来源设备国标编码';
COMMENT ON COLUMN gb_alarm.channel_id        IS '报警来源通道国标编码';
COMMENT ON COLUMN gb_alarm.method            IS '报警方式：1 电话 2 设备 3 短信 4 GPS 5 视频 6 设备故障 7 其他';
COMMENT ON COLUMN gb_alarm.method_name       IS '报警方式名称';
COMMENT ON COLUMN gb_alarm.priority          IS '报警级别：1 一级（最高）～ 4 四级（最低）';
COMMENT ON COLUMN gb_alarm.priority_name     IS '报警级别名称';
COMMENT ON COLUMN gb_alarm.alarm_type        IS '报警类型编号（含义随报警方式变化）';
COMMENT ON COLUMN gb_alarm.alarm_type_name   IS '报警类型名称';
COMMENT ON COLUMN gb_alarm.event_type        IS '事件类型（GB28181-2022 扩展）';
COMMENT ON COLUMN gb_alarm.alarm_description IS '报警描述';
COMMENT ON COLUMN gb_alarm.start_time        IS '报警发生时间';
COMMENT ON COLUMN gb_alarm.end_time          IS '报警结束时间（复位时写入）';
COMMENT ON COLUMN gb_alarm.state             IS '状态：ACTIVE 未复位 / RESET 已复位';
COMMENT ON COLUMN gb_alarm.longitude         IS '报警位置经度';
COMMENT ON COLUMN gb_alarm.latitude          IS '报警位置纬度';
COMMENT ON COLUMN gb_alarm.video_path        IS '关联录像路径';
COMMENT ON COLUMN gb_alarm.snap_path         IS '关联抓拍图片路径';
COMMENT ON COLUMN gb_alarm.create_time       IS '入库时间';

-- -------------------- gb_cascade --------------------
COMMENT ON TABLE  gb_cascade IS '级联配置：本级作为下级，向上级平台注册';
COMMENT ON COLUMN gb_cascade.id                  IS '自增主键';
COMMENT ON COLUMN gb_cascade.cascade_id          IS '级联配置标识（平台生成，唯一）';
COMMENT ON COLUMN gb_cascade.name                IS '级联名称';
COMMENT ON COLUMN gb_cascade.parent_id           IS '上级平台国标编码（20 位）';
COMMENT ON COLUMN gb_cascade.parent_name         IS '上级平台名称';
COMMENT ON COLUMN gb_cascade.parent_ip           IS '上级平台 SIP 地址';
COMMENT ON COLUMN gb_cascade.parent_port         IS '上级平台 SIP 端口';
COMMENT ON COLUMN gb_cascade.parent_domain       IS '上级平台 SIP 域（Digest realm）';
COMMENT ON COLUMN gb_cascade.parent_password     IS '上级平台注册密码';
COMMENT ON COLUMN gb_cascade.parent_protocol     IS '上级平台信令传输：UDP / TCP';
COMMENT ON COLUMN gb_cascade.local_id            IS '本级对外暴露的设备编码';
COMMENT ON COLUMN gb_cascade.local_ip            IS '本级对外暴露的 SIP 地址';
COMMENT ON COLUMN gb_cascade.local_port          IS '本级对外暴露的 SIP 端口';
COMMENT ON COLUMN gb_cascade.username            IS '向上级注册用的 SIP 用户名';
COMMENT ON COLUMN gb_cascade.password            IS '向上级注册用的 SIP 密码';
COMMENT ON COLUMN gb_cascade.keepalive_interval  IS '心跳间隔（秒）';
COMMENT ON COLUMN gb_cascade.keepalive_max_count IS '心跳超时次数（超过则判定上级离线）';
COMMENT ON COLUMN gb_cascade.register_interval   IS '注册间隔（秒）';
COMMENT ON COLUMN gb_cascade.register_timeout    IS '注册有效期（秒）';
COMMENT ON COLUMN gb_cascade.catalog_group_size  IS '目录上报时单个消息包含的通道数';
COMMENT ON COLUMN gb_cascade.control_priority    IS '云台控制优先级（0～100）';
COMMENT ON COLUMN gb_cascade.load_limit          IS '流并发上限，0 表示不限制';
COMMENT ON COLUMN gb_cascade.charset             IS '字符集：GB2312 / UTF-8';
COMMENT ON COLUMN gb_cascade.allow_control       IS '是否允许上级平台控制本级设备';
COMMENT ON COLUMN gb_cascade.share_all_channel   IS '是否共享全部通道';
COMMENT ON COLUMN gb_cascade.stream_keepalive    IS '是否保持流心跳（点播保活）';
COMMENT ON COLUMN gb_cascade.enabled             IS '是否启用该级联配置';
COMMENT ON COLUMN gb_cascade.online              IS '当前是否已注册到上级平台';
COMMENT ON COLUMN gb_cascade.expire              IS '注册有效期（秒，上级返回）';
COMMENT ON COLUMN gb_cascade.remark              IS '备注';
COMMENT ON COLUMN gb_cascade.create_time         IS '创建时间';
COMMENT ON COLUMN gb_cascade.update_time         IS '更新时间';

-- -------------------- gb_cascade_channel --------------------
COMMENT ON TABLE  gb_cascade_channel IS '级联共享通道：哪些通道共享给哪个上级平台';
COMMENT ON COLUMN gb_cascade_channel.id          IS '自增主键';
COMMENT ON COLUMN gb_cascade_channel.cascade_id  IS '所属级联配置标识';
COMMENT ON COLUMN gb_cascade_channel.parent_id   IS '上级平台国标编码';
COMMENT ON COLUMN gb_cascade_channel.channel_id  IS '共享的通道国标编码';
COMMENT ON COLUMN gb_cascade_channel.shared      IS '是否共享';
COMMENT ON COLUMN gb_cascade_channel.pushed      IS '是否已推送目录给上级';
COMMENT ON COLUMN gb_cascade_channel.push_time   IS '最近一次目录推送时间';
COMMENT ON COLUMN gb_cascade_channel.create_time IS '创建时间';

-- -------------------- gb_device_group --------------------
COMMENT ON TABLE  gb_device_group IS '设备分组：平台侧自定义的树形分组';
COMMENT ON COLUMN gb_device_group.id          IS '自增主键';
COMMENT ON COLUMN gb_device_group.group_code  IS '分组编码（唯一）';
COMMENT ON COLUMN gb_device_group.group_name  IS '分组名称';
COMMENT ON COLUMN gb_device_group.parent_code IS '上级分组编码，空表示根分组';
COMMENT ON COLUMN gb_device_group.sort_no     IS '同级排序号';
COMMENT ON COLUMN gb_device_group.create_time IS '创建时间';

-- -------------------- gb_status_log --------------------
COMMENT ON TABLE  gb_status_log IS '状态变更日志：设备/通道上下线记录';
COMMENT ON COLUMN gb_status_log.id          IS '自增主键';
COMMENT ON COLUMN gb_status_log.device_id   IS '设备国标编码';
COMMENT ON COLUMN gb_status_log.channel_id  IS '通道国标编码（通道级事件才有）';
COMMENT ON COLUMN gb_status_log.old_status  IS '变更前状态';
COMMENT ON COLUMN gb_status_log.new_status  IS '变更后状态';
COMMENT ON COLUMN gb_status_log.description IS '变更描述';
COMMENT ON COLUMN gb_status_log.reason      IS '变更原因：register 注册 / silent 心跳超时 / manual 手工';
COMMENT ON COLUMN gb_status_log.create_time IS '发生时间';

-- -------------------- gb_position_log --------------------
COMMENT ON TABLE  gb_position_log IS '移动位置记录：移动设备轨迹';
COMMENT ON COLUMN gb_position_log.id          IS '自增主键';
COMMENT ON COLUMN gb_position_log.device_id   IS '设备国标编码';
COMMENT ON COLUMN gb_position_log.channel_id  IS '通道国标编码';
COMMENT ON COLUMN gb_position_log.method      IS '定位方式：GPS / 北斗 / 基站 等';
COMMENT ON COLUMN gb_position_log.longitude   IS '经度';
COMMENT ON COLUMN gb_position_log.latitude    IS '纬度';
COMMENT ON COLUMN gb_position_log.altitude    IS '海拔（米）';
COMMENT ON COLUMN gb_position_log.speed       IS '速度（km/h）';
COMMENT ON COLUMN gb_position_log.direction   IS '方向（度）';
COMMENT ON COLUMN gb_position_log.create_time IS '采集时间';

-- -------------------- gb_preset --------------------
COMMENT ON TABLE  gb_preset IS '预置位名称自定义：设备只存编号，名称由平台维护';
COMMENT ON COLUMN gb_preset.id          IS '自增主键';
COMMENT ON COLUMN gb_preset.channel_id  IS '通道国标编码';
COMMENT ON COLUMN gb_preset.preset_id   IS '预置位编号（1～255）';
COMMENT ON COLUMN gb_preset.name        IS '预置位名称（平台侧自定义）';
COMMENT ON COLUMN gb_preset.update_time IS '更新时间';

-- -------------------- gb_user --------------------
COMMENT ON TABLE  gb_user IS '平台用户';
COMMENT ON COLUMN gb_user.id              IS '自增主键';
COMMENT ON COLUMN gb_user.username        IS '登录用户名（唯一）';
COMMENT ON COLUMN gb_user.password        IS '密码存储值 = BCrypt(MD5(明文))，前端先算 MD5 再提交';
COMMENT ON COLUMN gb_user.nickname        IS '昵称';
COMMENT ON COLUMN gb_user.role            IS '角色，多角色以逗号分隔（admin / operator / user）';
COMMENT ON COLUMN gb_user.phone_number    IS '手机号';
COMMENT ON COLUMN gb_user.email           IS '邮箱';
COMMENT ON COLUMN gb_user.description     IS '描述';
COMMENT ON COLUMN gb_user.enable          IS '是否启用';
COMMENT ON COLUMN gb_user.has_all_channel IS '是否可访问全部通道';
COMMENT ON COLUMN gb_user.creator         IS '创建者';
COMMENT ON COLUMN gb_user.lock            IS '是否因多次登录失败被锁定';
COMMENT ON COLUMN gb_user.last_login_at   IS '最近登录时间';
COMMENT ON COLUMN gb_user.create_time     IS '创建时间';
COMMENT ON COLUMN gb_user.update_time     IS '更新时间';

-- -------------------- sa_token_store --------------------
COMMENT ON TABLE  sa_token_store IS 'Sa-Token 会话存储：登录 token 持久化，服务重启后登录态保留';
COMMENT ON COLUMN sa_token_store.k          IS 'Sa-Token 键（如 satoken:login:token:<token>）';
COMMENT ON COLUMN sa_token_store.obj        IS 'JDK 序列化的值（String / SaSession 等）';
COMMENT ON COLUMN sa_token_store.expire_at  IS '过期时间，NULL 表示永不过期';
COMMENT ON COLUMN sa_token_store.updated_at IS '最近写入时间（排障用）';

-- -------------------- gb_operation_log --------------------
COMMENT ON TABLE  gb_operation_log IS '操作日志：管理接口调用记录（方式/路径/远端/结果/用时）';
COMMENT ON COLUMN gb_operation_log.method      IS '调用方式：HTTP_GET / HTTP_POST（SIP_* 预留）';
COMMENT ON COLUMN gb_operation_log.resource    IS '资源路径（如 /api/v1/device/remove）';
COMMENT ON COLUMN gb_operation_log.remote_addr IS '远端地址（请求方 IP）';
COMMENT ON COLUMN gb_operation_log.result      IS '结果：HTTP 状态码或异常类名';
COMMENT ON COLUMN gb_operation_log.duration_ms IS '用时（毫秒）';
COMMENT ON COLUMN gb_operation_log.create_time IS '记录时间';


-- -------------------- 接入控制（黑白名单） --------------------
--
-- 与 SIP 注册密码是两道**独立**的闸门：
--   密码回答「你是谁」，本表回答「这个来源许不许进」。
--   访问控制在摘要认证之前生效，所以黑名单里的设备即使密码正确也进不来 ——
--   这正是「密码泄露后仍能拦住已知恶意来源」的全部意义。
--
-- kind 取值：
--   DEVICE   设备编码精确匹配（20 位国标编码；级联场景是下级平台编码）
--   PREFIX   设备编码前缀匹配，如 3402000000132（某厂商的一批设备）
--   IP       IPv4 精确匹配
--   CIDR     网段匹配，如 192.168.1.0/24
--
-- list_type：BLACK 命中即拒绝 / WHITE 只放行命中的
--
-- 两种名单可同时存在，判定顺序「先黑后白」：
--   1. 命中黑名单 → 拒绝（黑名单优先级最高，白名单不能给它开后门）
--   2. 存在任何白名单且未命中 → 拒绝
--   3. 其余放行
-- 这个顺序保证「临时拉黑某台设备」不会因为它在白名单里而失效。
CREATE TABLE IF NOT EXISTS gb_acl (
    id          BIGSERIAL PRIMARY KEY,
    list_type   VARCHAR(8)   NOT NULL DEFAULT 'BLACK',
    kind        VARCHAR(8)   NOT NULL DEFAULT 'DEVICE',
    -- 匹配值：设备编码 / 编码前缀 / IPv4 / 网段
    value       VARCHAR(64)  NOT NULL,
    -- 备注（写明为什么加这条，半年后只有它还能解释清楚）
    remark      VARCHAR(200),
    create_time TIMESTAMP    DEFAULT NOW(),
    UNIQUE (list_type, kind, value)
);
CREATE INDEX IF NOT EXISTS idx_gb_acl_type ON gb_acl (list_type, kind);

COMMENT ON TABLE  gb_acl IS '接入控制：SIP 注册的黑白名单';
COMMENT ON COLUMN gb_acl.list_type IS 'BLACK 命中即拒 / WHITE 只放行命中';
COMMENT ON COLUMN gb_acl.kind      IS 'DEVICE 编码 / PREFIX 编码前缀 / IP / CIDR 网段';
COMMENT ON COLUMN gb_acl.value     IS '匹配值';
COMMENT ON COLUMN gb_acl.remark    IS '备注：为什么加这条';


-- ============================================================
--  第 3 段：初始化数据
-- ============================================================

-- 初始管理员（密码 admin123）
-- 存储值 = BCrypt(MD5('admin123'))，与 UserService 的校验逻辑一致
INSERT INTO gb_user (username, password, nickname, role, creator, has_all_channel)
VALUES ('admin',
        '$2a$10$D0G.UwWmegaF3bCClR9VQOmwabjs/fLTJzWaY4VfNisKlo5PnU2ey',
        '管理员', 'admin', 'system', TRUE)
ON CONFLICT (username) DO NOTHING;
