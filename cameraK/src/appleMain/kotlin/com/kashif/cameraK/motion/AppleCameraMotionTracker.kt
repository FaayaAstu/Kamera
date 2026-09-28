@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.kashif.cameraK.motion

import kotlinx.cinterop.useContents
import platform.CoreMotion.CMAttitudeReferenceFrameXArbitraryZVertical
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationState.UIApplicationStateActive
import platform.UIKit.UIApplicationWillResignActiveNotification
import platform.darwin.NSObjectProtocol
import kotlin.math.sqrt

internal class AppleCameraMotionTracker : CameraMotionTracker {
    private val manager = CMMotionManager()
    private val processor = MotionProcessor()
    override val state = processor.state
    private var requested = false
    private var intervalMs = 50
    private var startTimeNs = 0L
    private var closed = false
    private val center = NSNotificationCenter.defaultCenter
    private var activeObserver: NSObjectProtocol? = null
    private var inactiveObserver: NSObjectProtocol? = null

    override fun start(intervalMs: Int) {
        check(!closed) { "Motion tracker is disposed" }
        validateMotionInterval(intervalMs)
        this.intervalMs = intervalMs
        requested = true
        if (activeObserver == null) {
            activeObserver =
                center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, NSOperationQueue.mainQueue) {
                    if (requested) register()
                }
            inactiveObserver =
                center.addObserverForName(UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue) {
                    unregister()
                }
        }
        unregister()
        if (UIApplication.sharedApplication.applicationState == UIApplicationStateActive) register()
    }

    private fun register() {
        if (!manager.deviceMotionAvailable || !manager.gyroAvailable) {
            processor.setStatus(MotionTrackingStatus.UNAVAILABLE, "Gyroscope and device motion required")
            return
        }
        processor.setStatus(MotionTrackingStatus.STARTING)
        startTimeNs = nowNs()
        manager.deviceMotionUpdateInterval = intervalMs / 1000.0
        manager.startDeviceMotionUpdatesUsingReferenceFrame(
            CMAttitudeReferenceFrameXArbitraryZVertical,
            NSOperationQueue.mainQueue,
        ) {
                motion,
                error,
            ->
            if (!requested || !manager.deviceMotionActive) return@startDeviceMotionUpdatesUsingReferenceFrame
            if (error != null) {
                unregister()
                processor.setStatus(MotionTrackingStatus.ERROR, error.localizedDescription)
            } else if (motion != null) {
                if ((motion.timestamp * 1_000_000_000).toLong() <
                    startTimeNs
                ) {
                    return@startDeviceMotionUpdatesUsingReferenceFrame
                }
                val rate = motion.rotationRate.useContents { sqrt(x * x + y * y + z * z) }
                motion.gravity.useContents {
                    processor.update((motion.timestamp * 1_000_000_000).toLong(), x, y, z, rate)
                }
            }
        }
    }

    private fun unregister() {
        manager.stopDeviceMotionUpdates()
        processor.setStatus(MotionTrackingStatus.STOPPED)
    }

    override fun stop() {
        requested = false
        unregister()
        activeObserver?.let { center.removeObserver(it) }
        inactiveObserver?.let { center.removeObserver(it) }
        activeObserver = null
        inactiveObserver = null
    }

    private fun nowNs(): Long = (NSProcessInfo.processInfo.systemUptime * 1_000_000_000).toLong()
    override fun calibrate(): Boolean = processor.calibrate(nowNs())
    override fun clearCalibration() = processor.clearCalibration()
    override fun snapshot(): MotionCaptureSnapshot? = processor.snapshot(nowNs())
    override fun close() {
        closed = true
        stop()
    }
}
