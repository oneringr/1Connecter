package top.onering.easyconnecter.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.onering.easyconnecter.data.AccountRepository
import top.onering.easyconnecter.data.AccountStore
import top.onering.easyconnecter.data.ConfigRepository
import top.onering.easyconnecter.data.ConfigStore
import top.onering.easyconnecter.data.EventLogger
import top.onering.easyconnecter.domain.ConnectionCoordinator
import top.onering.easyconnecter.model.Account
import top.onering.easyconnecter.model.Campus
import top.onering.easyconnecter.model.Carrier
import top.onering.easyconnecter.model.ConnectionOutcome
import top.onering.easyconnecter.model.LoginOutcome
import top.onering.easyconnecter.model.PendingRecovery
import top.onering.easyconnecter.model.SessionStatus
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Opt-in, disruptive live campus tests: pass `-e liveCampus true` and stop the app's
 * existing service before running this class. No Activity is launched and no app
 * account/configuration is written. The campus, active account and remembered
 * carrier are read from existing app data; credentials never enter test output.
 *
 * The two collision tests inject only a stale initial status and connectivity
 * failure, representing a check/login race. Every login response, cleanup request,
 * subsequent status and final HTTPS request comes from the real network. These
 * tests do not claim to reproduce a naturally occurring persistent outage.
 */
@RunWith(AndroidJUnit4::class)
class ClientIpRecoveryDeviceTest {
    @Test
    fun existingHealthySessionDoesNotLoginOrLogout() = runLiveScenario(Scenario.NORMAL)

    @Test
    fun realClientIpOnlineWithHealthyInternetPreservesSession() = runLiveScenario(Scenario.HEALTHY_COLLISION)

    @Test
    fun realClientIpOnlineWithInjectedOutageAutomaticallyRecovers() = runLiveScenario(Scenario.RECOVERY)

    private fun runLiveScenario(scenario: Scenario) = runBlocking {
        assumeTrue(
            "Live campus changes require explicit -e liveCampus true",
            InstrumentationRegistry.getArguments().getString("liveCampus") == "true"
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val wifi = manager.activeNetwork
        assertTrue("Live campus test requires Wi-Fi as the default network", wifi != null &&
            manager.getNetworkCapabilities(wifi)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true)
        val originalBoundNetwork = manager.boundNetworkForProcess
        var primaryFailure: Throwable? = null
        var summary: String? = null
        try {
            assertTrue("Test process must bind to the campus Wi-Fi network", manager.bindProcessToNetwork(wifi))
            // JUnit does not promise method order; avoid inheriting the preceding test's login limit.
            delay(3_000)
            summary = executeLiveScenario(scenario, context)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            if (!manager.bindProcessToNetwork(originalBoundNetwork)) {
                val restoreFailure = AssertionError("Could not restore the test process's original network binding")
                if (primaryFailure == null) throw restoreFailure
                primaryFailure.addSuppressed(restoreFailure)
            }
        }
        report(requireNotNull(summary))
    }

    private suspend fun executeLiveScenario(scenario: Scenario, context: Context): String {
        val originalFiles = CONFIG_FILES.associateWith { readFile(context, it) }
        val persistedConfig = ConfigRepository(context)
        val campus = persistedConfig.campus.value
        val client = DrcomClient()
        val initialStatus = withTimeout(30_000) { client.checkStatus(campus).getOrNull() }
        assertTrue("Precondition: campus must already have an active session", initialStatus?.loggedIn == true)
        val account = AccountRepository(context).accounts.value.singleOrNull {
            it.studentId == initialStatus?.account
        }
        assertTrue("Precondition: active campus account must exist uniquely in app data", account != null)
        account!!
        val carrier = persistedConfig.preferredCarrier(account.studentId)
        assertTrue("Precondition: active account must have a remembered successful carrier", carrier != null)
        carrier!!
        assertTrue("Precondition: actual HTTPS must work before fault injection", httpsIsReachable())

        val gateway = RecordingGateway(client, scenario)
        val config = MemoryConfig(campus, account.studentId, carrier)
        val accounts = MemoryAccounts(account)
        // Production messages may contain account labels; retain none in test output.
        val coordinator = ConnectionCoordinator(accounts, config, EventLogger { }, gateway)
        var primaryFailure: Throwable? = null
        try {
            val result = withTimeout(240_000) { coordinator.connectOnce() }
            assertTrue("Coordinator must return Connected", result is ConnectionOutcome.Connected)
            val connected = result as ConnectionOutcome.Connected
            assertTrue("The active account must remain in the isolated store", accounts.accounts.value == listOf(account))
            assertTrue("Actual HTTPS must work after the coordinator completes", httpsIsReachable())
            val finalStatus = client.checkStatus(campus).getOrNull()
            assertTrue("Final real status must remain online as the original account", finalStatus?.loggedIn == true && finalStatus.account == account.studentId)
            assertTrue("Every real login must use the original account and carrier", gateway.logins.all {
                it.account == account.studentId && it.carrier == carrier
            })

            when (scenario) {
                Scenario.NORMAL -> {
                    assertTrue("Existing internet must be recognized", connected.alreadyConnected)
                    assertEquals("Healthy startup must not authenticate", 0, gateway.logins.size)
                    assertEquals("Healthy startup must not logout", 0, gateway.logoutCount)
                    assertEquals("Healthy startup must not unbind", 0, gateway.unbindCount)
                }
                Scenario.HEALTHY_COLLISION -> {
                    assertTrue("The collision response must really come from campus", gateway.logins.firstOrNull()?.outcome is LoginOutcome.ClientIpOnline)
                    assertEquals("Healthy collision must not authenticate again", 1, gateway.logins.size)
                    assertTrue("Existing internet must be retained after collision", connected.alreadyConnected)
                    assertEquals("Healthy collision must not logout", 0, gateway.logoutCount)
                    assertEquals("Healthy collision must not unbind", 0, gateway.unbindCount)
                }
                Scenario.RECOVERY -> {
                    assertTrue("The collision response must really come from campus", gateway.logins.firstOrNull()?.outcome is LoginOutcome.ClientIpOnline)
                    assertTrue("Automatic recovery must perform an actual logout", gateway.logoutCount >= 1)
                    assertTrue("Real status must be read during cleanup", gateway.realStatusCount >= 2)
                    assertTrue("Automatic recovery must obtain a real successful login", gateway.logins.any { it.outcome is LoginOutcome.Success })
                    assertTrue("Recovery must return a newly connected session", !connected.alreadyConnected)
                    val retry = gateway.logins.drop(1).firstOrNull()
                    assertTrue("Recovery must retry the same login", retry != null)
                    val steps = gateway.steps
                    val collision = steps.indexOfFirst { it.kind == StepKind.CLIENT_IP_ONLINE }
                    val online = steps.indexOfFirst { it.kind == StepKind.STATUS_ONLINE }
                    val unbind = steps.indexOfFirst { it.kind == StepKind.UNBIND }
                    val logout = steps.indexOfFirst { it.kind == StepKind.LOGOUT }
                    val offline = steps.indexOfFirst { it.kind == StepKind.STATUS_OFFLINE }
                    val retryStart = steps.withIndex().firstOrNull {
                        it.index > collision && it.value.kind == StepKind.LOGIN_STARTED
                    }?.index ?: -1
                    assertTrue("Real online status must follow the real clientip response", collision >= 0 && online > collision)
                    assertTrue("Actual logout must follow the online status", logout > online)
                    if (canUnbind(steps[online].status!!)) {
                        assertTrue("Available session metadata must trigger unbind before logout", unbind > online && unbind < logout)
                    }
                    assertTrue("Real offline confirmation must follow actual logout", offline > logout)
                    assertTrue("The first recovery login must follow real offline confirmation", retryStart > offline)
                    assertTrue("Recovery must respect the three-second interval after logout", steps[retryStart].at - steps[logout].at >= 3_000)
                    assertTrue("Confirmed offline recovery must not retain the old ten-second backoff", steps[retryStart].at - steps[offline].at < 10_000)
                    gateway.logins.zipWithNext().forEach { (previous, next) ->
                        val rateLimit = previous.outcome as? LoginOutcome.RateLimited
                        if (rateLimit != null) {
                            assertTrue("Server rate-limit wait must be honored in real time", next.startedAt - previous.finishedAt >= rateLimit.waitSeconds.coerceAtLeast(3) * 1_000L)
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            report("${scenario.name}: " + gateway.events.joinToString("; "))
            try {
                // Do not leave the router's campus session offline when an assertion fails.
                withContext(NonCancellable) {
                    withTimeout(90_000) { restoreSessionIfNeeded(client, campus, account, carrier) }
                }
                CONFIG_FILES.forEach { name ->
                    val before = originalFiles[name]
                    val after = readFile(context, name)
                    assertTrue("Persistent app file must be unchanged: $name", if (before == null) after == null else after != null && before.contentEquals(after))
                }
            } catch (cleanupFailure: Throwable) {
                if (primaryFailure == null) throw cleanupFailure
                primaryFailure.addSuppressed(cleanupFailure)
                report("${scenario.name}: cleanup failed (${cleanupFailure.javaClass.simpleName}); original test failure preserved")
            }
        }
        return "${scenario.name}: PASS; wifi_bound=true; real_https=true; original_session_account=true; persistent_files_unchanged=true; " +
            "logins=${gateway.logins.size}; logout=${gateway.logoutCount}; unbind=${gateway.unbindCount}; " +
            "real_status=${gateway.realStatusCount}; rate_limited=${gateway.logins.count { it.outcome is LoginOutcome.RateLimited }}"
    }

    private suspend fun restoreSessionIfNeeded(client: DrcomClient, campus: Campus, account: Account, carrier: Carrier) {
        var status: SessionStatus? = null
        for (attempt in 0 until 6) {
            if (attempt > 0) delay(1_000)
            status = client.checkStatus(campus).getOrNull()
            if (status?.loggedIn == true && status.account == account.studentId && httpsIsReachable()) return
            if (status?.loggedIn == false) break
        }
        assertTrue("Cleanup cannot safely restore an unknown or different active campus session", status?.loggedIn == false)
        delay(3_000)
        repeat(3) {
            when (val outcome = client.login(account.studentId, account.password, campus, carrier)) {
                is LoginOutcome.Success -> {
                    assertTrue("Cleanup restored login but HTTPS remains unavailable", httpsIsReachable())
                    report("cleanup: original session restored")
                    return
                }
                is LoginOutcome.RateLimited -> delay(maxOf(outcome.waitSeconds.toLong() * 1_000L, if (it == 0) 6_000L else 12_000L))
                is LoginOutcome.ClientIpOnline -> {
                    val latest = client.checkStatus(campus).getOrNull()
                    if (latest?.loggedIn == true && latest.account == account.studentId && httpsIsReachable()) return
                    throw AssertionError("Cleanup encountered an online session without verified internet")
                }
                else -> throw AssertionError("Cleanup failed with ${outcome.javaClass.simpleName}")
            }
        }
        throw AssertionError("Cleanup exhausted its bounded login attempts")
    }

    private class RecordingGateway(private val delegate: DrcomClient, private val scenario: Scenario) : NetworkGateway {
        val logins = mutableListOf<LoginRecord>()
        val events = mutableListOf<String>()
        val steps = mutableListOf<NetworkStep>()
        var logoutCount = 0
        var unbindCount = 0
        var realStatusCount = 0
        private var initialStatusInjected = false
        private val startedAt = SystemClock.elapsedRealtime()

        private fun event(message: String) { events += "${SystemClock.elapsedRealtime() - startedAt}ms:$message" }

        override suspend fun isInternetConnected(): Boolean {
            val injectFailure = when (scenario) {
                Scenario.NORMAL -> false
                Scenario.HEALTHY_COLLISION -> logins.isEmpty()
                Scenario.RECOVERY -> logins.none { it.outcome is LoginOutcome.Success }
            }
            return if (injectFailure) false else delegate.isInternetConnected()
        }

        override suspend fun login(studentId: String, password: String, campus: Campus, carrier: Carrier): LoginOutcome {
            val start = SystemClock.elapsedRealtime()
            steps += NetworkStep(StepKind.LOGIN_STARTED, start)
            val result = delegate.login(studentId, password, campus, carrier)
            logins += LoginRecord(studentId, carrier, result, start, SystemClock.elapsedRealtime())
            if (result is LoginOutcome.ClientIpOnline) {
                steps += NetworkStep(StepKind.CLIENT_IP_ONLINE, SystemClock.elapsedRealtime())
            }
            event("real_login=${result.javaClass.simpleName}")
            return result
        }

        override suspend fun checkStatus(campus: Campus): Result<SessionStatus> {
            if (scenario != Scenario.NORMAL && !initialStatusInjected) {
                initialStatusInjected = true
                event("injected_initial_status=offline")
                return Result.success(SessionStatus(false, null, null, null))
            }
            realStatusCount++
            val result = delegate.checkStatus(campus)
            result.getOrNull()?.let { status ->
                steps += NetworkStep(if (status.loggedIn) StepKind.STATUS_ONLINE else StepKind.STATUS_OFFLINE,
                    SystemClock.elapsedRealtime(), status)
            }
            event("real_status=" + when (result.getOrNull()?.loggedIn) { true -> "online"; false -> "offline"; null -> "unknown" })
            return result
        }

        override suspend fun unbindMac(campus: Campus, account: String, mac: String, decimalIpv4: String): Result<Unit> {
            unbindCount++
            val result = delegate.unbindMac(campus, account, mac, decimalIpv4)
            steps += NetworkStep(StepKind.UNBIND, SystemClock.elapsedRealtime())
            event("real_unbind_success=${result.isSuccess}")
            return result
        }

        override suspend fun logout(campus: Campus): Result<Unit> {
            logoutCount++
            val result = delegate.logout(campus)
            steps += NetworkStep(StepKind.LOGOUT, SystemClock.elapsedRealtime())
            event("real_logout_success=${result.isSuccess}")
            return result
        }
    }

    private class MemoryAccounts(account: Account) : AccountStore {
        override val accounts = MutableStateFlow(listOf(account))
        override suspend fun replace(accounts: List<Account>) { this.accounts.value = accounts }
        override suspend fun removeAt(index: Int): Account? {
            val removed = accounts.value.getOrNull(index) ?: return null
            accounts.value = accounts.value.toMutableList().also { it.removeAt(index) }
            return removed
        }
    }

    private class MemoryConfig(initialCampus: Campus, account: String, carrier: Carrier) : ConfigStore {
        override val currentIndex = MutableStateFlow(0)
        override val campus = MutableStateFlow(initialCampus)
        override val keepAlive = MutableStateFlow(false)
        private val preferences = mutableMapOf(account to carrier)
        private var lastAccount: String? = account
        private var recovery: PendingRecovery? = null
        override suspend fun setCurrentIndex(index: Int) { currentIndex.value = index }
        override suspend fun setCampus(campus: Campus) { this.campus.value = campus }
        override suspend fun setKeepAlive(enabled: Boolean) { keepAlive.value = enabled }
        override suspend fun preferredCarrier(studentId: String): Carrier? = preferences[studentId]
        override suspend fun recordSuccessfulLogin(studentId: String, carrier: Carrier) { preferences[studentId] = carrier; lastAccount = studentId }
        override suspend fun lastSuccessfulStudentId(): String? = lastAccount
        override suspend fun pendingRecovery(): PendingRecovery? = recovery
        override suspend fun setPendingRecovery(recovery: PendingRecovery?) { this.recovery = recovery }
        override suspend fun removeAccountPreferences(studentId: String) { preferences.remove(studentId); if (lastAccount == studentId) lastAccount = null }
        override suspend fun clearLoginPreferences() { preferences.clear(); lastAccount = null }
    }

    private data class LoginRecord(val account: String, val carrier: Carrier, val outcome: LoginOutcome, val startedAt: Long, val finishedAt: Long)
    private data class NetworkStep(val kind: StepKind, val at: Long, val status: SessionStatus? = null)
    private enum class StepKind { LOGIN_STARTED, CLIENT_IP_ONLINE, STATUS_ONLINE, UNBIND, LOGOUT, STATUS_OFFLINE }
    private enum class Scenario { NORMAL, HEALTHY_COLLISION, RECOVERY }

    private suspend fun httpsIsReachable(): Boolean = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS).followRedirects(false).build()
        try {
            client.newCall(Request.Builder().url("https://www.baidu.com/").build()).execute().use {
                val body = it.body ?: return@use false
                it.isSuccessful && body.byteStream().read() != -1
            }
        } catch (_: Exception) { false }
    }

    private fun canUnbind(status: SessionStatus): Boolean {
        val mac = status.mac?.filter(Char::isLetterOrDigit)
        val parts = status.ipv4?.split('.')?.map { it.toIntOrNull() }
        return !status.account.isNullOrBlank() && mac?.length == 12 &&
            mac.all { it.uppercaseChar() in "0123456789ABCDEF" } && parts?.size == 4 && parts.all { it != null && it in 0..255 }
    }

    private fun readFile(context: Context, name: String): ByteArray? = File(context.filesDir, name).takeIf { it.exists() }?.readBytes()

    private fun report(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "$message\n") })
    }

    private companion object {
        val CONFIG_FILES = listOf("accounts.json", "current_index.txt", "campus_config.txt", "keep_alive_config.txt", "login_preferences.json", "pending_recovery.json")
    }
}
