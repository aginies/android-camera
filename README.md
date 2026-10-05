# 📹 AndroidCam

An Android app that streams the camera over the local network as MJPEG, records
H.264 MP4 video, and offers remote control via a token-protected web UI.

## Features

- **Live MJPEG stream** (~10 fps, 720p) served over HTTP
- **MP4 video recording** (H.264, hardware-encoded via CameraX `VideoCapture`)
- **Remote control** via web UI (any browser on the same network)
- **Token auth** — every endpoint requires a per-run token (shown in the app
  notification, logcat, and the mDNS advertisement)
- **Device discovery** via mDNS/Bonjour (`_androidcam._tcp`)
- **Foreground service** — streaming and recording survive app backgrounding
- **Screen timeout** — while recording, the screen turns off after a
  configurable idle time (a CPU wake lock keeps recording going)
- **Local controls** — record button and tap-to-focus in the app

## Quick Start

### Build

```bash
./build.sh            # or: ./gradlew :app:assembleDebug
```

### Run

1. Install the APK on an Android device (API 26+) with a camera
2. Open the app and grant the camera, microphone, and notification permissions
3. The app starts the streaming service and shows a notification with the
   device IP and auth token
4. From any device on the same network, open
   `http://<device-ip>:8080/?token=<token>` in a browser

### Get the token

The token is shown in:

- the app notification (sub-text)
- logcat: `adb logcat | grep "token:"`
- the mDNS advertisement (browse for `_androidcam._tcp` services)

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        RecordingService                         │
│  (foreground service — owns the whole pipeline)                 │
│                                                                 │
│  ┌──────────────┐    ┌───────────────┐    ┌──────────────────┐  │
│  │CameraManager │───▶│ FrameCapturer │───▶│   StreamServer   │  │
│  │ (CameraX)    │    │ (YUV→JPEG,    │    │ (Ktor/Netty:     │  │
│  │              │    │  ~10 fps)     │    │  MJPEG + REST +  │  │
│  │  • Preview   │    └───────────────┘    │  WebSocket + UI) │  │
│  │  • Image     │                         └──────────────────┘  │
│  │    Analysis  │    ┌───────────────┐    ┌──────────────────┐  │
│  │  • Video     │───▶│ MP4 file      │    │  MdnsDiscovery   │  │
│  │    Capture   │    │ (H.264)       │    │  (mDNS/Bonjour)  │  │
│  └──────────────┘    └───────────────┘    └──────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
         ▲
         │ bind (attach local preview)
┌──────────────┐        ┌──────────────┐
│ MainActivity │───────▶│ DeviceState  │  (shared, thread-safe state)
│ (UI + local  │        └──────────────┘
│  controls)   │
└──────────────┘
```

- **`RecordingService`** owns the camera, stream server, and mDNS advertising.
  It is a foreground service, so streaming/recording keep running after the
  activity is destroyed or the process is restarted.
- **`CameraManager`** is the single owner of the CameraX binding (preview +
  image analysis + video capture). The preview view is optional — the pipeline
  works without it.
- **`FrameCapturer`** converts `ImageAnalysis` frames (YUV_420_888, strides
  honoured) to JPEG at ~10 fps, applying the configured rotation.
- **`StreamServer`** serves the web UI (from `assets/web/`), the MJPEG stream
  (paced to the frame rate), and the control channel. It never mutates the
  pipeline directly — it calls `ControlCallback` methods implemented by the
  service.
- **`DeviceState`** is shared, thread-safe observation state. Writes go
  through setter methods so listeners are notified; the activity observes
  recording state, the server reads it for status.

## Project Structure

```
app/src/main/java/com/androidcam/
├── AppApplication.kt          # Application class, shared singletons, wake lock
├── camera/
│   ├── CameraManager.kt       # CameraX lifecycle (preview, analysis, recording)
│   └── FrameCapturer.kt       # YUV_420_888 → JPEG conversion for streaming
├── control/
│   └── DeviceState.kt         # Central device state (thread-safe)
├── discovery/
│   └── MdnsDiscovery.kt       # mDNS/Bonjour service discovery
├── stream/
│   ├── RecordingService.kt    # Foreground service owning the pipeline
│   └── StreamServer.kt        # Ktor HTTP/WebSocket server
└── ui/
    └── MainActivity.kt        # Camera preview + local controls
app/src/main/assets/web/
└── index.html                 # Web UI (served at /)
```

## API Reference

All endpoints except `GET /health` require `?token=<token>`.

### REST

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/status` | GET | Current device state (JSON) |
| `/api/control/start-recording` | POST | Start recording (MP4) |
| `/api/control/stop-recording` | POST | Stop recording |
| `/api/control/camera/{facing}` | POST | Set camera (`front` / `back`) |
| `/api/control/rotate/{angle}` | POST | Rotate stream (90/180/270) |
| `/api/control/toggle-torch` | POST | Toggle torch |
| `/api/control/resolution/{res}` | POST | Set recording resolution (`1920x1080`, `1280x720`, `640x480`) |
| `/api/control/screen-timeout?enabled={bool}&seconds={n}` | POST | Set screen timeout |

### WebSocket

Connect to `ws://<ip>:8080/ws/control?token=<token>`. Send JSON commands,
receive JSON responses:

```json
{"action": "start_recording"}
{"action": "stop_recording"}
{"action": "switch_camera"}
{"action": "set_camera", "facing": "front"}
{"action": "rotate", "angle": 90}
{"action": "toggle_torch"}
{"action": "set_resolution", "resolution": "1280x720"}
{"action": "set_screen_timeout", "enabled": true, "seconds": 30}
{"action": "get_status"}
```

### MJPEG

`http://<ip>:8080/stream/mjpeg?token=<token>` — multipart JPEG stream at the
frame rate produced by `FrameCapturer` (~10 fps).

## Permissions

| Permission | Why |
|------------|-----|
| `CAMERA` | Observe the camera |
| `RECORD_AUDIO` | Record audio with the video (falls back to silent video if denied) |
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` | Stream server + mDNS |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA` | Continuous streaming/recording |
| `POST_NOTIFICATIONS` | Foreground service notification (Android 13+) |
| `WAKE_LOCK` | Keep recording while the screen is off |

No storage permission is needed — recordings are written to the app's private
files directory (`filesDir/videos/`).

## Screen Timeout

While recording, the screen stays on for `screenTimeoutSeconds` (default 30,
configurable from the web UI or WebSocket) after the last touch, then turns
off. A CPU wake lock keeps the camera and encoder running. Touching the
preview resets the timer.

## Security Notes

- All endpoints are protected by a per-run token (12 random chars).
- Traffic is cleartext HTTP/WS — intended for trusted local networks only.
  Do not expose the port to the internet.
- The token is included in the mDNS advertisement so discovered devices can
  be connected to directly.

## License

Apache 2.0 — see [LICENSE](LICENSE).
