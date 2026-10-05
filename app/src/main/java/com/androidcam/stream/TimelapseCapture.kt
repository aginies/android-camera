package com.androidcam.stream

import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Captures one JPEG per [intervalMs] from the streaming frame feed, storing
 * them sequentially in [dir] as `frame_NNNNN.jpg`.
 *
 * The frames are assembled into a timelapse video when the session ends and
 * are then deleted. JPEGs keep the on-disk footprint small (~100-200 KB per
 * frame at 720p).
 *
 * The timestamp overlay (when enabled) is applied upstream in
 * [com.androidcam.camera.FrameCapturer], so the frames saved here already
 * carry it.
 *
 * [onFrame] is called from the frame-capturer executor thread only.
 */
class TimelapseCapture(
    private val dir: File,
    @Volatile var intervalMs: Long,
    private val onFrameSaved: () -> Unit,
) {
    init {
        dir.mkdirs()
    }

    // Continue numbering after any frames left over from a previous session.
    private val count = AtomicInteger(existingFrameCount())
    private var lastCaptureNanos = 0L

    /** Save [jpeg] if at least [intervalMs] have passed since the last capture. */
    fun onFrame(jpeg: ByteArray) {
        val now = System.nanoTime()
        if (now - lastCaptureNanos < intervalMs * 1_000_000L) return
        lastCaptureNanos = now
        val file = File(dir, "frame_%05d.jpg".format(count.get()))
        try {
            file.writeBytes(jpeg)
            count.incrementAndGet()
            onFrameSaved()
        } catch (e: Exception) {
            Timber.e(e, "Timelapse frame save failed")
        }
    }

    /** Captured frames in capture order. */
    fun frames(): List<File> =
        dir
            .listFiles { f -> f.name.endsWith(".jpg") }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun frameCount(): Int = count.get()

    private fun existingFrameCount(): Int =
        dir
            .listFiles { f -> f.name.endsWith(".jpg") }
            ?.size
            ?: 0
}
