package io.github.melihaydinyanibol.dynamicisland.util

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.util.TypedValue

private const val TAG = "DynamicIsland"

fun Context.dp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

fun Context.dpInt(value: Int): Int = dp(value.toFloat()).toInt()

/** Sends a PendingIntent from the overlay, opting in to starting activities from the background. */
fun PendingIntent.sendFromOverlay(context: Context): Boolean = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        @Suppress("DEPRECATION")
        val options = ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
        send(context, 0, null, null, null, null, options.toBundle())
    } else {
        send()
    }
    true
} catch (e: PendingIntent.CanceledException) {
    Log.w(TAG, "PendingIntent was cancelled", e)
    false
}

/** Launches an app's main activity. */
fun Context.launchPackage(packageName: String): Boolean {
    val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    return try {
        startActivity(intent)
        true
    } catch (e: RuntimeException) {
        Log.w(TAG, "Could not launch $packageName", e)
        false
    }
}

fun Context.appIcon(packageName: String): Drawable? = try {
    packageManager.getApplicationIcon(packageName)
} catch (e: PackageManager.NameNotFoundException) {
    null
}

fun Context.appLabel(packageName: String): CharSequence? = try {
    packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0))
} catch (e: PackageManager.NameNotFoundException) {
    null
}

/**
 * Average colour of a bitmap, lifted so it reads well on the black island.
 * Returns null if the bitmap cannot be sampled.
 */
fun Bitmap.vibrantAverageColor(): Int? = try {
    val source = if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this
    val pixel = Bitmap.createScaledBitmap(source, 1, 1, true).getPixel(0, 0)
    val hsv = FloatArray(3)
    Color.colorToHSV(pixel, hsv)
    hsv[1] = (hsv[1] * 1.2f).coerceIn(0f, 1f)
    hsv[2] = hsv[2].coerceAtLeast(0.75f)
    Color.HSVToColor(hsv)
} catch (e: RuntimeException) {
    null
}
