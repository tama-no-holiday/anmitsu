package dev.astra.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.astra.ime.core.FlickDirection
import dev.astra.ime.core.FlickKey
import dev.astra.ime.core.FlickLayout
import kotlin.math.abs
import kotlin.math.max

/**
 * A static key definition. Holds layout labels only, never typed text. [flickTexts] makes a text flick key (English
 * and number 12-key): the character for each direction in [FlickDirection] order, null where a direction is blank.
 */
class Key(
    val label: String,
    val flick: FlickKey? = null,
    val text: String? = null,
    val flickTexts: List<String?>? = null,
    val action: Int = 0,
    val weight: Float = 1f,
    val repeat: Boolean = false,
    val highlighted: Boolean = false,
    /** Actions of a flick on a [isCursorPad] key, one per direction (0: none). */
    val upAction: Int = 0,
    val downAction: Int = 0,
    val leftAction: Int = 0,
    val rightAction: Int = 0,
    /** Action of a still hold (the system long-press timeout); 0: none. The release after it sends nothing. */
    val longAction: Int = 0,
    /** Small corner hint naming what [longAction] opens. */
    val longLabel: String? = null,
) {
    internal val rect = RectF()

    /** The press means nothing until it resolves: see [KeyboardView]. */
    internal val isCursorPad: Boolean get() = upAction != 0 || downAction != 0

    /** The direction a tap and a hold of a [isCursorPad] key go. */
    internal val ownDirection: FlickDirection get() = if (action == leftAction) FlickDirection.LEFT else FlickDirection.RIGHT
}

/**
 * Framework-only keyboard. Tracks one active pointer per gesture; a new finger hands off (see
 * [handOff]) and ACTION_CANCEL emits nothing. Gesture samples live in a bounded in-memory
 * buffer that is reset on every gesture and never persisted or logged.
 *
 * A [Key.isCursorPad] key (◀ ▶) sends nothing on press, unlike ⌫: a press may still become a flick, and a key event
 * already sent cannot be taken back. Release decides: the flick's direction once (same threshold and dominant axis as
 * every other flick), otherwise the key's own action once. A finger held for REPEAT_DELAY_MS starts the repeat of the
 * key's own direction instead; the gesture is then locked to it and the release sends nothing. A finger past the flick
 * threshold in another direction can no longer start the repeat; one between half the threshold and the threshold
 * only postpones it.
 */
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onFlick(key: FlickKey, direction: FlickDirection)
        fun onText(text: String)
        fun onAction(action: Int)
    }

    var listener: Listener? = null

    /** Disabled for sensitive fields; toggling clears any buffered samples. */
    var collectSamples = false
        set(value) {
            if (field == value) return // called with every layout refresh, which may keep a held key going
            field = value
            clearGesture()
        }

    /** Off for password fields: no enlarged bubble of the character being typed. */
    var showPreview = true
        set(value) {
            field = value
            invalidate()
        }

    /** Colors of the chosen theme; a change repaints at once. */
    internal var colors: KeyboardColors = KeyboardTheme.WHITE.colors
        set(value) {
            field = value
            invalidate()
        }

    private var rows: List<List<Key>> = emptyList()

    /** Current layout (read by the JVM UI tests). */
    internal val keyRows: List<List<Key>> get() = rows
    private val density = resources.displayMetrics.density
    private val threshold = 18f * density
    private val gap = 3f * density
    private val radius = 8f * density
    private val shadow = 1f * density
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val drawRect = RectF()

    private var active: Key? = null
    private var pointerId = -1
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var direction = FlickDirection.CENTER
    private var lastX = 0f
    private var lastY = 0f
    /** The repeat of a [Key.isCursorPad] key has started; only then is the press spent. */
    private var repeating = false
    /** The [Key.longAction] of the held key has been sent; the press is spent. */
    private var longPressed = false

    // (x, y) normalized to the originating key, t = ms since down (monotonic event time).
    private val samples = FloatArray(MAX_SAMPLES * 3)
    private var sampleCount = 0

    private val repeater = object : Runnable {
        override fun run() {
            val key = active ?: return
            if (key.isCursorPad && !repeating) {
                if (strayed(key) >= threshold / 2f) {
                    postDelayed(this, REPEAT_INTERVAL_MS) // not still: possibly a slow flick
                    return
                }
                repeating = true
            }
            listener?.onAction(key.action)
            postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    /** Fires only while the finger has not left the key's center zone ([track] cancels it on any flick direction). */
    private val longPresser = Runnable {
        val key = active ?: return@Runnable
        if (direction != FlickDirection.CENTER) return@Runnable
        longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
        listener?.onAction(key.longAction)
    }

    fun setRows(newRows: List<List<Key>>) {
        clearGesture()
        rows = newRows
        requestLayout()
        layoutKeys()
        invalidate()
    }

    /**
     * Swaps in rows of the same shape (only labels differ) without ending the current gesture, so a held key such
     * as ⌫ keeps repeating; the active key object stays tracked until its finger lifts.
     */
    fun updateRows(newRows: List<List<Key>>) {
        if (newRows.size != rows.size || newRows.indices.any { newRows[it].size != rows[it].size }) {
            setRows(newRows)
            return
        }
        rows = newRows
        layoutKeys()
        invalidate()
    }

    fun clearGesture() {
        removeCallbacks(repeater)
        removeCallbacks(longPresser)
        active = null
        pointerId = -1
        direction = FlickDirection.CENTER
        repeating = false
        longPressed = false
        samples.fill(0f)
        sampleCount = 0
        invalidate()
    }

    /** Parent constraints win: under AT_MOST/EXACTLY rows shrink to fit instead of overflowing. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val rowHeight = (if (landscape) 40f else 56f) * density
        setMeasuredDimension(
            resolveSize(resources.displayMetrics.widthPixels, widthMeasureSpec),
            resolveSize((rows.size * rowHeight).toInt(), heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutKeys()

    override fun onDetachedFromWindow() {
        clearGesture()
        super.onDetachedFromWindow()
    }

    private fun layoutKeys() {
        if (rows.isEmpty() || width == 0 || height == 0) return
        val rowHeight = height.toFloat() / rows.size
        rows.forEachIndexed { r, row ->
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            var x = 0f
            for (key in row) {
                val w = width * key.weight / total
                key.rect.set(x, r * rowHeight, x + w, (r + 1) * rowHeight)
                x += w
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(colors.background)
        for (row in rows) for (key in row) drawKey(canvas, key)
        if (showPreview) active?.let { drawPreview(canvas, it) }
    }

    private fun drawKey(canvas: Canvas, key: Key) {
        if (key.label.isEmpty()) return
        drawRect.set(key.rect)
        drawRect.inset(gap, gap)
        // Thin bottom edge, as on Gboard; stays inside the key's own rect.
        drawRect.offset(0f, shadow)
        keyPaint.color = colors.shadow
        canvas.drawRoundRect(drawRect, radius, radius, keyPaint)
        drawRect.offset(0f, -shadow)
        keyPaint.color = when {
            key === active -> colors.pressed
            key.highlighted -> colors.highlight
            key.flick == null && key.text == null && key.flickTexts == null -> colors.functionKey
            else -> colors.key
        }
        canvas.drawRoundRect(drawRect, radius, radius, keyPaint)
        val cx = drawRect.centerX()
        val cy = drawRect.centerY()
        val flick = key.flick
        val texts = key.flickTexts
        val labels = when {
            flick != null -> FlickLayout.labels(flick)
            texts != null -> FlickDirection.entries.filter { it != FlickDirection.CENTER && texts.getOrNull(it.ordinal) != null }
                .associateWith { texts[it.ordinal]!! }
            else -> {
                drawLabel(canvas, key.label, cx, cy, if (key.label.length > 1) 15f else 20f, colors.text)
                key.longLabel?.let {
                    drawLabel(canvas, it, drawRect.right - drawRect.width() * 0.2f, drawRect.top + drawRect.height() * 0.2f, 9f, colors.hint)
                }
                if (key.isCursorPad) {
                    val x = drawRect.width() * 0.36f
                    val y = drawRect.height() * 0.33f
                    drawLabel(canvas, UP_HINT, cx, cy - y, 10f, colors.hint)
                    drawLabel(canvas, DOWN_HINT, cx, cy + y, 10f, colors.hint)
                    drawLabel(canvas, LEFT_HINT, cx - x, cy, 10f, colors.hint)
                    drawLabel(canvas, RIGHT_HINT, cx + x, cy, 10f, colors.hint)
                }
                return
            }
        }
        val center = if (flick != null) labels[FlickDirection.CENTER] ?: key.label else key.label
        drawLabel(canvas, center, cx, cy, if (center.length > 1) 17f else 21f, colors.text)
        val dx = drawRect.width() * 0.36f
        val dy = drawRect.height() * 0.33f
        labels[FlickDirection.LEFT]?.let { drawLabel(canvas, it, cx - dx, cy, 10f, colors.hint) }
        labels[FlickDirection.RIGHT]?.let { drawLabel(canvas, it, cx + dx, cy, 10f, colors.hint) }
        labels[FlickDirection.UP]?.let { drawLabel(canvas, it, cx, cy - dy, 10f, colors.hint) }
        labels[FlickDirection.DOWN]?.let { drawLabel(canvas, it, cx, cy + dy, 10f, colors.hint) }
    }

    /** Preview bubble for the current direction, shifted toward it and clamped inside the view. */
    private fun drawPreview(canvas: Canvas, key: Key) {
        val flick = key.flick
        val label = when {
            flick != null -> FlickLayout.kana(flick, direction) ?: FlickLayout.labels(flick)[direction]
            key.isCursorPad -> when {
                repeating -> null
                direction == FlickDirection.UP -> UP_HINT
                direction == FlickDirection.DOWN -> DOWN_HINT
                direction == FlickDirection.LEFT -> LEFT_HINT
                direction == FlickDirection.RIGHT -> RIGHT_HINT
                else -> null
            }
            else -> key.flickTexts?.getOrNull(direction.ordinal)
        } ?: return
        val w = key.rect.width()
        val h = key.rect.height()
        drawRect.set(key.rect)
        when (direction) {
            FlickDirection.LEFT -> drawRect.offset(-w, 0f)
            FlickDirection.RIGHT -> drawRect.offset(w, 0f)
            FlickDirection.UP -> drawRect.offset(0f, -h)
            FlickDirection.DOWN -> drawRect.offset(0f, h)
            FlickDirection.CENTER -> Unit
        }
        if (drawRect.left < 0f) drawRect.offset(-drawRect.left, 0f)
        if (drawRect.right > width) drawRect.offset(width - drawRect.right, 0f)
        if (drawRect.top < 0f) drawRect.offset(0f, -drawRect.top)
        if (drawRect.bottom > height) drawRect.offset(0f, height - drawRect.bottom)
        drawRect.inset(gap, gap)
        keyPaint.color = colors.accent
        canvas.drawRoundRect(drawRect, radius, radius, keyPaint)
        drawLabel(canvas, label, drawRect.centerX(), drawRect.centerY(), 28f, colors.onAccent)
    }

    private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float, sizeDp: Float, color: Int) {
        textPaint.textSize = sizeDp * density
        textPaint.color = color
        canvas.drawText(text, x, y - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }

    /** False for a picture of a layout (the setup guide): touches pass through and nothing is ever sent. */
    internal var interactive = true

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(event, 0)
            MotionEvent.ACTION_POINTER_DOWN -> handOff(event)
            MotionEvent.ACTION_MOVE -> track(event)
            // Only the tracked pointer ends a gesture; a handed-off finger's later up is ignored.
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                if (active != null && event.getPointerId(event.actionIndex) == pointerId) end(event, event.actionIndex)
            MotionEvent.ACTION_CANCEL -> clearGesture()
        }
        return true
    }

    /**
     * Rolling two-thumb input. A new finger first completes the tracked gesture at that pointer's
     * position in this event, so a key can legitimately emit before its finger lifts; a held
     * repeat key just stops repeating. The new finger is then tracked; the earlier finger's
     * later moves and up are ignored, so nothing is emitted twice. An unresolved ◀ ▶ press is dropped instead:
     * its finger has not said what it meant, and a wrong cursor move is worse than none.
     */
    private fun handOff(event: MotionEvent) {
        val prior = if (active?.isCursorPad == false) event.findPointerIndex(pointerId) else -1
        if (prior >= 0) end(event, prior) else clearGesture()
        begin(event, event.actionIndex)
    }

    private fun begin(event: MotionEvent, index: Int) {
        clearGesture()
        val x = event.getX(index)
        val y = event.getY(index)
        val key = keyAt(x, y) ?: return
        active = key
        pointerId = event.getPointerId(index)
        downX = x
        downY = y
        lastX = x
        lastY = y
        downTime = event.eventTime
        record(key, x, y, event.eventTime)
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (key.repeat) {
            if (!key.isCursorPad) listener?.onAction(key.action)
            postDelayed(repeater, REPEAT_DELAY_MS)
        }
        if (key.longAction != 0) postDelayed(longPresser, ViewConfiguration.getLongPressTimeout().toLong())
        invalidate()
    }

    private fun track(event: MotionEvent) {
        val key = active ?: return
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return
        for (h in 0 until event.historySize) {
            record(key, event.getHistoricalX(index, h), event.getHistoricalY(index, h), event.getHistoricalEventTime(h))
        }
        record(key, event.getX(index), event.getY(index), event.eventTime)
        lastX = event.getX(index)
        lastY = event.getY(index)
        val next = directionOf(lastX - downX, lastY - downY)
        if (key.isCursorPad && !repeating && next != FlickDirection.CENTER && next != key.ownDirection) {
            removeCallbacks(repeater)
        }
        if (next != FlickDirection.CENTER) removeCallbacks(longPresser)
        if (next != direction) {
            direction = next
            invalidate()
        }
    }

    private fun end(event: MotionEvent, index: Int) {
        val key = active ?: return
        val dir = directionOf(event.getX(index) - downX, event.getY(index) - downY)
        val repeated = repeating
        val spent = longPressed
        clearGesture() // before dispatch: listeners may swap layouts
        val l = listener ?: return
        if (spent) return
        if (key.isCursorPad) {
            if (repeated) return
            l.onAction(
                when (dir) {
                    FlickDirection.UP -> key.upAction
                    FlickDirection.DOWN -> key.downAction
                    FlickDirection.LEFT -> key.leftAction
                    FlickDirection.RIGHT -> key.rightAction
                    FlickDirection.CENTER -> key.action
                },
            )
            return
        }
        if (key.repeat) return
        val flick = key.flick
        val text = key.text
        val texts = key.flickTexts
        when {
            flick != null -> l.onFlick(flick, dir)
            // A blank direction emits nothing, as on the kana keys.
            texts != null -> texts.getOrNull(dir.ordinal)?.let(l::onText)
            text != null -> l.onText(text)
            key.action != 0 -> l.onAction(key.action)
        }
    }

    /** How far the finger went anywhere but in the [key]'s own direction, the one its repeat goes. */
    private fun strayed(key: Key): Float {
        val dx = lastX - downX
        return max(abs(lastY - downY), if (key.ownDirection == FlickDirection.LEFT) dx else -dx)
    }

    private fun directionOf(dx: Float, dy: Float): FlickDirection = when {
        max(abs(dx), abs(dy)) < threshold -> FlickDirection.CENTER
        abs(dx) >= abs(dy) -> if (dx < 0) FlickDirection.LEFT else FlickDirection.RIGHT
        else -> if (dy < 0) FlickDirection.UP else FlickDirection.DOWN
    }

    private fun record(key: Key, x: Float, y: Float, time: Long) {
        if (!collectSamples || sampleCount >= MAX_SAMPLES) return
        val o = sampleCount * 3
        samples[o] = (x - key.rect.left) / key.rect.width()
        samples[o + 1] = (y - key.rect.top) / key.rect.height()
        samples[o + 2] = (time - downTime).toFloat()
        sampleCount++
    }

    private fun keyAt(x: Float, y: Float): Key? {
        for (row in rows) for (key in row) {
            if (key.label.isNotEmpty() && key.rect.contains(x, y)) return key
        }
        return null
    }

    private companion object {
        const val MAX_SAMPLES = 64
        const val REPEAT_DELAY_MS = 400L
        const val REPEAT_INTERVAL_MS = 60L
        const val UP_HINT = "↑"
        const val DOWN_HINT = "↓"
        const val LEFT_HINT = "←"
        const val RIGHT_HINT = "→"
    }
}
