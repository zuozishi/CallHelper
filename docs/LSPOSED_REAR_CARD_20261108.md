# LSPosed 原生背屏联系人卡片

版本：0.1.9（versionCode 10）；日期：2026-11-08。

## 当前实现

按用户提供的 `C:\Users\Zuozishi\Desktop\workspace\手机备份\artifacts\rear-ha-poc-20261108\RearHaPoc` 改造，使用原生服务助手容器展示联系人卡片。

- 模块包名 `com.zuozishi.callhelper`，入口 `com.zuozishi.callhelper.rear.NativeRearHook`。
- 作用域仅 `com.xiaomi.subscreencenter` / 用户 0；不注入 system_server 或拨号器。
- 仅匹配原生背屏应用版本 `RELEASE-1.0.2609141923`。不匹配时不注入卡片并在设置显示不支持。
- `SmartAssistantPanel.e(List,int)` → `x2.a` 类型 3 → `E2.w.g()` → `E2.p` 原生 RemoteViews。只增删本模块 ID，保留其他业务卡片。
- 显示请求调用原生 `setAssistantVisibleImpl`，仅唤醒背屏；不启动或移动 APP Activity，不改显示密度、旋转或系统背屏超时。
- 移除 Shizuku API/provider、权限、AIDL/UserService、背屏 Activity 与透明任务转移实现。

卡片包含头像或姓名占位、姓名、岗位、单位、按设置显示的组织路径和关闭图标。多身份时显示第一身份及额外匹配数量。沿用示例的 112dp 左侧遮挡区预留，关闭图标有无障碍名称。

## 状态与隐私

联系人与头像保留在 APP 内存；Provider 仅返回当前卡片 RemoteViews，不提供人员库接口。除本 APP UID 外，调用方必须同时匹配原生背屏包 UID 与调用包名。

ContentObserver 通知宿主更新；无有效来电/预览时移除本卡片。APP 与宿主都检查单调时钟期限，最长 30 秒；接通约 5 秒收起、挂断移除沿用原监听。即使 APP 状态清理通知丢失，宿主也有期限回调。重启 APP 不恢复旧卡片。

关闭使用不可变、显式 PendingIntent，指向不导出的接收器；每张卡片使用独立 URI 令牌，旧卡片关闭事件不能删除新卡片。头像异步加载，旧任务完成后校验令牌再更新。

普通卡片与 AOD RemoteViews 分别渲染；默认 AOD/锁屏隐藏姓名、单位、组织与头像。用户可单独开启背屏锁屏显示详情。脱敏显式清除图片与可见性动作，避免 RemoteViews reapply 保留旧头像。

原生容器可能复制卡片对象，挂载时重新关联实际宿主对象，再更新其 RemoteViews。布局显式使用 `android.widget.ImageView`，防止容器 AppCompat inflater 替换为不支持 RemoteViews `setImageBitmap` 的控件。

## 手机安装与验证

目标：2509FPN0BC / popsicle，OS4.0.6.0.XPBCNXM，背屏应用版本匹配。已通过无线 ADB 保留数据安装 0.1.9 Debug；Vector 已启用本模块，作用域核验只有背屏应用/用户 0。仅重启背屏应用加载模块，没有禁用其他模块。

已通过：

- Debug 与 Release 构建、29 项既有单元测试、Debug Lint。
- `RearCardDeviceTest.nativeCardLifecyclePrivacyAndProviderBoundary`：OK（1 test）。测试覆盖原生宿主挂载确认、卡片替换、旧令牌隔离、模拟通话结束清理、未匹配隐藏、期限清理、关闭 PendingIntent、关闭功能、AOD 文本脱敏和 shell 非授权读取拒绝。
- 日志观察到 `HOOKS_READY`、`CARD_CREATED type=E2.p`、`HOST_CREATED native RemoteViews`、`ATTACHED active=true attached=true`、`SHOW_REQUESTED native assistant display=1`，结束后 `active=false attached=false`。
- 背屏截图已目视检查，使用合成联系人数据，无真实人员信息：`artifacts/rear-lsposed-20261108/native-rear.png`。

设备测试通过控制器模拟来电，不拨打真实电话；未将它描述为真实来电验收。真实来电与系统电话背屏卡片的优先级、真实接听/拒接/挂断、物理触摸关闭、防误触、长时间 AOD 与功耗仍待实际使用确认。截图不等同于物理屏幕触摸测试。

## 使用与回退

设置 → 开启「背屏联系人卡片」→ 预览图标。模块管理盾牌图标打开 Vector / LSPosed。背屏朝上并保持传感器无遮挡；原生防误触遵循系统行为。

背屏设置及人员详情中的背屏预览入口仅对 Xiaomi 17 Pro、17 Pro Max、18 Pro、18 Pro Max 可见。按 Xiaomi 厂商与完整机型名称白名单判断；Build.MODEL 为认证编号时在后台读取公开的 ro.product.marketname，不需要特权。其他机型隐藏整个背屏设置区。界面可见性与原生背屏应用版本兼容检查分别处理，显示入口不表示已适配其他背屏固件版本。

关闭背屏开关会删除当前卡片。完全回退时，在 Vector 禁用「来电名片」模块后重启背屏应用。主屏联系人卡片继续使用原有实现。

```powershell
./gradlew.bat :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
adb shell am instrument -w -r -e class com.zuozishi.callhelper.RearCardDeviceTest com.zuozishi.callhelper.test/androidx.test.runner.AndroidJUnitRunner
```

Release 产物仍为未签名 APK；手机安装的是保留原数据的 Debug 测试版本。
