import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';

/// 换机恢复金库（recovery_vault，schedule-app §11「Plan A v3」拍板）：
///
/// 金库包裹钥 `K_recovery` 由登录密码派生（Argon2id，独立 salt + 独立 context，
/// 与服务端密码哈希完全隔离）——密码=登录凭据+金库钥唯一点，改密即重封。
///
/// 信封 JSON 结构（服务端纯盲整体存储，端侧自解析）：
/// ```json
/// {"v":1,"kdf":{"alg":"argon2id","mem_kib":19456,"iter":2,"parallel":1},
///  "salt":"<b64>","nonce":"<b64>","cipher_text":"<b64>","mac":"<b64>"}
/// ```
/// 明文载荷 = MSK(32B) ‖ 身份私钥(32B) ‖ SPK 私钥(32B) 顺序拼接（各 32 字节定长）。
class RecoveryVaultCodec {
  RecoveryVaultCodec();

  static const _context = 'shiguang.recovery.v1';
  // Argon2id 参数：OWASP 推荐 ≥19MiB 内存、2 迭代（t=2, p=1）。
  static const _memKib = 19456;
  static const _iterations = 2;
  static const _parallelism = 1;
  static const _keyLen = 32;

  final AesGcm _aead = AesGcm.with256bits();

  final Random _rng = Random.secure();

  /// 封库：用密码派生 KEK 封存 [msk]/[identityPriv]/[spkPriv] 三段 32B 定长载荷。
  Future<Uint8List> sealVault({
    required String password,
    required Uint8List msk,
    required Uint8List identityPriv,
    required Uint8List spkPriv,
    Uint8List? salt,
  }) async {
    final s = salt ?? _randBytes(16);
    final kek = await _derive(password, s);
    final plain = Uint8List.fromList([...msk, ...identityPriv, ...spkPriv]);
    final box = await _aead.encrypt(
      plain,
      secretKey: SecretKey(kek),
      nonce: _randBytes(12),
      aad: utf8.encode(_context),
    );
    final envelope = {
      'v': 1,
      'kdf': {
        'alg': 'argon2id',
        'mem_kib': _memKib,
        'iter': _iterations,
        'parallel': _parallelism,
      },
      'salt': base64Encode(s),
      'nonce': base64Encode(box.nonce),
      'cipher_text': base64Encode(box.cipherText),
      'mac': base64Encode(box.mac.bytes),
    };
    return Uint8List.fromList(utf8.encode(jsonEncode(envelope)));
  }

  /// 开库：换机冷启动用登录密码解出三段私钥。密码错/信封坏一律抛
  /// [RecoveryVaultException]（调用方 UI 必须明示「重新生成身份」后果，禁止静默半盲）。
  Future<RecoveredKeys> openVault({
    required String password,
    required Uint8List envelope,
  }) async {
    final Map<String, dynamic> json;
    try {
      json = jsonDecode(utf8.decode(envelope)) as Map<String, dynamic>;
    } catch (_) {
      throw const RecoveryVaultException('金库信封格式非法');
    }
    if (json['v'] != 1) {
      throw const RecoveryVaultException('金库信封版本不支持');
    }
    final kdf = json['kdf'] as Map<String, dynamic>?;
    final saltB64 = json['salt'] as String?;
    final nonceB64 = json['nonce'] as String?;
    final cipherB64 = json['cipher_text'] as String?;
    final macB64 = json['mac'] as String?;
    if (kdf == null ||
        saltB64 == null ||
        nonceB64 == null ||
        cipherB64 == null ||
        macB64 == null) {
      throw const RecoveryVaultException('金库信封字段缺失');
    }
    if (kdf['alg'] != 'argon2id') {
      throw const RecoveryVaultException('KDF 算法不支持');
    }
    final salt = base64Decode(saltB64);
    final kek = await _deriveWith(
      memory: (kdf['mem_kib'] as num?)?.toInt() ?? _memKib,
      iterations: (kdf['iter'] as num?)?.toInt() ?? _iterations,
      parallelism: (kdf['parallel'] as num?)?.toInt() ?? _parallelism,
      password: password,
      salt: salt,
    );
    final SecretBox box;
    try {
      box = SecretBox(
        base64Decode(cipherB64),
        nonce: base64Decode(nonceB64),
        mac: Mac(base64Decode(macB64)),
      );
    } catch (_) {
      throw const RecoveryVaultException('金库信封 base64 非法');
    }
    final Uint8List plain;
    try {
      plain = Uint8List.fromList(await _aead.decrypt(
        box,
        secretKey: SecretKey(kek),
        aad: utf8.encode(_context),
      ));
    } catch (_) {
      throw const RecoveryVaultException('密码错误或金库已损坏');
    }
    if (plain.length != 96) {
      throw const RecoveryVaultException('金库载荷长度异常');
    }
    return RecoveredKeys(
      msk: Uint8List.sublistView(plain, 0, 32),
      identityPriv: Uint8List.sublistView(plain, 32, 64),
      spkPriv: Uint8List.sublistView(plain, 64, 96),
    );
  }

  Future<Uint8List> _derive(String password, Uint8List salt) =>
      _deriveWith(
        memory: _memKib,
        iterations: _iterations,
        parallelism: _parallelism,
        password: password,
        salt: salt,
      );

  Future<Uint8List> _deriveWith({
    required int memory,
    required int iterations,
    required int parallelism,
    required String password,
    required Uint8List salt,
  }) async {
    final d = Argon2id(
      memory: memory,
      parallelism: parallelism,
      iterations: iterations,
      hashLength: _keyLen,
    );
    final key = await d.deriveKey(
      secretKey: SecretKey(utf8.encode(password)),
      nonce: salt,
      // 独立 context：与任何其他 Argon2 用途（含服务端密码哈希）隔离
      associatedData: utf8.encode(_context),
    );
    return Uint8List.fromList(await key.extractBytes());
  }

  Uint8List _randBytes(int n) =>
      Uint8List.fromList(List<int>.generate(n, (_) => _rng.nextInt(256)));
}

/// 开库成功取回的三段私钥。
class RecoveredKeys {
  final Uint8List msk;
  final Uint8List identityPriv;
  final Uint8List spkPriv;

  const RecoveredKeys({
    required this.msk,
    required this.identityPriv,
    required this.spkPriv,
  });
}

/// 金库解封失败（密码错误 / 信封损坏 / 版本不支持）。
class RecoveryVaultException implements Exception {
  final String message;

  const RecoveryVaultException(this.message);

  @override
  String toString() => 'RecoveryVaultException: $message';
}
