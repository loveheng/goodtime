import 'dart:typed_data';

/// Key Ring：每个方案包的密钥版本链（协同设计 §5.2 / §5.3.1）。
///
/// REKEY 会产生版本链，回放引擎必须能解任意 seq 的事件——
/// 事件携带明文外层 `key_ver`，按版本查环解密；
/// 缺对应版本且 Vault 无法还原 → 该段历史标「无法解密」灰显，FSM 跳过内容但骨架仍推进。
class KeyRing {
  final Map<String, Map<int, Uint8List>> _ring = {};

  /// 存入某方案包某版本的 K_bundle。同版本覆盖视为轮转异常，此处以静默覆盖处理
  ///（真轮转由服务端 key_ver 递增驱动，端侧不该遇到同版本不同密钥）。
  void put(String bundleId, int keyVer, Uint8List key) {
    _ring.putIfAbsent(bundleId, () => {})[keyVer] = Uint8List.fromList(key);
  }

  Uint8List? get(String bundleId, int keyVer) => _ring[bundleId]?[keyVer];

  /// 该方案包已知的最高版本（未知返回 null）。
  int? latestVersion(String bundleId) {
    final versions = _ring[bundleId]?.keys;
    if (versions == null || versions.isEmpty) return null;
    return versions.reduce((a, b) => a > b ? a : b);
  }

  /// 明文清零（退后台/锁屏）。
  void clear() => _ring.clear();

  int get bundleCount => _ring.length;
}
