package com.kashif.cameraK.pro

import kotlin.test.Test
import kotlin.test.assertEquals

class Camera2ControlTimeoutTest {
    @Test
    fun longExposureIncludesPipelineDrain() {
        assertEquals(23000L, camera2ControlTimeoutMs(2_000_000_000L, 0L, 8))
    }

    @Test
    fun resettingAutoStillWaitsForPreviousLongExposure() {
        assertEquals(59000L, camera2ControlTimeoutMs(0L, 6_000_000_000L, 8))
    }

    @Test
    fun shortExposureHasBoundedOverhead() {
        assertEquals(5297L, camera2ControlTimeoutMs(10_000_000L, 0L, 8))
    }

    @Test
    fun invalidPipelineDepthIsBounded() {
        assertEquals(7000L, camera2ControlTimeoutMs(1_000_000_000L, 0L, 0))
        assertEquals(14000L, camera2ControlTimeoutMs(1_000_000_000L, 0L, 255))
    }
}
