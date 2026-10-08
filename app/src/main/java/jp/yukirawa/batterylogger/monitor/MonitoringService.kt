package jp.yukirawa.batterylogger.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
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
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MonitoringService : Service() {
    private lateinit var scheduler: ScheduledExecutorService
    private lateinit var collector: SnapshotCollector
    private val mainHandler = Handler(Looper.getMainLooper())
    private val buffer = ArrayDeque<LogRecord>()
    @Volatile private var stopRequested = false
    private var sessionId: String? = null
    private var nextRecordId = 1L
    private var nextSampleElapsedMs = 0L
    private var stopReason: String? = null

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
        if (sessionId != null) return
        sessionId = UUID.randomUUID().toString()
        stopRequested = false
        stopReason = null
        nextSampleElapsedMs = SystemClock.elapsedRealtime() + TimeUnit.SECONDS.toMillis(SAMPLE_INTERVAL_SECONDS)
        val notification = buildNotification("記録を開始しています")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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
        if (stopRequested) return
        val record = runCatching { collector.collect(sessionId ?: return, nextRecordId++) }
            .getOrElse { error ->
                MonitorUiStateStore.update { it.copy(error = error.message ?: "測定に失敗しました") }
                updateNotification("測定エラー。次の周期に再試行します")
                if (!stopRequested) scheduleNextSample()
                return
            }

        if (buffer.size >= MAX_BUFFER_RECORDS) {
            requestStopOnWorker("未保存データが上限に達しました。保存後に記録を停止します")
            return
        }
        if (buffer.lastOrNull()?.logDate?.let { it != record.logDate } == true) {
            flushBuffer()
        }
        buffer.addLast(record)
        MonitorUiStateStore.update {
            it.copy(latest = record, bufferedRecords = buffer.size, bufferedLogDates = bufferDates())
        }
        updateNotification(notificationSummary(record))

        if (buffer.size >= MAX_BUFFER_RECORDS) {
            requestStopOnWorker("未保存データが上限に達しました。保存後に記録を停止します")
            return
        }

        if (!stopRequested) scheduleNextSample()
    }

    private fun scheduleNextSample() {
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

    private fun scheduleFlush() {
        scheduler.schedule({
            if (!stopRequested) {
                flushBuffer()
                scheduleFlush()
            }
        }, FLUSH_INTERVAL_MINUTES, TimeUnit.MINUTES)
    }

    private fun flushBuffer(): Boolean {
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
                return false
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
        return true
    }

    private fun requestStop(startId: Int) {
        if (sessionId == null) {
            stopSelfResult(startId)
            return
        }
        stopRequested = true
        stopReason = null
        MonitorUiStateStore.update {
            it.copy(isRunning = false, isStopping = true, nextSampleAt = null)
        }
        updateNotification("保存して監視を停止しています")
        scheduler.execute { finishStop(startId) }
    }

    private fun requestStopOnWorker(reason: String) {
        stopRequested = true
        stopReason = reason
        MonitorUiStateStore.update {
            it.copy(isRunning = false, isStopping = true, error = reason, nextSampleAt = null)
        }
        updateNotification(reason)
        finishStop(startId = null)
    }

    private fun finishStop(startId: Int?) {
        if (flushBuffer()) {
            MonitorUiStateStore.update {
                it.copy(
                    isRunning = false,
                    isStopping = false,
                    bufferedRecords = 0,
                    bufferedLogDates = emptySet(),
                    error = stopReason,
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
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(android.R.drawable.ic_media_pause, stopLabel, stopService).build(),
            )
            .build()
    }

    private fun notificationSummary(record: LogRecord): String {
        val battery = record.batteryPct?.let { "電池 $it%" } ?: "電池情報なし"
        val power = record.powerEstimatedW?.let { " · ${"%.2f".format(it)} W" }.orEmpty()
        return "$battery$power · 未保存 ${buffer.size} 件"
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
        scheduler.shutdownNow()
        if (::collector.isInitialized) collector.close()
        if (MonitorUiStateStore.state.value.isRunning || MonitorUiStateStore.state.value.isStopping) {
            MonitorUiStateStore.update {
                it.copy(
                    isRunning = false,
                    isStopping = false,
                    bufferedRecords = 0,
                    bufferedLogDates = emptySet(),
                    error = "監視サービスが終了しました。前回の記録から空白がある可能性があります",
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
        private const val MAX_BUFFER_RECORDS = 720
    }
}
