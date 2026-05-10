package mousemaster;

import io.qt.core.Qt;
import io.qt.widgets.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Qt dialog listing <strong>every</strong> {@code key=value} entry in the properties file (with
 * search and grouping). Comment lines and blank lines are preserved on save. Chinese “说明” is
 * shown for {@code key-alias.*} keys where {@link KeyAliasUiDefinition} defines help text.
 */
public class VisualSettingsWindow extends QDialog {

    private final Path configurationPath;
    private final QLabel pathLabel = new QLabel();
    private final QLabel statsLabel = new QLabel();
    private final QLineEdit filterEdit = new QLineEdit();
    private final QComboBox categoryCombo = new QComboBox();
    private final QTableWidget table = new QTableWidget();
    private List<ConfigurationFileDocument.Line> document;
    private List<ConfigurationFileDocument.PropertyEntry> propertyRows = new ArrayList<>();
    private boolean configurationFileLoaded;

    public VisualSettingsWindow(Path configurationPath) {
        this.configurationPath = configurationPath;
        setWindowTitle("Mousemaster 配置");
        setMinimumSize(960, 640);
        resize(1024, 720);

        QVBoxLayout root = new QVBoxLayout(this);

        pathLabel.setWordWrap(true);
        pathLabel.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse);
        root.addWidget(pathLabel);

        QLabel intro = new QLabel(
                "下表列出当前配置文件中的全部配置项。注释行与空行会原样写回。\n"
                        + "「值」列按类型自动切换：纯 # 颜色为取色器 + 文本；true/false 为下拉框；整数 / 小数为数字框；"
                        + "key-alias 为输入框 +「选择键位」勾选常见键名；特别长的组合表达式为多行文本（保存时合并为单行）。\n"
                        + "「说明」列为内置中文 + 文件内紧贴该行的注释摘要。保存为 UTF-8；主程序一般会监视配置文件并重新加载。");
        intro.setWordWrap(true);
        root.addWidget(intro);

        QHBoxLayout filterRow = new QHBoxLayout();
        filterRow.addWidget(new QLabel("筛选键或值："));
        filterEdit.setPlaceholderText("输入片段，匹配配置项名称或值");
        filterRow.addWidget(filterEdit, 1);
        filterRow.addWidget(new QLabel("分组："));
        categoryCombo.addItem("（全部）");
        filterRow.addWidget(categoryCombo);
        root.addLayout(filterRow);

        statsLabel.setStyleSheet("color: palette(mid);");
        root.addWidget(statsLabel);

        table.setColumnCount(3);
        table.setHorizontalHeaderLabels(List.of("配置项", "值", "说明"));
        table.horizontalHeader().setSectionResizeMode(0, QHeaderView.ResizeMode.ResizeToContents);
        table.horizontalHeader().setSectionResizeMode(1, QHeaderView.ResizeMode.Stretch);
        table.horizontalHeader().setSectionResizeMode(2, QHeaderView.ResizeMode.Stretch);
        table.setAlternatingRowColors(true);
        // Values use embedded QLineEdit (see rebuildTable); items alone were not reliably editable
        // with row selection + flags on some platforms.
        table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectItems);
        table.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection);
        table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers);
        root.addWidget(table, 1);

        QHBoxLayout buttons = new QHBoxLayout();
        QPushButton saveBtn = new QPushButton("保存并应用");
        QPushButton reloadBtn = new QPushButton("从文件重新载入");
        QPushButton closeBtn = new QPushButton("关闭");
        saveBtn.setDefault(true);
        buttons.addWidget(saveBtn);
        buttons.addWidget(reloadBtn);
        buttons.addStretch();
        buttons.addWidget(closeBtn);
        root.addLayout(buttons);

        filterEdit.textChanged.connect(s -> applyRowFilters());
        categoryCombo.currentTextChanged.connect(s -> applyRowFilters());
        saveBtn.clicked.connect(this::save);
        reloadBtn.clicked.connect(this::reloadFromDisk);
        closeBtn.clicked.connect(this::reject);

        reloadFromDisk();
    }

    private void reloadFromDisk() {
        try {
            pathLabel.setText("配置文件：" + configurationPath.toAbsolutePath());
            document = ConfigurationFileDocument.parse(configurationPath);
            configurationFileLoaded = true;
            rebuildCategoryCombo();
            rebuildTable();
            applyRowFilters();
        } catch (Exception e) {
            QMessageBox.critical(this, "读取失败", e.getMessage());
            configurationFileLoaded = false;
            document = null;
            propertyRows.clear();
            table.setRowCount(0);
            categoryCombo.clear();
            categoryCombo.addItem("（全部）");
            statsLabel.setText("");
        }
    }

    private void rebuildCategoryCombo() {
        String previous = categoryCombo.currentText();
        categoryCombo.blockSignals(true);
        categoryCombo.clear();
        categoryCombo.addItem("（全部）");
        TreeSet<String> categories = new TreeSet<>();
        for (ConfigurationFileDocument.PropertyEntry e : ConfigurationFileDocument.propertyEntriesInOrder(
                document))
            categories.add(ConfigurationFileDocument.categoryOfPropertyKey(e.key));
        for (String c : categories)
            categoryCombo.addItem(c);
        categoryCombo.blockSignals(false);
        int ix = categoryCombo.findText(previous);
        if (ix >= 0)
            categoryCombo.setCurrentIndex(ix);
    }

    private void rebuildTable() {
        propertyRows = new ArrayList<>();
        List<String> hints = new ArrayList<>();
        for (int i = 0; i < document.size(); i++) {
            ConfigurationFileDocument.Line line = document.get(i);
            if (!(line instanceof ConfigurationFileDocument.PropertyLine pl))
                continue;
            propertyRows.add(pl.entry());
            String hint = mergeHints(ConfigurationPropertySemantics.zhDescriptionForKey(pl.entry().key),
                    ConfigurationFileDocument.hintFromPrecedingComments(document, i));
            hints.add(hint);
        }
        statsLabel.setText("共 " + propertyRows.size() + " 条配置项（不含注释与空行）");

        table.blockSignals(true);
        table.setRowCount(propertyRows.size());
        for (int r = 0; r < propertyRows.size(); r++) {
            ConfigurationFileDocument.PropertyEntry e = propertyRows.get(r);
            QTableWidgetItem keyItem = new QTableWidgetItem(e.key);
            keyItem.setFlags(Qt.ItemFlag.ItemIsSelectable, Qt.ItemFlag.ItemIsEnabled);
            table.setItem(r, 0, keyItem);

            ConfigurationPropertySemantics.ValueEditorKind kind =
                    ConfigurationPropertySemantics.classify(e.key, e.value);
            QWidget editor = PropertyValueEditors.createEditor(e, kind, table);
            table.setCellWidget(r, 1, editor);

            String hint = hints.get(r);
            QTableWidgetItem hintItem = new QTableWidgetItem(hint);
            hintItem.setFlags(Qt.ItemFlag.ItemIsSelectable, Qt.ItemFlag.ItemIsEnabled);
            if (!hint.isEmpty())
                hintItem.setToolTip(hint);
            table.setItem(r, 2, hintItem);
        }
        table.resizeRowsToContents();
        table.blockSignals(false);
    }

    private void applyRowFilters() {
        String cat = categoryCombo.currentText();
        String q = filterEdit.text().strip().toLowerCase();
        for (int r = 0; r < table.rowCount(); r++) {
            if (r >= propertyRows.size()) {
                table.setRowHidden(r, true);
                continue;
            }
            ConfigurationFileDocument.PropertyEntry e = propertyRows.get(r);
            boolean okCat = "（全部）".equals(cat) ||
                            ConfigurationFileDocument.categoryOfPropertyKey(e.key).equals(cat);
            boolean okSearch = q.isEmpty() || e.key.toLowerCase().contains(q) ||
                               e.value.toLowerCase().contains(q);
            table.setRowHidden(r, !(okCat && okSearch));
        }
    }

    private void syncVisibleValuesFromTable() {
        for (int r = 0; r < propertyRows.size(); r++) {
            QWidget w = table.cellWidget(r, 1);
            PropertyValueEditors.flushFromWidget(w);
        }
    }

    private static String mergeHints(String builtin, String fromFile) {
        String a = builtin == null ? "" : builtin.strip();
        String b = fromFile == null ? "" : fromFile.strip();
        if (!a.isEmpty() && !b.isEmpty())
            return a + "\n——文件注释： " + b;
        if (!a.isEmpty())
            return a;
        return b;
    }

    private void save() {
        if (!configurationFileLoaded || document == null) {
            QMessageBox.warning(this, "无法保存", "配置文件尚未成功读取，请检查路径后点击「从文件重新载入」。");
            return;
        }
        syncVisibleValuesFromTable();
        List<String> dups = ConfigurationFileDocument.findDuplicateKeys(document);
        if (!dups.isEmpty()) {
            QMessageBox.warning(this, "无法保存",
                    "配置文件存在重复的键（主程序会解析失败），请先改正后再保存：\n" +
                    String.join(", ", dups));
            return;
        }
        for (ConfigurationFileDocument.PropertyEntry e : propertyRows) {
            if (e.key.strip().isEmpty() || e.value.strip().isEmpty()) {
                QMessageBox.warning(this, "无法保存",
                        "键和值都不能为空，请检查配置项：" + e.key);
                return;
            }
        }
        try {
            ConfigurationFileDocument.write(configurationPath, document);
            QMessageBox.information(this, "已保存", "已写入配置文件。正在运行的 Mousemaster 会在检测到文件变化后重新加载。");
        } catch (Exception e) {
            QMessageBox.critical(this, "保存失败", e.getMessage());
        }
    }
}
