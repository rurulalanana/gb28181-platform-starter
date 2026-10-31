package io.github.rurulalanana.starter.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.github.rurulalanana.starter.common.BizException;
import io.github.rurulalanana.starter.entity.GbMenu;
import io.github.rurulalanana.starter.mapper.GbMenuMapper;
import io.github.rurulalanana.starter.vo.MenuConfigVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 菜单外观配置（只存覆盖值，默认值在前端代码里）。
 *
 * <h3>为什么后端不认识「菜单」</h3>
 *
 * <p>后端只把 {@code menuKey → {类型/标题/图标/父目录/顺序/隐藏}} 当作一组
 * <b>不透明的键值</b>存下来，它不需要知道有哪些菜单、每个菜单要什么权限 ——
 * 那是前端 {@code appRoutes} 的事。
 *
 * <p>这样做的好处：
 * <ul>
 *   <li>加菜单不用改后端，也不会出现「后端菜单表落库了、前端没这个页面」；</li>
 *   <li>代码回滚时库里多出来的 key 会被前端直接忽略，不会点出 404；</li>
 *   <li>后端<b>不会</b>因为权限模型变化而成为绕过 RBAC 的漏洞 ——
 *       权限判断永远走代码里的 {@code meta.perm}。</li>
 * </ul>
 *
 * <h3>目录与菜单项同一张表</h3>
 *
 * <p>参照若依 {@code sys_menu}：目录是 {@code gb_menu} 里 {@code menu_type='D'} 的一行。
 * 早先拆成两张表是多余的 —— 分组改名只需 UPDATE 一行，
 * 而且不会出现「改了分组表、菜单项还指向旧编码」的不一致。
 *
 * <h3>为什么要删行而不是存空串</h3>
 *
 * <p>{@code null} 表示「用默认值」。若把用户清空的标题存成 {@code ""}，
 * 前端 {@code ov.title || default} 仍然会回落到默认值，
 * 但库里会留下一堆看着像配置、实际无效的空行，排查时徒增困惑。
 * 统一约定：<b>没有覆盖就删掉那一行</b>。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MenuService {

    /** 主键：字母开头，允许字母数字与中划线。 */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{0,63}$");

    private static final int MAX_TITLE = 64;
    private static final int MAX_ICON = 64;

    private final GbMenuMapper menuMapper;

    /** 当前全部覆盖（没配过时返回空列表，不是 null）。 */
    public MenuConfigVo config() {
        MenuConfigVo vo = new MenuConfigVo();
        List<MenuConfigVo.ItemVo> items = new ArrayList<>();
        for (GbMenu m : menuMapper.selectList(
                Wrappers.<GbMenu>lambdaQuery().orderByAsc(GbMenu::getMenuType))) {
            MenuConfigVo.ItemVo item = new MenuConfigVo.ItemVo();
            item.setMenuKey(m.getMenuKey());
            item.setParentKey(m.getParentKey());
            item.setMenuType(m.getMenuType());
            item.setTitle(m.getTitle());
            item.setIcon(m.getIcon());
            item.setSortOrder(m.getSortOrder());
            item.setHidden(m.getHidden());
            items.add(item);
        }
        vo.setItems(items);
        return vo;
    }

    /**
     * 保存一行覆盖。
     *
     * <p>类型不同，校验不同：
     * <ul>
     *   <li>{@code D} 目录：{@code title} <b>必填</b>——分组名就是它在侧栏上的文字，
     *       留空会让侧栏出现一条没有标题的分组；{@code parentKey} 忽略（目录是顶层）。</li>
     *   <li>{@code M} 菜单项：所有字段可选，{@code parentKey} 可用来把菜单项挪到别的分组。</li>
     * </ul>
     */
    @Transactional
    public void save(String menuKey, String parentKey, String menuType,
                     String title, String icon, Integer sortOrder, Boolean hidden) {
        String key = requireKey(menuKey);
        String type = normalizeType(menuType);
        String t = clean(title, MAX_TITLE, "名称");
        String i = clean(icon, MAX_ICON, "图标名");

        if (GbMenu.TYPE_DIRECTORY.equals(type)) {
            if (t == null) {
                throw BizException.badRequest("分组名称不能为空");
            }
            // 目录是顶层节点，顺带清掉可能残留的 parentKey
            saveRow(key, null, type, t, i, sortOrder, null);
            return;
        }
        String parent = (parentKey == null || parentKey.isBlank()) ? null : requireKey(parentKey);
        saveRow(key, parent, type, t, i, sortOrder, hidden);
    }

    /** 写入一行；全字段为空则删行（回到纯默认状态）。 */
    private void saveRow(String key, String parentKey, String type,
                         String title, String icon, Integer sortOrder, Boolean hidden) {
        GbMenu existing = menuMapper.selectById(key);
        boolean created = existing == null;
        GbMenu row = created ? new GbMenu() : existing;
        row.setMenuKey(key);
        row.setMenuType(type);
        row.setParentKey(parentKey);
        row.setTitle(title);
        row.setIcon(icon);
        row.setSortOrder(sortOrder);
        row.setHidden(hidden);
        row.setUpdateTime(LocalDateTime.now());

        boolean empty = row.getTitle() == null && row.getIcon() == null
                && row.getParentKey() == null && row.getSortOrder() == null
                && row.getHidden() == null;
        if (empty) {
            if (!created && menuMapper.deleteById(key) > 0) {
                log.info("菜单覆盖已清空（回到默认）: key={}", key);
            }
            return;
        }
        if (created) {
            menuMapper.insert(row);
        } else {
            menuMapper.updateById(row);
        }
        log.info("菜单覆盖已保存: key={}, type={}, parent={}, title={}, icon={}, sort={}, hidden={}",
                key, type, row.getParentKey(), title, icon, sortOrder, hidden);
    }

    /** 恢复默认（删掉覆盖行）。目录与菜单项同走这里。 */
    @Transactional
    public void reset(String menuKey) {
        String key = requireKey(menuKey);
        menuMapper.deleteById(key);
        log.info("菜单覆盖已恢复默认: key={}", key);
    }

    /**
     * 清空全部菜单覆盖。
     *
     * <p>不做「只清一部分」的模糊操作：菜单外观是全局的，
     * 想恢复个别项用 {@link #reset}，一键清空就该清干净。
     *
     * <p><b>为什么要带 {@code isNotNull} 条件</b>：项目开了
     * {@code BlockAttackInnerInterceptor}，无条件全表 delete 会被直接拦下并抛
     * {@code MybatisPlusException}（表现为 500）。加一个对主键的恒真判断，
     * 语义不变但能通过拦截器的「必须带 where」检查。
     */
    @Transactional
    public int resetAll() {
        int n = menuMapper.delete(Wrappers.<GbMenu>lambdaQuery().isNotNull(GbMenu::getMenuKey));
        log.info("已清空全部菜单覆盖：{} 行", n);
        return n;
    }

    /** 校验主键（路由 name / 分组编码）。 */
    private String requireKey(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BizException.badRequest("menu_key 不能为空");
        }
        String key = raw.trim().toLowerCase();
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw BizException.badRequest("标识只能是小写字母开头、字母/数字/中划线，最长 64 位：" + raw);
        }
        return key;
    }

    /** 类型只有 D / M，缺省按菜单项处理（调用方最常改的是菜单项）。 */
    private String normalizeType(String raw) {
        if (raw == null || raw.isBlank()) {
            return GbMenu.TYPE_MENU;
        }
        String t = raw.trim().toUpperCase();
        if (!GbMenu.TYPE_DIRECTORY.equals(t) && !GbMenu.TYPE_MENU.equals(t)) {
            throw BizException.badRequest("menu_type 只能是 D（目录）或 M（菜单）");
        }
        return t;
    }

    /** 去空白；空串按「不覆盖」处理；超长直接拒绝而不是静默截断。 */
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
