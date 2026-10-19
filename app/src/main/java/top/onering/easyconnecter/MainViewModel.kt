package top.onering.easyconnecter

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.onering.easyconnecter.data.RuntimeStatus
import top.onering.easyconnecter.data.ReleaseChecker
import top.onering.easyconnecter.data.ReleaseCheckResult
import top.onering.easyconnecter.data.ConnectionPhase
import top.onering.easyconnecter.data.toRuntimeStatus
import top.onering.easyconnecter.model.Account
import top.onering.easyconnecter.model.Campus
import top.onering.easyconnecter.model.ConnectionOutcome
import top.onering.easyconnecter.model.LogoutOutcome
import top.onering.easyconnecter.model.ThemeMode
import top.onering.easyconnecter.service.KeepAliveService

data class MainUiState(
    val accounts: List<Account> = emptyList(),
    val logs: List<String> = emptyList(),
    val currentIndex: Int = 0,
    val campus: Campus = Campus.CHENGBEI,
    val keepAlive: Boolean = false,
    val autoCloseEnabled: Boolean = true,
    val showOnboarding: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val autoScrollLogs: Boolean = true,
    val runtimeStatus: RuntimeStatus = RuntimeStatus(),
    val showAccountManager: Boolean = false,
    val showCampusDialog: Boolean = false,
    val showAboutDialog: Boolean = false,
    val isCheckingUpdate: Boolean = false,
    val releaseCheckResult: ReleaseCheckResult? = null,
    val showLogoutDialog: Boolean = false,
    val restartAfterLogoutMessage: String? = null,
    val showFireworks: Boolean = false,
    val isBusy: Boolean = false
)

sealed interface MainUiEvent {
    data class Toast(val message: String) : MainUiEvent
    data object CloseApp : MainUiEvent
    data object RestartApp : MainUiEvent
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
            autoCloseEnabled = container.config.autoCloseEnabled.value,
            showOnboarding = !container.config.onboardingCompleted.value,
            themeMode = container.config.themeMode.value,
            runtimeStatus = container.runtime.status.value
        )
    )
    private val _events = MutableSharedFlow<MainUiEvent>(extraBufferCapacity = 4)
    private var normalModeJob: Job? = null
    private val connectionChangeMutex = Mutex()
    private var autoClose = true
    private val releaseChecker = ReleaseChecker()
    private var updateCheckJob: Job? = null

    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    val events: SharedFlow<MainUiEvent> = _events.asSharedFlow()

    init {
        observeRepositories()
        viewModelScope.launch {
            connectionChangeMutex.withLock {
                _uiState.update { it.copy(isBusy = true) }
                try {
                    container.config.ensureOnboardingInitialized()
                    when {
                        !container.config.onboardingCompleted.value -> {
                            pauseConnectionWork()
                            reportOnboardingPending()
                        }
                        container.accounts.accounts.value.isEmpty() -> {
                            container.runtime.update(ConnectionOutcome.NoAccounts.toRuntimeStatus(serviceRunning = false))
                            if (container.config.keepAlive.value) {
                                container.logs.add("服务模式已恢复，等待导入账号")
                            }
                        }
                        container.coordinator.connectionsPaused -> container.runtime.update(
                            RuntimeStatus(message = "连接已暂停", phase = ConnectionPhase.PAUSED)
                        )
                        container.config.keepAlive.value -> KeepAliveService.start(application)
                        else -> startNormalMode()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    container.coordinator.pauseConnections()
                    reportOperationFailure("初始化引导状态", error)
                } finally {
                    _uiState.update { it.copy(isBusy = false) }
                }
            }
        }
    }

    fun setKeepAlive(enabled: Boolean) {
        autoClose = false
        viewModelScope.launch {
            connectionChangeMutex.withLock {
                _uiState.update { it.copy(isBusy = true) }
                try {
                    pauseConnectionWork()
                    container.config.setKeepAlive(enabled)
                    if (enabled && container.accounts.accounts.value.isEmpty()) {
                        container.runtime.update(ConnectionOutcome.NoAccounts.toRuntimeStatus(serviceRunning = false))
                        container.logs.add("服务模式已记住，等待导入账号")
                        _events.emit(MainUiEvent.Toast("服务模式已记住，导入账号后自动启用"))
                        return@withLock
                    }
                    resumeConnectionWork()
                    container.logs.add(if (enabled) "服务模式已开启" else "服务模式已关闭")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    reportOperationFailure("切换服务模式", error)
                } finally {
                    _uiState.update { it.copy(isBusy = false) }
                }
            }
        }
    }

    fun setAutoClose(enabled: Boolean) {
        autoClose = false
        viewModelScope.launch {
            try {
                container.config.setAutoClose(enabled)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportOperationFailure("保存自动关闭设置", error)
            }
        }
    }

    fun completeOnboarding(campus: Campus, autoCloseEnabled: Boolean) {
        autoClose = false
        viewModelScope.launch {
            connectionChangeMutex.withLock {
                if (container.config.onboardingCompleted.value) return@withLock
                _uiState.update { it.copy(isBusy = true) }
                try {
                    pauseConnectionWork()
                    container.config.ensureOnboardingInitialized()
                    if (container.accounts.accounts.value.isEmpty()) {
                        _events.emit(MainUiEvent.Toast("请先成功导入 CSV 账号配置"))
                        reportOnboardingPending()
                        return@withLock
                    }
                    container.coordinator.changeCampus(campus)
                    container.config.setAutoClose(autoCloseEnabled)
                    container.config.markOnboardingCompleted()
                    container.logs.add("首次设置已完成，开始连接校园网")
                    resumeConnectionWork()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    reportOperationFailure("完成首次设置", error)
                } finally {
                    _uiState.update { it.copy(isBusy = false) }
                }
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        autoClose = false
        viewModelScope.launch {
            try {
                container.config.setThemeMode(mode)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportOperationFailure("保存主题设置", error)
            }
        }
    }

    fun setAutoScrollLogs(enabled: Boolean) {
        autoClose = false
        _uiState.update { it.copy(autoScrollLogs = enabled) }
    }

    fun selectInitialCampus(campus: Campus) {
        autoClose = false
        viewModelScope.launch {
            try {
                container.coordinator.changeCampus(campus)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportOperationFailure("设置校区", error)
            }
        }
    }

    fun importCsv(uri: Uri) {
        autoClose = false
        viewModelScope.launch {
            _uiState.update { it.copy(isBusy = true) }
            try {
                val result = getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                    container.accounts.importCsv(stream)
                }

                when {
                    result == null -> _events.emit(MainUiEvent.Toast("无法读取所选文件"))
                    result.fatalError != null -> _events.emit(MainUiEvent.Toast(result.fatalError))
                    result.accounts.isEmpty() -> _events.emit(MainUiEvent.Toast("未解析出有效账号"))
                    else -> {
                        connectionChangeMutex.withLock {
                            container.config.ensureOnboardingInitialized()
                            pauseConnectionWork()
                            container.coordinator.replaceAccounts(result.accounts)
                            val warningText = if (result.warnings.isEmpty()) "" else "，跳过 ${result.warnings.size} 行"
                            container.logs.add("成功导入 ${result.accounts.size} 个账号$warningText")
                            _events.emit(MainUiEvent.Toast("成功导入 ${result.accounts.size} 个账号$warningText"))
                            if (container.config.onboardingCompleted.value) {
                                resumeConnectionWork()
                            } else {
                                reportOnboardingPending()
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportOperationFailure("导入账号", error)
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun deleteAccount(index: Int) {
        autoClose = false
        viewModelScope.launch {
            try {
                container.config.ensureOnboardingInitialized()
                val removed = container.coordinator.removeAccount(index) ?: return@launch
                container.logs.add("手动删除账号: ${removed.name.ifBlank { removed.studentId }}")
                if (container.accounts.accounts.value.isEmpty()) {
                    KeepAliveService.stop(getApplication())
                    container.runtime.update(ConnectionOutcome.NoAccounts.toRuntimeStatus(serviceRunning = false))
                    if (container.config.keepAlive.value) {
                        container.logs.add("没有可用账号，服务已暂停，保留服务模式开关")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportOperationFailure("删除账号", error)
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
            try {
                val outputStream = requireNotNull(
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                ) { "无法打开保存位置" }
                val count = outputStream.use { container.accounts.exportCsv(it) }
                _events.emit(MainUiEvent.Toast("已导出 $count 个账号"))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportOperationFailure("CSV 导出", error)
            } finally {
                _uiState.update { it.copy(isBusy = false) }
            }
        }
    }

    fun showAccountManager() = interact { it.copy(showAccountManager = true) }
    fun dismissAccountManager() = _uiState.update { it.copy(showAccountManager = false) }
    fun showCampusDialog() = interact { it.copy(showCampusDialog = true) }
    fun dismissCampusDialog() = _uiState.update { it.copy(showCampusDialog = false) }
    fun showAboutDialog() = interact { it.copy(showAboutDialog = true) }
    fun dismissAboutDialog() = _uiState.update { it.copy(showAboutDialog = false) }

    fun checkForUpdate() {
        autoClose = false
        if (updateCheckJob?.isActive == true) return
        _uiState.update { it.copy(isCheckingUpdate = true, releaseCheckResult = null) }
        updateCheckJob = viewModelScope.launch {
            try {
                val result = releaseChecker.check(BuildConfig.VERSION_NAME)
                _uiState.update { it.copy(releaseCheckResult = result) }
            } finally {
                _uiState.update { it.copy(isCheckingUpdate = false) }
            }
        }
    }
    fun showLogoutDialog() = interact { it.copy(showLogoutDialog = true) }
    fun dismissLogoutDialog() = _uiState.update { it.copy(showLogoutDialog = false) }

    fun dismissRestartAfterLogout() {
        autoClose = false
        container.logs.add("已选择暂不重启，连接服务保持暂停")
        _uiState.update { it.copy(restartAfterLogoutMessage = null) }
    }

    fun restartAfterLogout() {
        autoClose = false
        container.logs.add("正在重启应用并恢复已记忆的服务模式")
        _uiState.update { it.copy(restartAfterLogoutMessage = null) }
        container.coordinator.resumeConnections()
        viewModelScope.launch { _events.emit(MainUiEvent.RestartApp) }
    }

    fun markUserInteraction() {
        autoClose = false
    }

    fun confirmCampusChange() {
        autoClose = false
        viewModelScope.launch {
            connectionChangeMutex.withLock {
                _uiState.update { it.copy(showCampusDialog = false, isBusy = true) }
                try {
                    pauseConnectionWork()
                    val newCampus = if (container.config.campus.value == Campus.CHENGBEI) {
                        Campus.ZHONGSHAN
                    } else {
                        Campus.CHENGBEI
                    }
                    container.coordinator.changeCampus(newCampus)
                    container.logs.add("已切换至${newCampus.displayName}校区")
                    resumeConnectionWork()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    reportOperationFailure("切换校区", error)
                } finally {
                    _uiState.update { it.copy(isBusy = false) }
                }
            }
        }
    }

    fun confirmLogout() {
        autoClose = false
        viewModelScope.launch {
            connectionChangeMutex.withLock {
                _uiState.update { it.copy(showLogoutDialog = false, isBusy = true) }
                try {
                    pauseConnectionWork()
                    val result = container.coordinator.logoutAndUnbind()
                    container.runtime.update(
                        RuntimeStatus(message = result.message, phase = ConnectionPhase.PAUSED)
                    )
                    if (shouldPromptRestartAfterLogout(result)) {
                        container.logs.add("校园网已注销，等待用户选择是否重启")
                        _uiState.update { it.copy(restartAfterLogoutMessage = result.message) }
                    } else {
                        _events.emit(MainUiEvent.Toast(result.message))
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    reportOperationFailure("注销校园网", error)
                } finally {
                    _uiState.update { it.copy(isBusy = false, showFireworks = false) }
                }
            }
        }
    }

    private suspend fun pauseConnectionWork() {
        container.coordinator.pauseConnections()
        normalModeJob?.cancelAndJoin()
        normalModeJob = null
        KeepAliveService.stopAndAwait(getApplication())
        container.coordinator.awaitIdle()
        container.runtime.update(RuntimeStatus(message = "连接已暂停", phase = ConnectionPhase.PAUSED))
    }

    private suspend fun reportOperationFailure(operation: String, error: Exception) {
        val pauseMessage = if (container.coordinator.connectionsPaused) "；连接保持暂停，请处理后重试或重启应用" else ""
        val reason = error.cause?.message ?: error.message ?: "未知错误"
        val message = "${operation}失败: $reason$pauseMessage"
        container.logs.add("❌ $message")
        if (container.coordinator.connectionsPaused) {
            container.runtime.update(RuntimeStatus(message = message, phase = ConnectionPhase.PAUSED))
        }
        _events.emit(MainUiEvent.Toast(message))
    }

    private suspend fun resumeConnectionWork() {
        container.config.ensureOnboardingInitialized()
        if (!container.config.onboardingCompleted.value) {
            container.coordinator.pauseConnections()
            reportOnboardingPending()
            return
        }
        container.coordinator.resumeConnections()
        if (container.accounts.accounts.value.isEmpty()) {
            container.runtime.update(ConnectionOutcome.NoAccounts.toRuntimeStatus(serviceRunning = false))
            return
        }
        if (container.config.keepAlive.value) KeepAliveService.start(getApplication()) else startNormalMode()
    }

    private fun startNormalMode() {
        if (!container.config.onboardingCompleted.value || container.coordinator.connectionsPaused || normalModeJob?.isActive == true ||
            container.accounts.accounts.value.isEmpty()) return
        normalModeJob = viewModelScope.launch {
            try {
                while (!container.config.keepAlive.value && container.accounts.accounts.value.isNotEmpty()) {
                    container.runtime.update(
                        RuntimeStatus(message = "正在检查校园网连接", phase = ConnectionPhase.CHECKING)
                    )
                    val outcome = container.coordinator.connectOnce()
                    container.runtime.update(outcome.toRuntimeStatus(serviceRunning = false))
                    when (outcome) {
                        is ConnectionOutcome.Connected -> {
                            if (outcome.alreadyConnected) {
                                container.logs.add("检测到网络已连接，应用保持打开")
                                return@launch
                            }
                            _uiState.update { it.copy(showFireworks = true) }
                            delay(3_000)
                            if (shouldAutoCloseAfterConnection(
                                    outcome = outcome,
                                    autoCloseEnabled = container.config.autoCloseEnabled.value,
                                    sessionAllowsAutoClose = autoClose,
                                    onboardingCompleted = container.config.onboardingCompleted.value
                                )) {
                                _events.emit(MainUiEvent.CloseApp)
                            } else {
                                container.logs.add("自动关闭已取消，应用保持打开")
                            }
                            return@launch
                        }
                        ConnectionOutcome.NoAccounts -> return@launch
                        else -> delay(3_000)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = "本轮连接已停止: ${error.message ?: "未知错误"}"
                container.logs.add("❌ $message")
                container.runtime.update(RuntimeStatus(message = message, phase = ConnectionPhase.PAUSED))
            } finally {
                if (container.accounts.accounts.value.isEmpty()) {
                    container.runtime.update(ConnectionOutcome.NoAccounts.toRuntimeStatus(serviceRunning = false))
                }
            }
        }
    }

    private fun interact(transform: (MainUiState) -> MainUiState) {
        autoClose = false
        _uiState.update(transform)
    }

    private fun reportOnboardingPending() {
        container.runtime.update(
            if (container.accounts.accounts.value.isEmpty()) {
                ConnectionOutcome.NoAccounts.toRuntimeStatus(serviceRunning = false)
            } else {
                RuntimeStatus(message = "请完成校区与自动关闭设置后开始使用", phase = ConnectionPhase.PAUSED)
            }
        )
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
            container.config.autoCloseEnabled.collect { enabled -> _uiState.update { it.copy(autoCloseEnabled = enabled) } }
        }
        viewModelScope.launch {
            container.config.onboardingCompleted.collect { completed -> _uiState.update { it.copy(showOnboarding = !completed) } }
        }
        viewModelScope.launch {
            container.config.themeMode.collect { mode -> _uiState.update { it.copy(themeMode = mode) } }
        }
        viewModelScope.launch {
            container.runtime.status.collect { status -> _uiState.update { it.copy(runtimeStatus = status) } }
        }
    }
}

internal fun shouldAutoCloseAfterConnection(
    outcome: ConnectionOutcome,
    autoCloseEnabled: Boolean,
    sessionAllowsAutoClose: Boolean = true,
    onboardingCompleted: Boolean = true
): Boolean = autoCloseEnabled && sessionAllowsAutoClose && onboardingCompleted &&
    outcome is ConnectionOutcome.Connected &&
    !outcome.alreadyConnected

internal fun shouldPromptRestartAfterLogout(outcome: LogoutOutcome): Boolean = outcome.sessionLoggedOut
