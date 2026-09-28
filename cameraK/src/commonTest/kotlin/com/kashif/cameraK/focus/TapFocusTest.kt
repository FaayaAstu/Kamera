package com.kashif.cameraK.focus

import com.kashif.cameraK.pro.ManualExposure
import com.kashif.cameraK.pro.ProCameraSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TapFocusTest {
    @Test
    fun invalidPreviewPointsAreRejected() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -0.01f, 1.01f).forEach {
            assertFailsWith<IllegalArgumentException> { FocusPoint(it, 0.5f) }
            assertFailsWith<IllegalArgumentException> { FocusPoint(0.5f, it) }
        }
        assertEquals(FocusPoint(0f, 1f), FocusPoint(0f, 1f))
    }

    @Test
    fun autoModeFocusesAndMetersOnlySupportedControls() {
        val settings = ProCameraSettings()
        assertEquals(TapFocusPlan(true, true), settings.tapFocusPlan(true, true))
        assertEquals(TapFocusPlan(false, true), settings.tapFocusPlan(false, true))
        assertEquals(TapFocusPlan(true, false), settings.tapFocusPlan(true, false))
        assertEquals(TapFocusPlan(false, false), settings.tapFocusPlan(false, false))
    }

    @Test
    fun manualExposureNeverEnablesAutoExposureMetering() {
        val settings = ProCameraSettings(manualExposure = ManualExposure(200, 20_000_000))
        assertEquals(TapFocusPlan(true, false), settings.tapFocusPlan(true, true))
        assertEquals(TapFocusPlan(false, false), settings.tapFocusPlan(false, true))
        assertEquals(ManualExposure(200, 20_000_000), settings.manualExposure)
    }

    @Test
    fun manualFocusBlocksTheEntireGesture() {
        listOf(0f, 0.5f, 1f).forEach {
            assertEquals(TapFocusPlan(false, false), ProCameraSettings(focusPosition = it).tapFocusPlan(true, true))
        }
    }
}
