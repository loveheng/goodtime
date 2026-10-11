import 'dart:convert';

/// 密钥材料的持久化通道抽象（协同设计 §5.3）。
///
/// 生产实现走设备安全区（Keychain/Keystore，见 [SecureSecretStore]）；
/// 测试注入 [MemorySecretStore]，无需平台插件即可验证密码学逻辑。
abstract class SecretStore {
  Future<String?> read(String key);
  Future<void> write(String key, String value);
  Future<void> delete(String key);
}

/// 内存实现（测试用）：进程退出即消失，绝不用于生产。
class MemorySecretStore implements SecretStore {
  final Map<String, String> _map = {};

  @override
  Future<String?> read(String key) async => _map[key];

  @override
  Future<void> write(String key, String value) async => _map[key] = value;

  @override
  Future<void> delete(String key) async => _map.remove(key);
}

/// 密文封装的落盘形态：JSON（nonce/cipherText/mac 三段 base64）。
///
/// 不用 SecretBox.concatenation() 之类的隐式拼接——三段显式命名，
/// 将来换算法/换 mac 长度时旧数据仍可识别，不会被当成一团字节猜。
class SealedBlob {
  final List<int> nonce;
  final List<int> cipherText;
  final List<int> mac;

  const SealedBlob({
    required this.nonce,
    required this.cipherText,
    required this.mac,
  });

  Map<String, String> toJson() => {
        'nonce': base64Encode(nonce),
        'cipherText': base64Encode(cipherText),
        'mac': base64Encode(mac),
      };

  String encode() => jsonEncode(toJson());

  static SealedBlob decode(String raw) {
    final map = jsonDecode(raw) as Map<String, dynamic>;
    return SealedBlob(
      nonce: base64Decode(map['nonce'] as String),
      cipherText: base64Decode(map['cipherText'] as String),
      mac: base64Decode(map['mac'] as String),
    );
  }
}
