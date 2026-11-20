package app.flick.tv.wallpaper

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import coil.ImageLoader
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** The last completed image stays visible while another is prepared. At most two images are drawn. */
@Composable
fun WallpaperBackground(
    photo: WallpaperPhoto?,
    dimAmount: Float,
    modifier: Modifier = Modifier,
) {
    val dim = dimAmount.takeIf { it.isFinite() }?.coerceIn(0f, .7f) ?: .18f
    val filter = remember(dim) {
        val brightness = 1f - dim
        ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(brightness, brightness, brightness, 1f) })
    }
    BoxWithConstraints(modifier) {
        val target = IntSize(constraints.maxWidth.coerceIn(1, 3840), constraints.maxHeight.coerceIn(1, 2160))
        WallpaperFrames(photo, target, filter)
    }
}

private enum class FrameKind { Photo, Fallback }
private data class WallpaperFrame(val image: ImageBitmap, val kind: FrameKind)
private data class FallbackFrame(val height: Int, val frame: WallpaperFrame)

@Composable
private fun WallpaperFrames(photo: WallpaperPhoto?, target: IntSize, filter: ColorFilter) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<WallpaperFrame?>(null) }
    var previous by remember { mutableStateOf<WallpaperFrame?>(null) }
    var fallback by remember { mutableStateOf<FallbackFrame?>(null) }
    val opacity = remember { Animatable(1f) }

    LaunchedEffect(photo?.uri, target) {
        // A rapid Next press settles the last successful frame before fetching another.
        opacity.snapTo(1f)
        previous = null
        if (photo == null) shown = null
        val background = fallback?.takeIf { it.height == target.height }?.frame
            ?: WallpaperFrame(wallpaperFallback(target.height).asImageBitmap(), FrameKind.Fallback)
                .also { fallback = FallbackFrame(target.height, it) }
        if (shown == null) shown = background
        if (photo == null) return@LaunchedEffect
        val request = ImageRequest.Builder(context)
            .data(photo.uri)
            .size(target.width, target.height)
            .scale(Scale.FIT)
            .precision(Precision.EXACT)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .transformations(WallpaperFrameTransformation(target))
            .build()
        val bitmap = loadWallpaper(context.imageLoader, request) ?: return@LaunchedEffect
        previous = shown
        shown = WallpaperFrame(bitmap, FrameKind.Photo)
        opacity.snapTo(0f)
        // Compose animation uses the platform's motion duration, including disabled motion.
        opacity.animateTo(1f, tween(700))
        previous = null
    }

    if (shown == null) Box(Modifier.fillMaxSize().background(Color(0xFF111720)))
    listOfNotNull(previous, shown).forEach { frame ->
        key(frame.image) {
            val incoming = frame === shown
            Image(frame.image, contentDescription = null, colorFilter = filter,
                contentScale = if (frame.kind == FrameKind.Fallback) ContentScale.FillBounds else ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    // Each image already contains its shade; fading needs no render target.
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    alpha = if (incoming) opacity.value else 1f
                })
        }
    }
}

private val wallpaperRetryDelays = listOf(1_000L, 2_000L, 4_000L)

/** Retry briefly when storage or an image server is not ready, keeping the current frame visible. */
private suspend fun loadWallpaper(loader: ImageLoader, request: ImageRequest): ImageBitmap? {
    for (attempt in 0..wallpaperRetryDelays.size) {
        val image = try {
            val result = loader.execute(request) as? SuccessResult
            (result?.drawable as? BitmapDrawable)?.bitmap?.asImageBitmap()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (image != null) return image
        delay(wallpaperRetryDelays.getOrNull(attempt) ?: return null)
    }
    return null
}
