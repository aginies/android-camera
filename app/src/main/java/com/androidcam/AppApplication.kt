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
    }

    private var wakeLock: PowerManager.WakeLock? = null

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
     * Acquire a CPU wake lock to keep recording while the screen is off.
     */
    fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock =
                pm
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "androidcam:recording")
                    .apply {
                        setReferenceCounted(false)
                        acquire(2 * 60 * 60 * 1000L) // max 2 hours
                    }
            Timber.d("Wake lock acquired")
        }
    }

    /**
     * Release the CPU wake lock.
     */
    fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Timber.d("Wake lock released")
            }
        }
        wakeLock = null
    }
}
