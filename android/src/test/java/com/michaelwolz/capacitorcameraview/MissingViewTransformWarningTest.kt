package com.michaelwolz.capacitorcameraview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the warning that makes a missing sensor-to-view matrix diagnosable.
 *
 * The analyzer wiring that feeds this class needs a bound session and a laid-out
 * `PreviewView`, so only the decision itself is verifiable off-device.
 */
class MissingViewTransformWarningTest {

    private fun warning() = MissingViewTransformWarning(graceMs = 1_000L, repeatMs = 10_000L)

    @Test
    fun `frames dropped within the grace period do not warn`() {
        val warning = warning()

        assertNull(warning.onFrameWithoutTransform(0L))
        assertNull(warning.onFrameWithoutTransform(500L))
        assertNull(warning.onFrameWithoutTransform(999L))
    }

    @Test
    fun `a run outlasting the grace period warns with the dropped frame count`() {
        val warning = warning()

        warning.onFrameWithoutTransform(0L)
        warning.onFrameWithoutTransform(500L)

        assertEquals(3, warning.onFrameWithoutTransform(1_000L))
    }

    @Test
    fun `the grace period is measured from the first drop, not from zero`() {
        val warning = warning()

        assertNull(warning.onFrameWithoutTransform(50_000L))
        assertNull(warning.onFrameWithoutTransform(50_999L))
        assertEquals(3, warning.onFrameWithoutTransform(51_000L))
    }

    @Test
    fun `further warnings are held back until the repeat interval elapses`() {
        val warning = warning()

        warning.onFrameWithoutTransform(0L)
        assertEquals(2, warning.onFrameWithoutTransform(1_000L))

        assertNull(warning.onFrameWithoutTransform(5_000L))
        assertNull(warning.onFrameWithoutTransform(10_999L))
        assertEquals(3, warning.onFrameWithoutTransform(11_000L))
    }

    @Test
    fun `each warning reports only the frames dropped since the previous one`() {
        val warning = warning()

        repeat(4) { warning.onFrameWithoutTransform(0L) }
        assertEquals(5, warning.onFrameWithoutTransform(1_000L))

        repeat(9) { warning.onFrameWithoutTransform(2_000L) }
        assertEquals(10, warning.onFrameWithoutTransform(11_000L))
    }

    @Test
    fun `a transform arriving ends the run and restores the full grace period`() {
        val warning = warning()

        warning.onFrameWithoutTransform(0L)
        assertEquals(2, warning.onFrameWithoutTransform(1_000L))

        warning.onTransformAvailable()

        assertNull(warning.onFrameWithoutTransform(2_000L))
        assertNull(warning.onFrameWithoutTransform(2_999L))
        assertEquals(3, warning.onFrameWithoutTransform(3_000L))
    }

    @Test
    fun `a transform arriving discards frames dropped before it`() {
        val warning = warning()

        repeat(20) { warning.onFrameWithoutTransform(0L) }
        warning.onTransformAvailable()

        warning.onFrameWithoutTransform(5_000L)
        assertEquals(2, warning.onFrameWithoutTransform(6_000L))
    }
}
