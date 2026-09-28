@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.kashif.cameraK.pro

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.AVFoundation.*
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import kotlin.math.ceil
import kotlin.math.floor

internal class AppleProCameraControls {
    private val mutableState = MutableStateFlow(ProCameraState())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var device: AVCaptureDevice? = null
    private var closed = false
    var isApplying = false
        private set

    fun bind(device: AVCaptureDevice) {
        check(!closed) { "Pro controls are disposed" }
        check(!isApplying) { "Cannot switch lenses while pro controls are applying" }
        val changed = this.device?.uniqueID != device.uniqueID
        this.device = device
        val capabilities = capabilities(device)
        if (changed) {
            configureAutoModes(device)
            mutableState.value = ProCameraState(capabilities)
        } else {
            mutableState.value = mutableState.value.copy(capabilities = capabilities)
        }
    }

    suspend fun apply(requested: ProCameraSettings): ProCameraSettings = mutex.withLock {
        check(!closed) { "Pro controls are disposed" }
        val current = device ?: error("Camera not ready")
        val settings = requested.validated(capabilities(current))
        withContext(NonCancellable) {
            isApplying = true
            try {
                val exposureDone = CompletableDeferred<Unit>()
                val focusDone = CompletableDeferred<Unit>()
                val biasDone = CompletableDeferred<Unit>()
                check(current.lockForConfiguration(null)) { "Unable to lock camera configuration" }
                try {
                    val exposure = settings.manualExposure
                    if (exposure != null) {
                        current.setExposureModeCustomWithDuration(
                            CMTimeMake(exposure.exposureTimeNs, 1_000_000_000),
                            exposure.iso.toFloat(),
                        ) {
                            exposureDone.complete(Unit)
                        }
                        biasDone.complete(Unit)
                    } else {
                        restoreAutoExposure(current)
                        exposureDone.complete(Unit)
                        current.setExposureTargetBias(settings.exposureCompensationEv) { biasDone.complete(Unit) }
                    }
                    if (settings.focusPosition != null) {
                        // AVFoundation lensPosition runs near -> infinity; shared API runs infinity -> near.
                        current.setFocusModeLockedWithLensPosition(1f - settings.focusPosition) {
                            focusDone.complete(Unit)
                        }
                    } else {
                        restoreAutoFocus(current)
                        focusDone.complete(Unit)
                    }
                } finally {
                    current.unlockForConfiguration()
                }
                withTimeout(settings.applicationTimeoutMs) {
                    exposureDone.await()
                    focusDone.await()
                    biasDone.await()
                }
                check(!closed) { "Pro controls are disposed" }
                mutableState.value = ProCameraState(capabilities(current), settings)
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
    }

    suspend fun <T> withReady(block: suspend () -> T): T = mutex.withLock {
        check(!closed) { "Pro controls are disposed" }
        check(device != null) { "Camera not ready" }
        check(mutableState.value.errorMessage == null) { "Pro controls failed; reapply settings before capture" }
        block()
    }

    fun close() {
        closed = true
        device = null
        mutableState.value = ProCameraState()
    }

    private fun capabilities(device: AVCaptureDevice): ProCameraCapabilities {
        val format = device.activeFormat
        val customExposure = device.isExposureModeSupported(AVCaptureExposureModeCustom)
        return ProCameraCapabilities(
            cameraId = device.uniqueID,
            isoRange = if (customExposure) {
                ceil(format.minISO.toDouble()).toInt()..floor(format.maxISO.toDouble()).toInt()
            } else {
                null
            },
            exposureTimeRangeNs = if (customExposure) {
                ceil(
                    CMTimeGetSeconds(format.minExposureDuration) * 1_000_000_000,
                ).toLong()..floor(CMTimeGetSeconds(format.maxExposureDuration) * 1_000_000_000).toLong()
            } else {
                null
            },
            supportsManualFocus = device.isLockingFocusWithCustomLensPositionSupported(),
            exposureCompensationRangeEv = if (device.maxExposureTargetBias >
                device.minExposureTargetBias
            ) {
                device.minExposureTargetBias..device.maxExposureTargetBias
            } else {
                null
            },
        )
    }

    private fun configureAutoModes(device: AVCaptureDevice) {
        check(device.lockForConfiguration(null)) { "Unable to lock camera configuration" }
        try {
            restoreAutoExposure(device)
            restoreAutoFocus(device)
            device.setExposureTargetBias(0f, null)
        } finally {
            device.unlockForConfiguration()
        }
    }

    private fun restoreAutoExposure(device: AVCaptureDevice) {
        if (device.isExposureModeSupported(AVCaptureExposureModeContinuousAutoExposure)) {
            device.exposureMode = AVCaptureExposureModeContinuousAutoExposure
        } else if (device.isExposureModeSupported(AVCaptureExposureModeAutoExpose)) {
            device.exposureMode = AVCaptureExposureModeAutoExpose
        }
    }

    private fun restoreAutoFocus(device: AVCaptureDevice) {
        if (device.isFocusModeSupported(AVCaptureFocusModeContinuousAutoFocus)) {
            device.focusMode = AVCaptureFocusModeContinuousAutoFocus
        } else if (device.isFocusModeSupported(AVCaptureFocusModeAutoFocus)) {
            device.focusMode = AVCaptureFocusModeAutoFocus
        }
    }
}
