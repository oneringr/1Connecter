package top.onering.easyconnecter.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RuntimeStatus(
    val serviceRunning: Boolean = false,
    val message: String = "空闲",
    val accountName: String? = null
)

class RuntimeRepository {
    private val _status = MutableStateFlow(RuntimeStatus())
    val status: StateFlow<RuntimeStatus> = _status.asStateFlow()

    fun update(status: RuntimeStatus) {
        _status.value = status
    }
}
