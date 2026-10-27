# Nexus UI 设计规范（iOS / Loon Style）

> 版本：2.0
> 适用：所有界面改动
> 所有权：UI 变更必须由设计规范 Review 通过（PR 模板勾选）
> 唯一事实来源：`app/src/main/java/com/github/mihomo/android/ui/theme/DesignTokens.kt`、`Color.kt`、`LoonTextStyles.kt`

本规范保证两件事：**整体风格统一**，以及**在 Android 上真实符合 iOS 的视觉与交互语义**。
它不是风格宣言，而是带编号、级别与验证方式的工程契约。

---

## 0. 一句话原则

**iOS 的视觉语言 + Android 的平台契约。**

- 视觉：连续曲率圆角、克制的色彩、原生级排版、少而精的触感、无 Material 水波纹。
- 交互：遵循 Android 的系统返回/预测性返回、TalkBack、字体缩放、48dp 热区、权限与通知模型。
- 冲突时：**可访问性与系统行为优先，其次才是 iOS 像素级还原**。

---

## 1. 规则格式与级别

每条规则使用 `UI-<域>-<编号>`，级别如下：

| 级别 | 含义 | 违反后果 |
| :--- | :--- | :--- |
| **MUST** | 必须遵守 | 阻断合并 |
| **SHOULD** | 默认遵守，例外需在 PR 说明理由 | Review 决定 |
| **MAY** | 可选风格建议 | 不阻断 |

新规则必须同时写明：**验证方式**（自动检查/人工走查）、**示例**、**例外**。
禁止只写“禁止/避免”而不说明如何验证。

---

## 2. 设计原则

1. **内容优先（Content-First）**：界面服务于数据与操作，不用装饰性容器挤占首屏。
2. **克制（Restraint）**：一屏只保留一个主操作；低频工具放进二级页、底部抽屉或上下文菜单。
3. **一致（Consistency）**：同一语义必须使用同一 token、同一组件、同一交互；不允许“这个页面看起来像 iOS，那个页面像 Material”。
4. **可访问（Accessible by Default）**：颜色、热区、语义、字体缩放、减少动效是默认要求，不是补丁。
5. **高效（Efficient）**：列表虚拟化、避免高频重组、JNI/IO 不阻塞主线程、息屏降载。

---

## 3. 设计 Tokens（唯一来源）

### 3.1 颜色

业务代码**禁止**直接写 `Color(0x...)`；通过 `Loon*` 语义访问器或 `LocalLoonColors` 读取。

| 语义 token | Light | Dark | 用途 |
| :--- | :--- | :--- | :--- |
| `bg` | `#F2F2F7` | `#000000` | 页面背景 |
| `card` | `#FFFFFF` | `#1C1C1E` | 分组卡片 / 抽屉 |
| `textPrimary` | `#000000` | `#FFFFFF` | 主文本 |
| `textSecondary` | `#6E6E73` | `#A1A1A6` | 必读次级信息（5.07:1 / 6.61:1） |
| `textMuted` | `#8E8E93` | `#8E8E93` | 占位符、装饰、disabled；**不得承载关键信息** |
| `successText` | `#1B7F3B` | `#30D158` | 成功/低延迟文本 |
| `warningText` | `#A85A00` | `#FFB340` | 警告/中等延迟文本 |
| `dangerText` | `#C62828` | `#FF6961` | 错误/超时文本 |
| `infoText` | `#0055CC` | `#409CFF` | 文本链接、可点操作 |
| `focusRing` | `#0055CC` | `#409CFF` | 键盘/D-pad 焦点环 |

**亮色填充仍使用 Apple 原色**（`IosBlue/IosGreen/IosOrange/RedDanger`），但**小字号文本必须使用 `*Text` 变体**。
原因：`#34C759` on white 仅 2.22:1、`#FF9500` 2.20:1、`#007AFF` 4.02:1，均不满足普通文本 4.5:1。

> 唯一例外：`CupertinoButtonStyle.Filled` 的主操作按钮保留 Apple 系统蓝 + 白色半粗标签（4.02:1），
> 作为 iOS 品牌控件；不得把该组合用于正文、说明或长文本。其余蓝色文本（链接、可点操作、选中标题）
> 使用 `infoText`（6.62:1）。

> `textMuted` 在浅色下为 3.26:1，只允许占位符/装饰/disabled；任何要读的信息用 `textSecondary`。

### 3.2 字体与排版

统一使用 `LoonTextStyles` 的语义角色，禁止在业务界面新建裸 `TextStyle(...)` 或手写 `fontSize`/`letterSpacing`。

| 角色 | 字号 / 字重 | iOS 字距 | 典型用途 |
| :--- | :--- | :--- | :--- |
| `largeTitle()` | 32 / Black | -0.45sp | 一级 Tab 标题 |
| `sectionTitle()` | 22 / Bold | -0.30sp | 页面区块标题 |
| `navTitle()` | 17 / SemiBold | -0.30sp | 导航栏、抽屉标题 |
| `dialogTitle()` | 17 / Bold | -0.30sp | 弹窗标题 |
| `cellTitle()` | 15.5 / Normal | -0.20sp | 列表主文本 |
| `body()` | 15 / Normal | -0.22sp | 正文 |
| `value()` | 14.5 / Normal | -0.18sp | 右侧值 |
| `subtitle()` | 12 / Normal | -0.12sp | 次级说明 |
| `caption()` | 11 / Normal | -0.12sp | 辅助信息 |
| `groupHeader()` | 12.5 / SemiBold | +0.3sp | 分组大写标题 |
| `button()` | 16 / SemiBold | -0.20sp | 主按钮/弹窗按钮 |
| `buttonSmall()` | 14 / SemiBold | -0.20sp | 紧凑按钮 |
| `actionLabel()` | 17 / Normal | -0.22sp | Action Sheet 项 |
| `segmentedLabel()` | 13 / Normal | -0.15sp | 分段控件 |
| `tab()` | 10.5 / Normal | -0.10sp | 底部导航 |
| `mono()` | 13 / Monospace | 0 | 日志、YAML/JS 编辑器、IP/端口 |

- 字距只在 `AppFont.IOS` 时生效；系统字体保持 `0sp`。
- “SF Pro / PingFang SC”依赖设备字体；绝大多数 Android 设备没有该字体，会回退到系统字体。该选项的可见差异主要是字距与 `includeFontPadding`，必须在真机上验证，不得宣称“内置 SF Pro”。
- 禁止在业务代码硬编码负字距；使用 `LoonTextStyles`。

### 3.3 间距、圆角、尺寸

使用 `LoonSpacing` / `LoonRadii` / `LoonSizes`：

- 间距：`2 / 4 / 8 / 12 / 16 / 20 / 24 / 32 dp`；禁止 9、13、14、18 等随意值。
- 字号：优先 `LoonTextStyles` 语义角色；确需一次性字号时使用 `LoonTypeScale`，禁止再写 `12.sp` 字面量。
- 圆角：`6（badge）/ 7（图标底）/ 10（控件）/ 14（卡片、弹窗）/ 16（底部抽屉）/ 28 / 32（底栏）`。
- 触摸热区：**最小 48×48dp**（`LoonSizes.touchTargetMin`）；视觉尺寸可以更小，但触摸/语义边界不得更小。
- 底部抽屉顶部圆角：**16dp**。
- 导航栏高度：**48dp**。

### 3.4 动效

使用 `LoonMotion`：`fast=150ms`、`normal=220ms`、`slow=300ms`、`pressScale=0.982`、`pressScaleStrong=0.97`。
禁止使用无 token 的 `tween(137)` 之类魔法值。

---

## 4. 组件规则

### 4.1 允许与禁止

**允许的 Material primitives**（只是底层实现，不呈现 Material 视觉）：
`MaterialTheme`、`Surface`、`Text`、`Icon`、`HorizontalDivider`、`ModalBottomSheet`、`SheetState`、`LocalTextStyle`。

**禁止的 opinionated Material 控件**：
`material3.AlertDialog`、`Button`、`TextButton`、`OutlinedButton`、`RadioButton`、`Switch`、`FloatingActionButton`，以及 `androidx.compose.foundation.shape.RoundedCornerShape`。

> 统一使用 `com.github.mihomo.android.ui.theme.RoundedCornerShape`（连续曲率）与 `Cupertino*` / `Loon*` 组件。

### 4.2 组件契约

每个组件必须具备：

- **状态矩阵**：default / pressed / focused / disabled / loading / error（如适用）。
- **语义**：角色（`Role.Button/Switch/Checkbox/Tab`）、状态、`contentDescription`、必要时 `paneTitle` / `liveRegion`。
- **尺寸来源**：全部来自 tokens，不允许组件内部写死配色与字号（含 `Cupertino*` 组件）。
- **预览**：Light/Dark × SYSTEM/IOS 字体 × zh/en/ru × 1.0/1.3/2.0 字体缩放。

### 4.3 图标

- 图标来源统一为 Material Icons（优先 Outlined/Rounded）或 `Canvas` 品牌图标；**不要在同一导航层级混用线性与填充风格**。
- 尺寸使用 `LoonSizes.icon`（20）/`iconSmall`（16）/`iconBadge`（30）；禁止随手 13/17/19/22。
- 仅图标的可点击控件必须有 `contentDescription`；纯装饰图标设 `null`。

### 4.4 组件清单

核心组件位于 `ui/components/CupertinoComponents.kt` 与 `LoonComponents.kt`：
`CupertinoCell`、`CupertinoGroupedSection`、`CupertinoSwitch`、`CupertinoSegmentedControl`、`CupertinoNavBar`、`CupertinoAlertDialog`、`CupertinoActionSheet`、`CupertinoModalBottomSheet`、`CupertinoToast`、`LoonFloatingBottomBar`、`LoonProxyItemCard`。

新增组件必须先加入该清单并说明与现有组件的差异，禁止复制粘贴出“第二个 Cell”。

---

## 5. 可访问性（MUST）

| ID | 规则 | 验证 |
| :--- | :--- | :--- |
| UI-A11Y-01 | 所有可点击/可切换元素触摸与语义边界 ≥ 48×48dp | 人工 + 截图/布局检查 |
| UI-A11Y-02 | 普通文本 ≥ 4.5:1，大文本 ≥ 3:1；状态色必须用 `*Text` token | 对比度脚本 + Review |
| UI-A11Y-03 | 自定义开关/复选/分段/底栏用 `toggleable(role=…)`、`selectable(role=…)`、`selectableGroup()` | 代码 Review + TalkBack |
| UI-A11Y-04 | 图标按钮必须有 `contentDescription`；装饰图标必须 `null` | Lint/Review |
| UI-A11Y-05 | 禁止用“无水波纹”代替焦点反馈；自定义按压必须有 `iosFocusRing` 或等价焦点态 | 键盘/D-pad 走查 |
| UI-A11Y-06 | Toast/短暂提示必须有 `liveRegion`；破坏性提示用 `Assertive` | TalkBack |
| UI-A11Y-07 | 支持字体缩放至 200%；含文本的卡片使用 `heightIn` 而非固定 `height` | 1.3/2.0 预览与真机 |
| UI-A11Y-08 | `ANIMATOR_DURATION_SCALE=0` 时必须减少/关闭弹簧、缩放、无限动画 | 开发者选项走查 |
| UI-A11Y-09 | 不得仅用颜色表达状态；延迟/在线状态需有文字或图标 | Review |


### 5.1 TalkBack 走查清单

- 每个开关读作“开关，已开启/已关闭”，而不是“按钮”。
- 每个复选项读作“复选框，已选中/未选中”。
- 每个仅图标按钮有可理解的名称。
- 弹窗/抽屉打开时焦点被限制，标题被朗读（`paneTitle`）。
- Toast 被朗读；失败提示不静默。
- 关闭动画/大字体下无截断、无重叠。

---

## 6. 布局与自适应

| ID | 规则 |
| :--- | :--- |
| UI-ADAPT-01 | 以 `screenWidthDp` 分档：compact < 600、medium 600–839、expanded ≥ 840；内容最大宽度 680dp |
| UI-ADAPT-02 | medium/expanded 使用两栏或居中内容，禁止把手机布局等比拉满 |
| UI-ADAPT-03 | 横屏、分屏、折叠屏必须可用；导航/底栏不遮挡内容 |
| UI-ADAPT-04 | 使用 `WindowInsets`/`imePadding`，适配 edge-to-edge、刘海与手势导航 |

主 Tab 内容在 medium/expanded 下必须通过 `LoonResponsiveContent(maxWidth = 680.dp)` 居中限宽；compact 下保持全宽。

---

## 7. 本地化与 RTL

| ID | 规则 |
| :--- | :--- |
| UI-I18N-01 | 所有用户可见字符串走 `AppStrings`；禁止在 UI 层写死中文/英文（日志与内部异常除外，且不得直接展示） |
| UI-I18N-02 | 占位符使用 `{name}`/`{n}` 形式；禁止字符串拼接；复数、日期、数字、单位使用本地化格式 |
| UI-I18N-03 | 三语（zh/en/ru）必须同时补齐；缺失键回退英文，不得回退中文 |
| UI-I18N-04 | RTL 下镜像 Chevron、进度、边距、边缘返回方向；左右语义改用 start/end |
| UI-I18N-05 | 文本膨胀 30% 不截断关键操作；按钮宽度使用内容自适应 |

边缘返回必须从 start 边缘起手，RTL 下从右侧向左滑；用户可见字符串必须位于 `com.github.mihomo.android.i18n`，非 UI 层不得依赖 `ui.theme`。

---

## 8. 动效与手势

| ID | 规则 |
| :--- | :--- |
| UI-MOTION-01 | 时长/曲线使用 `LoonMotion`；禁止无理由的 300ms+ 动画 |
| UI-MOTION-02 | 减少动效开启时：取消缩放/弹簧/循环动画，保留必要的透明度变化 |
| UI-MOTION-03 | 边缘返回必须支持 Android 系统返回与预测性返回；不得与横向 Pager 抢手势 |
| UI-HAPTIC-01 | 仅底部导航切换与 VPN 开关触发触感；其余 `lightTick/selection/impact` 是空实现，不应新增调用 |
| UI-HAPTIC-02 | 触感必须尊重系统设置；在无马达设备上静默降级 |

---

## 9. 交互模式

- **导航**：Tab 根页 + 二级页滑入；导航栏右侧操作 ≤ 2 个；搜索栏在内容区。
- **弹窗**：确认/破坏性操作 → `CupertinoAlertDialog`；多选项 → `CupertinoActionSheet`；表单/长内容 → `CupertinoModalBottomSheet`。
- **反馈**：轻提示 → `CupertinoToast`（带 liveRegion）；错误必须可读、可恢复，禁止只显示图标。
- **表单**：字段级校验 + 明确的保存/取消；禁止边输入边触发重网络/重内核操作。
- **破坏性操作**：必须二次确认或提供撤销；红色仅用于破坏性语义。
- **空/错/载入**：每种列表必须有 empty、error、loading 三态；超过 300ms 的加载显示骨架或指示器。

---

## 10. 性能预算

| ID | 预算 | 工具 |
| :--- | :--- | :--- |
| UI-PERF-01 | 冷启动 ≤ 1.5s（中端机）；首帧无可感知卡顿 | Macrobenchmark |
| UI-PERF-02 | 列表滚动丢帧 < 1%；无每帧 `animateColorAsState` 风暴 | Compose compiler metrics / Layout Inspector |
| UI-PERF-03 | JNI/文件 IO 不进入 composition 或主线程 | Review + StrictMode |
| UI-PERF-04 | 高频列表使用 `LazyColumn` + 稳定 key；缓冲区使用 `ArrayDeque` | Review |
| UI-PERF-05 | 息屏/后台停止轮询、动画、日志解析 | 手工 + 电量统计 |
| UI-PERF-06 | APK ≤ 40MB，内存峰值 ≤ 150MB | CI artifact / profiler |

---

## 11. 验证与治理

### 11.1 自动检查

```bash
./gradlew testDebugUnitTest detekt lintDebug checkUiTokens
```

以下检查必须全部通过：
- `detekt`：`ForbiddenImport` 禁止 Material 控件与 `foundation.RoundedCornerShape` 导入。
- `checkUiTokens`：禁止业务 UI 新增裸 `fontSize` / `lineHeight` / `letterSpacing` / `Color(0x…)` / 具名 Compose 颜色
  （`Color.White` 等）。该检查覆盖整个 `app/src/main/java` 源码树（仅排除 `ui/theme/`），
  并已挂到 `preBuild`，因此任何一次构建都会执行。
- `DesignTokenContrastTest`：核心文本 token 必须满足 WCAG AA 对比度。
- 组件变更必须在 `ui/components/DesignSystemPreview.kt` 的预览矩阵中覆盖 Light/Dark、SYSTEM/IOS、zh/en/ru 与 1.3 字体。

### 11.2 人工走查矩阵

| 维度 | 必测 |
| :--- | :--- |
| 主题 | Light / Dark（OLED 纯黑） |
| 字体 | SYSTEM / IOS，1.0 / 1.3 / 2.0 |
| 语言 | zh / en / ru |
| 方向 | LTR / RTL |
| 设备 | 手机竖屏 / 横屏 / 平板或分屏 |
| 辅助 | TalkBack、减少动效、无马达设备 |

### 11.3 例外流程

- 违反 MUST 必须在 PR 中写明：规则 ID、原因、影响范围、回滚条件、负责人。
- 连续两个版本保留的例外必须升级为规则修订或技术债任务。
