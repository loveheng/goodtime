import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../app_services.dart';
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
            ],
          );
        },
      ),
    );
  }
}
