package app.shiguang.shiguang.ui.gate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.shiguang.core.action.BridgeRepositoryPort
import app.shiguang.core.action.CommandActor
import app.shiguang.core.action.CommandHandler
import app.shiguang.core.action.ScheduleCommand
import app.shiguang.core.action.ScheduleQueries
import app.shiguang.core.model.Artifact
import app.shiguang.shiguang.ShiguangApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * 入口闸门轻 ViewModel（D15 同形，N8c；behavior/app_gate.md §A/§B 转正清单）：
 * 决策响应式（initial 查一次，此后随 repo 写入通知 revision 变化重查——引导完成
 * 播种/设置被清空都能即时翻转，不依赖回调续跑）+ 分享转投消费面（§2.3：原生消息
 * 可早于首帧/监听注册到达——初始值非空直接排消费，collect 兜新消息；并发由
 * [shareMutex] 守卫，挂起语义留池）。
 */
class GateViewModel(
    private val repo: BridgeRepositoryPort,
    /** 分享转投池只读面（AppContainer.pendingShare 进程内直投——D23 裁决 §D.1）。 */
    private val pendingShareFlow: StateFlow<String?>,
    private val mountedBus: kotlinx.coroutines.flow.MutableSharedFlow<String>? = null,
    /** 消费完成清池回调（AppContainer.clearPendingShare——写入收口容器，K5）。 */
    private val clearShare: () -> Unit,
) : ViewModel() {

    /** 三态决策：null=载入中 / false=引导 / true=主框架（Dart _initialized 同形）。 */
    private val _initialized = MutableStateFlow<Boolean?>(null)
    val initialized: StateFlow<Boolean?> = _initialized

    /** 分享转投池只读视图（AppGate collect 兜新消息）。 */
    val pendingShare: StateFlow<String?> = pendingShareFlow

    private val handler = CommandHandler(repo)
    private val shareMutex = Mutex()

    init {
        viewModelScope.launch {
            repo.revision.collectLatest { _ -> reload() }
        }
    }

    private suspend fun reload() {
        val settings = withContext(Dispatchers.IO) { ScheduleQueries(repo).getSettings() }
        val flag = (settings["initialized"] as? JsonPrimitive)?.booleanOrNull ?: false
        _initialized.value = flag
    }

    /**
     * 消费待发分享（Gate 挂载时与 pendingShare 变化时调用——Dart postFrameCallback
     * 等义于 composition 后 LaunchedEffect，D23 裁决 §D.2）：raw 入册（origin=shared，
     * state=raw，title 取首行 ≤40 字，verbal/verbal 占位=出生确认条款 §1.2，零解析
     * 纪律在命令层）→ 挂载 sheet 归浮层族轮（未归属兜底静默照抄——§D.3）。
     */
    fun consumePendingShare() {
        viewModelScope.launch {
            shareMutex.withLock {
                // guard-then-read（Dart _shareRunning 同形）：文本读取在互斥段内，
                // 双发消费时后到者见空池即退出
                val text = pendingShareFlow.value?.trim() ?: return@withLock
                if (text.isEmpty()) return@withLock
                try {
                    val firstLine = text.split(Regex("[\r\n]")).first().trim()
                    val title = if (firstLine.length <= SHARE_TITLE_MAX) firstLine else firstLine.take(SHARE_TITLE_MAX)
                    val r = handler.execute(
                        ScheduleCommand.UpsertFacts(
                            category = Artifact.CATEGORY_VERBAL,
                            sourceKind = Artifact.SOURCE_VERBAL,
                            rawText = text,
                            title = title,
                            origin = Artifact.ORIGIN_SHARED,
                            state = Artifact.STATE_RAW,
                        ),
                        actor = CommandActor.HUMAN,
                    )
                    // 挂载 sheet 唤起（浮层族轮 D25 接线）：入册 factId 上抛壳层
                    (r.targetId ?: "")?.takeIf { it.isNotEmpty() }?.let { mountedBus?.tryEmit(it) }
                } finally {
                    clearShare()
                }
            }
        }
    }

    companion object {
        const val SHARE_TITLE_MAX = 40

        /** Manual DI Factory（AppContainer 纪律；pendingShare 由容器注入）。 */
        val factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ShiguangApplication
                GateViewModel(app.container.repo, app.container.pendingShare, app.container.mountedFactId) { app.container.clearPendingShare() }
            }
        }
    }
}
