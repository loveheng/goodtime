import 'package:fixnum/fixnum.dart' as fixnum;
import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/collab/proto/relay_protocol.pb.dart';

// Phase 2.1：契约生成物在 Flutter 侧可用（补上 Phase 0.3 延后的「Dart 侧 import 编译通过」验收）。
// 纪律：本文件只验契约可编解码，不承载业务逻辑；契约改动须改 shiguang-relay/relay_protocol.proto
// 后跑 scripts/sync-relay-contract.sh 同步（禁止手改 lib/collab/proto/ 下的生成物）。
void main() {
  test('Envelope 契约往返编解码一致', () {
    final env = Envelope()
      ..bundleId = 'b_1001'
      ..action = ActionType.ATTACH_FACT
      ..seqId = fixnum.Int64(7)
      ..senderHint = 'me'
      ..cipher = <int>[0xDE, 0xAD, 0xBE, 0xEF]
      ..clientMsgId = 'msg-1';

    final bytes = env.writeToBuffer();
    final back = Envelope.fromBuffer(bytes);

    expect(back.bundleId, 'b_1001');
    expect(back.action, ActionType.ATTACH_FACT);
    expect(back.seqId.toInt(), 7);
    expect(back.cipher, <int>[0xDE, 0xAD, 0xBE, 0xEF]);
  });

  test('ServerFrame oneof 携带 Envelope', () {
    final frame = ServerFrame()
      ..seqId = fixnum.Int64(7)
      ..clientMsgId = 'msg-1'
      ..schedule = (Envelope()
        ..bundleId = 'b_1001'
        ..action = ActionType.ACCEPT
        ..seqId = fixnum.Int64(7));

    final back = ServerFrame.fromBuffer(frame.writeToBuffer());
    expect(back.whichBody(), ServerFrame_Body.schedule);
    expect(back.schedule.bundleId, 'b_1001');
    expect(back.schedule.action, ActionType.ACCEPT);
  });

  test('ClientFrame 可承载 CLIENT_ACK', () {
    final frame = ClientFrame()
      ..clientMsgId = 'ack-1'
      ..clientTs = fixnum.Int64(1760000000)
      ..schedule = (Envelope()
        ..bundleId = 'b_1001'
        ..action = ActionType.CLIENT_ACK
        ..seqId = fixnum.Int64(5));

    final back = ClientFrame.fromBuffer(frame.writeToBuffer());
    expect(back.whichBody(), ClientFrame_Body.schedule);
    expect(back.schedule.action, ActionType.CLIENT_ACK);
    expect(back.schedule.seqId.toInt(), 5);
  });
}
