package app.shiguang.shiguang.ui.collab

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 扫码页错误映射对拍（behavior/scan.md §A 转正清单逐字，D26）：状态码五分支 +
 * 解析失败/中继不可达/成功文案 + 双闸防重状态机——口径=lib/ui/collab/scan_page.dart 镜像。
 *
 * 双闸用例确定性化（D27 抓修）：SharedFlow 无 replay——emit 先于订阅即丢失（全量
 * 套件并发下曾白绿夜红）；显式 setMain(UnconfinedTestDispatcher) 隔离跨类 Main
 * 委托泄漏，先 onSubscription 挂位再 handle。
 */
class ScanErrorMapTest {

    private val ticketJson = """{"issuer_user_id":"u1","nonce":"n1","expires_at":1,"signature":"AAAA","enc_key_pub":"AAAA"}"""

    @Test
    fun `邀请错误状态码五分支`() {
        assertEquals("你们已经是好友，或申请已在处理中", ScanViewModel.inviteErrorText(409))
        assertEquals("这张名片已过期或被用过，让对方刷新一下", ScanViewModel.inviteErrorText(400))
        assertEquals("登录态已过期，请到「设置 → 协同账号」重新登录", ScanViewModel.inviteErrorText(401))
        assertEquals("名片不对，换一张试试", ScanViewModel.inviteErrorText(403))
        assertEquals("发送失败，稍后再试", ScanViewModel.inviteErrorText(500))
        assertEquals("发送失败，稍后再试", ScanViewModel.inviteErrorText(418))
    }

    @Test
    fun `固定文案逐字`() {
        assertEquals("这串不是有效的好友名片，换一张二维码试试", ScanViewModel.ErrInvalidCard)
        assertEquals("暂时连不上中继，稍后再试", ScanViewModel.ErrRelayUnreachable)
        assertEquals("已发出好友申请，等对方确认", ScanViewModel.OkInviteSent)
    }

    @Test
    fun `双闸防重状态机`() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
            val vm = ScanViewModel(submitInvite = { })
            vm.setPermission(true)
            // 空 trim 不走（无 done 翻转）
            vm.handle("   ")
            assertTrue(!vm.uiState.value.submitting)
            // 好码：先订阅后触发（onSubscription 挂位才 handle——emit 不再竞态丢件）
            val sent = withTimeout(2000) {
                coroutineScope {
                    val subscribed = CompletableDeferred<Unit>()
                    val wait = async {
                        vm.events.onSubscription { subscribed.complete(Unit) }
                            .first { it is ScanEvent.InviteSent }
                    }
                    subscribed.await()
                    vm.handle(ticketJson)
                    wait.await()
                }
            }
            assertTrue(sent is ScanEvent.InviteSent)
            // done 闸：随后码不再触发（Unconfined=submit 同步完成，无需等调度）
            var called = 0
            val vm2 = ScanViewModel(submitInvite = { called++ })
            vm2.setPermission(true)
            vm2.handle(ticketJson)
            vm2.handle(ticketJson)
            delay(200)
            assertEquals(1, called, "done 闸防重（一码一次）")
        } finally {
            Dispatchers.resetMain()
        }
    }
}
