package app.linkfill.find

import app.linkfill.core.find.Source

/** Whether a ranging technology can be used right now, as reported by the platform. */
enum class Availability { ENABLED, NOT_SUPPORTED, DISABLED_USER, DISABLED_REGULATORY, DISABLED_POLICY, UNKNOWN }

enum class Tech(val label: String, val source: Source) {
    UWB("Ultra-wideband", Source.UWB),
    BLE_CS("Bluetooth Channel Sounding", Source.BLE_CHANNEL_SOUNDING),
    BLE_RSSI("Bluetooth RSSI ranging", Source.BLE_RSSI_RANGING),
    WIFI_RTT("Wi-Fi NAN round-trip time", Source.WIFI_RTT),
}

/** What this phone can do, resolved at run time. Nothing is assumed from the phone model or the Bluetooth version. */
data class RangingCaps(
    /** Android version reports the Ranging module (Android 16, API 36, or newer). */
    val apiPresent: Boolean,
    val sdkInt: Int,
    val permissionGranted: Boolean,
    /** The platform answered the capabilities query (it is delivered asynchronously). */
    val queried: Boolean,
    val tech: Map<Tech, Availability>,
    val uwbAzimuth: Boolean,
    val uwbElevation: Boolean,
    val uwbBackground: Boolean,
) {
    val usable: List<Tech> get() = tech.filter { it.value == Availability.ENABLED }.keys.toList()

    /** True when at least one real ranging technology is enabled. BLE RSSI ranging alone does not give precise distance. */
    val anyPrecise: Boolean get() = Tech.UWB in usable || Tech.BLE_CS in usable || Tech.WIFI_RTT in usable

    fun summary(): String = when {
        !apiPresent -> "Android $sdkInt has no Ranging module (needs Android 16)"
        !permissionGranted -> "Ranging permission not granted"
        !queried -> "Checking ranging hardware..."
        else -> tech.entries.joinToString("; ") { "${it.key.label}: ${it.value.name.lowercase().replace('_', ' ')}" } +
            (if (uwbAzimuth) "; UWB azimuth supported" else "")
    }

    companion object {
        val NONE = RangingCaps(false, 0, false, false, emptyMap(), false, false, false)
    }
}

/** Free-text status for diagnostics (never shown as a measurement). */
data class RangingStatus(val text: String, val atMs: Long)
