import 'package:dio/dio.dart';
import 'package:flutter/material.dart';

import '../collab/account.dart';
import '../collab/relay/auth_api.dart'
    show RelayAuthException, errorCodeOf, retryAfterSecOf;
import '../theme/tokens.dart';

/// 协同账号页（账号体系 Plan A，2026-10-11）：登录 / 注册 / 改密 / 退出。
///
/// 用户只见「用户名 + 密码」；E2EE 密钥由端侧自动托管，本页零密钥概念。
/// 登录态门禁的入口：协同域各处「先到设置 → 协同账号登录」引导都落这里。
///
/// 错误口径（防账号枚举）：登录失败统一「用户名或密码错误」，绝不区分
/// 用户不存在与密码错误；文案全部出自 ui-spec §0.4 账号域词条。
class AccountPage extends StatefulWidget {
  final AccountSession session;

  const AccountPage({super.key, required this.session});

  @override
  State<AccountPage> createState() => _AccountPageState();
}

class _AccountPageState extends State<AccountPage> {
  final _username = TextEditingController();
  final _password = TextEditingController();
  final _confirm = TextEditingController();
  bool _register = false;
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    // 冷启动首次进入才读安全区（构造不触碰平台插件的纪律不破）。
    widget.session.restore();
  }

  @override
  void dispose() {
    _username.dispose();
    _password.dispose();
    _confirm.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final uname = _username.text.trim().toLowerCase();
    final pass = _password.text;
    setState(() => _error = null);
    if (!isValidUsername(uname)) {
      setState(() => _error = '用户名需为 3~32 位小写字母、数字或下划线');
      return;
    }
    if (!isValidPassword(pass)) {
      setState(() => _error = '密码至少 8 位');
      return;
    }
    if (_register && pass != _confirm.text) {
      setState(() => _error = '两次输入的密码不一致');
      return;
    }
    setState(() => _busy = true);
    try {
      if (_register) {
        await widget.session.register(username: uname, password: pass);
        _toast('已注册并登录');
      } else {
        await widget.session.login(username: uname, password: pass);
        _toast('已登录');
      }
      if (mounted) setState(() {});
    } on RelayAuthException catch (e) {
      if (mounted) setState(() => _error = _messageOf(e));
    } on DioException {
      if (mounted) setState(() => _error = '暂时连不上中继，稍后再试');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _changePassword() async {
    final oldCtrl = TextEditingController();
    final newCtrl = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('修改密码'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            TextField(
              controller: oldCtrl,
              obscureText: true,
              decoration: const InputDecoration(labelText: '当前密码'),
            ),
            TextField(
              controller: newCtrl,
              obscureText: true,
              decoration: const InputDecoration(labelText: '新密码（至少 8 位）'),
            ),
          ],
        ),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('确认修改')),
        ],
      ),
    );
    final oldPass = oldCtrl.text;
    final newPass = newCtrl.text;
    oldCtrl.dispose();
    newCtrl.dispose();
    if (ok != true || !mounted) return;
    try {
      await widget.session.changePassword(
        oldPassword: oldPass,
        newPassword: newPass,
      );
    } on RelayAuthException catch (e) {
      if (!mounted) return;
      _toast(e.statusCode == 401 ? '当前密码不正确' : '修改失败，稍后再试');
      return;
    } on DioException {
      if (!mounted) return;
      _toast('暂时连不上中继，稍后再试');
      return;
    } on ArgumentError {
      if (!mounted) return;
      _toast('密码至少 8 位');
      return;
    }
    if (!mounted) return;
    // 改密即踢会话（契约 §3.4）：session 已转未登录，视图随监听回登录表单。
    _toast('密码已更新，请重新登录');
  }

  Future<void> _logout() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('退出登录'),
        content: const Text('只退出账号，本地日程与协同数据都保留'),
        actions: [
          TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('取消')),
          FilledButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: const Text('退出')),
        ],
      ),
    );
    if (ok == true) {
      await widget.session.logout();
      if (mounted) _toast('已退出登录');
    }
  }

  /// 服务端错误码 → §0.4 账号域文案。登录失败统一口径，绝不区分两种原因；
  /// 账号锁定按服务端给的 retry_after_sec 提示等待窗口。
  String _messageOf(RelayAuthException e) {
    return switch (errorCodeOf(e.detail)) {
      'USER_OR_PASSWORD_INVALID' => '用户名或密码错误',
      'USERNAME_EXISTS' => '该用户名已被占用，换一个试试',
      'USERNAME_INVALID' => '用户名需为 3~32 位小写字母、数字或下划线',
      'PASSWORD_TOO_WEAK' => '密码至少 8 位',
      'PASSWORD_MISMATCH' => '当前密码不正确',
      'LOGIN_THROTTLED' => _throttledText(e),
      'RATE_LIMITED' => '操作太频繁，稍后再试',
      'CHALLENGE_INVALID' => '注册会话已过期，请重试',
      'TOKEN_REVOKED' || 'TOKEN_STALE' => '登录态已过期，请重新登录',
      _ => '操作失败，稍后再试',
    };
  }

  String _throttledText(RelayAuthException e) {
    final sec = retryAfterSecOf(e.detail);
    if (sec == null || sec <= 0) return '尝试太频繁，请稍后再试';
    if (sec >= 60) return '尝试太频繁，账号已临时锁定，约 ${ (sec / 60).ceil() } 分钟后再试';
    return '尝试太频繁，请 $sec 秒后再试';
  }

  void _toast(String msg) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('协同账号')),
      body: ListenableBuilder(
        listenable: widget.session,
        builder: (context, _) => switch (widget.session.status) {
          AccountStatus.unknown =>
            const Center(child: CircularProgressIndicator()),
          AccountStatus.loggedIn => _loggedInView(),
          AccountStatus.loggedOut => _authForm(),
        },
      ),
    );
  }

  Widget _loggedInView() {
    final session = widget.session;
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              children: [
                const Icon(Icons.account_circle_outlined, size: 40),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text('已登录：${session.username ?? ''}',
                          style: const TextStyle(
                              fontSize: 16, fontWeight: FontWeight.w600)),
                      const SizedBox(height: 4),
                      Text('协同日程与好友数据走这个账号',
                          style: TextStyle(
                              fontSize: 12, color: StColors.textSecondary)),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
        const SizedBox(height: 12),
        OutlinedButton.icon(
          key: const Key('account-change-password'),
          icon: const Icon(Icons.password_outlined, size: 18),
          label: const Text('修改密码'),
          onPressed: _changePassword,
        ),
        const SizedBox(height: 8),
        OutlinedButton.icon(
          key: const Key('account-logout'),
          icon: const Icon(Icons.logout, size: 18),
          label: const Text('退出登录'),
          onPressed: _logout,
        ),
        const SizedBox(height: 16),
        Text(
          '退出登录只清除本机的登录会话，日程与协同数据都保留在本机；'
          '修改密码不影响协同密钥与历史消息。',
          style: TextStyle(fontSize: 12, color: StColors.textSecondary),
        ),
      ],
    );
  }

  Widget _authForm() {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        SegmentedButton<bool>(
          segments: const [
            ButtonSegment(value: false, label: Text('登录')),
            ButtonSegment(value: true, label: Text('注册')),
          ],
          selected: {_register},
          onSelectionChanged: (s) => setState(() {
            _register = s.first;
            _error = null;
          }),
        ),
        const SizedBox(height: 16),
        TextField(
          key: const Key('account-username'),
          controller: _username,
          enabled: !_busy,
          decoration: const InputDecoration(
            labelText: '用户名',
            hintText: '小写字母、数字或下划线（3~32 位）',
            border: OutlineInputBorder(),
          ),
        ),
        const SizedBox(height: 12),
        TextField(
          key: const Key('account-password'),
          controller: _password,
          obscureText: true,
          enabled: !_busy,
          decoration: InputDecoration(
            labelText: '密码',
            hintText: '至少 8 位',
            border: const OutlineInputBorder(),
            errorText: _error,
          ),
        ),
        if (_register) ...[
          const SizedBox(height: 12),
          TextField(
            key: const Key('account-confirm'),
            controller: _confirm,
            obscureText: true,
            enabled: !_busy,
            decoration: const InputDecoration(
              labelText: '再输一次密码',
              border: OutlineInputBorder(),
            ),
          ),
        ],
        const SizedBox(height: 16),
        FilledButton(
          key: const Key('account-submit'),
          onPressed: _busy ? null : _submit,
          child: _busy
              ? const SizedBox(
                  height: 18,
                  width: 18,
                  child: CircularProgressIndicator(strokeWidth: 2))
              : Text(_register ? '注册并登录' : '登录'),
        ),
        const SizedBox(height: 16),
        Text(
          _register
              ? '注册即创建协同账号：用户名是好友找到你的入口；'
                  '协同密钥在本机自动生成，密码只用来登录。'
              : '登录后即可与好友协同日程；协同密钥在本机自动管理，无需记忆。',
          style: TextStyle(fontSize: 12, color: StColors.textSecondary),
        ),
      ],
    );
  }
}
