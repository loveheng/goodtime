package app.shiguang.shiguang.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.shiguang.shiguang.ShiguangApplication
import app.shiguang.shiguang.ui.schedule.UiEvent
import app.shiguang.shiguang.update.ApkInstaller
import app.shiguang.shiguang.update.RemoteConfig
import app.shiguang.shiguang.update.RemoteConfigStore
import app.shiguang.shiguang.update.UpdateException
import app.shiguang.shiguang.update.UpdateManifest
import app.shiguang.shiguang.update.UpdateService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 更新页轻 ViewModel（D15 同形，N8c；behavior/update_page.md §A 转正清单）：
 * 四相位状态机 idle/checking/downloading/readyToInstall + 消息行 + 下载进度；
 * 检查更新成功即 RemoteConfigStore.save（公告/MCP instructions 落缓存）。
 * 平台面无 ScheduleCommand——更新源走 SharedPreferences 镜像键（R1 双写者）。
 */
class UpdateViewModel(
    private val service: UpdateService,
    private val remoteConfig: RemoteConfigStore,
    private val installer: ApkInstaller,
    versionName: String,
    versionCode: Long,
) : ViewModel() {

    enum class Phase { IDLE, CHECKING, DOWNLOADING, READY_TO_INSTALL }

    data class UiState(
        val ready: Boolean = false,
        val phase: Phase = Phase.IDLE,
        val message: String? = null,
        val manifest: UpdateManifest? = null,
        val announcement: String? = null,
        val apkPath: String? = null,
        val received: Int = 0,
        val total: Int? = null,
        val sourceUrl: String = "",
        val versionName: String = "",
        val versionCode: Long = 0,
    )

    private val _uiState = MutableStateFlow(UiState(versionName = versionName, versionCode = versionCode))
    val uiState: StateFlow<UiState> = _uiState

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events

    /** 安装授权前置（未授权时由页拉系统开关页，Dart 2026-10 修复版语义）。 */
    fun canRequestPackageInstalls(): Boolean = installer.canRequestPackageInstalls()

    fun openUnknownSourceSettings() = installer.openUnknownSourceSettings()

    fun releaseInstaller() = installer.release()

    init {
        viewModelScope.launch {
            val source = runCatching { service.sourceUrl() }.getOrNull()
            _uiState.value = _uiState.value.copy(
                ready = true,
                sourceUrl = source.orEmpty(),
                announcement = remoteConfig.current.announcement,
            )
        }
    }

    /** 保存更新源（trim/空移除回默认/非法拒——§A 逐字）。 */
    fun saveSource(url: String) = viewModelScope.launch {
        try {
            service.setSourceUrl(url)
            _events.tryEmit(UiEvent(message = "更新源已保存", durationMs = 3000))
        } catch (e: UpdateException) {
            _events.tryEmit(UiEvent(message = e.message, durationMs = 3000, error = true))
        }
    }

    /** 检查更新：成功即配置缓存落盘 + 新旧判词消息行（§A 逐字错误串）。 */
    fun check() = viewModelScope.launch {
        _uiState.value = _uiState.value.copy(phase = Phase.CHECKING, message = null, apkPath = null)
        try {
            val manifest = service.check()
            remoteConfig.save(manifest.config)
            val newer = manifest.isNewerThan(_uiState.value.versionCode)
            _uiState.value = _uiState.value.copy(
                phase = Phase.IDLE,
                manifest = manifest,
                announcement = manifest.config.announcement ?: _uiState.value.announcement,
                message = if (newer) {
                    "发现新版本 v${manifest.versionName}（${manifest.changelog ?: "无更新说明"}）"
                } else {
                    "已是最新版本（远程 v${manifest.versionName}）"
                },
            )
        } catch (e: UpdateException) {
            _uiState.value = _uiState.value.copy(phase = Phase.IDLE, message = e.message)
        }
    }

    /** 下载：流式 sha256 校验，进度回调直推状态（§A 逐字错误串）。 */
    fun download() = viewModelScope.launch {
        val manifest = _uiState.value.manifest ?: return@launch
        _uiState.value = _uiState.value.copy(phase = Phase.DOWNLOADING, received = 0, total = null, message = null)
        try {
            val apk = service.downloadApk(
                manifest.apkUrl,
                expectedSha256 = manifest.sha256,
                onProgress = { received, total ->
                    _uiState.value = _uiState.value.copy(received = received, total = total)
                },
            )
            _uiState.value = _uiState.value.copy(
                apkPath = apk.path,
                phase = Phase.READY_TO_INSTALL,
                message = "下载完成并已通过完整性校验，点击安装",
            )
        } catch (e: UpdateException) {
            _uiState.value = _uiState.value.copy(phase = Phase.IDLE, message = e.message)
        }
    }

    /** 安装：授权前置未过→拉系统开关页+提示（Dart 修复版语义）；异常 error 3s。 */
    fun install() = viewModelScope.launch {
        val apkPath = _uiState.value.apkPath ?: return@launch
        if (!installer.canRequestPackageInstalls()) {
            installer.openUnknownSourceSettings()
            _events.tryEmit(
                UiEvent(
                    message = "请在系统页允许「安装未知应用」后，回到本页再点安装",
                    durationMs = 3000,
                ),
            )
            return@launch
        }
        installer.install(
            apkPath,
            onResult = { /* pending_user_action/installed 均无页内反馈（Dart 同） */ },
            onError = { msg -> _events.tryEmit(UiEvent(message = "安装失败：$msg", durationMs = 3000, error = true)) },
        )
    }

    companion object {
        /** Manual DI Factory（AppContainer 纪律；版本号取自 PackageManager）。 */
        val factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as ShiguangApplication
                UpdateViewModel.of(app)
            }
        }

        fun of(app: ShiguangApplication): UpdateViewModel {
            val pm = app.packageManager
            val info = pm.getPackageInfo(app.packageName, 0)
            val container = app.container
            return UpdateViewModel(
                service = UpdateService.of(app),
                remoteConfig = container.remoteConfig,
                installer = ApkInstaller(app),
                versionName = info.versionName.orEmpty(),
                versionCode = info.longVersionCode,
            )
        }
    }
}
