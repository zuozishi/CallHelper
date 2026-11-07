# 来电知人 · CallHelper

Android 来电人员识别助手，保留原电话页面，在其上方叠加姓名、岗位、单位和组织路径。Android 10+，当前开发版本 `0.1.0`。

## 当前功能

- 本地 CSV 导入与完整校验，失败保留旧人员库。
- Android Keystore + AES-256-GCM 加密保存，AtomicFile 原子替换，排除备份。
- 离线精确查号；按姓名、岗位、号码、单位与组织路径搜索。
- Miuix 0.9.4 界面和真正的 AndroidLiquidGlass LiquidBottomTabs 导航。
- 来电识别与悬浮窗权限引导；可选通话结束监听和通讯录来电识别权限。
- 独立来电卡片、拖动、关闭、浅深色、组织路径与字号设置，锁屏隐藏人员详情。
- 人员头像：可选 AvatarUrl 字段，缺失或加载失败使用姓氏占位。当前测试 CSV 没有头像地址。
- 预留加密 CSV 下载及解密接口；后端协议尚未给出，无真实 API 请求或定时同步。

## 开发与测试

Kotlin 2.4.21、AGP 9.3.3、Gradle 9.5.0、JDK 21、Android SDK 37，target 36。`local.properties` 指定本机 SDK，`JAVA_HOME` 指向兼容的 JDK / Android Studio JBR。

```powershell
python scripts/prepare_test_data.py --csv data/export_261108004228.csv --copy-fixtures
./gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew.bat :app:connectedDebugAndroidTest
```

Debug 首次启动自动导入本机测试 CSV。真实数据置于被忽略的 Debug / androidTest `assets/private/`，不进入 Git；本机 Debug APK 包含该数据。Release 无内置人员库，使用文件选择器导入。设备测试中的 4,300 人与 417 个单位对应此次指定文件。

本次开发不使用旧 SQLite 及其密码。正式 API 下载并认证解密后接入同一导入管线。

## 资料

- [CSV 分析与接入规范](docs/CSV_DATA_20261108.md)
- [当前架构](docs/architecture/ARCHITECTURE.md)
- [开发验证](docs/DEVELOPMENT_20261108.md)
- [界面设计](docs/design/v1/DESIGN.md) / [设计原型](docs/design/v1/index.html)
- [第三方代码](third_party/NOTICE.md)

早期设计图采用虚构人员和数量；原生 APP 使用当前导入数据，按设备空间适配。设计图导出：`python scripts/render_design.py`，需 Python Playwright 与本机 Edge。
