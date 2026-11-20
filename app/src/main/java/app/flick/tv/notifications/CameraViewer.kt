package app.flick.tv.notifications

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import app.flick.tv.R
import app.flick.tv.homeassistant.HaCamera
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** Owns a single live video feed. */
@OptIn(UnstableApi::class)
internal class CameraViewer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val stream: suspend (String) -> String,
) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val font = context.resources.getFont(R.font.google_sans_flex_tv)
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    private var root: CameraFrame? = null
    private var dialog: Dialog? = null
    private var player: ExoPlayer? = null
    private var request: Job? = null
    private var deadline: Job? = null

    fun show(title: String, camera: String, activity: Activity?, onBack: () -> Unit): Boolean {
        dismiss()
        val frame = CameraFrame(activity ?: context, onBack).apply { setBackgroundColor(Color.BLACK) }
        val texture = TextureView(frame.context)
        frame.addView(texture, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        var videoAspectRatio = 0f
        fun fitVideo() {
            if (videoAspectRatio <= 0f || frame.width <= 0 || frame.height <= 0) return
            val width = minOf(frame.width, (frame.height * videoAspectRatio).toInt()).coerceAtLeast(1)
            val height = minOf(frame.height, (width / videoAspectRatio).toInt()).coerceAtLeast(1)
            val layout = texture.layoutParams
            if (layout.width != width || layout.height != height) {
                texture.layoutParams = FrameLayout.LayoutParams(width, height, Gravity.CENTER)
            }
        }
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitVideo() }
        val status = label(frame.context, "Connecting…", 14f).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = pill(0xee16212a.toInt())
            accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val heading = LinearLayout(frame.context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(48), dp(28), dp(48), dp(22))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xe6080d15.toInt(), Color.TRANSPARENT))
            val back = control(frame.context, "‹  Back", onBack)
            addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
            addView(label(frame.context, title.ifBlank { "Camera" }, 22f).apply { maxLines = 1 },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
            addView(status)
            back.post { if (root === frame) back.requestFocus() }
        }
        frame.addView(heading, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        root = frame
        try {
            if (activity == null) {
                val params = WindowManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.OPAQUE)
                    .apply { setTitle("Flick camera") }
                windows.addView(frame, params)
            } else {
                val window = Dialog(activity, R.style.Theme_Flick)
                dialog = window
                window.setContentView(frame)
                window.setOnCancelListener { if (root === frame) onBack() }
                window.show()
                window.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        } catch (_: RuntimeException) {
            dismiss()
            return false
        }
        var hasVideo = false
        val live = SpannableString("●  Live").apply {
            setSpan(ForegroundColorSpan(Color.rgb(119, 227, 173)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        fun unavailable() {
            if (root !== frame) return
            deadline?.cancel()
            player?.release()
            player = null
            status.text = "Camera unavailable"
        }
        request = scope.launch {
            val url = try { stream(camera) } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { unavailable(); return@launch }
            if (root !== frame) return@launch
            try {
                val origin = url.toHttpUrl()
                val client = http.newBuilder().addInterceptor { chain ->
                    if (!HaCamera.sameOrigin(origin, chain.request().url)) throw IOException("Unexpected camera stream host")
                    chain.proceed(chain.request())
                }.build()
                val factory = HlsMediaSource.Factory(OkHttpDataSource.Factory(client))
                val video = ExoPlayer.Builder(context).build()
                player = video
                video.trackSelectionParameters = video.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
                video.setVideoTextureView(texture)
                video.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                        if (root !== frame || size.width <= 0 || size.height <= 0) return
                        videoAspectRatio = size.width * size.pixelWidthHeightRatio / size.height
                        fitVideo()
                    }

                    override fun onRenderedFirstFrame() {
                        if (root === frame) {
                            deadline?.cancel()
                            hasVideo = true
                            status.text = live
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        if (root !== frame) return
                        when (state) {
                            Player.STATE_BUFFERING -> {
                                status.text = "Connecting…"
                                if (hasVideo) {
                                    deadline?.cancel()
                                    deadline = scope.launch { delay(15_000); unavailable() }
                                }
                            }
                            Player.STATE_READY -> if (hasVideo) {
                                deadline?.cancel()
                                status.text = live
                            }
                            Player.STATE_ENDED -> scope.launch { unavailable() }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) { scope.launch { unavailable() } }
                })
                deadline = scope.launch { delay(15_000); unavailable() }
                video.setMediaSource(factory.createMediaSource(MediaItem.fromUri(url)))
                video.playWhenReady = true
                video.prepare()
            } catch (_: RuntimeException) { unavailable() }
        }
        return true
    }

    fun dismiss() {
        val view = root
        root = null
        request?.cancel()
        request = null
        deadline?.cancel()
        deadline = null
        player?.release()
        player = null
        val window = dialog
        dialog = null
        if (window != null) window.dismiss()
        else if (view != null) runCatching { windows.removeViewImmediate(view) }
    }

    private fun dp(value: Int) = (value * density + .5f).toInt()
    private fun label(context: Context, value: String, size: Float) = TextView(context).apply {
        text = value
        textSize = size
        typeface = Typeface.create(font, Typeface.NORMAL)
        setTextColor(Color.WHITE)
        includeFontPadding = false
    }
    private fun control(context: Context, value: String, onClick: () -> Unit) = label(context, value, 16f).apply {
        val active = intArrayOf(android.R.attr.state_focused)
        val idle = intArrayOf()
        gravity = Gravity.CENTER
        background = StateListDrawable().apply {
            addState(active, pill(Color.rgb(237, 240, 245)))
            addState(idle, pill(0xee16212a.toInt()))
        }
        setTextColor(ColorStateList(arrayOf(active, idle), intArrayOf(Color.rgb(23, 33, 49), Color.WHITE)))
        setPadding(dp(19), 0, dp(19), 0)
        isFocusable = true
        isFocusableInTouchMode = true
        setOnClickListener { onClick() }
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(64).toFloat()
    }

    private class CameraFrame(context: Context, private val onBack: () -> Unit) : FrameLayout(context) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onBack()
            return true
        }
    }
}
