package com.kaydn.fastfetchandroid

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.widget.Button
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ==================================================
// GEAR ICON
// ==================================================
//
// The settings button's label is the gear character. Android older than
// 6.0 has no font with that character and shows an empty box instead. So
// where the character is missing, the button gets a gear drawn in code.
// Where it exists (every device the normal app runs on, as far as tested),
// nothing changes.

internal object GearIcon {

    private const val GEAR = "⚙"

    /** Swaps the gear character for a drawn gear if the font lacks it. */
    fun applyIfNeeded(button: Button) {
        if (hasGearCharacter(button)) {
            return
        }

        // The gear draws the button's own background under itself. (A
        // LayerDrawable would do that too, but it crashes on Android 5.x
        // with a drawable that has no constant state, like this one.)
        val oldBackground = button.background

        val gear = GearDrawable(
            color = button.currentTextColor,
            sizePx = button.textSize,
            under = oldBackground
        )

        button.contentDescription = "Settings"
        button.text = ""
        button.background = gear

        // Setting a new background detaches the old one from the button.
        // It now redraws through the gear (press ripples and other
        // animated states).
        oldBackground?.callback = gear
    }

    private fun hasGearCharacter(button: Button): Boolean {
        // Paint.hasGlyph is API 23. Below that, no stock system font has
        // the gear, so draw it.
        return Build.VERSION.SDK_INT >= 23 &&
            button.paint.hasGlyph(GEAR)
    }

    // ==================================================
    // DRAWING
    // ==================================================

    /**
     * A gear with 8 teeth and a round hole, centered in its bounds, drawn
     * over [under] (the button's original background, if it had one).
     */
    private class GearDrawable(
        color: Int,
        private val sizePx: Float,
        private val under: Drawable?
    ) : Drawable(), Drawable.Callback {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }

        private val path = Path()

        override fun invalidateDrawable(who: Drawable) {
            invalidateSelf()
        }

        override fun scheduleDrawable(
            who: Drawable,
            what: Runnable,
            time: Long
        ) {
            scheduleSelf(what, time)
        }

        override fun unscheduleDrawable(
            who: Drawable,
            what: Runnable
        ) {
            unscheduleSelf(what)
        }

        override fun setHotspot(
            x: Float,
            y: Float
        ) {
            if (Build.VERSION.SDK_INT >= 21) {
                under?.setHotspot(x, y)
            }
        }

        override fun onBoundsChange(bounds: Rect) {
            under?.bounds = bounds
        }

        // Pressed and focused states still reach the original background.
        override fun isStateful(): Boolean {
            return under?.isStateful == true
        }

        override fun onStateChange(state: IntArray): Boolean {
            return under?.setState(state) == true
        }

        override fun draw(canvas: Canvas) {
            under?.draw(canvas)

            val bounds = bounds
            val centerX = bounds.exactCenterX()
            val centerY = bounds.exactCenterY()

            // About as large as the character was, but never past the button.
            val outer = min(
                sizePx * 0.5f,
                min(bounds.width(), bounds.height()) * 0.4f
            )
            val inner = outer * 0.74f
            val hole = outer * 0.34f

            path.reset()
            path.fillType = Path.FillType.EVEN_ODD

            // Each tooth: up the flank, across the tip, down, then along
            // the valley to the next tooth.
            val step = 2.0 * PI / TEETH

            for (tooth in 0 until TEETH) {
                val start = tooth * step
                val points = listOf(
                    start - step * 0.22 to inner,
                    start - step * 0.14 to outer,
                    start + step * 0.14 to outer,
                    start + step * 0.22 to inner
                )

                points.forEachIndexed { index, (angle, radius) ->
                    val x = centerX + (cos(angle) * radius).toFloat()
                    val y = centerY + (sin(angle) * radius).toFloat()

                    if (tooth == 0 && index == 0) {
                        path.moveTo(x, y)
                    } else {
                        path.lineTo(x, y)
                    }
                }
            }

            path.close()
            path.addCircle(
                centerX,
                centerY,
                hole,
                Path.Direction.CW
            )

            canvas.drawPath(path, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        // The Material theme tints a button's background with the app's
        // primary color (purple). The gear keeps the button's text color,
        // so tints and color filters are ignored.
        override fun setColorFilter(colorFilter: ColorFilter?) {
        }

        override fun setTintList(tint: ColorStateList?) {
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int {
            return PixelFormat.TRANSLUCENT
        }

        private companion object {
            const val TEETH = 8
        }
    }
}
