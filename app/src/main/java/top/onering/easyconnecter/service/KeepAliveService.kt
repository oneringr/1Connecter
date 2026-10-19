package top.onering.easyconnecter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.onering.easyconnecter.MainActivity
import top.onering.easyconnecter.R
import top.onering.easyconnecter.appContainer
import top.onering.easyconnecter.data.RuntimeStatus
import top.onering.easyconnecter.data.ConnectionPhase
import top.onering.easyconnecter.data.toRuntimeStatus
import top.onering.easyconnecter.model.ConnectionOutcome

class KeepAliveService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitorJob: Job? = null
    private val destroyed = CompletableDeferred<Unit>()
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runningInstance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground("正在启动校园网监测")
        if (intent?.action == ACTION_STOP) {
            if (!stopping) {
                stopping = true
                val previousJob = monitorJob
                previousJob?.cancel()
                serviceScope.launch {
                    previousJob?.join()
                    try {
                        appContainer.config.setKeepAlive(false)
                        stopMonitoring()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        val message = "保存服务模式失败: ${error.message ?: "未知错误"}"
                        appContainer.logs.add("❌ $message")
                        stopMonitoring(RuntimeStatus(message = message, phase = ConnectionPhase.PAUSED))
                    }
                }
            }
            return START_NOT_STICKY
        }

        if (stopping) return START_NOT_STICKY

        if (!appContainer.config.onboardingCompleted.value || appContainer.coordinator.connectionsPaused || !appContainer.config.keepAlive.value ||
            appContainer.accounts.accounts.value.isEmpty()) {
            stopMonitoring()
            return START_NOT_STICKY
        }

        if (monitorJob?.isActive != true) startMonitoring()
        return START_STICKY
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        appContainer.runtime.update(stoppedRuntimeStatus())
        serviceScope.cancel()
        if (runningInstance === this) runningInstance = null
        destroyed.complete(Unit)
        super.onDestroy()
    }

    private fun startMonitoring() {
        appContainer.runtime.update(
            RuntimeStatus(serviceRunning = true, message = "正在检查校园网连接", phase = ConnectionPhase.CHECKING)
        )
        monitorJob = serviceScope.launch {
            try {
                appContainer.config.ensureOnboardingInitialized()
                appContainer.coordinator.runKeepAlive(onChecking = {
                    val status = RuntimeStatus(
                        serviceRunning = true,
                        message = "正在检查校园网连接",
                        phase = ConnectionPhase.CHECKING
                    )
                    appContainer.runtime.update(status)
                    updateNotification(status.message, status.accountName)
                }) { outcome ->
                    val status = outcome.toRuntimeStatus(serviceRunning = true)
                    appContainer.runtime.update(status)
                    updateNotification(status.message, status.accountName)
                    if (outcome == ConnectionOutcome.NoAccounts) {
                        appContainer.logs.add("没有可用账号，服务已暂停，保留服务模式开关")
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = "服务异常: ${error.message ?: "未知错误"}"
                appContainer.logs.add("❌ $message")
                appContainer.runtime.update(RuntimeStatus(message = message, phase = ConnectionPhase.PAUSED))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } finally {
                if (!appContainer.config.keepAlive.value || appContainer.accounts.accounts.value.isEmpty()) {
                    stopSelf()
                }
            }
        }
    }

    private fun stopMonitoring(status: RuntimeStatus = stoppedRuntimeStatus()) {
        stopping = true
        monitorJob?.cancel()
        monitorJob = null
        appContainer.runtime.update(status)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stoppedRuntimeStatus(): RuntimeStatus {
        val previous = appContainer.runtime.status.value
        return when {
            appContainer.accounts.accounts.value.isEmpty() ->
                ConnectionOutcome.NoAccounts.toRuntimeStatus(serviceRunning = false)
            !appContainer.config.onboardingCompleted.value ->
                RuntimeStatus(message = "请完成首次设置后开始连接", phase = ConnectionPhase.PAUSED)
            !previous.serviceRunning && previous.phase == ConnectionPhase.PAUSED -> previous
            appContainer.coordinator.connectionsPaused ->
                RuntimeStatus(message = "连接已暂停", phase = ConnectionPhase.PAUSED)
            else -> RuntimeStatus(message = "服务模式已停止", phase = ConnectionPhase.IDLE)
        }
    }

    private fun startInForeground(message: String) {
        val notification = buildNotification(message)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(message: String, accountName: String?) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message, accountName))
    }

    private fun buildNotification(message: String, accountName: String? = null): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, KeepAliveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val campus = appContainer.config.campus.value.displayName
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("1Connecter · $campus")
            .setContentText(accountName?.let { "$message · $it" } ?: message)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "停止保活", stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "校园网保活",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示校园网连接和自动重连状态"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private var runningInstance: KeepAliveService? = null
        private const val CHANNEL_ID = "campus_keep_alive"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "top.onering.easyconnecter.action.START_KEEP_ALIVE"
        private const val ACTION_STOP = "top.onering.easyconnecter.action.STOP_KEEP_ALIVE"

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.appContainer.coordinator.pauseConnections()
            context.stopService(Intent(context, KeepAliveService::class.java))
        }

        suspend fun stopAndAwait(context: Context) = withContext(Dispatchers.Main.immediate) {
            val previous = runningInstance
            val previousJob = previous?.monitorJob
            previousJob?.cancel()
            stop(context)
            previousJob?.join()
            previous?.destroyed?.await()
        }
    }
}
