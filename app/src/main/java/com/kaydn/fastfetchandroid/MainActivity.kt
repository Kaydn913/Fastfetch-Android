package com.kaydn.fastfetchandroid

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import com.kaydn.fastfetchandroid.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Saved settings (see AppSettings). Lazy, because an Activity can't
    // open its prefs before onCreate.
    private val settings by lazy {
        AppSettings(applicationContext)
    }

    // Device information (see DeviceInfo). Lazy for the same reason.
    private val deviceInfo by lazy {
        DeviceInfo(
            activity = this,
            settings = settings,
            staticInfoFromJNI = ::staticInfoFromJNI,
            dynamicInfoFromJNI = ::dynamicInfoFromJNI
        )
    }

    // Colors and lays out the output (see OutputRenderer).
    private val outputRenderer by lazy {
        OutputRenderer(
            settings = settings
        )
    }

    // Builds the info lines (see OutputBuilder). Runs on the refresh thread.
    private val outputBuilder by lazy {
        OutputBuilder(
            activity = this,
            settings = settings,
            deviceInfo = deviceInfo,
            renderer = outputRenderer
        )
    }

    private var lastPlainOutput = ""

    // What the info TextView's buffer holds right now (plain text, one
    // entry per line, and the accent color it was drawn with), so the
    // next update can change only the lines that differ. Main thread only.
    private var shownLineTexts: List<String>? = null
    private var shownAccentColor = 0

    private val quicksandTypeface by lazy {
        ResourcesCompat.getFont(
            this,
            R.font.quicksand_regular
        )
    }

    private val robotoMonoTypeface by lazy {
        ResourcesCompat.getFont(
            this,
            R.font.roboto_mono_regular
        )
    }

    private val refreshHandler = Handler(Looper.getMainLooper())

    // Refresh state. All of these are only read and written on the
    // main thread; the refresh thread hands its result back via
    // refreshHandler.post.
    private var isStarted = false
    private var refreshGeneration = 0
    private var refreshInFlight = false
    private var refreshPending = false
    private var forceNextApply = true

    // How the logo and the text share the width (see OutputLayout and
    // the OUTPUT ARRANGEMENT section). Main thread only.
    private var outputArrangement: OutputLayout.Arrangement? = null

    // Width the info TextView was last pinned to, in px (< 0 = unknown).
    private var outputTextWidthPx = -1

    // Measured once, since the logo's text and font never change.
    private var logoWidthPx = -1

    // activity_main.xml's own end padding (220dp) and logo gap (18dp x
    // 1dp), read in onCreate so the XML stays the single source.
    private var wideEndPaddingPx = 0
    private var gapWidthPx = 0
    private var gapHeightPx = 0

    private val refreshRunnable = Runnable {
        requestRefresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyMainUiFont()

        binding.logoText.text = outputRenderer.buildAndroidLogo()

        setUpOutputArrangement()

        binding.settingsButton.setOnClickListener {
            showSettingsDialog(firstRun = false)
        }

        binding.copyButton.setOnClickListener {
            copyFastfetch()
        }

        binding.shareButton.setOnClickListener {
            shareFastfetch()
        }

        binding.sampleText.setOnLongClickListener {
            showSettingsDialog(firstRun = false)
            true
        }

        // Nothing to copy or share until the first refresh lands.
        setOutputActionsEnabled(false)

        // Fonts and logo visibility go on up front so the first frame
        // already looks right. The text itself arrives once the first
        // background refresh (started in onStart) finishes.
        applyOutputFont()
        binding.logoText.visibility =
            if (outputBuilder.shouldShowLogo(isWatchStyle())) View.VISIBLE else View.GONE

        binding.root.post {
            if (!settings.isSetupComplete()) {
                showSettingsDialog(firstRun = true)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        isStarted = true

        // Refresh right away (also covers coming back from the
        // background, so the screen never shows stale values).
        requestRefresh()
    }

    override fun onStop() {
        isStarted = false

        // Anything still running on the refresh thread belongs to the
        // old session; bumping the generation makes its result get
        // dropped when it arrives.
        refreshGeneration++
        refreshInFlight = false
        refreshPending = false
        refreshHandler.removeCallbacks(refreshRunnable)

        super.onStop()
    }

    // ==================================================
    // REFRESH / UI
    // ==================================================
    //
    // v1.0 built the whole output on the main thread every tick, which
    // froze the UI for ~100 ms per tick on slow devices (onn stick:
    // 29/29 frames janky, 105 ms median). Now:
    //
    //  1. requestRefresh() hands outputBuilder.buildFastfetchOutput() to a background
    //     thread.
    //  2. onRefreshFinished() puts the result on screen (main thread).
    //  3. Only then is the next tick scheduled, so slow ticks can never
    //     pile up behind each other.

    private fun requestRefresh() {
        if (!isStarted) {
            return
        }

        refreshHandler.removeCallbacks(refreshRunnable)

        if (refreshInFlight) {
            // Run exactly one more after the current one, so a settings
            // change made mid-refresh still shows up.
            refreshPending = true
            return
        }

        refreshInFlight = true
        val generation = refreshGeneration

        // A private copy of the TextView's paint (font, size), so the
        // refresh thread can measure lines without touching the real view.
        val measurePaint = TextPaint(binding.sampleText.paint)

        refreshExecutor.execute {
            traced("ff:tick") {
                val result =
                    try {
                        val output = traced("ff:build") { outputBuilder.buildFastfetchOutput() }

                        traced("ff:renderLines") {
                            outputRenderer.renderLines(
                                output,
                                measurePaint
                            )
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Refresh failed", t)
                        null
                    }

                refreshHandler.post {
                    onRefreshFinished(generation, result)
                }
            }
        }
    }

    private fun onRefreshFinished(
        generation: Int,
        result: FastfetchResult?
    ) {
        if (generation != refreshGeneration) {
            // Started before the last onStop(); that screen is gone.
            return
        }

        refreshInFlight = false

        if (isFinishing || isDestroyed) {
            return
        }

        if (result != null) {
            applyRefreshResult(result)
        }

        if (refreshPending) {
            refreshPending = false
            requestRefresh()
            return
        }

        scheduleNextRefresh()
    }

    private fun scheduleNextRefresh() {
        refreshHandler.removeCallbacks(refreshRunnable)

        val interval = settings.getRefreshIntervalMs()
        if (isStarted && interval > 0L) {
            refreshHandler.postDelayed(refreshRunnable, interval)
        }
    }

    private fun applyRefreshResult(result: FastfetchResult) {
        val logoVisibility =
            if (result.showLogo) View.VISIBLE else View.GONE

        if (binding.logoText.visibility != logoVisibility) {
            binding.logoText.visibility = logoVisibility
            updateOutputArrangement()
        }

        // Width first: with a fixed width, changing the text only redraws
        // instead of re-laying out the whole screen (that was ~21 ms per
        // update on the onn stick). Only an actual width change relayouts.
        fitOutputWidth(result.textWidthPx)

        // On most ticks nothing on screen changes, so the TextView isn't
        // touched at all.
        if (
            forceNextApply ||
            result.plainText != lastPlainOutput ||
            result.accentColor != shownAccentColor
        ) {
            setOutputText(result)
            lastPlainOutput = result.plainText
        }

        forceNextApply = false
        setOutputActionsEnabled(true)
    }

    /**
     * v1.1 replaced the whole text every time anything changed, so the
     * UI thread re-recorded the drawing of all ~30 lines (~7 ms on the onn
     * stick) when usually only Memory/Swap or Uptime moved. Now the text
     * lives in an editable buffer and only the lines that differ are
     * swapped out. With an editable buffer the TextView uses a
     * DynamicLayout, which re-measures just those lines and re-records
     * just the chunk of text they sit in.
     */
    private fun setOutputText(result: FastfetchResult) {
        val editable = binding.sampleText.editableText
        val shown = shownLineTexts

        // A new accent color or a settings change touches every line, so
        // those get a fresh full text instead.
        val updatedInPlace =
            editable != null &&
                    shown != null &&
                    !forceNextApply &&
                    result.accentColor == shownAccentColor &&
                    updateChangedLines(
                        editable,
                        shown,
                        result
                    )

        if (!updatedInPlace) {
            replaceAllLines(result)
        }

        shownLineTexts = result.lineTexts
        shownAccentColor = result.accentColor
    }

    /**
     * Swaps out only the lines that differ from what's on screen. Returns
     * false, changing nothing, if lines were added or removed (a network
     * line appearing, a module toggled) or the buffer doesn't look like
     * expected, so the caller replaces everything instead.
     */
    private fun updateChangedLines(
        editable: Editable,
        shown: List<String>,
        result: FastfetchResult
    ): Boolean {
        val next = result.lineTexts

        if (shown.size != next.size) {
            return false
        }

        // Where each line starts in the buffer (lines are joined by '\n').
        val starts = IntArray(shown.size)
        var offset = 0

        shown.forEachIndexed { index, line ->
            starts[index] = offset
            offset += line.length + 1
        }

        if (offset - 1 != editable.length) {
            return false
        }

        // Bottom-up, so the start offsets of the lines above stay valid
        // when a line below changes length.
        for (index in shown.indices.reversed()) {
            if (shown[index] == next[index]) {
                continue
            }

            val start = starts[index]
            val end = start + shown[index].length

            // The old line's colors go first, so none of them can end up
            // stretched over the new text.
            editable
                .getSpans(
                    start,
                    end,
                    ForegroundColorSpan::class.java
                )
                .forEach { span ->
                    if (
                        editable.getSpanStart(span) >= start &&
                        editable.getSpanEnd(span) <= end
                    ) {
                        editable.removeSpan(span)
                    }
                }

            editable.replace(
                start,
                end,
                result.lines[index]
            )
        }

        return true
    }

    private fun replaceAllLines(result: FastfetchResult) {
        val text = SpannableStringBuilder()

        result.lines.forEachIndexed { index, line ->
            if (index > 0) {
                text.append('\n')
            }

            text.append(line)
        }

        // EDITABLE, so later updates can change single lines in place.
        binding.sampleText.setText(
            text,
            TextView.BufferType.EDITABLE
        )
    }

    private fun fitOutputWidth(textWidthPx: Int) {
        val view = binding.sampleText

        val targetWidth =
            if (textWidthPx > 0) {
                textWidthPx + view.totalPaddingLeft + view.totalPaddingRight
            } else {
                ViewGroup.LayoutParams.WRAP_CONTENT
            }

        val params = view.layoutParams

        if (params.width != targetWidth) {
            params.width = targetWidth
            view.layoutParams = params
        }

        // Only a width change can change the arrangement, so most ticks
        // skip this.
        if (targetWidth != outputTextWidthPx) {
            outputTextWidthPx = targetWidth
            updateOutputArrangement()
        }
    }

    /** Called after settings are saved or reset. */
    private fun onSettingsChanged() {
        // Apply the font first, so the refresh below measures its text
        // against the new font instead of the old one.
        applyOutputFont()

        forceNextApply = true
        requestRefresh()
    }

    private fun setOutputActionsEnabled(enabled: Boolean) {
        binding.copyButton.isEnabled = enabled
        binding.shareButton.isEnabled = enabled
    }

    private fun isWatchStyle(): Boolean {
        return deviceInfo.getDeviceType() == "Watch" || settings.getOutputMode() == MODE_WATCH
    }

    private fun applyMainUiFont() {
        val typeface =
            quicksandTypeface
                ?: return

        binding.copyButton.setTypeface(
            typeface,
            Typeface.NORMAL
        )

        binding.shareButton.setTypeface(
            typeface,
            Typeface.NORMAL
        )

        binding.settingsButton.setTypeface(
            typeface,
            Typeface.NORMAL
        )
    }

    private fun applyOutputFont() {
        /*
         * Keep the ASCII Android logo monospaced no matter what.
         * Only the information text switches between Quicksand
         * and Roboto Mono.
         */
        robotoMonoTypeface?.let { logoTypeface ->
            binding.logoText.setTypeface(
                logoTypeface,
                Typeface.NORMAL
            )
        }

        val infoTypeface =
            when (settings.getOutputFontName()) {
                OUTPUT_FONT_ROBOTO ->
                    robotoMonoTypeface

                else ->
                    quicksandTypeface
            }
                ?: return

        binding.sampleText.setTypeface(
            infoTypeface,
            Typeface.NORMAL
        )
    }

    // ==================================================
    // OUTPUT ARRANGEMENT
    // ==================================================
    //
    // Fits the logo and the text into the width that's actually there
    // (portrait tablets on Android 16+, narrow windows). OutputLayout
    // makes the decision; this section measures and applies it. When
    // there's room, nothing changes from the v1.1 layout.

    private fun setUpOutputArrangement() {
        // The round watch layout has no logo row, so there's nothing to
        // arrange there.
        val scroll =
            binding.outputScroll
                ?: return

        val gap =
            binding.outputGap
                ?: return

        wideEndPaddingPx = scroll.paddingEnd
        gapWidthPx = gap.layoutParams.width
        gapHeightPx = gap.layoutParams.height

        // Catches width changes that don't recreate the activity. Posted,
        // because changing padding in the middle of a layout pass would
        // force a second pass right away.
        scroll.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                scroll.post {
                    updateOutputArrangement()
                }
            }
        }
    }

    private fun updateOutputArrangement() {
        val scroll =
            binding.outputScroll
                ?: return

        val logoVisible =
            binding.logoText.visibility == View.VISIBLE

        val arrangement =
            OutputLayout.choose(
                availableWidth = scroll.width - scroll.paddingStart,
                logoWidth = if (logoVisible) getLogoWidthPx() else 0,
                gap = gapWidthPx,
                textWidth = outputTextWidthPx,
                wideMargin = wideEndPaddingPx,
                slimMargin = dpToPx(SLIM_END_MARGIN_DP),
                slack = dpToPx(ARRANGEMENT_SLACK_DP),
                current = outputArrangement
            )

        if (arrangement == outputArrangement) {
            return
        }

        outputArrangement = arrangement
        applyOutputArrangement(arrangement)
    }

    private fun applyOutputArrangement(
        arrangement: OutputLayout.Arrangement
    ) {
        val scroll =
            binding.outputScroll
                ?: return

        val row =
            binding.outputRow
                ?: return

        val gap =
            binding.outputGap
                ?: return

        val stacked =
            arrangement == OutputLayout.Arrangement.STACKED

        val endPadding =
            when (arrangement) {
                OutputLayout.Arrangement.WIDE ->
                    wideEndPaddingPx

                OutputLayout.Arrangement.SLIM ->
                    dpToPx(SLIM_END_MARGIN_DP)

                // The text starts below the logo, well under the settings
                // button, so it can use the same margin as the left side.
                OutputLayout.Arrangement.STACKED ->
                    scroll.paddingStart
            }

        // Each of these only triggers a relayout when something really
        // changes.
        if (scroll.paddingEnd != endPadding) {
            scroll.setPaddingRelative(
                scroll.paddingStart,
                scroll.paddingTop,
                endPadding,
                scroll.paddingBottom
            )
        }

        row.orientation =
            if (stacked) {
                LinearLayout.VERTICAL
            } else {
                LinearLayout.HORIZONTAL
            }

        // Side by side the gap is 18dp wide; stacked it becomes a little
        // vertical space between the logo and the text.
        val gapWidth =
            if (stacked) gapHeightPx else gapWidthPx

        val gapHeight =
            if (stacked) dpToPx(STACKED_GAP_DP) else gapHeightPx

        val gapParams = gap.layoutParams

        if (
            gapParams.width != gapWidth ||
            gapParams.height != gapHeight
        ) {
            gapParams.width = gapWidth
            gapParams.height = gapHeight
            gap.layoutParams = gapParams
        }
    }

    private fun getLogoWidthPx(): Int {
        if (logoWidthPx < 0) {
            val logo = binding.logoText

            logoWidthPx =
                ceil(Layout.getDesiredWidth(logo.text, logo.paint)).toInt() +
                        logo.totalPaddingLeft +
                        logo.totalPaddingRight
        }

        return logoWidthPx
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).roundToInt()
    }

    // ==================================================
    // COPY / SHARE
    // ==================================================

    private fun copyFastfetch() {
        // The buttons stay disabled until the first refresh lands, so
        // this is just a safety net.
        if (lastPlainOutput.isBlank()) {
            return
        }

        val clipboard =
            getSystemService(
                ClipboardManager::class.java
            )

        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                "Fastfetch Android",
                lastPlainOutput
            )
        )

        Toast.makeText(
            this,
            "Fastfetch output copied",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun shareFastfetch() {
        if (lastPlainOutput.isBlank()) {
            return
        }

        val intent =
            Intent(
                Intent.ACTION_SEND
            ).apply {
                type = "text/plain"

                putExtra(
                    Intent.EXTRA_TEXT,
                    lastPlainOutput
                )
            }

        startActivity(
            Intent.createChooser(
                intent,
                "Share Fastfetch output"
            )
        )
    }

    // ==================================================
    // SETTINGS DIALOG
    // ==================================================

    private fun showSettingsDialog(
        firstRun: Boolean
    ) {
        SettingsDialog(
            activity = this,
            settings = settings,
            quicksandTypeface = quicksandTypeface,
            isWatchDevice = { deviceInfo.getDeviceType() == "Watch" },
            onSaved = ::onSettingsChanged
        ).show(
            firstRun = firstRun
        )
    }

    // ==================================================
    // JNI
    // ==================================================

    external fun staticInfoFromJNI(): String

    external fun dynamicInfoFromJNI(): String

    companion object {
        private const val TAG = "FastfetchAndroid"

        // One background thread shared by every MainActivity instance,
        // at background priority so it never competes with the UI and
        // RenderThread for CPU time.
        private val refreshExecutor: ExecutorService =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(
                    {
                        android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND
                        )
                        runnable.run()
                    },
                    "fastfetch-refresh"
                ).apply {
                    isDaemon = true
                }
            }

        // Output arrangement (see OutputLayout). The wide margin and the
        // side-by-side gap come from activity_main.xml.

        // The 44dp settings button + its 8dp margin + 4dp breathing room.
        private const val SLIM_END_MARGIN_DP =
            56

        private const val STACKED_GAP_DP =
            12

        private const val ARRANGEMENT_SLACK_DP =
            16

        init {
            System.loadLibrary(
                "fastfetchandroid"
            )
        }
    }
}
