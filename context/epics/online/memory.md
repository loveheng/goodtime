---
dev-loop: memory
format: v1
epic: online
total-merged: 7
last-merge: 2026-10-10
---

- [挂载] /home/zzh/app/shiguang-relay/README.md（**独立仓**，契约版本账 v0.1.6-contract + Phase 1 进度）
- [挂载] docs/design/collab-implementation.md §0–§2（Phase 0/1 施工表）
- [挂载] docs/design/collab-schedule-draft.md（协同设计 SSOT，§8.4 双仓边界）
- [挂载] docs/design/fact-user-relay-draft.md §5/§7.1（中继服务契约 + 停火协议）

# online —— 协同中继上线（relay 独立仓 Phase 0 → Phase 1）

状态只记进度/决策/指针；**代码 SSOT=独立仓 `/home/zzh/app/shiguang-relay`（Go，零拾光依赖）**，本 epic 只记决策、验证与断点。
**停火协议（fact §7.1-5）**：Phase 0 只交付契约产物（proto + DDL）并打 tag、**零 Go 业务代码**；tag `v0.1.6-contract` 后解除，Phase 1 起可写业务代码。

## 进度

- [2026-10-09] 自 `background-context-draft` 切绑建档（用户指令：收口 Phase 0 后开中继）。切绑审计——Phase 0 五步仅 0.2 半到位：0.1 仓骨架 ❌ 缺；0.3 双端代码生成 ❌ 缺；0.4 DDL 未跑 `psql -f`；0.5 契约版本与 tag 脱节（README v0.1.6 / tag v0.1.1）。
- [2026-10-09] **双仓分离**（用户指令：中继要独立项目，不混进拾光主仓）：主仓 `shiguang-relay/` 并入同级**已存在**的独立仓 `/home/zzh/app/shiguang-relay`，主仓侧移除该目录；docs 引用路径不变（独立仓内同名文件）。
- [2026-10-09] **Phase 0 契约冻结完成**（commit `0e320ec` + tag `v0.1.6-contract`）：仓骨架 + proto `go_package` + 双端生成 + 容器内建库 10 表且 seq 唯一约束生效 + README 鉴权定稿章节。
- [2026-10-09] **Phase 1.1 鉴权闭环完成**（commit `bf9b494`）：`cmd/relayd` + `internal/{config,apierr,store,auth,httpapi}`——challenge（nonce 32B + PoW 题面，Redis SETEX 60s、GETDEL 一次性消费）/ login（PoW → Ed25519 PoP 验签 nonce → 双 Token HS256 + 算法白名单）/ refresh / Bearer 鉴权 401 / `client_msg_id` 幂等（SETEX 300，重放原样回放 + `X-Idempotent-Replay: 1`，时钟偏差超窗 400）；统一错误码响应体（`error_code/message/detail` 零明文）；`/v1/schedule/action` 501 占位待 1.4。

## 联调事实

- [2026-10-10] **真 relay 公网地址 = `gs.oklhj.eu.org`**（用户口述）。改为**配置文件**注入（用户不希望在命令里写）：`config/relay.env` 的 `RELAY_BASE_URL=https://gs.oklhj.eu.org`，由 `flutter_dotenv` 在 `main` 启动早期 `dotenv.load(fileName:'config/relay.env')` 载入；`RelayConfig.fromEnvironment()` 三级解析 = `dart-define`(CI/临时覆盖) > `relay.env`(默认联调值) > `http://10.0.2.2:8080`(模拟器回环兜底，勿改)。`RelayConfig` 据 `http(s)→ws(s)` + 纯路径 `/ws` 推导 WSS 地址。

## 构建配置

- [2026-10-10] **Android 构建链升级（release 构建收口）**：根因 `permission_handler_android`(^13.0.2) 要求 `compileSdk ≥ 37`，而原 AGP 9.1.0 上限即 36 → `:app:checkReleaseAarMetadata` 失败。三处改动（均在 Flutter 3.47.5 支持矩阵内：AGP 上限 9.2、Gradle 上限 9.3.1，但 AGP 9.2 最低要 Gradle 9.4.1 故 Gradle 超出 Flutter「已知」上限仍可用）：①`android/settings.gradle.kts` AGP `9.1.0 → 9.2.0`（支持 compileSdk 37）；②`android/app/build.gradle.kts` `compileSdk = flutter.compileSdkVersion`(=36) → 硬编码 `compileSdk = 37`（独立于 targetSdk/minSdk，AGP 官方允许单独上调）；③`android/gradle/wrapper/gradle-wrapper.properties` Gradle `9.3.1 → 9.4.1`（AGP 9.2.0 最低要求）。验证：`flutter build apk --release` 通过，产出 `build/app/outputs/flutter-apk/app-release.apk`(78.4MB)；`bonsoir_android` 使用 KGP 仅为告警（未来 Flutter 版本才强制），非阻断。

## 口径决策

- 生成物落点：`gen/go/` + `gen/dart/` 全在独立仓，**不进拾光主仓**——Dart 侧 `lib/collab/` 装配与「import 编译通过」验收延至 Phase 2（collab-implementation §0「Phase 2 起才碰拾光主仓」）。
- 契约冻结 tag 在 Phase 0 五步全部验收后打（版本 `v0.1.6-contract`，与 README 版本账对齐）；Phase 1 起每个可验收切片单独 commit，不再打 contract tag。
- **本机容器编排 = podman-compose**：脚本统一 `COMPOSE` 变量（默认 `podman-compose`，可覆盖 `COMPOSE="docker compose"`）；Go 代理走 goproxy.cn（proxy.golang.org 超时）。

- [2026-10-09] **Phase 1.2 注册与 PreKey 托管完成**（commit `0b69b88`）：`POST /v1/users`（PoP 自签 + PoW + 单 IP 限流 3/min + OPK 首批 10~20，**单事务**落 users 与 key_envelopes）/ `GET /v1/users/{id}/prekey`（`FOR UPDATE SKIP LOCKED` + `DELETE ... RETURNING` 原子弹出 1 个 OPK，空池恒 200 + `OPK_EMPTY_WARNING` 降级 3-DH）/ `POST /v1/users/me/prekeys`（Bearer 鉴权后补仓 ≤50）；常量集中在 `internal/store/users.go`（10/20/50/低水位 5）。

- [2026-10-09] **Phase 1.3 好友域完成**（commit `102736c`）：`POST /v1/friends/invite`（Ticket 规范化消息 Ed25519 验签 + 过期 + nonce 一次性 SETNX + 待审批配额 ≤50 + 关系去重 409）/ `POST /v1/friends/accept`（仅被邀方可处置，false→blocked，intro 置 NULL）/ `GET /v1/friends`（intro 仅「我是被邀方 + 仍 pending」回包）。

- [2026-10-09] **Phase 1.4 协同动作完成**（commit `f3e1d98`）：`POST /v1/schedule/action` 收口——PROPOSE 建包（准入严管双向 accepted + 成员 ≤16 + 活跃包 ≤20，单事务落 bundle/skeleton/成员信封/seq:1 事件）；其余动作成员 ACL + 行锁取号（`UPDATE ... RETURNING max_seq_id`）+ 单事务落流水；事实三动作绕过 `base_revision`；`FORCE_FINALIZE` 落槌后一切 409；`Broadcaster` 接口已立（Noop，待 1.6 WSS Hub）。

- [2026-10-09] **Phase 1.5 发现与同步完成**（commit `5788cb4`）：换机冷启动链路全通——`participating`（参与中包 + max_seq_id + last_acked_seq）→ `sync`（增量补拉，成员 ACL，单页 ≤500）→ `key-envelopes`（只回本成员信封）/ `vault/envelopes`（Append-Only 同版本幂等，绝不 LWW）；瞬时信令 `relay/msg`（双向好友 ACL）→ `relay/pending` → `relay/ack`（acked + 物理 DELETE 阅后即焚）。

- [2026-10-09] **Phase 1.6 WSS Hub 完成**（commit `72164a3`）：`GET /ws` 纯路径建连（token 不进 URL）+ 首帧 `{"action":"AUTH","token":...}` 3s 掐线 + 256KB 读限 + 60s 心跳；握手后一律 protobuf 二进制帧（`relayv1.ServerFrame/ClientFrame`，复用冻结契约不另立编码）；`CLIENT_ACK` 落游标（GREATEST 单调）；`Hub` 接替 `NoopBroadcaster`，在线直推 / 离线交 `WakePusher`（Noop，1.7 实装推送）。

## 近期验证状态

- [2026-10-09] Phase 0：`gen-proto.sh` 出双端生成物 → `check-proto-sync.sh` OK → `go build ./gen/...` 通过 → `verify-phase0.sh` 建库 10 表、重复 `(bundle_id, seq_id)` 被唯一约束拦截。工具链：`~/sdk/go1.23.6`、`~/sdk/protoc`、`~/go/bin/protoc-gen-go`、`~/.pub-cache/bin/protoc-gen-dart`。
- [2026-10-09] Phase 1.1：`go build ./...` + `go vet ./...` 全绿；`go test ./...` 全绿（auth 包；httpapi 包用 miniredis + 假 IdentityResolver：401 / 重放幂等 / 缺 msg_id / 伪造签名）；`scripts/smoke-phase1.sh` 真 PG/Redis + 真 relayd 进程冒烟全通（healthz 200、无 token 401、缺 msg_id 400、challenge 200、未注册 401、重放体一致）。修一处真缺陷：`captureWriter.WriteHeader` 未调用底层 `WriteHeader`，导致首响 200 而缓存 501——已修并补测试覆盖。

## 断点

- [2026-10-11] **recovery_vault 换机切片完成（v3 拍板：金库包裹钥=登录密码派生，改密即原子重封）**：用户拍板采纳 1Password/Bitwarden 模型，废 v2「金库包裹钥独立于登录密码」红线（schedule-app §11「Plan A v3：换机恢复」拍板行留痕，含强度门槛+忘密代价取舍）。relay 仓契约 **v0.1.7-contract**（零 DDL）：change-password 加可选 `new_recovery_vault` 与新哈希**单事务同落**（`UpdatePasswordWithVault`），go build/vet/test 全绿。端侧：`recovery_vault.dart`（RecoveryVaultCodec，Argon2id 独立 salt/context，信封 KDF 参数端侧自解析，RecoveryVaultException 禁止静默半盲）/ registration 注册即封库上传 / auth_api changePassword 携新金库 / account 改密重封（**用金库解出的原身份密钥，勿用本机 MSK——会偷换身份**）+ `restoreIdentityFromVault` 换机免密恢复 + keyStore.importMsk / relay_mock 对齐。验证：新增 8 例，全量 **431/431**（EXIT=0），analyze 零 issue，docs-lint 过。**挂账**：换机恢复/解封失败 UI 入口未接（AccountPage）；密码强度门槛未加（现 ≥8）；存量联调账号不迁移重新注册。**下一步 = 真机联调全链路（注册→换机登录→免密恢复→解历史密文）+ Phase 4.2 拒绝面板+审计轴续行**。

- [2026-10-11] **用户体系契约 v2 对接完成（会话纪律：轮换/踢会话/登出/自账户/关系管理）**：用户更新对接文档补 6 端点+3 硬约束，服务端已全部实现（核对 router.go/me.go/friends.go）。端侧落地：①access 只存内存+refresh 落安全区（§5 红线），refresh 强制轮换后立即落盘新票，重用旧票 401 TOKEN_REVOKED 自动降级未登录；②改密即踢会话（TOKEN_STALE 纪元）——changePassword 成功本地转 loggedOut 回登录表单，登录锁定 429 LOGIN_THROTTLED 按 retry_after_sec 提示；③登出走服务端（双 jti 拉黑、幂等、离线不阻断）+ 自账户 GET/PATCH /v1/users/me（me()/updateMe() 透传，recovery_vault 取回通道就绪）+ 好友关系管理（removeFriend/blockFriend + friends_page 解除/拉黑/解除拉黑 UI，RELATION_EXISTS 统一口径勿区分被拉黑）；④relay_mock 重写（轮换/拉黑/锁定/关系存储）。验证：扩展 2 套测试 +11 例，全量 **412/412**（EXIT=0），analyze 零 issue，arch-guard 6/6，docs-lint 过；ui-spec §0.4 补 7 词条、schedule-app §11 加 v2 拍板行、fact-user-relay §3.2 补 v2 注记。**挂账**：撤回申请 UI 入口（需 FriendView 方向位）；换机恢复链路（recovery_vault 取回+端侧解密）挂换机切片——注册侧金库现为 MSK 封存体，届时须按「金库包裹钥独立于登录密码」红线重设计。**下一步 = 真机联调全链路 + Phase 4.2 拒绝面板+审计轴续行**。
- [2026-10-11] **用户体系 Plan A 对接完成（登录态门禁，口令解锁层退役）**：用户交付《用户体系对接文档》并拍板「去掉复杂的解锁和加密判定，改为对用户登录状态的判定」；经核对 relay 独立仓服务端已实现账号体系 Plan A（username+password 注册/登录/改密 + lookup + friends/request，客户端滞后）。端侧落地：①`auth_api` 重写（注册体 username+password、user_id 服务端发号、PoP 取消 PoW 保留、login 纯密码、change-password/lookup/搜索加好友补齐）②`AccountSession` 新建（凭据落安全区、restore 复原、过期自动 refresh、refresh 401 降级未登录、登出只清会话）③KeyStore 去口令化（删 Argon2id KEK/unlock/lock 三层链与 VaultSession/VaultLockPage，MSK 改 ensureReady 自动托管，seal/open 半盲纪律不变）④门禁全量改登录态判定（relay_session/runtime/app_services/schedule_page + 四页文案），AppServices 删匿名自动注册链。UI：AccountPage（登录/注册/改密/退出）+ settings「协同账号」入口 + friends_page 搜用户名加好友通道。验证：新增 3 套测试 18 例 + 改造 7 套，全量 `flutter test` 全绿（EXIT=0），analyze 零 issue，arch-guard 6/6，docs-lint 过；schedule-app §11 加拍板行「用户体系 Plan A：登录态门禁」、fact-user-relay-draft §3.2 加修订注记、ui-spec §0.4 入「账号域」词条组。**已知边界挂账**：换机冷启动半盲（挂 recovery_vault 切片）、换号登录不做迁移防护。**下一步 = 真机联调全链路（注册→登录→加好友→发起→接收）+ Phase 4.2 拒绝面板+审计轴续行**。
- [2026-10-10] **Phase 4.3b App 冷启动挂常驻引擎完成（被邀端密钥入户一并闭环）**：`lib/collab/enroll.dart`（`BundleKeyIntake`：fetch 我的信封→SPK 私钥 `openBundleKeyEnvelope`→`BundleKeySource.persist`，半盲纪律全 swallow 不抛）+ `lib/collab/runtime.dart`（`CollabRuntime`：App 级单例，起停前置= vault 解锁+有协同身份，缺一则不起）；`lib/app_services.dart` 加 `attachCollabLifecycle`（vault 机态监听：解锁即起、锁定即停，§5.3 退后台清零 MSK 不空转）+ `lib/main.dart` 挂接。**至此 Phase 4.3a 的三个「下一步」已收两项**（被邀端密钥入口 + 常驻引擎），系统真正常驻：WSS 实时建连 / 网络恢复自动补发 / 被邀端首条事件到达即补拉 K_bundle 落户。验证：新增 2 套单测 9 例全绿（`collab_enroll_test` 5：真实 X25519 信封往返落户+离线/MAC 错/空列表静默跳过+幂等；`collab_runtime_test` 4：锁定/无身份/重复 start/未起即 stop 全不触网不崩）；全量 **383/383**（EXIT=0），`flutter analyze` 新文件零 issue。**下一步 = 好友域 UI（§1.6 / Phase 4.6）**：扫码建联 + 安全码核对页（发起页名单依赖；否则发起页好友恒空，协同仍不可达真机）。

- [2026-10-10] **Phase 4.6 好友域 UI 完成（扫码建联 + 安全码核对）**：`lib/collab/friends_ticket.dart`（FriendshipTicket 模型 + `signFriendshipTicket` 用本地身份私钥 Ed25519 签 `shiguang.relay.v1/friendship-ticket|issuer|subject|nonce|expiresAt` 规范化消息，逐字节对齐 relay `auth.TicketMessage` + QR 编解码 + 安全码 `safetyNumberDigits` 6×5 位顺序无关 SHA-256 指纹）/ `lib/collab/relay/friends_api.dart`（`submitFriendInvite` POST /v1/friends/invite + `acceptFriendInvite` POST /v1/friends/accept；HTTP 4xx/5xx 归一 `RelayFriendsException` 带状态码供 UI 区分「已加过/名片过期/登录失效」，纯网络失败原样抛归离线）/ `relay_session` 扩 `issueTicket`·`submitInvite`·`acceptInvite`·`acceptInviteFromPeer`（invite_id 拼 `{peer}:{me}`，服务端强制被邀方=本机，发起方误点 404 安全不误判）·`peerIdentityPublicKey`·`buildFriendsSession` 装配；UI：`lib/ui/collab/{friends,my_qr,scan,safety_number}_page.dart`（好友页含待确认处置/已互加安全码入口/空态引导 + 协同日程页 AppBar「好友」钮 + 发起页零好友「去好友里加一位」深链，闭环 `acceptedFriends` 恒空）+ `qr_flutter`/`mobile_scanner`/`permission_handler` 入依赖 + AndroidManifest 加 CAMERA 权限；ui-spec §0.4 先入词再上屏。验证：新增 2 套单测 8 例全绿（`collab_friends_ticket_test` 4：签验签逐字节对齐 relay 规范化消息/篡改 nonce 验签失败/QR 往返无损/安全码顺序无关确定可复现；`collab_friends_api_test` 4：invite 正确投递+409 归一/accept 正确投递+404 归一，mock `HttpClientAdapter` 零网络）；`flutter analyze` 新文件零 issue。**断点收口：发起页好友名单不再恒空，协同可达真机**。下一步 = 真机扫码联调（`flutter run` 实机扫名片 + relay pod 起 `smoke-phase1` 全通链路）与 Phase 2.5–2.7 双机对测收尾。

- [2026-10-10] **Phase 4.3a 本地发起 bundle 完成**（首次端侧发出第一条 PROPOSE）：`lib/collab/propose.dart`（K_bundle 生成→MSK 封存落户→逐成员 `EK×SPK` 封 `member_key_envelopes[]`→payload 自证入箱）+ `PendingProposalReconciler` 认祖归宗 + `peers_api`（好友清单/成员 SPK）+ `relay_session`（身份→PoP 登录→`BundleComposeKit` 真机装配）+ `ui/collab/compose_bundle_page.dart`（发起表单，入口在成员混合日历页浮动钮）。**关键契约事实**：Go `store.Propose` 是 `INSERT … RETURNING bundle_id`——**bundle_id 由服务端发号**，`bundle_id` 空即建包分支判据；201 回执经新结局 `SendProposed` 带回，端侧做「密钥迁号 + `local_outbox_events` 改绑 + 落包头」三处幂等迁移（不改绑→后续操作恒 404）。传输层补齐 Go `actionReq` 早支持却没发的 `proposal_type`/`participant_uids`/`expires_at`/`topology`/`quorum`/`member_key_envelopes`。**排障两处真实缺陷**：①`OutboxDrainer.drain` 原开局缓存整批，认祖归宗后缓存条目仍拿临时 id（必 404）→改「每发一条重新取首条」；②`CollabDrainScheduler.dispose` 与在途 drain 竞赛→`reports.add` 打在已关闭管道→dispose 先等在途链 + `isClosed` 守卫。验证：新增 23 例（propose 11 / relay_session 7 / compose_form 5）全绿，全量 **374/374**；ui-spec §0.4 先入词再上屏，schedule-app §11 加「协同包号同源」台账行。**下一步 = 被邀端密钥入口**（`/bundles/{id}/key-envelopes`→解 K_bundle→落户，否则被邀端解不开邀约）+ **App 冷启动挂常驻 `CollabEngine`**（现仅发起页手动 drainNow 弹出）+ **好友域 UI（§1.6/4.6）**。

- [2026-10-10] **Phase 3.4 装配完成——Phase 3 端侧状态机层（3.1–3.4）验收达成**：①FreeMask（`lib/collab/freemask.dart`，§3.6 三约束：靶向区间强制入参杜绝整周探测 / 30min 量化桶半开区间 / 成员 EK×发起人 SPK X25519 单向加密，发起人端按位与出全员共振条）；②`HttpOutboxTransport`（`lib/collab/relay/action_api.dart`）：对齐 Go `action.go` 冻结契约——Bearer + `X-Client-Msg-Id`（与 OutboxEntry.id 同源）+ `cipher=base64(SealedPayload JSON utf8)`（2.7 既有约定）；2xx→Accepted / 4xx→`SendRejected(error_code)` / 5xx+401+DioException→Offline（401 归 Offline：凭证问题非操作矛盾，清箱丢操作）；③Wake Pull（`lib/collab/relay/sync_api.dart` + `lib/collab/wake_puller.dart`）：零明文载荷 `{push_type,bundle_id,latest_seq_id}` → `/sync` 分页 → 喂 `SingleConsumerPipeline.pushHttp` 与 WSS 共用去重排序；**契约关键事实：sync 回包无 key_ver**（解密方按 SealedPayload 自描述字段取版本）；`latest_seq` 以服务端**本页**事件最大值推进（对齐 sync.go）。验证：freemask 9 + action_transport 4 + wake 5 = 18 例新增全绿，全量 **271/271**（EXIT=0），analyze 新文件零 issue。**下一步 = Phase 4 UI 组装**（六页 §11.2，文案先入 ui-spec §0.4 词汇表再上屏；Phase 4 编排把 WakePuller 游标接 collab 表、Drainer 接网络监听常驻）。

- [2026-10-09] **Phase 1.7 部署 + 1.8 网关收尾完成**（commit `adab287`）：`deploy/Caddyfile`（WSS Upgrade 透传 / 256KB / 日志过滤 query 与 Authorization）、`docker-compose.prod.yml`（PG 卷 + Redis + relay + Caddy + 每小时 `pg_dump|gzip` 备份 sidecar）、`scripts/backup.sh`、`scripts/ops-cleanup.sh`（TTL + 磁盘水位）、`.env.example`；`healthz` 真探针（依赖不可达 503）；错误码字典入 README。**Phase 1 服务端最小闭环（1.1–1.8）验收达成**。
- [2026-10-09] **Phase 2.1 依赖接入完成**（首次碰拾光主仓）：`cryptography`/`flutter_secure_storage`/`web_socket_channel`/`dio`/`protobuf`(＋`fixnum`) 入 pubspec；契约生成物落 `lib/collab/proto/`（**只读**，经 `scripts/sync-relay-contract.sh` 从独立仓同步）；`test/collab_proto_test.dart` 3 例全绿 → Phase 0.3 延后的「Dart 侧 import 编译通过」闭环。
- [2026-10-09] **Phase 2.2 密钥域完成**：`lib/collab/crypto/`（secret_store / secure_secret_store / key_store / key_ring）。两层密钥包装：kek=Argon2id(主密码) 只包装 MSK，MSK 加密身份私钥与 K_bundle 信封；**改密 O(1) 只重包装 MSK、业务信封一条不动**（防改密雪崩重加密）；KeyRing 每包密钥版本链按 `key_ver` 回放取密钥。`test/collab_key_store_test.dart` 5 例全绿。
- [2026-10-09] **Phase 2.3 注册流完成**：`lib/collab/`（identity / pow / relay/auth_api / identity_store / registration）。PoP=身份私钥签 nonce 原样字节、PoW=SHA256(challenge+solution) 前导零、坐标系分离（Ed25519 签名钥 / X25519 加密钥）、OPK 首批 10~20、本地 MSK 封存身份私钥后 `POST /v1/users` 入账。**关键事实**：注册 REST 是原生 JSON（非 `RegisterRequest` proto），OPK 窗 10~20（非 100）。`test/collab_register_test.dart` 6 例全绿（含对忠实复刻 relay 校验链的 mock 端到端）。
- [2026-10-09] **Phase 2.4 加密封装单点完成**：`lib/collab/sealing.dart`（`sealPayload`/`openPayload` + 自描述 `SealedPayload`：key_ver/nonce/cipher_text/mac 的 base64 JSON）。512B 定长填充（抗长度侧信道）+ AES-256-GCM（12B nonce 不复用）+ key_ver 标注。**实现注记**：经典 PKCS#7 单字节填充量上限 255 无法表达整 512B 块，故用 2 字节大端长度尾注承载真实 pad 量（1..512），填充字节取 pad 低 8 位占位——512 粒度下 PKCS#7 稳妥落地；GCM mac 随 SealedPayload 单走（`SecretBox.cipherText` 不含 tag）。`test/collab_sealing_test.dart` 5 例全绿。
- [2026-10-09] **Phase 2.5 WSS 客户端完成**：`lib/collab/relay/wss_client.dart`（`RelayWssClient`）+ `relay/auth_api.dart` 补 `login`/`refresh`/`TokenPair`。逐条对齐 hub.go 三约束：纯路径建连 token 不进 URL / 首帧 JSON AUTH / 握手后 protobuf 二进制帧（ServerFrame 解码广播、ClientFrame 出站、`sendClientAck` 推游标）；断线指数退避重连 1s→2s→4s 封顶 30s 成功归零。**关键事实**：`web_socket_channel` 3.x 无 `IOWebSocketChannel`/`pingInterval`，心跳改应用层保活——每 30s 发被 relay 忽略的 `CLIENT_ACK(seqId:0)` 重置其 60s 读截止（relay 读循环对任意入站消息续期）；AUTH 供票走 challenge+PoW+PoP 的 login（PoP 与注册共用身份私钥）。`test/collab_wss_test.dart` 4 例全绿（VM 内 HttpServer 复刻 hub.go：握手+分发/断连自动重连/非法 token 掐线进重连/CLIENT_ACK 端到端）；全量 222/222。
- [2026-10-09] **Phase 2.6 单消费管道完成**：`lib/collab/pipeline.dart`（`SingleConsumerPipeline` + 归一化 `CollabInboundEvent.fromServerFrame`）。对齐协同设计 §11.1「单一消费网关」：WSS `pushWss` 与 HTTP `/sync` `pushHttp` 两路汇入同一条 `StreamQueue`（单订阅 StreamController，FIFO），单一消费者 `await _queue.next` 逐条取出（重入守护 `_active==1`，峰值 `peakConcurrent`）；取出后按 `(bundleId,seqId)` 去重 + 按 seq 升序 flush（缺号暂存 `SplayTreeMap` 待 `/sync` 补拉连续推进）。`attachWss` 把 `RelayWssClient.frames` 直接串入 WSS 入口。验证：`test/collab_pipeline_test.dart` 4 例全绿（两路并发注入同包去重后严格升序无缺口+峰值并发恒 1 / 不同包各自有序 / replay 只应用一次 / attachWss+fromServerFrame 贯通）；全量 226/226。
- [2026-10-09] **Phase 2.7 双机对测完成（Phase 2 退出条件达成）**：`lib/collab/crypto/session.dart`（`agreeSessionKey`：SPK↔SPK X25519 DH，本端 signedPreKey 私钥 × 对端 `/v1/users/{id}/prekeys` 广播 SPK 公钥 → 对称 32B 会话密钥，直喂 `sealPayload`/`openPayload`）+ `lib/collab/relay/relay_mock.dart`（进程内忠实 relay：challenge/login/refresh/register/prekeys/`/sync` + WSS hub 带 token 校验、CLIENT_ACK 保活忽略、信封 (bundleId,seqId) 取号落库广播、WSS/HTTP 两路同源）。**关键事实**：protobuf-Dart 陷阱——未赋值 `bytes` 字段 `..cipher.addAll(x)` 改的是 getter 返回的临时默认列表，必须 `..cipher = bytes` 走 setter；X3DH 核心取 SPK↔SPK 单 DH（坐标系铁律 X25519），完整 EK/OPK 增强留会话层。`test/collab_e2e_test.dart` 2 例全绿（A 注册→DH 协商→密封→服务端中转→B 经管道解开原文 + B→A 回程；WSS 已收事件再经 HTTP `/sync` 入管道被去重只应用一次）。全量 228 例：227 绿 + 1 条**既有** `ui_reject_reason_test.dart` 隔离性失败（单跑必挂、与 collab 无关，系跨文件测试顺序脆弱性，非本次引入）。
- [2026-10-09] **Phase 3.1 db v5 幂等迁移完成（连带 fact 反转补链销账）**：`lib/data/db.dart` `version: 5` + `migrateV5`/`createAll` 公开静态（onCreate 与升级共用同一 DDL 源=新装同构）。反转关联五步全幂等顺序不可换：①blocks ADD `artifact_id`（1:N 软引用）②artifacts ADD `bundle_id`（弱引用）③**存量绑定搬家**（block_id→块侧回填，悬空静默丢）④payload 清洗（移除 `block_id` 键，畸形不动）⑤DROP COLUMN（≥3.35 原生，老内核 12 步重建兜底）。`collab_*` 六表：`collab_bundles`/`collab_steps`（PK bundle+step，threshold/votes）/`collab_events`（UNIQUE(bundle_id,seq_id)）/`collab_key_ring`（PK bundle+key_ver 同版本幂等绝不 LWW）/`local_outbox_events`（设计稿 §11.1 定盘表名）/`collab_block_refs`（PK block_id）。`source` 扩 `'collab'` 零 DDL（无 CHECK）。**连带反转补链（fact §9.5-6/8，状态表自记「未落地」→已销账）**：`Artifact` 弃 blockId 字段+payload 投影；`addArtifact(bindBlockId:)` 插行+块回填同事务原子；`artifactForBlock` 块侧 JOIN（悬空静默降级）；编辑回填/voided/物理删除一律块侧置空+解 pinned（1:N 全量）；`delete_plan` detach 双 NULL（未归属池口径=双 NULL，§5.5）；MCP `upsert_facts.block_id` 入参保留写块侧。验证：`test/collab_db_migration_test.dart` 4 例全绿（忠实 v4 复刻播种→升级绑定搬家/清洗/出列/反转 JOIN/悬空静默；新装同构列集逐一相同+`'collab'` 可写+六表在位；幂等重跑零副作用；六表约束拦截）。全量 232 例：231 绿 + 1 条既有 UI 隔离性失败（非本次引入）。
- [2026-10-09] **Phase 3.2 FSM 回放器完成**：`lib/collab/fsm.dart` 纯函数 Reducer（`reduceEvent`/`replay`/`maybeAbandon`/`maybeExpire`）。**关键事实**：①步骤子状态在 `CollabStepState.copyWith` 收口自动派生（表决只改 ballots，若不收口 status 停在 pending、Bundle 聚合全错——首轮 13/15 挂的根因）；②REOPEN 须**显式落 STALLED**（步骤回 PENDING 无 clash，纯派生得 PROPOSED，违反 §4.3 表格）；③冻结 proto 无 PENDING_RECHECK 枚举，回音壁成员再确认=重发 ACCEPT 覆盖表决；④BYPASS 门限须收缩 min(M, 剩余人数)（t=N 全员门限移 1 人后否则永不可达）；⑤Consensus 投票人必须在 participants（PROPOSE 的 sender+uids）；⑥`CollabBundleState.empty` 携 proposalType 分发策略，否则 Consensus PROPOSE 走错分支。时钟全由事件 `serverAtMs` 注入（§5.2 法定时钟），reducer 零端钟。`test/collab_fsm_test.dart` 15 例全绿（两条 5 事件注入主线 + 转移表逐格 + Consensus 四例）；全量 246 绿 + 1 条既有 UI 隔离性失败（231+15 严丝合缝）。

## 近期验证状态

- [2026-10-09] Phase 2.3 验证：`flutter analyze`（新文件）零 issue；`flutter test` 全绿（2.1 契约 3 + 2.2 密钥域 5 + 2.3 注册流 6 + 既有 199 = 213）。注册端点无 podman 真服务，改用复刻 `register.go` 校验链（nonce 一次性消费 + PoW + PoP + OPK 数量窗）的本地 mock 服务做端到端验证；对真 relay 联调待 2.5/2.6 起用 `RELAY_BASE_URL` 接入。

- [2026-10-09] **Phase 3.3 本地 Outbox 完成**：`lib/collab/outbox.dart` 三件套——`OutboxRepository`（入队即落库 PENDING_SEND、`pending()` 按 `(created_at,id)` 全序、确认即物理删除、`markAttempt`；时钟+幂等键**双注入**，id=client_msg_id 同源，重试靠 1.1 幂等中间件安全）+ `OutboxTransport` 抽象（真实接 `/v1/schedule/action` 留 3.4 装配；测试注入脚本化假传输）+ `OutboxDrainer.drain()`。**两种失败严加区分**：Offline 非终态（保留+attempts+1+**保序立即停止**——§5.1 到达序取号，乱序必 409）；Rejected 终态（清条+`conflicts` 上抛引导 UI，**不阻塞后续**）。传输层裸异常归一化 Offline，绝不炸弹出循环。验证：`test/collab_outbox_test.dart` 6 例全绿（入队即落盘+全序 / **杀进程不丢** file DB close→reopen / drain 全成功 / 弱网保序停止+恢复补发 / 409 清箱引导 / 裸异常归一化）。全量 **253/253 全绿**（EXIT=0）；上轮 flake 的 `ui_reject_reason_test` 本序通过，确证为跨文件顺序依赖而非真实故障。

## 近期验证状态

- [断点] **Phase 0 冻结 + Phase 1.1–1.8 + Phase 2.1–2.7 + Phase 3.1–3.4 全部完成——Phase 3 端侧状态机层验收达成（db v5 迁移 + FSM 回放器 15 例 + 本地 Outbox 6 例 + 3.4 装配 18 例，全量 271/271）**。3.4 已落：FreeMask（freemask.dart，§3.6 三约束）/ `HttpOutboxTransport`（action_api.dart，Bearer + X-Client-Msg-Id + cipher=base64(SealedPayload JSON)，4xx→Rejected / 5xx·401→Offline）/ Wake Pull（sync_api.dart + wake_puller.dart，零明文载荷→/sync 分页→喂 SingleConsumerPipeline 与 WSS 共用去重；**sync 回包无 key_ver**，`latest_seq` 以本页最大值推进）。下一步 = **4.2 拒绝面板+审计轴**（§11.2 C/D 页：双维拒绝面板 + 审计时间轴；文案先入 ui-spec §0.4 词汇表再上屏）。**4.1 成员混合日历已落地**（三态预占 HARD_TENTATIVE/SOFT_CANDIDATE/CONFIRMED + 一键响应浮条 同意/拒绝并说明 + 日程 Tab 顶部入口；纯渲染层 `CollabBundleListView`/`ResponseSheet` 抽为公开可单测组件，8 例全绿；全量 300/300）。**编排收口已达成**（三件套：①WakePuller 游标接 collab 表——`CollabBundleRepository.maxSeqId` 读 `collab_bundles.max_seq_id`，引擎订阅 `pipeline.applied`→落库推进水位，进程被杀后从本地水位精准续拉；②Drainer 接网络监听常驻——`CollabDrainScheduler` 串行链挂 `ConnectivityWatcher`（断→通自动重 drain，多源触发不并发双发）；③真 relay 联调 `RELAY_BASE_URL`——`RelayConfig` 单一来源，`wireCollabSystem` 焊接 WSS/action/sync 三路传输）。新增 4 套单测 19 例全绿、全量 290/290，db v6 迁移补 collab_events.server_at_ms。

- [2026-10-10] **Phase 4 编排收口完成**（六页 §11.2 前置的三件套，先于 UI 落地使系统可常驻运行）：`lib/collab/relay_config.dart`（`RelayConfig` 单一来源，`RELAY_BASE_URL` 经 dart-define 注入，默认 `http://10.0.2.2:8080`，`wssUri` 由 http→ws 追加纯路径 `/ws`）/ `lib/data/collab_repository.dart`（`CollabBundleRepository`：maxSeqId·ensureBundle·recordEvent·loadEvents，sinceSeqOf 真实落库水位 + 原始事件保真落库供 FSM 重放，UNIQUE(bundle_id,seq_id) 幂等）/ `lib/collab/connectivity.dart`（`ConnectivityWatcher` 抽象 + `PollingConnectivityWatcher` 零依赖 + `CollabDrainScheduler` 串行链把 `OutboxDrainer` 挂到断→通）/ `lib/collab/engine.dart`（`CollabEngine` 焊接 pipeline.applied→落库 + attachWss + WakePuller(sinceSeqOf) + resident drainer；`wireCollabSystem` 以 `RelayConfig` 焊接三路传输）。`pipeline.dart` 补 `serverAtMs`（法定时钟 §5.2，wake_puller 注入 sync serverAtSec）；`db.dart` 升 v6 迁移补 `collab_events.server_at_ms`（新装同构即认）。

- [2026-10-10] [验证]: 4 套单测 19 例全绿——`collab_relay_config_test` 4（默认兜底 / 显式 / wssUri 推导 http→ws·https→wss）/ `collab_bundle_repo_test` 7（maxSeqId 缺省·ensureBundle IGNORE·水位 GREATEST·重复 UNIQUE 忽略·先落事件后建包头·loadEvents 升序）/ `collab_drain_scheduler_test` 4（常离线不 drain·断→通自动 drain·trigger reports·连续跃迁各一轮）/ `collab_engine_test` 4（收口① applied→落库推进水位·收口①续 本地水位续拉不重拖·收口② drainNow 清空·收口③ wireCollabSystem 焊接·onWake 驱动）。**全量 `flutter test` 290/290 全绿（EXIT=0）**；`flutter analyze` 新文件零 issue。

- [2026-10-10] [排障]: ①引擎 `dispose` 关库早于管道在途落库→`database_closed`（`pullBundle` 推入的增量事件由管道异步 apply，测试结束关库时仍在途）——给 `CollabEngine` 加 `_inflight` 集合，`dispose` 先 `Future.wait(_inflight)` 再关库。②`CollabDrainScheduler` 注释声称「多源触发共享同一把锁」实则 `trigger()` 无锁，并发触发会 TOCTOU 双发同一条（违背 §11.1 离线保序铁律）——落地 `_chain` 串行 Future 链，所有触发排队串行，掉队触发不丢（下次连通/心跳补）。两处均为真实缺陷修复，非绕过。
---

# main 侧增量：Binder 拆分化 epic 记忆（2026-10-17，合并带入；status: active）

- [pairs_with] goodhub-local-plugin（Binder 嵌入对接域，见 context/epics/goodhub-local-plugin/memory.md）
- [断点·main] R2 CDN 串版已修 + 28021 已按版本化 key 重推（2026-10-15，客户端全链路核验过）；下一步：真机「检查更新」装 28021（sha 校验应过）→ 验（整卡长按唤调试菜单+adb reverse 回环+宿主 bind）或轴 B 第二段 `shiguang_mcp` 纯 Dart 包（待宿主驱动）

# ⚡ 给「拾光」AI 代理的整体改造提示词（让它自己完成拆分化）

> 本文件是 shiguang 当前 epic `online` 的开工指令。读到即执行下述方案；每完成一步在末尾断点区更新进度。任何偏离须先回到本方案的「原则」与「禁止项」。

> **绑定域（硬约束优先）**：本 epic 的 Binder 嵌入改造已绑定到 shiguang 侧 `goodhub-local-plugin` 域（`context/epics/goodhub-local-plugin/memory.md`）——该域是 goodshare 同名范本的对接锚点，含现状评估与 **G1–G7 修正留痕**。执行本方案时，以下与域对齐的硬约束**优先于本文件下文措辞**：(1) `HttpTransport` 落点钉死 `lib/service`（不进 `lib/mcp`）；(2) Step 1 必须同步迁移 `test/mcp_server_test.dart`（否则 test 全过不可达）；(3) 轴 B 解耦**三处**平台耦合（含 sqflite 存储抽象）；(4) 验收 = 响应体逐字节等价 + HTTP 状态码漂移逐条登记。

---

## 0. 角色与目标

你是「拾光」（shiguang）这个 Flutter/Dart app 的改造代理。目标：**把 shiguang 的 MCP 能力拆分化，使其既能在本 app 内以 HTTP 运行，又能作为 Binder 插件被宿主（GoodHub 式 app）进程内嵌入，并复用 relay 走内网/公网可达**——而这一切以「零返工、行为零变化、复用已验证骨架」为前提。

这不是重写，是**结构性拆分**：把已建好的无头化内核从传输与平台外壳中分离出来，让内核可独立被嵌入。

## 1. 现状盘点（基于本仓库真实代码，勿假设）

已具备（直接复用，**不要重写**）：
- 命令模式：`lib/action/command_handler.dart`（`CommandHandler.execute(ScheduleCommand, actor: CommandActor.ai)`）、`commands.dart`、`queries.dart`、`rules.dart`——与 UI 组装同源的 `ScheduleCommand.fromJson` 单一入口。
- 数据仓库：`lib/data/repository.dart`——FIFO 串行锁 `synchronized`、乐观锁 `version`、patch 白名单 `_whitelist`（防注入）、`transaction` 批原子写。业务校验在命令层，仓库只保台账。
- MCP 工具面：`lib/mcp/tools.dart`（`toolSchemas()` + `callTool(name, args, repo)`）——只做 JSON↔命令 的翻译，不做业务判断（防呆全在命令层）。
- 协议常量：`lib/mcp/jsonrpc.dart`（`McpProtocol`、`McpRpcError`、错误码）。
- HTTP 总控：`lib/service/mcp_controller.dart`（`ChangeNotifier`，装配 token/mDNS/前台服务 + 起 `McpServer`）。
- 本地桥：`mcp-bridge/stdio-bridge.mjs`（stdio↔HTTP :8765，token 鉴权）；relay 服务：`shiguang-relay/`（Go）。

**唯一的结构性缺口（本次改造的命门）**：
- `lib/mcp/mcp_server.dart` 的 `McpServer` **内嵌 `dart:io` 的 `HttpServer`**，并把 HTTP 专属逻辑 inline 在 `_onRequest` 里（origin/DNS-rebinding 校验、`x-api-key` 校验、`/mcp` 路径与 `POST` 方法检查、202 通知处理、`_json`/`_safeJson` 写回）。
- 它没有 goodshare 那种 **`String handleJsonRpc(String)` 传输无关入口**。这导致：现在想把它嵌成 Binder 插件，必须复制整套 JSON-RPC 分发逻辑——返工由此而生。

## 2. 已确认方向（钉死，不做二次讨论）

1. **Binder 插件嵌入**：shiguang 的 MCP 层终态是「可作为 Binder 插件被宿主进程内嵌入」。这**不是外挂，而是本次拆分的內禀终点**——`shiguang_mcp` 内核包就是那个被嵌入的单元。
2. **复用 relay**（公网）：relay 只做 TCP/JSON 中转，不碰业务；内网 bonsoir 发现已退役（2026-10-15 [SSOT 修正] 旧「内网 bonsoir 发现 + 公网 relay 中转」➔ 新「仅公网 relay + 暴露面收口为 Binder 宿主 + 回环调试」，见 schedule-app §11「MCP 暴露面」行）。
3. 参考样板（**只读，不跨项目改**）：goodshare 的 `lib/mcp/mcp_server.dart` 中 `handleJsonRpc(String)` 是已验证的传输无关范本；goodshare `context/epics/goodhub-local-plugin/memory.md` 记录了 Binder 嵌入「核心零改动，只加 Binder 传输层 + 同签名权限协调，无缺口」。

## 3. 原则（来自 goodshare 的血泪，务必遵守）

- **复用骨架与纪律，重写领域血肉**：协议逻辑、`command`、`repository` 校验一律不重写；只动「接缝」与「外壳」。
- **先立接缝、后加传输（M1 加法先行）**：先做最小接缝（抽 `handleJsonRpc`），HTTP 行为字面上零变化跑通；真要有宿主时再建 `BinderTransport`，**绝不提前建空壳**。
- **任何改造先加后减**：先做适配层跑通闭环 → 再摘旧依赖，不一次性大改接线。
- **护栏前移为交付线**：每个改动须过 `flutter analyze`（0 issue）+ `flutter test`（全过），否则不算完成。

## 4. 四轴改造方案（按杠杆从高到低）

### 轴 A — 传输无关内核（最高杠杆，先做，见第 5 节 Step 1）
把 `McpServer` 从 `HttpServer` 中解放，暴露 `Future<String> handleJsonRpc(String rawBody)`。HTTP 专属逻辑移到 `HttpTransport`。这是 Binder 嵌入的前提，也是返工的唯一来源点。

### 轴 B — 内核包边界（`shiguang_mcp` 可嵌入单元）
待轴 A 稳定后，把 `lib/mcp/` + `lib/action/` + `lib/data/` 收为纯 Dart 内核包，供宿主依赖。
- **唯一需解耦点**：`lib/data/repository.dart` 现 `extends ChangeNotifier`（Flutter 依赖）。把 `notifyListeners`/`revision` 信号机制抽成可选回调或抽象接口（如 `void Function()? onChange`），让 `Repository` 在纯 Dart 下可编译。命令层/仓库业务逻辑不动。
- 终态：宿主 `import 'package:shiguang_mcp/...'` 即可拿 `handleJsonRpc` + `tools`，只需注入自己的 `Repository`（DB 实现由宿主提供）。

### 轴 C — relay 复用（内网/公网可达）
复用 `shiguang-relay`（Go）。relay 仅做中转，无需改造内核；配置层（`RelayClient`）挂在 `McpController` 之后，与 `HttpTransport` 并列作为另一种传输（2026-10-15 暴露面收口：默认关、显式配置才挂载，bonsoir 发现引用退役）。

### 轴 D — 平台能力接口化
前台保活（`flutter_foreground_task`）、自更新（`open_filex`/`package_info_plus`）、网络广播（`bonsoir`，2026-10-15 随暴露面收口退役）已是抽象。确认这些**只出现在 `lib/service` 与 `lib/update`，不渗入 `lib/mcp`、`lib/action`、`lib/data`**。如有越界，在轴 A/B 时一并收敛。

## 5. Step 1 详细配方（立即执行，风险极低，行为零变化）

在 `lib/mcp/mcp_server.dart` 中：

**(a) 新增传输无关入口**（搬运现有 `_dispatch` 与其周围 try/catch，去掉所有 `HttpRequest` 依赖）：
```dart
/// 传输无关：输入 JSON-RPC 请求体，输出响应体字符串。
/// 通知（id==null）返回空串，由传输层决定回 202。
Future<String> handleJsonRpc(String rawBody) async {
  Object? msg;
  try {
    msg = jsonDecode(rawBody);
  } catch (_) {
    return jsonEncode(_rpcError(null, errParse, 'JSON 解析失败'));
  }
  if (msg is! Map<String, Object?>) {
    return jsonEncode(_rpcError(null, errInvalidRequest, '仅支持单条 JSON-RPC 消息'));
  }
  final method = msg['method'];
  final id = msg['id'];
  if (id == null) return ''; // 通知：无需应答
  if (method is! String) {
    return jsonEncode(_rpcError(id, errInvalidRequest, '缺少 method'));
  }
  try {
    final result = await _dispatch(method, msg['params']);
    return jsonEncode({'jsonrpc': '2.0', 'id': id, 'result': result});
  } on McpRpcError catch (e) {
    return jsonEncode({'jsonrpc': '2.0', 'id': id, 'error': e.toMap()});
  } catch (e) {
    return jsonEncode(_rpcError(id, errInternal, '工具执行失败: $e'));
  }
}
```
> `_dispatch`、`_rpcError`、`McpRpcError`、错误码常量均保留原位，不需改。`tokenProvider`/`instructionsProvider`/`onSuggestion` 仍由 `McpServer` 持有（它们是服务策略，非传输）。

**(b) 抽出 HTTP 专属到 `lib/mcp/transport/http_transport.dart`**（或留在 `lib/service`）：把 origin/DNS-rebinding 校验、`x-api-key` 校验、`/mcp` 路径与 `POST` 检查、`utf8` 解码、202 通知处理、`_json`/`_safeJson` 写回全部搬到这里。它持有 `McpServer` 实例，对每请求：`serve(req)` → 校验 → `final resp = await server.handleJsonRpc(body)` → 若 `resp.isEmpty` 回 202，否则 `Content-Type: json` 写回。

**(c) `McpServer` 瘦身**：删掉 `dart:io` 的 `HttpServer`、`start()`/`stop()`、`_onRequest`、`_json`、`_safeJson`、`_isAllowedOrigin`；保留 `handleJsonRpc`、`_dispatch`、构造参数、`builtinInstructions`。`McpServer` 变为**纯 Dart、无 Flutter/无 dart:io 依赖**。

**(d) `McpController` 调整**（`lib/service/mcp_controller.dart`）：它仍 `extends ChangeNotifier`，仍负责 token/mDNS/前台服务；但不再 `new McpServer().start()`，改为：`new McpServer(repo:...)` 后，自己 `HttpServer.bind` 并把请求交给 `HttpTransport`（或把 `HttpServer` 起在 `HttpTransport` 内，`McpController` 调用 `HttpTransport.serve(server)`）。

**(e) 验收（行为零变化）**：
- `flutter analyze` 0 issue；`flutter test` 全过。
- 端到端：`node mcp-bridge/stdio-bridge.mjs --token <app 内 token>` 经 `adb reverse tcp:8765 tcp:8765` 连 `:8765`，`tools/list`、`add_plan`、`get_schedule`、`propose_schedule` 等输出须与改造前**逐字节等价**（用 `golden-samples.md` 或既有 e2e 比对）。
- 通知（如 `notifications/initialized`）仍正确回 202、不写响应体。

## 6. 后续轴（轴 A 验收后再排期，勿提前动工）

- **轴 A→Binder 终点**：仅当真有宿主要嵌入时，新增 `lib/mcp/transport/binder_transport.dart`（纯 Dart）+ Android 原生 `McpBinderService`（`onEventEmitter` → `handleJsonRpc`），权限协调走**同签名**（参照 goodshare `goodhub-local-plugin` 拍板「无缺口」）。无真实宿主前**禁止建空壳**。
- **轴 B**：抽 `Repository` 的 Flutter 信号依赖为可选回调，建 `shiguang_mcp` 包；宿主侧提供 `Repository` 适配。
- **轴 C**：接 `shiguang-relay` 作为 `McpController` 之后的另一种传输。
- **轴 D**：巡检并收敛平台依赖越界。

## 7. 禁止项

- 禁止重写 `McpServer` 的协议/分发逻辑、`tools.dart`、`command_handler`、`repository` 校验。
- 禁止把 `dart:io`/`HttpServer`/Flutter 依赖留在拆分后的 `McpServer`（内核须纯 Dart）。
- 禁止在轴 A 完成前建 `BinderTransport` 空壳。
- 禁止一次性大改接线；任何改造先加适配层跑通闭环再摘旧依赖。
- 禁止修改 goodshare 仓库（参考仅只读）。

## 8. 参考（只读）

- goodshare `lib/mcp/mcp_server.dart` 的 `handleJsonRpc(String)`（传输无关范本）。
- goodshare `context/epics/goodhub-local-plugin/memory.md`（Binder 嵌入：核心零改动 + Binder 传输 + 同签名权限协调）。
- 本仓库 `docs/design/schedule-app.md`、`functional-spec.md`、`ui-spec.md`（既有设计 SSOT）。

---

## 断点记录（每步完成后更新此处）

- [x] **轴 A→Binder 终点（goodhub SDK 接入）**：2026-10-14 实装，analyze 0 / 202 测试全过（+4）。
  goodhub 宿主已建成，按 G7 先实开核对 goodhub `android-sdk/plugin-core` AIDL 签名、
  discovery（intent action `com.zzh.goodhub.action.MCP_SERVER` + SERVER_ID/TITLE meta-data）、
  信任模型（同签名零弹窗）与 goodshare 范本 B1/B3 修复清单后再动手。走 goodshare 同款
  Flutter 路线（工具面在 Dart，非原生 @McpTool 路线）：
  - AIDL：`IMcpChannel.aidl`/`IMcpCallback.aidl` 原文拷入
    `android/app/src/main/aidl/com/zzh/goodhub/mcp/` + `buildFeatures { aidl = true }`。
  - 原生：`ShiguangMcpBinderService.kt`（照抄 goodshare McpBinderService，通道
    `shiguang/mcp_binder`、entrypoint `mcpBinderMain`；B1 per-call ≤5s 超时、B3 id 回显、
    ready 竞态 5s、FlutterInjector loader 坑全落）。差异：shiguang 工具面无 >5s 长任务，
    MVP 不走 `_async`+onEvent 终帧切片（answerReverse 兜底静默，契约位保留）。
  - Manifest：`<permission com.zzh.goodhub.permission.MCP_SERVER signature>`（声明方=本 app）
    + exported service（signature 护门 + action + SERVER_ID=shiguang/TITLE=拾光）。
  - Dart：`lib/mcp_binder_entrypoint.dart`（@pragma vm:entry-point）——**不经
    AppServices.init**（那会装配 McpController 起 HTTP/前台服务/远程配置，Binder isolate
    只要台账内核）：显式 `Db.registerDefaultStorageFactory()`（轴 B 纪律：不能靠模块加载
    副作用）+ `Repository()` + `McpServer(tokenProvider: () => null, weather: WeatherService())`。
  - R1 缓解：`db.dart` onOpen 加 `PRAGMA busy_timeout = 5000`（主/binder 双连接共写 WAL）。
  - 测试：`test/binder_entrypoint_test.dart`（binder 等价体=entrypoint 同构装配）——
    零鉴权 initialize/tools/list/add_plan 落库/坏 JSON 与未知方法 B3 id 回显。
  - **遗留**：真机联调未跑——两 app 须同机同批 debug 签名出包（same_sign_coord 方案 1），
    D.1 keytool 指纹比对 + D.2 bind 探针过门禁后才算 e2e 闭环。
- [x] **Step 1**：抽 `handleJsonRpc`，HTTP 行为零变化验收通过（analyze 0 / test 全过 / e2e 等价）
- [x] **轴 A→Binder 终点真机联调**：2026-10-14 完成，D.1/D.2 门禁全过，e2e 闭环。
  两 app 同签名（~/.android/debug.keystore）同批 debug 出包装机（OPPO CPH2767）；
  discovery 解析到 `app.shiguang.shiguang/.ShiguangMcpBinderService`；goodhub 调试页
  bind + add_plan 经 Binder 全链路落库核验（plans 表 1 行）。途中修两 bug：
  ① `main.dart` 漏 import `mcp_binder_entrypoint.dart` → 库被 AOT tree-shake，
  `Dart_LookupLibrary not found`（goodshare 范本有此步，补 import + ignore 注释）；
  ② `db.dart` `PRAGMA busy_timeout` 用 `db.execute` 报
  "Queries can be performed using SQLiteDatabase query or rawQuery methods only"——
  带返回值的 PRAGMA 须走 `rawQuery`。修复后 analyze 0 / 202 测试全过。
- [x] **Step 2（轴 B 第一段·就地纯 Dart 化）**：三处平台依赖解耦完成（2026-10-14），
  全仓 analyze 0 issue + 194 测试全过：
  - ① `Repository`：`extends ChangeNotifier` → `implements Listenable`（自实现
    listener 列表 + revision 自增；15 处 `ListenableBuilder` 消费面零改动）。
    注意：因 UI 全量依赖 `Listenable`，`flutter/foundation` 仅余 `Listenable` 类型依赖。
  - ② 存储注入：新建 `lib/data/storage.dart`（`ScheduleStorage`/`StorageTxn` 纯 Dart
    接口，含 query/insert/delete/rawUpdate/update 子集——命令层事务需要 read-your-writes）；
    `SqfliteStorage` 适配器留在 `db.dart`（平台依赖收口）；`Repository._database()` →
    惰性 `_store`。工厂注册**不能**用顶层 IIFE（Dart 顶层 final 惰性初始化不会执行！），
    改为 `Db.instance()` 幂等注册 + `AppServices.init()` 显式先注册。
  - ③ 天气注入：新建 `lib/mcp/weather_provider.dart`（`WeatherProvider` + NullWeatherProvider），
    `tools.dart` 去 `service/weather.dart` 依赖；`McpServer` 增 `weather` 字段，
    `McpController` 注入真实 `weatherService`；测试直接调 `callTool` 的用默认空实现。
  - 消费面改动：0（构造签名全兼容）。遗留决策：是否抽 `shiguang_mcp` 包待真实宿主驱动。
- [ ] 轴 B 第二段：`shiguang_mcp` 纯 Dart 包（待宿主）
- [ ] 轴 C：relay 传输接入
- [x] **Step 3（轴 D·平台依赖越界收敛）**：2026-10-14 完成，analyze 0 / 194 测试全过。
  - models 纯化：`artifact.dart`/`background.dart` 的 `debugPrint` → `dart:developer log`
    （`[DEGRADE]` 标记保留，无测试捕获 debugPrint 输出，已核实）。
  - `util/lan_ip.dart`（dart:io）归位 `lib/service/lan_ip.dart`（唯一消费者 mcp_controller）。
  - 保留项（已记录决策）：`repository.dart` 的 `Listenable` 类型依赖（轴 B ①；
    15 处 UI ListenableBuilder 消费）；`db.dart` 的 sqflite/path（合法收口点）。
  - 收敛后内核依赖面：lib/mcp+action+models+util 纯 Dart；lib/data 仅 Listenable 类型。
- [x] **Step 4（轴 C·MCP over relay 最小闭环）**：2026-10-14 完成。拍板：复用 RelayMessage
  扩展（v0.1.8 加性 header map）、好友 ACL + x-api-key 双保险、全链路最小闭环。
  - 契约：`RelayMessage.header`（role/corr_id/token；cipher=JSON-RPC 文本），
    **MVP 应用层明文依赖 TLS+ACL，E2EE 后续切片**（勿在此通道传非 MCP 敏感数据）；
    gen/dart 按本机 protoc_plugin 25.1.0 重生成（旧产物配不上 protobuf 4.x-6.x 任意运行时，
    最终定 `protobuf ^6.1.0`）。
  - relay 侧：`Hub.PushRelay` 定向直推 + `handleRelaySend` 落库后直推（所有 kind 通用，
    响应加 `delivered_online`）；**收方 WSS 收到后 REST ack 消费防 pending 重放**（语义已在
    Hub 注释钉死）。零 DDL。
  - app 侧：`lib/service/relay_client.dart`（WSS+AUTH→收单→handleJsonRpc→REST 回发+ack；
    connect/rest 可注入已单测）；`McpController` enable 时 fail-open 挂载（配置在
    mcp_settings_page relay 区，RelayKeys 内部键）；契约产物随仓副本 `lib/relay_proto/`
    （proto 变更后须从 relay 仓重拷）。
  - 桌面侧：`mcp-bridge/relay-bridge.mjs`（零依赖；应答匹配按 JSON-RPC id 回显——
    REST pending 不回 header，零 DDL 口径；`--peer` 缺省打印本端 user_id 供建好友）。
  - 验收：relay build/vet/test 绿（PG 集成测试按 RELAY_TEST_PG_DSN 约定跳过——含新增
    在线直推/离线留 pending 两个用例）；app analyze 0 issue + 198 测全过（+4 relay）。
    **遗留**：真机/真 relay e2e 联调未跑（本环境 docker 不可用）；顺手修复 sim_activity_test.go
    五处存量编译错误（该文件从未编译通过）。
- [x] **MCP 暴露面收口（2026-10-15）**：用户拍板「MCP 服务隐藏、不对外开放、只内部调试，
  机制全部走 bind（Binder 宿主通道）」+ relay 处置裁决（代码保留、随隐藏入口收起、默认关）：
  - HTTP：`HttpServer.bind(InternetAddress.loopbackIPv4)` 仅回环（原 anyIPv4 全网卡）；
    桌面侧经 `adb reverse tcp:8765 tcp:8765` 接入；`endpoint()` 改回环常量展示。
  - mDNS：bonsoir 依赖摘除（pubspec）、mcp_controller 广播块与 lan_ip.dart 删除；
    schedule-app §11「网络配对」行标废弃。
  - UI：设置页「MCP 服务」入口行移除→隐藏入口（页尾「关于」区长按标题唤出「调试菜单」
    →「MCP 调试服务」带运行态，照 goodshare 范本，用户指令改形态）；今日页
    「桌面 AI 未连接」配对卡摘除；MCP 子页地址改回环展示+提示改调试口径；relay 区保留。
  - 保留项：token 鉴权（Android 同设备回环不隔离）、前台服务保活（Binder 与调试均需
    进程存活，通知文案改调试口径）。
  - 验收：flutter analyze 0 issue / flutter test 202/202 / arch-guard 6/6 / docs-lint 过
    （ui_settings_token_test 改走隐藏入口手势）；m1 bonsoir 升级挂账作废归档 done.md；
    workflow/index skill + README/mcp-bridge README + ui-spec §0.4 两行 + schedule-app
    §11「MCP 暴露面」新行同步。
  - **遗留**：真机验证未跑（装包+隐藏入口手势+adb reverse 回环可达+宿主仍可 bind 核验）。
- [x] **R2 发布 v1.2.1 (28020)（2026-10-15）**：MCP 暴露面收口内容发布（基线 68e201a + 版本抬升 8fd5568 + 清单 e9f56d6）；
  `release-r2.sh --commit` 全流程过（analyze 0 → arm64 release 21.0MB → sha256 e63de6e8… → R2 公开域）。
  远端核验：公开清单 GET 过（五字段齐）+ APK HEAD 200（20,964,788 字节与构建产物一致）。
  **遗留**：真机自更新流程未验（app「检查更新」→ 装 28020 → 隐藏入口/回环/宿主 bind 一并核）。
- [x] **R2 补发 v1.2.1 (28021)（2026-10-15）**：设置页双「设置」标题修复（摘 SettingsPage 自有 AppBar，
  「去设置」改跳 Tab）+ 「关于」长按触发区扩到整卡（真机长按未弹菜单→小目标手势被列表滚动抢占）。
  基线 972443a + 版本 6560928 + 清单 c9fc45e；远端清单 GET + APK HEAD 核验过。
  **遗留**：真机验 28021（整卡长按唤「调试菜单」→ MCP 调试页；连同回环/宿主 bind 一并核）。
- [x] **R2 CDN 串版修复 + 28021 重推（2026-10-15）**：用户报 sha256 校验失败 → 双取证实锤公开域 CDN
  对固定 key 返回 28020 旧字节（源站正确）。发布脚本改 APK 版本化不可变 key（app-<build>.apk）+
  清单 no-cache（commit e29769c）；28021 重推（内容不变不抬版本号），端到端核验过。
  **教训**：公开域 R2 无 CDN purge 权限（无 zone token），版本化 key + no-cache 为常态对策；
  旧固定 key app-release.apk 残留 28020 对象，无客户端再访问，留待后续清理。
  **遗留**：真机「检查更新」装 28021（sha 应过）+ 整卡长按/回环/宿主 bind 一并核。
- [x] **MCP 隐藏入口改「关于」长按调试菜单（2026-10-15）**：settings_page 撤五连点手势，页尾「关于」区整卡长按唤调试菜单（首版标题目标过小被滚动抢手势，扩到整卡）；双「设置」标题修复（SettingsPage 摘自有 AppBar，外壳 Tab 标题唯一；「去设置」改跳 Tab）。五处留痕（ui-spec §0.4/§11/memory/workflow skill/pending-confirm）。**遗留**：真机长按路径核验随 28021。
- [x] **F1 Binder `_async` 终帧切片（2026-10-17）**：goodhub 并行调用汇入单 Dart isolate 串行竞争，同步 call 的 B1 5s per-call 超时下后到调用必超时（呈现为中断）——「工具面无 >5s 长任务」旧假设作废，§11 拍板留痕。修法：`ShiguangMcpBinderService.call()` 对 tools/call（回调已注册）立回 `_async` 占位帧，dispatch 结果/异常由原生 Result 回调经 `IMcpCallback.onEvent` 转发同 id 终帧（全异常路径兜底防宿主悬挂，推失败 `[DEGRADE]` 日志）；goodhub 侧 `BinderTransport` 终帧 idle 超时兜底 120s（可注入，send completer + sendStream 看门狗）+ 防悬挂回归单测。F2（Isolate.run 包 CPU 重段）按准入评估降级暂缓。验证：analyze/arch-guard/APK 28023 编译绿，goodhub 全量 774 测试含新单测过；flutter test 90/91（ui_reject_reason 存量失败与本轮无关）。**遗留**：真机 goodhub 并行场景 e2e 验证无中断 + goodhub 侧改动单独 commit。

## 断点
- [断点] 下一步：并发断环取证中：带 [F1] 日志的 debug APK 已就绪（21:56, sha 624b2af4…）→ 待用户装包复现三并发 + 贴 logcat（grep ShiguangMcpBinder）；判读表见本轮对话，断环定位后修两仓
