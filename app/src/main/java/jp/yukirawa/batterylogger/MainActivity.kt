package jp.yukirawa.batterylogger

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import jp.yukirawa.batterylogger.data.CsvLogStore
import jp.yukirawa.batterylogger.data.MonitorUiStateStore
import jp.yukirawa.batterylogger.monitor.MonitoringService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        MonitorUiStateStore.permissionsChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            val monitorState by MonitorUiStateStore.state.collectAsState()
            val permissionRevision by MonitorUiStateStore.permissionRevision.collectAsState()
            val scope = rememberCoroutineScope()
            var pendingExportName by rememberSaveable { mutableStateOf<String?>(null) }

            val notificationPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                if (granted) startMonitoring() else MonitorUiStateStore.update {
                    it.copy(error = "監視通知を表示できるよう通知権限を許可してください")
                }
            }
            val locationPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { MonitorUiStateStore.permissionsChanged() }
            val phonePermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { MonitorUiStateStore.permissionsChanged() }
            val exportDocument = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("text/csv"),
            ) { destination: Uri? ->
                if (destination == null) {
                    pendingExportName = null
                    return@rememberLauncherForActivityResult
                }
                val selectedName = pendingExportName
                pendingExportName = null
                val selectedFile = selectedName?.let { CsvLogStore.fileByName(context, it) }
                if (selectedFile == null) {
                    MonitorUiStateStore.update { it.copy(error = "選択した CSV が見つかりません", notice = null) }
                } else {
                    val logDate = selectedName
                        .removePrefix("power_log_")
                        .substringBefore("_schema-")
                        .removeSuffix(".csv")
                    val mayBeIncomplete = MonitorUiStateStore.state.value.bufferedLogDates.contains(logDate)
                    MonitorUiStateStore.update { it.copy(notice = null) }
                    scope.launch(Dispatchers.IO) {
                        runCatching { CsvLogStore.exportTo(context, selectedFile, destination) }
                            .onSuccess {
                                MonitorUiStateStore.update {
                                    it.copy(
                                        notice = if (mayBeIncomplete) {
                                            "CSV を書き出しました。書き出し中に追記されたレコードが含まれない可能性があります"
                                        } else {
                                            "CSV を書き出しました"
                                        },
                                    )
                                }
                            }
                            .onFailure { error ->
                                MonitorUiStateStore.update {
                                    it.copy(error = "CSV 書き出しエラー: ${error.localizedMessage}")
                                }
                            }
                    }
                }
            }

            MaterialTheme {
                Surface {
                    BatteryLoggerScreen(
                        state = monitorState,
                        permissionRevision = permissionRevision,
                        onStart = {
                            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                startMonitoring()
                            }
                        },
                        onStop = { stopMonitoring() },
                        onRequestWifiAccess = {
                            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        },
                        onRequestMobileAccess = {
                            phonePermission.launch(Manifest.permission.READ_PHONE_STATE)
                        },
                        onOpenUsageAccess = {
                            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        },
                        onOpenLocationSettings = {
                            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                        },
                        onOpenNotificationSettings = {
                            startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                            )
                        },
                        onExport = { name ->
                            pendingExportName = name
                            exportDocument.launch(name)
                        },
                    )
                }
            }
        }
    }

    private fun startMonitoring() {
        val intent = Intent(this, MonitoringService::class.java).setAction(MonitoringService.ACTION_START)
        startForegroundService(intent)
    }

    private fun stopMonitoring() {
        startService(
            Intent(this, MonitoringService::class.java).setAction(MonitoringService.ACTION_STOP),
        )
    }
}
