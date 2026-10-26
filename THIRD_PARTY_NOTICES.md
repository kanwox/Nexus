# Third-Party Notices

Nexus is distributed under the GNU General Public License v3.0 (see [LICENSE](LICENSE))
because it links against the GPL-licensed Mihomo core. The components below keep their own
licenses; this file satisfies the attribution requirements for binary distribution.

## Bundled native components

| Component | Version / source | License |
| :--- | :--- | :--- |
| Mihomo (Clash.Meta) core — `app/src/main/jniLibs/arm64-v8a/libclash.so` | <https://github.com/MetaCubeX/mihomo> | GPL-3.0 |
| Mihomo Android bridge — `libbridge.so`, `com.github.kr328.clash.core.*` | derived from <https://github.com/Kr328/ClashForAndroid> | GPL-3.0 |
| MetaCubeX rules data — `geoip.metadb`, `geosite.dat`, `Country.mmdb` | <https://github.com/MetaCubeX/meta-rules-dat> | GPL-3.0 |

The corresponding source for the GPL components is available at the upstream projects
above. If you redistribute this application, you must also comply with GPL-3.0 (provide the
license text, keep the notices, and make the corresponding source available).

## Third-party libraries

| Library | License |
| :--- | :--- |
| AndroidX / Jetpack Compose / WorkManager / CameraX | Apache-2.0 |
| Kotlin, kotlinx-coroutines, kotlinx-serialization | Apache-2.0 |
| OkHttp / Okio | Apache-2.0 |
| SnakeYAML | Apache-2.0 |
| ZXing (`com.google.zxing:core`) | Apache-2.0 |
| quickjs-kt (`io.github.dokar3`) / QuickJS | MIT |
| JUnit 4 | EPL-1.0 |

## Fonts

The optional "SF Pro / PingFang SC" UI font setting references fonts that may be installed on
the device (`DeviceFontFamilyName`). No Apple font files are bundled with this application;
when the fonts are absent the system default is used.
