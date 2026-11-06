# 本机构建速查

## 环境

| 用途 | 路径 |
|---|---|
| JDK 21 | `E:\AndroidEnv\jdk\jdk-21.0.11+10` |
| Android SDK | `E:\AndroidEnv\sdk` |
| 依赖缓存 | `E:\gradle-home` |

- 旧环境 `C:\Users\kuan\.android_build_env\` 已删除，系统 `JAVA_HOME` 仍指向它。
- `gradle.properties` 已钉住 `org.gradle.java.home`，Gradle 本身不再依赖 `JAVA_HOME`，但 `gradlew.bat` 启动脚本仍读它，终端里要手动覆盖（见下方命令）。
- `local.properties` 的 `sdk.dir` 已指向 `E:\AndroidEnv\sdk`。

## 命令

系统 `JAVA_HOME` 指向已删除的旧路径，`gradlew.bat` 启动时会读它报错。每次开新终端先设一次：

```powershell
$env:JAVA_HOME = "E:\AndroidEnv\jdk\jdk-21.0.11+10"
$env:GRADLE_USER_HOME = "E:\gradle-home"
Set-Location "E:\kuan\Mihomo"

# 测试
.\gradlew.bat :app:testDebugUnitTest --offline

# 完整门禁
.\gradlew.bat :app:testDebugUnitTest :app:detekt :app:checkUiTokens :app:lintDebug --offline
```

## 坑

1. gradle.org / Google Maven 直连超时，一律加 `--offline`。新增依赖时去掉 `--offline` 并先开代理。
2. 报 `Timeout waiting to lock`：先 `Get-Process java | Stop-Process -Force`。
3. Android Studio 自带 JBR 是 JDK 25，detekt 不认（`Invalid value (25) passed to --jvm-target`），必须用上面的 JDK 21。
4. 不要删 `E:\gradle-home` 和 `E:\AndroidEnv`。

## 2026-10-30

评审建议 2/3/4/5/9 已实现。源码备份：`backup-appsrc-20261030.zip`。验证：224 tests / detekt / checkUiTokens / lintDebug 全绿。
