@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.kashif.cameraK.focus

import com.kashif.cameraK.pro.ProCameraSettings
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import platform.AVFoundation.*
import platform.CoreGraphics.CGPointMake

/** AVFoundation reports adjustment activity, not an autofocus-success flag. */
internal class AppleTapFocus {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var resetJob: Job? = null
    private var reset: (() -> Unit)? = null
    private var generation = 0

    fun cancel() {
        generation++
        resetJob?.cancel()
        resetJob = null
        reset?.invoke()
        reset = null
    }

    suspend fun focus(
        device: AVCaptureDevice,
        layer: AVCaptureVideoPreviewLayer,
        point: FocusPoint,
        settings: ProCameraSettings,
    ): TapFocusResult {
        if (settings.focusPosition != null) return TapFocusResult.MANUAL_FOCUS_ACTIVE
        val focusMode = when {
            device.isFocusModeSupported(AVCaptureFocusModeAutoFocus) -> AVCaptureFocusModeAutoFocus
            device.isFocusModeSupported(AVCaptureFocusModeContinuousAutoFocus) -> AVCaptureFocusModeContinuousAutoFocus
            else -> null
        }
        val plan = settings.tapFocusPlan(
            device.isFocusPointOfInterestSupported() && focusMode != null,
            device.isExposurePointOfInterestSupported() &&
                device.isExposureModeSupported(AVCaptureExposureModeContinuousAutoExposure),
        )
        if (!plan.focus && !plan.meterExposure) return TapFocusResult.UNSUPPORTED
        val layerPoint = layer.bounds.useContents {
            if (size.width <= 0 || size.height <= 0) return TapFocusResult.NOT_READY
            CGPointMake(origin.x + point.x * size.width, origin.y + point.y * size.height)
        }
        val nativePoint = layer.captureDevicePointOfInterestForPoint(layerPoint)
        if (!nativePoint.useContents { x in 0.0..1.0 && y in 0.0..1.0 }) return TapFocusResult.NOT_READY
        cancel()
        val request = generation
        check(device.lockForConfiguration(null)) { "Unable to focus: camera configuration is busy" }
        try {
            if (plan.focus) {
                device.focusPointOfInterest = nativePoint
                device.focusMode = focusMode!!
            }
            if (plan.meterExposure) {
                device.exposurePointOfInterest = nativePoint
                device.exposureMode = AVCaptureExposureModeContinuousAutoExposure
            }
        } finally {
            device.unlockForConfiguration()
        }
        reset = {
            if (device.lockForConfiguration(null)) {
                try {
                    if (plan.focus) {
                        device.focusPointOfInterest = CGPointMake(0.5, 0.5)
                        if (device.isFocusModeSupported(AVCaptureFocusModeContinuousAutoFocus)) {
                            device.focusMode = AVCaptureFocusModeContinuousAutoFocus
                        }
                    }
                    if (plan.meterExposure) device.exposurePointOfInterest = CGPointMake(0.5, 0.5)
                } finally {
                    device.unlockForConfiguration()
                }
            }
        }
        resetJob = scope.launch {
            delay(5000)
            if (request == generation) cancel()
        }
        try {
            val settled = withTimeoutOrNull(4500) {
                // Give the focus scan a chance to start before checking adjustment activity.
                delay(200)
                while (device.isAdjustingFocus() || (plan.meterExposure && device.isAdjustingExposure())) delay(50)
                true
            } ?: false
            return when {
                !settled -> TapFocusResult.NOT_FOCUSED
                !plan.focus -> TapFocusResult.METERED
                else -> TapFocusResult.ADJUSTED
            }
        } catch (cancelled: CancellationException) {
            if (request == generation) cancel()
            throw cancelled
        }
    }
}
