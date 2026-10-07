---
status: active
updated: 2026-10-07
---

# 自更新与热更指南（拾光）

拾光的更新体系分两层，按需启用：

| 层 | 机制 | 改什么 |
|---|---|---|
| 应用内自更新 | app 检查远程清单 → 下载 APK（带进度 + SHA-256 校验）→ 拉起系统安装器 | 一切（整包） |
| 配置热更 | 清单里的 `config` 段随检查更新下发并缓存 | 公告、MCP instructions、功能开关 |

> Shorebird（Dart 代码补丁热更）为可选第三方方案，拾光当前未接入；如需可参考拾贝（goodshare）接入流程，本仓库暂仅做整包自更新。

## 1. 更新源托管（应用内自更新 + 配置热更）

任选一个静态托管（推荐 **gitee**，国内可达；github raw / 自建均可）：

1. 建一个仓库（可私有转公开 raw，或用 gitee Pages），建目录 `updates/`
2. 放两样东西：
   - `shiguang-update.json`（清单，格式见下）
   - APK 文件（如 `app-release.apk`）
3. 在 app「设置 → 应用更新」页填入目录 URL，如 `https://gitee.com/<user>/<repo>/raw/master/updates`

### Cloudflare R2（推荐：国内外均稳、免流量费、可控）

适合作为常驻更新源。前置：Cloudflare 后台对该 bucket 开启「公开访问」并绑定自定义域（如 `update.example.com`），确保 `https://<域>/updates/...` 可公开 GET。

1. 准备仓库根 `.env`（参考 `.env.example`）：填 `R2_ACCOUNT_ID` / `R2_ACCESS_KEY_ID` / `R2_SECRET_ACCESS_KEY` / `R2_BUCKET` / `R2_PUBLIC_DOMAIN`。无需额外 CLI（上传由 `scripts/upload-r2.mjs` 用 Node 内置模块完成）。
2. 仓库根执行：`bash scripts/release-r2.sh`（可选 `--commit` 把清单纳入版本管理）。
   > 版本号：发布前先 `toolbox run bump-version --build` 抬 versionCode（须严格大于真机已装值，否则 app 判「已是最新」不提示）。changelog 用 `CHANGELOG=文案 bash scripts/release-r2.sh` 传入，留空则取最近一条 git commit 主题；注意 `.env` 里不要留空的 `CHANGELOG=` 行——`source .env` 会用它覆盖外部传入值（2026-10-07 发布实测踩坑，该行已注释）。
   脚本自动：读取 `pubspec.yaml` 版本 → `flutter build apk --release`（arm64-v8a）→ 算 sha256 → 生成 `updates/shiguang-update.json` → 上传 apk + json 到 R2 公开域。
3. 脚本末尾打印「更新源 URL」，形如 `https://update.example.com/updates`，填进 App「应用更新」页。
   - `release-r2.sh` 已在 `flutter build` 时通过 `--dart-define=UPDATE_SOURCE_URL=...` 把该源编进 APK，因此**装上即自带默认更新源，首次无需手动填**；App「应用更新」页仍可手动改源覆盖。

> `r2.dev` 默认公开域有限速，不建议作生产更新源；生产请绑定自定义域（自定义域免 egress 流量费）。`R2_PUBLIC_DOMAIN` 与 `REMOTE_PREFIX` 共同决定最终 URL 与 App 端源地址，务必一致。

### 清单格式 shiguang-update.json

```json
{
  "versionCode": 3,
  "versionName": "1.1.0",
  "apkUrl": "https://gitee.com/<user>/<repo>/raw/master/updates/app-release.apk",
  "sha256": "<apk 的 sha256，建议必填>",
  "changelog": "修复文本分享丢失；新增热更体系",
  "config": {
    "announcement": "公告文本，可省略",
    "announcementId": "2026-09-27-1",
    "mcpInstructions": "覆盖 MCP initialize 的 instructions，可省略",
    "flags": {}
  }
}
```

- `versionCode` 与 `pubspec.yaml` 的 `+N` 比较，**大于**才提示更新
- `sha256` 生成：`sha256sum app-release.apk`
- `config.mcpInstructions` 非空时会覆盖 MCP `initialize` 返回的服务说明（下次客户端连接生效）
- 公告随「检查更新」刷新并缓存，更新页与设置页「应用更新」卡片展示

### config.flags（功能开关热更）

`config.flags` 是可选 map，目前仅作通用开关容器；非空时随检查更新缓存进 `RemoteConfigStore`，业务侧按需读取（如后续接入端侧模型目录下发可复用此通道）。

### 发布流程

```bash
flutter build apk --release           # 模板默认用 debug 签名，个人使用可用；正式分发请配 release 签名
cp build/app/outputs/flutter-apk/app-release.apk <更新源目录>/
sha256sum build/app/outputs/flutter-apk/app-release.apk
# 编辑 shiguang-update.json 的 versionCode/versionName/sha256/changelog
git add . && git commit && git push   # gitee raw 即时生效
```

## 2. 数据落点

| 资产 | 位置 |
|---|---|
| 清单模型 / 远程配置模型 | `lib/update/update_manifest.dart` |
| 检查/下载/SHA-256/源管理 | `lib/update/update_service.dart` |
| 远程配置本地缓存（last-good） | `lib/update/remote_config_store.dart` |
| 系统安装器拉起 | `lib/update/installer.dart` |
| 更新页 UI | `lib/ui/update_page.dart` |
| 设置页入口 | `lib/ui/settings_page.dart`（`_updateSection`） |
| MCP instructions 热更消费 | `lib/service/mcp_controller.dart`（`instructionsProvider`） |
| 启动预读缓存 | `lib/app_services.dart`（`AppServices.init`） |
| 安装权限 | `android/app/src/main/AndroidManifest.xml`（`REQUEST_INSTALL_PACKAGES`） |

## 安全提示

- 清单与 APK 建议放同一可控源；`sha256` 校验已内置，**不要**留空发布
- 更新源可随时在 app 内修改；发现源被污染立即换源并重置
