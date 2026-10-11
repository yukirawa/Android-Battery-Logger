package jp.yukirawa.batterylogger.monitor

import android.content.Context
import android.app.Notification
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import jp.yukirawa.batterylogger.data.MonitorUiStateStore
import org.json.JSONObject

/** Adds Xiaomi's documented HyperOS 3 island payload to the ongoing monitoring notification. */
internal object XiaomiHyperIsland {
    private const val FOCUS_PARAM = "miui.focus.param"
    private const val FOCUS_PICS = "miui.focus.pics"

    fun decorate(context: Context, notification: Notification, summary: String): Notification {
        if (!Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) return notification

        val protocolVersion = runCatching {
            Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)
        }.getOrDefault(0)
        // Xiaomi documents protocol 3 as the version that supports the HyperOS island.
        if (protocolVersion < 3) return notification

        val batteryPct = MonitorUiStateStore.state.value.latest?.batteryPct
        val islandText = batteryPct?.let { "電池 $it%" } ?: "電池情報を取得中"
        val compactSummary = summary.take(64)
        val imageKey = "miui.focus.pic_imageText"

        val imageTextInfo = JSONObject()
            .put("type", 1)
            .put("picInfo", JSONObject().put("type", 1).put("pic", imageKey))
            .put(
                "miui.focus.paramtextInfo",
                JSONObject()
                    .put("frontTitle", "端末監視")
                    .put("title", islandText)
                    .put("content", compactSummary)
                    .put("useHighLight", false),
            )

        val params = JSONObject()
            .put(
                "param_v2",
                JSONObject()
                    .put("protocol", 1)
                    .put("business", "battery_monitoring")
                    .put("updatable", true)
                    .put("enableFloat", false)
                    .put("timeout", 720)
                    .put("ticker", islandText)
                    .put("aodTitle", islandText)
                    .put(
                        "param_island",
                        JSONObject()
                            .put("islandProperty", 1)
                            .put("islandTimeout", 43200)
                            .put(
                                "bigIslandArea",
                                JSONObject()
                                    .put("imageTextInfoLeft", imageTextInfo)
                                    .put("picInfo", JSONObject().put("type", 1).put("pic", imageKey)),
                            )
                            .put(
                                "smallIslandArea",
                                JSONObject().put("picInfo", JSONObject().put("type", 1).put("pic", imageKey)),
                            ),
                    )
                    .put(
                        "baseInfo",
                        JSONObject()
                            .put("title", "端末を監視中")
                            .put("content", compactSummary)
                            .put("type", 2),
                    ),
            )

        notification.extras.putString(FOCUS_PARAM, params.toString())
        notification.extras.putBundle(
            FOCUS_PICS,
            Bundle().apply {
                putParcelable(IMAGE_TEXT_KEY, Icon.createWithResource(context, android.R.drawable.ic_lock_idle_charging))
            },
        )
        return notification
    }

    private const val IMAGE_TEXT_KEY = "miui.focus.pic_imageText"
}
