# Changelog

All notable changes to Nexus are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses a
`MAJOR.MINOR.PATCH[-PreRelease]` version scheme.

The release workflow reads the section whose heading matches the `versionName` in
`app/build.gradle.kts`, so bump both together.

## [Unreleased]

## [1.0.5-Alpha.4] - 2026-10-26

### Stability & performance

- **FGS notification contract hardening**: `MihomoVpnService` now promptly reinforces
  the foreground notification on any incoming start command (including re-entrant
  starts while `STARTING` or `RUNNING`), eliminating potential Android 14+
  `ForegroundServiceDidNotStartInTimeException` risks.
- **Background subscription auto-update hot reload**: When `SubscriptionUpdateWorker`
  refreshes the currently active profile while the VPN is connected, it now triggers
  `ClashCore.reloadActiveConfig` to smoothly hot-reload the running core without
  requiring a manual restart.
- **Request repository O(1) indexed lookup**: Replaced the linear `indexOfFirst` search
  over active connection records with an insertion-ordered `LinkedHashMap`, reducing
  poll lookup complexity from O(N) to O(1) and lowering background CPU overhead.
- **App routing icon lazy loading & memory optimization**: `AppInfoManager` now decouples
  package listing from bitmap decoding; `AppRoutingScreen` loads icons asynchronously
  on-demand with a bounded LRU bitmap cache, eliminating peak memory spikes on devices
  with hundreds of installed applications.
- **Thread-safe speed test delay recording**: `ProxiesViewModel` now serializes all
  concurrent worker writes to `delayCache` behind a dedicated lock, protecting Compose
  snapshot state maps from race conditions during parallel group health checks.

### Security & networking

- **AES-256-GCM encrypted backup support**: `BackupManager` now supports optional password-based
  AES-256-GCM encryption with PBKDF2 key derivation for exported configuration backups,
  protecting subscription tokens and LAN passwords while maintaining full backward
  compatibility for plain JSON restores.
- **Update check proxy fallback**: `UpdateChecker` and the in-app APK downloader now
  automatically fall back to the local mixed-port proxy when direct physical network
  requests to GitHub are blocked or restricted while the app traffic bypasses the VPN.

### Changed

- `versionName` 1.0.5-Alpha.4 / `versionCode` 20.

## [1.0.5-Alpha.3] - 2026-10-26

### Design system & accessibility

- Added a single source of truth for the visual language: `DesignTokens.kt` (semantic
  light/dark colors, spacing, radii, sizes, motion) and `LoonTextStyles.kt` (semantic text
  roles). The component library now consumes those tokens instead of raw sizes/colors.
- Removed the remaining raw `fontSize = N.sp` and `Color(0x…)` literals from feature UI:
  one-off sizes use `LoonTypeScale`, shadows/gradients/syntax colors use `LoonExtraColors`.
  A new `checkUiTokens` Gradle task (also run by CI) rejects reintroducing them.
- Responsive layout: `WindowWidthClass`/`LocalWindowWidthClass` and
  `LoonResponsiveContent(maxWidth = 680.dp)` center main-tab content on tablets/foldables
  while leaving compact phone layouts unchanged.
- `AppStrings`/`LocalAppLanguage` moved from `ui.theme` to the neutral
  `com.github.mihomo.android.i18n` package; service/data layers no longer depend on UI.
- `DesignSystemPreview.kt` adds the Light/Dark × SYSTEM/IOS × zh/en/ru × 1.3-font preview
  matrix, and `DesignTokenContrastTest` guards WCAG AA contrast in CI.
- Introduced text-safe status colors (`successText`/`warningText`/`dangerText`/`infoText`)
  and raised secondary/muted text contrast: light secondary `#8E8E93` (3.26:1) → `#6E6E73`
  (5.07:1), dark secondary → `#A1A1A6`, dark muted `#636366` (2.84:1) → `#8E8E93` (5.22:1).
  Bright Apple colors remain for fills and icons.
- Accessibility: `CupertinoSwitch` now exposes `Role.Switch` via `toggleable` and a 48dp
  touch target; app-selection rows use `Role.Checkbox`; segmented controls and the bottom
  bar use `selectable`/`selectableGroup`; toasts announce through `liveRegion`; custom
  pressables draw a visible keyboard/D-pad focus ring because ripples are disabled.
- Motion honors the system "remove animations" setting (`LocalReduceMotion`) for press
  scale, switch thumb/track and segmented/bottom-bar transitions.
- Edge-swipe back is now layout-direction aware: it starts from the start edge and drags in
  the correct direction under RTL.
- Replaced Material 3 wildcard imports with explicit primitives and enabled detekt
  `ForbiddenImport` for Material controls / `foundation.RoundedCornerShape`, so the
  "no Material controls" rule is enforced by CI.
- Rewrote `UI_GUIDELINES.md` as a rule-ID based design system with contrast table,
  accessibility, adaptive layout, RTL/i18n, motion, interaction patterns, performance
  budgets and an explicit review matrix.

### Changed

- `versionName` 1.0.5-Alpha.3 / `versionCode` 19.

## [1.0.5-Alpha.2] - 2026-10-26

Correctness, security-hardening and localization pass over the 1.0.5 alpha line.

### Fixed

- **Per-profile overrides were silently dropped for large subscriptions.** The YAML
  sanitizer inherited SnakeYAML's 3 MB code point limit; anything larger fell back to text
  stripping, which cannot apply prepend rules, hosts merges or the plaintext-DNS reject
  rule. The limit is now 100 MB and the fallback is reported in the core log.
- **YAML 1.1 boolean coercion corrupted node data.** A proxy named `on` or a password
  `yes` was parsed as a boolean and re-emitted as `true`. The parser now follows YAML 1.2
  scalar rules (`true`/`false` only), and each parse uses its own SnakeYAML instance
  (instances are not thread-safe).
- **Node details and provider updates failed for names containing spaces.**
  `URLEncoder` emits `+` for spaces, which is only decoded in query strings; REST path
  segments are now encoded with `%20`.
- **The DNS settings screen destroyed the server list.** It displayed the first upstream
  server and saved that single value back, dropping the rest (also when toggling the
  plaintext-DNS switch). Upstream and fallback servers can now be added, edited and removed
  individually.
- **The LAN password reloaded the whole core on every typing pause.** It is now committed
  with an explicit Save action, so one reload happens per save.
- **An empty per-app whitelist proxied every app.** It now fails with a clear message
  instead of contradicting the "Proxy Selected Apps" label.
- **The core page reported the wrong uptime** (measured from when the screen was opened)
  and recomposed the entire page once per second, including a JNI traffic query during
  composition. Uptime now starts when the tunnel reaches RUNNING and only the fields that
  change are recomposed.
- **Untrusted group icons could OOM the app.** Downloads are capped at 512 KB, images are
  downsampled before decoding, and the disk cache is bounded.
- **The in-app update download had no limits.** It now reuses a shared client with
  timeouts, writes to a fixed cache filename (no URL-derived paths), and aborts above
  200 MB. The confirmation button uses the in-app installer when the release ships an APK.
- **Backup import read the whole file into memory.** It is capped at 50 MB.
- **URL validation misclassified public hostnames** such as `fc2.com` as private because it
  compared string prefixes. IPv4/IPv6 literals are now parsed properly; link-local and
  unspecified addresses remain always rejected.
- **External-controller startup could silently disable observability.** Port probing is
  widened to 9090-9199 and a busy range is reported in the core log.
- **The screen-off gate could remain stuck** after the VPN service was destroyed while the
  screen was off; it is reset on teardown and whenever the UI returns to the foreground.
- **Switching profiles could publish a stale parse** because cancellation was not observed
  before the result was assigned.
- **Missed localization:** bottom sheets defaulted to Chinese "完成", several VPN toasts /
  notifications and the unresolved app label were Chinese-only, and ScriptManager ignored
  the configured mixed port.

### Changed

- `versionName` 1.0.5-Alpha.2 / `versionCode` 18.
- Android Gradle Plugin upgraded from 9.0.1 to **9.2.1**, which fixes the R8 "error parsing
  Kotlin metadata" warnings on release builds and the stale lint opt-in false positives.
- CI robustness: installs `platform-tools`, pins `build-tools 36.0.0`, accepts SDK licenses,
  disables AGP's automatic SDK download (`android.builder.sdkDownload=false`) and uses the
  official Google/Maven Central repositories instead of the China mirrors. This fixes the
  "IO exception while downloading manifest" configuration failure on GitHub runners.
- YAML serialization keeps long scalars on one physical line (no 80-column folding that
  corrupted long rules/URLs) and allows up to 1000 aliases for anchor-heavy configs.
- CI now runs Android Lint in addition to detekt and unit tests, and has a concurrency
  guard and job timeout.
- Release notes moved to this file; the workflow extracts the matching section instead of
  shipping a hardcoded body.
- README corrected: the unreliable experimental app lock, screenshot protection and live
  throughput chart are no longer advertised.
- Added `LICENSE` (GPL-3.0) and `THIRD_PARTY_NOTICES.md` covering the bundled Mihomo core
  and other dependencies.
- `AndroidManifest.xml` declares the camera as optional (`required="false"`), so devices
  without a camera can still install the app.

## [1.0.5-Alpha.1] - 2026-10-25

First preview of the 1.0.5 line.

### Added

- external-controller based observation (loopback only, random per-process secret, injected
  by the runtime config and not overridable by subscriptions): request records with real
  connection ids, proxy chains, byte counters and per-connection close, DNS records, core
  status (memory, connections, uptime, traffic ranking) and providers.
- Subscription auto-update through WorkManager with a per-profile interval.
- Per-profile overrides: prepend rules, hosts mapping and DNS upstream override, applied in
  the same structural YAML pass as the protected-key stripping.
- Rule tester, per-app routing, QR subscription import, backup export/import, script
  template library, node detail sheet with latency history.
- In-app update check and APK download from GitHub Releases.
- Trilingual UI strings (简体中文 / English / Русский) and a system/iOS font switch.

### Fixed

- Kill switch (default on) closes the reconnect leak window.
- Plaintext third-party DNS blocking.
- IPv6 physical-network DNS is no longer dropped.
- Config synthesis is serialized with a mutex.
- Subscription URLs pointing at link-local addresses are rejected.

### Removed

- Experimental app lock (biometric unlock), screenshot protection and the live throughput
  chart were rolled back in 1.0.5-Alpha.2; they were unreliable and are no longer listed as
  features.
