# DSH Mobile

[English](README.md) | 中文

DeepSeek Harness 家族的安卓端对话客户端。原生 Kotlin + Jetpack Compose，直连任意
**OpenAI 兼容**接口，不需要在手机上跑 `dsh` 引擎。

`dsh-desktop` 是给本机引擎做的外壳；手机上跑不了 Node 引擎，所以这个 App 走的是另一条路：
自己实现流式对话、思考过程展示和本地会话存储，把网关当作唯一依赖。

```
┌─ MainActivity (Compose) ──────────────────────────────┐
│  ChatScreen      抽屉 / 消息流 / 输入栏 / 模型选择      │
│  SettingsScreen  服务、模型、生成参数、界面、数据        │
│  ui/markdown     自研 Markdown 子集 + 代码高亮          │
└───────────────┬───────────────────────────────────────┘
                │ StateFlow
┌───────────────▼───────────────────────────────────────┐
│  ChatViewModel   流式编排、多服务、重试/停止/改写       │
│  data/ContextWindow  按 token 预算选窗口（计量表与请求  │
│                      共用，保证「显示的就是发出去的」） │
├───────────────────────────────────────────────────────┤
│  net/OpenAiClient   SSE 解析、reasoning_content、错误   │
│  data/ConversationStore  一会话一个 JSON，写入合并      │
│  data/SettingsStore      settings.json                 │
└───────────────────────────────────────────────────────┘
                │ HTTPS
                ▼
   https://<gateway>/v1/chat/completions
```

## 功能

**对话**
- 流式输出，随时停止；关掉流式则走一次性返回。
- **思考过程**：DeepSeek 的 `reasoning_content` 单独渲染成可折叠卡片，思考中默认展开、出答案后自动收起，并显示耗时与字数。
- **Markdown 渲染**：标题、列表（含任务勾选框）、引用、表格、分割线、行内样式、链接，代码块带语言标签、一键复制和轻量语法高亮。
- 消息操作：复制、重新生成、删除；用户消息可编辑后重新发送。
- **HTML 预览**：模型写出整页 HTML 时，代码块上会出现「预览页面」——全屏 WebView 直接跑，JS 可用；还能交给浏览器打开或分享成文件带出手机。只认整页不认片段，流式输出没结束前不显示。
- 输出被 `max_tokens` 截断时给出「继续生成」；只返回思考过程、没有正文时也会说明原因。
- 多会话，可搜索、重命名、置顶、删除；标题自动取首条提问。导出整段对话为 Markdown。
- 每个会话可以有自己的系统提示词，留空则用全局的。

**附件**
- **文件**：代码、日志、Markdown、CSV、JSON、YAML 等能解码成文本的文件，直接以 `<file name="…">` 块拼进提示里 —— **任何文本模型都能用**，不需要多模态。二进制格式会被明确拒绝而不是塞进去乱码。
- **Office 文档**：`.docx` / `.xlsx` / `.pptx` 都是 zip 装 XML，直接解出文字，不引第三方库。表格按制表符分行、空单元格保位以对齐表头，幻灯片带页码。旧版 `.doc/.xls/.ppt` 是二进制格式，会明确告知改存新格式。
- **PDF**：平台只能把 PDF 栅格化，所以按页转成图片（最多 8 页）交给能看图的模型。
- **图片**：相册选择或直接拍照，上传前压到 1280px 以内重新编码。
- 当前模型不支持图片时，**不会把按钮灰掉** —— 会自动切到同一服务里能看图的模型并告知；整个服务都没有才拒绝。
- 别的应用可以把文字或图片**分享**进来，落在输入框里而不是直接发出去。
- 语音输入（走系统语音识别）。

**服务与模型**
- **可配置多个 OpenAI 兼容服务**，各自独立的 Base URL 和 Key，一键切换、可测试连接。每个会话记住它用的服务与模型，重新打开就回到那一对。
- 模型列表来自 `GET /v1/models`，按服务分组，标注「看图 / 思考 / 出图 / 上下文长度」。

**上下文**
- 按 **token 预算**裁剪，不是按消息条数。自动模式按模型窗口留出回复空间；也可手动在 4K…1M 的档位里选。
- 顶栏实时显示占用，形如 `12K / 1M`；需要丢弃早期消息时显示「裁剪 N」。
- 附件按实际成本计入预算（文本按估算字数，图片按固定费率）。

**其它**
- **应用内更新**：见下一节。
- 主题跟随系统 / 浅色 / 深色；回车发送可选。
- 会话与图片只写在应用私有目录，`allowBackup=false`。

## 应用内更新

不用再手动下载安装包。**设置 → 更新** 里可以「检查更新」，发现新版就在应用内下载、校验、直接调起安装。

默认更新源是本仓库的 GitHub Releases：

```
https://api.github.com/repos/adgry/dsh-mobile/releases/latest
```

它同时认两种格式 —— GitHub 的 `releases/latest` 响应，以及自建的 `update.json`：

```json
{
  "versionCode": 10301,
  "versionName": "1.3.1",
  "apkUrl": "https://example.com/dsh-mobile-1.3.1.apk",
  "sha256": "<小写十六进制，可选但强烈建议>",
  "sizeBytes": 1780000,
  "notes": "更新说明，支持 Markdown"
}
```

想换成自己的服务器，只改设置里的地址即可。给了 `sha256` 就会在安装前校验，没给则只依赖 HTTPS。

两个绕不开的前提：

- **系统要允许本应用安装应用。** 这是 Android 的规定，任何应用都无法自己绕过。首次更新时应用会引导你去开这个开关（设置里也能看到当前状态）。
- **新旧版本必须用同一个签名密钥。** 否则系统会拒绝覆盖安装。所以 `keystore/` 一定要备份好，见下文。

## 发布新版本

发布由 GitHub Actions 完成，因为它要同时做「构建 + 签名 + 上传附件」三件事：

```sh
git tag v1.3.1 && git push origin v1.3.1
```

也可以在 Actions 页面手动运行 `Release APK` 并填版本号。流程跑完会生成一个带 APK 附件的 Release，应用端的「检查更新」随即就能发现它。

本仓库的 Secret 已经配好了。**如果你 fork 或换了签名密钥**，在 **Settings → Secrets and variables → Actions** 里补上（只做一次）：

| Secret | 值 |
| --- | --- |
| `KEYSTORE_BASE64` | `base64 -i keystore/dsh-release.jks` 的输出 |
| `KEYSTORE_PASSWORD` | 密钥库口令 |
| `KEY_ALIAS` | 密钥别名（本项目是 `dsh`） |
| `KEY_PASSWORD` | 密钥口令 |
| `DEFAULT_API_KEY` | 可选：想预填进安装包的网关 Key |

没配 `KEYSTORE_BASE64` 时流程会直接失败并说明原因 —— 这是故意的，免得发出一个签名不同、装不上去的包。

也可以用 `gh` 一次性设好，值直接从本地未跟踪的文件里读，不经过屏幕：

```sh
base64 -i keystore/dsh-release.jks | tr -d '\n' | gh secret set KEYSTORE_BASE64
grep '^storePassword=' keystore.properties | cut -d= -f2- | tr -d '\n' | gh secret set KEYSTORE_PASSWORD
grep '^keyAlias='      keystore.properties | cut -d= -f2- | tr -d '\n' | gh secret set KEY_ALIAS
grep '^keyPassword='   keystore.properties | cut -d= -f2- | tr -d '\n' | gh secret set KEY_PASSWORD
```

## 安装

直接装 `app/release/dsh-mobile-<version>.apk`（或 `app/build/outputs/apk/release/app-release.apk`）。
需要 Android 8.0（API 26）及以上。手机上要先允许「安装未知来源应用」。

首次打开即可用：默认已填好开发期使用的网关（`https://token.sensenova.cn/v1`）和一把测试
Key，模型默认 `deepseek-v4-flash`。换成自己的服务在 **设置 → 服务** 里改，改完点「测试连接」。

## 从源码构建

需要 JDK 17+（本仓库用 21 验证）和 Android SDK（compileSdk 37、build-tools 37.0.0）。

```sh
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
cp secrets.properties.example secrets.properties   # 可选：填入想预填的网关 Key

./gradlew assembleDebug        # 调试包
./gradlew assembleRelease      # 发布包（R8 压缩 + 签名）
./gradlew :app:collectRelease  # 顺带把 APK 复制成 release/dsh-mobile-<版本>.apk
```

Gradle wrapper 已随仓库提交，版本锁在 9.7.0，不需要本机预装 Gradle。

### 预填的 API Key

`secrets.properties` 里的 `defaultApiKey` 会在构建时注入成 `BuildConfig.DEFAULT_API_KEY`，让新装的应用不用先填 Key 就能对话。这个文件**不进版本库**，所以仓库公开也不会泄露可用凭据；留空就是发一个什么都没预填的包。

### 签名

`keystore.properties` 指向 `keystore/dsh-release.jks`。两者都不进版本库。

> **务必备份 `keystore/` 和它的口令。** 应用内更新依赖签名一致：密钥丢了，之后发布的所有版本都无法覆盖安装到已有的应用上，只能让用户卸载重装（会丢本地会话）。

`keystore.properties` 不存在时，release 会退回用 debug 签名，构建仍然能过 —— 但那样出来的包和正式包签名不同，装不上去，只适合本地跑一下。

## 几个值得记下来的坑

**AGP 9 自带 Kotlin。** 再显式应用 `org.jetbrains.kotlin.android` 会直接报错退出。
AGP 9.3.1 内置 KGP 2.2.10，所以 `libs.versions.toml` 里的 `kotlin` 版本必须与它一致，
Compose 和 serialization 这两个编译器插件也跟着它走。`jvmTarget` 不用单独设，默认取
`android.compileOptions.targetCompatibility`。

**Compose BOM 2026.08.00 要求 compileSdk 37。** 低于它会在 `checkDebugAarMetadata`
阶段失败，报每个 androidx 依赖都要求 API 37。

**网关的流式细节**（`token.sensenova.cn`，DeepSeek 方言）：
- 中间 chunk 的 `finish_reason` 是空字符串 `""` 而不是 `null`，当成「没有」处理。
- 传 `stream_options.include_usage: true` 才有 token 统计，它会在最后单独发一个
  `choices: []` 的 usage chunk —— 解析时必须容忍空 `choices`。
- 错误里的 `code` 有时是字符串有时是数字，所以错误信息是从 JSON 树里手工取的，
  没有用严格 DTO。
- `supported_sampling_parameters` 只有 `temperature` 和 `stop`，别顺手带上 `top_p`。
- 有 TPM 限流，会返回 `429`（例如 `inference tpm exhausted / 429001`）；App 把这类
  错误标成可重试，并在消息里给出重试按钮。

**待发送的图片必须计入预算。** 输入框里挂着的图片下一轮就会发出去，如果只把它算进「已用」
而不占预算，计量表会出现「显示超了却说不裁剪」的自相矛盾，请求也没给图片留位置。

**升级不能弄丢用户的 Key。** 多服务之前的版本把 `baseUrl`/`apiKey` 写在配置顶层，这两个字段
现在已不在 `AppSettings` 上，`ignoreUnknownKeys` 会直接忽略它们、退回内置默认值 —— 所以读盘时
会从原始 JSON 里把它们捞出来折成第一个服务。这条有单测覆盖。

**`max_tokens` 对思考型模型是陷阱。** 额度会先被 `reasoning_tokens` 吃掉：给 200 时
测试请求的 200 个 token 全花在思考上，`finish_reason` 直接是 `length`，正文一个字都没有。
所以默认不发这个字段。

**Compose 里「末尾是否可见」永远不能用来做流式自动跟随，`max_tokens` 也别当成输出长度。** 这两条和下面几条一样，都是真机上踩出来的。

**用户按「停止」不是错误。** 停止会关掉 socket，阻塞中的读取随即抛出
`IOException("Socket closed")`，而这个异常可能比协程的取消先到达收集方 —— 于是一条好端端的
半截回复会被标成红色错误卡。所以 ViewModel 用一个 `stopRequested` 标记记录意图，而不是去猜
异常类型。

**流式的自动跟随不能用「末尾是否可见」来判断。** 回复增长会在两次重绘之间把尾部顶到折叠线
以下，任何严格判断都会在第一次溢出时翻成 false 并且再也回不来，画面就卡在答案中间。列表末尾
恒有一个占位 item，所以合理的阈值是「折叠线下超过这一个占位」，而不是「有任何东西在下面」；
再留半屏余量，既跟得上流式，也允许读者主动往上翻。

**明文 HTTP 是放开的。** 这个 App 里所有地址都由用户自己填 —— 网关、局域网自建的模型服务、家里机器上的更新清单。Android 默认禁止明文会让这些场景以一个看不懂的错误失败，比让用户自己承担已经选择的风险更糟。代价是真实的：走 http 时 API Key 是明文传输，离开局域网的连接请用 https。

**一条只返回思考过程的回复也必须有页脚。** `max_tokens` 太小时额度会先被 `reasoning_tokens` 吃光，正文为空 —— 而这恰好是最需要「已达长度上限」和「继续生成」的时候。早先按「有没有正文」来决定是否显示页脚，结果那种情况下只剩一张孤零零的「已思考」卡片，连重新生成都点不到。

**顶栏的 token 数是估算，和账单不完全一致。** 它估的是「我们发出去的文本」：实测一次文档提问，估 120、网关计 182，同一量级。但思考型模型长时间推理后，网关会把推理上下文也计入 input（同一条实测到 `↑4.3k`），那不是 App 多发了东西。裁剪只需要量级对，所以估算刻意偏高。

## 目录

```
app/src/main/java/com/dshmobile/app/
├── DshApp.kt            Application + 手写容器
├── MainActivity.kt      主题、聊天/设置切换
├── data/                模型、设置存储、会话存储、上下文窗口规划
├── net/OpenAiClient.kt  OpenAI 兼容客户端（SSE + 非流式 + /models）
├── ui/                  ChatScreen、SettingsScreen、Composer、抽屉、模型选择、消息
│   ├── components/      小按钮、打字动画、空状态、附件缩略图
│   ├── markdown/        块解析、行内解析、渲染、代码高亮
│   └── theme/           品牌配色（不使用 Material You，保持跨机型一致）
└── util/                图片缩放、剪贴板、时间与数字格式化
```
