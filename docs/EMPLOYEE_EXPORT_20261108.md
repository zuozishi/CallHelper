# 真实员工接口接入 0.1.4

此文档记录 0.1.4 历史接入；当前配置已改为前台录入或二维码导入，见 [同步配置](SYNC_CONFIGURATION_20261108.md)。以下构建注入密钥的描述仅适用于旧版本，不要恢复已删除的 secrets 配置。现行协议与独立部署步骤见[后端服务搭建指南](BACKEND_SETUP.md)。

2026-11-08。接口为 https://example.com/api/employee/export（占位地址），匿名 GET，无查询参数，正文为 Base64 文本。实现依据用户提供的《员工加密导出接口.md》和本机密钥 JSON，不将密钥内容复制到文档。

## 同步与迁移

应用启动先恢复本地加密库，另起后台任务刷新；手动同步使用同一串行管线。来电服务只等候本地恢复，不受网络刷新延迟影响。

网络响应按大小限制读取，保留 HTTPS 证书验证，不跟随重定向，不使用缓存，连接 / 读取超时分别为 15 / 30 秒。Base64 解码后布局为版本 1、12 字节 nonce、16 字节 tag、密文，AAD 为版本字节。Java GCM 输入重排为密文加 tag，认证成功后才解析 UTF-8 CSV。

下载、认证或 CSV 校验失败均不覆盖已有接口人员库，错误文本不包含响应正文、人员信息或密钥。服务器允许多条有效主职时，保留相同 Id 的多行，用独立 rowKey 区分列表项，来电共享号码仍返回全部身份。协议不含时间戳 / 数据版本，因此未实现防重放或增量合并。

已删除工作区五个 CSV 文件（两个原始测试导出、Debug / androidTest 各一个资源、一个示例文件）、BundledData 和复制测试资源脚本。原 bundled 缓存在升级时清除，不再作为接口数据展示。存储单测使用内存生成的虚构记录。旧历史 APK、真实人员截图和日志已在本次敏感数据清理中删除。

## 密钥

密钥 JSON 位于被 Git 忽略的 secrets/employee-export-key.json。构建复制到生成资源 assets/employee-export-key.json，Debug 与 Release 均使用该配置；缺少配置时构建失败。未写入源码、日志或报告。后端导出密钥与 Android Keystore 本机存储密钥独立。

对称密钥随 APK 分发，可由 APK 持有者提取；这是当前接口协议的客户端分发方式，不构成服务器访问控制。密钥轮换后需要重新构建 / 部署。

## 检查

- 真实接口只读联调：HTTP 200，AES-GCM 认证成功，4,300 条记录、417 个单位、七个预期字段，当前重复 Id 为 0；解密明文仅留内存。
- 29 项 JVM 单元测试通过：包含协议布局、错误密钥、版本 / nonce / tag / 密文篡改、Base64 与截断封包、多主职数据，以及既有 CSV 和组织索引测试。
- Debug、unsigned Release、androidTest 构建与 Lint 检查通过。
- verify_private_assets.py 确认 Debug / Release 无 CSV 资源，密钥源被 Git 忽略；工作区源目录已无 CSV 文件。
- 真机安装与测试记录位于被忽略的 artifacts/private/backend-0.1.4/。不运行此前未能完成的 Compose 界面自动化。
- 真机正常前台启动日志已确认 sync-success count=4300，并生成约 1.26 MB 的本地加密人员库。无界面设备自动化也发生等待未完成，已终止，不计为通过；设备存储回归完整验收仍开放。
- 最终 0.1.4（5）覆盖安装并冷启动成功，正常启动日志依次确认 restore-complete count=4300 和 sync-success count=4300：离线恢复与再次真实下载均成功。最终安装包保存在 artifacts/private/backend-0.1.4/callhelper-0.1.4-debug.apk；normal-start.txt 保存只含阶段和数量的证据。

可重复执行 scripts/verify_remote_export.py 做只输出聚合统计的只读联调；脚本需要本机 Python cryptography，不保存明文。
