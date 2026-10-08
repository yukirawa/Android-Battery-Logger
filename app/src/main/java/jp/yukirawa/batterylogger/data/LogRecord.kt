package jp.yukirawa.batterylogger.data

import java.time.Instant
import java.time.LocalDate

data class LogRecord(
    val logDate: LocalDate,
    val sessionId: String,
    val recordId: Long,
    val deviceModel: String,
    val androidApiLevel: Int,
    val osBuild: String,
    val appVersion: String,
    val timestampUtc: Instant,
    val timezoneOffsetMin: Int,
    val elapsedRealtimeMs: Long,
    val batteryPct: Int?,
    val batteryPctStatus: String,
    val batteryStatus: String,
    val batteryTempC: Double?,
    val batteryTempStatus: String,
    val batteryVoltageMv: Int?,
    val batteryVoltageStatus: String,
    val batteryCurrentUa: Int?,
    val batteryCurrentStatus: String,
    val powerEstimatedW: Double?,
    val powerStatus: String,
    val screenOn: Boolean?,
    val screenStatus: String,
    val brightnessSetting: Int?,
    val brightnessStatus: String,
    val ramAvailableBytes: Long?,
    val ramTotalBytes: Long?,
    val memoryStatus: String,
    val foregroundPackage: String?,
    val foregroundLabel: String?,
    val foregroundStatus: String,
    val foregroundAgeS: Long?,
    val wifiSsid: String?,
    val wifiRssiDbm: Int?,
    val wifiStatus: String,
    val wifiAgeS: Long?,
    val mobileSignalDbm: Int?,
    val mobileNetworkType: String?,
    val mobileStatus: String,
    val mobileAgeS: Long?,
    val monitorCpuMsDelta: Long?,
) {
    fun toCsvRow(): String = listOf(
        SCHEMA_VERSION,
        sessionId,
        recordId,
        deviceModel,
        androidApiLevel,
        osBuild,
        appVersion,
        timestampUtc.toString(),
        timezoneOffsetMin,
        elapsedRealtimeMs,
        batteryPct,
        batteryPctStatus,
        batteryStatus,
        batteryTempC,
        batteryTempStatus,
        batteryVoltageMv,
        batteryVoltageStatus,
        batteryCurrentUa,
        batteryCurrentStatus,
        powerEstimatedW,
        powerStatus,
        screenOn,
        screenStatus,
        brightnessSetting,
        brightnessStatus,
        ramAvailableBytes,
        ramTotalBytes,
        memoryStatus,
        foregroundPackage,
        foregroundLabel,
        foregroundStatus,
        foregroundAgeS,
        wifiSsid,
        wifiRssiDbm,
        wifiStatus,
        wifiAgeS,
        mobileSignalDbm,
        mobileNetworkType,
        mobileStatus,
        mobileAgeS,
        monitorCpuMsDelta,
    ).joinToString(",") { value -> csvCell(value?.toString().orEmpty()) }

    private fun csvCell(value: String): String {
        val escaped = value.replace("\"", "\"\"")
        return if (escaped.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"$escaped\""
        } else {
            escaped
        }
    }

    companion object {
        const val SCHEMA_VERSION = "1.1"
        const val CSV_HEADER = "schema_version,session_id,record_id,device_model,android_api_level,os_build,app_version,timestamp_utc,timezone_offset_min,elapsed_realtime_ms,battery_pct,battery_pct_status,battery_status,battery_temp_c,battery_temp_status,battery_voltage_mv,battery_voltage_status,battery_current_ua,battery_current_status,power_estimated_w,power_status,screen_on,screen_status,brightness_setting,brightness_status,ram_available_bytes,ram_total_bytes,memory_status,foreground_package,foreground_label,foreground_status,foreground_age_s,wifi_ssid,wifi_rssi_dbm,wifi_status,wifi_age_s,mobile_signal_dbm,mobile_network_type,mobile_status,mobile_age_s,monitor_cpu_ms_delta"
    }
}
