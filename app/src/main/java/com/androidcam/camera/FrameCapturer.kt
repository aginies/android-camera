package com.androidcam.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
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
 */
class FrameCapturer(
    private val deviceState: DeviceState,
    private val onFrame: (jpegBytes: ByteArray) -> Unit,
) : ImageAnalysis.Analyzer {
    companion object {
        /** Minimum time between processed frames (~10 fps stream rate). */
        private const val MIN_FRAME_INTERVAL_NS = 100_000_000L
        private val TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd  HH:mm:ss", Locale.US)
    }

    /** Single-thread executor the [ImageAnalysis] use case delivers frames on. */
    val executor: Executor = Executors.newSingleThreadExecutor()

    private val lastFrameNanos = AtomicLong(0)

    // Cached timestamp text bitmap; re-rasterized only when the displayed
    // second (or the frame height) changes, not on every frame.
    private var textBitmap: Bitmap? = null
    private var textBitmapSecond = 0L
    private var textBitmapHeight = 0

    override fun analyze(imageProxy: ImageProxy) {
        try {
            val now = System.nanoTime()
            if (now - lastFrameNanos.get() < MIN_FRAME_INTERVAL_NS) return
            lastFrameNanos.set(now)

            val jpeg = imageProxyToJpeg(imageProxy)
            val rotation = deviceState.rotationDegrees % 360
            val frame =
                when {
                    rotation == 0 && deviceState.timestampEnabled -> overlayTimestamp(jpeg)
                    rotation == 0 -> jpeg
                    else -> rotateJpeg(jpeg, rotation)
                }
            onFrame(frame)
        } catch (e: Exception) {
            Timber.e(e, "Frame processing failed")
            deviceState.lastError = "Frame processing failed: ${e.message}"
        } finally {
            imageProxy.close()
        }
    }

    /**
     * Convert an [ImageProxy] in YUV_420_888 format to a JPEG byte array.
     *
     * The three YUV_420_888 planes are repacked into NV21 (which [YuvImage]
     * understands), honouring row and pixel strides so tiled or interleaved
     * plane layouts work.
     */
    private fun imageProxyToJpeg(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height

        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val yRowStride = image.planes[0].rowStride
        val uvRowStride = image.planes[1].rowStride
        val uvPixelStride = image.planes[1].pixelStride

        val nv21 = ByteArray(width * height * 3 / 2)
        var outPos = 0

        // Y plane (absolute reads — plane buffers are read-only)
        if (yRowStride == width) {
            for (i in 0 until width * height) {
                nv21[i] = yBuffer.get(i)
            }
            outPos = width * height
        } else {
            for (row in 0 until height) {
                val base = row * yRowStride
                for (col in 0 until width) {
                    nv21[outPos++] = yBuffer.get(base + col)
                }
            }
        }

        // Interleave V/U in NV21 order (V first)
        val halfWidth = width / 2
        val halfHeight = height / 2
        for (row in 0 until halfHeight) {
            for (col in 0 until halfWidth) {
                val idx = row * uvRowStride + col * uvPixelStride
                nv21[outPos++] = vBuffer.get(idx)
                nv21[outPos++] = uBuffer.get(idx)
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            Rect(0, 0, width, height),
            deviceState.jpegQuality,
            out,
        )
        return out.toByteArray()
    }

    /** Rotate a JPEG by [degrees] (90/180/270), stamping the timestamp if enabled. */
    private fun rotateJpeg(
        jpeg: ByteArray,
        degrees: Int,
    ): ByteArray {
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        if (rotated !== src) src.recycle()
        if (deviceState.timestampEnabled) {
            drawTimestamp(Canvas(rotated), rotated.height)
        }
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, deviceState.jpegQuality, out)
        rotated.recycle()
        return out.toByteArray()
    }

    /** Decode [jpeg], burn in the timestamp, and re-encode. */
    private fun overlayTimestamp(jpeg: ByteArray): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        drawTimestamp(Canvas(bitmap), bitmap.height)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, deviceState.jpegQuality, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /** Draw the timestamp text at the bottom-left of [canvas]. */
    private fun drawTimestamp(canvas: Canvas, height: Int) {
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
