package com.michaelwolz.capacitorcameraview

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val AWAIT_TIMEOUT_MS = 1_000L

/**
 * Unit tests for the rule that decides whether a failed bind is reported to the caller of
 * `start()`: the first bind attempt of a session has a caller waiting, every later rebind
 * does not.
 *
 * Driving the gate from a real bind needs a CameraX session, so only that decision is
 * verifiable off-device.
 */
class SessionStartGateTest {

    @Test
    fun `awaiting returns once the first bind succeeds`() = runBlocking {
        val gate = SessionStartGate()

        gate.onBound()
        withTimeout(AWAIT_TIMEOUT_MS) { gate.awaitFirstBind() }
    }

    @Test
    fun `a failed first bind is reported to the waiting caller`() {
        val gate = SessionStartGate()
        val cause = IllegalStateException("no such camera")

        assertTrue(gate.onFailure(cause))

        assertEquals(cause.message, awaitFailure(gate).message)
    }

    @Test
    fun `a failed rebind after a successful bind is not reported`() = runBlocking {
        val gate = SessionStartGate()

        gate.onBound()

        assertFalse(gate.onFailure(IllegalStateException("rebind failed")))
        withTimeout(AWAIT_TIMEOUT_MS) { gate.awaitFirstBind() }
    }

    @Test
    fun `only the first failure is reported`() {
        val gate = SessionStartGate()
        val first = IllegalStateException("first")

        assertTrue(gate.onFailure(first))
        assertFalse(gate.onFailure(IllegalStateException("second")))

        assertEquals("first", awaitFailure(gate).message)
    }

    @Test
    fun `a bind that succeeds after the gate failed does not revive the caller`() {
        val gate = SessionStartGate()
        val cause = IllegalStateException("no such camera")

        gate.onFailure(cause)
        gate.onBound()

        assertEquals(cause.message, awaitFailure(gate).message)
    }

    @Test
    fun `a teardown before the first bind is reported as a failure`() {
        val gate = SessionStartGate()

        assertTrue(gate.onFailure(CameraError.CameraNotInitialized()))

        val reported = awaitFailure(gate)
        assertEquals("SESSION_NOT_RUNNING", (reported as CameraError).code)
    }

    private fun awaitFailure(gate: SessionStartGate): Throwable = runBlocking {
        val caught = try {
            withTimeout(AWAIT_TIMEOUT_MS) { gate.awaitFirstBind() }
            null
        } catch (e: Exception) {
            e
        }

        caught ?: throw AssertionError("awaitFirstBind was expected to rethrow the bind failure")
    }
}
