package dev.astra.ime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * One group of the expanded candidate panel, drawn on one canvas like [PaletteView]: only what is on screen is drawn
 * and no View exists per candidate, so a list of any length costs one view.
 *
 * With [twoRows] candidates fill two rows sharing the view's height (each goes to the shorter one) and scroll sideways;
 * otherwise they wrap into a grid and scroll vertically. A candidate wider than the view wraps onto more lines there, so
 * a long one is never cut. Switching between the two keeps the position of each axis. The first move past the touch slop
 * locks the gesture: along the scroll axis it scrolls, across it nothing happens, and either way the release commits
 * nothing. Only an unmoved tap on one candidate picks it. A second finger and ACTION_CANCEL pick nothing.
 */
class CandidateSectionView @JvmOverloads constructor(context: Context, twoRows: Boolean = false) : View(context) {

    /** False while this group has the panel to itself. Any gesture in progress ends without a pick. */
    var twoRows: Boolean = twoRows
        set(value) {
            if (field == value) return
            cancelGesture()
            axisOffset[if (field) 0 else 1] = offset
            field = value
            wrapped.clear()
            laidOutFor = -1
            scrollTo(0, 0)
            restoreOffset = axisOffset[if (value) 0 else 1]
            requestLayout()
            invalidate()
        }

    /** Called with the index of a tapped candidate. */
    var onPick: ((Int) -> Unit)? = null

    internal var colors: KeyboardColors = KeyboardTheme.WHITE.colors
        set(value) {
            field = value
            invalidate()
        }

    /** Shown when there is nothing to list. */
    var emptyLabel: String = ""
        set(value) {
            field = value
            invalidate()
        }

    private var items: List<String> = emptyList()
    private var marked = -1
    private var primary = -1

    /** Candidates currently listed (read by the JVM UI tests). */
    internal val entries: List<String> get() = items
    /** Index drawn as the one the composition shows, -1 if none. */
    internal val markedIndex: Int get() = marked

    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = TEXT_SP * resources.displayMetrics.scaledDensity }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cell = RectF()

    // Cell geometry in content coordinates, by item; in wrapped mode y never decreases, so visible cells can be searched.
    private var cellX = FloatArray(0)
    private var cellY = FloatArray(0)
    private var cellW = FloatArray(0)
    private var cellH = FloatArray(0)
    private var contentExtent = 0f
    private var laidOutFor = -1
    private var laidOutHeight = -1
    /** Measured once per list, so a switch of layout measures nothing again. */
    private var textWidth: FloatArray? = null

    /** Position kept for the axis not in use (sideways, then vertical); reapplied at the next layout, when the size is known. */
    private val axisOffset = IntArray(2)
    private var restoreOffset = -1

    /** Wrapped text is built on demand for the few cells on screen. */
    private val wrapped = object : LinkedHashMap<Int, StaticLayout>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, StaticLayout>) = size > WRAP_CACHE
    }

    private var pointerId = -1
    private var downX = 0f
    private var downY = 0f
    private var last = 0f
    private var pressed = -1
    /** The finger left the slop: this gesture commits nothing any more. */
    private var moved = false
    /** Direction lock: the first move went along the scroll axis. */
    private var scrolling = false

    private val viewport: Int get() = if (twoRows) width else height
    private val offset: Int get() = if (twoRows) scrollX else scrollY

    internal val maxScroll: Int get() = max(0, ceil(contentExtent).toInt() - viewport)
    internal val scrollOffset: Int get() = offset

    /**
     * Replaces the list, back at the start unless [keepScroll] (the same opening's list, only completed by the lookup).
     * A press in progress is dropped either way: its index may mean another word now. The same list again changes nothing.
     */
    fun setItems(newItems: List<String>, markedIndex: Int = -1, primaryIndex: Int = -1, keepScroll: Boolean = false) {
        val same = newItems == items
        if (same && markedIndex == marked && primaryIndex == primary) return
        marked = markedIndex
        primary = primaryIndex
        if (!same) {
            cancelGesture()
            items = newItems.toList()
            textWidth = null
            laidOutFor = -1
            if (keepScroll) {
                ensureLayout()
                moveTo(offset)
            } else {
                axisOffset.fill(0)
                restoreOffset = -1
                moveTo(0)
            }
        }
        invalidate()
    }

    private fun moveTo(position: Int) {
        val clamped = position.coerceIn(0, maxScroll)
        if (twoRows) scrollTo(clamped, 0) else scrollTo(0, clamped)
    }

    private fun ensureLayout() {
        if ((laidOutFor == width && laidOutHeight == height) || width == 0) return
        laidOutFor = width
        laidOutHeight = height
        wrapped.clear()
        val n = items.size
        val measured = textWidth ?: FloatArray(n) { textPaint.measureText(items[it]) }.also { textWidth = it }
        cellX = FloatArray(n)
        cellY = FloatArray(n)
        cellW = FloatArray(n)
        cellH = FloatArray(n)
        val pad = PAD_DP * density
        val minW = MIN_CELL_DP * density
        val rowH = if (twoRows && height > 0) height / 2f else ROW_DP * density
        if (twoRows) {
            val ends = floatArrayOf(0f, 0f)
            for (i in 0 until n) {
                val row = if (ends[1] < ends[0]) 1 else 0
                val w = max(minW, measured[i] + 2 * pad)
                cellX[i] = ends[row]
                cellY[i] = row * rowH
                cellW[i] = w
                cellH[i] = rowH
                ends[row] += w
            }
            contentExtent = max(ends[0], ends[1])
            return
        }
        val full = width.toFloat()
        val lineH = textPaint.fontMetrics.let { it.descent - it.ascent }
        var x = 0f
        var top = 0f
        var rowHeight = rowH
        var rowStart = 0
        for (i in 0 until n) {
            val text = measured[i]
            val w = min(full, max(minW, text + 2 * pad))
            if (x > 0f && x + w > full) {
                for (k in rowStart until i) cellH[k] = rowHeight
                top += rowHeight
                x = 0f
                rowHeight = rowH
                rowStart = i
            }
            cellX[i] = x
            cellY[i] = top
            cellW[i] = w
            if (text + 2 * pad > full) rowHeight = max(rowH, linesFor(text, full - 2 * pad) * lineH + 2 * pad)
            x += w
        }
        for (k in rowStart until n) cellH[k] = rowHeight
        contentExtent = top + if (n > 0) rowHeight else 0f
    }

    private fun linesFor(text: Float, available: Float) = max(1, ceil(text / available).toInt())

    /** Items intersecting the viewport; only these are drawn. */
    internal fun visibleItems(): IntRange {
        ensureLayout()
        if (items.isEmpty() || width == 0 || height == 0) return IntRange.EMPTY
        if (twoRows) {
            val first = items.indices.firstOrNull { cellX[it] + cellW[it] > scrollX } ?: return IntRange.EMPTY
            val last = items.indices.last { cellX[it] < scrollX + width }
            return first..last
        }
        // First item whose row ends below the top edge, last whose row starts above the bottom edge.
        var low = 0
        var high = items.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (cellY[mid] + cellH[mid] > scrollY) high = mid else low = mid + 1
        }
        val first = low
        high = items.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (cellY[mid] < scrollY + height) low = mid + 1 else high = mid
        }
        return first until low
    }

    /** Index of the candidate at view coordinates (relative to the viewport), or -1. */
    internal fun indexAt(x: Float, y: Float): Int {
        if (x < 0 || x >= width || y < 0 || y >= height) return -1
        val cx = x + scrollX
        val cy = y + scrollY
        for (i in visibleItems()) {
            if (cx >= cellX[i] && cx < cellX[i] + cellW[i] && cy >= cellY[i] && cy < cellY[i] + cellH[i]) return i
        }
        return -1
    }

    /** Center of candidate [index] in view coordinates (for the JVM UI tests); it may lie outside the viewport. */
    internal fun centerOf(index: Int): Pair<Float, Float> {
        ensureLayout()
        return (cellX[index] + cellW[index] / 2 - scrollX) to (cellY[index] + cellH[index] / 2 - scrollY)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wanted = (2 * ROW_DP * density).toInt()
        setMeasuredDimension(
            resolveSize(resources.displayMetrics.widthPixels, widthMeasureSpec),
            resolveSize(wanted, heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        laidOutFor = -1
        ensureLayout()
        moveTo(offset)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (restoreOffset < 0) return
        ensureLayout()
        moveTo(restoreOffset)
        restoreOffset = -1
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(colors.background)
        if (items.isEmpty()) {
            textPaint.color = colors.hint
            textPaint.textSize = HINT_SP * resources.displayMetrics.scaledDensity
            val y = height / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(emptyLabel, PAD_DP * density + scrollX, y, textPaint)
            textPaint.textSize = TEXT_SP * resources.displayMetrics.scaledDensity
            return
        }
        val pad = PAD_DP * density
        for (i in visibleItems()) {
            cell.set(cellX[i], cellY[i], cellX[i] + cellW[i], cellY[i] + cellH[i])
            if (i == marked || (i == pressed && !moved)) {
                cell.inset(density, density)
                fill.color = if (i == marked) colors.shown else colors.pressed
                canvas.drawRoundRect(cell, 6f * density, 6f * density, fill)
            }
            textPaint.color = when (i) {
                marked -> colors.onShown
                primary -> colors.primary
                else -> colors.text
            }
            val text = items[i]
            val available = cellW[i] - 2 * pad
            if (!twoRows && textPaint.measureText(text) > available) {
                val layout = layoutFor(i, available.toInt())
                canvas.save()
                canvas.translate(cellX[i] + pad, cellY[i] + (cellH[i] - layout.height) / 2f)
                layout.draw(canvas)
                canvas.restore()
            } else {
                val y = cellY[i] + cellH[i] / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
                val x = cellX[i] + (cellW[i] - textPaint.measureText(text)) / 2f
                canvas.drawText(text, x, y, textPaint)
            }
        }
        drawIndicator(canvas)
    }

    private fun layoutFor(index: Int, width: Int): StaticLayout = wrapped.getOrPut(index) {
        val text = items[index]
        StaticLayout.Builder.obtain(text, 0, text.length, textPaint, max(1, width))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(linesFor(textPaint.measureText(text), width.toFloat()) + 1)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    /** Thin position indicator along the far edge, only when there is more to scroll. */
    private fun drawIndicator(canvas: Canvas) {
        val range = maxScroll
        if (range == 0) return
        val track = viewport.toFloat()
        val thumb = max(24f * density, track * track / (range + track))
        val along = offset + (track - thumb) * offset / range
        fill.color = colors.scrollbar
        if (twoRows) cell.set(along, height - 3f * density, along + thumb, height - density)
        else cell.set(width - 4f * density, along, width - density, along + thumb)
        canvas.drawRoundRect(cell, 2f * density, 2f * density, fill)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            moveTo(if (twoRows) scroller.currX else scroller.currY)
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
                last = if (twoRows) event.x else event.y
                pressed = indexAt(event.x, event.y)
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                invalidate()
            }
            // A second finger never picks: the gesture can only scroll from here on.
            MotionEvent.ACTION_POINTER_DOWN -> markMoved()
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index < 0) return true
                velocity?.addMovement(event)
                val x = event.getX(index)
                val y = event.getY(index)
                if (!moved) {
                    val dx = abs(x - downX)
                    val dy = abs(y - downY)
                    if (dx > slop || dy > slop) {
                        scrolling = if (twoRows) dx >= dy else dy >= dx
                        markMoved()
                    }
                }
                val along = if (twoRows) x else y
                if (scrolling) moveTo(offset + (last - along).toInt())
                last = along
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointerId) reset()
            MotionEvent.ACTION_UP -> {
                val index = event.findPointerIndex(pointerId)
                val tapped = pressed
                val wasMoved = moved
                if (index >= 0 && scrolling) fling(event)
                val hit = if (index >= 0) indexAt(event.getX(index), event.getY(index)) else -1
                reset()
                if (!wasMoved && tapped >= 0 && hit == tapped) {
                    clicked = tapped
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> reset()
        }
        return true
    }

    private fun fling(event: MotionEvent) {
        val v = velocity ?: return
        v.addMovement(event)
        v.computeCurrentVelocity(1000, maxFling)
        val speed = if (twoRows) v.getXVelocity(pointerId) else v.getYVelocity(pointerId)
        if (abs(speed) < minFling) return
        if (twoRows) scroller.fling(scrollX, 0, -speed.toInt(), 0, 0, maxScroll, 0, 0)
        else scroller.fling(0, scrollY, 0, -speed.toInt(), 0, 0, 0, maxScroll)
        postInvalidateOnAnimation()
    }

    /** Index of the tap being committed; a click without a tap (e.g. from accessibility) picks nothing. */
    private var clicked = -1

    override fun performClick(): Boolean {
        super.performClick()
        val i = clicked
        clicked = -1
        if (i !in items.indices) return false
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onPick?.invoke(i)
        return true
    }

    /** Drops a press or drag in progress without picking (panel closed, list replaced, editor changed). */
    fun cancelGesture() {
        scroller.forceFinished(true)
        reset()
    }

    private fun markMoved() {
        moved = true
        pressed = -1
        invalidate()
    }

    private fun reset() {
        pressed = -1
        moved = false
        scrolling = false
        pointerId = -1
        velocity?.recycle()
        velocity = null
        invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelGesture()
        super.onDetachedFromWindow()
    }

    internal companion object {
        const val ROW_DP = 44f
        const val MIN_CELL_DP = 48f
        const val PAD_DP = 14f
        const val TEXT_SP = 18f
        const val HINT_SP = 13f
        const val WRAP_CACHE = 32
    }
}
