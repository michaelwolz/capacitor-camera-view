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
    fun `a failed first bind is reported to the waiting caller`() = runBlocking {
        val gate = SessionStartGate()
        val cause = IllegalStateException("no such camera")

        assertTrue(gate.onFailure(cause))

        val reported = try {
            withTimeout(AWAIT_TIMEOUT_MS) { gate.awaitFirstBind() }
            throw AssertionError("awaitFirstBind was expected to rethrow the bind failure")
        } catch (e: IllegalStateException) {
            e
        }

        assertEquals(cause.message, reported.message)
    }

    @Test
    fun `a failed rebind after a successful bind is not reported`() = runBlocking {
        val gate = SessionStartGate()

        gate.onBound()

        assertFalse(gate.onFailure(IllegalStateException("rebind failed")))
        withTimeout(AWAIT_TIMEOUT_MS) { gate.awaitFirstBind() }
    }
}
