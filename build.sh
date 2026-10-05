#!/usr/bin/env bash
# =============================================================================
# AndroidCam — Build Script
# =============================================================================
# Usage:
#   ./build.sh              # Build debug APK
#   ./build.sh release      # Build release APK/AAB
#   ./build.sh install      # Build + install to connected device
#   ./build.sh clean        # Clean build artifacts
#   ./build.sh run          # Build + install + launch on device
#   ./build.sh lint         # Run lint checks
#   ./build.sh test         # Run unit tests
#   ./build.sh help         # Show this help
# =============================================================================

set -euo pipefail

# Colors (ANSI-C quoting so the vars hold real escape chars — works in both
# echo and heredocs)
RED=$'\033[0;31m'
GREEN=$'\033[0;32m'
YELLOW=$'\033[1;33m'
_BLUE=$'\033[0;34m' # reserved palette entry
CYAN=$'\033[0;36m'
NC=$'\033[0m' # No Color

# Project root
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Gradle wrapper
GRADLE="./gradlew"

# App ID
APP_ID="com.androidcam"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

log()  { echo -e "${GREEN}[AndroidCam]${NC} $*"; }
warn() { echo -e "${YELLOW}[AndroidCam] WARNING:${NC} $*" >&2; }
err()  { echo -e "${RED}[AndroidCam]${RED} ERROR:${NC} $*" >&2; exit 1; }
info() { echo -e "${CYAN}[AndroidCam]${NC} $*"; }

check_prerequisites() {
    # Check Java/Kotlin (JDK 17+)
    if ! command -v java &>/dev/null; then
        err "Java not found. Install JDK 17+ and set JAVA_HOME."
    fi

    JAVA_VERSION=$(java -version 2>&1 | head -1 | grep -oP '(?<=version ")[0-9]+' || echo "0")
    if [ "$JAVA_VERSION" -lt 17 ]; then
        warn "Java $JAVA_VERSION detected. JDK 17+ recommended."
    fi

    # Check Android SDK
    if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
        warn "ANDROID_HOME not set. Trying to detect from environment..."
        # Common locations
        if [ -d "$HOME/Android/Sdk" ]; then
            export ANDROID_HOME="$HOME/Android/Sdk"
            log "Detected SDK at $ANDROID_HOME"
        elif [ -d "$HOME/Library/Android/sdk" ]; then
            export ANDROID_HOME="$HOME/Library/Android/sdk"
            log "Detected SDK at $ANDROID_HOME"
        else
            err "Android SDK not found. Set ANDROID_HOME or install Android Studio."
        fi
    fi

    if [ ! -d "${ANDROID_HOME:-}" ]; then
        err "ANDROID_HOME points to non-existent directory: ${ANDROID_HOME}"
    fi

    # Check for required SDK components
    if [ ! -d "${ANDROID_HOME}/platforms" ]; then
        err "Android SDK platforms not found. Run: $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager 'platforms;android-35'"
    fi

    # Check Gradle wrapper
    if [ ! -f "$GRADLE" ]; then
        err "Gradle wrapper not found. Run: ./gradlew wrapper"
    fi

    chmod +x "$GRADLE"
}

# ---------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------

cmd_build_debug() {
    log "Building debug APK..."
    info "SDK: $ANDROID_HOME"
    info "Java: $(java -version 2>&1 | head -1)"
    $GRADLE assembleDebug
    log "✅ Debug APK built successfully"
    log "📦 APK location:"
    find app/build/outputs/apk/debug -name "*.apk" -exec echo "  {}" \;
}

cmd_build_release() {
    log "Building release APK and AAB..."

    # Check for signing config
    if [ ! -f "app/release.keystore" ] && [ -z "${KEYSTORE_PATH:-}" ]; then
        warn "No release keystore found. Building unsigned APK (for testing only)."
        warn "To sign releases, create app/release.keystore and set KEYSTORE_PATH."
        $GRADLE assembleRelease
    else
        $GRADLE bundleRelease assembleRelease
    fi

    log "✅ Release build completed"
    log "📦 Output:"
    find app/build/outputs -name "*.apk" -o -name "*.aab" | while read f; do
        echo "  $f ($(du -h "$f" | cut -f1))"
    done
}

cmd_install() {
    log "Building and installing on connected device..."
    cmd_build_debug
    log "Installing APK..."
    adb install -r "$(find app/build/outputs/apk/debug -name "*.apk" | head -1)"
    log "✅ Installed"
}

cmd_run() {
    cmd_install
    log "Launching app..."
    adb shell am start -n "$APP_ID/.ui.MainActivity"
    log "✅ App launched"
}

cmd_clean() {
    log "Cleaning build artifacts..."
    $GRADLE clean
    log "✅ Cleaned"
}

cmd_lint() {
    log "Running lint checks..."
    $GRADLE lintDebug
    log "✅ Lint complete — check app/build/reports/lint-results-debug.html"
}

cmd_test() {
    log "Running unit tests..."
    $GRADLE testDebugUnitTest
    log "✅ Tests complete — check app/build/reports/tests/testDebugUnitTest/"
}

cmd_deps() {
    log "Project dependencies:"
    $GRADLE app:dependencies --configuration debugRuntimeClasspath 2>/dev/null | head -80
}

cmd_ip() {
    local ip
    # Get device IP address on current network
    ip=$(adb shell ip route 2>/dev/null | awk '{print $9}' | head -1)
    if [ -n "$ip" ]; then
        log "Device IP: $ip"
        log "Web UI:   http://$ip:8080"
        log "MJPEG:    http://$ip:8080/stream/mjpeg"
        log "WebSocket: ws://$ip:8080/ws/control"
    else
        err "Could not determine device IP. Check adb connection."
    fi
}

cmd_logcat() {
    log "Streaming logcat (Ctrl+C to stop)..."
    adb logcat -s AndroidCam:* CameraManager:* FrameCapturer:* VideoEncoder:* StreamServer:* RecordingService:* DeviceState:*
}

cmd_help() {
    cat <<EOF
${CYAN}AndroidCam Build Script${NC}
========================

Usage: ./build.sh <command>

Commands:
  ${GREEN}build${NC}               Build debug APK (default)
  ${GREEN}build release${NC}       Build release APK + AAB
  ${GREEN}install${NC}             Build + install to connected device
  ${GREEN}run${NC}                 Build + install + launch on device
  ${GREEN}clean${NC}               Clean build artifacts
  ${GREEN}lint${NC}                Run lint checks
  ${GREEN}test${NC}                Run unit tests
  ${GREEN}deps${NC}                List project dependencies
  ${GREEN}ip${NC}                  Show device IP and streaming URLs
  ${GREEN}logcat${NC}              Stream app logcat
  ${GREEN}help${NC}                Show this help

Environment variables:
  ANDROID_HOME         Android SDK path (auto-detected if unset)
  JAVA_HOME            JDK 17+ path
  KEYSTORE_PATH        Path to release keystore (for release builds)
  KEY_ALIAS            Keystore alias
  KEY_PASSWORD         Keystore password
  STORE_PASSWORD       Store password

Examples:
  ./build.sh                  # Build debug APK
  ./build.sh build release    # Build release
  ./build.sh install          # Build + install
  ./build.sh run              # Build + install + launch
  ./build.sh ip               # Get device IP for remote control
  KEYSTORE_PATH=~/my.keystore ./build.sh build release
EOF
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

main() {
    local command="${1:-build}"
    local subcommand="${2:-}"

    case "$command" in
        build)
            check_prerequisites
            if [ "$subcommand" = "release" ]; then
                cmd_build_release
            else
                cmd_build_debug
            fi
            ;;
        install) check_prerequisites && cmd_install ;;
        run)     check_prerequisites && cmd_run ;;
        clean)   check_prerequisites && cmd_clean ;;
        lint)    check_prerequisites && cmd_lint ;;
        test)    check_prerequisites && cmd_test ;;
        deps)    check_prerequisites && cmd_deps ;;
        ip)      cmd_ip ;;
        logcat)  cmd_logcat ;;
        help|--help|-h) cmd_help ;;
        *) err "Unknown command: $command. Run ./build.sh help" ;;
    esac
}

main "$@"
