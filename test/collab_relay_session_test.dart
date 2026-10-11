import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:shiguang/collab/account.dart';
import 'package:shiguang/collab/crypto/key_store.dart';
import 'package:shiguang/collab/crypto/secret_store.dart';
import 'package:shiguang/collab/identity.dart';
import 'package:shiguang/collab/identity_store.dart';
import 'package:shiguang/collab/relay/auth_api.dart' show TokenPair;
import 'package:shiguang/collab/relay/peers_api.dart';
import 'package:shiguang/collab/relay_session.dart';
import 'package:shiguang/data/db.dart';

// 本地发起的真机前置两问：①我是谁（本地身份 → 还原 Ed25519 私钥）；②能邀谁、
// 信封怎么封（好友清单 + 成员 SPK 公钥）。两者都落在这一层。
// 2026-10-11 起：MSK 端侧自动托管（ensureReady），门禁=登录态（AccountSession）。

KeyStore _lightKeyStore() => KeyStore(MemorySecretStore());

AccountSession _account(KeyStore ks, IdentityStore store) =>
    AccountSession(
      dio: Dio(),
      baseUrl: 'http://127.0.0.1:1',
      keyStore: ks,
      identityStore: store,
    )..debugEstablishSession(
        username: 'alice',
        userId: 'u-a',
        tokens: const TokenPair(access: 'tok-a', refresh: 'r-a'),
      );

/// 复刻 `friends.go` / `register.go` 的响应面（够验证解析与取值范围）。
class MockPeersRelay {
  HttpServer? _server;
  final List<String> paths = [];

  Future<String> start() async {
    _server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    _server!.listen(_handle);
    return 'http://127.0.0.1:${_server!.port}';
  }

  Future<void> close() => _server!.close(force: true);

  Future<void> _handle(HttpRequest req) async {
    paths.add('${req.method} ${req.uri.path}');
    final seg = req.uri.pathSegments;
    Object? body;
    if (seg.length == 2 && seg[1] == 'friends') {
      body = {
        'friends': [
          {'user_id': 'u-b', 'status': 'accepted', 'created_at': 1700000000},
          // pending 且我是被邀方：自报家门只在这时出现（A 侧自己也解不开）
          {
            'user_id': 'u-c',
            'status': 'pending',
            'created_at': 1700000001,
            'encrypted_intro': base64Encode(<int>[1, 2, 3]),
          },
          {'user_id': 'u-d', 'status': 'blocked', 'created_at': 1700000002},
        ],
      };
    } else if (seg.length == 4 &&
        seg[0] == 'v1' &&
        seg[1] == 'users' &&
        seg[3] == 'prekey') {
      body = {
        'user_id': seg[2],
        'identity_key_pub': base64Encode(List<int>.filled(32, 9)),
        'signed_prekey_pub': base64Encode(List<int>.filled(32, 3)),
        'signed_prekey_sig': base64Encode(List<int>.filled(64, 4)),
        'one_time_prekeys': const [],
        'remaining_opk': 2,
        'warning': 'OPK_EMPTY_WARNING',
      };
    } else {
      req.response.statusCode = 404;
      await req.response.close();
      return;
    }
    req.response
      ..statusCode = 200
      ..headers.contentType = ContentType.json
      ..write(jsonEncode(body));
    await req.response.close();
  }
}

void main() {
  sqfliteFfiInit();
  databaseFactory = databaseFactoryFfi;

  late Directory tmp;
  late Database db;

  setUp(() async {
    tmp = await Directory.systemTemp.createTemp('collab_session_test');
    db = await openDatabase(
      p.join(tmp.path, 't.db'),
      version: 7,
      onCreate: (d, _) => Db.createAll(d),
    );
  });

  tearDown(() async {
    await db.close();
    await tmp.delete(recursive: true);
  });

  group('身份还原', () {
    test('Ed25519 私钥字节 → newKeyPairFromSeed：还原后公钥与原公钥一致',
        () async {
      final id = await generateIdentity(opkCount: 10);
      final priv = await id.identityKey.extractPrivateKeyBytes();
      final expectPub = await id.identityPublicKey;

      final ks = _lightKeyStore();
      await ks.ensureReady();
      final rec = IdentityRecord(
        userId: 'u-a',
        sealedIdentityPrivateKey: await ks.seal(Uint8List.fromList(priv)),
        signedPreKeyPub: await id.signedPreKeyPublic,
      );

      final restored = await restoreIdentityKeyPair(keyStore: ks, record: rec);
      expect((await restored.extractPublicKey()).bytes, expectPub);

      // 还原出来的私钥能正常签名（Ed25519 确定性，坐标系分离铁律的前提）
      final sig = await Ed25519().sign(utf8.encode('shiguang'), keyPair: restored);
      final ok = await Ed25519().verify(
        utf8.encode('shiguang'),
        signature: Signature(sig.bytes,
            publicKey:
                SimplePublicKey(expectPub, type: KeyPairType.ed25519)),
      );
      expect(ok, isTrue);
    });

    test('MSK 未就绪时还原入口自己 ensureReady：开箱即用不抛', () async {
      final id = await generateIdentity(opkCount: 10);
      final priv = await id.identityKey.extractPrivateKeyBytes();
      // 注册落账：封存私钥（写侧就绪 MSK）
      final shared = MemorySecretStore();
      final w = KeyStore(shared);
      await w.ensureReady();
      final rec = IdentityRecord(
        userId: 'u-a',
        sealedIdentityPrivateKey: await w.seal(Uint8List.fromList(priv)),
        signedPreKeyPub: await id.signedPreKeyPublic,
      );
      // 换进程：全新内存态（MSK 不在），读侧只拿安全区内容
      final fresh = KeyStore(shared);
      expect(fresh.isUnlocked, isFalse);
      final restored = await restoreIdentityKeyPair(keyStore: fresh, record: rec);
      expect((await restored.extractPublicKey()).bytes,
          await id.identityPublicKey);
    });
  });

  group('好友域与成员公钥', () {
    test('好友清单：accepted 才进可邀名单，pending/blocked 被滤掉', () async {
      final relay = MockPeersRelay();
      final baseUrl = await relay.start();
      addTearDown(relay.close);

      final all = await fetchFriends(Dio(), baseUrl, 'tok');
      expect(all.map((f) => f.userId), ['u-b', 'u-c', 'u-d']);
      expect(all[1].encryptedIntro, [1, 2, 3]);
      expect(all.last.encryptedIntro, isNull);

      final accepted = await fetchAcceptedFriends(Dio(), baseUrl, 'tok');
      expect(accepted.map((f) => f.userId), ['u-b']);
      expect(relay.paths.first, 'GET /v1/friends');
    });

    test('成员公钥：X25519 SPK 取到 32B，低水位 OPK 带警戒', () async {
      final relay = MockPeersRelay();
      final baseUrl = await relay.start();
      addTearDown(relay.close);

      final bundle = await fetchPeerKeys(Dio(), baseUrl, 'tok', 'u-b');
      expect(bundle.signedPrekeyPub.length, 32,
          reason: 'X3DH 只吃 X25519 raw 32B');
      expect(bundle.identityKeyPub.length, 32);
      expect(bundle.opkLow, isTrue);
      expect(bundle.remainingOpk, 2);
      expect(relay.paths.single, 'GET /v1/users/u-b/prekey');
    });
  });

  group('发起套件装配（门禁=登录态 + 本机身份）', () {
    Future<IdentityRecord> identity(KeyStore ks) async {
      await ks.ensureReady();
      final id = await generateIdentity(opkCount: 10);
      final priv = await id.identityKey.extractPrivateKeyBytes();
      return IdentityRecord(
        userId: 'u-a',
        sealedIdentityPrivateKey: await ks.seal(Uint8List.fromList(priv)),
        signedPreKeyPub: await id.signedPreKeyPublic,
      );
    }

    test('本机未注册协同身份 → 返回 null（UI 转引导，不给必然失败的按钮）',
        () async {
      final ks = _lightKeyStore();
      await ks.ensureReady();
      final kit = await buildBundleComposeKit(
        db,
        account: _account(ks, MemoryIdentityStore()),
        keyStore: ks,
        identityStore: MemoryIdentityStore(),
        dio: Dio(),
        baseUrl: 'http://127.0.0.1:1',
      );
      expect(kit, isNull);
    });

    test('已登录 + 有身份 → 套件成形：发起器 / 弹出器 / 好友名单三件套齐全',
        () async {
      final ks = _lightKeyStore();
      final store = MemoryIdentityStore()..save(await identity(ks));

      final relay = MockPeersRelay();
      final baseUrl = await relay.start();
      addTearDown(relay.close);

      final kit = await buildBundleComposeKit(
        db,
        account: _account(ks, store),
        keyStore: ks,
        identityStore: store,
        dio: Dio(),
        baseUrl: baseUrl,
      );
      expect(kit, isNotNull);
      expect(kit!.proposer, isA<Object>());
      expect(kit.drainer, isA<Object>());
      // 好友名单由 RelaySession 惰性取 token 驱动，装配本身不碰网络
      expect(relay.paths, isEmpty);
    });

    test('好友域会话：已登录 + 有身份 → RelaySession 成形，token 取自账号会话',
        () async {
      final ks = _lightKeyStore();
      final store = MemoryIdentityStore()..save(await identity(ks));
      final session = await buildFriendsSession(
        db,
        account: _account(ks, store),
        keyStore: ks,
        identityStore: store,
        dio: Dio(),
        baseUrl: 'http://127.0.0.1:1',
      );
      expect(session, isNotNull);
      expect(await session!.accessToken(), 'tok-a');
    });
  });
}
