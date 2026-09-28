package com.kashif.cameraK.pro

import androidx.compose.runtime.Immutable
import kotlin.math.roundToInt

/** Ranges for the currently bound lens, not the logical multi-camera as a whole. */
@Immutable
data class ProCameraCapabilities(
    val cameraId: String? = null,
    val isoRange: IntRange? = null,
    val exposureTimeRangeNs: LongRange? = null,
    val supportsManualFocus: Boolean = false,
    val exposureCompensationRangeEv: ClosedFloatingPointRange<Float>? = null,
    /** Android quantizes EV to this step. Null means continuous or unsupported. */
    val exposureCompensationStepEv: Float? = null,
) {
    val supportsManualExposure: Boolean get() = isoRange != null && exposureTimeRangeNs != null
}

/** ISO and shutter are set together to avoid ambiguous partial-auto exposure. */
@Immutable
data class ManualExposure(val iso: Int, val exposureTimeNs: Long)

@Immutable
data class ProCameraSettings(
    /** Null restores continuous auto exposure. Manual exposure requires flash OFF and EV zero. */
    val manualExposure: ManualExposure? = null,
    /** Null restores autofocus. 0 = infinity, 1 = nearest supported focus; not metres. */
    val focusPosition: Float? = null,
    /** Exposure compensation in stops, only while auto exposure is enabled. */
    val exposureCompensationEv: Float = 0f,
)

/** Last successfully configured settings, not measured sensor exposure values. */
@Immutable
data class ProCameraState(
    val capabilities: ProCameraCapabilities = ProCameraCapabilities(),
    val settings: ProCameraSettings = ProCameraSettings(),
    val errorMessage: String? = null,
)

internal val ProCameraSettings.applicationTimeoutMs: Long
    get() = 5000L + (manualExposure?.exposureTimeNs ?: 0L) / 1_000_000L

internal fun ProCameraSettings.validated(capabilities: ProCameraCapabilities): ProCameraSettings {
    require(exposureCompensationEv.isFinite()) { "EV must be finite" }
    manualExposure?.let { exposure ->
        val iso = capabilities.isoRange ?: throw UnsupportedOperationException("Manual ISO is unavailable on this lens")
        val shutter = capabilities.exposureTimeRangeNs
            ?: throw UnsupportedOperationException("Manual shutter is unavailable on this lens")
        require(exposure.iso in iso) { "ISO must be within $iso" }
        require(exposure.exposureTimeNs in shutter) { "Shutter time must be within $shutter nanoseconds" }
        require(exposureCompensationEv == 0f) { "EV compensation requires auto exposure" }
    }
    focusPosition?.let {
        if (!capabilities.supportsManualFocus) {
            throw UnsupportedOperationException(
                "Manual focus is unavailable on this lens",
            )
        }
        require(it.isFinite() && it in 0f..1f) { "Focus position must be within 0..1" }
    }
    val range = capabilities.exposureCompensationRangeEv
    if (exposureCompensationEv != 0f && range == null) {
        throw UnsupportedOperationException("EV compensation is unavailable on this lens")
    }
    if (range != null) require(exposureCompensationEv in range) { "EV must be within $range" }
    val step = capabilities.exposureCompensationStepEv
    val ev = if (step != null && step > 0f) {
        ((exposureCompensationEv / step).roundToInt() * step).coerceIn(range ?: 0f..0f)
    } else {
        exposureCompensationEv
    }
    return copy(exposureCompensationEv = ev)
}
