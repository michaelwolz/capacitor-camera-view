package com.michaelwolz.capacitorcameraview

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [classifyLensDeviceType]'s intrinsic-zoom-ratio thresholds. Everything else in
 * the lens-classification path needs a real device `CameraInfo` and isn't covered here.
 */
class LensClassificationTest {

    @Test
    fun `classifyLensDeviceType reports wideAngle for the default camera's ratio of 1`() {
        assertEquals("wideAngle", classifyLensDeviceType(1.0f))
    }

    @Test
    fun `classifyLensDeviceType reports ultraWide below 1`() {
        assertEquals("ultraWide", classifyLensDeviceType(0.5f))
        assertEquals("ultraWide", classifyLensDeviceType(0.9999f))
    }

    @Test
    fun `classifyLensDeviceType reports telephoto above 1`() {
        assertEquals("telephoto", classifyLensDeviceType(2.0f))
        assertEquals("telephoto", classifyLensDeviceType(1.0001f))
    }
}
