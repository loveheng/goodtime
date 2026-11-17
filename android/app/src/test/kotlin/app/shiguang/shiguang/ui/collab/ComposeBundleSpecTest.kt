package app.shiguang.shiguang.ui.collab

import app.shiguang.core.model.collab.DrainReport
import app.shiguang.core.model.collab.composeFailureText
import app.shiguang.core.model.collab.composeSentOut
import app.shiguang.core.model.collab.CreatedBundle
import app.shiguang.core.model.collab.relay.RelayAuthException
import app.shiguang.core.model.collab.relay.RelayPeersException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发起协同回执对拍（behavior/compose_bundle.md §A 逐字，T4.5）：composeSentOut
 * 判定 + composeFailureText 白话翻译映射——口径=lib/collab/propose.dart 镜像
 * （Kotlin 补课=Outbox.kt 同文件承载）。
 */
class ComposeBundleSpecTest {

    private fun report(stopped: Boolean, createdCount: Int) = DrainReport(
        sent = 0,
        conflicts = emptyList(),
        stoppedByNetwork = stopped,
        created = List(createdCount) { CreatedBundle(entryId = "e$it", localBundleId = "local-x", bundleId = "b$it", seqId = 1) },
    )

    @Test
    fun `composeSentOut 判定`() {
        assertFalse(composeSentOut(null))
        assertFalse(composeSentOut(report(stopped = true, createdCount = 1)))
        assertFalse(composeSentOut(report(stopped = false, createdCount = 0)))
        assertTrue(composeSentOut(report(stopped = false, createdCount = 1)))
    }

    @Test
    fun `composeFailureText relay 状态码映射逐字`() {
        assertEquals(
            "没发出去：登录态已过期，请到「设置 → 协同账号」重新登录",
            composeFailureText(RelayAuthException(401, "detail")),
        )
        assertEquals(
            "没发出去：取不到对方的通信公钥，让对方重新登录一次再约",
            composeFailureText(RelayPeersException(404, "detail")),
        )
        assertEquals("没发出去：操作太频繁，稍后再试", composeFailureText(RelayAuthException(429, "d")))
        assertEquals("没发出去：中继打回了这次邀请（403），稍后再试", composeFailureText(RelayAuthException(403, "d")))
    }

    @Test
    fun `composeFailureText 网络与兜底`() {
        assertEquals("没发出去：暂时连不上中继，联网后再试一次", composeFailureText(IOException("boom")))
        val generic = composeFailureText(IllegalStateException("装箱炸了"))
        assertTrue(generic.startsWith("没发出去："))
        assertTrue(generic.contains("装箱炸了"))
    }
}
