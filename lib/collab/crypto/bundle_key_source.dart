import 'dart:convert';
import 'dart:typed_data';

import 'package:sqflite/sqflite.dart';

import 'key_store.dart';

/// 运行期 K_bundle 检索（Phase 4.3 收口，补齐「最后一公里」）。
///
/// ## 它补的是什么缺口
///
/// 回放/物化/决策矩阵都要 K_bundle，而此前全仓只有 [KeyStore]（MSK 层）与内存态
/// [KeyRing]，**没有把「本地持久信封 → MSK 解封 → K_bundle」这条检索通路接上进工程
/// 序**——于是 UI 只能传 `bundleKey: null`，派生数据一律显示为空态。
///
/// ## 数据流向
///
///   collab_key_ring.envelope（MSK 封送的 K_bundle，密文落本地库）
///     → [KeyStore.open]（需 vault 已解锁）
///     → 明文 K_bundle（32B）→ 交给 [replay] 解密事件 / [materialize] 回填派生。
///
/// ## 半盲纪律（铁律 1）
///
/// 任一环节取不到都返回 **null 而非抛**：vault 未解锁、该 (bundle, version) 无信封、
/// MSK 解封失败（MAC 校验不过）——调用方统一按「这段历史不可解密」处理，骨架仍推进。
class BundleKeySource {
  final Database db;
  final KeyStore vault;

  BundleKeySource(this.db, this.vault);

  /// 取 (bundleId, keyVer) 对应的 K_bundle；取不到返回 null。
  Future<List<int>?> call(String bundleId, int keyVer) async {
    if (!vault.isUnlocked) return null; // vault 未解锁：整段历史半盲
    final rows = await db.query(
      'collab_key_ring',
      columns: ['envelope'],
      where: 'bundle_id = ? AND key_ver = ?',
      whereArgs: [bundleId, keyVer],
      limit: 1,
    );
    if (rows.isEmpty) return null; // 未 enrolled / 版本缺失
    final envelope = rows.first['envelope'] as String?;
    if (envelope == null || envelope.isEmpty) return null;
    try {
      // KeyStore.open 收的是 SealedBlob JSON 的 utf8 字节，与 [persist] 严格互逆。
      return await vault.open(Uint8List.fromList(utf8.encode(envelope)));
    } catch (_) {
      return null; // MAC 校验失败 / 换机后 MSK 对不上：标不可解密
    }
  }

  /// 丢弃某版本的本地信封（认祖归宗用：临时本地 id 迁入服务端 bundle_id 后，
  /// 临时条目必须删除，绝不两条并存——并存会让同一份密钥挂两个身份，回放取钥
  /// 就出现了歧义分支）。
  Future<int> discard(String bundleId, int keyVer) async {
    return db.delete(
      'collab_key_ring',
      where: 'bundle_id = ? AND key_ver = ?',
      whereArgs: [bundleId, keyVer],
    );
  }

  /// 把明文 K_bundle 用 MSK 封送后落 `collab_key_ring`（同版本**幂等**，不覆盖）。
  ///
  /// 主键 (bundle_id, key_ver)：REKEY 产生的 key_ver 递增会追加新行，旧版本信封保留
  /// 供离线回放历史明文（relay user_key_envelopes 同口径）。同版本重复落按已扣等值
  /// 处理——真轮转由服务端 key_ver 驱动，端侧不该遇到同版本不同密钥（见 [KeyRing]）。
  Future<void> persist(String bundleId, int keyVer, List<int> key) async {
    final sealed = await vault.seal(Uint8List.fromList(key));
    await db.insert(
      'collab_key_ring',
      {
        'bundle_id': bundleId,
        'key_ver': keyVer,
        'envelope': utf8.decode(sealed),
        'created_at': DateTime.now().millisecondsSinceEpoch,
      },
      conflictAlgorithm: ConflictAlgorithm.ignore,
    );
  }
}
