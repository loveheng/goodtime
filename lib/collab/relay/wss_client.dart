import 'dart:async';
import 'dart:convert';

import 'package:fixnum/fixnum.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

import '../proto/relay_protocol.pb.dart';

/// WSS 客户端连接状态（仅做 UI/编排可见性，不承载业务）。
enum WssConnectionState {
  disconnected,
  connecting,
  open,
  reconnecting,
}

/// 拉取 AUTH 首帧所需的 access token（登录态；失败应抛错触发重连）。
typedef TokenProvider = Future<String> Function();

/// 中继 WSS 客户端（relay `internal/wss/hub.go`，冻结 tag v0.1.6-contract）。
///
/// 三条硬约束逐条对齐：
/// 1. **纯路径建连** `ws(s)://host/ws`——token 绝不进 URL（反代 Access Log 会明文泄露）；
/// 2. **首帧 AUTH**：连接后第一帧必须是 `{"action":"AUTH","token":"<access>"}` JSON，
///    relay 侧 3s 未收到即掐线（非法 token 立即关）；
/// 3. **握手后一律 protobuf 二进制帧** `ServerFrame`/`ClientFrame`。
///
/// 断线后**指数退避自动重连**（1s → 2s → 4s … 封顶 30s），重连成功归零计数。
/// 因 `web_socket_channel` 3.x 无 `pingInterval`，心跳靠**应用层保活**：
/// 每 `keepaliveInterval` 发一个被 relay 忽略的 `CLIENT_ACK(seqId:0)`，重置其 60s 读截止。
class RelayWssClient {
  final Uri wsUri;
  final TokenProvider getToken;
  final Duration initialBackoff;
  final Duration maxBackoff;
  final Duration keepaliveInterval;

  final StreamController<ServerFrame> _frames =
      StreamController<ServerFrame>.broadcast();
  final StreamController<WssConnectionState> _state =
      StreamController<WssConnectionState>.broadcast();

  WebSocketChannel? _channel;
  Timer? _backoffTimer;
  Timer? _keepaliveTimer;
  int _attempt = 0;
  bool _disposed = false;
  bool _started = false;
  bool _reconnecting = false;

  RelayWssClient({
    required this.wsUri,
    required this.getToken,
    this.initialBackoff = const Duration(seconds: 1),
    this.maxBackoff = const Duration(seconds: 30),
    this.keepaliveInterval = const Duration(seconds: 30),
  });

  /// 入站 ServerFrame 流（已解码）。
  Stream<ServerFrame> get frames => _frames.stream;

  /// 连接状态变化流。
  Stream<WssConnectionState> get state => _state.stream;

  /// 启动连接（幂等：重复调用无效）。
  void connect() {
    if (_started || _disposed) return;
    _started = true;
    _state.add(WssConnectionState.connecting);
    _connectOnce();
  }

  Future<void> _connectOnce() async {
    if (_disposed) return;
    _state.add(WssConnectionState.connecting);
    final channel = WebSocketChannel.connect(wsUri);
    _channel = channel;

    String token;
    try {
      await channel.ready;
      token = await getToken();
    } catch (_) {
      _scheduleReconnect();
      return;
    }

    // 首帧 AUTH（JSON 文本帧；token 不进 URL）
    try {
      channel.sink.add(jsonEncode({'action': 'AUTH', 'token': token}));
    } catch (_) {
      _scheduleReconnect();
      return;
    }

    _state.add(WssConnectionState.open);
    _attempt = 0;

    channel.stream.listen(
      _onMessage,
      onError: (_) => _scheduleReconnect(),
      onDone: () => _scheduleReconnect(),
      cancelOnError: false,
    );

    _startKeepalive();
  }

  void _onMessage(dynamic msg) {
    if (msg is! List<int>) return; // 仅处理二进制 protobuf 帧
    try {
      _frames.add(ServerFrame.fromBuffer(msg));
    } catch (_) {
      // 无法解析的控制帧，忽略
    }
  }

  /// 发送任意 ClientFrame（握手后使用）。
  void send(ClientFrame frame) {
    try {
      _channel?.sink.add(frame.writeToBuffer());
    } catch (_) {
      // 连接已断，发送失败静默丢弃（重连后自然续上）
    }
  }

  /// 发送 CLIENT_ACK 推进游标（relay 按 `last_acked_seq` 判定落后者才推送）。
  void sendClientAck(String bundleId, int seqId) {
    send(ClientFrame()
      ..schedule = (Envelope()
        ..action = ActionType.CLIENT_ACK
        ..bundleId = bundleId
        ..seqId = Int64(seqId)));
  }

  void _startKeepalive() {
    _keepaliveTimer?.cancel();
    _keepaliveTimer = Timer.periodic(keepaliveInterval, (_) {
      if (_channel == null) return;
      // 被 relay 忽略的保活帧：重置其 60s 读截止，避免空闲连接被掐。
      send(ClientFrame()
        ..schedule = (Envelope()
          ..action = ActionType.CLIENT_ACK
          ..seqId = Int64(0)));
    });
  }

  void _stopKeepalive() => _keepaliveTimer?.cancel();

  Duration _backoffFor(int attempt) {
    final exp = initialBackoff * (1 << (attempt - 1)); // 1,2,4,8…
    return exp > maxBackoff ? maxBackoff : exp;
  }

  void _scheduleReconnect() {
    if (_disposed || _reconnecting) return;
    _reconnecting = true;
    _attempt++;
    final delay = _backoffFor(_attempt);
    _state.add(WssConnectionState.reconnecting);
    _stopKeepalive();
    _backoffTimer?.cancel();
    _backoffTimer = Timer(delay, () {
      _reconnecting = false;
      _connectOnce();
    });
  }

  /// 释放全部资源。
  void dispose() {
    _disposed = true;
    _backoffTimer?.cancel();
    _stopKeepalive();
    try {
      _channel?.sink.close();
    } catch (_) {}
    _channel = null;
    _frames.close();
    _state.close();
  }
}
