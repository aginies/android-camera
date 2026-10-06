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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.androidcam.BuildConfig
import com.androidcam.R
import com.androidcam.camera.CameraManager
import com.androidcam.camera.FrameCapturer
import com.androidcam.camera.ResolutionDetector
import com.androidcam.control.DeviceState
import com.androidcam.discovery.MdnsDiscovery
import com.androidcam.prusa.PrusaCameraInfo
import com.androidcam.prusa.PrusaConnectClient
import com.androidcam.prusa.PrusaConnectSettings
import com.androidcam.prusa.PrusaUploader
import com.androidcam.prusa.QrTokenParser
import com.androidcam.ui.MainActivity
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

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
        private const val KEY_RTSP_ENABLED = "rtsp_enabled"

        // Prusa Connect settings persistence
        private const val KEY_PRUSA_ENABLED = "prusa_enabled"
        private const val KEY_PRUSA_TOKEN = "prusa_token"
        private const val KEY_PRUSA_FINGERPRINT = "prusa_fingerprint"
        private const val KEY_PRUSA_NAME = "prusa_name"
        private const val KEY_PRUSA_INTERVAL = "prusa_interval"

        // Prusa QR scan
        private const val QR_SCAN_TIMEOUT_MS = 30_000L
        private const val QR_SCAN_PERIOD_MS = 250L

        /**
         * Longest side (px) of the bitmap handed to the QR scanner. Full
         * frames are downscaled: QR codes decode fine from much smaller
         * images, and the smaller bitmap makes ML Kit faster and cheaper.
         */
        private const val QR_SCAN_MAX_DIM = 1024

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

        // Prefix of the per-session timelapse frames directories.
        private const val SESSION_DIR_PREFIX = "timelapse_frames_"
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
    private var rtspServer: RtspServer? = null
    private var h264Encoder: H264StreamEncoder? = null
    private var mdnsDiscovery: MdnsDiscovery? = null

    // Prusa Connect: settings + upload loop (see prusa/ package).
    private var prusaSettings = PrusaConnectSettings()
    private var prusaClient: PrusaConnectClient? = null
    private var prusaUploader: PrusaUploader? = null

    // Whether this service currently holds a wake-lock reference for Prusa
    // uploads (the wake lock itself is reference-counted in AppApplication).
    @Volatile
    private var prusaWakeLockHeld = false

    // Prusa QR scan: one active scan job + a shared ML Kit scanner.
    private var qrScanJob: Job? = null
    private var qrScanner: BarcodeScanner? = null

    private var token: String = ""
    private var currentRecordingFile: File? = null

    /** True while a timelapse-only recording is active (no full video). */
    @Volatile
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
    private var rtspEnabled = false

    // Active timelapse session (null when disabled): captures one JPEG from
    // the stream every [intervalSeconds]. @Volatile: written on the main
    // thread, read on the frame-capturer executor thread.
    @Volatile
    private var timelapseCapture: TimelapseCapture? = null

    // Frames directory of the active session. Each session gets its own
    // directory, so a background encode of a finished session can never read
    // or delete frames belonging to a newer one.
    private var activeSessionDir: File? = null

    // Bumped on every new session; lets a finishing encode tell whether it is
    // still the current session before resetting shared state (frame count).
    private val timelapseSessionGeneration = AtomicInteger(0)

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
        startPrusaUploader()
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
        deviceState.setIntervalSettings(intervalEnabled, intervalSeconds)
        timestampEnabled = prefs.getBoolean(KEY_TIMESTAMP, false)
        deviceState.timestampEnabled = timestampEnabled
        jpegQuality = prefs.getInt(KEY_JPEG_QUALITY, DEFAULT_JPEG_QUALITY)
        deviceState.jpegQuality = jpegQuality
        rtspEnabled = prefs.getBoolean(KEY_RTSP_ENABLED, false)
        deviceState.setRtspEnabled(rtspEnabled)
        storageLocation = prefs.getString(KEY_STORAGE_LOCATION, STORAGE_INTERNAL) ?: STORAGE_INTERNAL
        customTreeUri = prefs.getString(KEY_CUSTOM_TREE_URI, null)?.let { Uri.parse(it) }
        requestedQuality =
            when (prefs.getString(KEY_RESOLUTION, "FHD")) {
                "HD" -> Quality.HD
                "SD" -> Quality.SD
                else -> Quality.FHD
            }
        // Prusa Connect: load settings; generate a stable fingerprint once.
        val prusaFingerprint = prefs.getString(KEY_PRUSA_FINGERPRINT, "").orEmpty()
        prusaSettings =
            PrusaConnectSettings(
                enabled = prefs.getBoolean(KEY_PRUSA_ENABLED, false),
                token = prefs.getString(KEY_PRUSA_TOKEN, "").orEmpty(),
                fingerprint =
                    prusaFingerprint.ifEmpty {
                        UUID
                            .randomUUID()
                            .toString()
                            .replace("-", "")
                            .also { prefs.edit().putString(KEY_PRUSA_FINGERPRINT, it).apply() }
                    },
                cameraName =
                    prefs
                        .getString(KEY_PRUSA_NAME, PrusaConnectSettings.DEFAULT_CAMERA_NAME)
                        .orEmpty()
                        .ifEmpty { PrusaConnectSettings.DEFAULT_CAMERA_NAME },
                intervalSeconds =
                    prefs.getInt(KEY_PRUSA_INTERVAL, PrusaConnectSettings.DEFAULT_INTERVAL_SECONDS),
            )
        Timber.d(
            "Settings loaded: token=${if (rawToken.isNullOrEmpty()) "generated" else "persisted"} interval=$intervalEnabled/${intervalSeconds}s storage=$storageLocation prusa=${prusaSettings.enabled} rtsp=$rtspEnabled",
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
     *
     * Toggling while a recording is active switches modes cleanly (timelapse
     * replaces video and vice versa), so the app never ends up recording
     * neither — or both.
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
        deviceState.setIntervalSettings(intervalEnabled, intervalSeconds)

        val recording = deviceState.recordingState == DeviceState.RecordingState.RECORDING
        when {
            // A full video is recording and timelapse is being enabled: the
            // timelapse mode replaces the video, so end it cleanly first.
            enabled && recording && !timelapseOnlyRecording -> {
                stopRecording()
                startTimelapseSession()
                timelapseOnlyRecording = true
                deviceState.setTimelapseRecording(true)
                deviceState.setRecordingState(DeviceState.RecordingState.RECORDING)
                AppApplication.instance.acquireWakeLock()
                updateNotification()
                Timber.i("Timelapse recording started (every ${intervalSeconds}s, replaced video)")
            }

            // A timelapse is recording and the feature is being disabled: end
            // the recording cleanly (assembling the captured frames).
            !enabled && recording && timelapseOnlyRecording -> {
                stopRecording()
            }

            enabled -> {
                val capture = timelapseCapture
                if (capture != null) {
                    // Running session keeps its frames; only the cadence changes.
                    capture.intervalMs = intervalSeconds.toLong() * 1000L
                }
                // Otherwise the session starts with the next recording.
            }

            else -> {
                stopTimelapseSession()
            }
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

    // --- RTSP server ----------------------------------------------------------

    /** Whether RTSP streaming is enabled. */
    override fun rtspEnabled(): Boolean = rtspEnabled

    /** RTSP port (0 if not running). */
    override fun rtspPort(): Int = rtspServer?.controlPort ?: 0

    /** Enable/disable the RTSP stream. Persists the setting. */
    override fun setRtspEnabled(enabled: Boolean) {
        rtspEnabled = enabled
        deviceState.setRtspEnabled(enabled)
        prefs.edit().putBoolean(KEY_RTSP_ENABLED, enabled).apply()
        if (enabled) {
            val ip = streamServer?.getDeviceIp()
            if (ip != null && ip != "127.0.0.1") {
                try {
                    startRtspServer(InetAddress.getByName(ip))
                } catch (e: Exception) {
                    Timber.w(e, "Failed to start RTSP server")
                }
            }
            ensureH264Encoder()
        } else {
            stopRtspServer()
            releaseH264Encoder()
        }
        Timber.i("RTSP stream: ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Create + start the H.264 encoder if RTSP is enabled and the camera is
     * streaming. The encoder configures its codec on the first frame (the
     * delivered resolution is only known at runtime).
     */
    private fun ensureH264Encoder() {
        if (h264Encoder != null) return
        if (!deviceState.isStreaming) return
        val encoder = H264StreamEncoder()
        encoder.start()
        h264Encoder = encoder
    }

    private fun releaseH264Encoder() {
        h264Encoder?.release()
        h264Encoder = null
    }

    private fun startRtspServer(ip: InetAddress) {
        if (rtspServer != null) return
        val ipv4 =
            (ip as? Inet4Address) ?: run {
                Timber.w("RTSP server requires an IPv4 address; $ip is unsupported")
                return
            }
        rtspServer =
            RtspServer(
                token,
                { h264Encoder },
                // Re-resolve the IP per SDP so a network change (DHCP, Wi-Fi
                // switch) doesn't leave the SDP advertising a stale address.
                { streamServer?.getDeviceIp()?.takeIf { it != "127.0.0.1" } },
            )
        val ok = rtspServer!!.start(ipv4)
        if (ok) {
            Timber.i("RTSP server started on rtsp://$ip:${rtspServer!!.controlPort}")
        } else {
            Timber.w("RTSP server failed to start (ports busy)")
        }
    }

    private fun stopRtspServer() {
        rtspServer?.stop()
        rtspServer = null
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
            val addr = InetAddress.getByName(ip)
            try {
                mdnsDiscovery =
                    MdnsDiscovery().also {
                        it.start("AndroidCam-${Build.MODEL}", deviceState.streamPort, addr, token)
                    }
            } catch (e: Exception) {
                Timber.w(e, "mDNS setup failed")
            }
            // Start RTSP server if enabled.
            if (rtspEnabled) {
                startRtspServer(addr)
            }
        } else {
            Timber.w("No local network address found; skipping mDNS advertising")
        }
    }

    /** Stop the stream server + mDNS advertising. */
    private fun stopServerAndDiscovery() {
        stopRtspServer()
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
        // Each session gets its own frames directory, so a background encode
        // of a finished session can never read or delete frames belonging to
        // a new one.
        val dir = newSessionDir()
        activeSessionDir = dir
        timelapseSessionGeneration.incrementAndGet()
        timelapseCapture =
            TimelapseCapture(
                dir,
                intervalSeconds.toLong() * 1000L,
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
        val dir = activeSessionDir
        activeSessionDir = null
        if (dir != null) finishTimelapse(dir, timelapseSessionGeneration.get())
    }

    /**
     * Assemble the frames in [dir] into a timelapse video on a background
     * thread, then delete them. No-op when there are no frames.
     *
     * [generation] identifies the session; the completion block only resets
     * the shared frame count (and clears errors) when this session is still
     * the current one, so a slow encode never clobbers a newer session.
     */
    private fun finishTimelapse(
        dir: File,
        generation: Int,
    ) {
        val frames =
            dir
                .listFiles { f -> f.name.endsWith(".jpg") }
                ?.sortedBy { it.name }
                ?: emptyList()
        if (frames.isEmpty()) {
            dir.delete()
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
                dir.delete()
                if (timelapseSessionGeneration.get() == generation) {
                    deviceState.setTimelapseFrames(0)
                    deviceState.lastError = null
                }
            } else {
                deviceState.lastError = deviceState.lastError ?: "Timelapse encoding failed"
            }
            deviceState.setTimelapseEncoding(false, 0)
        }
    }

    /** Drop frames left by previous (possibly killed) sessions. */
    private fun cleanupLeftoverFrames() {
        filesDir
            .listFiles { f -> f.isDirectory && f.name.startsWith(SESSION_DIR_PREFIX) }
            ?.forEach { dir ->
                dir.listFiles()?.forEach { it.delete() }
                dir.delete()
            }
    }

    /** Create a fresh frames directory for a new timelapse session. */
    private fun newSessionDir(): File = File(filesDir, "$SESSION_DIR_PREFIX${System.currentTimeMillis()}")

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
            FrameCapturer(
                deviceState,
                onFrame = { jpeg ->
                    streamServer?.publishFrame(jpeg)
                    timelapseCapture?.onFrame(jpeg)
                },
                // Raw NV21 feed for the H.264 RTSP encoder (rotation is applied
                // inside the encoder to match the MJPEG stream).
                onYuvFrame = { nv21, w, h, rotation ->
                    h264Encoder?.inputFrame(nv21, w, h, rotation)
                },
            )
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
        if (rtspEnabled) ensureH264Encoder()
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
        // Release the capturer's executor thread (non-daemon; it would leak
        // otherwise). Late frame deliveries are dropped safely by the
        // capturer's executor wrapper.
        frameCapturer?.shutdown()
        frameCapturer = null
        releaseH264Encoder()
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
            deviceState.setTimelapseRecording(true)
            deviceState.setRecordingState(DeviceState.RecordingState.RECORDING)
            AppApplication.instance.acquireWakeLock()
            updateNotification()
            Timber.i("Timelapse recording started (every ${intervalSeconds}s)")
            return true
        }
        timelapseOnlyRecording = false
        deviceState.setTimelapseRecording(false)
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
        deviceState.setTimelapseRecording(false)
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

    // --- Prusa Connect ----------------------------------------------------------

    /**
     * Create the Prusa Connect client + uploader (once) and start the upload
     * loop if the feature is enabled. The loop stays dormant while disabled,
     * so a settings change from any entry point takes effect without a
     * service restart.
     */
    private fun startPrusaUploader() {
        if (prusaUploader == null) {
            prusaClient = PrusaConnectClient { prusaSettings }
            prusaUploader =
                PrusaUploader(
                    client = prusaClient!!,
                    settingsProvider = { prusaSettings },
                    latestFrameProvider = { streamServer?.getLatestFrame() },
                    cameraInfoProvider = { cameraInfo() },
                    onState = { deviceState.setPrusaState(it) },
                    onFatal = {
                        prusaReleaseWakeLock()
                        updateNotification()
                    },
                )
        }
        if (prusaSettings.enabled) {
            prusaAcquireWakeLock()
            prusaUploader?.start()
        }
        updateNotification()
    }

    /** Camera description sent to `PUT /c/info`. */
    private fun cameraInfo(): PrusaCameraInfo {
        val ip = streamServer?.getDeviceIp().orEmpty()
        return PrusaCameraInfo(
            name = prusaSettings.cameraName,
            firmware = BuildConfig.VERSION_NAME,
            manufacturer = "Android",
            model = Build.MODEL,
            width = deviceState.videoWidth,
            height = deviceState.videoHeight,
            wifiIpv4 = ip,
        )
    }

    private fun prusaAcquireWakeLock() {
        if (!prusaWakeLockHeld) {
            AppApplication.instance.acquireWakeLock()
            prusaWakeLockHeld = true
        }
    }

    private fun prusaReleaseWakeLock() {
        if (prusaWakeLockHeld) {
            AppApplication.instance.releaseWakeLock()
            prusaWakeLockHeld = false
        }
    }

    /** Current Prusa Connect settings (for the UI). */
    fun getPrusaSettings(): PrusaConnectSettings = prusaSettings

    /** Current Prusa Connect settings (control API). */
    override fun prusaSettings(): PrusaConnectSettings = prusaSettings

    /** Enable/disable Prusa Connect uploads. */
    override fun setPrusaEnabled(enabled: Boolean) {
        prusaSettings = prusaSettings.copy(enabled = enabled)
        prefs.edit().putBoolean(KEY_PRUSA_ENABLED, enabled).apply()
        if (enabled) {
            prusaAcquireWakeLock()
            prusaUploader?.start()
        } else {
            prusaUploader?.stop()
            prusaReleaseWakeLock()
        }
        deviceState.setPrusaState(deviceState.prusaState.copy(enabled = enabled))
        updateNotification()
        Timber.i("Prusa Connect: ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Set the Prusa Connect token (20 chars, from the Prusa Connect app/web).
     * @return false if the token is not exactly [PrusaConnectSettings.TOKEN_LENGTH] chars.
     */
    override fun setPrusaToken(token: String): Boolean {
        val trimmed = token.trim()
        if (!PrusaConnectSettings.isValidToken(trimmed)) return false
        prusaSettings = prusaSettings.copy(token = trimmed)
        prefs.edit().putString(KEY_PRUSA_TOKEN, trimmed).apply()
        prusaUploader?.markInfoStale()
        // Resumes the loop if it had stopped on an invalid token.
        if (prusaSettings.enabled) prusaUploader?.start()
        Timber.i("Prusa Connect token updated")
        return true
    }

    /** Set the camera name shown in Prusa Connect (max 64 chars). */
    override fun setPrusaName(name: String) {
        val trimmed =
            name
                .trim()
                .take(PrusaConnectSettings.NAME_MAX_LENGTH)
                .ifEmpty { PrusaConnectSettings.DEFAULT_CAMERA_NAME }
        prusaSettings = prusaSettings.copy(cameraName = trimmed)
        prefs.edit().putString(KEY_PRUSA_NAME, trimmed).apply()
        prusaUploader?.markInfoStale()
        Timber.i("Prusa Connect camera name: $trimmed")
    }

    /** Set the snapshot upload interval in seconds (clamped to 5-3600). */
    override fun setPrusaInterval(seconds: Int) {
        val clamped = seconds.coerceIn(5, 3600)
        prusaSettings = prusaSettings.copy(intervalSeconds = clamped)
        prefs.edit().putInt(KEY_PRUSA_INTERVAL, clamped).apply()
        Timber.i("Prusa Connect interval: ${clamped}s")
    }

    /** WS-triggered scan: a found token is set and Prusa Connect enabled. */
    override fun startPrusaQrScan() {
        startPrusaQrScan { token ->
            if (token != null) {
                setPrusaToken(token)
                setPrusaEnabled(true)
                Timber.i("Prusa QR scan (WS): token found, Prusa Connect enabled")
            } else {
                Timber.i("Prusa QR scan (WS): no token found")
            }
        }
    }

    // --- Prusa QR scan ---------------------------------------------------------

    /**
     * Scan stream frames for a QR code containing a Prusa Connect token.
     * The camera is turned on automatically if it is off (frames are the scan
     * source — no second camera session needed), and turned off again when
     * the scan ends if it was off before.
     *
     * @param onResult invoked on the main thread with the extracted token, or
     *   null if no QR with a token was found within the timeout.
     */
    fun startPrusaQrScan(onResult: (String?) -> Unit) {
        stopPrusaQrScan()
        val scanner =
            qrScanner
                ?: BarcodeScanning
                    .getClient(
                        BarcodeScannerOptions
                            .Builder()
                            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                            .build(),
                    ).also { qrScanner = it }
        val wasStreaming = deviceState.isStreaming
        if (!wasStreaming) {
            Timber.i("Prusa QR scan: camera off — starting streaming")
            startStreaming()
        }
        qrScanJob =
            encodeScope.launch {
                val deadline = System.currentTimeMillis() + QR_SCAN_TIMEOUT_MS
                var token: String? = null
                try {
                    while (isActive && token == null && System.currentTimeMillis() < deadline) {
                        val frame = streamServer?.getLatestFrame()
                        if (frame != null) {
                            val bitmap = decodeScanBitmap(frame)
                            if (bitmap != null) {
                                try {
                                    for (barcode in scanQr(scanner, bitmap)) {
                                        val raw = barcode.rawValue.orEmpty()
                                        Timber.i("Prusa QR scan: decoded QR payload: $raw")
                                        val t = QrTokenParser.extractToken(raw)
                                        if (t != null) {
                                            token = t
                                            break
                                        }
                                    }
                                } catch (e: Exception) {
                                    Timber.w(e, "Prusa QR scan: frame failed")
                                } finally {
                                    bitmap.recycle()
                                }
                            }
                        }
                        delay(QR_SCAN_PERIOD_MS)
                    }
                    Timber.i("Prusa QR scan: ${if (token != null) "token found" else "timeout"}")
                } finally {
                    // Restore the camera state: stop the capture the scan started.
                    if (!wasStreaming) {
                        Timber.i("Prusa QR scan: stopping streaming (camera was off before)")
                        stopStreaming()
                    }
                }
                withContext(Dispatchers.Main) { onResult(token) }
            }
    }

    /** Cancel an in-flight QR scan, if any. */
    fun stopPrusaQrScan() {
        qrScanJob?.cancel()
        qrScanJob = null
    }

    /**
     * Decode a JPEG stream frame for QR scanning, downscaled so the longest
     * side is at most [QR_SCAN_MAX_DIM] px. The full-resolution bitmap is
     * recycled before the scaled one is returned.
     */
    private fun decodeScanBitmap(jpeg: ByteArray): Bitmap? {
        val full = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        val maxSide = maxOf(full.width, full.height)
        if (maxSide <= QR_SCAN_MAX_DIM) return full
        val scale = QR_SCAN_MAX_DIM.toFloat() / maxSide
        val scaled =
            Bitmap.createScaledBitmap(
                full,
                (full.width * scale).toInt().coerceAtLeast(1),
                (full.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        full.recycle()
        return scaled
    }

    /** Run the ML Kit QR scanner on one frame (Task → suspend bridge). */
    private suspend fun scanQr(
        scanner: BarcodeScanner,
        bitmap: Bitmap,
    ): List<Barcode> =
        suspendCancellableCoroutine { cont ->
            val task: com.google.android.gms.tasks.Task<List<Barcode>> =
                scanner.process(InputImage.fromBitmap(bitmap, 0))
            task.addOnCompleteListener { result ->
                if (cont.isCancelled) return@addOnCompleteListener
                if (result.isSuccessful) {
                    cont.resumeWith(Result.success(result.result))
                } else {
                    cont.resumeWith(Result.failure(requireNotNull(result.exception)))
                }
            }
        }

    /** The directory recordings are written to, based on [storageLocation]. */
    private fun videosDir(): File =
        if (storageLocation == STORAGE_EXTERNAL) {
            File(getExternalFilesDir(null) ?: filesDir, "videos")
        } else {
            File(filesDir, "videos")
        }

    override fun onDestroy() {
        stopStreaming()
        stopPrusaQrScan()
        qrScanner?.close()
        qrScanner = null
        unregisterNetworkCallback()
        prusaUploader?.release()
        prusaUploader = null
        prusaClient?.close()
        prusaClient = null
        prusaReleaseWakeLock()
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
                    streamServer?.invalidateIpCache()
                    deviceState.setStreamUrl(null)
                }

                override fun onAvailable(network: Network) {
                    // The LAN address may have changed: re-resolve the IP,
                    // refresh the displayed URL, and re-send the camera info
                    // so Prusa Connect shows the current address.
                    streamServer?.invalidateIpCache()
                    refreshStreamUrl()
                    prusaUploader?.markInfoStale()
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
        val prusa =
            if (deviceState.prusaState.enabled) {
                if (deviceState.prusaState.error != null) " • Prusa ⚠" else " • Prusa ✓"
            } else {
                ""
            }
        val sub =
            when {
                recording && timelapseOnlyRecording -> "Timelapse • $url$prusa"
                recording -> "Recording • $url$prusa"
                deviceState.isStreaming -> "Streaming • $url$prusa"
                else -> "Camera off • $url$prusa"
            }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(sub))
    }
}
