/// 被邀端密钥入户（§5.3 K_bundle 分发的收件侧）。
///
/// 发起端把 K_bundle 用「一次性 EK × 被邀人 SPK 公钥」封成信封交给服务端盲存
/// （`propose.wrapBundleKeyForMember`）；被邀端要做的是三件事：
/// 1. `GET /v1/bundles/{id}/key-envelopes` 拉**自己那一份**（服务端按 caller 过滤，
///    别人的信封一条也不回）；
/// 2. 用本地 SPK **私钥**解开——这一步此前全仓做不了，因为 `IdentityRecord` 只封
///    存了身份私钥，没存 SPK 私钥（已在注册侧补齐，新增 `sealed_signed_prekey_priv`）；
/// 3. 落户 `collab_key_ring`（MSK 重新封送，同版本幂等不覆盖）。
///
/// ## 半盲纪律（铁律 1）
///
/// 任一环节取不到都**跳过而非抛**：没落钥的那段历史对本地就是不可解密，骨架照常
/// 推进（回放器已按「缺密钥→跳过」处理），UI 显示骨架提示。绝不为了让列表好看
/// 而写入空密钥或伪造版本。
library;

import 'package:cryptography/cryptography.dart';

import 'crypto/bundle_key_source.dart';
import 'propose.dart' show openBundleKeyEnvelope;
import 'relay/keys_api.dart';

/// 被邀端密钥入户。
class BundleKeyIntake {
  /// 拉信封（真机＝`fetchBundleKeyEnvelopes` 的注入版）。
  final Future<List<KeyEnvelopeView>> Function(String bundleId) fetch;

  /// 本地密钥检索 / 落户（MSK 封送）。
  final BundleKeySource keys;

  /// 本端 X25519 SPK 密钥对（解锁后还原；未解锁即抛，由调用方降级）。
  final Future<SimpleKeyPair> Function() mySignedPreKey;

  const BundleKeyIntake({
    required this.fetch,
    required this.keys,
    required this.mySignedPreKey,
  });

  /// 把该包里「还没落户」的信封逐条解开并落户，返回本次新落户条数。
  ///
  /// [already] 是可选项：调用方若已知道本地有哪些版本（如包头 `key_version`），
  /// 传入可省一次解密；缺省则逐条问 `keys`。
  Future<int> enroll(String bundleId, {Iterable<int>? already}) async {
    final List<KeyEnvelopeView> envelopes;
    try {
      envelopes = await fetch(bundleId);
    } catch (_) {
      return 0; // 拉不到（离线/403/5xx）：下次补拉，骨架照常推进
    }
    if (envelopes.isEmpty) return 0;

    final spk = await mySignedPreKey();
    var enrolled = 0;
    for (final env in envelopes) {
      if (env.wrappedKey.isEmpty) continue;
      if (already?.contains(env.keyVer) ?? false) continue;
      if (await keys(bundleId, env.keyVer) != null) continue; // 已落户：幂等跳过
      try {
        final key = await openBundleKeyEnvelope(
          envelope: env.toEnvelope(),
          mySignedPreKey: spk,
        );
        if (key.length != 32) continue;
        await keys.persist(bundleId, env.keyVer, key);
        enrolled++;
      } catch (_) {
        continue; // MAC 校验失败（不是给我的信封/密钥换了）：静默跳过
      }
    }
    return enrolled;
  }
}
