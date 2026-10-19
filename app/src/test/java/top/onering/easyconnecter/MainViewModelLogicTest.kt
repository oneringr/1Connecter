package top.onering.easyconnecter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.onering.easyconnecter.model.ConnectionOutcome
import top.onering.easyconnecter.model.LogoutOutcome

class MainViewModelLogicTest {
    @Test
    fun alreadyConnectedDoesNotAutoClose() {
        val outcome = ConnectionOutcome.Connected(null, null, alreadyConnected = true)

        assertFalse(shouldAutoCloseAfterConnection(outcome, autoCloseEnabled = true))
    }

    @Test
    fun successfulLoginAutoClosesWhenEnabled() {
        val outcome = ConnectionOutcome.Connected(null, null, alreadyConnected = false)

        assertTrue(shouldAutoCloseAfterConnection(outcome, autoCloseEnabled = true))
        assertFalse(shouldAutoCloseAfterConnection(outcome, autoCloseEnabled = false))
    }

    @Test
    fun unfinishedOnboardingAndUserInteractionIndependentlyPreventAutomaticExit() {
        val outcome = ConnectionOutcome.Connected(null, null, alreadyConnected = false)

        assertFalse(shouldAutoCloseAfterConnection(
            outcome, autoCloseEnabled = true, sessionAllowsAutoClose = true, onboardingCompleted = false
        ))
        assertFalse(shouldAutoCloseAfterConnection(
            outcome, autoCloseEnabled = true, sessionAllowsAutoClose = false, onboardingCompleted = true
        ))
        assertTrue(shouldAutoCloseAfterConnection(
            outcome, autoCloseEnabled = true, sessionAllowsAutoClose = true, onboardingCompleted = true
        ))
    }

    @Test
    fun failedAndEmptyAccountOutcomesNeverCloseTheApp() {
        assertFalse(shouldAutoCloseAfterConnection(ConnectionOutcome.Failed("离线"), autoCloseEnabled = true))
        assertFalse(shouldAutoCloseAfterConnection(ConnectionOutcome.NoAccounts, autoCloseEnabled = true))
    }

    @Test
    fun restartPromptOnlyAppearsAfterConfirmedLogout() {
        val successful = LogoutOutcome(
            sessionLoggedOut = true,
            unbindAttempted = true,
            unbindSucceeded = true,
            logoutRequestSucceeded = true,
            message = "校园网已解绑并注销"
        )

        assertTrue(shouldPromptRestartAfterLogout(successful))
        assertFalse(shouldPromptRestartAfterLogout(successful.copy(sessionLoggedOut = false)))
    }
}
