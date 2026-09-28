package com.kashif.cameraK.motion

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MotionProcessorTest {
    private fun tracker() = MotionProcessor().apply { setStatus(MotionTrackingStatus.STARTING) }

    @Test
    fun portraitLandscapeAndFlatHaveConsistentCrossPlatformAngles() {
        val tracker = tracker()
        tracker.update(1, 0.0, -9.81, 0.0, 0.0)
        assertEquals(0.0, tracker.state.value.attitude!!.rollDegrees!!, 1e-6)
        assertEquals(0.0, tracker.state.value.attitude!!.pitchDegrees, 1e-6)
        assertEquals(90.0, tracker.state.value.attitude!!.tiltFromHorizontalDegrees, 1e-6)
        tracker.update(2, 1.0, 0.0, 0.0, kotlin.math.PI)
        assertEquals(90.0, tracker.state.value.attitude!!.rollDegrees!!, 1e-6)
        assertEquals(180.0, tracker.state.value.attitude!!.rotationRateDegreesPerSecond!!, 1e-6)
        tracker.update(3, 0.0, 0.0, -1.0, null)
        assertNull(tracker.state.value.attitude!!.rollDegrees)
        assertEquals(-90.0, tracker.state.value.attitude!!.pitchDegrees, 1e-6)
        assertEquals(0.0, tracker.state.value.attitude!!.tiltFromHorizontalDegrees, 1e-6)
    }

    @Test
    fun nearFlatGravityDoesNotInventAnUnstableRoll() {
        val tracker = tracker()
        tracker.update(1, 0.005, -0.005, -1.0, null)
        assertNull(tracker.state.value.attitude!!.rollDegrees)
        assertTrue(tracker.state.value.attitude!!.tiltFromHorizontalDegrees < 1.0)
    }

    @Test
    fun calibrationMeasuresGravityDeviationAndCanBeCleared() {
        val tracker = tracker()
        tracker.update(1, 0.0, -1.0, 0.0, null)
        assertTrue(tracker.calibrate(1))
        tracker.update(2, 0.0, -sqrt(0.5), -sqrt(0.5), null)
        assertEquals(45.0, tracker.state.value.attitude!!.referenceDeviationDegrees!!, 1e-6)
        tracker.clearCalibration()
        assertNull(tracker.state.value.attitude!!.referenceDeviationDegrees)
    }

    @Test
    fun staleSamplesAndStoppedSensorsNeverProduceCaptureMetadata() {
        val tracker = tracker()
        tracker.update(1, 0.0, -1.0, 0.0, null)
        assertEquals(250_000_000L, tracker.snapshot(250_000_001)!!.ageNs)
        assertNull(tracker.snapshot(250_000_002))
        assertFalse(tracker.calibrate(250_000_002))
        tracker.setStatus(MotionTrackingStatus.STOPPED)
        tracker.update(2, 0.0, -1.0, 0.0, null)
        assertNull(tracker.snapshot(2))
        assertNull(tracker.state.value.attitude)
    }

    @Test
    fun invalidAndOutOfOrderSamplesCannotReplaceFreshReading() {
        val tracker = tracker()
        tracker.update(10, 0.0, -1.0, 0.0, null)
        tracker.update(9, 0.0, 1.0, 0.0, null)
        tracker.update(11, Double.NaN, 0.0, 0.0, null)
        tracker.update(12, 0.0, 0.0, 0.0, null)
        assertEquals(10L, tracker.state.value.attitude!!.timestampNs)
        assertNull(tracker.snapshot(0))
    }
}
