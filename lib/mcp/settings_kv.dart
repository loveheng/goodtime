/// MCP 内核 KV 持久化端口（依赖反转接缝）：总控的 token/开关/通道配置
/// 只需要「单键读 + 批量写」的 KV 能力，不依赖具体存储。
/// 模板侧：宿主实现本接口（移动端可由 sqlite app_settings 表承载，
/// 服务端可由文件/内存承载）。
abstract class SettingsKv {
  /// 单键读；不存在返回 null。
  Future<String?> settingsGet(String key);

  /// 批量 upsert。
  Future<void> settingsSet(Map<String, String> values);
}
