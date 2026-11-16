package app.flick.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A failed or slow request leaves the last decoded image visible. At most two frames are retained. */
@Composable
fun WallpaperBackground(
    photo: WallpaperPhoto?,
    dimAmount: Float,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    var previous by remember { mutableStateOf<ImageBitmap?>(null) }
    val opacity = remember { Animatable(1f) }

    LaunchedEffect(photo?.uri) {
        // A rapid Next press settles the last successful frame before fetching another.
        opacity.snapTo(1f)
        previous = null
        if (photo == null) {
            shown = null
            return@LaunchedEffect
        }
        val request = ImageRequest.Builder(context)
            .data(photo.uri)
            .size(1920, 1080)
            .scale(Scale.FIT)
            .precision(Precision.EXACT)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        val result = try {
            context.imageLoader.execute(request) as? SuccessResult ?: return@LaunchedEffect
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@LaunchedEffect
        }
        val bitmap = withContext(Dispatchers.Default) { frame(result.drawable) }
        previous = shown
        shown = bitmap
        opacity.snapTo(0f)
        // Compose animation uses the platform's motion-duration setting, including disabled motion.
        opacity.animateTo(1f, tween(700))
        previous = null
    }

    Box(modifier.background(Brush.verticalGradient(listOf(Color(0xFF243448), Color(0xFF111720))))) {
        previous?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        shown?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize().alpha(opacity.value), contentScale = ContentScale.Crop) }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dimAmount.takeIf { it.isFinite() }?.coerceIn(0f, .7f) ?: .18f)))
    }
}

private fun frame(drawable: Drawable): ImageBitmap {
    if (drawable is BitmapDrawable) return drawable.bitmap.asImageBitmap()
    val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth.coerceIn(1, 1920), drawable.intrinsicHeight.coerceIn(1, 1080), Bitmap.Config.ARGB_8888)
    val bounds = drawable.copyBounds()
    drawable.setBounds(0, 0, bitmap.width, bitmap.height)
    drawable.draw(Canvas(bitmap))
    drawable.bounds = bounds
    return bitmap.asImageBitmap()
}
