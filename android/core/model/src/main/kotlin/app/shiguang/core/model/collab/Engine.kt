package app.shiguang.core.model.collab

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import app.shiguang.core.model.SqlStore
import app.shiguang.core.model.collab.relay.CollabInboundEvent
import app.shiguang.core.model.collab.relay.HttpOutboxTransport
import app.shiguang.core.model.collab.OutboxTransport
import app.shiguang.core.model.collab.relay.SyncPage
import app.shiguang.core.model.collab.relay.RelayWssClient
import app.shiguang.core.model.collab.relay.SingleConsumerPipeline
import app.shiguang.core.model.collab.relay.WakePayload
import app.shiguang.core.model.collab.relay.WakePuller
import shiguang.relay.v1.RelayProtocol.ActionType

/**
 * 协同编排收口引擎（lib/collab/engine.dart Kotlin 等价，Phase 4 编排收口项）。
 *
 * 把零散组件焊接成一条**常驻运行**的协同回路：
 * - [SingleConsumerPipeline]：WSS 实时推送 + /sync 补拉两路汇入同一条串行 FIFO；
 * - WakePuller.sinceSeqOf ← [CollabBundleRepository.maxSeqId]（游标接 collab 表，
 *   进程被杀后从本地水位精准续拉，不重复拖全量）；
 * - 订阅 pipeline.applied → [CollabBundleRepository.recordEvent]（落库水位 + 原始
 *   事件，供 FSM 重放；这里只保真落库，不解密；REKEY 经 rekey 旗标推进包头列——D9）；
 * - RelayWssClient.attachWss 接入实时流；
 * - [CollabDrainScheduler] 把 OutboxDrainer 挂到连通性监听（网络恢复自动弹出）。
 */
class CollabEngine(
    val pipeline: SingleConsumerPipeline,
    val repo: CollabBundleRepository,
    val wakePuller: WakePuller,
    val drainScheduler: CollabDrainScheduler,
    val wssClient: RelayWssClient? = null,
    /** 某个包的事件首次到达时的钩子（被邀端密钥入户的触发点）。只补一次（seen 去重），
     *  失败静默——解不开的那段历史按半盲处理，骨架照常推进。 */
    val onBundleSeen: (suspend (String) -> Unit)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var appliedJob: Job? = null
    private val seen = mutableSetOf<String>()

    /** 焊接回路：接入 WSS 实时流 + 订阅 applied 落库 + 启动常驻发件箱调度。 */
    fun start() {
        wssClient?.let { pipeline.attachWss(it) }
        // UNDISPATCHED：订阅同步完成（镜像 Dart broadcast listen 的同步语义——
        // SharedFlow 无 replay，订阅前的 applied 事件会丢）。
        appliedJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            pipeline.applied.collect { e -> onApplied(e) }
        }
        scope.launch { drainScheduler.start() }
    }

    private suspend fun onApplied(e: CollabInboundEvent) {
        try {
            repo.recordEvent(
                bundleId = e.bundleId,
                seqId = e.seqId,
                actionType = e.action.name,
                senderId = e.senderHint ?: "",
                cipher = e.cipher ?: ByteArray(0),
                keyVer = e.keyVer ?: 1,
                clientMsgId = e.clientMsgId ?: "${e.bundleId}:${e.seqId}",
                topologyRef = e.topologyRef,
                serverAtMs = e.serverAtMs,
                // REKEY 应用即推进包头 key_version 列（D9 拍板，FSM keyVersion+1 持久投影）
                rekey = e.action == ActionType.REKEY,
            )
        } catch (e2: CancellationException) {
            throw e2
        }
        // 不吞落库异常：与 Dart 一致（applied 链上的错误保持可见，不静默半盲）。
        // 收到某包第一条事件 = 我被拉进了一个包（被邀）：趁在线补拉我的 K_bundle 信封
        if (onBundleSeen != null && seen.add(e.bundleId)) {
            try {
                onBundleSeen.invoke(e.bundleId)
            } catch (e2: CancellationException) {
                throw e2
            } catch (_: Exception) {
                // 入户失败（未解锁/无网络/密钥缺失）全部按半盲降级，绝不拖垮落库回路
            }
        }
    }

    /** 静默唤醒入口（系统推送唤醒 → Wake Pull 精准补拉，已落库水位续拉）。 */
    suspend fun onWake(wake: WakePayload): Long = wakePuller.onWake(wake)

    /** 主动补拉一个包（冷启动 / 手动）。 */
    suspend fun pullBundle(bundleId: String, latestSeqHint: Long? = null): Long =
        wakePuller.pullBundle(bundleId, latestSeqHint)

    /** 触发一轮发件箱弹出（回前台 / 心跳 / 手动同步）。 */
    suspend fun drainNow(): DrainReport = drainScheduler.trigger()

    /** 统一释放全部常驻资源。 */
    suspend fun dispose() {
        appliedJob?.cancelAndJoin()
        drainScheduler.dispose()
        wssClient?.dispose()
        pipeline.close()
        scope.cancel()
    }
}

/**
 * 以 [RelayConfig]（= 编译期 RELAY_BASE_URL）为唯一地址源，构造并焊接整套协同回路，
 * 返回可直接 [CollabEngine.start] 的常驻引擎（lib/collab/engine.dart wireCollabSystem 等价）。
 *
 * 三路传输统一吃 config 的 base URL（HTTP 基址 → WSS /ws 纯路径由 RelayConfig.wssUri
 * 推导），真 relay 联调只需改 RELAY_BASE_URL 一处。网络相关组件可在测试时经 override
 * 注入脚本化替身，避免触碰真网。
 */
fun wireCollabSystem(
    config: app.shiguang.core.model.collab.relay.RelayConfig,
    db: SqlStore,
    client: okhttp3.OkHttpClient,
    tokenProvider: suspend () -> String,
    bundleKey: suspend (String, Int) -> ByteArray,
    connectivity: ConnectivityWatcher,
    /** 本地发起 bundle 的密钥检索源；给值即自动挂上认祖归宗钩子。不给（默认）时本
     *  引擎仍是纯「接收 + 弹出」回路；要承载本地发起就必须给值。 */
    bundleKeys: BundleKeySource? = null,
    onBundleSeen: (suspend (String) -> Unit)? = null,
    transportOverride: OutboxTransport? = null,
    pullOverride: (suspend (String, Long) -> SyncPage)? = null,
    wssOverride: RelayWssClient? = null,
): CollabEngine {
    val pipeline = app.shiguang.core.model.collab.relay.SingleConsumerPipeline()
    val repo = CollabBundleRepository(db)
    val wssClient = wssOverride ?: RelayWssClient(
        wsUri = config.wssUri,
        getToken = { tokenProvider() },
        baseClient = client,
    )
    val pull: suspend (String, Long) -> SyncPage = pullOverride ?: { bundleId: String, sinceSeq: Long ->
        app.shiguang.core.model.collab.relay.fetchSyncPage(client, config.baseUrl, tokenProvider(), bundleId, sinceSeq)
    }
    val wakePuller = WakePuller(
        pipeline = pipeline,
        pull = pull,
        sinceSeqOf = { bundleId -> repo.maxSeqId(bundleId) }, // 游标接 collab 表
    )
    val transport = transportOverride ?: HttpOutboxTransport(
        client = client,
        baseUrl = config.baseUrl,
        accessToken = { tokenProvider() },
        bundleKey = bundleKey,
    )
    val outboxRepo = OutboxRepository(db)
    val drainer = OutboxDrainer(
        outboxRepo,
        transport,
        onBundleCreated = bundleKeys?.let { keys ->
            val reconciler = PendingProposalReconciler(
                bundles = repo,
                keys = keys,
                outbox = outboxRepo,
            )
            ({ entry, ack -> reconciler(entry, ack) })
        },
    )
    val drainScheduler = CollabDrainScheduler(drainer, connectivity)
    return CollabEngine(
        pipeline = pipeline,
        repo = repo,
        wakePuller = wakePuller,
        drainScheduler = drainScheduler,
        wssClient = wssClient,
        onBundleSeen = onBundleSeen,
    )
}
