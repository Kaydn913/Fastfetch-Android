package com.kaydn.fastfetchandroid

import com.kaydn.fastfetchandroid.OutputLayout.Arrangement
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sizes are in dp-like units at density 1. The numbers roughly match
 * real devices: the logo is ~168 wide and compact output ~270-400.
 */
class OutputLayoutTest {

    private fun choose(
        available: Int,
        text: Int,
        logo: Int = 168,
        current: Arrangement? = null
    ): Arrangement {
        return OutputLayout.choose(
            availableWidth = available,
            logoWidth = logo,
            gap = 18,
            textWidth = text,
            wideMargin = 220,
            slimMargin = 56,
            slack = 16,
            current = current
        )
    }

    @Test
    fun tvKeepsTheOriginalLayout() {
        // onn stick: 961dp wide minus 20dp start padding.
        assertEquals(
            Arrangement.WIDE,
            choose(available = 941, text = 271)
        )
    }

    @Test
    fun portraitTabletShrinksTheMargin() {
        // 800dp portrait: 168 + 18 + 400 + 220 = 806 doesn't fit,
        // 168 + 18 + 400 + 56 = 642 does.
        assertEquals(
            Arrangement.SLIM,
            choose(available = 780, text = 400)
        )
    }

    @Test
    fun narrowWindowStacksTheLogo() {
        assertEquals(
            Arrangement.STACKED,
            choose(available = 500, text = 400)
        )
    }

    @Test
    fun exactFitCounts() {
        // 168 + 18 + 400 + 220 = 806
        assertEquals(
            Arrangement.WIDE,
            choose(available = 806, text = 400)
        )
    }

    @Test
    fun hiddenLogoNeverStacks() {
        assertEquals(
            Arrangement.SLIM,
            choose(available = 300, text = 600, logo = 0)
        )
    }

    @Test
    fun hiddenLogoOnlyNeedsRoomForTheText() {
        // 400 + 220 = 620
        assertEquals(
            Arrangement.WIDE,
            choose(available = 620, text = 400, logo = 0)
        )
    }

    @Test
    fun movingBackToRoomierNeedsSlack() {
        // Fits WIDE exactly, but SLIM is on screen: stay SLIM until
        // there's 16 to spare, so the layout can't flip every tick.
        assertEquals(
            Arrangement.SLIM,
            choose(available = 806, text = 400, current = Arrangement.SLIM)
        )

        assertEquals(
            Arrangement.WIDE,
            choose(available = 822, text = 400, current = Arrangement.SLIM)
        )
    }

    @Test
    fun stayingPutNeedsNoSlack() {
        assertEquals(
            Arrangement.WIDE,
            choose(available = 806, text = 400, current = Arrangement.WIDE)
        )
    }

    @Test
    fun gettingTighterIsImmediate() {
        assertEquals(
            Arrangement.SLIM,
            choose(available = 805, text = 400, current = Arrangement.WIDE)
        )
    }

    @Test
    fun stackedNeedsSlackToGoSideBySide() {
        // SLIM needs 642. Stacked stays stacked until 658.
        assertEquals(
            Arrangement.STACKED,
            choose(available = 642, text = 400, current = Arrangement.STACKED)
        )

        assertEquals(
            Arrangement.SLIM,
            choose(available = 658, text = 400, current = Arrangement.STACKED)
        )
    }

    @Test
    fun unmeasuredKeepsTheCurrentArrangement() {
        assertEquals(
            Arrangement.STACKED,
            choose(available = 0, text = 400, current = Arrangement.STACKED)
        )

        assertEquals(
            Arrangement.WIDE,
            choose(available = 780, text = -1)
        )
    }
}
