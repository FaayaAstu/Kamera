package com.kashif.cameraK.motion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class DesktopCameraMotionTracker : CameraMotionTracker {
    private val mutableState =
        MutableStateFlow(
            CameraMotionState(MotionTrackingStatus.UNAVAILABLE, message = "Desktop motion sensors are unsupported"),
        )
    override val state = mutableState.asStateFlow()
    override fun start(intervalMs: Int) = validateMotionInterval(intervalMs)
    override fun stop() = Unit
    override fun calibrate(): Boolean = false
    override fun clearCalibration() = Unit
    override fun snapshot(): MotionCaptureSnapshot? = null
    override fun close() = Unit
}
