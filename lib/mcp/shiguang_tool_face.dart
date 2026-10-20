import '../data/repository.dart';
import 'tool_face.dart';
import 'tools.dart';
import 'weather_provider.dart';

/// 拾光工具面适配器：业务工具面（tools.dart 十余工具 + CommandHandler，依赖
/// Repository）装配成内核 McpServer 所需的 [McpToolFace] 端口——
/// 内核不沾 Repository / 业务模型，天气注入也收口在此。
class ShiguangToolFace implements McpToolFace {
  ShiguangToolFace(this.repo, {this.weather = const NullWeatherProvider()});

  final Repository repo;

  /// 天气投影后端（dart:io 网络在 lib/service，内核不沾）。
  final WeatherProvider weather;

  @override
  List<Map<String, Object?>> schemas() => toolSchemas();

  @override
  Future<List<Map<String, Object?>>> call(
    String name,
    Map<String, Object?> args,
  ) =>
      callTool(name, args, repo, weather: weather);
}
