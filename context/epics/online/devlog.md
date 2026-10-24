# online epic devlog

## 2026-10-11 · recovery_vault 换机切片：金库包裹钥=登录密码派生（v3 拍板）

用户拍板采纳 1Password/Bitwarden 模型（废 v2「金库包裹钥独立于登录密码」红线）：密码派生 `K_recovery`（Argon2id，独立 salt+context），改密即原子重封，换机免密无感恢复；忘密=身份重建（Signal 同款心智）。schedule-app §11 加「Plan A v3：换机恢复」拍板行（含强度门槛+忘密代价两条有意取舍留痕）。

### relay 仓（契约 v0.1.7-contract）
- `POST /v1/auth/change-password` 加可选 `new_recovery_vault`（base64）：与密码哈希**单事务同落**（`store.UpdatePasswordWithVault`：Begin→两写→Commit，缺省字段仅换哈希兼容无金库账号），落库后照常纪元 Bump。零 DDL 变更；README 版本账升级 v0.1.7 + 改密段重写。验证：`go build/vet/test` 全绿（go1.23.6）。

### 端侧
- `lib/collab/recovery_vault.dart`（新）：`RecoveryVaultCodec` sealVault/openVault——信封 JSON `{v,kdf{alg,mem_kib,iter,parallel},salt,nonce,cipher_text,mac}`（服务端纯盲端侧自解析）；明文载荷=MSK‖身份私钥‖SPK 私钥三段 32B 定长；AAD=context 串 `shiguang.recovery.v1`；密码错/信封坏/版本不支持一律 `RecoveryVaultException`（禁止静默半盲）。
- `registration.dart`：注册时密码派生 KEK 封存三段私钥为 recovery_vault 上传（本机日常封存体仍走 KeyStore.seal/MSK，口径分开：本机零密码无感，换机才用密码解库）。
- `auth_api.dart`：`changePassword` 加 `newRecoveryVault` 入参（present 才带字段）。
- `account.dart`：①`changePassword` 改密即重封——`me()` 取回的金库信封缓存 `_lastVaultEnvelope`（仅内存），改密时旧密码解包→**用金库里的原身份密钥重封**（不能用本机 MSK——新设备 MSK 是新生成的，用了会偷换身份）→同包提交；无金库账号缺省纯改密兼容。②`restoreIdentityFromVault`（换机恢复）：`/me` 取金库→密码解出三段→`KeyStore.importMsk` 落安全区+IdentityStore 落本机；返回 false=服务端无金库。
- `key_store.dart`：+`importMsk`（换机恢复专用：金库解出的 MSK 接管安全区）。
- `relay_mock.dart`：注册落 recovery_vault + 改密重封落库（对齐真服务端口径）。

### 验收
- 新增 `test/collab_recovery_vault_test.dart` 8 例：roundtrip/KDF 信封自解析/错密码抛/坏信封抛/独立 salt 密文不同/换机恢复 MSK 一致/无金库 false/改密重封后新密码可恢复旧密码不可解。
- 全量 `flutter test` **431/431**（EXIT=0）；analyze 零 issue；docs-lint 过。
- 两处真缺陷修于施工中：①relay_mock `_register` 原不落 recovery_vault（/me 恒 null）；②改密重封首版误用本机 MSK（会偷换身份），改用金库解出的原身份密钥。

### 边界与挂账
- 存量联调账号不迁移（v3 拍板⑤）：注册侧金库口径变了，老账号金库是 MSK 封存体解不开——重新注册即可。
- 换机恢复/解封失败的 UI 入口未接（AccountPage 暂无「恢复身份」流程；解封失败须明示「重新生成身份」并预告 safety number 全变/好友重建联后果）。
- 密码强度门槛（≥10 位或 passphrase 提示）未加：现沿用 ≥8 位，服务端盲存可离线爆破，密码熵=金库安全，建议真机联调前补。

### 下一步
真机联调全链路（现可完整走通换机场景：注册→换机登录→免密恢复身份→解历史密文）；Phase 4.2 拒绝面板+审计轴续行。

## 2026-10-11 · 用户体系契约 v2 对接：会话纪律（轮换/踢会话/登出/自账户/关系管理）

用户更新《用户体系对接文档》并补充 6 端点 + 3 硬约束；经核对 relay 独立仓服务端已全部实现（router.go/me.go/friends.go + apierr 字典）。客户端本轮对齐。

### 契约新增（服务端事实，逐 handler 核对）
- `POST /v1/auth/logout`（Bearer + 可选 refresh_token）：双 jti 拉黑至自然到期，幂等，登出永远 200。
- refresh **强制轮换**：换新后旧票 jti 拉黑，重用 → 401 `TOKEN_REVOKED`。
- 改密即踢会话：纪元 Bump，全端 token 即灭 → 401 `TOKEN_STALE`。
- 登录防爆破：同账号失败 ≥5 锁 15min（正确密码也 429 `LOGIN_THROTTLED`，detail.retry_after_sec）；IP 桶 10/min。
- `GET /v1/users/me`（读回 user_id/username/created_at/encrypted_profile?/recovery_vault?）+ `PATCH /v1/users/me`（三指针字段：缺省不动/present 替换/push_token 空串清空；超限 413 ATTACHMENT_OVERSIZE；红线=金库包裹钥独立于登录密码）。
- `DELETE /v1/friends/{id}`（删好友/撤回申请/解除拉黑三合一，双向命中；无关系 404 INVITE_NOT_FOUND）+ `POST /v1/friends/{id}/block`（无需既有关系；配额 ≤500 → 400 EXCEED_BLOCKED_LIST）。
- `RELATION_EXISTS` 统一口径（含被拉黑，不泄露）。

### 落子
- `auth_api.dart`：+`logoutRemote`（网络失败不阻断——登出必须本地可完成，契约语义非猜测兜底）/`fetchMe`（MeProfile）/`patchMe`（只序列化 present 字段）/`retryAfterSecOf`。
- `friends_api.dart`：+`removeFriend`/`blockFriend`（4xx/5xx 归一 RelayFriendsException）。
- `account.dart`（重写持久化与终结语义）：**access 只存内存、安全区只存 refresh**（§5 红线）；restore 后首次取票走 refresh 轮换；换新成功立即落盘新票（轮换与落盘间崩溃=重新登录，安全优先取舍，已注释）；`_terminateSession`（本地终结，不触网）与 `logout`（先服务端拉黑再本地清）分离；`changePassword` 成功 → 本地同步踢会话转 loggedOut；login 的 user_id 补齐通道从 lookup 自查改 `GET /users/me`（语义对口）；透传 `me()`/`updateMe()`（push_token 换发钩子，UI 暂不接）。
- `relay_session.dart`：+`removeFriend`/`blockFriend` 透传（friends_api 别名导入防方法名遮蔽递归）。
- UI：AccountPage 改密成功 toast「密码已更新，请重新登录」（session 监听自动回表单）；错误映射补 PASSWORD_MISMATCH/LOGIN_THROTTLED（retry_after_sec 换算分钟）/TOKEN_REVOKED/TOKEN_STALE；friends_page accepted 行「⋮」菜单（解除好友/拉黑）、blocked 行「解除拉黑」，破坏性操作二次确认，409 文案统一「关系已存在」（勿区分被拉黑）。
- `relay_mock.dart`（重写）：轮换+拉黑集合、改密删全 token、logout 幂等、账号失败计数锁定、关系存储（pending/accepted/blocked + 409/404 面）、/users/me GET+PATCH。

### 验收
- 扩展 2 套测试 +11 例：`collab_auth_api_test`（轮换重用 TOKEN_REVOKED / 登录锁定 429+retry_after_sec / logout 拉黑+幂等 / 自账户 GET+PATCH 独立生效与清空 / 关系管理 removed-可重申-blocked-404 面与 409 统一口径）；`collab_account_session_test`（access 不落盘断言 / restore 首取走轮换 / 轮换重用降级 / 改密踢会话+旧票 401 / 登出走服务端+幂等 / login-only user_id 来自 /me）。
- 全量 `flutter test` **412/412**（EXIT=0）；`flutter analyze` 零 issue；arch-guard 6/6；docs-lint 过。
- 文档：ui-spec §0.4 账号域+好友域补 7 词条（账号锁定/改密旧密码错/改密成功/会话终结/解除好友/拉黑/解除拉黑/关系已存在）；schedule-app §11 加「用户体系 Plan A v2：会话纪律」拍板行；fact-user-relay-draft §3.2 注记补 v2 段。

### 边界与挂账
- 撤回申请（pending 发起方 DELETE）API 已通，UI 暂无入口（pending 行分不清方向；待 FriendView 带 direction 再上）。
- recovery_vault 取回链路（换机恢复：登录 → /me 取金库 → 端侧解密恢复）契约就绪，端侧施工挂换机切片——且注册侧 recovery_vault 目前是 MSK 封存体，换机后新设备 MSK 不同解不开，届时需按「金库包裹钥独立于登录密码」红线重设计（可能引入一次性恢复口令）。
- WSS 3.4.2 无需改码：客户端重连恒取最新 token（ensureAccessToken），登出即停引擎。

### 下一步
真机联调全链路（含改密踢会话/拉黑/解除）；Phase 4.2 拒绝面板+审计轴续行。

## 2026-10-11 · 用户体系 Plan A 对接：登录态门禁（口令解锁层退役）

用户交付《用户体系对接文档（客户端 ↔ 中继）》并拍板：**去掉复杂的解锁和加密判定，改为对用户登录状态的判定**。服务端（relay 独立仓）已实现账号体系 Plan A（README 版本账），客户端整体滞后——本次把端侧用户体系对齐：用户只见「用户名+密码」，E2EE 密钥藏在账号背后全程无感。

### 落子（账号域）
- `lib/collab/relay/auth_api.dart`（重写）：注册体改 `username`+`password`（**user_id 服务端发号、signed_pop 取消**，PoW 保留）；`login` 改纯密码换双 Token（含 `access_expires_at`；用户不存在与密码错误同一 401 口径）；新增 `changePassword`/`lookupUser`；`errorCodeOf` 提取服务端统一错误体。全部端点 4xx/5xx 归一 `RelayAuthException`。
- `lib/collab/account.dart`（新）：`AccountSession`——登录态单一来源。三态 unknown/loggedOut/loggedIn；凭据（username/user_id/双 Token）落安全区 `collab.account.session`，冷启动 `restore` 复原；`register`=注册+自动登录、`login`、`logout` 只清会话不动数据；`ensureAccessToken` 到期（留 60s 余量）自动 refresh，refresh 401 自动降级未登录；端侧用户名/密码校验与服务端同口径（`isValidUsername`/`isValidPassword`）。
- `lib/collab/registration.dart`（重写）：`registerAccount` 去 userId/passphrase 入参，密码只进注册请求体（服务端 PBKDF2，与本地密钥封存完全解耦）。
- `lib/collab/relay/friends_api.dart` 补 `submitFriendRequest`（搜索路径，`/v1/friends/request`），与扫码 Ticket 双通道并存。

### 落子（密钥域去口令化）
- `lib/collab/crypto/key_store.dart`（重写）：**口令→Argon2id KEK→包装 MSK 三层链整体退役**。MSK 改端侧自动托管：`ensureReady` 首次生成 256 位随机钥落安全区（`collab.msk.raw`），明文只驻「安全区+内存」；`seal/open` 语义与半盲纪律不变。删 `createVault/unlock/lock/changePassphrase/hasVault` 与 `VaultSession`、`VaultLockPage`。
- `lib/collab/identity_store.dart`：删 `SecureUserIdStore`（handle 自生成体系随服务端发号退役）；`identity.dart` 删 `signProofOfPossession`（PoP 取消）。

### 落子（门禁接线）
- `relay_session.dart`：token 改由 `AccountSession.ensureAccessToken` 颁发；删三处 `isUnlocked` 门禁；`restoreIdentityKeyPair`/`restoreSignedPreKeyPair` 自带 `ensureReady`（换进程开箱即用）；`buildBundleComposeKit`/`buildFriendsSession` 改 `account` 必传，null 口径收敛为「本机无协同身份」。
- `runtime.dart`：start 前置从「vault 解锁+身份」收敛为「有身份」，登出即停。
- `app_services.dart`：`vault` 单例改 `keys`（KeyStore）+ `account`（AccountSession）双惰性装配；`attachCollabLifecycle` 改挂登录态——登录即 `ensureReady` 备钥、登出即停回路；删匿名自动注册链（`_ensureRegistered`/`_resolveCollabUserId`，注册现在必须显式给用户名密码）。
- `schedule_page.dart`：协同入口先判 `account.isLoggedIn` 再装配 compose/friends 套件。
- UI：`lib/ui/account_page.dart`（新）——登录/注册双模式表单 + 已登录视图（改密/退出）；settings 入口「协同口令」→「协同账号」（摘要行随登录态刷新）；friends_page 增「搜用户名加好友」通道；scan/my_qr/member_calendar/decision_matrix 四页解锁文案统一改登录引导。文案先入 ui-spec §0.4「账号域」词条组再上屏。

### 验收
- 新增 3 套测试：`collab_auth_api_test` 7 例（登录/续期/改密/lookup/搜索加好友/errorCodeOf，打进程内 mock relay）、`collab_account_session_test` 8 例（注册即登录/凭据跨进程/restore 容错/统一 401/过期 refresh/refresh 失效降级/登出保数据/端侧守卫）、`account_page_test` 3 例（表单校验×2 + 已登录视图退出，零网络——testWidgets 拦截真 HTTP，网络流由会话单测覆盖）。
- 改造 6 套既有测试：relay_mock 重写为账号契约（用户名唯一/PoW/OPK 窗/lookup/friends-request）、register/relay_session/runtime/e2e/enroll/bundle_key_source 同步迁移；`decision_matrix_view_test` 文案断言同步。
- `flutter analyze` 全仓零 issue；`toolbox run arch-guard` 6/6。
- 文档：schedule-app §11 加「用户体系 Plan A：登录态门禁」拍板行；fact-user-relay-draft §3.2 加修订注记（PoP 骨架退役留痕，契约 SSOT=relay 仓 README）；ui-spec §0.4 账号域词条组；docs-lint 通过。

### 已知边界（挂账）
- 换机冷启动：登录成功但本机无协同身份记录 → K_bundle 解不开走半盲（好友/发起可用，历史密文不可解）——完整恢复挂 `recovery_vault` 换机切片。
- 换号登录：本机身份记录与登录账号不一致时暂不做迁移防护（联调期口径，见 §11 拍板行）。
- 服务端（relay 仓）侧如与本文档字段有出入，以 relay 仓 README 契约为 SSOT 回改。

### 下一步
真机联调全链路（注册→登录→扫码/搜索加好友→发起→接收），Phase 4.2 拒绝面板+审计轴续行。

## 2026-10-10 · Phase 4.3b App 冷启动挂常驻引擎（被邀端密钥入户一并闭环）

Phase 4.3a 把「写」侧第一条 PROPOSE 打通后，系统仍只在发起页手动 `drainNow` 时弹一次——WSS 不建连、网络恢复不补发、被邀端收不到推送。本切片把回路真正挂成 App 级常驻，并把被邀端密钥入户（上一节遗留的「下一步」）一并收口。

### 落子
- `lib/collab/enroll.dart`（新）：`BundleKeyIntake` —— 被邀端密钥入户编排。`fetch`(注入＝`fetchBundleKeyEnvelopes`)→ 逐条 `openBundleKeyEnvelope`(SPK 私钥开信封)→ `BundleKeySource.persist`。**半盲纪律**：拉不到（离线/403/5xx）、MAC 不过（不是我的信封）、已落户版本，全部 swallow 成跳过而非抛——骨架照常推进，绝不写空密钥/伪造版本。
- `lib/collab/runtime.dart`（新）：`CollabRuntime` —— App 级单例持有 `CollabEngine`。起停前置严格：vault 已解锁（MSK 在内存）+ 本机有协同身份；缺一即不起（没 MSK 取不出 K_bundle，起了也半盲）。`onBundleSeen` 钩子把 `BundleKeyIntake.enroll` 焊到「某包首条事件到达」——趁在线补拉我的 K_bundle 信封，解不开那段历史按半盲降级。
- `lib/app_services.dart` + `lib/main.dart`：把回路与 vault 机态绑定——`attachCollabLifecycle` 监听 vault，`isUnlocked`→`start`、`lock`→`stop`（§5.3 退后台清零 MSK，回路空转无意义）；start/stop 全幂等，装配失败（中继不可达）catch 成非致命，下次解锁再试。

### 卡点与定盘
- 被邀端解钥的钥匙是 `IdentityRecord.sealedSignedPreKeyPrivate`：注册侧已封存 X25519 SPK 私钥（Phase 2.3 补齐），存量未封存记录 `restoreSignedPreKeyPair` 抛 `StateError` 走半盲降级（重新注册可恢复）。
- `PinnedProposalReconciler` 迁号 + `BundleKeyIntake` 落户 + 常驻引擎，三处共同让「认祖归宗」与「被邀入户」在临时 id → 服务端号迁移后一致：否则被邀端窗口期内的密钥挂临时号、服务端只认真号，回放取钥歧义。

### 验收
- 新增 2 套单测 9 例全绿——`collab_enroll_test` 5（真实 X25519 信封往返落户逐字节相等 / 离线返回 0 不抛 / 已落户幂等跳过 / A 封 B 解 MAC 不过静默跳过 / 空列表返回 0）+ `collab_runtime_test` 4（锁定返回 false 不起 / 已解锁无身份返回 false 不触网 / 重复 start 幂等 / 未起即 stop 安全）；全量 **383/383**（EXIT=0），`flutter analyze` 新文件零 issue。

### 下一步
- 好友域 UI（§1.6 / Phase 4.6）：扫码建联 + 安全码核对页。发起页 `acceptedFriends` 依赖它；否则发起页好友名单恒空，协同仍不可达真机。

## 2026-10-10 · Phase 4.3a 本地发起 bundle（服务端发号前的半程补齐）

目标：此前服务端 Phase 1.4 早已支持 PROPOSE 建包（准入/行锁/配额齐全），但**端侧从来没有发出过第一条 PROPOSE**——六页 UI 全是「读」侧。本切片把「写」侧第一条链路打通。

### 落子
- `lib/collab/propose.dart`（新）：`BundleProposalService` 编排「生成 K_bundle→MSK 封存落户→逐成员 `EK×SPK` X3DH 封出 `member_key_envelopes[]`→组装 payload 入发件箱」；`serverTopologyOf` 只吐 §4.2 结构四件套（标题/时段零外露），`proposalPayloadOf` 与 `replay.signalFromPayload` 严格互逆；`PendingProposalReconciler` 负责认祖归宗。
- `lib/collab/relay/peers_api.dart`（新）：`GET /v1/friends`（双向 accepted 才进可邀名单）+ `GET /v1/users/{id}/prekey`（取成员 X25519 SPK 公钥）。
- `lib/collab/relay_session.dart`（新）：本地身份→MSK 解开 Ed25519 私钥→PoP 登录缓存 token，装配真机 `BundleComposeKit`；vault 未解锁/未注册身份一律返回 null。
- `lib/ui/collab/compose_bundle_page.dart`（新）：发起表单（和谁一起 / 怎么定 / 要做什么），入口挂在成员混合日历页浮动钮。
- `lib/collab/relay/action_api.dart`：补齐 Go `actionReq` 早已支持却没发的字段；本地发起 `bundle_id` 留空（服务端建包分支判据），201 回执经新结局 `SendProposed` 带回。
- `lib/collab/outbox.dart`：新增 `SendProposed` / `DrainReport.created` / `OutboxRepository.rebindBundle`，`wireCollabSystem` 挂认祖归宗钩子。

### 卡点与定盘
- **bundle_id 谁发**：Go `store.Propose` 是 `INSERT … RETURNING bundle_id`，服务端发号；端侧在发出前只有临时 id，而密钥信封与窗口期内随手的后续操作都挂在临时 id 上——不改绑则后续操作恒 404。定盘：服务端号为唯一真值（已入 schedule-app §11 台账），端侧做幂等迁移。

### 排障（两处真实缺陷，非绕过）
- `OutboxDrainer.drain` 原开局缓存整批 pending，认祖归宗改绑后缓存里的后续条目仍拿临时 id 发（必 404）→ 改为「每发一条重新取首条」，用一次查询换改绑即时生效。
- `CollabDrainScheduler.dispose` 与在途 drain 竞赛 → `reports.add` 打在已关闭 broadcast 上炸未处理异步错误 → dispose 先等在途链落地，`trigger` 侧补 `isClosed` 守卫。

### 验收
- 新增 3 套单测 23 例全绿（propose 11 / relay_session 7 / compose_form 5）；全量 **374/374**（EXIT=0），`flutter analyze` 新文件零 issue；`toolbox run docs-lint` 通过（ui-spec §0.4 已先入词再上屏）。

### 下一步
- 被邀端密钥入口（`/bundles/{id}/key-envelopes` → 解 K_bundle → 落户），否则被邀端解不开自己的邀约；
- App 冷启动挂载常驻 `CollabEngine`（当前只有发起页手动 drainNow 会弹出）；
- 好友域 UI（§1.6 / Phase 4.6）补齐后发起页名单才真有数据。

## 2026-10-10 · Phase 4 编排收口（三件套先于六页 UI 落地）

收口目标：把 Phase 2/3 的零散组件焊接成常驻运行的协同回路，使系统可端到端跑起来，再上 §11.2 六页 UI。

### 落子
- `lib/collab/relay_config.dart` — `RelayConfig`：`RELAY_BASE_URL` 单一来源（dart-define 注入，默认 `http://10.0.2.2:8080`），`wssUri` 由 http→ws 追加纯路径 `/ws`。
- `lib/data/collab_repository.dart` — `CollabBundleRepository`：`maxSeqId`(读 `collab_bundles.max_seq_id`，sinceSeqOf 数据源) · `ensureBundle` · `recordEvent`(落原始事件 + 水位 GREATEST，UNIQUE 幂等) · `loadEvents`(升序供 FSM 重放)。
- `lib/collab/connectivity.dart` — `ConnectivityWatcher` 抽象 + `PollingConnectivityWatcher`(零依赖) + `CollabDrainScheduler`(串行链把 `OutboxDrainer` 挂到断→通)。
- `lib/collab/engine.dart` — `CollabEngine` 焊接 `pipeline.applied→落库` + `attachWss` + `WakePuller(sinceSeqOf)` + resident drainer；`wireCollabSystem` 以 `RelayConfig` 焊接 WSS/action/sync 三路。
- `pipeline.dart` 补 `serverAtMs`(§5.2 法定时钟)，`wake_puller.dart` 注入 sync `serverAtSec*1000`。
- `db.dart` 升 v6 迁移补 `collab_events.server_at_ms`（新装同构即认）。

### 排障（真实缺陷修复）
- 引擎 `dispose` 关库早于管道在途落库 → `database_closed`：加 `_inflight` 集合，`dispose` 先 `Future.wait` 再关库。
- `CollabDrainScheduler` 注释称「共享一把锁」实则 `trigger()` 无锁，并发触发 TOCTOU 双发同一条（违背 §11.1 离线保序）：落地 `_chain` 串行 Future 链。

### 验收
- 4 套单测 19 例全绿；全量 `flutter test` 290/290（EXIT=0）；`flutter analyze` 新文件零 issue。

### 下一步
六页 UI 组装（§11.2：4.1 成员混合日历 / 4.2 拒绝面板+审计轴 / 4.3 发起人画板+决策矩阵 / 4.4 Consensus 看板 / 4.5 下沉事务+REOPEN 强通知 / 4.6 好友域 UI）；文案先入 ui-spec §0.4 词汇表再上屏。

## 2026-10-10 · Phase 4.6 好友域 UI（扫码建联 + 安全码核对）

发起页 `acceptedFriends` 一直恒空——根因是缺少「好友域 UI」：服务端 1.3 已就绪，但端侧没有签发/扫名片/确认/核对的入口，名单永远拉不到人，协同仍不可达真机。本切片补齐端侧好友域。

### 落子（lib 层）
- `lib/collab/friends_ticket.dart`（新）：`FriendshipTicket` 模型（字段名对齐 relay `ticketReq`，可直接当 QR 载荷转发）+ `signFriendshipTicket`（本地身份私钥 Ed25519 签 `shiguang.relay.v1/friendship-ticket|issuer|subject|nonce|expiresAt`，与 relay `auth.TicketMessage` 逐字节一致）+ QR 编解码 + `safetyNumberDigits`（双方身份公钥顺序无关 SHA-256 派生 6×5 位，双端算同一串）。
- `lib/collab/relay/friends_api.dart`（新）：`submitFriendInvite` POST /v1/friends/invite + `acceptFriendInvite` POST /v1/friends/accept；HTTP 4xx/5xx 归一 `RelayFriendsException`（带状态码，UI 区分「已加过/名片过期/登录失效」），纯网络失败原样抛归离线。
- `relay_session.dart` 扩：`issueTicket`·`submitInvite`·`acceptInvite`·`acceptInviteFromPeer`（invite_id 拼 `{peer}:{me}`，服务端强制被邀方=本机，发起方误点 404 安全不误判）·`peerIdentityPublicKey`·`myIdentityPublicKey`·`buildFriendsSession` 装配（未解锁/未注册 → null，UI 转引导）。

### 落子（UI 层）
- `lib/ui/collab/friends_page.dart`（新）：好友主页——我的名片/扫一扫入口、已互加（安全码入口）、待确认（通过/忽略）、已拉黑、空态引导。
- `lib/ui/collab/my_qr_page.dart`（新）：签发我的名片（含 TTL，可刷新）展示二维码。
- `lib/ui/collab/scan_page.dart`（新）：`mobile_scanner` 扫码 → 解析 Ticket → 提交邀请；无相机/权限失败时给「手动填入名片串」兜底。
- `lib/ui/collab/safety_number_page.dart`（新）：6×5 安全码核对页。
- 接线：`member_calendar_page` 加「好友」AppBar 入口 + 发起页零好友「去好友里加一位」深链；`schedule_page` 注入 `loadFriendsSession`。
- 依赖：`qr_flutter`/`mobile_scanner`/`permission_handler`；AndroidManifest 加 `CAMERA` 权限与 `android.hardware.camera` 特性。
- 文案：ui-spec §0.4 先入「好友域」词汇表再上屏（半盲纪律的 UI 侧）。

### 验收
- 新增 2 套单测 8 例全绿：`collab_friends_ticket_test` 4（签验签逐字节对齐 relay 规范化消息 / 篡改 nonce 验签失败 / QR 往返无损 / 安全码顺序无关确定可复现）、`collab_friends_api_test` 4（invite 正确投递+409 归一 / accept 正确投递+404 归一，mock `HttpClientAdapter` 零网络）。
- `flutter analyze` 新文件零 issue。
- **断点收口**：发起页好友名单不再恒空，协同可达真机。

### 下一步
真机扫码联调（`flutter run` 实机扫名片 + relay pod 起 `smoke-phase1` 全通链路）+ Phase 2.5–2.7 双机对测收尾。
## 2026-10-11 · 发起回执诚实化（「已留在待发里」谎报修正）

真机验收（Phase 4.6 后）：好友能加上，但发起协同点「发出邀请」弹「暂时发不出去，已经留在待发里」。排查定位：

### 根因定性
- 该文案在 `_submit` 的裸 `catch (_)` 分支——即 **propose 阶段**（取成员公钥 / K_bundle 落户 / 发件箱落盘）任一步抛异常。此阶段失败＝**箱里一条都没有**，但文案谎称「已经留在待发里」：用户据此等待常驻调度补发将永远等不到（§11.1「先落盘、后反馈」被违反在反馈侧）。
- 具体哪一步挂死需真机复验：release 包 not debuggable、无 Flutter 日志可捞；屏面回执是唯一现场。候选：`GET /v1/users/{id}/prekey` 失败（RelayPeersException/DioException）、`AccountSession.ensureAccessToken` 抛、KeyStore.seal 异常。另：drain 阶段若被 4xx 清箱会弹「已记下，联网后自动发出」同样是谎（已另列观察）。

### 落子
- `lib/ui/collab/compose_bundle_page.dart`：`_submit` 拆两段——propose 未完成→「没发出去：<原因>」（新增顶层纯函数 `composeFailureText`：DioException→联网再试 / 401→登录引导 / 404→对方重新登录 / 429→限频 / 兜底带异常摘要；debugPrint 留现场）；propose 成功（确已入箱）→弹出战报只会说「已发出…」或「已记下，联网后自动发出」，drain 自身异常不再吞成失败态。sent 判定抽顶层 `composeSentOut(DrainReport?)`。
- 文案先入 ui-spec §0.4：「发起回执」行修正 + 新增「发起失败原因」行；docs-lint 过。
- 测试：`test/collab_compose_submit_test.dart`（新）11 例全绿——composeSentOut 4 / composeFailureText 5 / 编排级「propose 抛＝箱空」「propose 成＝条目在」2。**教训**：端到端 widget 用例（真实 propose + sqflite ffi）在 FakeAsync 下挂起（runAsync 也不解，tearDown 卡死），按项目既有经验改走纯函数 + 编排级分层，不硬磕 widget 环境。

### 验收
- `flutter analyze`（改动文件）零 issue；`collab_compose_form_test`/`collab_propose_test` 回归 17 例全绿。
- **下一步**：真机重装后复点「发出邀请」，把新回执原文带回——那句「没发出去：…」就是根因，再修传输/会话层真伤。
## 2026-10-11 · 好友页「一直提示登录」（登录态快照缺陷）

真机反馈：登录后进好友页仍反复提示「先到设置→协同账号登录」。

### 根因
`schedule_page` 协同日程入口把 `AppServices.account.isLoggedIn` **拍进闭包快照**（`loadComposeKit`/`loadFriendsSession` 两个钩子长期持有）。用户先开过协同日程页（未登录，或冷启动 `restore` 未完成）→ 快照 false → 之后再去设置里登录，钩子仍按快照放行 null——好友页/发起页怎么登录都提示登录。

### 落子
- `schedule_page.dart`：两个装配钩子改**现查** `AppServices.account.isLoggedIn`，删闭包快照。
- `friends_page.dart`：null 会话有两种来路（未登录 / 已登录但本机无协同身份记录，如卸载重装后安全区被清），原文案对后者误导成死循环——改「完成登录与协同身份注册，才能管理好友」；ui-spec §0.4「未登录引导」词条同步（2026-10-11 修正）。

### 验收
`flutter analyze`（改动文件）零 issue；`collab_compose_submit_test`+`collab_compose_form_test` 回归 17 例全绿；docs-lint 过。

## main 侧增量（Binder 拆分化，2026-10-17，合并带入；增量日志按行追加，≥5 条自动归并进 memory.md）
- [2026-10-17] [变更]: 并发停滞取证轮：真机三并发复现「占位帧回、终帧不到、goodhub 响应停滞」。静态排查排除 Dart 层并发卡死（新增 mcp_binder_parallel_sim_test 并发模拟钉子）/AIDL oneway（已确认）/goodhub 原生链路；断环需真机日志定位，原生 async 路径加 [F1] 生命周期日志（dispatch posted/done/terminal emitted）+ [DEGRADE] 已有
- [2026-10-17] [验证]: 3 并发 dispatch 模拟单测 ✓（Dart 层无卡死）；analyze 0 issue（修 sim 测试多余 !）；debug APK 21:56 重编 ✓ sha256 前缀 624b2af4
- [2026-10-25] [变更]: 新增 test/collab_scenario_test.dart 分层多端场景测试 3 例：小规模 4 端全链路（发起→定向解密→3 票回执→全局 seq 唯一）、大规模 16 端并发风暴（实收面 seq 一致无重无缺 + 管道连续前缀 + 单消费恒 1）、离线端 /sync 追平与双路去重；全量 467/467 + analyze 零 issue
- [2026-10-25] [验证]: flutter analyze 0 issue; flutter test 467/467 全绿
