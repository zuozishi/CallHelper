# 当前架构

2026-11-08。以用户指定 CSV 为实施输入，旧 SQLite 提案不再使用。

## 数据与存储

本地文件 / Debug 测试资源 → 限制大小的流读取 → 严格 UTF-8 CSV 解析 → 字段校验 → 新快照 → AES-GCM 加密 → AtomicFile 原子保存 → 发布快照。

当前 Debug 测试资源为带 AvatarUrl 的 CSV。内置源按摘要更新，手动导入源不覆盖；头像通过 Coil 异步加载，地址缺失或加载失败显示姓氏占位，锁屏隐藏人员头像。头像数据验证见 `docs/AVATAR_DATA_20261108.md`。

Personnel 与六列直接对应。DirectorySnapshot 按完整规范化号码建立内存索引，重复号码保留所有身份。IO 在线程池执行，搜索在 Default 线程执行；来电不等待网络。

文件位于 noBackupFilesDir，采用 Android Keystore 生成的 AES-256 密钥、随机 IV、GCM 认证与版本关联数据。密钥不进源码；云备份与迁移排除应用数据。失败不发布新快照，AtomicFile 处理上一版恢复。Debug 内置测试 CSV 为明文开发资源，仅位于本地私有测试包；Release 无该资源。

## 来电与窗口

CallIdentificationService 立即放行来电，然后最多等候 2.5 秒恢复本地库并查号；不拦截、静音、接听、挂断或替换原电话页面。

OverlayController 使用 TYPE_APPLICATION_OVERLAY、非焦点窗口和真实卡片边界，卡片外维持系统电话操作。浮窗以 Android View 绘制，页面以 Compose / Miuix 绘制。单进程只有一个浮窗，组织路径换行、多身份可滚动，拖动保存相对纵向位置。锁屏仅显示无人员详情提示。

卡片最多显示 30 秒；可选 READ_PHONE_STATE 授权后，接通约 5 秒收起，通话结束移除。未授权悬浮窗或已暂停时不创建卡片，号码隐藏或查无结果时明确呈现。系统电话应用与 HyperOS 后台策略仍需真机验收；没有常驻前台服务保证进程不被回收。

## UI 与 API

页面使用 Miuix，导航使用 LiquidBottomTabs / LiquidBottomTab 及其手势辅助代码，内容由 layerBackdrop 参与玻璃背景取样。图标保留语义标签与选中状态，来源和许可见 third_party/。

EncryptedCsvTransport.download 与 CsvPayloadDecryptor.decrypt 是加密 API 的待接入边界，成功解密的 CSV 复用相同校验和替换。没有自创线上加密格式、没有实际网络请求、增量合并或自动同步。后端需确认 HTTPS 端点、认证、算法、IV / tag / 签名、密钥分发与轮换、数据版本、防重放与错误语义。

具体实现验证与未验收范围见 docs/DEVELOPMENT_20261108.md。
