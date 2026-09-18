package com.michaelwolz.capacitorcameraview

/**
 * Buffers a value and its callback issued before some readiness condition is met (e.g. a
 * camera not being bound yet), so it can be applied via [propagateIfPresent] once that
 * condition holds instead of being dropped.
 *
 * A later [set] supersedes an earlier one; the superseded callback is invoked with `null`,
 * since only the outcome of the latest request is observable once it is actually applied.
 */
class PendingValue<T> {
    private var pending: Pair<T, (Exception?) -> Unit>? = null

    /** Buffers [value], invoking any previously buffered callback with `null` first. */
    fun set(value: T, callback: (Exception?) -> Unit) {
        pending?.second?.invoke(null)
        pending = value to callback
    }

    /** Discards a buffered value (if any), invoking its callback with [error]. */
    fun clear(error: Exception) {
        pending?.second?.invoke(error)
        pending = null
    }

    /** Applies a buffered value (if any) via [apply], then discards it. */
    fun propagateIfPresent(apply: (T, (Exception?) -> Unit) -> Unit) {
        val (value, callback) = pending ?: return
        pending = null
        apply(value, callback)
    }
}
