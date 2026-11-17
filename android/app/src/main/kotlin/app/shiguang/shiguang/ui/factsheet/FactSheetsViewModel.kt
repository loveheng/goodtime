package app.shiguang.shiguang.ui.factsheet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.shiguang.core.action.ActionException
import app.shiguang.core.action.BridgeRepositoryPort
import app.shiguang.core.action.CommandActor
import app.shiguang.core.action.CommandHandler
import app.shiguang.core.action.DbWriteQueue
import app.shiguang.core.action.ScheduleCommand
import app.shiguang.core.model.Artifact
import app.shiguang.core.model.Plan
import app.shiguang.shiguang.ShiguangApplication
import app.shiguang.shiguang.ui.schedule.UiEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** 通关卡 sheet 族轻 ViewModel（D15 同形）：挂载/改挂/删除命令 + 装载面 + 事件。 */
class FactSheetsViewModel(
    /** 仓储读端口（K8 白名单族——装载走 suspend 读面，写=命令面 handler）。 */
    val repo: BridgeRepositoryPort,
) : ViewModel() {

    /** 挂载 sheet 装配数据（artifactById/listPlans/artifactsUnattributed 三查并行语义）。 */
    data class FactMountData(
        val fact: Artifact,
        val plans: List<Plan>,
        val unattributed: List<Artifact>,
    )

    private val handler = CommandHandler(repo)

    /** 变更刷新面透传（面板 revision 驱动重载——K8 面 composable 零 repo 触达）。 */
    val revision: kotlinx.coroutines.flow.StateFlow<Long> = repo.revision

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events

    suspend fun mountData(factId: String): FactMountData? = withContext(Dispatchers.IO) {
        val fact = repo.artifactById(factId)?.let { Artifact.fromMap(it) } ?: return@withContext null
        val plans = (repo.listPlans() as? JsonArray)?.filterIsInstance<JsonObject>()?.map { Plan.fromMap(it) } ?: emptyList()
        val unattr = (repo.artifactsUnattributed() as? JsonArray)?.filterIsInstance<JsonObject>()?.map { Artifact.fromMap(it) } ?: emptyList()
        FactMountData(fact, plans, unattr)
    }

    suspend fun unattributed(): List<Artifact> = withContext(Dispatchers.IO) {
        (repo.artifactsUnattributed() as? JsonArray)?.filterIsInstance<JsonObject>()?.map { Artifact.fromMap(it) } ?: emptyList()
    }

    suspend fun plans(): List<Plan> = withContext(Dispatchers.IO) {
        (repo.listPlans() as? JsonArray)?.filterIsInstance<JsonObject>()?.map { Plan.fromMap(it) } ?: emptyList()
    }

    /** 归属（挂载 sheet）：成功 note 2s→true（页 pop）；失败 message 3s。 */
    suspend fun mountTo(factId: String, planId: String): Boolean = try {
        val r = withContext(DbWriteQueue.dispatcher) {
            handler.execute(ScheduleCommand.UpsertFacts(id = factId, planId = planId), actor = CommandActor.HUMAN)
        }
        _events.tryEmit(UiEvent(message = r.note ?: "已归属", durationMs = 2000))
        true
    } catch (e: ActionException) {
        _events.tryEmit(UiEvent(message = e.message, durationMs = 3000))
        false
    }

    /** 改挂（常驻组/面板长按）：成功 note 2s；失败默认 4s（Dart 无 duration 同构）。 */
    suspend fun remountTo(factId: String, planId: String): Boolean = try {
        val r = withContext(DbWriteQueue.dispatcher) {
            handler.execute(ScheduleCommand.UpsertFacts(id = factId, planId = planId), actor = CommandActor.HUMAN)
        }
        _events.tryEmit(UiEvent(message = r.note ?: "已改挂", durationMs = 2000))
        true
    } catch (e: ActionException) {
        _events.tryEmit(UiEvent(message = e.message, durationMs = 4000))
        false
    }

    /** 删除（仅 human §0.4；删凭证绝不删块）：成功 note 2s；失败默认 4s。 */
    fun deleteFact(fact: Artifact) = viewModelScope.launch {
        try {
            val r = withContext(DbWriteQueue.dispatcher) {
                handler.execute(ScheduleCommand.DeleteArtifact(fact.id!!), actor = CommandActor.HUMAN)
            }
            _events.tryEmit(UiEvent(message = r.note ?: "已删除", durationMs = 2000))
        } catch (e: ActionException) {
            _events.tryEmit(UiEvent(message = e.message, durationMs = 4000))
        }
    }

    /** 通关卡复制动作事件（2s「已复制 {code}」）。 */
    fun notifyFactCopied(code: String) {
        _events.tryEmit(UiEvent(message = "已复制 $code", durationMs = 2000))
    }

    companion object {
        /** Manual DI Factory（AppContainer 纪律）。 */
        val factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ShiguangApplication
                FactSheetsViewModel(app.container.repo)
            }
        }
    }
}
