package com.kashif.cameraK.pro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProCameraSettingsTest {
    private val capabilities = ProCameraCapabilities(
        cameraId = "back-wide",
        isoRange = 50..3200,
        exposureTimeRangeNs = 100_000L..1_000_000_000L,
        supportsManualFocus = true,
        exposureCompensationRangeEv = -2f..2f,
        exposureCompensationStepEv = 0.5f,
    )

    @Test
    fun evIsQuantizedInStops() {
        assertEquals(
            0.5f,
            ProCameraSettings(exposureCompensationEv = 0.7f).validated(capabilities).exposureCompensationEv,
        )
        assertEquals(
            -0.5f,
            ProCameraSettings(exposureCompensationEv = -0.7f).validated(capabilities).exposureCompensationEv,
        )
    }

    @Test
    fun manualExposureCannotSilentlyIgnoreEv() {
        assertFailsWith<IllegalArgumentException> {
            ProCameraSettings(ManualExposure(100, 10_000_000), exposureCompensationEv = 1f).validated(capabilities)
        }
    }

    @Test
    fun validatesEachSelectedLensRangeAndFiniteValues() {
        assertFailsWith<IllegalArgumentException> {
            ProCameraSettings(ManualExposure(6400, 10_000_000)).validated(capabilities)
        }
        assertFailsWith<IllegalArgumentException> { ProCameraSettings(ManualExposure(100, 0)).validated(capabilities) }
        assertFailsWith<IllegalArgumentException> {
            ProCameraSettings(focusPosition = Float.NaN).validated(capabilities)
        }
        assertFailsWith<IllegalArgumentException> { ProCameraSettings(focusPosition = 1.01f).validated(capabilities) }
        assertFailsWith<IllegalArgumentException> {
            ProCameraSettings(exposureCompensationEv = 3f).validated(capabilities)
        }
    }

    @Test
    fun unsupportedHardwareAcceptsAutoButRejectsManual() {
        val fixedLens = ProCameraCapabilities()
        assertEquals(ProCameraSettings(), ProCameraSettings().validated(fixedLens))
        assertFailsWith<UnsupportedOperationException> { ProCameraSettings(focusPosition = 0f).validated(fixedLens) }
        assertFailsWith<UnsupportedOperationException> {
            ProCameraSettings(ManualExposure(100, 10_000_000)).validated(fixedLens)
        }
        assertFailsWith<UnsupportedOperationException> {
            ProCameraSettings(exposureCompensationEv = 1f).validated(fixedLens)
        }
    }
}
