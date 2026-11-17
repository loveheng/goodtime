---
status: active
updated: 2026-11-16
---

# 行为清单 · account（协同账号页，协作九页施工输入——C 档单元）

> §9.1.6 模板产出（2026-11-16 抽取）。抽取源 = `lib/ui/account_page.dart`（337 行全文）+ `lib/collab/account.dart`（AccountSession 三态+校验纯函数）+ ui-pages §8.4（C 档 2 态）+ Kotlin 就位面（AccountSession.kt 全量平移：isValidUsername/isValidPassword/AccountStatus/register/login/logout/changePassword/restore/ensureRestored——CollabAccountSessionTest 8 例承载）。

## 七字段

| 字段 | 内容 |
|---|---|
| 交互意图 | 协同账号页（账号体系 Plan A）：登录/注册/改密/退出；用户只见「用户名+密码」，E2EE 密钥端侧自动托管零密钥概念；协同域登录态门禁统一引导落点 |
| 触发与阈值 | 点按类：SegmentedButton 登录/注册切换（切注册清 error）；「登录/注册并登录」FilledButton（busy 禁用+18 转圈）；提交门禁=isValidUsername(trim+toLowerCase)（3~32 小写字母数字下划线）→isValidPassword（≥8 位）→注册态两次一致；「修改密码」OutlinedButton（password_outlined 18）→AlertDialog 双 obscure 输入；「退出登录」OutlinedButton（logout 18）→AlertDialog 确认。无手势数值阈值 |
| 状态空间 | C 档 S1–S2：S1=内容展示态（ loggedIn 卡片：account_circle 40+「已登录：{username}」16sp w600+「协同日程与好友数据走这个账号」12sp+双钮+尾注）；S2=无数据/登录表单态（三态门禁 unknown=整页转圈/loggedOut=表单）；表单三字段（用户名 hint「小写字母、数字或下划线（3~32 位）」/密码 obscure hint「至少 8 位」+errorText/注册态再输一次密码 obscure）+尾注按注册态二选一。B 档断连态（S3/S4）不适用本页 |
| 落库命令与拒绝码 | 零本地 ScheduleCommand——AccountSession.register/login/logout/changePassword；错误码映射逐字（errorCodeOf）：USER_OR_PASSWORD_INVALID=「用户名或密码错误」（**防账号枚举统一口径，绝不区分两种原因**）/USERNAME_EXISTS=「该用户名已被占用，换一个试试」/USERNAME_INVALID=「用户名需为 3~32 位小写字母、数字或下划线」/PASSWORD_TOO_WEAK=「密码至少 8 位」/PASSWORD_MISMATCH=「当前密码不正确」/LOGIN_THROTTLED=retry_after_sec 派生（null/<=0→「尝试太频繁，请稍后再试」；>=60→「尝试太频繁，账号已临时锁定，约 {ceil(秒/60)} 分钟后再试」；否则→「尝试太频繁，请 {sec} 秒后再试」）/RATE_LIMITED=「操作太频繁，稍后再试」/CHALLENGE_INVALID=「注册会话已过期，请重试」/TOKEN_REVOKED|TOKEN_STALE=「登录态已过期，请重新登录」/其余=「操作失败，稍后再试」；DioException=「暂时连不上中继，稍后再试」 |
| 反馈形式 | 登录/注册成功：SnackBar（默认样式）「已登录」/「已注册并登录」+视图随监听切 loggedIn；改密成功：SnackBar「密码已更新，请重新登录」（改密即踢会话契约 §3.4——session 转未登录视图回表单）；改密失败：401→「当前密码不正确」/ArgumentError→「密码至少 8 位」/DioException→「暂时连不上中继，稍后再试」/其他 RelayAuthException→「修改失败，稍后再试」（SnackBar）；退出成功：SnackBar「已退出登录」；表单校验错误=password 字段 errorText 内联（非 SnackBar） |
| 黄金向量指针 | 零新向量（传输层行为=CollabAuthApiTest 8 例承载；校验纯函数=isValidUsername/isValidPassword JVM 直测随施工轮钉住）；time_basis 无涉 |
| 像素基线指针 | C 档 account_page 2 态——**manifest 已有 1/2 组**（收口轮核对补齐 S2 态与态位对齐） |

## §A 数据装配

| 行为 | 口径 |
|---|---|
| 三态门禁 | ListenableBuilder 监听 session.status：unknown=转圈/loggedIn=卡片视图/loggedOut=表单；冷启动 initState 调 session.restore()（读安全区；构造不触碰平台插件纪律）——Kotlin=ensureRestored/restore 已平移 |
| 提交链 | trim+toLowerCase 后校验→register（注册自动登录）或 login→busy 复位视图随监听刷新 |
| 改密 | 对话框双输入（当前密码/新密码至少 8 位，均 obscure）确认后 changePassword(old,new)；成功踢会话 |
| 退出 | 对话框「只退出账号，本地日程与协同数据都保留」确认后 logout |
| Kotlin 装配 | AccountSession 三态 StateFlow 已平移（Compose=collectAsState 替代 ListenableBuilder）；register 返回 RegistrationResult；restoreIdentityFromVault 金库原子重封已平移（本页 UI 不暴露金库入口——现状零入口） |

## §B 骨架与组件

| 行为 | 口径 |
|---|---|
| AppBar | 「协同账号」 |
| 表单 | ListView padding 16：SegmentedButton「登录」/「注册」+16+用户名 TextField（Outline）+12+密码 TextField（obscure+errorText）+注册态 12+确认密码+16+提交钮（「登录」/「注册并登录」）+16+尾注 12sp（注册=「注册即创建协同账号：用户名是好友找到你的入口；协同密钥在本机自动生成，密码只用来登录。」/登录=「登录后即可与好友协同日程；协同密钥在本机自动管理，无需记忆。」） |
| 卡片视图 | Card padding 16：头像行+12+「修改密码」OutlinedButton+8+「退出登录」OutlinedButton+16+尾注 12sp（「退出登录只清除本机的登录会话，日程与协同数据都保留在本机；修改密码不影响协同密钥与历史消息。」） |
| 对话框 | 改密=「修改密码」双 obscure 字段+取消/「确认修改」；退出=「退出登录」正文「只退出账号，本地日程与协同数据都保留」+取消/「退出」 |
| Kotlin 骨架 | ShiguangScaffold+TopAppBar；三态=when(status) 单点分流；表单校验内联 errorText；busy 禁用全字段（enabled=!busy） |

## §C 架构接线

- 路由：`Account`（routing_map 既有 data object 零参；session 经容器注入）。
- VM/装配：AccountSession 由容器持有（协作栈装配轮入 AppContainer）；本页 UI 直收会话状态流（collectAsState）——三态已是 StateFlow，禁 Composable 内 new。
- 对拍发现 F1（loggedIn 尾注 username 空串）：session.username 为 null 时显示「已登录：」空尾——现状口径保持（unknown 三态门禁下短暂存在，收口轮观察项）。
- 对拍发现 F2（restore 副作用位置）：initState 直接调 restore()——P4 挂账条目「build 中副作用改 LaunchedEffect 惯用法」同族，Kotlin 施工=LaunchedEffect(Unit) 承载 restore，禁构造/init 直调。
