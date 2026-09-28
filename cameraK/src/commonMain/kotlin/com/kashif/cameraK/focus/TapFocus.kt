package com.kashif.cameraK.focus

import com.kashif.cameraK.pro.ProCameraSettings

/** Coordinates in the visible CameraPreviewView overlay, independent of display density. */
data class FocusPoint(val x: Float, val y: Float) {
    init {
        require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f) {
            "Focus point must be within the preview (0..1)"
        }
    }
}

enum class TapFocusResult {
    FOCUSED,

    /** The platform finished adjusting; it does not report whether focus succeeded (iOS). */
    ADJUSTED,
    NOT_FOCUSED,
    METERED,
    UNSUPPORTED,
    MANUAL_FOCUS_ACTIVE,
    NOT_READY,
}

/** Manual focus blocks the gesture; manual exposure only disables exposure metering. */
internal data class TapFocusPlan(val focus: Boolean, val meterExposure: Boolean)

internal fun ProCameraSettings.tapFocusPlan(supportsFocus: Boolean, supportsMetering: Boolean): TapFocusPlan =
    if (focusPosition != null) {
        TapFocusPlan(false, false)
    } else {
        TapFocusPlan(supportsFocus, supportsMetering && manualExposure == null)
    }
