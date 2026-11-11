package com.kaydn.fastfetchandroid

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Paint
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.StatFs
import android.os.Trace
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.View

// ==================================================
// COMPAT
// ==================================================
//
// Every call whose API is newer than Android 4.1 (API 16), next to the
// fallback the legacy edition uses on older Android. In :app (minSdk 25)
// every version check here is true, so the normal app runs exactly the
// code it ran before. Lint on :legacy (NewApi) flags any new call that
// skips this file.

internal object Compat {

    // ==================================================
    // TRACING (API 18)
    // ==================================================

    fun beginTrace(name: String) {
        if (Build.VERSION.SDK_INT >= 18) {
            Trace.beginSection(name)
        }
    }

    fun endTrace() {
        if (Build.VERSION.SDK_INT >= 18) {
            Trace.endSection()
        }
    }

    // ==================================================
    // ACTIVITY AND VIEWS (API 17)
    // ==================================================

    /**
     * Below 4.2 there's no isDestroyed. Callers also check isFinishing,
     * and MainActivity drops refresh results from an old session anyway.
     */
    fun isDestroyed(activity: Activity): Boolean {
        return if (Build.VERSION.SDK_INT >= 17) {
            activity.isDestroyed
        } else {
            false
        }
    }

    // Android 4.1 has no right-to-left layout, so start is always left.

    fun paddingStart(view: View): Int {
        return if (Build.VERSION.SDK_INT >= 17) {
            view.paddingStart
        } else {
            view.paddingLeft
        }
    }

    fun paddingEnd(view: View): Int {
        return if (Build.VERSION.SDK_INT >= 17) {
            view.paddingEnd
        } else {
            view.paddingRight
        }
    }

    fun setPaddingRelative(
        view: View,
        start: Int,
        top: Int,
        end: Int,
        bottom: Int
    ) {
        if (Build.VERSION.SDK_INT >= 17) {
            view.setPaddingRelative(
                start,
                top,
                end,
                bottom
            )
        } else {
            view.setPadding(
                start,
                top,
                end,
                bottom
            )
        }
    }

    // ==================================================
    // DEVICE (API 21 / 23)
    // ==================================================

    @Suppress("DEPRECATION")
    fun supportedAbis(): Array<String> {
        if (Build.VERSION.SDK_INT >= 21) {
            return Build.SUPPORTED_ABIS
        }

        return listOf(
            Build.CPU_ABI,
            Build.CPU_ABI2
        )
            .filter { it.isNotBlank() && it != "unknown" }
            .distinct()
            .toTypedArray()
    }

    /**
     * Whether this app process is 64-bit, or null if unknown. Below 6.0
     * it asks the native code ("Process Bits"), which is only needed on
     * 5.x: there's no 64-bit Android before 5.0.
     */
    fun is64BitProcess(nativeProcessBits: () -> String?): Boolean? {
        if (Build.VERSION.SDK_INT >= 23) {
            return android.os.Process.is64Bit()
        }

        return when (nativeProcessBits()) {
            "64" -> true
            "32" -> false
            else -> if (Build.VERSION.SDK_INT < 21) false else null
        }
    }

    /** How many cameras actually exist (USB webcams included on 21+). */
    fun cameraCount(context: Context): Int {
        if (Build.VERSION.SDK_INT >= 21) {
            val cameraManager =
                context.getSystemService(
                    Context.CAMERA_SERVICE
                ) as CameraManager

            return cameraManager.cameraIdList.size
        }

        @Suppress("DEPRECATION")
        return android.hardware.Camera.getNumberOfCameras()
    }

    @Suppress("DEPRECATION")
    fun securityPatch(): String {
        return if (Build.VERSION.SDK_INT >= 23) {
            Build.VERSION.SECURITY_PATCH
        } else {
            // Monthly security patch levels started with Android 6.0.
            "Unavailable"
        }
    }

    // ==================================================
    // DISPLAY (API 17 / 23)
    // ==================================================

    class DisplaySize(
        val width: Int,
        val height: Int,
        val refreshRate: Float
    )

    /**
     * The display's resolution and refresh rate. 6.0+ reads the current
     * Display.Mode. Older Android reads the full screen size and turns it
     * to the display's natural orientation, the way Display.Mode reports
     * it (a phone in landscape still says 1080x1920).
     */
    @Suppress("DEPRECATION")
    fun displaySize(activity: Activity): DisplaySize {
        val display = activity.windowManager.defaultDisplay

        if (Build.VERSION.SDK_INT >= 23) {
            val displayMode = display.mode

            return DisplaySize(
                width = displayMode.physicalWidth,
                height = displayMode.physicalHeight,
                refreshRate = displayMode.refreshRate
            )
        }

        val (width, height) = fullScreenSize(display)

        val rotated =
            display.rotation == Surface.ROTATION_90 ||
                display.rotation == Surface.ROTATION_270

        return DisplaySize(
            width = if (rotated) height else width,
            height = if (rotated) width else height,
            refreshRate = display.refreshRate
        )
    }

    @Suppress("DEPRECATION")
    private fun fullScreenSize(display: Display): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 17) {
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)

            return metrics.widthPixels to metrics.heightPixels
        }

        // Android 4.1 only has the full size as a hidden method, which
        // apps may still call there. Without it, use the size minus the
        // system bars.
        return try {
            val rawWidth = Display::class.java.getMethod("getRawWidth").invoke(display) as Int
            val rawHeight = Display::class.java.getMethod("getRawHeight").invoke(display) as Int

            rawWidth to rawHeight
        } catch (_: Exception) {
            val metrics = DisplayMetrics()
            display.getMetrics(metrics)

            metrics.widthPixels to metrics.heightPixels
        }
    }

    // ==================================================
    // STORAGE (API 18)
    // ==================================================

    /** Total and available bytes of the filesystem holding [path]. */
    @Suppress("DEPRECATION")
    fun storageBytes(path: String): Pair<Long, Long> {
        val statFs = StatFs(path)

        if (Build.VERSION.SDK_INT >= 18) {
            return statFs.totalBytes to statFs.availableBytes
        }

        // Long before multiplying: block counts times block size overflow
        // an Int on anything over 2 GB.
        val blockSize = statFs.blockSize.toLong()

        return statFs.blockCount.toLong() * blockSize to
            statFs.availableBlocks.toLong() * blockSize
    }

    // ==================================================
    // LOCALE AND TEXT (API 21 / 24)
    // ==================================================

    /** The UI language as a tag, like "en-US". */
    @Suppress("DEPRECATION")
    fun localeTag(configuration: Configuration): String {
        if (Build.VERSION.SDK_INT >= 24) {
            return configuration.locales[0].toLanguageTag()
        }

        val locale = configuration.locale

        if (Build.VERSION.SDK_INT >= 21) {
            return locale.toLanguageTag()
        }

        return listOf(
            locale.language,
            locale.country,
            locale.variant
        )
            .filter { it.isNotEmpty() }
            .joinToString("-")
            .ifEmpty { "und" }
    }

    fun letterSpacing(paint: Paint): Float {
        return if (Build.VERSION.SDK_INT >= 21) {
            paint.letterSpacing
        } else {
            0f
        }
    }
}
