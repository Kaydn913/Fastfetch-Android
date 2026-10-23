package com.kaydn.fastfetchandroid

import android.app.ActivityManager
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.text.Editable
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.SpannedString
import android.text.TextPaint
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import com.kaydn.fastfetchandroid.databinding.ActivityMainBinding
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.file.Files
import java.util.Locale
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

    private val logoColor = Color.rgb(61, 220, 132)
    private val normalColor = Color.rgb(232, 232, 232)

    private var lastPlainOutput = ""

    // What the info TextView's buffer holds right now (plain text, one
    // entry per line, and the accent color it was drawn with), so the
    // next update can change only the lines that differ. Main thread only.
    private var shownLineTexts: List<String>? = null
    private var shownAccentColor = 0

    // Only touched on the refresh thread (from buildFastfetchOutput).
    private var cachedThermalHeadroom: Float? = null
    private var lastThermalHeadroomRead = 0L

    // Only touched on the refresh thread (from renderLines): last tick's
    // colored and measured lines, keyed by their text, and the style they
    // were made with.
    private var lineCache = HashMap<String, RenderedLine>()
    private var lineCacheStyle: LineStyle? = null

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

    private data class FastfetchResult(
        // The info lines as plain text, in order (no color blocks).
        val infoLines: List<String>,
        val plainText: String,
        val showLogo: Boolean,
        // Every on-screen line (the info lines plus the color blocks),
        // colored and without newlines. Filled in on the refresh thread.
        val lines: List<CharSequence> = emptyList(),
        // The same lines as plain text, so the UI thread can tell which
        // ones changed since the last update.
        val lineTexts: List<String> = emptyList(),
        // The accent color the lines were colored with.
        val accentColor: Int = 0,
        // Exact width the text needs, in px, measured on the refresh
        // thread. -1 if unknown (the view stays wrap_content).
        val textWidthPx: Int = -1
    )

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyMainUiFont()

        binding.logoText.text = buildAndroidLogo()

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
            if (shouldShowLogo(isWatchStyle())) View.VISIBLE else View.GONE

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
    //  1. requestRefresh() hands buildFastfetchOutput() to a background
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
                        val output = traced("ff:build") { buildFastfetchOutput() }

                        traced("ff:renderLines") {
                            renderLines(
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

    private fun shouldShowLogo(watchStyle: Boolean): Boolean {
        return !watchStyle && settings.getShowLogo()
    }

    private fun isWatchStyle(): Boolean {
        return getDeviceType() == "Watch" || settings.getOutputMode() == MODE_WATCH
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

    private fun applyQuicksandToViewTree(
        view: View
    ) {
        val typeface =
            quicksandTypeface
                ?: return

        if (view is TextView) {
            val style =
                view.typeface?.style
                    ?: Typeface.NORMAL

            view.setTypeface(
                typeface,
                style
            )
        }

        if (view is ViewGroup) {
            for (
            index in
            0 until view.childCount
            ) {
                applyQuicksandToViewTree(
                    view.getChildAt(index)
                )
            }
        }
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
    // OUTPUT BUILDER
    // ==================================================

    private inline fun <T> traced(name: String, block: () -> T): T {
        android.os.Trace.beginSection(name)
        try {
            return block()
        } finally {
            android.os.Trace.endSection()
        }
    }

    // Runs on the refresh thread. Must not touch views.
    private fun buildFastfetchOutput(): FastfetchResult {
        val deviceType = traced("ff:deviceType") { getDeviceType() }
        val requestedMode = settings.getOutputMode()
        val mode = if (deviceType == "Watch") MODE_WATCH else requestedMode
        val watchStyle = mode == MODE_WATCH

        val username = settings.getUsername()
        val title = "$username@${Build.MODEL}"
        val separator = "-".repeat(title.length)

        val manufacturer = cachedManufacturer
        val firstAbi = cachedFirstAbi
        val allAbis = cachedAllAbis

        // The JNI signature includes uptime, so the dynamic call stays live
        // even when hardware and memory modules are disabled.
        val staticNative = traced("ff:jni") { getParsedStaticNativeLines() }
        val dynamicNative = traced("ff:jni") { getDynamicNativeLines() }

        fun nativeLine(name: String): String? {
            return staticNative[name] ?: dynamicNative.firstOrNull {
                it.startsWith("$name:")
            }
        }

        val kernel = nativeLine("Kernel")
        val uptime = nativeLine("Uptime")
        val cpu = nativeLine("CPU")
        val swap = nativeLine("Swap")
        val gpu = staticNative["GPU.value"]
        val gpuVendor = staticNative["GPU Vendor.value"]
        val nativeArchitecture = staticNative["Architecture.value"]
        val bootloader = nativeLine("Bootloader")
        val verifiedBoot = nativeLine("Verified Boot")
        val root = nativeLine("Root")

        val selinux =
            when (val rawSelinux = nativeLine("SELinux")) {
                null,
                "SELinux: Unknown" -> "SELinux: Unknown [Restricted]"
                else -> rawSelinux
            }

        val openGlFull = staticNative["OpenGL ES.value"]
        val openGlVersion = staticNative["OpenGL ES.version"]
        val vulkan = staticNative["Vulkan.value"]

        val infoLines = mutableListOf<String>()

        infoLines.add(title)
        infoLines.add(separator)

        if (watchStyle) {
            infoLines.add("OS: Android ${Build.VERSION.RELEASE}")
        } else {
            infoLines.add("OS: Android ${Build.VERSION.RELEASE} $firstAbi")
        }

        if (deviceType.startsWith("Emulator")) {
            val emulatorHost =
                if (deviceType.contains("BlueStacks")) "BlueStacks" else "Android Emulator"

            infoLines.add("Host: $emulatorHost")
            infoLines.add("Guest: $manufacturer ${Build.MODEL}")
        } else {
            infoLines.add("Host: $manufacturer ${Build.MODEL}")
        }

        infoLines.add("Type: $deviceType")

        kernel?.let {
            if (watchStyle) {
                infoLines.add("Kernel: ${shortenKernel(it)}")
            } else {
                infoLines.add(it)
            }
        }

        uptime?.let {
            infoLines.add(it)
        }

        val groups = linkedMapOf<String, List<String>>()

        if (settings.isModuleEnabled(MODULE_DISPLAY)) {
            val displayText = run {
                @Suppress("DEPRECATION")
                val displayMode = windowManager.defaultDisplay.mode

                "${displayMode.physicalWidth}x${displayMode.physicalHeight} @ " +
                        "${displayMode.refreshRate.roundToInt()} Hz"
            }

            groups[MODULE_DISPLAY] =
                buildDisplayLines(
                    mode = mode,
                    displayText = displayText,
                    density = resources.displayMetrics.densityDpi,
                    orientation = getOrientationName()
                )
        }

        if (settings.isModuleEnabled(MODULE_HARDWARE)) {
            groups[MODULE_HARDWARE] =
                buildHardwareLines(
                    mode = mode,
                    soc = traced("ff:soc") { getSocName() },
                    cpu = cpu,
                    gpu = gpu,
                    gpuVendor = gpuVendor
                )
        }

        if (settings.isModuleEnabled(MODULE_GRAPHICS)) {
            groups[MODULE_GRAPHICS] =
                buildGraphicsLines(
                    mode = mode,
                    openGlFull = openGlFull,
                    openGlVersion = openGlVersion,
                    vulkan = vulkan
                )
        }

        if (settings.isModuleEnabled(MODULE_MEMORY)) {
            val memoryText = run {
                val activityManager =
                    getSystemService(ACTIVITY_SERVICE) as ActivityManager

                val memoryInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memoryInfo)

                val totalRam = memoryInfo.totalMem
                val usedRam = totalRam - memoryInfo.availMem

                val ramPercent =
                    if (totalRam > 0) {
                        (usedRam.toDouble() / totalRam * 100.0).roundToInt()
                    } else {
                        0
                    }

                "${formatGiB(usedRam)} / ${formatGiB(totalRam)} ($ramPercent%)"
            }

            val storage = run {
                val statFs = StatFs(filesDir.absolutePath)
                val totalStorage = statFs.totalBytes
                val usedStorage = totalStorage - statFs.availableBytes

                val storagePercent =
                    if (totalStorage > 0) {
                        (usedStorage.toDouble() / totalStorage * 100.0).roundToInt()
                    } else {
                        0
                    }

                Triple(usedStorage, totalStorage, storagePercent)
            }

            groups[MODULE_MEMORY] =
                buildMemoryLines(
                    mode = mode,
                    memoryText = memoryText,
                    swap = swap,
                    usedStorage = storage.first,
                    totalStorage = storage.second,
                    storagePercent = storage.third,
                    fileSystem = getFileSystemType()
                )
        }

        if (settings.isModuleEnabled(MODULE_NETWORK)) {
            groups[MODULE_NETWORK] =
                buildNetworkOutput(
                    mode = mode,
                    networkLines = traced("ff:network") { getNetworkLines() }
                )
        }

        if (settings.isModuleEnabled(MODULE_POWER)) {
            groups[MODULE_POWER] =
                buildPowerLines(
                    mode = mode,
                    deviceType = deviceType,
                    battery = traced("ff:battery") { getBatterySnapshot() },
                    thermalLines = traced("ff:thermal") { getThermalLines() }
                )
        }

        if (settings.isModuleEnabled(MODULE_ANDROID)) {
            groups[MODULE_ANDROID] =
                buildAndroidLines(
                    mode = mode,
                    nativeArchitecture = nativeArchitecture,
                    allAbis = allAbis
                )
        }

        if (settings.isModuleEnabled(MODULE_SECURITY)) {
            groups[MODULE_SECURITY] =
                buildSecurityLines(
                    mode = mode,
                    selinux = selinux,
                    bootloader = bootloader,
                    verifiedBoot = verifiedBoot,
                    root = root
                )
        }

        if (settings.isModuleEnabled(MODULE_MISC)) {
            groups[MODULE_MISC] =
                buildMiscLines(
                    mode = mode,
                    locale = resources.configuration.locales[0].toLanguageTag()
                )
        }

        settings.getModuleOrder().forEach { module ->
            if (!settings.isModuleEnabled(module)) {
                return@forEach
            }

            val lines = groups[module].orEmpty()
            if (lines.isEmpty()) {
                return@forEach
            }

            if (
                mode == MODE_DETAILED &&
                infoLines.isNotEmpty() &&
                infoLines.last().isNotEmpty()
            ) {
                infoLines.add("")
            }

            infoLines.addAll(lines)
        }

        val includeLogo =
            shouldShowLogo(watchStyle)

        val plainText =
            if (includeLogo) {
                buildPlainFastfetch(infoLines)
            } else {
                infoLines.joinToString("\n")
            }

        // Coloring and measuring happen in renderLines(), which can reuse
        // last tick's work for lines that didn't change.
        return FastfetchResult(
            infoLines = infoLines,
            plainText = plainText,
            showLogo = includeLogo
        )
    }

    /**
     * GPU, Vulkan, OpenGL, SELinux, bootloader, root, kernel: none of
     * it changes while the app is open, and reading the GPU means
     * creating an EGL context plus a Vulkan instance. So it's read once
     * per process and cached.
     */
    private fun getStaticNativeLines(): List<String> {
        cachedStaticNativeLines?.let {
            return it
        }

        return try {
            staticInfoFromJNI()
                .trim()
                .lines()
                .also { cachedStaticNativeLines = it }
        } catch (_: Throwable) {
            // Not cached, so the next tick tries again.
            emptyList()
        }
    }

    private fun getParsedStaticNativeLines(): Map<String, String> {
        cachedParsedStaticNativeLines?.let { return it }

        val lines = getStaticNativeLines()
        val parsed = linkedMapOf<String, String>()
        lines.forEach { line ->
            val name = line.substringBefore(":")
            if (":" in line && name !in parsed) {
                parsed[name] = line
                parsed["$name.value"] = line.removePrefix("$name: ").trim()
            }
        }
        val openGlFull = cleanOpenGlValue(parsed["OpenGL ES"])
        if (openGlFull != null) {
            parsed["OpenGL ES.value"] = openGlFull
            getOpenGlVersion(openGlFull)?.let { parsed["OpenGL ES.version"] = it }
        } else {
            parsed.remove("OpenGL ES.value")
        }

        // A failed JNI call must remain retryable on the next tick.
        if (cachedStaticNativeLines != null) {
            cachedParsedStaticNativeLines = parsed
        }
        return parsed
    }

    /** Uptime, CPU cores online, swap: cheap, so read every tick. */
    private fun getDynamicNativeLines(): List<String> {
        return try {
            dynamicInfoFromJNI()
                .trim()
                .lines()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // ==================================================
    // OUTPUT MODULES
    // ==================================================

    private fun buildDisplayLines(
        mode: String,
        displayText: String,
        density: Int,
        orientation: String
    ): List<String> {
        return when (mode) {
            MODE_WATCH,
            MODE_MINIMAL -> {
                listOf(
                    "Display: $displayText"
                )
            }

            MODE_DETAILED -> {
                listOf(
                    "Display: $displayText",
                    "Density: $density dpi",
                    "Orientation: $orientation",
                    "WM: SurfaceFlinger"
                )
            }

            else -> {
                listOf(
                    "Display: $displayText | Density: $density dpi | WM: SurfaceFlinger"
                )
            }
        }
    }

    private fun buildHardwareLines(
        mode: String,
        soc: String,
        cpu: String?,
        gpu: String?,
        gpuVendor: String?
    ): List<String> {
        val lines = mutableListOf<String>()

        lines.add("SoC: $soc")

        cpu?.let {
            lines.add(it)
        }

        if (gpu != null) {
            if (
                mode == MODE_COMPACT ||
                mode == MODE_DETAILED
            ) {
                if (gpuVendor != null) {
                    lines.add("GPU: $gpu | Vendor: $gpuVendor")
                } else {
                    lines.add("GPU: $gpu")
                }
            } else {
                lines.add("GPU: $gpu")
            }
        }

        if (mode == MODE_DETAILED) {
            // (Process bitness is shown under Android, not here, so it
            // doesn't appear twice in detailed mode.)
            lines.add("Sensors: ${getSensorCount()}")
        }

        return lines
    }

    private fun buildGraphicsLines(
        mode: String,
        openGlFull: String?,
        openGlVersion: String?,
        vulkan: String?
    ): List<String> {
        val parts = mutableListOf<String>()

        if (openGlVersion != null) {
            parts.add(
                if (mode == MODE_WATCH || mode == MODE_MINIMAL) {
                    "GLES $openGlVersion"
                } else {
                    "OpenGL ES $openGlVersion"
                }
            )
        }

        if (vulkan != null) {
            parts.add(
                if (mode == MODE_WATCH || mode == MODE_MINIMAL) {
                    "VK $vulkan"
                } else {
                    "Vulkan: $vulkan"
                }
            )
        }

        val lines = mutableListOf<String>()

        if (parts.isNotEmpty()) {
            lines.add("Graphics: ${parts.joinToString(" | ")}")
        }

        if (mode == MODE_DETAILED && openGlFull != null) {
            lines.add("OpenGL Driver: $openGlFull")
        }

        return lines
    }

    private fun buildMemoryLines(
        mode: String,
        memoryText: String,
        swap: String?,
        usedStorage: Long,
        totalStorage: Long,
        storagePercent: Int,
        fileSystem: String
    ): List<String> {
        val diskBase =
            "Disk: ${formatGiB(usedStorage)} / ${formatGiB(totalStorage)} ($storagePercent%)"

        return when (mode) {
            MODE_COMPACT -> {
                val lines = mutableListOf<String>()

                if (swap != null) {
                    lines.add("Memory: $memoryText | $swap")
                } else {
                    lines.add("Memory: $memoryText")
                }

                if (fileSystem != "Unknown") {
                    lines.add("$diskBase | FS: $fileSystem")
                } else {
                    lines.add(diskBase)
                }

                lines
            }

            MODE_DETAILED -> {
                val lines = mutableListOf<String>()

                lines.add("Memory: $memoryText")

                swap?.let {
                    lines.add(it)
                }

                lines.add(diskBase)

                if (fileSystem != "Unknown") {
                    lines.add("Filesystem: $fileSystem")
                }

                lines
            }

            else -> {
                buildList {
                    add("Memory: $memoryText")

                    if (mode == MODE_WATCH) {
                        swap?.let { add(it) }
                    }

                    add(diskBase)
                }
            }
        }
    }

    private fun buildNetworkOutput(
        mode: String,
        networkLines: List<String>
    ): List<String> {
        if (networkLines.isEmpty()) {
            return emptyList()
        }

        val network =
            networkLines.firstOrNull {
                it.startsWith("Network:")
            }

        if (mode == MODE_WATCH || mode == MODE_MINIMAL) {
            return network
                ?.let {
                    listOf(it.substringBefore(" ("))
                }
                ?: emptyList()
        }

        if (mode == MODE_DETAILED) {
            return networkLines
        }

        val ipv4 =
            networkLines.firstOrNull {
                it.startsWith("IPv4:")
            }

        val ipv6 =
            networkLines.firstOrNull {
                it.startsWith("IPv6:")
            }

        val dns =
            networkLines.firstOrNull {
                it.startsWith("DNS:")
            }

        val lines = mutableListOf<String>()

        when {
            network != null && ipv4 != null ->
                lines.add("$network | $ipv4")

            network != null ->
                lines.add(network)

            ipv4 != null ->
                lines.add(ipv4)
        }

        ipv6?.let {
            lines.add(it)
        }

        dns?.let {
            lines.add(it)
        }

        return lines
    }

    private fun buildPowerLines(
        mode: String,
        deviceType: String,
        battery: BatterySnapshot,
        thermalLines: List<String>
    ): List<String> {
        val lines = mutableListOf<String>()

        when {
            deviceType.startsWith("Emulator") -> {
                lines.add("Power: Emulated")
            }

            deviceType == "TV" -> {
                lines.add("Power: AC")
            }

            deviceType == "Automotive" -> {
                lines.add("Power: Vehicle")
            }

            !battery.present -> {
                lines.add("Power: External")
            }

            battery.percent >= 0 -> {
                when (mode) {
                    MODE_COMPACT -> {
                        val parts = mutableListOf<String>()

                        parts.add(
                            "${battery.percent}% [${battery.state}]"
                        )

                        if (battery.tempC != null) {
                            parts.add("Temp: ${formatOneDecimal(battery.tempC)}°C")
                        }

                        if (battery.voltageV != null) {
                            parts.add("Voltage: ${formatThreeDecimals(battery.voltageV)} V")
                        }

                        lines.add(
                            "Battery: ${parts.joinToString(" | ")}"
                        )
                    }

                    MODE_DETAILED -> {
                        lines.add(
                            "Battery: ${battery.percent}% [${battery.state}]"
                        )

                        battery.tempC?.let {
                            lines.add(
                                "Battery Temp: ${formatOneDecimal(it)}°C"
                            )
                        }

                        battery.voltageV?.let {
                            lines.add(
                                "Battery Voltage: ${formatThreeDecimals(it)} V"
                            )
                        }

                        battery.source?.let {
                            lines.add("Power Source: $it")
                        }
                    }

                    else -> {
                        lines.add(
                            "Battery: ${battery.percent}% [${battery.state}]"
                        )

                        battery.tempC?.let {
                            lines.add(
                                "Temp: ${formatOneDecimal(it)}°C"
                            )
                        }
                    }
                }
            }

            else -> {
                lines.add("Power: Unknown")
            }
        }

        if (
            deviceType.startsWith("Emulator")
        ) {
            return lines
        }

        if (mode == MODE_COMPACT && thermalLines.isNotEmpty()) {
            lines.add(
                thermalLines
                    .map {
                        it.replaceFirst(
                            "Thermal Headroom:",
                            "Headroom:"
                        )
                    }
                    .joinToString(" | ")
            )
        } else {
            thermalLines.forEach { line ->
                if (mode == MODE_WATCH) {
                    lines.add(
                        line.replaceFirst(
                            "Thermal Headroom:",
                            "Headroom:"
                        )
                    )
                } else {
                    lines.add(line)
                }
            }
        }

        return lines
    }

    private fun buildAndroidLines(
        mode: String,
        nativeArchitecture: String?,
        allAbis: String
    ): List<String> {
        return when (mode) {
            MODE_WATCH -> {
                listOf(
                    "API: ${Build.VERSION.SDK_INT}",
                    "Patch: ${Build.VERSION.SECURITY_PATCH}"
                )
            }

            MODE_MINIMAL -> {
                listOf(
                    "Android API: ${Build.VERSION.SDK_INT}"
                )
            }

            MODE_DETAILED -> {
                buildList {
                    add("Android API: ${Build.VERSION.SDK_INT}")
                    add("Patch: ${Build.VERSION.SECURITY_PATCH}")
                    add("Build: ${Build.DISPLAY}")
                    add("Device: ${Build.DEVICE}")

                    nativeArchitecture?.let {
                        add("Architecture: $it")
                    }

                    add("ABI: $allAbis")
                    add(getRuntimeLine())
                    add("Process: ${getProcessBitness()}")
                }
            }

            else -> {
                buildList {
                    add(
                        "Android API: ${Build.VERSION.SDK_INT} | " +
                                "Patch: ${Build.VERSION.SECURITY_PATCH}"
                    )

                    add("Build: ${Build.DISPLAY}")

                    // Process bitness matters on devices like the onn
                    // stick: a 64-bit-capable CPU (armv8l) running a
                    // 32-bit userspace otherwise looks contradictory.
                    val process = "Process: ${getProcessBitness()}"

                    if (nativeArchitecture != null) {
                        add(
                            "Architecture: $nativeArchitecture | ABI: $allAbis | $process"
                        )
                    } else {
                        add("ABI: $allAbis | $process")
                    }

                    add(getRuntimeLine())
                }
            }
        }
    }

    private fun buildSecurityLines(
        mode: String,
        selinux: String,
        bootloader: String?,
        verifiedBoot: String?,
        root: String?
    ): List<String> {
        val lines = mutableListOf<String>()

        lines.add(selinux)

        if (mode == MODE_WATCH) {
            val bootParts = mutableListOf<String>()

            bootloader?.let {
                bootParts.add(
                    it.replaceFirst(
                        "Bootloader:",
                        "Boot:"
                    )
                )
            }

            verifiedBoot?.let {
                bootParts.add(
                    it.replaceFirst(
                        "Verified Boot:",
                        "VB:"
                    )
                )
            }

            if (bootParts.isNotEmpty()) {
                lines.add(bootParts.joinToString(" | "))
            }
        } else if (mode == MODE_COMPACT) {
            when {
                bootloader != null && verifiedBoot != null ->
                    lines.add("$bootloader | $verifiedBoot")

                bootloader != null ->
                    lines.add(bootloader)

                verifiedBoot != null ->
                    lines.add(verifiedBoot)
            }
        } else {
            bootloader?.let {
                lines.add(it)
            }

            verifiedBoot?.let {
                lines.add(it)
            }
        }

        root?.let {
            lines.add(it)
        }

        return lines
    }

    private fun buildMiscLines(
        mode: String,
        locale: String
    ): List<String> {
        return when (mode) {
            MODE_MINIMAL,
            MODE_WATCH -> {
                listOf(
                    "Locale: $locale"
                )
            }

            MODE_DETAILED -> {
                listOf(
                    "Locale: $locale",
                    "App: Fastfetch Android ${getAppVersion()}",
                    "Features: ${traced("ff:features") { getFeatureSummary() }}"
                )
            }

            else -> {
                listOf(
                    "Locale: $locale",
                    "App: Fastfetch Android ${getAppVersion()}",
                    "Features: ${traced("ff:features") { getFeatureSummary() }}"
                )
            }
        }
    }

    // ==================================================
    // POWER / BATTERY
    // ==================================================

    private data class BatterySnapshot(
        val present: Boolean,
        val percent: Int,
        val state: String,
        val tempC: Double?,
        val voltageV: Double?,
        val source: String?
    )

    private fun getBatterySnapshot(): BatterySnapshot {
        val batteryIntent =
            registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )

        if (batteryIntent == null) {
            return BatterySnapshot(
                present = false,
                percent = -1,
                state = "Unknown",
                tempC = null,
                voltageV = null,
                source = null
            )
        }

        val present =
            batteryIntent.getBooleanExtra(
                BatteryManager.EXTRA_PRESENT,
                true
            )

        val rawLevel =
            batteryIntent.getIntExtra(
                BatteryManager.EXTRA_LEVEL,
                -1
            )

        val scale =
            batteryIntent.getIntExtra(
                BatteryManager.EXTRA_SCALE,
                100
            )

        val percent =
            if (rawLevel >= 0 && scale > 0) {
                (rawLevel * 100f / scale).roundToInt()
            } else {
                -1
            }

        val status =
            batteryIntent.getIntExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN
            )

        val plugged =
            batteryIntent.getIntExtra(
                BatteryManager.EXTRA_PLUGGED,
                0
            )

        val source =
            when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                else -> null
            }

        val baseState =
            when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                BatteryManager.BATTERY_STATUS_FULL -> "Full"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not Charging"
                else -> "Unknown"
            }

        val state =
            if (
                source != null &&
                (
                        status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                status == BatteryManager.BATTERY_STATUS_FULL
                        )
            ) {
                "$baseState, $source"
            } else {
                baseState
            }

        val tempRaw =
            batteryIntent.getIntExtra(
                BatteryManager.EXTRA_TEMPERATURE,
                -1
            )

        val tempC =
            if (tempRaw in 50..800) {
                tempRaw / 10.0
            } else {
                null
            }

        val voltageMv =
            batteryIntent.getIntExtra(
                BatteryManager.EXTRA_VOLTAGE,
                -1
            )

        val voltageV =
            if (voltageMv in 2500..6000) {
                voltageMv / 1000.0
            } else {
                null
            }

        return BatterySnapshot(
            present = present,
            percent = percent,
            state = state,
            tempC = tempC,
            voltageV = voltageV,
            source = source
        )
    }

    // ==================================================
    // DEVICE TYPE / EMULATORS
    // ==================================================

    private fun getDeviceType(): String {
        val fixedType = cachedDeviceType ?: synchronized(Companion) {
            cachedDeviceType ?: readFixedDeviceType().also { cachedDeviceType = it }
        }

        // Phone/tablet classification follows configuration width (folding,
        // external displays); only build identity and package features are fixed.
        return fixedType.ifEmpty {
            if (resources.configuration.smallestScreenWidthDp >= 600) "Tablet" else "Phone"
        }
    }

    private fun readFixedDeviceType(): String {
        val pm = packageManager

        val identity =
            listOf(
                Build.MANUFACTURER,
                Build.BRAND,
                Build.MODEL,
                Build.DEVICE,
                Build.PRODUCT,
                Build.HARDWARE,
                Build.BOARD,
                Build.FINGERPRINT
            )
                .joinToString(" ")
                .lowercase(Locale.US)

        val primaryAbi =
            Build.SUPPORTED_ABIS
                .firstOrNull()
                ?.lowercase(Locale.US)
                ?: ""

        val isX86 =
            primaryAbi == "x86" ||
                    primaryAbi == "x86_64"

        val hasVrHeadTracking =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    pm.hasSystemFeature(
                        PackageManager.FEATURE_VR_HEADTRACKING
                    )

        val looksLikeQuest =
            identity.contains("quest") ||
                    identity.contains("oculus")

        if (hasVrHeadTracking || looksLikeQuest) {
            return "VR Headset"
        }

        val hasBlueStacksName =
            identity.contains("bluestacks") ||
                    identity.contains("bstack") ||
                    identity.contains("bstacks")

        val looksLikeBlueStacksSamsungSpoof =
            isX86 &&
                    Build.MANUFACTURER.equals(
                        "samsung",
                        ignoreCase = true
                    ) &&
                    (
                            Build.MODEL.startsWith(
                                "SM-",
                                ignoreCase = true
                            ) ||
                                    Build.HARDWARE.contains(
                                        "exynos",
                                        ignoreCase = true
                                    ) ||
                                    Build.BOARD.contains(
                                        "universal",
                                        ignoreCase = true
                                    )
                            )

        if (
            hasBlueStacksName ||
            looksLikeBlueStacksSamsungSpoof
        ) {
            return "Emulator (BlueStacks)"
        }

        val looksLikeEmulator =
            Build.FINGERPRINT.startsWith(
                "generic",
                ignoreCase = true
            ) ||
                    Build.FINGERPRINT.startsWith(
                        "unknown",
                        ignoreCase = true
                    ) ||
                    Build.MODEL.contains(
                        "google_sdk",
                        ignoreCase = true
                    ) ||
                    Build.MODEL.contains(
                        "emulator",
                        ignoreCase = true
                    ) ||
                    Build.MODEL.contains(
                        "Android SDK built for",
                        ignoreCase = true
                    ) ||
                    Build.MANUFACTURER.contains(
                        "Genymotion",
                        ignoreCase = true
                    ) ||
                    Build.HARDWARE.contains(
                        "goldfish",
                        ignoreCase = true
                    ) ||
                    Build.HARDWARE.contains(
                        "ranchu",
                        ignoreCase = true
                    ) ||
                    Build.HARDWARE.contains(
                        "vbox",
                        ignoreCase = true
                    ) ||
                    Build.PRODUCT.contains(
                        "sdk",
                        ignoreCase = true
                    )

        if (looksLikeEmulator) {
            return if (
                identity.contains("genymotion")
            ) {
                "Emulator (Genymotion)"
            } else {
                "Emulator"
            }
        }

        if (
            pm.hasSystemFeature(
                PackageManager.FEATURE_WATCH
            )
        ) {
            return "Watch"
        }

        if (
            pm.hasSystemFeature(
                PackageManager.FEATURE_AUTOMOTIVE
            )
        ) {
            return "Automotive"
        }

        if (
            pm.hasSystemFeature(
                PackageManager.FEATURE_LEANBACK
            )
        ) {
            return "TV"
        }

        return ""
    }

    // ==================================================
    // DEVICE / RUNTIME HELPERS
    // ==================================================

    private fun getSocName(): String {
        cachedSocName?.let { return it }

        return synchronized(Companion) {
            cachedSocName ?: readSocName().also { cachedSocName = it }
        }
    }

    private fun readSocName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manufacturer =
                Build.SOC_MANUFACTURER.trim()

            val model =
                Build.SOC_MODEL.trim()

            val combined =
                "$manufacturer $model".trim()

            if (
                combined.isNotBlank() &&
                combined.lowercase(Locale.US) != "unknown unknown"
            ) {
                return combined
            }
        }

        val hardware = Build.HARDWARE.trim()

        return if (hardware.isBlank()) {
            "Unknown"
        } else {
            hardware
        }
    }

    private fun getProcessBitness(): String {
        return if (android.os.Process.is64Bit()) {
            "64-bit"
        } else {
            "32-bit"
        }
    }

    private fun getOrientationName(): String {
        return when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "Landscape"
            Configuration.ORIENTATION_PORTRAIT -> "Portrait"
            else -> "Undefined"
        }
    }

    private fun getRuntimeLine(): String {
        val vmName =
            System.getProperty("java.vm.name")
                ?.takeIf { it.isNotBlank() }
                ?: "Android Runtime"

        val vmVersion =
            System.getProperty("java.vm.version")
                ?.takeIf { it.isNotBlank() }

        return if (vmVersion != null) {
            "Runtime: $vmName $vmVersion"
        } else {
            "Runtime: $vmName"
        }
    }

    private fun getSensorCount(): Int {
        return try {
            val sensorManager =
                getSystemService(SENSOR_SERVICE) as SensorManager

            sensorManager
                .getSensorList(Sensor.TYPE_ALL)
                .size
        } catch (_: Exception) {
            0
        }
    }

    private fun getFeatureSummary(): String {
        val fixedFeatures = cachedStaticFeatures ?: synchronized(Companion) {
            cachedStaticFeatures ?: readStaticFeatures().also { cachedStaticFeatures = it }
        }
        val features = fixedFeatures.toMutableList()

        // USB cameras can appear after startup. Keep Camera between BLE and Touch.
        if (hasCamera()) {
            val cameraIndex = features.indexOfFirst { it == "Touch" || it == "GPS" }
            features.add(if (cameraIndex < 0) features.size else cameraIndex, "Camera")
        }

        return if (features.isEmpty()) "None reported" else features.joinToString(", ")
    }

    private fun readStaticFeatures(): List<String> {
        val pm = packageManager
        val features = mutableListOf<String>()

        if (
            pm.hasSystemFeature(
                PackageManager.FEATURE_WIFI
            )
        ) {
            features.add("Wi-Fi")
        }

        if (
            pm.hasSystemFeature(
                PackageManager.FEATURE_BLUETOOTH
            )
        ) {
            features.add("Bluetooth")
        }

        if (
            pm.hasSystemFeature(
                PackageManager.FEATURE_BLUETOOTH_LE
            )
        ) {
            features.add("BLE")
        }

        if (
            pm.hasSystemFeature(
                PackageManager.FEATURE_TOUCHSCREEN
            )
        ) {
            features.add("Touch")
        }

        if (
            pm.hasSystemFeature(
                PackageManager.FEATURE_LOCATION_GPS
            )
        ) {
            features.add("GPS")
        }

        return features
    }

    /**
     * FEATURE_CAMERA_ANY is also declared by devices that only support
     * plugging in a USB webcam (the onn Google TV stick reports it with
     * zero cameras), so count the cameras that actually exist. This
     * also picks up a USB webcam as soon as one is plugged in.
     */
    private fun hasCamera(): Boolean {
        return try {
            getSystemService(CameraManager::class.java)
                .cameraIdList
                .isNotEmpty()
        } catch (_: Exception) {
            // Camera service unavailable: fall back to built-in cameras
            // only, never "external camera supported".
            packageManager.hasSystemFeature(
                PackageManager.FEATURE_CAMERA
            ) || packageManager.hasSystemFeature(
                PackageManager.FEATURE_CAMERA_FRONT
            )
        }
    }

    private fun getAppVersion(): String {
        cachedAppVersion?.let { return it }

        return synchronized(Companion) {
            cachedAppVersion ?: readAppVersion().also {
                if (it != "Unknown") {
                    cachedAppVersion = it
                }
            }
        }
    }

    private fun readAppVersion(): String {
        return try {
            @Suppress("DEPRECATION")
            packageManager
                .getPackageInfo(packageName, 0)
                .versionName
                ?.takeIf { it.isNotBlank() }
                ?: "Unknown"
        } catch (_: Exception) {
            "Unknown"
        }
    }

    // ==================================================
    // OPENGL / FILESYSTEM
    // ==================================================

    private fun cleanOpenGlValue(line: String?): String? {
        if (line == null) {
            return null
        }

        var value =
            line.substringAfter(
                ":",
                ""
            ).trim()

        if (value.isBlank()) {
            return null
        }

        if (
            value.startsWith("OpenGL ES ")
        ) {
            value =
                value.removePrefix(
                    "OpenGL ES "
                ).trim()
        }

        return value
    }

    private fun getOpenGlVersion(value: String?): String? {
        if (value == null) {
            return null
        }

        return Regex(
            """^(\d+(?:\.\d+)+)"""
        )
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun getFileSystemType(): String {
        cachedFileSystemType?.let { return it }

        return synchronized(Companion) {
            cachedFileSystemType ?: readFileSystemType().also {
                if (it != "Unknown") {
                    cachedFileSystemType = it
                }
            }
        }
    }

    private fun readFileSystemType(): String {
        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.O
        ) {
            return "Unknown"
        }

        return try {
            val store =
                Files.getFileStore(
                    filesDir.toPath()
                )

            val type = store.type().trim()

            if (type.isBlank()) {
                "Unknown"
            } else {
                type
            }
        } catch (_: Exception) {
            "Unknown"
        }
    }

    // ==================================================
    // NETWORK
    // ==================================================

    private fun getNetworkLines(): List<String> {
        val lines = mutableListOf<String>()

        val connectivityManager =
            getSystemService(
                ConnectivityManager::class.java
            )

        val activeNetwork =
            connectivityManager.activeNetwork
                ?: return lines

        val properties =
            connectivityManager
                .getLinkProperties(activeNetwork)
                ?: return lines

        val capabilities =
            connectivityManager
                .getNetworkCapabilities(activeNetwork)

        val connectionType =
            when {
                capabilities?.hasTransport(
                    NetworkCapabilities.TRANSPORT_VPN
                ) == true -> "VPN"

                capabilities?.hasTransport(
                    NetworkCapabilities.TRANSPORT_WIFI
                ) == true -> "Wi-Fi"

                capabilities?.hasTransport(
                    NetworkCapabilities.TRANSPORT_CELLULAR
                ) == true -> "Cellular"

                capabilities?.hasTransport(
                    NetworkCapabilities.TRANSPORT_ETHERNET
                ) == true -> "Ethernet"

                else -> "Network"
            }

        val interfaceName = properties.interfaceName

        if (interfaceName.isNullOrBlank()) {
            lines.add("Network: $connectionType")
        } else {
            lines.add("Network: $connectionType ($interfaceName)")
        }

        val ipv4 =
            properties.linkAddresses
                .firstOrNull { linkAddress ->
                    linkAddress.address is Inet4Address &&
                            !linkAddress.address.isLoopbackAddress
                }

        if (ipv4 != null) {
            lines.add(
                "IPv4: ${ipv4.address.hostAddress}/${ipv4.prefixLength}"
            )
        }

        val ipv6 =
            properties.linkAddresses
                .firstOrNull { linkAddress ->
                    linkAddress.address is Inet6Address &&
                            !linkAddress.address.isLoopbackAddress &&
                            !linkAddress.address.isLinkLocalAddress
                }

        // A global IPv6 address is unique to the home network, so it can
        // be hidden for screenshots and copied output.
        val hideIpv6 = settings.getHideIpv6()

        if (ipv6 != null) {
            if (hideIpv6) {
                lines.add("IPv6: Hidden")
            } else {
                lines.add(
                    "IPv6: ${ipv6.address.hostAddress}/${ipv6.prefixLength}"
                )
            }
        }

        val dns =
            properties.dnsServers
                .take(2)
                .mapNotNull { server ->
                    if (
                        hideIpv6 &&
                        ipv6 != null &&
                        server is Inet6Address &&
                        isInSamePrefix(
                            server,
                            ipv6.address,
                            ipv6.prefixLength
                        )
                    ) {
                        // The router advertising itself as DNS: its
                        // address shares the home network's prefix and
                        // would leak what the IPv6 line hides.
                        "Hidden"
                    } else {
                        server.hostAddress
                    }
                }

        if (dns.isNotEmpty()) {
            lines.add(
                "DNS: ${dns.joinToString(", ")}"
            )
        }

        return lines
    }

    /** True if both addresses share their first [prefixLength] bits. */
    private fun isInSamePrefix(
        a: InetAddress,
        b: InetAddress,
        prefixLength: Int
    ): Boolean {
        val x = a.address
        val y = b.address

        if (x.size != y.size) {
            return false
        }

        val bits = prefixLength.coerceIn(0, x.size * 8)
        val fullBytes = bits / 8

        for (i in 0 until fullBytes) {
            if (x[i] != y[i]) {
                return false
            }
        }

        val remainingBits = bits % 8

        if (remainingBits == 0) {
            return true
        }

        val mask = (0xFF shl (8 - remainingBits)) and 0xFF

        return (x[fullBytes].toInt() and mask) ==
                (y[fullBytes].toInt() and mask)
    }

    // ==================================================
    // THERMALS
    // ==================================================

    private fun getThermalLines(): List<String> {
        val lines = mutableListOf<String>()

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.Q
        ) {
            return lines
        }

        val powerManager =
            getSystemService(
                POWER_SERVICE
            ) as PowerManager

        val thermalText =
            when (powerManager.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "None"
                PowerManager.THERMAL_STATUS_LIGHT -> "Light"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
                else -> "Unknown"
            }

        lines.add("Thermal: $thermalText")

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.R
        ) {
            val now = SystemClock.elapsedRealtime()

            if (
                cachedThermalHeadroom == null ||
                now - lastThermalHeadroomRead >=
                THERMAL_HEADROOM_INTERVAL
            ) {
                lastThermalHeadroomRead = now

                try {
                    val headroom =
                        powerManager.getThermalHeadroom(0)

                    cachedThermalHeadroom =
                        if (headroom.isFinite()) {
                            headroom
                        } else {
                            null
                        }
                } catch (_: Exception) {
                    cachedThermalHeadroom = null
                }
            }

            cachedThermalHeadroom?.let { headroom ->
                lines.add(
                    "Thermal Headroom: ${
                        String.format(
                            Locale.US,
                            "%.2f",
                            headroom
                        )
                    }"
                )
            }
        }

        return lines
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

    private fun buildAndroidLogo(): SpannableStringBuilder {
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

    private fun buildPlainFastfetch(
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
    private fun renderLines(
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

    // ==================================================
    // FORMATTING
    // ==================================================

    private fun shortenKernel(
        kernelLine: String
    ): String {
        var kernel =
            kernelLine
                .removePrefix("Kernel:")
                .trim()
                .removePrefix("Linux ")
                .trim()

        val maxLength = 22

        if (kernel.length > maxLength) {
            kernel =
                kernel.take(maxLength) + "…"
        }

        return kernel
    }

    private fun formatGiB(bytes: Long): String {
        return String.format(
            Locale.US,
            "%.2f GiB",
            bytes / 1073741824.0
        )
    }

    private fun formatOneDecimal(value: Double): String {
        return String.format(
            Locale.US,
            "%.1f",
            value
        )
    }

    private fun formatThreeDecimals(value: Double): String {
        return String.format(
            Locale.US,
            "%.3f",
            value
        )
    }

    // ==================================================
    // SETTINGS DIALOG 2.0
    // ==================================================

    private fun showSettingsDialog(
        firstRun: Boolean
    ) {
        val dialog = Dialog(this)

        dialog.setContentView(
            R.layout.dialog_username
        )

        dialog.setCancelable(
            !firstRun
        )

        val dialogRoot =
            dialog.findViewById<View>(
                R.id.dialog_root
            )

        val title =
            dialog.findViewById<TextView>(
                R.id.dialog_title
            )

        val subtitle =
            dialog.findViewById<TextView>(
                R.id.dialog_subtitle
            )

        val usernameInput =
            dialog.findViewById<EditText>(
                R.id.username_input
            )

        val preview =
            dialog.findViewById<TextView>(
                R.id.username_preview
            )

        val presetSpinner =
            dialog.findViewById<Spinner>(
                R.id.preset_spinner
            )
        val showLogoSwitch =
            dialog.findViewById<Switch>(
                R.id.show_logo_switch
            )
        val hideIpv6Switch =
            dialog.findViewById<Switch>(
                R.id.hide_ipv6_switch
            )

        val accentSpinner =
            dialog.findViewById<Spinner>(
                R.id.accent_spinner
            )

        val fontSpinner =
            dialog.findViewById<Spinner>(
                R.id.font_spinner
            )

        val refreshSpinner =
            dialog.findViewById<Spinner>(
                R.id.refresh_spinner
            )

        val displaySwitch =
            dialog.findViewById<Switch>(
                R.id.module_display_switch
            )

        val hardwareSwitch =
            dialog.findViewById<Switch>(
                R.id.module_hardware_switch
            )

        val graphicsSwitch =
            dialog.findViewById<Switch>(
                R.id.module_graphics_switch
            )

        val memorySwitch =
            dialog.findViewById<Switch>(
                R.id.module_memory_switch
            )

        val networkSwitch =
            dialog.findViewById<Switch>(
                R.id.module_network_switch
            )

        val powerSwitch =
            dialog.findViewById<Switch>(
                R.id.module_power_switch
            )

        val androidSwitch =
            dialog.findViewById<Switch>(
                R.id.module_android_switch
            )

        val securitySwitch =
            dialog.findViewById<Switch>(
                R.id.module_security_switch
            )

        val miscSwitch =
            dialog.findViewById<Switch>(
                R.id.module_misc_switch
            )

        val orderInput =
            dialog.findViewById<EditText>(
                R.id.module_order_input
            )

        val reset =
            dialog.findViewById<Button>(
                R.id.reset_button
            )

        val cancel =
            dialog.findViewById<Button>(
                R.id.cancel_button
            )

        val save =
            dialog.findViewById<Button>(
                R.id.save_button
            )

        val density =
            resources.displayMetrics.density

        dialogRoot.background =
            GradientDrawable().apply {
                setColor(
                    Color.rgb(
                        24,
                        24,
                        24
                    )
                )

                cornerRadius =
                    20f * density
            }

        applyQuicksandToViewTree(
            dialogRoot
        )

        if (firstRun) {
            title.text =
                "Welcome to Fastfetch Android"

            subtitle.text =
                "Choose your username and a starting preset."

            reset.text =
                "Use defaults"

            cancel.visibility =
                View.GONE
        } else {
            title.text =
                "Fastfetch Settings"

            subtitle.text =
                if (getDeviceType() == "Watch") {
                    "Watch Compact is automatic. The other settings still apply."
                } else {
                    "Presets, modules, colors, refresh rate, and module order."
                }

            reset.text =
                "Reset"

            cancel.visibility =
                View.VISIBLE
        }

        setupSpinner(
            presetSpinner,
            PRESET_LABELS
        )

        setupSpinner(
            accentSpinner,
            ACCENT_LABELS
        )

        setupSpinner(
            fontSpinner,
            OUTPUT_FONT_LABELS
        )

        setupSpinner(
            refreshSpinner,
            REFRESH_LABELS
        )

        usernameInput.setText(
            settings.getUsername()
        )

        usernameInput.selectAll()

        presetSpinner.setSelection(
            OUTPUT_MODES.indexOf(
                settings.getOutputMode()
            ).coerceAtLeast(0)
        )

        val initialPresetPosition =
            presetSpinner.selectedItemPosition

        accentSpinner.setSelection(
            ACCENT_VALUES.indexOf(
                settings.getAccentName()
            ).coerceAtLeast(0)
        )

        fontSpinner.setSelection(
            OUTPUT_FONT_VALUES.indexOf(
                settings.getOutputFontName()
            ).coerceAtLeast(0)
        )

        refreshSpinner.setSelection(
            REFRESH_VALUES.indexOf(
                settings.getRefreshIntervalMs()
            ).coerceAtLeast(0)
        )

        showLogoSwitch.isChecked =
            settings.getShowLogo()

        hideIpv6Switch.isChecked =
            settings.getHideIpv6()

        displaySwitch.isChecked =
            settings.isModuleEnabled(
                MODULE_DISPLAY
            )

        hardwareSwitch.isChecked =
            settings.isModuleEnabled(
                MODULE_HARDWARE
            )

        graphicsSwitch.isChecked =
            settings.isModuleEnabled(
                MODULE_GRAPHICS
            )

        memorySwitch.isChecked =
            settings.isModuleEnabled(
                MODULE_MEMORY
            )

        networkSwitch.isChecked =
            settings.isModuleEnabled(
                MODULE_NETWORK
            )

        powerSwitch.isChecked =
            settings.isModuleEnabled(
                MODULE_POWER
            )

        androidSwitch.isChecked =
            settings.isModuleEnabled(
                MODULE_ANDROID
            )

        securitySwitch.isChecked =
            settings.isModuleEnabled(
                MODULE_SECURITY
            )

        miscSwitch.isChecked =
            settings.isModuleEnabled(
                MODULE_MISC
            )

        orderInput.setText(
            settings.getModuleOrder()
                .joinToString(", ")
        )

        fun updatePreview() {
            val username =
                sanitizeUsername(
                    usernameInput
                        .text
                        .toString()
                )

            preview.text =
                "$username@${Build.MODEL}"
        }

        updatePreview()

        usernameInput.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(
                    s: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int
                ) {
                    updatePreview()
                }

                override fun afterTextChanged(
                    s: Editable?
                ) {
                }
            }
        )

        save.setOnClickListener {
            val username =
                sanitizeUsername(
                    usernameInput
                        .text
                        .toString()
                )

            val outputMode =
                OUTPUT_MODES[
                    presetSpinner.selectedItemPosition
                ]

            val accent =
                ACCENT_VALUES[
                    accentSpinner.selectedItemPosition
                ]

            val outputFont =
                OUTPUT_FONT_VALUES[
                    fontSpinner.selectedItemPosition
                ]

            val refreshInterval =
                REFRESH_VALUES[
                    refreshSpinner.selectedItemPosition
                ]

            var showLogoToSave =
                showLogoSwitch.isChecked

            var displayEnabled =
                displaySwitch.isChecked

            var hardwareEnabled =
                hardwareSwitch.isChecked

            var graphicsEnabled =
                graphicsSwitch.isChecked

            var memoryEnabled =
                memorySwitch.isChecked

            var networkEnabled =
                networkSwitch.isChecked

            var powerEnabled =
                powerSwitch.isChecked

            var androidEnabled =
                androidSwitch.isChecked

            var securityEnabled =
                securitySwitch.isChecked

            var miscEnabled =
                miscSwitch.isChecked

            var normalizedOrder =
                normalizeModuleOrder(
                    orderInput
                        .text
                        .toString()
                )
                    .joinToString(",")

            /*
             * No separate "Apply preset" button anymore.
             *
             * If the preset was changed, Save applies that preset's
             * default modules/logo and saves everything in one shot.
             *
             * If the preset was NOT changed, manually customized
             * module switches and module order are preserved.
             */
            if (
                presetSpinner.selectedItemPosition !=
                initialPresetPosition
            ) {
                when (outputMode) {
                    MODE_MINIMAL -> {
                        displayEnabled = true
                        hardwareEnabled = true
                        graphicsEnabled = false
                        memoryEnabled = true
                        networkEnabled = false
                        powerEnabled = true
                        androidEnabled = false
                        securityEnabled = false
                        miscEnabled = false
                        showLogoToSave = true
                    }

                    MODE_WATCH -> {
                        displayEnabled = true
                        hardwareEnabled = true
                        graphicsEnabled = true
                        memoryEnabled = true
                        networkEnabled = true
                        powerEnabled = true
                        androidEnabled = true
                        securityEnabled = true
                        miscEnabled = true
                        showLogoToSave = false
                    }

                    else -> {
                        displayEnabled = true
                        hardwareEnabled = true
                        graphicsEnabled = true
                        memoryEnabled = true
                        networkEnabled = true
                        powerEnabled = true
                        androidEnabled = true
                        securityEnabled = true
                        miscEnabled = true
                        showLogoToSave = true
                    }
                }

                normalizedOrder =
                    DEFAULT_MODULE_ORDER
            }

            settings
                .edit()
                .putString(
                    KEY_USERNAME,
                    username
                )
                .putString(
                    KEY_OUTPUT_MODE,
                    outputMode
                )
                .putBoolean(
                    KEY_SHOW_LOGO,
                    showLogoToSave
                )
                .putBoolean(
                    KEY_HIDE_IPV6,
                    hideIpv6Switch.isChecked
                )
                .putString(
                    KEY_ACCENT,
                    accent
                )
                .putString(
                    KEY_OUTPUT_FONT,
                    outputFont
                )
                .putLong(
                    KEY_REFRESH_INTERVAL,
                    refreshInterval
                )
                .putBoolean(
                    "$KEY_MODULE_PREFIX$MODULE_DISPLAY",
                    displayEnabled
                )
                .putBoolean(
                    "$KEY_MODULE_PREFIX$MODULE_HARDWARE",
                    hardwareEnabled
                )
                .putBoolean(
                    "$KEY_MODULE_PREFIX$MODULE_GRAPHICS",
                    graphicsEnabled
                )
                .putBoolean(
                    "$KEY_MODULE_PREFIX$MODULE_MEMORY",
                    memoryEnabled
                )
                .putBoolean(
                    "$KEY_MODULE_PREFIX$MODULE_NETWORK",
                    networkEnabled
                )
                .putBoolean(
                    "$KEY_MODULE_PREFIX$MODULE_POWER",
                    powerEnabled
                )
                .putBoolean(
                    "$KEY_MODULE_PREFIX$MODULE_ANDROID",
                    androidEnabled
                )
                .putBoolean(
                    "$KEY_MODULE_PREFIX$MODULE_SECURITY",
                    securityEnabled
                )
                .putBoolean(
                    "$KEY_MODULE_PREFIX$MODULE_MISC",
                    miscEnabled
                )
                .putString(
                    KEY_MODULE_ORDER,
                    normalizedOrder
                )
                .putBoolean(
                    KEY_SETUP_COMPLETE,
                    true
                )
                .apply()

            onSettingsChanged()
            dialog.dismiss()
        }

        reset.setOnClickListener {
            settings
                .edit()
                .putString(
                    KEY_USERNAME,
                    DEFAULT_USERNAME
                )
                .putString(
                    KEY_OUTPUT_MODE,
                    MODE_COMPACT
                )
                .putBoolean(
                    KEY_SHOW_LOGO,
                    true
                )
                .putBoolean(
                    KEY_HIDE_IPV6,
                    false
                )
                .putString(
                    KEY_ACCENT,
                    ACCENT_CYAN
                )
                .putString(
                    KEY_OUTPUT_FONT,
                    OUTPUT_FONT_QUICKSAND
                )
                .putLong(
                    KEY_REFRESH_INTERVAL,
                    DEFAULT_REFRESH_INTERVAL
                )
                .putString(
                    KEY_MODULE_ORDER,
                    DEFAULT_MODULE_ORDER
                )
                .putBoolean(
                    "$KEY_MODULE_PREFIX$MODULE_DISPLAY",
                    true
                )
                .putBoolean(
                    "$KEY_MODULE_PREFIX$MODULE_HARDWARE",
                    true
                )
                .putBoolean(
                    "$KEY_MODULE_PREFIX$MODULE_GRAPHICS",
                    true
                )
                .putBoolean(
                    "$KEY_MODULE_PREFIX$MODULE_MEMORY",
                    true
                )
                .putBoolean(
                    "$KEY_MODULE_PREFIX$MODULE_NETWORK",
                    true
                )
                .putBoolean(
                    "$KEY_MODULE_PREFIX$MODULE_POWER",
                    true
                )
                .putBoolean(
                    "$KEY_MODULE_PREFIX$MODULE_ANDROID",
                    true
                )
                .putBoolean(
                    "$KEY_MODULE_PREFIX$MODULE_SECURITY",
                    true
                )
                .putBoolean(
                    "$KEY_MODULE_PREFIX$MODULE_MISC",
                    true
                )
                .putBoolean(
                    KEY_SETUP_COMPLETE,
                    true
                )
                .apply()

            onSettingsChanged()
            dialog.dismiss()
        }

        cancel.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()

        dialog.window?.apply {
            setBackgroundDrawable(
                ColorDrawable(
                    Color.TRANSPARENT
                )
            )

            addFlags(
                WindowManager.LayoutParams.FLAG_DIM_BEHIND
            )

            val params = attributes
            params.dimAmount = 0.65f
            attributes = params

            val screenWidth =
                resources
                    .displayMetrics
                    .widthPixels

            val maxWidth =
                (
                        620 *
                                resources
                                    .displayMetrics
                                    .density
                        ).toInt()

            val width =
                minOf(
                    (
                            screenWidth *
                                    0.92f
                            ).toInt(),
                    maxWidth
                )

            setLayout(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT
            )

            @Suppress("DEPRECATION")
            setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
    }

    private fun setupSpinner(
        spinner: Spinner,
        values: List<String>
    ) {
        val adapter =
            ArrayAdapter(
                this,
                R.layout.spinner_item,
                values
            )

        adapter.setDropDownViewResource(
            R.layout.spinner_dropdown_item
        )

        spinner.adapter =
            adapter
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

        // Process-wide cache for staticInfoFromJNI(). Lives in the
        // companion so it survives activity recreation (rotation, dark
        // mode toggle, folding), and the GPU is only probed once.
        @Volatile
        private var cachedStaticNativeLines: List<String>? = null

        // Build identity and installed-package metadata survive activity recreation.
        // Volatile publication also serves main-thread device-type callers.
        @Volatile
        private var cachedDeviceType: String? = null

        @Volatile
        private var cachedSocName: String? = null

        @Volatile
        private var cachedAppVersion: String? = null

        @Volatile
        private var cachedFileSystemType: String? = null

        @Volatile
        private var cachedStaticFeatures: List<String>? = null

        @Volatile
        private var cachedParsedStaticNativeLines: Map<String, String>? = null

        private val cachedManufacturer =
            Build.MANUFACTURER.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
            }

        private val cachedFirstAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown"
        private val cachedAllAbis =
            Build.SUPPORTED_ABIS
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", ")
                ?: "Unknown"

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

        // Output arrangement (see OutputLayout). The wide margin and the
        // side-by-side gap come from activity_main.xml.

        // The 44dp settings button + its 8dp margin + 4dp breathing room.
        private const val SLIM_END_MARGIN_DP =
            56

        private const val STACKED_GAP_DP =
            12

        private const val ARRANGEMENT_SLACK_DP =
            16

        private const val THERMAL_HEADROOM_INTERVAL =
            10_000L

        init {
            System.loadLibrary(
                "fastfetchandroid"
            )
        }
    }
}
