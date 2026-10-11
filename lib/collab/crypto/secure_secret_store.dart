import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'secret_store.dart';

/// 生产实现：密钥材料落设备安全区（Android Keystore / iOS Keychain）。
///
/// 纪律：**明文密钥永不落 SharedPreferences / 本地 SQLite**——
/// 本地库由 SQLCipher 全盘加密（§11.1），密钥本身只在安全区与内存。
class SecureSecretStore implements SecretStore {
  SecureSecretStore({FlutterSecureStorage? storage})
      : _storage = storage ?? const FlutterSecureStorage();

  final FlutterSecureStorage _storage;

  @override
  Future<String?> read(String key) => _storage.read(key: key);

  @override
  Future<void> write(String key, String value) => _storage.write(key: key, value: value);

  @override
  Future<void> delete(String key) => _storage.delete(key: key);
}
