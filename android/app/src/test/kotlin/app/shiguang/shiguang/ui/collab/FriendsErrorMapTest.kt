package app.shiguang.shiguang.ui.collab

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 好友域错误映射对拍（behavior/friends.md §A 逐字，T4.1）：关系处置三态 + 搜索
 * 双异常族（账号域/好友域）+ 门禁/横幅固定文案——口径=lib/ui/collab/friends_page.dart 镜像。
 */
class FriendsErrorMapTest {

    @Test
    fun `关系处置错误映射四分支`() {
        assertEquals("这条关系已不存在，刷新一下", FriendsViewModel.manageErrorText(404))
        assertEquals("拉黑列表已满（上限 500），先清理一下", FriendsViewModel.manageErrorText(400))
        assertEquals("登录态已过期，请到「设置 → 协同账号」重新登录", FriendsViewModel.manageErrorText(401))
        assertEquals("操作失败，稍后再试", FriendsViewModel.manageErrorText(500))
    }

    @Test
    fun `搜索账号域错误映射四分支`() {
        assertEquals("没有找到这个用户名", FriendsViewModel.searchAuthErrorText(404))
        assertEquals("操作太频繁，稍后再试", FriendsViewModel.searchAuthErrorText(429))
        assertEquals("登录态已过期，请到「设置 → 协同账号」重新登录", FriendsViewModel.searchAuthErrorText(401))
        assertEquals("发送失败，稍后再试", FriendsViewModel.searchAuthErrorText(418))
    }

    @Test
    fun `搜索好友域错误映射四分支`() {
        assertEquals("关系已存在", FriendsViewModel.searchFriendsErrorText(409))
        assertEquals("操作太频繁，稍后再试", FriendsViewModel.searchFriendsErrorText(429))
        assertEquals("登录态已过期，请到「设置 → 协同账号」重新登录", FriendsViewModel.searchFriendsErrorText(401))
        assertEquals("发送失败，稍后再试", FriendsViewModel.searchFriendsErrorText(418))
    }

    @Test
    fun `固定文案逐字`() {
        assertEquals("到「设置 → 协同账号」完成登录与协同身份注册，才能管理好友", FriendsViewModel.SessionGateError)
        assertEquals("暂时连不上中继，取不到好友名单", FriendsViewModel.ErrListFetch)
        assertEquals("暂时连不上中继，稍后再试", FriendsViewModel.ErrRelayUnreachable)
        assertEquals("这是你发出的邀请，等待对方确认", FriendsViewModel.RespondErrSelfInvited)
        assertEquals("操作失败了，稍后再试", FriendsViewModel.RespondErrGeneric)
    }
}
