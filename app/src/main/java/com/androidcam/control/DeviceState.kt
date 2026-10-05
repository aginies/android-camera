package com.androidcam.control

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

    // --- Timestamp overlay --------------------------------------------------------

    // When true, timelapse frames get a date/time stamp burned in.
    private val _timestampEnabled = AtomicBoolean(false)
    var timestampEnabled: Boolean
        get() = _timestampEnabled.get()
        set(value) {
            _timestampEnabled.set(value)
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
