package jp.yukirawa.batterylogger.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.telephony.CellSignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import java.util.concurrent.Executor

data class NetworkSnapshot(
    val wifiSsid: String?,
    val wifiRssiDbm: Int?,
    val wifiStatus: String,
    val wifiAgeS: Long?,
    val mobileSignalDbm: Int?,
    val mobileNetworkType: String?,
    val mobileStatus: String,
    val mobileAgeS: Long?,
)

/** Keeps the latest Wi-Fi and cellular state from system callbacks for periodic snapshots. */
class NetworkStateMonitor(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
    private val telephony = appContext.getSystemService(TelephonyManager::class.java)
    private val mainExecutor: Executor = appContext.mainExecutor

    @Volatile private var wifiState = WifiState(null, null, "unknown", null)
    @Volatile private var mobileState = MobileState(null, null, "unknown", null)
    @Volatile private var registeredWifi = false
    @Volatile private var registeredTelephony = false
    private var lastWifiLocationPermission: Boolean? = null
    private var lastWifiLocationEnabled: Boolean? = null
    private val wifiNetworks = mutableMapOf<Network, WifiState>()
    private val wifiNetworkValidated = mutableMapOf<Network, Boolean>()
    private var telephonyCallback: TelephonyCallback? = null

    private val wifiCallback = object : ConnectivityManager.NetworkCallback(
        ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO,
    ) {
        override fun onAvailable(network: Network) = Unit

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            updateWifi(network, capabilities)
        }

        override fun onLost(network: Network) {
            removeWifiNetwork(network)
        }
    }

    @Synchronized
    fun start() {
        if (!registeredWifi) {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            registeredWifi = runCatching {
                connectivity.registerNetworkCallback(request, wifiCallback)
                true
            }.getOrDefault(false)
        }
        refreshTelephonyAccess()
    }

    fun snapshot(): NetworkSnapshot {
        refreshTelephonyAccess()
        val now = SystemClock.elapsedRealtime()

        val hasLocationPermission = appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val locationEnabled = runCatching {
            appContext.getSystemService(android.location.LocationManager::class.java).isLocationEnabled
        }.getOrDefault(false)
        refreshWifiForAccess(hasLocationPermission, locationEnabled)
        val hasWifiFeature = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)
        val wifi = wifiState
        val wifiAgeS = wifi.updatedAtElapsedMs
            ?.let { ((now - it).coerceAtLeast(0L)) / 1_000L }
        val wifiStatus = when {
            !hasWifiFeature -> "unsupported"
            wifi.status == "disconnected" -> "disconnected"
            !hasLocationPermission -> "permission_denied"
            (wifi.ssid != null || wifi.rssi != null) &&
                wifiAgeS != null && wifiAgeS > CACHE_STALE_AFTER_SECONDS -> "stale"
            !locationEnabled -> "unknown"
            else -> wifi.status
        }

        val mobile = mobileState
        val mobileAgeS = mobile.updatedAtElapsedMs
            ?.let { ((now - it).coerceAtLeast(0L)) / 1_000L }
        val hasTelephonyFeature = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        val hasPhonePermission = appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        val mobileStatus = when {
            !hasTelephonyFeature -> "unsupported"
            !hasPhonePermission -> "permission_denied"
            (mobile.signalDbm != null || mobile.networkType != null) &&
                mobileAgeS != null && mobileAgeS > CACHE_STALE_AFTER_SECONDS -> "stale"
            else -> mobile.status
        }

        return NetworkSnapshot(
            wifiSsid = wifi.ssid.takeIf { hasLocationPermission && locationEnabled && wifiStatus in USABLE_CACHE_STATUSES },
            wifiRssiDbm = wifi.rssi.takeIf { hasWifiFeature && wifi.status != "disconnected" },
            wifiStatus = wifiStatus,
            wifiAgeS = wifiAgeS,
            mobileSignalDbm = mobile.signalDbm.takeIf { hasTelephonyFeature && mobileStatus in USABLE_CACHE_STATUSES },
            mobileNetworkType = mobile.networkType.takeIf { hasTelephonyFeature && mobileStatus in USABLE_CACHE_STATUSES },
            mobileStatus = mobileStatus,
            mobileAgeS = mobileAgeS,
        )
    }

    @Synchronized
    private fun refreshTelephonyAccess() {
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) {
            mobileState = MobileState(null, null, "unsupported", null)
            return
        }
        val permitted = appContext.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!permitted) {
            if (registeredTelephony) {
                runCatching { telephony.unregisterTelephonyCallback(telephonyCallback!!) }
                registeredTelephony = false
                telephonyCallback = null
            }
            return
        }
        if (registeredTelephony) return

        val callback = object : TelephonyCallback(),
            TelephonyCallback.SignalStrengthsListener,
            TelephonyCallback.DataConnectionStateListener {
            override fun onSignalStrengthsChanged(signalStrength: android.telephony.SignalStrength) {
                val signal = signalStrength.cellSignalStrengths
                    .map(CellSignalStrength::getDbm)
                    .filter { it in -160..0 }
                    .maxOrNull()
                updateMobileSignal(signal)
            }

            override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
                updateMobileNetwork(networkTypeName(networkType))
            }
        }

        runCatching {
            telephony.registerTelephonyCallback(mainExecutor, callback)
            telephonyCallback = callback
            registeredTelephony = true
            updateMobileValues(
                signalDbm = telephony.signalStrength?.cellSignalStrengths
                    ?.map(CellSignalStrength::getDbm)
                    ?.filter { it in -160..0 }
                    ?.maxOrNull(),
                networkType = networkTypeName(telephony.dataNetworkType),
            )
        }.onFailure {
            mobileState = MobileState(null, null, "error", SystemClock.elapsedRealtime())
        }
    }

    @Synchronized
    private fun refreshWifiForAccess(locationPermission: Boolean, locationEnabled: Boolean) {
        if (lastWifiLocationPermission == locationPermission && lastWifiLocationEnabled == locationEnabled) return
        val networks = wifiNetworks.keys.toList()
        if (networks.isEmpty()) {
            lastWifiLocationPermission = locationPermission
            lastWifiLocationEnabled = locationEnabled
            return
        }
        var allUpdated = true
        networks.forEach { network ->
            val capabilities = runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull()
            if (capabilities == null) {
                allUpdated = false
            } else {
                updateWifi(network, capabilities)
            }
        }
        if (allUpdated) {
            lastWifiLocationPermission = locationPermission
            lastWifiLocationEnabled = locationEnabled
        }
    }

    @Synchronized
    private fun updateWifi(network: Network, capabilities: NetworkCapabilities) {
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
        val info = capabilities.transportInfo as? WifiInfo
        val rssi = info?.rssi?.takeIf { it in -126..0 }
        val locationAllowed = appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val locationEnabled = runCatching {
            appContext.getSystemService(android.location.LocationManager::class.java).isLocationEnabled
        }.getOrDefault(false)
        val ssid = info?.ssid
            ?.takeUnless { it == WifiManager.UNKNOWN_SSID || it == "<unknown ssid>" }
            ?.removeSurrounding("\"")
        val status = when {
            !locationAllowed -> "permission_denied"
            !locationEnabled -> "unknown"
            info == null || ssid == null -> "unknown"
            else -> "ok"
        }
        wifiNetworks[network] = WifiState(
            ssid = ssid.takeIf { status == "ok" },
            rssi = rssi,
            status = status,
            updatedAtElapsedMs = SystemClock.elapsedRealtime(),
        )
        wifiNetworkValidated[network] = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        lastWifiLocationPermission = locationAllowed
        lastWifiLocationEnabled = locationEnabled
        publishWifiState()
    }

    @Synchronized
    private fun removeWifiNetwork(network: Network) {
        wifiNetworks.remove(network)
        wifiNetworkValidated.remove(network)
        publishWifiState()
    }

    private fun publishWifiState() {
        val selected = wifiNetworks.entries
            .maxWithOrNull(
                compareBy<Map.Entry<Network, WifiState>> { wifiNetworkValidated[it.key] == true }
                    .thenBy { it.value.updatedAtElapsedMs ?: 0L },
            )
        wifiState = selected?.value
            ?: WifiState(null, null, "disconnected", SystemClock.elapsedRealtime())
    }

    @Synchronized
    private fun updateMobileSignal(signalDbm: Int?) =
        updateMobileValues(signalDbm, mobileState.networkType)

    @Synchronized
    private fun updateMobileNetwork(networkType: String?) =
        updateMobileValues(mobileState.signalDbm, networkType)

    @Synchronized
    private fun updateMobileValues(signalDbm: Int?, networkType: String?) {
        val type = networkType?.takeUnless { it == "unknown" }
        val signal = signalDbm?.takeIf { it in -160..0 }
        val status = when {
            signal != null || type != null -> "ok"
            runCatching { telephony.simState == TelephonyManager.SIM_STATE_ABSENT }.getOrDefault(false) ->
                "disconnected"
            else -> "unknown"
        }
        mobileState = MobileState(signal, type, status, SystemClock.elapsedRealtime())
    }

    override fun close() {
        if (registeredWifi) runCatching { connectivity.unregisterNetworkCallback(wifiCallback) }
        if (registeredTelephony) {
            runCatching { telephony.unregisterTelephonyCallback(telephonyCallback!!) }
        }
        registeredWifi = false
        registeredTelephony = false
        telephonyCallback = null
    }

    private fun networkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
        TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
        TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
        TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
        TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO_0"
        TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO_A"
        TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
        TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
        TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
        TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
        TelephonyManager.NETWORK_TYPE_IDEN -> "IDEN"
        TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO_B"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_EHRPD -> "EHRPD"
        TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPAP"
        TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "TD_SCDMA"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
        TelephonyManager.NETWORK_TYPE_NR -> "NR"
        else -> "unknown"
    }

    private data class WifiState(
        val ssid: String?,
        val rssi: Int?,
        val status: String,
        val updatedAtElapsedMs: Long?,
    )

    private data class MobileState(
        val signalDbm: Int?,
        val networkType: String?,
        val status: String,
        val updatedAtElapsedMs: Long?,
    )

    companion object {
        private const val CACHE_STALE_AFTER_SECONDS = 5 * 60L
        private val USABLE_CACHE_STATUSES = setOf("ok", "stale")
    }
}
