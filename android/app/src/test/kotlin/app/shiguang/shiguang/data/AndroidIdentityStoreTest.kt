package app.shiguang.shiguang.data

import app.shiguang.core.model.collab.IdentityRecord
import app.shiguang.core.model.collab.MemorySecretStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** AndroidIdentityStore 安全区适配往返（D27 协作轮容器装配；JVM=MemorySecretStore）。 */
class AndroidIdentityStoreTest {

    private fun record() = IdentityRecord(
        userId = "u1",
        sealedIdentityPrivateKey = ByteArray(32) { it.toByte() },
        signedPreKeyPub = ByteArray(32) { (it + 7).toByte() },
        opkRemaining = 3,
        sealedSignedPreKeyPrivate = ByteArray(32) { (it + 13).toByte() },
    )

    @Test
    fun `save load roundtrip preserves all fields`() = runTest {
        val store = AndroidIdentityStore(MemorySecretStore())
        store.save(record())
        val loaded = store.load()
        assertNotNull(loaded)
        assertEquals("u1", loaded!!.userId)
        assertEquals(record().sealedIdentityPrivateKey.toList(), loaded.sealedIdentityPrivateKey.toList())
        assertEquals(record().signedPreKeyPub.toList(), loaded.signedPreKeyPub.toList())
        assertEquals(3, loaded.opkRemaining)
        assertEquals(
            record().sealedSignedPreKeyPrivate!!.toList(),
            loaded.sealedSignedPreKeyPrivate!!.toList(),
        )
    }

    @Test
    fun `load empty returns null`() = runTest {
        assertNull(AndroidIdentityStore(MemorySecretStore()).load())
    }

    @Test
    fun `clear removes record`() = runTest {
        val store = AndroidIdentityStore(MemorySecretStore())
        store.save(record())
        store.clear()
        assertNull(store.load())
    }
}
