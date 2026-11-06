# Changelog

All notable changes to Nexus are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses a
`MAJOR.MINOR.PATCH[-PreRelease]` version scheme.

The release workflow reads the section whose heading matches the `versionName` in
`app/build.gradle.kts`, so bump both together.

## [Unreleased]

## [1.0.6] - 2026-11-06

### Added

- **Batch Subscription Refresh**: Added bounded concurrent subscription updates (`Semaphore(3)`) with live navigation bar activity indication and completion summary feedback.
- **Local Rule Tester Enhancements**: Added `DST-PORT` and `PORT` evaluation (supporting port lists, ranges, `host:port`, and `[ipv6]:port` inputs), input debouncing for large rule sets, and static-estimation notices when higher-priority dynamic rules (`GEOIP`, `GEOSITE`, `RULE-SET`, `PROCESS-NAME`) are skipped.
- **Backup Restore Diagnostics**: Added detailed post-import failure reporting via `CupertinoAlertDialog` when individual profiles or scripts fail validation, paired with immediate in-memory settings synchronization and core hot-reload.
- **Silent Automatic Update Check**: Added an optional background update check (once every 24 hours on launch) that surfaces available releases via a non-intrusive badge on the Settings and About rows without modal startup interruptions.

### Changed

- **iOS / Cupertino Interaction & Accessibility**:
  - Moved `LocalIndication` (`NoIndication`) and `LocalOverscrollFactory` inside `MaterialTheme` and removed `Surface(onClick)` ripple wrappers so tabs, segmented controls, switches, and action buttons render without Material ink ripples.
  - Added `onLongClick` support (`combinedClickable`) to `iosPressable` and removed duplicate `pointerInput` gesture detectors on proxy node cards.
  - Replaced fixed `.height(...)` constraints with `.heightIn(min = ...)` across `CupertinoNavBar`, `CupertinoActionSheet`, `CupertinoSegmentedControl`, `CupertinoAlertDialog` (with scrollable body), `CupertinoSearchBar`, and group pills.
  - Enforced 48dp minimum touch targets (`LoonSizes.touchTargetMin`) on navigation bar actions, search clear buttons, group speed-test buttons, and profile item menus.
  - Updated `bounceOverscroll` to compute pull thresholds via `LocalDensity` and bypass overscroll translation when `LocalReduceMotion` is enabled.
  - Configured numeric `KeyboardOptions` on port, MTU, and auto-update interval inputs, and set `contentDescription = null` on decorative group icons.
- **Navigation & Service Restart Coalescing**:
  - Tracked parent sub-screens (`PROFILES -> QR_SCAN`, `DNS -> DNS_SETTINGS`) with 3-level transition depth and RTL-aware horizontal slide transitions so back navigation returns to the originating sub-screen.
  - Coalesced per-app routing (`APP_ROUTING`) modifications to trigger a single VPN restart upon leaving the screen.
- **Geo Asset Extraction & System Receivers**:
  - Recorded `.bundled_geo_version` by APK `longVersionCode` in `Bridge.extractAssets` to refresh bundled Geo databases on app upgrades without overwriting newer user-updated files.
  - Updated `BootReceiver` to execute profile checks and service startup asynchronously via `goAsync()` on `Dispatchers.IO`.
  - Added `forcePersist` to `LogRepository.addLog` so background subscription updates in `SubscriptionUpdateWorker` persist at `info` level while the screen is off.

### Fixed

- **Speed Test State & Live Sorting**:
  - Mapped native `0` and `-1` delay sentinels to `SpeedTestState.Untested` (`--`) and `-2` / `>= 65535` to `SpeedTestState.Timeout`.
  - Recorded `SpeedTestState.Timeout` in `delayCacheStore` when probes fail and prioritized group-qualified keys (`group:node`) during online proxy refreshes.
  - Subscribed Tabs-mode `DELAY` sorting to `delayCache` snapshots for live reordering and sank failed or timed-out nodes to the bottom tier.
  - Updated `NodeDetailSheet` availability state to distinguish untested and in-progress probes from unreachable nodes.
  - Rethrew `CancellationException` and guarded `activeGroupChecks` cleanup with `deferred.isCompleted` in `ClashCore.testProxyDelayNative`.
- **Configuration Reload & State Rollback**:
  - Propagated file read errors in `BackupManager.exportBackup` and checked `Result.getOrThrow()` during backup export.
  - Enforced a 20 MB stream cap and structure validation (`validateConfigStructure`) on local profile URI imports.
  - Verified `ClashCore.patchSelector` and `ClashCore.reloadActiveConfig` return values across proxy selection, tunnel mode, Fake-IP, DNS, IPv6, LAN sharing, mixed port, and log level changes, rolling back UI and persisted settings on failure and removing duplicate reload calls in `DnsSettingsScreen`.
  - Prevented `refreshSubscription` from emitting a success toast after a failed hot-reload rollback.
  - Awaited `VpnState.Status.STOPPED` before calling `ClashCore.reset()` and cleared ViewModel speed-test and delay caches on core reset.
  - Displayed `--` before asynchronous profile file sizes finish loading, refreshed relative timestamps every 60 seconds, validated non-empty subscription URLs when editing, and matched toast dismissal by message ID in `CupertinoToast.dismiss`.
- **DNS & Request Log Accuracy**:
  - Distinguished `[DNS] cache hit` entries (`isCacheHit`), pending resolutions (`isResolving`), and parsed latency values in `DnsRepository`, and prevented `"Resolving..."` placeholders from polluting `domainToIpMap`.
  - Excluded `REJECT` connections from `RequestRepository.activeCountFlow`, gated local connection closure on `MihomoApi.closeConnection` returning `true`, formatted IPv6 endpoints as `[ipv6]:port`, and derived `DnsScreen` and `RequestLogsScreen` detail sheets from live record IDs.
- **In-App Update System**:
  - Routed release metadata and APK download requests through the local mixed proxy port first when the VPN tunnel is active (falling back to direct connection), and restricted requests to direct connection when the tunnel is stopped.
  - Resumed APK installation automatically upon returning from the system unknown-sources permission screen and reused verified cached APKs in `cacheDir/updates` to avoid redundant downloads.
  - Ensured download state cleanup inside a `finally` block on coroutine cancellation and removed the 300-character truncation on scrollable release notes.
