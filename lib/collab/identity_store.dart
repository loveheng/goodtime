import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// 身份材料的本地落点（§5.3：明文密钥只驻内存/安全区，绝不落 SP/SQLite）。
///
/// 注册成功后把「身份私钥（由 MSK 封存）」+ SPK 公钥 + 服务端回执的剩余 OPK 数入账，
/// 供后续登录态签名、低水位静默补仓（§1.5 心跳）、换机还原复用。
class IdentityRecord {
  final String userId;
  final Uint8List sealedIdentityPrivateKey; // MSK 封存的 Ed25519 私钥
  final List<int> signedPreKeyPub; // X25519 raw 32B，对外广播用
  final int opkRemaining;

  /// MSK 封存的 **X25519 SPK 私钥**——被邀端解开 K_bundle 信封的唯一钥匙。
  ///
  /// 可空：此前的注册记录只封了身份私钥（发起端够用，被邀端不够）。存量记录缺失
  /// 即「解不开历史邀约」，按半盲纪律降级为不可解密，绝不拿假密钥凑数。
  final Uint8List? sealedSignedPreKeyPrivate;

  const IdentityRecord({
    required this.userId,
    required this.sealedIdentityPrivateKey,
    required this.signedPreKeyPub,
    this.opkRemaining = 0,
    this.sealedSignedPreKeyPrivate,
  });

  Map<String, dynamic> toJson() => {
        'user_id': userId,
        'sealed_identity_private_key':
            base64Encode(sealedIdentityPrivateKey),
        'signed_prekey_pub': base64Encode(signedPreKeyPub),
        'opk_remaining': opkRemaining,
        if (sealedSignedPreKeyPrivate != null)
          'sealed_signed_prekey_priv': base64Encode(sealedSignedPreKeyPrivate!),
      };

  factory IdentityRecord.fromJson(Map<String, dynamic> j) => IdentityRecord(
        userId: j['user_id'] as String,
        sealedIdentityPrivateKey: base64Decode(j['sealed_identity_private_key']),
        signedPreKeyPub: base64Decode(j['signed_prekey_pub']),
        opkRemaining: (j['opk_remaining'] as int?) ?? 0,
        sealedSignedPreKeyPrivate: j['sealed_signed_prekey_priv'] == null
            ? null
            : base64Decode(j['sealed_signed_prekey_priv'] as String),
      );

  String encode() => jsonEncode(toJson());
  factory IdentityRecord.decode(String raw) =>
      IdentityRecord.fromJson(jsonDecode(raw) as Map<String, dynamic>);
}

abstract class IdentityStore {
  Future<IdentityRecord?> load();
  Future<void> save(IdentityRecord record);
  Future<void> clear();
}

/// 内存实现（测试用）。
class MemoryIdentityStore implements IdentityStore {
  IdentityRecord? _rec;
  @override
  Future<IdentityRecord?> load() async => _rec;
  @override
  Future<void> save(IdentityRecord record) async => _rec = record;
  @override
  Future<void> clear() async => _rec = null;
}

/// 安全区实现：身份记录落 Keychain/Keystore。
class SecureIdentityStore implements IdentityStore {
  SecureIdentityStore({FlutterSecureStorage? storage})
      : _storage = storage ?? const FlutterSecureStorage();

  static const _key = 'collab.identity.record';
  final FlutterSecureStorage _storage;

  @override
  Future<IdentityRecord?> load() async {
    final raw = await _storage.read(key: _key);
    return raw == null ? null : IdentityRecord.decode(raw);
  }

  @override
  Future<void> save(IdentityRecord record) async =>
      _storage.write(key: _key, value: record.encode());

  @override
  Future<void> clear() async => _storage.delete(key: _key);
}
