package top.onering.easyconnecter

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import top.onering.easyconnecter.ui.MainScreen
import top.onering.easyconnecter.ui.theme._1ConnecterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            _1ConnecterTheme {
                val viewModel: MainViewModel = viewModel()
                val state by viewModel.uiState.collectAsState()

                val csvLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent()
                ) { uri -> uri?.let(viewModel::importCsv) }

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    viewModel.setKeepAlive(true)
                    if (!granted) {
                        Toast.makeText(
                            this,
                            "通知权限未授予；保活仍会运行，可在系统任务管理器中查看",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                LaunchedEffect(viewModel) {
                    viewModel.events.collect { event ->
                        when (event) {
                            MainUiEvent.CloseApp -> finish()
                            is MainUiEvent.Toast -> Toast.makeText(
                                this@MainActivity,
                                event.message,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }

                MainScreen(
                    state = state,
                    onSelectCampus = viewModel::selectInitialCampus,
                    onImportCsv = { csvLauncher.launch("text/*") },
                    onReimportCsv = {
                        viewModel.markUserInteraction()
                        csvLauncher.launch("text/*")
                    },
                    onToggleKeepAlive = { enabled ->
                        viewModel.markUserInteraction()
                        val needsPermission = enabled &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        if (needsPermission) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            viewModel.setKeepAlive(enabled)
                        }
                    },
                    onShowAccounts = viewModel::showAccountManager,
                    onDismissAccounts = viewModel::dismissAccountManager,
                    onDeleteAccount = viewModel::deleteAccount,
                    onShowCampusDialog = viewModel::showCampusDialog,
                    onDismissCampusDialog = viewModel::dismissCampusDialog,
                    onConfirmCampusChange = viewModel::confirmCampusChange,
                    onShowAbout = viewModel::showAboutDialog,
                    onDismissAbout = viewModel::dismissAboutDialog,
                    onShowLogout = viewModel::showLogoutDialog,
                    onDismissLogout = viewModel::dismissLogoutDialog,
                    onConfirmLogout = viewModel::confirmLogout,
                    onExportLogs = viewModel::exportLogs
                )
            }
        }
    }
}
