package top.onering.easyconnecter.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.onering.easyconnecter.data.AccountStore
import top.onering.easyconnecter.data.ConfigStore
import top.onering.easyconnecter.data.EventLogger
import top.onering.easyconnecter.model.Account
import top.onering.easyconnecter.model.Campus
import top.onering.easyconnecter.model.Carrier
import top.onering.easyconnecter.model.ConnectionOutcome
import top.onering.easyconnecter.model.LoginOutcome
import top.onering.easyconnecter.model.SessionStatus
import top.onering.easyconnecter.network.NetworkGateway

class ConnectionCoordinatorTest {
    @Test
    fun triesAlternateCarrierRemembersSuccessAndAdvancesLiveIndex() = runTest {
        val accounts = FakeAccountStore(listOf(account("one"), account("two")))
        val config = FakeConfigStore()
        val gateway = FakeNetworkGateway(
            loginOutcomes = mutableListOf(
                LoginOutcome.ProtocolError("移动登录失败"),
                LoginOutcome.Success(Carrier.TELECOM)
            )
        )
        var now = 0L
        val coordinator = coordinator(accounts, config, gateway, { now }) { now += it }

        val outcome = coordinator.connectOnce()

        assertTrue(outcome is ConnectionOutcome.Connected)
        assertEquals(listOf(Carrier.MOBILE, Carrier.TELECOM), gateway.loginCarriers)
        assertEquals(Carrier.TELECOM, config.preferred["one"])
        assertEquals(1, config.currentIndex.value)
        assertEquals(2, accounts.accounts.value.size)
    }

    @Test
    fun deletesEveryConfiguredRejectedAccountOutcome() = runTest {
        val rejectedOutcomes = listOf(
            LoginOutcome.InvalidCredentials("校园网账号或密码错误"),
            LoginOutcome.OperatorNotBound("未绑定移动运营商账号"),
            LoginOutcome.InvalidCredentials("绑定的电信运营商账号密码错误")
        )

        rejectedOutcomes.forEach { rejectedOutcome ->
            val accounts = FakeAccountStore(listOf(account("bad")))
            val config = FakeConfigStore()
            val gateway = FakeNetworkGateway(loginOutcomes = mutableListOf(rejectedOutcome))

            val outcome = coordinator(accounts, config, gateway).connectOnce()

            assertTrue(outcome is ConnectionOutcome.AccountRemoved)
            assertTrue(accounts.accounts.value.isEmpty())
            assertEquals(1, gateway.loginCarriers.size)
            assertEquals(0, config.currentIndex.value)
        }
    }

    @Test
    fun waitsForRateLimitThenRetriesSameCarrierOnce() = runTest {
        val accounts = FakeAccountStore(listOf(account("one")))
        val config = FakeConfigStore()
        val gateway = FakeNetworkGateway(
            loginOutcomes = mutableListOf(
                LoginOutcome.RateLimited(3, "error5 waitsec <3"),
                LoginOutcome.Success(Carrier.MOBILE)
            )
        )
        val waits = mutableListOf<Long>()
        var now = 0L
        val coordinator = coordinator(accounts, config, gateway, { now }) {
            waits += it
            now += it
        }

        coordinator.connectOnce()

        assertEquals(listOf(Carrier.MOBILE, Carrier.MOBILE), gateway.loginCarriers)
        assertTrue(waits.contains(3_000L))
    }

    @Test
    fun triesRememberedCarrierFirst() = runTest {
        val accounts = FakeAccountStore(listOf(account("one")))
        val config = FakeConfigStore().apply { preferred["one"] = Carrier.TELECOM }
        val gateway = FakeNetworkGateway(
            loginOutcomes = mutableListOf(LoginOutcome.Success(Carrier.TELECOM))
        )

        coordinator(accounts, config, gateway).connectOnce()

        assertEquals(listOf(Carrier.TELECOM), gateway.loginCarriers)
    }

    @Test
    fun checksConnectivityUpToFiveTimesAndStopsAtFirstSuccess() = runTest {
        val accounts = FakeAccountStore(listOf(account("one")))
        val config = FakeConfigStore()
        val gateway = FakeNetworkGateway(
            connectivityResults = mutableListOf(false, false, true)
        )

        val outcome = coordinator(accounts, config, gateway).connectOnce()

        assertTrue(outcome is ConnectionOutcome.Connected)
        assertTrue((outcome as ConnectionOutcome.Connected).alreadyConnected)
        assertEquals(3, gateway.connectivityChecks)
        assertTrue(gateway.loginCarriers.isEmpty())
    }

    @Test
    fun checksConnectivityFiveTimesBeforeStartingLogin() = runTest {
        val accounts = FakeAccountStore(listOf(account("one")))
        val config = FakeConfigStore()
        val gateway = FakeNetworkGateway(
            connectivityResults = MutableList(5) { false },
            loginOutcomes = mutableListOf(LoginOutcome.Success(Carrier.MOBILE))
        )

        val outcome = coordinator(accounts, config, gateway).connectOnce()

        assertTrue(outcome is ConnectionOutcome.Connected)
        assertEquals(5, gateway.connectivityChecks)
        assertEquals(listOf(Carrier.MOBILE), gateway.loginCarriers)
    }

    @Test
    fun logsOutOnceAndRetriesSameLoginForClientIpOnline() = runTest {
        val accounts = FakeAccountStore(listOf(account("one")))
        val config = FakeConfigStore()
        val gateway = FakeNetworkGateway(
            loginOutcomes = mutableListOf(
                LoginOutcome.ClientIpOnline("clientip online"),
                LoginOutcome.Success(Carrier.MOBILE)
            )
        )

        val outcome = coordinator(accounts, config, gateway).connectOnce()

        assertTrue(outcome is ConnectionOutcome.Connected)
        assertEquals(listOf(Carrier.MOBILE, Carrier.MOBILE), gateway.loginCarriers)
        assertEquals(1, gateway.logoutCalls)
    }

    @Test
    fun clientIpOnlineRecoveryDoesNotLoopLogout() = runTest {
        val accounts = FakeAccountStore(listOf(account("one")))
        val config = FakeConfigStore()
        val gateway = FakeNetworkGateway(
            loginOutcomes = mutableListOf(
                LoginOutcome.ClientIpOnline("clientip online"),
                LoginOutcome.ClientIpOnline("clientip online"),
                LoginOutcome.ClientIpOnline("clientip online")
            )
        )

        coordinator(accounts, config, gateway).connectOnce()

        assertEquals(listOf(Carrier.MOBILE, Carrier.MOBILE, Carrier.TELECOM), gateway.loginCarriers)
        assertEquals(1, gateway.logoutCalls)
    }

    @Test
    fun performsFullLogoutAndConvertsIdentityFields() = runTest {
        val accounts = FakeAccountStore(listOf(account("one")))
        val config = FakeConfigStore().apply { setKeepAlive(true) }
        val gateway = FakeNetworkGateway(
            statuses = mutableListOf(
                Result.success(SessionStatus(true, "one", "aa:bb:cc:dd:ee:ff", "10.106.92.155")),
                Result.success(SessionStatus(false, null, null, "10.106.92.155"))
            )
        )
        val coordinator = coordinator(accounts, config, gateway)

        val result = coordinator.logoutAndUnbind()

        assertTrue(result.sessionLoggedOut)
        assertTrue(result.unbindAttempted)
        assertEquals(true, result.unbindSucceeded)
        assertFalse(config.keepAlive.value)
        assertEquals("AABBCCDDEEFF", gateway.unboundMac)
        assertEquals("174742683", gateway.unboundIp)
        assertEquals(listOf("status", "unbind", "logout", "status"), gateway.calls)
    }

    @Test
    fun continuesLogoutWhenUnbindFailsAndReportsPartialSuccess() = runTest {
        val accounts = FakeAccountStore(listOf(account("one")))
        val config = FakeConfigStore()
        val gateway = FakeNetworkGateway(
            statuses = mutableListOf(
                Result.success(SessionStatus(true, "one", "AABBCCDDEEFF", "10.1.2.3")),
                Result.success(SessionStatus(false, null, null, null))
            ),
            unbindResult = Result.failure(IllegalStateException("unbind rejected"))
        )

        val result = coordinator(accounts, config, gateway).logoutAndUnbind()

        assertTrue(result.sessionLoggedOut)
        assertEquals(false, result.unbindSucceeded)
        assertTrue(result.message.contains("解绑失败"))
        assertEquals(listOf("status", "unbind", "logout", "status"), gateway.calls)
    }

    private fun coordinator(
        accounts: FakeAccountStore,
        config: FakeConfigStore,
        gateway: FakeNetworkGateway,
        now: () -> Long = { 0L },
        sleep: suspend (Long) -> Unit = {}
    ) = ConnectionCoordinator(accounts, config, EventLogger {}, gateway, now, sleep)

    private fun account(id: String) = Account(id, "123456", id, "", "", "510000200001011234")

    private class FakeAccountStore(initial: List<Account>) : AccountStore {
        private val mutableAccounts = MutableStateFlow(initial)
        override val accounts: StateFlow<List<Account>> = mutableAccounts

        override suspend fun replace(accounts: List<Account>) {
            mutableAccounts.value = accounts
        }

        override suspend fun removeAt(index: Int): Account? {
            val removed = mutableAccounts.value.getOrNull(index) ?: return null
            mutableAccounts.value = mutableAccounts.value.toMutableList().also { it.removeAt(index) }
            return removed
        }
    }

    private class FakeConfigStore : ConfigStore {
        override val currentIndex = MutableStateFlow(0)
        override val campus = MutableStateFlow(Campus.CHENGBEI)
        override val keepAlive = MutableStateFlow(false)
        val preferred = mutableMapOf<String, Carrier>()
        private var lastSuccessful: String? = null

        override suspend fun setCurrentIndex(index: Int) {
            currentIndex.value = index
        }

        override suspend fun setCampus(campus: Campus) {
            this.campus.value = campus
        }

        override suspend fun setKeepAlive(enabled: Boolean) {
            keepAlive.value = enabled
        }

        override suspend fun preferredCarrier(studentId: String): Carrier? = preferred[studentId]

        override suspend fun recordSuccessfulLogin(studentId: String, carrier: Carrier) {
            preferred[studentId] = carrier
            lastSuccessful = studentId
        }

        override suspend fun lastSuccessfulStudentId(): String? = lastSuccessful

        override suspend fun removeAccountPreferences(studentId: String) {
            preferred.remove(studentId)
            if (lastSuccessful == studentId) lastSuccessful = null
        }

        override suspend fun clearLoginPreferences() {
            preferred.clear()
            lastSuccessful = null
        }
    }

    private class FakeNetworkGateway(
        private val loginOutcomes: MutableList<LoginOutcome> = mutableListOf(),
        private val statuses: MutableList<Result<SessionStatus>> = mutableListOf(),
        private val unbindResult: Result<Unit> = Result.success(Unit),
        private val connectivityResults: MutableList<Boolean> = mutableListOf()
    ) : NetworkGateway {
        val loginCarriers = mutableListOf<Carrier>()
        val calls = mutableListOf<String>()
        var unboundMac: String? = null
        var unboundIp: String? = null
        var connectivityChecks = 0
        var logoutCalls = 0

        override suspend fun isInternetConnected(): Boolean {
            connectivityChecks++
            return if (connectivityResults.isEmpty()) false else connectivityResults.removeAt(0)
        }

        override suspend fun login(
            studentId: String,
            password: String,
            campus: Campus,
            carrier: Carrier
        ): LoginOutcome {
            loginCarriers += carrier
            return loginOutcomes.removeAt(0)
        }

        override suspend fun checkStatus(campus: Campus): Result<SessionStatus> {
            calls += "status"
            return statuses.removeAt(0)
        }

        override suspend fun unbindMac(
            campus: Campus,
            account: String,
            mac: String,
            decimalIpv4: String
        ): Result<Unit> {
            calls += "unbind"
            unboundMac = mac
            unboundIp = decimalIpv4
            return unbindResult
        }

        override suspend fun logout(campus: Campus): Result<Unit> {
            calls += "logout"
            logoutCalls++
            return Result.success(Unit)
        }
    }
}
