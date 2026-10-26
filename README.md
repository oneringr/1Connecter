<a id="readme-top"></a>

<div align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="1Connecter 项目图标" width="128" height="128">
  <h1>1Connecter</h1>
  <p>面向 SWMU 校园网的 Android 自动登录与断线恢复工具</p>
  <p>
    <a href="https://github.com/oneringr/1Connecter/releases/latest">下载最新版</a>
    ·
    <a href="#usage">使用说明</a>
    ·
    <a href="https://github.com/oneringr/1Connecter/issues/new">反馈问题 / 提出建议</a>
  </p>
</div>

<details>
  <summary>目录</summary>
  <ol>
    <li><a href="#about">关于项目</a></li>
    <li><a href="#getting-started">开始使用</a></li>
    <li><a href="#usage">使用说明</a></li>
    <li><a href="#scope">数据与适用范围</a></li>
    <li><a href="#contributing">开发与贡献</a></li>
    <li><a href="#roadmap">待办事项</a></li>
    <li><a href="#license">许可证</a></li>
    <li><a href="#contact">联系与反馈</a></li>
    <li><a href="#acknowledgments">致谢</a></li>
  </ol>
</details>

<a id="about"></a>

## 关于项目

1Connecter 通过 Dr.COM 接口登录校园网，支持移动、电信、多账号轮换和运营商记忆。应用先检查外网与校园网会话，保留可用连接；服务模式通过前台通知持续监测，断线后按会话状态恢复。

- 通过 CSV 导入、管理和导出账号，按姓名或学号搜索。
- 在首页查看连接状态，在日志页查看和导出运行记录。
- 在设置中切换校区、主题与服务模式，或主动注销校园网。

### 界面截图

2.0 浅色主题，Android 9 真机截图。

| 首页 | 账号 | 日志 | 设置 |
| :---: | :---: | :---: | :---: |
| ![首页：连接状态与账号配置](docs/screenshots/home.png) | ![账号：CSV 导入导出与折叠列表](docs/screenshots/accounts.png) | ![日志：运行记录与导出](docs/screenshots/logs.png) | ![设置：服务模式、自动关闭与校区](docs/screenshots/settings.png) |

<a id="getting-started"></a>

## 开始使用

需要 Android 8.0（API 26）及以上设备、有效校园网账号，以及可访问对应网关的网络。

| 校区 | 登录地址 |
| --- | --- |
| 城北 | `http://172.17.254.6/drcom/login` |
| 忠山 | `http://192.168.2.27/drcom/login` |

1. 从 [Releases](https://github.com/oneringr/1Connecter/releases/latest) 下载并安装 APK。升级前导出账号配置；相同签名的 APK 可覆盖安装。
2. 连接校园网络或接入校园网的路由器 Wi-Fi。
3. 首次打开应用，导入 [CSV 账号文件](#csv-账号配置)，点击“下一步”。
4. 选择校区和“连接成功后自动关闭”设置，点击“开始使用”。应用随后检测网络并按需登录。
5. 需要持续监测时，在设置中开启“服务模式”。

未完成的引导会在下次打开时继续，已导入的账号会保留。已有账号的用户升级后直接进入首页，沿用原有设置。

在“设置 → 关于”点击“检查更新”可查询 GitHub 最新正式版本；发现新版本后，再次点击提示打开 Release 页面，手动下载安装 APK。

<a id="usage"></a>

## 使用说明

### CSV 账号配置

下载 [CSV 模板](template/template.csv)，按以下表头填写，列顺序不限。

| 表头 | 是否必填 | 用途 |
| --- | --- | --- |
| 学号 | 是 | 校园网登录账号 |
| 身份证号 | 是 | 生成登录密码 |
| 姓名 | 否 | 账号显示名称 |
| 班级 | 否 | 保存与导出 |
| 联系电话 | 否 | 保存与导出 |

密码取身份证号字段后 6 位，其中的 `X` 或 `x` 替换为 `0`；非空但不足 6 位时使用 `123456` 并产生导入警告。学号或身份证号为空的记录会被跳过。

推荐 UTF-8，兼容 UTF-8 BOM、GBK / GB18030、空表头列、引号字段、字段内逗号和多行字段。导出文件保留上述五列并带 UTF-8 BOM，可再次导入。**成功导入会替换现有账号列表，并重置轮换位置与运营商记忆。**

在账号页展开“全部账号”可浏览或搜索；候选标记指向下一轮待登录的账号。切页时会保留滚动位置、账号列表展开状态及搜索关键词。

### 普通模式与服务模式

| 模式 | 行为 |
| --- | --- |
| 普通模式 | 打开应用后检测网络并按需登录。“连接成功后自动关闭”默认开启，本次实际登录成功 3 秒后关闭界面。 |
| 服务模式 | 通过带常驻通知的前台服务持续监测，联网时约每 60 秒检查一次，断线后进入恢复流程。 |

用户操作会取消本次自动关闭；完成首次引导或启动时已联网，界面保持打开。自动关闭、服务模式和主题设置会保存。

服务模式开关、实际服务运行和连接暂停状态分别显示。账号列表为空时暂停服务，导入有效账号后可继续；主动注销会暂停连接并保留服务模式选择。通知中的“停止保活”会关闭服务模式，下次打开应用仍可按普通模式登录。

Android 13 及以上会请求通知权限，拒绝后前台服务仍可运行，通知可能仅出现在系统任务管理器。系统后台限制可能结束进程，划掉最近任务后的行为取决于设备；设备重启或强行停止后需重新打开应用。

### 连接与恢复

2.1 使用有上限的快速恢复，验证记录见[验证范围](#validation)。

`clientip online` 表示网关认为当前 IP 已在线。应用结合 HTTPS 探测和会话状态判断是否需要恢复：

1. **检查外网**：并行探测百度和腾讯 HTTPS 站点，单轮总超时 3 秒；任一站点可达即取消其余探测并保留连接。清理在线会话前再次探测外网。
2. **确认会话**：外网不可用时查询校园网状态，优先恢复本地保存的实际在线账号。状态未知或在线账号不在本地时，保留会话并暂缓恢复。
3. **清理会话**：会话信息齐全时先尝试解绑 MAC，再注销；短间隔查询状态，确认离线后立即继续，兼容网关注销状态延迟。
4. **重试与轮换**：确认离线后继续登录；下一次登录与前次登录或注销至少间隔 3 秒；遭遇限流时，本轮两次重试分别至少等待 6 秒、12 秒，服务端要求更长等待时按其返回值执行。恢复时每个账号、每个运营商最多尝试两次，确认离线后再换运营商；两种运营商均失败则保留账号，切换下一候选。
5. **保存进度**：恢复身份、尝试次数和已用尽的运营商独立保存，下一轮或重启进程后继续处理。状态未知、注销未确认或网络请求失败时保留会话与候选，暂缓 15 秒后再检查；连续三个账号失败，或唯一账号的两种运营商均失败时也会降频，服务端等待时间更长时以其为准。

普通认证优先使用账号上次成功的运营商，否则先移动后电信。协议错误可在确认离线后轮换；`userid error`、`userid error2`、`bind userid error` 或 `Oppp error` 会移除对应账号。恢复已有会话时遇到这些错误，会保留账号，确认离线后停止尝试当前运营商并继续轮换。

### 主动注销与日志

“注销校园网”先暂停认证，取消并等待已有任务及 HTTP 请求退出，再清理会话。确认离线后，可选“立即重启”恢复连接，或“暂不重启”保持暂停；暂停期间重建界面也会保留该状态。

日志页可暂停自动滚动，并导出运行记录到：

```text
/sdcard/Download/connect_logs_yyyyMMdd_HHmmss_SSS.txt
```

Android 8–9 首次导出需授予存储权限，Android 10 及以上无需该权限。

<a id="scope"></a>

## 数据与适用范围

账号、连接状态、恢复进度和界面偏好保存在应用私有目录，账号信息未额外加密。导出的 CSV 和日志可能包含个人信息，分享前应脱敏。校园网网关请求使用明文 HTTP。

系统云备份与设备迁移排除 `accounts.json`、`login_preferences.json` 和 `pending_recovery.json`。更换设备时，通过 CSV 手动导出、导入账号。

应用依赖上述校区的 Dr.COM 接口与参数，适配其他学校通常需要修改代码。HTTPS 探测和校园网请求使用系统默认网络：蜂窝数据接管时，探测成功可能掩盖校园网断网；两个探测站点同时不可达时，也可能误判外网状态。

<a id="validation"></a>

### 验证范围

| 范围 | 已有记录与待验证项 |
| --- | --- |
| 城北校园网 | 2.1 在 Android 9 真机通过三个真实校园网场景：健康连接不重复认证、可上网的 `clientip online` 保留会话、冲突后自动解绑 / 注销 / 登录并恢复 HTTPS。冲突测试注入前置离线状态与断网判断，网关请求和响应来自真实网络；本次恢复约 21.6 秒，包含两次服务端限流。 |
| 忠山校园网 | 登录及同构的 `:801/eportal` 解绑接口已实现，待真实网络验证。 |
| 网络路由 | 实机测试使用 Wi-Fi 默认网络，自动化恢复测试将测试进程绑定到 Wi-Fi；蜂窝数据接管场景待验证。 |
| 2.0 发布 | 91 项 JVM 测试、18 项 Android 14 模拟器测试通过；Debug / Release 构建与 lint 完成，0 错误、31 条警告。签名 Release 已验证从 1.7 覆盖升级并保留校区与服务设置；Android 9 真机完成引导、重启恢复、链接及按压反馈检查，最终调试包完成覆盖安装和启动验证。2.0 发布时未重跑真实校园网恢复测试。 |
| 2.1 发布 | 102 项 JVM 测试、5 项 Android 9 真机测试通过；Debug / Release 构建与 lint 完成，0 错误、24 条警告。验证恢复进度持久化、账号轮换、延迟注销状态、限流等待与重启后保留健康连接。实机使用同源码 Debug 包；正式 APK 的 v2 / v3 签名已验证，证书与 2.0 一致。 |
| 持续运行日志 | 2026-10-26 导出的最近 1000 条日志覆盖 03:34–19:28，包含自然断网后的多账号轮换与恢复、可上网的 `clientip online` 会话保留。服务进程运行约 56 小时，但日志窗口不覆盖全部时段。网关曾返回临时代理行为封禁提示，校园侧封禁与上游故障仍需由对应网络恢复。 |

<a id="contributing"></a>

## 开发与贡献

项目使用 Kotlin、Jetpack Compose / Miuix、Coroutines 和 OkHttp，依赖版本见 [版本目录](gradle/libs.versions.toml)。

### 从源码构建

准备 JDK 17 或兼容当前 Gradle / AGP 的更高版本，以及 Android SDK Platform 37。通过 `ANDROID_HOME` 或本地 `local.properties` 的 `sdk.dir` 指定 SDK；项目的 Java / Kotlin 字节码目标为 Java 11。

```bash
git clone https://github.com/oneringr/1Connecter.git
cd 1Connecter
```

Windows：

```powershell
.\gradlew.bat assembleDebug
```

macOS / Linux：

```bash
./gradlew assembleDebug
```

APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`，也可在 Android Studio 中运行 `app` 模块。发布签名资料由维护者在仓库外管理。

### 测试与反馈

单元测试与静态检查：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug
```

界面及生命周期测试位于 `app/src/androidTest`。真实校园网恢复测试需显式启用 `liveCampus=true`，会注销并重新登录；运行条件为具备有效测试账号、允许短暂断网的校园网设备。

欢迎通过 Issue 提供复现步骤，或提交 Pull Request。故障报告应注明应用版本、Android 版本、校区与接入方式，并附脱敏日志；不要提交账号 CSV、身份证号、密码、签名文件或未经处理的抓包。

<a id="roadmap"></a>

## 待办事项

- [x] 限制单账号恢复次数，保存重试进度，并验证自然断网后的账号轮换与恢复。
- [ ] 完成忠山校区真实网络验证。

<a id="license"></a>

## 许可证

采用 GNU General Public License v3.0（GPL-3.0），详见 [LICENSE](LICENSE)。

<a id="contact"></a>

## 联系与反馈

维护者：[oneringr](https://github.com/oneringr) · 问题反馈：[GitHub Issues](https://github.com/oneringr/1Connecter/issues)

<a id="acknowledgments"></a>

## 致谢

- [Best-README-Template](https://github.com/othneildrew/Best-README-Template)：README 结构参考。
- Android Jetpack、Kotlin、OkHttp、Gson、Apache Commons CSV 与 Konfetti 等开源项目。
- [Miuix](https://github.com/compose-miuix-ui/miuix)：Compose 界面组件；[HuaweiPods](https://github.com/Nshpiter/HuaweiPods)：页面、分组卡片与导航设计参考。
- 提供校园网测试环境、问题反馈和复现信息的用户。

[返回顶部](#readme-top)
