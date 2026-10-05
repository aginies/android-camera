package com.androidcam.stream

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import timber.log.Timber
import java.io.File

/**
 * Assembles a timelapse MP4 (H.264, no audio) from a sequence of JPEG frames.
 *
 * Each frame is decoded, converted to YUV 4:2:0, and fed to a [MediaCodec]
 * encoder at [OUTPUT_FPS], so captures taken every N seconds play back as a
 * sped-up timelapse. [encode] is blocking — call it on a background thread.
 * The [muxer] is stopped (not closed) on return; the caller closes it.
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
        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
        )
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val colorFormat = encoder.inputFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT)
        val ySize = width * height
        val yuv = ByteArray(ySize + ySize / 2)
        val rgba = IntArray(ySize)
        val track = intArrayOf(-1)
        val frameUs = 1_000_000L / OUTPUT_FPS
        var pts = 0L
        var encoded = 0
        var failed = false

        try {
            for ((index, file) in frames.withIndex()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap == null) {
                    Timber.w("Timelapse: skipping undecodable frame ${file.name}")
                    continue
                }
                val scaled =
                    if (bitmap.width == width && bitmap.height == height) {
                        bitmap
                    } else {
                        Bitmap.createScaledBitmap(bitmap, width, height, true).also {
                            if (it !== bitmap) bitmap.recycle()
                        }
                    }
                scaled.getPixels(rgba, 0, width, 0, 0, width, height)
                scaled.recycle()
                rgbToYuv(rgba, yuv, width, height, colorFormat)

                val inIdx = awaitInputBuffer(encoder, muxer, track, frameIndex = index)
                if (inIdx < 0) {
                    failed = true
                    break
                }
                val input = encoder.getInputBuffer(inIdx)
                if (input == null) {
                    failed = true
                    break
                }
                input.clear()
                input.put(yuv)
                encoder.queueInputBuffer(inIdx, 0, yuv.size, pts, 0)
                pts += frameUs
                encoded++
                onProgress(index + 1, frames.size)
                drain(encoder, muxer, track, timeoutUs = 0)
            }

            if (!failed) {
                val inIdx = awaitInputBuffer(encoder, muxer, track, frameIndex = null)
                if (inIdx >= 0) {
                    encoder.queueInputBuffer(inIdx, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    failed = true
                }
                drain(encoder, muxer, track, timeoutUs = 10_000, untilEos = true)
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
     * Wait for a free input buffer, draining output while waiting so the
     * encoder's pipeline keeps moving. Returns the buffer index, or -1 after
     * a ~1s budget (the encoder is stuck).
     */
    private fun awaitInputBuffer(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        track: IntArray,
        frameIndex: Int?,
    ): Int {
        var inIdx = -1
        var waited = 0
        while (inIdx < 0 && waited < 100) {
            inIdx = encoder.dequeueInputBuffer(10_000)
            if (inIdx < 0) {
                drain(encoder, muxer, track, timeoutUs = 0)
                waited++
            }
        }
        if (inIdx < 0) {
            Timber.e(
                "Timelapse: encoder input buffer unavailable " +
                    if (frameIndex == null) "at end-of-stream" else "at frame $frameIndex",
            )
        }
        return inIdx
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
                        // are monotonically increasing (input PTS starts at 0 and
                        // advances per frame). Resetting sync frames to 0 makes
                        // MediaMuxer throw "timestamp is not monotonically
                        // increasing" once a second keyframe is emitted.
                        muxer.writeSampleData(track[0], buf, info)
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /**
     * Convert RGBA pixels to YUV 4:2:0 in [out]: Y plane followed by
     * interleaved chroma (NV12 order, or NV21 when the encoder reports
     * COLOR_FormatYUV420PackedPlanar).
     */
    private fun rgbToYuv(
        rgba: IntArray,
        out: ByteArray,
        width: Int,
        height: Int,
        colorFormat: Int,
    ) {
        val ySize = width * height
        var i = 0
        for (row in 0 until height) {
            for (col in 0 until width) {
                val p = rgba[row * width + col]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                out[i++] =
                    (((19595 * r + 38470 * g + 7471 * b + 32768) shr 16) + 16)
                        .coerceIn(16, 235)
                        .toByte()
            }
        }
        val nv21 = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar
        val halfW = width / 2
        val halfH = height / 2
        var uv = ySize
        for (row in 0 until halfH) {
            for (col in 0 until halfW) {
                val p = rgba[row * 2 * width + col * 2]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val u = ((-16874 * r - 33126 * g + 50000 * b + 32768) shr 16) + 128
                val v = ((50000 * r - 41869 * g - 8131 * b + 32768) shr 16) + 128
                if (nv21) {
                    out[uv++] = v.coerceIn(16, 240).toByte()
                    out[uv++] = u.coerceIn(16, 240).toByte()
                } else {
                    out[uv++] = u.coerceIn(16, 240).toByte()
                    out[uv++] = v.coerceIn(16, 240).toByte()
                }
            }
        }
    }
}
