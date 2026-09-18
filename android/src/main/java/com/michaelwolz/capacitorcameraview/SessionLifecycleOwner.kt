package com.michaelwolz.capacitorcameraview

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * The [LifecycleOwner] the camera use cases are bound to.
 *
 * Its state is the host's, floored at `CREATED` while inactive: the camera follows the host
 * into the background, and `active` keeps a stopped session from resuming with the host.
 *
 * Must be created and mutated on the main thread.
 */
class SessionLifecycleOwner(private val host: LifecycleOwner) :
    LifecycleOwner, DefaultLifecycleObserver {

    private val registry = LifecycleRegistry(this)
    private var active = false

    override val lifecycle: Lifecycle
        get() = registry

    init {
        host.lifecycle.addObserver(this)
        syncState()
    }

    fun setActive(active: Boolean) {
        if (this.active == active) return
        this.active = active
        syncState()
    }

    /** Detaches from the host and destroys this lifecycle, unbinding everything bound to it. */
    fun release() {
        host.lifecycle.removeObserver(this)
        if (registry.currentState != Lifecycle.State.DESTROYED) {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    override fun onStart(owner: LifecycleOwner) = syncState()

    override fun onResume(owner: LifecycleOwner) = syncState()

    override fun onPause(owner: LifecycleOwner) = syncState()

    override fun onStop(owner: LifecycleOwner) = syncState()

    override fun onDestroy(owner: LifecycleOwner) = release()

    private fun syncState() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return

        val hostState = host.lifecycle.currentState
        registry.currentState = when {
            hostState == Lifecycle.State.DESTROYED -> Lifecycle.State.DESTROYED
            !active || hostState == Lifecycle.State.INITIALIZED -> Lifecycle.State.CREATED
            else -> hostState
        }
    }
}
