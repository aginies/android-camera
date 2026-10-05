package com.androidcam.stream

import android.content.Context
import com.androidcam.control.DeviceState
import com.androidcam.prusa.PrusaConnectSettings
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import timber.log.Timber
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicReference

/**
 * HTTP + WebSocket server for streaming and remote control.
 *
 * Serves the web UI, the MJPEG stream, and a control channel (REST + WebSocket).
 * Every endpoint except `GET /health` requires the shared [token] as a query
 * parameter.
 */
class StreamServer(
    private val context: Context,
    private val deviceState: DeviceState,
    private val token: String,
    private val control: ControlCallback,
) {
    /**
     * Actions that need the recording pipeline. Implemented by [RecordingService],
     * so the server never mutates pipeline state directly.
     */
    interface ControlCallback {
        /** @return true if recording actually started, false if blocked. */
        fun startRecording(): Boolean

        fun stopRecording()

        fun startStreaming()

        fun stopStreaming()

        fun switchCamera()

        fun setCameraFacing(facing: String)

        fun toggleTorch(): Boolean

        fun setResolution(resolution: String)

        /** Supported resolutions as "WxH" strings, highest first. */
        fun supportedResolutions(): List<String>

        /** Current interval (timelapse) settings: (enabled, seconds). */
        fun intervalSettings(): Pair<Boolean, Int>

        fun updateIntervalSettings(
            enabled: Boolean,
            seconds: Int,
        )

        /** Whether timelapse frames get a date/time stamp burned in. */
        fun timestampEnabled(): Boolean

        fun setTimestampEnabled(enabled: Boolean)

        /** JPEG compression quality for the stream (10-100). */
        fun jpegQuality(): Int

        fun setJpegQuality(quality: Int)

        fun setStorageLocation(location: String): Boolean

        fun storageLocationName(): String

        // --- Prusa Connect ---

        /** Current Prusa Connect settings (for the UI). */
        fun prusaSettings(): PrusaConnectSettings

        /** Enable/disable Prusa Connect uploads. */
        fun setPrusaEnabled(enabled: Boolean)

        /** Set the Prusa Connect token. @return false if the token is invalid. */
        fun setPrusaToken(token: String): Boolean

        /** Set the camera name shown in Prusa Connect. */
        fun setPrusaName(name: String)

        /** Set the snapshot upload interval in seconds. */
        fun setPrusaInterval(seconds: Int)
    }

    companion object {
        private const val MJPEG_BOUNDARY = "--frame"

        /** Idle poll interval for the MJPEG loop when no new frame is available. */
        private const val MJPEG_IDLE_DELAY_MS = 50L
    }

    private var server: ApplicationEngine? = null

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            isLenient = true
        }

    /** Latest JPEG frame; the MJPEG endpoint writes it when it changes. */
    private val latestFrame = AtomicReference<ByteArray?>(null)

    /** Publish a new JPEG frame to the MJPEG stream. */
    fun publishFrame(jpegBytes: ByteArray) {
        latestFrame.set(jpegBytes)
    }

    /** Latest JPEG frame, or null while the camera is off. */
    fun getLatestFrame(): ByteArray? = latestFrame.get()

    /** Get the device's local IPv4 address (site-local preferred). */
    fun getDeviceIp(): String {
        try {
            for (iface in NetworkInterface.getNetworkInterfaces().toList()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && isSiteLocal(addr)) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error getting device IP")
        }
        return "127.0.0.1"
    }

    private fun isSiteLocal(addr: InetAddress): Boolean {
        val b = addr.address
        val first = b[0].toInt() and 0xFF
        val second = b[1].toInt() and 0xFF
        return first == 10 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
    }

    /** Start the server on [DeviceState.streamPort]. */
    fun start() {
        val port = deviceState.streamPort
        // NOTE: pass a lambda, not a function reference (::appModule). Ktor
        // resolves the module's declaring class via reflection and tries to
        // instantiate it, which fails for a bound reference to a member
        // function.
        server =
            embeddedServer(
                Netty,
                port = port,
                host = "0.0.0.0",
            ) {
                appModule(this)
            }.start(wait = false)
        val ip = getDeviceIp()
        Timber.i("Stream server started on port $port")
        Timber.i("Web UI: http://$ip:$port/?token=$token")
        Timber.i("MJPEG: http://$ip:$port/stream/mjpeg?token=$token")
        Timber.i("WebSocket: ws://$ip:$port/ws/control?token=$token")
    }

    /** Stop the server. */
    fun stop() {
        server?.stop(1000, 1000)
        server = null
        Timber.d("Stream server stopped")
    }

    private fun authorized(call: ApplicationCall): Boolean = call.request.queryParameters["token"] == token

    private suspend fun unauthorized(call: ApplicationCall) {
        call.respondText("Unauthorized", ContentType.Text.Plain, HttpStatusCode.Unauthorized)
    }

    private fun appModule(application: Application) {
        application.install(WebSockets)
        application.install(CORS) {
            anyHost()
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
        }

        application.routing {
            get("/") {
                val html = loadAsset("web/index.html")
                if (html != null) {
                    call.respondText(html, ContentType.Text.Html)
                } else {
                    call.respondText("Web UI not found", ContentType.Text.Plain, HttpStatusCode.NotFound)
                }
            }

            // Health check (open — used for connectivity probes)
            get("/health") {
                call.respondText("OK", ContentType.Text.Plain)
            }

            // MJPEG stream, paced to the frame rate produced by FrameCapturer
            get("/stream/mjpeg") {
                if (!authorized(call)) return@get unauthorized(call)
                call.respondOutputStream(
                    contentType =
                        ContentType("multipart", "x-mixed-replace").withParameter("boundary", MJPEG_BOUNDARY),
                ) {
                    var lastWritten: ByteArray? = null
                    try {
                        while (true) {
                            val frame = latestFrame.get()
                            if (frame != null && frame !== lastWritten) {
                                write("--$MJPEG_BOUNDARY\r\n".toByteArray())
                                write("Content-Type: image/jpeg\r\n".toByteArray())
                                write("Content-Length: ${frame.size}\r\n\r\n".toByteArray())
                                write(frame)
                                write("\r\n".toByteArray())
                                flush()
                                lastWritten = frame
                            } else {
                                delay(MJPEG_IDLE_DELAY_MS)
                            }
                        }
                    } catch (e: IOException) {
                        // Client disconnected — end the stream
                        Timber.d("MJPEG client disconnected")
                    }
                }
            }

            // WebSocket control channel
            webSocket("/ws/control") {
                if (!authorized(call)) {
                    call.respond(HttpStatusCode.Unauthorized)
                    return@webSocket
                }
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val response = handleControlMessage(frame.readText())
                            send(Frame.Text(response))
                        }

                        is Frame.Close -> {
                            break
                        }

                        else -> {}
                    }
                }
                close(CloseReason(CloseReason.Codes.NORMAL, "Connection closed"))
            }

            // Remote control API (REST)
            route("/api") {
                get("/status") {
                    if (!authorized(call)) return@get unauthorized(call)
                    call.respondText(statusJson().toString(), ContentType.Application.Json)
                }

                get("/resolutions") {
                    if (!authorized(call)) return@get unauthorized(call)
                    call.respondText(
                        buildJsonArray {
                            control.supportedResolutions().forEach { add(JsonPrimitive(it)) }
                        }.toString(),
                        ContentType.Application.Json,
                    )
                }

                route("/control") {
                    post("/start-streaming") {
                        if (!authorized(call)) return@post unauthorized(call)
                        control.startStreaming()
                        call.respondText("Streaming started")
                    }

                    post("/stop-streaming") {
                        if (!authorized(call)) return@post unauthorized(call)
                        control.stopStreaming()
                        call.respondText("Streaming stopped")
                    }

                    post("/start-recording") {
                        if (!authorized(call)) return@post unauthorized(call)
                        val started = control.startRecording()
                        if (started) {
                            call.respondText("Recording started")
                        } else {
                            call.respondText(
                                "Recording not started: ${deviceState.lastError ?: "unknown"}",
                                ContentType.Text.Plain,
                                HttpStatusCode.BadRequest,
                            )
                        }
                    }

                    post("/stop-recording") {
                        if (!authorized(call)) return@post unauthorized(call)
                        control.stopRecording()
                        call.respondText("Recording stopped")
                    }

                    post("/camera/{facing}") {
                        if (!authorized(call)) return@post unauthorized(call)
                        val facing = call.parameters["facing"] ?: "back"
                        control.setCameraFacing(facing)
                        call.respondText("Camera: ${deviceState.facing.name.lowercase()}")
                    }

                    post("/rotate/{angle}") {
                        if (!authorized(call)) return@post unauthorized(call)
                        val angle = call.parameters["angle"]?.toIntOrNull() ?: 0
                        deviceState.rotationDegrees = angle
                        call.respondText("Rotated to $angle degrees")
                    }

                    post("/toggle-torch") {
                        if (!authorized(call)) return@post unauthorized(call)
                        val on = control.toggleTorch()
                        call.respondText("Torch ${if (on) "on" else "off"}")
                    }

                    post("/resolution/{resolution}") {
                        if (!authorized(call)) return@post unauthorized(call)
                        val resolution = call.parameters["resolution"] ?: "1920x1080"
                        control.setResolution(resolution)
                        call.respondText("Resolution: ${deviceState.videoWidth}x${deviceState.videoHeight}")
                    }

                    post("/screen-timeout") {
                        if (!authorized(call)) return@post unauthorized(call)
                        val enabled = call.parameters["enabled"]?.toBooleanStrictOrNull() ?: true
                        val seconds = call.parameters["seconds"]?.toIntOrNull() ?: 30
                        deviceState.screenTimeoutEnabled = enabled
                        deviceState.screenTimeoutSeconds = seconds
                        call.respondText("Screen timeout: ${if (enabled) "$seconds s" else "disabled"}")
                    }

                    post("/storage-location") {
                        if (!authorized(call)) return@post unauthorized(call)
                        val location = call.parameters["location"] ?: "internal"
                        val ok = control.setStorageLocation(location)
                        if (ok) {
                            call.respondText("Storage location: $location")
                        } else {
                            call.respondText(
                                "Invalid storage location",
                                ContentType.Text.Plain,
                                HttpStatusCode.BadRequest,
                            )
                        }
                    }

                    post("/interval") {
                        if (!authorized(call)) return@post unauthorized(call)
                        val enabled = call.parameters["enabled"]?.toBooleanStrictOrNull() ?: false
                        val seconds = call.parameters["seconds"]?.toIntOrNull() ?: 60
                        control.updateIntervalSettings(enabled, seconds)
                        call.respondText("Interval: ${if (enabled) "on" else "off"} (${seconds}s)")
                    }

                    post("/timestamp") {
                        if (!authorized(call)) return@post unauthorized(call)
                        val enabled = call.parameters["enabled"]?.toBooleanStrictOrNull() ?: false
                        control.setTimestampEnabled(enabled)
                        call.respondText("Timestamp: ${if (enabled) "on" else "off"}")
                    }

                    post("/jpeg-quality") {
                        if (!authorized(call)) return@post unauthorized(call)
                        val quality = call.parameters["quality"]?.toIntOrNull() ?: 80
                        control.setJpegQuality(quality)
                        call.respondText("JPEG quality: ${control.jpegQuality()}")
                    }

                    post("/prusa-connect") {
                        if (!authorized(call)) return@post unauthorized(call)
                        val enabled = call.parameters["enabled"]?.toBooleanStrictOrNull() ?: false
                        control.setPrusaEnabled(enabled)
                        call.respondText("Prusa Connect: ${if (enabled) "on" else "off"}")
                    }

                    post("/prusa-token") {
                        if (!authorized(call)) return@post unauthorized(call)
                        val token = call.parameters["token"].orEmpty()
                        if (control.setPrusaToken(token)) {
                            call.respondText("Prusa token set")
                        } else {
                            call.respondText(
                                "Invalid token (must be exactly 20 characters)",
                                ContentType.Text.Plain,
                                HttpStatusCode.BadRequest,
                            )
                        }
                    }

                    post("/prusa-name") {
                        if (!authorized(call)) return@post unauthorized(call)
                        val name = call.parameters["name"].orEmpty()
                        control.setPrusaName(name)
                        call.respondText("Prusa camera name: ${control.prusaSettings().cameraName}")
                    }

                    post("/prusa-interval") {
                        if (!authorized(call)) return@post unauthorized(call)
                        val seconds = call.parameters["seconds"]?.toIntOrNull() ?: 30
                        control.setPrusaInterval(seconds)
                        call.respondText("Prusa interval: ${control.prusaSettings().intervalSeconds}s")
                    }
                }
            }
        }
    }

    /** Single source of truth for the status payload (REST + WebSocket). */
    private fun statusJson(): JsonObject =
        buildJsonObject {
            put("recording", deviceState.recordingState.name.lowercase())
            put("streaming", deviceState.isStreaming)
            put("facing", deviceState.facing.name.lowercase())
            put("rotation", deviceState.rotationDegrees)
            put("resolution", "${deviceState.videoWidth}x${deviceState.videoHeight}")
            putJsonArray("supportedResolutions") {
                control.supportedResolutions().forEach { add(JsonPrimitive(it)) }
            }
            put("ip", getDeviceIp())
            put("port", deviceState.streamPort)
            put("recordedVideos", deviceState.recordedVideosCount)
            put("lastRecordedFile", deviceState.lastRecordedFile ?: "")
            put("screenTimeoutEnabled", deviceState.screenTimeoutEnabled)
            put("screenTimeoutSeconds", deviceState.screenTimeoutSeconds)
            put("storageLocation", control.storageLocationName())
            val (intervalOn, intervalSec) = control.intervalSettings()
            put("intervalEnabled", intervalOn)
            put("intervalSeconds", intervalSec)
            put("timelapseFrames", deviceState.timelapseFrames)
            put("timelapseEncoding", deviceState.timelapseEncoding)
            put("timelapseProgress", deviceState.timelapseProgress)
            put("timestampEnabled", control.timestampEnabled())
            put("jpegQuality", control.jpegQuality())
            put("prusaEnabled", deviceState.prusaState.enabled)
            put("prusaRegistered", deviceState.prusaState.registered)
            put("prusaLastUpload", deviceState.prusaState.lastUploadMs)
            put("prusaError", deviceState.prusaState.error ?: "")
            put("prusaToken", control.prusaSettings().token)
            put("prusaName", control.prusaSettings().cameraName)
            put("prusaInterval", control.prusaSettings().intervalSeconds)
            put("error", deviceState.lastError ?: "")
        }

    /** Handle a WebSocket control message and return a JSON response. */
    private fun handleControlMessage(message: String): String =
        try {
            val cmd =
                json.parseToJsonElement(message) as? JsonObject
                    ?: throw IllegalArgumentException("Expected JSON object")
            val action =
                cmd["action"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("No action")

            when (action) {
                "start_streaming" -> {
                    control.startStreaming()
                    ok("streaming_started")
                }

                "stop_streaming" -> {
                    control.stopStreaming()
                    ok("streaming_stopped")
                }

                "start_recording" -> {
                    control.startRecording()
                    ok("recording_started")
                }

                "stop_recording" -> {
                    control.stopRecording()
                    ok("recording_stopped")
                }

                "rotate" -> {
                    val angle = cmd["angle"]?.jsonPrimitive?.intOrNull ?: 0
                    deviceState.rotationDegrees = angle
                    buildJsonObject {
                        put("status", "ok")
                        put("rotation", angle)
                    }.toString()
                }

                "switch_camera" -> {
                    control.switchCamera()
                    buildJsonObject {
                        put("status", "ok")
                        put("facing", deviceState.facing.name.lowercase())
                    }.toString()
                }

                "set_camera" -> {
                    val facing = cmd["facing"]?.jsonPrimitive?.contentOrNull ?: "back"
                    control.setCameraFacing(facing)
                    buildJsonObject {
                        put("status", "ok")
                        put("facing", deviceState.facing.name.lowercase())
                    }.toString()
                }

                "toggle_torch" -> {
                    val on = control.toggleTorch()
                    buildJsonObject {
                        put("status", "ok")
                        put("torch", on)
                    }.toString()
                }

                "set_resolution" -> {
                    val resolution = cmd["resolution"]?.jsonPrimitive?.contentOrNull ?: "1920x1080"
                    control.setResolution(resolution)
                    buildJsonObject {
                        put("status", "ok")
                        put("resolution", "${deviceState.videoWidth}x${deviceState.videoHeight}")
                    }.toString()
                }

                "set_screen_timeout" -> {
                    val enabled = cmd["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
                    val seconds = cmd["seconds"]?.jsonPrimitive?.intOrNull ?: 30
                    deviceState.screenTimeoutEnabled = enabled
                    deviceState.screenTimeoutSeconds = seconds
                    buildJsonObject {
                        put("status", "ok")
                        put(
                            "screenTimeout",
                            buildJsonObject {
                                put("enabled", enabled)
                                put("seconds", seconds)
                            },
                        )
                    }.toString()
                }

                "set_storage_location" -> {
                    val location = cmd["location"]?.jsonPrimitive?.contentOrNull ?: "internal"
                    val applied = control.setStorageLocation(location)
                    buildJsonObject {
                        put("status", if (applied) "ok" else "error")
                        put("storageLocation", control.storageLocationName())
                    }.toString()
                }

                "set_interval" -> {
                    val enabled = cmd["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                    val seconds = cmd["seconds"]?.jsonPrimitive?.intOrNull ?: 60
                    control.updateIntervalSettings(enabled, seconds)
                    buildJsonObject {
                        put("status", "ok")
                        put(
                            "interval",
                            buildJsonObject {
                                put("enabled", enabled)
                                put("seconds", seconds)
                            },
                        )
                    }.toString()
                }

                "set_timestamp" -> {
                    val enabled = cmd["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                    control.setTimestampEnabled(enabled)
                    buildJsonObject {
                        put("status", "ok")
                        put("timestampEnabled", enabled)
                    }.toString()
                }

                "set_jpeg_quality" -> {
                    val quality = cmd["quality"]?.jsonPrimitive?.intOrNull ?: 80
                    control.setJpegQuality(quality)
                    buildJsonObject {
                        put("status", "ok")
                        put("jpegQuality", control.jpegQuality())
                    }.toString()
                }

                "set_prusa_connect" -> {
                    val enabled = cmd["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                    control.setPrusaEnabled(enabled)
                    ok("prusa_connect_set")
                }

                "set_prusa_token" -> {
                    val token = cmd["token"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (control.setPrusaToken(token)) {
                        ok("prusa_token_set")
                    } else {
                        errorJson("Invalid token (must be exactly 20 characters)")
                    }
                }

                "set_prusa_name" -> {
                    val name = cmd["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    control.setPrusaName(name)
                    buildJsonObject {
                        put("status", "ok")
                        put("prusaName", control.prusaSettings().cameraName)
                    }.toString()
                }

                "set_prusa_interval" -> {
                    val seconds = cmd["seconds"]?.jsonPrimitive?.intOrNull ?: 30
                    control.setPrusaInterval(seconds)
                    buildJsonObject {
                        put("status", "ok")
                        put("prusaInterval", control.prusaSettings().intervalSeconds)
                    }.toString()
                }

                "get_status" -> {
                    statusJson().toString()
                }

                else -> {
                    errorJson("Unknown action: $action")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Control message error")
            errorJson(e.message ?: "Invalid message")
        }

    private fun ok(event: String): String =
        buildJsonObject {
            put("status", "ok")
            put("event", event)
        }.toString()

    private fun errorJson(message: String): String =
        buildJsonObject {
            put("status", "error")
            put("message", message)
        }.toString()

    /** Load a text file from the APK's assets directory. */
    private fun loadAsset(path: String): String? =
        try {
            context.assets
                .open(path)
                .bufferedReader()
                .use { it.readText() }
        } catch (e: Exception) {
            Timber.w(e, "Failed to load asset: $path")
            null
        }
}
