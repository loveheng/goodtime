import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../app_services.dart';
import '../service/relay_client.dart';
import '../theme/tokens.dart';

/// MCP 服务设置页（ui-spec §1「SET→MCPG 独立配对页」原 IA 形态，2026-10-08
/// 子页化落地）：服务开关/连接地址/访问令牌/配对说明。自 settings_page
/// 大平铺迁出（目录页只留入口行）。
class McpSettingsPage extends StatelessWidget {
  const McpSettingsPage({super.key});

  @override
  Widget build(BuildContext context) {
    final mcp = AppServices.mcp;
    return Scaffold(
      appBar: AppBar(title: const Text('MCP 服务')),
      body: ListenableBuilder(
        listenable: mcp,
        builder: (context, _) {
          final running = mcp.running;
          return ListView(
            padding: const EdgeInsets.all(16),
            children: [
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: Row(
                  children: [
                    Container(
                      width: 10,
                      height: 10,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        color: running
                            ? const Color(0xFF81C784)
                            : StColors.safelineOff,
                      ),
                    ),
                    const SizedBox(width: 8),
                    Text(running ? '运行中' : '已停止'),
                  ],
                ),
                value: running,
                onChanged: (on) async {
                  if (on) {
                    final error = await mcp.enable();
                    if (error != null && context.mounted) {
                      ScaffoldMessenger.of(context)
                          .showSnackBar(SnackBar(content: Text(error)));
                    }
                  } else {
                    await mcp.disable();
                  }
                },
              ),
              FutureBuilder<String>(
                future: mcp.endpoint(),
                builder: (context, snap) => ListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('连接地址'),
                  subtitle: Text(snap.data ?? '…'),
                  trailing: IconButton(
                    icon: const Icon(Icons.copy, size: 18),
                    tooltip: '复制',
                    onPressed: snap.data == null
                        ? null
                        : () {
                            Clipboard.setData(ClipboardData(text: snap.data!));
                            ScaffoldMessenger.of(context).showSnackBar(
                              const SnackBar(
                                  content: Text('已复制'),
                                  duration: Duration(seconds: 1)),
                            );
                          },
                  ),
                ),
              ),
              ListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('访问令牌'),
                subtitle: Text(mcp.token ?? '…'),
                trailing: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    IconButton(
                      icon: const Icon(Icons.copy, size: 18),
                      tooltip: '复制',
                      onPressed: mcp.token == null
                          ? null
                          : () {
                              Clipboard.setData(ClipboardData(text: mcp.token!));
                              ScaffoldMessenger.of(context).showSnackBar(
                                const SnackBar(
                                    content: Text('已复制'),
                                    duration: Duration(seconds: 1)),
                              );
                            },
                    ),
                    TextButton(
                      onPressed: () => mcp.regenerateToken(),
                      child: const Text('重新生成'),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 8),
              Text('桌面 AI 用此地址配对（USB 场景先 adb reverse tcp:8765 tcp:8765）',
                  style: TextStyle(fontSize: 11, color: StColors.textSecondary)),
              const SizedBox(height: 16),
              const _RelaySection(),
            ],
          );
        },
      ),
    );
  }
}

/// relay 中转区（轴 C）：外网可达的第二种传输——手机与桌面各持 relay 账号且互为好友，
/// MCP 请求经 relay 定向中转（好友 ACL + 本页访问令牌双保险；契约 v0.1.8）。
/// 配置保存后于下次「打开服务」生效。
class _RelaySection extends StatefulWidget {
  const _RelaySection();

  @override
  State<_RelaySection> createState() => _RelaySectionState();
}

class _RelaySectionState extends State<_RelaySection> {
  final _endpoint = TextEditingController();
  final _username = TextEditingController();
  final _password = TextEditingController();
  bool _enabled = false;
  bool _loaded = false;

  @override
  void initState() {
    super.initState();
    _restore();
  }

  Future<void> _restore() async {
    final repo = AppServices.repo;
    _endpoint.text = await repo.settingsGet(RelayKeys.endpoint) ?? '';
    _username.text = await repo.settingsGet(RelayKeys.username) ?? '';
    _password.text = await repo.settingsGet(RelayKeys.password) ?? '';
    _enabled = await repo.settingsGet(RelayKeys.enabled) == '1';
    if (mounted) setState(() => _loaded = true);
  }

  Future<void> _save(String key, String value) async {
    await AppServices.repo
        .settingsSet({key: value});
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
            content: Text('已保存（下次打开服务生效）'),
            duration: Duration(seconds: 1)),
      );
    }
  }

  @override
  void dispose() {
    _endpoint.dispose();
    _username.dispose();
    _password.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (!_loaded) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('relay 中转（外网可达）'),
          subtitle: const Text('需与桌面端互为 relay 好友；保存后下次打开服务生效'),
          value: _enabled,
          onChanged: (on) {
            setState(() => _enabled = on);
            _save(RelayKeys.enabled, on ? '1' : '0');
          },
        ),
        if (_enabled) ...[
          TextField(
            controller: _endpoint,
            decoration: const InputDecoration(
              labelText: 'relay 服务器地址',
              hintText: 'https://relay.example.com',
            ),
            onSubmitted: (v) => _save(RelayKeys.endpoint, v.trim()),
          ),
          const SizedBox(height: 8),
          TextField(
            controller: _username,
            decoration: const InputDecoration(labelText: 'relay 用户名'),
            onSubmitted: (v) => _save(RelayKeys.username, v.trim()),
          ),
          const SizedBox(height: 8),
          TextField(
            controller: _password,
            obscureText: true,
            decoration: const InputDecoration(labelText: 'relay 密码'),
            onSubmitted: (v) => _save(RelayKeys.password, v),
          ),
          const SizedBox(height: 8),
          Text(
            'MCP 请求门禁：relay 好友 ACL + 本页访问令牌双保险；'
            '桌面端用 relay-bridge.mjs 接入（手机不在线即失败）。',
            style: TextStyle(fontSize: 11, color: StColors.textSecondary),
          ),
        ],
      ],
    );
  }
}
