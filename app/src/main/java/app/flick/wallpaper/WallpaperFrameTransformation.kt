package app.flick.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.unit.IntSize
import coil.size.Size
import coil.transform.Transformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Flatten the photo and contrast shading before Coil uploads the final image. */
internal class WallpaperFrameTransformation(private val target: IntSize) : Transformation {
    override val cacheKey = "flick-wallpaper-header-shade-v2:${target.width}x${target.height}"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap = composeBitmap(target.width, target.height) { canvas ->
        drawFallback(canvas, target.width, target.height)
        val scale = max(target.width.toFloat() / input.width, target.height.toFloat() / input.height)
        val width = input.width * scale
        val height = input.height * scale
        val left = (target.width - width) / 2f
        val top = (target.height - height) / 2f
        canvas.drawBitmap(input, null, RectF(left, top, left + width, top + height),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        currentCoroutineContext().ensureActive()
        drawShade(canvas, target.width, target.height)
    }
}

/** The fallback varies only vertically, so one column covers the whole viewport without a large bitmap. */
internal suspend fun wallpaperFallback(height: Int): Bitmap = composeBitmap(1, height) { canvas ->
    drawFallback(canvas, 1, height)
    drawShade(canvas, 1, height)
}

/** Ownership transfers only after the dispatcher returns; cancellation releases unpublished pixels. */
private suspend fun composeBitmap(width: Int, height: Int, draw: suspend (Canvas) -> Unit): Bitmap {
    require(width in 1..3840 && height in 1..2160)
    var unpublished: Bitmap? = null
    try {
        val result = withContext(Dispatchers.Default) {
            currentCoroutineContext().ensureActive()
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            unpublished = output
            draw(Canvas(output))
            output.setHasAlpha(false)
            currentCoroutineContext().ensureActive()
            output
        }
        currentCoroutineContext().ensureActive()
        unpublished = null
        return result
    } catch (failure: Throwable) {
        unpublished?.recycle()
        throw failure
    }
}

private fun drawFallback(canvas: Canvas, width: Int, height: Int) {
    val paint = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
            0xFF243448.toInt(), 0xFF111720.toInt(), Shader.TileMode.CLAMP)
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
}

private fun drawShade(canvas: Canvas, width: Int, height: Int) {
    fun black(alpha: Float) = (alpha * 255).roundToInt().coerceIn(0, 255) shl 24
    val paint = Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, height.toFloat(), intArrayOf(
            black(.78f), black(.65f), black(0f), black(.86f),
        ), floatArrayOf(0f, .16f, .46f, 1f), Shader.TileMode.CLAMP)
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
}
