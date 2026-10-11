import 'package:flutter_test/flutter_test.dart';

import 'package:shiguang/collab/relay_config.dart';

/// RelayConfig 单测：RELAY_BASE_URL 单一来源 + WSS 地址推导。
void main() {
  test('默认兜底：未注入 RELAY_BASE_URL 时回落模拟器回环地址', () {
    // 编译期未定义 dart-define 时取 defaultValue
    final c = RelayConfig.fromEnvironment();
    expect(c.baseUrl, 'http://10.0.2.2:8080');
  });

  test('显式构造：baseUrl 原样保留', () {
    const c = RelayConfig(baseUrl: 'https://relay.example.com:8443');
    expect(c.baseUrl, 'https://relay.example.com:8443');
  });

  test('wssUri 推导：http→ws 追加纯路径 /ws（含尾斜杠归一）', () {
    const c = RelayConfig(baseUrl: 'http://10.0.2.2:8080/');
    expect(c.wssUri.toString(), 'ws://10.0.2.2:8080/ws');
  });

  test('wssUri 推导：https→wss', () {
    const c = RelayConfig(baseUrl: 'https://relay.example.com');
    expect(c.wssUri.toString(), 'wss://relay.example.com/ws');
  });
}
