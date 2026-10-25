# Nexus

基于 Mihomo (Clash.Meta) 内核的 Android 代理客户端,iOS / Loon 拟真风格 UI。

## 特性

- **协议支持**:Shadowsocks、VMess、VLESS、Trojan、Hysteria 2、TUIC、WireGuard
- **分流控制**:域名/IP 规则分流、分应用代理(全局/绕过/仅代理)、前置规则与 Hosts 覆写、规则测试器
- **订阅管理**:URL/本地文件/剪贴板/扫码导入、订阅流量与到期展示、后台自动更新(WorkManager)、长按拖拽排序
- **核心观测**:external-controller REST API(随机 secret,仅回环)驱动的请求记录(真实连接 id、代理链、流量计数、按应用/协议过滤、单连接关闭)、DNS 记录、内核状态页(内存/连接数/运行时长/流量排行)
- **提供者体系**:proxy-provider 与 rule-provider 列表、更新时间与手动更新
- **节点详情**:长按查看协议类型、UDP 支持、可用状态与延迟历史
- **实用功能**:Fake-IP DNS 增强、局域网代理共享(端口可配置)、配置 JS 脚本复写(含模板库)、下拉快捷磁贴、实时速率图表、脚本测试器
- **安全隐私**:断网保护(Kill Switch)、允许应用绕过 VPN、第三方明文 DNS 拦截、订阅地址内网段校验开关、生物识别应用锁、防截屏、配置备份导出/导入
- **维护**:应用内检查并下载更新(GitHub Release)、核心日志三语界面(简中/English/Русский)

## 构建

```bash
# Debug APK
./gradlew assembleDebug

# Release APK（需签名配置）
./gradlew assembleRelease
```

## 说明

- 支持架构：`arm64-v8a`
- 订阅支持：`HTTP` 与 `HTTPS` 链接导入
- 请求/DNS/提供者数据依赖内核 external-controller(注入配置自动开启，无需额外设置)
