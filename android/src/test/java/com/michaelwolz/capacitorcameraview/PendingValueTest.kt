package com.michaelwolz.capacitorcameraview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the buffering that replays a setZoomFactor/setTorchMode/setFlashMode call
 * made before the camera is attached. Every buffered value owns a plugin call's callback, so
 * the invariant under test is that no path drops one without resolving it.
 *
 * Wiring [PendingValue] into a real bind (setting it from `setZoomFactor` and draining it
 * from `applyPostBindState`) needs a bound CameraX session, so only the buffering logic
 * itself is verifiable off-device.
 */
class PendingValueTest {

    @Test
    fun `a buffered value is applied once and then forgotten`() {
        val pending = PendingValue<Float>()
        var appliedValue: Float? = null
        var appliedCount = 0

        pending.set(2.0f) { }
        pending.propagateIfPresent { value, _ ->
            appliedValue = value
            appliedCount++
        }
        pending.propagateIfPresent { _, _ -> appliedCount++ }

        assertEquals(2.0f, appliedValue)
        assertEquals(1, appliedCount)
    }

    @Test
    fun `a newer value supersedes an older one and resolves its callback with null`() {
        val pending = PendingValue<Float>()
        var firstCallbackError: Exception? = Exception("not yet invoked")
        var firstCallbackInvoked = false

        pending.set(2.0f) { error ->
            firstCallbackInvoked = true
            firstCallbackError = error
        }
        pending.set(3.0f) { }

        assertEquals(true, firstCallbackInvoked)
        assertNull(firstCallbackError)

        var appliedValue: Float? = null
        pending.propagateIfPresent { value, _ -> appliedValue = value }

        assertEquals(3.0f, appliedValue)
    }

    @Test
    fun `clear resolves a buffered callback with the given error`() {
        val pending = PendingValue<Float>()
        val expectedError = CameraError.CameraNotInitialized()
        var receivedError: Exception? = null

        pending.set(2.0f) { error -> receivedError = error }
        pending.clear(expectedError)

        assertEquals(expectedError, receivedError)

        var applied = false
        pending.propagateIfPresent { _, _ -> applied = true }
        assertEquals(false, applied)
    }
}
