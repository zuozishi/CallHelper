# 关于与应用更新实现

日期：2026-11-08。已按 `docs/design/about-update-v1/` 的设计实现。版本号保持 0.2.0，本次没有发布新的 GitHub Release 或安装到用户手机。

## 功能

- 第五个导航图标为“关于”，保留无障碍名称和选中语义。页面展示当前版本、检查更新、GitHub 仓库、作者及更新记录，适配深浅色主题。
- 打开主界面时，每个应用进程自动检查一次稳定版本。后台异步请求不阻塞人员库；自动检查无新版或网络失败均不显示提示、警告或错误状态。手动检查会显示进行中、已是最新版本或“无法连接，请重试”。
- Release 元信息和 APK 均先访问 `https://gh-proxy.com/` 加 GitHub 完整 URL，失败后回退官方。元信息解析、下载长度、摘要或 APK 验证失败也触发回退。取消任务不触发重试。请求有超时和大小限制，不发送 GitHub 令牌或人员库同步配置。
- 新版弹层显示版本、包大小和说明；支持下载、稍后、收起和取消。收起后下载继续，关于页显示进度并可重新展开。长发布说明单独滚动，主要操作保持可见。
- 下载写入私有临时文件，先检查 Release 给出的大小与可用的 SHA-256，再使用 apksig 验证 APK 加密签名，并核对当前签名、包名、版本名、versionCode、最低系统要求及原生库 ABI。通过后才转为待安装文件，使用 FileProvider 的只读内容 URI 交给安装器。
- 前台下载完成后自动调起系统安装器；尚未允许安装时先展示用途说明，进入系统设置授权，返回后自动继续。等待授权标记持久化，进程重建后重新验证待安装文件再继续。拒绝授权或取消安装后保留“安装更新”入口，不循环强制跳转。
- 后台完成下载时保留待安装状态，回到前台继续；不绕过 Android 后台启动限制。应用进程被系统终止时不承诺继续下载：下次删除未完成文件，重新下载。已下载且验证通过的文件可以恢复，过期或已安装的包会被清理。
- 更新记录从 Release 列表获取并缓存最近 30 项稳定版本。离线保留缓存和内置公开 0.2.0 记录，刷新失败提供重试。远端说明使用有限长度纯文本展示，不执行 HTML；源码保留公开仓库与作者链接。

应用更新与人员库过期提醒分开管理，人员库或人员详情对话框存在时暂缓显示更新弹层。应用更新不更改人员库同步配置，也不包含人员数据。

## 代码入口

| 文件 | 职责 |
| --- | --- |
| `update/ReleasePolicy.kt` | 稳定版本比较、可信下载路径、APK 选择和双路回退 |
| `update/ReleaseClient.kt` | 元信息请求、有限下载、进度与摘要验证 |
| `update/ApkVerifier.kt` | APK 加密签名及升级兼容性验证 |
| `update/UpdateController.kt` | 进程内任务去重、检查/下载/安装/历史状态及文件恢复 |
| `ui/AboutPage.kt` | 关于页、更新记录及底部更新弹层 |
| `MainActivity.kt` | 第五导航入口、前台自动安装与系统授权结果恢复 |
| `res/xml/update_paths.xml` | 私有更新目录的 FileProvider 路径 |

路径均相对于 `app/src/main/java/com/zuozishi/callhelper/`，资源路径除外。签名目前要求与已安装版本当前签名完全一致，不支持切换签名身份或签名轮换；Debug 包无法覆盖正式签名版本。

## 验证结果

| 验证 | 结果 |
| --- | --- |
| JVM 单元测试 | 共 47 项通过，其中新增 12 项更新流程测试 |
| 模拟器设备测试 | 7 项通过：关于页/离线历史/深色、自动静默与手动失败、新版弹层/进度/授权布局、APK 验证、下载完成请求安装、授权继续与系统安装器入口 |
| 加强安装器检查 | 额外 1 次通过，确认系统安装器有“更新/安装”操作；没有点击确认安装 |
| Debug / Release 构建 | 通过；Release 产物为 unsigned |
| Lint Debug | 0 错误、33 警告，主要为既有配置、依赖和 KTX 风格建议 |
| 打包隐私检查 | Debug 与 Release 均通过 |
| 镜像与官方 Release API | 两路实际返回 200，当前发布版本均为 v0.2.0 |
| 镜像与官方 APK 下载 | 两路均完整读取 24,251,625 字节，大小和 SHA-256 与 Release 元信息一致；该网络探测从 Windows 执行 |

模拟器为只读会话，设备 ID `emulator-5554`。没有修改连接中的 2509FPN0BC 手机。签名与安装测试使用独立的公开虚拟 APK，覆盖有效新版、错误签名、错误包名和旧版本；虚拟 APK 不包含应用业务代码，仅用于验证，不可作为发行包。

现有正式 Release 为 0.2.0，尚无真实更高版本用于完整覆盖升级验收。因此“正式签名旧版 → 正式签名新版”的真实设备升级仍需新版本发布后验证；已分别验证有效新版文件完成后产生自动安装请求，以及系统安装器可以打开并提供确认操作。

实际截图位于 `artifacts/update-screens/`；包含关于页浅深色、更新记录、发现新版、下载进度、授权说明和系统安装器。`artifacts/update-device-tests.txt` 保存最终设备测试输出，`artifacts/update-installer-test.txt` 保存加强安装器检查输出。

## 复验

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug :app:assembleRelease
python scripts/create_update_test_fixtures.py
```

设备测试前仅向测试模拟器安装 Debug 及 androidTest APK。通过 `adb push` 将 `artifacts/update-fixtures/{next,old,wrong-signer,wrong-package}.apk` 写入 `/data/local/tmp/`，再通过 `run-as com.zuozishi.callhelper` 复制到应用 `files/update-fixtures/`；不能直接把 shell 创建的外部存储目录当成应用可读目录。

```powershell
adb -s emulator-5554 shell am instrument -w -e class com.zuozishi.callhelper.AboutUpdateDeviceTest,com.zuozishi.callhelper.ApkUpdateDeviceTest,com.zuozishi.callhelper.UpdateDialogDeviceTest com.zuozishi.callhelper.test/androidx.test.runner.AndroidJUnitRunner
```

`ApkUpdateDeviceTest` 必须在隔离模拟器执行，会允许测试应用请求安装并打开系统安装器，但不会确认安装。签名样本生成器只使用 Android Debug 签名与一次性测试签名，不读取 `.env` 中的正式签名。

## 外部依据

- [GitHub Releases API](https://docs.github.com/en/rest/releases/releases)：公开稳定 Release 和资源下载地址。
- [GH-Proxy 使用说明](https://gh-proxy.com/docs)：GitHub API 与 Release 下载代理。
- [Android 安装来源授权](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls())：普通应用需安装来源授权，最终由系统安装器确认。
