package io.github.melihaydinyanibol.dynamicisland.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Animated "now playing" bars shown next to the camera while music plays. */
class EqualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var phase = 0f

    private val animator = ValueAnimator.ofFloat(0f, (2 * PI).toFloat()).apply {
        duration = 1400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    var color: Int = 0xFFFFFFFF.toInt()
        set(value) {
            field = value
            paint.color = value
            invalidate()
        }

    var playing: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            updateAnimation()
            invalidate()
        }

    init {
        paint.color = color
    }

    private fun updateAnimation() {
        if (playing && isAttachedToWindow && visibility == VISIBLE) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val gap = w / (BARS * 2 + 1)
        val barWidth = gap * 1.4f
        val radius = barWidth / 2
        val totalWidth = BARS * barWidth + (BARS - 1) * gap * 0.6f
        var x = (w - totalWidth) / 2
        for (i in 0 until BARS) {
            val level = if (playing) 0.25f + 0.75f * abs(sin(phase + i * 1.1f)) else 0.25f
            val barHeight = (h * level).coerceAtLeast(barWidth)
            val top = (h - barHeight) / 2
            canvas.drawRoundRect(x, top, x + barWidth, top + barHeight, radius, radius, paint)
            x += barWidth + gap * 0.6f
        }
    }

    private companion object {
        const val BARS = 4
    }
}
