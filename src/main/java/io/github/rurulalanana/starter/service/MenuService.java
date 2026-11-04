package io.github.rurulalanana.starter.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.common.Permissions;
import io.github.rurulalanana.starter.dto.MenuSaveRequest;
import io.github.rurulalanana.starter.dto.MenuSyncRequest;
import io.github.rurulalanana.starter.entity.GbMenu;
import io.github.rurulalanana.starter.mapper.GbMenuMapper;
import io.github.rurulalanana.starter.vo.MenuConfigVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 菜单结构（目录 / 菜单 / 按钮三层，表 {@code gb_menu}）。
 * 目录与菜单行由前端 {@code router/routes.js} 经 {@link #sync} 补写，按钮行由 {@link #seedButtons}
 * 按 {@link Permissions} 补写（权限标识是后端常量）；两边都用 {@code gb_menu_seed} 记版本，
 * 只版本变了才补写，否则运维删掉的菜单每次重启又冒出来。
 * 本表只决定显示什么，不是授权手段。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MenuService {

    /** 主键：小写字母开头，允许字母/数字/中划线；按钮行直接用权限标识（含冒号）。 */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9:-]{0,63}$");

    /** 权限标识：至少一段 {@code 资源:动作}。 */
    private static final Pattern PERMS_PATTERN = Pattern.compile("^[a-z][a-z0-9-]*(:[a-z0-9-]+)+$");

    /** 组件路径：{@code views/Xxx.vue}，前端由 {@code import.meta.glob} 解析。 */
    private static final Pattern COMPONENT_PATTERN = Pattern.compile("^views/[A-Za-z0-9_-]+\\.vue$");

    /** 种子版本记录的两把钥匙。 */
    private static final String SEED_STRUCTURE = "structure";
    private static final String SEED_BUTTONS = "buttons";

    /** 权限标识 → 菜单键的精确映射（默认规则：模块名 == 菜单键 → 模块名 + s → {@link #MODULE_MENU} → perms 前缀）。 */
    private static final Map<String, String> CODE_MENU = Map.of(
            // 设备录像挂在 playback 页，实时点播在 live 页 —— 两者同属 play 模块
            "play:record", "playback"
    );

    /** 模块 → 菜单键的显式覆盖：按前缀猜会命中排序更靠前的同前缀菜单，容易挂错页面。 */
    private static final Map<String, String> MODULE_MENU = Map.of(
            // 系统类权限属于「系统设置」，而不是排序更靠前的「菜单管理」
            "system", "settings",
            "play", "live"
    );

    private static final int MAX_TITLE = 64;
    private static final int MAX_ICON = 64;
    private static final int MAX_PATH = 160;
    private static final int MAX_COMPONENT = 160;
    private static final int MAX_PERMS = 128;

    private final GbMenuMapper menuMapper;

    /** 全部节点（目录/菜单/按钮混排，前端按 {@code parentKey} 自行成树）。 */
    public MenuConfigVo config() {
        MenuConfigVo vo = new MenuConfigVo();
        List<MenuConfigVo.ItemVo> items = new ArrayList<>();
        // D 在前、B 在后：前端建树时父节点一定先于子节点出现，不用做多轮扫描
        for (GbMenu m : menuMapper.selectList(Wrappers.<GbMenu>lambdaQuery()
                .orderByAsc(GbMenu::getMenuType)
                .orderByAsc(GbMenu::getSortOrder)
                .orderByAsc(GbMenu::getMenuKey))) {
            items.add(toVo(m));
        }
        vo.setItems(items);
        return vo;
    }

    /**
     * 补齐缺失的目录 / 菜单行（版本一致时是空操作）。已有行只补空列，绝不覆盖：
     * 运维改过的名称、图标、排序都要保留；老库缺 {@code path/component/perms} 正好由这次补写升级。
     *
     * @return 新插入的行数
     */
    @Transactional
    public synchronized int sync(MenuSyncRequest req) {
        String version = clean(req.getVersion(), 128, "种子版本");
        if (version == null) {
            throw BizException.badRequest("种子版本不能为空");
        }
        List<MenuConfigVo.ItemVo> items = req.getItems();
        if (items == null || items.isEmpty()) {
            throw BizException.badRequest("种子内容不能为空");
        }

        int inserted = 0;
        if (!version.equals(menuMapper.selectSeedVersion(SEED_STRUCTURE))) {
            for (MenuConfigVo.ItemVo it : items) {
                String key = requireKey(it.getMenuKey());
                String type = normalizeType(it.getMenuType());
                if (GbMenu.TYPE_BUTTON.equals(type)) {
                    // 按钮由 seedButtons 负责，前端提交的一律忽略，避免两边打架
                    continue;
                }
                GbMenu row = menuMapper.selectById(key);
                if (row == null) {
                    menuMapper.insert(newRow(key, type, it));
                    inserted++;
                    continue;
                }
                if (backfill(row, type, it)) {
                    menuMapper.updateById(row);
                }
            }
            menuMapper.upsertSeedVersion(SEED_STRUCTURE, version);
            log.info("菜单结构已同步到版本 {}：新增 {} 行", version, inserted);
        }

        // 结构同步时顺便补一次按钮（幂等）：通常启动时已经补过，
        // 这里兜住「运行期新增权限点、又不想重启」的场景。
        seedButtons();
        return inserted;
    }

    /**
     * 启动时补齐按钮行。按钮是后端权限清单的投影，不能靠前端触发：前端只在菜单结构版本变化时
     * POST {@code /menu/sync}，而新增权限点并不改变菜单结构。
     * 监听 {@code ApplicationReadyEvent} 而非 {@code @PostConstruct}：表由
     * {@code SchemaInitializer} 的 ApplicationRunner 建，之后才一定存在。
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedButtonsOnStartup() {
        try {
            seedButtons();
        } catch (Exception e) {
            // 种不出来不该拦住启动：菜单页会显示缺行，但平台其余功能正常
            log.warn("启动时同步菜单按钮失败（不影响启动）：{}", e.getMessage());
        }
    }

    /**
     * 新增或修改一个节点。
     *
     * @return 节点主键（新增时由服务端生成）
     */
    @Transactional
    public String save(MenuSaveRequest req) {
        String type = normalizeType(req.getMenuType());
        String title = requireTitle(req.getTitle());
        String icon = clean(req.getIcon(), MAX_ICON, "图标名");
        String parentKey = clean(req.getParentKey(), 64, "上级编码");
        Integer status = req.getStatus() == null ? GbMenu.STATUS_ENABLED : req.getStatus();
        if (status != GbMenu.STATUS_ENABLED && status != GbMenu.STATUS_DISABLED) {
            throw BizException.badRequest("菜单状态只能是 0（正常）或 1（停用）");
        }
        boolean hidden = Boolean.TRUE.equals(req.getHidden());

        String key;
        if (req.getMenuKey() == null || req.getMenuKey().isBlank()) {
            key = generateKey(type);
        } else {
            key = requireKey(req.getMenuKey());
        }
        // 目录是顶层节点，不许挂在别人下面
        if (GbMenu.TYPE_DIRECTORY.equals(type)) {
            parentKey = null;
        } else {
            if (parentKey == null) {
                throw BizException.badRequest("请选择上级节点");
            }
            GbMenu parent = menuMapper.selectById(parentKey);
            if (parent == null) {
                throw BizException.badRequest("上级节点不存在：" + parentKey);
            }
            requireParentType(type, parent);
        }

        GbMenu row = menuMapper.selectById(key);
        boolean created = row == null;
        if (created) {
            row = new GbMenu();
            row.setMenuKey(key);
        }
        row.setMenuType(type);
        row.setParentKey(parentKey);
        row.setTitle(title);
        row.setIcon(GbMenu.TYPE_BUTTON.equals(type) ? null : icon);
        row.setSortOrder(req.getSortOrder() == null ? nextSortOrder(parentKey) : req.getSortOrder());
        row.setHidden(hidden);
        row.setStatus(status);

        if (GbMenu.TYPE_MENU.equals(type)) {
            row.setPath(requirePath(req.getPath()));
            row.setComponent(requireComponent(req.getComponent()));
            row.setPerms(cleanPerms(req.getPerms()));
        } else if (GbMenu.TYPE_BUTTON.equals(type)) {
            row.setPath(null);
            row.setComponent(null);
            row.setPerms(requirePerms(req.getPerms()));
        } else {
            row.setPath(null);
            row.setComponent(null);
            row.setPerms(null);
        }
        row.setUpdateTime(LocalDateTime.now());

        if (created) {
            menuMapper.insert(row);
            log.info("菜单节点已新增: key={}, type={}, parent={}, title={}", key, type, parentKey, title);
        } else {
            menuMapper.updateById(row);
            log.info("菜单节点已修改: key={}, type={}, parent={}, title={}", key, type, parentKey, title);
        }
        return key;
    }

    /**
     * 删除一个节点。
     *
     * @param cascade 是否连同全部下级一起删除
     * @return 实际删除的行数
     */
    @Transactional
    public int delete(String menuKey, boolean cascade) {
        String key = requireKey(menuKey);
        List<String> keys = cascade ? collectSubtree(key) : List.of(key);
        int n = 0;
        for (String k : keys) {
            n += menuMapper.deleteById(k);
        }
        log.info("菜单节点已删除: key={}, cascade={}, 行数={}", key, cascade, n);
        return n;
    }

    /** 收集自身 + 全部后代的主键（按层展开，避免递归深度受数据影响）。 */
    private List<String> collectSubtree(String root) {
        Map<String, List<String>> children = new HashMap<>();
        for (GbMenu m : menuMapper.selectList(Wrappers.<GbMenu>lambdaQuery()
                .select(GbMenu::getMenuKey, GbMenu::getParentKey))) {
            if (m.getParentKey() != null) {
                children.computeIfAbsent(m.getParentKey(), k -> new ArrayList<>()).add(m.getMenuKey());
            }
        }
        List<String> out = new ArrayList<>();
        List<String> frontier = new ArrayList<>(List.of(root));
        while (!frontier.isEmpty()) {
            List<String> next = new ArrayList<>();
            for (String k : frontier) {
                if (out.contains(k)) {
                    continue;
                }
                out.add(k);
                next.addAll(children.getOrDefault(k, List.of()));
            }
            frontier = next;
        }
        return out;
    }

    /**
     * 按 {@link Permissions} 清单补齐按钮行。版本号取「条目数 + 全部编码的哈希」，
     * 增删权限点或改名会触发补写，平时重启不会把运维删掉的按钮种回来。
     */
    @Transactional
    public int seedButtons() {
        List<Permissions.Def> defs = Permissions.all();
        // 版本号带一个常量：改了编号规则（例如从全局序号改成同级序号）时改它，强制重种
        String version = "3-" + defs.size() + "-" + Integer.toHexString(
                defs.stream().map(Permissions.Def::code).collect(Collectors.joining(",")).hashCode());
        if (version.equals(menuMapper.selectSeedVersion(SEED_BUTTONS))) {
            return 0;
        }

        // 三条查找路径，从最贴合到最宽松：
        // 1) 按菜单键（模块名本身 / 模块名加 s）  2) 按菜单行 perms 的模块前缀  3) 兜底映射
        Map<String, String> byKey = new LinkedHashMap<>();
        Map<String, String> byPerms = new LinkedHashMap<>();
        for (GbMenu m : menuMapper.selectList(Wrappers.<GbMenu>lambdaQuery()
                .eq(GbMenu::getMenuType, GbMenu.TYPE_MENU)
                .orderByAsc(GbMenu::getSortOrder))) {
            byKey.putIfAbsent(m.getMenuKey(), m.getMenuKey());
            if (m.getPerms() != null) {
                byPerms.putIfAbsent(moduleOf(m.getPerms()), m.getMenuKey());
            }
        }

        int inserted = 0;
        // 同级序号：每个菜单下的按钮各自从 1 开始，界面上看起来才像若依
        Map<String, Integer> sortByParent = new LinkedHashMap<>();
        for (Permissions.Def def : defs) {
            String code = def.code();
            String module = moduleOf(code);
            String parent = CODE_MENU.get(code);
            if (parent == null) {
                parent = byKey.get(module);
            }
            if (parent == null) {
                parent = byKey.get(module + "s");
            }
            if (parent == null) {
                parent = MODULE_MENU.get(module);
            }
            if (parent == null) {
                parent = byPerms.get(module);
            }
            // 先占号再判断是否已存在：已有行也要占位，否则新补的按钮会与它们撞号
            int sort = sortByParent.merge(parent == null ? "" : parent, 1, Integer::sum);
            if (menuMapper.selectById(def.code()) != null) {
                continue;
            }
            GbMenu row = new GbMenu();
            // 按钮主键直接用权限标识：它是天然唯一的稳定键，也让「授权」与「菜单」对得上号
            row.setMenuKey(def.code());
            row.setParentKey(parent);
            row.setMenuType(GbMenu.TYPE_BUTTON);
            row.setTitle(def.name());
            row.setSortOrder(sort);
            row.setPerms(def.code());
            row.setHidden(false);
            row.setStatus(GbMenu.STATUS_ENABLED);
            row.setUpdateTime(LocalDateTime.now());
            menuMapper.insert(row);
            inserted++;
        }
        menuMapper.upsertSeedVersion(SEED_BUTTONS, version);
        log.info("菜单按钮已同步到版本 {}：新增 {} 行", version, inserted);
        return inserted;
    }

    // ==================== 内部工具 ====================

    private MenuConfigVo.ItemVo toVo(GbMenu m) {
        MenuConfigVo.ItemVo item = new MenuConfigVo.ItemVo();
        item.setMenuKey(m.getMenuKey());
        item.setParentKey(m.getParentKey());
        item.setMenuType(m.getMenuType());
        item.setTitle(m.getTitle());
        item.setIcon(m.getIcon());
        item.setSortOrder(m.getSortOrder());
        item.setHidden(m.getHidden());
        item.setPath(m.getPath());
        item.setComponent(m.getComponent());
        item.setPerms(m.getPerms());
        item.setStatus(m.getStatus() == null ? GbMenu.STATUS_ENABLED : m.getStatus());
        return item;
    }

    private GbMenu newRow(String key, String type, MenuConfigVo.ItemVo it) {
        GbMenu row = new GbMenu();
        row.setMenuKey(key);
        row.setMenuType(type);
        row.setParentKey(clean(it.getParentKey(), 64, "上级编码"));
        row.setTitle(clean(it.getTitle(), MAX_TITLE, "名称"));
        row.setIcon(clean(it.getIcon(), MAX_ICON, "图标名"));
        row.setSortOrder(it.getSortOrder());
        row.setPath(clean(it.getPath(), MAX_PATH, "路由地址"));
        row.setComponent(clean(it.getComponent(), MAX_COMPONENT, "组件路径"));
        row.setPerms(clean(it.getPerms(), MAX_PERMS, "权限标识"));
        row.setHidden(Boolean.TRUE.equals(it.getHidden()));
        row.setStatus(GbMenu.STATUS_ENABLED);
        row.setUpdateTime(LocalDateTime.now());
        return row;
    }

    /**
     * 用种子补齐已存在行的空列。只填 {@code null}：老库里存的是运维改过的名称 / 图标 / 排序，
     * 被种子覆盖一次等于把他们的配置全抹了。
     *
     * @return 是否需要写回
     */
    private boolean backfill(GbMenu row, String type, MenuConfigVo.ItemVo it) {
        boolean dirty = false;
        if (row.getMenuType() == null) {
            row.setMenuType(type);
            dirty = true;
        }
        if (row.getTitle() == null && it.getTitle() != null) {
            row.setTitle(it.getTitle());
            dirty = true;
        }
        if (row.getIcon() == null && it.getIcon() != null) {
            row.setIcon(it.getIcon());
            dirty = true;
        }
        if (row.getSortOrder() == null && it.getSortOrder() != null) {
            row.setSortOrder(it.getSortOrder());
            dirty = true;
        }
        if (row.getParentKey() == null && it.getParentKey() != null) {
            row.setParentKey(it.getParentKey());
            dirty = true;
        }
        if (row.getPath() == null && it.getPath() != null) {
            row.setPath(it.getPath());
            dirty = true;
        }
        if (row.getComponent() == null && it.getComponent() != null) {
            row.setComponent(it.getComponent());
            dirty = true;
        }
        if (row.getPerms() == null && it.getPerms() != null) {
            row.setPerms(it.getPerms());
            dirty = true;
        }
        if (row.getStatus() == null) {
            row.setStatus(GbMenu.STATUS_ENABLED);
            dirty = true;
        }
        if (dirty) {
            row.setUpdateTime(LocalDateTime.now());
        }
        return dirty;
    }

    private void requireParentType(String childType, GbMenu parent) {
        String parentType = parent.getMenuType();
        boolean ok = GbMenu.TYPE_BUTTON.equals(childType)
                // 按钮只能挂菜单；但要容忍历史数据里按钮挂在目录下
                ? GbMenu.TYPE_MENU.equals(parentType) || GbMenu.TYPE_DIRECTORY.equals(parentType)
                : GbMenu.TYPE_DIRECTORY.equals(parentType);
        if (!ok) {
            throw BizException.badRequest(GbMenu.TYPE_BUTTON.equals(childType)
                    ? "按钮的上级只能是菜单"
                    : "菜单的上级只能是目录");
        }
    }

    private String generateKey(String type) {
        String prefix = GbMenu.TYPE_DIRECTORY.equals(type) ? "d"
                : GbMenu.TYPE_BUTTON.equals(type) ? "b" : "m";
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** 同级排序：没填就排到末尾，省得每次新增都手填。 */
    private int nextSortOrder(String parentKey) {
        List<GbMenu> siblings = menuMapper.selectList(Wrappers.<GbMenu>lambdaQuery()
                .select(GbMenu::getSortOrder)
                .eq(parentKey != null, GbMenu::getParentKey, parentKey)
                .isNull(parentKey == null, GbMenu::getParentKey));
        return siblings.stream()
                .map(GbMenu::getSortOrder)
                .filter(Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }

    private String requireTitle(String raw) {
        String title = clean(raw, MAX_TITLE, "名称");
        if (title == null) {
            throw BizException.badRequest("名称不能为空");
        }
        return title;
    }

    private String requirePath(String raw) {
        String path = clean(raw, MAX_PATH, "路由地址");
        if (path == null) {
            throw BizException.badRequest("路由地址不能为空");
        }
        if (!path.startsWith("/")) {
            throw BizException.badRequest("路由地址必须以 / 开头：" + path);
        }
        return path;
    }

    private String requireComponent(String raw) {
        String component = clean(raw, MAX_COMPONENT, "组件路径");
        if (component == null) {
            throw BizException.badRequest("组件路径不能为空");
        }
        if (!COMPONENT_PATTERN.matcher(component).matches()) {
            throw BizException.badRequest("组件路径应形如 views/UserView.vue：" + component);
        }
        return component;
    }

    private String requirePerms(String raw) {
        String perms = cleanPerms(raw);
        if (perms == null) {
            throw BizException.badRequest("权限标识不能为空");
        }
        return perms;
    }

    private String cleanPerms(String raw) {
        String perms = clean(raw, MAX_PERMS, "权限标识");
        if (perms == null) {
            return null;
        }
        if (!PERMS_PATTERN.matcher(perms).matches()) {
            throw BizException.badRequest("权限标识应形如 device:write：" + perms);
        }
        return perms;
    }

    /** 权限标识的模块前缀（{@code device:write → device}）。 */
    private static String moduleOf(String perms) {
        int i = perms == null ? -1 : perms.indexOf(':');
        return i > 0 ? perms.substring(0, i) : String.valueOf(perms);
    }

    /** 校验主键（路由 name / 分组编码 / 权限标识）。 */
    private String requireKey(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BizException.badRequest("menu_key 不能为空");
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw BizException.badRequest("标识只能是小写字母开头、字母/数字/中划线/冒号，最长 64 位：" + raw);
        }
        return key;
    }

    /** 类型只有 D / M / B，缺省按菜单处理（调用方最常改的是菜单）。 */
    private String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) {
            return GbMenu.TYPE_MENU;
        }
        String t = raw.trim().toUpperCase(Locale.ROOT);
        if (!Set.of(GbMenu.TYPE_DIRECTORY, GbMenu.TYPE_MENU, GbMenu.TYPE_BUTTON).contains(t)) {
            throw BizException.badRequest("menu_type 只能是 D（目录）/ M（菜单）/ B（按钮）");
        }
        return t;
    }

    /** 去空白；空串按「没填」处理；超长直接拒绝而不是静默截断。 */
    private String clean(String raw, int max, String label) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return null;
        }
        if (v.length() > max) {
            throw BizException.badRequest(label + "最长 " + max + " 个字符");
        }
        return v;
    }
}
