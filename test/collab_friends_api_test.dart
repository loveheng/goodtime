import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shiguang/collab/friends_ticket.dart';
import 'package:shiguang/collab/relay/friends_api.dart';

/// 极简 HttpClientAdapter：记录请求、按 path 回 canned 响应，零网络。
class _MockAdapter implements HttpClientAdapter {
  final Map<String, Map<String, dynamic>> byPath;
  final List<RequestOptions> calls = [];
  _MockAdapter(this.byPath);

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    calls.add(options);
    final r = byPath[options.uri.path] ?? {'status': 404, 'body': ''};
    return ResponseBody.fromString(
      r['body'] as String,
      r['status'] as int,
      headers: {Headers.contentTypeHeader: [Headers.jsonContentType]},
    );
  }

  @override
  void close({bool force = false}) {}
}

FriendshipTicket _ticket() => FriendshipTicket(
      issuerUserId: 'B',
      subjectUserId: '',
      nonce: 'n',
      expiresAt: 123,
      signature: const [1],
      encKeyPub: const [2],
    );

void main() {
  group('friends_api', () {
    test('submitFriendInvite 正确投递 ticket 并返回 invite_id', () async {
      final adapter = _MockAdapter({
        '/v1/friends/invite': {
          'status': 201,
          'body': jsonEncode({'invite_id': 'a:b', 'status': 'pending'}),
        },
      });
      final dio = Dio()..httpClientAdapter = adapter;
      final id = await submitFriendInvite(dio, 'https://mock', 'tok', _ticket());
      expect(id, 'a:b');
      expect(adapter.calls, hasLength(1));

      final req = adapter.calls.first;
      expect(req.uri.path, '/v1/friends/invite');
      final auth = req.headers['Authorization'];
      expect(auth is List ? auth.first : auth, 'Bearer tok');
      final body = jsonDecode(req.data as String) as Map<String, dynamic>;
      expect(body['ticket']['issuer_user_id'], 'B');
      expect(body['encrypted_intro'], '');
    });

    test('submitFriendInvite 把 409 归一成 RelayFriendsException', () async {
      final adapter = _MockAdapter({
        '/v1/friends/invite': {
          'status': 409,
          'body': jsonEncode({'error_code': 'RELATION_EXISTS'}),
        },
      });
      final dio = Dio()..httpClientAdapter = adapter;
      expect(
        () => submitFriendInvite(dio, 'https://mock', 'tok', _ticket()),
        throwsA(isA<RelayFriendsException>()),
      );
    });

    test('acceptFriendInvite 正确投递 invite_id 并返回 status', () async {
      final adapter = _MockAdapter({
        '/v1/friends/accept': {
          'status': 200,
          'body': jsonEncode({'user_id': 'a', 'status': 'accepted'}),
        },
      });
      final dio = Dio()..httpClientAdapter = adapter;
      final status = await acceptFriendInvite(dio, 'https://mock', 'tok', 'a:b', true);
      expect(status, 'accepted');

      final body = jsonDecode(adapter.calls.first.data as String) as Map<String, dynamic>;
      expect(body['invite_id'], 'a:b');
      expect(body['accept'], isTrue);
    });

    test('acceptFriendInvite 把 404 归一成 RelayFriendsException', () async {
      final adapter = _MockAdapter({
        '/v1/friends/accept': {
          'status': 404,
          'body': jsonEncode({'error_code': 'INVITE_NOT_FOUND'}),
        },
      });
      final dio = Dio()..httpClientAdapter = adapter;
      expect(
        () => acceptFriendInvite(dio, 'https://mock', 'tok', 'a:b', true),
        throwsA(isA<RelayFriendsException>()),
      );
    });
  });
}
