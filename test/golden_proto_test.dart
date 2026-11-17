// P0 黄金向量基准执行器（Dart 侧）——relay proto 序列化字节（lib/collab/proto/ 生成码）。
//
// 职责：把 test/golden/relay_proto_vectors.json 跑通并锁定期望值（不依赖 Kotlin）。
// - 向量 = 跨实现契约数据（Dart 与 Kotlin 双侧消费，Kotlin 对拍在 P2 接入，
//   runtime=protobuf-javalite）；
// - 本执行器逐 case 真跑生成码对拍 wire bytes，凡生成物漂移即红；
// - ⚠ 拍板②语义异常记录：Dart 生成物为 has-语义（显式 set 默认值也编码）——
//   protobuf-javalite 按 proto3 隐式 presence 重生成后本向量预期变红，届时以
//   重生成行为更新向量集（Kotlin 对拍红 ≠ Kotlin 错，先查本向量时点）。
// 向量语义与字段说明见 test/golden/README.md；向量文件为只读契约，实现漂移时
// 唯一出路是修实现，严禁改向量迁就实现（dev-loop 测试写保护）。
import 'dart:convert';
import 'dart:io';

import 'package:fixnum/fixnum.dart' as fixnum;

import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/collab/proto/relay_protocol.pb.dart';

Map<String, dynamic> _loadSet(String name) =>
    jsonDecode(File('test/golden/$name').readAsStringSync())
        as Map<String, dynamic>;

String _hex(List<int> b) =>
    b.map((x) => x.toRadixString(16).padLeft(2, '0')).join();

List<int> _hexOf(String s) => [
      for (var i = 0; i < s.length; i += 2)
        int.parse(s.substring(i, i + 2), radix: 16),
    ];

Envelope _envelopeOf(Map<String, dynamic> f) {
  final msg = Envelope();
  if (f['bundle_id'] != null) msg.bundleId = f['bundle_id'] as String;
  if (f['action'] != null) {
    msg.action =
        ActionType.values.firstWhere((v) => v.name == f['action'] as String);
  }
  if (f['seq_id'] != null) msg.seqId = fixnum.Int64(f['seq_id'] as int);
  if (f['sender_hint'] != null) msg.senderHint = f['sender_hint'] as String;
  if (f['cipher'] != null) msg.cipher = _hexOf(f['cipher'] as String);
  if (f['client_msg_id'] != null) {
    msg.clientMsgId = f['client_msg_id'] as String;
  }
  if (f['client_ts'] != null) msg.clientTs = fixnum.Int64(f['client_ts'] as int);
  if (f['topology_ref'] != null) msg.topologyRef = f['topology_ref'] as String;
  if (f['key_ver'] != null) msg.keyVer = f['key_ver'] as int;
  for (final m
      in ((f['member_key_envelopes'] as List?) ?? const []).cast<Map>()) {
    msg.memberKeyEnvelopes.add(MemberKeyEnvelope()
      ..recipientUserId = m['recipient_user_id'] as String
      ..wrappedKey = _hexOf(m['wrapped_key'] as String)
      ..keyVer = m['key_ver'] as int);
  }
  return msg;
}

void main() {
  final set = _loadSet('relay_proto_vectors.json');
  group('golden: relay-proto-bytes', () {
    for (final c in (set['cases'] as List).cast<Map<String, dynamic>>()) {
      final id = c['id'] as String;
      final input = c['input'] as Map<String, dynamic>;
      final expected = c['expect'] as Map<String, dynamic>;
      switch (c['op'] as String) {
        case 'serialize_envelope':
          test('serialize_envelope $id', () {
            final msg = _envelopeOf(input['fields'] as Map<String, dynamic>);
            final bytes = msg.writeToBuffer();
            expect(_hex(bytes), expected['bytes_hex']);
            // 回读断言（presence/枚举/值）
            final back = Envelope.fromBuffer(bytes);
            final p = expected['parsed'] as Map<String, dynamic>;
            expect(back.hasBundleId(), p['has_bundle_id']);
            expect(back.hasSeqId(), p['has_seq_id']);
            expect(back.hasKeyVer(), p['has_key_ver']);
            expect(back.seqId.toInt(), p['seq_id']);
            expect(back.action.name, p['action']);
            expect(back.hasBundleId() ? back.bundleId : null, p['bundle_id']);
          });
          break;
        case 'serialize_frame':
          test('serialize_frame $id', () {
            if (input['kind'] == 'envelope_from_bytes') {
              // 拍板③：未知字段向前兼容往返
              final bytes = _hexOf(input['bytes_hex'] as String);
              final back = Envelope.fromBuffer(bytes);
              final p = expected['parsed'] as Map<String, dynamic>;
              expect(back.bundleId, p['bundle_id']);
              expect(back.action.name, p['action']);
              expect(back.seqId.toInt(), p['seq_id']);
              expect(back.unknownFields.isNotEmpty, p['has_unknown_fields']);
              expect(_hex(back.writeToBuffer()), expected['re_bytes_hex']);
              return;
            }
            final isServer = input['kind'] == 'server_frame';
            final body = input['body'] as Map<String, dynamic>;
            if (isServer) {
              final f = ServerFrame()
                ..seqId = fixnum.Int64(input['seq_id'] as int)
                ..clientMsgId = input['client_msg_id'] as String;
              if (body['kind'] == 'schedule') {
                f.schedule = _envelopeOf(body['envelope'] as Map<String, dynamic>);
              } else if (body['kind'] == 'error') {
                f.error = body['error'] as String;
              } else {
                fail('未知 body kind: ${body['kind']}');
              }
              final bytes = f.writeToBuffer();
              expect(_hex(bytes), expected['bytes_hex']);
              final back = ServerFrame.fromBuffer(bytes);
              expect(back.whichBody().name, expected['parsed']['which_body']);
            } else {
              final f = ClientFrame()
                ..clientMsgId = input['client_msg_id'] as String
                ..clientTs = fixnum.Int64(input['client_ts'] as int);
              if (body['kind'] == 'schedule') {
                f.schedule = _envelopeOf(body['envelope'] as Map<String, dynamic>);
              } else {
                fail('未知 body kind: ${body['kind']}');
              }
              final bytes = f.writeToBuffer();
              expect(_hex(bytes), expected['bytes_hex']);
              final back = ClientFrame.fromBuffer(bytes);
              expect(back.whichBody().name, expected['parsed']['which_body']);
            }
          });
          break;
        default:
          fail('未知 op: ${c['op']}（向量集与执行器失配，须同步更新）');
      }
    }
  });
}
