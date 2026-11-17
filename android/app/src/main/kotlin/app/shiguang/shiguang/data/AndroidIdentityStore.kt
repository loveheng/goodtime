package app.shiguang.shiguang.data

import app.shiguang.core.model.collab.IdentityRecord
import app.shiguang.core.model.collab.IdentityStore
import app.shiguang.core.model.collab.SecretStore

/**
 * 身份记录安全区存储（lib/collab/identity_store.dart SecureIdentityStore 对位，
 * D27 协作轮容器装配）：IdentityRecord JSON 入 [SecretStore]（生产=
 * [AndroidSecretStore] EncryptedSharedPreferences）。明文密钥只驻内存/安全区，
 * 绝不落 SP/SQLite（§5.3）。
 */
class AndroidIdentityStore(private val secrets: SecretStore) : IdentityStore {

    override suspend fun load(): IdentityRecord? =
        secrets.read(KEY)?.let { IdentityRecord.decode(it) }

    override suspend fun save(record: IdentityRecord) {
        secrets.write(KEY, record.encode())
    }

    override suspend fun clear() {
        secrets.delete(KEY)
    }

    companion object {
        /** 安全区键（Dart SecureIdentityStore._key 同源；D11 拍板存量不迁移，同名无害）。 */
        private const val KEY = "collab.identity.record"
    }
}
