import 'dart:convert';
import 'dart:io';

/// 天气投影（schedule-app.md §11 拍板）：无 key JSON 源（Open-Meteo）+ 内存 TTL
/// 缓存不落库（照抄日历投影模式）；经 get_schedule 返回体搭载三日结构化预报。
/// **机械层（校验/管家/水位线）对天气无感**——天气只进提案推理与态势句；
/// 户外敏感块是 playbook 启发式不加字段；获取失败静默降级（AI 可改口问用户）。
class WeatherService {
  WeatherService({Future<String> Function(Uri url)? fetch})
      : _fetch = fetch ?? _defaultFetch;

  final Future<String> Function(Uri url) _fetch;

  /// TTL（§11：内存缓存不落库，重启/日变更高即重读）。
  static const ttl = Duration(hours: 1);

  String? _cacheCity;
  DateTime? _cacheAt;
  Map<String, Object?>? _cacheData;

  static Future<String> _defaultFetch(Uri url) async {
    final client = HttpClient();
    try {
      final req = await client.getUrl(url);
      final res = await req.close();
      if (res.statusCode != 200) {
        throw HttpException('HTTP ${res.statusCode}');
      }
      return await utf8.decoder.bind(res).join();
    } finally {
      client.close();
    }
  }

  /// 三日结构化预报；城市为空或任一步失败 → null（静默降级，不抛）。
  Future<Map<String, Object?>?> forecast3(String city, {DateTime? now}) async {
    if (city.trim().isEmpty) return null;
    final at = now ?? DateTime.now();
    if (_cacheData != null &&
        _cacheCity == city &&
        _cacheAt != null &&
        at.difference(_cacheAt!) < ttl) {
      return _cacheData;
    }
    try {
      final geo = jsonDecode(await _fetch(Uri.parse(
          'https://geocoding-api.open-meteo.com/v1/search?name=${Uri.encodeComponent(city)}&count=1&language=zh&format=json'))) as Map<String, dynamic>;
      final results = geo['results'];
      if (results is! List || results.isEmpty) return null;
      final lat = results.first['latitude'];
      final lon = results.first['longitude'];
      final raw = jsonDecode(await _fetch(Uri.parse(
          'https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon'
          '&daily=weather_code,temperature_2m_max,temperature_2m_min,'
          'precipitation_probability_max&forecast_days=3&timezone=auto'))) as Map<String, dynamic>;
      final daily = raw['daily'];
      if (daily is! Map<String, dynamic>) return null;
      final dates = (daily['time'] as List? ?? const []).cast<String>();
      final codes = (daily['weather_code'] as List? ?? const []).cast<num>();
      final days = <Map<String, Object?>>[];
      for (var i = 0; i < dates.length && i < 3; i++) {
        days.add({
          'date': dates[i],
          'summary': describeCode(codes.isNotEmpty ? codes[i].toInt() : -1),
          't_max': daily['temperature_2m_max']?[i],
          't_min': daily['temperature_2m_min']?[i],
          'precip_prob': daily['precipitation_probability_max']?[i],
        });
      }
      final out = <String, Object?>{'city': city, 'days': days};
      _cacheCity = city;
      _cacheAt = at;
      _cacheData = out;
      return out;
    } catch (_) {
      // DEGRADE: [weather_unavailable] 天气源不可达/解析失败——静默降级（§11 拍板），不缓存失败
      return null;
    }
  }

  /// WMO weather code → 白话摘要（户外敏感是 playbook 启发式，不加字段）。
  static String describeCode(int code) {
    if (code < 0) return '未知';
    if (code == 0) return '晴';
    if (code <= 3) return '多云';
    if (code == 45 || code == 48) return '雾';
    if (code >= 51 && code <= 67) return '雨';
    if (code >= 71 && code <= 77) return '雪';
    if (code >= 80 && code <= 82) return '阵雨';
    if (code >= 95) return '雷雨';
    return '多变';
  }
}

/// 模块级单例：TTL 缓存跨 get_schedule 调用存续。
final weatherService = WeatherService();
