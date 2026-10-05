package com.androidcam.stream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Captures one JPEG per [intervalMs] from the streaming frame feed, storing
 * them sequentially in [dir] as `frame_NNNNN.jpg`.
 *
 * The frames are assembled into a timelapse video when the session ends and
 * are then deleted. JPEGs keep the on-disk footprint small (~100-200 KB per
 * frame at 720p).
 *
 * When [timestampEnabled] reports true, each frame gets a date/time stamp
 * burned in (bottom-left) before being saved.
 *
 * [onFrame] is called from the frame-capturer executor thread only.
 */
class TimelapseCapture(
    private val dir: File,
    @Volatile var intervalMs: Long,
    private val timestampEnabled: () -> Boolean,
    private val onFrameSaved: () -> Unit,
) {
    companion object {
        private const val TIMESTAMP_JPEG_QUALITY = 95
        private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.US)
    }
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
        val data = if (timestampEnabled()) overlayTimestamp(jpeg) else jpeg
        val file = File(dir, "frame_%05d.jpg".format(count.get()))
        try {
            file.writeBytes(data)
            count.incrementAndGet()
            onFrameSaved()
        } catch (e: Exception) {
            Timber.e(e, "Timelapse frame save failed")
        }
    }

    /** Burn a date/time stamp into [jpeg] (bottom-left) and re-encode. */
    private fun overlayTimestamp(jpeg: ByteArray): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val canvas = Canvas(bitmap)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = bitmap.height * 0.045f
                setShadowLayer(textSize * 0.15f, 0f, 0f, Color.BLACK)
            }
        val margin = bitmap.height * 0.03f
        canvas.drawText(TIMESTAMP_FORMAT.format(Date()), margin, bitmap.height - margin, paint)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, TIMESTAMP_JPEG_QUALITY, out)
        bitmap.recycle()
        return out.toByteArray()
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
