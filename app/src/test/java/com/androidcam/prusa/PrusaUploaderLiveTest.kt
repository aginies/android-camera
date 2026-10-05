package com.androidcam.prusa

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import timber.log.Timber
import java.util.Base64

/**
 * Live round-trip test against the real Prusa Connect API.
 *
 * Runs the actual [PrusaUploader] loop on the JVM: registers the camera
 * (`PUT /c/info`) and uploads one snapshot (`PUT /c/snapshot`). Requires
 * network access and a valid token — opt-in, not part of the default test
 * run:
 *
 *     PRUSA_LIVE_TEST=1 ./gradlew :app:testDebugUnitTest \
 *         --tests "com.androidcam.prusa.PrusaUploaderLiveTest"
 *
 * Token/fingerprint can be overridden with `PRUSA_TEST_TOKEN` /
 * `PRUSA_TEST_FINGERPRINT` (the defaults are a throwaway test camera).
 */
class PrusaUploaderLiveTest {
    @Test
    fun liveRegisterAndUpload() {
        assumeTrue(
            "Set PRUSA_LIVE_TEST=1 to run the live Prusa Connect round-trip test",
            System.getenv("PRUSA_LIVE_TEST") == "1",
        )
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
                token = System.getenv("PRUSA_TEST_TOKEN") ?: "j7VrJaOcw3bizJ5vqlwY",
                // Prusa Connect binds a token to the FIRST fingerprint that
                // registers it. This token was bound to this fingerprint by
                // the curl protocol test — a different fingerprint gets 403.
                fingerprint = System.getenv("PRUSA_TEST_FINGERPRINT") ?: "androidcam-test-0000001",
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
