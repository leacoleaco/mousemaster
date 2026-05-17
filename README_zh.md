# mousemaster 中文使用手册

<a href="https://discord.gg/GSB6MaKb2R"><img src="https://img.shields.io/discord/854326924402622474?color=%235865F2&label=discord" alt="加入 Discord"></a>

> 一款 Windows 专属的键盘驱动鼠标控制工具，同时支持高级键盘自定义。
> 灵感来自 [warpd](https://github.com/rvaiya/warpd)、[mouseable](https://github.com/wirekang/mouseable/) 和 [kanata](https://github.com/jtroo/kanata)。

---

## 目录

- [简介](#简介)
- [安装](#安装)
- [快速上手](#快速上手)
- [操作模式详解](#操作模式详解)
  - [空闲模式 (idle-mode)](#空闲模式-idle-mode)
  - [普通模式 (normal-mode)](#普通模式-normal-mode)
  - [边缘模式 (edge-mode)](#边缘模式-edge-mode)
  - [网格模式 (grid-mode)](#网格模式-grid-mode)
  - [窗口模式 (window-mode)](#窗口模式-window-mode)
  - [提示模式一级 (hint1-mode)](#提示模式一级-hint1-mode)
  - [提示模式二级 (hint2-mode)](#提示模式二级-hint2-mode)
  - [屏幕选择模式 (screen-selection-mode)](#屏幕选择模式-screen-selection-mode)
  - [UI 提示模式 (ui-hint-mode)](#ui-提示模式-ui-hint-mode)
- [配置文件说明](#配置文件说明)
  - [按键别名](#按键别名)
  - [鼠标物理参数](#鼠标物理参数)
  - [组合键语法](#组合键语法)
  - [宏与键位重映射](#宏与键位重映射)
  - [模式属性动态变换](#模式属性动态变换)
- [高级功能](#高级功能)
  - [提示系统](#提示系统)
  - [网格系统](#网格系统)
  - [缩放功能](#缩放功能)
  - [光标指示器](#光标指示器)
  - [位置历史](#位置历史)
  - [应用专属模式](#应用专属模式)
  - [变量系统](#变量系统)
- [可用配置方案](#可用配置方案)
- [贡献与社区](#贡献与社区)

---

## 简介

**mousemaster** 是一款面向 Windows 平台的工具，让你完全通过键盘控制鼠标，并提供强大的键盘自定义能力。它基于"模式"（mode）架构，所有行为都定义在一个单一的配置文件中，保存后**实时生效，无需重启**。

主要能力：

- **连续鼠标移动**：用键盘按键控制光标移动，支持物理加速/减速
- **提示导航**：屏幕上显示带标签的网格提示框，键入标签直接跳转
- **UI 提示导航**：自动标记活跃窗口中的按钮、链接、文本框等控件
- **网格导航**：将屏幕分割为网格，通过逐步缩小范围精确定位
- **键位重映射**：定义组合键来重映射按键、发送按键序列或输入文本

---

## 安装

1. 从 [Release 页面](https://github.com/petoncle/mousemaster/releases/latest) 下载 **mousemaster.exe**（便携版可执行文件）。
2. 在同一页面选择并下载配置文件：
   - **neo-mousekeys-ijkl.properties**（**推荐**）：IJKL 方向键布局
   - **neo-mousekeys-wasd.properties**：WASD 方向键布局
   - **warpd.properties**：HJKL 布局（类 vim 风格）
   - **mouseable.properties**：另一种 HJKL 布局
   - **author.properties**：纯右手操作的 IJKL 布局
3. 将 `.exe` 与配置文件放在同一目录下。
4. 将配置文件重命名为 **mousemaster.properties**。
5. 运行 **mousemaster.exe**（建议以管理员身份运行，以确保覆盖层显示在最顶层）。

---

## 快速上手

以下以推荐配置 **neo-mousekeys-ijkl** 为例：

| 操作 | 按键 |
|------|------|
| 激活 mousemaster | `左Alt + E` 或 `左Alt + CapsLock` |
| 退出到空闲状态 | `Q` 或 `P` |
| 鼠标移动（上/左/下/右） | `I` / `J` / `K` / `L` |
| 鼠标左键 | `;` |
| 鼠标中键 | `右Shift` |
| 鼠标右键 | `'` |
| 进入网格模式 | `G` |
| 进入提示模式 | `F` |
| 进入 UI 提示模式 | `左Alt + F` |
| 选择屏幕（多显示器） | `C` |

---

## 操作模式详解

mousemaster 的所有行为都由"模式"（mode）驱动。每个模式都有独立的按键绑定、鼠标参数和视觉样式。唯一的内置模式名称是 `idle-mode`，其余所有模式均由用户在配置文件中定义。

---

### 空闲模式 (idle-mode)

**默认状态**，即程序启动后的初始模式。

- mousemaster 处于非激活状态，所有按键正常传递给操作系统
- 宏映射（如方向键重映射、Alt-Tab 集成）在此模式下依然有效
- **激活方式**：双击 `CapsLock`（两次间隔不超过 400ms）进入 `normal-mode`

---

### 普通模式 (normal-mode)

**主要鼠标控制状态**，激活 mousemaster 后的核心工作模式。

#### 鼠标移动

| 按键 | 功能 |
|------|------|
| `H` / `J` / `K` / `L` | 左 / 下 / 上 / 右 |
| 按住 `左Shift` | 慢速移动 |
| 按住 `CapsLock` | 超慢速移动 |
| 按住 `B` | 快速移动 |

#### 鼠标按键

| 按键 | 功能 |
|------|------|
| `R` 或 `G` | 左键点击 |
| `T` | 右键点击 |
| `0` | 中键点击 |
| `N` | 切换左键拖拽（按下/释放） |

#### 滚动

| 按键 | 功能 |
|------|------|
| `E` | 向上滚动 |
| `D` | 向下滚动 |
| `U` | 向左滚动 |
| `O` | 向右滚动 |

#### 模式切换

| 按键 | 目标模式 |
|------|----------|
| `F` | 进入提示模式 (hint1-mode) |
| `Y` | 进入网格模式 (grid-mode) |
| `C` | 进入屏幕选择模式 |
| `左Shift + G` | 进入窗口模式 (window-mode) |
| `右Alt + 方向` | 进入边缘模式 (edge-mode) |
| `Q` / `P` / `Space` | 退出到空闲模式 |

---

### 边缘模式 (edge-mode)

**快速将鼠标吸附到屏幕边缘。**

- 使用一个覆盖屏幕 80%×95% 的隐形网格
- 按方向键将鼠标跳转到对应屏幕边缘
- **进入**：在 normal-mode 中按住 `右Alt` 后按方向键
- **退出**：松开 `右Alt`

---

### 网格模式 (grid-mode)

**通过逐步缩小网格区域精确定位。**

工作流程：

1. 进入 grid-mode → 屏幕被分割为 2×2 网格，显示红色网格线，鼠标跳至中心
2. 按方向键 → 网格向该方向缩小一半（如按 `H` 则保留左半部分）
3. 重复操作，直到网格覆盖目标区域
4. 退出后鼠标停留在网格中心

| 按键 | 功能 |
|------|------|
| `H`/`J`/`K`/`L` | 向左/下/上/右缩小网格 |
| `G` 或 `Esc` | 退出网格模式，返回 normal-mode |

---

### 窗口模式 (window-mode)

**在当前活跃窗口内进行边缘导航。**

- 类似 edge-mode，但范围限定在当前窗口内
- 顶部留有 15px 的标题栏间距
- 按 `G` 将鼠标移至窗口中心
- **进入**：在 normal-mode 中按住 `左Shift` 后按 `G`
- **退出**：松开 `左Shift`

---

### 提示模式一级 (hint1-mode)

**屏幕上布满带标签的提示框，键入标签跳转鼠标。**

工作流程：

1. 按 `F` 进入 → 屏幕被均匀覆盖提示框（默认 6行×5列）
2. 键入提示标签的第一个字符 → 不匹配的提示框变暗
3. 继续键入完整标签 → 鼠标立即跳转至该位置
4. 按 `Backspace` 撤销上一次按键

提示键（默认）：`Q W E R A S D F Z X C V`

| 按键 | 功能 |
|------|------|
| 标签字符 | 选择对应提示位置 |
| `Esc` / `Backspace` | 退出提示模式 |
| 按住 `左Shift` + 提示键 | 进入二级提示模式 |

---

### 提示模式二级 (hint2-mode)

**在一级提示选定位置附近进行高精度微调。**

- 显示以上次选定提示为中心的小范围 3×8 提示网格
- 屏幕以该位置为中心放大 5 倍，便于精确操作
- 选择提示后返回 normal-mode

| 按键 | 功能 |
|------|------|
| 标签字符 | 精确跳转 |
| `Esc` / `Backspace` | 返回一级提示模式 |

---

### 屏幕选择模式 (screen-selection-mode)

**多显示器环境下快速切换鼠标所在屏幕。**

- 每块屏幕上显示一个大号提示标签（字体 120pt）
- 键入对应字母，鼠标跳转至该屏幕
- **进入**：在 normal-mode 中按 `C`
- **退出**：按 `C`、`Esc`、`Backspace` 或选择一块屏幕后自动退出

---

### UI 提示模式 (ui-hint-mode)

**自动识别并标记活跃窗口中的交互元素。**

- 使用 Windows UI Automation API 检测按钮、链接、文本框、复选框等控件
- 每个控件上显示一个带标签的黄色提示框
- 键入标签后鼠标跳转至该控件位置
- **进入**：在 normal-mode 中按 `左Shift + F`
- **退出**：按 `Esc`、`Backspace` 或选择提示后自动退出

---

## 配置文件说明

所有配置均在单一文件 `mousemaster.properties` 中完成，**保存后立即生效**，无需重启程序。

### 按键别名

可以为一组按键定义别名，方便复用：

```properties
key-alias.up=k
key-alias.down=j
key-alias.left=h
key-alias.right=l
key-alias.leftbutton=r g
key-alias.hint=f
```

### 鼠标物理参数

控制鼠标移动的速度和加速度：

```properties
# 初始速度（像素/秒）
normal-mode.mouse.initial-velocity=200
# 最大速度
normal-mode.mouse.max-velocity=2200
# 加速度
normal-mode.mouse.acceleration=1000
# 减速时间（停止按键后减速到0的时间，毫秒）
normal-mode.mouse.deceleration-time=100
```

### 组合键语法

组合键（combo）是触发命令的按键模式，语法非常丰富：

| 语法 | 含义 |
|------|------|
| `+key` | 按下按键（被拦截，不传给系统） |
| `#key` | 按下按键（传给操作系统） |
| `-key` | 释放按键 |
| `key` | 点击（按下后释放） |
| `{+a +b}` | 任意顺序和弦 |
| `+key-100` | 按下后等待至少 100ms |
| `+key-0-250` | 按下后下一个事件须在 250ms 内 |
| `_{keys}` | 前置条件：这些键必须被按住 |
| `^{keys}` | 前置条件：这些键必须未被按住 |
| `combo1 \| combo2` | 两种组合键均可触发 |

**常见高级模式示例：**

```properties
# 双击 CapsLock 进入 normal-mode
idle-mode.to.normal-mode=capslock-0-400 capslock

# 按住 Shift 减速（属性动态变换，不切换模式）
normal-mode.mouse.max-velocity=2200 | _{slow} -> 350

# 单击 F 进入 hint 模式，单击 Esc 退出
normal-mode.to.hint1-mode=f
hint1-mode.to.normal-mode=esc
```

### 宏与键位重映射

将按键组合映射到输出序列：

```properties
# 将 v 映射为 Ctrl+V（粘贴）
normal-mode.macro.paste=+v -> +leftctrl +v -v -leftctrl

# 左Alt + HJKL 发送实际的方向键
idle-mode.macro.leftalt-h=+leftalt +h -> +leftalt +left -h -left
idle-mode.macro.leftalt-j=+leftalt +j -> +leftalt +down -j -down
idle-mode.macro.leftalt-k=+leftalt +k -> +leftalt +up   -k -up
idle-mode.macro.leftalt-l=+leftalt +l -> +leftalt +right -l -right
```

宏输出语法：

| 输出语法 | 含义 |
|----------|------|
| `+key` / `-key` | 向操作系统发送按下/释放 |
| `#key` / `~key` | 向 mousemaster 自身发送按下/释放（触发其他组合键） |
| `'text'` | 直接输入文字（与键盘布局无关） |
| `wait-N` | 等待 N 毫秒 |

### 模式属性动态变换

无需切换模式，即可根据当前按住的键动态改变属性：

```properties
# 默认最大速度 2200，按住 slow 键时降为 350，按住 fast 键时升为 4500
normal-mode.mouse.max-velocity=2200 | _{slow} -> 350 | _{fast} -> 4500

# 继承另一模式的设置
slow-mode.indicator=normal-mode.indicator
```

---

## 高级功能

### 提示系统

提示（hint）共有三种类型：

**1. 网格提示（grid）**
- 在屏幕/窗口上均匀覆盖带标签的提示框
- 支持多级选择（一级粗定位 + 二级精调）
- 可配置单元格大小、字体、颜色、阴影、动画
- 支持按屏幕分辨率覆盖配置（如 `.3840x2160` 后缀）

**2. UI 提示（ui）**
- 使用 Windows UI Automation API 枚举可交互控件
- 每个控件一个提示框，大小自适应标签文字

**3. 位置历史提示（position-history）**
- 显示所有已保存的鼠标位置（最多 16 个）
- 配合 `save-position` 命令使用

### 网格系统

网格操作支持以下命令：

| 命令 | 说明 |
|------|------|
| `shrink-grid.*` | 向指定方向缩小网格（上/下/左/右） |
| `move-grid.*` | 移动整个网格而不改变大小 |
| `snap.*` | 将鼠标吸附到最近的网格边缘 |
| `move-to-grid-center` | 将鼠标移至网格中心 |

网格同步模式：

- `mouse-follows-grid-center`：鼠标随网格中心移动
- `grid-center-follows-mouse`：网格中心跟随鼠标
- `mouse-and-grid-center-unsynchronized`：完全独立

### 缩放功能

任意模式均可开启屏幕放大：

```properties
hint2-mode.zoom.percent=5.0
hint2-mode.zoom.center=last-selected-hint
hint2-mode.zoom.animation-enabled=true
hint2-mode.zoom.animation-easing=smoothstep
```

缩放中心选项：`screen-center`、`mouse`、`last-selected-hint`

### 光标指示器

在光标旁显示一个可自定义的多边形图标，随模式和鼠标状态改变：

```properties
# 边数（3=三角形，4=正方形，30+=圆形）
normal-mode.indicator.idle.edge-count=3
# 颜色、大小、透明度
normal-mode.indicator.idle.fill-color=#FF5733
normal-mode.indicator.idle.size=14
# 鼠标移动时显示不同颜色
normal-mode.indicator.move.fill-color=#33FF57
```

可配置的状态：`idle`、`move`、`wheel`、`mouse-press`、`left-mouse-press`、`unhandled-key-press`

### 位置历史

保存常用的鼠标位置，随时通过提示跳回：

```properties
# 保存当前位置
normal-mode.combo.save=+s -> save-position
# 通过提示跳回已保存的位置
normal-mode.to.position-history-mode=+h
```

### 应用专属模式

根据当前活跃的应用程序自动切换模式：

```properties
# 定义浏览器应用别名
app-alias.browserapp=firefox.exe chrome.exe msedge.exe

# 在浏览器激活时自动切换到浏览器专属模式
idle-mode.to.browser-mode=_{browserapp}
browser-mode.to.idle-mode=^{browserapp}
```

### 变量系统

跨模式切换时保留的布尔标志，用于动态修改行为：

```properties
# 设置变量
mode.combo.enable-fast=+f -> set-variable fast
# 清除变量
mode.combo.disable-fast=-f -> unset-variable fast
# 根据变量动态改变属性
normal-mode.mouse.max-velocity=2200 | _{fast} -> 4500
```

---

## 可用配置方案

以下是官方提供的几套预置配置方案：

| 配置文件 | 说明 |
|----------|------|
| `neo-mousekeys-ijkl.properties` | **推荐**，使用 IJKL 作为方向键 |
| `neo-mousekeys-wasd.properties` | 使用 WASD 作为方向键（游戏玩家友好） |
| `warpd.properties` | 使用 HJKL 布局，类 vim 风格 |
| `mouseable.properties` | 另一种 HJKL 布局 |
| `author.properties` | 纯右手操作的 IJKL 布局 |

每套方案均有详细文档，位于 `configuration/` 目录下。

---

## 核心架构说明

| 组件 | 功能 |
|------|------|
| `Mousemaster` | 主循环，协调所有子系统，监视配置文件变化 |
| `Mode` | 命名状态，包含组合键、鼠标设置和视觉样式 |
| `ComboWatcher` | 追踪键盘事件并匹配组合键模式 |
| `CommandRunner` | 执行匹配到的命令 |
| `ModeController` | 管理模式切换和模式历史栈 |
| `HintManager` | 管理提示覆盖层 |
| `GridManager` | 管理网格覆盖层和缩小/吸附逻辑 |
| `ZoomManager` | 处理屏幕放大 |
| `MacroPlayer` | 执行宏输出序列 |
| `WindowsOverlay` | 基于 Qt 的透明覆盖窗口 |
| `WindowsPlatform` | 底层 Windows 钩子（键盘/鼠标） |
| `ActiveAppFinder` | 检测当前活跃的应用程序 |

---

## 贡献与社区

- **分享配置**：如果你有好的配置方案，欢迎在 [GitHub Issue](https://github.com/petoncle/mousemaster/issues) 或 [Discord](https://discord.gg/GSB6MaKb2R) 分享。
- **跨平台支持**：目前仅支持 Windows，覆盖层部分已初步实现跨平台。如有兴趣参与 macOS/Linux 移植工作，欢迎联系。
- **赞助**：如果你喜欢这个工具，可以通过 [Ko-fi](https://ko-fi.com/petoncle) 支持作者。
