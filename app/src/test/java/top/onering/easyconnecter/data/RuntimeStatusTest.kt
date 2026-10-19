package top.onering.easyconnecter.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.onering.easyconnecter.model.ConnectionOutcome

class RuntimeStatusTest {
    @Test
    fun connectedNetworkDoesNotImplyAServiceOrAKnownAccount() {
        val result = ConnectionOutcome.Connected(null, null, alreadyConnected = true)
        val normal = result.toRuntimeStatus(serviceRunning = false)
        val monitored = result.toRuntimeStatus(serviceRunning = true)

        assertEquals(ConnectionPhase.CONNECTED, normal.phase)
        assertFalse(normal.serviceRunning)
        assertNull(normal.accountName)
        assertEquals(ConnectionPhase.CONNECTED, monitored.phase)
        assertTrue(monitored.serviceRunning)
        assertNull(monitored.accountName)
    }

    @Test
    fun failureReportsRetryingWithoutClaimingAnActiveAccount() {
        val result = ConnectionOutcome.Failed("正在等待下一次检查").toRuntimeStatus(serviceRunning = true)

        assertEquals(ConnectionPhase.RETRYING, result.phase)
        assertTrue(result.serviceRunning)
        assertNull(result.accountName)
        assertEquals("正在等待下一次检查", result.message)
    }

    @Test
    fun noAccountsCannotClaimAnActiveService() {
        val result = ConnectionOutcome.NoAccounts.toRuntimeStatus(serviceRunning = true)

        assertEquals(ConnectionPhase.NO_ACCOUNTS, result.phase)
        assertFalse(result.serviceRunning)
        assertNull(result.accountName)
    }
}
