package app.flick.notifications

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.accessibility.AccessibilityNodeInfo
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import app.flick.R
import app.flick.platform.isNavigationFeedback

/** An app overlay, never a system alert impersonation or a full-screen intent. */
internal class NoticeOverlay(private val context: Context, private val onNavigationFeedback: () -> Unit) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val foreground = Color.rgb(237, 240, 245)
    private val secondary = Color.rgb(183, 191, 202)
    private val surface = Color.rgb(27, 35, 46)
    private val typeface = context.resources.getFont(R.font.google_sans_flex_tv)
    private var root: View? = null
    private var image: ImageView? = null
    private var bitmap: Bitmap? = null
    private var photoStatus: TextView? = null

    fun show(notice: TvNotice, picture: Bitmap?, onDismiss: () -> Unit, onAction: (NoticeAction) -> Unit) {
        dismiss()
        val card = object : LinearLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (isNavigationFeedback(event.action, event.keyCode, event.repeatCount, event.isLongPress)) onNavigationFeedback()
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) onDismiss()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            isSoundEffectsEnabled = false
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = shape(surface, 20)
            elevation = dp(8).toFloat()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val width = minOf(dp(340), context.resources.displayMetrics.widthPixels - dp(64)).coerceAtLeast(dp(200))
        if (notice.photo != NoticePhoto.None) {
            val photo = FrameLayout(context).apply {
                background = shape(Color.rgb(18, 26, 36), 12)
                clipToOutline = true
            }
            image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            photo.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            photoStatus = text(if (notice.photo is NoticePhoto.Loading) "Loading photo…" else "Photo unavailable", 13f, secondary, 1)
            photo.addView(photoStatus, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            card.addView(photo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (width - dp(36)) * 9 / 16).apply {
                bottomMargin = dp(12)
            })
        }
        if (notice.title.isNotEmpty()) card.addView(text(notice.title, 18f, foreground, 2))
        if (notice.message.isNotEmpty()) card.addView(text(notice.message, 14f, secondary, 4), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) })
        var dismissButton: View? = null
        if (notice.interactive) {
            val buttons = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val hasCamera = notice.actions.any { it.camera != null }
            fun addButton(label: String, camera: Boolean = false, click: () -> Unit): View {
                val button = button(label, if (hasCamera) 22 else 12, camera, click)
                buttons.addView(button, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                    if (buttons.childCount > 0) marginStart = dp(8)
                })
                return button
            }
            dismissButton = addButton("Dismiss", click = onDismiss)
            notice.actions.forEach { action -> addButton(action.label, camera = action.camera != null) { onAction(action) } }
            card.addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(14) })
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            if (notice.interactive) 0 else (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        val layout = WindowManager.LayoutParams(
            width, ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(32)
            y = dp(28)
            setTitle("Flick notification")
        }
        root = card
        try {
            if (picture != null) updateImage(picture)
            windows.addView(card, layout)
            dismissButton?.let { button -> button.post { if (root === card) button.requestFocus() } }
        } catch (failure: RuntimeException) {
            dismiss()
            throw failure
        }
    }

    fun updateImage(picture: Bitmap?) {
        val target = image ?: run { picture?.recycle(); return }
        target.setImageBitmap(picture)
        photoStatus?.let { status ->
            status.text = "Photo unavailable"
            status.visibility = if (picture == null) View.VISIBLE else View.GONE
        }
        bitmap?.recycle()
        bitmap = picture
    }

    fun dismiss() {
        root?.let { try { windows.removeViewImmediate(it) } catch (_: IllegalArgumentException) { } }
        root = null
        image?.setImageDrawable(null)
        image = null
        photoStatus = null
        bitmap?.recycle()
        bitmap = null
    }

    private fun text(value: String, size: Float, color: Int, lines: Int) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = this@NoticeOverlay.typeface
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    private fun button(label: String, radius: Int, camera: Boolean, click: () -> Unit): View {
        val states = arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf())
        val textColor = foreground
        val colors = ColorStateList(states, intArrayOf(surface, surface, textColor))
        return LinearLayout(context).apply {
            gravity = Gravity.CENTER
            val padding = dp(if (camera) 12 else 6)
            setPadding(padding, 0, padding, 0)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            isSoundEffectsEnabled = false
            background = StateListDrawable().apply {
                addState(states[0], shape(textColor, radius))
                addState(states[1], shape(textColor, radius))
                addState(states[2], shape(Color.rgb(37, 47, 61), radius))
            }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                    info.text = label
                }
            }
            if (camera) addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_notice_camera)
                imageTintList = colors
                isDuplicateParentStateEnabled = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
            addView(text(label, 13f, textColor, 2).apply {
                setTextColor(colors)
                gravity = Gravity.CENTER
                isDuplicateParentStateEnabled = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            setOnClickListener { click() }
        }
    }

    private fun shape(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }
    private fun dp(value: Int) = (value * density + 0.5f).toInt()
}
