# Changelog

All notable changes to Nexus are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses a
`MAJOR.MINOR.PATCH[-PreRelease]` version scheme.

The release workflow reads the section whose heading matches the `versionName` in
`app/build.gradle.kts`, so bump both together.

## [Unreleased]

## [1.0.5] - 2026-10-28

### Added

- **TUN User-Space Stacks**: Supported Mips (`mips`, based on lwIP/mipstack, default) and gVisor (`gvisor`) user-space network stacks for reliable packet processing under Android VpnService.
- **Multi-Source Configuration Import**: Supported remote subscription URLs, local YAML file imports, and QR code scanning.
- **Subscription Auto-Update**: Background periodic update scheduling via WorkManager with configurable intervals and live core hot-reloading.
- **Core Observability**: Integrated lightweight external-controller over loopback, providing structured request tracing, connection management, node latency history, and provider status.
- **Encrypted Backup & Restore**: Optional AES-256-GCM backup export and import with PBKDF2 key derivation for profile credentials and application preferences.
- **Design System & Aesthetics**: Fully unified iOS / Loon design system (`DesignTokens`, `LoonTextStyles`) featuring continuous corner curvature, strict minimum 52dp list row heights, symmetric hairline dividers, and borderless modal sheets.
- **Internationalization**: Complete localization across Simplified Chinese (简体中文), English, and Russian (Русский).

### Changed

- **TUN Routing Compatibility**: Resolved policy routing and UID isolation conflicts with the TUN interface, ensuring seamless bidirectional communication for local listening sockets.
- **Streamlined DNS Settings**: Focused the DNS configuration on Fake-IP, IPv6 support, and plaintext DNS leak protection, removing redundant manual upstream/fallback server editors.
- **Profiles Management Overhaul**: Simplified profile card metadata layout, embedded auto-update schedule settings into profile editing, and added one-tap batch update actions.
- **Rendering & Resource Optimization**:
  - Virtualized proxy node lists with localized state updates, eliminating recomposition overhead during concurrent health checks.
  - Implemented asynchronous icon decoding with bounded LRU caching in per-app routing.
  - Adaptive connection polling intervals based on application visibility and screen state.
- **Accessibility & Interaction**:
  - Enforced minimum 48dp touch targets and visible focus indicators across all interactive controls.
  - Full support for system-wide "reduce animations" preferences.
  - Verified WCAG AA contrast compliance across light and dark themes.
- **Build & Toolchain Upgrade**:
  - Targeted and compiled against Android 15 (API 37).
  - Updated toolchain: Gradle 9.8.2, AGP 9.4.1, Kotlin 2.4.21, Compose 1.12.1, and OkHttp 5.5.1.
  - Enforced SHA-256 dependency verification and strict design token linting.

### Fixed

- **TCP Handshake on Android VpnService**: Fixed traffic stall on non-root Android environments by utilizing pure user-space TCP/IP stacks and eliminating unsupported kernel socket redirection.
- **Plaintext DNS Leak Protection**: Corrected rule syntax to use natively supported Mihomo rule primitives (`AND`, `NOT`, `DST-PORT`, `IP-CIDR`), expanding protection to both UDP and TCP on port 53.
- **Network Handover**: Added active detection for physical network loss and capabilities changes to ensure timely core route and DNS cache refreshes during Wi-Fi and cellular transitions.
- **Core Lifecycle Serialization**: Serialized core initialization, configuration reloads, and tunnel teardown routines to prevent race conditions and file descriptor leaks.
- **Service Restart Semantics**: Corrected foreground service restart behaviors (`START_NOT_STICKY` with persisted intent state) to avoid unintended tunnel resurrection after explicit user stops.
- **Data & Parsing Reliability**:
  - Resolved 13-byte magic header truncation in encrypted backup detection.
  - Enforced strict YAML 1.2 boolean scalar parsing to prevent corruption of proxy names or credentials (e.g., `on`, `yes`).
  - Added SSRF boundary guards to reject subscription downloads and HTTP redirects targeting private or loopback addresses.
  - Applied centralized file path sanitization (`SafeFileName`) to mitigate directory traversal risks.
  - Added payload validation to reject truncated or HTML error bodies during subscription updates.
  - Percent-encoded REST API endpoints to support node and group names containing whitespace.
- **Scripting Security**: Enforced a 64 MB heap cap on the QuickJS engine and ensured global scripting master toggles take immediate effect.

### Removed

- Removed experimental biometric application lock.
- Removed screenshot prevention toggle.
- Removed live network throughput line chart.
- Removed standalone Profile Override screen and redundant custom DNS server edit fields.

### Security

- Restricted Android 12+ device-to-device and cloud backup transfers to prevent credential leakage (`backup_rules.xml`, `data_extraction_rules.xml`).
- Guarded sensitive runtime configuration parameters (`listeners`, `sub-rules`, `tunnels`, controller credentials) against untrusted subscription overrides.
- Excluded virtual and tunnel interfaces from LAN proxy IP resolution.
