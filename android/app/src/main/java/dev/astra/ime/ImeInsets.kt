package dev.astra.ime

import android.os.Build
import android.view.View
import android.view.WindowInsets
import kotlin.math.max

/**
 * Keeps keys out of the OS navigation area (gesture handle, IME hide/switch buttons drawn over
 * the IME window when it is edge-to-edge). The input root is padded, not the keyboard: padding
 * is plain background that handles no touches, so OS buttons over it keep working and no key
 * bounds extend under them. Padding is set absolutely on every dispatch, never accumulated, and
 * never derived from ime() (that inset describes this window itself). Gestures are not excluded.
 */
internal object ImeInsets {
    /**
     * Bottom room kept even when the framework delivers zero or already-consumed insets (the
     * original overlap was seen on a real device). Combined with measured insets by max, not sum.
     */
    // A full control-height fallback: the reported bug can survive a smaller 32dp spacer.
    const val MIN_BOTTOM_DP = 48f

    class Safe(val left: Int, val right: Int, val bottom: Int, val top: Int)

    @Suppress("DEPRECATION") // systemWindowInset* is the only source on API 26..29
    fun safeArea(insets: WindowInsets): Safe {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout())
            // Only the bottom of the gesture area matters: side back-gesture zones would waste width.
            val gestures = insets.getInsets(WindowInsets.Type.mandatorySystemGestures())
            val top = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
            return Safe(bars.left, bars.right, max(bars.bottom, gestures.bottom), top)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val cut = insets.displayCutout
            return Safe(
                max(insets.systemWindowInsetLeft, cut?.safeInsetLeft ?: 0),
                max(insets.systemWindowInsetRight, cut?.safeInsetRight ?: 0),
                max(insets.systemWindowInsetBottom, cut?.safeInsetBottom ?: 0),
                max(insets.systemWindowInsetTop, cut?.safeInsetTop ?: 0),
            )
        }
        return Safe(insets.systemWindowInsetLeft, insets.systemWindowInsetRight, insets.systemWindowInsetBottom, insets.systemWindowInsetTop)
    }

    /** [onSafe] hears every dispatch, after the padding is set. */
    fun install(root: View, onSafe: (Safe) -> Unit = {}) {
        val min = (MIN_BOTTOM_DP * root.resources.displayMetrics.density).toInt()
        root.setPadding(0, 0, 0, min) // until the first dispatch arrives
        root.setOnApplyWindowInsetsListener { v, insets ->
            val s = safeArea(insets)
            v.setPadding(s.left, 0, s.right, max(s.bottom, min))
            onSafe(s)
            insets
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = v.requestApplyInsets()
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
        if (root.isAttachedToWindow) root.requestApplyInsets()
    }
}
