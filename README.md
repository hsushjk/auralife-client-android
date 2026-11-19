# auralife-client-android

DSH 插件 [dsh-auralife](https://github.com/hsushjk/dsh-auralife) 的安卓客户端，协议与 [Windows 端](https://github.com/hsushjk/auralife-client-win) 一致

**系统要求**

- 本客户端使用 `Kotlin + Jetpack Compose`
- 理论最低系统要求：Android 8.0
- 推荐系统：Android 15+
- 兼容：arm64-v8a / armeabi-v7a / x86 / x86_64

**入口 / 连接**

- 服务端默认监听 `2332` 端口的前提下，地址栏可仅填 ip ：`192.168.1.5` → `http://192.168.1.5:2332`
- 支持https
- 登录态本地保存
- 自签证书会弹「指纹（SHA-256）确认」对话框，核对一致才信任并连接，其余连接仍走系统信任链

**聊天**

- 文字 / 表情 / 图片 / 语音四种消息收发，对方打字时显示「正在输入…」
- 图片语音发送走内联 base64（服务端落盘），接收按 `mediaId` 走 `api/media` 下载并落盘缓存
- 媒体缓存上限 300 MB，超了按最早访问时间自动删；设置页能看大小、一键清空；被服务端清掉显示「图片/语音已过期」
- 分片历史：首屏 `file=last`，翻到顶自动 `file=prev`，按 id 去重、不跳位；`history` 事件到达时裁掉过期消息
- 消息按天插「今天 / 昨天 / x月x日」分隔气泡
- 长按气泡：复制文字 / 保存图片表情到相册（`Pictures/AuraLife`）/ 保存语音（`Music/AuraLife`）
- 语音条：点一下播放、波形按进度点亮、显示 `x″ / x″`
- 发送失败不静默：本地红色气泡显示原因，点「重试」重发、点「扔掉」丢弃
- 未读数 + 右下角「回到底部 ↓ N」；草稿停手 0.5 秒落盘；会话内搜索；图片点开全屏
- 常驻前台服务保活 + 新消息横幅（通知正文只显示 `[图片]` / `[表情]` / `[语音 Ns]`）

**导航**

- 聊天 / 空间 / 生命 / 记忆 / 设置 / 日志 —— 结构照网页端顶层导航；记忆、日志只有主人可见
- 生命页：状态（三激素 + 体力）、现在（身份 / 主动搭话 / 模型 / 上下文）、内分泌变化记录、身体、执念、闹钟、人格（含形象头像）、器官列表
- 空间页：`共 N 篇 · 今天 N 篇` + 日记条目 + 评论（发 / 回复，回复缩进；日记与评论只读，不允许删）
- 记忆页 3 个 tab：事实 / 事件 / 联想（联想显示「凭据 / 关联」两种边）
- 日志页：`时间 [级别] 区域 文本` + 清空

> 亚克力是客户端本地玻璃效果（主题保存在本地，不与服务端同步）；没设背景图时降级成渐变底。

**调试面板**

聊天页左上角头像连点 5 下提示、第 6 下打开。列记忆 / 列执念 / 列自我 / 列闹钟等只读操作不烧 token，整理对话 / 记忆重放 / 叫 Ta 一次 / 写日记 / 跑社交会烧 token（页面有标注）。

## 安装 / 编译

需要 JDK **21** 和 Android SDK 35。提供工具链脚本，装到 `%USERPROFILE%\tools` 和 `%USERPROFILE%\Android\Sdk`，不修改系统 PATH：

```
powershell -ExecutionPolicy Bypass -File _setup-toolchain.ps1
build.cmd
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

已有 JDK 21 和 SDK 的机器可以跳过第一步。`build.cmd` 会依次找 `%USERPROFILE%\tools\jdk21`、`JAVA_HOME`、Android Studio 自带的 JBR，SDK 则认 `ANDROID_HOME` 和 Android Studio 的默认目录。仓库自带 Gradle wrapper（8.13），第一次编译会自动下载 Gradle。

发布（签名）包：`gradlew assembleRelease`。签名信息从 `keystore.properties` 读，没有这个文件时 release 走默认未签名，debug 不受影响。

## 连接方式

| 你填的 | 实际请求的 |
| --- | --- |
| `192.168.1.5` | `http://192.168.1.5:2332` |
| `192.168.1.5:2332` | `http://192.168.1.5:2332` |
| `chat.example.com` | `http://chat.example.com:2332` |
| `https://chat.example.com` | `https://chat.example.com` |

## 数据

聊天记录和媒体都存**服务端**（见 dsh-auralife 的「数据」一节）。客户端本地只留：登录态、草稿、主题偏好、媒体缓存（`cacheDir/media`，LRU 300 MB），删 app 数据即彻底清空本地。

## 使用的接口

`api/login`、`api/logout`、`api/state`、`api/messages`（GET/POST，`?file=last|prev` / `?before=`）、`api/media/<id>`、`api/typing`、`api/stream`、`api/stickers`、`api/sticker/<md5>`，以及设置页整套：`api/config`、`api/status`、`api/logs`、`api/test`、`api/memory*`、`api/diary*`、`api/personas*`、`api/guests*`、`api/avatar`、`api/background`、`api/voice/test`、`api/account/password`。

## 目录结构

```text
auralife-client-android/
├── app/
│   ├── build.gradle.kts          应用模块
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/dev/auralife/client/
│           ├── MainActivity.kt   入口 + 底部导航
│           ├── AuraApp.kt
│           ├── LiveService.kt    前台服务保活
│           ├── Version.kt
│           ├── data/Prefs.kt     本地偏好
│           ├── net/              网络层
│           │   ├── AuraApi.kt    HTTP 接口 + SSE
│           │   ├── Live.kt       聊天状态 / 分片分页
│           │   ├── Media.kt      媒体下载与缓存
│           │   ├── Images.kt     图片解码
│           │   ├── SettingsApi.kt
│           │   ├── Tls.kt        自签证书指纹
│           │   └── Voice.kt      语音
│           └── ui/               Compose 界面
│               ├── ChatScreen.kt     聊天
│               ├── ConnectScreen.kt  连接页
│               ├── SettingsScreen.kt / SettingsSections.kt
│               ├── MemoryScreen.kt / LogsScreen.kt / NavScreens.kt
│               ├── AppTheme.kt       主题效果
│               └── UiKit.kt          通用组件
├── icon/                          图标源
├── build.cmd                      编译
├── _setup-toolchain.ps1           安装 JDK 21 和 Android SDK
├── gradlew / gradlew.bat          Gradle wrapper
├── build.gradle.kts / settings.gradle.kts / gradle.properties
├── keystore.properties            签名配置
├── local.properties               Android SDK 路径（可选）
└── README.md
```
