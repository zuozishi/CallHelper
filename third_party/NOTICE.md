# 第三方代码

二维码扫描使用 [ZXing Android Embedded 4.3.0](https://github.com/journeyapps/zxing-android-embedded)，二维码编解码使用其依赖 ZXing Core，均采用 Apache License 2.0。

`app/src/main/java/com/kyant/backdrop/catalog/` 来自 Kyant 的 AndroidLiquidGlass Catalog 示例组件及辅助实现，Apache License 2.0。

- [上游项目](https://github.com/Kyant0/AndroidLiquidGlass)
- [LiquidBottomTabs](https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt)
- 基础版本：`65ab177e90e5c1d8c62e70cf7755841982da65f6`，保留原文件来源头。
- 复用本机 AutoSync 工程的 Android 适配版本（帧调度绑定、主题输入及测试标识），未复制其业务逻辑。
- 许可全文见 `LICENSE-AndroidLiquidGlass.txt`。

其他运行依赖包括 Miuix、AndroidX / Jetpack Compose、Kotlin / kotlinx.coroutines 与 Kyant Backdrop / Shapes；本文件不改变上游许可。

`app/libs/xposed-api82.jar` 复用用户提供 RearHaPoc 示例内的官方 Xposed API 82，仅为 compileOnly 构建依赖，不打包进 APK。API 文档：https://api.xposed.info/ 。`NativeRearHook` 的原生工厂、类型 3 RemoteViews 与服务助手显示流程改编自用户提供的 RearHaPoc/RearHook.java，增加来电状态、卡片移除、宿主过期清理和访问控制，不包含示例的 Home Assistant 模拟业务。
