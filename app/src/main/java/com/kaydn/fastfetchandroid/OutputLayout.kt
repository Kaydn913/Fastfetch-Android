package com.kaydn.fastfetchandroid

/**
 * Decides how the ASCII logo and the info text share the screen width.
 *
 * Android 16+ ignores the app's landscape lock on large screens, so
 * tablets can end up running it in portrait. The landscape layout keeps
 * 220dp free on the right (that space once held the Copy/Share buttons;
 * now only the 44dp settings button is left), which pushed long lines
 * off screen. So the arrangement is picked from the space that's
 * actually there, roomiest first:
 *
 *  - WIDE: everything fits with the full 220dp margin. This is the
 *    v1.1 layout, so TVs and landscape phones look exactly the same.
 *  - SLIM: it fits once the margin shrinks to just clear the settings
 *    button.
 *  - STACKED: the logo goes on top and the text gets the whole width
 *    below it.
 *
 * Lines that are still too long can scroll sideways, like before.
 *
 * Plain Kotlin with no Android types, so it can be unit tested.
 */
object OutputLayout {

    enum class Arrangement {
        // Declared roomiest first; choose() relies on this order.
        WIDE,
        SLIM,
        STACKED
    }

    /**
     * All sizes in px.
     *
     * @param availableWidth room for the content: the scroll view's width
     *   minus its start padding (the end margin is what's being decided).
     * @param logoWidth 0 when the logo is hidden.
     * @param gap space between the logo and the text when side by side.
     * @param textWidth width the info text needs.
     * @param wideMargin the original end margin (220dp).
     * @param slimMargin an end margin that still clears the settings
     *   button.
     * @param slack extra room needed before moving back to a roomier
     *   arrangement, so a line growing or shrinking by one character
     *   can't make the layout flip back and forth every tick.
     * @param current the arrangement on screen now, or null if none yet.
     */
    fun choose(
        availableWidth: Int,
        logoWidth: Int,
        gap: Int,
        textWidth: Int,
        wideMargin: Int,
        slimMargin: Int,
        slack: Int,
        current: Arrangement?
    ): Arrangement {
        if (availableWidth <= 0 || textWidth <= 0) {
            // Not measured yet: keep whatever is on screen.
            return current ?: Arrangement.WIDE
        }

        val sideBySideWidth =
            if (logoWidth > 0) {
                logoWidth + gap + textWidth
            } else {
                textWidth
            }

        fun fits(
            arrangement: Arrangement,
            margin: Int
        ): Boolean {
            // Only moving to a roomier arrangement than the current one
            // needs the slack. Staying put or getting tighter doesn't.
            val extra =
                if (
                    current != null &&
                    arrangement < current
                ) {
                    slack
                } else {
                    0
                }

            return sideBySideWidth + margin + extra <= availableWidth
        }

        return when {
            fits(
                Arrangement.WIDE,
                wideMargin
            ) -> Arrangement.WIDE

            // Without a logo there's nothing to stack, so a slim margin
            // (plus sideways scrolling for long lines) is as good as it
            // gets.
            logoWidth <= 0 ||
                    fits(
                        Arrangement.SLIM,
                        slimMargin
                    ) -> Arrangement.SLIM

            else -> Arrangement.STACKED
        }
    }
}
