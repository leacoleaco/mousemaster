package mousemaster;

/**
 * One editable key-alias row in the visual settings UI. {@link #zhTitle} and
 * {@link #zhDescription} are shown to the user; {@link #aliasName} is the
 * configuration alias name (the segment after {@code key-alias.}).
 */
public record KeyAliasUiDefinition(
        String aliasName,
        boolean layoutSpecific,
        String zhTitle,
        String zhDescription
) {

    /**
     * Built-in key aliases from the stock {@code mousemaster.properties}, with Chinese copy.
     * Order matches the default file grouping for easier mental mapping.
     */
    public static final KeyAliasUiDefinition[] DEFINITIONS = {
            new KeyAliasUiDefinition("enablemod", true,
                    "进入正常模式 — 修饰键",
                    "按住此修饰键，再按下方「启用键」可从空闲模式进入正常模式（与 idle-mode.to.normal-mode 组合一致）。"),
            new KeyAliasUiDefinition("enablekey", true,
                    "进入正常模式 — 启用键",
                    "可与「启用修饰键」组合；可填多个键，空格分隔（例如 f、e、capslock）。"),
            new KeyAliasUiDefinition("exit", true,
                    "退出到空闲模式",
                    "从正常模式退回空闲模式的按键，可多个。"),
            new KeyAliasUiDefinition("clickthendisable", true,
                    "点击后退出",
                    "触发一次点击后关闭正常模式等（常为 space）。"),

            new KeyAliasUiDefinition("up", true, "向上移动", "控制鼠标向上移动的按键。"),
            new KeyAliasUiDefinition("down", true, "向下移动", "控制鼠标向下移动的按键。"),
            new KeyAliasUiDefinition("left", true, "向左移动", "控制鼠标向左移动的按键。"),
            new KeyAliasUiDefinition("right", true, "向右移动", "控制鼠标向右移动的按键。"),
            new KeyAliasUiDefinition("edge", true,
                    "贴边模式",
                    "进入沿屏幕边缘移动/吸附模式的按键。"),

            new KeyAliasUiDefinition("wheelup", true, "滚轮向上", "向上滚动的按键。"),
            new KeyAliasUiDefinition("wheeldown", true, "滚轮向下", "向下滚动的按键。"),
            new KeyAliasUiDefinition("wheelleft", true, "滚轮向左", "向左滚动的按键。"),
            new KeyAliasUiDefinition("wheelright", true, "滚轮向右", "向右滚动的按键。"),

            new KeyAliasUiDefinition("leftbutton", true,
                    "左键（按下/点按）",
                    "模拟鼠标左键操作的按键，可填多个。"),
            new KeyAliasUiDefinition("rightbutton", true, "右键", "模拟鼠标右键。"),
            new KeyAliasUiDefinition("middlebutton", true, "中键", "模拟鼠标中键。"),
            new KeyAliasUiDefinition("toggleleft", true,
                    "切换左键状态",
                    "切换左键按下/释放的按键。"),

            new KeyAliasUiDefinition("fast", true, "加速", "加快指针/滚轮速度的修饰键。"),
            new KeyAliasUiDefinition("slow", true, "减速", "减慢速度的按键。"),
            new KeyAliasUiDefinition("superslow", true,
                    "极慢",
                    "更慢速度的修饰键（常与 slow 组合）。"),

            new KeyAliasUiDefinition("grid", true,
                    "网格模式",
                    "打开全屏网格以快速定位的按键。"),
            new KeyAliasUiDefinition("windowmod", true,
                    "窗口网格 — 修饰键",
                    "按住时配合「网格」键可进入「活动窗口」网格模式。"),
            new KeyAliasUiDefinition("hint", true,
                    "提示模式（字母网格）",
                    "打开屏幕字母提示网格的主键；详细行为见配置中 hint 相关模式。"),
            new KeyAliasUiDefinition("hint2mod", true,
                    "第二层提示 — 修饰键",
                    "在提示选择时按住可进入更细的第二次提示网格。"),
            new KeyAliasUiDefinition("uihintmod", true,
                    "UI 提示 — 修饰键",
                    "与「提示」键组合，进入界面元素（UI）提示模式。"),
            new KeyAliasUiDefinition("screenselection", true,
                    "跨屏选择",
                    "进入跨屏幕区域选择的按键。"),

            new KeyAliasUiDefinition("navigateback", true,
                    "浏览器后退",
                    "触发后退宏所绑定的按键（见 normal-mode.macro.navigateback）。"),
            new KeyAliasUiDefinition("navigateforward", true,
                    "浏览器前进",
                    "触发前进宏所绑定的按键。"),

            new KeyAliasUiDefinition("hint1key", true,
                    "提示网格 — 第一组选择键",
                    "第一次提示网格使用的多个字母/符号键，空格分隔。"),
            new KeyAliasUiDefinition("hint2key", true,
                    "提示网格 — 第二组选择键",
                    "第二次提示网格使用的键集合。"),
            new KeyAliasUiDefinition("extendedhint1key", true,
                    "提示网格 — 扩展第一组",
                    "用于结束 hint1 等场景的扩展键集合。"),
            new KeyAliasUiDefinition("extendedhint2key", true,
                    "提示网格 — 扩展第二组",
                    "hint2 模式下用于选择的扩展键集合。"),
            new KeyAliasUiDefinition("hintscreenselectionkey", true,
                    "跨屏选择 — 提示键",
                    "屏幕选择模式中用于提示选择的键。"),

            new KeyAliasUiDefinition("arrowmod", true,
                    "方向键重映射 — 修饰键",
                    "与物理方向键组合，用于配置中的箭头宏 remapping。"),
            new KeyAliasUiDefinition("arrowkey", true,
                    "方向键重映射 — 方向键",
                    "一般为 up down left right（虚拟键名），空格分隔。"),

            new KeyAliasUiDefinition("arrowextramod", false,
                    "方向键 — 额外修饰键",
                    "可与方向键一起参与的修饰键列表；该项通常不带键盘布局后缀，对所有布局生效。"),
            new KeyAliasUiDefinition("arrowhandledkey", false,
                    "方向宏 — 内部键别名",
                    "供箭头重映射宏引用的键名列表，除非你知道在改什么，否则保持默认即可。"),
    };

    /**
     * Short Chinese hint for the “说明” column; only {@code key-alias.*} keys are documented here.
     */
    public static String zhHintForPropertyKey(String propertyKey) {
        if (propertyKey == null || !propertyKey.startsWith("key-alias."))
            return "";
        String rest = propertyKey.substring("key-alias.".length());
        int dot = rest.indexOf('.');
        String aliasName = dot < 0 ? rest : rest.substring(0, dot);
        for (KeyAliasUiDefinition d : DEFINITIONS) {
            if (d.aliasName().equals(aliasName))
                return d.zhTitle() + " — " + d.zhDescription();
        }
        return "";
    }
}
