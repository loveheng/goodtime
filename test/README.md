# test/golden/ · P0 黄金向量集

跨实现对拍向量（native-migration §7 P0）：**Dart 与 Kotlin 双侧消费**，任一侧实现漂移即对拍红。
向量文件为**只读契约**（dev-loop 测试写保护）——实现漂移时唯一出路是修实现，严禁改向量迁就实现。

## 文件清单

| 文件 | 内容 | 生成器 |
|---|---|---|
| `e2ee_sealing_vectors.json` | E2EE 封装往返·sealing（31 cases） | `tool/golden/gen_sealing_vectors.dart` |
| `e2ee_freemask_vectors.json` | E2EE 封装往返·freemask（17 cases） | `tool/golden/gen_freemask_vectors.dart` |
| `fsm_transition_vectors.json` | FSM 转移表（54 cases，Directive/Consensus/敲定后生命周期） | `tool/golden/gen_fsm_vectors.dart` |
| `mcp_rejection_vectors.json` | 命令拒绝码·内核面（17 cases，JSON-RPC 协议码 + 分发 + 透传） | `tool/golden/gen_mcp_rejection_vectors.dart` |
| `sqlite_schema_vectors.json` | SQLite Schema 快照（5 cases，全结构快照/升级同构/约束拒绝） | `tool/golden/gen_schema_vectors.dart`（flutter test 薄壳驱动） |
| `mcp_toolface_vectors.json` | MCP tools/call 返回（23 cases，值泛化结构 + 领域码值级） | `tool/golden/gen_toolface_vectors.dart`（flutter test 薄壳驱动） |
| `relay_proto_vectors.json` | relay proto 序列化字节（8 cases，含拍板三组边界） | `tool/golden/gen_proto_vectors.dart` |
| `time_basis_vectors.json` | 时间基准（29 cases，作息日切割/ISO/闰日/epoch64） | `tool/golden/gen_time_vectors.dart` |
| `model_json_vectors.json` | 领域模型 JSON 契约·Artifact 试点（14 cases，v 序/键序/防御/缺省） | `tool/golden/gen_model_vectors.dart` |
| `models_vectors.json` | 领域模型三件（Plan/ScheduleBlock/Background，10 cases） | `tool/golden/gen_models_vectors.dart` |
| `command_parse_vectors.json` | 命令解析契约（12 cases，fromJson 判别/严进/缺省） | `tool/golden/gen_command_parse_vectors.dart` |
| `golden_e2ee_test.dart` | Dart 侧基准执行器（消费上述 E2EE 向量） | 人写 |
| `golden_fsm_test.dart` | Dart 侧基准执行器（消费 FSM 向量） | 人写 |
| `golden_mcp_test.dart` | Dart 侧基准执行器（消费 MCP 拒绝码向量） | 人写 |
| `golden_schema_test.dart` | Dart 侧基准执行器（消费 Schema 向量） | 人写 |
| `golden_toolface_test.dart` | Dart 侧基准执行器（消费 toolface 向量） | 人写 |
| `golden_proto_test.dart` | Dart 侧基准执行器（消费 proto 字节向量） | 人写 |
| `golden_time_test.dart` | Dart 侧基准执行器（消费时间基准向量） | 人写 |
| `golden_model_test.dart` | Dart 侧基准执行器（消费模型向量；Kotlin 对拍=:core:model） | 人写 |
| `golden_models_test.dart` | Dart 侧基准执行器（消费三模型向量；Kotlin=:core:model GoldenModelsTest） | 人写 |
| `golden_command_parse_test.dart` | Dart 侧基准执行器（消费命令解析向量；Kotlin=:core:action） | 人写 |

## 生成与验证

```bash
export PATH="$HOME/flutter/bin:$PATH"
dart run tool/golden/gen_sealing_vectors.dart     # 确定性：全常量 nonce/种子，重复运行字节一致
dart run tool/golden/gen_freemask_vectors.dart
dart run tool/golden/gen_fsm_vectors.dart         # 确定性：全常量事件脚本与 server_at_ms
dart run tool/golden/gen_mcp_rejection_vectors.dart  # 确定性：fake 工具面全常量
flutter test tool/golden/gen_schema_boot_test.dart   # Schema 生成器经 flutter test 薄壳驱动
                                                     # （db.dart 沾 sqflite→flutter 链，dart run 不可编译）
flutter test tool/golden/gen_toolface_boot_test.dart # toolface 生成器薄壳（同因）
dart run tool/golden/gen_proto_vectors.dart       # proto 生成码纯 Dart，直接 dart run
dart run tool/golden/gen_time_vectors.dart        # 时间基准纯 Dart 函数族
dart run tool/golden/gen_model_vectors.dart       # 模型 JSON 契约（试点 Artifact）
dart run tool/golden/gen_models_vectors.dart      # 模型三件
dart run tool/golden/gen_command_parse_vectors.dart  # 命令解析契约（纯 Dart）
flutter test test/golden/                          # Dart 侧基准执行器 → 全绿 = 期望值已锁定
```

生成器纪律：禁 Random、禁时钟；向量语义只取**既有测试已固化行为 + 标准算法原语**（AES-256-GCM / X25519 RFC 7748）；
边界候选一律**不进**生成器，见文末候选清单。

## 向量集 JSON Schema（v1）

```
{ set, schema:1, generated, generator, reference, consumers[], encoding,
  rules{...},            # 跨实现复刻所需的规则描述（Kotlin 只读本文件即可实现对拍）
  cases: [ { id, op, input{...}, expect{...} } ] }
```

- **bytes 字段一律小写 hex**；时间字段为毫秒时间戳（绝对值，无本地时区依赖）。
- **语义状态码**：`expect.status` 缺省 = ok（expect 含载荷）；`reject_argument` = 参数校验拒绝（ArgumentError 族）；
  `reject_auth` = 认证失败（GCM MAC 校验，SecretBoxAuthenticationError 族）；`reject_format` = 畸形数据必拒
  （**异常类非契约**——跨端各自用本土异常，只钉「拒绝」这一外部可观察行为）。
- **信封字段形态**：向量集内一律 hex；真实现的信封 wire 形态为 base64（sealing 的 `SealedPayload.encode` JSON、
  freemask 临时信箱 map）——对拍过实现前按字段转码，执行器 `_eciesEnvelopeOf` 为参照实现。

## op 一览

**sealing 集**（规则 `pad_512`：`padded = plaintext ‖ pad×(pad&0xff) ‖ BE16(pad)`，`pad = 512 - len mod 512` 恒 1..512，
密文长度 ≡ 2 mod 512；aead：AES-256-GCM / 12B nonce / 无 AAD / 16B tag 随 `mac` 字段走）：

| op | input | expect | 说明 |
|---|---|---|---|
| `pad_512` | plaintext | padded | pad 规则逐字节（Dart 侧经 open 链验证） |
| `aesgcm_encrypt` | key, nonce, padded | cipher_text, mac | 原语级（输入已填充） |
| `seal_composite` | key, nonce, plaintext, key_ver | cipher_text, mac, nonce_echo | pad+AES-GCM 全路径，Kotlin seal 同输入须逐位一致 |
| `envelope_json` | key_ver, nonce, cipher_text, mac | json | `SealedPayload.encode` 编解码见证（base64 字段，键序=Dart 产出序，解析方不得依赖键序） |
| `open` | key, envelope | plaintext / reject_auth | 解方向 + 错误密钥拒绝 |
| `seal_len` | plaintext | cipher_text_len | 长度性质（nonce 无关） |
| `seal_guard` | key(非 32B), plaintext, key_ver | reject_argument | 守卫 |
| `envelope_malformed` | json(畸形信封) | reject_format | 缺字段/坏 base64 → 必拒；异常类非契约（拍板⑤） |
| `keyring_get_unknown` | setup[{bundle,key_ver,key}], probe | found: null | 未知版本查环 → null（拍板⑥） |

**freemask 集**（`kFreeMaskBucketMs = 1800000`；quantize 半开区间判据
`busyStart < bucketEnd && bucketStart < busyEnd`，桶数向上取整，start ≥ end 拒绝；
ECIES：X25519 裸 DH 输出（无 KDF）即 AES-256-GCM 密钥，明文 = signal JSON 紧凑序列化，
字段序 `target_start,target_end,mask`，无空白——**该序列化为 interop 契约**）：

| op | input | expect | 说明 |
|---|---|---|---|
| `x25519_publickey` | seed | pub | RFC 7748 标量基点（含 clamping） |
| `x25519_dh` | ek_seed, spk_pub | shared | 裸 DH 32B；DH 对称双向量 |
| `quantize` | target_start, target_end, busy[[s,e]..] | mask / reject_argument | 30min 量化桶 |
| `bitwise_and` | masks[] | mask / reject_argument | 全员重叠区 |
| `ecies_seal` | ek_seed, spk_seed, spk_pub, nonce, signal, signal_json | ek_pub, cipher_text, mac | 成员端单向加密全路径 |
| `ecies_open` | spk_seed, envelope | signal 字段 / reject_auth | 发起人解 / 非发起人拒 / 篡改拒 |

**fsm-transition 集**（op 统一 `replay`；语义 = `test/collab_fsm_test.dart` 19 用例逐一镜像。
`input.events` 为事件脚本，元素二形：`{signal:{action,sender,seq,…}}` 网络事件 /
`{local:{fn: maybe_expire|maybe_abandon, now_ms}}` 本地伪事件（纯函数时钟注入）；
`expect` = 终态全字段快照，枚举一律语义大写名，对象键与 participants 字典序、
required_uids/candidate_slots 保构造序，可空字段显式 null——**完整规范化规则见向量集
`rules` 节（8 条：enum_names / canonical_order / nullable / event_script / seq_watermark /
step_derive / bundle_derive / mutations / constants），Kotlin 实现转移表只读该节即可对拍**）：

| 分组 | cases | 钉样要点 |
|---|---|---|
| Directive | 25 | 主线确认、REJECT 双维 clash、PATCH_TIME 回音壁、MAKE_OPTIONAL 收敛、REOPEN 定向退回、CANCEL 守卫、M-of-N、BYPASS 门限收缩、48h 熔断三段（未满/恰满/再激活）、seq 防御三态（重复/REKEY/越权）＋边界拍板：REOPEN 缺省全量重置、PROPOSE 丢弃三态、步骤查无五连守卫、BYPASS 无可移丢弃、中性事件全族、maybeExpire/maybeAbandon 类型态限定 |
| Consensus | 16 | 门限固化与 winning_slot、✕不行 不计票、FORCE_FINALIZE 发起人独占、REOPEN 清票 + ADD_SLOT 满 8 上限、TTL 流局三态（23h/24h/CONFIRMED 免疫）＋边界拍板：VOTE 改投翻转固化、ADD_SLOT 重复候选不去重、PST slotId 双态校验、LEAVE 于 CONFIRMED 放行 |
| 敲定后生命周期 | 13 | PATCH_STEP_TIME 双端（Directive 步骤入 recheck 包不动 / Consensus 退回重议清票）、守卫（非发起人/非 CONFIRMED）、复活与破约（ACCEPT 复活 / REJECT 落 STALLED）、LEAVE（空名单转 optional / 门限收缩 / 发起人与终态守卫 / Consensus 撤票后补票） |

**mcp-rejection 集**（op `handle` = 内核 `handleJsonRpc(raw_body) → 响应对象` 对拍 + op
`negotiate` 版本协商纯函数；语义 = mcp_server_test / binder_entrypoint_test /
mcp_tools_test 镜像。对拍口径：JSON 语义对象深比，不钉原始串键序/空白；
**error.message 为参考文案不比**（剔除后深比，rules.message_policy），code / data / id /
result 逐字——instructions 政策精华整段逐字契约。fake 工具面仅钉包装与透传，业务工具
content 与 14 工具 tripwire 属「MCP tools/call 返回」类）：

| op | input | expect | 说明 |
|---|---|---|---|
| `negotiate` | requested | version | 支持版本回显 / 未知或非字符串回最新 2025-06-18 |
| `handle` | raw_body | 响应对象 / `{empty:true}` | initialize/ping/通知/协议拒绝码族（-32700/-32600 双态/-32601/-32602 缺 name·未知工具）/-32603 兜底/tools/list 包装/领域拒绝透传(data.schedule_rejected)/成功形态(content,isError) |

传输层守卫（-32001 鉴权判定式 / Origin 白名单 / 405/404）不进内核对拍，复刻规则在
向量集 rules.transport_guards 节（Kotlin 传输层逐条复刻）。

**sqlite-schema 集**（期望值由真实现跑库产出——sqflite_ffi 新装 createAll / v4 遗留库
经 migrateV5/V6/V7 升级；生成器内断言两者全结构同构后落盘。抽取规则全 PRAGMA 标准接口：
表名字典序、**列按名字典序**（物理 cid 序随迁移路径变化——ALTER TABLE 只能追加列，
列序非契约，访问恒按列名）、索引按 name 含 sqlite_autoindex_*、外键按 id、dflt 原样。
2026-11-14 D6 拍板：migrateV5 尾部补幂等索引 7 条修复「升级不补索引」存量缺陷）：

| op | input | expect | 说明 |
|---|---|---|---|
| `schema_snapshot` | kind=fresh | {version:7, tables{12 表全结构}} | 新装终态快照（列/索引/外键三方见证表级约束） |
| `upgrade_isomorphic` | legacy_ddl[v4 六表] | 同构终态 | v4 遗留库升级 ≡ 新装（全结构不变量，含索引） |
| `constraint_reject` | seed[], probe{table,row} | reject | UNIQUE(bundle,seq) / 复合 PK 拒绝（异常类非契约） |

**mcp-toolface 集**（op `tool_call`=值泛化结构对拍 + op `tool_reject`=领域码值级；
语义 = mcp_tools_test / mcp_facts_tools_test 镜像。值泛化规则 rules.generalize_rule：
uuid→"uuid"、YYYY-MM-DD→"date"、ISO 时刻→"iso_datetime"、毫秒戳→"timestamp_ms"——
uuid/实时钟返回两侧天然不同，结构级对拍；`$ref` 解引用解决种子内 uuid 引用；
领域拒绝 data.code/data_keys 值级钉样）：

| op | input | expect | 说明 |
|---|---|---|---|
| `tool_call` | seed[], tool, args | payload（泛化结构） | 14 工具代表路径：计划/设置/提案/调整/读面/建言/背景双工具/硬事实 |
| `tool_reject` | seed[], tool, args | code + data_code + data_keys | version_conflict / schedule_rejected / invalid_request / forbidden / budget_exceeded 全族 |

**relay-proto-bytes 集**（op `serialize_envelope`/`serialize_frame`；语义 = collab_proto_test
镜像 + 2026-11-11 拍板三组字节级边界。proto3 线格式确定性：字段按 field number 序、
负 int64 恒 varint 10 字节、unknown fields 解析后原 tag 保留回写）：

| op | input | expect | 说明 |
|---|---|---|---|
| `serialize_envelope` | fields（has 位探测） | bytes_hex + parsed | 拍板①空消息（0 字节）/ ②默认值标量 / 全字段（含负 int64、嵌套信封）/ 枚举值位 |
| `serialize_frame` | kind + fields | bytes_hex + which_body | 拍板③未知字段向前兼容往返 / ServerFrame oneof 双分支 / ClientFrame CLIENT_ACK |

⚠ **拍板②语义异常记录（rules.default_scalars_semantics）**：Dart 生成物为 has-语义
（PbFieldType.O），显式 set 默认值 → 编码 tag+0x00；proto3 隐式 presence 语义上应不编码
——2026-11-14 已验证闭环：重生成字节一致 + protoc_plugin 25.1.0 探针同行为——语义非
生成链陈旧而是 **Dart protobuf 运行时实然**（set 即 has）；业务不显式 set 0 → wire 兼容
影响≈0。javalite 重生成后 `proto_default_scalars` 预期变红（届时以重生成行为更新）。

**time-basis 集**（op `schedule_day`/`iso_date`/`parse_iso`/`add_days`/`cross_midnight`/
`epoch64`；语义 = schedule_day_test 镜像 + 时间基准五点拍板的可测子集：闰日 2024-02-29、
wake 00:01 前后（分钟粒度——秒不参与判定）、32 位秒溢出 2038-01-19 epoch 毫秒 > 2^31
往返无损（**64 位列强制 Long 契约**）。⚠ DST 跳变与时区骤变两点**挂账 Kotlin 时钟基建**
（Dart DateTime 本地时区无 tz 库不可注入——Kotlin 以 java.time.ZonedDateTime 人工构造
期望并过目，rules.dst_timezone_defer））：

| op | input | expect | 说明 |
|---|---|---|---|
| `schedule_day` | y,mo,d,h,mi,wake | iso | 作息日 wake 切割（跨年/凌晨收尾/wake=0/wake=1 秒级边界） |
| `parse_iso` | s | iso+hour+minute / reject | 严格补零；溢出日/月越界/未补零拒绝不回绕；闰年 02-29 |
| `add_days` | y,mo,d,n | iso | 月/年边界 + 闰日算术 |
| `cross_midnight` | start,end | crosses + span | 跨午夜判定与 24:00 折返时长 |
| `epoch64` | y,mo,d | ms + back_iso | 毫秒往返（>2^31 量级，Long 契约见证） |

**model-json-artifact 集**（P2 试点；op `payload_encode`/`payload_decode`/`payload_with_refs`/
`artifact_to_map`/`artifact_from_map`——**JSON 串逐字对拍（键序=序列化契约）**：
payload **v 恒首位**（值取 payload["v"] ?? 1）、toMap 键序=DB 行序列化契约
（badge/plan_id 恒输出 null→JSON null，id/created_at/updated_at 条件输出）、
读侧防御（null/空/畸形/非对象→{"v":1} 不抛）、fromMap 缺省（badge=null/capturedBy="me"/
version=0/planId=null 未归属池）。Kotlin 等价=:core:model Artifact（LinkedHashMap 保序同构），
对拍执行器=:core:model GoldenModelJsonTest（Kotlin 与 Dart 双执行器共读同一向量文件）：

| op | input | expect | 说明 |
|---|---|---|---|
| `payload_encode` | payload | json 串 | v 首位 + spread 保序（v 值覆盖不改位） |
| `payload_decode` | raw | payload / {"v":1} | 读侧防御（DEGRADE 日志旁路不比对） |
| `payload_with_refs` | payload, plan_id? | payload | 双写同步三态（有值/显式 null 移除/缺省保持） |
| `artifact_to_map` | fields | map | 全字段键序逐字（TEXT 串形态） |
| `artifact_from_map` | map | map + 断言字段 | 往返 + 缺省口径（勿与输入比幂等——缺省补全后多键属正常） |

**command-parse 集**（op `command_parse`；语义 = ScheduleCommand.fromJson 判别快照——
**写入口严进**（_reqStr 非空必填 / _objList·_strList 类型不对整组拒 / merge kind 非法拒）+
缺省口径（importance=false / shift minutes=30 / update_plan patch 字段 null=不改）+
CAS expected_version 字符串数字均可。Kotlin 对拍=:core:action GoldenCommandParseTest
（12 case，拒绝断言=抛 ActionException 且 message/hint 含锚文本——异常类非契约））：

| op | input | expect | 说明 |
|---|---|---|---|
| `command_parse` | json | 命令字段断言 / reject_argument | 坏 op+缺 op（hint 可选全列）/ quick_capture / update_plan CAS / adjust_blocks / upsert_facts 严进 / upsert_background 必填 / merge kind·delete 缺 id / shift 缺省 |

## Kotlin 侧消费指引（P2）

1. 按 `rules` 实现算法（BouncyCastle：AES-256-GCM / X25519Agreement；Argon2id 属 KEK 层不在本集）。
2. 逐 case：构造 input → 运行 → 与 expect 逐字节比对（hex）或断言同语义拒绝。
3. `seal_composite` / `ecies_seal` 需可注入 nonce（或单独暴露原语层）以做确定性对拍。
4. 新增 case：先过人工语义评审（下节），再进生成器重跑。

## 边界向量候选 → 拍板记录（2026-11-14 人工裁决，四问全推荐项，均已入集）

| # | 候选 | 拍板 | 落点 |
|---|---|---|---|
| ① | 空明文 seal（len=0） | **接受·固化现状**（封「空」无害；冻结期不加新拒绝面） | `seal_empty_ver1` + pad/seal_len ×4 |
| ②③ | 恰 512B / 511B / 513B 明文 | **全部固化**（尾注设计使全块填充无歧义） | `pad_exact_512` / `pad_len_511` / `pad_len_513` + 对应 seal_len |
| ④ | quantize busy 恰对齐桶边界 | **固化**（半开区间双端临界 → '1011'） | freemask `quantize_aligned_busy` |
| ⑤ | 信封畸形拒绝语义 | **固化现状 reject_format**，只钉「必拒」不钉异常类；升格机器可读码留迁移后 Day-2 | `envelope_missing_mac` / `envelope_bad_base64` |
| ⑥ | key_ver 不在 KeyRing | **钉 KeyRing 层 null**；调用方拒绝语义留 P2 行为清单 | `keyring_get_unknown_ver` |

后续新增边界候选仍走同门禁：AI 提候选 → 人工拍板语义 → 生成器追加。

## FSM 转移表边界候选 → 拍板记录（2026-11-14 人工裁决，四问全推荐项，均已入集 +14 case）

| # | 候选 | 拍板 | 落点 |
|---|---|---|---|
| ① | REOPEN 缺省 stepId 全量重置（含 READY 步骤） | **固化**（保守口径：密文解不开绝不漏退；READY 重置由后续事件派生恢复） | `d_reopen_default` |
| ② | PROPOSE 二次到达 / 空 skeleton（DRAFT 态）丢弃 | **固化**（幂等防御；事件仍推水位） | `d_propose_empty_draft` / `d_propose_repeat` / `c_propose_repeat` |
| ③ | 发起人操作目标步骤查无丢弃 | **固化**（PATCH_TIME/REASSIGN/MAKE_OPTIONAL/BYPASS/PRUNE 五连守卫） | `d_step_missing_guards` |
| ④ | BYPASS 点名成员均不在必答名单（kept==原名单）丢弃 | **固化** | `d_bypass_noop` |
| ⑤ | Consensus VOTE 改投覆盖旧票（✕→✓ 翻转即时计票可触发固化） | **固化**（平权重投自洽；落槌竞态由服务端 First-Commit-Wins 把关） | `c_vote_revote` |
| ⑥ | Consensus ADD_SLOT 重复候选不去重 | **固化现状**（votes 按 slotId 索引功能无危害；重复候选治理登记迁移后 Day-2） | `c_addslot_duplicate` |
| ⑦ | Consensus PST slotId 校验（≠winning_slot 丢弃 / null 宽松退回） | **固化**（双态入集） | `c_pst_slot_mismatch` / `c_pst_slot_null` |
| ⑧ | maybeExpire 仅 CONSENSUS+PROPOSED / maybeAbandon 仅 STALLED | **固化**（DIRECTIVE 携 expires 不流局；CONFIRMED 不熔断） | `c_expire_directive_immune` / `d_abandon_only_stalled` |
| ⑨ | Consensus LEAVE 于 CONFIRMED 态放行（撤票不降状态） | **固化**（人走撤票；winning_slot 已定不受影响；与 Directive 侧放行口径一致） | `c_leave_confirmed` |
| ⑩ | 骨架中性事件全族仅推水位（9 个动作 + STEP_NOTE） | **固化**（端侧中性，发起人变更由服务端权威） | `d_neutral_family` |

后续新增边界候选仍走同门禁：AI 提候选 → 人工拍板语义 → 生成器追加。

## SQLite Schema 边界候选 → 拍板记录（2026-11-14 D7 人工裁决，均已处置）

| # | 候选 | 拍板 | 落点 |
|---|---|---|---|
| ① | 外键悬空拒绝 | **固化**（引擎标准行为 + foreign_keys=ON 生产口径实证；constraint_reject 组连接口径统一显式开 FK） | `reject_fk_dangling` |
| ② | 老内核 DROP COLUMN 兜底路径 | **留 P2 行为清单**（本机不可达无法对拍，不自证；Kotlin minSdk 定策时人工构造——minSdk ≥33 无需实现） | rules.drop_column_fallback |

后续新增边界候选仍走同门禁：AI 提候选 → 人工拍板语义 → 生成器追加。

## MCP 拒绝码边界候选 → 拍板记录（2026-11-14 人工裁决，D5 全推荐项，均已固化）

| # | 候选 | 拍板 | 落点 |
| ② | 非 JSON-RPC 对象 / 缺 method → -32600 | **固化**（id=null / B3 id 回显双态） | `mcp_non_object_body` / `mcp_missing_method` |
| ③ | tools/call 缺 tool name → -32602 | **固化** | `mcp_missing_tool_name` |
| ④ | 工具面普通异常 → -32603 兜底 | **固化**（message 含异常串为参考文案，对拍只钉 code） | `mcp_internal_error` |
| ⑤ | 传输层空 token 串视为关闭鉴权 | **固化**（判定式 `token != null && isNotEmpty && key != token`） | rules.transport_guards |
| ⑥ | Origin 白名单（localhost 族任意端口，其余 403+-32600） | **固化**（MCP 规范 DNS-rebinding 防护；桌面直连不带 Origin 不受影响） | rules.transport_guards |
| ⑦ | 未知路径 404 / 非 POST 405（均 -32600） | **固化**（本服务不提供 SSE） | rules.transport_guards |

后续新增边界候选仍走同门禁：AI 提候选 → 人工拍板语义 → 生成器追加。
