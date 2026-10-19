package top.onering.easyconnecter.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.onering.easyconnecter.model.ConnectionOutcome

enum class ConnectionPhase {
    IDLE,
    CHECKING,
    CONNECTED,
    RETRYING,
    PAUSED,
    NO_ACCOUNTS
}

data class RuntimeStatus(
    val serviceRunning: Boolean = false,
    val message: String = "空闲",
    val accountName: String? = null,
    val phase: ConnectionPhase = ConnectionPhase.IDLE
)

internal fun ConnectionOutcome.toRuntimeStatus(serviceRunning: Boolean): RuntimeStatus = when (this) {
    is ConnectionOutcome.Connected -> RuntimeStatus(
        serviceRunning = serviceRunning,
        message = if (alreadyConnected) "网络正常" else "${carrier?.displayName.orEmpty()}登录成功",
        accountName = account?.let { it.name.ifBlank { it.studentId } },
        phase = ConnectionPhase.CONNECTED
    )
    is ConnectionOutcome.AccountRemoved -> RuntimeStatus(
        serviceRunning = serviceRunning,
        message = "错误账号已移除，正在尝试下一个账号",
        phase = ConnectionPhase.RETRYING
    )
    is ConnectionOutcome.Failed -> RuntimeStatus(
        serviceRunning = serviceRunning,
        message = message,
        phase = ConnectionPhase.RETRYING
    )
    ConnectionOutcome.NoAccounts -> RuntimeStatus(
        message = "请导入账号后连接校园网",
        phase = ConnectionPhase.NO_ACCOUNTS
    )
}

class RuntimeRepository {
    private val _status = MutableStateFlow(RuntimeStatus())
    val status: StateFlow<RuntimeStatus> = _status.asStateFlow()

    fun update(status: RuntimeStatus) {
        _status.value = status
    }
}
