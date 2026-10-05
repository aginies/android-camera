package com.androidcam.prusa

import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber
import java.util.Base64

/**
 * Live round-trip test against the real Prusa Connect API.
 *
 * Runs the actual [PrusaUploader] loop on the JVM: registers the camera
 * (`PUT /c/info`) and uploads one snapshot (`PUT /c/snapshot`) with the
 * project's test token. Requires network access — run manually, not in CI:
 *
 *     ./gradlew :app:testDebugUnitTest --tests "com.androidcam.prusa.PrusaUploaderLiveTest"
 */
class PrusaUploaderLiveTest {
    @Test
    fun liveRegisterAndUpload() {
        Timber.plant(
            object : Timber.Tree() {
                override fun log(
                    priority: Int,
                    tag: String?,
                    message: String,
                    t: Throwable?,
                ) {
                    println("  [$priority/$tag] $message ${t?.toString().orEmpty()}")
                }
            },
        )

        var state = PrusaState()
        var fatal: String? = null

        val settings =
            PrusaConnectSettings(
                enabled = true,
                token = "j7VrJaOcw3bizJ5vqlwY",
                // Prusa Connect binds a token to the FIRST fingerprint that
                // registers it. This token was bound to this fingerprint by
                // the curl protocol test — a different fingerprint gets 403.
                fingerprint = "androidcam-test-0000001",
                cameraName = "AndroidCam-JVM-Test",
                intervalSeconds = 10,
            )

        val client = PrusaConnectClient { settings }
        val uploader =
            PrusaUploader(
                client = client,
                settingsProvider = { settings },
                latestFrameProvider = { testJpeg() },
                cameraInfoProvider = {
                    PrusaCameraInfo(
                        name = settings.cameraName,
                        firmware = "0.0.0-jvm",
                        manufacturer = "Android",
                        model = "jvm-test",
                        width = 640,
                        height = 480,
                        wifiIpv4 = null,
                    )
                },
                onState = { state = it },
                onFatal = { fatal = it },
            )

        uploader.start()
        // Info is sent immediately; the first snapshot after one interval (10 s).
        Thread.sleep(25_000)
        uploader.release()
        client.close()

        println("=== FINAL STATE: $state")
        if (fatal != null) throw AssertionError("fatal: $fatal")
        assertTrue("not registered: $state", state.registered)
        assertTrue("no snapshot uploaded: $state", state.lastUploadMs > 0)
        assertTrue("unexpected error: ${state.error}", state.error == null)
    }

    private fun testJpeg(): ByteArray {
        val b64 =
            javaClass
                .getResourceAsStream("/prusa_test_snapshot_b64.txt")!!
                .readBytes()
                .decodeToString()
                .filterNot { it.isWhitespace() }
        return Base64.getDecoder().decode(b64)
    }
}
