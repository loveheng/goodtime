package app.shiguang.shiguang.ui.collab

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.shiguang.core.model.collab.AccountStatus
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.component.ShiguangScaffold
import app.shiguang.shiguang.ui.component.ShiguangSnackbarHost
import app.shiguang.shiguang.ui.component.showShiguangSnackbar
import app.shiguang.shiguang.ui.theme.LocalStColors

/**
 * 协同账号页（behavior/account.md 转正清单 §B，T4.8；账号体系 Plan A）：登录/
 * 注册/改密/退出——用户只见「用户名+密码」，E2EE 密钥端侧自动托管零密钥概念；
 * 三态门禁（unknown=转圈/loggedIn=卡片/loggedOut=表单）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountPage(modifier: Modifier = Modifier) {
    val vm: AccountViewModel = viewModel(factory = AccountViewModel.factory)
    val form by vm.form.collectAsState()
    val status by vm.session.status.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(vm) {
        vm.events.collect { msg -> snackbarHostState.showShiguangSnackbar(msg) }
    }
    // 冷启动恢复（§C F2：LaunchedEffect 惯用法承载 restore——禁构造/init 直调）
    LaunchedEffect(Unit) { vm.session.ensureRestored() }
    var changePwOpen by rememberSaveable { mutableStateOf(false) }
    var logoutOpen by rememberSaveable { mutableStateOf(false) }

    ShiguangScaffold(
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        snackbarHost = { ShiguangSnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text("协同账号") }) },
    ) { padding ->
        when (status) {
            AccountStatus.UNKNOWN -> {
                Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
            AccountStatus.LOGGED_IN -> LoggedInBody(padding, vm.session.username, onChangePw = { changePwOpen = true }, onLogout = { logoutOpen = true })
            AccountStatus.LOGGED_OUT -> AuthFormBody(
                padding = padding,
                form = form,
                onRegister = vm::setRegister,
                onSubmit = vm::submit,
            )
        }
        if (changePwOpen) {
            ChangePasswordDialog(
                onDismiss = { changePwOpen = false },
                onConfirm = { old, new ->
                    changePwOpen = false
                    vm.changePassword(old, new)
                },
            )
        }
        if (logoutOpen) {
            AlertDialog(
                onDismissRequest = { logoutOpen = false },
                title = { Text("退出登录") },
                text = { Text("只退出账号，本地日程与协同数据都保留") },
                confirmButton = { Button(onClick = { logoutOpen = false; vm.logout() }) { Text("退出") } },
                dismissButton = { TextButton(onClick = { logoutOpen = false }) { Text("取消") } },
            )
        }
    }
}

/** 已登录卡片视图（§B：头像行+双钮+尾注）。 */
@Composable
private fun LoggedInBody(
    padding: androidx.compose.foundation.layout.PaddingValues,
    username: String?,
    onChangePw: () -> Unit,
    onLogout: () -> Unit,
) {
    val palette = LocalStColors.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(StScale.insetLg.dp),
    ) {
        Card {
            Row(Modifier.fillMaxWidth().padding(StScale.insetLg.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AccountCircle, contentDescription = null, modifier = Modifier.size(StUi.collabAvatarIconDp.dp))
                Spacer(Modifier.width(StScale.insetMd.dp))
                Column {
                    Text("已登录：${username ?: ""}", fontSize = StUi.collabCardTitleSp.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(StScale.insetXs.dp))
                    Text("协同日程与好友数据走这个账号", fontSize = StUi.collabHintSp.sp, color = Color(palette.textSecondary))
                }
            }
        }
        Spacer(Modifier.height(StScale.insetMd.dp))
        OutlinedButton(onClick = onChangePw, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Password, contentDescription = null, modifier = Modifier.size(StUi.collabActionIconDp.dp))
            Spacer(Modifier.width(StScale.insetSm.dp))
            Text("修改密码")
        }
        Spacer(Modifier.height(StScale.insetSm.dp))
        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Logout, contentDescription = null, modifier = Modifier.size(StUi.collabActionIconDp.dp))
            Spacer(Modifier.width(StScale.insetSm.dp))
            Text("退出登录")
        }
        Spacer(Modifier.height(StScale.insetLg.dp))
        Text(
            "退出登录只清除本机的登录会话，日程与协同数据都保留在本机；修改密码不影响协同密钥与历史消息。",
            fontSize = StUi.collabHintSp.sp,
            color = Color(palette.textSecondary),
        )
    }
}

/** 登录/注册表单（§B：Segmented+三字段+提交+尾注按注册态二选一）。 */
@Composable
private fun AuthFormBody(
    padding: androidx.compose.foundation.layout.PaddingValues,
    form: AccountViewModel.AccountFormState,
    onRegister: (Boolean) -> Unit,
    onSubmit: (String, String, String) -> Unit,
) {
    val palette = LocalStColors.current
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(StScale.insetLg.dp),
    ) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !form.register,
                onClick = { onRegister(false) },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
            ) { Text("登录") }
            SegmentedButton(
                selected = form.register,
                onClick = { onRegister(true) },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
            ) { Text("注册") }
        }
        Spacer(Modifier.height(StScale.insetLg.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !form.busy,
            singleLine = true,
            label = { Text("用户名") },
            placeholder = { Text("小写字母、数字或下划线（3~32 位）") },
        )
        Spacer(Modifier.height(StScale.insetMd.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = !form.busy,
            singleLine = true,
            label = { Text("密码") },
            placeholder = { Text("至少 8 位") },
            isError = form.error != null,
            supportingText = { if (form.error != null) Text(form.error!!) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        if (form.register) {
            Spacer(Modifier.height(StScale.insetMd.dp))
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !form.busy,
                singleLine = true,
                label = { Text("再输一次密码") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        }
        Spacer(Modifier.height(StScale.insetLg.dp))
        Button(onClick = { onSubmit(username, password, confirm) }, enabled = !form.busy, modifier = Modifier.fillMaxWidth()) {
            if (form.busy) {
                CircularProgressIndicator(modifier = Modifier.size(StUi.collabBusySpinnerDp.dp), strokeWidth = StUi.collabSpinnerStrokeDp.dp)
            } else {
                Text(if (form.register) "注册并登录" else "登录")
            }
        }
        Spacer(Modifier.height(StScale.insetLg.dp))
        Text(
            if (form.register) {
                "注册即创建协同账号：用户名是好友找到你的入口；协同密钥在本机自动生成，密码只用来登录。"
            } else {
                "登录后即可与好友协同日程；协同密钥在本机自动管理，无需记忆。"
            },
            fontSize = StUi.collabHintSp.sp,
            color = Color(palette.textSecondary),
        )
    }
}

/** 改密对话框（§B：双 obscure 字段+取消/确认修改）。 */
@Composable
private fun ChangePasswordDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var old by rememberSaveable { mutableStateOf("") }
    var new by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改密码") },
        text = {
            Column {
                TextField(
                    value = old,
                    onValueChange = { old = it },
                    singleLine = true,
                    label = { Text("当前密码") },
                    visualTransformation = PasswordVisualTransformation(),
                )
                Spacer(Modifier.height(StScale.insetSm.dp))
                TextField(
                    value = new,
                    onValueChange = { new = it },
                    singleLine = true,
                    label = { Text("新密码（至少 8 位）") },
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = { Button(onClick = { onConfirm(old, new) }) { Text("确认修改") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
