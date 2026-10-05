package com.androidcam

import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.androidcam.control.DeviceState
import timber.log.Timber
import timber.log.Timber.Tree

/**
 * Application class — initializes shared singletons.
 */
class AppApplication : android.app.Application() {
    companion object {
        lateinit var instance: AppApplication
            private set

        val deviceState: DeviceState by lazy { DeviceState() }

        /** Max hold time for the CPU wake lock (2 hours). */
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L
    }

    // Reference-counted: recording and Prusa Connect uploads can each hold
    // the wake lock independently; the lock is released when the last holder
    // lets go.
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockHolders = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        initTimber()
    }

    /**
     * Initialize Timber logging.
     */
    private fun initTimber() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        } else {
            Timber.plant(
                object : Tree() {
                    override fun log(
                        priority: Int,
                        tag: String?,
                        message: String,
                        t: Throwable?,
                    ) {
                        if (t != null) {
                            Log.println(priority, tag, "$message\n${Log.getStackTraceString(t)}")
                        } else {
                            Log.println(priority, tag, message)
                        }
                    }
                },
            )
        }
    }

    /**
     * Acquire one reference to the CPU wake lock (keeps work running while
     * the screen is off). Call [releaseWakeLock] for each acquisition.
     */
    fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock =
                pm
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "androidcam:active")
                    .apply { setReferenceCounted(true) }
        }
        wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
        wakeLockHolders++
        Timber.d("Wake lock acquired (holders: $wakeLockHolders)")
    }

    /**
     * Release one reference to the CPU wake lock. The lock itself is released
     * only when no holders remain.
     */
    fun releaseWakeLock() {
        wakeLockHolders = (wakeLockHolders - 1).coerceAtLeast(0)
        if (wakeLockHolders == 0) {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
            Timber.d("Wake lock released")
        }
    }
}
