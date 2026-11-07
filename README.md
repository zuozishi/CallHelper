# 来电知人 · CallHelper

Android 来电人员识别助手：通过电话号码匹配姓名、岗位、单位，以轻量悬浮卡片展示。

当前交付为 **V1 界面设计与目录骨架**，尚未实现 Android 功能、后端接口或加解密；没有可安装 APK。

## 查看设计

- [六屏设计总览](docs/design/v1/overview.png)
- [可交互设计稿](docs/design/v1/index.html)：浏览器直接打开；支持人员搜索、同步状态演示、主题和悬浮窗开关。
- [设计与交互规范](docs/design/v1/DESIGN.md)
- [架构与接口待定项](docs/architecture/ARCHITECTURE.md)
- [单屏 PNG](docs/design/v1/screens/)

所有人员、号码、统计和同步结果均为演示数据。

## 目录规划

```text
app/src/main/
  java/com/zuozishi/callhelper/
    core/                 # 号码规范化、主题与通用能力
    data/                 # 同步、CSV 解码、人员本地索引
    domain/               # 人员模型、查号与同步用例
    feature/              # home / directory / sync / settings
    service/              # 来电识别、悬浮窗生命周期
  res/                    # 后续 Android 资源
app/src/test/             # 后续单元测试
app/src/androidTest/      # 后续设备测试
docs/design/v1/           # 设计源文件、图和规范
docs/architecture/        # 架构方案
scripts/                  # 设计图导出脚本
samples/                  # 仅虚构 CSV 样例
```

采用 Kotlin + Jetpack Compose，界面计划接入 [Miuix](https://github.com/compose-miuix-ui/miuix)，底部导航指定采用 AndroidLiquidGlass 的 [LiquidBottomTabs](https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTabs.kt) 示例组件。当前 HTML 是用于评审的可编辑设计源，不代表已经接入原生组件。Android 构建配置和库版本在实施阶段确认。

## 重新导出设计图

安装 Python Playwright，并准备本地 Chromium 或 Microsoft Edge：

```powershell
python -m pip install playwright
python scripts/render_design.py
```

导出脚本支持 `--browser` 指定浏览器可执行文件；默认使用 Windows Edge。设计稿无外部字体、脚本或图片依赖，可离线打开。
