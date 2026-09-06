package top.onering.easyconnecter.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class LogRepository : EventLogger {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    override fun add(message: String) {
        val entry = "[${TIME_FORMAT.format(LocalDateTime.now())}] $message"
        _logs.update { current -> (current + entry).takeLast(MAX_LOG_ENTRIES) }
    }

    fun snapshot(): List<String> = _logs.value

    private companion object {
        const val MAX_LOG_ENTRIES = 1_000
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    }
}
