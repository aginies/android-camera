package com.androidcam.prusa

/**
 * Prusa Connect camera settings (persisted in SharedPreferences).
 *
 * The app acts as a Prusa Connect "camera": it registers itself with
 * `PUT /c/info` and periodically uploads JPEG snapshots with
 * `PUT /c/snapshot`, authenticated by the [token] (20 chars, created in the
 * Prusa Connect web/app when adding a camera to a printer) and a stable
 * [fingerprint] (16-64 chars, generated once per device).
 */
data class PrusaConnectSettings(
    val enabled: Boolean = false,
    val token: String = "",
    val fingerprint: String = "",
    val cameraName: String = DEFAULT_CAMERA_NAME,
    val intervalSeconds: Int = DEFAULT_INTERVAL_SECONDS,
    val hostname: String = DEFAULT_HOSTNAME,
) {
    companion object {
        const val DEFAULT_HOSTNAME = "connect.prusa3d.com"
        const val DEFAULT_CAMERA_NAME = "AndroidCam"
        const val DEFAULT_INTERVAL_SECONDS = 30

        /** Tokens are exactly 20 characters per the Connect API spec. */
        const val TOKEN_LENGTH = 20

        /** Camera names are limited to 64 characters per the spec. */
        const val NAME_MAX_LENGTH = 64

        fun isValidToken(token: String): Boolean = token.length == TOKEN_LENGTH
    }
}

/**
 * Live status of the Prusa Connect link, published to [com.androidcam.control.DeviceState].
 */
data class PrusaState(
    val enabled: Boolean = false,
    /** True after the first successful `PUT /c/info`. */
    val registered: Boolean = false,
    /** Epoch millis of the last successful snapshot upload (0 = never). */
    val lastUploadMs: Long = 0,
    /** Human-readable error, or null when healthy. */
    val error: String? = null,
)
