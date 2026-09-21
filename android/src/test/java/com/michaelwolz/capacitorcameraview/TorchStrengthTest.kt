package com.michaelwolz.capacitorcameraview

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the torch-strength mapping between the plugin's normalized 0.0-1.0 range and
 * the device's 1-indexed `CameraInfo`/`CameraControl` strength levels.
 */
class TorchStrengthTest {

    @Test
    fun `resolveTorchStrengthLevel stays inside the 1-indexed device range`() {
        assertEquals(1, resolveTorchStrengthLevel(0.0f, maxStrengthLevel = 5))
        assertEquals(5, resolveTorchStrengthLevel(1.0f, maxStrengthLevel = 5))
        assertEquals(1, resolveTorchStrengthLevel(-1.0f, maxStrengthLevel = 5))
        assertEquals(5, resolveTorchStrengthLevel(2.0f, maxStrengthLevel = 5))
    }

    @Test
    fun `normalizedTorchLevel reports 0 when disabled and 1 when strength isn't supported`() {
        assertEquals(0.0f, normalizedTorchLevel(enabled = false, maxStrengthLevel = 5, currentStrengthLevel = 5), 0.0f)
        assertEquals(1.0f, normalizedTorchLevel(enabled = true, maxStrengthLevel = 0, currentStrengthLevel = 0), 0.0f)
        assertEquals(0.6f, normalizedTorchLevel(enabled = true, maxStrengthLevel = 5, currentStrengthLevel = 3), 0.0001f)
    }
}
