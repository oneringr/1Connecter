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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import top.onering.easyconnecter.MainActivity
import top.onering.easyconnecter.R
import top.onering.easyconnecter.appContainer
import top.onering.easyconnecter.data.RuntimeStatus
import top.onering.easyconnecter.model.ConnectionOutcome

class KeepAliveService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitorJob: Job? = null
    private var lastAccountName: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground("正在启动校园网监测")
        if (intent?.action == ACTION_STOP) {
            serviceScope.launch {
                appContainer.config.setKeepAlive(false)
                stopMonitoring()
            }
            return START_NOT_STICKY
        }

        if (!appContainer.config.keepAlive.value || appContainer.accounts.accounts.value.isEmpty()) {
            stopMonitoring()
            return START_NOT_STICKY
        }

        if (monitorJob?.isActive != true) startMonitoring()
        return START_STICKY
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        appContainer.runtime.update(RuntimeStatus(serviceRunning = false, message = "服务模式已停止"))
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startMonitoring() {
        appContainer.runtime.update(RuntimeStatus(serviceRunning = true, message = "正在监测校园网"))
        monitorJob = serviceScope.launch {
            try {
                appContainer.coordinator.runKeepAlive { outcome ->
                    val status = outcome.toRuntimeStatus()
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
                appContainer.runtime.update(RuntimeStatus(serviceRunning = false, message = message))
            } finally {
                if (!appContainer.config.keepAlive.value || appContainer.accounts.accounts.value.isEmpty()) {
                    stopSelf()
                }
            }
        }
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        appContainer.runtime.update(RuntimeStatus(serviceRunning = false, message = "服务模式已停止"))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ConnectionOutcome.toRuntimeStatus(): RuntimeStatus {
        if (this is ConnectionOutcome.Connected && account != null) {
            lastAccountName = account.name.ifBlank { account.studentId }
        }
        return when (this) {
            is ConnectionOutcome.Connected -> RuntimeStatus(
                serviceRunning = true,
                message = if (alreadyConnected) "网络正常，持续监测中" else "${carrier?.displayName.orEmpty()}登录成功",
                accountName = lastAccountName
            )
            is ConnectionOutcome.AccountRemoved -> RuntimeStatus(
                true,
                "错误账号已移除",
                account.name.ifBlank { account.studentId }
            )
            is ConnectionOutcome.Failed -> RuntimeStatus(true, message, lastAccountName)
            ConnectionOutcome.NoAccounts -> RuntimeStatus(false, "没有可用账号", lastAccountName)
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
        private const val CHANNEL_ID = "campus_keep_alive"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "top.onering.easyconnecter.action.START_KEEP_ALIVE"
        private const val ACTION_STOP = "top.onering.easyconnecter.action.STOP_KEEP_ALIVE"

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }
    }
}
