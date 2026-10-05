package com.androidcam.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.OutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.androidcam.control.DeviceState
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executor

/**
 * Manages the CameraX lifecycle: preview, frame analysis (MJPEG streaming), and
 * video recording (MP4/H.264 via [VideoCapture]).
 *
 * This class is the single owner of the camera binding. The preview view is
 * optional — streaming and recording keep working without it (e.g. in the
 * background, or after the activity is destroyed).
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val deviceState: DeviceState,
    private val frameCapturer: FrameCapturer?,
) {
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var previewView: PreviewView? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var videoQuality: Quality = Quality.FHD

    /** Attach (or replace) the local preview view and (re)start the camera. */
    fun attachPreview(view: PreviewView) {
        previewView = view
        startCamera()
    }

    /** Drop the local preview (e.g. activity destroyed); streaming continues. */
    fun detachPreview() {
        previewView = null
        startCamera()
    }

    /**
     * (Re)bind the camera with the current facing plus preview, analysis, and
     * recording use cases.
     */
    fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider =
                try {
                    providerFuture.get()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to get camera provider")
                    deviceState.lastError = "Camera provider unavailable: ${e.message}"
                    return@addListener
                }
            cameraProvider = provider

            val selector =
                when (deviceState.facing) {
                    DeviceState.CameraFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
                    DeviceState.CameraFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
                }

            val preview =
                Preview.Builder().build().also { useCase ->
                    previewView?.let { view -> useCase.surfaceProvider = view.surfaceProvider }
                }

            val imageAnalysis =
                ImageAnalysis
                    .Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(Size(1280, 720))
                    .build()
                    .also { useCase ->
                        frameCapturer?.let { capturer ->
                            useCase.setAnalyzer(capturer.executor) { proxy -> capturer.analyze(proxy) }
                        }
                    }

            val recorder =
                Recorder
                    .Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            videoQuality,
                            FallbackStrategy.lowerQualityOrHigherThan(videoQuality),
                        ),
                    ).build()
                    .also {
                        Timber.i("startCamera: videoQuality=$videoQuality")
                    }
            val capture = VideoCapture.withOutput(recorder)
            videoCapture = capture

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis, capture)
                Timber.d("Camera started: ${deviceState.facing} (preview=${previewView != null})")
            } catch (e: Exception) {
                Timber.e(e, "Camera bind failed")
                deviceState.lastError = "Camera bind failed: ${e.message}"
            }
        }, mainExecutor)
    }

    /** Switch between front and back cameras. No-op while recording. */
    fun switchCamera() {
        if (deviceState.recordingState == DeviceState.RecordingState.RECORDING) {
            deviceState.lastError = "Cannot switch camera while recording"
            return
        }
        deviceState.switchCamera()
        startCamera()
    }

    /** Set an explicit camera facing. No-op while recording or if unchanged. */
    fun setCameraFacing(facing: DeviceState.CameraFacing) {
        if (facing == deviceState.facing) return
        if (deviceState.recordingState == DeviceState.RecordingState.RECORDING) {
            deviceState.lastError = "Cannot switch camera while recording"
            return
        }
        deviceState.facing = facing
        startCamera()
    }

    /** Toggle the torch (flash). Returns the new torch state. */
    fun toggleTorch(): Boolean {
        val isOn = camera?.cameraInfo?.torchState?.value == TorchState.ON
        camera?.cameraControl?.enableTorch(!isOn)
        return !isOn
    }

    /**
     * Focus at [x]/[y] — pixel coordinates in the preview view, as expected by
     * [SurfaceOrientedMeteringPointFactory].
     */
    fun setFocusPoint(
        x: Float,
        y: Float,
    ) {
        val view = previewView ?: return
        if (view.width == 0 || view.height == 0) return
        val factory = SurfaceOrientedMeteringPointFactory(view.width.toFloat(), view.height.toFloat())
        val point: MeteringPoint = factory.createPoint(x, y)
        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    }

    /**
     * Change the recording quality; takes effect on the next (re)bind.
     * No-op while recording.
     */
    fun setVideoQuality(quality: Quality) {
        if (quality == videoQuality) return
        if (deviceState.recordingState == DeviceState.RecordingState.RECORDING) {
            deviceState.lastError = "Cannot change resolution while recording"
            return
        }
        videoQuality = quality
        startCamera()
    }

    /**
     * Set the video quality without rebinding. Intended for use before the
     * camera is started, so a resolution chosen while streaming was off is
     * applied on the next start.
     */
    fun setInitialQuality(quality: Quality) {
        videoQuality = quality
    }

    /** Start recording using [outputOptions]. [displayPath] is shown to the user. Returns true on success. */
    fun startRecording(
        outputOptions: OutputOptions,
        displayPath: String,
    ): Boolean {
        val capture =
            videoCapture
                ?: run {
                    deviceState.lastError = "Camera not ready"
                    return false
                }
        return try {
            // Audio is a runtime permission the user may have denied — fall back
            // to silent video in that case.
            val pending =
                when (outputOptions) {
                    is FileOutputOptions -> {
                        capture.output.prepareRecording(context, outputOptions)
                    }

                    is FileDescriptorOutputOptions -> {
                        capture.output.prepareRecording(context, outputOptions)
                    }

                    is MediaStoreOutputOptions -> {
                        capture.output.prepareRecording(context, outputOptions)
                    }

                    else -> {
                        deviceState.lastError =
                            "Unsupported output options: ${outputOptions::class.simpleName}"
                        return false
                    }
                }
            val pendingWithAudio =
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    pending.withAudioEnabled()
                } else {
                    pending
                }
            val newRecording =
                pendingWithAudio.start(mainExecutor) { event ->
                    when (event) {
                        is VideoRecordEvent.Finalize -> {
                            if (event.hasError()) {
                                Timber.e("Recording error: ${event.cause}")
                                deviceState.lastError =
                                    "Recording error: ${event.cause?.message ?: event.error}"
                            } else {
                                deviceState.incrementRecordedVideos()
                                deviceState.setLastRecordedFile(displayPath)
                                Timber.i("Recording saved: $displayPath")
                            }
                        }

                        else -> {
                            Unit
                        }
                    }
                }
            recording = newRecording
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to start recording")
            deviceState.lastError = "Failed to start recording: ${e.message}"
            false
        }
    }

    /** Stop the current recording; the file is finalized asynchronously. */
    fun stopRecording() {
        recording?.stop()
        recording = null
    }

    /** Stop and release the camera. */
    fun stopCamera() {
        stopRecording()
        val provider = cameraProvider ?: return
        mainExecutor.execute {
            provider.unbindAll()
            camera = null
            Timber.d("Camera stopped")
        }
    }
}
