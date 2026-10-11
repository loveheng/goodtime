import 'dart:async';
import 'dart:convert';

import 'package:cryptography/cryptography.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/collab/crypto/session.dart';
import 'package:shiguang/collab/identity.dart';
import 'package:shiguang/collab/pow.dart';
import 'package:shiguang/collab/proto/relay_protocol.pb.dart';
import 'package:shiguang/collab/relay/auth_api.dart';
import 'package:shiguang/collab/relay/relay_mock.dart';
import 'package:shiguang/collab/relay/wss_client.dart';
import 'package:shiguang/collab/sealing.dart';
import 'package:shiguang/collab/pipeline.dart';

/// 轮询等待条件成立，超时抛 [TimeoutException]。
Future<void> _waitUntil(bool Function() cond,
    {Duration timeout = const Duration(seconds: 8)}) async {
  final end = DateTime.now().add(timeout);
  while (!cond()) {
    if (DateTime.now().isAfter(end)) {
      throw TimeoutException('条件未在 ${timeout.inSeconds}s 内满足');
    }
    await Future.delayed(const Duration(milliseconds: 15));
  }
}

/// 一台「设备」：持有私钥材料 + 登录态 + WSS 客户端 + 单消费管道。
class _Device {
  final String userId;
  final String username;
  final IdentityMaterial material;
  final TokenPair tokens;
  final RelayWssClient client;
  final SingleConsumerPipeline pipeline;
  final Dio dio;
  final String baseUrl;

  _Device({
    required this.userId,
    required this.username,
    required this.material,
    required this.tokens,
    required this.client,
    required this.pipeline,
    required this.dio,
    required this.baseUrl,
  });

  String get wsUri => '${baseUrl.replaceFirst('http', 'ws')}/ws';

  /// 注册 + 登录（账号体系 Plan A：用户名+密码注册、密码登录换票），
  /// user_id 由服务端发号；私钥材料本地保留用于密钥协商。
  static Future<_Device> bootstrap(
      String username, String baseUrl, Dio dio) async {
    final material = await generateIdentity(opkCount: 12);
    final password = 'password-$username';

    final ch = await fetchChallenge(dio, baseUrl);
    final body = buildRegisterBody(
      username: username,
      password: password,
      identityKeyPub: await material.identityPublicKey,
      signedPrekeyPub: await material.signedPreKeyPublic,
      signedPrekeySig: await material.signedPreKeySignature(),
      nonce: ch.nonce,
      powSolution: solvePoW(ch.powChallenge, ch.powDifficulty),
      oneTimePreKeys: material.oneTimePreKeys,
    );
    final reg = await registerUser(dio, baseUrl, body);
    final tokens = await login(dio, baseUrl, username, password);

    final client = RelayWssClient(
      wsUri: Uri.parse('${baseUrl.replaceFirst('http', 'ws')}/ws'),
      getToken: () async => tokens.access,
      keepaliveInterval: const Duration(hours: 1),
    );
    final pipeline = SingleConsumerPipeline();
    pipeline.attachWss(client);
    client.connect();

    return _Device(
      userId: reg.userId,
      username: username,
      material: material,
      tokens: tokens,
      client: client,
      pipeline: pipeline,
      dio: dio,
      baseUrl: baseUrl,
    );
  }

  /// 取对端 SPK 公钥（X3DH 预密钥包）。
  Future<SimplePublicKey> fetchPeerSpk(String peerId) async {
    final resp =
        await dio.get('$baseUrl/v1/users/prekeys?user_id=$peerId');
    final pub = base64Decode(resp.data['signed_prekey_pub'] as String);
    return SimplePublicKey(pub, type: KeyPairType.x25519);
  }

  /// 密封明文并经由 WSS 发布到 bundle（复刻 envelope 上行）。
  Future<void> publish(String bundleId, List<int> plaintext,
      {required String peerUserId}) async {
    final key = await agreeSessionKey(
      mySpk: material.signedPreKey,
      peerSpkPub: await fetchPeerSpk(peerUserId),
    );
    final sealed = await sealPayload(key, plaintext, 1);
    final bytes = utf8.encode(sealed.encode());
    final cf = ClientFrame()
      ..schedule = (Envelope()
        ..action = ActionType.PROPOSE
        ..bundleId = bundleId
        ..cipher = bytes
        ..keyVer = 1);
    client.send(cf);
  }
}

void main() {
  test('双机对测：A 发密文 → 服务端 → B 经管道收到并解密成功（双向）', () async {
    final relay = RelayMockServer();
    await relay.start();
    final dio = Dio();
    final alice = await _Device.bootstrap('alice', relay.baseUrl, dio);
    final bob = await _Device.bootstrap('bob', relay.baseUrl, dio);

    // 等待双方都通过 AUTH 进入 _conns（否则服务端广播会漏掉 bob）
    await _waitUntil(
        () => relay.authedUsers.toSet().containsAll({alice.userId, bob.userId}));

    // B 订阅管道入站
    final bReceived = <CollabInboundEvent>[];
    bob.pipeline.applied.listen(bReceived.add);

    // A → B 发文（bundleId 标识对端为 bob；user_id 服务端发号，发布时带上）
    await alice.publish('b-ob', utf8.encode('hello-bob-🌏'),
        peerUserId: bob.userId);

    await _waitUntil(() => bReceived.isNotEmpty);
    final ev = bReceived.first;
    expect(ev.bundleId, 'b-ob');
    expect(ev.seqId, 1);
    expect(ev.senderHint, alice.userId);

    // B 用与 A 对称的 DH 密钥解开
    final bSpkForA = await bob.fetchPeerSpk(alice.userId);
    final keyBA = await agreeSessionKey(
        mySpk: bob.material.signedPreKey, peerSpkPub: bSpkForA);
    final sealed = SealedPayload.decode(utf8.decode(ev.cipher!));
    final opened = await openPayload(keyBA, sealed);
    expect(utf8.decode(opened), 'hello-bob-🌏');

    // 双向：B → A 回复，A 收到并解开（同一把对称密钥）
    final aReceived = <CollabInboundEvent>[];
    alice.pipeline.applied.listen(aReceived.add);
    await bob.publish('b-oa', utf8.encode('hi-alice'),
        peerUserId: alice.userId);
    await _waitUntil(() => aReceived.isNotEmpty);
    final rSealed = SealedPayload.decode(utf8.decode(aReceived.first.cipher!));
    final openedR = await openPayload(keyBA, rSealed);
    expect(utf8.decode(openedR), 'hi-alice');

    alice.client.dispose();
    bob.client.dispose();
    await relay.close();
  });

  test('双路去重：WSS 已收的事件再经 HTTP /sync 入管道不重复应用', () async {
    final relay = RelayMockServer();
    await relay.start();
    final dio = Dio();
    final alice = await _Device.bootstrap('alice', relay.baseUrl, dio);
    final bob = await _Device.bootstrap('bob', relay.baseUrl, dio);
    await _waitUntil(
        () => relay.authedUsers.toSet().containsAll({alice.userId, bob.userId}));

    final bReceived = <CollabInboundEvent>[];
    bob.pipeline.applied.listen(bReceived.add);

    await alice.publish('b-ob', utf8.encode('dup-test'),
        peerUserId: bob.userId);
    await _waitUntil(
        () => bReceived.where((e) => e.bundleId == 'b-ob').isNotEmpty);

    // 经 HTTP /sync 再拉同一 bundle（after=0），推入管道
    final syncResp =
        await dio.get('${relay.baseUrl}/v1/sync?bundle_id=b-ob&after=0');
    final frames = (syncResp.data['events'] as List)
        .map((e) => ServerFrame.fromBuffer(base64Decode(e as String)))
        .toList();
    bob.pipeline.pushSyncBatch(frames
        .map((f) => CollabInboundEvent.fromServerFrame(f, source: 'http'))
        .toList());

    await Future.delayed(const Duration(milliseconds: 200));
    // WSS 已应用 1 条；HTTP 同 seq 入管道被去重，仍为 1
    expect(bReceived.where((e) => e.bundleId == 'b-ob').length, 1);

    alice.client.dispose();
    bob.client.dispose();
    await relay.close();
  });
}
