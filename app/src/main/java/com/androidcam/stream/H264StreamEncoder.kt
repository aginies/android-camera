package com.androidcam.stream

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Hardware H.264 encoder feeding the RTSP server.
 *
 * Input: NV21 frames from the camera's ImageAnalysis feed (via
 * [com.androidcam.camera.FrameCapturer]), rotated to match the MJPEG stream's
 * orientation. Output: H.264 access units (NAL units without start codes) for
 * RFC 6184 RTP packetization, plus SPS/PPS for the SDP.
 *
 * A daemon "pump" thread continuously drains the encoder output into
 * [latestUnit] so the encoder never stalls (even without RTSP clients) and
 * SPS/PPS are extracted as soon as they exist. The MediaCodec itself is
 * created lazily on the first input frame, because the delivered frame size
 * is only known at runtime.
 */
class H264StreamEncoder(
    private val fps: Int = DEFAULT_FPS,
    private val bitrate: Int = DEFAULT_BITRATE,
    private val targetWidth: Int = DEFAULT_TARGET_WIDTH,
    private val targetHeight: Int = DEFAULT_TARGET_HEIGHT,
) {
    companion object {
        private const val MIME = "video/avc"

        /** Stream frame rate (matches the MJPEG stream). */
        private const val DEFAULT_FPS = 10

        /** 4 Mbps is high quality for 720p@10 fps and far below the MJPEG bitrate. */
        private const val DEFAULT_BITRATE = 4_000_000

        /**
         * Encode at most this size. The camera may deliver frames larger than
         * the requested ImageAnalysis target (and some hardware encoders reject
         * unusual large sizes), so input is downscaled to fit.
         */
        private const val DEFAULT_TARGET_WIDTH = 1280
        private const val DEFAULT_TARGET_HEIGHT = 720

        /** IDR interval in seconds; a joining client waits at most this long. */
        private const val KEYFRAME_INTERVAL_S = 2

        // MediaCodecInfo.CodecCapabilities color formats
        private const val CF_NV21 = 17
        private const val CF_YV12 = 19
        private const val CF_I420 = 20
        private const val CF_NV12 = 21
    }

    /** One encoded frame: NAL units (no start codes) plus metadata. */
    data class AccessUnit(
        /** Monotonically increasing id so consumers can detect new frames. */
        val id: Long,
        val nals: List<ByteArray>,
        val isKeyFrame: Boolean,
        /** Presentation timestamp in 90 kHz RTP clock units. */
        val pts90k: Long,
    )

    /** Latest encoded frame; null until the first frame is encoded. */
    @Volatile
    var latestUnit: AccessUnit? = null
        private set

    /** Sequence parameter set (without start code), once known. */
    @Volatile
    var sps: ByteArray? = null
        private set

    /** Picture parameter set (without start code), once known. */
    @Volatile
    var pps: ByteArray? = null
        private set

    private val running = AtomicBoolean(false)
    private val unitCounter = AtomicLong(0)

    /**
     * Shared between the capturer thread (writer) and the pump thread (reader)
     * — volatile so the pump sees reconfigurations immediately.
     */
    @Volatile
    private var codec: MediaCodec? = null

    private var pumpThread: Thread? = null

    @Volatile
    private var colorFormat = CF_I420
    private var width = 0
    private var height = 0

    @Volatile
    private var lastConfigureFailMs = 0L

    /**
     * All MediaCodec access (configure, queue input, dequeue output,
     * setParameters) happens on the pump thread. The capturer thread only
     * does CPU work (rotate/scale/convert) and stages the result here —
     * concurrent MediaCodec access from two threads makes the Qualcomm
     * encoder throw "buffer has been freed".
     */
    private class PendingFrame(
        val data: ByteArray,
        val w: Int,
        val h: Int,
    )

    @Volatile
    private var pendingFrame: PendingFrame? = null

    /** Size the capturer wants the codec configured to (pump applies it). */
    @Volatile
    private var desiredSize: Pair<Int, Int>? = null

    /** New RTSP client asked for an IDR (pump applies it). */
    @Volatile
    private var keyFrameRequested = false

    /**
     * Frame counter for presentation timestamps. Using a counter (rather than
     * nanoTime) keeps the 90kHz RTP timestamp small and monotonic — a large
     * arbitrary PTS overflows the 32-bit RTP timestamp field and makes
     * players (VLC) treat every frame as "5+ seconds late" and drop it.
     */
    private var frameCount = 0L

    /**
     * Start the pump thread. It owns the MediaCodec: it configures it when
     * the first frame arrives (the delivered size is only known then), feeds
     * staged input frames, and drains output into [latestUnit].
     */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        pumpThread =
            Thread({
                while (running.get()) {
                    try {
                        // (Re)configure when the capturer asked for a size.
                        val want = desiredSize
                        if (want != null) {
                            val c = codec
                            if (c == null || want.first != width || want.second != height) {
                                desiredSize = null
                                width = want.first
                                height = want.second
                                sps = null
                                pps = null
                                latestUnit = null
                                configureAndStart()
                                sleepQuietly(50)
                                continue
                            }
                        }
                        // A new RTSP client wants an IDR now.
                        if (keyFrameRequested) {
                            keyFrameRequested = false
                            requestKeyFrameInternal()
                        }
                        val c = codec
                        if (c == null) {
                            sleepQuietly(20)
                            continue
                        }
                        // Drain all available output (non-blocking).
                        val info = MediaCodec.BufferInfo()
                        while (true) {
                            val idx = c.dequeueOutputBuffer(info, 0)
                            if (idx < 0) break
                            val buf = c.getOutputBuffer(idx)
                            if (buf != null) {
                                // Read the data BEFORE releasing the buffer —
                                // the encoder reclaims it on release.
                                processOutput(buf, info)
                            }
                            c.releaseOutputBuffer(idx, false)
                        }
                        // Feed the latest staged input frame.
                        val frame = pendingFrame
                        if (frame != null) {
                            val iidx = c.dequeueInputBuffer(0)
                            if (iidx >= 0) {
                                pendingFrame = null
                                val ibuf = c.getInputBuffer(iidx)
                                if (ibuf != null) {
                                    ibuf.clear()
                                    ibuf.put(frame.data)
                                    val ptsUs = frameCount * (1_000_000L / fps)
                                    frameCount++
                                    c.queueInputBuffer(iidx, 0, frame.data.size, ptsUs, 0)
                                }
                            }
                        }
                        sleepQuietly(5)
                    } catch (e: Exception) {
                        // The codec may be reconfigured concurrently; the next
                        // iteration picks up the new instance.
                        sleepQuietly(20)
                    }
                }
            }, "h264-pump").apply {
                isDaemon = true
                start()
            }
        Timber.i("H264 encoder started ($fps fps, ${bitrate / 1000} kbps)")
    }

    /**
     * Feed one NV21 frame. [rotation] is the display rotation in degrees
     * (0/90/180/270). Called from the capturer thread. Does CPU conversion
     * here and stages the result for the pump thread (which owns the codec).
     */
    fun inputFrame(
        nv21: ByteArray,
        srcW: Int,
        srcH: Int,
        rotation: Int,
    ) {
        if (!running.get() || srcW == 0 || srcH == 0) return
        val r = ((rotation % 360) + 360) % 360
        val rotW = if (r == 90 || r == 270) srcH else srcW
        val rotH = if (r == 90 || r == 270) srcW else srcH
        // Downscale to fit within the target while preserving aspect ratio.
        // Dimensions are rounded to 16 (the encoder's block size).
        val scale =
            minOf(1.0, targetWidth.toDouble() / rotW, targetHeight.toDouble() / rotH)
        val outW = (rotW * scale).toInt() / 16 * 16
        val outH = (rotH * scale).toInt() / 16 * 16
        if (outW < 64 || outH < 64) return
        // Ask the pump to (re)configure when the size changes.
        val c = codec
        if (c == null || outW != width || outH != height) {
            desiredSize = outW to outH
        }
        try {
            val rotated = if (r == 0) nv21 else rotateNv21(nv21, srcW, srcH, r)
            val scaled =
                if (outW == rotW && outH == rotH) {
                    rotated
                } else {
                    downscaleNv21(rotated, rotW, rotH, outW, outH)
                }
            val yuv = convertToColorFormat(scaled, outW, outH)
            pendingFrame = PendingFrame(yuv, outW, outH)
        } catch (e: Exception) {
            Timber.w(e, "H264 encoder input error")
        }
    }

    /** Ask the encoder for an IDR frame as soon as possible (new RTSP client). */
    fun requestKeyFrame() {
        keyFrameRequested = true
    }

    private fun requestKeyFrameInternal() {
        try {
            val c = codec ?: return
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            c.setParameters(params)
        } catch (e: Exception) {
            Timber.w(e, "H264 requestKeyFrame failed")
        }
    }

    /** Stop the pump and release the codec. */
    fun release() {
        if (!running.compareAndSet(true, false)) return
        codec?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        codec = null
        latestUnit = null
        sps = null
        pps = null
        Timber.d("H264 encoder released")
    }

    // -- Internals -------------------------------------------------------------

    private fun processOutput(
        buf: ByteBuffer,
        info: MediaCodec.BufferInfo,
    ) {
        val flags = info.flags
        if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            // Config data (SPS/PPS) — not a frame.
            for (nal in parseNals(toByteArray(buf))) {
                when (nal[0].toInt() and 0x1F) {
                    7 -> sps = nal
                    8 -> pps = nal
                }
            }
            return
        }
        val nals = parseNals(toByteArray(buf))
        if (nals.isEmpty()) return
        for (nal in nals) {
            when (nal[0].toInt() and 0x1F) {
                7 -> sps = nal
                8 -> pps = nal
            }
        }
        val isKey = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        latestUnit =
            AccessUnit(
                id = unitCounter.incrementAndGet(),
                nals = nals,
                isKeyFrame = isKey,
                pts90k = info.presentationTimeUs * 90_000 / 1_000_000,
            )
    }

    private fun configureAndStart() {
        // Release any existing codec (first configure or size change).
        codec?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        codec = null
        try {
            val result = createConfiguredEncoder()
            if (result == null) {
                lastConfigureFailMs = System.currentTimeMillis()
                Timber.e("H264 encoder configure failed (${width}x$height): no encoder/color-format combination accepted")
                return
            }
            val (c, format) = result
            colorFormat = format
            c.start()
            codec = c
            requestKeyFrameInternal()
            Timber.i("H264 encoder configured: ${width}x$height colorFormat=$colorFormat")
        } catch (e: Exception) {
            lastConfigureFailMs = System.currentTimeMillis()
            Timber.e(e, "H264 encoder start failed (${width}x$height)")
        }
    }

    /**
     * Find an encoder + color format combination that works. Tries each
     * available H.264 encoder (in system priority order) with each supported
     * YUV 4:2:0 layout; some hardware encoders reject requested color formats
     * and some (C2) require one explicitly.
     */
    private fun createConfiguredEncoder(): Pair<MediaCodec, Int>? {
        val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        for (info in codecInfos) {
            if (!info.isEncoder) continue
            if (MIME !in info.supportedTypes) continue
            for (cf in intArrayOf(CF_I420, CF_NV12, CF_NV21, CF_YV12)) {
                val format =
                    MediaFormat.createVideoFormat(MIME, width, height).apply {
                        setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                        setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_INTERVAL_S)
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, cf)
                    }
                val candidate = MediaCodec.createByCodecName(info.name)
                try {
                    candidate.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    val actual = candidate.inputFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT)
                    Timber.i("H264 using encoder: ${info.name} (colorFormat=$actual)")
                    return candidate to actual
                } catch (e: Exception) {
                    try {
                        candidate.release()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        return null
    }

    /**
     * Split an encoder output buffer into NAL units. Handles both annex-B
     * (start codes) and AVCC (4-byte length prefix) layouts.
     */
    private fun parseNals(data: ByteArray): List<ByteArray> {
        val scStart = mutableListOf<Int>()
        val scLen = mutableListOf<Int>()
        var i = 0
        while (i + 2 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                scStart.add(i)
                scLen.add(3)
                i += 3
            } else if (
                i + 3 < data.size &&
                data[i] == 0.toByte() &&
                data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() &&
                data[i + 3] == 1.toByte()
            ) {
                scStart.add(i)
                scLen.add(4)
                i += 4
            } else {
                i++
            }
        }
        if (scStart.isNotEmpty()) {
            return scStart.indices.map { j ->
                data.copyOfRange(
                    scStart[j] + scLen[j],
                    if (j + 1 < scStart.size) scStart[j + 1] else data.size,
                )
            }
        }
        // No start codes: assume AVCC (4-byte big-endian length prefixes).
        val nals = mutableListOf<ByteArray>()
        var pos = 0
        while (pos + 4 <= data.size) {
            val len =
                ((data[pos].toInt() and 0xFF) shl 24) or
                    ((data[pos + 1].toInt() and 0xFF) shl 16) or
                    ((data[pos + 2].toInt() and 0xFF) shl 8) or
                    (data[pos + 3].toInt() and 0xFF)
            if (len <= 0 || pos + 4 + len > data.size) break
            nals.add(data.copyOfRange(pos + 4, pos + 4 + len))
            pos += 4 + len
        }
        return nals
    }

    /** Convert NV21 to the encoder's input color format. */
    private fun convertToColorFormat(
        nv21: ByteArray,
        w: Int,
        h: Int,
    ): ByteArray =
        when (colorFormat) {
            CF_NV21, CF_YV12 -> {
                nv21
            }

            CF_NV12 -> {
                val out = ByteArray(nv21.size)
                val ySize = w * h
                System.arraycopy(nv21, 0, out, 0, ySize)
                for (i in 0 until (ySize / 2) step 2) {
                    out[ySize + i] = nv21[ySize + i + 1] // U
                    out[ySize + i + 1] = nv21[ySize + i] // V
                }
                out
            }

            else -> {
                // I420: Y plane, then separate U and V planes (each w*h/4).
                val out = ByteArray(nv21.size)
                val ySize = w * h
                val uvSize = ySize / 2
                val quarter = uvSize / 2
                System.arraycopy(nv21, 0, out, 0, ySize)
                var u = 0
                for (i in 0 until uvSize step 2) {
                    out[ySize + u] = nv21[ySize + i + 1] // U
                    out[ySize + quarter + u] = nv21[ySize + i] // V
                    u++
                }
                out
            }
        }

    /** Rotate an NV21 buffer by [degrees] (90/180/270). */
    private fun rotateNv21(
        src: ByteArray,
        w: Int,
        h: Int,
        degrees: Int,
    ): ByteArray {
        val r = ((degrees % 360) + 360) % 360
        if (r == 0) return src
        val out = ByteArray(src.size)
        val ySize = w * h
        val hw = w / 2
        val hh = h / 2
        when (r) {
            180 -> {
                for (y in 0 until h) {
                    val srcRow = (h - 1 - y) * w
                    val dstRow = y * w
                    for (x in 0 until w) {
                        out[dstRow + x] = src[srcRow + (w - 1 - x)]
                    }
                }
                for (y in 0 until hh) {
                    // UV rows are full frame width; chroma columns are 2 bytes.
                    val srcRow = (hh - 1 - y) * w
                    val dstRow = y * w
                    for (x in 0 until hw) {
                        val s = ySize + srcRow + (hw - 1 - x) * 2
                        val d = ySize + dstRow + x * 2
                        out[d] = src[s]
                        out[d + 1] = src[s + 1]
                    }
                }
            }

            90 -> {
                // 90° clockwise: out(x, y) = src(w-1-y, x); output is h x w.
                val outW = h
                for (y in 0 until w) {
                    for (x in 0 until outW) {
                        out[y * outW + x] = src[x * w + (w - 1 - y)]
                    }
                }
                val outUw = outW / 2
                for (cy in 0 until w / 2) {
                    for (cx in 0 until outUw) {
                        // out_uv(cx,cy) = src_uv(hw-1-cy, cx); UV rows are full width.
                        val s = ySize + cx * w + (hw - 1 - cy) * 2
                        val d = ySize + cy * outW + cx * 2
                        out[d] = src[s]
                        out[d + 1] = src[s + 1]
                    }
                }
            }

            else -> {
                // 270° clockwise (90° counter-clockwise): out(x, y) = src(y, h-1-x).
                val outW = h
                for (y in 0 until w) {
                    for (x in 0 until outW) {
                        out[y * outW + x] = src[(h - 1 - x) * w + y]
                    }
                }
                val outUw = outW / 2
                for (cy in 0 until w / 2) {
                    for (cx in 0 until outUw) {
                        // out_uv(cx,cy) = src_uv(cy, hh-1-cx); UV rows are full width.
                        val s = ySize + (hh - 1 - cx) * w + cy * 2
                        val d = ySize + cy * outW + cx * 2
                        out[d] = src[s]
                        out[d + 1] = src[s + 1]
                    }
                }
            }
        }
        return out
    }

    /** Nearest-neighbour NV21 downscale (fast; good enough for live video). */
    private fun downscaleNv21(
        src: ByteArray,
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int,
    ): ByteArray {
        val out = ByteArray(dstW * dstH * 3 / 2)
        for (y in 0 until dstH) {
            val sy = (y * srcH / dstH) * srcW
            val dy = y * dstW
            for (x in 0 until dstW) {
                out[dy + x] = src[sy + x * srcW / dstW]
            }
        }
        val srcUv = srcW * srcH
        val dstUv = dstW * dstH
        val srcUw = srcW / 2
        val srcUh = srcH / 2
        val dstUw = dstW / 2
        val dstUh = dstH / 2
        for (y in 0 until dstUh) {
            // UV rows span the FULL frame width (interleaved U,V pairs), not
            // half width. Stepping by half width packs the rows into half the
            // plane and leaves the bottom half zeroed (green in the output).
            val sy = (y * srcUh / dstUh) * srcW
            val dy = y * dstW
            for (x in 0 until dstUw) {
                val s = srcUv + sy + x * srcUw / dstUw * 2
                val d = dstUv + dy + x * 2
                out[d] = src[s]
                out[d + 1] = src[s + 1]
            }
        }
        return out
    }

    private fun toByteArray(buf: ByteBuffer): ByteArray {
        val dup = buf.duplicate()
        val out = ByteArray(dup.remaining())
        dup.get(out)
        return out
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }
}
