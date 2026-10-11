import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:fixnum/fixnum.dart';

import '../pow.dart';
import '../proto/relay_protocol.pb.dart';

/// 忠实复刻 relay 契约的**进程内 mock 服务端**（仅测试用，SSOT 仍是 Go relayd）。
///
/// 覆盖账号体系（Plan A，含 2026-10-11 v2 语义）+ 双机对测所需全链路：
/// - 账号：challenge / login（用户名+密码 + 账号锁定防爆破）/ refresh（**强制轮换**，
///   旧票重用 → 401 `TOKEN_REVOKED`）/ change-password（**纪元踢会话**：全端 token
///   即时失效）/ logout（双 jti 拉黑，幂等 200）/ `GET|PATCH /v1/users/me`。
/// - 好友：request（搜索路径）/ invite（扫码路径）/ accept / GET /v1/friends /
///   `DELETE /v1/friends/{id}`（解除三合一）/ `POST /v1/friends/{id}/block`。
/// - X3DH：`GET /v1/users/{id}/prekeys`（预密钥包）；`GET /v1/sync` 拉增量。
/// - WSS `/ws`：首帧 JSON `AUTH(token)` → 二进制 ClientFrame 信封中继。
///
/// 已知简化（对客户端行为无影响）：改密踢会话直接删除该用户全部 token——客户端
/// 看到的是 401（服务端细分 `UNAUTHORIZED`/`TOKEN_STALE`，客户端统一按 401 降级）。
class RelayMockServer {
  final Map<String, _MockUser> _users = {}; // userId → 用户（含公钥材料）
  final Map<String, String> _userIds = {}; // username → userId
  final Map<String, _Cred> _credentials = {}; // username → 凭据
  final Map<String, _Relation> _relations = {}; // 'a|b'（字典序）→ 关系行
  final Map<String, _Nonce> _nonces = {};
  final Map<String, _Token> _tokens = {}; // 存活 token → 持有者
  final Set<String> _revokedTokens = {}; // jti 拉黑（轮换重用/登出）
  final Map<String, int> _failedLogins = {}; // username → 连续失败次数
  final Map<String, int> _bundleSeq = {};
  final Map<String, List<ServerFrame>> _bundleEvents = {};
  final List<_Conn> _conns = [];
  final Random _rng = Random.secure();

  late final HttpServer _server;
  late final String baseUrl;

  Future<void> start() async {
    _server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
    baseUrl = 'http://${_server.address.host}:${_server.port}';
    _server.listen(_onRequest);
  }

  Future<void> close() => _server.close(force: true);

  /// 已通过 AUTH 的连接对应的 userId 列表（测试用，便于等待双方就绪）。
  List<String> get authedUsers =>
      _conns.map((c) => c.userId).whereType<String>().toList();

  Future<void> _onRequest(HttpRequest req) async {
    try {
      if (req.uri.path == '/ws' && WebSocketTransformer.isUpgradeRequest(req)) {
        final ws = await WebSocketTransformer.upgrade(req);
        _onWs(ws);
        return;
      }
      final body = await _readJson(req);
      final seg = req.uri.pathSegments;
      if (req.method == 'POST' && req.uri.path == '/v1/auth/challenge') {
        return _challenge(req, body);
      }
      if (req.method == 'POST' && req.uri.path == '/v1/auth/login') {
        return _login(req, body);
      }
      if (req.method == 'POST' && req.uri.path == '/v1/auth/refresh') {
        return _refresh(req, body);
      }
      if (req.method == 'POST' && req.uri.path == '/v1/auth/change-password') {
        return _changePassword(req, body);
      }
      if (req.method == 'POST' && req.uri.path == '/v1/auth/logout') {
        return _logout(req, body);
      }
      if (req.method == 'POST' && req.uri.path == '/v1/users') {
        return _register(req, body);
      }
      if (req.method == 'GET' && req.uri.path == '/v1/users/me') {
        return _getMe(req);
      }
      if (req.method == 'PATCH' && req.uri.path == '/v1/users/me') {
        return _patchMe(req, body);
      }
      if (req.method == 'GET' && req.uri.path == '/v1/users/lookup') {
        return _lookup(req);
      }
      if (req.method == 'POST' && req.uri.path == '/v1/friends/request') {
        return _friendRequest(req, body);
      }
      if (req.method == 'POST' && req.uri.path == '/v1/friends/invite') {
        return _friendInvite(req, body);
      }
      if (req.method == 'POST' &&
          seg.length == 4 &&
          seg[0] == 'v1' &&
          seg[1] == 'friends' &&
          seg[3] == 'block') {
        return _blockFriend(req, seg[2]);
      }
      if (req.method == 'DELETE' &&
          seg.length == 3 &&
          seg[0] == 'v1' &&
          seg[1] == 'friends') {
        return _removeFriend(req, seg[2]);
      }
      if (req.method == 'POST' && req.uri.path == '/v1/friends/accept') {
        return _acceptFriend(req, body);
      }
      if (req.method == 'GET' && req.uri.path == '/v1/friends') {
        return _listFriends(req);
      }
      if (req.method == 'GET' && req.uri.pathSegments.length == 3 &&
          req.uri.pathSegments[0] == 'v1' &&
          req.uri.pathSegments[1] == 'users' &&
          req.uri.pathSegments[2] == 'prekeys') {
        // 注意：真实路由是 /v1/users/{id}/prekeys，这里用 query 简化
        return _prekeys(req, req.uri.queryParameters['user_id'] ?? '');
      }
      if (req.method == 'GET' && req.uri.path == '/v1/sync') {
        return _sync(req, req.uri.queryParameters);
      }
      _json(req, 404, {'error_code': 'NOT_FOUND'});
    } catch (e) {
      try {
        _json(req, 500, {'error_code': 'MOCK_ERROR', 'detail': '$e'});
      } catch (_) {}
    }
  }

  // ---- 账号 ----

  void _challenge(HttpRequest req, Map<String, dynamic> body) {
    final n = _randHex(32);
    final pow = _randHex(16);
    _nonces[n] = _Nonce(pow: pow);
    _json(req, 200, {
      'nonce': n,
      'pow_challenge': pow,
      'pow_difficulty': 2,
      'expires_at':
          DateTime.now().add(const Duration(minutes: 1)).millisecondsSinceEpoch ~/
              1000,
    });
  }

  void _login(HttpRequest req, Map<String, dynamic> body) {
    final username =
        (body['username'] as String? ?? '').trim().toLowerCase();
    final password = body['password'] as String? ?? '';
    // 账号锁定防爆破：窗口内失败 ≥5 次连正确密码也 429（成功登录清零）
    if ((_failedLogins[username] ?? 0) >= 5) {
      return _json(req, 429, {
        'error_code': 'LOGIN_THROTTLED',
        'message': 'account temporarily locked after too many failed logins',
        'detail': {'retry_after_sec': 900},
      });
    }
    final cred = _credentials[username];
    // 用户不存在与密码错误同一 401 口径（防账号枚举，复刻 register 口径）
    if (cred == null || cred.password != password) {
      _failedLogins[username] = (_failedLogins[username] ?? 0) + 1;
      return _json(req, 401, {'error_code': 'USER_OR_PASSWORD_INVALID'});
    }
    _failedLogins.remove(username);
    final (access, refresh) = _issueTokens(cred.userId);
    _json(req, 200, {
      'access_token': access,
      'refresh_token': refresh,
      'access_expires_at': _nowSec() + 7200,
    });
  }

  void _refresh(HttpRequest req, Map<String, dynamic> body) {
    final rt = body['refresh_token'] as String? ?? '';
    final t = _tokens[rt];
    if (_revokedTokens.contains(rt)) {
      // 轮换重用 / 登出拉黑：盗号止损，绝不换新
      return _json(req, 401, {'error_code': 'TOKEN_REVOKED'});
    }
    if (t == null || t.exp < _nowSec()) {
      return _json(req, 401, {'error_code': 'UNAUTHORIZED'});
    }
    // rotation：旧 refresh jti 拉黑至原到期时刻，新对签发
    _revokedTokens.add(rt);
    final (access, refresh) = _issueTokens(t.userId);
    _json(req, 200, {
      'access_token': access,
      'refresh_token': refresh,
      'access_expires_at': _nowSec() + 7200,
    });
  }

  void _changePassword(HttpRequest req, Map<String, dynamic> body) {
    final caller = _callerOf(req);
    if (caller == null) {
      return _json(req, 401, {'error_code': 'UNAUTHORIZED'});
    }
    final username = _users[caller]!.username;
    final cred = _credentials[username]!;
    if (cred.password != (body['old_password'] as String? ?? '')) {
      return _json(req, 401, {'error_code': 'PASSWORD_MISMATCH'});
    }
    final newPw = body['new_password'] as String? ?? '';
    if (newPw.length < 8) {
      return _json(req, 400, {'error_code': 'PASSWORD_TOO_WEAK'});
    }
    _credentials[username] = _Cred(userId: caller, password: newPw);
    // v3 拍板：改密即原子重封——new_recovery_vault 与新哈希同请求落库
    //（缺省字段=不改金库，与真服务端 UpdatePasswordWithVault 口径一致）。
    final newVault = body['new_recovery_vault'] as String?;
    if (newVault != null) {
      _users[caller]!.recoveryVault = base64Decode(newVault);
    }
    // 纪元 Bump（踢会话）：该用户全部存活 token 即时失效（mock 直接删除；
    // 真服务端细分 TOKEN_STALE，客户端统一按 401 降级，行为一致）
    _tokens.removeWhere((_, t) => t.userId == caller);
    _json(req, 200, {'status': 'ok'});
  }

  void _logout(HttpRequest req, Map<String, dynamic> body) {
    final h = req.headers.value('authorization') ?? '';
    // 登出永远 200：非法/过期 token 静默忽略，幂等无害
    if (h.startsWith('Bearer ')) {
      final at = h.substring(7);
      if (_tokens.containsKey(at)) _revokedTokens.add(at);
      final rt = body['refresh_token'] as String?;
      if (rt != null && rt.isNotEmpty && _tokens.containsKey(rt)) {
        _revokedTokens.add(rt);
      }
    }
    _json(req, 200, {'status': 'ok'});
  }

  void _register(HttpRequest req, Map<String, dynamic> body) {
    // 1) 用户名/密码策略（register.go 同口径）
    final username =
        (body['username'] as String? ?? '').trim().toLowerCase();
    if (!RegExp(r'^[a-z0-9_]{3,32}$').hasMatch(username)) {
      return _json(req, 400, {'error_code': 'USERNAME_INVALID'});
    }
    if ((body['password'] as String? ?? '').length < 8) {
      return _json(req, 400, {'error_code': 'PASSWORD_TOO_WEAK'});
    }
    if (_userIds.containsKey(username)) {
      return _json(req, 409, {'error_code': 'USERNAME_EXISTS'});
    }
    // 2) nonce 一次性消费 + PoW
    final stored = _nonces.remove(body['nonce'] as String?);
    if (stored == null) {
      return _json(req, 401, {'error_code': 'CHALLENGE_INVALID'});
    }
    if (!verifyPoW(stored.pow, body['pow_solution'] as String? ?? '', 2)) {
      return _json(req, 401, {'error_code': 'POW_INVALID'});
    }
    // 3) E2EE 公钥材料（服务端纯盲存）
    final idPub = base64Decode(body['identity_key_pub'] as String);
    if (idPub.length != 32) {
      return _json(req, 400, {'error_code': 'INVALID_REQUEST'});
    }
    final opks = body['one_time_prekeys'] as List;
    if (opks.length < 10 || opks.length > 20) {
      return _json(req, 400, {'error_code': 'PREKEY_COUNT_INVALID'});
    }
    final userId = 'u-${_randHex(8)}';
    _users[userId] = _MockUser(
      username: username,
      ikPub: body['identity_key_pub'] as String,
      spkPub: body['signed_prekey_pub'] as String,
      spkSig: body['signed_prekey_sig'] as String,
      opks: opks
          .map((o) => {
                'key_id': (o as Map)['key_id'] as String,
                'pub': o['pub'] as String,
                'signature': o['signature'] as String,
              })
          .toList(),
    );
    _userIds[username] = userId;
    _credentials[username] =
        _Cred(userId: userId, password: body['password'] as String);
    // v3 拍板：注册即上传密码派生 KEK 封存的恢复金库（服务端纯盲存）。
    final regVault = body['recovery_vault'] as String?;
    if (regVault != null) {
      _users[userId]!.recoveryVault = base64Decode(regVault);
    }
    _json(req, 201, {'user_id': userId, 'username': username, 'opk_count': opks.length});
  }

  void _getMe(HttpRequest req) {
    final caller = _callerOf(req);
    if (caller == null) {
      return _json(req, 401, {'error_code': 'UNAUTHORIZED'});
    }
    final u = _users[caller]!;
    final j = <String, dynamic>{
      'user_id': caller,
      'username': u.username,
      'created_at': 1700000000,
    };
    if (u.encryptedProfile != null) {
      j['encrypted_profile'] = base64Encode(u.encryptedProfile!);
    }
    if (u.recoveryVault != null) {
      j['recovery_vault'] = base64Encode(u.recoveryVault!);
    }
    _json(req, 200, j);
  }

  void _patchMe(HttpRequest req, Map<String, dynamic> body) {
    final caller = _callerOf(req);
    if (caller == null) {
      return _json(req, 401, {'error_code': 'UNAUTHORIZED'});
    }
    if (body.isEmpty) {
      return _json(req, 400, {'error_code': 'INVALID_REQUEST'});
    }
    final u = _users[caller]!;
    // present 即整体替换（服务端同口径：指针字段缺省不动）
    if (body.containsKey('encrypted_profile')) {
      u.encryptedProfile = base64Decode(body['encrypted_profile'] as String);
    }
    if (body.containsKey('recovery_vault')) {
      u.recoveryVault = base64Decode(body['recovery_vault'] as String);
    }
    if (body.containsKey('push_token')) {
      u.pushToken = body['push_token'] as String;
    }
    _json(req, 200, {'status': 'ok'});
  }

  void _lookup(HttpRequest req) {
    final caller = _callerOf(req);
    if (caller == null) {
      return _json(req, 401, {'error_code': 'UNAUTHORIZED'});
    }
    final username =
        (req.uri.queryParameters['username'] ?? '').trim().toLowerCase();
    final userId = _userIds[username];
    if (userId == null) {
      return _json(req, 404, {'error_code': 'USER_NOT_FOUND'});
    }
    final u = _users[userId]!;
    _json(req, 200, {
      'user_id': userId,
      'username': u.username,
      'identity_key_pub': u.ikPub,
      'signed_prekey_pub': u.spkPub,
      'signed_prekey_sig': u.spkSig,
    });
  }

  // ---- 好友 ----

  void _friendRequest(HttpRequest req, Map<String, dynamic> body) {
    final caller = _callerOf(req);
    if (caller == null) {
      return _json(req, 401, {'error_code': 'UNAUTHORIZED'});
    }
    final to = body['to_user_id'] as String? ?? '';
    if (to.isEmpty || to == caller) {
      return _json(req, 400, {'error_code': 'INVALID_REQUEST'});
    }
    if (!_users.containsKey(to)) {
      return _json(req, 404, {'error_code': 'USER_NOT_FOUND'});
    }
    final key = _relKey(caller, to);
    if (_relations.containsKey(key)) {
      // 已是好友 / 已有 pending / 被拉黑——同一 409 口径（不泄露拉黑事实）
      return _json(req, 409, {'error_code': 'RELATION_EXISTS'});
    }
    _relations[key] = _Relation(a: caller, b: to, status: 'pending');
    _json(req, 201, {'invite_id': '$caller:$to', 'status': 'pending'});
  }

  void _friendInvite(HttpRequest req, Map<String, dynamic> body) {
    final caller = _callerOf(req);
    if (caller == null) {
      return _json(req, 401, {'error_code': 'UNAUTHORIZED'});
    }
    // mock 不验签 Ticket（409/404 错误面已够测试用）；签发方存在性由 ticket 自带
    final issuer = (body['ticket'] as Map?)?['issuer_user_id'] as String? ?? '';
    if (issuer.isEmpty || !_users.containsKey(issuer)) {
      return _json(req, 400, {'error_code': 'INVALID_REQUEST'});
    }
    final key = _relKey(caller, issuer);
    if (_relations.containsKey(key)) {
      return _json(req, 409, {'error_code': 'RELATION_EXISTS'});
    }
    _relations[key] = _Relation(a: issuer, b: caller, status: 'pending');
    _json(req, 201, {'invite_id': '$caller:$issuer', 'status': 'pending'});
  }

  void _acceptFriend(HttpRequest req, Map<String, dynamic> body) {
    final caller = _callerOf(req);
    if (caller == null) {
      return _json(req, 401, {'error_code': 'UNAUTHORIZED'});
    }
    final parts = (body['invite_id'] as String? ?? '').split(':');
    if (parts.length != 2) {
      return _json(req, 400, {'error_code': 'INVALID_REQUEST'});
    }
    final key = _relKey(parts[0], parts[1]);
    final rel = _relations[key];
    if (rel == null || rel.status != 'pending' || rel.b != caller) {
      return _json(req, 404, {'error_code': 'INVITE_NOT_FOUND'});
    }
    rel.status = body['accept'] == true ? 'accepted' : 'blocked';
    _json(req, 200, {'status': rel.status});
  }

  void _removeFriend(HttpRequest req, String peer) {
    final caller = _callerOf(req);
    if (caller == null) {
      return _json(req, 401, {'error_code': 'UNAUTHORIZED'});
    }
    if (peer.isEmpty || peer == caller) {
      return _json(req, 400, {'error_code': 'INVALID_REQUEST'});
    }
    final removed = _relations.remove(_relKey(caller, peer));
    if (removed == null) {
      return _json(req, 404, {'error_code': 'INVITE_NOT_FOUND'});
    }
    _json(req, 200, {'user_id': peer, 'status': 'removed'});
  }

  void _blockFriend(HttpRequest req, String peer) {
    final caller = _callerOf(req);
    if (caller == null) {
      return _json(req, 401, {'error_code': 'UNAUTHORIZED'});
    }
    if (peer.isEmpty || peer == caller) {
      return _json(req, 400, {'error_code': 'INVALID_REQUEST'});
    }
    if (!_users.containsKey(peer)) {
      return _json(req, 404, {'error_code': 'USER_NOT_FOUND'});
    }
    _relations[_relKey(caller, peer)] =
        _Relation(a: caller, b: peer, status: 'blocked');
    _json(req, 200, {'user_id': peer, 'status': 'blocked'});
  }

  void _listFriends(HttpRequest req) {
    final caller = _callerOf(req);
    if (caller == null) {
      return _json(req, 401, {'error_code': 'UNAUTHORIZED'});
    }
    final rows = _relations.values
        .where((r) => r.a == caller || r.b == caller)
        .map((r) => {
              'user_id': r.a == caller ? r.b : r.a,
              'status': r.status,
              'created_at': 1700000000,
            })
        .toList();
    _json(req, 200, {'friends': rows});
  }

  void _prekeys(HttpRequest req, String userId) {
    final u = _users[userId];
    if (u == null) return _json(req, 404, {'error_code': 'USER_NOT_FOUND'});
    _json(req, 200, {
      'user_id': userId,
      'identity_key_pub': u.ikPub,
      'signed_prekey_pub': u.spkPub,
      'signed_prekey_sig': u.spkSig,
      'one_time_prekeys': u.opks,
    });
  }

  void _sync(HttpRequest req, Map<String, String> q) {
    final bundleId = q['bundle_id'] ?? '';
    final after = int.tryParse(q['after'] ?? '0') ?? 0;
    final events = _bundleEvents[bundleId] ?? [];
    final out = events
        .where((e) => e.seqId.toInt() > after)
        .map((e) => base64Encode(e.writeToBuffer()))
        .toList();
    _json(req, 200, {'events': out});
  }

  /// Bearer access token → userId（无效/过期/已拉黑即 null）。
  String? _callerOf(HttpRequest req) {
    final h = req.headers.value('authorization') ?? '';
    if (!h.startsWith('Bearer ')) return null;
    final at = h.substring(7);
    if (_revokedTokens.contains(at)) return null;
    final t = _tokens[at];
    if (t == null || t.exp < _nowSec()) return null;
    return t.userId;
  }

  String _relKey(String a, String b) =>
      a.compareTo(b) <= 0 ? '$a|$b' : '$b|$a';

  (String, String) _issueTokens(String userId) {
    final access = _randHex(24);
    final refresh = _randHex(24);
    final now = _nowSec();
    _tokens[access] = _Token(userId: userId, exp: now + 7200);
    _tokens[refresh] = _Token(userId: userId, exp: now + 2592000);
    return (access, refresh);
  }

  // ---- WSS ----

  void _onWs(WebSocket ws) {
    String? userId;
    final conn = _Conn(ws);
    ws.listen((msg) async {
      if (userId == null) {
        // 首帧：JSON AUTH（拉黑/失效 token 拒入，同 REST 口径）
        if (msg is! String) {
          await ws.close(1008, 'expected AUTH');
          return;
        }
        try {
          final j = jsonDecode(msg) as Map<String, dynamic>;
          if (j['action'] != 'AUTH') {
            await ws.close(1008, 'expected AUTH');
            return;
          }
          final tok = j['token'] as String? ?? '';
          final t = _tokens[tok];
          if (_revokedTokens.contains(tok) || t == null || t.exp < _nowSec()) {
            await ws.close(1008, 'bad token');
            return;
          }
          userId = t.userId;
          conn.userId = userId!;
          _conns.add(conn);
        } catch (_) {
          ws.close(1008, 'bad AUTH');
        }
        return;
      }
      if (msg is List<int>) {
        try {
          final cf = ClientFrame.fromBuffer(msg);
          if (!cf.hasSchedule()) return;
          if (cf.schedule.action == ActionType.CLIENT_ACK) return; // 保活忽略
          _publish(conn, cf.schedule);
        } catch (_) {}
      }
    }, onDone: () {
      _conns.remove(conn);
      ws.close();
    }, onError: (_) {
      _conns.remove(conn);
      ws.close();
    });
  }

  void _publish(_Conn from, Envelope env) {
    final seq = (_bundleSeq[env.bundleId] ?? 0) + 1;
    _bundleSeq[env.bundleId] = seq;
    final sf = ServerFrame()
      ..seqId = Int64(seq)
      ..schedule = (Envelope()
        ..action = env.action
        ..bundleId = env.bundleId
        ..seqId = Int64(seq)
        ..senderHint = from.userId ?? ''
        ..cipher = List<int>.from(env.cipher)
        ..keyVer = env.keyVer
        ..clientMsgId = env.clientMsgId
        ..topologyRef = env.topologyRef);
    (_bundleEvents[env.bundleId] ??= []).add(sf);
    // 广播给其余连接（复刻 hub.go 信封中继）
    for (final c in _conns) {
      if (c != from) {
        try {
          c.ws.add(sf.writeToBuffer());
        } catch (_) {}
      }
    }
  }

  // ---- utils ----

  Future<Map<String, dynamic>> _readJson(HttpRequest req) async {
    final raw = await utf8.decoder.bind(req).join();
    return raw.isEmpty
        ? <String, dynamic>{}
        : jsonDecode(raw) as Map<String, dynamic>;
  }

  void _json(HttpRequest req, int status, Map<String, dynamic> body) {
    req.response
      ..statusCode = status
      ..headers.contentType = ContentType.json
      ..write(jsonEncode(body));
    req.response.close();
  }

  int _nowSec() => DateTime.now().millisecondsSinceEpoch ~/ 1000;
  String _randHex(int bytes) =>
      List<int>.generate(bytes * 2, (_) => _rng.nextInt(16))
          .map((n) => n.toRadixString(16))
          .join();
}

class _MockUser {
  final String username;
  final String ikPub;
  final String spkPub;
  final String spkSig;
  final List<Map<String, String>> opks;
  List<int>? encryptedProfile;
  List<int>? recoveryVault;
  String? pushToken;
  _MockUser({
    required this.username,
    required this.ikPub,
    required this.spkPub,
    required this.spkSig,
    required this.opks,
  });
}

class _Relation {
  String a; // 关系一侧（pending/accepted 的发起方）
  String b; // 另一侧（pending 的被邀方）
  String status; // pending | accepted | blocked
  _Relation({required this.a, required this.b, required this.status});
}

class _Nonce {
  final String pow;
  _Nonce({required this.pow});
}

class _Cred {
  final String userId;
  final String password;
  _Cred({required this.userId, required this.password});
}

class _Token {
  final String userId;
  final int exp;
  _Token({required this.userId, required this.exp});
}

class _Conn {
  final WebSocket ws;
  String? userId;
  _Conn(this.ws);
}
