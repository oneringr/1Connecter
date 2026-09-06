package top.onering.easyconnecter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import top.onering.easyconnecter.MainUiState
import top.onering.easyconnecter.model.Campus
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: MainUiState,
    onSelectCampus: (Campus) -> Unit,
    onImportCsv: () -> Unit,
    onReimportCsv: () -> Unit,
    onToggleKeepAlive: (Boolean) -> Unit,
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
    onExportLogs: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val listState = rememberLazyListState()

    fun closeDrawerThen(action: () -> Unit) {
        scope.launch { drawerState.close() }
        action()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(12.dp))
                Text("医连接 - 菜单", Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                HorizontalDivider()
                NavigationDrawerItem(
                    label = {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("服务模式")
                            Switch(checked = state.keepAlive, onCheckedChange = null)
                        }
                    },
                    selected = false,
                    onClick = { onToggleKeepAlive(!state.keepAlive) }
                )
                NavigationDrawerItem(
                    label = { Text("管理账号") },
                    selected = false,
                    onClick = { closeDrawerThen(onShowAccounts) }
                )
                NavigationDrawerItem(
                    label = { Text("切换校区") },
                    selected = false,
                    onClick = { closeDrawerThen(onShowCampusDialog) }
                )
                NavigationDrawerItem(
                    label = { Text("注销校园网") },
                    selected = false,
                    onClick = { closeDrawerThen(onShowLogout) }
                )
                NavigationDrawerItem(
                    label = { Text("重新导入配置") },
                    selected = false,
                    onClick = { closeDrawerThen(onReimportCsv) }
                )
                NavigationDrawerItem(
                    label = { Text("导出日志") },
                    selected = false,
                    onClick = { closeDrawerThen(onExportLogs) }
                )
                Spacer(Modifier.weight(1f))
                NavigationDrawerItem(
                    label = { Text("关于") },
                    selected = false,
                    onClick = { closeDrawerThen(onShowAbout) }
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("1Connecter") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "菜单")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(Modifier.padding(innerPadding).fillMaxSize()) {
                if (state.accounts.isEmpty()) {
                    EmptyAccountContent(state.campus, onSelectCampus, onImportCsv)
                } else {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Text(
                            "运行日志 - ${state.campus.displayName}",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            state.runtimeStatus.message,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)
                            ) {
                                items(state.logs) { log ->
                                    Text(log, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (state.logs.isNotEmpty()) {
                                SmallFloatingActionButton(
                                    onClick = {
                                        scope.launch { listState.animateScrollToItem(state.logs.lastIndex) }
                                    },
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                                ) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "滚动到底部")
                                }
                            }
                        }
                    }
                }

                if (state.showFireworks) {
                    val party = remember {
                        Party(
                            speed = 0f,
                            maxSpeed = 30f,
                            damping = 0.9f,
                            spread = 360,
                            colors = listOf(0xfce18a, 0xff726d, 0xf4306d, 0xb48def),
                            emitter = Emitter(duration = 100, TimeUnit.MILLISECONDS).max(100),
                            position = Position.Relative(0.5, 0.3)
                        )
                    }
                    KonfettiView(Modifier.fillMaxSize(), parties = listOf(party))
                }

                if (state.isBusy) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    if (state.showAccountManager) {
        AccountManagerDialog(state.accounts, state.currentIndex, onDeleteAccount, onDismissAccounts)
    }
    if (state.showCampusDialog) {
        CampusChangeDialog(state.campus, onConfirmCampusChange, onDismissCampusDialog)
    }
    if (state.showAboutDialog) AboutDialog(onDismissAbout)
    if (state.showLogoutDialog) LogoutDialog(onConfirmLogout, onDismissLogout)
}

@Composable
private fun EmptyAccountContent(
    campus: Campus,
    onSelectCampus: (Campus) -> Unit,
    onImportCsv: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("请选择校区并导入账号配置", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Campus.entries.forEach { option ->
                Button(
                    onClick = { onSelectCampus(option) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (campus == option) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondary
                        }
                    )
                ) {
                    Text("${option.displayName}校区")
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        Button(onClick = onImportCsv) { Text("导入配置 (CSV)") }
    }
}
