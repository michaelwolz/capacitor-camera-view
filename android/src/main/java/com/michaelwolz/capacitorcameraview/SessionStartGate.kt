package com.michaelwolz.capacitorcameraview

import kotlinx.coroutines.CompletableDeferred

/**
 * The outcome a session start waits for: the resolution of that session's *first* bind
 * attempt, which is normally deferred until the preview view has been laid out and so
 * happens after the starting call has already returned.
 *
 * Only the first attempt has a caller left to answer. Later rebinds - a layout change, a
 * camera flip, a recording use-case swap - resolve against an already-answered gate.
 */
class SessionStartGate {
    private val firstBind = CompletableDeferred<Unit>()

    /** Suspends until the first bind attempt resolves, rethrowing the cause if it failed. */
    suspend fun awaitFirstBind() = firstBind.await()

    /** Records a successful bind. */
    fun onBound() {
        firstBind.complete(Unit)
    }

    /**
     * Records a bind that failed, or a teardown that arrived before any bind succeeded.
     *
     * @return whether [cause] reached a waiting [awaitFirstBind]; `false` once the session
     *         has bound at least once, leaving the failure with no caller to report to.
     */
    fun onFailure(cause: Exception): Boolean = firstBind.completeExceptionally(cause)
}
