package jp.yukirawa.batterylogger.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MonitorUiState(
    val isRunning: Boolean = false,
    val isStopping: Boolean = false,
    val latest: LogRecord? = null,
    val bufferedRecords: Int = 0,
    val bufferedLogDates: Set<String> = emptySet(),
    val lastSavedAt: String? = null,
    val nextSampleAt: String? = null,
    val error: String? = null,
    val sessionId: String? = null,
)

object MonitorUiStateStore {
    private val mutableState = MutableStateFlow(MonitorUiState())
    val state: StateFlow<MonitorUiState> = mutableState.asStateFlow()

    private val mutablePermissionRevision = MutableStateFlow(0)
    val permissionRevision: StateFlow<Int> = mutablePermissionRevision.asStateFlow()

    fun update(transform: (MonitorUiState) -> MonitorUiState) {
        mutableState.update(transform)
    }

    fun permissionsChanged() {
        mutablePermissionRevision.update { it + 1 }
    }
}
