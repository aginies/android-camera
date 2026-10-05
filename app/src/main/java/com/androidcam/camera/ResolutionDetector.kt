package com.androidcam.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import androidx.camera.core.CameraInfo
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.core.content.ContextCompat
import com.androidcam.control.DeviceState
import timber.log.Timber

/**
 * Detects the recording resolutions a camera can actually produce.
 *
 * CameraX's Recorder only accepts one of the four [Quality] tiers (SD/HD/FHD/UHD).
 * For each supported tier, [QualitySelector.getResolution] reports the exact
 * size the device's encoder will use. The pairs returned here are therefore
 * the complete set of selectable recording resolutions — no guessing, no
 * fallback surprises.
 */
object ResolutionDetector {
    /**
     * Asynchronously resolve the (quality, size) pairs supported for the given
     * [facing], highest resolution first. [callback] runs on the main thread
     * and receives an empty list if detection fails.
     */
    fun detect(
        context: Context,
        facing: DeviceState.CameraFacing,
        callback: (List<Pair<Quality, Size>>) -> Unit,
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                val pairs =
                    try {
                        val provider = future.get()
                        val lensFacing =
                            when (facing) {
                                DeviceState.CameraFacing.BACK -> CameraCharacteristics.LENS_FACING_BACK
                                DeviceState.CameraFacing.FRONT -> CameraCharacteristics.LENS_FACING_FRONT
                            }
                        val info: CameraInfo =
                            provider.availableCameraInfos.firstOrNull { it.lensFacing == lensFacing }
                                ?: provider.availableCameraInfos.firstOrNull()
                                ?: return@addListener callback(emptyList())
                        QualitySelector
                            .getSupportedQualities(info)
                            .mapNotNull { quality ->
                                QualitySelector.getResolution(info, quality)?.let {
                                    quality to it
                                }
                            }.sortedByDescending { (_, size) -> size.width.toLong() * size.height }
                    } catch (e: Exception) {
                        Timber.w(e, "Resolution detection failed")
                        emptyList()
                    }
                callback(pairs)
            },
            ContextCompat.getMainExecutor(context),
        )
    }
}
