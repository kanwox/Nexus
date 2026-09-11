# Mihomo for Android (原生通用代理客户端)

基于 **Mihomo (Clash.Meta)** 内核与 **Jetpack Compose Material 3** 构建的现代 Android 原生通用代理客户端。

---

## ✨ 核心特性

- **高性能原生内核**：集成原生 `libclash.so` (Mihomo 核心) 与 `libbridge.so` JNI 通信桥，支持 `arm64-v8a` 极致性能。
- **OLED 极简纯黑美学**：针对 OLED 屏幕定制的纯黑沉浸式主题，搭配霓虹赛博青与动态呼吸动画。
- **全协议兼容**：支持 Hysteria 2、TUIC v5、VLESS-Reality、Shadowsocks 2022、WireGuard、Trojan、VMess 等几乎所有主流现代代理协议。
- **系统级深度集成**：
  - 标准 Android `VpnService` 虚拟网卡通道，支持 Mixed / gVisor / System 三种 TUN 协议栈模式。
  - 下拉控制中心快捷磁贴 (`MihomoTileService`)，一键在系统控制中心启停。
  - 前台常驻通知栏，实时展示上下行速率与累计数据流量。
- **分应用代理 (Per-App Routing)**：支持“全局代理”、“仅代理选中应用（白名单）”和“绕过选中应用（黑名单）”，精准控制网络分流。
- **灵活的规则与配置合成**：内置 Clash YAML 订阅拉取与本地配置合成引擎，自动配置 Fake-IP (198.18.0.1/16)、DNS 劫持与控制器端口。
- **五大核心页面**：
  1. **仪表盘 (Dashboard)**：电源大按钮、实时上下行网速卡片、流量统计、规则/全局/直连模式快速切换、当前出站节点快捷指示。
  2. **节点 (Proxies)**：策略组滚动标签、节点列表、并发延迟测速 (Ping / HealthCheck)、节点快速切换。
  3. **配置 (Profiles)**：Clash 订阅添加与一键更新、本地配置文件管理。
  4. **分应用 (Apps)**：设备已安装应用搜索与精细勾选过滤。
  5. **设置 (Settings)**：TUN 堆栈切换、开机自启、系统 VPN 快捷跳转、内核信息查看与重置。

---

## 📁 项目目录结构

```
E:\kuan\Mihomo
├── Mihomo.apk                        # 编译生成的可直接安装的 APK 安装包
├── app                               # Android 主模块
│   ├── src/main/
│   │   ├── AndroidManifest.xml       # 权限与服务声明 (VpnService, TileService)
│   │   ├── java/com/github/
│   │   │   ├── kr328/clash/core/     # Mihomo 核心 JNI 桥接层 (Bridge, TunInterface)
│   │   │   └── mihomo/android/
│   │   │       ├── core/             # 核心控制器 (ClashCore)
│   │   │       ├── data/             # 订阅管理、配置合成 (ConfigManager, SettingsManager)
│   │   │       ├── service/          # 后台与系统服务 (MihomoVpnService, MihomoTileService)
│   │   │       └── ui/               # Jetpack Compose 界面 (Dashboard, Proxies, Profiles, Apps, Settings)
│   │   ├── jniLibs/arm64-v8a/        # 预编译 Mihomo 原生内核 (libclash.so, libbridge.so)
│   │   └── res/                      # 资源文件 (图标、字符串、网络安全配置)
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── build.gradle.kts                  # 根构建脚本
├── settings.gradle.kts               # 仓库源配置
├── gradle.properties                 # 构建性能优化配置
└── local.properties                  # Android SDK / NDK 路径配置
```

---

## 🚀 快速开始与安装

### 1. 安装 APK
项目根目录下已打包好最新的安装包：
- 文件路径：`E:\kuan\Mihomo\Mihomo.apk`
- 调试输出路径：`E:\kuan\Mihomo\app\build\outputs\apk\debug\app-debug.apk`

你可以直接在手机上安装该 APK，或者连接手机并启用 USB 调试后执行：
```bash
adb install -r E:\kuan\Mihomo\Mihomo.apk
```

### 2. 重新编译工程
如果对代码进行了修改，可在 `E:\kuan\Mihomo` 目录下使用 Gradle 快速构建：
```cmd
set "JAVA_HOME=C:\Users\kuan\.android_build_env\jdk\jdk-21.0.11+10" && gradlew.bat assembleDebug
```
