package io.github.melihaydinyanibol.dynamicisland.device

import kotlin.math.roundToInt

/** Position of the front camera punch-hole, in pixels of the current display. */
data class CutoutSpec(
    val centerX: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

/**
 * Per-device tuning. The live [android.view.DisplayCutout] is always preferred; these values are
 * a fallback for when the system does not report a cutout (e.g. some launchers or in landscape),
 * plus small corrections so the pill sits perfectly concentric with the lens.
 *
 * Both target phones use a 6.7" 1080 × 2340 panel with a centred Infinity-O punch-hole.
 */
enum class DeviceProfile(
    val displayName: String,
    private val modelPrefixes: List<String>,
    /** Fallback hole geometry measured on a 1080 px wide panel. */
    private val holeCenterXFraction: Float,
    private val holeTopPx: Int,
    private val holeDiameterPx: Int,
    /** Extra ring (dp) around the hole for the idle pill. */
    val idlePaddingDp: Int,
) {
    GALAXY_S25_FE(
        displayName = "Galaxy S25 FE",
        modelPrefixes = listOf("SM-S731"),
        holeCenterXFraction = 0.5f,
        holeTopPx = 30,
        holeDiameterPx = 82,
        idlePaddingDp = 6,
    ),
    GALAXY_A56(
        displayName = "Galaxy A56 5G",
        modelPrefixes = listOf("SM-A566"),
        holeCenterXFraction = 0.5f,
        holeTopPx = 32,
        holeDiameterPx = 86,
        idlePaddingDp = 6,
    ),
    GENERIC(
        displayName = "Android device",
        modelPrefixes = emptyList(),
        holeCenterXFraction = 0.5f,
        holeTopPx = 28,
        holeDiameterPx = 80,
        idlePaddingDp = 6,
    ),
    ;

    val isTuned: Boolean get() = this != GENERIC

    fun fallbackCutout(displayWidthPx: Int): CutoutSpec {
        val scale = displayWidthPx / REFERENCE_WIDTH_PX.toFloat()
        val diameter = (holeDiameterPx * scale).roundToInt()
        return CutoutSpec(
            centerX = (displayWidthPx * holeCenterXFraction).roundToInt(),
            top = (holeTopPx * scale).roundToInt(),
            width = diameter,
            height = diameter,
        )
    }

    companion object {
        private const val REFERENCE_WIDTH_PX = 1080

        /** Matches a `Build.MODEL` such as `SM-S731B` or `SM-A566E/DS`. */
        fun detect(model: String?): DeviceProfile {
            val normalized = model?.trim()?.uppercase() ?: return GENERIC
            return entries.firstOrNull { profile ->
                profile.modelPrefixes.any { normalized.startsWith(it) }
            } ?: GENERIC
        }
    }
}
