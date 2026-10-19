package top.onering.easyconnecter.domain

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.onering.easyconnecter.data.AccountStore
import top.onering.easyconnecter.data.ConfigStore
import top.onering.easyconnecter.data.EventLogger
import top.onering.easyconnecter.model.Account
import top.onering.easyconnecter.model.Campus
import top.onering.easyconnecter.model.Carrier
import top.onering.easyconnecter.model.ConnectionOutcome
import top.onering.easyconnecter.model.LoginOutcome
import top.onering.easyconnecter.model.LogoutOutcome
import top.onering.easyconnecter.model.PendingRecovery
import top.onering.easyconnecter.model.SessionStatus
import top.onering.easyconnecter.network.NetworkGateway

class ConnectionCoordinator(
    private val accountRepository: AccountStore,
    private val configRepository: ConfigStore,
    private val logRepository: EventLogger,
    private val networkGateway: NetworkGateway,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {
    private val operationMutex = Mutex()
    private var lastLoginStartedAt = Long.MIN_VALUE
    private var recoveryAllowedAt = Long.MIN_VALUE
    @Volatile private var connectionsEnabled = true
    val connectionsPaused: Boolean get() = !connectionsEnabled

    fun pauseConnections() {
        connectionsEnabled = false
    }

    suspend fun awaitIdle() = operationMutex.withLock {}

    fun resumeConnections() {
        connectionsEnabled = true
    }

    suspend fun connectOnce(): ConnectionOutcome = operationMutex.withLock {
        try {
            connectLocked()
        } catch (_: ConnectionPausedException) {
            ConnectionOutcome.Failed("连接已暂停")
        } catch (error: RecoveryPersistenceException) {
            deferRecovery("无法保存或读取待恢复会话，停止本轮登录和注销: ${error.cause?.message ?: "未知错误"}")
        }
    }

    private suspend fun connectLocked(): ConnectionOutcome {
        ensureConnectionsEnabled()
        if (hasInternetConnectivity()) {
            recoveryAllowedAt = Long.MIN_VALUE
            savePendingRecovery(null)
            logRepository.add("✅ 网络正常")
            return ConnectionOutcome.Connected(null, null, alreadyConnected = true)
        }

        val accounts = accountRepository.accounts.value
        if (accounts.isEmpty()) return ConnectionOutcome.NoAccounts
        if (nowMillis() < recoveryAllowedAt) {
            return ConnectionOutcome.Failed("校园网恢复暂缓，稍后重新检查会话")
        }

        val index = configRepository.currentIndex.value.coerceIn(accounts.indices)
        if (index != configRepository.currentIndex.value) configRepository.setCurrentIndex(index)
        val campus = configRepository.campus.value
        val savedRecovery = readPendingRecovery()
        val pending = savedRecovery?.takeIf { it.campus == campus && accounts.any { account -> account.studentId == it.studentId } }
        if (savedRecovery != pending) savePendingRecovery(null)
        var account = pending?.let { recovery -> accounts.first { it.studentId == recovery.studentId } } ?: accounts[index]
        var carrier = pending?.carrier ?: configRepository.preferredCarrier(account.studentId) ?: Carrier.MOBILE
        var triedAlternateCarrier = false
        var recoveringSession = pending != null
        var advanceIndexOnSuccess = pending == null
        var recoveryCount = 0
        var rateLimitRetries = 0

        ensureConnectionsEnabled()
        val initialStatus = networkGateway.checkStatus(campus).getOrElse {
            return deferRecovery("无法确认校园网会话状态，暂不重复登录: ${it.message ?: "未知错误"}")
        }
        ensureConnectionsEnabled()
        if (initialStatus.loggedIn) {
            if (hasInternetConnectivity()) {
                savePendingRecovery(null)
                return ConnectionOutcome.Connected(null, null, alreadyConnected = true)
            }
            val activeAccount = accounts.find { it.studentId == initialStatus.account }
            if (activeAccount == null) {
                savePendingRecovery(null)
                return deferRecovery("在线会话账号不在本地，保留会话并停止自动恢复")
            }
            if (activeAccount.studentId != account.studentId) {
                account = activeAccount
                carrier = configRepository.preferredCarrier(activeAccount.studentId) ?: Carrier.MOBILE
            }
            recoveringSession = true
            advanceIndexOnSuccess = false
            savePendingRecovery(PendingRecovery(campus, account.studentId, carrier))
            logRepository.add("⚠️ 校园网会话在线但外网不可用，先清理并确认离线")
            val cleanup = logoutAndUnbindLocked(campus, Result.success(initialStatus), automatic = true)
            if (!cleanup.sessionLoggedOut) return deferRecovery(cleanup.message)
            recoveryCount++
            waitForRecovery(recoveryCount)
        }

        while (true) {
            enforceLoginInterval()
            ensureConnectionsEnabled()
            logRepository.add("尝试${carrier.displayName}登录(${campus.displayName}): ${account.displayLabel()}")
            lastLoginStartedAt = nowMillis()
            val outcome = networkGateway.login(account.studentId, account.password, campus, carrier)
            ensureConnectionsEnabled()
            when (outcome) {
                is LoginOutcome.Success, is LoginOutcome.ClientIpOnline -> {
                    if (hasInternetConnectivity()) {
                        recoveryAllowedAt = Long.MIN_VALUE
                        savePendingRecovery(null)
                        if (outcome is LoginOutcome.ClientIpOnline) {
                            logRepository.add("✅ clientip online，但外网正常，保留现有会话")
                            return ConnectionOutcome.Connected(null, null, alreadyConnected = true)
                        }
                        configRepository.recordSuccessfulLogin(account.studentId, carrier)
                        if (advanceIndexOnSuccess) configRepository.setCurrentIndex((index + 1) % accounts.size)
                        logRepository.add("✅ ${carrier.displayName}登录成功，已确认外网可用")
                        return ConnectionOutcome.Connected(account, carrier, alreadyConnected = false)
                    }

                    logRepository.add(if (outcome is LoginOutcome.ClientIpOnline) {
                        "⚠️ clientip online 且外网不可用，检查当前会话"
                    } else {
                        "⚠️ 登录响应成功，但外网仍不可用，检查当前会话"
                    })
                    // Keep the attempted identity even if the status response is lost.
                    savePendingRecovery(PendingRecovery(campus, account.studentId, carrier))
                    ensureConnectionsEnabled()
                    val status = networkGateway.checkStatus(campus).getOrElse {
                        return deferRecovery("无法确认校园网会话状态，停止本轮恢复: ${it.message ?: "未知错误"}")
                    }
                    ensureConnectionsEnabled()
                    if (status.loggedIn) {
                        val activeAccount = accounts.find { it.studentId == status.account }
                        if (activeAccount == null) {
                            savePendingRecovery(null)
                            return deferRecovery("在线会话账号不在本地，保留会话并停止自动恢复")
                        }
                        if (activeAccount.studentId != account.studentId) {
                            account = activeAccount
                            carrier = configRepository.preferredCarrier(activeAccount.studentId) ?: Carrier.MOBILE
                        }
                        advanceIndexOnSuccess = false
                    }
                    recoveringSession = true
                    savePendingRecovery(PendingRecovery(campus, account.studentId, carrier))
                    if (recoveryCount >= MAX_CLIENT_IP_ONLINE_RETRIES) {
                        return deferRecovery("校园网会话冲突或外网故障仍存在，停止本轮恢复，保留当前账号")
                    }
                    val cleanup = logoutAndUnbindLocked(campus, Result.success(status), automatic = true)
                    if (!cleanup.sessionLoggedOut) return deferRecovery(cleanup.message)
                    recoveryCount++
                    rateLimitRetries = 0
                    waitForRecovery(recoveryCount)
                }

                is LoginOutcome.InvalidCredentials, is LoginOutcome.OperatorNotBound -> {
                    if (recoveringSession) return deferRecovery("${outcome.message}，保留待恢复账号并停止本轮重试")
                    logRepository.add("❌ ${outcome.message}，删除账号: ${account.displayLabel()}")
                    removeAccountLocked(accounts.indexOf(account), account)
                    return ConnectionOutcome.AccountRemoved(account)
                }

                is LoginOutcome.RateLimited -> {
                    val waitMillis = outcome.waitSeconds.coerceAtLeast(MIN_LOGIN_INTERVAL_SECONDS) * 1_000L
                    if (rateLimitRetries >= MAX_RATE_LIMIT_RETRIES) {
                        return deferRecovery("${outcome.message}，本轮停止重试，保留当前账号", waitMillis)
                    }
                    rateLimitRetries++
                    logRepository.add("⚠️ 请求过快，${waitMillis / 1_000} 秒后重试${carrier.displayName}")
                    sleep(waitMillis)
                    ensureConnectionsEnabled()
                }

                is LoginOutcome.NetworkError -> {
                    logRepository.add("❌ ${outcome.message}")
                    return if (recoveringSession) deferRecovery(outcome.message) else ConnectionOutcome.Failed(outcome.message)
                }

                is LoginOutcome.ProtocolError -> {
                    logRepository.add("❌ ${outcome.message}")
                    if (recoveringSession) return deferRecovery(outcome.message)
                    if (triedAlternateCarrier) {
                        configRepository.setCurrentIndex((index + 1) % accounts.size)
                        return ConnectionOutcome.Failed("账号 ${account.displayLabel()} 的移动和电信登录均失败")
                    }
                    carrier = carrier.alternate()
                    triedAlternateCarrier = true
                    rateLimitRetries = 0
                }
            }
        }
    }

    suspend fun runKeepAlive(
        onChecking: suspend () -> Unit = {},
        onOutcome: suspend (ConnectionOutcome) -> Unit = {}
    ) {
        while (currentCoroutineContext().isActive && configRepository.keepAlive.value) {
            val outcome = if (accountRepository.accounts.value.isEmpty()) {
                ConnectionOutcome.NoAccounts
            } else {
                onChecking()
                connectOnce()
            }
            onOutcome(outcome)
            val waitMillis = when (outcome) {
                is ConnectionOutcome.Connected -> KEEP_ALIVE_INTERVAL_MILLIS
                ConnectionOutcome.NoAccounts -> return
                else -> MIN_LOGIN_INTERVAL_MILLIS
            }
            sleep(waitMillis)
        }
    }

    suspend fun replaceAccounts(accounts: List<Account>) = operationMutex.withLock {
        savePendingRecovery(null)
        accountRepository.replace(accounts)
        configRepository.setCurrentIndex(0)
        configRepository.clearLoginPreferences()
        recoveryAllowedAt = Long.MIN_VALUE
    }

    suspend fun removeAccount(index: Int): Account? = operationMutex.withLock {
        val account = accountRepository.accounts.value.getOrNull(index) ?: return@withLock null
        removeAccountLocked(index, account)
        account
    }

    suspend fun changeCampus(campus: Campus) = operationMutex.withLock {
        savePendingRecovery(null)
        configRepository.setCampus(campus)
        recoveryAllowedAt = Long.MIN_VALUE
    }

    suspend fun logoutAndUnbind(): LogoutOutcome = operationMutex.withLock {
        try {
            savePendingRecovery(null)
        } catch (error: RecoveryPersistenceException) {
            val message = "无法清除待恢复会话，暂未注销: ${error.cause?.message ?: "未知错误"}"
            logRepository.add("❌ $message")
            return@withLock LogoutOutcome(false, false, null, false, message)
        }
        logoutAndUnbindLocked(configRepository.campus.value).also {
            if (it.sessionLoggedOut) recoveryAllowedAt = Long.MIN_VALUE
        }
    }

    private suspend fun logoutAndUnbindLocked(
        campus: Campus,
        knownStatus: Result<SessionStatus>? = null,
        automatic: Boolean = false
    ): LogoutOutcome {
        suspend fun ensureOperationEnabled() {
            currentCoroutineContext().ensureActive()
            if (automatic) ensureConnectionsEnabled()
        }

        ensureOperationEnabled()
        val warnings = mutableListOf<String>()
        val initialStatusResult = knownStatus ?: networkGateway.checkStatus(campus)
        ensureOperationEnabled()
        val initialStatus = initialStatusResult.getOrNull()

        if (initialStatusResult.isFailure) {
            warnings += "注销前状态检查失败: ${initialStatusResult.exceptionOrNull()?.message ?: "未知错误"}"
        } else if (initialStatus?.loggedIn == false) {
            logRepository.add("校园网当前已注销")
            return LogoutOutcome(
                sessionLoggedOut = true,
                unbindAttempted = false,
                unbindSucceeded = null,
                logoutRequestSucceeded = false,
                message = "校园网当前已注销"
            )
        }

        val account = initialStatus?.account ?: configRepository.lastSuccessfulStudentId()
        val normalizedMac = normalizeMac(initialStatus?.mac)
        val decimalIpv4 = initialStatus?.ipv4?.let(::ipv4ToDecimal)
        val canUnbind = account != null && normalizedMac != null && decimalIpv4 != null
        var unbindSucceeded: Boolean? = null

        if (canUnbind) {
            ensureOperationEnabled()
            val result = networkGateway.unbindMac(campus, account, normalizedMac, decimalIpv4)
            ensureOperationEnabled()
            unbindSucceeded = result.isSuccess
            if (result.isSuccess) {
                logRepository.add("已解除校园网 MAC 绑定")
            } else {
                val warning = "MAC 解绑失败: ${result.exceptionOrNull()?.message ?: "未知错误"}"
                warnings += warning
                logRepository.add("⚠️ $warning，继续注销会话")
            }
        } else {
            val missing = buildList {
                if (account == null) add("账号")
                if (normalizedMac == null) add("MAC")
                if (decimalIpv4 == null) add("IPv4")
            }.joinToString("、")
            val warning = "缺少$missing，跳过 MAC 解绑"
            warnings += warning
            logRepository.add("⚠️ $warning")
        }

        ensureOperationEnabled()
        val logoutResult = networkGateway.logout(campus)
        ensureOperationEnabled()
        if (logoutResult.isFailure) {
            val warning = "注销请求失败: ${logoutResult.exceptionOrNull()?.message ?: "未知错误"}"
            warnings += warning
            logRepository.add("❌ $warning")
        }

        var confirmedLoggedOut = false
        for (attempt in 0 until STATUS_CONFIRM_ATTEMPTS) {
            if (attempt > 0) sleep(STATUS_CONFIRM_DELAY_MILLIS)
            ensureOperationEnabled()
            val status = networkGateway.checkStatus(campus).getOrNull()
            ensureOperationEnabled()
            if (status?.loggedIn == false) {
                confirmedLoggedOut = true
                break
            }
        }

        val message = when {
            !confirmedLoggedOut -> "未能确认校园网已注销"
            logoutResult.isFailure -> "已确认离线，但注销请求报告失败"
            unbindSucceeded == false -> "校园网已注销，但 MAC 解绑失败"
            !canUnbind -> "校园网已注销，但未执行 MAC 解绑"
            else -> "校园网已解绑并注销"
        }
        logRepository.add(if (confirmedLoggedOut) "✅ $message" else "❌ $message")
        return LogoutOutcome(
            sessionLoggedOut = confirmedLoggedOut,
            unbindAttempted = canUnbind,
            unbindSucceeded = unbindSucceeded,
            logoutRequestSucceeded = logoutResult.isSuccess,
            message = message,
            warnings = warnings
        )
    }

    private suspend fun removeAccountLocked(index: Int, account: Account) {
        if (readPendingRecovery()?.studentId == account.studentId) savePendingRecovery(null)
        val oldIndex = configRepository.currentIndex.value
        accountRepository.removeAt(index)
        configRepository.removeAccountPreferences(account.studentId)
        val remainingSize = accountRepository.accounts.value.size
        val adjustedIndex = when {
            remainingSize == 0 -> 0
            index < oldIndex -> oldIndex - 1
            oldIndex >= remainingSize -> 0
            else -> oldIndex
        }
        configRepository.setCurrentIndex(adjustedIndex)
    }

    private suspend fun enforceLoginInterval() {
        ensureConnectionsEnabled()
        if (lastLoginStartedAt == Long.MIN_VALUE) return
        val elapsed = nowMillis() - lastLoginStartedAt
        if (elapsed < MIN_LOGIN_INTERVAL_MILLIS) sleep(MIN_LOGIN_INTERVAL_MILLIS - elapsed)
        ensureConnectionsEnabled()
    }

    private suspend fun hasInternetConnectivity(): Boolean {
        repeat(CONNECTIVITY_PROBE_ATTEMPTS) {
            ensureConnectionsEnabled()
            val connected = networkGateway.isInternetConnected()
            ensureConnectionsEnabled()
            if (connected) return true
        }
        return false
    }

    private suspend fun ensureConnectionsEnabled() {
        currentCoroutineContext().ensureActive()
        if (!connectionsEnabled) throw ConnectionPausedException()
    }

    private suspend fun waitForRecovery(attempt: Int) {
        val waitMillis = attempt * CLIENT_IP_ONLINE_RETRY_STEP_MILLIS
        logRepository.add("已确认校园网离线，${waitMillis / 1_000} 秒后重试同一会话账号（第 $attempt 次恢复）")
        sleep(waitMillis)
        ensureConnectionsEnabled()
    }

    private fun deferRecovery(message: String, minimumDelayMillis: Long = 0): ConnectionOutcome.Failed {
        recoveryAllowedAt = nowMillis() + maxOf(RECOVERY_COOLDOWN_MILLIS, minimumDelayMillis)
        logRepository.add("⚠️ $message")
        return ConnectionOutcome.Failed(message)
    }

    private class ConnectionPausedException : IllegalStateException()

    private suspend fun readPendingRecovery(): PendingRecovery? = try {
        configRepository.pendingRecovery()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        throw RecoveryPersistenceException(error)
    }

    private suspend fun savePendingRecovery(recovery: PendingRecovery?) {
        try {
            configRepository.setPendingRecovery(recovery)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw RecoveryPersistenceException(error)
        }
    }

    private class RecoveryPersistenceException(cause: Exception) : IllegalStateException(cause)

    internal fun ipv4ToDecimal(ipv4: String): String? {
        val octets = ipv4.split('.').map { it.toIntOrNull() ?: return null }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return null
        return octets.fold(0L) { value, octet -> (value shl 8) + octet }.toString()
    }

    internal fun normalizeMac(mac: String?): String? {
        val normalized = mac?.filter(Char::isLetterOrDigit)?.uppercase() ?: return null
        return normalized.takeIf { it.length == 12 && it.all { char -> char in '0'..'9' || char in 'A'..'F' } }
    }

    private fun Account.displayLabel(): String = name.ifBlank { studentId }

    private companion object {
        const val MIN_LOGIN_INTERVAL_SECONDS = 3
        const val MIN_LOGIN_INTERVAL_MILLIS = MIN_LOGIN_INTERVAL_SECONDS * 1_000L
        const val KEEP_ALIVE_INTERVAL_MILLIS = 60_000L
        const val CLIENT_IP_ONLINE_RETRY_STEP_MILLIS = 10_000L
        const val MAX_CLIENT_IP_ONLINE_RETRIES = 6
        const val MAX_RATE_LIMIT_RETRIES = 2
        const val RECOVERY_COOLDOWN_MILLIS = 60_000L
        const val CONNECTIVITY_PROBE_ATTEMPTS = 5
        const val STATUS_CONFIRM_ATTEMPTS = 3
        const val STATUS_CONFIRM_DELAY_MILLIS = 1_000L
    }
}
