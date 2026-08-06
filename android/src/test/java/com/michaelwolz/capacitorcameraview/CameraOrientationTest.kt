package com.michaelwolz.capacitorcameraview

import android.view.Surface
import androidx.camera.core.CameraSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the facing/rotation decision logic used by the Android capture path.
 *
 * These functions are factored out of [CameraView] specifically so the orientation and
 * mirroring behaviour can be verified without a device/emulator.
 */
class CameraOrientationTest {

    @Test
    fun `isLensFacingFront only true for the front lens`() {
        assertTrue(isLensFacingFront(CameraSelector.LENS_FACING_FRONT))
        assertFalse(isLensFacingFront(CameraSelector.LENS_FACING_BACK))
        assertFalse(isLensFacingFront(CameraSelector.LENS_FACING_EXTERNAL))
        assertFalse(isLensFacingFront(null))
    }

    @Test
    fun `back camera subtracts display rotation from frame rotation`() {
        assertEquals(90, calculateImageRotation(90, Surface.ROTATION_0, isFrontFacing = false))
        assertEquals(0, calculateImageRotation(90, Surface.ROTATION_90, isFrontFacing = false))
        assertEquals(270, calculateImageRotation(90, Surface.ROTATION_180, isFrontFacing = false))
        assertEquals(180, calculateImageRotation(90, Surface.ROTATION_270, isFrontFacing = false))
    }

    @Test
    fun `front camera adds display rotation to frame rotation`() {
        assertEquals(270, calculateImageRotation(270, Surface.ROTATION_0, isFrontFacing = true))
        assertEquals(0, calculateImageRotation(270, Surface.ROTATION_90, isFrontFacing = true))
        assertEquals(90, calculateImageRotation(270, Surface.ROTATION_180, isFrontFacing = true))
        assertEquals(180, calculateImageRotation(270, Surface.ROTATION_270, isFrontFacing = true))
    }

    @Test
    fun `pre-rotated buffer at natural orientation is not rotated again`() {
        // A HAL that returns pre-rotated buffers reports ImageInfo.rotationDegrees == 0.
        assertEquals(0, calculateImageRotation(0, Surface.ROTATION_0, isFrontFacing = false))
        assertEquals(0, calculateImageRotation(0, Surface.ROTATION_0, isFrontFacing = true))
    }

    @Test
    fun `output tracks the frame rotationDegrees for back camera`() {
        // At a fixed display orientation the result varies with the frame's own rotation,
        // proving ImageInfo.rotationDegrees is honoured instead of being ignored.
        assertEquals(0, calculateImageRotation(0, Surface.ROTATION_0, isFrontFacing = false))
        assertEquals(90, calculateImageRotation(90, Surface.ROTATION_0, isFrontFacing = false))
        assertEquals(180, calculateImageRotation(180, Surface.ROTATION_0, isFrontFacing = false))
        assertEquals(270, calculateImageRotation(270, Surface.ROTATION_0, isFrontFacing = false))
    }
}
