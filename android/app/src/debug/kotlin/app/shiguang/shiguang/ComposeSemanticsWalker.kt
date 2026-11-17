package app.shiguang.shiguang

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import com.appdebugbridge.ui.DebugNodeAction
import com.appdebugbridge.ui.SemanticsNodeDescriptor

/**
 * Compose 语义树 → SDK SemanticsNodeDescriptor 适配器（app-debug-bridge 0.5.0 原生
 * 宿主注入面；Dart 宿主无语义面故未注入）。取面=AndroidComposeView.semanticsOwner
 * 直取 SemanticsNode 树（无障碍虚拟视图树不随 View 级节点下发子树——2026-11-15
 * 真机实证 provider/createAccessibilityNodeInfo 两路均只得壳节点）；动作面=
 * SemanticsActions 直调（OnClick/OnLongClick/SetText/ImeAction/ScrollBy）。
 *
 * 走查纪律：只读语义 + 触发既有语义动作，零业务写入面；数据断言/还原走
 * NativeDebugBridgeWiring 自定义动作（repo_read/repo_set 族）。
 */
object ComposeSemanticsWalker {

    /** 单页语义树上限（防病态树拖死 HTTP 面；SDK DebugUiTree 同口径）。 */
    private const val MAX_NODES = 300

    /** 深度上限（Compose 语义树正常 ≤30；防御环）。 */
    private const val MAX_DEPTH = 40

    /** AndroidComposeView internal——反射取语义属主（compose 契约方法名稳定）。 */
    private val getSemanticsOwner: (View) -> SemanticsOwner? = { view ->
        try {
            @Suppress("UNCHECKED_CAST")
            val m = view.javaClass.methods.firstOrNull { it.name == "getSemanticsOwner" }
            m?.invoke(view) as? SemanticsOwner
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 收集语义描述树（SDK semanticsTreeProvider 自行 flatten）。扫面=全窗口根视图
     * （WindowManagerGlobal.mViews 反射——ModalBottomSheet/Dialog 在独立窗口，
     *  Activity decorView 不含其语义树，2026-11-15 真机实证）+ 参数 root 兜底。
     */
    fun collect(root: View): List<SemanticsNodeDescriptor> {
        val out = mutableListOf<SemanticsNodeDescriptor>()
        val counter = intArrayOf(0)
        val scanned = java.util.IdentityHashMap<View, Boolean>()
        for (rootView in allRootViews() + listOf(root)) {
            if (scanned.put(rootView, true) == true) continue
            scanView(rootView, out, counter)
        }
        return out
    }

    /** 全窗口根视图（mViews 反射；含 dialog/浮层窗口——debug-only 走查面）。 */
    private fun allRootViews(): List<View> = try {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        @Suppress("UNCHECKED_CAST")
        val views = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as? List<View>
        views ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun scanView(
        view: View,
        out: MutableList<SemanticsNodeDescriptor>,
        counter: IntArray,
    ) {
        if (out.size >= MAX_NODES) return
        // 语义属主直取（反射；不沾无障碍框架的虚拟视图解析）
        val owner = getSemanticsOwner(view)
        if (owner != null) {
            // 单根语义树（semanticsOwner 契约面；树内部含全部子节点）
            val root = owner.rootSemanticsNode
            toDescriptor(root, out, counter, depth = 0)?.let { out.add(it) }
            return // 语义树已覆盖 Compose 子树
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                scanView(view.getChildAt(i), out, counter)
            }
        }
    }

    private fun toDescriptor(
        node: SemanticsNode,
        out: MutableList<SemanticsNodeDescriptor>,
        counter: IntArray,
        depth: Int,
    ): SemanticsNodeDescriptor? {
        if (counter[0] >= MAX_NODES || depth >= MAX_DEPTH) return null
        counter[0]++
        val config = node.config
        // Text/ContentDescription 条目统一 AnnotatedString 兼容面
        // SemanticsConfiguration.get 缺键即抛——一律 contains 守卫（真机实证）
        val text = if (config.contains(SemanticsProperties.Text)) {
            config[SemanticsProperties.Text]
                .joinToString("\n") { (it as? androidx.compose.ui.text.AnnotatedString)?.text ?: it.toString() }
                .takeIf { it.isNotEmpty() }
        } else null
        val desc = if (config.contains(SemanticsProperties.ContentDescription)) {
            config[SemanticsProperties.ContentDescription]
                .joinToString("\n") { (it as? androidx.compose.ui.text.AnnotatedString)?.text ?: it.toString() }
                .takeIf { it.isNotEmpty() }
        } else null
        val bounds = node.boundsInWindow
        val children = node.children.mapNotNull { toDescriptor(it, out, counter, depth + 1) }
        return SemanticsNodeDescriptor(
            key = (desc ?: text ?: "node${counter[0]}"),
            label = (desc ?: text).orEmpty(),
            text = (text ?: desc).orEmpty(),
            type = classify(config),
            testTag = if (config.contains(SemanticsProperties.TestTag)) {
                config[SemanticsProperties.TestTag]
                    .let { (it as? androidx.compose.ui.text.AnnotatedString)?.text ?: it.toString() }
            } else "",
            stableId = "${node.id}-${bounds.left}-${bounds.top}",
            clickable = config.contains(SemanticsActions.OnClick),
            editable = config.contains(SemanticsActions.SetText),
            interactive = config.contains(SemanticsActions.OnClick) ||
                config.contains(SemanticsActions.OnLongClick) ||
                config.contains(SemanticsActions.SetText),
            bounds = listOf(
                bounds.left.toDouble(),
                bounds.top.toDouble(),
                bounds.width.toDouble(),
                bounds.height.toDouble(),
            ),
            action = NodeAction(config),
            children = children,
        )
    }

    private fun classify(config: androidx.compose.ui.semantics.SemanticsConfiguration): String = when {
        config.contains(SemanticsActions.SetText) -> "input"
        config.contains(SemanticsActions.OnClick) -> "button"
        else -> "text"
    }

    /** SemanticsConfiguration 动作面 → SDK DebugNodeAction（直调语义动作 lambda）。 */
    private class NodeAction(private val config: androidx.compose.ui.semantics.SemanticsConfiguration) : DebugNodeAction {
        override fun performClick(): Boolean = try {
            if (config.contains(SemanticsActions.OnClick)) config[SemanticsActions.OnClick].action?.invoke() == true else false
        } catch (e: Exception) {
            false
        }

        override fun performLongClick(): Boolean = try {
            if (config.contains(SemanticsActions.OnLongClick)) config[SemanticsActions.OnLongClick].action?.invoke() == true else false
        } catch (e: Exception) {
            false
        }

        override fun performTextInput(text: String): Boolean = try {
            if (config.contains(SemanticsActions.SetText)) {
                config[SemanticsActions.SetText].action
                    ?.invoke(androidx.compose.ui.text.AnnotatedString(text)) == true
            } else false
        } catch (e: Exception) {
            false
        }

        override fun performImeAction(actionName: String): Boolean = try {
            if (config.contains(SemanticsActions.OnImeAction)) config[SemanticsActions.OnImeAction].action?.invoke() == true else false
        } catch (e: Exception) {
            false
        }

        override fun performScroll(deltaX: Double, deltaY: Double): Boolean = try {
            if (config.contains(SemanticsActions.ScrollBy)) {
                config[SemanticsActions.ScrollBy].action?.invoke(deltaX.toFloat(), deltaY.toFloat()) == true
            } else false
        } catch (e: Exception) {
            false
        }

        /** 惯性滑动手势无语义动作对应——诚实拒（不冒充）。 */
        override fun performSwipe(fromX: Double, fromY: Double): Boolean = false
    }
}
