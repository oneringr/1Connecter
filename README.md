# 1Connecter

一个面向SWMU校园网登录场景的 Android 自动连接工具。项目使用 Jetpack Compose 构建界面，通过请求 Dr.COM 登录接口完成校园网登录，并支持多账号轮换、断线保活、日志导出等能力。

- 城北校区：`http://172.17.254.6/drcom/login`
- 忠山校区：`http://192.168.2.27/drcom/login`

根据您的校区选择使用。

## 功能特性

- 支持导入 CSV 账号配置（需要您自行提供）
- 支持多账号顺序轮换登录
- 支持自动轮试移动（`R3=1`）和电信（`R3=2`），并记住账号上次成功的运营商
- 支持检测网络状态后自动发起登录
- 支持前台服务保活，关闭界面后仍可持续监测和重连
- 支持解绑 MAC、注销校园网会话并确认注销状态
- 支持切换校区登录地址
- 支持导出运行日志
- 使用本地文件持久化保存账号、当前索引和运行配置

## 技术栈

- Kotlin
- Android SDK 34
- Jetpack Compose
- Material 3
- Kotlin Coroutines
- OkHttp
- Gson
- Apache Commons CSV
- Konfetti

## 环境要求

- Android Studio 最新稳定版
- JDK 11
- Android SDK
  - `compileSdk = 34`
  - `targetSdk = 34`
  - `minSdk = 26`

## 快速开始

### 1. 克隆并打开项目

```bash
git clone <your-repo-url>
cd 1Connecter
```

使用 Android Studio 打开项目根目录。

### 2. 构建调试包

Windows:

```bash
gradlew.bat assembleDebug
```

macOS / Linux:

```bash
./gradlew assembleDebug
```

### 3. 运行应用

连接 Android 设备或启动模拟器后，在 Android Studio 中直接运行 `app` 模块即可。

## 使用说明

### 首次使用

1. 启动应用
2. 选择校区
3. 导入账号 CSV 文件
4. 应用开始检测网络，并依次尝试移动和电信线路

### 保活模式

开启后，应用会启动带常驻通知的前台服务持续检测网络状态：

- 网络正常时继续监测
- 检测到断线时自动重试登录
- 关闭应用界面后服务仍可继续运行
- 可通过通知中的“停止保活”操作结束服务

Android 13 及以上会询问通知权限。拒绝权限不会阻止保活服务运行，但通知可能只在系统任务管理器中显示。设备重启或用户强制停止应用后不会自动恢复服务。

### 普通模式

普通模式下，应用登录成功后会结束运行流程，并可按当前逻辑自动关闭应用。

### 日志导出

应用可将运行日志导出到：

`/Android/data/top.onering.easyconnecter/files/connect_logs.txt`

## CSV 配置格式

推荐使用 UTF-8 编码的标准 CSV；程序也会兼容 GBK/GB18030 文件、UTF-8 BOM、引号字段、字段内逗号和多行字段。代码识别以下表头：

- `学号`
- `身份证号`
- `姓名`
- `班级`
- `联系电话`

其中 `学号` 和 `身份证号` 为必填字段，`名字` 有助于您记忆条目，其他可以忽略。

程序会根据 `身份证号` 自动生成登录密码：

- 如果身份证号长度不少于 6 位，取后 6 位作为密码
- 若最后 6 位中包含 `X`，会替换为 `0`
- 如果身份证号字段异常，回退为默认密码 `123456`

您可以在`template/template.csv`下载模版

## 工作流程

应用启动后会：

1. 从本地读取账号列表、当前轮换索引、校区地址和保活配置
2. 通过访问 `https://www.baidu.com` 检查当前网络是否已经可用
3. 如果未联网，则向校园网网关发起登录请求
4. 登录失败时切换到下一个账号继续尝试
5. 每个账号优先使用上次成功的运营商，否则先移动后电信
6. 只有服务端明确返回 `userid error` 时才会将账号从本地列表中移除
7. 登录成功后立即切换并保存下一个待尝试的账号索引

手动注销时，应用会先关闭保活服务，然后读取当前会话身份、尝试解绑 MAC、注销 Dr.COM 会话，并通过状态接口确认已经离线。

## 数据存储

应用将配置保存在应用私有目录中，主要包括：

- `accounts.json`：账号列表
- `current_index.txt`：当前轮换索引
- `campus_config.txt`：当前校区登录地址
- `keep_alive_config.txt`：是否开启保活模式
- `login_preferences.json`：账号上次成功的运营商和最后成功账号

请注意保护您的隐私。

## 项目结构

```text
app/
  src/main/java/top/onering/easyconnecter/
    MainActivity.kt              # Activity 入口与系统权限交互
    MainViewModel.kt             # 界面状态和用户操作
    domain/ConnectionCoordinator.kt # 登录轮换、注销和并发协调
    data/                        # 账号、配置、日志与 CSV 管理
    model/Account.kt             # 账号数据模型
    network/DrcomClient.kt       # 网络检测与 Dr.COM 协议
    service/KeepAliveService.kt  # 前台保活服务
    ui/                          # Compose 页面和弹窗
  src/main/res/
    xml/network_security_config.xml
```

## 网络与安全说明

- 项目申请了 `INTERNET` 和 `ACCESS_NETWORK_STATE` 权限
- 为适配校园网网关，允许对 `172.17.254.6` 和 `192.168.2.27` 发起明文 HTTP 请求
- 账号信息当前以明文形式保存在应用私有目录中，没有做额外加密

## 已知限制

- 当前逻辑依赖固定的 Dr.COM 登录、状态和注销接口参数
- 忠山校区的 `:801/eportal` 解绑接口按城北校区的同构路径实现，需在真实网络中验证
- 网络连通性检测使用 `https://www.baidu.com`，在某些网络环境下可能误判
- 项目主要面向SWMU校园网场景，迁移到其他学校通常需要调整代码

## License

本项目采用 GNU 通用公共许可证 v3（GPL-3.0）许可，详情请参阅 LICENSE 文件。
