---
status: active
updated: 2026-11-15
---

# 行为清单 · update_page（更新子页 + RemoteConfigStore Kotlin 化，N8c 施工输入，2026-11-15 D23 过目转正）

> §9.1.6 模板产出（2026-11-15 N8c 前置抽取）。抽取源 = `lib/ui/update_page.dart`（295 行全文）+ `lib/update/update_service.dart`（153 行）+ `lib/update/update_manifest.dart`（86 行）+ `lib/update/installer.dart`（46 行）+ `lib/update/remote_config_store.dart`（49 行）+ `docs/guide/self-update.md`（托管格式）+ `android/.../MainActivity.kt`（installApk PackageInstaller 会话通道已平移）。**范围 = 更新页全件（当前版本/检查/下载/安装/公告/更新源）+ RemoteConfigStore + UpdateService/Manifest Kotlin 化**；公告行在设置主页的渲染归 settings_page 清单（§B 已含挂账）。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | 应用内自更新（检查→下载→校验→安装，静态托管单清单文件）+ 远程配置热更入口（公告/MCP instructions 随清单 config 段下发，检查更新成功即落缓存） |
| 触发与阈值 | 按钮相位机：检查钮=bootstrap 完成且 phase==idle；下载钮=远程版本更新且 phase∈{idle,checking}；安装钮=phase==readyToInstall；下载进度回调逐 chunk |
| 状态空间 | ui-pages §8 C 档 2 态，基线 `update_page S1/S2` 已采（manifest）；页内相位 idle/checking/downloading/readyToInstall 四态 + 消息行/公告行条件渲染 |
| 落库命令与拒绝码 | 无 ScheduleCommand——更新源走 SharedPreferences 键 `update_source_url`（非 app_settings）；RemoteConfig 缓存文件 documents/remote_config.json；错误串逐字见 §A（UpdateException.message，非 ActionErrorCode 族） |
| 反馈形式 | 全 SnackBar 3s（error 底色=colorScheme.error 区分）；相内消息行 bodySmall；下载中 LinearProgressIndicator（total 已知=比例/未知=indeterminate） |
| 黄金向量指针 | 无专集（自更新链路为平台面，无内核向量；sha256 校验属下载器内建）；MCP instructions 消费端语义随 mcp 清单 §C |
| 像素基线指针 | `update_page S1/S2`（manifest 已采）；相位中间态（downloading/readyToInstall）为瞬态不采 |

## §A 页面行为

| 行为 | 口径 |
|---|---|
| bootstrap | PackageInfo 版本读取（缺省「读取中…」）+ SharedPreferences 取 sourceUrl 回填输入框（saved 优先，回退编译期 UPDATE_SOURCE_URL 注入值，双空=null）+ 公告=RemoteConfigStore.current.announcement；完成后 _ready=true 解锁按钮 |
| 保存更新源 | trim；空=移除用户值（回退内置默认源）；非 http/https 或解析失败 → 「更新源必须是合法的 http(s) URL」错误 SnackBar；成功 SnackBar「更新源已保存」 |
| 检查更新 | phase=checking 清消息/APK → GET {source}/shiguang-update.json（尾斜杠归一）：源未配置 →「尚未配置更新源」；HTTP≠200 →「清单拉取失败：HTTP n」；JSON 格式错误 →「清单格式错误：{msg}」（versionCode 非整数/versionName·apkUrl 缺失或类型错均入此）；网络异常 →「网络异常：{e}」；清单声明 applicationId 且≠本机包名 →「更新包身份不符（疑似串包，已拒绝）：清单=x 本机=y」（多 app 共源防串包）；成功 → **RemoteConfigStore.save(manifest.config)**（公告/announcementId/mcpInstructions/flags 落缓存）+ 消息「发现新版本 v{versionName}（{changelog ?? 无更新说明}）」或「已是最新版本（远程 v{versionName}）」 |
| 下载 | 仅 manifest.isNewerThan(本机 buildNumber) 显钮；phase=downloading 清零进度 → 流式下载 APK 边下边算 sha256（目标 documents/updates/update.apk.part）：HTTP≠200 →「APK 下载失败：HTTP n」；sha256 预期值不符 → 删临时文件 +「SHA-256 校验失败，安装包可能被篡改或下载不完整」；成功 rename 为 update-<毫秒时间戳>.apk → phase=readyToInstall +「下载完成并已通过完整性校验，点击安装」；其他异常「下载异常：{e}」 |
| 安装 | canRequestPackageInstalls 未授权 → openUnknownSourceSettings 拉系统「安装未知应用」授权页（直达本应用条目）+ toast「请在系统页允许「安装未知应用」后，回到本页再点安装」（授权回页重试；原实现仅 toast 兜底文案的修复版）；已授权 → installApk（MethodChannel app.shiguang/installer——**Kotlin PackageInstaller 会话通道已平移**，绕开 ACTION_VIEW+FileProvider Android 14+「软件包无效」坑）；异常 → 错误 SnackBar「安装失败：{msg}」 |
| 页骨架 | AppBar（leading 关闭 X tooltip「关闭」pop + title「更新」，automaticallyImplyLeading=false）+ ListView padding 16 三卡：①当前版本/远程更新卡（version（build n）+远程 version（code）+消息行+进度条+按钮 Wrap spacing 8）②公告卡（campaign_outlined+「公告」+subtitle，非空才显）③更新源卡（说明文案「默认更新源已内置（构建时注入）…托管格式见 docs/guide/self-update.md」bodySmall + TextField hint「https://<host>/updates」isDense + 保存钮 save_outlined「保存更新源」） |

## §B RemoteConfigStore Kotlin 化（本轮挂账转正项）

| 件 | 口径 |
|---|---|
| 形态 | 单例 current（RemoteConfig：announcement/announcementId/mcpInstructions/flags Map）——Kotlin=object + MutableStateFlow 或普通单例（消费方只读快照，**无监听需求**：公告行随页面自建刷新，MCP instructions 每次调用现取） |
| load | documents/remote_config.json 存在才读；解析失败/任意异常 → 回 RemoteConfig.empty（缓存损坏等下次检查更新重建——**吞异常有据：DEGRADE 注记随行**） |
| save | 四键条件序列化（null 不写键；flags 空不写）+ 同步 current |
| 消费方 | ①设置主页公告行（settings_page §B）；②McpController.instructionsProvider（mcp 清单 §C）——load 时机=冷启动装配（app_services bootstrap 顺序：restoreIfNeeded 后 load） |

## §C UpdateService/Manifest Kotlin 化

- sourceUrl：saved 非空优先 → 编译期内置默认源（BuildConfig 字段或 gradle 注入，**--dart-define 等价物随施工轮定**）→ null；setSourceUrl 校验/移除同 §A。
- check()：HttpClient 等价物（OkHttp/HttpURLConnection 随施工轮定，P3 网络栈口径）+ 包身份校验（packageName）。
- downloadApk()：流式落盘 + 边下边 sha256（MessageDigest/DigestInputStream）+ .part rename 原子收尾 + 进度回调。
- UpdateManifest.parse：versionCode int / versionName·apkUrl String 强校验（缺失/类型错 → 格式错误串）；config 段选填。

## §D 过目点（须人工拍板）

1. **RemoteConfigStore 形态**：object 单例 vs AppContainer 注入——迁移第四轮拍板②「纯 Manual DI（AppContainer 单例，禁 Composable 内 new 单例）」倾向 **AppContainer 注入**，但 Dart 侧是全局单例、消费方（instructionsProvider）在控制器闭包内。**默认建议=AppContainer 装配注入**（守 DI 纪律，单例语义由容器保证）。
2. **默认更新源注入**：Dart 走 `String.fromEnvironment('UPDATE_SOURCE_URL')`——Kotlin 等价物 gradle `buildConfigField`。release 分包是否已有注入脚本待核（build-apk 流水线）。**默认建议=buildConfigField 注入，debug 缺省空串**。
3. **SnackBar 3s**：本页 5 处 3s——Compose SnackbarDuration 只有 Short(4s)/Long(10s)，丢失点 #1 自建 delay 解法（ShiguangScaffold 组件族已建与否随 UI 基建轮核对）；照抄 3s。
