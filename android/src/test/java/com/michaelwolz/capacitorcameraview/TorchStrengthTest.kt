package com.michaelwolz.capacitorcameraview

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the torch-strength mapping between the plugin's normalized 0.0-1.0 range and
 * the device's 1-indexed `CameraInfo`/`CameraControl` strength levels.
 */
class TorchStrengthTest {

    @Test
    fun `resolveTorchStrengthLevel maps 0 to the dimmest level, not off`() {
        assertEquals(1, resolveTorchStrengthLevel(0.0f, maxStrengthLevel = 5))
    }

    @Test
    fun `resolveTorchStrengthLevel maps 1 to the maximum level`() {
        assertEquals(5, resolveTorchStrengthLevel(1.0f, maxStrengthLevel = 5))
    }

    @Test
    fun `resolveTorchStrengthLevel rounds to the nearest level`() {
        assertEquals(3, resolveTorchStrengthLevel(0.5f, maxStrengthLevel = 5))
        assertEquals(2, resolveTorchStrengthLevel(0.3f, maxStrengthLevel = 5))
    }

    @Test
    fun `resolveTorchStrengthLevel clamps a level outside 0-1`() {
        assertEquals(1, resolveTorchStrengthLevel(-1.0f, maxStrengthLevel = 5))
        assertEquals(5, resolveTorchStrengthLevel(2.0f, maxStrengthLevel = 5))
    }

    @Test
    fun `resolveTorchStrengthLevel handles a single-level range`() {
        assertEquals(1, resolveTorchStrengthLevel(0.0f, maxStrengthLevel = 1))
        assertEquals(1, resolveTorchStrengthLevel(1.0f, maxStrengthLevel = 1))
    }

    @Test
    fun `normalizedTorchLevel reports 0 when disabled regardless of the current level`() {
        assertEquals(0.0f, normalizedTorchLevel(enabled = false, maxStrengthLevel = 5, currentStrengthLevel = 5), 0.0f)
    }

    @Test
    fun `normalizedTorchLevel reports 1 when strength isn't supported`() {
        assertEquals(1.0f, normalizedTorchLevel(enabled = true, maxStrengthLevel = 0, currentStrengthLevel = 0), 0.0f)
    }

    @Test
    fun `normalizedTorchLevel divides the current level by the max`() {
        assertEquals(0.6f, normalizedTorchLevel(enabled = true, maxStrengthLevel = 5, currentStrengthLevel = 3), 0.0001f)
        assertEquals(1.0f, normalizedTorchLevel(enabled = true, maxStrengthLevel = 5, currentStrengthLevel = 5), 0.0f)
        assertEquals(0.2f, normalizedTorchLevel(enabled = true, maxStrengthLevel = 5, currentStrengthLevel = 1), 0.0001f)
    }

    @Test
    fun `resolveTorchStrengthLevel and normalizedTorchLevel round-trip through the device range`() {
        val maxStrengthLevel = 7
        for (level in 0..maxStrengthLevel) {
            val requested = level.toFloat() / maxStrengthLevel
            val resolved = resolveTorchStrengthLevel(requested, maxStrengthLevel)
            val reported = normalizedTorchLevel(enabled = true, maxStrengthLevel, resolved)
            assertEquals(resolved, resolveTorchStrengthLevel(reported, maxStrengthLevel))
        }
    }
}
