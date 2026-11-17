package app.shiguang.shiguang.ui.collab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.shiguang.core.model.collab.CollabBundleRepository
import app.shiguang.core.model.collab.CollabStep
import app.shiguang.shiguang.ShiguangApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 排期画板轻 ViewModel（behavior/collab_canvas.md 转正清单 §A，T4.11）：步骤块=
 * listSteps 已回填时段投影；掩码/私事区间由调用方注入（B 档播种器挂账——空=
 * 共振条降级态，Ghost 层不渲染）。
 */
class CollabCanvasViewModel(
    private val collabRepository: CollabBundleRepository,
    private val bundleId: String,
) : ViewModel() {

    data class CanvasUiState(
        val loading: Boolean = true,
        /** 全量步骤（时段回填筛选在渲染层：startsAtMs!=null）。 */
        val steps: List<CollabStep> = emptyList(),
        /** 全员 FreeMask（同区间同长；空=降级）。 */
        val masks: List<String> = emptyList(),
        /** 发起人私事忙碌区间（幽灵图层数据源；空=不投影）。 */
        val privateBusy: List<Pair<Long, Long>> = emptyList(),
    )

    private val _uiState = MutableStateFlow(CanvasUiState())
    val uiState: StateFlow<CanvasUiState> = _uiState

    /** 装载（IO）：listSteps 投影；masks/privateBusy=B 档播种器挂账（恒空降级）。 */
    fun load() {
        viewModelScope.launch {
            val steps = withContext(Dispatchers.IO) { collabRepository.listSteps(bundleId) }
            _uiState.value = CanvasUiState(loading = false, steps = steps)
        }
    }

    companion object {
        /** Manual DI Factory（容器协同栈注入）。 */
        fun factory(bundleId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ShiguangApplication
                CollabCanvasViewModel(
                    collabRepository = app.container.collabRepository,
                    bundleId = bundleId,
                )
            }
        }
    }
}
