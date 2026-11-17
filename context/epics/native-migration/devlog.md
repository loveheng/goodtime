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
