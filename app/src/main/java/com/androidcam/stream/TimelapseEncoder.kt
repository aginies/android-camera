package com.androidcam.stream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.view.Surface
import timber.log.Timber
import java.io.File

/**
 * Assembles a timelapse MP4 (H.264, no audio) from a sequence of JPEG frames.
 *
 * Each frame is decoded and drawn into the encoder's input [Surface] at
 * [OUTPUT_FPS], so captures taken every N seconds play back as a sped-up
 * timelapse. The encoder consumes the surface (COLOR_FormatSurface), so the
 * GPU performs the color conversion — no per-frame software RGB→YUV pass.
 *
 * There is no public API to set a surface buffer's timestamp, so the encoder
 * takes the wall-clock post time as each frame's PTS (the same scheme camera
 * recordings use). The posts are therefore paced to exactly [OUTPUT_FPS] to
 * keep the playback rate constant.
 *
 * [encode] is blocking — call it on a background thread. The [muxer] is
 * stopped (not closed) on return; the caller closes it.
 */
object TimelapseEncoder {
    private const val MIME = "video/avc"
    private const val OUTPUT_FPS = 30
    private const val BIT_RATE = 8_000_000

    /**
     * Encode [frames] (in order) into [muxer]. [onProgress] is invoked with
     * (done, total) after each frame is fed to the encoder. Returns true if a
     * playable video was produced.
     */
    fun encode(
        frames: List<File>,
        muxer: MediaMuxer,
        onProgress: (Int, Int) -> Unit,
    ): Boolean {
        if (frames.isEmpty()) return false
        val probe = BitmapFactory.decodeFile(frames[0].absolutePath)
        if (probe == null) {
            Timber.e("Timelapse: cannot decode first frame ${frames[0].name}")
            return false
        }
        val width = probe.width
        val height = probe.height
        probe.recycle()

        val encoder = MediaCodec.createEncoderByType(MIME)
        val format = MediaFormat.createVideoFormat(MIME, width, height)
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, OUTPUT_FPS)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val track = intArrayOf(-1)
        val frameNs = 1_000_000_000L / OUTPUT_FPS
        val srcRect = Rect()
        val dstRect = Rect(0, 0, width, height)
        var firstPostNanos: Long? = null
        var encoded = 0
        var failed = false

        try {
            for ((index, file) in frames.withIndex()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap == null) {
                    Timber.w("Timelapse: skipping undecodable frame ${file.name}")
                    continue
                }
                // Drain before blocking on an input buffer so the encoder
                // always has room to emit output while we wait.
                drain(encoder, muxer, track, timeoutUs = 0)
                val canvas = lockCanvas(inputSurface)
                if (canvas == null) {
                    bitmap.recycle()
                    failed = true
                    break
                }
                try {
                    canvas.drawColor(Color.BLACK)
                    // Stretch to the encoder dimensions (GPU-scaled), matching
                    // the old createScaledBitmap behavior.
                    srcRect.set(0, 0, bitmap.width, bitmap.height)
                    canvas.drawBitmap(bitmap, srcRect, dstRect, null)
                    inputSurface.unlockCanvasAndPost(canvas)
                } finally {
                    bitmap.recycle()
                }
                encoded++
                onProgress(index + 1, frames.size)
                drain(encoder, muxer, track, timeoutUs = 0)
                // The encoder takes the wall-clock post time as the frame's
                // PTS (no public API sets a surface buffer's timestamp), so
                // pace the posts 1/OUTPUT_FPS apart for a constant playback
                // rate.
                val base = firstPostNanos ?: System.nanoTime().also { firstPostNanos = it }
                val targetNanos = base + (encoded - 1) * frameNs
                val sleepNanos = targetNanos - System.nanoTime()
                if (sleepNanos > 0L) {
                    Thread.sleep(sleepNanos / 1_000_000L, (sleepNanos % 1_000_000L).toInt())
                }
            }

            if (!failed) {
                // The end-of-stream flag rides on the next buffer posted to
                // the surface; that buffer is not encoded, it only terminates
                // the stream.
                encoder.signalEos()
                drain(encoder, muxer, track, timeoutUs = 0)
                val canvas = lockCanvas(inputSurface)
                if (canvas == null) {
                    failed = true
                } else {
                    canvas.drawColor(Color.BLACK)
                    inputSurface.unlockCanvasAndPost(canvas)
                    drain(encoder, muxer, track, timeoutUs = 10_000, untilEos = true)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Timelapse encoding failed")
            failed = true
        } finally {
            try {
                encoder.stop()
            } catch (_: Exception) {
            }
            encoder.release()
            if (track[0] >= 0) {
                try {
                    muxer.stop()
                } catch (e: Exception) {
                    Timber.e(e, "Timelapse: muxer stop failed")
                    failed = true
                }
            }
        }
        return !failed && encoded > 0
    }

    /**
     * Signal end-of-stream to [encoder]. [MediaCodec.signalEndOfInputStream]
     * is public from API 33; on older devices the equivalent
     * `signalEndOfFrame()` is @hide, so it is invoked via reflection.
     */
    private fun MediaCodec.signalEos() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            signalEndOfInputStream()
        } else {
            javaClass.getMethod("signalEndOfFrame").invoke(this)
        }
    }

    /**
     * Lock a canvas on the encoder's input surface, preferring a hardware
     * canvas (GPU composition, no CPU copy of the canvas). Returns null if
     * the surface cannot be locked.
     */
    private fun lockCanvas(surface: Surface): Canvas? =
        try {
            surface.lockHardwareCanvas() ?: surface.lockCanvas(null)
        } catch (e: Exception) {
            Timber.w(e, "Timelapse: failed to lock encoder surface")
            null
        }

    /**
     * Drain available output buffers into [muxer]; initializes the track on
     * format change. [untilEos] keeps polling (with a bounded retry budget)
     * until the end-of-stream flag arrives.
     */
    private fun drain(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        track: IntArray,
        timeoutUs: Long,
        untilEos: Boolean = false,
    ) {
        val info = MediaCodec.BufferInfo()
        var retries = 0
        while (true) {
            val outIdx = encoder.dequeueOutputBuffer(info, timeoutUs)
            when {
                outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEos) return
                    if (++retries > 2000) {
                        Timber.e("Timelapse: timed out waiting for encoder end-of-stream")
                        return
                    }
                    Thread.sleep(5)
                }

                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (track[0] < 0) {
                        track[0] = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                    }
                }

                outIdx >= 0 -> {
                    val buf = encoder.getOutputBuffer(outIdx)
                    if (info.size > 0 && buf != null && track[0] >= 0) {
                        // Keep the encoder's presentation timestamps as-is: they
                        // are monotonically increasing (wall-clock post times,
                        // paced 1/OUTPUT_FPS apart). Resetting sync frames to 0
                        // makes MediaMuxer throw "timestamp is not monotonically
                        // increasing" once a second keyframe is emitted.
                        muxer.writeSampleData(track[0], buf, info)
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }
}
