import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';

import 'secret_store.dart';

/// MSK（Master Secret Key）托管（协同设计 §5.3，2026-10-11 拍板改版）。
///
/// 旧设计是「用户口令 → Argon2id KEK → 包装 MSK」两层结构，MSK 驻内存与否由
/// 用户解锁/锁定决定；账号体系（Plan A）落地后门禁统一为**登录态**，口令解锁层
/// 退役：MSK 由端侧自动托管——首次用到时生成 256 位随机密钥，明文只存在于
/// 「设备安全区（Keystore/Keychain）+ 内存」两种形态，对用户全程无感。
///
/// 业务数据（身份私钥 / K_bundle 信封）仍一律经 [seal]/[open] 用 MSK 封存，
/// 「明文密钥永不落 SQLite/SharedPreferences」纪律不变。
class KeyStore {
  KeyStore(this._store);

  final SecretStore _store;

  static const _mskKey = 'collab.msk.raw';

  final AesGcm _aead = AesGcm.with256bits();
  final Random _rng = Random.secure();

  Uint8List? _msk;
  Future<void>? _ensuring;

  /// 已就绪的 MSK（未就绪返回 null）。调用方**不得**长期持有副本。
  Uint8List? get mskBytes => _msk == null ? null : Uint8List.fromList(_msk!);

  /// MSK 是否已在内存（旧名保留：语义从「用户已解锁」变为「密钥已就绪」）。
  bool get isUnlocked => _msk != null;

  /// 确保 MSK 就绪（幂等；并发调用合并为一次）。
  ///
  /// 安全区已有 → 直接载入；没有 → 生成新 MSK 并落安全区。失败时清掉在途
  /// Future，下次调用重试。
  Future<void> ensureReady() {
    return _ensuring ??= _doEnsure().whenComplete(() => _ensuring = null);
  }

  Future<void> _doEnsure() async {
    if (_msk != null) return;
    final raw = await _store.read(_mskKey);
    if (raw != null && raw.isNotEmpty) {
      _msk = base64Decode(raw);
      return;
    }
    final msk = _randBytes(32);
    await _store.write(_mskKey, base64Encode(msk));
    _msk = msk;
  }

  Uint8List _randBytes(int n) =>
      Uint8List.fromList(List<int>.generate(n, (_) => _rng.nextInt(256)));

  /// 换机恢复专用：用金库解出的 MSK 接管本机安全区（覆盖旧值并置内存就绪）。
  ///
  /// 只在 [AccountSession.restoreIdentityFromVault] 成功解封后调用——新设备
  /// 安全区无 MSK 或 MSK 属于另一身份时，以恢复出的服务端身份为准。
  Future<void> importMsk(Uint8List msk) async {
    await _store.write(_mskKey, base64Encode(msk));
    _msk = Uint8List.fromList(msk);
  }

  /// 用 MSK 加密业务数据（身份私钥 / K_bundle 信封）。未就绪即抛，调用方先
  /// [ensureReady]。
  Future<Uint8List> seal(Uint8List data) async {
    final msk = _requireMsk();
    final box = await _aead.encrypt(data, secretKey: SecretKey(msk), nonce: _randBytes(12));
    final json = SealedBlob(
      nonce: box.nonce,
      cipherText: box.cipherText,
      mac: box.mac.bytes,
    ).encode();
    return Uint8List.fromList(utf8.encode(json));
  }

  /// 解封业务数据。
  Future<Uint8List> open(Uint8List sealedBase64) async {
    final msk = _requireMsk();
    final blob = SealedBlob.decode(utf8.decode(sealedBase64));
    final data = await _aead.decrypt(
      SecretBox(blob.cipherText, nonce: blob.nonce, mac: Mac(blob.mac)),
      secretKey: SecretKey(msk),
    );
    return Uint8List.fromList(data);
  }

  Uint8List _requireMsk() {
    final msk = _msk;
    if (msk == null) {
      throw StateError('MSK 未就绪：先 await keyStore.ensureReady()');
    }
    return msk;
  }
}
