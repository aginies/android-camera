package com.androidcam.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.androidcam.AppApplication
import com.androidcam.R
import com.androidcam.control.DeviceState
import com.androidcam.databinding.ActivityMainBinding
import com.androidcam.databinding.DialogSettingsBinding
import com.androidcam.stream.RecordingService
import timber.log.Timber

/**
 * Main activity — camera preview + local record control.
 *
 * The recording/streaming pipeline is owned by [RecordingService]; this
 * activity starts and binds to it, attaches the preview view, and provides
 * local controls. Remote control is available via the web UI on the device's
 * IP (token shown in the notification).
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var service: RecordingService? = null
    private var bound = false

    private val deviceState: DeviceState get() = AppApplication.deviceState

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.values.all { it }) {
                startStreamingService()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            }
        }

    // SAF picker for choosing a custom recording directory.
    private val pickDirectoryLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                service?.setCustomTreeUri(uri)
                Toast.makeText(this, R.string.settings_dir_selected, Toast.LENGTH_SHORT).show()
            }
        }

    private val serviceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName?,
                obj: IBinder?,
            ) {
                val localBinder = obj as? RecordingService.LocalBinder ?: return
                service = localBinder.service
                bound = true
                localBinder.service.attachPreview(binding.previewView)
                Timber.d("Bound to RecordingService")
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                bound = false
                service = null
            }
        }

    private val recordingListener: (DeviceState.RecordingState) -> Unit = { state ->
        runOnUiThread { onRecordingStateChanged(state) }
    }

    private val streamUrlListener: (String?) -> Unit = {
        runOnUiThread { updateStreamStatus() }
    }

    private val streamingListener: (Boolean) -> Unit = { streaming ->
        runOnUiThread { onStreamingStateChanged(streaming) }
    }

    private val lastRecordedFileListener: (String?) -> Unit = { path ->
        runOnUiThread { showRecordedFile(path) }
    }

    private val timelapseListener: () -> Unit = {
        runOnUiThread { updateStreamStatus() }
    }

    private val screenTimeoutHandler = Handler(Looper.getMainLooper())
    private var screenTimeoutRunnable: Runnable? = null

    private val recordedFileHandler = Handler(Looper.getMainLooper())
    private var recordedFileRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAndRequestPermissions()
        setupTouchFocus()
        setupStreamingButton()
        setupRecordButton()
        setupSettingsButton()
        deviceState.addRecordingListener(recordingListener)
        deviceState.addStreamUrlListener(streamUrlListener)
        deviceState.addStreamingListener(streamingListener)
        deviceState.addLastRecordedFileListener(lastRecordedFileListener)
        deviceState.addTimelapseListener(timelapseListener)
        onStreamingStateChanged(deviceState.isStreaming)
        onRecordingStateChanged(deviceState.recordingState)
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        } else {
            startStreamingService()
        }
    }

    private fun startStreamingService() {
        val intent = Intent(this, RecordingService::class.java)
        // Forward any settings extras from the launch intent so the app can be
        // started with a specific token / interval config, e.g.
        //   am start -n com.androidcam/.ui.MainActivity --es set_token <token>
        forwardSettingsExtras(intent)
        ContextCompat.startForegroundService(this, intent)
        val boundOk = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (!boundOk) {
            Timber.w("Failed to bind RecordingService")
            Toast.makeText(this, "Failed to start streaming service", Toast.LENGTH_LONG).show()
        }
        showBatteryOptimizationPromptIfNeeded()
    }

    /**
     * One-time prompt explaining how to keep the app alive in the background.
     * Aggressive OEM power managers (Xiaomi/MIUI "One Key Clean", etc.) kill
     * the streaming service when the app is backgrounded unless the app is
     * exempted from battery optimizations, so the web stream drops.
     */
    private fun showBatteryOptimizationPromptIfNeeded() {
        val prefs = getSharedPreferences("androidcam_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("battery_prompt_shown", false)) return
        prefs.edit().putBoolean("battery_prompt_shown", true).apply()

        AlertDialog
            .Builder(this)
            .setTitle("Keep streaming in the background")
            .setMessage(
                "To stream to your computer while the phone is locked or the app is in " +
                    "the background, allow this app to ignore battery optimizations.\n\n" +
                    "On Xiaomi/MIUI you should also: lock the app in Recents (long-press " +
                    "its card → Lock) and enable Autostart.",
            ).setPositiveButton("Open settings") { _, _ -> openBatterySettings() }
            .setNegativeButton("Later", null)
            .show()
    }

    /** Open the system dialog to exempt this app from battery optimizations. */
    private fun openBatterySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                },
            )
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:$packageName")
                    },
                )
            } catch (e2: Exception) {
                Timber.w(e2, "Could not open battery settings")
            }
        }
    }

    /** Copy settings extras from the activity's launch intent into [serviceIntent]. */
    private fun forwardSettingsExtras(serviceIntent: Intent) {
        val extras = intent?.extras ?: return
        extras
            .getString(RecordingService.EXTRA_SET_TOKEN)
            ?.let { serviceIntent.putExtra(RecordingService.EXTRA_SET_TOKEN, it) }
        if (extras.containsKey(RecordingService.EXTRA_INTERVAL_ENABLED)) {
            serviceIntent.putExtra(
                RecordingService.EXTRA_INTERVAL_ENABLED,
                extras.getBoolean(RecordingService.EXTRA_INTERVAL_ENABLED, false),
            )
            serviceIntent.putExtra(
                RecordingService.EXTRA_INTERVAL_SECONDS,
                extras.getInt(RecordingService.EXTRA_INTERVAL_SECONDS, 60),
            )
        }
    }

    private fun setupTouchFocus() {
        binding.previewView.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Pixel coordinates in the preview view — what CameraX expects.
                    service?.setFocusPoint(event.x, event.y)
                    resetScreenTimeout()
                }

                MotionEvent.ACTION_UP -> {
                    view.performClick()
                }
            }
            false
        }
    }

    private fun setupStreamingButton() {
        binding.streamingButton.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (deviceState.isStreaming) {
                svc.stopStreaming()
            } else {
                svc.startStreaming()
            }
        }
    }

    private fun setupRecordButton() {
        binding.recordButton.setOnClickListener {
            val svc = service ?: return@setOnClickListener
            if (deviceState.recordingState == DeviceState.RecordingState.RECORDING) {
                svc.stopRecording()
            } else {
                if (!deviceState.isStreaming) {
                    Toast.makeText(this, R.string.record_requires_streaming, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                svc.startRecording()
            }
        }
    }

    private fun setupSettingsButton() {
        binding.settingsButton.setOnClickListener { showSettingsDialog() }
    }

    /** Update the streaming button label + status based on the camera state. */
    private fun onStreamingStateChanged(streaming: Boolean) {
        binding.streamingButton.setText(
            if (streaming) R.string.stop_streaming else R.string.start_streaming,
        )
        updateStreamStatus()
    }

    /** Show the settings dialog to configure the token and interval recording. */
    private fun showSettingsDialog() {
        val svc =
            service
                ?: run {
                    Toast.makeText(this, "Service not ready", Toast.LENGTH_SHORT).show()
                    return
                }
        val db = DialogSettingsBinding.inflate(LayoutInflater.from(this))

        // Prefill current values
        db.tokenInput.setText(svc.getToken())
        val (enabled, intervalSec) = svc.getIntervalSettings()
        db.intervalSwitch.isChecked = enabled
        db.intervalInput.setText(intervalSec.toString())
        db.timestampSwitch.isChecked = svc.timestampEnabled()

        // Storage location spinner
        val storageOptions =
            listOf(
                getString(R.string.settings_storage_internal),
                getString(R.string.settings_storage_external),
                getString(R.string.settings_storage_custom),
            )
        db.storageSpinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_item, storageOptions)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        db.storageSpinner.setSelection(
            when (svc.storageLocationName()) {
                RecordingService.STORAGE_EXTERNAL -> 1
                RecordingService.STORAGE_CUSTOM -> 2
                else -> 0
            },
        )

        // "Select directory" button launches the SAF picker.
        db.selectDirButton.setOnClickListener {
            pickDirectoryLauncher.launch(null)
        }

        AlertDialog
            .Builder(this)
            .setTitle(R.string.settings_title)
            .setView(db.root)
            .setPositiveButton(R.string.settings_save) { _, _ -> applySettings(svc, db) }
            .setNegativeButton(R.string.settings_cancel, null)
            .show()
    }

    private fun applySettings(
        svc: RecordingService,
        db: DialogSettingsBinding,
    ) {
        val token =
            db.tokenInput.text
                .toString()
                .trim()
        val intervalSec =
            db.intervalInput.text
                .toString()
                .toIntOrNull()
        if (token.isEmpty() || intervalSec == null || intervalSec < 1) {
            Toast.makeText(this, R.string.settings_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        svc.updateToken(token)
        svc.updateIntervalSettings(db.intervalSwitch.isChecked, intervalSec)
        svc.setTimestampEnabled(db.timestampSwitch.isChecked)
        val storage =
            when (db.storageSpinner.selectedItemPosition) {
                1 -> RecordingService.STORAGE_EXTERNAL
                2 -> RecordingService.STORAGE_CUSTOM
                else -> RecordingService.STORAGE_INTERNAL
            }
        svc.setStorageLocation(storage)
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    private fun onRecordingStateChanged(state: DeviceState.RecordingState) {
        val recording = state == DeviceState.RecordingState.RECORDING
        binding.recordButton.setText(if (recording) R.string.stop_recording else R.string.start_recording)
        updateStreamStatus()
        if (recording) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            scheduleScreenTimeout()
        } else {
            cancelScreenTimeout()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Show the public stream URL (with IP) once it is known, else a placeholder. */
    private fun updateStreamStatus() {
        val recording = deviceState.recordingState == DeviceState.RecordingState.RECORDING
        val url = deviceState.streamUrl
        val base =
            when {
                recording && url != null -> getString(R.string.status_recording, url)
                deviceState.isStreaming && url != null -> getString(R.string.status_streaming, url)
                url != null -> getString(R.string.status_camera_off)
                else -> getString(R.string.status_starting)
            }
        val timelapse =
            when {
                deviceState.timelapseEncoding -> {
                    "\nCreating timelapse video… ${deviceState.timelapseProgress}%"
                }

                deviceState.timelapseFrames > 0 -> {
                    "\nTimelapse: ${deviceState.timelapseFrames} frames captured"
                }

                else -> {
                    ""
                }
            }
        binding.streamStatus.text = base + timelapse
    }

    /**
     * While recording, keep the screen on for [DeviceState.screenTimeoutSeconds]
     * after the last interaction, then let it turn off (the CPU wake lock keeps
     * recording going). Touching the preview resets the timer.
     */
    private fun scheduleScreenTimeout() {
        cancelScreenTimeout()
        if (!deviceState.screenTimeoutEnabled) return
        val runnable =
            Runnable {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                Timber.i("Screen turned off after ${deviceState.screenTimeoutSeconds}s idle")
            }
        screenTimeoutRunnable = runnable
        screenTimeoutHandler.postDelayed(runnable, deviceState.screenTimeoutSeconds * 1000L)
    }

    private fun resetScreenTimeout() {
        if (deviceState.recordingState == DeviceState.RecordingState.RECORDING) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            scheduleScreenTimeout()
        }
    }

    private fun cancelScreenTimeout() {
        screenTimeoutRunnable?.let { screenTimeoutHandler.removeCallbacks(it) }
        screenTimeoutRunnable = null
    }

    /** Show where the last recording was saved, for 5 seconds. */
    private fun showRecordedFile(path: String?) {
        val overlay = binding.recordedFileOverlay
        if (path.isNullOrBlank()) {
            overlay.visibility = View.GONE
            return
        }
        overlay.text = getString(R.string.recorded_file_label, path)
        overlay.visibility = View.VISIBLE
        recordedFileRunnable?.let { recordedFileHandler.removeCallbacks(it) }
        recordedFileRunnable =
            Runnable { overlay.visibility = View.GONE }.also {
                recordedFileHandler.postDelayed(it, 5000L)
            }
    }

    override fun onDestroy() {
        cancelScreenTimeout()
        recordedFileRunnable?.let { recordedFileHandler.removeCallbacks(it) }
        deviceState.removeRecordingListener(recordingListener)
        deviceState.removeStreamUrlListener(streamUrlListener)
        deviceState.removeStreamingListener(streamingListener)
        deviceState.removeLastRecordedFileListener(lastRecordedFileListener)
        deviceState.removeTimelapseListener(timelapseListener)
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
        super.onDestroy()
    }
}
