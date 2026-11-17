package app.shiguang.shiguang

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withNameEndingWith
import com.lemonappdev.konsist.api.ext.list.withPackage
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.Test

/**
 * Konsist 四条类型级护栏（todo P4 既定，P4 起生效；coding-standards §1 分层矩阵的
 * 类型级执行面，与 K1–K8 文本护栏互补）。作用域 = :app production 源码（UI 全住
 * :app，三层模块图既定）；工作目录锚 = gradle 单测默认 module 目录（android/app）。
 */
class ArchitectureKonsistTest {
    private val scope = Konsist.scopeFromDirectory("app/src/main")
    private val dataLayerRegex = Regex(""".*\.shiguang\.(data|storage|network)\..*""")
    private val repoCallRegex = Regex("""\w+(Repository|Dao)\s*[.(\s]""")

    /** P4-1：UI 包不得依赖 data / storage / network 包。 */
    @Test
    fun `ui package does not import data storage network`() {
        scope.files
            .withPackage("..shiguang.ui..")
            .assertTrue { file ->
                file.imports.none { it.name.matches(dataLayerRegex) }
            }
    }

    /** P4-2：ViewModel 对外暴露属性必须是 StateFlow 而非 MutableStateFlow。 */
    @Test
    fun `viewmodel exposes StateFlow instead of MutableStateFlow`() {
        scope.classes(false, false)
            .withNameEndingWith("ViewModel")
            .assertTrue { viewModel ->
                viewModel.properties(false).none { property ->
                    !property.hasPrivateModifier && property.text.contains("MutableStateFlow")
                }
            }
    }

    /** P4-3：Composable 不得直接调用 Repository / Dao（函数体内禁止出现仓储触达）。 */
    @Test
    fun `composables do not call repository or dao`() {
        scope.functions(false, false)
            .assertTrue { function ->
                if (function.hasAnnotationWithName("Composable")) {
                    !repoCallRegex.containsMatchIn(function.text)
                } else {
                    true
                }
            }
    }

    /** P4-4：仓库层（Repository/Dao，类与接口双面）所在文件不得 import Compose（imports 住文件层）。 */
    @Test
    fun `repository layer does not import compose`() {
        val repoRegex = Regex("""\w+(Repository|Dao)\b""")
        scope.files
            .assertTrue { file ->
                val declaresRepoLayer =
                    file.classes(false, false).any { repoRegex.containsMatchIn(it.name ?: "") } ||
                        file.interfaces(false).any { repoRegex.containsMatchIn(it.name ?: "") }
                if (declaresRepoLayer) {
                    file.imports.none { it.name.startsWith("androidx.compose") }
                } else {
                    true
                }
            }
    }
}
