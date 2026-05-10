package mousemaster;

import io.qt.core.Qt;
import io.qt.gui.QColor;
import io.qt.widgets.QColorDialog;
import io.qt.widgets.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds the value column widget for each configuration row and reads values back before save.
 */
public final class PropertyValueEditors {

    private static final Pattern SIMPLE_HEX = Pattern.compile("#[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?");

    private PropertyValueEditors() {
    }

    /** Tokens in the key-alias picker (modifier / letter / digit names used in configs). */
    private static final String[] PICKABLE_KEY_TOKENS;

    static {
        Set<String> s = new LinkedHashSet<>();
        s.addAll(Arrays.asList(
                "none", "leftalt", "rightalt", "leftctrl", "rightctrl", "leftshift", "rightshift",
                "leftwin", "rightwin", "space", "tab", "esc", "enter", "backspace", "capslock",
                "delete", "insert", "home", "end", "pageup", "pagedown",
                "up", "down", "left", "right",
                "f1", "f2", "f3", "f4", "f5", "f6", "f7", "f8", "f9", "f10", "f11", "f12",
                "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
                "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m",
                "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
                ";", ",", ".", "/", "`", "[", "]", "'", "-", "="));
        for (KeyAliasUiDefinition d : KeyAliasUiDefinition.DEFINITIONS)
            s.add(d.aliasName());
        List<String> list = new ArrayList<>(s);
        list.sort(String.CASE_INSENSITIVE_ORDER);
        PICKABLE_KEY_TOKENS = list.toArray(new String[0]);
    }

    public static QWidget createEditor(ConfigurationFileDocument.PropertyEntry entry,
                                       ConfigurationPropertySemantics.ValueEditorKind kind,
                                       QWidget parent) {
        return new ValueCellHost(parent, entry, kind);
    }

    public static void flushFromWidget(QWidget widget) {
        if (widget instanceof ValueCellHost v)
            v.flushToEntry();
    }

    public static final class ValueCellHost extends QWidget {
        private final ConfigurationFileDocument.PropertyEntry entry;
        private final ConfigurationPropertySemantics.ValueEditorKind kind;
        private QLineEdit lineEdit;
        private QPlainTextEdit plainEdit;
        private QComboBox boolCombo;
        private QSpinBox spinBox;
        private QDoubleSpinBox doubleSpin;

        ValueCellHost(QWidget parent, ConfigurationFileDocument.PropertyEntry entry,
                      ConfigurationPropertySemantics.ValueEditorKind kind) {
            super(parent);
            this.entry = entry;
            this.kind = kind;
            QHBoxLayout row = new QHBoxLayout(this);
            row.setContentsMargins(2, 2, 2, 2);
            switch (kind) {
                case BOOLEAN -> buildBoolean(row);
                case INTEGER -> buildInteger(row);
                case DECIMAL -> buildDecimal(row);
                case COLOR_HEX -> buildColor(row);
                case KEY_ALIAS_TOKENS -> buildKeyAlias(row);
                case TEXT_MULTILINE -> buildMultiline(row);
                case TEXT_LINE -> buildLine(row);
            }
        }

        private void buildBoolean(QHBoxLayout row) {
            boolCombo = new QComboBox();
            boolCombo.addItems(List.of("true", "false"));
            boolCombo.setCurrentText(entry.value.strip().equalsIgnoreCase("false") ? "false" : "true");
            boolCombo.currentTextChanged.connect(t -> entry.value = t);
            row.addWidget(boolCombo, 1);
        }

        private void buildInteger(QHBoxLayout row) {
            spinBox = new QSpinBox();
            spinBox.setRange(-2_000_000_000, 2_000_000_000);
            try {
                spinBox.setValue(Integer.parseInt(entry.value.strip()));
            } catch (NumberFormatException e) {
                spinBox.setValue(0);
            }
            spinBox.valueChanged.connect(v -> entry.value = String.valueOf(v));
            row.addWidget(spinBox, 1);
        }

        private void buildDecimal(QHBoxLayout row) {
            doubleSpin = new QDoubleSpinBox();
            doubleSpin.setDecimals(8);
            doubleSpin.setRange(-1e9, 1e9);
            try {
                doubleSpin.setValue(Double.parseDouble(entry.value.strip()));
            } catch (NumberFormatException e) {
                doubleSpin.setValue(0);
            }
            doubleSpin.valueChanged.connect(v -> entry.value = formatDecimal(v));
            row.addWidget(doubleSpin, 1);
        }

        private void buildColor(QHBoxLayout row) {
            QLabel swatch = new QLabel();
            swatch.setFixedSize(22, 22);
            swatch.setFrameShape(QFrame.Shape.StyledPanel);
            lineEdit = new QLineEdit(entry.value);
            lineEdit.setPlaceholderText("#RRGGBB");
            QLabel swatchRef = swatch;
            Runnable updateSwatch = () -> {
                QColor c = parseHexColor(lineEdit.text().strip());
                if (c != null)
                    swatchRef.setStyleSheet("background-color: " + c.name(QColor.NameFormat.HexRgb));
                else
                    swatchRef.setStyleSheet("");
            };
            lineEdit.textChanged.connect(t -> {
                entry.value = t;
                updateSwatch.run();
            });
            updateSwatch.run();
            QPushButton colorButton = new QPushButton("选色…");
            colorButton.clicked.connect(() -> {
                QColor initial = parseHexColor(lineEdit.text().strip());
                if (initial == null)
                    initial = new QColor(0x20, 0x4e, 0x8a);
                QColor chosen = QColorDialog.getColor(initial, this, "选择颜色");
                if (chosen != null && chosen.isValid()) {
                    lineEdit.setText(chosen.name(QColor.NameFormat.HexRgb));
                    entry.value = lineEdit.text();
                    updateSwatch.run();
                }
            });
            row.addWidget(swatch);
            row.addWidget(colorButton);
            row.addWidget(lineEdit, 1);
        }

        private void buildKeyAlias(QHBoxLayout row) {
            lineEdit = new QLineEdit(entry.value);
            lineEdit.setPlaceholderText("空格分隔键名，或「选择键位…」勾选添加");
            lineEdit.textChanged.connect(t -> entry.value = t);
            QPushButton keysButton = new QPushButton("选择键位…");
            keysButton.clicked.connect(() -> KeyPickerDialog.editKeys(this, lineEdit));
            row.addWidget(lineEdit, 1);
            row.addWidget(keysButton);
        }

        private void buildLine(QHBoxLayout row) {
            lineEdit = new QLineEdit(entry.value);
            lineEdit.setClearButtonEnabled(true);
            lineEdit.textChanged.connect(t -> entry.value = t);
            row.addWidget(lineEdit, 1);
        }

        private void buildMultiline(QHBoxLayout row) {
            plainEdit = new QPlainTextEdit(entry.value);
            plainEdit.setPlaceholderText("组合条件、宏等长文本；保存时换行会合并为空格。");
            plainEdit.setMinimumHeight(72);
            plainEdit.setMaximumHeight(160);
            plainEdit.textChanged.connect(() -> entry.value = collapseWhitespace(plainEdit.toPlainText()));
            row.addWidget(plainEdit, 1);
        }

        void flushToEntry() {
            switch (kind) {
                case BOOLEAN -> entry.value = boolCombo.currentText();
                case INTEGER -> entry.value = String.valueOf(spinBox.value());
                case DECIMAL -> entry.value = formatDecimal(doubleSpin.value());
                case COLOR_HEX, KEY_ALIAS_TOKENS, TEXT_LINE -> {
                    if (lineEdit != null)
                        entry.value = lineEdit.text().strip();
                }
                case TEXT_MULTILINE -> {
                    if (plainEdit != null)
                        entry.value = collapseWhitespace(plainEdit.toPlainText());
                }
            }
        }
    }

    private static String collapseWhitespace(String raw) {
        return raw.replace('\n', ' ').replaceAll("\\s+", " ").strip();
    }

    private static String formatDecimal(double v) {
        if (!Double.isFinite(v))
            return "0";
        return BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    private static QColor parseHexColor(String t) {
        if (t == null || !SIMPLE_HEX.matcher(t.strip()).matches())
            return null;
        QColor c = new QColor(t.strip());
        if (c.isValid())
            return c;
        return null;
    }

    private static final class KeyPickerDialog extends QDialog {
        private final QListWidget list;

        private KeyPickerDialog(QWidget parent, Set<String> initialChecked) {
            super(parent);
            setWindowTitle("选择键名（勾选后加入）");
            setMinimumSize(440, 500);
            QVBoxLayout root = new QVBoxLayout(this);
            root.addWidget(new QLabel("勾选常用键名，确定后与当前「值」合并（去重）。也可继续在输入框里手输。"));
            list = new QListWidget();
            for (String token : PICKABLE_KEY_TOKENS) {
                QListWidgetItem it = new QListWidgetItem(token);
                it.setFlags(Qt.ItemFlag.ItemIsUserCheckable,
                        Qt.ItemFlag.ItemIsEnabled,
                        Qt.ItemFlag.ItemIsSelectable);
                it.setCheckState(initialChecked.contains(token) ? Qt.CheckState.Checked : Qt.CheckState.Unchecked);
                list.addItem(it);
            }
            root.addWidget(list, 1);
            QHBoxLayout btns = new QHBoxLayout();
            QPushButton ok = new QPushButton("确定");
            QPushButton cancel = new QPushButton("取消");
            btns.addStretch();
            btns.addWidget(ok);
            btns.addWidget(cancel);
            root.addLayout(btns);
            ok.clicked.connect(this::accept);
            cancel.clicked.connect(this::reject);
        }

        private Set<String> checkedInList() {
            Set<String> out = new LinkedHashSet<>();
            for (int i = 0; i < list.count(); i++) {
                QListWidgetItem it = list.item(i);
                if (it.checkState() == Qt.CheckState.Checked)
                    out.add(it.text());
            }
            return out;
        }

        private static Set<String> parseTokens(String line) {
            Set<String> s = new LinkedHashSet<>();
            for (String p : line.strip().split("\\s+")) {
                if (!p.isEmpty())
                    s.add(p);
            }
            return s;
        }

        static void editKeys(QWidget parent, QLineEdit target) {
            Set<String> current = parseTokens(target.text());
            KeyPickerDialog dlg = new KeyPickerDialog(parent, current);
            if (dlg.exec() != QDialog.DialogCode.Accepted.value())
                return;
            Set<String> merged = new LinkedHashSet<>(current);
            merged.addAll(dlg.checkedInList());
            target.setText(String.join(" ", merged));
        }
    }
}
