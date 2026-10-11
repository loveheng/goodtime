/// 好友域 REST 客户端（relay `friends.go` 冻结契约）。
///
/// 两张写接口：
/// 1. `POST /v1/friends/invite`——扫到对方名片 Ticket 后提交，服务端验签 + 验过期 +
///    nonce 一次性 + 配额后才写 `friendships(pending)`（§1.6 防撞库骚扰）；
/// 2. `POST /v1/friends/accept`——仅被邀方可处置（accept=false → blocked）。
///
/// 拉取好友名单见 [peers_api.fetchFriends]（同仓，FriendshipTicket 不在此文件）。
///
/// 错误归一：HTTP 4xx/5xx（带响应体）→ [RelayFriendsException]（带状态码，UI 据此区分
/// 「已加过 / 名片过期 / 登录失效」）；纯网络层失败（无响应）→ 原样抛 DioException，
/// 由上层归为离线。
library;

import 'dart:convert';

import 'package:dio/dio.dart';

import '../friends_ticket.dart';

/// 提交好友邀请（扫到的对方名片）。
///
/// [encryptedIntro] 为「自报家门」密文（ECIES），端到端加密、服务端盲存；可留空——
/// 服务端不因此拒建联，仅 B 端解不开那段字节。
Future<String> submitFriendInvite(
  Dio dio,
  String baseUrl,
  String accessToken,
  FriendshipTicket ticket, {
  String encryptedIntro = '',
}) async {
  try {
    final resp = await dio.post(
      '$baseUrl/v1/friends/invite',
      options: Options(
        headers: {'Authorization': 'Bearer $accessToken'},
        validateStatus: (s) => s == 201,
      ),
      data: jsonEncode({
        'ticket': ticket.toJson(),
        'encrypted_intro': encryptedIntro,
      }),
    );
    if (resp.statusCode != 201) {
      throw RelayFriendsException(resp.statusCode ?? 0, resp.data);
    }
    final j = resp.data as Map<String, dynamic>;
    return j['invite_id'] as String;
  } on DioException catch (e) {
    if (e.response != null) {
      throw RelayFriendsException(
          e.response!.statusCode ?? 0, e.response!.data);
    }
    rethrow;
  }
}

/// 按用户名直发好友申请（账号体系搜索路径，`friends.go` 双通道之一）：
/// 先 `GET /v1/users/lookup` 拿对方 user_id，再 `POST /v1/friends/request`。
/// 两路殊途同归落 pending，被邀方处置接口同 [acceptFriendInvite]。
Future<String> submitFriendRequest(
  Dio dio,
  String baseUrl,
  String accessToken, {
  required String toUserId,
  String encryptedIntro = '',
}) async {
  try {
    final resp = await dio.post(
      '$baseUrl/v1/friends/request',
      options: Options(
        headers: {'Authorization': 'Bearer $accessToken'},
        validateStatus: (s) => s == 201,
      ),
      data: jsonEncode({
        'to_user_id': toUserId,
        'encrypted_intro': encryptedIntro,
      }),
    );
    if (resp.statusCode != 201) {
      throw RelayFriendsException(resp.statusCode ?? 0, resp.data);
    }
    final j = resp.data as Map<String, dynamic>;
    return j['invite_id'] as String;
  } on DioException catch (e) {
    if (e.response != null) {
      throw RelayFriendsException(
          e.response!.statusCode ?? 0, e.response!.data);
    }
    rethrow;
  }
}

/// 解除关系（`DELETE /v1/friends/{user_id}`）：删除好友 / 撤回申请 / 解除拉黑
/// 三合一，双向命中；双方回到陌生状态，此后可重新申请。
/// 无关系 → 404 `INVITE_NOT_FOUND`（抛 [RelayFriendsException]）。
Future<String> removeFriend(
  Dio dio,
  String baseUrl,
  String accessToken,
  String userId,
) async {
  try {
    final resp = await dio.delete(
      '$baseUrl/v1/friends/$userId',
      options: Options(
        headers: {'Authorization': 'Bearer $accessToken'},
        validateStatus: (s) => s == 200,
      ),
    );
    if (resp.statusCode != 200) {
      throw RelayFriendsException(resp.statusCode ?? 0, resp.data);
    }
    final j = resp.data as Map<String, dynamic>;
    return j['status'] as String;
  } on DioException catch (e) {
    if (e.response != null) {
      throw RelayFriendsException(
          e.response!.statusCode ?? 0, e.response!.data);
    }
    rethrow;
  }
}

/// 主动拉黑（`POST /v1/friends/{user_id}/block`）：无需既有关系；
/// pending/accepted 一并转 blocked。拉黑列表配额 ≤500，超限
/// 400 `EXCEED_BLOCKED_LIST`（抛 [RelayFriendsException]）。
Future<String> blockFriend(
  Dio dio,
  String baseUrl,
  String accessToken,
  String userId,
) async {
  try {
    final resp = await dio.post(
      '$baseUrl/v1/friends/$userId/block',
      options: Options(
        headers: {'Authorization': 'Bearer $accessToken'},
        validateStatus: (s) => s == 200,
      ),
    );
    if (resp.statusCode != 200) {
      throw RelayFriendsException(resp.statusCode ?? 0, resp.data);
    }
    final j = resp.data as Map<String, dynamic>;
    return j['status'] as String;
  } on DioException catch (e) {
    if (e.response != null) {
      throw RelayFriendsException(
          e.response!.statusCode ?? 0, e.response!.data);
    }
    rethrow;
  }
}

/// 被邀方处置一条邀请：accept=true → accepted；false → blocked（拉黑）。
Future<String> acceptFriendInvite(
  Dio dio,
  String baseUrl,
  String accessToken,
  String inviteId,
  bool accept,
) async {
  try {
    final resp = await dio.post(
      '$baseUrl/v1/friends/accept',
      options: Options(
        headers: {'Authorization': 'Bearer $accessToken'},
        validateStatus: (s) => s == 200,
      ),
      data: jsonEncode({'invite_id': inviteId, 'accept': accept}),
    );
    if (resp.statusCode != 200) {
      throw RelayFriendsException(resp.statusCode ?? 0, resp.data);
    }
    final j = resp.data as Map<String, dynamic>;
    return j['status'] as String;
  } on DioException catch (e) {
    if (e.response != null) {
      throw RelayFriendsException(
          e.response!.statusCode ?? 0, e.response!.data);
    }
    rethrow;
  }
}

class RelayFriendsException implements Exception {
  final int statusCode;
  final Object? detail;
  RelayFriendsException(this.statusCode, [this.detail]);
  @override
  String toString() => 'RelayFriendsException($statusCode): $detail';
}
