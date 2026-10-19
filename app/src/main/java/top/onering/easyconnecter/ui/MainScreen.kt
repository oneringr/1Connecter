package top.onering.easyconnecter.ui

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import top.onering.easyconnecter.MainUiState
import top.onering.easyconnecter.data.ConnectionPhase
import top.onering.easyconnecter.model.Campus
import top.onering.easyconnecter.model.ThemeMode
import top.onering.easyconnecter.ui.theme.LocalConnectorPalette
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import java.util.concurrent.TimeUnit

private enum class MainPage(val title: String, val tag: String) {
    HOME("首页", "home"), ACCOUNTS("账号", "accounts"), LOGS("日志", "logs"), SETTINGS("设置", "settings"),
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun MainScreen(
    state: MainUiState,
    onSelectCampus: (Campus) -> Unit,
    onImportCsv: () -> Unit,
    onReimportCsv: () -> Unit,
    onExportCsv: () -> Unit,
    onToggleKeepAlive: (Boolean) -> Unit,
    onToggleAutoScroll: (Boolean) -> Unit,
    onShowAccounts: () -> Unit,
    onDismissAccounts: () -> Unit,
    onDeleteAccount: (Int) -> Unit,
    onShowCampusDialog: () -> Unit,
    onDismissCampusDialog: () -> Unit,
    onConfirmCampusChange: () -> Unit,
    onShowAbout: () -> Unit,
    onDismissAbout: () -> Unit,
    onShowLogout: () -> Unit,
    onDismissLogout: () -> Unit,
    onConfirmLogout: () -> Unit,
    onRestartAfterLogout: () -> Unit,
    onDismissRestartAfterLogout: () -> Unit,
    onExportLogs: () -> Unit,
    onSetAutoClose: (Boolean) -> Unit,
    onCompleteOnboarding: (Campus, Boolean) -> Unit,
    onOpenProject: () -> Unit,
    onCheckForUpdate: () -> Unit,
    onOpenRelease: (String) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onUserInteraction: () -> Unit,
) {
    if (state.showOnboarding) {
        OnboardingScreen(state, onImportCsv, onCompleteOnboarding, onUserInteraction)
        return
    }
    var selectedPage by rememberSaveable { mutableIntStateOf(MainPage.HOME.ordinal) }
    var accountsExpanded by rememberSaveable { mutableStateOf(false) }
    var accountQuery by rememberSaveable { mutableStateOf("") }
    val page = MainPage.entries.getOrElse(selectedPage) { MainPage.HOME }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // Hoisted outside the selected-page branch, so switching tabs preserves each viewport.
    val homeList = rememberLazyListState()
    val accountsList = rememberLazyListState()
    val logsList = rememberLazyListState()
    val settingsList = rememberLazyListState()
    val appBarStates = listOf(
        rememberTopAppBarState(), rememberTopAppBarState(),
        rememberTopAppBarState(), rememberTopAppBarState(),
    )
    val scrollBehaviors = appBarStates.map { MiuixScrollBehavior(it) }
    val scrollBehavior = scrollBehaviors[page.ordinal]
    val palette = LocalConnectorPalette.current
    // Reserve the visible navigation label at the current accessibility font scale.
    val navigationItemHeight = 42.dp + with(LocalDensity.current) { 24.sp.toDp() }
    fun interacted(action: () -> Unit): () -> Unit = { onUserInteraction(); action() }
    fun clearSearchFocus() {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }
    fun navigate(target: MainPage) {
        onUserInteraction()
        clearSearchFocus()
        selectedPage = target.ordinal
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding().observeUserTouches(onUserInteraction),
        containerColor = palette.background,
        topBar = {
            TopAppBar(
                title = if (page == MainPage.HOME) "1Connecter" else page.title,
                subtitle = if (page == MainPage.HOME) "医连接 · SWMU 校园网" else "",
                color = palette.background,
                scrollBehavior = scrollBehavior,
                actions = {
                    if (state.isBusy) {
                        Row(
                            Modifier.padding(end = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            InfiniteProgressIndicator(size = 20.dp, color = palette.accentText)
                            SupportingText("处理中")
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                color = palette.background,
                showDivider = true,
            ) {
                MainPage.entries.forEach { destination ->
                    val icon = when (destination) {
                        MainPage.HOME -> NavigationIcons.Home
                        MainPage.ACCOUNTS -> NavigationIcons.Accounts
                        MainPage.LOGS -> NavigationIcons.Logs
                        MainPage.SETTINGS -> NavigationIcons.Settings
                    }
                    NavigationBarItem(
                        selected = page == destination,
                        onClick = { navigate(destination) },
                        icon = icon,
                        label = destination.title,
                        modifier = Modifier.heightIn(min = navigationItemHeight).testTag("nav_${destination.tag}"),
                    )
                }
            }
        },
    ) { insets ->
        val direction = LocalLayoutDirection.current
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            AnimatedContent(
                targetState = page,
                modifier = Modifier.fillMaxSize().clipToBounds(),
                contentAlignment = Alignment.TopCenter,
                transitionSpec = {
                    val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    (slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { direction * it / 10 } +
                        fadeIn(tween(160))) togetherWith
                        (slideOutHorizontally(tween(180, easing = FastOutSlowInEasing)) { -direction * it / 10 } +
                            fadeOut(tween(120)))
                },
                label = "bottomNavigationPage",
            ) { visiblePage ->
                val contentPadding = PaddingValues(
                    start = 12.dp + insets.calculateLeftPadding(direction),
                    end = 12.dp + insets.calculateRightPadding(direction),
                    top = insets.calculateTopPadding() + 12.dp,
                    bottom = 12.dp + if (visiblePage == MainPage.LOGS) insets.calculateBottomPadding() else 0.dp,
                )
                val pageModifier = Modifier.widthIn(max = 760.dp).fillMaxSize()
                    .nestedScroll(scrollBehaviors[visiblePage.ordinal].nestedScrollConnection)
                    // Keep scroll-to-item and accessibility targets above the overlaid bottom bar.
                    .then(if (visiblePage != MainPage.LOGS) {
                        Modifier.padding(bottom = insets.calculateBottomPadding()).testTag("${visiblePage.tag}_list")
                    } else Modifier)
                when (visiblePage) {
                    MainPage.HOME -> HomePage(
                        state, homeList, pageModifier, contentPadding,
                        onSelectCampus = { onUserInteraction(); onSelectCampus(it) },
                        onImportCsv = interacted(onImportCsv),
                        onShowCampusDialog = interacted(onShowCampusDialog),
                        onAccounts = { navigate(MainPage.ACCOUNTS) },
                        onLogs = { navigate(MainPage.LOGS) },
                    )
                    MainPage.ACCOUNTS -> AccountsPage(
                        state, accountsList, pageModifier, contentPadding,
                        onImportCsv = interacted(onImportCsv),
                        onReimportCsv = interacted(onReimportCsv),
                        onExportCsv = interacted(onExportCsv),
                        onDeleteAccount = { onUserInteraction(); onDeleteAccount(it) },
                        accountsExpanded = accountsExpanded,
                        onAccountsExpandedChange = {
                            onUserInteraction()
                            if (!it) clearSearchFocus()
                            accountsExpanded = it
                        },
                        accountQuery = accountQuery,
                        onAccountQueryChange = { onUserInteraction(); accountQuery = it },
                    )
                    MainPage.LOGS -> LogsPage(
                        state, logsList, pageModifier, contentPadding,
                        onToggleAutoScroll = { onUserInteraction(); onToggleAutoScroll(it) },
                        onExportLogs = interacted(onExportLogs),
                        onUserInteraction = onUserInteraction,
                    )
                    MainPage.SETTINGS -> SettingsPage(
                        state, settingsList, pageModifier, contentPadding,
                        onToggleKeepAlive = { onUserInteraction(); onToggleKeepAlive(it) },
                        onSetAutoClose = { onUserInteraction(); onSetAutoClose(it) },
                        onSetThemeMode = { onUserInteraction(); onSetThemeMode(it) },
                        onShowCampusDialog = interacted(onShowCampusDialog),
                        onShowLogout = interacted(onShowLogout),
                        onShowAbout = interacted(onShowAbout),
                    )
                }
            }
            if (state.showFireworks) {
                val party = remember {
                    Party(
                        speed = 0f, maxSpeed = 22f, damping = 0.9f, spread = 360,
                        colors = listOf(0x3482ff, 0x78b4ff, 0x8dd9c0, 0xf2d282),
                        emitter = Emitter(100, TimeUnit.MILLISECONDS).max(60),
                        position = Position.Relative(0.5, 0.25),
                    )
                }
                KonfettiView(Modifier.fillMaxSize(), parties = listOf(party))
            }
        }
    }

    when {
        state.showCampusDialog -> CampusChangeDialog(
            state.campus, interacted(onConfirmCampusChange), interacted(onDismissCampusDialog),
            enabled = !state.isBusy, onUserInteraction = onUserInteraction,
        )
        state.showLogoutDialog -> LogoutDialog(
            interacted(onConfirmLogout), interacted(onDismissLogout),
            enabled = !state.isBusy, onUserInteraction = onUserInteraction,
        )
        state.restartAfterLogoutMessage != null -> RestartAfterLogoutDialog(
            state.restartAfterLogoutMessage, interacted(onRestartAfterLogout), interacted(onDismissRestartAfterLogout),
            enabled = !state.isBusy, onUserInteraction = onUserInteraction,
        )
        state.showAboutDialog -> AboutDialog(
            onDismiss = interacted(onDismissAbout),
            onUserInteraction = onUserInteraction,
            isCheckingUpdate = state.isCheckingUpdate,
            updateResult = state.releaseCheckResult,
            onOpenProject = interacted(onOpenProject),
            onCheckForUpdate = interacted(onCheckForUpdate),
            onOpenRelease = { onUserInteraction(); onOpenRelease(it) },
        )
        state.showAccountManager -> AccountManagerDialog(
            state.accounts, state.currentIndex,
            onDelete = { onUserInteraction(); onDeleteAccount(it) },
            onDismiss = interacted(onDismissAccounts), enabled = !state.isBusy, onUserInteraction = onUserInteraction,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomePage(
    state: MainUiState, listState: LazyListState, modifier: Modifier, contentPadding: PaddingValues,
    onSelectCampus: (Campus) -> Unit, onImportCsv: () -> Unit,
    onShowCampusDialog: () -> Unit, onAccounts: () -> Unit, onLogs: () -> Unit,
) {
    val palette = LocalConnectorPalette.current
    val phase = if (state.accounts.isEmpty()) ConnectionPhase.NO_ACCOUNTS else state.runtimeStatus.phase
    val statusTitle = when (phase) {
        ConnectionPhase.IDLE -> "等待连接"
        ConnectionPhase.CHECKING -> "正在连接"
        ConnectionPhase.CONNECTED -> "网络已连接"
        ConnectionPhase.RETRYING -> "正在重试"
        ConnectionPhase.PAUSED -> "连接已暂停"
        ConnectionPhase.NO_ACCOUNTS -> "从导入账号开始"
    }
    val statusColor = when (phase) {
        ConnectionPhase.CONNECTED -> palette.success
        ConnectionPhase.PAUSED, ConnectionPhase.RETRYING -> palette.warning
        else -> palette.muted
    }
    LazyColumn(modifier, listState, contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "connection") {
            ActionPanel {
                PanelContent {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(statusColor, CircleShape))
                        Text("连接状态", color = palette.muted, fontSize = 14.sp)
                    }
                    Text(statusTitle, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold,
                        color = palette.ink, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    SupportingText(
                        if (state.accounts.isEmpty()) "选择所在校区，导入 CSV 配置后即可自动连接。"
                        else state.runtimeStatus.message,
                    )
                    if (phase == ConnectionPhase.CONNECTED) {
                        SectionDivider()
                        Text(
                            state.runtimeStatus.accountName?.takeIf { it.isNotBlank() }?.let { "在线账号：$it" }
                                ?: "未确认校园网在线账号",
                            color = palette.ink, fontSize = 14.sp,
                        )
                    }
                }
                if (state.accounts.isNotEmpty()) {
                    ActionRow("查看运行日志", summary = "查看连接过程与最新结果", tag = "open_logs", onClick = onLogs)
                }
            }
        }
        if (state.accounts.isEmpty()) {
            item(key = "setup") {
                Panel {
                    Text("选择校区", fontWeight = FontWeight.SemiBold, color = palette.ink)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Campus.entries.forEach { campus ->
                            Button(
                                onClick = { onSelectCampus(campus) },
                                enabled = !state.isBusy,
                                modifier = Modifier.testTag("campus_${campus.name.lowercase()}")
                                    .semantics { selected = state.campus == campus },
                                colors = if (state.campus == campus) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                            ) { Text("${campus.displayName}校区") }
                        }
                    }
                    SupportingText("CSV 需要“学号”和“身份证号”表头，也可包含姓名、班级、联系电话。")
                    Button(onClick = onImportCsv, enabled = !state.isBusy,
                        modifier = Modifier.fillMaxWidth().testTag("import_csv"),
                        colors = ButtonDefaults.buttonColorsPrimary()) { Text("导入配置 (CSV)") }
                }
            }
        } else {
            item(key = "configuration") {
                ActionPanel {
                    ActionRow("所在校区", "${state.campus.displayName}校区", !state.isBusy, "change_campus", onClick = onShowCampusDialog)
                    SectionDivider(Modifier.padding(horizontal = 16.dp))
                    ActionRow("账号配置", "已保存 ${state.accounts.size} 个账号", tag = "open_accounts", onClick = onAccounts)
                }
            }
        }
        item(key = "mode") {
            Panel {
                Text(if (state.keepAlive) "服务模式" else "普通模式", color = palette.ink, fontWeight = FontWeight.SemiBold)
                SupportingText(
                    when {
                        state.keepAlive && state.runtimeStatus.serviceRunning -> "连接服务正在运行，持续检查并恢复校园网连接。"
                        state.keepAlive -> "已记住服务模式；当前服务未运行。"
                        state.autoCloseEnabled -> "新连接成功后 3 秒自动关闭；操作界面会取消本次自动关闭。"
                        else -> "连接成功后保持打开，可在设置中开启自动关闭。"
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogsPage(
    state: MainUiState, listState: LazyListState, modifier: Modifier, contentPadding: PaddingValues,
    onToggleAutoScroll: (Boolean) -> Unit, onExportLogs: () -> Unit, onUserInteraction: () -> Unit,
) {
    val palette = LocalConnectorPalette.current
    val scope = rememberCoroutineScope()
    // Include content identity: repository retains 1,000 entries, so size eventually stops changing.
    LaunchedEffect(state.logs, state.autoScrollLogs) {
        if (state.autoScrollLogs && state.logs.isNotEmpty()) listState.animateScrollToItem(state.logs.lastIndex)
    }
    Column(modifier.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel {
            ToggleRow("自动滚动到最新", "关闭后可停留在历史记录", state.autoScrollLogs,
                tag = "auto_scroll_switch", onCheckedChange = onToggleAutoScroll)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onExportLogs, enabled = !state.isBusy,
                    modifier = Modifier.testTag("export_logs")) { Text("导出日志") }
                Button(onClick = {
                    onUserInteraction()
                    scope.launch { listState.animateScrollToItem(state.logs.lastIndex) }
                }, enabled = state.logs.isNotEmpty(), modifier = Modifier.testTag("scroll_to_bottom")) {
                    Text("滚动到底部")
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("logs_list"), listState,
            contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (state.logs.isEmpty()) {
                item(key = "emptyLogs") {
                    Panel {
                        Text("还没有运行日志", color = palette.ink, fontWeight = FontWeight.SemiBold)
                        SupportingText("开始连接后，运行记录会显示在这里。")
                    }
                }
            } else {
                itemsIndexed(state.logs) { _, log ->
                    Text(log, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        color = palette.ink, fontSize = 13.sp, lineHeight = 21.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsPage(
    state: MainUiState, listState: LazyListState, modifier: Modifier, contentPadding: PaddingValues,
    onToggleKeepAlive: (Boolean) -> Unit, onSetAutoClose: (Boolean) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit, onShowCampusDialog: () -> Unit,
    onShowLogout: () -> Unit, onShowAbout: () -> Unit,
) {
    val palette = LocalConnectorPalette.current
    LazyColumn(modifier, listState, contentPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "connectionLabel") { SectionLabel("连接") }
        item(key = "connectionSettings") {
            ActionPanel {
                PanelContent {
                    ToggleRow("服务模式", "持续检查连接，记住开关状态", state.keepAlive,
                        enabled = !state.isBusy, tag = "keep_alive_switch", onCheckedChange = onToggleKeepAlive)
                    ToggleRow("连接成功后自动关闭", "普通模式下，新连接成功 3 秒后关闭；操作界面会取消本次自动关闭。",
                        state.autoCloseEnabled, enabled = !state.isBusy, tag = "auto_close_switch", onCheckedChange = onSetAutoClose)
                }
                ActionRow("切换校区", "当前：${state.campus.displayName}校区", !state.isBusy, "change_campus",
                    onClick = onShowCampusDialog)
                ActionRow("注销校园网", "暂停连接并解绑、注销当前会话", !state.isBusy, "logout",
                    destructive = true, onClick = onShowLogout)
            }
        }
        item(key = "appearanceLabel") { SectionLabel("外观") }
        item(key = "appearance") {
            Panel {
                Text("显示模式", color = palette.ink, fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        Button(
                            onClick = { onSetThemeMode(mode) }, enabled = !state.isBusy,
                            modifier = Modifier.testTag("theme_${mode.name.lowercase()}")
                                .semantics { selected = state.themeMode == mode },
                            colors = if (state.themeMode == mode) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
                        ) {
                            Text(when (mode) { ThemeMode.SYSTEM -> "跟随系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" })
                        }
                    }
                }
            }
        }
        item(key = "about") {
            ActionPanel { ActionRow("关于", "版本与项目说明", tag = "about", onClick = onShowAbout) }
        }
    }
}
