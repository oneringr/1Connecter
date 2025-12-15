package top.onering.easyconnecter

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nl.dionsegijn.konfetti.compose.KonfettiView
import nl.dionsegijn.konfetti.core.Party
import nl.dionsegijn.konfetti.core.Position
import nl.dionsegijn.konfetti.core.emitter.Emitter
import top.onering.easyconnecter.data.AccountManager
import top.onering.easyconnecter.model.Account
import top.onering.easyconnecter.network.NetworkManager
import top.onering.easyconnecter.ui.theme._1ConnecterTheme
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            _1ConnecterTheme {
                MainScreen(
                    restartApp = {
                        finish()
                        startActivity(intent)
                    },
                    closeApp = {
                        finish()
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(restartApp: () -> Unit, closeApp: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accountManager = remember { AccountManager(context) }
    val networkManager = remember { NetworkManager() }

    var accounts by remember { mutableStateOf(listOf<Account>()) }
    var logs by remember { mutableStateOf(listOf<String>()) }
    var isRunning by remember { mutableStateOf(false) }
    var showFireworks by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showCampusDialog by remember { mutableStateOf(false) }
    var showAccountManager by remember { mutableStateOf(false) }
    var keepAliveMode by remember { mutableStateOf(false) }

    var campusUrl by remember { mutableStateOf("http://172.17.254.6/drcom/login") }
    var autoClose by remember { mutableStateOf(true) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // Load accounts and config on startup
    LaunchedEffect(Unit) {
        accounts = accountManager.loadAccounts()
        currentIndex = accountManager.loadCurrentIndex()
        keepAliveMode = accountManager.loadKeepAlive()
        val savedUrl = accountManager.loadCampusUrl()
        if (savedUrl != null) {
            campusUrl = savedUrl
        } else {
            campusUrl = "http://172.17.254.6/drcom/login"
            accountManager.saveCampusUrl(campusUrl)
        }

        if (accounts.isNotEmpty()) {
            isRunning = true
        }
    }

    // File picker
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                inputStream?.let { stream ->
                    val newAccounts = accountManager.parseCsv(stream)
                    if (newAccounts.isNotEmpty()) {
                        accounts = newAccounts
                        accountManager.saveAccounts(newAccounts)
                        currentIndex = 0
                        accountManager.saveCurrentIndex(0)
                        logs = logs + "成功导入 ${newAccounts.size} 个账号"
                        isRunning = true
                    } else {
                        Toast.makeText(context, "未能解析出账号，请检查CSV格式", Toast.LENGTH_LONG)
                            .show()
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Main Logic Loop
    LaunchedEffect(isRunning, accounts, campusUrl) {
        if (isRunning && accounts.isNotEmpty()) {
            while (isRunning) {
                val isConnected = networkManager.isConnected()
                if (isConnected) {
                    if (keepAliveMode) {
                        // 移除之前的日志重复检查，每次检测都输出日志
                        logs = logs + "[${getTime()}] ✅ 网络正常，持续监测中..."
                        showFireworks = true
                        delay(30000) // 服务模式延迟，默认30000（30秒）
                    } else {
                        logs = logs + "[${getTime()}] ✅ 网络已连接🎉🎉🎉"
                        showFireworks = true
                        delay(3000) // 退出延迟，默认3000（3秒）

                        if (autoClose) {
                            delay(1000)
                            closeApp()
                        } else {
                            logs = logs + "[${getTime()}] 自动关闭已取消，请手动退出"
                        }
                        isRunning = false
                    }
                } else {
                    if (keepAliveMode) {
                        // 断线重连时也总是输出提示
                        logs = logs + "[${getTime()}] ⚠️ 监测到网络断开，开始尝试重连..."
                    }

                    if (currentIndex >= accounts.size) {
                        currentIndex = 0
                    }
                    val account = accounts[currentIndex]
                    logs =
                        logs + "[${getTime()}] 尝试登录(${if (campusUrl.contains("172")) "城北" else "忠山"}): ${account.name}"

                    val result =
                        networkManager.login(account.studentId, account.password, campusUrl)

                    if (result.success) {
                        logs = logs + "[${getTime()}] ✅ 登录成功!"
                        // Save the NEXT account index for future runs
                        val nextIndex = (currentIndex + 1) % accounts.size
                        accountManager.saveCurrentIndex(nextIndex)
                    } else {
                        // Check for password error
                        if (result.message == "账号或密码错误") {
                            logs = logs + "[${getTime()}] ❌ 密码错误，删除账号: ${account.name}"
                            // Delete account
                            val newAccounts = accounts.toMutableList()
                            if (currentIndex < newAccounts.size) {
                                newAccounts.removeAt(currentIndex)
                                accounts = newAccounts
                                accountManager.saveAccounts(newAccounts)

                                if (newAccounts.isEmpty()) {
                                    isRunning = false
                                    logs = logs + "[${getTime()}] 所有账号均已尝试失败"
                                } else {
                                    // If we deleted index 5, the old index 6 becomes 5.
                                    // So we don't increment index, but we need to check bounds.
                                    if (currentIndex >= newAccounts.size) {
                                        currentIndex = 0
                                    }
                                    accountManager.saveCurrentIndex(currentIndex)
                                }
                            }
                        } else {
                            logs = logs + "[${getTime()}] ❌ 登录失败: ${result.message}"
                            currentIndex = (currentIndex + 1) % accounts.size
                            accountManager.saveCurrentIndex(currentIndex)
                        }
                    }

                    delay(2000)
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(12.dp))
                Text(
                    "医连接 - 菜单",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleLarge
                )
                HorizontalDivider()
                NavigationDrawerItem(
                    label = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("服务模式")
                            Switch(
                                checked = keepAliveMode,
                                onCheckedChange = { checked ->
                                    keepAliveMode = checked
                                    accountManager.saveKeepAlive(checked)
                                    autoClose = false
                                    logs =
                                        logs + "[${getTime()}] 服务模式已更改，请手动重启程序以生效！"
                                    if (checked && accounts.isNotEmpty()) {
                                        isRunning = true
                                    }
                                }
                            )
                        }
                    },
                    selected = false,
                    onClick = {
                        val newValue = !keepAliveMode
                        keepAliveMode = newValue
                        accountManager.saveKeepAlive(newValue)
                        autoClose = false
                        if (newValue && accounts.isNotEmpty()) {
                            isRunning = true
                        }
                    }
                )
                NavigationDrawerItem(
                    label = { Text("管理账号") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        showAccountManager = true
                        autoClose = false
                    }
                )
                NavigationDrawerItem(
                    label = { Text("切换校区") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        showCampusDialog = true
                        autoClose = false
                    }
                )
                NavigationDrawerItem(
                    label = { Text("重新导入配置") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        launcher.launch("text/*")
                        autoClose = false
                    }
                )
                NavigationDrawerItem(
                    label = { Text("导出日志") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        val logContent = logs.joinToString("\n")
                        val file = File(context.getExternalFilesDir(null), "connect_logs.txt")
                        file.writeText(logContent)
                        Toast.makeText(
                            context,
                            "日志已保存至/Android/data/top.onering.easyconnecter/files/${file.name}",
                            Toast.LENGTH_SHORT
                        ).show()
                        autoClose = false
                    }
                )
                Spacer(modifier = Modifier.weight(1f))
                NavigationDrawerItem(
                    label = { Text("关于") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        showAboutDialog = true
                        autoClose = false
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
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
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Box(modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()) {
                if (accounts.isEmpty()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "请选择校区并导入账号配置",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(24.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Button(
                                onClick = {
                                    campusUrl = "http://172.17.254.6/drcom/login"
                                    accountManager.saveCampusUrl(campusUrl)
                                    Toast.makeText(context, "已选择: 城北校区", Toast.LENGTH_SHORT)
                                        .show()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (campusUrl.contains("172")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                )
                            ) {
                                Text("城北校区")
                            }

                            Button(
                                onClick = {
                                    campusUrl = "http://192.168.2.27/drcom/login"
                                    accountManager.saveCampusUrl(campusUrl)
                                    Toast.makeText(context, "已选择: 忠山校区", Toast.LENGTH_SHORT)
                                        .show()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (campusUrl.contains("192")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                )
                            ) {
                                Text("忠山校区")
                            }
                        }

                        Spacer(modifier = Modifier.height(32.dp))
                        Button(onClick = { launcher.launch("text/*") }) {
                            Text("导入配置 (CSV)")
                        }
                    }
                } else {
                    Column(modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)) {
                        Text(
                            "运行日志 - ${if (campusUrl.contains("172")) "城北" else "忠山"}",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            items(logs) { log ->
                                Text(log, style = MaterialTheme.typography.bodySmall)
                            }
                        }

                        // Buttons removed as requested, moved to Drawer
                    }
                }

                if (showFireworks) {
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
                    KonfettiView(
                        modifier = Modifier.fillMaxSize(),
                        parties = listOf(party)
                    )
                }

                // Account Manager Dialog/Overlay
                if (showAccountManager) {
                    AlertDialog(
                        onDismissRequest = { showAccountManager = false },
                        title = { Text("账号管理 (${accounts.size})") },
                        text = {
                            Column(modifier = Modifier.height(400.dp)) {
                                Text("当前运行位置: $currentIndex")
                                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    itemsIndexed(accounts) { index, account ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .background(if (index == currentIndex) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                                .padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column {
                                                Text(
                                                    account.name,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                                Text(
                                                    account.studentId,
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                            IconButton(onClick = {
                                                val newAccounts = accounts.toMutableList()
                                                newAccounts.removeAt(index)
                                                accounts = newAccounts
                                                accountManager.saveAccounts(newAccounts)
                                                // Adjust currentIndex if needed
                                                if (index < currentIndex) {
                                                    currentIndex--
                                                    accountManager.saveCurrentIndex(currentIndex)
                                                } else if (index == currentIndex) {
                                                    // If we deleted the current one, stay at index (which is next item) but check bounds
                                                    if (currentIndex >= newAccounts.size) {
                                                        currentIndex = 0
                                                    }
                                                    accountManager.saveCurrentIndex(currentIndex)
                                                }
                                            }) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = "删除"
                                                )
                                            }
                                        }
                                        HorizontalDivider()
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showAccountManager = false }) {
                                Text("关闭")
                            }
                        }
                    )
                }

                if (showAboutDialog) {
                    AlertDialog(
                        onDismissRequest = { showAboutDialog = false },
                        title = { Text("关于") },
                        text = { Text("1Connecter（医连接）\nSWMU校园网自动连接工具\n\n注意：本工具仅提供自动连接服务，不对您的信息来源负责！\n\nV1.2 Beta Version\nMade By: OneRingR\nSpecially Thanks: Larry for work") },
                        confirmButton = {
                            TextButton(onClick = { showAboutDialog = false }) {
                                Text("确定")
                            }
                        }
                    )
                }

                if (showCampusDialog) {
                    AlertDialog(
                        onDismissRequest = { showCampusDialog = false },
                        title = { Text("切换校区") },
                        text = { Text("切换校区将重启应用，是否继续？") },
                        confirmButton = {
                            TextButton(onClick = {
                                val newUrl =
                                    if (campusUrl.contains("172")) "http://192.168.2.27/drcom/login" else "http://172.17.254.6/drcom/login"
                                accountManager.saveCampusUrl(newUrl)
                                showCampusDialog = false
                                restartApp()
                            }) {
                                Text("切换并重启")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showCampusDialog = false }) {
                                Text("取消")
                            }
                        }
                    )
                }
            }
        }
    }
}

fun getTime(): String {
    val current = LocalDateTime.now()
    val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    return current.format(formatter)
}