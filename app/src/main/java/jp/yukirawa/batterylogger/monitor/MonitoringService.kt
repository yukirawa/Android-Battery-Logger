package jp.yukirawa.batterylogger.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import jp.yukirawa.batterylogger.MainActivity
import jp.yukirawa.batterylogger.R
import jp.yukirawa.batterylogger.data.CsvLogStore
import jp.yukirawa.batterylogger.data.LogRecord
import jp.yukirawa.batterylogger.data.MonitorUiStateStore
import jp.yukirawa.batterylogger.data.SnapshotCollector
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MonitoringService : Service() {
    private lateinit var scheduler: ScheduledExecutorService
    private lateinit var collector: SnapshotCollector
    private val mainHandler = Handler(Looper.getMainLooper())
    private val buffer = ArrayDeque<LogRecord>()
    private val dischargeSamples = ArrayDeque<BatterySample>()
    private val bufferLock = Any()
    @Volatile private var stopRequested = false
    @Volatile private var bufferLimitPause = false
    @Volatile private var sessionId: String? = null
    @Volatile private var sampleScheduled = false
    private var nextRecordId = 1L
    private var nextSampleElapsedMs = 0L

    override fun onCreate() {
        super.onCreate()
        scheduler = Executors.newSingleThreadScheduledExecutor()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> requestStop(startId)
            ACTION_RETRY_STOP -> requestStop(startId)
            else -> startMonitoring()
        }
        return START_NOT_STICKY
    }

    private fun startMonitoring() {
        if (sessionId != null) {
            if (stopRequested && !bufferLimitPause) {
                // A user stop is in flight; a new start cancels it (finishStop re-checks stopRequested).
                synchronized(bufferLock) { stopRequested = false }
                MonitorUiStateStore.update {
                    it.copy(isRunning = true, isStopping = false, error = null, nextSampleAt = null)
                }
                if (!sampleScheduled) {
                    nextSampleElapsedMs = SystemClock.elapsedRealtime() +
                        TimeUnit.SECONDS.toMillis(SAMPLE_INTERVAL_SECONDS)
                    scheduleNextSample()
                }
            }
            promoteToForeground(
                if (stopRequested) "保存して監視を停止しています" else "監視を継続しています",
            )
            return
        }
        sessionId = UUID.randomUUID().toString()
        dischargeSamples.clear()
        stopRequested = false
        sampleScheduled = false
        nextSampleElapsedMs = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(SAMPLE_INTERVAL_SECONDS)
        promoteToForeground("記録を開始しています")
        MonitorUiStateStore.update {
            it.copy(
                isRunning = true,
                isStopping = false,
                error = null,
                sessionId = sessionId,
                nextSampleAt = null,
            )
        }
        collector = SnapshotCollector(applicationContext)
        scheduler.execute { recoverBeforeSampling() }
        scheduleFlush()
    }

    private fun recoverBeforeSampling() {
        if (stopRequested) return
        runCatching { CsvLogStore.recoverIncompleteTails(this) }
            .onSuccess {
                MonitorUiStateStore.update { it.copy(error = null) }
                sampleNow()
            }
            .onFailure { error ->
                val message = "CSV の末尾確認に失敗しました: ${error.localizedMessage}"
                MonitorUiStateStore.update { it.copy(error = message) }
                updateNotification("CSV を確認できません。30 秒後に再試行します")
                scheduler.schedule({ recoverBeforeSampling() }, RETRY_INTERVAL_SECONDS, TimeUnit.SECONDS)
            }
    }

    private fun sampleNow() {
        sampleScheduled = false
        if (stopRequested) return
        val sessionId = sessionId ?: return
        val recordId = nextRecordId
        val record = runCatching { collector.collect(sessionId, recordId) }
            .getOrElse { error ->
                MonitorUiStateStore.update { it.copy(error = error.message ?: "測定に失敗しました") }
                updateNotification("測定エラー。次の周期に再試行します")
                if (!stopRequested) scheduleNextSample()
                return
            }
        nextRecordId = recordId + 1

        updateDischargeSamples(record)
        val dateChanged = synchronized(bufferLock) {
            buffer.lastOrNull()?.let { it.logDate != record.logDate } == true
        }
        if (dateChanged) flushBuffer()
        var bufferedCount: Int
        var bufferedDates: Set<String>
        synchronized(bufferLock) {
            buffer.addLast(record)
            bufferedCount = buffer.size
            bufferedDates = bufferDates()
        }
        MonitorUiStateStore.update {
            it.copy(latest = record, bufferedRecords = bufferedCount, bufferedLogDates = bufferedDates)
        }
        updateNotification(notificationSummary(record, bufferedCount))

        if (bufferedCount >= MAX_BUFFER_RECORDS) {
            requestStopOnWorker("未保存データが上限に達しました。保存後に記録を停止します")
            return
        }

        if (!stopRequested) scheduleNextSample()
    }

    private fun scheduleNextSample() {
        synchronized(bufferLock) {
            if (sampleScheduled) return
            sampleScheduled = true
            val nowElapsed = SystemClock.elapsedRealtime()
            if (nextSampleElapsedMs <= nowElapsed) {
                nextSampleElapsedMs = nowElapsed + TimeUnit.SECONDS.toMillis(SAMPLE_INTERVAL_SECONDS)
            }
            val scheduledElapsedMs = nextSampleElapsedMs
            nextSampleElapsedMs += TimeUnit.SECONDS.toMillis(SAMPLE_INTERVAL_SECONDS)
            val delayMs = (scheduledElapsedMs - nowElapsed).coerceAtLeast(0L)
            val next = Instant.now().plusMillis(delayMs)
            MonitorUiStateStore.update {
                if (it.isRunning) it.copy(nextSampleAt = DateTimeFormatter.ISO_INSTANT.format(next)) else it
            }
            scheduler.schedule({ sampleNow() }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun scheduleFlush() {
        scheduler.schedule({
            if (!stopRequested) flushBuffer()
            scheduleFlush()
        }, FLUSH_INTERVAL_MINUTES, TimeUnit.MINUTES)
    }

    private fun flushBuffer(): Boolean = synchronized(bufferLock) {
        if (buffer.isEmpty()) return true
        while (buffer.isNotEmpty()) {
            val date = buffer.first().logDate
            val group = buffer.takeWhile { it.logDate == date }
            try {
                CsvLogStore.appendBatch(this, date.toString(), group)
            } catch (error: Exception) {
                val message = error.localizedMessage ?: error.javaClass.simpleName
                MonitorUiStateStore.update {
                    it.copy(
                        bufferedRecords = buffer.size,
                        bufferedLogDates = bufferDates(),
                        error = "CSV 保存エラー: $message",
                    )
                }
                updateNotification("CSV 保存エラー。${buffer.size} 件を保持して再試行します")
                return@synchronized false
            }
            repeat(group.size) { buffer.removeFirst() }
            MonitorUiStateStore.update {
                it.copy(
                    bufferedRecords = buffer.size,
                    bufferedLogDates = bufferDates(),
                    lastSavedAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now()),
                    error = null,
                )
            }
        }
        true
    }

    private fun requestStop(startId: Int) {
        if (sessionId == null) {
            stopSelfResult(startId)
            return
        }
        synchronized(bufferLock) {
            bufferLimitPause = false
            stopRequested = true
        }
        MonitorUiStateStore.update {
            it.copy(isRunning = false, isStopping = true, nextSampleAt = null)
        }
        updateNotification("保存して監視を停止しています")
        scheduler.execute { finishStop(startId) }
    }

    private fun requestStopOnWorker(reason: String) {
        bufferLimitPause = true
        stopRequested = true
        MonitorUiStateStore.update {
            it.copy(isRunning = false, isStopping = true, error = reason, nextSampleAt = null)
        }
        updateNotification("保存できるまで測定を一時停止しています")
        finishBufferPause()
    }

    private fun finishBufferPause() {
        if (!bufferLimitPause) return
        if (flushBuffer()) {
            val resumed = synchronized(bufferLock) {
                if (!bufferLimitPause) {
                    false
                } else {
                    bufferLimitPause = false
                    stopRequested = false
                    nextSampleElapsedMs = SystemClock.elapsedRealtime() +
                        TimeUnit.SECONDS.toMillis(SAMPLE_INTERVAL_SECONDS)
                    MonitorUiStateStore.update {
                        it.copy(
                            isRunning = true,
                            isStopping = false,
                            bufferedRecords = buffer.size,
                            bufferedLogDates = bufferDates(),
                            error = null,
                        )
                    }
                    true
                }
            }
            if (!resumed) return
            val latest = MonitorUiStateStore.state.value.latest
            val bufferedCount = synchronized(bufferLock) { buffer.size }
            updateNotification(latest?.let { notificationSummary(it, bufferedCount) } ?: "監視を継続しています")
            scheduleNextSample()
        } else {
            scheduler.schedule({ finishBufferPause() }, RETRY_INTERVAL_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun finishStop(startId: Int?) {
        if (flushBuffer()) {
            val cancelled = synchronized(bufferLock) {
                if (stopRequested) {
                    false
                } else {
                    // A new start arrived while we were flushing; abandon the stop.
                    true
                }
            }
            if (cancelled) {
                MonitorUiStateStore.update {
                    it.copy(isRunning = true, isStopping = false, error = null, nextSampleAt = null)
                }
                if (!sampleScheduled) {
                    nextSampleElapsedMs = SystemClock.elapsedRealtime() +
                        TimeUnit.SECONDS.toMillis(SAMPLE_INTERVAL_SECONDS)
                    scheduleNextSample()
                }
                return
            }
            MonitorUiStateStore.update {
                it.copy(
                    isRunning = false,
                    isStopping = false,
                    bufferedRecords = 0,
                    bufferedLogDates = emptySet(),
                    error = null,
                    nextSampleAt = null,
                )
            }
            mainHandler.post {
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (startId != null) stopSelfResult(startId) else stopSelf()
            }
        } else {
            MonitorUiStateStore.update { it.copy(isRunning = false, isStopping = true) }
            scheduler.schedule({ finishStop(startId = null) }, RETRY_INTERVAL_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun updateNotification(content: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(content))
    }

    private fun promoteToForeground(content: String) {
        startForeground(
            NOTIFICATION_ID,
            buildNotification(content),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun bufferDates(): Set<String> = buffer.map { it.logDate.toString() }.toSet()

    private fun buildNotification(content: String): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopAction = if (stopRequested) ACTION_RETRY_STOP else ACTION_STOP
        val stopLabel = if (stopRequested) "保存を再試行" else "監視を停止"
        val stopService = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            Intent(this, MonitoringService::class.java).setAction(stopAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setStyle(Notification.BigTextStyle().bigText(content))
            .addAction(
                Notification.Action.Builder(android.R.drawable.ic_media_pause, stopLabel, stopService).build(),
            )

        if (Build.VERSION.SDK_INT >= 36) {
            builder
                .setShortCriticalText(criticalNotificationText())
                .addExtras(Bundle().apply {
                    // This extra is the platform request flag for Android 16 Live Updates.
                    putBoolean("android.requestPromotedOngoing", true)
                })
        }

        return XiaomiHyperIsland.decorate(this, builder.build(), content)
    }

    private fun criticalNotificationText(): String = MonitorUiStateStore.state.value.latest?.batteryPct
        ?.let { "$it%" }
        ?: "監視中"

    private fun notificationSummary(record: LogRecord, bufferedCount: Int): String {
        val recordingStatus = if (MonitorUiStateStore.state.value.isStopping) "停止処理中" else "記録中"
        val battery = record.batteryPct?.let { "電池 $it%" } ?: "電池情報なし"
        val usageRate = averageDischargeRate(record)
        val persistence = if (bufferedCount == 0) "保存済み" else "未保存 $bufferedCount 件"
        return "$recordingStatus · $battery · 平均消費 $usageRate · $persistence"
    }

    private fun updateDischargeSamples(record: LogRecord) {
        val batteryPct = record.batteryPct
        if (record.batteryStatus != "discharging" || batteryPct == null) {
            dischargeSamples.clear()
            return
        }

        val elapsedMs = record.elapsedRealtimeMs
        val previous = dischargeSamples.lastOrNull()
        if (previous != null && elapsedMs - previous.elapsedRealtimeMs > MAX_SAMPLE_GAP_MS) {
            dischargeSamples.clear()
        }
        dischargeSamples.addLast(BatterySample(elapsedMs, batteryPct))

        val cutoffMs = elapsedMs - USAGE_RATE_WINDOW_MS
        while (dischargeSamples.size > 2 && dischargeSamples.elementAt(1).elapsedRealtimeMs <= cutoffMs) {
            dischargeSamples.removeFirst()
        }
    }

    private fun averageDischargeRate(record: LogRecord): String {
        if (record.batteryStatus == "charging") return "充電中"
        if (record.batteryStatus != "discharging") return "—"

        val first = dischargeSamples.firstOrNull() ?: return "計測中"
        val last = dischargeSamples.lastOrNull() ?: return "計測中"
        val elapsedMs = last.elapsedRealtimeMs - first.elapsedRealtimeMs
        if (elapsedMs < MIN_RATE_WINDOW_MS) return "計測中"

        val percentagePointsPerHour =
            (first.batteryPct - last.batteryPct).coerceAtLeast(0) * HOUR_MS.toDouble() / elapsedMs
        return String.format(Locale.US, "%.1f%%/時", percentagePointsPerHour)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.monitoring_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.monitoring_channel_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        stopRequested = true
        // Final flush/close must not run on the main thread (ANR risk with fsync).
        val future = runCatching {
            scheduler.submit(Callable {
                val ok = runCatching { flushBuffer() }.getOrDefault(false)
                if (::collector.isInitialized) collector.close()
                ok
            })
        }.getOrNull()
        scheduler.shutdown()
        val flushSucceeded = if (future == null) {
            // Executor rejected the task; nothing will run it.
            val ok = runCatching { flushBuffer() }.getOrDefault(false)
            if (::collector.isInitialized) runCatching { collector.close() }
            ok
        } else {
            // On timeout the worker still finishes flush/close in the background.
            runCatching { future.get(DESTROY_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                .getOrDefault(false)
        }
        if (MonitorUiStateStore.state.value.isRunning || MonitorUiStateStore.state.value.isStopping) {
            val bufferedCount = synchronized(bufferLock) { buffer.size }
            val bufferedDates = synchronized(bufferLock) { bufferDates() }
            MonitorUiStateStore.update {
                it.copy(
                    isRunning = false,
                    isStopping = false,
                    bufferedRecords = bufferedCount,
                    bufferedLogDates = bufferedDates,
                    error = if (flushSucceeded) {
                        "監視サービスが終了しました。前回の記録から空白がある可能性があります"
                    } else {
                        "監視サービス終了時の CSV 保存に失敗しました。未保存データが残っています"
                    },
                    nextSampleAt = null,
                )
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "jp.yukirawa.batterylogger.action.START"
        const val ACTION_STOP = "jp.yukirawa.batterylogger.action.STOP"
        private const val ACTION_RETRY_STOP = "jp.yukirawa.batterylogger.action.RETRY_STOP"
        private const val CHANNEL_ID = "battery_monitoring"
        private const val NOTIFICATION_ID = 1701
        private const val STOP_REQUEST_CODE = 1702
        private const val SAMPLE_INTERVAL_SECONDS = 60L
        private const val FLUSH_INTERVAL_MINUTES = 10L
        private const val RETRY_INTERVAL_SECONDS = 30L
        private const val DESTROY_FLUSH_TIMEOUT_SECONDS = 3L
        private const val MAX_BUFFER_RECORDS = 720
        private const val MIN_RATE_WINDOW_MS = 15 * 60 * 1000L
        private const val USAGE_RATE_WINDOW_MS = 60 * 60 * 1000L
        private const val MAX_SAMPLE_GAP_MS = 5 * 60 * 1000L
        private const val HOUR_MS = 60 * 60 * 1000L
    }

    private data class BatterySample(val elapsedRealtimeMs: Long, val batteryPct: Int)
}
