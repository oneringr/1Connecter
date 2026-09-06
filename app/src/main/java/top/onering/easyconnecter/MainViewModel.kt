package top.onering.easyconnecter

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.onering.easyconnecter.data.RuntimeStatus
import top.onering.easyconnecter.model.Account
import top.onering.easyconnecter.model.Campus
import top.onering.easyconnecter.model.ConnectionOutcome
import top.onering.easyconnecter.service.KeepAliveService

data class MainUiState(
    val accounts: List<Account> = emptyList(),
    val logs: List<String> = emptyList(),
    val currentIndex: Int = 0,
    val campus: Campus = Campus.CHENGBEI,
    val keepAlive: Boolean = false,
    val autoScrollLogs: Boolean = true,
    val runtimeStatus: RuntimeStatus = RuntimeStatus(),
    val showAccountManager: Boolean = false,
    val showCampusDialog: Boolean = false,
    val showAboutDialog: Boolean = false,
    val showLogoutDialog: Boolean = false,
    val showFireworks: Boolean = false,
    val isBusy: Boolean = false
)

sealed interface MainUiEvent {
    data class Toast(val message: String) : MainUiEvent
    data object CloseApp : MainUiEvent
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = application.appContainer
    private val _uiState = MutableStateFlow(
        MainUiState(
            accounts = container.accounts.accounts.value,
            logs = container.logs.logs.value,
            currentIndex = container.config.currentIndex.value,
            campus = container.config.campus.value,
            keepAlive = container.config.keepAlive.value,
            runtimeStatus = container.runtime.status.value
        )
    )
    private val _events = MutableSharedFlow<MainUiEvent>(extraBufferCapacity = 4)
    private var normalModeJob: Job? = null
    private var autoClose = true

    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    val events: SharedFlow<MainUiEvent> = _events.asSharedFlow()

    init {
        observeRepositories()
        when {
            container.accounts.accounts.value.isEmpty() -> Unit
            container.config.keepAlive.value -> KeepAliveService.start(application)
            else -> startNormalMode()
        }
    }

    fun setKeepAlive(enabled: Boolean) {
        autoClose = false
        viewModelScope.launch {
            if (enabled && container.accounts.accounts.value.isEmpty()) {
                _events.emit(MainUiEvent.Toast("请先导入账号配置"))
                return@launch
            }
            container.config.setKeepAlive(enabled)
            if (enabled) {
                normalModeJob?.cancel()
                KeepAliveService.start(getApplication())
                container.logs.add("服务模式已开启")
            } else {
                KeepAliveService.stop(getApplication())
                container.logs.add("服务模式已关闭")
                startNormalMode()
            }
        }
    }

    fun setAutoScrollLogs(enabled: Boolean) {
        autoClose = false
        _uiState.update { it.copy(autoScrollLogs = enabled) }
    }

    fun selectInitialCampus(campus: Campus) {
        viewModelScope.launch { container.coordinator.changeCampus(campus) }
    }

    fun importCsv(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val result = try {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                    container.accounts.importCsv(stream)
                }
            } catch (error: Exception) {
                null
            }

            when {
                result == null -> _events.emit(MainUiEvent.Toast("无法读取所选文件"))
                result.fatalError != null -> _events.emit(MainUiEvent.Toast(result.fatalError))
                result.accounts.isEmpty() -> _events.emit(MainUiEvent.Toast("未解析出有效账号"))
                else -> {
                    container.coordinator.replaceAccounts(result.accounts)
                    val warningText = if (result.warnings.isEmpty()) "" else "，跳过 ${result.warnings.size} 行"
                    container.logs.add("成功导入 ${result.accounts.size} 个账号$warningText")
                    _events.emit(MainUiEvent.Toast("成功导入 ${result.accounts.size} 个账号$warningText"))
                    if (container.config.keepAlive.value) {
                        KeepAliveService.start(getApplication())
                    } else {
                        startNormalMode()
                    }
                }
            }
            _uiState.update { it.copy(isBusy = false) }
        }
    }

    fun deleteAccount(index: Int) {
        autoClose = false
        viewModelScope.launch {
            val removed = container.coordinator.removeAccount(index) ?: return@launch
            container.logs.add("手动删除账号: ${removed.name.ifBlank { removed.studentId }}")
            if (container.accounts.accounts.value.isEmpty()) {
                container.config.setKeepAlive(false)
                KeepAliveService.stop(getApplication())
            }
        }
    }

    fun exportLogs() {
        autoClose = false
        viewModelScope.launch {
            val result = container.logExporter.export(container.logs.snapshot())
            _events.emit(
                MainUiEvent.Toast(
                    result.fold(
                        onSuccess = { "日志已保存至 ${it.displayPath}" },
                        onFailure = { "日志导出失败: ${it.message ?: "未知错误"}" }
                    )
                )
            )
        }
    }

    fun exportCsv(uri: Uri) {
        autoClose = false
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            val result = runCatching {
                val outputStream = requireNotNull(
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                ) { "无法打开保存位置" }
                outputStream.use { container.accounts.exportCsv(it) }
            }
            _events.emit(
                MainUiEvent.Toast(
                    result.fold(
                        onSuccess = { "已导出 $it 个账号" },
                        onFailure = { "CSV 导出失败: ${it.message ?: "未知错误"}" }
                    )
                )
            )
            _uiState.update { it.copy(isBusy = false) }
        }
    }

    fun showAccountManager() = interact { it.copy(showAccountManager = true) }
    fun dismissAccountManager() = _uiState.update { it.copy(showAccountManager = false) }
    fun showCampusDialog() = interact { it.copy(showCampusDialog = true) }
    fun dismissCampusDialog() = _uiState.update { it.copy(showCampusDialog = false) }
    fun showAboutDialog() = interact { it.copy(showAboutDialog = true) }
    fun dismissAboutDialog() = _uiState.update { it.copy(showAboutDialog = false) }
    fun showLogoutDialog() = interact { it.copy(showLogoutDialog = true) }
    fun dismissLogoutDialog() = _uiState.update { it.copy(showLogoutDialog = false) }

    fun markUserInteraction() {
        autoClose = false
    }

    fun confirmCampusChange() {
        autoClose = false
        viewModelScope.launch {
            _uiState.update { it.copy(showCampusDialog = false, isBusy = true) }
            val wasKeepAlive = container.config.keepAlive.value
            normalModeJob?.cancel()
            KeepAliveService.stop(getApplication())
            val newCampus = if (container.config.campus.value == Campus.CHENGBEI) {
                Campus.ZHONGSHAN
            } else {
                Campus.CHENGBEI
            }
            container.coordinator.changeCampus(newCampus)
            container.logs.add("已切换至${newCampus.displayName}校区")
            if (wasKeepAlive) KeepAliveService.start(getApplication()) else startNormalMode()
            _uiState.update { it.copy(isBusy = false) }
        }
    }

    fun confirmLogout() {
        autoClose = false
        viewModelScope.launch {
            _uiState.update { it.copy(showLogoutDialog = false, isBusy = true) }
            normalModeJob?.cancel()
            container.config.setKeepAlive(false)
            KeepAliveService.stop(getApplication())
            val result = container.coordinator.logoutAndUnbind()
            _events.emit(MainUiEvent.Toast(result.message))
            _uiState.update { it.copy(isBusy = false, showFireworks = false) }
        }
    }

    private fun startNormalMode() {
        if (normalModeJob?.isActive == true || container.accounts.accounts.value.isEmpty()) return
        normalModeJob = viewModelScope.launch {
            while (!container.config.keepAlive.value && container.accounts.accounts.value.isNotEmpty()) {
                when (val outcome = container.coordinator.connectOnce()) {
                    is ConnectionOutcome.Connected -> {
                        if (outcome.alreadyConnected) {
                            container.logs.add("检测到网络已连接，应用保持打开")
                            return@launch
                        }
                        _uiState.update { it.copy(showFireworks = true) }
                        delay(3_000)
                        if (shouldAutoCloseAfterConnection(outcome, autoClose)) {
                            _events.emit(MainUiEvent.CloseApp)
                        } else {
                            container.logs.add("自动关闭已取消，请手动退出")
                        }
                        return@launch
                    }
                    ConnectionOutcome.NoAccounts -> return@launch
                    else -> delay(3_000)
                }
            }
        }
    }

    private fun interact(transform: (MainUiState) -> MainUiState) {
        autoClose = false
        _uiState.update(transform)
    }

    private fun observeRepositories() {
        viewModelScope.launch {
            container.accounts.accounts.collect { accounts ->
                _uiState.update { it.copy(accounts = accounts) }
            }
        }
        viewModelScope.launch {
            container.logs.logs.collect { logs -> _uiState.update { it.copy(logs = logs) } }
        }
        viewModelScope.launch {
            container.config.currentIndex.collect { index -> _uiState.update { it.copy(currentIndex = index) } }
        }
        viewModelScope.launch {
            container.config.campus.collect { campus -> _uiState.update { it.copy(campus = campus) } }
        }
        viewModelScope.launch {
            container.config.keepAlive.collect { enabled -> _uiState.update { it.copy(keepAlive = enabled) } }
        }
        viewModelScope.launch {
            container.runtime.status.collect { status -> _uiState.update { it.copy(runtimeStatus = status) } }
        }
    }
}

internal fun shouldAutoCloseAfterConnection(
    outcome: ConnectionOutcome,
    autoCloseEnabled: Boolean
): Boolean = autoCloseEnabled &&
    outcome is ConnectionOutcome.Connected &&
    !outcome.alreadyConnected
