// This is a generated file - do not edit.
//
// Generated from relay_protocol.proto.

// @dart = 3.3

// ignore_for_file: annotate_overrides, camel_case_types, comment_references
// ignore_for_file: constant_identifier_names
// ignore_for_file: curly_braces_in_flow_control_structures
// ignore_for_file: deprecated_member_use_from_same_package, library_prefixes
// ignore_for_file: non_constant_identifier_names, prefer_relative_imports

import 'dart:core' as $core;

import 'package:protobuf/protobuf.dart' as $pb;

class ActionType extends $pb.ProtobufEnum {
  /// —— 排程协商（FSM）——
  static const ActionType PROPOSE =
      ActionType._(0, _omitEnumNames ? '' : 'PROPOSE');
  static const ActionType ACCEPT =
      ActionType._(1, _omitEnumNames ? '' : 'ACCEPT');
  static const ActionType REJECT =
      ActionType._(2, _omitEnumNames ? '' : 'REJECT');
  static const ActionType PATCH_TIME =
      ActionType._(3, _omitEnumNames ? '' : 'PATCH_TIME');
  static const ActionType REASSIGN =
      ActionType._(4, _omitEnumNames ? '' : 'REASSIGN');
  static const ActionType MAKE_OPTIONAL =
      ActionType._(5, _omitEnumNames ? '' : 'MAKE_OPTIONAL');
  static const ActionType PRUNE =
      ActionType._(6, _omitEnumNames ? '' : 'PRUNE');
  static const ActionType CANCEL =
      ActionType._(7, _omitEnumNames ? '' : 'CANCEL');
  static const ActionType REOPEN =
      ActionType._(8, _omitEnumNames ? '' : 'REOPEN');
  static const ActionType VOTE = ActionType._(9, _omitEnumNames ? '' : 'VOTE');
  static const ActionType ADD_SLOT =
      ActionType._(10, _omitEnumNames ? '' : 'ADD_SLOT');
  static const ActionType FORCE_FINALIZE =
      ActionType._(11, _omitEnumNames ? '' : 'FORCE_FINALIZE');
  static const ActionType NUDGE =
      ActionType._(12, _omitEnumNames ? '' : 'NUDGE');
  static const ActionType BYPASS =
      ActionType._(13, _omitEnumNames ? '' : 'BYPASS');
  static const ActionType REKEY =
      ActionType._(14, _omitEnumNames ? '' : 'REKEY');
  static const ActionType MEMBER_REMOVED =
      ActionType._(15, _omitEnumNames ? '' : 'MEMBER_REMOVED');
  static const ActionType TRANSFER_INITIATOR =
      ActionType._(16, _omitEnumNames ? '' : 'TRANSFER_INITIATOR');
  static const ActionType BRIEFING_UPDATE =
      ActionType._(17, _omitEnumNames ? '' : 'BRIEFING_UPDATE');
  static const ActionType STEP_NOTE =
      ActionType._(18, _omitEnumNames ? '' : 'STEP_NOTE');

  /// —— 事实同步（2026-10-09 收敛进 bundle 事件流）——
  /// ATTACH_FACT 语义（v0.1.1 定调，v0.1.2 补 target_step_ref）：FSM 零影响（仅递增 seq_id + 广播，不改 Quorum、不触发 STALLED/CONFIRMED）
  ///   + 全员平权提交（内容共享非日程调度，不受 Directive 发起人独占限制）；端侧收到即物化进本地 artifacts。
  ///   解密后的 artifact payload 可携带可选 `target_step_ref`（bundle step_uid），供 CONFIRMED 下沉时精准绑定 block（详见契约文档）。
  static const ActionType ATTACH_FACT =
      ActionType._(19, _omitEnumNames ? '' : 'ATTACH_FACT');
  static const ActionType UPDATE_FACT =
      ActionType._(20, _omitEnumNames ? '' : 'UPDATE_FACT');
  static const ActionType DETACH_FACT =
      ActionType._(21, _omitEnumNames ? '' : 'DETACH_FACT');

  /// —— 拓扑可用性 ——
  static const ActionType FREEMASK_QUERY =
      ActionType._(22, _omitEnumNames ? '' : 'FREEMASK_QUERY');
  static const ActionType FREEMASK_RESPONSE =
      ActionType._(23, _omitEnumNames ? '' : 'FREEMASK_RESPONSE');

  /// —— 信令 / 生命周期 ——
  static const ActionType DELIVERY_ACK =
      ActionType._(24, _omitEnumNames ? '' : 'DELIVERY_ACK');
  static const ActionType CLIENT_ACK =
      ActionType._(25, _omitEnumNames ? '' : 'CLIENT_ACK');
  static const ActionType PING = ActionType._(26, _omitEnumNames ? '' : 'PING');
  static const ActionType ADD_MEMBER =
      ActionType._(27, _omitEnumNames ? '' : 'ADD_MEMBER');

  /// —— 敲定后生命周期（2026-10-25 拍板 §4.3/§4.5；终态门禁白名单仅有的两个非终态动作）——
  static const ActionType PATCH_STEP_TIME =
      ActionType._(28, _omitEnumNames ? '' : 'PATCH_STEP_TIME');
  static const ActionType LEAVE =
      ActionType._(29, _omitEnumNames ? '' : 'LEAVE');

  static const $core.List<ActionType> values = <ActionType>[
    PROPOSE,
    ACCEPT,
    REJECT,
    PATCH_TIME,
    REASSIGN,
    MAKE_OPTIONAL,
    PRUNE,
    CANCEL,
    REOPEN,
    VOTE,
    ADD_SLOT,
    FORCE_FINALIZE,
    NUDGE,
    BYPASS,
    REKEY,
    MEMBER_REMOVED,
    TRANSFER_INITIATOR,
    BRIEFING_UPDATE,
    STEP_NOTE,
    ATTACH_FACT,
    UPDATE_FACT,
    DETACH_FACT,
    FREEMASK_QUERY,
    FREEMASK_RESPONSE,
    DELIVERY_ACK,
    CLIENT_ACK,
    PING,
    ADD_MEMBER,
    PATCH_STEP_TIME,
    LEAVE,
  ];

  static final $core.List<ActionType?> _byValue =
      $pb.ProtobufEnum.$_initByValueList(values, 29);
  static ActionType? valueOf($core.int value) =>
      value < 0 || value >= _byValue.length ? null : _byValue[value];

  const ActionType._(super.value, super.name);
}

const $core.bool _omitEnumNames =
    $core.bool.fromEnvironment('protobuf.omit_enum_names');
