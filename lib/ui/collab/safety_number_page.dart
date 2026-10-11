import 'package:flutter/material.dart';

import '../../collab/friends_ticket.dart';
import '../../collab/relay/peers_api.dart';
import '../../collab/relay_session.dart';
import '../../theme/tokens.dart';

/// 安全码核对（§1.6 Safety Number）：双方身份公钥哈希指纹，6 组 5 位数字。
///
/// 当面或微信核对这 6 组数字，一致即可确认对方身份未被中间人替换（中继不可信，端侧可验证）。
class SafetyNumberPage extends StatefulWidget {
  final RelaySession session;
  final FriendView friend;
  const SafetyNumberPage({super.key, required this.session, required this.friend});

  @override
  State<SafetyNumberPage> createState() => _SafetyNumberPageState();
}

class _SafetyNumberPageState extends State<SafetyNumberPage> {
  List<String>? _digits;
  String? _error;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    try {
      final myIk = await widget.session.myIdentityPublicKey();
      final peerIk =
          await widget.session.peerIdentityPublicKey(widget.friend.userId);
      if (!mounted) return;
      setState(() {
        _digits = safetyNumberDigits(myIk, peerIk);
        _error = null;
        _loading = false;
      });
    } catch (_) {
      if (!mounted) return;
      setState(() {
        _error = '暂时取不到对方公钥，连上中继后再试';
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('核对安全码')),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          Text('和 ${widget.friend.userId} 当面或微信核对以下数字',
              style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w600)),
          const SizedBox(height: 6),
          Text('六组数字完全一致，即确认对方身份未被中间人替换；不一致请勿继续协同。',
              style: TextStyle(color: StColors.textSecondary)),
          const SizedBox(height: 24),
          if (_loading)
            const Center(child: CircularProgressIndicator())
          else if (_error != null)
            Column(
              children: [
                Text(_error!,
                    style: TextStyle(color: StColors.textSecondary)),
                const SizedBox(height: 16),
                FilledButton.icon(
                  onPressed: _load,
                  icon: const Icon(Icons.refresh, size: 18),
                  label: const Text('重试'),
                ),
              ],
            )
          else
            _DigitsGrid(digits: _digits!),
        ],
      ),
    );
  }
}

class _DigitsGrid extends StatelessWidget {
  final List<String> digits;
  const _DigitsGrid({required this.digits});

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        for (var i = 0; i < digits.length; i += 2)
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: Row(
              children: [
                Expanded(child: _DigitCell(label: '${i + 1}', value: digits[i])),
                const SizedBox(width: 12),
                Expanded(
                  child: i + 1 < digits.length
                      ? _DigitCell(label: '${i + 2}', value: digits[i + 1])
                      : const SizedBox.shrink(),
                ),
              ],
            ),
          ),
      ],
    );
  }
}

class _DigitCell extends StatelessWidget {
  final String label;
  final String value;
  const _DigitCell({required this.label, required this.value});

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 16),
        decoration: BoxDecoration(
          color: StColors.sparkBg,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.spaceBetween,
          children: [
            Text(label,
                style: TextStyle(color: StColors.textSecondary, fontSize: 13)),
            Text(value,
                style: const TextStyle(
                    fontSize: 22,
                    fontWeight: FontWeight.w700,
                    letterSpacing: 2)),
          ],
        ),
      );
}
