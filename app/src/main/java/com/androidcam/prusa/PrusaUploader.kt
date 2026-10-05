package com.androidcam.prusa

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Upload loop for Prusa Connect.
 *
 * Sends `PUT /c/info` on start (and whenever [markInfoStale] is called, e.g.
 * after a network change), then uploads the latest stream frame every
 * [PrusaConnectSettings.intervalSeconds] seconds with `PUT /c/snapshot`.
 *
 * Runs on its own IO scope owned by the recording service. Frames come from
 * the MJPEG pipeline — while the camera is off there is no frame, uploads
 * are skipped, and Connect simply shows the camera as offline.
 *
 * An invalid/expired token (401/403/404) is fatal: the loop stops itself and
 * reports it via [onFatal]; it resumes when [start] is called again (e.g.
 * after the user saves a new token).
 */
class PrusaUploader(
    private val client: PrusaConnectClient,
    private val settingsProvider: () -> PrusaConnectSettings,
    private val latestFrameProvider: () -> ByteArray?,
    private val cameraInfoProvider: () -> PrusaCameraInfo,
    private val onState: (PrusaState) -> Unit,
    private val onFatal: (String) -> Unit,
) {
    companion object {
        /** Poll cadence while disabled (picks up re-enabling without a restart). */
        private const val POLL_WHEN_DISABLED_MS = 5_000L

        /** Retry delay after a failed info send. */
        private const val INFO_RETRY_DELAY_MS = 15_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile private var running = false

    @Volatile private var infoStale = true

    @Volatile private var registered = false

    @Volatile private var lastUploadMs = 0L

    @Volatile private var error: String? = null

    /** Token length last reported as missing/invalid (avoid log spam). */
    @Volatile private var lastLoggedTokenLen = -1

    /** Force a re-send of `PUT /c/info` on the next cycle (e.g. new IP). */
    fun markInfoStale() {
        infoStale = true
    }

    /** Start (or resume) the loop. Idempotent. */
    fun start() {
        if (job?.isActive == true) return
        running = true
        infoStale = true
        job =
            scope.launch {
                // The launched job, from the child scope's context.
                loop(this.coroutineContext[Job]!!)
            }
        Timber.i("Prusa Connect uploader started")
    }

    /** Stop the loop and reset transient state. */
    fun stop() {
        running = false
        job?.cancel()
        job = null
        registered = false
        error = null
        Timber.i("Prusa Connect uploader stopped")
    }

    /** Stop the loop and release the coroutine scope (service shutdown). */
    fun release() {
        stop()
        scope.cancel()
    }

    private fun publishState() {
        onState(
            PrusaState(
                enabled = settingsProvider().enabled,
                registered = registered,
                lastUploadMs = lastUploadMs,
                error = error,
            ),
        )
    }

    private suspend fun loop(self: Job) {
        while (running) {
            self.ensureActive()
            try {
                val settings = settingsProvider()
                if (!settings.enabled || !PrusaConnectSettings.isValidToken(settings.token)) {
                    if (settings.enabled && settings.token.length != lastLoggedTokenLen) {
                        lastLoggedTokenLen = settings.token.length
                        Timber.w(
                            "Prusa: enabled but token is ${settings.token.length} chars " +
                                "(need ${PrusaConnectSettings.TOKEN_LENGTH}) — waiting for a valid token",
                        )
                    }
                    delay(POLL_WHEN_DISABLED_MS)
                    continue
                }

                // Registration phase: (re)send the camera info.
                if (infoStale) {
                    infoStale = false
                    Timber.d(
                        "Prusa: sending camera info (name=${settings.cameraName}, " +
                            "host=${settings.hostname}, fp=${settings.fingerprint.take(8)}…, " +
                            "interval=${settings.intervalSeconds}s)",
                    )
                    when (val result = client.sendInfo(cameraInfoProvider())) {
                        is PrusaResult.Ok -> {
                            registered = result.registered ?: true
                            error = null
                            publishState()
                            Timber.i("Prusa: info accepted (registered=$registered)")
                        }

                        is PrusaResult.InvalidToken -> {
                            Timber.w(
                                "Prusa: info rejected — invalid token: " +
                                    "${result.detail.take(200)}",
                            )
                            fatal("Token invalid or expired — re-add the camera in Prusa Connect")
                            return
                        }

                        is PrusaResult.Rejected -> {
                            Timber.w(
                                "Prusa: info rejected — HTTP ${result.code} " +
                                    "${result.detail.take(200)}",
                            )
                            error = "Connect rejected info: ${result.detail}"
                            publishState()
                            infoStale = true
                            delay(INFO_RETRY_DELAY_MS)
                        }

                        is PrusaResult.NetworkError -> {
                            Timber.w("Prusa: info network error: ${result.detail}")
                            error = "Cannot reach Prusa Connect"
                            publishState()
                            infoStale = true
                            delay(INFO_RETRY_DELAY_MS)
                        }
                    }
                }

                // Snapshot phase: one upload per interval until stopped.
                while (running) {
                    self.ensureActive()
                    delay(settingsProvider().intervalSeconds * 1000L)
                    val frame = latestFrameProvider()
                    if (frame == null) {
                        Timber.d("Prusa: skipping upload — no frame yet (camera off?)")
                        continue
                    }
                    Timber.d("Prusa: uploading snapshot (${frame.size} bytes)")
                    val started = System.currentTimeMillis()
                    when (val result = client.uploadSnapshot(frame)) {
                        is PrusaResult.Ok -> {
                            lastUploadMs = System.currentTimeMillis()
                            error = null
                            publishState()
                            Timber.i(
                                "Prusa: snapshot uploaded OK (${frame.size} bytes, " +
                                    "${System.currentTimeMillis() - started} ms)",
                            )
                        }

                        is PrusaResult.InvalidToken -> {
                            Timber.w(
                                "Prusa: snapshot rejected — invalid token: " +
                                    "${result.detail.take(200)}",
                            )
                            fatal("Token invalid or expired — re-add the camera in Prusa Connect")
                            return
                        }

                        is PrusaResult.Rejected -> {
                            Timber.w(
                                "Prusa: snapshot rejected — HTTP ${result.code} " +
                                    "${result.detail.take(200)}",
                            )
                            error = "Connect rejected snapshot: ${result.detail}"
                            publishState()
                        }

                        is PrusaResult.NetworkError -> {
                            Timber.w("Prusa: snapshot network error: ${result.detail}")
                            error = "Upload failed: ${result.detail}"
                            publishState()
                        }
                    }
                }
                // Outer loop re-checks settings (interval/name/token changes).
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never die silently: log, surface the error, and retry.
                Timber.e(
                    e,
                    "Prusa: upload loop crashed — retrying in ${INFO_RETRY_DELAY_MS / 1000}s",
                )
                error = "Prusa error: ${e.message ?: e::class.java.simpleName}"
                publishState()
                infoStale = true
                delay(INFO_RETRY_DELAY_MS)
            }
        }
    }

    private fun fatal(message: String) {
        error = message
        running = false
        publishState()
        Timber.w("Prusa Connect: $message")
        onFatal(message)
    }
}
