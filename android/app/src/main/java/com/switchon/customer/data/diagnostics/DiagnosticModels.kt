package com.switchon.customer.data.diagnostics

import com.switchon.customer.data.DeviceType
import com.switchon.customer.data.IssueType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.LocalDateTime

/**
 * Outcome of one check. Missing hardware or a property the phone doesn't expose is UNSUPPORTED, never a failure.
 * INFO is for device details (software, display, camera and audio hardware), which describe the phone and are never
 * a repair result.
 */
enum class DiagnosticStatus(val label: String) {
    PASS("Working normally"),
    ATTENTION("Needs a closer look"),
    SKIPPED("Not checked"),
    UNSUPPORTED("Not available"),
    INFO("Device information"),
}

/**
 * Every check in the flow, in the order it runs. [guided] checks need the customer to confirm what they saw or heard.
 * [info] entries only describe the device and never count as checks.
 */
enum class DiagnosticTest(val label: String, val guided: Boolean, val info: Boolean = false) {
    DEVICE_INFO("Device software", false, info = true),
    MEMORY("Memory", false),
    BATTERY("Battery", false),
    STORAGE("Storage", false),
    DISPLAY_INFO("Display details", false, info = true),
    SENSORS("Sensors present", false),
    SENSOR_RESPONSE("Sensor response", false),
    CONNECTIVITY("Connection", false),
    CAMERA_INFO("Camera hardware", false, info = true),
    AUDIO_INFO("Audio hardware", false, info = true),
    TOUCH("Touchscreen", true),
    DISPLAY("Display", true),
    SPEAKER("Speaker", true),
    VIBRATION("Vibration", true),
    MICROPHONE("Microphone", true),
    CAMERA("Camera", true),
    CHARGING("Charging", true),
}

/** A reading worth showing, e.g. "Battery temperature: 31.5 °C". A value the phone doesn't expose says so. */
@Serializable
data class DiagnosticMetric(val label: String, val value: String) {
    companion object {
        const val NOT_AVAILABLE = "Not available on this device"
        fun of(label: String, value: String?) = DiagnosticMetric(label, value ?: NOT_AVAILABLE)
    }
}

enum class FindingLevel { INFO, WARNING, RED_FLAG }

/** Something the customer (and the shop) should know. Always phrased as possible, never as a diagnosis. */
@Serializable
data class DiagnosticFinding(val test: DiagnosticTest, val level: FindingLevel, val message: String)

@Serializable
data class DiagnosticResult(
    val test: DiagnosticTest,
    val status: DiagnosticStatus,
    /** One plain-language line, e.g. "Charging detected" or "Permission not granted". */
    val note: String,
    val metrics: List<DiagnosticMetric> = emptyList(),
    /** The customer confirmed this outcome themselves (guided checks). */
    val userConfirmed: Boolean = false,
)

/** How much of the check was completed, which is all this confidence describes. */
enum class Confidence { LOW, MEDIUM, HIGH }

/** A possible repair category, capped at medium confidence: a device check alone never settles the fault. */
@Serializable
data class SuggestedIssue(val issue: IssueType, val confidence: Confidence, val reason: String)

@Serializable
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
    /** PHONE or TABLET, from the screen's smallest width. */
    val deviceType: DeviceType,
)

@Serializable
data class DiagnosticReport(
    @Serializable(with = LocalDateTimeIso::class) val checkedAt: LocalDateTime,
    val device: DeviceInfo,
    val results: List<DiagnosticResult>,
    val findings: List<DiagnosticFinding>,
    /** The repair hand-off summary: a few plain sentences for the customer and the technician. */
    val summary: String,
    val confidence: Confidence,
    val suggestedIssue: SuggestedIssue?,
    val disclaimer: String = DISCLAIMER,
) {
    /** Results that are checks, as opposed to device information. */
    val checks get() = results.filter { !it.test.info }
    val deviceInformation get() = results.filter { it.test.info }
    val userConfirmed get() = results.filter { it.userConfirmed }
    val warnings get() = findings.filter { it.level != FindingLevel.INFO }
    val redFlags get() = findings.filter { it.level == FindingLevel.RED_FLAG }
    val passed get() = checks.filter { it.status == DiagnosticStatus.PASS }
    val needsAttention get() = checks.filter { it.status == DiagnosticStatus.ATTENTION }
    val notChecked get() = checks.filter { it.status == DiagnosticStatus.SKIPPED || it.status == DiagnosticStatus.UNSUPPORTED }
    /** Checks that actually ran to an outcome. */
    val completedCount get() = passed.size + needsAttention.size

    companion object {
        const val DISCLAIMER =
            "This is a preliminary software check of what the phone reports about itself. It is not a physical repair " +
                "diagnosis: it can't see liquid damage, internal cables, the motherboard or battery wear. The repair shop " +
                "confirms the fault and the final quote after inspecting the device."
    }
}

/** ISO-8601 local date-time, for reports saved on the phone. */
object LocalDateTimeIso : KSerializer<LocalDateTime> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDateTimeIso", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDateTime) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDateTime = LocalDateTime.parse(decoder.decodeString())
}
