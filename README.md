# 来电名片 · CallHelper

Android 来电人员识别助手。在系统来电界面上叠加人员名片，显示头像、姓名、岗位、单位与组织路径，并提供离线通讯录查询。

支持 Android 10 及以上，当前版本 **0.2.0**。普通来电识别无需 Root；小米背屏扩展需要 LSPosed / Vector 和受支持的背屏应用。

## 功能

- **离线识别**：按完整号码精确匹配，共享号码保留多个人员身份。
- **组织通讯录**：逐级浏览组织，按姓名、号码、岗位、单位和组织路径搜索，人员详情支持拨号。
- **加密同步**：HTTPS 下载 AES-256-GCM 加密的全量人员库；下载、认证或校验失败保留旧库。
- **本机保护**：Android Keystore 加密保存配置与人员库，排除系统备份，锁屏默认隐藏详情。
- **同步配置**：手动填写、扫码或导入二维码图片，支持配置二维码导出；安装包不预置服务地址或密钥。
- **来电卡片**：支持拖动、关闭、暂停、字号、组织路径及浅深色设置，不替换系统电话界面。
- **小米背屏**：可选原生联系人卡片，锁屏详情开关独立控制。
- **应用更新**：关于页提供仓库、作者、检查更新和更新记录；启动静默检查，Release 信息与 APK 优先使用 gh-proxy.com，失败回退 GitHub。下载并校验完成后自动打开系统安装器。

## 开始使用

1. 安装自行构建并签名的 APK，打开应用。
2. 按应用引导授予来电识别角色与悬浮窗权限；通话状态和通讯录权限按需授权。
3. 进入「数据同步」，点击齿轮图标，录入管理员提供的 HTTPS URL 和 Base64 解密密钥，或导入配置二维码。
4. 核对后点击勾选图标保存，再点击同步图标下载人员库。**保存配置本身不发起网络请求**；后续启动会后台刷新。

首次下载需要联网，之后可使用已保存的离线人员库。打开应用时，距上次成功更新超过 7 天会提醒更新；目前没有定时后台同步或增量同步。

应用版本检查与人员库同步分别处理：启动版本检查遇到网络问题不提示，手动检查失败会提供重试。更新安装首次需要系统“允许安装应用”授权，最终安装仍由系统确认；后台下载完成时返回应用后继续安装。更新说明见[关于与更新实现](docs/APP_UPDATES_20261108.md)。

配置二维码包含明文密钥，仅向可信人员分发。具体入口与格式见[同步配置](docs/SYNC_CONFIGURATION_20261108.md)。

### 小米背屏（可选）

在 LSPosed / Vector 启用「来电名片」模块，作用域仅选择背屏应用 `com.xiaomi.subscreencenter`，重启该应用，再在来电名片设置中开启「背屏联系人卡片」。当前仅适配背屏应用版本 `RELEASE-1.0.2609141923`，不代表其他 HyperOS 版本兼容。详见[背屏配置与验证](docs/LSPOSED_REAR_CARD_20261108.md)。

## 搭建后端

服务需要返回与客户端兼容的加密 CSV；本仓库包含 Android 客户端及联调工具，未包含现有业务后端源码。

[后端服务搭建指南](docs/BACKEND_SETUP.md)提供独立参考服务、字段与封包规范、Linux 部署、HTTPS 配置、密钥轮换和联调步骤。已有后端可直接按协议实现，无需采用示例技术栈。

## 构建与检查

构建配置以 Gradle 文件为准：JDK 21、Android SDK 37、target API 36、min API 29、Kotlin 2.4.21、AGP 9.3.3、Gradle 9.5.0。使用仓库附带的 Gradle Wrapper，无需另装 Gradle。

1. 克隆仓库并用 Android Studio 打开，安装对应 SDK。
2. 将 `JAVA_HOME` 指向 JDK 21 或兼容的 Android Studio JBR；由 Android Studio 生成本机 `local.properties`，或自行设置 `sdk.dir`。
3. 在仓库根目录执行：

```powershell
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew.bat :app:assembleRelease
python scripts/verify_private_assets.py
python -m unittest discover -s scripts -p test_private_assets.py
```

macOS / Linux 使用 `./gradlew` 替换 `./gradlew.bat`。Python 用于检查脚本，不是 Android 编译依赖。

Gradle 原始输出目录为 `app/build/outputs/apk/debug/` 和 `app/build/outputs/apk/release/`；直接执行 Gradle 的 Release 构建生成 unsigned APK。Debug 包仅供开发测试。

正式版使用本地 `.env` 中保存的发布身份，通过以下脚本完成检查、对齐、签名和验证，产物保存到 `artifacts/releases/`：

```powershell
# 仅首次生成；已有 .env 时拒绝覆盖
python scripts/build_release.py --init-signing
python scripts/build_release.py
```

完整说明见[正式版签名与打包](docs/RELEASE_BUILD.md)。`.env` 包含完整签名库与密码，不提交 Git，需安全备份；后续升级保持同一签名。

GitHub Actions 支持推送 `v<versionName>` tag 后自动检查、签名并发布 APK、SHA-256 与构建信息到 Release；带预发布后缀的版本自动标记为 Prerelease。首次使用需将 `.env` 中四项签名配置添加到仓库 Secrets，见[流水线配置](docs/RELEASE_BUILD.md#github-actions推送-tag-自动发布)。

构建不需要同步 URL、导出密钥或真实人员文件。不要将这些文件放入项目目录：构建会拒绝私有输入，并检查 APK 资源白名单。`app/libs/xposed-api82.jar` 是背屏扩展的编译依赖，需随源码一起保留，不会打包进 APK。

## 项目结构

```text
app/                 Android 应用、JVM 单测与设备测试
docs/                配置指南、架构、设计与历史验证记录
scripts/             协议联调、打包隐私检查与设计图工具
third_party/         第三方代码来源与许可
gradle/              Gradle Wrapper
```

## 兼容性与数据边界

Android 10/11 使用纯色图标导航，Android 12 及以上使用玻璃导航。来电角色、锁屏悬浮窗和后台存活受设备系统策略影响，构建与自动化检查不能替代目标设备的真实通话验收。

导出密钥与本机存储密钥相互独立。当前客户端仅支持不附带登录凭据的 HTTPS GET；共享解密密钥不构成服务端身份认证。协议不含数据版本或时间戳，不提供防重放保证。头像通过独立 HTTPS 请求加载，地址缺失或加载失败时显示姓氏占位。

## 推送 GitHub 前

- 执行上述构建、Lint、单测及隐私检查；APK 检查不等于整个仓库或 Git 历史的秘密扫描。
- 审阅 `git status --short` 与 `git diff --cached`，确认新源码、`app/libs/` 与文档已纳入，删除项符合预期。
- 排除真实人员数据、配置二维码、服务密钥、签名文件、私有截图和日志；`.gitignore` 不能移除已经跟踪或进入历史的文件。
- 确认第三方代码分发许可与项目授权方式。本仓库尚未提供项目级 `LICENSE`，第三方许可见 [NOTICE](third_party/NOTICE.md)。

Git 提交消息使用简体中文和约定式提交格式，例如 `docs: 完善后端部署指南与项目说明`。

## 文档

| 文档 | 内容 |
| --- | --- |
| [后端服务搭建](docs/BACKEND_SETUP.md) | 接口协议、参考服务、部署与排障 |
| [正式版打包](docs/RELEASE_BUILD.md) | 本地签名身份、打包脚本与产物校验 |
| [同步配置](docs/SYNC_CONFIGURATION_20261108.md) | 手动配置、二维码与密钥保存 |
| [当前架构](docs/architecture/ARCHITECTURE.md) | 同步、存储、来电与背屏实现 |
| [组织层级](docs/DIRECTORY_HIERARCHY_20261108.md) | 组织导航与索引 |
| [打包隐私检查](docs/PRIVACY_CLEANUP_20261108.md) | 检查范围、命令与历史结果 |
| [背屏配置](docs/LSPOSED_REAR_CARD_20261108.md) | 模块作用域与适配限制 |
| [第三方说明](third_party/NOTICE.md) | 复用代码来源与许可 |

[早期开发记录](docs/DEVELOPMENT_20261108.md)、[CSV 数据分析](docs/CSV_DATA_20261108.md)、[0.1.4 接口联调](docs/EMPLOYEE_EXPORT_20261108.md)与[头像验证](docs/AVATAR_DATA_20261108.md)包含历史状态；当前配置和协议以上述指南及源码为准。[界面原型](docs/design/v1/index.html)使用虚构人员，仅用于设计演示。
