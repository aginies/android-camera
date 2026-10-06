package com.androidcam.control

import com.androidcam.prusa.PrusaState
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Central device state — shared between camera, stream server, and remote control.
 *
 * Thread-safe: all mutable fields are backed by atomic types. State is *written*
 * only through the setter methods below (so listeners can be notified of changes)
 * and *observed* (read) by the pipeline and UI.
 */
class DeviceState {
    enum class RecordingState { IDLE, RECORDING }

    enum class CameraFacing { BACK, FRONT }

    // --- Recording -----------------------------------------------------------

    private val _recordingState = AtomicReference(RecordingState.IDLE)
    val recordingState: RecordingState get() = _recordingState.get()

    private val recordingListeners = CopyOnWriteArrayList<(RecordingState) -> Unit>()

    /** Set the recording state and notify listeners (skipped if unchanged). */
    fun setRecordingState(state: RecordingState) {
        if (_recordingState.getAndSet(state) == state) return
        recordingListeners.forEach { it(state) }
    }

    fun addRecordingListener(listener: (RecordingState) -> Unit) {
        recordingListeners.add(listener)
    }

    fun removeRecordingListener(listener: (RecordingState) -> Unit) {
        recordingListeners.remove(listener)
    }

    // True while the active recording is a timelapse (frames only, no full
    // video), so the UI can show "Timelapse" instead of "Recording".
    private val _timelapseRecording = AtomicBoolean(false)
    val timelapseRecording: Boolean get() = _timelapseRecording.get()

    /**
     * Set whether the active recording is a timelapse. No notification: it is
     * always set together with a recording-state change, which drives the UI
     * refresh.
     */
    fun setTimelapseRecording(value: Boolean) {
        _timelapseRecording.set(value)
    }

    private val _recordedVideosCount = AtomicInteger(0)
    val recordedVideosCount: Int get() = _recordedVideosCount.get()

    /** Increment the number of completed recordings (call when a file is finalized). */
    fun incrementRecordedVideos() {
        _recordedVideosCount.incrementAndGet()
    }

    // Path of the most recently finalized recording (absolute). Null until the
    // first recording completes. Used to tell the user where the file was saved.
    private val _lastRecordedFile = AtomicReference<String?>(null)
    val lastRecordedFile: String? get() = _lastRecordedFile.get()

    private val lastRecordedFileListeners = CopyOnWriteArrayList<(String?) -> Unit>()

    /** Set the path of the last finalized recording and notify listeners. */
    fun setLastRecordedFile(path: String?) {
        _lastRecordedFile.set(path)
        lastRecordedFileListeners.forEach { it(path) }
    }

    fun addLastRecordedFileListener(listener: (String?) -> Unit) {
        lastRecordedFileListeners.add(listener)
    }

    fun removeLastRecordedFileListener(listener: (String?) -> Unit) {
        lastRecordedFileListeners.remove(listener)
    }

    // --- Camera --------------------------------------------------------------

    private val _facing = AtomicReference(CameraFacing.BACK)
    var facing: CameraFacing
        get() = _facing.get()
        set(value) {
            _facing.set(value)
        }

    /** Toggle between front and back camera. */
    fun switchCamera() {
        _facing.set(
            when (_facing.get()) {
                CameraFacing.BACK -> CameraFacing.FRONT
                CameraFacing.FRONT -> CameraFacing.BACK
            },
        )
    }

    private val _rotationDegrees = AtomicInteger(0) // 0, 90, 180, 270
    var rotationDegrees: Int
        get() = _rotationDegrees.get()
        set(value) {
            _rotationDegrees.set(value)
        }

    // --- Video ---------------------------------------------------------------

    private val _videoWidth = AtomicInteger(1920)
    var videoWidth: Int
        get() = _videoWidth.get()
        set(value) {
            _videoWidth.set(value)
        }

    private val _videoHeight = AtomicInteger(1080)
    var videoHeight: Int
        get() = _videoHeight.get()
        set(value) {
            _videoHeight.set(value)
        }

    // --- Stream --------------------------------------------------------------

    private val _streamPort = AtomicInteger(8080)
    var streamPort: Int
        get() = _streamPort.get()
        set(value) {
            _streamPort.set(value)
        }

    private val _rtspEnabled = AtomicBoolean(false)
    val rtspEnabled: Boolean get() = _rtspEnabled.get()

    /** Enable/disable the RTSP stream. */
    fun setRtspEnabled(enabled: Boolean) {
        if (_rtspEnabled.getAndSet(enabled) == enabled) return
        Timber.i("RTSP stream: ${if (enabled) "enabled" else "disabled"}")
    }

    private val _isStreaming = AtomicBoolean(false)
    val isStreaming: Boolean get() = _isStreaming.get()

    private val streamingListeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    /**
     * Set whether the camera is live (MJPEG streaming active) and notify
     * listeners (skipped if unchanged). "Streaming" here means the camera is
     * open and producing frames — independent of whether the HTTP server is up.
     */
    fun startStreaming() {
        if (_isStreaming.getAndSet(true)) return
        streamingListeners.forEach { it(true) }
    }

    fun stopStreaming() {
        if (!_isStreaming.getAndSet(false)) return
        streamingListeners.forEach { it(false) }
    }

    fun addStreamingListener(listener: (Boolean) -> Unit) {
        streamingListeners.add(listener)
    }

    fun removeStreamingListener(listener: (Boolean) -> Unit) {
        streamingListeners.remove(listener)
    }

    // Public stream URL (http://ip:port/?token=...), known only once the
    // stream server has started and resolved the device's LAN address.
    // Null until then.
    private val _streamUrl = AtomicReference<String?>(null)
    val streamUrl: String? get() = _streamUrl.get()

    private val streamUrlListeners = CopyOnWriteArrayList<(String?) -> Unit>()

    /** Set the public stream URL and notify listeners (skipped if unchanged). */
    fun setStreamUrl(url: String?) {
        if (_streamUrl.getAndSet(url) == url) return
        streamUrlListeners.forEach { it(url) }
    }

    fun addStreamUrlListener(listener: (String?) -> Unit) {
        streamUrlListeners.add(listener)
    }

    fun removeStreamUrlListener(listener: (String?) -> Unit) {
        streamUrlListeners.remove(listener)
    }

    // --- Status ----------------------------------------------------------------

    private val _lastError = AtomicReference<String?>(null)
    var lastError: String?
        get() = _lastError.get()
        set(value) {
            _lastError.set(value)
        }

    // --- Timelapse (interval capture) -----------------------------------------

    // Mirrors the service's interval (timelapse) settings so the UI can react
    // to changes made from anywhere (app, web UI, API) without a service
    // reference.
    private val _intervalEnabled = AtomicBoolean(false)
    val intervalEnabled: Boolean get() = _intervalEnabled.get()

    private val _intervalSeconds = AtomicInteger(60)
    val intervalSeconds: Int get() = _intervalSeconds.get()

    private val intervalSettingsListeners = CopyOnWriteArrayList<(Boolean, Int) -> Unit>()

    /** Update the interval (timelapse) settings and notify listeners. */
    fun setIntervalSettings(
        enabled: Boolean,
        seconds: Int,
    ) {
        _intervalEnabled.set(enabled)
        _intervalSeconds.set(seconds)
        intervalSettingsListeners.forEach { it(enabled, seconds) }
    }

    fun addIntervalSettingsListener(listener: (Boolean, Int) -> Unit) {
        intervalSettingsListeners.add(listener)
    }

    fun removeIntervalSettingsListener(listener: (Boolean, Int) -> Unit) {
        intervalSettingsListeners.remove(listener)
    }

    // Number of JPEG frames captured for the current timelapse session.
    private val _timelapseFrames = AtomicInteger(0)
    val timelapseFrames: Int get() = _timelapseFrames.get()

    // True while the captured frames are being assembled into a video.
    private val _timelapseEncoding = AtomicBoolean(false)
    val timelapseEncoding: Boolean get() = _timelapseEncoding.get()

    // Video-creation progress, 0-100 (meaningful while [timelapseEncoding]).
    private val _timelapseProgress = AtomicInteger(0)
    val timelapseProgress: Int get() = _timelapseProgress.get()

    private val timelapseListeners = CopyOnWriteArrayList<() -> Unit>()

    /** Update the captured-frame count and notify listeners. */
    fun setTimelapseFrames(count: Int) {
        _timelapseFrames.set(count)
        timelapseListeners.forEach { it() }
    }

    /** Record one more captured frame and notify listeners. */
    fun addTimelapseFrame() {
        _timelapseFrames.incrementAndGet()
        timelapseListeners.forEach { it() }
    }

    /** Set the encoding state + progress and notify listeners. */
    fun setTimelapseEncoding(
        encoding: Boolean,
        progress: Int,
    ) {
        _timelapseEncoding.set(encoding)
        _timelapseProgress.set(progress)
        timelapseListeners.forEach { it() }
    }

    fun addTimelapseListener(listener: () -> Unit) {
        timelapseListeners.add(listener)
    }

    fun removeTimelapseListener(listener: () -> Unit) {
        timelapseListeners.remove(listener)
    }

    // --- JPEG quality -------------------------------------------------------

    // JPEG compression quality (10-100) for the MJPEG stream and the
    // timelapse frames captured from it.
    private val _jpegQuality = AtomicInteger(80)
    var jpegQuality: Int
        get() = _jpegQuality.get()
        set(value) {
            _jpegQuality.set(value.coerceIn(10, 100))
        }

    // --- Timestamp overlay --------------------------------------------------------

    // When true, timelapse frames get a date/time stamp burned in.
    private val _timestampEnabled = AtomicBoolean(false)
    var timestampEnabled: Boolean
        get() = _timestampEnabled.get()
        set(value) {
            _timestampEnabled.set(value)
        }

    // --- Prusa Connect ---------------------------------------------------------

    // Live status of the Prusa Connect link (published by the uploader loop).
    private val _prusaState = AtomicReference(PrusaState())
    val prusaState: PrusaState get() = _prusaState.get()

    private val prusaStateListeners = CopyOnWriteArrayList<(PrusaState) -> Unit>()

    /** Set the Prusa Connect status and notify listeners (skipped if unchanged). */
    fun setPrusaState(state: PrusaState) {
        if (_prusaState.getAndSet(state) == state) return
        prusaStateListeners.forEach { it(state) }
    }

    fun addPrusaStateListener(listener: (PrusaState) -> Unit) {
        prusaStateListeners.add(listener)
    }

    fun removePrusaStateListener(listener: (PrusaState) -> Unit) {
        prusaStateListeners.remove(listener)
    }

    // --- Screen timeout ---------------------------------------------------------

    private val _screenTimeoutEnabled = AtomicBoolean(true)
    var screenTimeoutEnabled: Boolean
        get() = _screenTimeoutEnabled.get()
        set(value) {
            _screenTimeoutEnabled.set(value)
        }

    private val _screenTimeoutSeconds = AtomicInteger(30)
    var screenTimeoutSeconds: Int
        get() = _screenTimeoutSeconds.get()
        set(value) {
            _screenTimeoutSeconds.set(value)
        }
}
