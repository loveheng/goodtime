---
status: active
updated: 2026-11-15
---

# 行为清单 · 配置面小件族清单（P3③，待人工过目——纯清单，实现挂 P4 消费面）

> §9.1.6 模板产出（2026-11-15 晨间补走抽取，抽取源=lib/update/ 全域 + lib/main.dart dotenv 段 + lib/ui/fact_sheet.dart 外链 + relay_config.dart）。
> 定位：**本轮零代码**——清单先行防 P4 脚手架式预建；各件实现随其消费 UI Compose 化同轮接线（禁无消费方先建端口）。

## 配置面清单（五件）

| 件 | Dart 现状 | Kotlin 目标 | 消费面（P4 接线时机） |
|---|---|---|---|
| 更新源持久化 | SharedPreferences 单键 `update_source_url`：getString（空→回退编译期 `UPDATE_SOURCE_URL` dart-define）/ setString（trim 后存）/ remove（空串语义=清除回退默认）；setSourceUrl 校验 http(s) 合法 URL 否则抛 UpdateException「更新源必须是合法的 http(s) URL」 | DataStore Preferences 同键名；校验在命令层（UpdateService Kotlin 件同轮） | 更新页（update_page Compose 化） |
| 远程配置缓存 | `<documents>/remote_config.json`：RemoteConfig{announcement?, announcementId?, mcpInstructions?, flags} JSON；读损坏→回空配置等下次检查重建；写入=四键有则存 | filesDir 同名文件（getApplicationDocumentsDirectory ≈ Kotlin filesDir 映射待 P4 确认）；损坏回空语义保留 | 更新检查链（UpdateService Kotlin 件） |
| 版本号 | PackageInfo.fromPlatform（version/versionCode/buildSignature 消费于更新比对） | PackageManager.getPackageInfo | 更新页+自更新通道 |
| relay 配置 | `config/relay.env` dotenv 于 main 早期 load；relay_config.dart 读取（**relay 默认关、显式配置才挂载**——online 拍板） | BuildConfig 字段或独立 assets 解析；**挂 B 档 relay 资源联调同轮**，勿单独预建 | relay 联调轮（挂账） |
| 外链 | fact_sheet.dart 两处 canLaunchUrl+launchUrl（外部浏览器） | Intent.ACTION_VIEW | 事实页 Compose 化 |

## 平移注记

- update_service/update_manifest/installer 的**命令面与清单校验逻辑**（SHA-256/版本比较/PackageInstaller 会话）是独立行为域——MainActivity 已原生承载安装通道（01690df 前），Dart UpdateService 域逻辑抽取挂 P4 更新页轮（另出清单，本文件只登记配置面）。
- `update_source_url` 为全仓唯一 SharedPreferences 键（已 grep 核实）——DataStore 落地即完成 shared_preferences 摘除前提。
