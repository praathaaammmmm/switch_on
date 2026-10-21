package com.switchon.customer.data.diagnostics

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import com.switchon.customer.data.DeviceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Reads the device through public Android APIs only. Anything a phone doesn't expose comes back as null or
 * "not present", which the rules report as unsupported. Every suspending call releases what it opened
 * (sensor listeners, AudioRecord, AudioTrack) in `finally`, so cancelling the caller's scope cleans up.
 */
class AndroidDiagnosticEngine(context: Context) : DiagnosticEngine {
    private val app = context.applicationContext

    override fun deviceInfo() = DeviceInfo(
        manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
        model = Build.MODEL,
        androidVersion = "Android ${Build.VERSION.RELEASE}",
        sdkInt = Build.VERSION.SDK_INT,
        deviceType = if (app.resources.configuration.smallestScreenWidthDp >= 600) DeviceType.TABLET else DeviceType.PHONE,
    )

    override fun software() = SoftwareSnapshot(
        securityPatch = Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() },
        buildType = Build.TYPE.takeIf { it.isNotBlank() },
        uptimeMs = SystemClock.elapsedRealtime(),
    )

    // ---- Memory ----

    override fun memory(): MemorySnapshot? = runCatching {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        MemorySnapshot(info.availMem, info.totalMem, info.lowMemory)
    }.getOrNull()

    // ---- Display ----

    override fun display(): DisplaySnapshot? = runCatching {
        val display = (app.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val mode = display.mode
        val resolver = app.contentResolver
        // Settings.System is readable without a permission. The legacy 0–255 scale is the one every phone exposes.
        val brightness = runCatching { Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()
        val adaptive = runCatching { Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE) }.getOrNull()
        DisplaySnapshot(
            widthPx = mode.physicalWidth.takeIf { it > 0 },
            heightPx = mode.physicalHeight.takeIf { it > 0 },
            densityDpi = app.resources.displayMetrics.densityDpi.takeIf { it > 0 },
            refreshHz = display.refreshRate.takeIf { it > 0 },
            maxRefreshHz = display.supportedModes.maxOfOrNull { it.refreshRate }?.takeIf { it > 0 },
            brightnessPercent = brightness?.let { (it.coerceIn(0, 255) * 100 + 127) / 255 },
            adaptiveBrightness = adaptive?.let { it == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC },
        )
    }.getOrNull()

    // ---- Battery ----

    override fun battery(): BatterySnapshot? {
        val intent: Intent = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        if (!intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)) return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val state = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> ChargeState.CHARGING
            BatteryManager.BATTERY_STATUS_DISCHARGING -> ChargeState.DISCHARGING
            BatteryManager.BATTERY_STATUS_FULL -> ChargeState.FULL
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> ChargeState.NOT_CHARGING
            else -> ChargeState.UNKNOWN
        }
        val source = when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
            BatteryManager.BATTERY_PLUGGED_AC -> PowerSource.AC
            BatteryManager.BATTERY_PLUGGED_USB -> PowerSource.USB
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> PowerSource.WIRELESS
            BatteryManager.BATTERY_PLUGGED_DOCK -> PowerSource.DOCK
            0 -> PowerSource.NONE
            else -> PowerSource.UNKNOWN
        }
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.let { it / 10f }
        val health = when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> BatteryHealth.GOOD
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> BatteryHealth.OVERHEAT
            BatteryManager.BATTERY_HEALTH_DEAD -> BatteryHealth.DEAD
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> BatteryHealth.OVER_VOLTAGE
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> BatteryHealth.FAILURE
            BatteryManager.BATTERY_HEALTH_COLD -> BatteryHealth.COLD
            else -> BatteryHealth.UNKNOWN
        }
        val bm = app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val charging = state == ChargeState.CHARGING
        return BatterySnapshot(
            levelPercent = if (level >= 0 && scale > 0) level * 100 / scale else null,
            state = state, source = source, temperatureC = temp, health = health,
            currentNowMa = bm?.let { current(it, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW, charging) },
            currentAverageMa = bm?.let { current(it, BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE, charging) },
            voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it > 0 },
            cycleCount = if (Build.VERSION.SDK_INT >= 34) intent.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1).takeIf { it >= 0 } else null,
            chargeCounterMah = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                ?.takeIf { it != Int.MIN_VALUE && it > 0 }?.let { it / 1000 },
        )
    }

    /**
     * Current in mA, or null when the phone doesn't expose it (Integer.MIN_VALUE or a constant 0). Most phones report
     * microamps, a few milliamps; the sign convention also varies, so while charging the magnitude is used.
     */
    private fun current(bm: BatteryManager, property: Int, charging: Boolean): Int? {
        val raw = bm.getIntProperty(property)
        if (raw == Int.MIN_VALUE || raw == 0) return null
        val ma = if (abs(raw) > 20_000) raw / 1000 else raw
        return if (charging) abs(ma) else ma
    }

    override suspend fun observeCharging(seconds: Int, onSample: (BatterySnapshot?) -> Unit): List<BatterySnapshot> {
        val samples = mutableListOf<BatterySnapshot>()
        repeat(seconds) {
            val s = battery()
            onSample(s)
            if (s != null) samples += s
            delay(1000)
        }
        return samples
    }

    // ---- Storage ----

    override fun storage(): StorageSnapshot? = runCatching {
        val fs = StatFs(Environment.getDataDirectory().path)
        StorageSnapshot(fs.availableBytes, fs.totalBytes)
    }.getOrNull()

    // ---- Sensors ----

    private val sensorManager get() = app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    /** The sensor to use for [k], or null when the phone doesn't provide one. Rotation falls back to the gyro-only variant. */
    private fun sensorFor(sm: SensorManager, k: SensorKind): Sensor? = when (k) {
        SensorKind.ACCELEROMETER -> sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        SensorKind.GYROSCOPE -> sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        SensorKind.PROXIMITY -> sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        SensorKind.LIGHT -> sm.getDefaultSensor(Sensor.TYPE_LIGHT)
        SensorKind.MAGNETOMETER -> sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        SensorKind.ROTATION -> sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    }

    override fun sensorsPresent(): Map<SensorKind, Boolean> {
        val sm = sensorManager ?: return SensorKind.entries.associateWith { false }
        return SensorKind.entries.associateWith { sensorFor(sm, it) != null }
    }

    /**
     * Listens at SENSOR_DELAY_NORMAL (about 5 readings a second, no high-frequency sampling) for [windowMs]. A sensor
     * "responds" when it delivers finite readings; motion sensors must deliver more than one. Listeners are always
     * unregistered, including on cancellation.
     */
    override suspend fun sensorResponse(kinds: Set<SensorKind>, windowMs: Long): Map<SensorKind, Boolean> {
        val sm = sensorManager ?: return kinds.associateWith { false }
        val counts = ConcurrentHashMap<SensorKind, Int>()
        val listeners = kinds.mapNotNull { k ->
            val sensor = sensorFor(sm, k) ?: return@mapNotNull null
            val l = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    if (e.values.isNotEmpty() && e.values.all { it.isFinite() }) counts.merge(k, 1, Int::plus)
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
            }
            sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            l
        }
        try {
            delay(windowMs)
        } finally {
            listeners.forEach { sm.unregisterListener(it) }
        }
        return kinds.associateWith { k ->
            val n = counts[k] ?: 0
            when (k) {
                // Proximity and light report on change only; one reading is a meaningful response.
                SensorKind.PROXIMITY, SensorKind.LIGHT -> n >= 1
                else -> n >= 2
            }
        }
    }

    // ---- Connectivity ----

    override fun connectivity(): ConnectivitySnapshot {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return ConnectivitySnapshot(false, null)
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return ConnectivitySnapshot(false, null)
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
            else -> Transport.OTHER
        }
        // Signal strength comes from the network's capabilities: no location or Wi-Fi permission is involved.
        val rssi = if (Build.VERSION.SDK_INT >= 29 && transport == Transport.WIFI)
            caps.signalStrength.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED && it < 0 } else null
        return ConnectivitySnapshot(
            online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            transport = transport,
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            wifiRssiDbm = rssi,
        )
    }

    // ---- Vibration ----

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= 31) (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else @Suppress("DEPRECATION") (app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
    }

    override val hasVibrator get() = vibrator?.hasVibrator() == true

    override fun vibrate() {
        runCatching { vibrator?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    override fun cancelVibration() { runCatching { vibrator?.cancel() } }

    // ---- Speaker ----

    override suspend fun playTone(durationMs: Int) = withContext(Dispatchers.Default) {
        val rate = 44_100
        val samples = rate * durationMs / 1000
        // 880 Hz sine with short fades so it doesn't click.
        val pcm = ShortArray(samples) { i ->
            val fade = minOf(1.0, i / 2000.0, (samples - i) / 2000.0)
            (sin(2 * PI * 880 * i / rate) * fade * Short.MAX_VALUE * 0.6).toInt().toShort()
        }
        val track = staticTrack(rate, pcm)
        try {
            track.play()
            delay(durationMs + 100L)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun staticTrack(rate: Int, pcm: ShortArray): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(pcm.size * 2)
        .build()
        .apply { write(pcm, 0, pcm.size) }

    // ---- Audio hardware ----

    override fun audio(): AudioSnapshot? = runCatching {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS).map { it.type }
        val route = if (Build.VERSION.SDK_INT >= 33) {
            val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            am.getAudioDevicesForAttributes(media).firstOrNull()?.type?.let(::routeOf)
        } else {
            // Before Android 13 there's no direct answer: headphones, when connected, take media audio.
            outputs.map(::routeOf).firstOrNull { it != AudioRoute.SPEAKER && it != AudioRoute.OTHER } ?: AudioRoute.SPEAKER.takeIf { AudioDeviceInfo.TYPE_BUILTIN_SPEAKER in outputs }
        }
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        AudioSnapshot(
            route = route,
            builtInSpeaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER in outputs,
            builtInMicrophone = AudioDeviceInfo.TYPE_BUILTIN_MIC in inputs || hasMicrophone,
            mediaVolumePercent = if (max > 0) am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max else null,
        )
    }.getOrNull()

    private fun routeOf(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.SPEAKER
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG -> AudioRoute.WIRED
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioRoute.BLUETOOTH
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> AudioRoute.USB
        else -> AudioRoute.OTHER
    }

    // ---- Microphone ----

    override val hasMicrophone get() = app.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)

    @SuppressLint("MissingPermission") // checked just below
    override suspend fun recordAndPlayBack(recordMs: Int, onPhase: (String) -> Unit): MicOutcome = withContext(Dispatchers.IO) {
        if (!hasMicrophone) return@withContext MicOutcome.NO_MICROPHONE
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return@withContext MicOutcome.PERMISSION_DENIED
        val rate = 16_000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return@withContext MicOutcome.COULD_NOT_RECORD
        val clip = ShortArray(rate * recordMs / 1000)
        val recorder = runCatching { AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2) }
            .getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED } ?: return@withContext MicOutcome.COULD_NOT_RECORD
        var read = 0
        try {
            onPhase("Recording… say something")
            recorder.startRecording()
            while (read < clip.size) {
                coroutineContext.ensureActive()
                val n = recorder.read(clip, read, minOf(min, clip.size - read))
                if (n <= 0) break
                read += n
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        if (read == 0) return@withContext MicOutcome.COULD_NOT_RECORD
        // Play back from memory only; the clip is never written anywhere and is cleared afterwards.
        val track = staticTrack(rate, clip.copyOf(read))
        try {
            onPhase("Playing it back…")
            track.play()
            delay(read * 1000L / rate + 150)
        } finally {
            runCatching { track.stop() }
            track.release()
            clip.fill(0)
        }
        MicOutcome.RECORDED_AND_PLAYED
    }

    // ---- Camera ----

    /** Characteristics only: reading them doesn't open the camera and needs no permission. */
    override fun cameraDetails(): List<CameraDetail> = runCatching {
        val cm = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cm.cameraIdList.mapNotNull { id ->
            val c = cm.getCameraCharacteristics(id)
            val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> CameraFacing.BACK
                CameraCharacteristics.LENS_FACING_FRONT -> CameraFacing.FRONT
                else -> return@mapNotNull null
            }
            val level = when (c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "Basic"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "Limited"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "Full"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "Advanced"
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "External"
                else -> null
            }
            val preview = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(SurfaceTexture::class.java)?.maxByOrNull { it.width.toLong() * it.height }
            CameraDetail(facing, c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE), level, preview?.width, preview?.height)
        }
    }.getOrDefault(emptyList())
}
