package dev.pocketwheel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.roundToInt

/** One owning pointer per pedal. Android's split event dispatch permits both thumbs at once. */
class PedalView(context: Context, private val title: String, private val accent: Int, private val onValue: (Double) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val track = RectF()
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    var value = 0.0
        private set
    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity

    init {
        isFocusable = true
        isClickable = true
        contentDescription = "$title pedal. Slide up to increase, release to return to zero."
        minimumWidth = (96 * density).roundToInt()
        minimumHeight = (144 * density).roundToInt()
    }

    fun release() { pointerId = MotionEvent.INVALID_POINTER_ID; change(0.0); isPressed = false }
    private fun change(next: Double) {
        value = next.coerceIn(0.0, 1.0)
        onValue(value)
        invalidate()
    }
    private fun at(y: Float): Double {
        val top = 58f * density
        val bottom = height - 24f * density
        return if (bottom > top) ((bottom - y) / (bottom - top)).toDouble().coerceIn(0.0, 1.0) else 0.0
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) { release(); return false }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(0)
                isPressed = true
                parent.requestDisallowInterceptTouchEvent(true)
                change(at(event.getY(0)))
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index >= 0) change(at(event.getY(index))) else release()
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointerId) release()
            MotionEvent.ACTION_UP -> { release(); performClick() }
            MotionEvent.ACTION_CANCEL -> release()
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val alpha = if (isEnabled) 255 else 125
        paint.color = context.getColor(R.color.surface_variant)
        paint.alpha = 255
        track.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(track, 12f * density, 12f * density, paint)
        val bottom = height - 24f * density
        val top = 58f * density
        paint.color = accent
        paint.alpha = if (isEnabled) 48 else 20
        track.set(16f * density, top, width - 16f * density, bottom)
        canvas.drawRoundRect(track, 8f * density, 8f * density, paint)
        paint.alpha = alpha
        val fillY = (bottom - (bottom - top) * value).toFloat()
        if (value > 0) {
            canvas.save()
            canvas.clipRect(track.left, fillY, track.right, bottom)
            canvas.drawRoundRect(track, 8f * density, 8f * density, paint)
            canvas.restore()
        }
        paint.strokeWidth = 3f * density
        canvas.drawLine(track.left + 8f * density, fillY, track.right - 8f * density, fillY, paint)
        paint.color = context.getColor(R.color.on_surface)
        paint.alpha = alpha
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        paint.textSize = 18f * scaled
        canvas.drawText(title, width / 2f, 25f * density, paint)
        paint.textSize = 14f * scaled
        canvas.drawText("${(value * 100).roundToInt()}%", width / 2f, 46f * density, paint)
        paint.textSize = 11f * scaled
        canvas.drawText(if (isEnabled) "HOLD + SLIDE" else "ARM TO USE", width / 2f, height - 7f * density, paint)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, 100f, (value * 100).toFloat())
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (isEnabled && action in listOf(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            change(value + if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) 0.1 else -0.1)
            // Accessibility actions have no pointer-up; apply only a short pulse.
            removeCallbacks(accessibilityRelease)
            postDelayed(accessibilityRelease, 250)
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }
    private val accessibilityRelease = Runnable { release() }
    override fun onDetachedFromWindow() { removeCallbacks(accessibilityRelease); release(); super.onDetachedFromWindow() }
}
