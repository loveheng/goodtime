package app.shiguang.shiguang.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.shiguang.core.tokens.StScale
import app.shiguang.shiguang.ui.component.ShiguangScaffold
import app.shiguang.shiguang.ui.component.ShiguangSnackbarHost
import app.shiguang.shiguang.ui.component.ShiguangSnackbarDuration
import app.shiguang.shiguang.ui.component.showShiguangSnackbar

/**
 * 更新页（behavior/update_page.md §A 转正清单 D23 2026-11-15）：应用内自更新四相位
 * （检查/下载/校验/安装）+ 公告卡 + 更新源三卡；错误反馈走 error 底色 SnackBar 3s
 * （壳层状态提升容器色）。Dart Flutter 侧同名页镜像——双轨期生产仍走 Dart 页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatePage(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: UpdateViewModel = viewModel(factory = UpdateViewModel.factory)
    val ui by vm.uiState.collectAsState()
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }
    var errorToast by remember { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            errorToast = e.error
            snackbarHostState.showShiguangSnackbar(e.message, ShiguangSnackbarDuration.fromMs(e.durationMs))
        }
    }
    // 离开页面释放安装 receiver（ApkInstaller 生命周期契约）
    androidx.compose.runtime.DisposableEffect(vm) {
        onDispose { vm.releaseInstaller() }
    }

    ShiguangScaffold(
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        snackbarHost = {
            ShiguangSnackbarHost(
                snackbarHostState,
                containerColorOverride = if (errorToast) MaterialTheme.colorScheme.error else null,
            )
        },
        topBar = {
            TopAppBar(
                title = { Text("更新") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭")
                    }
                },
            )
        },
        content = { padding ->
            LazyColumn(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(StScale.insetLg.dp),
            ) {
                item {
                    UpdateCard(ui, onCheck = vm::check, onDownload = vm::download, onInstall = vm::install)
                    Spacer(Modifier.height(StScale.insetLg.dp))
                }
                if (ui.announcement != null) {
                    item {
                        Card(Modifier.fillMaxWidth()) {
                            ListItem(
                                headlineContent = { Text("公告") },
                                supportingContent = { Text(ui.announcement!!) },
                                leadingContent = { Icon(Icons.Outlined.Campaign, contentDescription = null) },
                            )
                        }
                        Spacer(Modifier.height(StScale.insetLg.dp))
                    }
                }
                item {
                    SourceCard(
                        sourceUrl = ui.sourceUrl,
                        ready = ui.ready,
                        onSave = vm::saveSource,
                    )
                }
            }
        },
    )
}

@Composable
private fun UpdateCard(
    ui: UpdateViewModel.UiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(StScale.insetLg.dp)) {
            Text("当前版本", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(StScale.insetXs.dp))
            Text("v${ui.versionName}（build ${ui.versionCode}）")
            Spacer(Modifier.height(StScale.insetMd.dp))
            Text("远程更新", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(StScale.insetXs.dp))
            ui.manifest?.let { m ->
                Text("远程版本 v${m.versionName}（build ${m.versionCode}）")
            }
            ui.message?.let { msg ->
                Spacer(Modifier.height(StScale.insetSm.dp))
                Text(msg, style = MaterialTheme.typography.bodySmall)
            }
            if (ui.phase == UpdateViewModel.Phase.DOWNLOADING) {
                Spacer(Modifier.height(StScale.insetMd.dp))
                val total = ui.total
                if (total != null && total > 0) {
                    LinearProgressIndicator(
                        progress = { ui.received.toFloat() / total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    // total 未知=不定态（Dart value:null 同形）
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(StScale.insetMd.dp))
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(StScale.insetSm.dp)) {
                Button(
                    onClick = onCheck,
                    enabled = ui.ready && ui.phase == UpdateViewModel.Phase.IDLE,
                ) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null)
                    Spacer(Modifier.width(StScale.insetXs.dp))
                    Text("检查更新")
                }
                val manifest = ui.manifest
                if (manifest != null && manifest.isNewerThan(ui.versionCode)) {
                    FilledTonalButton(
                        onClick = onDownload,
                        enabled = ui.phase == UpdateViewModel.Phase.IDLE ||
                            ui.phase == UpdateViewModel.Phase.CHECKING,
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null)
                        Spacer(Modifier.width(StScale.insetXs.dp))
                        Text(if (ui.phase == UpdateViewModel.Phase.DOWNLOADING) "下载中…" else "下载安装包")
                    }
                }
                if (ui.phase == UpdateViewModel.Phase.READY_TO_INSTALL) {
                    Button(onClick = onInstall) {
                        Icon(Icons.Outlined.SystemUpdateAlt, contentDescription = null)
                        Spacer(Modifier.width(StScale.insetXs.dp))
                        Text("安装")
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceCard(
    sourceUrl: String,
    ready: Boolean,
    onSave: (String) -> Unit,
) {
    var text by remember(sourceUrl) { mutableStateOf(sourceUrl) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(StScale.insetLg.dp)) {
            Text("更新源", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(StScale.insetXs.dp))
            Text(
                "默认更新源已内置（构建时注入），装上即可「检查更新」；" +
                    "如需更换为其他静态托管，可在此修改后点「保存更新源」。" +
                    "托管格式见 docs/guide/self-update.md。",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(StScale.insetSm.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("https://<host>/updates") },
            )
            Spacer(Modifier.height(StScale.insetSm.dp))
            OutlinedButton(onClick = { onSave(text) }, enabled = ready) {
                Icon(Icons.Outlined.Save, contentDescription = null)
                Spacer(Modifier.width(StScale.insetXs.dp))
                Text("保存更新源")
            }
        }
    }
}
