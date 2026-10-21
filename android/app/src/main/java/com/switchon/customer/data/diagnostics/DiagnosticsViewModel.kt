package com.switchon.customer.data.diagnostics

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.switchon.customer.data.diagnostics.DiagnosticTest.*
import com.switchon.customer.data.nowIst
import java.time.LocalDateTime

enum class DiagnosticPhase { INTRO, RUNNING, RESULTS }

/** The numbered steps the customer sees. The first runs every automatic check; the rest each need an answer. */
enum class DiagnosticStep(val label: String, val tests: List<DiagnosticTest>) {
    AUTOMATIC("Automatic checks", listOf(DEVICE_INFO, MEMORY, BATTERY, STORAGE, DISPLAY_INFO, SENSORS, SENSOR_RESPONSE, CONNECTIVITY, CAMERA_INFO, AUDIO_INFO)),
    TOUCH_STEP("Touchscreen", listOf(TOUCH)),
    DISPLAY_STEP("Display", listOf(DISPLAY)),
    SPEAKER_STEP("Speaker", listOf(SPEAKER)),
    VIBRATION_STEP("Vibration", listOf(VIBRATION)),
    MICROPHONE_STEP("Microphone", listOf(MICROPHONE)),
    CAMERA_STEP("Camera", listOf(CAMERA)),
    CHARGING_STEP("Charging", listOf(CHARGING)),
}

/**
 * State for the device check: phase, current step, collected results and findings. Hardware work is exposed as
 * suspend functions that the screen runs in its own scope, so leaving the screen cancels them and the engine
 * releases sensors, audio and the microphone.
 */
class DiagnosticsViewModel(
    private val engine: DiagnosticEngine,
    private val clock: () -> LocalDateTime = ::nowIst,
) : ViewModel() {

    var phase by mutableStateOf(DiagnosticPhase.INTRO); private set
    var stepIndex by mutableStateOf(0); private set
    val step get() = DiagnosticStep.entries[stepIndex]
    val stepCount get() = DiagnosticStep.entries.size

    val results = mutableStateMapOf<DiagnosticTest, DiagnosticResult>()
    private val findings = mutableListOf<DiagnosticFinding>()

    /** What's happening right now, e.g. "Listening to sensors…"; null when idle. */
    var activity by mutableStateOf<String?>(null); private set
    /** Live charging readings while watching the charger. */
    var chargingLive by mutableStateOf<BatterySnapshot?>(null); private set
    var report by mutableStateOf<DiagnosticReport?>(null); private set

    val device: DeviceInfo by lazy { engine.deviceInfo() }
    val hasVibrator get() = engine.hasVibrator
    val hasMicrophone get() = engine.hasMicrophone
    val cameras: Set<CameraFacing> by lazy { engine.cameras() }

    fun start() {
        results.clear(); findings.clear(); report = null; activity = null; chargingLive = null
        stepIndex = 0
        phase = DiagnosticPhase.RUNNING
    }

    /** Back: one step at a time, then out to the intro. Returns false when there's nothing to go back to. */
    fun back(): Boolean = when {
        phase == DiagnosticPhase.RUNNING && stepIndex > 0 -> { stepIndex--; true }
        phase == DiagnosticPhase.RUNNING -> { phase = DiagnosticPhase.INTRO; true }
        else -> false
    }

    fun record(result: DiagnosticResult) { results[result.test] = result }

    /** Marks whatever's unanswered in this step as skipped and moves on. */
    fun skipStep(reason: String = "Skipped") {
        step.tests.filter { it !in results }.forEach { record(DiagnosticRules.skipped(it, reason)) }
        next()
    }

    fun next() {
        activity = null
        if (stepIndex < stepCount - 1) stepIndex++ else finish()
    }

    fun finish() {
        report = DiagnosticRules.buildReport(device, results.toMap(), findings.toList(), clock())
        phase = DiagnosticPhase.RESULTS
    }

    // ---- Automatic checks ----

    val automaticDone get() = DiagnosticStep.AUTOMATIC.tests.all { it in results }

    suspend fun runAutomatic() {
        if (automaticDone) return
        try {
            activity = "Reading device details…"
            record(DiagnosticRules.deviceInfo(device, engine.software()))
            activity = "Checking memory…"
            val memory = engine.memory()
            record(DiagnosticRules.memory(memory))
            findings += DiagnosticRules.memoryFindings(memory)
            activity = "Reading the battery…"
            val battery = engine.battery()
            record(DiagnosticRules.battery(battery))
            findings += DiagnosticRules.batteryFindings(battery)
            activity = "Checking storage…"
            val storage = engine.storage()
            record(DiagnosticRules.storage(storage))
            findings += DiagnosticRules.storageFindings(storage)
            record(DiagnosticRules.display(engine.display()))
            activity = "Finding sensors…"
            val present = engine.sensorsPresent()
            record(DiagnosticRules.sensors(present))
            activity = "Listening to sensors for a few seconds… pick the phone up and tilt it"
            val responded = engine.sensorResponse(present.filterValues { it }.keys)
            record(DiagnosticRules.sensorResponse(present, responded))
            activity = "Checking the connection…"
            record(DiagnosticRules.connectivity(engine.connectivity()))
            findings += DiagnosticRules.connectivityFinding
            activity = "Reading camera and audio hardware…"
            record(DiagnosticRules.cameraInfo(engine.cameraDetails()))
            val audio = engine.audio()
            record(DiagnosticRules.audioInfo(audio))
            findings += DiagnosticRules.audioFindings(audio)
        } finally {
            activity = null
        }
    }

    // ---- Guided checks ----

    suspend fun playTone() {
        activity = "Playing a tone…"
        try { engine.playTone() } finally { activity = null }
    }

    /** Read fresh each time the speaker step asks, since the customer may turn the volume up or unplug headphones. */
    fun speakerHint(): String? = DiagnosticRules.speakerHint(engine.audio())

    fun vibrate() = engine.vibrate()
    fun stopVibration() = engine.cancelVibration()

    /** Records and plays back a short clip. Anything other than a successful playback becomes the result. */
    suspend fun testMicrophone(): MicOutcome {
        try {
            val outcome = engine.recordAndPlayBack(onPhase = { activity = it })
            when (outcome) {
                MicOutcome.NO_MICROPHONE -> record(DiagnosticRules.unsupported(MICROPHONE, "No microphone on this device"))
                MicOutcome.PERMISSION_DENIED -> record(DiagnosticRules.permissionDenied(MICROPHONE))
                MicOutcome.COULD_NOT_RECORD -> record(DiagnosticRules.skipped(MICROPHONE, "The microphone was busy or unavailable, so the check couldn't run"))
                MicOutcome.RECORDED_AND_PLAYED -> Unit
            }
            return outcome
        } finally {
            activity = null
        }
    }

    /** The customer answered the system permission prompt for [test]. A refusal skips the check, never blocks the flow. */
    fun onPermissionResult(test: DiagnosticTest, granted: Boolean) {
        if (!granted) record(DiagnosticRules.permissionDenied(test))
    }

    suspend fun watchCharging(seconds: Int = 12) {
        activity = "Watching for charging…"
        try {
            val samples = engine.observeCharging(seconds) { chargingLive = it }
            record(DiagnosticRules.charging(samples))
        } finally {
            activity = null
        }
    }
}
