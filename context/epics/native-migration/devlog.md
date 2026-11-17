---
dev-loop: devlog
format: v1
epic: native-migration
total-merged: 20
last-merge: 2026-11-17
---
（空——2026-11-17 归并：65 条进 memory 第 22-24 批；P4 尾段清算/G2 签字/P3-P4 收口/P5 切包八件/Day-2 五件均已按主题归并；2026-11-16 前次：stage5 S1 收口等 19 条）
- [2026-11-17] [变更]: Day-2 全屏修复（用户报「app 小一圈/非全屏/大外边界」）：根因=legacy 主题（Flutter 模板 Theme.Light/Black.NoTitleBar）在 API 37 触发系统级 compat fitting——窗口满屏但绘制区内缩（状态栏 141+导航 84 黑带）；修复=①主题父级换 Theme.Material(Light).NoActionBar（values+values-night）②主题补 statusBarColor/navigationBarColor=transparent ③enableEdgeToEdge() 替换弃用的 WindowCompat.setDecorFitsSystemWindows ④根级 fillMaxSize ⑤ShiguangScaffold contentWindowInsets 清零。诊断过程=品红/绿屏颜色标记+ui-tree+视图层级三步定位（surface 满屏但绘制内缩→主题层）
- [2026-11-17] [验证]: gradlew test 全绿（core 21s+:app 15s）+真机 3B161700Y0600000 截图采样：状态栏区 (20,18,24) 与内容区同色（原 (0,0,0) 黑带消除）+底栏延伸到屏幕底（手势条无缝）
- [2026-11-17] [变更]: Day-2 全页视觉走查修复（用户报「左右没全屏」）：根因=壳层 content 对 systemGestures 横向手势保留区做 windowInsetsPadding（30dp×2），叠加页面 insetLg 16dp=46dp 大外边界——§11 拍板修订丢失点 #8：仅保留底部手势带避让，横向撤销（留痕 docs/design/schedule-app.md §11）。同轮排除假象：标题/底部选中态「卡死设置」为测试点击落手势区未点中导航项，hasRoute(qualifiedName) 实测正常（logcat route/hToday 验证），代码未改
- [2026-11-17] [验证]: 真机截图采样：清单/设置页卡片左边界 161px→56px（insetLg 16dp），三 Tab 标题/选中态跟随正确，上下左右四向满屏
- [2026-11-18] [变更]: Day-2 UI 走查：底部导航栏 80dp→64dp 紧凑版（用户评估「Tab 按钮太大」+美学/人机评审拍板）——M3 NavigationBar 默认 80dp 对 3 Tab 视觉过重无交互收益；规范先行 ui-design §3.1 补 64dp 档（图标 24dp+标签双层与 48dp 触控目标保留），代码 ShiguangAppShell NavigationBar(Modifier.height(64.dp))（该版 material3 无 height 参数走 modifier）
- [2026-11-18] [验证]: 真机 3B161700Y0600000 截图：导航栏顶边 2576px（64dp 含手势条），三 Tab 图标/标签/选中态正常，纵向内容区增益约 20dp；docs-lint ✓
- [2026-11-18] [变更]: Day-2 UI 走查第二轮（用户三反馈：图标被吞/不要字/下滑隐藏）——①图标被吞根因=64dp 栏内 M3 再垫 bottom 手势带 inset，40dp 剩余装不下药丸+图标+标签双层结构；②拍板去标签改仅图标（3 个高频通用图标识别无歧义）；③下滑隐藏/上滑重现=scroll-aware chrome（壳层 NestedScrollConnection onPreScroll 阈值 40f + AnimatedVisibility slide）——规范先行 ui-design §3.1 导航栏条目改「64dp 仅图标+下滑隐藏」
- [2026-11-18] [验证]: 真机截图两态：下滑后导航区为内容色（隐藏生效）、上滑恢复（33,31,38）栏底色；仅图标无裁切、选中药丸正常；docs-lint ✓
- [2026-11-18] [变更]: Day-2 UI 走查补遗（用户报「图标没有居中」）：64dp 仅图标栏内 M3 NavigationBar 内建 bottom 手势带 inset 把图标顶到可用区上部——windowInsets=WindowInsets(0,0,0,0) 清零后图标在 64dp 全高居中（实测图标中点 2710 vs 栏中点 2688，目检居中，与底部手势药丸无冲突）
- [2026-11-18] [验证]: 真机截图：三 Tab 图标垂直居中、无裁切、手势药丸无重叠
- [2026-11-18] [变更]: Day-2 UI 走查第三轮（用户报「三 tab 有明显黑条」）：NavigationBar 默认容器色（surfaceContainer #212126）与页面背景（#141218）形成深色断带——containerColor 改 colorScheme.background 同色，图标/选中药丸浮于背景，导航区与内容无缝
- [2026-11-18] [验证]: 真机截图采样：栏区 2576-2800 全部 (20,18,24) 与内容同色，断带消除；图标/手势药丸正常
- [2026-11-18] [变更]: Day-2 UI 走查第三轮补遗（用户澄清本意「减小三个 tab 整个区块面积」非仅图标）：64dp→48dp 总高（恰达 48dp 最小触控目标，图标 24dp 居中于 2592-2800 栏区，与 2752+ 手势药丸不重叠）——ui-design §3.1 同步改档
- [2026-11-18] [验证]: 真机截图：48dp 栏（168px）图标居中无裁切，时间轴较 80dp 版增益约 32dp 纵向空间
- [2026-11-18] [变更]: Day-2 UI 走查第四轮（用户拍板「隐藏功能进设置，默认不隐藏」）：新增 uiOnly 设置键 nav_auto_hide（默认 false=常驻）——链路四处：①SettingsKeys 注册+ALL 白名单 ②CommandHandler uiOnly 拦截集 ③ResidualHandler 布尔校验分支 ④getSettings 投影补字段（根因坑：投影是固定字段 JsonObject，新键漏 put 则壳层读恒 null——本次 UI 点不动假象即此+偶次点击抵消叠加）⑤设置页导航栏卡 Switch ⑥壳层 navBarScrollConnection 按设置开关挂载（remember(navAutoHide) 重建，关=强制常驻）
- [2026-11-18] [验证]: 真机两态：开关开+下滑=导航栏隐藏（目检），上滑=重现；开关关=下滑常驻（目检）；docs-lint ✓
- [2026-11-18] [变更]: Day-2 UI 走查第五轮（用户报「导航区没法操作：图标在按不到的区域、中间键与小横条混在一起」）——导航区重设计定版：48dp 内容高 + 手势带 = 总 footprint 72dp，恢复 NavigationBar 内建 bottom inset（清零它会让图标压进系统手势区被拦截点击——上轮 48dp 版的可按性缺陷根因），仅图标+容器色同背景+下滑隐藏设置项均保留；ui-design §3.1 三轮修订留痕
- [2026-11-18] [验证]: 真机：图标 y 2632-2663（高于手势带 2716），点清单 Tab 实测切换成功，下滑隐藏/上滑重现不回归
- [2026-11-18] [变更]: Day-2 UI 走查第六轮（用户给云闪付截图作参照「这样的」）：导航栏选中态去药丸底，纯靠图标着色区分（选中=primary、未选中=onSurfaceVariant 中性灰，NavigationBarItemDefaults.colors indicatorColor=Transparent）——ui-design §3.1 补选中态条款
- [2026-11-18] [验证]: 真机截图：日程页选中图标 primary 着色、未选中灰、无药丸底，栏与背景无缝
- [2026-11-18] [变更]: Day-2 UI 走查第七轮（用户问「导航区高度为何还那么高」→ 拍板再收一档）：72dp→56dp 总高（32dp 内容高+24dp 手势带）——触控高度豁免 §3.1 44dp 钳制（每项宽 121dp 以宽度补偿，云闪付式紧凑）；ui-design §3.1 四轮修订留痕
- [2026-11-18] [验证]: 真机：56dp 栏（196px）图标居中无裁切、点清单 Tab 实测切换成功、选中态 primary 着色正常
- [2026-11-18] [变更]: Day-2 UI 走查第八轮（用户澄清布局本意「下部抬高、上部分裁掉没用的高度」非居中）：导航栏 56dp 居中→48dp 贴底（内建 inset 清零，图标贴近手势药丸上方，上部仅余 12dp 呼吸）——ui-design §3.1 同步改档
- [2026-11-18] [验证]: 真机截图：48dp 栏图标贴底、与手势药丸无重叠、无上部留白
- [2026-11-18] [变更]: Day-2 UI 走查第五轮定版（用户点破「图标高度一直在减，但导航区总高从没降过」）：真根因=壳层 content 双扣手势带（Scaffold bottomBar padding 已含 24dp 手势带，再叠 windowInsetsPadding(Bottom)）→ 内容区底界恒定、历轮栏高缩减全部无效——已移除重复扣减；同时恢复文字标签（用户拍板「加文字」），栏定版 64dp 图标+文字（内建 inset 清零后双层无裁切），选中态着色无药丸不变
- [2026-11-18] [验证]: 真机：标签完整显示无裁切、内容区底界实际下移（时间轴多见约一行）、下滑隐藏/上滑重现正常；docs-lint ✓
- [2026-11-18] [变更]: Day-2 UI 走查定版（用户拍板「加文字」+云闪付同级高度）：导航栏定版 72dp 图标+文字标签（内建 inset 保留，双层悬于手势带上方不压药丸）+选中态着色无药丸+双扣手势带已修——ui-design §3.1 同步
- [2026-11-18] [验证]: 真机：日程页 72dp 栏图标+标签完整、选中 primary 着色、无药丸、与背景无缝
- [2026-11-18] [变更]: Day-2 UI 走查残留修复（用户报「清单跳转页面有残留」）：根因=清单/协同族子路由（PlanDetail/PlanBackground/PlanFacts/Collab*）未归所属 Tab——底部选中与标题恒落「设置」else 分支（带参路由 hasRoute 精确匹配失效）；修复=壳层路由归族改前缀匹配（Plan*/Collab* 前缀归族，主 Tab 精确匹配），并拆分 isPlans（底部选中族）与 isPlansTab（Tab 级修饰/FAB）两个语义；另清除测试残留（外观还原跟随系统、nav_auto_hide 复位默认关）
- [2026-11-18] [验证]: 真机截图：验收走查计划/浮层走查计划详情各自正确渲染、随行凭证子页底部清单选中正确、清单→详情→返回 diff 仅状态栏时钟、外观/导航栏开关复位；docs-lint ✓
- [2026-11-18] [变更]: Day-2 UI 走查动效定版（用户报「跳转不自然」）：新增导航过渡档 220ms ease-out 交叉淡入淡出（StMotion.navTransitionMs）——NavHost 页面进出/子路由推入返回统一走该过渡；壳层大标题随子路由进出做淡出+垂直收展（AnimatedVisibility）——ui-design §3.3 动效表新增档留痕
- [2026-11-18] [验证]: 真机：清单页与详情页切换平滑（无硬切）、详情页单标题无堆叠、底部选中正确
