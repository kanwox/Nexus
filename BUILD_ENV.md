# 本机构建与门禁速查

## 环境

| 项目 | 值 / 路径 |
|---|---|
| JDK 21 | `E:\DevEnv\AndroidEnv\jdk\jdk-21.0.11+10`（Eclipse Adoptium `21.0.11+10-LTS`） |
| Android SDK | `E:\DevEnv\AndroidEnv\sdk`（`local.properties` 中配置 `sdk.dir`） |
| Gradle 缓存 | `E:\DevEnv\gradle-home` |
| 仓库路径 | `E:\Projects\Nexus` |
| 工具链版本 | Gradle `9.8.2`（内嵌 Kotlin DSL `2.4.10`）、AGP `9.4.1`（内置 Kotlin；Compose / Serialization 插件 `2.4.21`） |

- `gradle.properties` 未配置 `org.gradle.java.home`，`gradlew.bat` 依赖终端中的 `JAVA_HOME`、`ANDROID_HOME` 与 `GRADLE_USER_HOME`。
- `gradle.properties` 已启用 `org.gradle.configuration-cache=true`、`org.gradle.caching=true`、`org.gradle.parallel=true` 及 `android.builder.sdkDownload=false`，需预先在 SDK 中安装 `platform-tools`、`platforms;android-37.0`、`build-tools;37.0.0`。

## 常用命令

```powershell
$env:JAVA_HOME = "E:\DevEnv\AndroidEnv\jdk\jdk-21.0.11+10"
$env:ANDROID_HOME = "E:\DevEnv\AndroidEnv\sdk"
$env:GRADLE_USER_HOME = "E:\DevEnv\gradle-home"
Set-Location "E:\Projects\Nexus"

# 单元测试（离线）
.\gradlew.bat :app:testDebugUnitTest --offline --no-daemon --console=plain

# 完整质量与发布门禁（与 .github/workflows/build.yml 对齐）
.\gradlew.bat :app:detekt :app:testDebugUnitTest :app:checkUiTokens :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleRelease --offline --no-daemon --console=plain
```

## 注意事项

1. AGP 9.4.1 使用内置 Kotlin（已移除独立的 `org.jetbrains.kotlin.android` 插件），Detekt `1.23.8` 仅注册 `:app:detekt`（无 `:app:detektDebug` 变体任务）。
2. 本机无法直连远端仓库时保持 `--offline`；新增或升级依赖后需联网/走镜像生成校验清单，归一化 `gradle/verification-metadata.xml` 为 LF 后再不带 `--write-verification-metadata` 重跑全量门禁确认通过：
   ```powershell
   .\gradlew.bat :app:detekt :app:testDebugUnitTest :app:checkUiTokens :app:lintDebug :app:compileDebugAndroidTestKotlin :app:assembleRelease --write-verification-metadata sha256 --no-daemon --console=plain
   ```
3. 遇到 `Timeout waiting to lock` 时排查残留 `java` 进程；Detekt `1.23.8` 不支持 JDK 25（例如 Android Studio JBR），必须使用 JDK 21。
4. 仓库通过 `.gitattributes` 统一行尾：源码、配置与文档强制 LF，仅 `*.bat` 使用 CRLF。
5. **发布签名内置说明（有意设计）**：`app/nexus-release.jks` 与 `app/build.gradle.kts` 中的默认回退签名凭据为项目有意保留，用于保证本地离线构建与 CI 发布无需额外配置即可产出签名一致的 Release APK（如需替换，可通过 `local.properties` 或 `RELEASE_*` 环境变量覆盖）。后续代码审查无需重复将其报告为密钥泄露缺陷。

## 最新全量门禁实测记录（v1.0.7）

- 严格无 `--write-verification-metadata` 门禁及 `--offline` 复验均 `BUILD SUCCESSFUL`（配置缓存已生效）：
  - `:app:detekt`：0 issues（全量覆盖含 `ui/`，已开启复杂度/异常/协程规则）
  - `:app:testDebugUnitTest`：44 suites / 353 tests 全部通过（0 failed / 0 errors / 0 skipped）
  - `:app:checkUiTokens`：扫描 71 个 Kotlin 文件，0 违规
  - `:app:lintDebug`：0 errors（14 warnings/hints）
  - `:app:compileDebugAndroidTestKotlin`：通过
  - `:app:assembleRelease`：通过（R8 裁剪启用，已关闭混淆 `-dontobfuscate`；`app/build/outputs/apk/release/app-release.apk` 为 `versionCode = 27` / `versionName = "1.0.7"`，大小 `28.05 MiB` / `29,410,500` bytes，`apksigner verify` v2 签名校验通过）
  - `gradle/verification-metadata.xml`：462 个 `<component>` 校验条目，LF 换行
