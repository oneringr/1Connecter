# 1Connecter

一个面向校园网登录场景的 Android 自动连接工具。项目使用 Jetpack Compose 构建界面，通过请求 Dr.COM 登录接口完成校园网登录，并支持多账号轮换、断线保活、日志导出等能力。

当前代码中的网关地址和文案表明，这个版本主要面向西南医科大学校园网使用场景，内置了两个校区登录地址：

- 城北校区：`http://172.17.254.6/drcom/login`
- 忠山校区：`http://192.168.2.27/drcom/login`

如果你的校园网环境不同，需要修改登录地址或请求参数后再使用。

## 功能特性

- 支持导入 CSV 账号配置
- 支持多账号顺序轮换登录
- 支持检测网络状态后自动发起登录
- 支持保活模式，断线后持续重连
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
4. 应用开始检测网络并尝试登录

### 保活模式

开启后，应用会持续检测网络状态：

- 网络正常时继续监测
- 检测到断线时自动重试登录
- 不会在登录成功后自动关闭应用

### 普通模式

普通模式下，应用登录成功后会结束运行流程，并可按当前逻辑自动关闭应用。

### 日志导出

应用可将运行日志导出到：

`/Android/data/top.onering.easyconnecter/files/connect_logs.txt`

## CSV 配置格式

导入文件使用 UTF-8 编码的 CSV。代码当前识别以下表头：

- `学号`
- `身份证号`
- `姓名`
- `班级`
- `联系电话`

其中 `学号` 和 `身份证号` 为必填字段。

程序会根据 `身份证号` 自动生成登录密码：

- 如果身份证号长度不少于 6 位，取后 6 位作为密码
- 若最后 6 位中包含 `X`，会替换为 `0`
- 如果身份证号字段异常，回退为默认密码 `123456`

示例：

```csv
学号,身份证号,姓名,班级,联系电话
20230001,510123200001011234,张三,2023级1班,13800000000
20230002,51012320000202123X,李四,2023级2班,13900000000
```

## 工作流程

应用启动后会：

1. 从本地读取账号列表、当前轮换索引、校区地址和保活配置
2. 通过访问 `https://www.baidu.com` 检查当前网络是否已经可用
3. 如果未联网，则向校园网网关发起登录请求
4. 登录失败时切换到下一个账号继续尝试
5. 如果识别到账号或密码错误，会将该账号从本地列表中移除
6. 登录成功后保存下一个待尝试的账号索引，供下次启动继续使用

## 数据存储

应用将配置保存在应用私有目录中，主要包括：

- `accounts.json`：账号列表
- `current_index.txt`：当前轮换索引
- `campus_config.txt`：当前校区登录地址
- `keep_alive_config.txt`：是否开启保活模式

## 项目结构

```text
app/
  src/main/java/top/onering/easyconnecter/
    MainActivity.kt              # Compose UI 与主流程
    data/AccountManager.kt       # 账号与本地配置管理
    model/Account.kt             # 账号数据模型
    network/NetworkManager.kt    # 网络检测与登录请求
  src/main/res/
    xml/network_security_config.xml
```

## 网络与安全说明

- 项目申请了 `INTERNET` 和 `ACCESS_NETWORK_STATE` 权限
- 为适配校园网网关，允许对 `172.17.254.6` 和 `192.168.2.27` 发起明文 HTTP 请求
- 账号信息当前以明文形式保存在应用私有目录中，没有做额外加密

如果需要在正式场景长期使用，建议补充：

- 本地敏感信息加密存储
- 更严格的错误提示与异常上报
- 更通用的网关配置方式
- 单元测试和 UI 测试

## 版本信息

- `applicationId`: `top.onering.easyconnecter`
- `versionCode`: `102`
- `versionName`: `1.2`

仓库中已包含一个发布产物：

- `app/release/app-release.apk`

## 已知限制

- 当前逻辑依赖固定的 Dr.COM 登录接口参数
- 网络连通性检测使用 `https://www.baidu.com`，在某些网络环境下可能误判
- 项目主要面向特定校园网场景，迁移到其他学校通常需要调整代码
- 仓库当前没有测试代码

## License

仓库中暂未声明许可证。如需开源分发，建议补充 `LICENSE` 文件并在此处说明。
