// P0 黄金向量基准执行器（Dart 侧）——E2EE 封装往返（sealing + freemask）。
//
// 职责：把 test/golden/ 的向量集跑通并锁定期望值（不依赖 Kotlin）。
// - 向量 = 跨实现契约数据（Dart 与 Kotlin 双侧消费，Kotlin 对拍在 P2 接入）；
// - 本执行器逐 op 把向量对拍真实实现，凡实现漂移即红；
// - 附活性往返性质（随机 nonce 路径），覆盖生成器确定性构造之外的真实 seal 路径。
// 向量语义与字段说明见 test/golden/README.md；向量文件为只读契约，实现漂移时
// 唯一出路是修实现，严禁改向量迁就实现（dev-loop 测试写保护）。
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

import 'package:cryptography/cryptography.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/collab/crypto/key_ring.dart';
import 'package:shiguang/collab/freemask.dart';
import 'package:shiguang/collab/sealing.dart';

// ---- 编解码小工具（与生成器解耦的独立副本，Kotlin 执行器同款自包含） ---------
Uint8List hexOf(String s) {
  final out = Uint8List(s.length ~/ 2);
  for (var i = 0; i < out.length; i++) {
    out[i] = int.parse(s.substring(i * 2, i * 2 + 2), radix: 16);
  }
  return out;
}

String toHex(List<int> b) =>
    b.map((x) => x.toRadixString(16).padLeft(2, '0')).join();

/// hex → base64：真实现信封字段为 base64 形态（freemask.dart），
/// 向量集统一记 hex，过实现前按字段转码。
String b64OfHex(String h) => base64Encode(hexOf(h));

/// freemask ECIES 信封（hex 向量形态）→ 真实现信封（base64 形态）。
Map<String, String> _eciesEnvelopeOf(Map<String, dynamic> hexEnv) => {
      'ek_pub': b64OfHex(hexEnv['ek_pub'] as String),
      'nonce': b64OfHex(hexEnv['nonce'] as String),
      'cipher_text': b64OfHex(hexEnv['cipher_text'] as String),
      'mac': b64OfHex(hexEnv['mac'] as String),
    };

final _aead = AesGcm.with256bits();
final _x25519 = X25519();

Map<String, dynamic> _loadSet(String name) =>
    jsonDecode(File('test/golden/$name').readAsStringSync())
        as Map<String, dynamic>;

SealedPayload _envelopeOf(Map<String, dynamic> env) => SealedPayload(
      keyVer: env['key_ver'] as int,
      nonce: hexOf(env['nonce'] as String),
      cipherText: hexOf(env['cipher_text'] as String),
      mac: hexOf(env['mac'] as String),
    );

/// 按向量语义状态码断言拒绝行为。
Matcher _rejectOf(Map<String, dynamic> statusMap) {
  switch (statusMap['status'] as String) {
    case 'reject_argument':
      return throwsA(isA<ArgumentError>());
    case 'reject_auth':
      return throwsA(isA<SecretBoxAuthenticationError>());
    case 'reject_format':
      return throwsA(anything); // 异常类非契约（2026-11-14 拍板⑤）
    default:
      throw StateError('未知语义状态码: ${statusMap['status']}');
  }
}

void main() {
  // ============================================================================
  // sealing 向量集
  // ============================================================================
  final sealingSet = _loadSet('e2ee_sealing_vectors.json');
  group('golden: e2ee-sealing', () {
    for (final c in (sealingSet['cases'] as List).cast<Map<String, dynamic>>()) {
      final id = c['id'] as String;
      final input = c['input'] as Map<String, dynamic>;
      final expected = c['expect'] as Map<String, dynamic>;
      switch (c['op'] as String) {
        case 'pad_512':
          // 文档化 pad 规则的验证走 open 链：向量 padded → 同钥自洽包成信封 →
          // 真实现 openPayload 须 unpad 回 input.plaintext（规则与实现失配即红）。
          test('pad_512 $id（经 open 链验证）', () async {
            const runnerKey = '5152535455565758595a5b5c5d5e5f60'
                '6162636465666768696a6b6c6d6e6f70';
            const runnerNonce = '101112131415161718191a1b';
            final key = hexOf(runnerKey);
            final nonce = hexOf(runnerNonce);
            final box = await _aead.encrypt(
              hexOf(expected['padded'] as String),
              secretKey: SecretKey(key),
              nonce: nonce,
            );
            final env = SealedPayload(
              keyVer: 0,
              nonce: nonce,
              cipherText: box.cipherText,
              mac: box.mac.bytes,
            );
            final opened = await openPayload(key, env);
            expect(toHex(opened), input['plaintext']);
          });
          break;
        case 'aesgcm_encrypt':
          test('aesgcm_encrypt $id', () async {
            final box = await _aead.encrypt(
              hexOf(input['padded'] as String),
              secretKey: SecretKey(hexOf(input['key'] as String)),
              nonce: hexOf(input['nonce'] as String),
            );
            expect(toHex(box.cipherText), expected['cipher_text']);
            expect(toHex(box.mac.bytes), expected['mac']);
          });
          break;
        case 'seal_composite':
          test('seal_composite $id（信封重组 → 真实现 open）', () async {
            final env = SealedPayload(
              keyVer: input['key_ver'] as int,
              nonce: hexOf(input['nonce'] as String),
              cipherText: hexOf(expected['cipher_text'] as String),
              mac: hexOf(expected['mac'] as String),
            );
            final opened = await openPayload(hexOf(input['key'] as String), env);
            expect(toHex(opened), input['plaintext']);
            // 活性 seal 路径同明文往返（随机 nonce，性质校验）
            final live = await sealPayload(
                hexOf(input['key'] as String),
                hexOf(input['plaintext'] as String),
                input['key_ver'] as int);
            expect(await openPayload(hexOf(input['key'] as String), live),
                hexOf(input['plaintext'] as String));
          });
          break;
        case 'envelope_json':
          test('envelope_json $id（编解码见证）', () async {
            final decoded = SealedPayload.decode(expected['json'] as String);
            expect(decoded.keyVer, input['key_ver']);
            expect(toHex(decoded.nonce), input['nonce']);
            expect(toHex(decoded.cipherText), input['cipher_text']);
            expect(toHex(decoded.mac), input['mac']);
            // Dart 自洽：encode 重建与见证串逐位一致（键序 = 产出序）
            expect(decoded.encode(), expected['json']);
          });
          break;
        case 'open':
          test('open $id', () async {
            final key = hexOf(input['key'] as String);
            final env = _envelopeOf(input['envelope'] as Map<String, dynamic>);
            if (expected.containsKey('status')) {
              await expectLater(openPayload(key, env), _rejectOf(expected));
            } else {
              final opened = await openPayload(key, env);
              expect(toHex(opened), expected['plaintext']);
            }
          });
          break;
        case 'seal_len':
          test('seal_len $id（密文长度性质，nonce 无关）', () async {
            final live = await sealPayload(
                List<int>.filled(32, 7),
                hexOf(input['plaintext'] as String),
                1);
            expect(live.cipherText.length, expected['cipher_text_len']);
            expect(live.cipherText.length % 512, 2);
          });
          break;
        case 'envelope_malformed':
          test('envelope_malformed $id（畸形信封必拒，异常类非契约）', () {
            expect(
              () => SealedPayload.decode(input['json'] as String),
              _rejectOf(expected),
            );
          });
          break;
        case 'keyring_get_unknown':
          test('keyring_get_unknown $id（查无版本 → null，2026-11-14 拍板⑥）', () {
            final ring = KeyRing();
            for (final e
                in (input['setup'] as List).cast<Map<String, dynamic>>()) {
              ring.put(e['bundle'] as String, e['key_ver'] as int,
                  Uint8List.fromList(hexOf(e['key'] as String)));
            }
            final found = ring.get(
                input['probe_bundle'] as String, input['probe_key_ver'] as int);
            expect(found, isNull);
          });
          break;
        case 'seal_guard':
          test('seal_guard $id', () async {
            await expectLater(
              sealPayload(
                hexOf(input['key'] as String),
                hexOf(input['plaintext'] as String),
                input['key_ver'] as int,
              ),
              _rejectOf(expected),
            );
          });
          break;
        default:
          fail('未知 op: ${c['op']}（向量集与执行器失配，须同步更新）');
      }
    }
  });

  // ============================================================================
  // freemask 向量集
  // ============================================================================
  final freemaskSet = _loadSet('e2ee_freemask_vectors.json');
  group('golden: e2ee-freemask', () {
    for (final c in (freemaskSet['cases'] as List).cast<Map<String, dynamic>>()) {
      final id = c['id'] as String;
      final input = c['input'] as Map<String, dynamic>;
      final expected = c['expect'] as Map<String, dynamic>;
      switch (c['op'] as String) {
        case 'x25519_publickey':
          test('x25519_publickey $id', () async {
            final pair = await _x25519.newKeyPairFromSeed(
                hexOf(input['seed'] as String));
            final pub = await pair.extractPublicKey();
            expect(toHex(pub.bytes), expected['pub']);
          });
          break;
        case 'x25519_dh':
          test('x25519_dh $id（裸输出，无 KDF）', () async {
            final ek =
                await _x25519.newKeyPairFromSeed(hexOf(input['ek_seed'] as String));
            final shared = await _x25519.sharedSecretKey(
              keyPair: ek,
              remotePublicKey: SimplePublicKey(
                  hexOf(input['spk_pub'] as String),
                  type: KeyPairType.x25519),
            );
            expect(toHex(await shared.extractBytes()), expected['shared']);
          });
          break;
        case 'quantize':
          test('quantize $id', () async {
            final busy = (input['busy'] as List)
                .map((e) => ((e as List)[0] as int, e[1] as int))
                .toList();
            if (expected.containsKey('status')) {
              expect(
                () => quantizeFreeMask(
                  targetStartMs: input['target_start'] as int,
                  targetEndMs: input['target_end'] as int,
                  busy: busy,
                ),
                _rejectOf(expected),
              );
            } else {
              expect(
                quantizeFreeMask(
                  targetStartMs: input['target_start'] as int,
                  targetEndMs: input['target_end'] as int,
                  busy: busy,
                ),
                expected['mask'],
              );
            }
          });
          break;
        case 'bitwise_and':
          test('bitwise_and $id', () {
            final masks = (input['masks'] as List).cast<String>();
            if (expected.containsKey('status')) {
              expect(() => freeMaskBitwiseAnd(masks), _rejectOf(expected));
            } else {
              expect(freeMaskBitwiseAnd(masks), expected['mask']);
            }
          });
          break;
        case 'ecies_seal':
          test('ecies_seal $id（信封重组 → 真实现 open）', () async {
            final spk = await _x25519.newKeyPairFromSeed(
                hexOf(input['spk_seed'] as String));
            final env = _eciesEnvelopeOf({
              'ek_pub': expected['ek_pub'],
              'nonce': input['nonce'],
              'cipher_text': expected['cipher_text'],
              'mac': expected['mac'],
            });
            final opened = await openFreeMaskFromMember(
              sealed: env,
              initiatorSpk: spk,
            );
            final signal = input['signal'] as Map<String, dynamic>;
            expect(opened.targetStartMs, signal['target_start']);
            expect(opened.targetEndMs, signal['target_end']);
            expect(opened.mask, signal['mask']);
            // 明文序列化契约：signal JSON 紧凑形逐位一致（字段序 = interop 契约）
            expect(jsonEncode(signal), input['signal_json']);
            // 活性 seal 路径：新 EK 往返（随机 nonce，性质校验）
            final freshEk = await _x25519.newKeyPair();
            final liveSealed = await sealFreeMaskForInitiator(
              signal: FreeMaskSignal(
                targetStartMs: signal['target_start'] as int,
                targetEndMs: signal['target_end'] as int,
                mask: signal['mask'] as String,
              ),
              senderEphemeral: freshEk,
              initiatorSpkPub: (await spk.extractPublicKey()),
            );
            final liveOpened = await openFreeMaskFromMember(
              sealed: liveSealed,
              initiatorSpk: spk,
            );
            expect(liveOpened.mask, signal['mask']);
          });
          break;
        case 'ecies_open':
          test('ecies_open $id', () async {
            final spk = await _x25519.newKeyPairFromSeed(
                hexOf(input['spk_seed'] as String));
            if (expected.containsKey('status')) {
              await expectLater(
                openFreeMaskFromMember(
                  sealed: _eciesEnvelopeOf(
                      input['envelope'] as Map<String, dynamic>),
                  initiatorSpk: spk,
                ),
                _rejectOf(expected),
              );
            } else {
              final opened = await openFreeMaskFromMember(
                sealed: _eciesEnvelopeOf(
                    input['envelope'] as Map<String, dynamic>),
                initiatorSpk: spk,
              );
              expect(opened.targetStartMs, expected['target_start']);
              expect(opened.targetEndMs, expected['target_end']);
              expect(opened.mask, expected['mask']);
            }
          });
          break;
        default:
          fail('未知 op: ${c['op']}（向量集与执行器失配，须同步更新）');
      }
    }
  });
}
