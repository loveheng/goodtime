import 'data/repository.dart';
import 'service/housekeeper.dart';
import 'service/mcp_controller.dart';

/// 应用级服务装配（轻量服务定位器）：main 与测试共用同一初始化路径。
/// 命令层/查询层是无状态包装，用时装配（CommandHandler(repo)/ScheduleQueries(repo)）。
class AppServices {
  AppServices._();

  static Repository? _repo;
  static McpController? _mcp;

  static Repository get repo => _repo ?? (throw StateError('AppServices 未初始化'));
  static McpController get mcp => _mcp ?? (throw StateError('AppServices 未初始化'));
  static bool get ready => _repo != null;

  static Future<void> init() async {
    if (_repo != null) return;
    final repo = Repository();
    _repo = repo;
    final mcp = McpController(repo: repo);
    _mcp = mcp;
    await mcp.restoreIfNeeded();
    // 冷启动日切（幂等，每作息日至多一次）：missed/作废迁移先行，digest 随取随算
    await Housekeeper(repo).dailyCutIfNeeded();
  }
}
