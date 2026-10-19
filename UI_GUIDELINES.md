# Nexus UI 设计规范与组件约束指南 (iOS / Loon Style)

本项目（Nexus）是一套运行于 Android 系统的 iOS / Loon 拟真风格代理应用。为避免后续开发中出现风格混乱、东拼西凑（如误用 Material Design 控件）的现象，特制定本规范。所有新功能界面与已有界面重构必须严格遵循本指南。

---

## 1. 核心设计原则

1. **纯正 iOS 拟物微交互**：
   - **禁止墨水涟漪**：一律使用 `iosPressable` 进行微弹性缩放（`0.982f`），禁止使用 Material Design 的圆形水波纹动画。
   - **原生触感反馈**：所有交互（点击按钮、切换开关、选择列表、清空输入）统一使用 `rememberIosHaptic()` 注入触感（`lightTick`、`selection`、`mediumImpact`）。
   - **橡皮筋阻尼回弹**：所有可滚动的界面容器（`verticalScroll`、`LazyColumn`）必须附加 `.bounceOverscroll()` 修饰符。

2. **严禁混用 Material 控件**：
   - ❌ **严禁使用**：`androidx.compose.material3.AlertDialog`、`Button`、`TextButton`、`OutlinedButton`、`RadioButton`、`Switch`。
   - ✅ **统一使用**：`CupertinoAlertDialog`、`CupertinoButton`、`CupertinoSearchBar`、`CupertinoTextField`、`CupertinoSwitch`、`CupertinoSelectDialog`。

3. **内容遵从与视觉克制 (Deference & Restraint)**：
   - **内容优先 (Content-First)**：界面应服务于内容本身而非装饰。常驻界面的控件与辅助容器应保持轻量，最大限度将屏幕有效面积留给核心数据流与主任务操作，避免常驻大块低频组件挤占首屏空间。
   - **高信噪比 (High Signal-to-Noise Ratio)**：严格控制界面的信息密度与视觉层级。剔除缺乏实际参考价值的冗余数据与无区分度的重复装饰性图元，确保主要文本具备完整的展示与阅读空间。
   - **渐进披露 (Progressive Disclosure)**：单一视图聚焦单一核心心智。低频调试工具、次级参数及高阶操作应通过按需触发展现（如二级详情、模态抽屉或上下文菜单），而非在主视觉流中并列平铺。

---

## 2. 基础设计规范 Tokens

### 色彩系统 (Color Tokens)

| Token 变量 | 浅色模式 (Light) | 深色模式 (Dark / OLED) | 说明 |
| :--- | :--- | :--- | :--- |
| `LoonBg` | `#F2F2F7` | `#000000` (纯黑) | 页面全局背景 (System Grouped) |
| `LoonCard` | `#FFFFFF` | `#1C1C1E` | 分组卡片、弹窗与底色 |
| `LoonCardBorder` | `0x1A000000` (发丝线) | `0x2EFFFFFF` (发丝线) | `0.5.dp` 极细卡片描边 |
| `LoonSeparator` | `0x263C3C43` | `0x38545458` | `0.5.dp` 列表分割线 |
| `LoonTertiaryBg` | `#E5E5EA` | `#2C2C2E` | 搜索栏、文本输入框背景底色 |
| `LoonTextPrimary` | `#000000` | `#FFFFFF` | 主标题、正文标题文字 |
| `LoonTextSecondary`| `#8E8E93` | `#8E8E93` | 次级说明标签、小号提示文字 |
| `LoonTextMuted` | `#C7C7CC` | `#636366` | 占位提示文字 (Placeholder) |
| `IosBlue` | `#007AFF` | `#007AFF` | 默认主要操作色 (Accent / Links) |
| `IosGreen` | `#34C759` | `#34C759` | 成功、连接就绪状态 |
| `IosOrange` | `#FF9500` | `#FF9500` | 警告、规则分流状态 |
| `RedDanger` (`IosRed`)| `#FF3B30` | `#FF3B30` | 危险操作、断开或报错状态 |

### 圆角与尺寸 (Corner Radius & Sizing)

*   **分组卡片 / 弹窗 (Grouped Section / Dialog)**：`RoundedCornerShape(14.dp)`
*   **搜索框 / 文本输入框 (SearchBar / TextField)**：`RoundedCornerShape(10.dp)`，高度 `38.dp`
*   **胶囊操作按钮 (CupertinoButton)**：`RoundedCornerShape(10.dp)`，常规高度 `44.dp`
*   **图标方角徽章 (Squircle Icon Badge)**：`RoundedCornerShape(7.dp)`，尺寸 `30.dp`（图标尺寸 `17.dp`）
*   **iOS 物理开关 (CupertinoSwitch)**：尺寸 `51.dp × 31.dp`，滑块 `27.dp` 圆形
*   **状态徽标 / 胶囊 (Status Capsule / Pill)**：当必须使用容器标签时，一律采用完全圆角胶囊 `CircleShape`（圆角半径等于高度的一半或 `100.dp`）。❌ **严禁使用 `RoundedCornerShape(2~6.dp)` 这类 Web/Material 式的小圆角方形色块**。
*   **图标尺寸与触摸热区 (Icons & Touch Targets)**：
    - **导航栏图标**：视觉尺寸标准为 `20.dp`，触摸热区容器最小 `40.dp × 40.dp`。
    - **列表行次级操作图标**：视觉尺寸标准为 `15~16.dp`。
    - **图标风格与去 Android 化**：严禁直接混用平切硬拐角的 Google Material 默认图标；优先使用圆润平滑、具备 SF Symbols 字体化笔触特征的符号，或直接使用纯文本动作替代。

---

## 3. 标准组件使用规范

### 1) 导航栏 (Navigation Bar)
二级页面统一采用标准 iOS 导航栏：
```kotlin
CupertinoNavBar(
    title = "页面标题",
    showBackButton = true,
    onBack = onBack,
    actions = {
        // 右侧操作项
    }
)
```

#### 导航栏操作项约束 (NavBar Actions Rules)
- **严格控制右侧操作数量**：右侧操作项原则上**不得超过 1 个（极特殊场景最多 2 个）**。严禁并排堆叠 3~4 个小图标，避免破坏 iOS 核心的“主标题绝对居中”视觉平衡。
- **优先采用纯文字动作按钮 (Text Action Button)**：高频动作（如“清空”、“完成”、“编辑”、“保存”）优先使用 iOS 经典的文字按钮（常规操作为 `IosBlue`，破坏性危险操作为 `RedDanger`，字号 `16.sp`，点击热区补齐 `40~44.dp`），替代语意模糊的纯图形按钮。
- **搜索栏不收敛为顶栏图标**：搜索功能应作为内容区顶部的 `CupertinoSearchBar` 随页面内容自然滚动或吸顶，严禁在导航栏右上角放置放大镜图标来折叠弹开搜索框（此为 Android ActionBar 惯性，非 iOS HIG 标准）。

### 2) 分组列表与单元格 (Grouped Section & Cells)
遵循 iOS Inset Grouped 视觉：
```kotlin
CupertinoGroupedSection(
    header = "网络设置", // 自动大写并加小号间距
    footer = "这是关于该分组的辅助说明文案"
) {
    CupertinoCell(
        title = "系统代理",
        subtitle = "全局流量转发",
        icon = Icons.Default.VpnKey,
        iconBg = IosBlue,
        showChevron = true,
        onClick = { /* 跳转详情 */ }
    )
    CupertinoIndentedDivider() // 自动 59dp 文字对齐缩进分割线
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

### 3) 模态对话框 (Alert Dialog)
统一使用 `CupertinoAlertDialog`，支持单按钮、双按钮（带发丝线十字切分）与危险操作红字：
```kotlin
CupertinoAlertDialog(
    title = "删除配置",
    message = "确定要删除该配置文件吗？此操作不可逆。",
    confirmText = "删除",
    dismissText = "取消",
    isDestructive = true,
    onConfirm = { /* 确认回调 */ },
    onDismissRequest = { /* 关闭弹窗 */ }
)
```

### 4) 搜索栏 (Search Bar)
统一使用带清除叉号的 `CupertinoSearchBar`：
```kotlin
CupertinoSearchBar(
    value = searchQuery,
    onValueChange = { searchQuery = it },
    placeholder = "搜索节点、规则或域名..."
)
```

### 5) 按钮 (Cupertino Button)
统一使用 `CupertinoButton`，支持四种样式：
*   `CupertinoButtonStyle.Filled`：主操作填充背景
*   `CupertinoButtonStyle.Tinted`：半透明柔和背景
*   `CupertinoButtonStyle.Plain`：无底色文字按钮
*   `CupertinoButtonStyle.Destructive`：红色警示按钮
```kotlin
CupertinoButton(
    text = "保存并生效",
    onClick = { ... },
    style = CupertinoButtonStyle.Filled
)
```

---

## 4. 样式验收检查清单 (Checklist)

每次新增或修改界面后，请核对：
- [ ] 是否引入了任何 `androidx.compose.material3.Button` 或 `AlertDialog`？（若有，必须移除并替换）。
- [ ] 背景色是否直接写死？（必须引用 `LoonBg` 或 `LoonCard`，确保深浅主题平滑自适应）。
- [ ] 列表是否有文字缩进发丝线 `CupertinoIndentedDivider()`？
- [ ] 点击是否具有微缩回弹与触感反馈？
- [ ] 滚动容器是否添加了 `.bounceOverscroll()`？
- [ ] 界面是否落实“内容优先”，避免常驻大面积低频辅助容器挤占首屏核心内容？
- [ ] 列表与排版是否维持高信噪比，剔除了无辨识度的装饰元素并确保主要信息完整呈现？
- [ ] 列表次级元数据是否遵循“排版优先”原则，杜绝使用小圆角矩形（2~6.dp）Web/Material 式色块？
- [ ] 导航栏右侧操作项是否控制在 1~2 个以内，且优先采用 iOS 纯文字动作按钮（如“清空”、“完成”）？
- [ ] 独立操作按钮与图标的触摸热区是否达到 ≥ 40.dp？
- [ ] 搜索栏是否直接嵌入内容区呈现，而非使用 Android 风格的右上角折叠放大镜图标？
- [ ] 高阶与次级操作是否遵循渐进披露原则，保持主交互链路轻量直达？
