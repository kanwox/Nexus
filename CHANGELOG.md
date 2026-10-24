# Changelog

## [1.0.3] - 2026-10-24

### Changed
- Upgraded Mihomo core to v1.19.33 (compiled with Go 1.27 and NDK 29; binary stripped to 56 MB).
- Simplified DNS settings to Fake-IP mode and IPv6 resolution toggles.
- Standardized font selection labels in Appearance settings to System Default and SF Pro.
- Adjusted primary screen top padding to 8 dp in accordance with iOS navigation standards.
- Restricted haptic feedback exclusively to bottom navigation and VPN toggle events.
- Reorganized dashboard shortcuts into a balanced 8-item grid.

### Fixed
- Fixed domain resolution capture in request logs for newer Mihomo log formats.
- Fixed UDP resolution tracking in connection monitoring.
- Resolved Android 15 edge-to-edge status bar and Compose lifecycle deprecation warnings.

### Removed
- Removed manual DNS server editor and presets from DNS settings.
- Removed Core Logs shortcut from dashboard.
