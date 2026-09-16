package net.osmand.plus.plugins.flightmode

import android.graphics.Bitmap
import androidx.compose.runtime.*
import kotlin.math.*
import kotlinx.coroutines.*
import net.osmand.util.PhotoDehaze

/** CPU worker shared by Compose previews and GL's existing asset preparation queue. */
internal object FlightPhotoDehaze {
    data class Recipe(val amount: Float = 0f, val depth: FlightPhotoDepthProfile? = null)

    fun recipe(settings: FlightPhotoImageAdjustments): Recipe =
        settings.clamped().let {
            if (it.dehaze == 0f) Recipe()
            else Recipe(it.dehaze, it.depthProfile.takeIf { _ -> it.depthEnabled })
        }

    /**
     * Caller owns the returned bitmap. Source is retained and is never recycled or changed here.
     */
    fun apply(source: Bitmap, recipe: Recipe): Bitmap {
        if (recipe.amount <= 0) return source
        val factor = min(1.0, 256.0 / max(source.width, source.height))
        val w = max(1, (source.width * factor).roundToInt())
        val h = max(1, (source.height * factor).roundToInt())
        val small = Bitmap.createScaledBitmap(source, w, h, true)
        val preview = IntArray(w * h)
        try {
            small.getPixels(preview, 0, w, 0, 0, w, h)
        } finally {
            if (small !== source) small.recycle()
        }
        val analysis = PhotoDehaze.analyse(preview, w, h)
        val depth =
            recipe.depth?.let { profile ->
                val values = profile.opticalKm.map { it ?: Float.NaN }.toFloatArray()
                FloatArray(w * h) { i ->
                    PhotoDehaze.sample(
                        values,
                        profile.width,
                        profile.height,
                        ((i % w) + .5) * profile.width / w - .5,
                        ((i / w) + .5) * profile.height / h - .5,
                        true,
                    )
                }
            }
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        val result =
            PhotoDehaze.render(pixels, source.width, source.height, analysis, depth, recipe.amount)
        if (Thread.currentThread().isInterrupted) throw CancellationException()
        return Bitmap.createBitmap(result, source.width, source.height, Bitmap.Config.ARGB_8888)
    }
}

internal data class FlightDehazePreview(
    val bitmap: Bitmap?,
    val busy: Boolean = false,
    val failed: Boolean = false,
)

/** Cancellation/latest-result-wins prevents rapid slider movements from queuing expensive work. */
@Composable
internal fun rememberDehazedPhoto(
    source: Bitmap?,
    settings: FlightPhotoImageAdjustments,
): FlightDehazePreview {
    val recipe =
        remember(settings.dehaze, settings.depthEnabled, settings.depthProfile) {
            FlightPhotoDehaze.recipe(settings)
        }
    var result by remember(source) { mutableStateOf(FlightDehazePreview(source)) }
    FlightResumedEffect(source, recipe) {
        if (source == null || recipe.amount == 0f) {
            result = FlightDehazePreview(source)
            return@FlightResumedEffect
        }
        result = result.copy(busy = true, failed = false)
        delay(180)
        try {
            val processed =
                runInterruptible(Dispatchers.Default) { FlightPhotoDehaze.apply(source, recipe) }
            result = FlightDehazePreview(processed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            result = FlightDehazePreview(source, failed = true)
        }
    }
    // Bitmaps displayed by RenderThread must not be recycled before it has finished using them.
    // Once replaced they become unreachable and Android reclaims them; no process-global bitmap
    // cache.
    return if (recipe.amount == 0f) FlightDehazePreview(source) else result
}
