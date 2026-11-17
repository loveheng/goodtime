package app.shiguang.shiguang.ui.collab

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.shiguang.core.tokens.StScale
import app.shiguang.core.tokens.StUi
import app.shiguang.shiguang.ui.component.ShiguangBottomSheet
import app.shiguang.shiguang.ui.theme.LocalStColors
import kotlinx.coroutines.launch

/**
 * 双维拒绝面板（behavior/reject_panel.md 转正清单 §A–§B，T4.2；§11.2 D 页 §3.4
 * 减层版 2026-10-25 拍板）：默认一屏（主维度+子类+可选备注即可提交），隐私分级与
 * 建议转派收进【详细说明】展开态——必填正是制造敷衍的根源。
 *
 * 结构化结果经 suspend [onConfirm] 回调交调用方（member_calendar 落 outbox
 * REJECT/VOTE 分流并关浮层反馈）；reason/privacy=FSM 枚举名逐字
 * （TIME_CONFLICT/ROLE_UNWILLING、PUBLIC/INITIATOR_ONLY），禁翻译改写。
 */

/** 双维拒绝的结构化结果（FSM 枚举名逐字；Dart RejectResult 对位）。 */
data class RejectResult(
    /** 主维度：TIME_CONFLICT / ROLE_UNWILLING（FSM RejectReason 枚举名）。 */
    val reason: String,
    /** 子类芯片（四选一）：NOT_MY_JOB / PRIORITY / MISSING_PREREQ / PERSONAL。 */
    val subReason: String,
    /** 详细说明（选填，发起人会参考）；trim 空=不携带。 */
    val detail: String?,
    /** 隐私分级：PUBLIC（默认）/ INITIATOR_ONLY。 */
    val privacy: String,
    /** 可选建议转派的目标成员 uid（仅建议，由发起人后续处置）。 */
    val suggestReassign: String?,
)

/** 子类芯片四选一（§A 逐字：FSM 枚举名→上屏白话；键序=面板渲染序）。 */
val RejectSubReasons: LinkedHashMap<String, String> = linkedMapOf(
    "NOT_MY_JOB" to "非我职责",
    "PRIORITY" to "优先级不符",
    "MISSING_PREREQ" to "缺前置条件",
    "PERSONAL" to "个人原因",
)

/** 主维度枚举名（FSM RejectReason 逐字）。 */
object RejectDimension {
    const val TIME_CONFLICT = "TIME_CONFLICT"
    const val ROLE_UNWILLING = "ROLE_UNWILLING"
}

/** 隐私分级枚举名（FSM 载荷逐字）。 */
object RejectPrivacy {
    const val PUBLIC = "PUBLIC"
    const val INITIATOR_ONLY = "INITIATOR_ONLY"
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RejectPanel(
    stepId: String,
    participants: List<String>,
    currentUserId: String?,
    onConfirm: suspend (RejectResult) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStColors.current
    val scope = rememberCoroutineScope()
    // 草稿态 saveable（丢失点 #9：进程重建抽屉态不丢）
    var dimension by rememberSaveable { mutableStateOf<String?>(null) }
    var subReason by rememberSaveable { mutableStateOf<String?>(null) }
    var privacy by rememberSaveable { mutableStateOf(RejectPrivacy.PUBLIC) }
    var suggestReassign by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by rememberSaveable { mutableStateOf("") }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }

    val canSubmit = dimension != null && subReason != null

    ShiguangBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        Column(Modifier.padding(horizontal = StScale.insetLg.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("提出异议", fontSize = StUi.collabHeroSp.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(StScale.insetSm.dp))
                Text("步骤 $stepId", color = Color(palette.textSecondary))
            }
            Spacer(Modifier.height(StUi.collabFieldGapDp.dp))
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text("主要卡在哪一类？", color = Color(palette.textSecondary), fontSize = StUi.collabFootnoteSp.sp)
                Spacer(Modifier.height(StUi.collabRowGapDp.dp))
                DimensionOption(
                    icon = { Icon(Icons.Outlined.Schedule, contentDescription = null, tint = Color(palette.sparkStroke), modifier = Modifier.size(StUi.collabActionIconDp.dp)) },
                    title = "时间冲突",
                    subtitle = "这个时段我已有安排",
                    selected = dimension == RejectDimension.TIME_CONFLICT,
                    onClick = { dimension = RejectDimension.TIME_CONFLICT },
                )
                Spacer(Modifier.height(StUi.collabRowGapDp.dp))
                DimensionOption(
                    icon = { Icon(Icons.Outlined.Block, contentDescription = null, tint = Color(palette.sparkStroke), modifier = Modifier.size(StUi.collabActionIconDp.dp)) },
                    title = "权责异议",
                    subtitle = "这件事不该由我来做",
                    selected = dimension == RejectDimension.ROLE_UNWILLING,
                    onClick = { dimension = RejectDimension.ROLE_UNWILLING },
                )
                Spacer(Modifier.height(StUi.collabFieldGapDp.dp))
                Text("更具体的原因（四选一）", color = Color(palette.textSecondary), fontSize = StUi.collabFootnoteSp.sp)
                Spacer(Modifier.height(StUi.collabRowGapDp.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(StScale.insetSm.dp),
                    verticalArrangement = Arrangement.spacedBy(StScale.insetSm.dp),
                ) {
                    RejectSubReasons.forEach { (key, label) ->
                        FilterChip(
                            selected = subReason == key,
                            onClick = { subReason = key },
                            label = { Text(label) },
                        )
                    }
                }
                Spacer(Modifier.height(StUi.collabFieldGapDp.dp))
                OutlinedTextField(
                    value = detail,
                    onValueChange = { detail = it },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2,
                    placeholder = { Text("补充说明（选填，发起人会参考）") },
                )
                Spacer(Modifier.height(StScale.insetSm.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { showAdvanced = !showAdvanced }
                        .padding(vertical = StScale.insetSm.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (showAdvanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(StUi.collabMenuIconDp.dp),
                        tint = Color(palette.textSecondary),
                    )
                    Spacer(Modifier.width(StScale.insetXs.dp))
                    Text("详细说明与隐私（选填）", color = Color(palette.textSecondary), fontSize = StUi.collabFootnoteSp.sp)
                }
                if (showAdvanced) {
                    Text("谁可以看到你的理由？", color = Color(palette.textSecondary), fontSize = StUi.collabFootnoteSp.sp)
                    Spacer(Modifier.height(StUi.collabRowGapDp.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = privacy == RejectPrivacy.PUBLIC,
                            onClick = { privacy = RejectPrivacy.PUBLIC },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text("全员可见") }
                        SegmentedButton(
                            selected = privacy == RejectPrivacy.INITIATOR_ONLY,
                            onClick = { privacy = RejectPrivacy.INITIATOR_ONLY },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text("仅发起人可见") }
                    }
                    Spacer(Modifier.height(StUi.collabRowGapDp.dp))
                    Text(
                        if (privacy == RejectPrivacy.INITIATOR_ONLY) {
                            "理由经发起人公钥单向加密，其他成员端只看到骨架提示，永远解不开。"
                        } else {
                            "理由用协同包密钥加密，所有参与成员的时间轴都能看到。"
                        },
                        color = Color(palette.textSecondary),
                        fontSize = StUi.collabHintSp.sp,
                    )
                    Spacer(Modifier.height(StUi.collabFieldGapDp.dp))
                    Text("建议转给谁？（选填）", color = Color(palette.textSecondary), fontSize = StUi.collabFootnoteSp.sp)
                    Spacer(Modifier.height(StUi.collabRowGapDp.dp))
                    val candidates = participants.filter { it != currentUserId }
                    if (candidates.isEmpty()) {
                        Text("没有可转派的成员", color = Color(palette.textSecondary))
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(StScale.insetSm.dp),
                            verticalArrangement = Arrangement.spacedBy(StScale.insetSm.dp),
                        ) {
                            candidates.forEach { uid ->
                                FilterChip(
                                    selected = suggestReassign == uid,
                                    onClick = { suggestReassign = if (suggestReassign == uid) null else uid },
                                    label = { Text(uid) },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(StUi.collabRowGapDp.dp))
                    Text("这只是一个建议，由发起人决定是否转派。", color = Color(palette.textSecondary), fontSize = StUi.collabHintSp.sp)
                    Spacer(Modifier.height(StScale.insetXs.dp))
                }
            }
            Spacer(Modifier.height(StUi.collabFieldGapDp.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = {
                        val dim = dimension ?: return@Button
                        val sub = subReason ?: return@Button
                        scope.launch {
                            submitting = true
                            onConfirm(
                                RejectResult(
                                    reason = dim,
                                    subReason = sub,
                                    detail = detail.trim().ifEmpty { null },
                                    privacy = privacy,
                                    suggestReassign = suggestReassign,
                                ),
                            )
                            submitting = false
                        }
                    },
                    enabled = canSubmit && !submitting,
                ) {
                    if (submitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(StUi.collabSpinnerDp.dp),
                            strokeWidth = StUi.collabSpinnerStrokeDp.dp,
                        )
                    } else {
                        Text("提交异议")
                    }
                }
            }
        }
    }
}

/** 主维度大卡（§B：选中=sparkStroke 1.5 边+sparkBg 底，未选=灰 1 边）。 */
@Composable
private fun DimensionOption(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalStColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .border(
                BorderStroke(
                    if (selected) StUi.collabSelectedBorderDp.dp else StUi.collabIdleBorderDp.dp,
                    if (selected) Color(palette.sparkStroke) else Color(palette.hairlineGrey),
                ),
                RoundedCornerShape(StUi.collabCardRadiusDp.dp),
            )
            .background(if (selected) Color(palette.sparkBg) else Color.Transparent)
            .padding(horizontal = StUi.collabDimPadDp.dp, vertical = StUi.collabDimPadDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(StScale.insetMd.dp))
        Column {
            Text(title, fontSize = StUi.collabSectionSp.sp)
            Text(subtitle, color = Color(palette.textSecondary), fontSize = StUi.collabHintSp.sp)
        }
    }
}
