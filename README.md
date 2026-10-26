# Nexus

基于 Mihomo (Clash.Meta) 内核的 Android 代理客户端，iOS / Loon 拟真风格 UI。

## 特性

- **协议支持**：Shadowsocks、VMess、VLESS、Trojan、Hysteria 2、TUIC、WireGuard（由内核提供）
- **分流控制**：域名/IP 规则分流、分应用代理（全部代理 / 仅代理所选应用 / 仅绕过所选应用）、前置规则与 Hosts 覆写、规则测试器
- **订阅管理**：URL / 本地文件 / 剪贴板 / 扫码导入、订阅流量与到期展示、后台自动更新（WorkManager）、长按拖拽排序
- **核心观测**：external-controller REST API（随机 secret、仅回环）驱动的请求记录（真实连接 id、代理链、流量计数、按应用/协议过滤、单连接关闭）、DNS 记录、内核状态页、提供者列表与手动更新
- **节点详情**：长按查看协议类型、UDP 支持、可用状态与延迟历史
- **实用功能**：Fake-IP DNS 增强、局域网代理共享（端口与访问密码可配置）、配置 JS 脚本复写（含模板库）、下拉快捷磁贴、脚本测试器
- **安全隐私**：断网保护（Kill Switch）、允许应用绕过 VPN、第三方明文 DNS 拦截、订阅地址内网段校验开关、配置备份导出/导入
- **维护**：应用内检查并下载更新（GitHub Release）、界面与主要提示三语（简体中文 / English / Русский）

## 构建

需要预装 Android SDK **Platform 36**、**Build-Tools 36.0.0** 与 **Platform-Tools**。
AGP 的自动 SDK 下载已关闭（`android.builder.sdkDownload=false`），缺少组件时会直接给出明确错误，
而不是在配置阶段联网拉取 SDK 清单（CI 上曾因此出现 `IO exception while downloading manifest`）。

```bash
# Debug APK
./gradlew assembleDebug

# Release APK（签名配置见 app/build.gradle.kts）
./gradlew assembleRelease
```

## 质量门禁

与 CI 保持一致，提交前建议执行：

```bash
./gradlew testDebugUnitTest detekt lintDebug checkUiTokens
```

- `testDebugUnitTest`：配置合成、YAML 安全、URL 校验、日志/请求仓库、颜色对比度等纯 JVM 单测
- `detekt`：静态分析（`config/detekt/detekt.yml`），包含禁用 Material 控件/普通圆角的 `ForbiddenImport`
- `lintDebug`：Android Lint（CI 会拦截 error 级问题）
- `checkUiTokens`：禁止业务 UI 新增裸 `fontSize` / `Color(0x…)`（`UI_GUIDELINES.md` §3）

## 目录结构

| 路径 | 说明 |
| :--- | :--- |
| `app/src/main/java/com/github/kr328/clash` | 内核桥接层（JNI `Bridge`、模型、流量格式化） |
| `app/src/main/java/com/github/mihomo/android/core` | 内核生命周期与健康检查封装 |
| `app/src/main/java/com/github/mihomo/android/data` | 订阅/配置/脚本/备份/日志/请求仓库，以及 YAML 清洗管线 |
| `app/src/main/java/com/github/mihomo/android/service` | `VpnService` 与快捷磁贴 |
| `app/src/main/java/com/github/mihomo/android/ui` | Compose 界面、ViewModel、自定义 Cupertino 组件与主题 |

配置链路：`ConfigManager.prepareConfig()` 将运行时头部（模式、端口、DNS、external-controller）与
清洗后的用户 YAML 合成为 `filesDir/config.yaml`，再交给内核；`MihomoApi` 通过回环 REST 读取
连接、DNS 与内核状态。

## 说明与限制

- 支持架构：`arm64-v8a`（调试版在模拟器上会跳过 Go 内核初始化）。
- 请求/DNS/提供者/内核状态依赖内核 external-controller，注入配置自动开启，无需额外设置；
  若本机 9090-9199 端口全部被占用，相关页面会为空并在内核日志中给出提示。
- 订阅支持 `HTTP` 与 `HTTPS`。应用允许明文 HTTP（用于自建订阅/脚本），但订阅地址通常含
  token，建议优先使用 HTTPS。
- 应用自身始终被排除在 VPN 之外（避免回环），因此订阅下载、更新检查与图标请求走物理网络；
  GitHub raw 脚本下载会优先尝试本地 mixed 端口。
- 分应用代理选择"仅代理所选应用"时，必须至少选择一个应用，否则启动会给出明确错误。
- 备份文件是明文 JSON，包含订阅地址（含 token）与局域网密码，请妥善保管。

## 变更记录与许可

- 设计规范：[UI_GUIDELINES.md](UI_GUIDELINES.md)（iOS 视觉 + Android 交互契约，含可访问性、Tokens、自适应/本地化/动效规则）
- 版本变更见 [CHANGELOG.md](CHANGELOG.md)
- 许可证：[GPL-3.0](LICENSE)
- 第三方组件与内核来源：[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
