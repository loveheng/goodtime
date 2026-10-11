import 'dart:typed_data';

import 'package:dio/dio.dart';

import 'crypto/key_store.dart';
import 'identity.dart';
import 'identity_store.dart';
import 'pow.dart';
import 'recovery_vault.dart';
import 'relay/auth_api.dart';

/// 注册流编排结果。
class RegistrationResult {
  final String userId;
  final String username;
  final int opkCount;

  const RegistrationResult({
    required this.userId,
    required this.username,
    required this.opkCount,
  });
}

/// 端到端注册（账号体系 Plan A，2026-10-11 改版）：
///
/// 1. 确保 MSK 就绪（端侧自动托管，无需用户输入）；
/// 2. 生成身份材料（Ed25519 身份钥 + X25519 SPK + OPK 首批 10~20）；
/// 3. 领 challenge → 解 PoW；
/// 4. 用**登录密码派生的 KEK**（Argon2id，独立 salt/context）封存
///    MSK+身份私钥+SPK 私钥为 `recovery_vault` 回传服务端盲存（v3 拍板：换机
///    恢复=密码解库，改密即重封——见 recovery_vault.dart）；
/// 5. 组装并 `POST /v1/users`（username + password + 公钥材料）；
/// 6. 回执入账本地 [IdentityStore]（user_id 由服务端发号）。
///
/// 密码明文只在内存中使用（派生 KEK + 注册请求体），本地不落任何派生形态。
Future<RegistrationResult> registerAccount({
  required KeyStore keyStore,
  required IdentityStore identityStore,
  required Dio dio,
  required String baseUrl,
  required String username,
  required String password,
  int opkCount = 20,
  String? pushToken,
}) async {
  await keyStore.ensureReady();

  final id = await generateIdentity(opkCount: opkCount);

  final challenge = await fetchChallenge(dio, baseUrl);
  final powSolution = solvePoW(challenge.powChallenge, challenge.powDifficulty);

  // v3 换机恢复金库：密码派生 KEK 封存 MSK+身份私钥+SPK 私钥（三段 32B 定长）。
  final msk = keyStore.mskBytes!;
  final idPrivBytes = await id.identityKey.extractPrivateKeyBytes();
  final spkPrivBytes = await id.signedPreKey.extractPrivateKeyBytes();
  final recoveryVault = await RecoveryVaultCodec().sealVault(
    password: password,
    msk: msk,
    identityPriv: Uint8List.fromList(idPrivBytes),
    spkPriv: Uint8List.fromList(spkPrivBytes),
  );

  // 本机日常用的封存体仍走 MSK（KeyStore.seal），与恢复金库口径分开：
  // 本机解封零密码无感，换机才用密码解 recovery_vault。
  final sealedIdPriv = await keyStore.seal(Uint8List.fromList(idPrivBytes));
  final sealedSpkPriv = await keyStore.seal(Uint8List.fromList(spkPrivBytes));

  final body = buildRegisterBody(
    username: username,
    password: password,
    identityKeyPub: await id.identityPublicKey,
    signedPrekeyPub: await id.signedPreKeyPublic,
    signedPrekeySig: await id.signedPreKeySignature(),
    nonce: challenge.nonce,
    powSolution: powSolution,
    oneTimePreKeys: id.oneTimePreKeys,
    pushToken: pushToken,
    recoveryVault: recoveryVault,
  );

  final resp = await registerUser(dio, baseUrl, body);

  await identityStore.save(IdentityRecord(
    userId: resp.userId,
    sealedIdentityPrivateKey: sealedIdPriv,
    signedPreKeyPub: await id.signedPreKeyPublic,
    opkRemaining: resp.opkCount,
    sealedSignedPreKeyPrivate: sealedSpkPriv,
  ));

  return RegistrationResult(
    userId: resp.userId,
    username: resp.username,
    opkCount: resp.opkCount,
  );
}
