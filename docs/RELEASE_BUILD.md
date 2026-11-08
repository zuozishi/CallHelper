# 正式版签名与打包

正式版使用本地独立 RSA-4096 签名身份。完整 PKCS12 签名库以 Base64 保存在项目根目录 `.env`，同一文件还保存密码与别名；它已被 Git 忽略，不进入源码资源、Gradle 配置或 APK。Base64 是编码，不是额外加密，持有该文件就能使用发布身份，应安全备份并限制访问。

## 环境

- Python 3.10 及以上，脚本仅使用 Python 标准库。
- JDK 21，设置 `JAVA_HOME`，须包含 `java` 和 `keytool`。
- 与项目匹配的 Android SDK；通过 `local.properties` 的 `sdk.dir` 或 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 指定。
- Android SDK Build Tools 35.0.0 或以上，需包含 `zipalign` 和 `lib/apksigner.jar`；脚本选择已安装的最高稳定版本。
- Git，用于确认 `.env` 未被跟踪并记录构建来源。

在仓库根目录执行以下命令。脚本也支持从其他工作目录调用，所有项目路径按脚本位置解析。

## 首次生成（仅一次）

```powershell
python scripts/build_release.py --init-signing
```

脚本用 `keytool` 生成 RSA-4096 / SHA256withRSA 签名库，自签名证书有效期为 10000 天，别名为 `callhelper-release`。密码由系统安全随机数生成，不打印到终端或传入命令行参数。生成期间只在临时目录保存 PKCS12 文件，结束后删除，长期仅保留 `.env`。

`.env` 中只允许以下四项，不执行 shell 语法、变量展开或额外配置：

| 配置项 | 内容 |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | PKCS12 文件的标准 Base64，单行保存 |
| `RELEASE_STORE_PASSWORD` | 签名库密码 |
| `RELEASE_KEY_ALIAS` | 签名条目别名 |
| `RELEASE_KEY_PASSWORD` | 私钥密码（生成时与签名库密码相同） |

[.env.example](../.env.example) 仅描述结构，空值不能用于签名。初始化拒绝覆盖任何已存在的 `.env`；不要删除它来解决构建问题。换电脑时恢复原文件，不重新生成签名身份。

## 打包

```powershell
python scripts/build_release.py
```

脚本依次：

1. 检查 `.env` 已被 Git 忽略、未被跟踪，并校验签名字段。
2. 执行 `assembleRelease`、`testDebugUnitTest`、`lintDebug`，失败立即退出。
3. 检查 unsigned APK，并额外扫描 `.env` 中的密码与签名库 Base64，避免私有值进入包内。
4. 使用 `zipalign` 做 16 KiB 页对齐，临时还原签名库，再用 `apksigner` 的 v2 / v3 方案签名；项目最低 Android 10，无需 v1，也不生成 v4 的独立 idsig 文件。
5. 验证 APK 签名、签名证书与本地身份一致、ZIP 对齐及签名后 APK 隐私检查。
6. 输出正式 APK、校验文件与公开构建信息，清理临时签名文件。

密码只通过子进程环境变量交给 `keytool` / `apksigner`，不交给 Gradle 或其常驻 daemon。临时目录由上下文管理器在成功或一般异常时清理；若强制结束进程或系统崩溃，需检查 `artifacts/releases/signing-*` 中是否残留签名库，并按实际目录清理。

输出示例（版本号读取 Gradle 的产物元数据）：

```text
artifacts/releases/callhelper-0.2.0-release.apk
artifacts/releases/callhelper-0.2.0-release.apk.sha256
artifacts/releases/callhelper-0.2.0-release.apk.json
```

JSON 包含包大小、APK SHA-256、证书 SHA-256、版本号、Git 提交及工作区是否有改动，不包含密钥或密码。相同版本重复构建会替换同名产物；只有检查全部通过后才替换 APK。`artifacts/` 整体被 Git 忽略。

直接执行 `./gradlew.bat :app:assembleRelease` 仍生成 unsigned APK，签名只在上述脚本中执行；普通 Debug 开发无需 `.env`。

## 保存发布身份与升级

将 `.env` 加密备份到独立安全位置，并限制本地目录权限；不要发送到公开聊天、GitHub、网盘公开分享或日志。丢失密钥可能无法为现有安装发布可覆盖升级的版本。

后续正式版保持同一签名，并在 `app/build.gradle.kts` 提升 `versionCode`，按版本计划更新 `versionName`。正式签名与过去的 Debug 签名不同，通常无法覆盖安装 Debug 版；卸载会删除本机配置和离线库，迁移应由用户自行安排。本地打包脚本只打包，不安装、不上传、不提交；下面的 GitHub Actions 工作流负责自动发布。

独立复查：

```powershell
python -m unittest discover -s scripts -p 'test_*.py'
python scripts/verify_private_assets.py
git check-ignore .env
git status --short
```

隐私检查脚本会在 `.env` 存在时校验其结构，并在 Debug / unsigned Release 中扫描签名私有值；正式包由打包脚本在签名前后检查。检查不能替代整个仓库及 Git 历史的秘密扫描。

签名工具参数依据 [Android apksigner 官方文档](https://developer.android.com/tools/apksigner)、[命令行构建说明](https://developer.android.com/build/building-cmdline)及 [JDK 21 keytool 文档](https://docs.oracle.com/en/java/javase/21/docs/specs/man/keytool.html)。

## GitHub Actions：推送 tag 自动发布

工作流：[.github/workflows/release.yml](../.github/workflows/release.yml)。仅推送 `v*` tag 时触发，普通分支提交不会发布。tag 必须等于 `v` 加当前 `versionName`，如 `v0.2.0`；`v1.0.0-rc.1` 这样的后缀版本会标记为 Prerelease，且 `versionName` 也必须为 `1.0.0-rc.1`。

### 首次配置 Secrets

在仓库 **Settings → Secrets and variables → Actions → Repository secrets** 配置四项，名称和对应值与本地 `.env` 完全一致：

- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

必须复用原签名身份，不在 CI 中生成新密钥。若已安装 GitHub CLI 并登录，可在项目根目录执行下面的命令，将本地 `.env` 导入当前仓库 Secrets（这是上传秘密配置的操作，仅在准备好目标仓库后执行）：

```powershell
gh secret set --env-file .env
```

GitHub CLI 参数见[官方 secret set 文档](https://cli.github.com/manual/gh_secret_set)。仓库需要允许 Actions 运行及工作流请求 `contents: write` 权限；组织策略可能进一步限制该权限。发布步骤使用自动提供的 `GITHUB_TOKEN`，无需另配个人访问令牌。

### 发布流程

1. 提升 `versionCode`，设置 `versionName`；提交代码、签名脚本及工作流，确认 `.env` 没有被跟踪。
2. 将代码和版本 tag 推送到目标仓库，例如版本为 `0.2.0` 时：

```powershell
git push origin main
git tag -a v0.2.0 -m "发布 0.2.0"
git push origin v0.2.0
```

仅在 tag 尚不存在时创建；失败后从 Actions 页面重跑原任务，无需移动已发布 tag。若默认分支名称不同，替换上面的 `main`。

流水线使用 Ubuntu 24.04、JDK 21、Android SDK 37 和 Build Tools 36.1.0，先校验 tag、运行 Python 工具测试，再从 Secrets 临时恢复 `.env`，调用同一本地脚本完成 JVM 测试、Lint、构建、对齐、签名及私有值检查。`.env` 和临时签名目录在结束时清理，不上传为附件或缓存。

检查通过后上传以下三项到该 tag 的 Release：签名 APK、`.apk.sha256`、`.apk.json`。新 Release 先创建为草稿，附件上传成功后公开并自动生成发布说明；任务重跑时上传并替换同名附件，保留已有发布说明。若仓库启用不可变 Release，已公开的附件不能替换，需要发布新版本。

脚本在构建前及读取 APK 元数据后均验证 tag / `versionName`，防止 tag 名称与 APK 版本不符。它不自动递增版本或替你创建 tag；上面的示例值应替换为实际版本。首次线上运行仍需确认 SDK 下载、仓库权限、Secrets 和 Release 上传均正常。

发布命令依据 [GitHub CLI release create](https://cli.github.com/manual/gh_release_create) 和 [release upload](https://cli.github.com/manual/gh_release_upload) 官方文档。
