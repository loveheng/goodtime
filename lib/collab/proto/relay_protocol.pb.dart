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

import 'package:fixnum/fixnum.dart' as $fixnum;
import 'package:protobuf/protobuf.dart' as $pb;

import 'relay_protocol.pbenum.dart';

export 'package:protobuf/protobuf.dart' show GeneratedMessageGenericExtensions;

export 'relay_protocol.pbenum.dart';

/// ---------------------------------------------------------------------------
/// 顶层信封（bundle 事件流）
/// ---------------------------------------------------------------------------
class Envelope extends $pb.GeneratedMessage {
  factory Envelope({
    $core.String? bundleId,
    ActionType? action,
    $fixnum.Int64? seqId,
    $core.String? senderHint,
    $core.List<$core.int>? cipher,
    $core.String? clientMsgId,
    $fixnum.Int64? clientTs,
    $core.String? topologyRef,
    $core.int? keyVer,
    $core.Iterable<MemberKeyEnvelope>? memberKeyEnvelopes,
  }) {
    final result = Envelope._();
    if (bundleId != null) result.bundleId = bundleId;
    if (action != null) result.action = action;
    if (seqId != null) result.seqId = seqId;
    if (senderHint != null) result.senderHint = senderHint;
    if (cipher != null) result.cipher = cipher;
    if (clientMsgId != null) result.clientMsgId = clientMsgId;
    if (clientTs != null) result.clientTs = clientTs;
    if (topologyRef != null) result.topologyRef = topologyRef;
    if (keyVer != null) result.keyVer = keyVer;
    if (memberKeyEnvelopes != null)
      result.memberKeyEnvelopes.addAll(memberKeyEnvelopes);
    return result;
  }

  Envelope._();

  factory Envelope.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      Envelope()..mergeFromBuffer(data, registry);
  factory Envelope.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      Envelope()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'Envelope',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'shiguang.relay.v1'),
      createEmptyInstance: Envelope.$_createMessage)
    ..aOS(1, _omitFieldNames ? '' : 'bundleId')
    ..aE<ActionType>(2, _omitFieldNames ? '' : 'action',
        enumValues: ActionType.values)
    ..aInt64(3, _omitFieldNames ? '' : 'seqId')
    ..aOS(4, _omitFieldNames ? '' : 'senderHint')
    ..a<$core.List<$core.int>>(
        5, _omitFieldNames ? '' : 'cipher', $pb.PbFieldType.OY)
    ..aOS(6, _omitFieldNames ? '' : 'clientMsgId')
    ..aInt64(7, _omitFieldNames ? '' : 'clientTs')
    ..aOS(8, _omitFieldNames ? '' : 'topologyRef')
    ..aI(9, _omitFieldNames ? '' : 'keyVer')
    ..pPM<MemberKeyEnvelope>(10, _omitFieldNames ? '' : 'memberKeyEnvelopes',
        subBuilder: MemberKeyEnvelope.$_createMessage)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  Envelope clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  Envelope copyWith(void Function(Envelope) updates) =>
      super.copyWith((message) => updates(message as Envelope)) as Envelope;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use Envelope() / Envelope.new instead')
  static Envelope create() => Envelope._();
  static $pb.GeneratedMessage $_createMessage() => Envelope._();
  @$core.override
  Envelope createEmptyInstance() => Envelope._();
  @$core.pragma('dart2js:noInline')
  static Envelope getDefault() => _defaultInstance ??=
      $pb.GeneratedMessage.$_defaultFor<Envelope>(Envelope.$_createMessage);
  static Envelope? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get bundleId => $_getSZ(0);
  @$pb.TagNumber(1)
  set bundleId($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasBundleId() => $_has(0);
  @$pb.TagNumber(1)
  void clearBundleId() => $_clearField(1);

  @$pb.TagNumber(2)
  ActionType get action => $_getN(1);
  @$pb.TagNumber(2)
  set action(ActionType value) => $_setField(2, value);
  @$pb.TagNumber(2)
  $core.bool hasAction() => $_has(1);
  @$pb.TagNumber(2)
  void clearAction() => $_clearField(2);

  @$pb.TagNumber(3)
  $fixnum.Int64 get seqId => $_getI64(2);
  @$pb.TagNumber(3)
  set seqId($fixnum.Int64 value) => $_setInt64(2, value);
  @$pb.TagNumber(3)
  $core.bool hasSeqId() => $_has(2);
  @$pb.TagNumber(3)
  void clearSeqId() => $_clearField(3);

  @$pb.TagNumber(4)
  $core.String get senderHint => $_getSZ(3);
  @$pb.TagNumber(4)
  set senderHint($core.String value) => $_setString(3, value);
  @$pb.TagNumber(4)
  $core.bool hasSenderHint() => $_has(3);
  @$pb.TagNumber(4)
  void clearSenderHint() => $_clearField(4);

  @$pb.TagNumber(5)
  $core.List<$core.int> get cipher => $_getN(4);
  @$pb.TagNumber(5)
  set cipher($core.List<$core.int> value) => $_setBytes(4, value);
  @$pb.TagNumber(5)
  $core.bool hasCipher() => $_has(4);
  @$pb.TagNumber(5)
  void clearCipher() => $_clearField(5);

  @$pb.TagNumber(6)
  $core.String get clientMsgId => $_getSZ(5);
  @$pb.TagNumber(6)
  set clientMsgId($core.String value) => $_setString(5, value);
  @$pb.TagNumber(6)
  $core.bool hasClientMsgId() => $_has(5);
  @$pb.TagNumber(6)
  void clearClientMsgId() => $_clearField(6);

  @$pb.TagNumber(7)
  $fixnum.Int64 get clientTs => $_getI64(6);
  @$pb.TagNumber(7)
  set clientTs($fixnum.Int64 value) => $_setInt64(6, value);
  @$pb.TagNumber(7)
  $core.bool hasClientTs() => $_has(6);
  @$pb.TagNumber(7)
  void clearClientTs() => $_clearField(7);

  @$pb.TagNumber(8)
  $core.String get topologyRef => $_getSZ(7);
  @$pb.TagNumber(8)
  set topologyRef($core.String value) => $_setString(7, value);
  @$pb.TagNumber(8)
  $core.bool hasTopologyRef() => $_has(7);
  @$pb.TagNumber(8)
  void clearTopologyRef() => $_clearField(8);

  @$pb.TagNumber(9)
  $core.int get keyVer => $_getIZ(8);
  @$pb.TagNumber(9)
  set keyVer($core.int value) => $_setSignedInt32(8, value);
  @$pb.TagNumber(9)
  $core.bool hasKeyVer() => $_has(8);
  @$pb.TagNumber(9)
  void clearKeyVer() => $_clearField(9);

  @$pb.TagNumber(10)
  $pb.PbList<MemberKeyEnvelope> get memberKeyEnvelopes => $_getList(9);
}

/// 成员专属 K_bundle 信封（X3DH 包裹，服务端纯盲；服务端同事务落 bundle_members 表）
class MemberKeyEnvelope extends $pb.GeneratedMessage {
  factory MemberKeyEnvelope({
    $core.String? recipientUserId,
    $core.List<$core.int>? wrappedKey,
    $core.int? keyVer,
  }) {
    final result = MemberKeyEnvelope._();
    if (recipientUserId != null) result.recipientUserId = recipientUserId;
    if (wrappedKey != null) result.wrappedKey = wrappedKey;
    if (keyVer != null) result.keyVer = keyVer;
    return result;
  }

  MemberKeyEnvelope._();

  factory MemberKeyEnvelope.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      MemberKeyEnvelope()..mergeFromBuffer(data, registry);
  factory MemberKeyEnvelope.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      MemberKeyEnvelope()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'MemberKeyEnvelope',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'shiguang.relay.v1'),
      createEmptyInstance: MemberKeyEnvelope.$_createMessage)
    ..aOS(1, _omitFieldNames ? '' : 'recipientUserId')
    ..a<$core.List<$core.int>>(
        2, _omitFieldNames ? '' : 'wrappedKey', $pb.PbFieldType.OY)
    ..aI(3, _omitFieldNames ? '' : 'keyVer')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  MemberKeyEnvelope clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  MemberKeyEnvelope copyWith(void Function(MemberKeyEnvelope) updates) =>
      super.copyWith((message) => updates(message as MemberKeyEnvelope))
          as MemberKeyEnvelope;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use MemberKeyEnvelope() / MemberKeyEnvelope.new instead')
  static MemberKeyEnvelope create() => MemberKeyEnvelope._();
  static $pb.GeneratedMessage $_createMessage() => MemberKeyEnvelope._();
  @$core.override
  MemberKeyEnvelope createEmptyInstance() => MemberKeyEnvelope._();
  @$core.pragma('dart2js:noInline')
  static MemberKeyEnvelope getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<MemberKeyEnvelope>(
          MemberKeyEnvelope.$_createMessage);
  static MemberKeyEnvelope? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get recipientUserId => $_getSZ(0);
  @$pb.TagNumber(1)
  set recipientUserId($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasRecipientUserId() => $_has(0);
  @$pb.TagNumber(1)
  void clearRecipientUserId() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.List<$core.int> get wrappedKey => $_getN(1);
  @$pb.TagNumber(2)
  set wrappedKey($core.List<$core.int> value) => $_setBytes(1, value);
  @$pb.TagNumber(2)
  $core.bool hasWrappedKey() => $_has(1);
  @$pb.TagNumber(2)
  void clearWrappedKey() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.int get keyVer => $_getIZ(2);
  @$pb.TagNumber(3)
  set keyVer($core.int value) => $_setSignedInt32(2, value);
  @$pb.TagNumber(3)
  $core.bool hasKeyVer() => $_has(2);
  @$pb.TagNumber(3)
  void clearKeyVer() => $_clearField(3);
}

/// ---------------------------------------------------------------------------
/// 鉴权（双 Token）— 详见 collab-schedule-draft.md §1.4
/// ---------------------------------------------------------------------------
class AuthChallenge extends $pb.GeneratedMessage {
  factory AuthChallenge({
    $core.String? nonce,
    $core.String? powChallenge,
    $core.int? powDifficulty,
    $fixnum.Int64? expiresAt,
  }) {
    final result = AuthChallenge._();
    if (nonce != null) result.nonce = nonce;
    if (powChallenge != null) result.powChallenge = powChallenge;
    if (powDifficulty != null) result.powDifficulty = powDifficulty;
    if (expiresAt != null) result.expiresAt = expiresAt;
    return result;
  }

  AuthChallenge._();

  factory AuthChallenge.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      AuthChallenge()..mergeFromBuffer(data, registry);
  factory AuthChallenge.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      AuthChallenge()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'AuthChallenge',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'shiguang.relay.v1'),
      createEmptyInstance: AuthChallenge.$_createMessage)
    ..aOS(1, _omitFieldNames ? '' : 'nonce')
    ..aOS(2, _omitFieldNames ? '' : 'powChallenge')
    ..aI(3, _omitFieldNames ? '' : 'powDifficulty')
    ..aInt64(4, _omitFieldNames ? '' : 'expiresAt')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  AuthChallenge clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  AuthChallenge copyWith(void Function(AuthChallenge) updates) =>
      super.copyWith((message) => updates(message as AuthChallenge))
          as AuthChallenge;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use AuthChallenge() / AuthChallenge.new instead')
  static AuthChallenge create() => AuthChallenge._();
  static $pb.GeneratedMessage $_createMessage() => AuthChallenge._();
  @$core.override
  AuthChallenge createEmptyInstance() => AuthChallenge._();
  @$core.pragma('dart2js:noInline')
  static AuthChallenge getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<AuthChallenge>(
          AuthChallenge.$_createMessage);
  static AuthChallenge? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get nonce => $_getSZ(0);
  @$pb.TagNumber(1)
  set nonce($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasNonce() => $_has(0);
  @$pb.TagNumber(1)
  void clearNonce() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get powChallenge => $_getSZ(1);
  @$pb.TagNumber(2)
  set powChallenge($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasPowChallenge() => $_has(1);
  @$pb.TagNumber(2)
  void clearPowChallenge() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.int get powDifficulty => $_getIZ(2);
  @$pb.TagNumber(3)
  set powDifficulty($core.int value) => $_setSignedInt32(2, value);
  @$pb.TagNumber(3)
  $core.bool hasPowDifficulty() => $_has(2);
  @$pb.TagNumber(3)
  void clearPowDifficulty() => $_clearField(3);

  @$pb.TagNumber(4)
  $fixnum.Int64 get expiresAt => $_getI64(3);
  @$pb.TagNumber(4)
  set expiresAt($fixnum.Int64 value) => $_setInt64(3, value);
  @$pb.TagNumber(4)
  $core.bool hasExpiresAt() => $_has(3);
  @$pb.TagNumber(4)
  void clearExpiresAt() => $_clearField(4);
}

class RegisterRequest extends $pb.GeneratedMessage {
  factory RegisterRequest({
    $core.String? userId,
    $core.List<$core.int>? identityKeyPub,
    $core.List<$core.int>? signedPrekeyPub,
    $core.List<$core.int>? signedPrekeySig,
    $core.String? pushToken,
    $core.List<$core.int>? encryptedProfile,
    $core.List<$core.int>? signedPop,
    $core.String? powSolution,
  }) {
    final result = RegisterRequest._();
    if (userId != null) result.userId = userId;
    if (identityKeyPub != null) result.identityKeyPub = identityKeyPub;
    if (signedPrekeyPub != null) result.signedPrekeyPub = signedPrekeyPub;
    if (signedPrekeySig != null) result.signedPrekeySig = signedPrekeySig;
    if (pushToken != null) result.pushToken = pushToken;
    if (encryptedProfile != null) result.encryptedProfile = encryptedProfile;
    if (signedPop != null) result.signedPop = signedPop;
    if (powSolution != null) result.powSolution = powSolution;
    return result;
  }

  RegisterRequest._();

  factory RegisterRequest.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      RegisterRequest()..mergeFromBuffer(data, registry);
  factory RegisterRequest.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      RegisterRequest()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'RegisterRequest',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'shiguang.relay.v1'),
      createEmptyInstance: RegisterRequest.$_createMessage)
    ..aOS(1, _omitFieldNames ? '' : 'userId')
    ..a<$core.List<$core.int>>(
        2, _omitFieldNames ? '' : 'identityKeyPub', $pb.PbFieldType.OY)
    ..a<$core.List<$core.int>>(
        3, _omitFieldNames ? '' : 'signedPrekeyPub', $pb.PbFieldType.OY)
    ..a<$core.List<$core.int>>(
        4, _omitFieldNames ? '' : 'signedPrekeySig', $pb.PbFieldType.OY)
    ..aOS(5, _omitFieldNames ? '' : 'pushToken')
    ..a<$core.List<$core.int>>(
        6, _omitFieldNames ? '' : 'encryptedProfile', $pb.PbFieldType.OY)
    ..a<$core.List<$core.int>>(
        7, _omitFieldNames ? '' : 'signedPop', $pb.PbFieldType.OY)
    ..aOS(8, _omitFieldNames ? '' : 'powSolution')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  RegisterRequest clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  RegisterRequest copyWith(void Function(RegisterRequest) updates) =>
      super.copyWith((message) => updates(message as RegisterRequest))
          as RegisterRequest;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use RegisterRequest() / RegisterRequest.new instead')
  static RegisterRequest create() => RegisterRequest._();
  static $pb.GeneratedMessage $_createMessage() => RegisterRequest._();
  @$core.override
  RegisterRequest createEmptyInstance() => RegisterRequest._();
  @$core.pragma('dart2js:noInline')
  static RegisterRequest getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<RegisterRequest>(
          RegisterRequest.$_createMessage);
  static RegisterRequest? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get userId => $_getSZ(0);
  @$pb.TagNumber(1)
  set userId($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasUserId() => $_has(0);
  @$pb.TagNumber(1)
  void clearUserId() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.List<$core.int> get identityKeyPub => $_getN(1);
  @$pb.TagNumber(2)
  set identityKeyPub($core.List<$core.int> value) => $_setBytes(1, value);
  @$pb.TagNumber(2)
  $core.bool hasIdentityKeyPub() => $_has(1);
  @$pb.TagNumber(2)
  void clearIdentityKeyPub() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.List<$core.int> get signedPrekeyPub => $_getN(2);
  @$pb.TagNumber(3)
  set signedPrekeyPub($core.List<$core.int> value) => $_setBytes(2, value);
  @$pb.TagNumber(3)
  $core.bool hasSignedPrekeyPub() => $_has(2);
  @$pb.TagNumber(3)
  void clearSignedPrekeyPub() => $_clearField(3);

  @$pb.TagNumber(4)
  $core.List<$core.int> get signedPrekeySig => $_getN(3);
  @$pb.TagNumber(4)
  set signedPrekeySig($core.List<$core.int> value) => $_setBytes(3, value);
  @$pb.TagNumber(4)
  $core.bool hasSignedPrekeySig() => $_has(3);
  @$pb.TagNumber(4)
  void clearSignedPrekeySig() => $_clearField(4);

  @$pb.TagNumber(5)
  $core.String get pushToken => $_getSZ(4);
  @$pb.TagNumber(5)
  set pushToken($core.String value) => $_setString(4, value);
  @$pb.TagNumber(5)
  $core.bool hasPushToken() => $_has(4);
  @$pb.TagNumber(5)
  void clearPushToken() => $_clearField(5);

  @$pb.TagNumber(6)
  $core.List<$core.int> get encryptedProfile => $_getN(5);
  @$pb.TagNumber(6)
  set encryptedProfile($core.List<$core.int> value) => $_setBytes(5, value);
  @$pb.TagNumber(6)
  $core.bool hasEncryptedProfile() => $_has(5);
  @$pb.TagNumber(6)
  void clearEncryptedProfile() => $_clearField(6);

  @$pb.TagNumber(7)
  $core.List<$core.int> get signedPop => $_getN(6);
  @$pb.TagNumber(7)
  set signedPop($core.List<$core.int> value) => $_setBytes(6, value);
  @$pb.TagNumber(7)
  $core.bool hasSignedPop() => $_has(6);
  @$pb.TagNumber(7)
  void clearSignedPop() => $_clearField(7);

  @$pb.TagNumber(8)
  $core.String get powSolution => $_getSZ(7);
  @$pb.TagNumber(8)
  set powSolution($core.String value) => $_setString(7, value);
  @$pb.TagNumber(8)
  $core.bool hasPowSolution() => $_has(7);
  @$pb.TagNumber(8)
  void clearPowSolution() => $_clearField(8);
}

class LoginRequest extends $pb.GeneratedMessage {
  factory LoginRequest({
    $core.String? userId,
    $core.List<$core.int>? identityKeyPub,
    $core.List<$core.int>? signedPop,
    $core.String? powSolution,
  }) {
    final result = LoginRequest._();
    if (userId != null) result.userId = userId;
    if (identityKeyPub != null) result.identityKeyPub = identityKeyPub;
    if (signedPop != null) result.signedPop = signedPop;
    if (powSolution != null) result.powSolution = powSolution;
    return result;
  }

  LoginRequest._();

  factory LoginRequest.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      LoginRequest()..mergeFromBuffer(data, registry);
  factory LoginRequest.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      LoginRequest()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'LoginRequest',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'shiguang.relay.v1'),
      createEmptyInstance: LoginRequest.$_createMessage)
    ..aOS(1, _omitFieldNames ? '' : 'userId')
    ..a<$core.List<$core.int>>(
        2, _omitFieldNames ? '' : 'identityKeyPub', $pb.PbFieldType.OY)
    ..a<$core.List<$core.int>>(
        3, _omitFieldNames ? '' : 'signedPop', $pb.PbFieldType.OY)
    ..aOS(4, _omitFieldNames ? '' : 'powSolution')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  LoginRequest clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  LoginRequest copyWith(void Function(LoginRequest) updates) =>
      super.copyWith((message) => updates(message as LoginRequest))
          as LoginRequest;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use LoginRequest() / LoginRequest.new instead')
  static LoginRequest create() => LoginRequest._();
  static $pb.GeneratedMessage $_createMessage() => LoginRequest._();
  @$core.override
  LoginRequest createEmptyInstance() => LoginRequest._();
  @$core.pragma('dart2js:noInline')
  static LoginRequest getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<LoginRequest>(
          LoginRequest.$_createMessage);
  static LoginRequest? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get userId => $_getSZ(0);
  @$pb.TagNumber(1)
  set userId($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasUserId() => $_has(0);
  @$pb.TagNumber(1)
  void clearUserId() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.List<$core.int> get identityKeyPub => $_getN(1);
  @$pb.TagNumber(2)
  set identityKeyPub($core.List<$core.int> value) => $_setBytes(1, value);
  @$pb.TagNumber(2)
  $core.bool hasIdentityKeyPub() => $_has(1);
  @$pb.TagNumber(2)
  void clearIdentityKeyPub() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.List<$core.int> get signedPop => $_getN(2);
  @$pb.TagNumber(3)
  set signedPop($core.List<$core.int> value) => $_setBytes(2, value);
  @$pb.TagNumber(3)
  $core.bool hasSignedPop() => $_has(2);
  @$pb.TagNumber(3)
  void clearSignedPop() => $_clearField(3);

  @$pb.TagNumber(4)
  $core.String get powSolution => $_getSZ(3);
  @$pb.TagNumber(4)
  set powSolution($core.String value) => $_setString(3, value);
  @$pb.TagNumber(4)
  $core.bool hasPowSolution() => $_has(3);
  @$pb.TagNumber(4)
  void clearPowSolution() => $_clearField(4);
}

class AuthResponse extends $pb.GeneratedMessage {
  factory AuthResponse({
    $core.String? accessToken,
    $core.String? refreshToken,
    $fixnum.Int64? accessExpiresAt,
  }) {
    final result = AuthResponse._();
    if (accessToken != null) result.accessToken = accessToken;
    if (refreshToken != null) result.refreshToken = refreshToken;
    if (accessExpiresAt != null) result.accessExpiresAt = accessExpiresAt;
    return result;
  }

  AuthResponse._();

  factory AuthResponse.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      AuthResponse()..mergeFromBuffer(data, registry);
  factory AuthResponse.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      AuthResponse()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'AuthResponse',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'shiguang.relay.v1'),
      createEmptyInstance: AuthResponse.$_createMessage)
    ..aOS(1, _omitFieldNames ? '' : 'accessToken')
    ..aOS(2, _omitFieldNames ? '' : 'refreshToken')
    ..aInt64(3, _omitFieldNames ? '' : 'accessExpiresAt')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  AuthResponse clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  AuthResponse copyWith(void Function(AuthResponse) updates) =>
      super.copyWith((message) => updates(message as AuthResponse))
          as AuthResponse;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use AuthResponse() / AuthResponse.new instead')
  static AuthResponse create() => AuthResponse._();
  static $pb.GeneratedMessage $_createMessage() => AuthResponse._();
  @$core.override
  AuthResponse createEmptyInstance() => AuthResponse._();
  @$core.pragma('dart2js:noInline')
  static AuthResponse getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<AuthResponse>(
          AuthResponse.$_createMessage);
  static AuthResponse? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get accessToken => $_getSZ(0);
  @$pb.TagNumber(1)
  set accessToken($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasAccessToken() => $_has(0);
  @$pb.TagNumber(1)
  void clearAccessToken() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get refreshToken => $_getSZ(1);
  @$pb.TagNumber(2)
  set refreshToken($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasRefreshToken() => $_has(1);
  @$pb.TagNumber(2)
  void clearRefreshToken() => $_clearField(2);

  @$pb.TagNumber(3)
  $fixnum.Int64 get accessExpiresAt => $_getI64(2);
  @$pb.TagNumber(3)
  set accessExpiresAt($fixnum.Int64 value) => $_setInt64(2, value);
  @$pb.TagNumber(3)
  $core.bool hasAccessExpiresAt() => $_has(2);
  @$pb.TagNumber(3)
  void clearAccessExpiresAt() => $_clearField(3);
}

class RefreshRequest extends $pb.GeneratedMessage {
  factory RefreshRequest({
    $core.String? refreshToken,
  }) {
    final result = RefreshRequest._();
    if (refreshToken != null) result.refreshToken = refreshToken;
    return result;
  }

  RefreshRequest._();

  factory RefreshRequest.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      RefreshRequest()..mergeFromBuffer(data, registry);
  factory RefreshRequest.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      RefreshRequest()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'RefreshRequest',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'shiguang.relay.v1'),
      createEmptyInstance: RefreshRequest.$_createMessage)
    ..aOS(1, _omitFieldNames ? '' : 'refreshToken')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  RefreshRequest clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  RefreshRequest copyWith(void Function(RefreshRequest) updates) =>
      super.copyWith((message) => updates(message as RefreshRequest))
          as RefreshRequest;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use RefreshRequest() / RefreshRequest.new instead')
  static RefreshRequest create() => RefreshRequest._();
  static $pb.GeneratedMessage $_createMessage() => RefreshRequest._();
  @$core.override
  RefreshRequest createEmptyInstance() => RefreshRequest._();
  @$core.pragma('dart2js:noInline')
  static RefreshRequest getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<RefreshRequest>(
          RefreshRequest.$_createMessage);
  static RefreshRequest? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get refreshToken => $_getSZ(0);
  @$pb.TagNumber(1)
  set refreshToken($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasRefreshToken() => $_has(0);
  @$pb.TagNumber(1)
  void clearRefreshToken() => $_clearField(1);
}

/// ---------------------------------------------------------------------------
/// 好友（Ticket 制，防撞库）— 替代旧 {to_user} 直接邀请
/// ---------------------------------------------------------------------------
class FriendshipTicket extends $pb.GeneratedMessage {
  factory FriendshipTicket({
    $core.String? issuerUserId,
    $core.String? subjectUserId,
    $core.String? nonce,
    $fixnum.Int64? expiresAt,
    $core.List<$core.int>? signature,
    $core.List<$core.int>? encKeyPub,
  }) {
    final result = FriendshipTicket._();
    if (issuerUserId != null) result.issuerUserId = issuerUserId;
    if (subjectUserId != null) result.subjectUserId = subjectUserId;
    if (nonce != null) result.nonce = nonce;
    if (expiresAt != null) result.expiresAt = expiresAt;
    if (signature != null) result.signature = signature;
    if (encKeyPub != null) result.encKeyPub = encKeyPub;
    return result;
  }

  FriendshipTicket._();

  factory FriendshipTicket.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      FriendshipTicket()..mergeFromBuffer(data, registry);
  factory FriendshipTicket.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      FriendshipTicket()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'FriendshipTicket',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'shiguang.relay.v1'),
      createEmptyInstance: FriendshipTicket.$_createMessage)
    ..aOS(1, _omitFieldNames ? '' : 'issuerUserId')
    ..aOS(2, _omitFieldNames ? '' : 'subjectUserId')
    ..aOS(3, _omitFieldNames ? '' : 'nonce')
    ..aInt64(4, _omitFieldNames ? '' : 'expiresAt')
    ..a<$core.List<$core.int>>(
        5, _omitFieldNames ? '' : 'signature', $pb.PbFieldType.OY)
    ..a<$core.List<$core.int>>(
        6, _omitFieldNames ? '' : 'encKeyPub', $pb.PbFieldType.OY)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  FriendshipTicket clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  FriendshipTicket copyWith(void Function(FriendshipTicket) updates) =>
      super.copyWith((message) => updates(message as FriendshipTicket))
          as FriendshipTicket;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use FriendshipTicket() / FriendshipTicket.new instead')
  static FriendshipTicket create() => FriendshipTicket._();
  static $pb.GeneratedMessage $_createMessage() => FriendshipTicket._();
  @$core.override
  FriendshipTicket createEmptyInstance() => FriendshipTicket._();
  @$core.pragma('dart2js:noInline')
  static FriendshipTicket getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<FriendshipTicket>(
          FriendshipTicket.$_createMessage);
  static FriendshipTicket? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get issuerUserId => $_getSZ(0);
  @$pb.TagNumber(1)
  set issuerUserId($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasIssuerUserId() => $_has(0);
  @$pb.TagNumber(1)
  void clearIssuerUserId() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get subjectUserId => $_getSZ(1);
  @$pb.TagNumber(2)
  set subjectUserId($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasSubjectUserId() => $_has(1);
  @$pb.TagNumber(2)
  void clearSubjectUserId() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.String get nonce => $_getSZ(2);
  @$pb.TagNumber(3)
  set nonce($core.String value) => $_setString(2, value);
  @$pb.TagNumber(3)
  $core.bool hasNonce() => $_has(2);
  @$pb.TagNumber(3)
  void clearNonce() => $_clearField(3);

  @$pb.TagNumber(4)
  $fixnum.Int64 get expiresAt => $_getI64(3);
  @$pb.TagNumber(4)
  set expiresAt($fixnum.Int64 value) => $_setInt64(3, value);
  @$pb.TagNumber(4)
  $core.bool hasExpiresAt() => $_has(3);
  @$pb.TagNumber(4)
  void clearExpiresAt() => $_clearField(4);

  @$pb.TagNumber(5)
  $core.List<$core.int> get signature => $_getN(4);
  @$pb.TagNumber(5)
  set signature($core.List<$core.int> value) => $_setBytes(4, value);
  @$pb.TagNumber(5)
  $core.bool hasSignature() => $_has(4);
  @$pb.TagNumber(5)
  void clearSignature() => $_clearField(5);

  @$pb.TagNumber(6)
  $core.List<$core.int> get encKeyPub => $_getN(5);
  @$pb.TagNumber(6)
  set encKeyPub($core.List<$core.int> value) => $_setBytes(5, value);
  @$pb.TagNumber(6)
  $core.bool hasEncKeyPub() => $_has(5);
  @$pb.TagNumber(6)
  void clearEncKeyPub() => $_clearField(6);
}

class FriendshipRequest extends $pb.GeneratedMessage {
  factory FriendshipRequest({
    FriendshipTicket? ticket,
    $core.List<$core.int>? encryptedIntro,
  }) {
    final result = FriendshipRequest._();
    if (ticket != null) result.ticket = ticket;
    if (encryptedIntro != null) result.encryptedIntro = encryptedIntro;
    return result;
  }

  FriendshipRequest._();

  factory FriendshipRequest.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      FriendshipRequest()..mergeFromBuffer(data, registry);
  factory FriendshipRequest.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      FriendshipRequest()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'FriendshipRequest',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'shiguang.relay.v1'),
      createEmptyInstance: FriendshipRequest.$_createMessage)
    ..aOM<FriendshipTicket>(1, _omitFieldNames ? '' : 'ticket',
        subBuilder: FriendshipTicket.$_createMessage)
    ..a<$core.List<$core.int>>(
        2, _omitFieldNames ? '' : 'encryptedIntro', $pb.PbFieldType.OY)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  FriendshipRequest clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  FriendshipRequest copyWith(void Function(FriendshipRequest) updates) =>
      super.copyWith((message) => updates(message as FriendshipRequest))
          as FriendshipRequest;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use FriendshipRequest() / FriendshipRequest.new instead')
  static FriendshipRequest create() => FriendshipRequest._();
  static $pb.GeneratedMessage $_createMessage() => FriendshipRequest._();
  @$core.override
  FriendshipRequest createEmptyInstance() => FriendshipRequest._();
  @$core.pragma('dart2js:noInline')
  static FriendshipRequest getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<FriendshipRequest>(
          FriendshipRequest.$_createMessage);
  static FriendshipRequest? _defaultInstance;

  @$pb.TagNumber(1)
  FriendshipTicket get ticket => $_getN(0);
  @$pb.TagNumber(1)
  set ticket(FriendshipTicket value) => $_setField(1, value);
  @$pb.TagNumber(1)
  $core.bool hasTicket() => $_has(0);
  @$pb.TagNumber(1)
  void clearTicket() => $_clearField(1);
  @$pb.TagNumber(1)
  FriendshipTicket ensureTicket() => $_ensure(0);

  @$pb.TagNumber(2)
  $core.List<$core.int> get encryptedIntro => $_getN(1);
  @$pb.TagNumber(2)
  set encryptedIntro($core.List<$core.int> value) => $_setBytes(1, value);
  @$pb.TagNumber(2)
  $core.bool hasEncryptedIntro() => $_has(1);
  @$pb.TagNumber(2)
  void clearEncryptedIntro() => $_clearField(2);
}

class FriendshipResponse extends $pb.GeneratedMessage {
  factory FriendshipResponse({
    $core.String? inviteId,
    $core.bool? accept,
  }) {
    final result = FriendshipResponse._();
    if (inviteId != null) result.inviteId = inviteId;
    if (accept != null) result.accept = accept;
    return result;
  }

  FriendshipResponse._();

  factory FriendshipResponse.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      FriendshipResponse()..mergeFromBuffer(data, registry);
  factory FriendshipResponse.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      FriendshipResponse()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'FriendshipResponse',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'shiguang.relay.v1'),
      createEmptyInstance: FriendshipResponse.$_createMessage)
    ..aOS(1, _omitFieldNames ? '' : 'inviteId')
    ..aOB(2, _omitFieldNames ? '' : 'accept')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  FriendshipResponse clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  FriendshipResponse copyWith(void Function(FriendshipResponse) updates) =>
      super.copyWith((message) => updates(message as FriendshipResponse))
          as FriendshipResponse;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use FriendshipResponse() / FriendshipResponse.new instead')
  static FriendshipResponse create() => FriendshipResponse._();
  static $pb.GeneratedMessage $_createMessage() => FriendshipResponse._();
  @$core.override
  FriendshipResponse createEmptyInstance() => FriendshipResponse._();
  @$core.pragma('dart2js:noInline')
  static FriendshipResponse getDefault() => _defaultInstance ??=
      $pb.GeneratedMessage.$_defaultFor<FriendshipResponse>(
          FriendshipResponse.$_createMessage);
  static FriendshipResponse? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get inviteId => $_getSZ(0);
  @$pb.TagNumber(1)
  set inviteId($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasInviteId() => $_has(0);
  @$pb.TagNumber(1)
  void clearInviteId() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.bool get accept => $_getBF(1);
  @$pb.TagNumber(2)
  set accept($core.bool value) => $_setBool(1, value);
  @$pb.TagNumber(2)
  $core.bool hasAccept() => $_has(1);
  @$pb.TagNumber(2)
  void clearAccept() => $_clearField(2);
}

class FriendshipView extends $pb.GeneratedMessage {
  factory FriendshipView({
    $core.String? userId,
    $core.String? status,
    $fixnum.Int64? createdAt,
    $core.List<$core.int>? encryptedIntro,
  }) {
    final result = FriendshipView._();
    if (userId != null) result.userId = userId;
    if (status != null) result.status = status;
    if (createdAt != null) result.createdAt = createdAt;
    if (encryptedIntro != null) result.encryptedIntro = encryptedIntro;
    return result;
  }

  FriendshipView._();

  factory FriendshipView.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      FriendshipView()..mergeFromBuffer(data, registry);
  factory FriendshipView.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      FriendshipView()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'FriendshipView',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'shiguang.relay.v1'),
      createEmptyInstance: FriendshipView.$_createMessage)
    ..aOS(1, _omitFieldNames ? '' : 'userId')
    ..aOS(2, _omitFieldNames ? '' : 'status')
    ..aInt64(3, _omitFieldNames ? '' : 'createdAt')
    ..a<$core.List<$core.int>>(
        4, _omitFieldNames ? '' : 'encryptedIntro', $pb.PbFieldType.OY)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  FriendshipView clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  FriendshipView copyWith(void Function(FriendshipView) updates) =>
      super.copyWith((message) => updates(message as FriendshipView))
          as FriendshipView;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use FriendshipView() / FriendshipView.new instead')
  static FriendshipView create() => FriendshipView._();
  static $pb.GeneratedMessage $_createMessage() => FriendshipView._();
  @$core.override
  FriendshipView createEmptyInstance() => FriendshipView._();
  @$core.pragma('dart2js:noInline')
  static FriendshipView getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<FriendshipView>(
          FriendshipView.$_createMessage);
  static FriendshipView? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get userId => $_getSZ(0);
  @$pb.TagNumber(1)
  set userId($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasUserId() => $_has(0);
  @$pb.TagNumber(1)
  void clearUserId() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get status => $_getSZ(1);
  @$pb.TagNumber(2)
  set status($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasStatus() => $_has(1);
  @$pb.TagNumber(2)
  void clearStatus() => $_clearField(2);

  @$pb.TagNumber(3)
  $fixnum.Int64 get createdAt => $_getI64(2);
  @$pb.TagNumber(3)
  set createdAt($fixnum.Int64 value) => $_setInt64(2, value);
  @$pb.TagNumber(3)
  $core.bool hasCreatedAt() => $_has(2);
  @$pb.TagNumber(3)
  void clearCreatedAt() => $_clearField(3);

  @$pb.TagNumber(4)
  $core.List<$core.int> get encryptedIntro => $_getN(3);
  @$pb.TagNumber(4)
  set encryptedIntro($core.List<$core.int> value) => $_setBytes(3, value);
  @$pb.TagNumber(4)
  $core.bool hasEncryptedIntro() => $_has(3);
  @$pb.TagNumber(4)
  void clearEncryptedIntro() => $_clearField(4);
}

/// ---------------------------------------------------------------------------
/// 密钥分发（X3DH 预密钥包）
/// ---------------------------------------------------------------------------
/// X3DH 预密钥生命周期（v0.1.4 定盘 REST 契约）：
///   GET  /v1/users/{user_id}/prekey —— 拉取对方 KeyBundle（identity + signed prekey + 签名），服务端原子弹出 1 个 OPK（消耗即删）
///   POST /v1/users/me/prekeys       —— OPK 存量低于阈值时后台静默批量补仓 20~50 个
class KeyBundleQuery extends $pb.GeneratedMessage {
  factory KeyBundleQuery({
    $core.String? userId,
  }) {
    final result = KeyBundleQuery._();
    if (userId != null) result.userId = userId;
    return result;
  }

  KeyBundleQuery._();

  factory KeyBundleQuery.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      KeyBundleQuery()..mergeFromBuffer(data, registry);
  factory KeyBundleQuery.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      KeyBundleQuery()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'KeyBundleQuery',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'shiguang.relay.v1'),
      createEmptyInstance: KeyBundleQuery.$_createMessage)
    ..aOS(1, _omitFieldNames ? '' : 'userId')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  KeyBundleQuery clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  KeyBundleQuery copyWith(void Function(KeyBundleQuery) updates) =>
      super.copyWith((message) => updates(message as KeyBundleQuery))
          as KeyBundleQuery;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use KeyBundleQuery() / KeyBundleQuery.new instead')
  static KeyBundleQuery create() => KeyBundleQuery._();
  static $pb.GeneratedMessage $_createMessage() => KeyBundleQuery._();
  @$core.override
  KeyBundleQuery createEmptyInstance() => KeyBundleQuery._();
  @$core.pragma('dart2js:noInline')
  static KeyBundleQuery getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<KeyBundleQuery>(
          KeyBundleQuery.$_createMessage);
  static KeyBundleQuery? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get userId => $_getSZ(0);
  @$pb.TagNumber(1)
  set userId($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasUserId() => $_has(0);
  @$pb.TagNumber(1)
  void clearUserId() => $_clearField(1);
}

class KeyBundleResponse extends $pb.GeneratedMessage {
  factory KeyBundleResponse({
    $core.List<$core.int>? identityKeyPub,
    $core.List<$core.int>? signedPrekeyPub,
    $core.List<$core.int>? signedPrekeySig,
    $core.Iterable<PreKey>? oneTimePrekeys,
  }) {
    final result = KeyBundleResponse._();
    if (identityKeyPub != null) result.identityKeyPub = identityKeyPub;
    if (signedPrekeyPub != null) result.signedPrekeyPub = signedPrekeyPub;
    if (signedPrekeySig != null) result.signedPrekeySig = signedPrekeySig;
    if (oneTimePrekeys != null) result.oneTimePrekeys.addAll(oneTimePrekeys);
    return result;
  }

  KeyBundleResponse._();

  factory KeyBundleResponse.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      KeyBundleResponse()..mergeFromBuffer(data, registry);
  factory KeyBundleResponse.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      KeyBundleResponse()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'KeyBundleResponse',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'shiguang.relay.v1'),
      createEmptyInstance: KeyBundleResponse.$_createMessage)
    ..a<$core.List<$core.int>>(
        1, _omitFieldNames ? '' : 'identityKeyPub', $pb.PbFieldType.OY)
    ..a<$core.List<$core.int>>(
        2, _omitFieldNames ? '' : 'signedPrekeyPub', $pb.PbFieldType.OY)
    ..a<$core.List<$core.int>>(
        3, _omitFieldNames ? '' : 'signedPrekeySig', $pb.PbFieldType.OY)
    ..pPM<PreKey>(4, _omitFieldNames ? '' : 'oneTimePrekeys',
        subBuilder: PreKey.$_createMessage)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  KeyBundleResponse clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  KeyBundleResponse copyWith(void Function(KeyBundleResponse) updates) =>
      super.copyWith((message) => updates(message as KeyBundleResponse))
          as KeyBundleResponse;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use KeyBundleResponse() / KeyBundleResponse.new instead')
  static KeyBundleResponse create() => KeyBundleResponse._();
  static $pb.GeneratedMessage $_createMessage() => KeyBundleResponse._();
  @$core.override
  KeyBundleResponse createEmptyInstance() => KeyBundleResponse._();
  @$core.pragma('dart2js:noInline')
  static KeyBundleResponse getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<KeyBundleResponse>(
          KeyBundleResponse.$_createMessage);
  static KeyBundleResponse? _defaultInstance;

  @$pb.TagNumber(1)
  $core.List<$core.int> get identityKeyPub => $_getN(0);
  @$pb.TagNumber(1)
  set identityKeyPub($core.List<$core.int> value) => $_setBytes(0, value);
  @$pb.TagNumber(1)
  $core.bool hasIdentityKeyPub() => $_has(0);
  @$pb.TagNumber(1)
  void clearIdentityKeyPub() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.List<$core.int> get signedPrekeyPub => $_getN(1);
  @$pb.TagNumber(2)
  set signedPrekeyPub($core.List<$core.int> value) => $_setBytes(1, value);
  @$pb.TagNumber(2)
  $core.bool hasSignedPrekeyPub() => $_has(1);
  @$pb.TagNumber(2)
  void clearSignedPrekeyPub() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.List<$core.int> get signedPrekeySig => $_getN(2);
  @$pb.TagNumber(3)
  set signedPrekeySig($core.List<$core.int> value) => $_setBytes(2, value);
  @$pb.TagNumber(3)
  $core.bool hasSignedPrekeySig() => $_has(2);
  @$pb.TagNumber(3)
  void clearSignedPrekeySig() => $_clearField(3);

  @$pb.TagNumber(4)
  $pb.PbList<PreKey> get oneTimePrekeys => $_getList(3);
}

class PreKey extends $pb.GeneratedMessage {
  factory PreKey({
    $core.String? keyId,
    $core.List<$core.int>? pub,
  }) {
    final result = PreKey._();
    if (keyId != null) result.keyId = keyId;
    if (pub != null) result.pub = pub;
    return result;
  }

  PreKey._();

  factory PreKey.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      PreKey()..mergeFromBuffer(data, registry);
  factory PreKey.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      PreKey()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'PreKey',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'shiguang.relay.v1'),
      createEmptyInstance: PreKey.$_createMessage)
    ..aOS(1, _omitFieldNames ? '' : 'keyId')
    ..a<$core.List<$core.int>>(
        2, _omitFieldNames ? '' : 'pub', $pb.PbFieldType.OY)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PreKey clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  PreKey copyWith(void Function(PreKey) updates) =>
      super.copyWith((message) => updates(message as PreKey)) as PreKey;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use PreKey() / PreKey.new instead')
  static PreKey create() => PreKey._();
  static $pb.GeneratedMessage $_createMessage() => PreKey._();
  @$core.override
  PreKey createEmptyInstance() => PreKey._();
  @$core.pragma('dart2js:noInline')
  static PreKey getDefault() => _defaultInstance ??=
      $pb.GeneratedMessage.$_defaultFor<PreKey>(PreKey.$_createMessage);
  static PreKey? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get keyId => $_getSZ(0);
  @$pb.TagNumber(1)
  set keyId($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasKeyId() => $_has(0);
  @$pb.TagNumber(1)
  void clearKeyId() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.List<$core.int> get pub => $_getN(1);
  @$pb.TagNumber(2)
  set pub($core.List<$core.int> value) => $_setBytes(1, value);
  @$pb.TagNumber(2)
  $core.bool hasPub() => $_has(1);
  @$pb.TagNumber(2)
  void clearPub() => $_clearField(2);
}

/// ---------------------------------------------------------------------------
/// P2P 瞬时信令（relay_messages，阅后即焚）— 非 bundle 事件
/// ---------------------------------------------------------------------------
class RelayMessage extends $pb.GeneratedMessage {
  factory RelayMessage({
    $core.String? msgId,
    $core.String? recipientUserId,
    $core.String? senderUserId,
    $core.String? kind,
    $core.List<$core.int>? cipher,
    $fixnum.Int64? expiresAt,
  }) {
    final result = RelayMessage._();
    if (msgId != null) result.msgId = msgId;
    if (recipientUserId != null) result.recipientUserId = recipientUserId;
    if (senderUserId != null) result.senderUserId = senderUserId;
    if (kind != null) result.kind = kind;
    if (cipher != null) result.cipher = cipher;
    if (expiresAt != null) result.expiresAt = expiresAt;
    return result;
  }

  RelayMessage._();

  factory RelayMessage.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      RelayMessage()..mergeFromBuffer(data, registry);
  factory RelayMessage.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      RelayMessage()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'RelayMessage',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'shiguang.relay.v1'),
      createEmptyInstance: RelayMessage.$_createMessage)
    ..aOS(1, _omitFieldNames ? '' : 'msgId')
    ..aOS(2, _omitFieldNames ? '' : 'recipientUserId')
    ..aOS(3, _omitFieldNames ? '' : 'senderUserId')
    ..aOS(4, _omitFieldNames ? '' : 'kind')
    ..a<$core.List<$core.int>>(
        5, _omitFieldNames ? '' : 'cipher', $pb.PbFieldType.OY)
    ..aInt64(6, _omitFieldNames ? '' : 'expiresAt')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  RelayMessage clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  RelayMessage copyWith(void Function(RelayMessage) updates) =>
      super.copyWith((message) => updates(message as RelayMessage))
          as RelayMessage;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use RelayMessage() / RelayMessage.new instead')
  static RelayMessage create() => RelayMessage._();
  static $pb.GeneratedMessage $_createMessage() => RelayMessage._();
  @$core.override
  RelayMessage createEmptyInstance() => RelayMessage._();
  @$core.pragma('dart2js:noInline')
  static RelayMessage getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<RelayMessage>(
          RelayMessage.$_createMessage);
  static RelayMessage? _defaultInstance;

  @$pb.TagNumber(1)
  $core.String get msgId => $_getSZ(0);
  @$pb.TagNumber(1)
  set msgId($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasMsgId() => $_has(0);
  @$pb.TagNumber(1)
  void clearMsgId() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get recipientUserId => $_getSZ(1);
  @$pb.TagNumber(2)
  set recipientUserId($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasRecipientUserId() => $_has(1);
  @$pb.TagNumber(2)
  void clearRecipientUserId() => $_clearField(2);

  @$pb.TagNumber(3)
  $core.String get senderUserId => $_getSZ(2);
  @$pb.TagNumber(3)
  set senderUserId($core.String value) => $_setString(2, value);
  @$pb.TagNumber(3)
  $core.bool hasSenderUserId() => $_has(2);
  @$pb.TagNumber(3)
  void clearSenderUserId() => $_clearField(3);

  @$pb.TagNumber(4)
  $core.String get kind => $_getSZ(3);
  @$pb.TagNumber(4)
  set kind($core.String value) => $_setString(3, value);
  @$pb.TagNumber(4)
  $core.bool hasKind() => $_has(3);
  @$pb.TagNumber(4)
  void clearKind() => $_clearField(4);

  @$pb.TagNumber(5)
  $core.List<$core.int> get cipher => $_getN(4);
  @$pb.TagNumber(5)
  set cipher($core.List<$core.int> value) => $_setBytes(4, value);
  @$pb.TagNumber(5)
  $core.bool hasCipher() => $_has(4);
  @$pb.TagNumber(5)
  void clearCipher() => $_clearField(5);

  @$pb.TagNumber(6)
  $fixnum.Int64 get expiresAt => $_getI64(5);
  @$pb.TagNumber(6)
  set expiresAt($fixnum.Int64 value) => $_setInt64(5, value);
  @$pb.TagNumber(6)
  $core.bool hasExpiresAt() => $_has(5);
  @$pb.TagNumber(6)
  void clearExpiresAt() => $_clearField(6);
}

/// 瞬时信令拉取确认（v0.1.3）：对应 REST `POST /v1/relay/ack {message_ids}`。
/// 服务端收到即将 relay_messages.status 置 'acked' 并物理 DELETE（阅后即焚）。
/// 在线路径由 WSS ServerFrame.relay 直推；离线/冷启动由 `GET /v1/relay/pending` 拉取后回 ack。
class RelayAckRequest extends $pb.GeneratedMessage {
  factory RelayAckRequest({
    $core.Iterable<$core.String>? messageIds,
  }) {
    final result = RelayAckRequest._();
    if (messageIds != null) result.messageIds.addAll(messageIds);
    return result;
  }

  RelayAckRequest._();

  factory RelayAckRequest.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      RelayAckRequest()..mergeFromBuffer(data, registry);
  factory RelayAckRequest.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      RelayAckRequest()..mergeFromJson(json, registry);

  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'RelayAckRequest',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'shiguang.relay.v1'),
      createEmptyInstance: RelayAckRequest.$_createMessage)
    ..pPS(1, _omitFieldNames ? '' : 'messageIds')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  RelayAckRequest clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  RelayAckRequest copyWith(void Function(RelayAckRequest) updates) =>
      super.copyWith((message) => updates(message as RelayAckRequest))
          as RelayAckRequest;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use RelayAckRequest() / RelayAckRequest.new instead')
  static RelayAckRequest create() => RelayAckRequest._();
  static $pb.GeneratedMessage $_createMessage() => RelayAckRequest._();
  @$core.override
  RelayAckRequest createEmptyInstance() => RelayAckRequest._();
  @$core.pragma('dart2js:noInline')
  static RelayAckRequest getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<RelayAckRequest>(
          RelayAckRequest.$_createMessage);
  static RelayAckRequest? _defaultInstance;

  @$pb.TagNumber(1)
  $pb.PbList<$core.String> get messageIds => $_getList(0);
}

enum ClientFrame_Body {
  schedule,
  relay,
  auth,
  register,
  login,
  friendReq,
  friendResp,
  notSet
}

/// ---------------------------------------------------------------------------
/// WSS 帧
/// ---------------------------------------------------------------------------
class ClientFrame extends $pb.GeneratedMessage {
  factory ClientFrame({
    $core.String? clientMsgId,
    $fixnum.Int64? clientTs,
    Envelope? schedule,
    RelayMessage? relay,
    AuthChallenge? auth,
    RegisterRequest? register,
    LoginRequest? login,
    FriendshipRequest? friendReq,
    FriendshipResponse? friendResp,
  }) {
    final result = ClientFrame._();
    if (clientMsgId != null) result.clientMsgId = clientMsgId;
    if (clientTs != null) result.clientTs = clientTs;
    if (schedule != null) result.schedule = schedule;
    if (relay != null) result.relay = relay;
    if (auth != null) result.auth = auth;
    if (register != null) result.register = register;
    if (login != null) result.login = login;
    if (friendReq != null) result.friendReq = friendReq;
    if (friendResp != null) result.friendResp = friendResp;
    return result;
  }

  ClientFrame._();

  factory ClientFrame.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      ClientFrame()..mergeFromBuffer(data, registry);
  factory ClientFrame.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      ClientFrame()..mergeFromJson(json, registry);

  static const $core.Map<$core.int, ClientFrame_Body> _ClientFrame_BodyByTag = {
    3: ClientFrame_Body.schedule,
    4: ClientFrame_Body.relay,
    5: ClientFrame_Body.auth,
    6: ClientFrame_Body.register,
    7: ClientFrame_Body.login,
    8: ClientFrame_Body.friendReq,
    9: ClientFrame_Body.friendResp,
    0: ClientFrame_Body.notSet
  };
  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'ClientFrame',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'shiguang.relay.v1'),
      createEmptyInstance: ClientFrame.$_createMessage)
    ..oo(0, [3, 4, 5, 6, 7, 8, 9])
    ..aOS(1, _omitFieldNames ? '' : 'clientMsgId')
    ..aInt64(2, _omitFieldNames ? '' : 'clientTs')
    ..aOM<Envelope>(3, _omitFieldNames ? '' : 'schedule',
        subBuilder: Envelope.$_createMessage)
    ..aOM<RelayMessage>(4, _omitFieldNames ? '' : 'relay',
        subBuilder: RelayMessage.$_createMessage)
    ..aOM<AuthChallenge>(5, _omitFieldNames ? '' : 'auth',
        subBuilder: AuthChallenge.$_createMessage)
    ..aOM<RegisterRequest>(6, _omitFieldNames ? '' : 'register',
        subBuilder: RegisterRequest.$_createMessage)
    ..aOM<LoginRequest>(7, _omitFieldNames ? '' : 'login',
        subBuilder: LoginRequest.$_createMessage)
    ..aOM<FriendshipRequest>(8, _omitFieldNames ? '' : 'friendReq',
        subBuilder: FriendshipRequest.$_createMessage)
    ..aOM<FriendshipResponse>(9, _omitFieldNames ? '' : 'friendResp',
        subBuilder: FriendshipResponse.$_createMessage)
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ClientFrame clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ClientFrame copyWith(void Function(ClientFrame) updates) =>
      super.copyWith((message) => updates(message as ClientFrame))
          as ClientFrame;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use ClientFrame() / ClientFrame.new instead')
  static ClientFrame create() => ClientFrame._();
  static $pb.GeneratedMessage $_createMessage() => ClientFrame._();
  @$core.override
  ClientFrame createEmptyInstance() => ClientFrame._();
  @$core.pragma('dart2js:noInline')
  static ClientFrame getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<ClientFrame>(
          ClientFrame.$_createMessage);
  static ClientFrame? _defaultInstance;

  @$pb.TagNumber(3)
  @$pb.TagNumber(4)
  @$pb.TagNumber(5)
  @$pb.TagNumber(6)
  @$pb.TagNumber(7)
  @$pb.TagNumber(8)
  @$pb.TagNumber(9)
  ClientFrame_Body whichBody() => _ClientFrame_BodyByTag[$_whichOneof(0)]!;
  @$pb.TagNumber(3)
  @$pb.TagNumber(4)
  @$pb.TagNumber(5)
  @$pb.TagNumber(6)
  @$pb.TagNumber(7)
  @$pb.TagNumber(8)
  @$pb.TagNumber(9)
  void clearBody() => $_clearField($_whichOneof(0));

  @$pb.TagNumber(1)
  $core.String get clientMsgId => $_getSZ(0);
  @$pb.TagNumber(1)
  set clientMsgId($core.String value) => $_setString(0, value);
  @$pb.TagNumber(1)
  $core.bool hasClientMsgId() => $_has(0);
  @$pb.TagNumber(1)
  void clearClientMsgId() => $_clearField(1);

  @$pb.TagNumber(2)
  $fixnum.Int64 get clientTs => $_getI64(1);
  @$pb.TagNumber(2)
  set clientTs($fixnum.Int64 value) => $_setInt64(1, value);
  @$pb.TagNumber(2)
  $core.bool hasClientTs() => $_has(1);
  @$pb.TagNumber(2)
  void clearClientTs() => $_clearField(2);

  @$pb.TagNumber(3)
  Envelope get schedule => $_getN(2);
  @$pb.TagNumber(3)
  set schedule(Envelope value) => $_setField(3, value);
  @$pb.TagNumber(3)
  $core.bool hasSchedule() => $_has(2);
  @$pb.TagNumber(3)
  void clearSchedule() => $_clearField(3);
  @$pb.TagNumber(3)
  Envelope ensureSchedule() => $_ensure(2);

  @$pb.TagNumber(4)
  RelayMessage get relay => $_getN(3);
  @$pb.TagNumber(4)
  set relay(RelayMessage value) => $_setField(4, value);
  @$pb.TagNumber(4)
  $core.bool hasRelay() => $_has(3);
  @$pb.TagNumber(4)
  void clearRelay() => $_clearField(4);
  @$pb.TagNumber(4)
  RelayMessage ensureRelay() => $_ensure(3);

  @$pb.TagNumber(5)
  AuthChallenge get auth => $_getN(4);
  @$pb.TagNumber(5)
  set auth(AuthChallenge value) => $_setField(5, value);
  @$pb.TagNumber(5)
  $core.bool hasAuth() => $_has(4);
  @$pb.TagNumber(5)
  void clearAuth() => $_clearField(5);
  @$pb.TagNumber(5)
  AuthChallenge ensureAuth() => $_ensure(4);

  @$pb.TagNumber(6)
  RegisterRequest get register => $_getN(5);
  @$pb.TagNumber(6)
  set register(RegisterRequest value) => $_setField(6, value);
  @$pb.TagNumber(6)
  $core.bool hasRegister() => $_has(5);
  @$pb.TagNumber(6)
  void clearRegister() => $_clearField(6);
  @$pb.TagNumber(6)
  RegisterRequest ensureRegister() => $_ensure(5);

  @$pb.TagNumber(7)
  LoginRequest get login => $_getN(6);
  @$pb.TagNumber(7)
  set login(LoginRequest value) => $_setField(7, value);
  @$pb.TagNumber(7)
  $core.bool hasLogin() => $_has(6);
  @$pb.TagNumber(7)
  void clearLogin() => $_clearField(7);
  @$pb.TagNumber(7)
  LoginRequest ensureLogin() => $_ensure(6);

  @$pb.TagNumber(8)
  FriendshipRequest get friendReq => $_getN(7);
  @$pb.TagNumber(8)
  set friendReq(FriendshipRequest value) => $_setField(8, value);
  @$pb.TagNumber(8)
  $core.bool hasFriendReq() => $_has(7);
  @$pb.TagNumber(8)
  void clearFriendReq() => $_clearField(8);
  @$pb.TagNumber(8)
  FriendshipRequest ensureFriendReq() => $_ensure(7);

  @$pb.TagNumber(9)
  FriendshipResponse get friendResp => $_getN(8);
  @$pb.TagNumber(9)
  set friendResp(FriendshipResponse value) => $_setField(9, value);
  @$pb.TagNumber(9)
  $core.bool hasFriendResp() => $_has(8);
  @$pb.TagNumber(9)
  void clearFriendResp() => $_clearField(9);
  @$pb.TagNumber(9)
  FriendshipResponse ensureFriendResp() => $_ensure(8);
}

enum ServerFrame_Body { schedule, relay, auth, friend, error, notSet }

class ServerFrame extends $pb.GeneratedMessage {
  factory ServerFrame({
    $fixnum.Int64? seqId,
    $core.String? clientMsgId,
    Envelope? schedule,
    RelayMessage? relay,
    AuthResponse? auth,
    FriendshipView? friend,
    $core.String? error,
  }) {
    final result = ServerFrame._();
    if (seqId != null) result.seqId = seqId;
    if (clientMsgId != null) result.clientMsgId = clientMsgId;
    if (schedule != null) result.schedule = schedule;
    if (relay != null) result.relay = relay;
    if (auth != null) result.auth = auth;
    if (friend != null) result.friend = friend;
    if (error != null) result.error = error;
    return result;
  }

  ServerFrame._();

  factory ServerFrame.fromBuffer($core.List<$core.int> data,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      ServerFrame()..mergeFromBuffer(data, registry);
  factory ServerFrame.fromJson($core.String json,
          [$pb.ExtensionRegistry registry = $pb.ExtensionRegistry.EMPTY]) =>
      ServerFrame()..mergeFromJson(json, registry);

  static const $core.Map<$core.int, ServerFrame_Body> _ServerFrame_BodyByTag = {
    3: ServerFrame_Body.schedule,
    4: ServerFrame_Body.relay,
    5: ServerFrame_Body.auth,
    6: ServerFrame_Body.friend,
    7: ServerFrame_Body.error,
    0: ServerFrame_Body.notSet
  };
  static final $pb.BuilderInfo _i = $pb.BuilderInfo(
      _omitMessageNames ? '' : 'ServerFrame',
      package:
          const $pb.PackageName(_omitMessageNames ? '' : 'shiguang.relay.v1'),
      createEmptyInstance: ServerFrame.$_createMessage)
    ..oo(0, [3, 4, 5, 6, 7])
    ..aInt64(1, _omitFieldNames ? '' : 'seqId')
    ..aOS(2, _omitFieldNames ? '' : 'clientMsgId')
    ..aOM<Envelope>(3, _omitFieldNames ? '' : 'schedule',
        subBuilder: Envelope.$_createMessage)
    ..aOM<RelayMessage>(4, _omitFieldNames ? '' : 'relay',
        subBuilder: RelayMessage.$_createMessage)
    ..aOM<AuthResponse>(5, _omitFieldNames ? '' : 'auth',
        subBuilder: AuthResponse.$_createMessage)
    ..aOM<FriendshipView>(6, _omitFieldNames ? '' : 'friend',
        subBuilder: FriendshipView.$_createMessage)
    ..aOS(7, _omitFieldNames ? '' : 'error')
    ..hasRequiredFields = false;

  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ServerFrame clone() => deepCopy();
  @$core.Deprecated('See https://github.com/google/protobuf.dart/issues/998.')
  ServerFrame copyWith(void Function(ServerFrame) updates) =>
      super.copyWith((message) => updates(message as ServerFrame))
          as ServerFrame;

  @$core.override
  $pb.BuilderInfo get info_ => _i;

  @$core.pragma('dart2js:noInline')
  @$core.Deprecated('Use ServerFrame() / ServerFrame.new instead')
  static ServerFrame create() => ServerFrame._();
  static $pb.GeneratedMessage $_createMessage() => ServerFrame._();
  @$core.override
  ServerFrame createEmptyInstance() => ServerFrame._();
  @$core.pragma('dart2js:noInline')
  static ServerFrame getDefault() =>
      _defaultInstance ??= $pb.GeneratedMessage.$_defaultFor<ServerFrame>(
          ServerFrame.$_createMessage);
  static ServerFrame? _defaultInstance;

  @$pb.TagNumber(3)
  @$pb.TagNumber(4)
  @$pb.TagNumber(5)
  @$pb.TagNumber(6)
  @$pb.TagNumber(7)
  ServerFrame_Body whichBody() => _ServerFrame_BodyByTag[$_whichOneof(0)]!;
  @$pb.TagNumber(3)
  @$pb.TagNumber(4)
  @$pb.TagNumber(5)
  @$pb.TagNumber(6)
  @$pb.TagNumber(7)
  void clearBody() => $_clearField($_whichOneof(0));

  @$pb.TagNumber(1)
  $fixnum.Int64 get seqId => $_getI64(0);
  @$pb.TagNumber(1)
  set seqId($fixnum.Int64 value) => $_setInt64(0, value);
  @$pb.TagNumber(1)
  $core.bool hasSeqId() => $_has(0);
  @$pb.TagNumber(1)
  void clearSeqId() => $_clearField(1);

  @$pb.TagNumber(2)
  $core.String get clientMsgId => $_getSZ(1);
  @$pb.TagNumber(2)
  set clientMsgId($core.String value) => $_setString(1, value);
  @$pb.TagNumber(2)
  $core.bool hasClientMsgId() => $_has(1);
  @$pb.TagNumber(2)
  void clearClientMsgId() => $_clearField(2);

  @$pb.TagNumber(3)
  Envelope get schedule => $_getN(2);
  @$pb.TagNumber(3)
  set schedule(Envelope value) => $_setField(3, value);
  @$pb.TagNumber(3)
  $core.bool hasSchedule() => $_has(2);
  @$pb.TagNumber(3)
  void clearSchedule() => $_clearField(3);
  @$pb.TagNumber(3)
  Envelope ensureSchedule() => $_ensure(2);

  @$pb.TagNumber(4)
  RelayMessage get relay => $_getN(3);
  @$pb.TagNumber(4)
  set relay(RelayMessage value) => $_setField(4, value);
  @$pb.TagNumber(4)
  $core.bool hasRelay() => $_has(3);
  @$pb.TagNumber(4)
  void clearRelay() => $_clearField(4);
  @$pb.TagNumber(4)
  RelayMessage ensureRelay() => $_ensure(3);

  @$pb.TagNumber(5)
  AuthResponse get auth => $_getN(4);
  @$pb.TagNumber(5)
  set auth(AuthResponse value) => $_setField(5, value);
  @$pb.TagNumber(5)
  $core.bool hasAuth() => $_has(4);
  @$pb.TagNumber(5)
  void clearAuth() => $_clearField(5);
  @$pb.TagNumber(5)
  AuthResponse ensureAuth() => $_ensure(4);

  @$pb.TagNumber(6)
  FriendshipView get friend => $_getN(5);
  @$pb.TagNumber(6)
  set friend(FriendshipView value) => $_setField(6, value);
  @$pb.TagNumber(6)
  $core.bool hasFriend() => $_has(5);
  @$pb.TagNumber(6)
  void clearFriend() => $_clearField(6);
  @$pb.TagNumber(6)
  FriendshipView ensureFriend() => $_ensure(5);

  @$pb.TagNumber(7)
  $core.String get error => $_getSZ(6);
  @$pb.TagNumber(7)
  set error($core.String value) => $_setString(6, value);
  @$pb.TagNumber(7)
  $core.bool hasError() => $_has(6);
  @$pb.TagNumber(7)
  void clearError() => $_clearField(7);
}

const $core.bool _omitFieldNames =
    $core.bool.fromEnvironment('protobuf.omit_field_names');
const $core.bool _omitMessageNames =
    $core.bool.fromEnvironment('protobuf.omit_message_names');
