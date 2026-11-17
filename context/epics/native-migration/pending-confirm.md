---
confirm: pending
format: v1
epic: native-migration
---

## native-migration · 待确认

- [ ] [2026-11-16] 设计性等价判定（锚点: todos #31 像素基线人工签存档）：`settings_page` S1/S2 与 `gallery` rejectD/rejectC 两组状态在同设备同 DPR 下逐字节全等——settings `f4fccb5c62855c9069053ba13655a17688140eb050b77e32828f4101ad139994`、gallery `957b95ee63f259c29aec02b59367d6d14d0f110c7d2d328364df6a42407909a8`（`test/golden/baseline/manifest.json` 第 8–21、311–325 行）；memory 第 15 批记为「设计性退化 / 视觉等价（Day-2 观察项）」。请裁决是否接受为设计性等价/退化、像素 diff 对这两组直接判 pass 且不再挂账。默认建议：接受（哈希全等即无视觉差异信号，且 S3/S4 瞬时态另有 D28「转交互对拍」先例）。未决前不升级为闭环、不回填 todos、不 refresh 断点。
- [ ] [2026-11-16] P4｜F9 保护区虚线框密度档 6/4dp（bandDashLen/Gap）处置：转正写回 ui-design F9 注记+StTokens/TimelineLayers 消标，还是调档（默认建议: 6/4dp 转正——Dart 基线为实线无虚线可对拍，S1 像素对拍路径不成立，密度档本质是规范驱动的人工视觉裁决）
- [x] [2026-11-16] P4｜F9 保护区虚线框密度档 6/4dp（bandDashLen/Gap）处置：转正写回 ui-design F9 注记+StTokens/TimelineLayers 消标，还是调档（已裁 2026-11-16：6/4dp 转正——Dart 基线为实线无虚线可对拍，密度档本质是规范驱动的人工视觉裁决）
