package top.onering.easyconnecter.domain

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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

    suspend fun connectOnce(): ConnectionOutcome = operationMutex.withLock {
        if (hasInternetConnectivity()) {
            logRepository.add("✅ 网络正常")
            return@withLock ConnectionOutcome.Connected(null, null, alreadyConnected = true)
        }

        val accounts = accountRepository.accounts.value
        if (accounts.isEmpty()) return@withLock ConnectionOutcome.NoAccounts

        val index = configRepository.currentIndex.value.coerceIn(accounts.indices)
        if (index != configRepository.currentIndex.value) configRepository.setCurrentIndex(index)
        val account = accounts[index]
        val preferredCarrier = configRepository.preferredCarrier(account.studentId)
        val carriers = if (preferredCarrier == null) {
            listOf(Carrier.MOBILE, Carrier.TELECOM)
        } else {
            listOf(preferredCarrier, preferredCarrier.alternate())
        }

        for (carrier in carriers) {
            var clientIpOnlineRetryCount = 0
            var rateLimitRetried = false
            while (true) {
                enforceLoginInterval()
                logRepository.add("尝试${carrier.displayName}登录(${configRepository.campus.value.displayName}): ${account.displayLabel()}")
                lastLoginStartedAt = nowMillis()
                when (val outcome = networkGateway.login(
                    account.studentId,
                    account.password,
                    configRepository.campus.value,
                    carrier
                )) {
                    is LoginOutcome.Success -> {
                        configRepository.recordSuccessfulLogin(account.studentId, carrier)
                        val nextIndex = (index + 1) % accounts.size
                        configRepository.setCurrentIndex(nextIndex)
                        logRepository.add("✅ ${carrier.displayName}登录成功")
                        return@withLock ConnectionOutcome.Connected(account, carrier, alreadyConnected = false)
                    }

                    is LoginOutcome.InvalidCredentials -> {
                        logRepository.add("❌ ${outcome.message}，删除账号: ${account.displayLabel()}")
                        removeAccountLocked(index, account)
                        return@withLock ConnectionOutcome.AccountRemoved(account)
                    }

                    is LoginOutcome.OperatorNotBound -> {
                        logRepository.add("❌ ${outcome.message}，删除账号: ${account.displayLabel()}")
                        removeAccountLocked(index, account)
                        return@withLock ConnectionOutcome.AccountRemoved(account)
                    }

                    is LoginOutcome.ClientIpOnline -> {
                        if (clientIpOnlineRetryCount >= MAX_CLIENT_IP_ONLINE_RETRIES) {
                            logRepository.add(
                                "⚠️ clientip online 在等待 60 秒重试后仍存在，" +
                                    "停止尝试${carrier.displayName}"
                            )
                            break
                        }
                        clientIpOnlineRetryCount++
                        val waitMillis = clientIpOnlineRetryCount * CLIENT_IP_ONLINE_RETRY_STEP_MILLIS
                        logRepository.add(
                            "⚠️ 检测到 clientip online，第 $clientIpOnlineRetryCount 次注销当前会话，" +
                                "${waitMillis / 1_000} 秒后重试${carrier.displayName}"
                        )
                        val logoutResult = networkGateway.logout(configRepository.campus.value)
                        if (logoutResult.isSuccess) {
                            logRepository.add("已请求注销当前校园网会话")
                        } else {
                            logRepository.add(
                                "⚠️ 校园网注销请求失败: " +
                                    (logoutResult.exceptionOrNull()?.message ?: "未知错误") +
                                    "，仍按计划等待后重试"
                            )
                        }
                        sleep(waitMillis)
                    }

                    is LoginOutcome.RateLimited -> {
                        if (rateLimitRetried) {
                            logRepository.add("⚠️ ${outcome.message}，本轮不再重试${carrier.displayName}")
                            break
                        }
                        rateLimitRetried = true
                        val waitMillis = outcome.waitSeconds.coerceAtLeast(MIN_LOGIN_INTERVAL_SECONDS) * 1_000L
                        logRepository.add("⚠️ 请求过快，${waitMillis / 1_000} 秒后重试${carrier.displayName}")
                        sleep(waitMillis)
                    }

                    is LoginOutcome.NetworkError -> {
                        logRepository.add("❌ ${outcome.message}")
                        return@withLock ConnectionOutcome.Failed(outcome.message)
                    }

                    is LoginOutcome.ProtocolError -> {
                        logRepository.add("❌ ${outcome.message}")
                        break
                    }
                }
            }
        }

        val nextIndex = (index + 1) % accounts.size
        configRepository.setCurrentIndex(nextIndex)
        ConnectionOutcome.Failed("账号 ${account.displayLabel()} 的移动和电信登录均失败")
    }

    suspend fun runKeepAlive(onOutcome: suspend (ConnectionOutcome) -> Unit = {}) {
        while (currentCoroutineContext().isActive && configRepository.keepAlive.value) {
            val outcome = if (accountRepository.accounts.value.isEmpty()) {
                ConnectionOutcome.NoAccounts
            } else {
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
        accountRepository.replace(accounts)
        configRepository.setCurrentIndex(0)
        configRepository.clearLoginPreferences()
    }

    suspend fun removeAccount(index: Int): Account? = operationMutex.withLock {
        val account = accountRepository.accounts.value.getOrNull(index) ?: return@withLock null
        removeAccountLocked(index, account)
        account
    }

    suspend fun changeCampus(campus: Campus) = operationMutex.withLock {
        configRepository.setCampus(campus)
    }

    suspend fun logoutAndUnbind(): LogoutOutcome = operationMutex.withLock {
        val campus = configRepository.campus.value
        val warnings = mutableListOf<String>()
        val initialStatusResult = networkGateway.checkStatus(campus)
        val initialStatus = initialStatusResult.getOrNull()

        if (initialStatusResult.isFailure) {
            warnings += "注销前状态检查失败: ${initialStatusResult.exceptionOrNull()?.message ?: "未知错误"}"
        } else if (initialStatus?.loggedIn == false) {
            logRepository.add("校园网当前已注销")
            return@withLock LogoutOutcome(
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
            val result = networkGateway.unbindMac(campus, account, normalizedMac, decimalIpv4)
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

        val logoutResult = networkGateway.logout(campus)
        if (logoutResult.isFailure) {
            val warning = "注销请求失败: ${logoutResult.exceptionOrNull()?.message ?: "未知错误"}"
            warnings += warning
            logRepository.add("❌ $warning")
        }

        var confirmedLoggedOut = false
        for (attempt in 0 until STATUS_CONFIRM_ATTEMPTS) {
            if (attempt > 0) sleep(STATUS_CONFIRM_DELAY_MILLIS)
            val status = networkGateway.checkStatus(campus).getOrNull()
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
        LogoutOutcome(
            sessionLoggedOut = confirmedLoggedOut,
            unbindAttempted = canUnbind,
            unbindSucceeded = unbindSucceeded,
            logoutRequestSucceeded = logoutResult.isSuccess,
            message = message,
            warnings = warnings
        )
    }

    private suspend fun removeAccountLocked(index: Int, account: Account) {
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
        if (lastLoginStartedAt == Long.MIN_VALUE) return
        val elapsed = nowMillis() - lastLoginStartedAt
        if (elapsed < MIN_LOGIN_INTERVAL_MILLIS) sleep(MIN_LOGIN_INTERVAL_MILLIS - elapsed)
    }

    private suspend fun hasInternetConnectivity(): Boolean {
        repeat(CONNECTIVITY_PROBE_ATTEMPTS) {
            if (networkGateway.isInternetConnected()) return true
        }
        return false
    }

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
        const val CONNECTIVITY_PROBE_ATTEMPTS = 5
        const val STATUS_CONFIRM_ATTEMPTS = 3
        const val STATUS_CONFIRM_DELAY_MILLIS = 1_000L
    }
}
