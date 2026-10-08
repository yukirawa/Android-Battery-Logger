package jp.yukirawa.batterylogger

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import jp.yukirawa.batterylogger.data.CsvLogStore
import jp.yukirawa.batterylogger.data.LogRecord
import jp.yukirawa.batterylogger.data.MonitorUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun BatteryLoggerScreen(
    state: MonitorUiState,
    permissionRevision: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestWifiAccess: () -> Unit,
    onRequestMobileAccess: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onOpenLocationSettings: () -> Unit,
    onExport: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    @Suppress("UNUSED_VARIABLE")
    val refreshPermissions = permissionRevision
    val latest = state.latest
    val usageAllowed = hasUsageAccess(context)
    val locationAllowed = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val phoneAllowed = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
        PackageManager.PERMISSION_GRANTED
    val hasWifiFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)
    val hasTelephonyFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    val csvFiles = CsvLogStore.listFiles(context)
    var selectedLogName by remember { mutableStateOf(csvFiles.firstOrNull()?.name.orEmpty()) }
    val selectedLog = csvFiles.firstOrNull { it.name == selectedLogName } ?: csvFiles.firstOrNull()
    var exportMenuExpanded by remember { mutableStateOf(false) }
    val selectedLogDate = selectedLog?.name
        ?.removePrefix("power_log_")
        ?.substringBefore("_schema-")
        ?.removeSuffix(".csv")
    val canExport = selectedLogDate != null && selectedLogDate !in state.bufferedLogDates

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Android 電池記録器", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = when {
                state.isStopping -> "停止中 — 未保存データを書き込んでいます"
                state.isRunning -> "監視中 · セッション ${state.sessionId?.take(8).orEmpty()}"
                else -> "停止中"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.isRunning) {
                Button(onClick = onStop, enabled = !state.isStopping) { Text("監視を停止") }
            } else {
                Button(onClick = onStart, enabled = !state.isStopping) { Text("監視を開始") }
            }
            Column {
                OutlinedButton(
                    onClick = { exportMenuExpanded = true },
                    enabled = csvFiles.isNotEmpty(),
                ) {
                    Text(selectedLog?.name?.removePrefix("power_log_")?.removeSuffix(".csv") ?: "日付を選択")
                }
                DropdownMenu(
                    expanded = exportMenuExpanded,
                    onDismissRequest = { exportMenuExpanded = false },
                ) {
                    csvFiles.forEach { file ->
                        DropdownMenuItem(
                            text = { Text(file.name.removePrefix("power_log_").removeSuffix(".csv")) },
                            onClick = {
                                selectedLogName = file.name
                                exportMenuExpanded = false
                            },
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = { selectedLog?.let { onExport(it.name) } },
                enabled = canExport,
            ) { Text("CSV を書き出す") }
        }

        state.error?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = message,
                    modifier = Modifier.padding(14.dp),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("現在の状態", style = MaterialTheme.typography.titleMedium)
                Text(
                    "バッテリー: ${latest?.batteryPct?.let { "$it%" } ?: "取得できません"} " +
                        "(${latest?.batteryPctStatus ?: "未測定"})",
                )
                Text("充電状態: ${latest?.batteryStatus ?: "未測定"}")
                Text(
                    "電圧: ${latest?.batteryVoltageMv?.let { "$it mV" } ?: "取得できません"} " +
                        "(${latest?.batteryVoltageStatus ?: "未測定"})",
                )
                Text(
                    "電流: ${latest?.batteryCurrentUa?.let { "$it μA" } ?: "取得できません"} " +
                        "(${latest?.batteryCurrentStatus ?: "未測定"})",
                )
                Text(
                    "推定電力: ${latest?.powerEstimatedW?.let { "%.3f W".format(it) } ?: "取得できません"} " +
                        "(${latest?.powerStatus ?: "未測定"})",
                )
                Text(
                    "バッテリー温度: ${latest?.batteryTempC?.let { "%.1f °C".format(it) } ?: "取得できません"} " +
                        "(${latest?.batteryTempStatus ?: "未測定"})",
                )
                Text(
                    "画面: ${latest?.screenOn?.let { if (it) "点灯" else "消灯" } ?: "取得できません"} " +
                        "(${latest?.screenStatus ?: "未測定"})",
                )
                Text(
                    "輝度設定値: ${latest?.brightnessSetting?.toString() ?: "未取得"} / 255 " +
                        "(${latest?.brightnessStatus ?: "未測定"})",
                )
                Text(
                    "空きメモリ: ${latest?.ramAvailableBytes?.let(::formatBytes) ?: "未測定"} / " +
                        (latest?.ramTotalBytes?.let(::formatBytes) ?: "未測定") +
                        " (${latest?.memoryStatus ?: "未測定"})",
                )
                Text("Wi-Fi: ${wifiSummary(latest)}")
                Text("モバイル回線: ${mobileSummary(latest)}")
                Text("利用中アプリ: ${latest?.foregroundLabel ?: latest?.foregroundPackage ?: "未取得"} (${latest?.foregroundStatus ?: "未測定"})")
                Text("最終測定: ${latest?.timestampUtc?.let(::formatTime) ?: "未測定"}")
                Text("次回予定: ${state.nextSampleAt?.let(::formatIsoTime) ?: "—"}")
                Text("未保存レコード: ${state.bufferedRecords}")
                Text("最終保存: ${state.lastSavedAt?.let(::formatIsoTime) ?: "まだ保存されていません"}")
                if (state.bufferedRecords > 0) {
                    Text("未保存データがある日付の CSV は、監視を停止して保存後に書き出してください。")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("任意の記録項目", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Wi-Fi SSID: ${when {
                        !hasWifiFeature -> "この端末は非対応"
                        locationAllowed -> "位置情報権限を許可済み"
                        else -> "権限なし"
                    }}",
                )
                if (hasWifiFeature) {
                    OutlinedButton(onClick = onRequestWifiAccess) { Text("SSID 記録の位置情報権限") }
                    if (locationAllowed) {
                        OutlinedButton(onClick = onOpenLocationSettings) { Text("位置情報サービスの設定") }
                    }
                }
                Text(
                    "モバイル回線: ${when {
                        !hasTelephonyFeature -> "この端末は非対応"
                        phoneAllowed -> "電話状態権限を許可済み"
                        else -> "権限なし"
                    }}",
                )
                if (hasTelephonyFeature) {
                    OutlinedButton(onClick = onRequestMobileAccess) { Text("モバイル回線情報の権限") }
                }
                Text("利用中アプリ: ${if (usageAllowed) "利用履歴アクセスを許可済み" else "未許可"}")
                OutlinedButton(onClick = onOpenUsageAccess) { Text("利用履歴アクセスを設定") }
            }
        }

        Text(
            "記録は端末内に保存します。Wi-Fi SSID の取得には位置情報権限と位置情報サービスが必要です。60 秒周期はベストエフォートで、省電力制御により遅れることがあります。",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
    }
}

private fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(AppOpsManager::class.java)
    return appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName,
    ) == AppOpsManager.MODE_ALLOWED
}

private fun wifiSummary(record: LogRecord?): String {
    if (record == null) return "未測定"
    val ssid = record.wifiSsid?.let { "\"$it\"" } ?: "SSID なし"
    val rssi = record.wifiRssiDbm?.let { " · $it dBm" }.orEmpty()
    return "$ssid$rssi (${record.wifiStatus})"
}

private fun mobileSummary(record: LogRecord?): String {
    if (record == null) return "未測定"
    val signal = record.mobileSignalDbm?.let { "$it dBm" } ?: "電波強度なし"
    val network = record.mobileNetworkType ?: "種別なし"
    return "$signal · $network (${record.mobileStatus})"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= (1L shl 30) -> "%.1f GiB".format(bytes.toDouble() / (1L shl 30))
    bytes >= (1L shl 20) -> "%.0f MiB".format(bytes.toDouble() / (1L shl 20))
    else -> "$bytes B"
}

private fun formatTime(instant: Instant): String = DateTimeFormatter
    .ofPattern("yyyy-MM-dd HH:mm:ss z")
    .withZone(ZoneId.systemDefault())
    .format(instant)

private fun formatIsoTime(value: String): String = runCatching {
    formatTime(Instant.parse(value))
}.getOrDefault(value)
