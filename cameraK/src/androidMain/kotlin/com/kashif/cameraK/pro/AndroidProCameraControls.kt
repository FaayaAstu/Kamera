package com.kashif.cameraK.pro

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

@OptIn(ExperimentalCamera2Interop::class)
internal class AndroidProCameraControls(private val executor: Executor) {
    private val mutableState = MutableStateFlow(ProCameraState())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var camera: Camera? = null
    private var characteristics: CameraCharacteristics? = null
    var isApplying = false
        private set
    private var closed = false
    private var pendingExposureNs = 0L

    suspend fun bind(camera: Camera, id: String, characteristics: CameraCharacteristics) = mutex.withLock {
        check(!closed) { "Pro controls are disposed" }
        val previous = mutableState.value
        if (previous.capabilities.cameraId != id) pendingExposureNs = 0L
        this.camera = camera
        this.characteristics = characteristics
        val capabilities = capabilities(id, characteristics)
        // A new lens may have different ranges or be fixed-focus. Never carry unsafe values over.
        val settings = if (previous.capabilities.cameraId == id) previous.settings else ProCameraSettings()
        mutableState.value = ProCameraState(capabilities, settings)
        // Initial automatic capture needs no override and must also work before Lifecycle STARTED.
        if (settings != ProCameraSettings() || previous.settings != ProCameraSettings()) applyLocked(settings)
    }

    suspend fun apply(settings: ProCameraSettings): ProCameraSettings = mutex.withLock {
        applyLocked(settings.validated(mutableState.value.capabilities))
    }

    suspend fun awaitReady() = mutex.withLock {
        check(camera != null) { "Camera not ready" }
        check(mutableState.value.errorMessage == null) { "Pro controls failed; reapply settings before capture" }
    }

    private suspend fun applyLocked(settings: ProCameraSettings): ProCameraSettings = withContext(NonCancellable) {
        val current = camera ?: error("Camera not ready")
        val options = CaptureRequestOptions.Builder()
        settings.manualExposure?.let {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, it.iso)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, it.exposureTimeNs)
            val maxFrame =
                characteristics?.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: it.exposureTimeNs
            options.setCaptureRequestOption(
                CaptureRequest.SENSOR_FRAME_DURATION,
                maxOf(it.exposureTimeNs, 33_333_333L).coerceAtMost(maxFrame),
            )
        }
        settings.focusPosition?.let {
            val diopters = characteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, it * diopters)
        }
        if (settings.exposureCompensationEv != 0f) {
            val step = mutableState.value.capabilities.exposureCompensationStepEv!!
            options.setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                (
                    settings.exposureCompensationEv /
                        step
                    ).roundToInt(),
            )
        }
        val requestedExposureNs = settings.manualExposure?.exposureTimeNs ?: 0L
        val pipelineDepth = characteristics?.get(CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH)?.toInt() ?: 8
        val timeoutMs = camera2ControlTimeoutMs(requestedExposureNs, pendingExposureNs, pipelineDepth)
        // A timed-out request may still be in flight. Keep its duration until an update is acknowledged.
        pendingExposureNs = maxOf(pendingExposureNs, requestedExposureNs)
        try {
            isApplying = true
            // Replacing the library's interop options also restores CameraX's auto modes when keys are absent.
            withTimeout(timeoutMs) {
                Camera2CameraControl.from(
                    current.cameraControl,
                ).setCaptureRequestOptions(options.build()).awaitControl(executor)
            }
            pendingExposureNs = requestedExposureNs
            check(!closed) { "Pro controls are disposed" }
            mutableState.value = mutableState.value.copy(settings = settings, errorMessage = null)
            settings
        } catch (error: Exception) {
            if (!closed) {
                mutableState.value =
                    mutableState.value.copy(errorMessage = error.message ?: "Camera controls failed")
            }
            throw error
        } finally {
            isApplying = false
        }
    }

    fun close() {
        closed = true
        // CameraX can reuse its CameraControl across controllers; don't leak manual overrides.
        camera?.let { Camera2CameraControl.from(it.cameraControl).clearCaptureRequestOptions() }
        camera = null
        characteristics = null
        mutableState.value = ProCameraState()
    }

    private fun capabilities(id: String, info: CameraCharacteristics): ProCameraCapabilities {
        val keys = info.availableCaptureRequestKeys.orEmpty()
        val manual =
            info.get(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
            )?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) ==
                true &&
                keys.contains(CaptureRequest.SENSOR_EXPOSURE_TIME) &&
                keys.contains(CaptureRequest.SENSOR_SENSITIVITY)
        val iso = info.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposure = info.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val maxFrame = info.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: Long.MAX_VALUE
        val focus = (info.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f) > 0f &&
            keys.contains(CaptureRequest.LENS_FOCUS_DISTANCE) &&
            info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.contains(CaptureRequest.CONTROL_AF_MODE_OFF) ==
            true
        val ev = info.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val step = info.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat()
        return ProCameraCapabilities(
            cameraId = id,
            isoRange = if (manual && iso != null) iso.lower..iso.upper else null,
            exposureTimeRangeNs = if (manual &&
                exposure != null
            ) {
                exposure.lower..minOf(exposure.upper, maxFrame)
            } else {
                null
            },
            supportsManualFocus = focus,
            exposureCompensationRangeEv = if (ev != null &&
                ev.upper > ev.lower &&
                step != null &&
                step > 0f
            ) {
                ev.lower * step..ev.upper * step
            } else {
                null
            },
            exposureCompensationStepEv = step?.takeIf { it > 0f },
        )
    }
}

private suspend fun <T> ListenableFuture<T>.awaitControl(executor: Executor): T =
    suspendCancellableCoroutine { continuation ->
        addListener({
            if (continuation.isActive) {
                try {
                    continuation.resume(get())
                } catch (error: Exception) {
                    continuation.resumeWithException(error.cause ?: error)
                }
            }
        }, executor)
    }
