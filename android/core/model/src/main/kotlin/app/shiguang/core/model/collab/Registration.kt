package app.shiguang.core.model.collab

import app.shiguang.core.model.collab.relay.buildRegisterBody
import app.shiguang.core.model.collab.relay.fetchChallenge
import app.shiguang.core.model.collab.relay.registerUser
import okhttp3.OkHttpClient

/** 注册流编排结果（lib/collab/registration.dart Kotlin 等价）。 */
data class RegistrationResult(
    val userId: String,
    val username: String,
    val opkCount: Int,
)

/**
 * 端到端注册（账号体系 Plan A，2026-10-11 改版）：
 *
 * 1. 确保 MSK 就绪（端侧自动托管，无需用户输入）；
 * 2. 生成身份材料（Ed25519 身份钥 + X25519 SPK + OPK 首批 10~20）；
 * 3. 领 challenge → 解 PoW；
 * 4. 用**登录密码派生的 KEK**（Argon2id，独立 salt/context）封存 MSK+身份私钥+SPK
 *    私钥为 recovery_vault 回传服务端盲存（v3 拍板：换机恢复=密码解库，改密即重封
 *    ——见 [RecoveryVaultCodec]）；
 * 5. 组装并 POST /v1/users（username + password + 公钥材料）；
 * 6. 回执入账本地 [IdentityStore]（user_id 由服务端发号）。
 *
 * 密码明文只在内存中使用（派生 KEK + 注册请求体），本地不落任何派生形态。
 */
suspend fun registerAccount(
    keyStore: KeyStore,
    identityStore: IdentityStore,
    client: OkHttpClient,
    baseUrl: String,
    username: String,
    password: String,
    opkCount: Int = 20,
    pushToken: String? = null,
): RegistrationResult {
    keyStore.ensureReady()

    val id = generateIdentity(opkCount = opkCount)

    val challenge = fetchChallenge(client, baseUrl)
    val powSolution = solvePoW(challenge.powChallenge, challenge.powDifficulty)

    // v3 换机恢复金库：密码派生 KEK 封存 MSK+身份私钥+SPK 私钥（三段 32B 定长）。
    val msk = keyStore.mskBytes ?: throw IllegalStateException("MSK 未就绪")
    val recoveryVault = RecoveryVaultCodec().sealVault(
        password = password,
        msk = msk,
        identityPriv = id.identityKeySeed,
        spkPriv = id.signedPreKey,
    )

    // 本机日常用的封存体仍走 MSK（KeyStore.seal），与恢复金库口径分开：
    // 本机解封零密码无感，换机才用密码解 recovery_vault。
    val sealedIdPriv = keyStore.seal(id.identityKeySeed)
    val sealedSpkPriv = keyStore.seal(id.signedPreKey)

    val body = buildRegisterBody(
        username = username,
        password = password,
        identityKeyPub = id.identityPublicKey,
        signedPrekeyPub = id.signedPreKeyPublic,
        signedPrekeySig = id.signedPreKeySignature(),
        nonce = challenge.nonce,
        powSolution = powSolution,
        oneTimePreKeys = id.oneTimePreKeys,
        pushToken = pushToken,
        recoveryVault = recoveryVault,
    )

    val resp = registerUser(client, baseUrl, body)

    identityStore.save(
        IdentityRecord(
            userId = resp.userId,
            sealedIdentityPrivateKey = sealedIdPriv,
            signedPreKeyPub = id.signedPreKeyPublic,
            opkRemaining = resp.opkCount,
            sealedSignedPreKeyPrivate = sealedSpkPriv,
        ),
    )

    return RegistrationResult(
        userId = resp.userId,
        username = resp.username,
        opkCount = resp.opkCount,
    )
}
