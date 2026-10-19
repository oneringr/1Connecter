package top.onering.easyconnecter

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import top.onering.easyconnecter.service.KeepAliveService
import java.io.File

@RunWith(AndroidJUnit4::class)
class ConnectionLifecycleTest {
    @Test
    fun manualPauseSurvivesActivityRecreationWithoutChangingServicePreference() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = context.appContainer
        val rememberedMode = container.config.keepAlive.value
        val wasPaused = container.coordinator.connectionsPaused
        container.coordinator.pauseConnections()
        runBlocking {
            KeepAliveService.stopAndAwait(context)
            container.coordinator.awaitIdle()
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.recreate()
                instrumentation.waitForIdleSync()
                assertTrue(container.coordinator.connectionsPaused)
                assertFalse(container.runtime.status.value.serviceRunning)
                assertEquals(rememberedMode, container.config.keepAlive.value)
            }
        } finally {
            if (wasPaused) container.coordinator.pauseConnections()
            else container.coordinator.resumeConnections()
        }
    }

    @Test
    fun notificationStopAllowsNormalModeWhenActivityReopensInSameProcess() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val container = context.appContainer
        assertTrue("Lifecycle test requires configured accounts", container.accounts.accounts.value.isNotEmpty())
        assertTrue("Lifecycle test requires healthy HTTPS before opening normal mode", container.network.isInternetConnected())
        val rememberedMode = container.config.keepAlive.value
        val wasPaused = container.coordinator.connectionsPaused
        val wasRunning = isKeepAliveServiceRunning(context)
        val preservedFiles = listOf(
            "accounts.json", "current_index.txt", "campus_config.txt", "login_preferences.json"
        ).associateWith { readFile(context, it) }
        try {
            container.coordinator.pauseConnections()
            KeepAliveService.stopAndAwait(context)
            container.coordinator.awaitIdle()
            container.config.setKeepAlive(true)
            container.coordinator.resumeConnections()
            KeepAliveService.start(context)
            val notifications = context.getSystemService(NotificationManager::class.java)
            waitUntil {
                container.runtime.status.value.serviceRunning &&
                    notifications.activeNotifications.any { notification ->
                        notification.notification.actions.orEmpty().any { it.title.toString() == "停止保活" }
                    }
            }
            val stopAction = notifications.activeNotifications
                .flatMap { it.notification.actions.orEmpty().asList() }
                .first { it.title.toString() == "停止保活" }
            stopAction.actionIntent.send()
            waitUntil {
                !container.config.keepAlive.value && !container.runtime.status.value.serviceRunning &&
                    !isKeepAliveServiceRunning(context)
            }
            assertFalse("Notification stop must not leave the manual logout gate paused", container.coordinator.connectionsPaused)

            val normalModeMessage = "检测到网络已连接，应用保持打开"
            val priorMessages = container.logs.snapshot().count { it.contains(normalModeMessage) }
            ActivityScenario.launch(MainActivity::class.java).use {
                waitUntil { container.logs.snapshot().count { it.contains(normalModeMessage) } > priorMessages }
                assertFalse(container.coordinator.connectionsPaused)
                assertFalse(container.config.keepAlive.value)
                assertFalse(isKeepAliveServiceRunning(context))
            }
        } finally {
            container.coordinator.pauseConnections()
            KeepAliveService.stopAndAwait(context)
            container.coordinator.awaitIdle()
            container.config.setKeepAlive(rememberedMode)
            if (wasPaused) container.coordinator.pauseConnections()
            else container.coordinator.resumeConnections()
            if (wasRunning && rememberedMode && !wasPaused) KeepAliveService.start(context)
            preservedFiles.forEach { (name, original) ->
                assertArrayEquals("Lifecycle test must preserve $name", original, readFile(context, name))
            }
        }
    }

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(30_000) {
        while (!condition()) delay(50)
    }

    @Suppress("DEPRECATION")
    private fun isKeepAliveServiceRunning(context: Context): Boolean =
        context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == KeepAliveService::class.java.name }

    private fun readFile(context: Context, name: String): ByteArray? =
        File(context.filesDir, name).takeIf { it.exists() }?.readBytes()
}
