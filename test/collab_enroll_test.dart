import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:shiguang/collab/crypto/bundle_key_source.dart';
import 'package:shiguang/collab/crypto/key_store.dart';
import 'package:shiguang/collab/crypto/secret_store.dart';
import 'package:shiguang/collab/enroll.dart';
import 'package:shiguang/collab/propose.dart'
    show generateBundleKey, wrapBundleKeyForMember;
import 'package:shiguang/collab/relay/keys_api.dart' show KeyEnvelopeView;
import 'package:shiguang/data/db.dart';

/// 测试用轻 KDF（同 bundle_key_source_test：密码学往返与参数无关，单测调轻提速）。

void main() {
  sqfliteFfiInit();
  databaseFactory = databaseFactoryFfi;

  late Database db;
  late KeyStore vault;

  setUp(() async {
    db = await openDatabase(
      inMemoryDatabasePath,
      version: 7,
      onCreate: (d, _) => Db.createAll(d),
    );
    vault = KeyStore(MemorySecretStore());
  });

  tearDown(() async => await db.close());

  /// 造一条「发起人 EK × 被邀人 SPK 公钥」封出的真实信封（与发起侧同函数，互逆自证）。
  Future<Map<String, Object?>> makeEnvelope({
    required List<int> bundleKey,
    required int keyVer,
    required List<int> spkPub,
  }) =>
      wrapBundleKeyForMember(
        bundleKey: bundleKey,
        keyVer: keyVer,
        recipientUserId: 'me',
        memberSpkPub: spkPub,
      );

  test('enroll：拉到自己的信封 → SPK 私钥解开 → 落户，返回新落户条数 1', () async {
    await vault.ensureReady();
    final spk = await X25519().newKeyPair();
    final spkPub = (await spk.extractPublicKey()).bytes;
    final bundleKey = generateBundleKey();
    const keyVer = 1;
    final env = await makeEnvelope(
        bundleKey: bundleKey, keyVer: keyVer, spkPub: spkPub);

    final keys = BundleKeySource(db, vault);
    final intake = BundleKeyIntake(
      fetch: (_) => Future.value([KeyEnvelopeView.fromJson(env)]),
      keys: keys,
      mySignedPreKey: () => Future.value(spk),
    );

    expect(await intake.enroll('b1'), 1);
    // 落户后按 (bundleId, keyVer) 可取回明文 K_bundle（逐字节相等）
    final got = await keys('b1', keyVer);
    expect(got, isNotNull);
    expect(Uint8List.fromList(got!), equals(Uint8List.fromList(bundleKey)));
  });

  test('enroll：拉不到（离线/5xx）→ 返回 0，不抛，骨架照常推进', () async {
    await vault.ensureReady();
    final spk = await X25519().newKeyPair();
    final keys = BundleKeySource(db, vault);
    final intake = BundleKeyIntake(
      fetch: (_) => throw Exception('relay unreachable'),
      keys: keys,
      mySignedPreKey: () => Future.value(spk),
    );

    expect(await intake.enroll('b1'), 0);
    // 半盲纪律：没有落户任何密钥
    expect(await keys('b1', 1), isNull);
  });

  test('enroll：已落户版本幂等跳过，不重复计数也不覆盖', () async {
    await vault.ensureReady();
    final spk = await X25519().newKeyPair();
    final spkPub = (await spk.extractPublicKey()).bytes;
    final bundleKey = generateBundleKey();
    const keyVer = 1;
    final env = await makeEnvelope(
        bundleKey: bundleKey, keyVer: keyVer, spkPub: spkPub);

    final keys = BundleKeySource(db, vault);
    await keys.persist('b1', keyVer, bundleKey); // 先手动落户（认祖归宗等）
    var fetchCount = 0;
    final intake = BundleKeyIntake(
      fetch: (_) {
        fetchCount++;
        return Future.value([KeyEnvelopeView.fromJson(env)]);
      },
      keys: keys,
      mySignedPreKey: () => Future.value(spk),
    );

    expect(await intake.enroll('b1'), 0); // 已落户：跳过
    expect(fetchCount, 1); // fetch 仍执行（真实补拉不该省）
  });

  test('enroll：密钥对不上（A 封 B 解）→ MAC 不过静默跳过，返回 0', () async {
    await vault.ensureReady();
    final spkOfA = await X25519().newKeyPair();
    final spkPubA = (await spkOfA.extractPublicKey()).bytes;
    final bundleKey = generateBundleKey();
    const keyVer = 1;
    final env = await makeEnvelope(
        bundleKey: bundleKey, keyVer: keyVer, spkPub: spkPubA);

    // 被邀端用「另一把」SPK 私钥去解（不该是我的信封）
    final spkOfB = await X25519().newKeyPair();
    final keys = BundleKeySource(db, vault);
    final intake = BundleKeyIntake(
      fetch: (_) => Future.value([KeyEnvelopeView.fromJson(env)]),
      keys: keys,
      mySignedPreKey: () => Future.value(spkOfB),
    );

    expect(await intake.enroll('b1'), 0);
    expect(await keys('b1', keyVer), isNull);
  });

  test('enroll：空信封列表（发起人自己没有信封）→ 返回 0', () async {
    await vault.ensureReady();
    final spk = await X25519().newKeyPair();
    final keys = BundleKeySource(db, vault);
    final intake = BundleKeyIntake(
      fetch: (_) => Future.value(const []),
      keys: keys,
      mySignedPreKey: () => Future.value(spk),
    );
    expect(await intake.enroll('b1'), 0);
  });
}
