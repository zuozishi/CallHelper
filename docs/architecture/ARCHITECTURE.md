# 当前架构

2026-11-08。0.1.4 起使用真实员工加密导出接口，旧 SQLite 提案和内置测试 CSV 均不使用。

## 数据与存储

HTTPS GET → 限制大小的 Base64 正文 → 按版本 / nonce / tag / 密文拆包 → AES-256-GCM 认证解密 → 严格 UTF-8 CSV 解析 → 字段校验 → 新快照与组织索引 → 本机 AES-GCM 加密 → AtomicFile 原子保存 → 发布快照。

Debug 与 Release 均不打包人员 CSV。头像通过 Coil 异步加载，地址缺失或加载失败显示姓氏占位，锁屏隐藏人员头像。旧 bundled 来源升级时清除，接口来源加密库先加载再后台更新，失败保留上次接口数据。

Personnel 保留接口字段与独立 rowKey。接口允许同员工多条有效主职，重复 Id 不丢行，rowKey 区分列表项；本地解析工具的默认严格模式仍拒绝重复 Id。DirectorySnapshot 按完整规范化号码建立内存索引，重复号码保留所有身份。组织索引保留 FullOrgName 完整祖先链。IO 在线程池执行，搜索在 Default 线程执行；来电不等待网络。

文件位于 noBackupFilesDir，采用 Android Keystore 生成的 AES-256 本机密钥、随机 IV、GCM 认证与版本关联数据。云备份与迁移排除应用数据。失败不发布新快照，AtomicFile 处理上一版恢复。本机密钥与服务器导出密钥相互独立；服务器密钥由用户在前台表单录入或二维码导入，独立加密保存到本机，不随 APK 分发。

## 来电与窗口

CallIdentificationService 立即放行来电，然后最多等候 2.5 秒恢复本地库并查号；不拦截、静音、接听、挂断或替换原电话页面。

OverlayController 使用 TYPE_APPLICATION_OVERLAY、非焦点窗口和真实卡片边界，卡片外维持系统电话操作。浮窗以 Android View 绘制，页面以 Compose / Miuix 绘制。单进程只有一个浮窗，组织路径换行、多身份可滚动，拖动保存相对纵向位置。锁屏仅显示无人员详情提示。

卡片最多显示 30 秒；可选 READ_PHONE_STATE 授权后，接通约 5 秒收起，通话结束移除。未授权悬浮窗或已暂停时不创建卡片，号码隐藏或查无结果时明确呈现。系统电话应用与 HyperOS 后台策略仍需真机验收；没有常驻前台服务保证进程不被回收。

## UI 与 API

0.1.9 起，背屏由 LSPosed / Vector 的 NativeRearHook 在 com.xiaomi.subscreencenter 中挂钩原生 SmartAssistantPanel，构造类型 3 的 RemoteViews 卡片。沿用用户 RearHaPoc 的工厂及容器路径，仅对已匹配的版本注入，不启动或移动来电名片 Activity；不包含 Shizuku 依赖。具体作用域、接口身份校验及设备证据见 docs/LSPOSED_REAR_CARD_20261108.md。

RearCardController 保存短期内存卡片，NativeRearProvider 仅允许本 APP 和身份匹配的背屏包读取当前 RemoteViews，ContentObserver 推送变化。卡片期限由 APP 和背屏宿主分别检查，进程重启不恢复联系人卡片；关闭 PendingIntent 使用显式、不导出的接收器及逐卡令牌。背屏锁屏详情开关独立于主屏；普通 RV 与 AOD RV 分别渲染，脱敏时显式清除头像动作，避免 reapply 保留旧图片。

页面使用 Miuix，导航使用 LiquidBottomTabs / LiquidBottomTab 及其手势辅助代码，内容由 layerBackdrop 参与玻璃背景取样。图标保留语义标签与选中状态，来源和许可见 third_party/。

EmployeeExportTransport 使用 HttpsURLConnection 调用用户配置的 HTTPS 端点，保留平台 TLS 校验、禁用缓存和自动重定向、限制大小与连接 / 读取超时。EmployeeExportDecryptor 按提供的协议处理版本 1、12 字节 nonce、16 字节 tag、密文，以版本字节作为 AAD，认证成功才返回明文。启动刷新和手动同步串行执行，不提供定时或增量同步。协议未提供服务器数据版本或时间戳，不声称具有防重放能力。

本次接入验证见 docs/EMPLOYEE_EXPORT_20261108.md；旧实现历史验证见 docs/DEVELOPMENT_20261108.md。
