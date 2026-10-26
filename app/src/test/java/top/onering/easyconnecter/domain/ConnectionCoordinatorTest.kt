package top.onering.easyconnecter.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import top.onering.easyconnecter.data.AccountStore
import top.onering.easyconnecter.data.ConfigStore
import top.onering.easyconnecter.data.ConfigRepository
import top.onering.easyconnecter.data.EventLogger
import top.onering.easyconnecter.model.Account
import top.onering.easyconnecter.model.Campus
import top.onering.easyconnecter.model.Carrier
import top.onering.easyconnecter.model.ConnectionOutcome
import top.onering.easyconnecter.model.LoginOutcome
import top.onering.easyconnecter.model.PendingRecovery
import top.onering.easyconnecter.model.SessionStatus
import top.onering.easyconnecter.network.NetworkGateway

class ConnectionCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

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
        assertEquals(listOf(6_000L), waits)
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
    fun healthyProbeSkipsAllAuthenticationRequests() = runTest {
        val gateway = FakeNetworkGateway(connectivityResults = mutableListOf(true))
        val outcome = coordinator(FakeAccountStore(listOf(account("one"))), FakeConfigStore(), gateway).connectOnce()
        assertTrue(outcome is ConnectionOutcome.Connected)
        assertTrue((outcome as ConnectionOutcome.Connected).alreadyConnected)
        assertEquals(1, gateway.connectivityChecks)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun checksConnectivityOnceBeforeLoginAndVerifiesTheResult() = runTest {
        val accounts = FakeAccountStore(listOf(account("one")))
        val config = FakeConfigStore()
        val gateway = FakeNetworkGateway(
            connectivityResults = mutableListOf(false),
            loginOutcomes = mutableListOf(LoginOutcome.Success(Carrier.MOBILE))
        )

        val outcome = coordinator(accounts, config, gateway).connectOnce()

        assertTrue(outcome is ConnectionOutcome.Connected)
        assertEquals(2, gateway.connectivityChecks)
        assertEquals(listOf(Carrier.MOBILE), gateway.loginCarriers)
    }

    @Test
    fun waitsOnlyForMinimumIntervalAfterConfirmedLogout() = runTest {
        val accounts = FakeAccountStore(listOf(account("one")))
        val config = FakeConfigStore().apply { setKeepAlive(true) }
        var now = 0L
        var logoutCompletedAt = 0L
        val loginStartedTimes = mutableListOf<Long>()
        val waits = mutableListOf<Long>()
        val gateway = FakeNetworkGateway(
            loginOutcomes = mutableListOf(
                LoginOutcome.ClientIpOnline("clientip online"),
                LoginOutcome.Success(Carrier.MOBILE)
            ),
            onLogin = { loginStartedTimes += now },
            onLogout = {
                now += 2_000L
                logoutCompletedAt = now
            }
        )

        val outcome = coordinator(accounts, config, gateway, { now }) {
            waits += it
            now += it
        }.connectOnce()

        assertTrue(outcome is ConnectionOutcome.Connected)
        assertEquals(listOf(Carrier.MOBILE, Carrier.MOBILE), gateway.loginCarriers)
        assertEquals(1, gateway.logoutCalls)
        assertEquals(listOf("status", "login", "status", "logout", "status", "login"), gateway.calls)
        assertEquals(listOf(3_000L), waits)
        assertEquals(3_000L, loginStartedTimes[1] - logoutCompletedAt)
        assertTrue(config.keepAlive.value)
    }

    @Test
    fun stopsWithoutRotatingWhenLogoutFailsAndSessionRemainsOnline() = runTest {
        val accounts = FakeAccountStore(listOf(account("one")))
        val config = FakeConfigStore()
        var now = 0L
        val waits = mutableListOf<Long>()
        val gateway = FakeNetworkGateway(
            loginOutcomes = mutableListOf(
                LoginOutcome.ClientIpOnline("clientip online"),
                LoginOutcome.Success(Carrier.MOBILE)
            ),
            logoutResult = Result.failure(IllegalStateException("logout rejected"))
        )

        val outcome = coordinator(accounts, config, gateway, { now }) {
            waits += it
            now += it
        }.connectOnce()

        assertTrue(outcome is ConnectionOutcome.Failed)
        assertEquals(listOf(Carrier.MOBILE), gateway.loginCarriers)
        assertEquals(List(5) { 1_000L }, waits)
        assertEquals(0, config.currentIndex.value)
        assertEquals(1, accounts.accounts.value.size)
    }

    @Test
    fun delayedLogoutStatusIsPolledUntilOfflineBeforeRetrying() = runTest {
        val online = Result.success(SessionStatus(true, "one", null, null))
        val offline = Result.success(SessionStatus(false, null, null, null))
        val gateway = FakeNetworkGateway(
            statuses = (listOf(offline, online) + List(3) { online } + offline).toMutableList(),
            loginOutcomes = mutableListOf(LoginOutcome.ClientIpOnline("clientip online"), LoginOutcome.Success(Carrier.MOBILE))
        )
        val waits = mutableListOf<Long>()
        var now = 0L
        val outcome = coordinator(FakeAccountStore(listOf(account("one"))), FakeConfigStore(), gateway, { now }) {
            waits += it
            now += it
        }.connectOnce()

        assertTrue(outcome is ConnectionOutcome.Connected)
        assertEquals(1, gateway.logoutCalls)
        assertEquals(2, gateway.loginAccounts.size)
        assertEquals(List(3) { 1_000L }, waits)
        assertEquals(listOf("logout", "status", "status", "status", "status", "login"), gateway.calls.drop(3))
    }

    @Test
    fun exhaustedRecoveryConfirmsOfflineAndAdvancesInsteadOfPinningTheAccount() = runTest {
        val accounts = FakeAccountStore(listOf(account("one"), account("two")))
        val config = FakeConfigStore()
        var now = 0L
        val waits = mutableListOf<Long>()
        val gateway = FakeNetworkGateway(loginOutcomes = (
            List(4) { LoginOutcome.ClientIpOnline("clientip online") } + LoginOutcome.Success(Carrier.MOBILE)
        ).toMutableList())
        val coordinator = coordinator(accounts, config, gateway, { now }) { waits += it; now += it }

        assertTrue(coordinator.connectOnce() is ConnectionOutcome.Failed)
        assertEquals(listOf(Carrier.MOBILE, Carrier.MOBILE, Carrier.TELECOM, Carrier.TELECOM), gateway.loginCarriers)
        assertEquals(4, gateway.logoutCalls)
        assertEquals("status", gateway.calls.last())
        assertEquals(1, config.currentIndex.value)
        assertNull(config.pendingRecovery())
        assertEquals(2, accounts.accounts.value.size)
        assertEquals(listOf(3_000L, 3_000L, 3_000L), waits)

        assertTrue(coordinator.connectOnce() is ConnectionOutcome.Connected)
        assertEquals("two", gateway.loginAccounts.last())
        assertTrue(waits.all { it <= 3_000L })
    }

    @Test
    fun keepsHealthySessionAfterClientIpOnlineWithoutLogoutOrIndexChanges() = runTest {
        val accounts = FakeAccountStore(listOf(account("one"), account("two")))
        val config = FakeConfigStore().apply { setCurrentIndex(1) }
        val gateway = FakeNetworkGateway(
            connectivityResults = listOf(false, true).toMutableList(),
            loginOutcomes = mutableListOf(LoginOutcome.ClientIpOnline("clientip online"))
        )

        val outcome = coordinator(accounts, config, gateway).connectOnce()

        assertTrue(outcome is ConnectionOutcome.Connected)
        assertTrue((outcome as ConnectionOutcome.Connected).alreadyConnected)
        assertEquals(0, gateway.logoutCalls)
        assertEquals(1, config.currentIndex.value)
        assertTrue(config.preferred.isEmpty())
    }

    @Test
    fun rechecksInternetBeforeCleaningExistingOnlineSession() = runTest {
        val config = FakeConfigStore().apply { setCurrentIndex(1) }
        val gateway = FakeNetworkGateway(
            connectivityResults = listOf(false, true).toMutableList(),
            statuses = mutableListOf(Result.success(SessionStatus(true, "one", null, null)))
        )

        val outcome = coordinator(FakeAccountStore(listOf(account("one"), account("two"))), config, gateway).connectOnce()

        assertTrue(outcome is ConnectionOutcome.Connected)
        assertTrue(gateway.loginAccounts.isEmpty())
        assertEquals(0, gateway.logoutCalls)
        assertEquals(1, config.currentIndex.value)
    }

    @Test
    fun restoresExistingSessionAccountAndCarrierInsteadOfNextIndex() = runTest {
        val accounts = FakeAccountStore(listOf(account("one"), account("two")))
        val config = FakeConfigStore().apply {
            setCurrentIndex(1)
            recordSuccessfulLogin("one", Carrier.TELECOM)
        }
        val gateway = FakeNetworkGateway(
            statuses = mutableListOf(Result.success(SessionStatus(true, "one", "AABBCCDDEEFF", "10.1.2.3"))),
            loginOutcomes = mutableListOf(LoginOutcome.Success(Carrier.TELECOM))
        )
        val waits = mutableListOf<Long>()

        val outcome = coordinator(accounts, config, gateway, sleep = { waits += it }).connectOnce()

        assertTrue(outcome is ConnectionOutcome.Connected)
        assertEquals(listOf("one"), gateway.loginAccounts)
        assertEquals(listOf(Carrier.TELECOM), gateway.loginCarriers)
        assertEquals(listOf("status", "unbind", "logout", "status", "login"), gateway.calls)
        assertEquals(listOf(3_000L), waits)
        assertEquals(1, config.currentIndex.value)
    }

    @Test
    fun learnsActiveAccountFromConflictThenRestoresThatSession() = runTest {
        val accounts = FakeAccountStore(listOf(account("one"), account("two")))
        val config = FakeConfigStore().apply {
            setCurrentIndex(1)
            recordSuccessfulLogin("one", Carrier.TELECOM)
        }
        val gateway = FakeNetworkGateway(
            statuses = mutableListOf(
                Result.success(SessionStatus(false, null, null, null)),
                Result.success(SessionStatus(true, "one", null, null))
            ),
            loginOutcomes = mutableListOf(LoginOutcome.ClientIpOnline("clientip online"), LoginOutcome.Success(Carrier.TELECOM))
        )
        var now = 0L

        val outcome = coordinator(accounts, config, gateway, { now }) { now += it }.connectOnce()

        assertTrue(outcome is ConnectionOutcome.Connected)
        assertEquals(listOf("two", "one"), gateway.loginAccounts)
        assertEquals(listOf(Carrier.MOBILE, Carrier.TELECOM), gateway.loginCarriers)
        assertEquals(1, config.currentIndex.value)
    }

    @Test
    fun recoveryKeepsCurrentCarrierAfterAlternateCarrierAcceptedLogin() = runTest {
        val config = FakeConfigStore().apply { preferred["one"] = Carrier.MOBILE }
        val gateway = FakeNetworkGateway(
            connectivityResults = listOf(false, false, false, true).toMutableList(),
            loginOutcomes = mutableListOf(
                LoginOutcome.ProtocolError("移动失败"),
                LoginOutcome.Success(Carrier.TELECOM),
                LoginOutcome.Success(Carrier.TELECOM)
            )
        )
        var now = 0L

        val outcome = coordinator(FakeAccountStore(listOf(account("one"))), config, gateway, { now }) { now += it }.connectOnce()

        assertTrue(outcome is ConnectionOutcome.Connected)
        assertEquals(listOf(Carrier.MOBILE, Carrier.TELECOM, Carrier.TELECOM), gateway.loginCarriers)
        assertEquals(Carrier.TELECOM, config.preferred["one"])
        assertEquals(1, gateway.logoutCalls)
    }

    @Test
    fun failedStatusAfterAcceptedLoginRetainsCarrierForNextRound() = runTest {
        val config = FakeConfigStore().apply { preferred["one"] = Carrier.MOBILE }
        val gateway = FakeNetworkGateway(
            connectivityResults = listOf(false, false, false, false, true).toMutableList(),
            statuses = mutableListOf(
                Result.success(SessionStatus(false, null, null, null)),
                Result.success(SessionStatus(false, null, null, null)),
                Result.failure(IllegalStateException("status response lost"))
            ),
            loginOutcomes = mutableListOf(
                LoginOutcome.ProtocolError("移动失败"),
                LoginOutcome.Success(Carrier.TELECOM),
                LoginOutcome.Success(Carrier.TELECOM)
            )
        )
        var now = 0L
        val coordinator = coordinator(FakeAccountStore(listOf(account("one"))), config, gateway, { now }) { now += it }

        assertTrue(coordinator.connectOnce() is ConnectionOutcome.Failed)
        assertEquals(0, gateway.logoutCalls)
        now += 60_000L
        val outcome = coordinator.connectOnce()

        assertTrue(outcome is ConnectionOutcome.Connected)
        assertEquals(listOf(Carrier.MOBILE, Carrier.TELECOM, Carrier.TELECOM), gateway.loginCarriers)
        assertEquals(Carrier.TELECOM, config.preferred["one"])
        assertEquals(1, gateway.logoutCalls)
    }

    @Test
    fun switchingToActiveAccountWithoutPreferenceUsesItsDefaultCarrier() = runTest {
        listOf(true, false).forEach { initiallyOnline ->
            val config = FakeConfigStore().apply {
                setCurrentIndex(1)
                preferred["two"] = Carrier.TELECOM
            }
            val activeStatus = Result.success(SessionStatus(true, "one", null, null))
            val gateway = FakeNetworkGateway(
                statuses = if (initiallyOnline) mutableListOf(activeStatus) else mutableListOf(
                    Result.success(SessionStatus(false, null, null, null)), activeStatus
                ),
                loginOutcomes = if (initiallyOnline) mutableListOf(LoginOutcome.Success(Carrier.MOBILE)) else mutableListOf(
                    LoginOutcome.ClientIpOnline("clientip online"), LoginOutcome.Success(Carrier.MOBILE)
                )
            )
            var now = 0L

            val outcome = coordinator(FakeAccountStore(listOf(account("one"), account("two"))), config, gateway, { now }) { now += it }.connectOnce()

            assertTrue(outcome is ConnectionOutcome.Connected)
            assertEquals(Carrier.MOBILE, gateway.loginCarriers.last())
            assertEquals("one", gateway.loginAccounts.last())
            assertEquals(1, config.currentIndex.value)
        }
    }

    @Test
    fun unknownOnlineAccountIsPreservedBeforeLoginAndAfterConflict() = runTest {
        listOf(true, false).forEach { initiallyOnline ->
            listOf("external-account", null).forEach { activeAccount ->
                val config = FakeConfigStore()
                val onlineStatus = Result.success(SessionStatus(true, activeAccount, "AABBCCDDEEFF", "10.1.2.3"))
                val gateway = FakeNetworkGateway(
                    statuses = if (initiallyOnline) mutableListOf(onlineStatus) else mutableListOf(
                        Result.success(SessionStatus(false, null, null, null)), onlineStatus
                    ),
                    loginOutcomes = mutableListOf(LoginOutcome.ClientIpOnline("clientip online"))
                )

                val outcome = coordinator(FakeAccountStore(listOf(account("one"))), config, gateway).connectOnce()

                assertTrue(outcome is ConnectionOutcome.Failed)
                assertEquals(if (initiallyOnline) 0 else 1, gateway.loginAccounts.size)
                assertEquals(0, gateway.logoutCalls)
                assertFalse(gateway.calls.contains("unbind"))
                assertEquals(0, config.currentIndex.value)
            }
        }
    }

    @Test
    fun retainsRecoveryAccountAndCarrierAcrossFailedConnectionRounds() = runTest {
        val failures = listOf(
            listOf(LoginOutcome.NetworkError("response lost")),
            List(3) { LoginOutcome.RateLimited(3, "waitsec <3") }
        )
        failures.forEach { failedOutcomes ->
            val accounts = FakeAccountStore(listOf(account("one"), account("two")))
            val config = FakeConfigStore().apply {
                setCurrentIndex(1)
                recordSuccessfulLogin("one", Carrier.TELECOM)
            }
            val gateway = FakeNetworkGateway(
                statuses = mutableListOf(Result.success(SessionStatus(true, "one", null, null))),
                loginOutcomes = (failedOutcomes + LoginOutcome.Success(Carrier.TELECOM)).toMutableList()
            )
            var now = 0L
            val coordinator = coordinator(accounts, config, gateway, { now }) { now += it }

            val failed = coordinator.connectOnce()
            assertTrue(failed is ConnectionOutcome.Failed)
            assertEquals(2, accounts.accounts.value.size)
            now += 60_000L
            val recovered = coordinator.connectOnce()

            assertTrue(recovered is ConnectionOutcome.Connected)
            assertEquals(List(failedOutcomes.size + 1) { "one" }, gateway.loginAccounts)
            assertEquals(List(failedOutcomes.size + 1) { Carrier.TELECOM }, gateway.loginCarriers)
            assertEquals(1, gateway.logoutCalls)
            assertEquals(1, config.currentIndex.value)
        }
    }

    @Test
    fun explicitlyRemovingRecoveryAccountPreventsReuseOfItsCredentials() = runTest {
        val accounts = FakeAccountStore(listOf(account("one"), account("two")))
        val config = FakeConfigStore().apply { setCurrentIndex(1) }
        val gateway = FakeNetworkGateway(
            statuses = mutableListOf(Result.success(SessionStatus(true, "one", null, null))),
            loginOutcomes = mutableListOf(LoginOutcome.NetworkError("response lost"), LoginOutcome.Success(Carrier.MOBILE))
        )
        var now = 0L
        val coordinator = coordinator(accounts, config, gateway, { now }) { now += it }

        assertTrue(coordinator.connectOnce() is ConnectionOutcome.Failed)
        coordinator.removeAccount(0)
        now += 60_000L
        val outcome = coordinator.connectOnce()

        assertTrue(outcome is ConnectionOutcome.Connected)
        assertEquals(listOf("one", "two"), gateway.loginAccounts)
        assertEquals(listOf(account("two")), accounts.accounts.value)
    }

    @Test
    fun explicitManualLogoutClearsPendingRecoveryAccount() = runTest {
        val config = FakeConfigStore().apply { setCurrentIndex(1) }
        val gateway = FakeNetworkGateway(
            statuses = mutableListOf(Result.success(SessionStatus(true, "one", null, null))),
            loginOutcomes = mutableListOf(LoginOutcome.NetworkError("response lost"), LoginOutcome.Success(Carrier.MOBILE))
        )
        var now = 0L
        val coordinator = coordinator(FakeAccountStore(listOf(account("one"), account("two"))), config, gateway, { now }) { now += it }

        assertTrue(coordinator.connectOnce() is ConnectionOutcome.Failed)
        assertTrue(coordinator.logoutAndUnbind().sessionLoggedOut)
        val outcome = coordinator.connectOnce()

        assertTrue(outcome is ConnectionOutcome.Connected)
        assertEquals(listOf("one", "two"), gateway.loginAccounts)
        assertEquals(0, config.currentIndex.value)
    }

    @Test
    fun recreatedCoordinatorRestoresPersistedSessionAfterProcessStopsDuringRecoveryWait() = runTest {
        val accounts = FakeAccountStore(listOf(account("one"), account("two")))
        val originalConfig = ConfigRepository(temporaryFolder.root).apply {
            setCurrentIndex(1)
            recordSuccessfulLogin("one", Carrier.TELECOM)
        }
        val originalGateway = FakeNetworkGateway(
            statuses = mutableListOf(Result.success(SessionStatus(true, "one", "AABBCCDDEEFF", "10.1.2.3")))
        )
        val recoveryWaitStarted = CompletableDeferred<Unit>()
        val original = coordinator(accounts, originalConfig, originalGateway, sleep = { waitMillis ->
            assertEquals(3_000L, waitMillis)
            recoveryWaitStarted.complete(Unit)
            awaitCancellation()
        })
        val connection = launch { original.connectOnce() }
        recoveryWaitStarted.await()
        connection.cancelAndJoin()
        assertEquals(1, originalGateway.logoutCalls)
        assertTrue(originalGateway.loginAccounts.isEmpty())
        assertFalse(temporaryFolder.root.resolve("pending_recovery.json").readText().contains("123456"))

        val recreatedConfig = ConfigRepository(temporaryFolder.root)
        assertEquals(PendingRecovery(Campus.CHENGBEI, "one", Carrier.TELECOM), recreatedConfig.pendingRecovery())
        val recreatedGateway = FakeNetworkGateway(loginOutcomes = mutableListOf(LoginOutcome.Success(Carrier.TELECOM)))
        val result = coordinator(accounts, recreatedConfig, recreatedGateway).connectOnce()

        assertTrue(result is ConnectionOutcome.Connected)
        assertEquals(listOf("one"), recreatedGateway.loginAccounts)
        assertEquals(listOf(Carrier.TELECOM), recreatedGateway.loginCarriers)
        assertEquals(1, recreatedConfig.currentIndex.value)
        assertNull(ConfigRepository(temporaryFolder.root).pendingRecovery())
    }

    @Test
    fun explicitRecoveryExitActionsClearThePersistedIdentity() = runTest {
        for (action in listOf("healthy", "logout", "remove", "campus", "replace")) {
            val directory = temporaryFolder.newFolder(action)
            val config = ConfigRepository(directory)
            config.setPendingRecovery(PendingRecovery(Campus.CHENGBEI, "one", Carrier.TELECOM))
            val accounts = FakeAccountStore(listOf(account("one"), account("two")))
            val gateway = FakeNetworkGateway(forcedConnectivity = action == "healthy")
            val recreated = coordinator(accounts, ConfigRepository(directory), gateway)

            when (action) {
                "healthy" -> assertTrue(recreated.connectOnce() is ConnectionOutcome.Connected)
                "logout" -> assertTrue(recreated.logoutAndUnbind().sessionLoggedOut)
                "remove" -> recreated.removeAccount(0)
                "campus" -> recreated.changeCampus(Campus.ZHONGSHAN)
                "replace" -> recreated.replaceAccounts(listOf(account("one"), account("two")))
            }

            assertNull("Pending identity must clear after $action", ConfigRepository(directory).pendingRecovery())
        }
    }

    @Test
    fun failureToPersistRecoveryNeverLogsOutTheExistingSession() = runTest {
        for (initiallyOnline in listOf(true, false)) {
            val directory = temporaryFolder.newFolder("persist-failure-$initiallyOnline")
            assertTrue(directory.resolve("pending_recovery.json.tmp").mkdir())
            val activeStatus = Result.success(SessionStatus(true, "one", "AABBCCDDEEFF", "10.1.2.3"))
            val gateway = FakeNetworkGateway(
                statuses = if (initiallyOnline) mutableListOf(activeStatus) else mutableListOf(
                    Result.success(SessionStatus(false, null, null, null)), activeStatus
                ),
                loginOutcomes = mutableListOf(LoginOutcome.ClientIpOnline("clientip online"))
            )

            val outcome = coordinator(FakeAccountStore(listOf(account("one"))), ConfigRepository(directory), gateway).connectOnce()

            assertTrue(outcome is ConnectionOutcome.Failed)
            assertEquals(if (initiallyOnline) 0 else 1, gateway.loginAccounts.size)
            assertEquals(0, gateway.logoutCalls)
            assertFalse(gateway.calls.contains("unbind"))
        }
    }

    @Test
    fun corruptPersistedIdentityStopsAutomaticAuthentication() = runTest {
        temporaryFolder.root.resolve("pending_recovery.json").writeText("{\"campus\":\"CHENGBEI\"}")
        val gateway = FakeNetworkGateway()

        val outcome = coordinator(FakeAccountStore(listOf(account("one"))), ConfigRepository(temporaryFolder.root), gateway).connectOnce()

        assertTrue(outcome is ConnectionOutcome.Failed)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun unknownInitialSessionPreventsLoginAndCleanup() = runTest {
        val gateway = FakeNetworkGateway(statuses = mutableListOf(Result.failure(IllegalStateException("invalid status"))))

        val outcome = coordinator(FakeAccountStore(listOf(account("one"))), FakeConfigStore(), gateway).connectOnce()

        assertTrue(outcome is ConnectionOutcome.Failed)
        assertTrue(gateway.loginAccounts.isEmpty())
        assertEquals(0, gateway.logoutCalls)
    }

    @Test
    fun unknownConflictStatusDoesNotTriggerDestructiveRecovery() = runTest {
        val gateway = FakeNetworkGateway(
            statuses = mutableListOf(
                Result.success(SessionStatus(false, null, null, null)),
                Result.failure(IllegalStateException("invalid status"))
            ),
            loginOutcomes = mutableListOf(LoginOutcome.ClientIpOnline("clientip online"))
        )

        val outcome = coordinator(FakeAccountStore(listOf(account("one"))), FakeConfigStore(), gateway).connectOnce()

        assertTrue(outcome is ConnectionOutcome.Failed)
        assertEquals(0, gateway.logoutCalls)
        assertEquals(1, gateway.loginAccounts.size)
    }

    @Test
    fun loginSuccessWithoutInternetIsNotReportedConnectedOrAdvanced() = runTest {
        val config = FakeConfigStore()
        val gateway = FakeNetworkGateway(
            loginOutcomes = mutableListOf(LoginOutcome.Success(Carrier.MOBILE)),
            forcedConnectivity = false,
            statuses = mutableListOf(
                Result.success(SessionStatus(false, null, null, null)),
                Result.failure(IllegalStateException("status unavailable"))
            )
        )

        val outcome = coordinator(FakeAccountStore(listOf(account("one"), account("two"))), config, gateway).connectOnce()

        assertTrue(outcome is ConnectionOutcome.Failed)
        assertEquals(0, config.currentIndex.value)
        assertTrue(config.preferred.isEmpty())
    }

    @Test
    fun failedLogoutCanRetryOnlyWhenOfflineWasIndependentlyConfirmed() = runTest {
        val gateway = FakeNetworkGateway(
            loginOutcomes = mutableListOf(LoginOutcome.ClientIpOnline("clientip online"), LoginOutcome.Success(Carrier.MOBILE)),
            logoutResult = Result.failure(IllegalStateException("response lost")),
            statuses = mutableListOf(
                Result.success(SessionStatus(false, null, null, null)),
                Result.success(SessionStatus(true, "one", null, null)),
                Result.success(SessionStatus(false, null, null, null))
            )
        )
        var now = 0L
        val waits = mutableListOf<Long>()

        val outcome = coordinator(FakeAccountStore(listOf(account("one"))), FakeConfigStore(), gateway, { now }) {
            waits += it
            now += it
        }.connectOnce()

        assertTrue(outcome is ConnectionOutcome.Connected)
        assertEquals(2, gateway.loginAccounts.size)
        assertEquals(listOf(3_000L), waits)
    }

    @Test
    fun repeatedRateLimitsDoNotChangeAccountOrCarrier() = runTest {
        val config = FakeConfigStore()
        val gateway = FakeNetworkGateway(loginOutcomes = MutableList(3) { LoginOutcome.RateLimited(7, "waitsec <7") })
        var now = 0L
        val waits = mutableListOf<Long>()

        val outcome = coordinator(FakeAccountStore(listOf(account("one"), account("two"))), config, gateway, { now }) {
            waits += it
            now += it
        }.connectOnce()

        assertTrue(outcome is ConnectionOutcome.Failed)
        assertEquals(List(3) { Carrier.MOBILE }, gateway.loginCarriers)
        assertEquals(List(3) { "one" }, gateway.loginAccounts)
        assertEquals(listOf(7_000L, 12_000L), waits)
        assertEquals(0, config.currentIndex.value)
    }

    @Test
    fun longerServerRateLimitOverridesAdaptiveWait() = runTest {
        val gateway = FakeNetworkGateway(loginOutcomes = mutableListOf(
            LoginOutcome.RateLimited(30, "waitsec <30"),
            LoginOutcome.Success(Carrier.MOBILE)
        ))
        val waits = mutableListOf<Long>()
        var now = 0L

        val outcome = coordinator(FakeAccountStore(listOf(account("one"))), FakeConfigStore(), gateway, { now }) {
            waits += it
            now += it
        }.connectOnce()

        assertTrue(outcome is ConnectionOutcome.Connected)
        assertEquals(listOf(30_000L), waits)
        assertEquals(listOf(Carrier.MOBILE, Carrier.MOBILE), gateway.loginCarriers)
    }

    @Test
    fun successfulCleanupResetsRateLimitRetryBudget() = runTest {
        val gateway = FakeNetworkGateway(loginOutcomes = mutableListOf(
            LoginOutcome.RateLimited(3, "waitsec <3"),
            LoginOutcome.RateLimited(3, "waitsec <3"),
            LoginOutcome.ClientIpOnline("clientip online"),
            LoginOutcome.RateLimited(3, "waitsec <3"),
            LoginOutcome.RateLimited(3, "waitsec <3"),
            LoginOutcome.Success(Carrier.MOBILE)
        ))
        var now = 0L

        val outcome = coordinator(FakeAccountStore(listOf(account("one"))), FakeConfigStore(), gateway, { now }) { now += it }.connectOnce()

        assertTrue(outcome is ConnectionOutcome.Connected)
        assertEquals(6, gateway.loginAccounts.size)
        assertEquals(1, gateway.logoutCalls)
    }

    @Test
    fun pausedCoordinatorRejectsConnectionsButAllowsManualLogout() = runTest {
        val gateway = FakeNetworkGateway(statuses = mutableListOf(Result.success(SessionStatus(true, "one", null, null))))
        val coordinator = coordinator(FakeAccountStore(listOf(account("one"))), FakeConfigStore(), gateway)

        coordinator.pauseConnections()
        coordinator.awaitIdle()
        val outcome = coordinator.connectOnce()
        val logout = coordinator.logoutAndUnbind()

        assertTrue(coordinator.connectionsPaused)
        assertEquals(ConnectionOutcome.Failed("连接已暂停"), outcome)
        assertEquals(0, gateway.connectivityChecks)
        assertTrue(gateway.loginAccounts.isEmpty())
        assertTrue(logout.sessionLoggedOut)
        assertEquals(1, gateway.logoutCalls)
    }

    @Test
    fun pauseDuringRecoveryPreventsRetryAndQueuedConnection() = runTest {
        val gateway = FakeNetworkGateway(loginOutcomes = mutableListOf(LoginOutcome.ClientIpOnline("clientip online")))
        val waitStarted = CompletableDeferred<Unit>()
        val releaseWait = CompletableDeferred<Unit>()
        val coordinator = coordinator(FakeAccountStore(listOf(account("one"))), FakeConfigStore(), gateway) {
            waitStarted.complete(Unit)
            releaseWait.await()
        }
        val outcomes = mutableListOf<ConnectionOutcome>()
        val active = launch { outcomes += coordinator.connectOnce() }
        waitStarted.await()
        val queued = launch { outcomes += coordinator.connectOnce() }

        coordinator.pauseConnections()
        releaseWait.complete(Unit)
        active.join()
        queued.join()
        coordinator.awaitIdle()

        assertEquals(List(2) { ConnectionOutcome.Failed("连接已暂停") }, outcomes)
        assertEquals(1, gateway.loginAccounts.size)
        assertEquals(1, gateway.logoutCalls)
    }

    @Test
    fun cancellationDuringRecoveryWaitPreventsRetry() = runTest {
        val accounts = FakeAccountStore(listOf(account("one")))
        val config = FakeConfigStore()
        val gateway = FakeNetworkGateway(
            loginOutcomes = mutableListOf(
                LoginOutcome.ClientIpOnline("clientip online"),
                LoginOutcome.Success(Carrier.MOBILE)
            )
        )
        val waitStarted = CompletableDeferred<Unit>()
        val waitFinished = CompletableDeferred<Unit>()
        val coordinator = coordinator(accounts, config, gateway) {
            assertEquals(3_000L, it)
            waitStarted.complete(Unit)
            waitFinished.await()
        }
        val connection = launch { coordinator.connectOnce() }

        waitStarted.await()
        connection.cancelAndJoin()

        assertEquals(listOf(Carrier.MOBILE), gateway.loginCarriers)
        assertEquals(1, gateway.logoutCalls)
    }

    @Test
    fun pausesKeepAliveWithoutClearingRememberedSwitchWhenNoAccountsRemain() = runTest {
        val accounts = FakeAccountStore(listOf(account("bad")))
        val config = FakeConfigStore().apply { setKeepAlive(true) }
        val gateway = FakeNetworkGateway(
            loginOutcomes = mutableListOf(LoginOutcome.InvalidCredentials("账号或密码错误"))
        )
        val outcomes = mutableListOf<ConnectionOutcome>()

        coordinator(accounts, config, gateway).runKeepAlive { outcomes += it }

        assertTrue(outcomes.first() is ConnectionOutcome.AccountRemoved)
        assertEquals(ConnectionOutcome.NoAccounts, outcomes.last())
        assertTrue(accounts.accounts.value.isEmpty())
        assertTrue(config.keepAlive.value)
        assertEquals(1, gateway.connectivityChecks)
    }

    @Test
    fun rememberedKeepAliveDoesNotProbeOrLoginWithoutAccounts() = runTest {
        val accounts = FakeAccountStore(emptyList())
        val config = FakeConfigStore().apply { setKeepAlive(true) }
        val gateway = FakeNetworkGateway()
        val outcomes = mutableListOf<ConnectionOutcome>()
        var checkingCalls = 0

        coordinator(accounts, config, gateway).runKeepAlive(onChecking = { checkingCalls++ }) { outcomes += it }

        assertEquals(listOf(ConnectionOutcome.NoAccounts), outcomes)
        assertTrue(config.keepAlive.value)
        assertEquals(0, gateway.connectivityChecks)
        assertTrue(gateway.loginCarriers.isEmpty())
        assertEquals(0, checkingCalls)
    }

    @Test
    fun announcesEachKeepAliveCheckBeforeTheNextNetworkProbe() = runTest {
        val accounts = FakeAccountStore(listOf(account("one")))
        val config = FakeConfigStore().apply { setKeepAlive(true) }
        val gateway = FakeNetworkGateway(forcedConnectivity = true)
        val checksAtAnnouncements = mutableListOf<Int>()
        val outcomes = mutableListOf<ConnectionOutcome>()

        coordinator(accounts, config, gateway, sleep = {}).runKeepAlive(
            onChecking = { checksAtAnnouncements += gateway.connectivityChecks }
        ) { outcome ->
            outcomes += outcome
            if (outcomes.size == 2) config.setKeepAlive(false)
        }

        assertEquals(listOf(0, 1), checksAtAnnouncements)
        assertEquals(2, outcomes.size)
        assertTrue(outcomes.all { it is ConnectionOutcome.Connected })
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
        assertTrue(config.keepAlive.value)
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


    @Test
    fun recoveryAuthenticationErrorsChangeCarrierOnlyAfterOfflineConfirmationWithoutDeletingAccount() = runTest {
        for (failure in listOf(
            LoginOutcome.InvalidCredentials("rejected"), LoginOutcome.OperatorNotBound("not bound"),
            LoginOutcome.ProtocolError("carrier rejected")
        )) {
            val accounts = FakeAccountStore(listOf(account("one"), account("two")))
            val config = FakeConfigStore().apply {
                setCurrentIndex(1)
                setPendingRecovery(PendingRecovery(Campus.CHENGBEI, "one", Carrier.TELECOM))
            }
            val gateway = FakeNetworkGateway(loginOutcomes = mutableListOf(failure, LoginOutcome.Success(Carrier.MOBILE)))
            var now = 0L
            val outcome = coordinator(accounts, config, gateway, { now }) { now += it }.connectOnce()
            assertTrue(outcome is ConnectionOutcome.Connected)
            assertEquals(listOf(Carrier.TELECOM, Carrier.MOBILE), gateway.loginCarriers)
            assertEquals(2, accounts.accounts.value.size)
            assertEquals(1, config.currentIndex.value)
            val firstLogin = gateway.calls.indexOf("login")
            assertEquals("status", gateway.calls[firstLogin + 1])
        }
    }

    @Test
    fun persistedAttemptBudgetSurvivesCoordinatorRecreationAndKeepsNextCandidateIndex() = runTest {
        val accounts = FakeAccountStore(listOf(account("one"), account("two")))
        val saved = ConfigRepository(temporaryFolder.root)
        saved.setCurrentIndex(1)
        saved.setPendingRecovery(PendingRecovery(Campus.CHENGBEI, "one", Carrier.MOBILE, 2))
        val recreated = ConfigRepository(temporaryFolder.root)
        val gateway = FakeNetworkGateway(loginOutcomes = mutableListOf(LoginOutcome.Success(Carrier.TELECOM)))
        val outcome = coordinator(accounts, recreated, gateway).connectOnce()
        assertTrue(outcome is ConnectionOutcome.Connected)
        assertEquals(listOf(Carrier.TELECOM), gateway.loginCarriers)
        assertEquals(listOf("one"), gateway.loginAccounts)
        assertEquals(1, recreated.currentIndex.value)
        assertNull(ConfigRepository(temporaryFolder.root).pendingRecovery())
    }

    @Test
    fun interruptedRecoveryRequestKeepsItsReservedAttemptAcrossRestart() = runTest {
        val accounts = FakeAccountStore(listOf(account("one"), account("two")))
        val config = ConfigRepository(temporaryFolder.root)
        config.setPendingRecovery(PendingRecovery(Campus.CHENGBEI, "one", Carrier.MOBILE))
        val interrupted = FakeNetworkGateway(onLogin = { throw kotlinx.coroutines.CancellationException("process interrupted") })
        assertTrue(runCatching { coordinator(accounts, config, interrupted).connectOnce() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        val restored = ConfigRepository(temporaryFolder.root)
        assertEquals(2, restored.pendingRecovery()?.loginAttempts)
        val resumed = FakeNetworkGateway(loginOutcomes = mutableListOf(LoginOutcome.Success(Carrier.TELECOM)))
        assertTrue(coordinator(accounts, restored, resumed).connectOnce() is ConnectionOutcome.Connected)
        assertEquals(listOf(Carrier.TELECOM), resumed.loginCarriers)
    }

    @Test
    fun secondConnectivityConfirmationPreservesSlowlyActivatedSession() = runTest {
        val gateway = FakeNetworkGateway(
            connectivityResults = mutableListOf(false, false, true),
            loginOutcomes = mutableListOf(LoginOutcome.Success(Carrier.MOBILE))
        )
        val outcome = coordinator(FakeAccountStore(listOf(account("one"))), FakeConfigStore(), gateway).connectOnce()
        assertTrue(outcome is ConnectionOutcome.Connected)
        assertEquals(1, gateway.loginAccounts.size)
        assertEquals(0, gateway.logoutCalls)
    }

    @Test
    fun repeatedAcceptedLoginsWithoutInternetAreBoundedAndDoNotDeleteAccounts() = runTest {
        val accounts = FakeAccountStore(listOf(account("one"), account("two")))
        val config = FakeConfigStore()
        val gateway = FakeNetworkGateway(
            loginOutcomes = MutableList(4) { LoginOutcome.Success(Carrier.MOBILE) }, forcedConnectivity = false
        )
        var now = 0L
        val outcome = coordinator(accounts, config, gateway, { now }) { now += it }.connectOnce()
        assertTrue(outcome is ConnectionOutcome.Failed)
        assertEquals(4, gateway.loginAccounts.size)
        assertEquals(4, gateway.logoutCalls)
        assertEquals(1, config.currentIndex.value)
        assertEquals(2, accounts.accounts.value.size)
        assertNull(config.pendingRecovery())
        assertTrue(config.preferred.isEmpty())
    }

    @Test
    fun singleAccountFailureUsesShortCooldownWithoutProbingOrAuthenticatingDuringIt() = runTest {
        val gateway = FakeNetworkGateway(loginOutcomes = MutableList(4) { LoginOutcome.ClientIpOnline("clientip online") })
        var now = 0L
        val coordinator = coordinator(FakeAccountStore(listOf(account("one"))), FakeConfigStore(), gateway, { now }) { now += it }
        assertTrue(coordinator.connectOnce() is ConnectionOutcome.Failed)
        val probes = gateway.connectivityChecks
        now += 14_000
        assertTrue(coordinator.connectOnce() is ConnectionOutcome.Failed)
        assertEquals(4, gateway.loginAccounts.size)
        assertEquals(probes, gateway.connectivityChecks)
    }

    private fun coordinator(
        accounts: FakeAccountStore,
        config: ConfigStore,
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
        private var recovery: PendingRecovery? = null

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

        override suspend fun pendingRecovery(): PendingRecovery? = recovery

        override suspend fun setPendingRecovery(recovery: PendingRecovery?) {
            this.recovery = recovery
        }

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
        private val connectivityResults: MutableList<Boolean> = mutableListOf(),
        private val logoutResult: Result<Unit> = Result.success(Unit),
        private val forcedConnectivity: Boolean? = null,
        private val onLogin: () -> Unit = {},
        private val onLogout: suspend () -> Unit = {}
    ) : NetworkGateway {
        val loginCarriers = mutableListOf<Carrier>()
        val loginAccounts = mutableListOf<String>()
        val calls = mutableListOf<String>()
        var unboundMac: String? = null
        var unboundIp: String? = null
        var connectivityChecks = 0
        var logoutCalls = 0
        private var connected = false
        private var currentStatus = SessionStatus(false, null, null, null)

        override suspend fun isInternetConnected(): Boolean {
            connectivityChecks++
            return if (connectivityResults.isEmpty()) forcedConnectivity ?: connected else connectivityResults.removeAt(0)
        }

        override suspend fun login(
            studentId: String,
            password: String,
            campus: Campus,
            carrier: Carrier
        ): LoginOutcome {
            calls += "login"
            onLogin()
            loginCarriers += carrier
            loginAccounts += studentId
            return loginOutcomes.removeAt(0).also {
                if (it is LoginOutcome.Success) connected = true
                if (it is LoginOutcome.Success || it is LoginOutcome.ClientIpOnline) {
                    currentStatus = SessionStatus(true, studentId, null, null)
                }
            }
        }

        override suspend fun checkStatus(campus: Campus): Result<SessionStatus> {
            calls += "status"
            return if (statuses.isEmpty()) Result.success(currentStatus) else statuses.removeAt(0)
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
            onLogout()
            if (logoutResult.isSuccess) {
                connected = false
                currentStatus = SessionStatus(false, null, null, null)
            }
            return logoutResult
        }
    }
}
