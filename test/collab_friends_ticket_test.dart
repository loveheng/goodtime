import 'dart:convert';

import 'package:cryptography/cryptography.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/collab/friends_ticket.dart';

void main() {
  group('friends_ticket', () {
    test('签发 + 验签 与 relay 规范化消息逐字节一致', () async {
      final kp = await Ed25519().newKeyPair();
      final pub = (await kp.extractPublicKey()).bytes;
      final ticket = await signFriendshipTicket(
        identityKeyPair: kp,
        issuerUserId: 'user-A',
        encKeyPub: [1, 2, 3, 4],
      );
      expect(ticket.issuerUserId, 'user-A');
      expect(ticket.subjectUserId, '');
      expect(ticket.signature.length, 64); // Ed25519 签名固定 64B

      // 用签发人公钥按 relay canonical message 验签，必须过。
      final msg = ticketMessage(
        issuer: ticket.issuerUserId,
        subject: ticket.subjectUserId,
        nonce: ticket.nonce,
        expiresAt: ticket.expiresAt,
      );
      final ok = await Ed25519().verify(
        utf8.encode(msg),
        signature:
            Signature(ticket.signature, publicKey: SimplePublicKey(pub, type: KeyPairType.ed25519)),
      );
      expect(ok, isTrue);
    });

    test('nonce 被篡改则验签失败', () async {
      final kp = await Ed25519().newKeyPair();
      final pub = (await kp.extractPublicKey()).bytes;
      final ticket =
          await signFriendshipTicket(identityKeyPair: kp, issuerUserId: 'A', encKeyPub: const []);
      final bad = ticketMessage(
        issuer: ticket.issuerUserId,
        subject: ticket.subjectUserId,
        nonce: '${ticket.nonce}x',
        expiresAt: ticket.expiresAt,
      );
      final ok = await Ed25519().verify(
        utf8.encode(bad),
        signature:
            Signature(ticket.signature, publicKey: SimplePublicKey(pub, type: KeyPairType.ed25519)),
      );
      expect(ok, isFalse);
    });

    test('QR 编码/解码 字段无损往返', () async {
      final kp = await Ed25519().newKeyPair();
      final ticket = await signFriendshipTicket(
        identityKeyPair: kp,
        issuerUserId: 'A',
        encKeyPub: const [9, 9],
      );
      final s = ticket.toQrString();
      final back = FriendshipTicket.fromQrString(s);
      expect(back.issuerUserId, ticket.issuerUserId);
      expect(back.nonce, ticket.nonce);
      expect(back.expiresAt, ticket.expiresAt);
      expect(back.signature, ticket.signature);
      expect(back.encKeyPub, ticket.encKeyPub);
    });

    test('安全码：顺序无关、6 组 5 位、确定可复现', () async {
      final a = List<int>.generate(32, (i) => i);
      final b = List<int>.generate(32, (i) => 255 - i);
      final s1 = safetyNumberDigits(a, b);
      final s2 = safetyNumberDigits(b, a);
      expect(s1, s2); // 双方算出同一串
      expect(s1.length, 6);
      for (final g in s1) {
        expect(g.length, 5);
        expect(int.parse(g), inInclusiveRange(0, 99999));
      }
    });
  });
}
