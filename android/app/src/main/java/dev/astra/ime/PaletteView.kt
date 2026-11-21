package dev.astra.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Vertically scrolling grid of palette entries (symbols or emoji) drawn on one canvas: only the visible rows are
 * drawn and no View is created per entry. A tap commits the entry under it on release; once the finger moves past
 * the touch slop the gesture only scrolls (or flings) and nothing is committed. A second finger and ACTION_CANCEL
 * also commit nothing. Columns adapt to the width so each cell stays at least [MIN_CELL_DP] wide.
 */
class PaletteView(context: Context) : View(context) {

    var onPick: ((String) -> Unit)? = null

    private var items: List<String> = emptyList()

    /** Colors of the chosen theme; a change repaints at once. */
    internal var colors: KeyboardColors = KeyboardTheme.WHITE.colors
        set(value) {
            field = value
            invalidate()
        }

    /** Entries currently shown (read by the JVM UI tests). */
    internal val entries: List<String> get() = items
    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cell = RectF()

    private var pointerId = -1
    private var downX = 0f
    private var downY = 0f
    private var lastY = 0f
    private var pressed = -1
    private var dragging = false

    /** Drawn, small and centered, when there are no entries (the empty 履歴 tab). */
    var emptyLabel: String = ""
        set(value) {
            field = value
            invalidate()
        }

    /** Entry text size: emoji read better larger than symbols. */
    var textSizeSp = 24f
        set(value) {
            field = value
            invalidate()
        }

    val columns: Int get() = max(MIN_COLUMNS, (width / (MIN_CELL_DP * density)).toInt())
    private val cellHeight: Float get() = CELL_HEIGHT_DP * density
    private val rowCount: Int get() = (items.size + columns - 1) / columns
    private val maxScroll: Int get() = max(0, (rowCount * cellHeight).toInt() - height)

    /** Replaces the entries and scrolls to the top; the same list again keeps the scroll position. */
    fun setItems(newItems: List<String>) {
        if (newItems === items) return
        reset()
        scroller.forceFinished(true)
        items = newItems
        scrollTo(0, 0)
        invalidate()
    }

    /** Rows [first, last) intersecting the viewport; only these are drawn. */
    internal fun visibleRows(): IntRange {
        if (items.isEmpty() || height == 0) return IntRange.EMPTY
        val first = (scrollY / cellHeight).toInt()
        val last = min(rowCount, ((scrollY + height) / cellHeight).toInt() + 1)
        return first until last
    }

    /** Index of the entry at view coordinates ([y] relative to the viewport), or -1. */
    internal fun indexAt(x: Float, y: Float): Int {
        if (x < 0 || x >= width || y < 0 || y >= height) return -1
        val col = min(columns - 1, (x / (width.toFloat() / columns)).toInt())
        val row = ((y + scrollY) / cellHeight).toInt()
        val i = row * columns + col
        return if (i in items.indices) i else -1
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val rows = if (landscape) 2f else 3.5f // a cut-off row shows there is more to scroll
        setMeasuredDimension(
            resolveSize(resources.displayMetrics.widthPixels, widthMeasureSpec),
            resolveSize((rows * cellHeight).toInt(), heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scrollTo(0, min(scrollY, maxScroll))
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(colors.background)
        val cols = columns
        val cw = width.toFloat() / cols
        val ch = cellHeight
        textPaint.textSize = textSizeSp * density
        textPaint.color = colors.text
        val center = -(textPaint.ascent() + textPaint.descent()) / 2f
        if (items.isEmpty() && emptyLabel.isNotEmpty()) {
            textPaint.textSize = 14f * density
            textPaint.color = colors.hint
            canvas.drawText(emptyLabel, width / 2f, ch / 2f - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
            return
        }
        for (row in visibleRows()) {
            for (col in 0 until cols) {
                val i = row * cols + col
                if (i >= items.size) break
                cell.set(col * cw, row * ch, (col + 1) * cw, (row + 1) * ch)
                if (i == pressed && !dragging) {
                    cell.inset(2f * density, 2f * density)
                    fill.color = colors.pressed
                    canvas.drawRoundRect(cell, 8f * density, 8f * density, fill)
                }
                canvas.drawText(items[i], cell.centerX(), cell.centerY() + center, textPaint)
            }
        }
        // Thin position indicator on the right edge (only when there is more to scroll).
        val range = maxScroll
        if (range > 0) {
            val track = height.toFloat()
            val thumb = max(24f * density, track * height / (range + height))
            val top = scrollY + (track - thumb) * scrollY / range
            fill.color = colors.scrollbar
            cell.set(width - 4f * density, top, width - 1f * density, top + thumb)
            canvas.drawRoundRect(cell, 2f * density, 2f * density, fill)
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(0, scroller.currY.coerceIn(0, maxScroll))
            postInvalidateOnAnimation()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                reset()
                pointerId = event.getPointerId(0)
                downX = event.x
                downY = event.y
                lastY = event.y
                pressed = indexAt(event.x, event.y)
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                invalidate()
            }
            // A second finger never commits: the gesture becomes a plain scroll of the first finger.
            MotionEvent.ACTION_POINTER_DOWN -> startDrag()
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index < 0) return true
                velocity?.addMovement(event)
                val x = event.getX(index)
                val y = event.getY(index)
                if (!dragging && (abs(y - downY) > slop || abs(x - downX) > slop)) startDrag()
                if (dragging) {
                    scrollTo(0, (scrollY + (lastY - y)).toInt().coerceIn(0, maxScroll))
                }
                lastY = y
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointerId) reset()
            MotionEvent.ACTION_UP -> {
                val index = event.findPointerIndex(pointerId)
                val tapped = pressed
                val wasDragging = dragging
                if (index >= 0 && wasDragging) {
                    velocity?.let { v ->
                        v.addMovement(event)
                        v.computeCurrentVelocity(1000, maxFling)
                        val vy = v.getYVelocity(pointerId)
                        if (abs(vy) >= minFling) {
                            scroller.fling(0, scrollY, 0, -vy.toInt(), 0, 0, 0, maxScroll)
                            postInvalidateOnAnimation()
                        }
                    }
                }
                val hit = if (index >= 0) indexAt(event.getX(index), event.getY(index)) else -1
                reset()
                // Release commits only an undragged tap that ends on the cell it started on.
                if (!wasDragging && tapped >= 0 && hit == tapped) {
                    clicked = tapped
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> reset()
        }
        return true
    }

    /** Index of the tap being committed; a click without a tap (e.g. from accessibility) commits nothing. */
    private var clicked = -1

    override fun performClick(): Boolean {
        super.performClick()
        val i = clicked
        clicked = -1
        if (i !in items.indices) return false
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onPick?.invoke(items[i])
        return true
    }

    /** Drops a press or drag in progress without committing (keyboard hidden, panel opened, editor changed). */
    fun cancelGesture() {
        scroller.forceFinished(true)
        reset()
    }

    private fun startDrag() {
        dragging = true
        pressed = -1
        invalidate()
    }

    private fun reset() {
        pressed = -1
        dragging = false
        pointerId = -1
        velocity?.recycle()
        velocity = null
        invalidate()
    }

    override fun onDetachedFromWindow() {
        scroller.forceFinished(true)
        reset()
        super.onDetachedFromWindow()
    }

    internal companion object {
        const val MIN_CELL_DP = 48f
        const val CELL_HEIGHT_DP = 48f
        const val MIN_COLUMNS = 4
    }
}
