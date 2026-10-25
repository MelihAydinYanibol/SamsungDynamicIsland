package io.github.melihaydinyanibol.dynamicisland.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import io.github.melihaydinyanibol.dynamicisland.model.IslandActivity
import io.github.melihaydinyanibol.dynamicisland.model.IslandUiState
import io.github.melihaydinyanibol.dynamicisland.util.dp
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Window content: keeps the [island] centred on the camera hole (so it can shrink into it) and
 * shows a second live activity as a small detached bubble, like iOS does with two activities.
 */
@SuppressLint("ViewConstructor")
class IslandRootView(context: Context) : ViewGroup(context) {

    val island = IslandView(context)

    private val bubble = ImageView(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.BLACK)
        }
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        clipToOutline = true
        visibility = GONE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private var bubbleProgress = 0f
    private var bubbleAnimator: ValueAnimator? = null
    private var secondaryKey: String? = null
    private val gap get() = context.dp(6f)

    init {
        addView(island)
        addView(bubble)
        clipChildren = false
        island.onShownChanged = { shown -> visibility = if (shown) VISIBLE else GONE }
        visibility = GONE

        bubble.setOnClickListener {
            val key = secondaryKey ?: return@setOnClickListener
            if (island.hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            island.callbacks?.onSecondaryTap(key)
        }
    }

    fun setGeometry(geometry: IslandGeometry) {
        island.setGeometry(geometry)
        requestLayout()
    }

    fun render(state: IslandUiState) {
        island.render(state)
        bindSecondary(state.secondary)
    }

    private fun bindSecondary(secondary: IslandActivity?) {
        secondaryKey = secondary?.key
        if (secondary != null) {
            val picture = secondary.image ?: secondary.icon
            bubble.setImageDrawable(picture)
            bubble.imageTintList =
                if (secondary.image == null && secondary.tintIcon) ColorStateList.valueOf(secondary.accentColor) else null
            val inset = if (secondary.image != null) 0 else (island.geometry.idleHeight * 0.22f).roundToInt()
            bubble.setPadding(inset, inset, inset, inset)
            bubble.scaleType = if (secondary.image != null) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        }
        animateBubble(if (secondary != null) 1f else 0f)
    }

    private fun animateBubble(to: Float) {
        if (bubbleAnimator?.isRunning != true && bubbleProgress == to) return
        bubbleAnimator?.cancel()
        if (to > 0f) bubble.visibility = VISIBLE
        val spring = if (to > bubbleProgress) Spring.EXPAND else Spring.COLLAPSE
        val from = bubbleProgress
        bubbleAnimator = ValueAnimator.ofFloat(0f, spring.settleSeconds).apply {
            duration = (spring.settleSeconds * 1000).toLong()
            interpolator = LinearInterpolator()
            startDelay = if (to > from) BUBBLE_DELAY_MS else 0
            addUpdateListener {
                val f = spring.value(it.animatedValue as Float)
                bubbleProgress = from + (to - from) * f
                if (to == 0f && it.animatedFraction >= 1f) bubble.visibility = GONE
                requestLayout()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        island.measure(unspecified, unspecified)
        val size = island.geometry.idleHeight
        bubble.measure(
            MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY),
        )
        val p = bubbleProgress.coerceAtLeast(0f)
        val side = ((gap + size) * p).roundToInt()
        val width = island.measuredWidth + 2 * side
        val height = max(island.geometry.idleHeight, islandTop() + island.measuredHeight)
        setMeasuredDimension(max(1, width), max(1, height))
    }

    /** Below idle height the pill stays vertically centred on the camera hole. */
    private fun islandTop(): Int = max(0, (island.geometry.idleHeight - island.measuredHeight) / 2)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val left = (width - island.measuredWidth) / 2
        val top = islandTop()
        island.layout(left, top, left + island.measuredWidth, top + island.measuredHeight)

        val p = bubbleProgress.coerceAtLeast(0f)
        val bubbleLeft = left + island.measuredWidth + (gap * p).roundToInt()
        bubble.layout(bubbleLeft, 0, bubbleLeft + bubble.measuredWidth, bubble.measuredHeight)
        bubble.pivotX = 0f
        bubble.pivotY = bubble.measuredHeight / 2f
        bubble.scaleX = p.coerceIn(0f, 1.2f)
        bubble.scaleY = p.coerceIn(0f, 1.2f)
        bubble.alpha = p.coerceIn(0f, 1f)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            island.callbacks?.onOutsideTouch()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDetachedFromWindow() {
        bubbleAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val BUBBLE_DELAY_MS = 120L
    }
}
