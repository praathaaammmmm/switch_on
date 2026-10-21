package com.switchon.customer.data.diagnostics

// Raw readings from the phone, as plain data. DiagnosticRules turns them into results; nothing here judges them.

enum class ChargeState(val label: String) { CHARGING("Charging"), DISCHARGING("On battery"), FULL("Full"), NOT_CHARGING("Plugged in, not charging"), UNKNOWN("Unknown") }

enum class PowerSource(val label: String) { AC("Wall charger"), USB("USB"), WIRELESS("Wireless charger"), DOCK("Dock"), NONE("Not plugged in"), UNKNOWN("Unknown") }

/** Battery health as Android reports it (BatteryManager.EXTRA_HEALTH). Not the same as wear or cycle count. */
enum class BatteryHealth(val label: String, val concerning: Boolean) {
    GOOD("Good", false), OVERHEAT("Overheating", true), DEAD("Dead", true), OVER_VOLTAGE("Over voltage", true),
    FAILURE("Unspecified failure", true), COLD("Cold", false), UNKNOWN("Not reported", false),
}

data class BatterySnapshot(
    val levelPercent: Int?,
    val state: ChargeState,
    val source: PowerSource,
    val temperatureC: Float?,
    val health: BatteryHealth,
    /** Only some phones expose these; null means not available. Positive is charging in mA. */
    val currentNowMa: Int?,
    val currentAverageMa: Int?,
    val voltageMv: Int? = null,
    /** Charge cycles, Android 14+ and only where the phone reports it. */
    val cycleCount: Int? = null,
    /** Charge left in the battery as the fuel gauge reports it (BATTERY_PROPERTY_CHARGE_COUNTER), in mAh. */
    val chargeCounterMah: Int? = null,
) {
    val chargingDetected get() = state == ChargeState.CHARGING || state == ChargeState.FULL || source !in setOf(PowerSource.NONE, PowerSource.UNKNOWN)
}

data class StorageSnapshot(val freeBytes: Long, val totalBytes: Long)

/** ActivityManager.MemoryInfo: memory available to apps right now, and whether Android itself considers it low. */
data class MemorySnapshot(val availableBytes: Long, val totalBytes: Long, val systemLowMemory: Boolean)

/** Display settings as reported. Nothing here can reveal screen damage. Null means not available. */
data class DisplaySnapshot(
    val widthPx: Int?,
    val heightPx: Int?,
    val densityDpi: Int?,
    val refreshHz: Float?,
    val maxRefreshHz: Float?,
    val brightnessPercent: Int?,
    val adaptiveBrightness: Boolean?,
)

/** Build details: device information, not a repair result. */
data class SoftwareSnapshot(
    /** As Android reports it, e.g. "2025-09-05". */
    val securityPatch: String?,
    /** Build.TYPE: "user" on retail phones, "userdebug" or "eng" on development builds. */
    val buildType: String?,
    val uptimeMs: Long?,
)

enum class SensorKind(val label: String) {
    ACCELEROMETER("Accelerometer"), GYROSCOPE("Gyroscope"), PROXIMITY("Proximity"), LIGHT("Light"), MAGNETOMETER("Magnetometer"),
    /** Android's fused orientation sensor; listed only where the phone provides it. */
    ROTATION("Rotation"),
}

enum class Transport(val label: String) { WIFI("Wi-Fi"), CELLULAR("Mobile data"), ETHERNET("Ethernet"), OTHER("Other network") }

data class ConnectivitySnapshot(
    val online: Boolean,
    val transport: Transport?,
    /** Android confirmed the network reaches the internet (NET_CAPABILITY_VALIDATED). */
    val validated: Boolean? = null,
    /** Wi-Fi RSSI from NetworkCapabilities.getSignalStrength (Android 10+, no location permission); null otherwise. */
    val wifiRssiDbm: Int? = null,
)

enum class CameraFacing(val label: String) { BACK("Rear"), FRONT("Front") }

/** What CameraManager reports about one camera, read without opening it (no permission needed). */
data class CameraDetail(
    val facing: CameraFacing,
    val flash: Boolean?,
    /** INFO_SUPPORTED_HARDWARE_LEVEL, e.g. "Full". */
    val hardwareLevel: String?,
    val maxPreviewWidth: Int?,
    val maxPreviewHeight: Int?,
)

enum class AudioRoute(val label: String) {
    SPEAKER("Built-in speaker"), WIRED("Wired headphones"), BLUETOOTH("Bluetooth audio"), USB("USB audio"), OTHER("Another output"),
}

data class AudioSnapshot(
    /** Where media sound is going now; null when the phone doesn't say. */
    val route: AudioRoute?,
    val builtInSpeaker: Boolean?,
    val builtInMicrophone: Boolean?,
    val mediaVolumePercent: Int?,
)

/** What the microphone check managed to do. */
enum class MicOutcome { RECORDED_AND_PLAYED, NO_MICROPHONE, PERMISSION_DENIED, COULD_NOT_RECORD }

/**
 * The phone-side half of the device check: everything that touches Android hardware APIs. Implementations must be
 * lifecycle-safe: suspending calls release sensors, the microphone and audio when they finish or are cancelled.
 */
interface DiagnosticEngine {
    fun deviceInfo(): DeviceInfo
    fun software(): SoftwareSnapshot?
    fun memory(): MemorySnapshot?
    fun display(): DisplaySnapshot?
    fun cameraDetails(): List<CameraDetail>
    fun audio(): AudioSnapshot?
    /** Null when the system reports no battery information at all. */
    fun battery(): BatterySnapshot?
    fun storage(): StorageSnapshot?
    fun sensorsPresent(): Map<SensorKind, Boolean>
    /** Listens briefly at normal (low) rate and reports which sensors produced meaningful readings. */
    suspend fun sensorResponse(kinds: Set<SensorKind>, windowMs: Long = 3000): Map<SensorKind, Boolean>
    fun connectivity(): ConnectivitySnapshot
    /** Samples the battery about once a second while the customer's charger is connected. */
    suspend fun observeCharging(seconds: Int = 12, onSample: (BatterySnapshot?) -> Unit = {}): List<BatterySnapshot>

    val hasVibrator: Boolean
    fun vibrate()
    fun cancelVibration()

    /** Plays a short generated tone through the speaker. */
    suspend fun playTone(durationMs: Int = 1200)

    val hasMicrophone: Boolean
    /** Records a short clip into memory, plays it back, then discards it. Never written to disk or uploaded. */
    suspend fun recordAndPlayBack(recordMs: Int = 3000, onPhase: (String) -> Unit = {}): MicOutcome

    fun cameras(): Set<CameraFacing> = cameraDetails().map { it.facing }.toSet()
}
