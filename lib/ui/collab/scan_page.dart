import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';
import 'package:permission_handler/permission_handler.dart';

import '../../collab/friends_ticket.dart';
import '../../collab/relay/friends_api.dart';
import '../../collab/relay_session.dart';
import '../../theme/tokens.dart';

/// 扫一扫建联（§1.6）：扫对方名片二维码 → 解析 Ticket → 提交好友邀请。
///
/// 扫码失败 / 无相机时可用「手动填入名片串」兜底（同为 Ticket 的 JSON）。
class ScanPage extends StatefulWidget {
  final RelaySession session;
  const ScanPage({super.key, required this.session});

  @override
  State<ScanPage> createState() => _ScanPageState();
}

class _ScanPageState extends State<ScanPage> {
  final MobileScannerController _controller = MobileScannerController();
  final TextEditingController _manual = TextEditingController();
  bool _granted = false;
  bool _done = false;
  bool _submitting = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _requestPermission();
  }

  Future<void> _requestPermission() async {
    final status = await Permission.camera.request();
    if (!mounted) return;
    setState(() => _granted = status.isGranted || status.isLimited);
  }

  @override
  void dispose() {
    _controller.dispose();
    _manual.dispose();
    super.dispose();
  }

  Future<void> _handle(String code) async {
    if (_done || _submitting) return;
    _done = true;
    setState(() => _submitting = true);
    try {
      final ticket = FriendshipTicket.fromQrString(code);
      await widget.session.submitInvite(ticket);
      if (!mounted) return;
      _popWith('已发出好友申请，等对方确认');
    } on FormatException {
      _resetAfterError('这串不是有效的好友名片，换一张二维码试试');
    } on RelayFriendsException catch (e) {
      _resetAfterError(_inviteErrorText(e.statusCode));
    } catch (_) {
      _resetAfterError('暂时连不上中继，稍后再试');
    }
  }

  String _inviteErrorText(int code) => switch (code) {
        409 => '你们已经是好友，或申请已在处理中',
        400 => '这张名片已过期或被用过，让对方刷新一下',
        401 => '登录态已过期，请到「设置 → 协同账号」重新登录',
        403 => '名片不对，换一张试试',
        _ => '发送失败，稍后再试',
      };

  void _resetAfterError(String msg) {
    if (!mounted) return;
    setState(() {
      _submitting = false;
      _error = msg;
      _done = false;
    });
  }

  void _popWith(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(msg), behavior: SnackBarBehavior.floating),
    );
    Navigator.of(context).pop();
  }

  Future<void> _submitManual() async {
    final code = _manual.text.trim();
    if (code.isEmpty) return;
    await _handle(code);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('扫一扫建联')),
      body: Column(
        children: [
          Expanded(child: _scannerArea()),
          _manualArea(),
        ],
      ),
    );
  }

  Widget _scannerArea() {
    if (!_granted) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 32),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.camera_alt_outlined, size: 56, color: StColors.textSecondary),
              const SizedBox(height: 16),
              Text('需要相机权限才能扫码',
                  style: TextStyle(fontSize: 16, color: StColors.textPrimary)),
              const SizedBox(height: 12),
              FilledButton(
                onPressed: _requestPermission,
                child: const Text('授予相机权限'),
              ),
            ],
          ),
        ),
      );
    }
    return Stack(
      children: [
        MobileScanner(
          controller: _controller,
          onDetect: (capture) {
            final code = capture.barcodes
                .where((b) => b.rawValue != null)
                .firstOrNull
                ?.rawValue;
            if (code != null) _handle(code);
          },
        ),
        if (_submitting) const Center(child: CircularProgressIndicator()),
        if (_error != null)
          Positioned(
            bottom: 24,
            left: 24,
            right: 24,
            child: Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: Colors.black87,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Text(_error!,
                  textAlign: TextAlign.center,
                  style: const TextStyle(color: Colors.white)),
            ),
          )
        else
          const Positioned(
            bottom: 24,
            left: 0,
            right: 0,
            child: Center(
              child: Text('把对方名片放进框里',
                  style: TextStyle(color: Colors.white70)),
            ),
          ),
      ],
    );
  }

  Widget _manualArea() => Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Divider(),
            const SizedBox(height: 4),
            Text('手动填入名片串',
                style: TextStyle(fontSize: 13, color: StColors.textSecondary)),
            const SizedBox(height: 6),
            Row(
              children: [
                Expanded(
                  child: TextField(
                    key: const Key('scan-manual-input'),
                    controller: _manual,
                    decoration: const InputDecoration(
                      isDense: true,
                      hintText: '粘贴对方名片二维码内容',
                      border: OutlineInputBorder(),
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                FilledButton(
                  key: const Key('scan-manual-submit'),
                  onPressed: _submitting ? null : _submitManual,
                  child: const Text('提交'),
                ),
              ],
            ),
          ],
        ),
      );
}
