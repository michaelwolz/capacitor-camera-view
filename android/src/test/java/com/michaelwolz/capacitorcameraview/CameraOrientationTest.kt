package com.michaelwolz.capacitorcameraview

import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the facing, rotation and framing decisions used by the Android capture path.
 *
 * These functions are factored out of [CameraView] specifically so the behaviour can be
 * verified without a device/emulator.
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
    fun `surface rotation constants map to degrees`() {
        assertEquals(0, surfaceRotationToDegrees(Surface.ROTATION_0))
        assertEquals(90, surfaceRotationToDegrees(Surface.ROTATION_90))
        assertEquals(180, surfaceRotationToDegrees(Surface.ROTATION_180))
        assertEquals(270, surfaceRotationToDegrees(Surface.ROTATION_270))
    }

    @Test
    fun `unknown display rotation is treated as natural orientation`() {
        assertEquals(0, surfaceRotationToDegrees(-1))
    }

    @Test
    fun `back camera subtracts display rotation from sensor rotation`() {
        assertEquals(90, relativeImageRotation(0, 90, isOppositeFacing = true))
        assertEquals(0, relativeImageRotation(90, 90, isOppositeFacing = true))
        assertEquals(270, relativeImageRotation(180, 90, isOppositeFacing = true))
        assertEquals(180, relativeImageRotation(270, 90, isOppositeFacing = true))
    }

    @Test
    fun `front camera adds display rotation to sensor rotation`() {
        assertEquals(270, relativeImageRotation(0, 270, isOppositeFacing = false))
        assertEquals(0, relativeImageRotation(90, 270, isOppositeFacing = false))
        assertEquals(90, relativeImageRotation(180, 270, isOppositeFacing = false))
        assertEquals(180, relativeImageRotation(270, 270, isOppositeFacing = false))
    }

    @Test
    fun `portrait viewport on a 90 degree sensor resolves to the sensor-space ratio`() {
        // A 1080x1920 portrait viewport is 16:9 once rotated into the sensor's landscape space.
        assertEquals(
            AspectRatio.RATIO_16_9,
            viewportAspectRatio(1080, 1920, 0, 90, isOppositeFacing = true)
        )
        assertEquals(
            AspectRatio.RATIO_4_3,
            viewportAspectRatio(1080, 1440, 0, 90, isOppositeFacing = true)
        )
    }

    @Test
    fun `landscape viewport on a 90 degree sensor keeps its own ratio`() {
        assertEquals(
            AspectRatio.RATIO_16_9,
            viewportAspectRatio(1920, 1080, 90, 90, isOppositeFacing = true)
        )
    }

    @Test
    fun `viewport that is neither 4 by 3 nor 16 by 9 has no preferred ratio`() {
        // The 16:10 panel from the reported device: 1200x1920 in portrait.
        assertEquals(
            AspectRatio.RATIO_DEFAULT,
            viewportAspectRatio(1200, 1920, 0, 90, isOppositeFacing = true)
        )
    }

    @Test
    fun `degenerate viewport has no preferred ratio`() {
        assertEquals(
            AspectRatio.RATIO_DEFAULT,
            viewportAspectRatio(0, 0, 0, 90, isOppositeFacing = true)
        )
    }

    @Test
    fun `pre-rotating HAL buffer stays portrait and is not rotated again`() {
        // Redmi Note 13 Pro+: a 90 degree sensor whose HAL hands back an already-rotated
        // 1512x2688 portrait buffer, so CameraX reports no further rotation is needed.
        val transform = planCaptureTransform(
            bufferWidth = 1512,
            bufferHeight = 2688,
            cropRect = CaptureCropRegion(0, 0, 1512, 2688),
            rotationDegrees = 0
        )

        assertEquals(0, transform.rotationDegrees)
        assertNull(transform.crop)
        assertEquals(1512 to 2688, transform.outputSize(1512, 2688))
    }

    @Test
    fun `sensor-oriented buffer is rotated into portrait`() {
        // The same capture on a HAL that does not pre-rotate: a landscape buffer plus the
        // 90 degrees CameraX reports, which must end up the same way up as the case above.
        val transform = planCaptureTransform(
            bufferWidth = 2688,
            bufferHeight = 1512,
            cropRect = CaptureCropRegion(0, 0, 2688, 1512),
            rotationDegrees = 90
        )

        assertEquals(90, transform.rotationDegrees)
        assertNull(transform.crop)
        assertEquals(1512 to 2688, transform.outputSize(2688, 1512))
    }

    @Test
    fun `crop is applied before rotation`() {
        val transform = planCaptureTransform(
            bufferWidth = 2688,
            bufferHeight = 1512,
            cropRect = CaptureCropRegion(134, 0, 2420, 1512),
            rotationDegrees = 90
        )

        assertEquals(CaptureCropRegion(134, 0, 2420, 1512), transform.crop)
        assertEquals(1512 to 2420, transform.outputSize(2688, 1512))
    }

    @Test
    fun `crop rect smaller than the buffer is honored`() {
        // A 16:9 capture cropped to the 1:1.6 viewport: 1512 x 1.6 = 2419.2 ~= 2420.
        val transform = planCaptureTransform(
            bufferWidth = 1512,
            bufferHeight = 2688,
            cropRect = CaptureCropRegion(0, 134, 1512, 2420),
            rotationDegrees = 0
        )

        assertEquals(CaptureCropRegion(0, 134, 1512, 2420), transform.crop)
        assertEquals(0, transform.rotationDegrees)
    }

    @Test
    fun `crop rect is clamped to the decoded buffer`() {
        val transform = planCaptureTransform(
            bufferWidth = 1000,
            bufferHeight = 1000,
            cropRect = CaptureCropRegion(-10, 500, 2000, 2000),
            rotationDegrees = 270
        )

        assertEquals(CaptureCropRegion(0, 500, 1000, 500), transform.crop)
        assertEquals(270, transform.rotationDegrees)
    }

    @Test
    fun `empty crop rect falls back to the full buffer`() {
        val transform = planCaptureTransform(
            bufferWidth = 1000,
            bufferHeight = 1000,
            cropRect = CaptureCropRegion(0, 0, 0, 0),
            rotationDegrees = 0
        )

        assertNull(transform.crop)
    }

    @Test
    fun `rotation is normalized into 0 until 360`() {
        val transform = planCaptureTransform(
            bufferWidth = 100,
            bufferHeight = 100,
            cropRect = CaptureCropRegion(0, 0, 100, 100),
            rotationDegrees = -90
        )

        assertEquals(270, transform.rotationDegrees)
    }
}

/**
 * The width and height of the bitmap `Bitmap.createBitmap(src, x, y, w, h, matrix, true)`
 * produces for this transform, which is what `imageProxyToBase64` ultimately encodes.
 */
private fun CaptureTransform.outputSize(
    bufferWidth: Int,
    bufferHeight: Int
): Pair<Int, Int> {
    val width = crop?.width ?: bufferWidth
    val height = crop?.height ?: bufferHeight

    return if (rotationDegrees == 90 || rotationDegrees == 270) {
        height to width
    } else {
        width to height
    }
}
