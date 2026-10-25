package io.github.melihaydinyanibol.dynamicisland.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A damped spring, described the way Apple's SwiftUI does it: [response] is the period of the
 * undamped oscillation in seconds and [damping] the damping ratio (1 = no bounce).
 */
data class Spring(val response: Float, val damping: Float) {

    private val omega = (2 * PI / response).toFloat()
    private val zeta = damping.coerceIn(0.05f, 0.999f)
    private val omegaD = omega * sqrt(1 - zeta * zeta)

    /** Time in seconds until the motion is within 0.2 % of its target. */
    val settleSeconds: Float = (ln(500f) / (zeta * omega))

    /** Position (0 → 1, possibly overshooting) at [t] seconds. */
    fun value(t: Float): Float {
        if (t <= 0f) return 0f
        val envelope = exp(-zeta * omega * t)
        return 1f - envelope * (cos(omegaD * t) + (zeta * omega / omegaD) * sin(omegaD * t))
    }

    companion object {
        /** Opening: lively with a small overshoot. */
        val EXPAND = Spring(response = 0.46f, damping = 0.72f)

        /** Closing: quicker and calmer. */
        val COLLAPSE = Spring(response = 0.38f, damping = 0.86f)

        /** Sinking into / rising out of the camera hole. */
        val CAMERA = Spring(response = 0.34f, damping = 0.9f)
    }
}
