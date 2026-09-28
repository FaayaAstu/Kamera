package com.kashif.cameraK.motion

import androidx.compose.runtime.Immutable
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

enum class MotionTrackingStatus { STOPPED, STARTING, TRACKING, UNAVAILABLE, ERROR }

/**
 * Fused gyro/gravity measurement in the device's fixed portrait axes (x right, y top, z out of screen).
 * Angles are degrees, independent of display rotation and front-camera mirroring.
 * Roll is undefined when the phone is flat; [rollDegrees] is then null.
 */
@Immutable
data class CameraAttitude(
    /** Monotonic sensor time since boot, not a wall clock timestamp. */
    val timestampNs: Long,
    val rollDegrees: Double?,
    /** -90 screen up and flat, 0 upright, +90 screen down and flat. */
    val pitchDegrees: Double,
    /** Plane angle to horizontal: 0 flat, 90 upright. */
    val tiltFromHorizontalDegrees: Double,
    /** Angle from the calibrated gravity direction; does not measure yaw or subject orientation. */
    val referenceDeviationDegrees: Double?,
    val rotationRateDegreesPerSecond: Double?,
    val gravityX: Double,
    val gravityY: Double,
    val gravityZ: Double,
)

@Immutable
data class CameraMotionState(
    val status: MotionTrackingStatus = MotionTrackingStatus.STOPPED,
    val attitude: CameraAttitude? = null,
    val message: String? = null,
)

/** Sensor snapshot at the capture request, NOT synchronized to the actual shutter exposure. */
@Immutable
data class MotionCaptureSnapshot(val attitude: CameraAttitude, val ageNs: Long)

internal interface CameraMotionTracker {
    val state: StateFlow<CameraMotionState>
    fun start(intervalMs: Int)
    fun stop()
    fun calibrate(): Boolean
    fun clearCalibration()
    fun snapshot(): MotionCaptureSnapshot?
    fun close()
}

internal class MotionProcessor {
    private val lock = SynchronizedObject()
    private val mutableState = MutableStateFlow(CameraMotionState())
    val state = mutableState.asStateFlow()
    private var reference: DoubleArray? = null

    fun setStatus(status: MotionTrackingStatus, message: String? = null) = synchronized(lock) {
        mutableState.value = CameraMotionState(status, message = message)
    }

    fun update(timestampNs: Long, x: Double, y: Double, z: Double, rotationRateRadPerSecond: Double?) =
        synchronized(lock) {
            if (state.value.status != MotionTrackingStatus.STARTING &&
                state.value.status != MotionTrackingStatus.TRACKING
            ) {
                return@synchronized
            }
            val norm = sqrt(x * x + y * y + z * z)
            if (!norm.isFinite() || norm < 1e-6 || timestampNs < 0) return@synchronized
            val previous = state.value.attitude
            if (previous != null && timestampNs <= previous.timestampNs) return@synchronized
            val gx = x / norm
            val gy = y / norm
            val gz = z / norm
            val horizontal = sqrt(gx * gx + gy * gy)
            val sample = CameraAttitude(
                timestampNs = timestampNs,
                rollDegrees = if (horizontal < 1e-3) null else atan2(gx, -gy).degrees(),
                pitchDegrees = atan2(gz, horizontal).degrees(),
                tiltFromHorizontalDegrees = acos(kotlin.math.abs(gz).coerceIn(0.0, 1.0)).degrees(),
                referenceDeviationDegrees = reference?.let {
                    acos((gx * it[0] + gy * it[1] + gz * it[2]).coerceIn(-1.0, 1.0)).degrees()
                },
                rotationRateDegreesPerSecond = rotationRateRadPerSecond?.takeIf { it.isFinite() && it >= 0 }?.degrees(),
                gravityX = gx,
                gravityY = gy,
                gravityZ = gz,
            )
            mutableState.value = CameraMotionState(MotionTrackingStatus.TRACKING, sample)
        }

    fun calibrate(nowNs: Long): Boolean = synchronized(lock) {
        val sample = snapshot(nowNs)?.attitude ?: return@synchronized false
        reference = doubleArrayOf(sample.gravityX, sample.gravityY, sample.gravityZ)
        mutableState.value = state.value.copy(attitude = sample.copy(referenceDeviationDegrees = 0.0))
        true
    }

    fun clearCalibration() = synchronized(lock) {
        reference = null
        mutableState.value = state.value.copy(attitude = state.value.attitude?.copy(referenceDeviationDegrees = null))
    }

    fun snapshot(nowNs: Long): MotionCaptureSnapshot? = synchronized(lock) {
        if (state.value.status != MotionTrackingStatus.TRACKING) return@synchronized null
        val sample = state.value.attitude ?: return@synchronized null
        val age = nowNs - sample.timestampNs
        // Never attach a stale reading after sensor suspension or delayed callbacks.
        if (age !in 0L..250_000_000L) return@synchronized null
        MotionCaptureSnapshot(sample, age)
    }
}

internal fun validateMotionInterval(intervalMs: Int) {
    require(intervalMs in 20..200) { "Motion interval must be 20..200 milliseconds" }
}

private fun Double.degrees(): Double = this * 180.0 / kotlin.math.PI
