package com.androidcam.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.androidcam.control.DeviceState
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

/**
 * Converts camera frames (YUV_420_888 from [ImageAnalysis]) into JPEG bytes for
 * the MJPEG stream.
 *
 * Frames are throttled to roughly [MIN_FRAME_INTERVAL_NS] apart and rotated
 * according to [DeviceState.rotationDegrees] before being handed to [onFrame].
 * When [DeviceState.timestampEnabled] is set, a date/time stamp is burned in
 * (bottom-left) on every frame, so the live view and the timelapse frames
 * captured from this feed both show it.
 *
 * The raw NV21 frame (unrotated) is also handed to [onYuvFrame] when set —
 * the H.264 RTSP encoder consumes it and applies the rotation itself.
 */
class FrameCapturer(
    private val deviceState: DeviceState,
    private val onFrame: (jpegBytes: ByteArray) -> Unit,
    private val onYuvFrame: ((nv21: ByteArray, width: Int, height: Int, rotation: Int) -> Unit)? = null,
) : ImageAnalysis.Analyzer {
    companion object {
        /** Minimum time between processed frames (~10 fps stream rate). */
        private const val MIN_FRAME_INTERVAL_NS = 100_000_000L
        private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.US)
    }

    private val frameExecutor = Executors.newSingleThreadExecutor()

    /**
     * Executor the [ImageAnalysis] use case delivers frames on. Swallows
     * [RejectedExecutionException] so a frame delivered while the capturer is
     * shutting down is dropped quietly instead of crashing the camera thread.
     */
    val executor: Executor =
        Executor { command ->
            try {
                frameExecutor.execute(command)
            } catch (e: RejectedExecutionException) {
                Timber.d("Frame dropped: capturer is shutting down")
            }
        }

    /** Shut down the frame executor; the worker thread terminates after draining queued frames. */
    fun shutdown() {
        frameExecutor.shutdown()
    }

    private val lastFrameNanos = AtomicLong(0)

    // Cached timestamp text bitmap; re-rasterized only when the displayed
    // second (or the frame height) changes, not on every frame.
    private var textBitmap: Bitmap? = null
    private var textBitmapSecond = 0L
    private var textBitmapHeight = 0

    // Scratch buffers for NV21 unpack and rotation, confined to the single-thread frameExecutor.
    private var scratchVRow: ByteArray? = null
    private var scratchURow: ByteArray? = null
    private var scratchRotatedNv21: ByteArray? = null

    override fun analyze(imageProxy: ImageProxy) {
        try {
            val now = System.nanoTime()
            if (now - lastFrameNanos.get() < MIN_FRAME_INTERVAL_NS) return
            lastFrameNanos.set(now)

            val rotation = ((deviceState.rotationDegrees % 360) + 360) % 360
            val srcW = imageProxy.width
            val srcH = imageProxy.height
            val nv21 = imageProxyToNv21(imageProxy)

            // Fast in-memory NV21 rotation (~1 ms) before compression. This eliminates
            // the heavy JPEG decode + Bitmap matrix transform + second JPEG compression.
            val (frameNv21, outW, outH) =
                if (rotation == 0) {
                    Triple(nv21, srcW, srcH)
                } else {
                    val rot = rotateNv21(nv21, srcW, srcH, rotation)
                    val rw = if (rotation == 90 || rotation == 270) srcH else srcW
                    val rh = if (rotation == 90 || rotation == 270) srcW else srcH
                    Triple(rot, rw, rh)
                }

            // Raw NV21 feed for the H.264 RTSP encoder, already rotated (rotation = 0).
            onYuvFrame?.invoke(frameNv21, outW, outH, 0)

            val jpeg = nv21ToJpeg(frameNv21, outW, outH)
            val frame = if (deviceState.timestampEnabled) overlayTimestamp(jpeg) else jpeg
            onFrame(frame)
        } catch (e: Exception) {
            Timber.e(e, "Frame processing failed")
            deviceState.lastError = "Frame processing failed: ${e.message}"
        } finally {
            imageProxy.close()
        }
    }

    /** Compress an NV21 buffer to JPEG at the configured quality. */
    private fun nv21ToJpeg(
        nv21: ByteArray,
        width: Int,
        height: Int,
    ): ByteArray {
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream(width * height / 8)
        yuvImage.compressToJpeg(
            Rect(0, 0, width, height),
            deviceState.jpegQuality,
            out,
        )
        return out.toByteArray()
    }

    /**
     * Convert an [ImageProxy] in YUV_420_888 format to an NV21 byte array.
     *
     * The three YUV_420_888 planes are repacked into NV21 (which [YuvImage]
     * understands), honouring row and pixel strides so tiled or interleaved
     * plane layouts work. Plane data is moved with bulk `ByteBuffer.get`
     * transfers instead of per-byte reads.
     */
    private fun imageProxyToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val yRowStride = image.planes[0].rowStride
        val uRowStride = image.planes[1].rowStride
        val vRowStride = image.planes[2].rowStride
        val uvPixelStride = image.planes[1].pixelStride

        val nv21 = ByteArray(width * height * 3 / 2)
        var outPos = 0

        // Y plane (bulk copies — plane buffers are read-only; duplicates keep
        // the original buffers' positions untouched)
        val yBuf = yBuffer.duplicate()
        if (yRowStride == width) {
            yBuf.get(nv21, 0, width * height)
            outPos = width * height
        } else {
            for (row in 0 until height) {
                yBuf.position(row * yRowStride)
                yBuf.get(nv21, outPos, width)
                outPos += width
            }
        }

        // Interleave V/U in NV21 order (V first)
        val halfWidth = width / 2
        val halfHeight = height / 2
        if (uvPixelStride == 1) {
            // Separate U/V planes: reuse scratch row buffers to eliminate allocations.
            val vRow =
                if (scratchVRow?.size == halfWidth) scratchVRow!! else ByteArray(halfWidth).also { scratchVRow = it }
            val uRow =
                if (scratchURow?.size == halfWidth) scratchURow!! else ByteArray(halfWidth).also { scratchURow = it }
            val vBuf = vBuffer.duplicate()
            val uBuf = uBuffer.duplicate()
            for (row in 0 until halfHeight) {
                vBuf.position(row * vRowStride)
                vBuf.get(vRow)
                uBuf.position(row * uRowStride)
                uBuf.get(uRow)
                var o = outPos
                for (c in 0 until halfWidth) {
                    nv21[o++] = vRow[c]
                    nv21[o++] = uRow[c]
                }
                outPos = o
            }
        } else {
            // Interleaved (pixelStride == 2) or exotic layout: the pair order
            // (NV12 vs NV21) is not exposed by the API, so read per pixel.
            for (row in 0 until halfHeight) {
                val base = row * uRowStride
                for (col in 0 until halfWidth) {
                    val idx = base + col * uvPixelStride
                    nv21[outPos++] = vBuffer.get(idx)
                    nv21[outPos++] = uBuffer.get(idx)
                }
            }
        }

        return nv21
    }

    /** Rotate an NV21 buffer in RAM by [degrees] (90/180/270). */
    private fun rotateNv21(
        src: ByteArray,
        w: Int,
        h: Int,
        degrees: Int,
    ): ByteArray {
        val r = ((degrees % 360) + 360) % 360
        if (r == 0) return src
        val out =
            if (scratchRotatedNv21?.size == src.size) {
                scratchRotatedNv21!!
            } else {
                ByteArray(src.size).also { scratchRotatedNv21 = it }
            }
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
                // 90° clockwise: out(x, y) = src(h-1-x, y); output is h x w.
                val outW = h
                for (y in 0 until w) {
                    for (x in 0 until outW) {
                        out[y * outW + x] = src[(h - 1 - x) * w + y]
                    }
                }
                val outUw = outW / 2
                for (cy in 0 until w / 2) {
                    for (cx in 0 until outUw) {
                        val s = ySize + (hh - 1 - cx) * w + cy * 2
                        val d = ySize + cy * outW + cx * 2
                        out[d] = src[s]
                        out[d + 1] = src[s + 1]
                    }
                }
            }

            else -> {
                // 270° clockwise (90° counter-clockwise): out(x, y) = src(x, w-1-y).
                val outW = h
                for (y in 0 until w) {
                    for (x in 0 until outW) {
                        out[y * outW + x] = src[x * w + (w - 1 - y)]
                    }
                }
                val outUw = outW / 2
                for (cy in 0 until w / 2) {
                    for (cx in 0 until outUw) {
                        val s = ySize + cx * w + (hw - 1 - cy) * 2
                        val d = ySize + cy * outW + cx * 2
                        out[d] = src[s]
                        out[d + 1] = src[s + 1]
                    }
                }
            }
        }
        return out
    }

    /** Decode [jpeg], burn in the timestamp, and re-encode. */
    private fun overlayTimestamp(jpeg: ByteArray): ByteArray {
        // inMutable is required: Canvas(bitmap) throws on immutable bitmaps,
        // and decodeByteArray returns immutable ones by default.
        val opts = BitmapFactory.Options().apply { inMutable = true }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return jpeg
        drawTimestamp(Canvas(bitmap), bitmap.height)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, deviceState.jpegQuality, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /** Draw the timestamp text at the bottom-left of [canvas]. */
    private fun drawTimestamp(
        canvas: Canvas,
        height: Int,
    ) {
        val text = timestampText(height) ?: return
        val margin = height * 0.03f
        canvas.drawBitmap(text, margin, (height - margin - text.height).toFloat(), null)
    }

    /**
     * The timestamp text as a bitmap (white on transparent, with a black
     * drop shadow), re-rasterized only when the displayed second or the
     * frame height changes.
     */
    private fun timestampText(height: Int): Bitmap? {
        val second = System.currentTimeMillis() / 1000
        val cached = textBitmap
        if (cached != null && textBitmapSecond == second && textBitmapHeight == height) {
            return cached
        }
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = height * 0.045f
                setShadowLayer(textSize * 0.15f, 0f, 0f, Color.BLACK)
            }
        val label = TIMESTAMP_FORMAT.format(Date())
        val pad = (height * 0.01f).toInt().coerceAtLeast(2)
        val w = ceil(paint.measureText(label)).toInt() + pad * 2
        val h = ceil(paint.descent() - paint.ascent()).toInt() + pad * 2
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(label, pad.toFloat(), pad - paint.ascent(), paint)
        cached?.recycle()
        textBitmap = bmp
        textBitmapSecond = second
        textBitmapHeight = height
        return bmp
    }
}
