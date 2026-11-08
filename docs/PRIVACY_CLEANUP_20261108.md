# 敏感数据清理与打包检查

按用户明确授权删除整个 artifacts、tmp、build、app/build、secrets 和 .env。清理内容包括旧导出密钥、数据库密码、包含真实人员 CSV 或导出密钥的历史 APK、真实设备截图及日志、遗留生成的密钥资源。build 和 app/build 随后由重新构建生成，不代表旧文件恢复。

源码、文档和联调脚本中的真实服务地址已移除，文档改用 example.com 占位地址。联调脚本通过交互方式输入 URL 和密钥，不保存私有配置文件；单元测试与设计原型继续使用虚构示例。手机上的用户配置与加密离线人员库未修改。

构建保护：

- preBuild 依赖 verifyNoPrivateInputs，拒绝项目根目录的私有配置输入及源码下的 CSV、数据库、证书私钥容器等文件；源码 assets / res/raw 仅允许经过校验的 Xposed 入口。
- assembleDebug / assembleRelease 依赖 APK 审核任务，拒绝私有文件路径、数据库和密钥容器、嵌套安装包 / ZIP，并扫描硬编码 Base64 AES-256 密钥候选；assets 只允许 Xposed 入口和 Android 生成的 baseline profile。
- scripts/verify_private_assets.py 解压读取 Debug / unsigned Release 的每个归档成员，检查文件类型、assets 白名单、入口内容、凭据 JSON 赋值模式和 profile 文件头，并输出 SHA-256。可通过标准输入提供仅驻留内存的已知私有值，额外检查 UTF-8 / UTF-16LE 内容；未提供时会明确输出检查的私有值数量为 0。
- scripts/test_private_assets.py 使用虚构内容验证私有文件、未知资源、各类 APK 成员中的敏感值及错误入口会被拒绝。

这些检查覆盖当前 APK 的静态打包内容；用户日后在手机上输入或同步的数据不属于安装包资源。资源白名单变更需要同步更新审核规则，不能为了让构建通过而直接加入私有资源。本次未改写 Git 历史；历史敏感文件名检查仅发现虚构 samples/people.example.csv，未发现曾提交的 .env、导出密钥文件或人员数据库。

本次结果：Debug / unsigned Release 构建及内置打包检查通过，JVM 测试和 Lint 通过，5 项打包隐私回归测试通过。逐项读取 Debug 的 155 个成员、Release 的 148 个成员，未发现禁用资源、凭据 JSON 赋值模式或 32 字节 Base64 密钥常量。删除原密钥和密码后最终检查未重新加载其原值，故不声称对已删除凭据执行了逐字节比对；结果中的 private_references_checked 为 0。

最终 APK SHA-256：

- Debug：`bca83d2f3d5b770bb8233fdf3b4db8553af60ea1ed0e42348aa427da6f4a9879`
- unsigned Release：`689512934afdeb46e980ed07412c7a02cbdf525c85adecdb37042678b691243b`

复查命令：

```powershell
./gradlew.bat :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
python scripts/verify_private_assets.py
python -m unittest discover -s scripts -p test_private_assets.py
```
