# Shizuku 背屏联系人卡片

> 历史记录：用户反馈效果不佳，0.1.9 已完全移除 Shizuku 依赖、授权入口及 Activity 移动路径，改用 [LSPosed 原生服务助手卡片](LSPOSED_REAR_CARD_20261108.md)。以下仅保留 0.1.8 的历史验证。

日期：2026-11-08；版本：0.1.8（versionCode 9）。

## 使用

1. 在 Shizuku 中启动服务。
2. 来电名片 → 设置 → 开启「背屏联系人卡片」。
3. 点击盾牌按钮授权 Shizuku，点击预览图标测试。
4. 需要手机锁屏时看到完整卡片时，单独开启「背屏锁屏显示详情」。默认只显示解锁提示，主屏仍隐藏详情。
5. 来电识别及通话结束监听授权继续使用原有入口。未匹配来电遵循现有隐藏设置。接通约 5 秒收起、挂断收起，最长 30 秒。

人员详情内也提供此人的背屏预览图标。整个功能默认关闭。Shizuku 不可用不影响原主屏卡片。

## 实现

- 官方 Shizuku API/provider 13.1.5，使用 UserService/AIDL，不调用已废弃的 newProcess。
- RearCardController 保存内存卡片、随机令牌、启动任务和有效期。来电查询同时分发至原 OverlayController 和背屏控制器。
- 在 Xiaomi 设备中动态查找唯一非主屏内置显示屏，不写死 displayId；不选择普通投屏目标。无法明确识别时不发送。
- 特权服务仅接受 displayId、userId、令牌；目标 Activity 固定，参数直接传给 ProcessBuilder，不经过 shell 拼接。联系人、号码和头像地址不进入 shell 参数。
- RearCardActivity 使用独立任务，屏幕尺寸自动布局，多身份可滚动，保持必要图标的无障碍名称。
- Activity 入口要求 DUMP 权限（供 shell/root 调用），并验证随机令牌、内存卡片和当前目标显示屏；进程重建不恢复旧卡片。无效令牌立即退出。兼容路径允许透明任务短暂停留主屏，但绝不在主屏绘制联系人，3 秒内未移动即退出。
- 页面在目标显示屏实际恢复后回传确认，控制器才显示「背屏已显示」。服务连接、命令执行和显示确认均有限时；主线程不执行阻塞 Binder 命令。
- 关闭功能、挂断、超时和 Shizuku Binder 失效时清除卡片。旧令牌不能清除新来电卡片。
- 锁屏隐私单独控制，收到锁屏/解锁状态变化重新渲染；详情默认隐藏，窗口启用 FLAG_SECURE。
- 直接背屏启动被拒绝时，创建透明、非焦点、无预览、无动画的独立卡片任务，再用 `am stack list` 找到固定组件对应的任务，通过 `am display move-stack` 移到背屏。只移动专用卡片任务，不移动主页面或系统拨号任务。页面确认到达目标屏后才绘制联系人。
- 只在卡片有效期保持页面唤醒，不修改 DPI、旋转、系统背屏超时，不停止背屏中心或电话进程。透明任务仍可能短暂触发主屏 Activity 生命周期变化，真实来电场景必须专项验收。

## 当前证据与边界

通过 ADB 读取到目标设备 2509FPN0BC / popsicle，系统 OS4.0.6.0.XPBCNXM，内置背屏为 976×596（当次 displayId 为 1）。Shizuku 已安装，观察到 root 身份服务进程。

Debug/Release 构建、单元测试和 Debug Lint 已通过。新增命令边界与任务选择测试；总计 32 项单元测试。设备测试 `RearCardDeviceTest` 已在目标手机通过（OK，1 test），覆盖专用页面在目标屏恢复、窗口可见、显示确认、卡片替换、旧令牌清理隔离、未匹配隐藏、超时和关闭清理。设备测试不使用真实人员数据，不等同于物理屏幕目视验收。

USB 传输多次断开，最终使用用户建立的无线 ADB，保留原数据安装 0.1.8 Debug 和设备测试包。Shizuku 独立授权已由用户完成。观察到两个同源 Shizuku 服务同时分发 Binder，授权只到其中一个、另一个拒绝 UserService；停止未授权的重复进程后调用恢复。

真机对直接 `am start --display` 返回 `Error: Activity not started, unknown error code 102`。独立 Presentation 与副屏 Overlay 探针未通过窗口可见断言，因此未将它们接入正式路径。改为透明任务移动后，背屏启动及生命周期测试通过。

HyperOS 4 当前固件上的锁屏启动、后台真实来电触发、系统原生来电背屏卡片抢占、真实接听/挂断及连续来电仍待实测。当前测试通过控制器模拟来电数据与清理，不会拨打真实电话。不持续抢占系统背屏，不更改默认拨号器。

## 验证命令

```powershell
./gradlew.bat :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
adb install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
adb install --no-streaming -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# 真机测试前先通过 Shizuku 授权界面授权，也可在开发设备用 adb 授予其运行时权限。
adb shell am instrument -w -r -e class com.zuozishi.callhelper.RearCardDeviceTest com.zuozishi.callhelper.test/androidx.test.runner.AndroidJUnitRunner
```

构建产物：app/build/outputs/apk/debug/app-debug.apk；Release 默认产物为未签名 APK，不作为已安装版本报告。

## 参考

- https://github.com/RikkaApps/Shizuku-API
- https://github.com/totallynotkasai/mi-back-screen

命令行为参考公开实现，未复制其常驻背屏接管逻辑。目标固件已验证透明任务移动路径；其他固件与真实锁屏来电仍需验证。
