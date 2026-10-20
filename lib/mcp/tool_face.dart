/// MCP 内核工具面端口（依赖反转接缝）：内核 McpServer 不直接依赖
/// Repository / 业务工具面，宿主 app 经本端口注入 schemas 与执行器。
/// 模板侧：新 app 实现本接口即可起 MCP 服务。
abstract class McpToolFace {
  /// tools/list 返回的工具 schema 列表。
  List<Map<String, Object?>> schemas();

  /// tools/call 单工具执行；返回 content 列表（MCP 规范形状）。
  Future<List<Map<String, Object?>>> call(
    String name,
    Map<String, Object?> args,
  );
}
