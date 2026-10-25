package io.github.melihaydinyanibol.dynamicisland.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.View.MeasureSpec
import android.view.ViewOutlineProvider
import android.view.animation.OvershootInterpolator
import android.widget.Chronometer
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import io.github.melihaydinyanibol.dynamicisland.R
import io.github.melihaydinyanibol.dynamicisland.model.IslandActivity
import io.github.melihaydinyanibol.dynamicisland.model.IslandMode
import io.github.melihaydinyanibol.dynamicisland.model.IslandUiState
import io.github.melihaydinyanibol.dynamicisland.util.TimeFormat
import io.github.melihaydinyanibol.dynamicisland.util.dp
import io.github.melihaydinyanibol.dynamicisland.util.dpInt
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Pixel sizes of the island in each of its shapes. */
data class IslandGeometry(
    val idleWidth: Int,
    val idleHeight: Int,
    val compactWidth: Int,
    val expandedWidth: Int,
    val expandedCorner: Float,
)

/**
 * The black pill drawn around the camera. It morphs between idle, compact and expanded shapes
 * with a springy animation and hosts the content for the activity currently on screen.
 */
@SuppressLint("ViewConstructor")
class IslandView(context: Context) : ViewGroup(context) {

    enum class Swipe { UP, DOWN, LEFT, RIGHT }

    interface Callbacks {
        fun onTap()
        fun onLongPress()
        fun onSwipe(direction: Swipe)
        fun onOutsideTouch()
        fun onUserInteraction()
        fun onSendIntent(intent: PendingIntent)
    }

    var callbacks: Callbacks? = null
    var hapticsEnabled: Boolean = true

    private val pillDrawable = GradientDrawable().apply { setColor(Color.BLACK) }

    private val compact: View
    private val compactIcon: ImageView
    private val compactText: TextView
    private val compactChrono: Chronometer
    private val compactEq: EqualizerView
    private val compactRing: ProgressRingView

    private val expanded: View
    private val expHeader: View
    private val expAppIcon: ImageView
    private val expAppName: TextView
    private val expChrono: Chronometer
    private val expEq: EqualizerView
    private val expTrailing: TextView
    private val expImage: ImageView
    private val expTitle: TextView
    private val expSubtitle: TextView
    private val expProgress: ProgressBar
    private val expMedia: View
    private val expElapsed: TextView
    private val expSeek: SeekBar
    private val expRemaining: TextView
    private val expPlay: ImageButton
    private val expActions: LinearLayout

    private var geometry = IslandGeometry(
        idleWidth = context.dpInt(96),
        idleHeight = context.dpInt(36),
        compactWidth = context.dpInt(220),
        expandedWidth = context.dpInt(360),
        expandedCorner = context.dp(36f),
    )

    private var curW = 0f
    private var curH = 0f
    private var curR = 0f
    private var targetW = -1f
    private var targetH = -1f
    private var targetR = -1f
    private var animator: ValueAnimator? = null

    private var mode = IslandMode.HIDDEN
    private var bound: IslandActivity? = null
    private var userSeeking = false

    private val ticker = object : Runnable {
        override fun run() {
            updateMediaProgress()
            if (bound?.media?.isPlaying == true) postDelayed(this, TICK_MS)
        }
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            performClick()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            haptic(HapticFeedbackConstants.LONG_PRESS)
            callbacks?.onLongPress()
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            val start = e1 ?: return false
            val dx = e2.rawX - start.rawX
            val dy = e2.rawY - start.rawY
            val threshold = context.dp(24f)
            val direction = when {
                abs(dy) > abs(dx) && abs(dy) > threshold -> if (dy < 0) Swipe.UP else Swipe.DOWN
                abs(dx) > threshold -> if (dx < 0) Swipe.LEFT else Swipe.RIGHT
                else -> return false
            }
            haptic(HapticFeedbackConstants.CLOCK_TICK)
            callbacks?.onSwipe(direction)
            return true
        }
    })

    init {
        LayoutInflater.from(context).inflate(R.layout.view_island, this, true)
        compact = findViewById(R.id.compact)
        compactIcon = findViewById(R.id.compact_icon)
        compactText = findViewById(R.id.compact_text)
        compactChrono = findViewById(R.id.compact_chrono)
        compactEq = findViewById(R.id.compact_eq)
        compactRing = findViewById(R.id.compact_ring)
        expanded = findViewById(R.id.expanded)
        expHeader = findViewById(R.id.exp_header)
        expAppIcon = findViewById(R.id.exp_app_icon)
        expAppName = findViewById(R.id.exp_app_name)
        expChrono = findViewById(R.id.exp_chrono)
        expEq = findViewById(R.id.exp_eq)
        expTrailing = findViewById(R.id.exp_trailing)
        expImage = findViewById(R.id.exp_image)
        expTitle = findViewById(R.id.exp_title)
        expSubtitle = findViewById(R.id.exp_subtitle)
        expProgress = findViewById(R.id.exp_progress)
        expMedia = findViewById(R.id.exp_media)
        expElapsed = findViewById(R.id.exp_elapsed)
        expSeek = findViewById(R.id.exp_seek)
        expRemaining = findViewById(R.id.exp_remaining)
        expPlay = findViewById(R.id.exp_play)
        expActions = findViewById(R.id.exp_actions)

        background = pillDrawable
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        pivotY = 0f

        compactIcon.outlineProvider = CircleOutline
        compactIcon.clipToOutline = true
        expAppIcon.outlineProvider = CircleOutline
        expAppIcon.clipToOutline = true
        expImage.clipToOutline = true

        compact.visibility = INVISIBLE
        expanded.visibility = INVISIBLE
        visibility = GONE

        findViewById<View>(R.id.exp_prev).setOnClickListener {
            interaction()
            bound?.media?.controls?.previous()
        }
        findViewById<View>(R.id.exp_next).setOnClickListener {
            interaction()
            bound?.media?.controls?.next()
        }
        expPlay.setOnClickListener {
            interaction()
            bound?.media?.controls?.playPause()
        }
        expSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) expElapsed.text = TimeFormat.duration(progress.toLong())
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                userSeeking = true
                interaction()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                userSeeking = false
                bound?.media?.controls?.seekTo(seekBar.progress.toLong())
            }
        })
    }

    fun setGeometry(newGeometry: IslandGeometry) {
        geometry = newGeometry
        expHeader.layoutParams = expHeader.layoutParams.apply { height = newGeometry.idleHeight }
        val sideSpace = (newGeometry.compactWidth - newGeometry.idleWidth) / 2
        compactText.maxWidth = max(context.dpInt(24), sideSpace - context.dpInt(4))
        expAppName.maxWidth = max(context.dpInt(40), (newGeometry.expandedWidth - newGeometry.idleWidth) / 2 - context.dpInt(48))
        if (mode != IslandMode.HIDDEN) {
            val (w, h, r) = targetFor(mode)
            animateTo(w, h, r)
        }
        requestLayout()
    }

    fun render(state: IslandUiState) {
        val previousMode = mode
        val previousKey = bound?.key
        mode = state.mode

        if (state.mode == IslandMode.HIDDEN) {
            animator?.cancel()
            targetW = -1f
            visibility = GONE
            bound = null
            stopDynamicContent()
            return
        }

        if (visibility != VISIBLE) {
            visibility = VISIBLE
            curW = geometry.idleWidth.toFloat()
            curH = geometry.idleHeight.toFloat()
            curR = curH / 2f
            applyShape()
        }

        bound = state.activity
        state.activity?.let(::bind)

        val (w, h, r) = targetFor(state.mode)
        animateTo(w, h, r)

        val contentChanged = previousMode != state.mode || previousKey != state.activity?.key
        if (contentChanged) {
            showContent(compact, state.mode == IslandMode.COMPACT)
            showContent(expanded, state.mode == IslandMode.EXPANDED)
            if (state.mode == IslandMode.EXPANDED) haptic(HapticFeedbackConstants.CONTEXT_CLICK)
        }
        updateDynamicContent()
    }

    private fun targetFor(mode: IslandMode): Triple<Float, Float, Float> {
        val idleH = geometry.idleHeight.toFloat()
        return when (mode) {
            IslandMode.HIDDEN, IslandMode.IDLE -> Triple(geometry.idleWidth.toFloat(), idleH, idleH / 2f)
            IslandMode.COMPACT -> Triple(geometry.compactWidth.toFloat(), idleH, idleH / 2f)
            IslandMode.EXPANDED -> {
                expanded.measure(exactly(geometry.expandedWidth), UNSPECIFIED_SPEC)
                Triple(
                    geometry.expandedWidth.toFloat(),
                    max(expanded.measuredHeight, geometry.idleHeight * 2).toFloat(),
                    geometry.expandedCorner,
                )
            }
        }
    }

    private fun animateTo(w: Float, h: Float, r: Float) {
        if (w == targetW && h == targetH && r == targetR) return
        targetW = w
        targetH = h
        targetR = r
        animator?.cancel()
        val startW = curW
        val startH = curH
        val startR = curR
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ANIMATION_MS
            interpolator = OvershootInterpolator(0.9f)
            addUpdateListener {
                val f = it.animatedValue as Float
                curW = max(1f, startW + (w - startW) * f)
                curH = max(1f, startH + (h - startH) * f)
                curR = max(0f, startR + (r - startR) * f)
                applyShape()
                requestLayout()
            }
            start()
        }
    }

    private fun applyShape() {
        pillDrawable.cornerRadius = min(curR, min(curW, curH) / 2f)
        invalidateOutline()
    }

    private fun showContent(view: View, show: Boolean) {
        view.animate().cancel()
        if (show) {
            view.visibility = VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setStartDelay(FADE_DELAY_MS).setDuration(FADE_MS).start()
        } else {
            view.alpha = 0f
            view.visibility = INVISIBLE
        }
    }

    private fun bind(a: IslandActivity) {
        val accent = a.accentColor
        val iconTint = if (a.tintIcon) ColorStateList.valueOf(accent) else null

        // Compact pill.
        if (a.image != null) {
            compactIcon.setImageDrawable(a.image)
            compactIcon.imageTintList = null
        } else {
            compactIcon.setImageDrawable(a.icon)
            compactIcon.imageTintList = iconTint
        }
        compactText.visibility = GONE
        compactChrono.visibility = GONE
        compactEq.visibility = GONE
        compactRing.visibility = GONE
        when {
            a.media != null -> {
                compactEq.visibility = VISIBLE
                compactEq.color = accent
                compactEq.playing = a.media.isPlaying
            }
            a.chronometerWhen != null -> {
                compactChrono.visibility = VISIBLE
                compactChrono.setTextColor(accent)
                startChronometer(compactChrono, a)
            }
            a.hasProgress && a.compactText.isNullOrEmpty() -> {
                compactRing.visibility = VISIBLE
                compactRing.color = accent
                compactRing.indeterminate = a.progressIndeterminate
                compactRing.progress = a.progressFraction
            }
            !a.compactText.isNullOrEmpty() -> {
                compactText.visibility = VISIBLE
                compactText.text = a.compactText
                compactText.setTextColor(accent)
            }
        }
        if (compactChrono.visibility != VISIBLE) compactChrono.stop()

        // Expanded card header.
        expAppIcon.setImageDrawable(a.icon)
        expAppIcon.imageTintList = iconTint
        expAppName.text = a.appName ?: ""
        expChrono.visibility = GONE
        expEq.visibility = GONE
        expTrailing.visibility = GONE
        when {
            a.media != null -> {
                expEq.visibility = VISIBLE
                expEq.color = accent
                expEq.playing = a.media.isPlaying
            }
            a.chronometerWhen != null -> {
                expChrono.visibility = VISIBLE
                expChrono.setTextColor(accent)
                startChronometer(expChrono, a)
            }
            !a.compactText.isNullOrEmpty() -> {
                expTrailing.visibility = VISIBLE
                expTrailing.text = a.compactText
                expTrailing.setTextColor(accent)
            }
        }
        if (expChrono.visibility != VISIBLE) expChrono.stop()

        // Expanded card body.
        if (a.image != null) {
            expImage.setImageDrawable(a.image)
            expImage.imageTintList = null
        } else {
            expImage.setImageDrawable(a.icon)
            expImage.imageTintList = iconTint
        }
        expTitle.text = a.title
        expSubtitle.text = a.subtitle
        expSubtitle.visibility = if (a.subtitle.isNullOrBlank()) GONE else VISIBLE

        expProgress.visibility = if (a.hasProgress && a.media == null) VISIBLE else GONE
        expProgress.isIndeterminate = a.progressIndeterminate
        expProgress.max = max(1, a.progressMax)
        expProgress.progress = a.progress
        expProgress.progressTintList = ColorStateList.valueOf(accent)

        val media = a.media
        expMedia.visibility = if (media != null) VISIBLE else GONE
        if (media != null) {
            expPlay.setImageResource(if (media.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            expSeek.isEnabled = media.durationMs > 0
            expSeek.max = media.durationMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            expSeek.progressTintList = ColorStateList.valueOf(accent)
            expSeek.thumbTintList = ColorStateList.valueOf(accent)
            updateMediaProgress()
        }

        bindActions(a)
    }

    private fun bindActions(a: IslandActivity) {
        expActions.removeAllViews()
        expActions.visibility = if (a.actions.isEmpty()) GONE else VISIBLE
        a.actions.forEachIndexed { index, action ->
            val button = TextView(context, null, 0, R.style.IslandButton).apply {
                text = action.title
                isClickable = true
                setOnClickListener {
                    interaction()
                    action.intent?.let { callbacks?.onSendIntent(it) }
                }
            }
            val params = LinearLayout.LayoutParams(0, context.dpInt(40), 1f).apply {
                if (index > 0) marginStart = context.dpInt(8)
            }
            expActions.addView(button, params)
        }
    }

    private fun startChronometer(chronometer: Chronometer, a: IslandActivity) {
        val wallTime = a.chronometerWhen ?: return
        chronometer.base = SystemClock.elapsedRealtime() + (wallTime - System.currentTimeMillis())
        chronometer.isCountDown = a.countDown
        chronometer.start()
    }

    private fun updateMediaProgress() {
        val media = bound?.media ?: return
        if (userSeeking) return
        val position = media.positionAt(SystemClock.elapsedRealtime())
        expSeek.progress = position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        expElapsed.text = TimeFormat.duration(position)
        expRemaining.text = if (media.durationMs > 0) {
            "-" + TimeFormat.duration(media.durationMs - position)
        } else {
            ""
        }
    }

    private fun updateDynamicContent() {
        removeCallbacks(ticker)
        if (mode == IslandMode.EXPANDED && bound?.media != null) {
            updateMediaProgress()
            if (bound?.media?.isPlaying == true) postDelayed(ticker, TICK_MS)
        }
    }

    private fun stopDynamicContent() {
        removeCallbacks(ticker)
        compactChrono.stop()
        expChrono.stop()
        compactEq.playing = false
        expEq.playing = false
        compactRing.indeterminate = false
    }

    private fun interaction() {
        callbacks?.onUserInteraction()
    }

    private fun haptic(constant: Int) {
        if (hapticsEnabled) performHapticFeedback(constant)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        compact.measure(exactly(geometry.compactWidth), exactly(geometry.idleHeight))
        expanded.measure(exactly(geometry.expandedWidth), UNSPECIFIED_SPEC)
        setMeasuredDimension(max(1, curW.roundToInt()), max(1, curH.roundToInt()))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        layoutCentered(compact, width)
        layoutCentered(expanded, width)
    }

    private fun layoutCentered(child: View, parentWidth: Int) {
        val left = (parentWidth - child.measuredWidth) / 2
        child.layout(left, 0, left + child.measuredWidth, child.measuredHeight)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            callbacks?.onOutsideTouch()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                interaction()
                pivotX = width / 2f
                animate().scaleX(PRESS_SCALE).scaleY(PRESS_SCALE).setDuration(120).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                animate().scaleX(1f).scaleY(1f).setDuration(180).start()
        }
        gestures.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        callbacks?.onTap()
        return true
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        stopDynamicContent()
        super.onDetachedFromWindow()
    }

    private object CircleOutline : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }

    private companion object {
        const val ANIMATION_MS = 480L
        const val FADE_MS = 200L
        const val FADE_DELAY_MS = 140L
        const val TICK_MS = 500L
        const val PRESS_SCALE = 0.96f
        val UNSPECIFIED_SPEC: Int = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

        fun exactly(size: Int): Int = MeasureSpec.makeMeasureSpec(max(0, size), MeasureSpec.EXACTLY)
    }
}
