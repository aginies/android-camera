package com.androidcam.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.MediaMuxer
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Size
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.androidcam.AppApplication
import com.androidcam.R
import com.androidcam.camera.CameraManager
import com.androidcam.camera.FrameCapturer
import com.androidcam.camera.ResolutionDetector
import com.androidcam.control.DeviceState
import com.androidcam.discovery.MdnsDiscovery
import com.androidcam.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Foreground service that owns the full pipeline:
 * camera → frame analysis (MJPEG stream) → video recording (MP4).
 *
 * The service is started and bound to by [MainActivity] (which attaches the
 * local preview). Remote clients control it through [StreamServer]. The
 * pipeline keeps running while the activity is destroyed, so streaming and
 * recording survive app backgrounding.
 */
class RecordingService :
    Service(),
    StreamServer.ControlCallback {
    companion object {
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "recording_channel"

        // Settings persistence
        private const val PREFS_NAME = "androidcam_settings"
        private const val KEY_TOKEN = "token"
        private const val KEY_INTERVAL_ENABLED = "interval_enabled"
        private const val KEY_INTERVAL_SECONDS = "interval_seconds"
        private const val KEY_TIMESTAMP = "timestamp_enabled"
        private const val KEY_JPEG_QUALITY = "jpeg_quality"
        private const val DEFAULT_JPEG_QUALITY = 80
        private const val KEY_STORAGE_LOCATION = "storage_location"
        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_CUSTOM_TREE_URI = "custom_tree_uri"

        /** Intent extra to set the auth token at runtime. */
        const val EXTRA_SET_TOKEN = "set_token"

        /** Intent extras to configure interval recording at runtime. */
        const val EXTRA_INTERVAL_ENABLED = "interval_enabled"
        const val EXTRA_INTERVAL_SECONDS = "interval_seconds"

        // Interval-recording defaults
        private const val DEFAULT_INTERVAL_SECONDS = 60

        // Storage location values
        const val STORAGE_INTERNAL = "internal"
        const val STORAGE_EXTERNAL = "external"
        const val STORAGE_CUSTOM = "custom"
    }

    /** Binder so the activity can attach the preview view. */
    inner class LocalBinder : Binder() {
        val service: RecordingService get() = this@RecordingService
    }

    private val deviceState: DeviceState get() = AppApplication.deviceState

    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    // Background scope for assembling timelapse videos (blocking encode work).
    private val encodeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var binder: LocalBinder? = null
    private var cameraManager: CameraManager? = null
    private var previewView: PreviewView? = null
    private var frameCapturer: FrameCapturer? = null
    private var streamServer: StreamServer? = null
    private var mdnsDiscovery: MdnsDiscovery? = null

    private var token: String = ""
    private var currentRecordingFile: File? = null

    /** True while a timelapse-only recording is active (no full video). */
    private var timelapseOnlyRecording = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Camera lifecycle: stays STARTED for the service's lifetime so the camera
    // keeps running while the screen is off / the app is backgrounded.
    private val cameraLifecycle = ServiceLifecycleOwner()

    // Timelapse (interval capture) settings
    private var intervalEnabled = false
    private var intervalSeconds = DEFAULT_INTERVAL_SECONDS
    private var timestampEnabled = false
    private var jpegQuality = DEFAULT_JPEG_QUALITY

    // Active timelapse session (null when disabled): captures one JPEG from
    // the stream every [intervalSeconds].
    private var timelapseCapture: TimelapseCapture? = null

    // Where recordings are written: STORAGE_INTERNAL, STORAGE_EXTERNAL, or STORAGE_CUSTOM
    private var storageLocation = STORAGE_INTERNAL

    // SAF tree URI for a user-chosen recording directory (used when storageLocation == STORAGE_CUSTOM)
    private var customTreeUri: Uri? = null

    // Recording resolution, applied when the camera (re)starts.
    private var requestedQuality: Quality = Quality.FHD

    // Resolutions the active camera can actually record at (quality → exact
    // size), detected via CameraX capabilities. Highest first.
    private var supportedResolutions: List<Pair<Quality, Size>> = emptyList()

    override fun onCreate() {
        super.onCreate()
        loadSettings()
        createNotificationChannel()
        val notification = buildNotification(null)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // The server + mDNS are always up so the web UI / API are reachable, but
        // the camera stays OFF until streaming is explicitly started.
        cameraLifecycle.markStarted()
        startServerAndDiscovery()
        registerNetworkCallback()
        cleanupLeftoverFrames()
        refreshSupportedResolutions()
        updateNotification()
        Timber.i("Service created (token: $token, camera off)")
    }

    /** Load persisted settings; generate + persist a token if none is set. */
    private fun loadSettings() {
        val rawToken = prefs.getString(KEY_TOKEN, "")
        token =
            rawToken.orEmpty().ifEmpty {
                UUID
                    .randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(12)
                    .also { prefs.edit().putString(KEY_TOKEN, it).apply() }
            }
        intervalEnabled = prefs.getBoolean(KEY_INTERVAL_ENABLED, false)
        intervalSeconds = prefs.getInt(KEY_INTERVAL_SECONDS, DEFAULT_INTERVAL_SECONDS)
        timestampEnabled = prefs.getBoolean(KEY_TIMESTAMP, false)
        deviceState.timestampEnabled = timestampEnabled
        jpegQuality = prefs.getInt(KEY_JPEG_QUALITY, DEFAULT_JPEG_QUALITY)
        deviceState.jpegQuality = jpegQuality
        storageLocation = prefs.getString(KEY_STORAGE_LOCATION, STORAGE_INTERNAL) ?: STORAGE_INTERNAL
        customTreeUri = prefs.getString(KEY_CUSTOM_TREE_URI, null)?.let { Uri.parse(it) }
        requestedQuality =
            when (prefs.getString(KEY_RESOLUTION, "FHD")) {
                "HD" -> Quality.HD
                "SD" -> Quality.SD
                else -> Quality.FHD
            }
        Timber.d(
            "Settings loaded: token=${if (rawToken.isNullOrEmpty()) "generated" else "persisted"} interval=$intervalEnabled/${intervalSeconds}s storage=$storageLocation",
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        // Allow settings to be applied programmatically, e.g.
        //   am start -n com.androidcam/.ui.MainActivity --es set_token <token>
        //   am start -n com.androidcam/.ui.MainActivity --ez interval_enabled true \
        //       --ei interval_seconds 60
        if (intent != null) {
            val newToken = intent.getStringExtra(EXTRA_SET_TOKEN)
            if (!newToken.isNullOrBlank()) updateToken(newToken)
            if (intent.hasExtra(EXTRA_INTERVAL_ENABLED)) {
                updateIntervalSettings(
                    intent.getBooleanExtra(EXTRA_INTERVAL_ENABLED, false),
                    intent.getIntExtra(EXTRA_INTERVAL_SECONDS, intervalSeconds),
                )
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        binder = LocalBinder()
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Activity went away; keep streaming but drop the local preview.
        previewView = null
        cameraManager?.detachPreview()
        binder = null
        return false
    }

    /**
     * Attach the local preview view and (re)start the camera with it.
     * Called by the activity when it (re)binds to the service. The view is
     * remembered even while the camera is off, so it is attached as soon as
     * streaming starts.
     */
    fun attachPreview(previewView: PreviewView) {
        this.previewView = previewView
        cameraManager?.attachPreview(previewView)
    }

    /** Forward a tap-to-focus request to the camera (preview-view pixels). */
    fun setFocusPoint(
        x: Float,
        y: Float,
    ) {
        cameraManager?.setFocusPoint(x, y)
    }

    // --- Settings ---------------------------------------------------------------

    /** Current auth token (for display in the UI). */
    fun getToken(): String = token

    /** Current interval-recording settings (for display in the UI). */
    fun getIntervalSettings(): Pair<Boolean, Int> = intervalEnabled to intervalSeconds

    /**
     * Set the auth token. Persists it and restarts the stream server + mDNS so
     * the new token takes effect immediately.
     */
    fun updateToken(newToken: String) {
        val trimmed = newToken.trim()
        if (trimmed.isEmpty()) return
        if (trimmed == token) return
        token = trimmed
        prefs.edit().putString(KEY_TOKEN, trimmed).apply()
        stopServerAndDiscovery()
        startServerAndDiscovery()
        updateNotification()
        Timber.i("Token updated")
    }

    /**
     * Set the timelapse (interval capture) settings. Persists them and starts
     * or ends the capture session immediately: while enabled, one JPEG is
     * captured every [intervalSec] seconds; when disabled, the captured frames
     * are assembled into a timelapse video and deleted.
     */
    override fun updateIntervalSettings(
        enabled: Boolean,
        intervalSec: Int,
    ) {
        intervalEnabled = enabled
        intervalSeconds = intervalSec.coerceAtLeast(1)
        prefs
            .edit()
            .putBoolean(KEY_INTERVAL_ENABLED, intervalEnabled)
            .putInt(KEY_INTERVAL_SECONDS, intervalSeconds)
            .apply()
        if (enabled) {
            val capture = timelapseCapture
            if (capture != null) {
                // Running session keeps its frames; only the cadence changes.
                capture.intervalMs = intervalSeconds.toLong() * 1000L
            } else if (deviceState.recordingState == DeviceState.RecordingState.RECORDING) {
                startTimelapseSession()
            }
            // Otherwise the session starts with the next recording.
        } else {
            stopTimelapseSession()
        }
        Timber.i("Timelapse: enabled=$intervalEnabled every ${intervalSeconds}s")
    }

    /** Current interval (timelapse) settings for the control API. */
    override fun intervalSettings(): Pair<Boolean, Int> = intervalEnabled to intervalSeconds

    /** Whether timelapse frames get a date/time stamp burned in. */
    override fun timestampEnabled(): Boolean = timestampEnabled

    /** Enable/disable the timestamp overlay on timelapse frames. */
    override fun setTimestampEnabled(enabled: Boolean) {
        timestampEnabled = enabled
        deviceState.timestampEnabled = enabled
        prefs.edit().putBoolean(KEY_TIMESTAMP, enabled).apply()
        Timber.i("Timestamp overlay: ${if (enabled) "on" else "off"}")
    }

    /** JPEG compression quality for the stream (10-100). */
    override fun jpegQuality(): Int = jpegQuality

    override fun setJpegQuality(quality: Int) {
        jpegQuality = quality.coerceIn(10, 100)
        deviceState.jpegQuality = jpegQuality
        prefs.edit().putInt(KEY_JPEG_QUALITY, jpegQuality).apply()
        Timber.i("JPEG quality: $jpegQuality")
    }

    /** Start the stream server + mDNS advertising with the current [token]. */
    private fun startServerAndDiscovery() {
        streamServer = StreamServer(this, deviceState, token, this).also { it.start() }
        val ip = streamServer!!.getDeviceIp()
        if (ip != "127.0.0.1") {
            // Expose the public stream URL (with token) so the UI can display it.
            deviceState.setStreamUrl("http://$ip:${deviceState.streamPort}/?token=$token")
            try {
                val addr = InetAddress.getByName(ip)
                mdnsDiscovery =
                    MdnsDiscovery().also {
                        it.start("AndroidCam-${Build.MODEL}", deviceState.streamPort, addr, token)
                    }
            } catch (e: Exception) {
                Timber.w(e, "mDNS setup failed")
            }
        } else {
            Timber.w("No local network address found; skipping mDNS advertising")
        }
    }

    /** Stop the stream server + mDNS advertising. */
    private fun stopServerAndDiscovery() {
        mdnsDiscovery?.stop()
        mdnsDiscovery = null
        streamServer?.stop()
        streamServer = null
        deviceState.setStreamUrl(null)
    }

    // --- Timelapse (interval capture) -----------------------------------------

    /**
     * Timelapse (a.k.a. interval recording): while a recording is active and
     * the feature is enabled, one JPEG is captured from the stream every
     * [intervalSeconds] ([TimelapseCapture]). When the recording stops, the
     * frames are assembled into an MP4 timelapse ([TimelapseEncoder]) and
     * then deleted. Each recording gets its own video: frames left over from
     * a previous session are dropped when a new one starts.
     */
    private fun startTimelapseSession() {
        if (timelapseCapture != null) return
        // Start from a clean slate so this video only contains frames from
        // this session (leftovers from a previous one are dropped).
        framesDir().listFiles()?.forEach { it.delete() }
        timelapseCapture =
            TimelapseCapture(
                framesDir(),
                intervalSeconds.toLong() * 1000L,
                { deviceState.timestampEnabled },
            ) {
                deviceState.addTimelapseFrame()
            }.also {
                deviceState.setTimelapseFrames(it.frameCount())
                Timber.i("Timelapse session started (every ${intervalSeconds}s)")
            }
    }

    /** End the active session, assembling the captured frames into a video. */
    private fun stopTimelapseSession() {
        if (timelapseCapture == null) return
        timelapseCapture = null
        finishTimelapse()
    }

    /**
     * Assemble the captured frames into a timelapse video on a background
     * thread, then delete the frames. No-op when there are no frames.
     */
    private fun finishTimelapse() {
        val frames =
            framesDir()
                .listFiles { f -> f.name.endsWith(".jpg") }
                ?.sortedBy { it.name }
                ?: emptyList()
        if (frames.isEmpty()) {
            framesDir().delete()
            deviceState.setTimelapseFrames(0)
            return
        }
        deviceState.setTimelapseFrames(frames.size)
        encodeScope.launch {
            deviceState.setTimelapseEncoding(true, 0)
            val ok =
                try {
                    createTimelapseVideo(frames)
                } catch (e: Exception) {
                    Timber.e(e, "Timelapse encoding failed")
                    deviceState.lastError = "Timelapse encoding failed: ${e.message}"
                    false
                }
            if (ok) {
                frames.forEach { it.delete() }
                framesDir().delete()
                deviceState.setTimelapseFrames(0)
                deviceState.lastError = null
            } else {
                deviceState.lastError = deviceState.lastError ?: "Timelapse encoding failed"
            }
            deviceState.setTimelapseEncoding(false, 0)
        }
    }

    /** Drop frames left by a previous (possibly killed) session. */
    private fun cleanupLeftoverFrames() {
        val dir = framesDir()
        dir.listFiles()?.forEach { it.delete() }
        if (dir.exists()) dir.delete()
    }

    private fun framesDir(): File = File(filesDir, "timelapse_frames")

    /** Encode [frames] into an MP4 in the configured storage location. */
    private fun createTimelapseVideo(frames: List<File>): Boolean {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "timelapse_$timestamp.mp4"
        return if (storageLocation == STORAGE_CUSTOM) {
            val treeUri = customTreeUri ?: return false
            val dir = DocumentFile.fromTreeUri(this, treeUri) ?: return false
            val doc = dir.createFile("video/mp4", fileName) ?: return false
            val pfd = contentResolver.openFileDescriptor(doc.uri, "w") ?: return false
            val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val ok = encodeAndReport(frames, muxer)
                if (ok) {
                    deviceState.setLastRecordedFile(doc.uri.toString())
                    deviceState.incrementRecordedVideos()
                    Timber.i("Timelapse saved (custom): ${doc.uri}")
                } else {
                    doc.delete()
                }
                ok
            } finally {
                muxer.release()
                pfd.close()
            }
        } else {
            val outputDir = videosDir().apply { mkdirs() }
            val file = File(outputDir, fileName)
            val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val ok = encodeAndReport(frames, muxer)
                if (ok) {
                    deviceState.setLastRecordedFile(file.absolutePath)
                    deviceState.incrementRecordedVideos()
                    Timber.i("Timelapse saved: ${file.absolutePath}")
                } else {
                    file.delete()
                }
                ok
            } finally {
                muxer.release()
            }
        }
    }

    /** Encode [frames] into [muxer], reporting progress to [DeviceState]. */
    private fun encodeAndReport(
        frames: List<File>,
        muxer: MediaMuxer,
    ): Boolean =
        TimelapseEncoder.encode(frames, muxer) { done, total ->
            deviceState.setTimelapseEncoding(true, done * 100 / total)
        }

    // --- Streaming (camera on/off) --------------------------------------------

    /**
     * Turn the camera on and start producing MJPEG frames. Idempotent. The
     * camera is bound to [cameraLifecycle], which stays STARTED for the service
     * lifetime, so streaming survives the screen turning off.
     */
    override fun startStreaming() {
        if (deviceState.isStreaming) return
        val capturer =
            FrameCapturer(deviceState) { jpeg ->
                streamServer?.publishFrame(jpeg)
                timelapseCapture?.onFrame(jpeg)
            }
        frameCapturer = capturer
        cameraManager =
            CameraManager(
                context = this,
                lifecycleOwner = cameraLifecycle,
                deviceState = deviceState,
                frameCapturer = capturer,
            ).also {
                Timber.i("startStreaming: requestedQuality=$requestedQuality")
                // Apply the resolution chosen while streaming was off.
                it.setInitialQuality(requestedQuality)
                // Attach the local preview if the activity has bound one (it may
                // have bound before streaming started).
                previewView?.let { view -> it.attachPreview(view) }
                it.startCamera()
            }
        deviceState.startStreaming()
        updateNotification()
        Timber.i("Streaming started (camera on)")
    }

    /**
     * Turn the camera off (stopping any active recording). Idempotent. The HTTP
     * server + mDNS keep running so the app can be re-streamed / controlled.
     */
    override fun stopStreaming() {
        if (!deviceState.isStreaming) return
        stopRecording()
        // Safety net: stopRecording() already ended the timelapse session.
        stopTimelapseSession()
        cameraManager?.stopCamera()
        cameraManager = null
        frameCapturer = null
        deviceState.stopStreaming()
        updateNotification()
        Timber.i("Streaming stopped (camera off)")
    }

    // --- StreamServer.ControlCallback -----------------------------------------

    override fun startRecording(): Boolean {
        if (deviceState.recordingState == DeviceState.RecordingState.RECORDING) return true
        if (!deviceState.isStreaming) {
            deviceState.lastError = "Start streaming before recording"
            Timber.w("Cannot record: streaming is off")
            return false
        }
        // Timelapse mode: capture frames only; no full video is recorded.
        if (intervalEnabled) {
            startTimelapseSession()
            timelapseOnlyRecording = true
            deviceState.setRecordingState(DeviceState.RecordingState.RECORDING)
            AppApplication.instance.acquireWakeLock()
            updateNotification()
            Timber.i("Timelapse recording started (every ${intervalSeconds}s)")
            return true
        }
        timelapseOnlyRecording = false
        val camera =
            cameraManager
                ?: run {
                    deviceState.lastError = "Camera not ready"
                    return false
                }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "recording_$timestamp.mp4"
        return if (storageLocation == STORAGE_CUSTOM) {
            startRecordingToCustomDir(camera, fileName)
        } else {
            val outputDir = videosDir().apply { mkdirs() }
            val file = File(outputDir, fileName)
            if (camera.startRecording(FileOutputOptions.Builder(file).build(), file.absolutePath)) {
                currentRecordingFile = file
                deviceState.setRecordingState(DeviceState.RecordingState.RECORDING)
                AppApplication.instance.acquireWakeLock()
                updateNotification()
                Timber.i("Recording started: ${file.absolutePath}")
                true
            } else {
                false
            }
        }
    }

    /** Start recording into the user-chosen SAF directory. */
    private fun startRecordingToCustomDir(
        camera: CameraManager,
        fileName: String,
    ): Boolean {
        val treeUri =
            customTreeUri ?: run {
                deviceState.lastError = "No custom directory chosen"
                return false
            }
        return try {
            val dir =
                DocumentFile.fromTreeUri(this, treeUri) ?: run {
                    deviceState.lastError = "Invalid custom directory"
                    return false
                }
            val doc =
                dir.createFile("video/mp4", fileName) ?: run {
                    deviceState.lastError = "Could not create file in custom directory"
                    return false
                }
            val pfd =
                contentResolver.openFileDescriptor(doc.uri, "w") ?: run {
                    deviceState.lastError = "Could not open file descriptor"
                    return false
                }
            if (camera.startRecording(
                    FileDescriptorOutputOptions.Builder(pfd).build(),
                    doc.uri.toString(),
                )
            ) {
                deviceState.setRecordingState(DeviceState.RecordingState.RECORDING)
                AppApplication.instance.acquireWakeLock()
                updateNotification()
                Timber.i("Recording started (custom): ${doc.uri}")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start custom recording")
            deviceState.lastError = "Failed to start custom recording: ${e.message}"
            false
        }
    }

    override fun stopRecording() {
        if (deviceState.recordingState != DeviceState.RecordingState.RECORDING) return
        if (timelapseOnlyRecording) {
            // No full video to stop; just end the timelapse capture.
            deviceState.setRecordingState(DeviceState.RecordingState.IDLE)
            AppApplication.instance.releaseWakeLock()
            updateNotification()
            Timber.i("Timelapse recording stopped")
        } else {
            cameraManager?.stopRecording()
            deviceState.setRecordingState(DeviceState.RecordingState.IDLE)
            AppApplication.instance.releaseWakeLock()
            updateNotification()
            Timber.i("Recording stopped: ${currentRecordingFile?.absolutePath}")
            currentRecordingFile = null
        }
        timelapseOnlyRecording = false
        // Assemble the timelapse frames captured during this recording.
        stopTimelapseSession()
    }

    override fun switchCamera() {
        cameraManager?.switchCamera()
        refreshSupportedResolutions()
    }

    override fun setCameraFacing(facing: String) {
        val target =
            when (facing.lowercase()) {
                "front" -> DeviceState.CameraFacing.FRONT
                else -> DeviceState.CameraFacing.BACK
            }
        cameraManager?.setCameraFacing(target)
        refreshSupportedResolutions()
    }

    override fun toggleTorch(): Boolean = cameraManager?.toggleTorch() ?: false

    override fun setResolution(resolution: String) {
        val parts = resolution.lowercase().split("x")
        val w = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val h = parts.getOrNull(1)?.toIntOrNull() ?: 0
        // Prefer an exact match against the detected sizes; fall back to the
        // nearest quality tier for unknown sizes.
        val quality =
            supportedResolutions.firstOrNull { it.second.width == w && it.second.height == h }?.first
                ?: nearestQuality(w, h)
        if (w > 0) deviceState.videoWidth = w
        if (h > 0) deviceState.videoHeight = h
        requestedQuality = quality
        val qualityName =
            when (quality) {
                Quality.HD -> "HD"
                Quality.SD -> "SD"
                Quality.UHD -> "UHD"
                else -> "FHD"
            }
        prefs.edit().putString(KEY_RESOLUTION, qualityName).apply()
        cameraManager?.setVideoQuality(quality)
    }

    /** Supported resolutions as "WxH" strings, highest first. */
    override fun supportedResolutions(): List<String> = supportedResolutions.map { "${it.second.width}x${it.second.height}" }

    /** Re-detect the supported resolutions for the current camera facing. */
    private fun refreshSupportedResolutions() {
        ResolutionDetector.detect(this, deviceState.facing) { pairs ->
            supportedResolutions = pairs
            Timber.d("Supported resolutions: ${pairs.map { it.second }}")
        }
    }

    /** Map a requested size to the nearest CameraX quality tier. */
    private fun nearestQuality(
        w: Int,
        h: Int,
    ): Quality {
        if (w <= 0 || h <= 0) return requestedQuality
        val canonical =
            listOf(
                Quality.SD to Size(640, 480),
                Quality.HD to Size(1280, 720),
                Quality.FHD to Size(1920, 1080),
                Quality.UHD to Size(3840, 2160),
            )
        return canonical
            .minByOrNull { (quality, size) ->
                val dx = size.width - w
                val dy = size.height - h
                dx * dx + dy * dy
            }!!
            .first
    }

    // --- Storage location -----------------------------------------------------

    /**
     * Set where recordings are stored. [location] is [STORAGE_INTERNAL] (app
     * files dir) or [STORAGE_EXTERNAL] (app-specific external dir). Persists the
     * choice. Returns false for an unknown location.
     */
    override fun setStorageLocation(location: String): Boolean {
        val normalized = location.lowercase()
        if (normalized != STORAGE_INTERNAL && normalized != STORAGE_EXTERNAL && normalized != STORAGE_CUSTOM) {
            Timber.w("Unknown storage location: $location")
            return false
        }
        // Custom requires a chosen directory; otherwise fall back to internal.
        if (normalized == STORAGE_CUSTOM && customTreeUri == null) {
            Timber.w("Custom storage requested but no directory chosen")
            return false
        }
        storageLocation = normalized
        prefs.edit().putString(KEY_STORAGE_LOCATION, normalized).apply()
        Timber.i("Storage location: $normalized")
        return true
    }

    /**
     * Set the SAF tree URI for a user-chosen recording directory and switch to
     * it. [uri] is the result of an ACTION_OPEN_DOCUMENT_TREE pick.
     */
    fun setCustomTreeUri(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: Exception) {
            Timber.w(e, "Could not take persistable URI permission")
        }
        customTreeUri = uri
        storageLocation = STORAGE_CUSTOM
        prefs
            .edit()
            .putString(KEY_CUSTOM_TREE_URI, uri.toString())
            .putString(KEY_STORAGE_LOCATION, STORAGE_CUSTOM)
            .apply()
        Timber.i("Custom recording directory set: $uri")
    }

    override fun storageLocationName(): String = storageLocation

    /** The directory recordings are written to, based on [storageLocation]. */
    private fun videosDir(): File =
        if (storageLocation == STORAGE_EXTERNAL) {
            File(getExternalFilesDir(null) ?: filesDir, "videos")
        } else {
            File(filesDir, "videos")
        }

    override fun onDestroy() {
        stopStreaming()
        unregisterNetworkCallback()
        stopServerAndDiscovery()
        cameraLifecycle.markDestroyed()
        encodeScope.cancel()
        super.onDestroy()
    }

    // --- Network changes -------------------------------------------------------

    /**
     * The device's LAN address can change (Wi-Fi reconnect, DHCP renewal,
     * switching networks). Re-resolve it and refresh the displayed stream URL
     * so the UI never shows a stale address.
     */
    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    deviceState.setStreamUrl(null)
                }

                override fun onAvailable(network: Network) {
                    refreshStreamUrl()
                }
            }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            Timber.w(e, "Failed to register network callback")
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback
            ?.let {
                try {
                    (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                        .unregisterNetworkCallback(it)
                } catch (e: Exception) {
                    Timber.w(e, "Failed to unregister network callback")
                }
            }
        networkCallback = null
    }

    private fun refreshStreamUrl() {
        val server = streamServer ?: return
        val ip = server.getDeviceIp()
        if (ip != "127.0.0.1") {
            deviceState.setStreamUrl("http://$ip:${deviceState.streamPort}/?token=$token")
            Timber.i("Stream URL updated: http://$ip:${deviceState.streamPort}")
        }
    }

    // --- Notification -----------------------------------------------------------

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.channel_description) }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(subText: String?): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val builder =
            NotificationCompat
                .Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(getString(R.string.service_title))
                .setContentText(getString(R.string.service_running))
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
        subText?.let { builder.setSubText(it) }
        return builder.build()
    }

    private fun updateNotification() {
        val server = streamServer ?: return
        val ip = server.getDeviceIp()
        val url = "http://$ip:${deviceState.streamPort}/?token=$token"
        val recording = deviceState.recordingState == DeviceState.RecordingState.RECORDING
        val sub =
            when {
                recording && timelapseOnlyRecording -> "Timelapse • $url"
                recording -> "Recording • $url"
                deviceState.isStreaming -> "Streaming • $url"
                else -> "Camera off • $url"
            }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(sub))
    }
}
