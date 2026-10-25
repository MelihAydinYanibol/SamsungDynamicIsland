package io.github.melihaydinyanibol.dynamicisland.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/** Small circular progress indicator for downloads, uploads and other progress notifications. */
class ProgressRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x33FFFFFF
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val bounds = RectF()
    private var sweepAngle = 0f

    private val spinner = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            sweepAngle = it.animatedValue as Float
            invalidate()
        }
    }

    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var indeterminate: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            updateSpinner()
        }

    var color: Int
        get() = arc.color
        set(value) {
            arc.color = value
            invalidate()
        }

    private fun updateSpinner() {
        if (indeterminate && isAttachedToWindow) {
            if (!spinner.isStarted) spinner.start()
        } else {
            spinner.cancel()
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateSpinner()
    }

    override fun onDetachedFromWindow() {
        spinner.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = width.coerceAtMost(height) * 0.14f
        track.strokeWidth = stroke
        arc.strokeWidth = stroke
        val inset = stroke / 2 + 1
        bounds.set(inset, inset, width - inset, height - inset)
        canvas.drawOval(bounds, track)
        if (indeterminate) {
            canvas.drawArc(bounds, sweepAngle - 90f, 90f, false, arc)
        } else {
            canvas.drawArc(bounds, -90f, 360f * progress, false, arc)
        }
    }
}
