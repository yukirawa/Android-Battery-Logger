package jp.yukirawa.batterylogger.data

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class SnapshotCollector(private val context: Context) {
    private var previousCpuTimeMs: Long? = null
    private val networkMonitor = NetworkStateMonitor(context).apply { start() }
    private var lastUsageEventQueryMs: Long? = null
    private val resumedActivities = mutableMapOf<String, ActiveActivity>()

    fun close() = networkMonitor.close()

    fun collect(sessionId: String, recordId: Long): LogRecord {
        val now = Instant.now()
        val elapsed = SystemClock.elapsedRealtime()
        val offsetMinutes = ZoneId.systemDefault().rules.getOffset(now).totalSeconds / 60
        val batteryIntent = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val batteryManager = context.getSystemService(BatteryManager::class.java)

        val batteryPct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 }
        val batteryCurrentUa = batteryManager
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            .takeIf { it != Int.MIN_VALUE }
        val batteryVoltageMv = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            ?.takeIf { it > 0 }
        val batteryTempC = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            ?.takeIf { it >= 0 }
            ?.div(10.0)
        val currentStatus = batteryIntent?.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN,
        ) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val batteryStatus = when (currentStatus) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        }
        val powerAvailable = batteryCurrentUa != null && batteryVoltageMv != null
        val estimatedPowerW = if (powerAvailable) {
            batteryCurrentUa!!.toDouble() * batteryVoltageMv!!.toDouble() / 1_000_000_000.0
        } else {
            null
        }

        val powerManager = context.getSystemService(PowerManager::class.java)
        val screenOn = runCatching { powerManager.isInteractive }.getOrNull()
        val brightnessResult = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }
        val brightness = brightnessResult.getOrNull()

        val memoryInfo = ActivityManager.MemoryInfo()
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memoryResult = runCatching { activityManager.getMemoryInfo(memoryInfo) }
        val availableMemory = memoryInfo.availMem.takeIf { it > 0L }
        val totalMemory = memoryInfo.totalMem.takeIf { it > 0L }
        val memoryStatus = when {
            memoryResult.isFailure -> "error"
            availableMemory != null && totalMemory != null -> "ok"
            else -> "unsupported"
        }

        val (foregroundPackage, foregroundLabel, foregroundStatus, foregroundAge) = readForegroundApp(now)
        val network = networkMonitor.snapshot()

        val processCpu = Process.getElapsedCpuTime()
        val cpuDelta = previousCpuTimeMs?.let { (processCpu - it).takeIf { delta -> delta >= 0L } }
        previousCpuTimeMs = processCpu

        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return LogRecord(
            logDate = LocalDate.ofInstant(now, ZoneId.systemDefault()),
            sessionId = sessionId,
            recordId = recordId,
            deviceModel = Build.MODEL.orEmpty(),
            androidApiLevel = Build.VERSION.SDK_INT,
            osBuild = Build.DISPLAY.orEmpty(),
            appVersion = packageInfo.versionName.orEmpty(),
            timestampUtc = now,
            timezoneOffsetMin = offsetMinutes,
            elapsedRealtimeMs = elapsed,
            batteryPct = batteryPct,
            batteryPctStatus = if (batteryPct != null) "ok" else "unsupported",
            batteryStatus = batteryStatus,
            batteryTempC = batteryTempC,
            batteryTempStatus = batteryMetricStatus(batteryTempC != null, batteryIntent != null),
            batteryVoltageMv = batteryVoltageMv,
            batteryVoltageStatus = batteryMetricStatus(batteryVoltageMv != null, batteryIntent != null),
            batteryCurrentUa = batteryCurrentUa,
            batteryCurrentStatus = if (batteryCurrentUa != null) "ok" else "unsupported",
            powerEstimatedW = estimatedPowerW,
            powerStatus = if (powerAvailable) "ok" else "unsupported",
            screenOn = screenOn,
            screenStatus = if (screenOn != null) "ok" else "error",
            brightnessSetting = brightness,
            brightnessStatus = if (brightnessResult.isSuccess) "ok" else "error",
            ramAvailableBytes = availableMemory,
            ramTotalBytes = totalMemory,
            memoryStatus = memoryStatus,
            foregroundPackage = foregroundPackage,
            foregroundLabel = foregroundLabel,
            foregroundStatus = foregroundStatus,
            foregroundAgeS = foregroundAge,
            wifiSsid = network.wifiSsid,
            wifiRssiDbm = network.wifiRssiDbm,
            wifiStatus = network.wifiStatus,
            wifiAgeS = network.wifiAgeS,
            mobileSignalDbm = network.mobileSignalDbm,
            mobileNetworkType = network.mobileNetworkType,
            mobileStatus = network.mobileStatus,
            mobileAgeS = network.mobileAgeS,
            monitorCpuMsDelta = cpuDelta,
        )
    }

    private fun readForegroundApp(now: Instant): ForegroundData {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val permissionMode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        if (permissionMode != AppOpsManager.MODE_ALLOWED) {
            resumedActivities.clear()
            lastUsageEventQueryMs = null
            return ForegroundData(null, null, "permission_denied", null)
        }

        val usageStats = context.getSystemService(UsageStatsManager::class.java)
        val end = now.toEpochMilli()
        val from = lastUsageEventQueryMs?.minus(USAGE_EVENT_OVERLAP_MS)
            ?.coerceAtLeast(0L)
            ?: (end - INITIAL_USAGE_LOOKBACK_MS).coerceAtLeast(0L)
        val events = runCatching { usageStats.queryEvents(from, end) }
            .getOrElse { return ForegroundData(null, null, "error", null) }
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            if (!events.getNextEvent(event)) break
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    val activityClass = event.className.orEmpty()
                    resumedActivities["${event.packageName}/$activityClass"] =
                        ActiveActivity(event.packageName, event.timeStamp)
                }
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val activityClass = event.className.orEmpty()
                    if (activityClass.isEmpty()) {
                        resumedActivities.entries.removeAll { it.value.packageName == event.packageName }
                    } else {
                        resumedActivities.remove("${event.packageName}/$activityClass")
                    }
                }
            }
        }
        lastUsageEventQueryMs = end
        val foreground = resumedActivities.values.maxByOrNull { it.resumedAtMs }
            ?: return ForegroundData(null, null, "unknown", null)
        val packageName = foreground.packageName
        val ageSeconds = ((end - foreground.resumedAtMs).coerceAtLeast(0L)) / 1_000L
        val label = runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(packageName, 0),
            ).toString()
        }.getOrNull()
        return ForegroundData(packageName, label, "ok", ageSeconds)
    }

    private fun batteryMetricStatus(available: Boolean, batteryIntentAvailable: Boolean): String = when {
        available -> "ok"
        batteryIntentAvailable -> "unsupported"
        else -> "error"
    }

    private data class ForegroundData(
        val packageName: String?,
        val label: String?,
        val status: String,
        val ageSeconds: Long?,
    )

    private data class ActiveActivity(val packageName: String, val resumedAtMs: Long)

    companion object {
        private const val INITIAL_USAGE_LOOKBACK_MS = 24 * 60 * 60 * 1_000L
        private const val USAGE_EVENT_OVERLAP_MS = 2_000L
    }
}
