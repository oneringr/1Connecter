package top.onering.easyconnecter.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.onering.easyconnecter.MainUiState
import top.onering.easyconnecter.model.Campus
import top.onering.easyconnecter.ui.theme.LocalConnectorPalette
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    state: MainUiState,
    onImportCsv: () -> Unit,
    onComplete: (Campus, Boolean) -> Unit,
    onUserInteraction: () -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(if (state.accounts.isEmpty()) 0 else 1) }
    var draftCampus by rememberSaveable { mutableStateOf(state.campus) }
    var draftAutoClose by rememberSaveable { mutableStateOf(state.autoCloseEnabled) }
    val importScroll = rememberScrollState()
    val settingsScroll = rememberScrollState()
    val palette = LocalConnectorPalette.current
    val hasAccounts = state.accounts.isNotEmpty()

    // Never leave an invalid restored step able to complete without an imported account.
    LaunchedEffect(hasAccounts) {
        if (!hasAccounts) step = 0
    }
    BackHandler(enabled = step == 1 || state.isBusy) {
        if (!state.isBusy) {
            onUserInteraction()
            step = 0
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(palette.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .observeUserTouches(onUserInteraction),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "1Connecter · 医连接",
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            color = palette.muted,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
        AnimatedContent(
            targetState = step,
            modifier = Modifier.weight(1f).widthIn(max = 680.dp).fillMaxWidth(),
            contentAlignment = Alignment.TopCenter,
            transitionSpec = {
                val direction = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { direction * it / 8 } +
                    fadeIn(tween(160))) togetherWith
                    (slideOutHorizontally(tween(180, easing = FastOutSlowInEasing)) { -direction * it / 8 } +
                        fadeOut(tween(120)))
            },
            label = "onboarding_step",
        ) { currentStep ->
            Column(
                modifier = Modifier.fillMaxSize()
                    .verticalScroll(if (currentStep == 0) importScroll else settingsScroll)
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .testTag(if (currentStep == 0) "onboarding_import_content" else "onboarding_settings_content"),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (currentStep == 0) "导入账号配置" else "完成连接设置",
                        color = palette.ink,
                        fontSize = 30.sp,
                        lineHeight = 38.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() },
                    )
                    SupportingText(
                        if (currentStep == 0) "先准备账号，再选择所在校区。完成设置前不会开始连接。"
                        else "确认所在校区与连接成功后的行为，然后开始使用。",
                    )
                }
                if (currentStep == 0) {
                    Panel {
                        Text("CSV 账号配置", color = palette.ink, fontWeight = FontWeight.Medium)
                        SupportingText("文件需要“学号”和“身份证号”表头，也可包含姓名、班级、联系电话。")
                        if (hasAccounts) {
                            Text(
                                "已导入 ${state.accounts.size} 个账号",
                                color = palette.success,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                            SupportingText("账号配置已就绪，可以继续下一步。")
                        } else {
                            SupportingText("成功导入至少一个有效账号后，才能继续下一步。")
                        }
                        Button(
                            onClick = { onUserInteraction(); onImportCsv() },
                            enabled = !state.isBusy,
                            modifier = Modifier.fillMaxWidth().testTag("onboarding_import"),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                        ) {
                            Text(if (hasAccounts) "重新导入配置" else "导入配置 (CSV)")
                        }
                        if (state.isBusy) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                InfiniteProgressIndicator(size = 20.dp, color = palette.accentText)
                                SupportingText("正在处理账号配置…")
                            }
                        }
                    }
                } else {
                    Panel {
                        Text("所在校区", color = palette.ink, fontWeight = FontWeight.Medium)
                        SupportingText("选择你当前使用校园网的校区。")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Campus.entries.forEach { campus ->
                                Button(
                                    onClick = { onUserInteraction(); draftCampus = campus },
                                    enabled = !state.isBusy,
                                    modifier = Modifier.testTag("onboarding_campus_${campus.name.lowercase()}")
                                        .semantics { selected = draftCampus == campus },
                                    colors = if (draftCampus == campus) {
                                        ButtonDefaults.buttonColorsPrimary()
                                    } else {
                                        ButtonDefaults.buttonColors()
                                    },
                                ) { Text("${campus.displayName}校区") }
                            }
                        }
                    }
                    Panel {
                        ToggleRow(
                            title = "连接成功后自动关闭",
                            summary = "普通模式下，新连接成功 3 秒后关闭应用界面。",
                            checked = draftAutoClose,
                            enabled = !state.isBusy,
                            tag = "onboarding_auto_close",
                            onCheckedChange = { onUserInteraction(); draftAutoClose = it },
                        )
                        SupportingText("点击或滚动界面会取消本次自动关闭。之后可在“设置”中修改，服务模式不受此开关影响。")
                    }
                    SupportingText("已准备 ${state.accounts.size} 个账号。点击“开始使用”后启动连接。")
                }
            }
        }
        // The footer has its own measured height; scrollable content never sits underneath it.
        Column(
            Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                Modifier.semantics { contentDescription = "第 ${step + 1} 步，共 2 步" },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(2) { index ->
                    Box(Modifier.size(8.dp).background(if (step == index) palette.ink else palette.divider, CircleShape))
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (step == 1) {
                    Button(
                        onClick = { onUserInteraction(); step = 0 },
                        enabled = !state.isBusy,
                        modifier = Modifier.weight(1f).testTag("onboarding_previous"),
                    ) { Text("上一步") }
                }
                Button(
                    onClick = {
                        onUserInteraction()
                        if (hasAccounts && !state.isBusy) {
                            if (step == 0) step = 1 else onComplete(draftCampus, draftAutoClose)
                        }
                    },
                    enabled = hasAccounts && !state.isBusy,
                    modifier = Modifier.weight(1f).testTag(if (step == 0) "onboarding_next" else "onboarding_finish"),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) { Text(if (step == 0) "下一步" else if (state.isBusy) "正在准备…" else "开始使用") }
            }
        }
    }
}
