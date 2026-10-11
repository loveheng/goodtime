import 'package:flutter/material.dart';
import 'package:qr_flutter/qr_flutter.dart';

import '../../collab/relay_session.dart';
import '../../theme/tokens.dart';

/// 我的名片（§1.6 Friendship Ticket）：端侧签发的可扫码名片，含 TTL 须刷新。
///
/// 让好友扫这张二维码，对方会收到你的好友申请（服务端验签 + 过期 + nonce 一次性）。
class MyQrPage extends StatefulWidget {
  final RelaySession session;
  const MyQrPage({super.key, required this.session});

  @override
  State<MyQrPage> createState() => _MyQrPageState();
}

class _MyQrPageState extends State<MyQrPage> {
  String? _qr;
  String? _error;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _issue();
  }

  Future<void> _issue() async {
    setState(() => _busy = true);
    try {
      final ticket = await widget.session.issueTicket();
      if (!mounted) return;
      setState(() {
        _qr = ticket.toQrString();
        _error = null;
        _busy = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _error = '暂时签不出名片，先到「设置 → 协同账号」登录或注册';
        _busy = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('我的名片')),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          const Text('让好友扫这张名片',
              style: TextStyle(fontSize: 17, fontWeight: FontWeight.w600)),
          const SizedBox(height: 6),
          Text('对方会收到你的好友申请，确认后即互加成功。名片带有效期，过期重新点一下即可刷新。',
              style: TextStyle(color: StColors.textSecondary)),
          const SizedBox(height: 24),
          Center(
            child: _busy
                ? const CircularProgressIndicator()
                : _qr != null
                    ? Container(
                        padding: const EdgeInsets.all(16),
                        decoration: BoxDecoration(
                          color: Colors.white,
                          borderRadius: BorderRadius.circular(16),
                        ),
                        child: QrImageView(
                          data: _qr!,
                          size: 240,
                          backgroundColor: Colors.white,
                        ),
                      )
                    : Text(_error ?? '出错了',
                        style: TextStyle(color: StColors.textSecondary)),
          ),
          const SizedBox(height: 24),
          if (_error != null)
            Center(
              child: FilledButton.icon(
                onPressed: _issue,
                icon: const Icon(Icons.refresh, size: 18),
                label: const Text('刷新名片'),
              ),
            ),
        ],
      ),
    );
  }
}
