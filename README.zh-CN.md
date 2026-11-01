# LeanCast

[English](README.md) | 简体中文

**[下载 APK](https://github.com/addozhang/leancast/releases/latest) · [支持的设备](#支持的设备) · [报告设备兼容性](https://github.com/addozhang/leancast/issues/new?template=device-compatibility.yml)**

<p align="center">
  <img src="docs/screenshots/main-zh.png?v=2" width="49%" alt="LeanCast 电视主界面" />
  <img src="docs/screenshots/player-osd-zh.png?v=2" width="49%" alt="LeanCast 播放 OSD" />
</p>

一个极简、无广告的 Android TV **DLNA/UPnP 投屏接收器**。在任意带投屏按钮的视频 App（B站、爱奇艺、腾讯视频、优酷、夸克等）中点击 [TV]，选择本机即可全屏硬解播放——没有广告、无需账号、不连云端。

```
手机 App --SSDP--> 发现设备    手机 App --SOAP SetAVTransportURI--> LeanCast
                                                                  |
                                                      ExoPlayer + MediaCodec 硬件解码
```

## 特性

- **标准 UPnP AV `MediaRenderer:1`** — SSDP 设备发现、AVTransport / RenderingControl / ConnectionManager SOAP 控制、完整的 GENA 事件（订阅后立即推送初始 `LastChange`，之后每次状态变化推送）。
- **硬件解码**（Media3/ExoPlayer）：HLS（`.m3u8`）、渐进式 MP4、Matroska、HTTP-FLV（尽力支持）、HTTP↔HTTPS 重定向、局域网明文地址。
- **TV 原生操控与 OSD**：OK 键 暂停/继续，◀ ▶ 长按**阶梯加速快进（10 秒 → 5 分钟）**，▼ 下键 循环切换**倍速（0.75x / 1.0x / 1.25x / 1.5x / 2.0x）**，▲ 上键 唤醒进度条，返回键 退出；屏上 HUD 实时进度条、片名与快进/倍速反馈，支持中英文。
- **轻量且保护隐私**：比电视自带桌面还省内存（见下表）；零埋点、除局域网外零网络请求；纯 Kotlin APK **无任何原生库**——一个安装包通吃 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64`。
- **零硬编码**：投屏名称默认 `LeanCast`，可随时改名；UUID 由设备标识派生，重启和重装都保持不变；界面语言可在简体中文与 English 间切换。
- 已在 Amazon Fire TV（Fire OS 7 / Android 9，32 位 MTK SoC）上实测，`minSdk 21` / `targetSdk 28`。

## 支持的设备

LeanCast 是**无原生库**的纯 Kotlin APK，`minSdk 21`（Android 5.0，2014 年），一个安装包覆盖 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64`。解码使用设备自身的 `MediaCodec`，因此编解码能力跟随设备：H.264 全员支持，HEVC/4K 需 2016 年后的电视，AV1 仅较新的盒子支持。

| 层级 | 设备 | 说明 |
| :--- | :--- | :--- |
| ✅ 实测设备 | Amazon Fire TV（见下方实测名录） | 主要测试设备：Fire OS 7.7，32 位 MTK SoC；其他型号需单独验证 |
| ✅ 预期兼容 | 认证 Android TV / Google TV（索尼、TCL、飞利浦、Chromecast w/ Google TV）、NVIDIA Shield、Amlogic / Rockchip 方案盒子、小米国际版盒子 | 装上即用 |
| ⚠️ 实验性 | 支持安装第三方应用的国产智能电视（MIUI TV / PatchWall、酷开、海信安卓机型）、天猫魔盒（YunOS） | 需开启「未知来源安装」；欢迎通过兼容性 Issue 反馈结果 |
| ❌ 不支持 | 三星（Tizen）、LG（webOS）、华为智慧屏（HarmonyOS）、非智能电视 | 无 Android 应用运行时——请搭配 ✅ 层级的电视盒子 |

说明：
- 手机「系统投屏」（Miracast）不在本项目范畴——LeanCast 是 DLNA/UPnP 接收器，请在视频 App 内使用 `[TV]` 按钮。
- 不能仅凭品牌判断兼容性：同一品牌不同型号、地区可能采用不同系统。原生 Tizen、webOS、Roku OS、VIDAA 等非 Android 系统不能运行此 APK，可搭配外置 Android TV 盒子使用。

### 已验证设备名录

以下为开发者或社区的具体实测记录，不代表同品牌所有型号均兼容。未提供的版本标为未知；某项功能测试通过不代表所有功能和发送端 App 都已验证。

| 品牌 / 型号 | 系统 / 固件 | Android 版本 | 已测试 LeanCast 版本 | 实测范围 | 来源 / 验证者 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| Amazon Fire TV（具体型号未记录） | Fire OS 7.7 | 9 | 未记录 | 开发者播放与资源占用实测 | [@addozhang](https://github.com/addozhang) |
| Sony KD-55X9000H | Android TV；固件未提供 | 未提供 | 0.2.5 / 0.2.6；倍速分支 `11ff197` | 报告使用爱奇艺 / 优酷投屏；倍速测试版反馈正常 | [Issue #1](https://github.com/addozhang/leancast/issues/1)、[@LabiKyo](https://github.com/LabiKyo) |

**你的电视、盒子或投影也能用？** 欢迎[提交设备兼容性报告](https://github.com/addozhang/leancast/issues/new?template=device-compatibility.yml)，帮助更多用户选择设备！中英双语表单提供主流品牌与系统选项，固件 / Android 版本不知道可留空，失败结果也欢迎反馈。使用外置盒子时，请填写实际运行 LeanCast 的盒子信息。审核后我们会将实测信息及贡献者链接加入名录。

## 安装与使用

1. 从 [GitHub Releases](https://github.com/addozhang/leancast/releases/latest) 下载最新的 `LeanCast-<版本号>.apk`，无需自行编译。
2. 在电视 / 盒子上允许安装未知来源应用，安装 APK 并打开 LeanCast。
3. 确保手机和接收设备处于同一局域网，在视频 App 内点击 `[TV]`，选择 LeanCast。

可在应用中修改投屏名称、切换中英文，并开启「开机自动启动」。

### 遥控器操作

| 按键 | 功能 |
| :--- | :--- |
| 确定键 | 暂停 / 继续 |
| 左 / 右键 | 点播快退 / 快进，长按阶梯加速（10 秒 → 5 分钟） |
| 下键 | 点播倍速循环：1.0x → 1.25x → 1.5x → 2.0x → 0.75x → 1.0x |
| 上键 | 查看时间与进度，不改变播放状态 |
| 返回键 | 停止并退出播放 |

直播不支持快进快退与倍速；每次新投屏重置为 1.0x。

### 可选：通过 ADB 安装

开启接收设备的网络调试后，可在电脑上执行（实际端口以设备为准）：

```bash
adb connect <TV-IP>:5555
adb install -r LeanCast-<版本号>.apk
adb shell am start -n io.leancast/io.leancast.app.MainActivity
```

## 资源占用

以下为实测数据（主测试设备 Fire TV，1.6GB 内存；空闲 = 停留在主界面待命）：

| 状态 | 内存 (PSS) | CPU | 存储 |
| :--- | :--- | :--- | :--- |
| 空闲待命 | 63–72 MB（私有约 33 MB） | ≈ 0% | 安装包 5 MB + 数据 < 0.1 MB |
| 播放 1080p | ≈ 74 MB | 全机约 4–5%（解码走硬件） | — |

同一设备上比 Fire TV 自带桌面（83 MB PSS）更省。

## 构建

要求：JDK 17+、Android SDK（platform 34）。

```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## 项目结构

```
domain/          纯 Kotlin 协议核心（JVM 单元测试，90 个用例）
  ssdp/          M-SEARCH 解析、目标匹配、USN、alive/byebye、MX 延迟
  upnp/          设备描述 + SCPD、SOAP 解析器（宽容 DIDL）、响应与故障、
                 H:MM:SS 时间换算、SOAP 路由
  cast/          AVTransport 状态机、播放器端口、音量数学
app/             Android 壳层（薄适配层，真机 E2E 验证）
  ControlServer  极简 HTTP/1.1 服务：/desc.xml、/scpd/*、/ctl/*、/evt/*
  SsdpAgent      组播 UDP 监听 + 通告（持有 MulticastLock）
  ExoPlayerPort  PlayerPort 适配器（主线程封装、位置缓存）
  ReceiverService  组装一切的前台服务
tools/
  e2e_control_point.py  脚本化 UPnP 控制点，用于真机 E2E
openspec/        规格驱动开发产物（proposal/specs/design/tasks）
```

开发流程遵循 **OpenSpec**（`openspec/`）+ 严格 TDD：每条规格场景都映射到
`domain` 中的单元测试，运行 `./gradlew :domain:test`。端到端验证由开发机
驱动真机完成：

```bash
python3 tools/e2e_control_point.py http://<局域网主机>/video.mp4
```

## 版本发布与 CI

仓库内置两个 GitHub Actions 工作流：

- **CI**（`.github/workflows/ci.yml`）— 每次 push 到 `main` 和每个 PR 自动运行 domain 单元测试 + debug 构建，并上传 debug APK 产物。
- **Release**（`.github/workflows/release.yml`）— 推送 `v*` 标签（或手动 workflow_dispatch）触发：构建 release APK、运行单元测试、创建带自动变更说明的 GitHub Release，并附上 `LeanCast-<版本号>.apk`。

发布新版本：

```bash
git tag v0.2.0
git push origin v0.2.0
```

### 发布签名

默认情况下 release APK 回退使用 debug keystore（个人安装足够）。如需跨版本稳定的发布签名，请在仓库中配置以下 Secrets（**Settings → Secrets and variables → Actions**）：

| Secret | 值 |
| :--- | :--- |
| `KEYSTORE_BASE64` | `base64 -i release.keystore \| pbcopy`（Linux 用 `base64 -w0`） |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | key 别名 |
| `KEY_PASSWORD` | key 密码 |

工作流会把 keystore 解码为 `ci-release.keystore`（已被 git 忽略）并用其签名。生成 keystore：

```bash
keytool -genkeypair -v -keystore release.keystore -alias leancast \
  -keyalg RSA -keysize 2048 -validity 10000
```

## 许可证

Apache-2.0。依赖：Media3（Apache-2.0）。
