package mousemaster;

import java.util.regex.Pattern;

/**
 * Classifies configuration property keys/values for the right editor widget and provides
 * concise Chinese explanations for the settings table.
 */
public final class ConfigurationPropertySemantics {

    private static final Pattern SIMPLE_HEX_COLOR = Pattern.compile(
            "#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?");
    private static final Pattern INTEGER_VALUE = Pattern.compile("-?\\d+");
    private static final Pattern DECIMAL_VALUE = Pattern.compile("-?\\d+\\.\\d+|-?\\d*\\.\\d+");

    public enum ValueEditorKind {
        /** Space-separated key tokens (key-alias.*) */
        KEY_ALIAS_TOKENS,
        /** #RRGGBB or #AARRGGBB only */
        COLOR_HEX,
        /** true / false */
        BOOLEAN,
        /** Whole number */
        INTEGER,
        /** Fraction */
        DECIMAL,
        /** Long combo / macro / conditional expressions */
        TEXT_MULTILINE,
        /** Default single-line text */
        TEXT_LINE,
    }

    private ConfigurationPropertySemantics() {
    }

    public static ValueEditorKind classify(String key, String value) {
        if (key == null)
            return ValueEditorKind.TEXT_LINE;
        String k = key.strip();
        String v = value == null ? "" : value.strip();

        if (k.startsWith("key-alias."))
            return ValueEditorKind.KEY_ALIAS_TOKENS;

        if (isBooleanValue(v))
            return ValueEditorKind.BOOLEAN;

        if (looksLikeColorKey(k) && SIMPLE_HEX_COLOR.matcher(v).matches())
            return ValueEditorKind.COLOR_HEX;

        if (INTEGER_VALUE.matcher(v).matches())
            return ValueEditorKind.INTEGER;

        if (DECIMAL_VALUE.matcher(v).matches())
            return ValueEditorKind.DECIMAL;

        if (isMultilineCandidate(v))
            return ValueEditorKind.TEXT_MULTILINE;

        return ValueEditorKind.TEXT_LINE;
    }

    private static boolean isMultilineCandidate(String v) {
        if (v.length() > 180 || v.contains("\n"))
            return true;
        return v.length() > 96 && (v.contains("->") || v.contains("|") || v.contains("_{"));
    }

    private static boolean isBooleanValue(String v) {
        return "true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v);
    }

    private static boolean looksLikeColorKey(String k) {
        return k.endsWith(".color") || k.endsWith("-color")
                || k.contains(".color.") || k.contains("font-color")
                || k.contains("border-color") || k.contains("line-color");
    }

    /**
     * Human-readable Chinese hint; does not repeat file comments.
     */
    public static String zhDescriptionForKey(String key) {
        if (key == null || key.isBlank())
            return "";
        String k = key;

        String alias = KeyAliasUiDefinition.zhHintForPropertyKey(k);
        if (!alias.isEmpty())
            return alias;

        if (k.equals("hide-console"))
            return "是否在启动时显示控制台窗口（仍可用托盘菜单覆盖）。";
        if (k.equals("forced-active-keyboard-layout") || k.equals("configuration-keyboard-layout"))
            return "强制使用的键盘布局标识（与 keyboard-layouts.json 中一致）；不熟悉请留空或保持默认。";
        if (k.equals("log-level"))
            return "日志级别：如 TRACE、DEBUG、INFO、WARN、ERROR。";
        if (k.equals("log-to-file"))
            return "是否写入 mousemaster.log 文件。";
        if (k.contains("log-redact"))
            return "是否在日志中对按键类信息做脱敏。";

        if (k.startsWith("app-alias."))
            return "进程别名：右侧为若干可执行文件名（空格分隔），用于按应用匹配模式或条件。";

        if (k.contains("push-mode-to-history-stack"))
            return "切换模式时是否把当前模式压入历史栈（便于返回上一模式）。";

        if (k.contains(".to."))
            return "切换到其它模式的触发条件（组合键 / 宏语法，含 +、_|、#、-> 等）。";

        if (k.contains(".indicator.")) {
            if (k.contains(".enabled"))
                return "是否显示指示器（靠近光标的标记）。";
            if (k.contains("color"))
                return "指示器颜色（#RRGGBB）。可为单独一色，复杂条件色请用文本编辑。";
            return "指示器外观或行为。";
        }

        if (k.contains(".mouse.") || k.contains(".wheel.")) {
            if (k.contains("velocity"))
                return "指针或滚轮速度（像素/秒类）；支持条件分支表达式。";
            if (k.contains("acceleration"))
                return "加速度；支持按修饰键切换多条分支（→ 语法）。";
            return "鼠标移动或滚轮参数。";
        }

        if (k.contains(".grid.")) {
            if (k.contains("area-width-percent") || k.contains("area-height-percent"))
                return "网格区域占屏幕/窗口的宽高比例（0–1 小数）。";
            if (k.contains("row-count") || k.contains("column-count")
                    || k.contains("max-row-count") || k.contains("max-column-count"))
                return "网格行数/列数（整数）。";
            if (k.contains("line-visible"))
                return "是否绘制网格线（调试用或可视化）。";
            if (k.contains("line-color"))
                return "网格线颜色。";
            if (k.contains("area") && k.endsWith("area"))
                return "网格区域：如 active-screen、active-window、all-screens。";
            if (k.contains("synchronization"))
                return "鼠标与网格中心的同步策略（如 mouse-follows-grid-center）。";
            if (k.contains("inset"))
                return "内边距（像素），避免贴边过紧。";
            return "网格布局与显示相关。";
        }

        if (k.contains(".hint.")) {
            if (k.contains("type"))
                return "提示类型：如 grid、ui。";
            if (k.contains("grid-area"))
                return "提示网格作用范围。";
            if (k.contains("grid-cell-width") || k.contains("grid-cell-height"))
                return "提示格子的宽高（像素或小数）。";
            if (k.contains("layout-row-count") || k.contains("layout-column-count"))
                return "提示字母布局的行列数。";
            if (k.contains("selection-keys"))
                return "使用哪组 key-alias 名称作为提示选择键。";
            if (k.contains("font-name") || k.contains("font-size") || k.contains("font-weight"))
                return "提示文字的字体、字号或粗细。";
            if (k.contains("font-color") || k.contains("box-color") || k.contains(".color"))
                return "提示或边框颜色；支持条件着色时用多行/原文编辑。";
            if (k.contains("opacity"))
                return "透明度（0–1 小数）。";
            if (k.contains("select") || k.contains("undo") || k.contains("break-combo"))
                return "与按键组合相关的触发条件（组合语法）。";
            return "屏幕提示（hint）网格或 UI 提示的外观与行为。";
        }

        if (k.contains(".zoom."))
            return "局部放大比例或中心点策略。";

        if (k.contains(".timeout.") || k.contains("duration") || k.contains("millis"))
            return "超时或持续时间（毫秒整数）。";

        if (k.contains(".macro."))
            return "按键宏：按下/释放序列映射（箭头或浏览器前进后退等）。";

        if (k.contains(".noop."))
            return "吞掉（不传给应用）的按键条件，避免误触。";

        if (k.contains(".press.") || k.contains(".release.") || k.contains(".toggle."))
            return "鼠标按键与 key-alias 的绑定（组合条件语法）。";

        if (k.contains("start-move") || k.contains("stop-move") || k.contains("start-wheel")
                || k.contains("stop-wheel") || k.contains(".snap."))
            return "开始/停止移动或滚轮、贴边方向的组合键条件。";

        if (k.contains(".shrink-grid."))
            return "在网格模式下缩小某一侧网格的快捷键条件。";

        if (k.contains("hide-cursor"))
            return "在该模式下是否隐藏系统光标。";

        if (k.startsWith("key-alias."))
            return "物理键名别名（空格可多键）；见内置 key-alias 说明或文件顶部注释。";

        if (k.contains(".font-"))
            return "字体相关设置。";

        if (k.contains("-mode."))
            return "隶属于某运行模式（键名中的 xxx-mode 段）。";

        return "";
    }
}
