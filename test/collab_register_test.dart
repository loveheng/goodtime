import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:cryptography/cryptography.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/collab/identity.dart';
import 'package:shiguang/collab/identity_store.dart';
import 'package:shiguang/collab/pow.dart';
import 'package:shiguang/collab/registration.dart';
import 'package:shiguang/collab/crypto/key_store.dart';
import 'package:shiguang/collab/crypto/secret_store.dart';
import 'package:shiguang/collab/relay/auth_api.dart';

void main() {
  group('identity', () {
    test('生成材料：坐标系与数量正确', () async {
      final id = await generateIdentity(opkCount: 20);
      final idPub = await id.identityPublicKey;
      final spkPub = await id.signedPreKeyPublic;
      expect(idPub.length, 32); // Ed25519 / X25519 raw 均 32B
      expect(spkPub.length, 32);
      expect(id.oneTimePreKeys.length, 20);
      for (final k in id.oneTimePreKeys) {
        expect(k.pub.length, 32);
        expect(k.signature.length, 64); // Ed25519 签名 64B
        final ok = await Ed25519().verify(k.pub,
            signature: Signature(k.signature, publicKey: SimplePublicKey(idPub, type: KeyPairType.ed25519)));
        expect(ok, isTrue);
      }
      final spkSig = await id.signedPreKeySignature();
      expect(spkSig.length, 64);
      final okSpk = await Ed25519().verify(spkPub,
          signature: Signature(spkSig, publicKey: SimplePublicKey(idPub, type: KeyPairType.ed25519)));
      expect(okSpk, isTrue);
    });
  });

  group('pow', () {
    test('解出的 solution 通过校验；篡改即失败', () {
      final challenge = 'a' * 32;
      final sol = solvePoW(challenge, 2);
      expect(verifyPoW(challenge, sol, 2), isTrue);
      expect(verifyPoW(challenge, '${sol}x', 2), isFalse);
    });
  });

  group('register body', () {
    test('字段齐全且密钥均为 base64 字符串；user_id/signed_pop 不再出现在请求体',
        () async {
      final id = await generateIdentity(opkCount: 12);
      final body = buildRegisterBody(
        username: 'alice',
        password: 'password-1',
        identityKeyPub: await id.identityPublicKey,
        signedPrekeyPub: await id.signedPreKeyPublic,
        signedPrekeySig: await id.signedPreKeySignature(),
        nonce: 'nonce123',
        powSolution: '0-nonce123',
        oneTimePreKeys: id.oneTimePreKeys,
      );
      expect(body['username'], 'alice');
      expect(body['password'], 'password-1');
      expect(body.containsKey('user_id'), isFalse,
          reason: 'user_id 由服务端发号，客户端不再自报');
      expect(body.containsKey('signed_pop'), isFalse,
          reason: '账号体系 Plan A：注册只验 PoW，不再验 PoP');
      expect(body['one_time_prekeys'], hasLength(12));
      expect(base64Decode(body['identity_key_pub'] as String), hasLength(32));
    });
  });

  group('端到端注册（对忠实复刻 relay 校验逻辑的本地 mock 服务）', () {
    late HttpServer server;
    late String baseUrl;
    final nonces = <String, String>{}; // nonce → pow_challenge

    setUp(() async {
      server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      baseUrl = 'http://${server.address.host}:${server.port}';
      server.listen((req) async {
        final body = await _readJson(req);
        if (req.uri.path == '/v1/auth/challenge' && req.method == 'POST') {
          final n = _randHex(32), pow = _randHex(16);
          nonces[n] = pow;
          req.response
            ..statusCode = 200
            ..headers.contentType = ContentType.json
            ..write(jsonEncode({
              'nonce': n,
              'pow_challenge': pow,
              'pow_difficulty': 2,
              'expires_at':
                  DateTime.now().add(const Duration(minutes: 1)).millisecondsSinceEpoch ~/
                      1000,
            }));
          await req.response.close();
          return;
        }
        if (req.uri.path == '/v1/users' && req.method == 'POST') {
          final resp = await _verifyRegister(body, nonces);
          req.response
            ..statusCode = resp.status
            ..headers.contentType = ContentType.json
            ..write(jsonEncode(resp.json));
          await req.response.close();
          return;
        }
        req.response.statusCode = 404;
        await req.response.close();
      });
    });

    tearDown(() => server.close(force: true));

    test('完整 Challenge→PoW→OPK 注册成功：user_id 服务端发号并本地入账', () async {
      final ks = KeyStore(MemorySecretStore());
      final store = MemoryIdentityStore();
      final result = await registerAccount(
        keyStore: ks,
        identityStore: store,
        dio: Dio(),
        baseUrl: baseUrl,
        username: 'alice',
        password: 'password-1',
        opkCount: 15,
      );
      expect(result.userId, startsWith('u-'), reason: 'user_id 由服务端发号');
      expect(result.username, 'alice');
      expect(result.opkCount, 15);

      final rec = await store.load();
      expect(rec, isNotNull);
      expect(rec!.userId, result.userId);
      expect(rec.opkRemaining, 15);
      expect(rec.sealedIdentityPrivateKey, isNotEmpty);

      // 入账的私钥能被 MSK 解开（MSK 已由 ensureReady 托管）
      final opened = await ks.open(rec.sealedIdentityPrivateKey);
      expect(opened, hasLength(32)); // Ed25519 私钥 32B
    });

    test('弱密码被 mock 服务端拒绝（PASSWORD_TOO_WEAK 400）', () async {
      await expectLater(
        registerAccount(
          keyStore: KeyStore(MemorySecretStore()),
          identityStore: MemoryIdentityStore(),
          dio: Dio(),
          baseUrl: baseUrl,
          username: 'carol',
          password: 'short',
        ),
        throwsA(isA<RelayAuthException>()),
      );
    });

    test('PoW 解错被拒（POW_INVALID 401，直接构造请求体验证服务端链）', () async {
      final id = await generateIdentity(opkCount: 10);
      final bad = await _verifyRegister(
        buildRegisterBody(
          username: 'dave',
          password: 'password-1',
          identityKeyPub: await id.identityPublicKey,
          signedPrekeyPub: await id.signedPreKeyPublic,
          signedPrekeySig: await id.signedPreKeySignature(),
          nonce: 'n',
          powSolution: 'garbage-solution',
          oneTimePreKeys: id.oneTimePreKeys,
        ),
        {'n': _randHex(16)},
      );
      expect(bad.status, 401);
      expect(bad.json['error_code'], 'POW_INVALID');
    });
  });
}

Future<Map<String, dynamic>> _readJson(HttpRequest req) async {
  final raw = await utf8.decoder.bind(req).join();
  return raw.isEmpty
      ? <String, dynamic>{}
      : jsonDecode(raw) as Map<String, dynamic>;
}

/// 复刻 relay `internal/httpapi/register.go` 的校验链（仅用于本地自测，SSOT 仍是 Go 服务）。
class _VerifyResult {
  final int status;
  final Map<String, dynamic> json;
  _VerifyResult(this.status, this.json);
}

Future<_VerifyResult> _verifyRegister(
    Map<String, dynamic> body, Map<String, String> nonces) async {
  final username =
      (body['username'] as String? ?? '').trim().toLowerCase();
  if (!RegExp(r'^[a-z0-9_]{3,32}$').hasMatch(username)) {
    return _VerifyResult(400, {'error_code': 'USERNAME_INVALID'});
  }
  if ((body['password'] as String? ?? '').length < 8) {
    return _VerifyResult(400, {'error_code': 'PASSWORD_TOO_WEAK'});
  }
  final nonce = body['nonce'] as String?;
  final pow = nonces.remove(nonce); // GETDEL 一次性消费
  if (pow == null) {
    return _VerifyResult(401, {'error_code': 'CHALLENGE_INVALID'});
  }
  if (!verifyPoW(pow, body['pow_solution'] as String, 2)) {
    return _VerifyResult(401, {'error_code': 'POW_INVALID'});
  }
  final idPub = base64Decode(body['identity_key_pub'] as String);
  if (idPub.length != 32) {
    return _VerifyResult(400, {'error_code': 'INVALID_REQUEST'});
  }
  final opks = body['one_time_prekeys'] as List;
  if (opks.length < 10 || opks.length > 20) {
    return _VerifyResult(400, {'error_code': 'PREKEY_COUNT_INVALID'});
  }
  return _VerifyResult(201, {
    'user_id': 'u-mock',
    'username': username,
    'opk_count': opks.length,
  });
}

String _randHex(int bytes) {
  final r = Random.secure();
  return List<int>.generate(bytes * 2, (_) => r.nextInt(16))
      .map((n) => n.toRadixString(16))
      .join();
}
