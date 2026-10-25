package io.github.melihaydinyanibol.dynamicisland.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.Chronometer
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import io.github.melihaydinyanibol.dynamicisland.R
import io.github.melihaydinyanibol.dynamicisland.model.ActionRole
import io.github.melihaydinyanibol.dynamicisland.model.IslandAction
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
    /** Size of the camera punch-hole the island sinks into. */
    val holeWidth: Int,
    val holeHeight: Int,
    /** When idle, shrink into the camera and disappear instead of resting as a pill. */
    val vanishWhenIdle: Boolean,
)

/**
 * The black pill drawn around the camera. It morphs between its shapes with spring physics,
 * rises out of / sinks back into the camera hole, and hosts the content of the activity on screen.
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
        fun onAction(action: IslandAction)
        fun onSecondaryTap(key: String)
    }

    var callbacks: Callbacks? = null
    var hapticsEnabled: Boolean = true

    /** Called when the island appears from, or has fully disappeared into, the camera. */
    var onShownChanged: ((Boolean) -> Unit)? = null

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

    var geometry = IslandGeometry(
        idleWidth = context.dpInt(96),
        idleHeight = context.dpInt(34),
        compactWidth = context.dpInt(230),
        expandedWidth = context.dpInt(360),
        expandedCorner = context.dp(40f),
        holeWidth = context.dpInt(26),
        holeHeight = context.dpInt(26),
        vanishWhenIdle = true,
    )
        private set

    /** Current animated pill size. */
    var pillWidth = 0f
        private set
    var pillHeight = 0f
        private set
    private var pillRadius = 0f

    private var targetW = -1f
    private var targetH = -1f
    private var targetR = -1f
    private var morph: ValueAnimator? = null

    private var mode = IslandMode.HIDDEN
    private var bound: IslandActivity? = null
    private var userSeeking = false
    private var shown = false
    private var sinking = false
    private var privacyColor: Int? = null

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowMatrix = Matrix()
    private var glowColor = 0
    private var glowAlpha = 0f
    private var glowAnimator: ValueAnimator? = null
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

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
        dotPaint.style = Paint.Style.FILL

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
            haptic(HapticFeedbackConstants.KEYBOARD_TAP)
            bound?.media?.controls?.previous()
        }
        findViewById<View>(R.id.exp_next).setOnClickListener {
            interaction()
            haptic(HapticFeedbackConstants.KEYBOARD_TAP)
            bound?.media?.controls?.next()
        }
        expPlay.setOnClickListener {
            interaction()
            haptic(HapticFeedbackConstants.KEYBOARD_TAP)
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
        expAppName.maxWidth = max(
            context.dpInt(40),
            (newGeometry.expandedWidth - newGeometry.idleWidth) / 2 - context.dpInt(48),
        )
        if (visibility == VISIBLE) {
            val (w, h, r) = targetFor(mode)
            morphTo(w, h, r)
        }
        requestLayout()
    }

    /** Camera / microphone in use: a coloured dot, and the idle pill stays out of the camera. */
    fun setPrivacyIndicator(color: Int?) {
        if (privacyColor == color) return
        privacyColor = color
        dotPaint.color = color ?: 0
        if (mode == IslandMode.IDLE) render(IslandUiState(IslandMode.IDLE)) else invalidate()
    }

    fun render(state: IslandUiState) {
        val previousMode = mode
        val previousKey = bound?.key
        mode = state.mode

        if (state.mode == IslandMode.HIDDEN) {
            hideContent()
            if (visibility == VISIBLE) sinkIntoCamera() else bound = null
            return
        }

        val (w, h, r) = targetFor(state.mode)
        val vanishing = isVanishingTarget(state.mode)

        if (!vanishing) appear()
        if (visibility != VISIBLE) return

        bound = state.activity ?: bound
        state.activity?.let(::bind)
        morphTo(w, h, r)

        val contentChanged = previousMode != state.mode || previousKey != state.activity?.key
        if (contentChanged) {
            if (state.mode == IslandMode.COMPACT) reveal(compact) else conceal(compact)
            if (state.mode == IslandMode.EXPANDED) reveal(expanded) else conceal(expanded)
            animateGlow(if (state.mode == IslandMode.EXPANDED && glowColor != 0) 1f else 0f)
            if (state.mode == IslandMode.EXPANDED) haptic(HapticFeedbackConstants.CONTEXT_CLICK)
        }
        updateDynamicContent()
    }

    private fun isVanishingTarget(mode: IslandMode) =
        mode == IslandMode.HIDDEN || (mode == IslandMode.IDLE && geometry.vanishWhenIdle && privacyColor == null)

    /** Make the island visible, rising out of the camera hole if it was hidden. */
    private fun appear() {
        sinking = false
        animate().cancel()
        alpha = 1f
        if (visibility != VISIBLE) {
            pillWidth = geometry.holeWidth.toFloat()
            pillHeight = geometry.holeHeight.toFloat()
            pillRadius = min(pillWidth, pillHeight) / 2f
            targetW = -1f
            applyShape()
            visibility = VISIBLE
        }
        if (!shown) {
            shown = true
            onShownChanged?.invoke(true)
        }
    }

    private fun sinkIntoCamera() {
        val holeW = geometry.holeWidth.toFloat()
        val holeH = geometry.holeHeight.toFloat()
        morphTo(holeW, holeH, min(holeW, holeH) / 2f)
    }

    /** Called when a morph finishes; completes a vanish with a short fade. */
    private fun onMorphEnd() {
        if (!isVanishingTarget(mode) || sinking) return
        sinking = true
        animate().alpha(0f).setDuration(VANISH_FADE_MS).setInterpolator(LinearInterpolator())
            .withEndAction {
                if (!sinking) return@withEndAction
                visibility = GONE
                bound = null
                stopDynamicContent()
                shown = false
                onShownChanged?.invoke(false)
            }
            .start()
    }

    private fun targetFor(mode: IslandMode): Triple<Float, Float, Float> {
        val idleH = geometry.idleHeight.toFloat()
        return when {
            isVanishingTarget(mode) -> {
                val w = geometry.holeWidth.toFloat()
                val h = geometry.holeHeight.toFloat()
                Triple(w, h, min(w, h) / 2f)
            }
            mode == IslandMode.IDLE -> Triple(geometry.idleWidth.toFloat(), idleH, idleH / 2f)
            mode == IslandMode.COMPACT -> Triple(geometry.compactWidth.toFloat(), idleH, idleH / 2f)
            else -> {
                expanded.measure(exactly(geometry.expandedWidth), UNSPECIFIED_SPEC)
                Triple(
                    geometry.expandedWidth.toFloat(),
                    max(expanded.measuredHeight, geometry.idleHeight * 2).toFloat(),
                    geometry.expandedCorner,
                )
            }
        }
    }

    private fun morphTo(w: Float, h: Float, r: Float) {
        if (w == targetW && h == targetH && r == targetR) return
        targetW = w
        targetH = h
        targetR = r
        morph?.cancel()

        val startW = pillWidth
        val startH = pillHeight
        val startR = pillRadius
        val growing = w * h > startW * startH
        val spring = when {
            w <= geometry.holeWidth + 1 -> Spring.CAMERA
            growing -> Spring.EXPAND
            else -> Spring.COLLAPSE
        }
        // Opening widens first then drops down; closing lifts up first then narrows.
        val tall = max(h, startH) > geometry.idleHeight * 1.5f
        val lagW = if (!growing && tall) STAGGER_S else 0f
        val lagH = if (growing && tall) STAGGER_S else 0f
        val total = spring.settleSeconds + max(lagW, lagH)

        morph = ValueAnimator.ofFloat(0f, total).apply {
            duration = (total * 1000).toLong()
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                val fw = spring.value(t - lagW)
                val fh = spring.value(t - lagH)
                pillWidth = max(1f, startW + (w - startW) * fw)
                pillHeight = max(1f, startH + (h - startH) * fh)
                pillRadius = max(0f, startR + (r - startR) * fh)
                applyShape()
                requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    pillWidth = w
                    pillHeight = h
                    pillRadius = r
                    applyShape()
                    requestLayout()
                    onMorphEnd()
                }
            })
            start()
        }
    }

    private fun applyShape() {
        pillDrawable.cornerRadius = min(pillRadius, min(pillWidth, pillHeight) / 2f)
        invalidateOutline()
        invalidate()
    }

    // region Content transitions

    private fun reveal(view: View) {
        view.animate().cancel()
        view.visibility = VISIBLE
        view.alpha = 0f
        view.scaleX = REVEAL_SCALE
        view.scaleY = REVEAL_SCALE
        view.pivotX = view.measuredWidth / 2f
        view.pivotY = 0f
        view.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setStartDelay(REVEAL_DELAY_MS)
            .setDuration(REVEAL_MS)
            .setInterpolator(EASE_OUT)
            .start()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ValueAnimator.ofFloat(context.dp(14f), 0f).apply {
                startDelay = REVEAL_DELAY_MS
                duration = REVEAL_MS
                interpolator = EASE_OUT
                addUpdateListener {
                    val radius = it.animatedValue as Float
                    view.setRenderEffect(
                        if (radius < 0.5f) null else RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP),
                    )
                }
                start()
            }
        }
    }

    private fun conceal(view: View) {
        view.animate().cancel()
        if (view.visibility != VISIBLE) return
        view.animate().alpha(0f).setStartDelay(0).setDuration(CONCEAL_MS).setInterpolator(EASE_OUT)
            .withEndAction { view.visibility = INVISIBLE }
            .start()
    }

    private fun hideContent() {
        conceal(compact)
        conceal(expanded)
        animateGlow(0f)
    }

    private fun animateGlow(to: Float) {
        glowAnimator?.cancel()
        glowAnimator = ValueAnimator.ofFloat(glowAlpha, to).apply {
            duration = if (to > glowAlpha) 600 else 200
            addUpdateListener {
                glowAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    // endregion

    private fun bind(a: IslandActivity) {
        val accent = a.accentColor
        val iconTint = if (a.tintIcon) ColorStateList.valueOf(accent) else null
        updateGlow(if (accent == IslandActivity.DEFAULT_ACCENT) 0 else accent)

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

    private fun updateGlow(color: Int) {
        if (color == glowColor) return
        glowColor = color
        if (color == 0) {
            glowPaint.shader = null
            return
        }
        val radius = context.dp(GLOW_RADIUS_DP)
        glowPaint.shader = RadialGradient(
            0f, 0f, radius,
            intArrayOf(withAlpha(color, 0x55), withAlpha(color, 0x18), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private fun bindActions(a: IslandActivity) {
        expActions.removeAllViews()
        expActions.visibility = if (a.actions.isEmpty()) GONE else VISIBLE
        a.actions.forEachIndexed { index, action ->
            val button = TextView(context, null, 0, R.style.IslandButton).apply {
                text = action.title
                isClickable = true
                when (action.role) {
                    ActionRole.HANG_UP -> setBackgroundResource(R.drawable.bg_island_button_hang_up)
                    ActionRole.ANSWER -> setBackgroundResource(R.drawable.bg_island_button_answer)
                    ActionRole.NEUTRAL -> Unit
                }
                setOnClickListener {
                    interaction()
                    haptic(HapticFeedbackConstants.CONFIRM)
                    callbacks?.onAction(action)
                }
            }
            val params = LinearLayout.LayoutParams(0, context.dpInt(42), 1f).apply {
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

    override fun dispatchDraw(canvas: Canvas) {
        if (glowAlpha > 0.01f && glowPaint.shader != null) {
            // Soft colour bloom behind the artwork, like album-tinted cards on iOS.
            val left = (width - expanded.measuredWidth) / 2f + expImage.left + expImage.width / 2f
            val top = geometry.idleHeight + context.dp(36f)
            glowMatrix.setTranslate(left, top)
            glowPaint.shader.setLocalMatrix(glowMatrix)
            glowPaint.alpha = (255 * glowAlpha).roundToInt()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint)
        }
        super.dispatchDraw(canvas)
        val dot = privacyColor
        if (dot != null && (mode == IslandMode.IDLE || mode == IslandMode.COMPACT) && pillHeight > 0f) {
            val radius = context.dp(3.5f)
            canvas.drawCircle(width - pillHeight / 2f, height / 2f, radius, dotPaint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        compact.measure(exactly(geometry.compactWidth), exactly(geometry.idleHeight))
        expanded.measure(exactly(geometry.expandedWidth), UNSPECIFIED_SPEC)
        setMeasuredDimension(max(1, pillWidth.roundToInt()), max(1, pillHeight.roundToInt()))
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

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                interaction()
                pivotX = width / 2f
                animate().scaleX(PRESS_SCALE).scaleY(PRESS_SCALE).setDuration(140).setInterpolator(EASE_OUT).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(EASE_OUT).start()
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
        morph?.cancel()
        glowAnimator?.cancel()
        stopDynamicContent()
        super.onDetachedFromWindow()
    }

    private object CircleOutline : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }

    private companion object {
        const val STAGGER_S = 0.05f
        const val REVEAL_MS = 320L
        const val REVEAL_DELAY_MS = 110L
        const val CONCEAL_MS = 120L
        const val VANISH_FADE_MS = 140L
        const val REVEAL_SCALE = 0.92f
        const val TICK_MS = 500L
        const val PRESS_SCALE = 0.95f
        const val GLOW_RADIUS_DP = 220f
        val EASE_OUT = PathInterpolator(0.2f, 0f, 0f, 1f)
        val UNSPECIFIED_SPEC: Int = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

        fun exactly(size: Int): Int = MeasureSpec.makeMeasureSpec(max(0, size), MeasureSpec.EXACTLY)

        fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
    }
}
