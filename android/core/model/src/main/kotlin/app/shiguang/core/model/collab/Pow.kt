package app.shiguang.core.model.collab

import java.security.MessageDigest

/**
 * Hashcash 工作量证明（lib/collab/pow.dart Kotlin 等价，fact §7.1-2 /
 * relay auth.pow.VerifyPoW）。
 *
 * 定位：只挡最低级扫描器，非主防线（真防线 = 注册孤岛无害化 + 单 IP 限流 +
 * OPK 瘦身）。校验口径与原端逐字节一致：SHA256(challenge + solution) 的
 * 十六进制串前导 '0' 个数不少于难度（默认 4 ≈ 2^16 次尝试，手机端 <50ms）。
 */
fun solvePoW(powChallenge: String, difficulty: Int, maxIterations: Int = 1 shl 24): String {
    for (i in 0 until maxIterations) {
        val solution = "$i-$powChallenge"
        if (verifyPoW(powChallenge, solution, difficulty)) return solution
    }
    throw IllegalStateException("PoW 在 $maxIterations 次内未解出（难度 $difficulty）")
}

fun verifyPoW(powChallenge: String, powSolution: String, difficulty: Int): Boolean {
    if (difficulty <= 0) return true
    val sum = MessageDigest.getInstance("SHA-256")
        .digest((powChallenge + powSolution).toByteArray(Charsets.UTF_8))
    return toHexLower(sum).startsWith("0".repeat(difficulty))
}

private fun toHexLower(bytes: ByteArray): String =
    bytes.joinToString("") { "%02x".format(it) }
