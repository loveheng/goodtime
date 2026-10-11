import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:shiguang/collab/crypto/bundle_key_source.dart';
import 'package:shiguang/collab/crypto/key_store.dart';
import 'package:shiguang/collab/crypto/secret_store.dart';
import 'package:shiguang/data/db.dart';

final _kb = Uint8List.fromList(List<int>.generate(32, (i) => i + 1));

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

  test('MSK 未就绪：整条通路半盲返回 null（不抛、不碰安全区）', () async {
    final src = BundleKeySource(db, vault);
    // 注意：此时 vault 从未 ensureReady，连 SecretStore 都不会被读到
    expect(vault.isUnlocked, isFalse);
    expect(await src('b1', 1), isNull);
  });

  test('persist → call 往返：取回的 K_bundle 与原文逐字节相等', () async {
    await vault.ensureReady();
    final src = BundleKeySource(db, vault);
    await src.persist('b1', 1, _kb);

    final got = await src('b1', 1);
    expect(got, isNotNull);
    expect(Uint8List.fromList(got!), equals(_kb));
    expect(got.length, 32);
  });

  test('按 key_ver 精确路由：REKEY 后新旧版本各自可取且互不串台', () async {
    await vault.ensureReady();
    final src = BundleKeySource(db, vault);
    final kb2 = Uint8List.fromList(List<int>.generate(32, (i) => i + 100));
    await src.persist('b1', 1, _kb);
    await src.persist('b1', 2, kb2);

    expect(Uint8List.fromList((await src('b1', 1))!), equals(_kb));
    expect(Uint8List.fromList((await src('b1', 2))!), equals(kb2));
  });

  test('未 enrolled 的包/版本返回 null', () async {
    await vault.ensureReady();
    final src = BundleKeySource(db, vault);
    await src.persist('b1', 1, _kb);
    expect(await src('b-other', 1), isNull); // 别的包
    expect(await src('b1', 7), isNull); // 没有的版本
  });

  test('换机后 MSK 对不上（解封失败）→ 返回 null 而非抛', () async {
    await vault.ensureReady();
    final writer = BundleKeySource(db, vault);
    await writer.persist('b1', 1, _kb);

    // 换一台设备：另一份安全区=另一把独立 MSK（各端各自生成，互不相通）
    final otherVault = KeyStore(MemorySecretStore());
    await otherVault.ensureReady();
    final otherSrc = BundleKeySource(db, otherVault);

    // MAC 校验必然不过；纪律要求 swallow 成 null（终止解释力强于崩溃）
    expect(await otherSrc('b1', 1), isNull);
  });

  test('persist 同版本幂等：重复写不覆盖，旧信封保持可解', () async {
    await vault.ensureReady();
    final src = BundleKeySource(db, vault);
    await src.persist('b1', 1, _kb);
    final other = Uint8List.fromList(List<int>.generate(32, (i) => i + 200));
    await src.persist('b1', 1, other); // 同版本重复写

    // IGNORE 语义：首次写入的 K_bundle 保留（不 LWW 覆盖）
    expect(Uint8List.fromList((await src('b1', 1))!), equals(_kb));
  });

  test('envelope 落的是 MSK 密文，而非明文 K_bundle', () async {
    await vault.ensureReady();
    final src = BundleKeySource(db, vault);
    await src.persist('b1', 1, _kb);

    final rows = await db.query('collab_key_ring', where: 'bundle_id = ?',
        whereArgs: ['b1']);
    final envelope = rows.first['envelope'] as String;
    // 明文 32 字节绝不出现在本地库里
    expect(envelope.contains(base64Encode(_kb)), isFalse);
    // 且必须是可解析的 SealedBlob JSON
    expect(jsonDecode(envelope), isA<Map<String, dynamic>>());
  });
}
