import 'dart:convert';

import 'package:crypto/crypto.dart';

/// Hashcash 工作量证明（fact §7.1-2 / relay `auth.pow.VerifyPoW`）。
///
/// 定位：只挡最低级扫描器，非主防线（真防线 = 注册孤岛无害化 + 单 IP 限流 + OPK 瘦身）。
/// 校验口径与原端逐字节一致：`SHA256(pow_challenge + pow_solution)` 的十六进制串
/// 前导 '0' 个数不少于难度（默认 4 ≈ 2^16 次尝试，手机端 <50ms）。
String solvePoW(String powChallenge, int difficulty,
    {int maxIterations = 1 << 24}) {
  for (var i = 0; i < maxIterations; i++) {
    final solution = '$i-$powChallenge';
    if (verifyPoW(powChallenge, solution, difficulty)) return solution;
  }
  throw StateError('PoW 在 $maxIterations 次内未解出（难度 $difficulty）');
}

bool verifyPoW(String powChallenge, String powSolution, int difficulty) {
  if (difficulty <= 0) return true;
  final sum = sha256.convert(utf8.encode(powChallenge + powSolution));
  return _toHex(sum.bytes).startsWith('0' * difficulty);
}

String _toHex(List<int> bytes) =>
    bytes.map((b) => b.toRadixString(16).padLeft(2, '0')).join();
