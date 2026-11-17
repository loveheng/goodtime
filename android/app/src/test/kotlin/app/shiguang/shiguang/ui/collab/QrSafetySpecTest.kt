package app.shiguang.shiguang.ui.collab

import app.shiguang.core.model.collab.safetyNumberDigits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 名片/安全码规格对拍（behavior/{my_qr,safety_number}.md §A，T4.7）：safetyNumberDigits
 * 派生形状（6 组×5 位数字）+确定性（同输入同输出，逐字节公钥）。
 */
class QrSafetySpecTest {

    private val myIk = ByteArray(32) { (it + 1).toByte() }
    private val peerIk = ByteArray(32) { (it + 17).toByte() }

    @Test
    fun `安全码形状 6 组 5 位数字`() {
        val digits = safetyNumberDigits(myIk, peerIk)
        assertEquals(6, digits.size)
        digits.forEach { group ->
            assertEquals(5, group.length)
            group.forEach { c -> assertTrue("$group 含非数字", c.isDigit()) }
        }
    }

    @Test
    fun `确定性同输入同输出`() {
        assertEquals(safetyNumberDigits(myIk, peerIk), safetyNumberDigits(myIk, peerIk))
    }

    @Test
    fun `固定文案逐字`() {
        assertEquals("暂时签不出名片，先到「设置 → 协同账号」登录或注册", MyQrViewModel.ErrIssue)
        assertEquals("暂时取不到对方公钥，连上中继后再试", SafetyNumberViewModel.ErrFetch)
    }
}
