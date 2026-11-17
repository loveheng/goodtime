package app.shiguang.shiguang.ui.collab

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PeopleOutline
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import app.shiguang.core.model.collab.relay.FriendView
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.component.ShiguangScaffold
import app.shiguang.shiguang.ui.component.ShiguangSnackbarHost
import app.shiguang.shiguang.ui.component.showShiguangSnackbar
import app.shiguang.shiguang.ui.theme.LocalStColors

/**
 * 好友域主页（behavior/friends.md 转正清单 §B，T4.1）：我的名片 / 扫一扫建联 /
 * 搜用户名加好友 / 名单三分区（待确认/已互加/已拉黑）/ 待确认处置 / 安全码核对入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsPage(
    onOpenMyQr: () -> Unit,
    onOpenScan: () -> Unit,
    onVerify: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: FriendsViewModel = viewModel(factory = FriendsViewModel.factory)
    val s by vm.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.events.collect { msg -> snackbarHostState.showShiguangSnackbar(msg) }
    }
    LaunchedEffect(Unit) { vm.init() }
    // 扫码返回刷新（Dart _openScan「扫完回来刷新名单」镜像）：ON_PAUSE→去扫码标记，
    // ON_RESUME 且标记在 → 重拉（首页次 resume 不触发）
    var wentScanning by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) wentScanning = true
            if (event == Lifecycle.Event.ON_RESUME && wentScanning) {
                wentScanning = false
                vm.reload()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ShiguangScaffold(
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        snackbarHost = { ShiguangSnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text("好友") }) },
        floatingActionButton = {
            if (s.sessionReady == true) {
                ExtendedFloatingActionButton(
                    onClick = onOpenScan,
                    icon = { Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(StUi.collabActionIconDp.dp)) },
                    text = { Text("扫一扫建联") },
                )
            }
        },
    ) { padding ->
        when (s.sessionReady) {
            null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            false -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = StScale.insetLg.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    FriendsViewModel.SessionGateError,
                    color = Color(LocalStColors.current.textSecondary),
                    textAlign = TextAlign.Center,
                )
            }
            else -> FriendsListBody(
                padding = padding,
                state = s,
                onMyQr = onOpenMyQr,
                onScan = onOpenScan,
                onSearch = { vm.searchAndRequest(it) },
                onRespond = { peer, accept -> vm.respond(peer, accept) },
                onVerify = onVerify,
                onManage = { friend, action -> vm.manage(friend, action) },
            )
        }
    }
}

/** 名单面（§B：动作行/搜索行/三分区/空态）。 */
@Composable
private fun FriendsListBody(
    padding: PaddingValues,
    state: FriendsViewModel.FriendsUiState,
    onMyQr: () -> Unit,
    onScan: () -> Unit,
    onSearch: (String) -> Unit,
    onRespond: (String, Boolean) -> Unit,
    onVerify: (String) -> Unit,
    onManage: (FriendView, FriendsViewModel.FriendAction) -> Unit,
) {
    val palette = LocalStColors.current
    var searchText by rememberSaveable { mutableStateOf("") }
    val pending = state.friends.filter { it.status == "pending" }
    val accepted = state.friends.filter { it.status == "accepted" }
    val blocked = state.friends.filter { it.status == "blocked" }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(start = StScale.insetLg.dp, top = StScale.insetMd.dp, end = StScale.insetLg.dp, bottom = StUi.collabListTailDp.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onMyQr, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.QrCode2, contentDescription = null, modifier = Modifier.size(StUi.collabActionIconDp.dp))
                    Spacer(Modifier.width(StScale.insetXs.dp))
                    Text("我的名片")
                }
                Spacer(Modifier.width(StScale.insetMd.dp))
                Button(onClick = onScan, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(StUi.collabActionIconDp.dp))
                    Spacer(Modifier.width(StScale.insetXs.dp))
                    Text("扫一扫")
                }
            }
            Spacer(Modifier.height(StScale.insetMd.dp))
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier.weight(1f),
                    enabled = !state.searching,
                    singleLine = true,
                    placeholder = { Text("输入对方用户名") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(StUi.collabMenuIconDp.dp)) },
                )
                Spacer(Modifier.width(StScale.insetMd.dp))
                FilledTonalButton(onClick = { onSearch(searchText); searchText = "" }, enabled = !state.searching) {
                    if (state.searching) {
                        CircularProgressIndicator(modifier = Modifier.size(StUi.collabSpinnerDp.dp), strokeWidth = StUi.collabSpinnerStrokeDp.dp)
                    } else {
                        Text("加好友")
                    }
                }
            }
        }
        if (state.error != null) {
            item {
                Spacer(Modifier.height(StScale.insetMd.dp))
                Text(state.error!!, fontSize = StUi.collabFootnoteSp.sp, color = Color(palette.textSecondary))
            }
        }
        if (pending.isNotEmpty()) {
            item {
                Spacer(Modifier.height(StScale.insetLg.dp))
                SectionLabel("待确认", "对方确认后才算互加好友")
                Spacer(Modifier.height(StScale.insetSm.dp))
            }
            items(pending.size) { i -> PendingTile(pending[i], onRespond) }
        }
        if (accepted.isNotEmpty()) {
            item {
                Spacer(Modifier.height(StScale.insetLg.dp))
                SectionLabel("已互加", null)
                Spacer(Modifier.height(StScale.insetSm.dp))
            }
            items(accepted.size) { i -> FriendTile(accepted[i], onVerify, onManage) }
        }
        if (blocked.isNotEmpty()) {
            item {
                Spacer(Modifier.height(StScale.insetLg.dp))
                SectionLabel("已拉黑", "无法与其协同")
                Spacer(Modifier.height(StScale.insetSm.dp))
            }
            items(blocked.size) { i -> FriendTile(blocked[i], null, onManage) }
        }
        if (state.friends.isEmpty() && state.error == null) {
            item {
                Spacer(Modifier.height(StUi.collabListTailDp.dp))
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = StScale.insetLg.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Outlined.PeopleOutline, contentDescription = null, modifier = Modifier.size(StUi.collabHeroIconDp.dp), tint = Color(palette.textSecondary))
                    Spacer(Modifier.height(StScale.insetLg.dp))
                    Text("还没有好友", fontSize = StUi.collabHeroSp.sp, fontWeight = FontWeight.SemiBold, color = Color(palette.textPrimary))
                    Spacer(Modifier.height(StScale.insetSm.dp))
                    Text(
                        "扫一扫出示名片，或让对方扫你的名片加一位",
                        color = Color(palette.textSecondary),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** 分区标题（§B：14sp w600 + 可选 hint 12sp textSecondary）。 */
@Composable
private fun SectionLabel(title: String, hint: String?) {
    val palette = LocalStColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = StUi.collabSectionSp.sp, fontWeight = FontWeight.SemiBold)
        if (hint != null) {
            Spacer(Modifier.width(StScale.insetSm.dp))
            Text(hint, fontSize = StUi.collabHintSp.sp, color = Color(palette.textSecondary))
        }
    }
}

/** 待确认行（§B：忽略 TextButton / 通过 FilledButton，零二次确认）。 */
@Composable
private fun PendingTile(friend: FriendView, onRespond: (String, Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = StScale.insetSm.dp)) {
        Row(
            Modifier.padding(horizontal = StScale.insetMd.dp, vertical = StScale.insetSm.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Person, contentDescription = null)
            Spacer(Modifier.width(StScale.insetMd.dp))
            Text(friend.userId, modifier = Modifier.weight(1f))
            TextButton(onClick = { onRespond(friend.userId, false) }) { Text("忽略") }
            Spacer(Modifier.width(StScale.insetXs.dp))
            Button(onClick = { onRespond(friend.userId, true) }) { Text("通过") }
        }
    }
}

/** 好友行（§B：核对安全码 + 管理菜单；拉黑态=解除拉黑；破坏性操作二次确认逐字文案）。 */
@Composable
private fun FriendTile(
    friend: FriendView,
    onVerify: ((String) -> Unit)?,
    onManage: (FriendView, FriendsViewModel.FriendAction) -> Unit,
) {
    val blocked = friend.status == "blocked"
    var menuOpen by remember { mutableStateOf(false) }
    var confirmAction by remember { mutableStateOf<FriendsViewModel.FriendAction?>(null) }
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = StScale.insetSm.dp)) {
        ListItem(
            headlineContent = { Text(friend.userId) },
            leadingContent = { Icon(Icons.Outlined.Person, contentDescription = null) },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!blocked && onVerify != null) {
                        TextButton(onClick = { onVerify(friend.userId) }) { Text("核对安全码") }
                    }
                    if (blocked) {
                        TextButton(onClick = { confirmAction = FriendsViewModel.FriendAction.UNBLOCK }) { Text("解除拉黑") }
                    } else {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = null, modifier = Modifier.size(StUi.collabMenuIconDp.dp))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("解除好友") },
                                    onClick = { menuOpen = false; confirmAction = FriendsViewModel.FriendAction.REMOVE },
                                )
                                DropdownMenuItem(
                                    text = { Text("拉黑") },
                                    onClick = { menuOpen = false; confirmAction = FriendsViewModel.FriendAction.BLOCK },
                                )
                            }
                        }
                    }
                }
            },
        )
    }
    confirmAction?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = {
                Text(
                    when (action) {
                        FriendsViewModel.FriendAction.REMOVE -> "解除好友"
                        FriendsViewModel.FriendAction.BLOCK -> "拉黑"
                        FriendsViewModel.FriendAction.UNBLOCK -> "解除拉黑"
                    },
                )
            },
            text = {
                Text(
                    when (action) {
                        FriendsViewModel.FriendAction.REMOVE -> "解除后你们回到陌生状态，之后可以重新加"
                        FriendsViewModel.FriendAction.BLOCK -> "拉黑后对方无法再向你发起任何申请"
                        FriendsViewModel.FriendAction.UNBLOCK -> "解除后对方可以重新申请加你"
                    },
                )
            },
            confirmButton = {
                Button(onClick = { confirmAction = null; onManage(friend, action) }) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) { Text("取消") }
            },
        )
    }
}
