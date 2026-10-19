package top.onering.easyconnecter

import android.app.Application
import android.content.Context
import top.onering.easyconnecter.data.AccountRepository
import top.onering.easyconnecter.data.ConfigRepository
import top.onering.easyconnecter.data.LogRepository
import top.onering.easyconnecter.data.RuntimeRepository
import top.onering.easyconnecter.data.SharedLogExporter
import top.onering.easyconnecter.domain.ConnectionCoordinator
import top.onering.easyconnecter.network.DrcomClient

class EasyConnecterApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(applicationContext)
    }
}

class AppContainer(context: Context) {
    val accounts = AccountRepository(context)
    val config = ConfigRepository(context, hasExistingAccounts = accounts.accounts.value.isNotEmpty())
    val logs = LogRepository()
    val logExporter = SharedLogExporter(context)
    val runtime = RuntimeRepository()
    val network = DrcomClient()
    val coordinator = ConnectionCoordinator(accounts, config, logs, network)
}

val Context.appContainer: AppContainer
    get() = (applicationContext as EasyConnecterApplication).container
