/// 天气投影提供方抽象（轴 B 纯 Dart 化，goodhub-local-plugin G2·③）：
/// 内核（lib/mcp/tools.dart）仅依赖此接口，不碰 dart:io。
/// 具体实现 [WeatherService]（HttpClient 网络请求）在 lib/service/weather.dart。
abstract class WeatherProvider {
  /// 三日结构化预报；城市为空或任一步失败 → null（静默降级，不抛）。
  Future<Map<String, Object?>?> forecast3(String city);
}

/// 默认空实现：未注入真实源时返回 null（get_schedule 静默不搭载 weather）。
class NullWeatherProvider implements WeatherProvider {
  const NullWeatherProvider();

  @override
  Future<Map<String, Object?>?> forecast3(String city) async => null;
}
