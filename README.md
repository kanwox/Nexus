# Nexus for Android

基于 **Mihomo (Clash.Meta)** 内核与 **Jetpack Compose Material 3** 构建的现代 Android 原生通用代理客户端。

---

## ✨ 核心特性

- **高性能原生内核**：集成原生 `libclash.so` (Mihomo 核心) 与 `libbridge.so` JNI 通信桥，支持 `arm64-v8a` 极致性能。
- **现代美学设计**：优雅精致的界面设计，支持多列卡片排版与流畅手势交互。
- **全协议兼容**：支持 Hysteria 2、TUIC v5、VLESS-Reality、Shadowsocks 2022、WireGuard、Trojan、VMess 等主流现代代理协议。
- **系统级深度集成**：
  - 标准 Android `VpnService` 虚拟网卡通道，支持 Mixed / gVisor / System 三种 TUN 协议栈模式。
  - 下拉控制中心快捷磁贴 (`MihomoTileService`)，一键在系统控制中心启停。
  - 前台常驻通知栏，实时展示上下行速率与累计数据流量。
- **分应用代理 (Per-App Routing)**：支持全局代理、白名单代理与黑名单分流模式，精准控制网络分流。
- **策略组隔离与精准测速**：
  - 各策略组测速数据彻底独立隔离，互不干扰；
  - 自动策略组（URL-Test / Fallback / Load-Balance）进应用自动探活与状态同步。
- **灵活的规则与脚本引擎**：内置 JavaScript (Rhino) 脚本配置热重写引擎，支持高度定制化订阅处理。

---

## 📁 项目目录结构

```
.
├── app                               # Android 主模块
│   ├── src/main/
│   │   ├── AndroidManifest.xml       # 权限与服务声明 (VpnService, TileService)
│   │   ├── java/com/github/
│   │   │   ├── kr328/clash/core/     # Mihomo 核心 JNI 桥接层
│   │   │   └── mihomo/android/
│   │   │       ├── core/             # 核心控制器 (ClashCore)
│   │   │       ├── data/             # 订阅管理、配置合成 (ConfigManager, SettingsManager)
│   │   │       ├── service/          # 后台与系统服务 (MihomoVpnService, MihomoTileService)
│   │   │       └── ui/               # Jetpack Compose 界面层
│   │   ├── jniLibs/arm64-v8a/        # 预编译 Mihomo 原生内核 (libclash.so, libbridge.so)
│   │   └── res/                      # 资源文件 (图标、字符串、网络安全配置)
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── build.gradle.kts                  # 根构建脚本
├── settings.gradle.kts               # 仓库源配置
└── gradle.properties                 # 构建性能优化配置
```

---

## 🚀 编译与构建

使用 Gradle 编译 Debug 版本 APK：

```bash
./gradlew assembleDebug
```

编译生成的 APK 位于：
`app/build/outputs/apk/debug/app-debug.apk`

