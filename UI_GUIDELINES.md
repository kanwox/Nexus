# Nexus UI 设计规范与工程约束指南 (iOS HIG / Loon Style)

本项目（Nexus）是一套运行于 Android 系统的 iOS / Loon 拟真风格代理应用。为确保全应用在视觉、微交互、排版、手势及能效表现上严格对齐 **Apple iOS Human Interface Guidelines (HIG)**，避免出现风格割裂或 Android Material 惯性设计，特制定本规范。所有新功能开发与已有界面重构必须严格遵循本指南。

---

## 1. 核心设计原则 (Core Design Principles)

1. **纯正 iOS 物理微交互与手势**：
   - **禁止墨水涟漪 (No Ripple)**：全局禁用 Material Design 水波纹（`NoIndication`）。可点击卡片、单元格与按钮统一使用 `iosPressable`（微弹性缩放默认 `0.982f`）或 `iosHighlightPressable`。
   - **精准触感反馈 (Taptic Engine Discipline)**：
     - **离散动作触发触感**：按钮点击、开关切换、列表项选择、分段控件切换、清空输入、长按拖拽激活等离散交互，统一使用 `rememberIosHaptic()`（`lightTick`、`selection`、`mediumImpact`）。
     - **连续滚动回弹保持静默**：所有可滚动容器（`verticalScroll`、`LazyColumn`）必须附加 `.bounceOverscroll()` 实现 iOS `UIScrollView` 橡皮筋阻尼视觉回弹，但**严禁在普通滚动触顶/触底回弹时触发震动**（遵循 iOS HIG 原生规范，防止触觉疲劳与线性马达无效耗电）。
   - **iOS 边缘侧滑返回 (Interactive Pop Gesture)**：二级子页面统一支持屏幕左边缘（`32.dp` 响应区）水平右滑跟手返回与弹簧回弹。

2. **严禁混用 Android / Material 原生视觉控件**：
   - ❌ **严禁使用**：`androidx.compose.material3.AlertDialog`、`Button`、`TextButton`、`OutlinedButton`、`RadioButton`、`Switch`、`FloatingActionButton`、Android 原生 `Toast.makeText`。
   - ❌ **严禁使用**：`androidx.compose.foundation.shape.RoundedCornerShape`（普通圆弧圆角）。
   - ✅ **统一使用**：`com.github.mihomo.android.ui.theme.RoundedCornerShape`（iOS 连续曲率超椭圆圆角）、`CupertinoAlertDialog`、`CupertinoModalBottomSheet`、`CupertinoButton`、`CupertinoSearchBar`、`CupertinoTextField`、`CupertinoSwitch`、`CupertinoSegmentedControl`、`CupertinoToast`。

3. **内容遵从与视觉克制 (Deference & Restraint)**：
   - **内容优先 (Content-First)**：界面应服务于内容本身而非装饰。常驻界面的控件与辅助容器应保持轻量，最大限度将屏幕有效面积留给核心数据流与主任务操作，避免常驻大块低频组件挤占首屏空间。
   - **高信噪比 (High Signal-to-Noise Ratio)**：严格控制界面的信息密度与视觉层级。剔除缺乏实际参考价值的冗余数据与无区分度的重复装饰性图元，确保主要文本具备完整的展示与阅读空间。
   - **渐进披露 (Progressive Disclosure)**：单一视图聚焦单一核心心智。低频调试工具、次级参数及高阶操作应通过按需触发展现（如二级详情、`CupertinoModalBottomSheet` 或上下文菜单），而非在主视觉流中并列平铺。

---

## 2. 基础设计规范 Tokens (Design Tokens)

### 2.1 色彩系统 (Color Tokens)

| Token 变量 | 浅色模式 (Light) | 深色模式 (Dark / OLED) | 说明 |
| :--- | :--- | :--- | :--- |
| `LoonBg` | `#F2F2F7` | `#000000` (纯黑) | 页面全局背景 (iOS System Grouped Background) |
| `LoonCard` | `#FFFFFF` | `#1C1C1E` | 分组卡片、弹窗与底色 (Secondary System Grouped) |
| `LoonCardBorder` | `0x1A000000` (发丝线) | `0x2EFFFFFF` (发丝线) | `0.5.dp` 极细卡片描边 |
| `LoonSeparator` | `0x263C3C43` | `0x38545458` | `0.5.dp` 列表发丝分割线 |
| `LoonTertiaryBg` | `#E5E5EA` | `#2C2C2E` | 搜索栏、文本输入框、分段控件槽位底色 |
| `LoonTextPrimary` | `#000000` | `#FFFFFF` | 主标题、正文标题文字 |
| `LoonTextSecondary`| `#8E8E93` | `#8E8E93` | 次级说明标签、小号提示文字 (Secondary Label) |
| `LoonTextMuted` | `#C7C7CC` | `#636366` | 占位提示文字 (Placeholder / Tertiary Label) |
| `IosBlue` | `#007AFF` | `#007AFF` | 默认主要交互操作色 (System Blue) |
| `IosGreen` | `#34C759` | `#34C759` | 成功、低延迟、连接就绪状态 (System Green) |
| `IosOrange` | `#FF9500` | `#FF9500` | 警告、中等延迟、规则分流状态 (System Orange) |
| `RedDanger` (`IosRed`)| `#FF3B30` | `#FF3B30` | 破坏性危险操作、超时或报错状态 (System Red) |

### 2.2 字体与排版规范 (Typography & Font System)

1. **双字体选项 (`AppFont`)**：
   - `AppFont.SYSTEM`（**默认**）：**系统默认 (Roboto / Noto Sans)**，采用中性字距（`0.sp`），去除 Material3 默认的 `+0.5.sp` 松散字距。
   - `AppFont.IOS`：**SF Pro / 苹方 (PingFang SC)**，遵循 Apple 官方中西文排版标准，采用紧凑光学字距（大标题 `-0.45.sp`、导航标题 `-0.30.sp`、正文 `-0.22.sp`、说明标签 `-0.12.sp`）并关闭额外字体内边距（`includeFontPadding = false`）。
2. **排版统一性铁律**：
   - **禁止新建裸 `TextStyle(...)`**：在 `BasicTextField` 等底层输入控件中，必须使用 `LocalTextStyle.current.copy(...)` 保留全局 `fontFamily` 与 `letterSpacing`，严禁直接构造 `TextStyle(...)` 导致输入框与普通文本字体不一致。
   - **禁止硬编码负字距**：业务界面中的标题与单元格严禁直接写死 `letterSpacing = (-0.6).sp` 等仅针对 iOS 字体的参数，应交由全局 `Typography` 或根据 `LocalAppFont.current == AppFont.IOS` 动态适配。
   - **等宽字体 (`FontFamily.Monospace`) 使用边界**：仅限在 JS/YAML 代码编辑器、核心实时日志流、IP/端口技术字段中使用等宽字体，普通列表标题与计数标签一律使用全局当前字体。

### 2.3 iOS 连续曲率圆角与尺寸 (Continuous Corner & Sizing)

*   **强制使用连续曲率圆角 (`IosContinuousCornerShape`)**：
    - 全局必须导入 `com.github.mihomo.android.ui.theme.RoundedCornerShape`（底层为 `IosContinuousCornerShape`，采用 Apple `CALayer.cornerCurve = .continuous` 三段三次贝塞尔 $G^2$ 曲率连续超椭圆算法），**严禁导入** `androidx.compose.foundation.shape.RoundedCornerShape`。
*   **标准组件圆角与尺寸规格**：
    - **分组卡片 / 模态弹窗 (`CupertinoGroupedSection` / `CupertinoAlertDialog`)**：`RoundedCornerShape(14.dp)`
    - **模态底部抽屉 (`CupertinoModalBottomSheet`)**：顶部圆角 `RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)`
    - **搜索框 / 文本输入框 (`CupertinoSearchBar` / `CupertinoTextField`)**：`RoundedCornerShape(10.dp)`，搜索栏高度 `36~38.dp`
    - **操作按钮 (`CupertinoButton`)**：`RoundedCornerShape(10.dp)`，常规高度 `44.dp`
    - **图标超椭圆徽章 (`CupertinoCell` 左侧图标底座)**：`RoundedCornerShape(7.dp)`，尺寸 `30.dp × 30.dp`（内部图标 `17.dp`）
    - **iOS 物理开关 (`CupertinoSwitch`)**：尺寸 `51.dp × 31.dp`，滑块 `27.dp` 圆形
    - **状态胶囊 (`CircleShape`)**：当必须使用胶囊标签或灵动岛提示时，采用完全圆角 `CircleShape`。❌ **严禁在列表行中堆砌 `2~6.dp` 的 Web/Material 式小圆角彩色底块标签**（列表次级元数据应直接通过字号、字重与语义文字颜色表达）。
*   **图标尺寸与触摸热区 (Icons & Touch Targets)**：
    - **导航栏操作项**：图标视觉尺寸 `20.dp`，文字动作字号 `16.sp`，触摸热区最小 `40.dp × 36~40.dp`。
    - **图标风格去 Android 化**：避免使用生硬的 Material 特有隐喻图标，优先使用贴近 Apple **SF Symbols** 笔触特征的圆润符号或纯文字操作按钮。

---

## 3. 标准组件使用规范 (Component Patterns)

### 1) 导航栏 (`CupertinoNavBar` & 一级大标题)
- **一级主标签页（策略、设置）**：采用 iOS Large Title 风格（`32.sp`，`FontWeight.Bold`）。
- **二级子页面**：统一采用标准居中标题导航栏 `CupertinoNavBar`：
```kotlin
CupertinoNavBar(
    title = "页面标题",
    showBackButton = true,
    onBack = onBack,
    actions = {
        // 右侧操作项（最多 1~2 个，优先纯文字动作按钮）
    }
)
```
- **导航栏操作项约束**：
  - 右侧操作项原则上**不得超过 1 个（极特殊场景最多 2 个）**，严禁并排堆叠 3~4 个小图标破坏主标题居中平衡。
  - 高频动作（如“保存”、“完成”、“编辑”、“清空”）优先采用 `IosBlue`（或危险操作 `RedDanger`）的 `16.sp` `FontWeight.SemiBold` 纯文字按钮。
  - **搜索栏不折叠为顶栏放大镜**：`CupertinoSearchBar` 必须直接呈现在导航栏下方的内容区域，严禁在右上角放放大镜点击展开搜索框。

### 2) 分组列表与长列表虚拟化 (`CupertinoGroupedSection` & `cupertinoGroupedItems`)
- **静态短列表（如设置项）**：使用 `CupertinoGroupedSection` + `CupertinoCell` + `CupertinoIndentedDivider()`：
```kotlin
CupertinoGroupedSection(
    title = "网络设置",
    footer = "分组底部的辅助说明文案"
) {
    CupertinoCell(
        title = "分应用代理",
        icon = Icons.Default.Apps,
        iconBg = IosBlue,
        showChevron = true,
        onClick = { /* 跳转详情 */ }
    )
    CupertinoIndentedDivider()
    CupertinoCell(
        title = "开机自启",
        icon = Icons.Default.PowerSettingsNew,
        iconBg = IosGreen,
        trailing = {
            CupertinoSwitch(checked = enabled, onCheckedChange = { ... })
        }
    )
}
```
- **动态长列表（如日志、请求、DNS、规则、应用列表）**：必须使用 `LazyColumn` 配合 `cupertinoGroupedItems(...)` 扩展函数，自动为首尾项裁剪连续圆角并绘制内缩分割线，严禁在 `verticalScroll` 的 `Column` 中 `forEach` 渲染上百个节点。

### 3) 弹窗、抽屉与顶部轻提示 (`CupertinoAlertDialog` / `CupertinoModalBottomSheet` / `CupertinoToast`)
- **确认与输入弹窗**：统一使用 `CupertinoAlertDialog`（毛玻璃质感卡片 + `0.5.dp` 十字发丝分割线）。
- **多选操作与详情抽屉**：统一使用 `CupertinoModalBottomSheet`。
- **轻量状态反馈**：统一调用 `CupertinoToast.show("提示文案", isDestructive = false)`（顶部灵动岛胶囊悬浮动画），严禁调用系统 `Toast`。

---

## 4. 能效与性能规范 (Energy Efficiency & Performance Rules)

1. **息屏与后台零无效唤醒 (Zero Idle Wakeups)**：
   - 息屏状态下（`Intent.ACTION_SCREEN_OFF` / `LogRepository.isScreenOff == true`），必须挂起非必要的 JNI 流量查询、日志 JSON/正则解析与 `StateFlow` 列表拷贝，杜绝后台网络包频繁唤醒 JVM 与 GC。
   - 应用退至后台时（`MihomoVpnService.isUiInForeground == false`），系统通知栏流量刷新必须节流至 `>= 3000ms` 并携带 `.setSilent(true)`，降低跨进程 SystemUI Binder IPC 开销。
   - 主界面代理组在线状态轮询仅在用户处于「仪表盘」或「策略」页且无二级子页面遮挡时激活。
2. **高频列表与编辑器渲染优化**：
   - 高频滚动的列表单元格（如 `LoonProxyItemCard`）内禁止分配多个 `animateColorAsState` 或无限循环动画协程。
   - 日志与请求记录等高频首部插入队列统一使用 `ArrayDeque`（$O(1)$ `addFirst` / `removeLast`），禁止对大容量 `ArrayList` 执行 `add(0, item)`。
   - 代码编辑器行号栏采用单 `Text` 合并渲染，`VisualTransformation` 必须对未变化的源文本做高亮缓存。

---

## 5. 样式与质量验收清单 (Acceptance Checklist)

每次新增或修改界面后，请逐项核对：
- [ ] 圆角是否全部导入自 `com.github.mihomo.android.ui.theme.RoundedCornerShape`（iOS 连续曲率圆角），且未混入 `androidx.compose.foundation.shape.RoundedCornerShape`？
- [ ] 是否杜绝了任何 `androidx.compose.material3.Button`、`AlertDialog`、`Switch`、`RadioButton` 或系统原生 `Toast`？
- [ ] 文本与 `BasicTextField` 是否统一继承全局字体设置（支持「系统默认」与「SF Pro / 苹方 (PingFang SC)」无缝切换），无硬编码割裂字距？
- [ ] 背景色与分割线是否全部引用 `LoonBg`、`LoonCard`、`LoonSeparator`，确保 Light / OLED Dark 双模式自适应？
- [ ] 滚动容器是否添加了 `.bounceOverscroll()`，且保持滚动回弹静默无震动、离散点击有 `rememberIosHaptic()` 反馈？
- [ ] 列表次级信息是否遵循“纯排版优先”，杜绝了 `2~6.dp` 小圆角矩形彩色底块？
- [ ] 导航栏右侧操作项是否控制在 1~2 个以内，且搜索栏位于内容区而非折叠为顶栏图标？
- [ ] 后台任务、JNI 回调或定时器是否遵循了息屏挂起与后台节流规范，无异常耗电隐患？
