package com.kaydn.fastfetchandroid

import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.SpannedString
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import kotlin.math.ceil

/**
 * Turns plain info lines into what's drawn: the ASCII logo, the colored
 * lines (with a per-line cache, so unchanged lines cost nothing), and the
 * plain text with the logo used by Copy/Share. renderLines() runs on the
 * refresh thread and its cache is only touched there; buildAndroidLogo()
 * runs once on the main thread.
 */
class OutputRenderer(
    private val settings: AppSettings
) {

    private val logoColor = Color.rgb(61, 220, 132)
    private val normalColor = Color.rgb(232, 232, 232)

    // Only touched on the refresh thread (from renderLines): last tick's
    // colored and measured lines, keyed by their text, and the style they
    // were made with.
    private var lineCache = HashMap<String, RenderedLine>()
    private var lineCacheStyle: LineStyle? = null

    // One colored output line and its width in px, kept between ticks.
    // Never changed after it's made, so both threads can read it.
    private class RenderedLine(
        val text: CharSequence,
        val widthPx: Float
    )

    // Everything that changes how a line looks or measures. When any of
    // it changes, the line cache starts over.
    private data class LineStyle(
        val accentColor: Int,
        val typeface: Typeface?,
        val textSize: Float,
        val textScaleX: Float,
        val letterSpacing: Float
    )

    // ==================================================
    // ASCII LOGO / TEXT RENDERING
    // ==================================================

    private fun getAndroidLogoLines(): List<String> {
        return listOf(
            "-o          o-",
            "+hydNNNNdyh+",
            "+mMMMMMMMMMMMMm+",
            "`dMMm:NMMMMMMN:mMMd`",
            "hMMMMMMMMMMMMMMMMMMh",
            "..  yyyyyyyyyyyyyyyyyyyy  ..",
            ".mMMm`MMMMMMMMMMMMMMMMMMMM`mMMm.",
            ":MMMM-MMMMMMMMMMMMMMMMMMMM-MMMM:",
            ":MMMM-MMMMMMMMMMMMMMMMMMMM-MMMM:",
            ":MMMM-MMMMMMMMMMMMMMMMMMMM-MMMM:",
            ":MMMM-MMMMMMMMMMMMMMMMMMMM-MMMM:",
            "-MMMM-MMMMMMMMMMMMMMMMMMMM-MMMM-",
            "+yy+  MMMMMMMMMMMMMMMMMMMM  +yy+",
            "mMMMMMMMMMMMMMMMMMMm",
            "`/++MMMMh++hMMMM++/`",
            "MMMMo  oMMMM",
            "MMMMo  oMMMM",
            "oNMm-  -mMNs"
        )
    }

    fun buildAndroidLogo(): SpannableStringBuilder {
        val logo = getAndroidLogoLines()
        val width = logo.maxOf { it.length }
        val output = SpannableStringBuilder()

        logo.forEachIndexed { index, line ->
            output.appendColored(
                centerText(
                    line,
                    width
                ),
                logoColor
            )

            if (index != logo.lastIndex) {
                output.append("\n")
            }
        }

        return output
    }

    fun buildPlainFastfetch(
        infoLines: List<String>
    ): String {
        val logo = getAndroidLogoLines()
        val width = logo.maxOf { it.length }

        val rows =
            maxOf(
                logo.size,
                infoLines.size
            )

        return buildString {
            for (row in 0 until rows) {
                val logoLine =
                    if (row < logo.size) {
                        centerText(
                            logo[row],
                            width
                        )
                    } else {
                        " ".repeat(width)
                    }

                append(logoLine)
                append("   ")

                if (row < infoLines.size) {
                    append(infoLines[row])
                }

                if (row < rows - 1) {
                    append('\n')
                }
            }
        }
    }

    private fun centerText(
        text: String,
        width: Int
    ): String {
        val padding = width - text.length
        val left = padding / 2
        val right = padding - left

        return " ".repeat(left) +
                text +
                " ".repeat(right)
    }

    /**
     * Runs on the refresh thread. Colors and measures every on-screen
     * line, reusing last tick's work for lines whose text didn't change.
     * That's nearly all of them: usually only Memory/Swap or Uptime move.
     * v1.1 re-colored, re-measured and pre-laid-out the whole block on
     * every tick (~11 ms of the ~24 ms tick on the onn stick).
     */
    fun renderLines(
        output: FastfetchResult,
        paint: TextPaint
    ): FastfetchResult {
        val accentColor = settings.getAccentColor()

        val style =
            LineStyle(
                accentColor = accentColor,
                typeface = paint.typeface,
                textSize = paint.textSize,
                textScaleX = paint.textScaleX,
                letterSpacing = paint.letterSpacing
            )

        if (style != lineCacheStyle) {
            lineCache.clear()
            lineCacheStyle = style
        }

        val texts = output.infoLines + COLOR_BLOCKS_TEXT
        val nextCache = HashMap<String, RenderedLine>(texts.size)
        var widestPx = 0f

        val lines =
            texts.mapIndexed { index, text ->
                // The two header lines and the color blocks are colored
                // differently from a module line with the same text, so
                // they get their own cache keys.
                val key =
                    when {
                        index == texts.lastIndex -> "\u0002$text"
                        index < 2 -> "\u0001$text"
                        else -> text
                    }

                val line =
                    lineCache[key]
                        ?: RenderedLine(
                            text = renderLine(
                                index,
                                texts.lastIndex,
                                text,
                                accentColor
                            ),
                            // Colors don't change a line's width, so the
                            // plain text measures the same.
                            widthPx = Layout.getDesiredWidth(
                                text,
                                paint
                            )
                        )

                nextCache[key] = line
                widestPx = maxOf(widestPx, line.widthPx)
                line.text
            }

        // Only this tick's lines are kept, so the cache can't grow.
        lineCache = nextCache

        return output.copy(
            lines = lines,
            lineTexts = texts,
            accentColor = accentColor,
            // Rounded up the same way TextView sizes wrap_content text,
            // so no line ever wraps.
            textWidthPx = ceil(widestPx).toInt()
        )
    }

    private fun renderLine(
        index: Int,
        lastIndex: Int,
        text: String,
        accentColor: Int
    ): CharSequence {
        val line = SpannableStringBuilder()

        when {
            index == lastIndex -> {
                COLOR_BLOCK_COLORS.forEach { color ->
                    line.appendColored(
                        COLOR_BLOCK,
                        color
                    )
                }
            }

            // Title and separator.
            index < 2 -> {
                line.appendColored(
                    text,
                    accentColor
                )
            }

            else -> {
                appendModuleLine(
                    line,
                    text,
                    accentColor
                )
            }
        }

        // Immutable, since the UI thread reads it while the refresh
        // thread may be working on the next tick.
        return SpannedString(line)
    }

    private fun appendModuleLine(
        output: SpannableStringBuilder,
        line: String,
        accentColor: Int
    ) {
        val sections = line.split(" | ")

        sections.forEachIndexed { index, section ->
            if (index > 0) {
                output.appendColored(
                    " | ",
                    normalColor
                )
            }

            appendModuleSection(
                output,
                section,
                accentColor
            )
        }
    }

    private fun appendModuleSection(
        output: SpannableStringBuilder,
        section: String,
        accentColor: Int
    ) {
        val colon = section.indexOf(':')

        if (colon == -1) {
            output.appendColored(
                section,
                normalColor
            )
            return
        }

        val name =
            section.substring(
                0,
                colon + 1
            )

        val value =
            section.substring(
                colon + 1
            )

        output.appendColored(
            name,
            accentColor
        )

        output.appendColored(
            value,
            normalColor
        )
    }

    private fun SpannableStringBuilder.appendColored(
        text: String,
        color: Int
    ) {
        val start = length
        append(text)

        setSpan(
            ForegroundColorSpan(color),
            start,
            length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    companion object {
        // The palette row under the info text.
        private const val COLOR_BLOCK =
            "██"

        private val COLOR_BLOCK_COLORS =
            listOf(
                Color.rgb(70, 70, 70),
                Color.rgb(255, 80, 80),
                Color.rgb(80, 220, 100),
                Color.rgb(255, 220, 80),
                Color.rgb(80, 130, 255),
                Color.rgb(220, 90, 255),
                Color.rgb(80, 220, 255),
                Color.rgb(235, 235, 235)
            )

        private val COLOR_BLOCKS_TEXT =
            COLOR_BLOCK.repeat(COLOR_BLOCK_COLORS.size)
    }
}
