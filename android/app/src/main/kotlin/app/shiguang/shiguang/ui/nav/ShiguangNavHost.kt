package app.shiguang.shiguang.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.navOptions

/**
 * 路由薄宿主（P4①-d）：只承载 NavHost 接缝，页面随各页轮以 composable&lt;ShiguangRoute.X&gt;
 * 注册进 builder——不预建空目的地桩（禁脚手架式预建）。
 */
@Composable
fun ShiguangNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: ShiguangRoute = ShiguangRoute.Onboarding,
    builder: NavGraphBuilder.() -> Unit,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        builder = builder,
    )
}

/**
 * 三 Tab 切换（OP1 裁决口径）：popUpTo 起点栈 + saveState/restoreState——Tab 切换
 * 不入返回栈、各自状态保留；快记 FAB 两 Tab 常驻由 bottomBar 宿主条件渲染承载。
 * 注：平面单层图，`graph.startDestinationId` 与嵌套图 findStartDestination 等价
 * （后者在 navigation 2.10.2 已移除，实查无此 API）。
 */
fun NavHostController.navigateTab(route: ShiguangRoute) {
    navigate(route, navOptions {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    })
}
