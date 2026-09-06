package top.onering.easyconnecter

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.onering.easyconnecter.model.ConnectionOutcome

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
}
