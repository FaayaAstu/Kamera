package com.kashif.cameraK.focus

import android.hardware.camera2.CameraCharacteristics
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.kashif.cameraK.pro.ProCameraSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** All entry points run on the main thread. Generation guards prevent old requests cancelling new ones. */
@OptIn(ExperimentalCamera2Interop::class)
internal class AndroidTapFocus {
    private var camera: Camera? = null
    private var generation = 0

    fun cancel() {
        generation++
        camera?.cameraControl?.cancelFocusAndMetering()
        camera = null
    }

    suspend fun focus(
        camera: Camera,
        preview: PreviewView,
        point: FocusPoint,
        settings: ProCameraSettings,
    ): TapFocusResult {
        if (settings.focusPosition != null) return TapFocusResult.MANUAL_FOCUS_ACTIVE
        if (preview.width == 0 || preview.height == 0 ||
            preview.outputTransform == null
        ) {
            return TapFocusResult.NOT_READY
        }
        val meteringPoint = preview.meteringPointFactory.createPoint(point.x * preview.width, point.y * preview.height)
        // FIT_CENTER can have letterboxing: do not turn a tap outside the image into a sensor-edge focus.
        if (meteringPoint.x !in 0f..1f || meteringPoint.y !in 0f..1f) return TapFocusResult.NOT_READY
        fun action(flags: Int) = FocusMeteringAction.Builder(meteringPoint, flags)
            .setAutoCancelDuration(5, TimeUnit.SECONDS).build()
        val afModes = Camera2CameraInfo.from(camera.cameraInfo)
            .getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        val hasAutoFocus = afModes?.contains(CameraCharacteristics.CONTROL_AF_MODE_AUTO) == true
        val plan = settings.tapFocusPlan(
            hasAutoFocus && camera.cameraInfo.isFocusMeteringSupported(action(FocusMeteringAction.FLAG_AF)),
            camera.cameraInfo.isFocusMeteringSupported(action(FocusMeteringAction.FLAG_AE)),
        )
        if (!plan.focus && !plan.meterExposure) return TapFocusResult.UNSUPPORTED
        cancel()
        this.camera = camera
        val request = generation
        val flags = (if (plan.focus) FocusMeteringAction.FLAG_AF else 0) or
            (if (plan.meterExposure) FocusMeteringAction.FLAG_AE else 0)
        try {
            val result = withTimeoutOrNull(4500) {
                suspendCancellableCoroutine { continuation ->
                    val future = camera.cameraControl.startFocusAndMetering(action(flags))
                    future.addListener({
                        if (continuation.isActive) {
                            try {
                                continuation.resume(future.get())
                            } catch (error: Exception) {
                                continuation.resumeWithException(error.cause ?: error)
                            }
                        }
                    }, ContextCompat.getMainExecutor(preview.context))
                }
            }
            if (result == null) {
                if (request == generation) cancel()
                return TapFocusResult.NOT_FOCUSED
            }
            return when {
                !plan.focus -> TapFocusResult.METERED
                result.isFocusSuccessful -> TapFocusResult.FOCUSED
                else -> TapFocusResult.NOT_FOCUSED
            }
        } catch (cancelled: CancellationException) {
            if (request == generation) cancel()
            throw cancelled
        } catch (error: Exception) {
            if (request == generation) cancel()
            throw error
        }
    }
}
