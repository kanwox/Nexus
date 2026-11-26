# Changelog

All notable changes to Nexus are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses a
`MAJOR.MINOR.PATCH[-PreRelease]` version scheme.

The release workflow reads the section whose heading matches the `versionName` in
`app/build.gradle.kts`, so bump both together.

## [Unreleased]

## [1.0.8] - 2026-11-26

### Added

- **Google-Style Dual Wings App Icon**: Replaced the legacy flat `N` launcher icon with the new Google 2026 soft multi-hue gradient **Dual Wings** icon across `ic_launcher-web.png`, all `mipmap-*` densities (legacy, round, and adaptive foreground), and a matching Android 13+ monochrome themed silhouette (`ic_launcher_monochrome.xml`).
- **Unified Shared Network Client Pool (`NetworkClients`)**: Consolidated 9 independent `OkHttpClient` instances across `ConfigManager`, `ScriptManager`, `UpdateChecker`, `GeoDataManager`, and `MihomoApi` onto a single shared `Dispatcher` and `ConnectionPool`.
- **Script Editor Unsaved-Changes Guard**: Added dirty-state tracking and a trilingual confirmation dialog (`scripts_discard_confirm`) when exiting the script editor with unsaved edits, and blocked outer sub-screen edge-swipe back gestures while the editor is open.

### Changed

- **Honest System Kill Switch & Unified Per-App Self-Exclusion**: Removed the non-functional `VpnService.Builder.setBlocking(true)` toggle from `MihomoVpnService` and converted the Kill Switch row in `VpnTunnelScreen` into a direct shortcut to Android's native system VPN settings (`Settings.ACTION_VPN_SETTINGS`); unified per-app proxy routing (`WHITELIST`, `BLACKLIST`, `DISABLED`) to consistently exclude Nexus itself from the TUN interface.
- **Fail-Closed Security Hardening**:
  - `ConfigValidation`: Expanded `PROTECTED_TOP_LEVEL_KEYS` (`external-controller-tls`, `tls`, `profile`, `experimental`, `ebpf`) and switched malformed YAML sanitization to fail-closed instead of silently falling back to text regex filtering; tightened `YamlSupport` code point limit to `25 MB`.
  - `LanPasswordCipher` & `SettingsManager`: Cached the Android Keystore key process-wide with lazy initialization (eliminating per-instantiation Keystore Binder IPC), removed plaintext software-key writes on production paths while preserving transparent `sw1:`-to-`ks1:` migration, and defaulted `allowPrivateSubscriptionHosts` / `requireSecureRemoteUrl` to fail-closed (`false`).
  - `ScriptManager` & `UpdateChecker` & `GeoDataManager`: Enforced HTTPS-only remote script downloads, enforced HTTPS + official GitHub release host allowlist on in-app update URLs, and added official `.sha256sum` verification with local-proxy-first fallback on Geo database updates.
- **Multi-Level Sub-Screen Stack & Compose Stability**: Upgraded `MainAppState` from a 2-slot parent pointer to a true saveable `SnapshotStateList<SubScreen>` stack, extracted `VpnServiceSupport.kt` from `MihomoVpnService.kt`, moved `allowLanPassword` writes off the main thread, added background debounced filtering for `RulesScreen`, reduced per-record allocations in `RequestLogsScreen`, and aligned `@Stable`/`@Immutable` annotations and scoped `DELAY` sorting in `ProxiesScreen` / `DashboardScreen`.
- **Documentation**: Documented the intentional embedded release keystore/fallback signing configuration in `README.md`, `BUILD_ENV.md`, and `app/build.gradle.kts`.

### Fixed

- **VPN Service & Core Concurrency / ANR**:
  - Wrapped `MihomoVpnService` `actorJob` command processing in exception recovery and guarded all `startForegroundNotification()` calls against `ForegroundServiceStartNotAllowedException`; fixed TUN fd ownership, instance-scoped teardown isolation, bounded `onDestroy` worker join timeout, `isUiInForeground` reference counting, 5s screen-off suspend debounce, notification-permission checks, and multi-network `PhysicalNetworkCallback` fallback.
  - Fixed `ClashCore` main-thread `init()` blocking on read-only queries, `reset()` locking, `ConcurrentHashMap.compute` recursive update risk during health checks, DST-aware timezone offset (`TimeZone.getOffset`), `loadedProfileId` clearing on default config load, and preserved underlying native error messages in `wrapClashFailure`.
  - Fixed `Bridge` safe `Application` casting and atomic asset moves, `BootReceiver` broadcast watchdog timeout, `MihomoTileService` lock-screen unlock parity on VPN stop and app-language subtitle sync, `TileVpnLaunchActivity` empty-profile redirect, and `MihomoApplication` crash-redaction OOM fallback.
- **Data Integrity, Script Sandbox & Transport Efficiency**: Fixed `ConfigScriptEngine` integrity TOCTOU via atomic `ScriptManager.readVerifiedScriptCode`, added recursion/length bounds to script JSON and console formatting, fixed `ProfileStore` write-before-cache ordering and corrupt-store quarantine/rebuild, fixed `ApiController` port/secret atomic snapshots, replaced `RequestRepository` 200ms WebSocket polling with `CompletableDeferred` zero-poll suspension and a bounded LRU UID cache, and made `LogRepository` trims and `DiagnosticsExporter` temp files atomic/race-free.
- **UI Bugs, Accessibility & Localization**: Fixed `DashboardScreen` shortcut drag-reorder stale closure and duplicate-node `LazyColumn` key crash in the quick node selector, hid background tabs and bottom bar from accessibility/keyboard focus (`hideFromAccessibility()` / `canFocus = false`) when a sub-screen overlay is active, fixed the speed-test button's `stateDescription`, enabled default keyboard focus rings on `iosPressable`, and made `AppStrings.current()` observe `LocalConfiguration` for live system-locale updates.

## [1.0.7] - 2026-11-14

### Added

- **Backup V2 Streaming Encryption & LAN Credential Protection**: Added V2 encrypted backups (`45`-byte AAD, `210,000` PBKDF2 iterations, `16 KB` chunked streaming cipher) while retaining V1 decryption and optional plaintext export/import, and encrypted stored LAN proxy passwords via `LanPasswordCipher` (Android Keystore / AES-256-GCM with `ks1:`/`sw1:` formats).
- **Tile Launch & Expanded JVM Test Suite**: Added `TileVpnLaunchActivity` for Quick Settings Tile VPN startup reliability and expanded pure-JVM unit tests covering core routing, config validation/scripts, streaming backup encryption, diagnostics redaction, transport/log/DNS repositories, Geo/update verification, `MihomoApi` URL encoding, and UI localization/cache invariants (`45` suites / `359` unit tests).

### Changed

- **Single Offline GeoIP Asset Consolidation**: Removed the bundled duplicate `Country.mmdb` asset so offline Geo resolution relies solely on `geoip.metadb` and `geosite.dat` with protobuf and MaxMind metadata validation on manual updates.
- **Core, Data & UI Modular Decomposition**: Extracted focused modules (`ProfileStore`, `ConfigValidation`, `ConfigScriptSupport`, `UpdateDownloadManager`, `ProxiesRuntimeCacheStore`, `ProxiesSpeedTestEngine`, `ProxiesSpeedTestHostAdapter`, `MainAppState`, `MainAppBackupDialogs`, `MainAppSubScreens`, `CupertinoFeedback`, `CupertinoModals`) with coroutine cancellation safety and `Locale.ROOT` formatting consistency.
- **Proxy Group List Mode, Navigation & Motion Polish**:
  - Kept collapsed and expanded group header right slots aligned at `48dp` minimum touch-target height, unified list-mode group-to-node vertical spacing at `8dp`, and disabled press-scale shrinking on group header cards.
  - Removed the selected-icon `1.08f` zoom animation from `BottomNavItem` while keeping color and weight transitions.
  - Added staggered cross-fade transitions (`statusFadeTransform`, respecting `LocalReduceMotion`) for speed-test spinners and latency status text.
- **Build, Detekt & R8 Configuration**: Upgraded CI workflow permissions, tag-gated release extraction, `:app:detekt` invocation under AGP 9.4.1, `compose-bom:2026.09.00` alignment, stricter `config/detekt/detekt.yml` complexity/coroutine rules, pruned `gradle/verification-metadata.xml` (`462` components), `.gitattributes` LF normalization, and narrowed R8 keep rules with `-dontobfuscate` preserved for readable crash traces.

### Fixed

- **P0 Security, Config Validation & Diagnostics Redaction**: Hardened URL/redirect validation (`UrlValidation`), QuickJS script execution limits (`ConfigScriptEngine`/`ScriptManager`), atomic per-profile writes and Base64 pre-validation (`ConfigManager`/`ProfileStore`), DNS root rule sanitization (`ConfigSanitizer`/`ConfigValidation`), `ProfileParser` bounded regex evaluation and legacy section parsing, and `DiagnosticsExporter` credential/token/IPv6 redaction without corrupting GitHub release download URLs.
- **VPN Lifecycle, Bridge & Network Tracking**: Fixed `Bridge.assetsReady()` retry recovery when extraction fails, moved cold-start `ClashCore.init` and legacy script migration off the main thread (`MihomoApplication`), replaced deprecated `cm.allNetworks` queries with active non-VPN physical network tracking, filtered TUN loopback DNS addresses, deduplicated network/timezone notifications, and guarded stale health checks and invalid per-app whitelist states (`ClashCore`/`MihomoVpnService`).
- **Speed Test State Transitions & Repository Consistency**: Synchronized testing-flag clearing with delay cache commits for single-node and group speed tests so loading indicators fade cleanly into measured latencies without intermediate flicker; fixed `ThrottledPublisher` lazy coroutine races, `RequestRepository` stream generation/eviction guards, `LogRepository` bounded disk writes, UI state and scroll stability, and multi-language string parity (`values`, `values-zh`, `values-en`, `values-ru`).
- **Secondary Screen Edge-Swipe Exit Rebound**: Fixed interactive side-edge drag and system `PredictiveBackHandler` exits in `MainSubScreenOverlay` (`SubScreenEdgeBackPolicy`/`MainAppSubScreens`) where releasing after a slow edge swipe immediately reset `translationX` to `0px` and triggered a second full-screen `slideOutHorizontally` rebound before returning to the primary or parent screen; gesture exits now continue monotonically off-screen until transition teardown completes, while canceled predictive drags settle smoothly back to `0px`.

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
