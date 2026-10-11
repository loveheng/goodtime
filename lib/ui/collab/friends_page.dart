import 'package:dio/dio.dart';
import 'package:flutter/material.dart';

import '../../collab/relay/auth_api.dart' show RelayAuthException;
import '../../collab/relay/friends_api.dart';
import '../../collab/relay/peers_api.dart';
import '../../collab/relay_session.dart';
import '../../theme/tokens.dart';
import 'my_qr_page.dart';
import 'safety_number_page.dart';
import 'scan_page.dart';

/// 好友域主页（§1.6 / Phase 4.6）：我的名片 / 扫一扫建联 / 搜用户名加好友 /
/// 好友名单 / 待确认处置 / 安全码核对。
///
/// 发起协同的可邀名单依赖本页建联——否则 `acceptedFriends` 恒空、协同不可达真机
/// （见 `online` epic 断点）。
class FriendsPage extends StatefulWidget {
  /// 好友域会话装配钩子（与 `MemberCalendarPage.loadComposeKit` 同构）。
  ///
  /// 返回 null = 未登录 / 未注册协同身份，本页转引导，不给必然失败的入口。
  final Future<RelaySession?> Function() loadSession;

  const FriendsPage({super.key, required this.loadSession});

  @override
  State<FriendsPage> createState() => _FriendsPageState();
}

class _FriendsPageState extends State<FriendsPage> {
  RelaySession? _session;
  bool _loading = true;
  String? _error;
  List<FriendView> _friends = const [];

  final _searchCtrl = TextEditingController();
  bool _searching = false;

  @override
  void initState() {
    super.initState();
    _init();
  }

  @override
  void dispose() {
    _searchCtrl.dispose();
    super.dispose();
  }

  Future<void> _init() async {
    final s = await widget.loadSession();
    if (!mounted) return;
    if (s == null) {
      setState(() {
        _loading = false;
        // null 有两种来路：未登录，或已登录但本机没有协同身份记录（如卸载
        // 重装后安全区被清）。文案必须两可都指到同一处置入口，别让已登录
        // 用户对着「请登录」死循环。
        _error = '到「设置 → 协同账号」完成登录与协同身份注册，才能管理好友';
      });
      return;
    }
    setState(() => _session = s);
    await _reload();
  }

  Future<void> _reload() async {
    final s = _session;
    if (s == null) return;
    setState(() => _loading = true);
    try {
      final friends = await s.listFriends();
      if (!mounted) return;
      setState(() {
        _friends = friends;
        _loading = false;
        _error = null;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _error = '暂时连不上中继，取不到好友名单';
      });
    }
  }

  List<FriendView> _ofStatus(String status) =>
      _friends.where((f) => f.status == status).toList();

  Future<void> _respond(String peer, bool accept) async {
    final s = _session;
    if (s == null) return;
    try {
      await s.acceptInviteFromPeer(peer, accept);
      await _reload();
    } on RelayFriendsException catch (e) {
      if (e.statusCode == 404) {
        // 这是我发出的邀请（我非被邀方），服务端不认——不是错误，等对方确认。
        _toast('这是你发出的邀请，等待对方确认');
        await _reload();
      } else {
        _toast('操作失败了，稍后再试');
      }
    } catch (_) {
      _toast('暂时连不上中继，稍后再试');
    }
  }

  /// 解除关系（删好友/解除拉黑）与拉黑：破坏性操作一律二次确认。
  Future<void> _manage(FriendView friend, _FriendAction action) async {
    final s = _session;
    if (s == null) return;
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(switch (action) {
          _FriendAction.remove => '解除好友',
          _FriendAction.block => '拉黑',
          _FriendAction.unblock => '解除拉黑',
        }),
        content: Text(switch (action) {
          _FriendAction.remove => '解除后你们回到陌生状态，之后可以重新加',
          _FriendAction.block => '拉黑后对方无法再向你发起任何申请',
          _FriendAction.unblock => '解除后对方可以重新申请加你',
        }),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('确认')),
        ],
      ),
    );
    if (ok != true) return;
    try {
      await switch (action) {
        _FriendAction.remove || _FriendAction.unblock =>
          s.removeFriend(friend.userId),
        _FriendAction.block => s.blockFriend(friend.userId),
      };
      if (!mounted) return;
      _toast(switch (action) {
        _FriendAction.remove => '已解除好友',
        _FriendAction.block => '已拉黑',
        _FriendAction.unblock => '已解除拉黑',
      });
      await _reload();
    } on RelayFriendsException catch (e) {
      _toast(switch (e.statusCode) {
        404 => '这条关系已不存在，刷新一下',
        400 => '拉黑列表已满（上限 500），先清理一下',
        401 => '登录态已过期，请到「设置 → 协同账号」重新登录',
        _ => '操作失败，稍后再试',
      });
    } on DioException {
      _toast('暂时连不上中继，稍后再试');
    } catch (_) {
      _toast('操作失败，稍后再试');
    }
  }

  Future<void> _openMyQr() async {
    final s = _session;
    if (s == null) return;
    await Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => MyQrPage(session: s)),
    );
  }

  Future<void> _openScan() async {
    final s = _session;
    if (s == null) return;
    await Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => ScanPage(session: s)),
    );
    await _reload(); // 扫完回来刷新名单（可能刚加了待确认）
  }

  Future<void> _openSafety(FriendView friend) async {
    final s = _session;
    if (s == null) return;
    await Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => SafetyNumberPage(session: s, friend: friend)),
    );
  }

  /// 搜用户名加好友（账号体系搜索路径）：lookup → friends/request，与扫码双通道并存。
  Future<void> _searchAndRequest() async {
    final s = _session;
    if (s == null || _searching) return;
    final uname = _searchCtrl.text.trim().toLowerCase();
    if (uname.isEmpty) return;
    setState(() => _searching = true);
    try {
      await s.requestFriendByUsername(uname);
      _searchCtrl.clear();
      _toast('已发出好友申请，等对方确认');
      await _reload();
    } on RelayAuthException catch (e) {
      _toast(switch (e.statusCode) {
        404 => '没有找到这个用户名',
        429 => '操作太频繁，稍后再试',
        401 => '登录态已过期，请到「设置 → 协同账号」重新登录',
        _ => '发送失败，稍后再试',
      });
    } on RelayFriendsException catch (e) {
      _toast(switch (e.statusCode) {
        409 => '关系已存在',
        429 => '操作太频繁，稍后再试',
        401 => '登录态已过期，请到「设置 → 协同账号」重新登录',
        _ => '发送失败，稍后再试',
      });
    } on DioException {
      _toast('暂时连不上中继，稍后再试');
    } catch (_) {
      _toast('发送失败，稍后再试');
    } finally {
      if (mounted) setState(() => _searching = false);
    }
  }

  void _toast(String text) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(text), behavior: SnackBarBehavior.floating),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('好友')),
      body: _buildBody(),
      floatingActionButton: _session == null
          ? null
          : FloatingActionButton.extended(
              icon: const Icon(Icons.qr_code_scanner, size: 18),
              label: const Text('扫一扫建联'),
              onPressed: _openScan,
            ),
    );
  }

  Widget _buildBody() {
    if (_loading && _session == null) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_session == null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 32),
          child: Text(_error ?? '无法打开好友',
              textAlign: TextAlign.center,
              style: TextStyle(color: StColors.textSecondary)),
        ),
      );
    }
    final pending = _ofStatus('pending');
    final accepted = _ofStatus('accepted');
    final blocked = _ofStatus('blocked');
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 32),
      children: [
        _ActionRow(onMyQr: _openMyQr, onScan: _openScan),
        const SizedBox(height: 12),
        _SearchRow(
          controller: _searchCtrl,
          busy: _searching,
          onSubmit: _searchAndRequest,
        ),
        if (_error != null) ...[
          const SizedBox(height: 12),
          Text(_error!,
              style: TextStyle(fontSize: 13, color: StColors.textSecondary)),
        ],
        if (pending.isNotEmpty) ...[
          const SizedBox(height: 20),
          _SectionLabel(title: '待确认', hint: '对方确认后才算互加好友'),
          const SizedBox(height: 8),
          for (final f in pending) _PendingTile(friend: f, onRespond: _respond),
        ],
        if (accepted.isNotEmpty) ...[
          const SizedBox(height: 20),
          _SectionLabel(title: '已互加', hint: null),
          const SizedBox(height: 8),
          for (final f in accepted)
            _FriendTile(
              friend: f,
              onVerify: () => _openSafety(f),
              onManage: (action) => _manage(f, action),
            ),
        ],
        if (blocked.isNotEmpty) ...[
          const SizedBox(height: 20),
          _SectionLabel(title: '已拉黑', hint: '无法与其协同'),
          const SizedBox(height: 8),
          for (final f in blocked)
            _FriendTile(
              friend: f,
              onManage: (action) => _manage(f, action),
            ),
        ],
        if (_friends.isEmpty && _error == null) ...[
          const SizedBox(height: 32),
          Center(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 32),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.people_outline, size: 56, color: StColors.textSecondary),
                  const SizedBox(height: 16),
                  Text('还没有好友',
                      style: TextStyle(
                          fontSize: 17,
                          fontWeight: FontWeight.w600,
                          color: StColors.textPrimary)),
                  const SizedBox(height: 8),
                  Text('扫一扫出示名片，或让对方扫你的名片加一位',
                      textAlign: TextAlign.center,
                      style: TextStyle(color: StColors.textSecondary)),
                ],
              ),
            ),
          ),
        ],
      ],
    );
  }
}

class _ActionRow extends StatelessWidget {
  final VoidCallback onMyQr;
  final VoidCallback onScan;
  const _ActionRow({required this.onMyQr, required this.onScan});

  @override
  Widget build(BuildContext context) => Row(
        children: [
          Expanded(
            child: OutlinedButton.icon(
              key: const Key('friends-my-qr'),
              onPressed: onMyQr,
              icon: const Icon(Icons.qr_code_2, size: 18),
              label: const Text('我的名片'),
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: FilledButton.icon(
              key: const Key('friends-scan'),
              onPressed: onScan,
              icon: const Icon(Icons.qr_code_scanner, size: 18),
              label: const Text('扫一扫'),
            ),
          ),
        ],
      );
}

/// 搜用户名加好友（§0.4 账号域词条「搜用户名加好友」）。
class _SearchRow extends StatelessWidget {
  final TextEditingController controller;
  final bool busy;
  final VoidCallback onSubmit;
  const _SearchRow({
    required this.controller,
    required this.busy,
    required this.onSubmit,
  });

  @override
  Widget build(BuildContext context) => Row(
        children: [
          Expanded(
            child: TextField(
              key: const Key('friends-search-field'),
              controller: controller,
              enabled: !busy,
              textInputAction: TextInputAction.done,
              onSubmitted: (_) => onSubmit(),
              decoration: const InputDecoration(
                isDense: true,
                prefixIcon: Icon(Icons.search, size: 20),
                hintText: '输入对方用户名',
                border: OutlineInputBorder(),
              ),
            ),
          ),
          const SizedBox(width: 12),
          FilledButton.tonal(
            key: const Key('friends-search-submit'),
            onPressed: busy ? null : onSubmit,
            child: busy
                ? const SizedBox(
                    height: 16,
                    width: 16,
                    child: CircularProgressIndicator(strokeWidth: 2))
                : const Text('加好友'),
          ),
        ],
      );
}

class _SectionLabel extends StatelessWidget {
  final String title;
  final String? hint;
  const _SectionLabel({required this.title, this.hint});
  @override
  Widget build(BuildContext context) => Row(
        children: [
          Text(title,
              style: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600)),
          if (hint != null) ...[
            const SizedBox(width: 8),
            Text(hint!, style: TextStyle(fontSize: 12, color: StColors.textSecondary)),
          ],
        ],
      );
}

enum _FriendAction { remove, block, unblock }

class _FriendTile extends StatelessWidget {
  final FriendView friend;
  final VoidCallback? onVerify;
  final void Function(_FriendAction action)? onManage;
  const _FriendTile({required this.friend, this.onVerify, this.onManage});

  @override
  Widget build(BuildContext context) {
    final blocked = friend.status == 'blocked';
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: ListTile(
        leading: const Icon(Icons.person_outline),
        title: Text(friend.userId),
        trailing: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (!blocked && onVerify != null)
              TextButton(
                key: Key('verify-${friend.userId}'),
                onPressed: onVerify,
                child: const Text('核对安全码'),
              ),
            if (blocked)
              TextButton(
                key: Key('unblock-${friend.userId}'),
                onPressed: onManage == null
                    ? null
                    : () => onManage!(_FriendAction.unblock),
                child: const Text('解除拉黑'),
              ),
            if (!blocked && onManage != null)
              PopupMenuButton<_FriendAction>(
                key: Key('manage-${friend.userId}'),
                icon: const Icon(Icons.more_vert, size: 20),
                onSelected: onManage,
                itemBuilder: (_) => const [
                  PopupMenuItem(
                      value: _FriendAction.remove, child: Text('解除好友')),
                  PopupMenuItem(
                      value: _FriendAction.block, child: Text('拉黑')),
                ],
              ),
          ],
        ),
      ),
    );
  }
}

class _PendingTile extends StatelessWidget {
  final FriendView friend;
  final Future<void> Function(String peer, bool accept) onRespond;
  const _PendingTile({required this.friend, required this.onRespond});

  @override
  Widget build(BuildContext context) => Card(
        margin: const EdgeInsets.only(bottom: 8),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
          child: Row(
            children: [
              const Icon(Icons.person_outline),
              const SizedBox(width: 12),
              Expanded(child: Text(friend.userId)),
              TextButton(
                key: Key('decline-${friend.userId}'),
                onPressed: () => onRespond(friend.userId, false),
                child: const Text('忽略'),
              ),
              const SizedBox(width: 4),
              FilledButton(
                key: Key('accept-${friend.userId}'),
                onPressed: () => onRespond(friend.userId, true),
                child: const Text('通过'),
              ),
            ],
          ),
        ),
      );
}
