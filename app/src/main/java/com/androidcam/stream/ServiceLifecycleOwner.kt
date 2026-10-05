package com.androidcam.stream

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import timber.log.Timber

/**
 * A [LifecycleOwner] that stays [Lifecycle.State.STARTED] for the lifetime of
 * the [RecordingService], independent of activity lifecycle.
 *
 * CameraX opens the camera when its bound lifecycle reaches STARTED and closes
 * it when it drops to STOPPED. Binding to [androidx.lifecycle.ProcessLifecycleOwner]
 * (the default) therefore stops the camera — and interrupts any recording — the
 * moment the screen turns off or the app is backgrounded. For a remote camera
 * that must keep streaming/recording while the device is idle, the camera is
 * bound to this owner instead: it is marked STARTED when the pipeline starts and
 * DESTROYED when it stops, and never reacts to activity transitions.
 */
class ServiceLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle get() = registry

    /** Move to STARTED (idempotent). No-op once DESTROYED. */
    fun markStarted() {
        if (registry.currentState == Lifecycle.State.DESTROYED) {
            Timber.w("ServiceLifecycleOwner already destroyed; ignoring markStarted")
            return
        }
        if (!registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            registry.currentState = Lifecycle.State.STARTED
        }
    }

    /** Move to DESTROYED (idempotent). */
    fun markDestroyed() {
        if (registry.currentState != Lifecycle.State.DESTROYED) {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
