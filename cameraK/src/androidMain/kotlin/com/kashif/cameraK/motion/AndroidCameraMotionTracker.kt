package com.kashif.cameraK.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import kotlin.math.sqrt

internal class AndroidCameraMotionTracker(context: Context, private val owner: LifecycleOwner) :
    CameraMotionTracker,
    SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val processor = MotionProcessor()
    override val state = processor.state
    private var requested = false
    private var registered = false
    private var intervalMs = 50
    private var gyroTimestampNs = 0L
    private var rotationRate: Double? = null
    private var startTimeNs = 0L
    private var closed = false
    private val matrix = FloatArray(9)
    private val observer = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> if (requested) register()
            Lifecycle.Event.ON_STOP -> unregister()
            Lifecycle.Event.ON_DESTROY -> close()
            else -> Unit
        }
    }

    init {
        owner.lifecycle.addObserver(observer)
    }

    override fun start(intervalMs: Int) {
        checkMainThread()
        check(!closed) { "Motion tracker is disposed" }
        validateMotionInterval(intervalMs)
        this.intervalMs = intervalMs
        requested = true
        unregister()
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) register()
    }

    private fun register() {
        if (registered) return
        if (rotationSensor == null || gyro == null) {
            processor.setStatus(MotionTrackingStatus.UNAVAILABLE, "Gyroscope and fused rotation sensor required")
            return
        }
        processor.setStatus(MotionTrackingStatus.STARTING)
        startTimeNs = SystemClock.elapsedRealtimeNanos()
        val handler = Handler(Looper.getMainLooper())
        val rotationOk = manager.registerListener(this, rotationSensor, intervalMs * 1000, handler)
        val gyroOk = manager.registerListener(this, gyro, intervalMs * 1000, handler)
        registered = rotationOk && gyroOk
        if (!registered) {
            manager.unregisterListener(this)
            processor.setStatus(MotionTrackingStatus.ERROR, "Sensor registration failed")
        }
    }

    private fun unregister() {
        manager.unregisterListener(this)
        registered = false
        rotationRate = null
        gyroTimestampNs = 0L
        processor.setStatus(MotionTrackingStatus.STOPPED)
    }

    override fun stop() {
        checkMainThread()
        requested = false
        unregister()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!registered || event.timestamp < startTimeNs) return
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            rotationRate = sqrt(event.values.take(3).sumOf { it.toDouble() * it })
            gyroTimestampNs = event.timestamp
        } else {
            SensorManager.getRotationMatrixFromVector(matrix, event.values)
            // R maps device to world coordinates; -row(2) is gravity in device axes.
            val rate = rotationRate.takeIf { kotlin.math.abs(event.timestamp - gyroTimestampNs) <= 200_000_000L }
            processor.update(event.timestamp, -matrix[6].toDouble(), -matrix[7].toDouble(), -matrix[8].toDouble(), rate)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun calibrate(): Boolean = processor.calibrate(SystemClock.elapsedRealtimeNanos())
    override fun clearCalibration() = processor.clearCalibration()
    override fun snapshot(): MotionCaptureSnapshot? = processor.snapshot(SystemClock.elapsedRealtimeNanos())

    override fun close() {
        if (closed) return
        closed = true
        stop()
        owner.lifecycle.removeObserver(observer)
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Start/stop motion tracking on the main thread" }
    }
}
