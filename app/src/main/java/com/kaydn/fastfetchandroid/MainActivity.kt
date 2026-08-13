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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import com.kaydn.fastfetchandroid.databinding.ActivityMainBinding
import java.net.Inet4Address
import java.net.Inet6Address
import java.nio.file.Files
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val logoColor = Color.rgb(61, 220, 132)
    private val normalColor = Color.rgb(232, 232, 232)

    private var lastPlainOutput = ""
    private var cachedThermalHeadroom: Float? = null
    private var lastThermalHeadroomRead = 0L

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

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshFastfetch()

            val interval = getRefreshIntervalMs()
            if (interval > 0L) {
                refreshHandler.postDelayed(this, interval)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyMainUiFont()

        binding.logoText.text = buildAndroidLogo()

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

        refreshFastfetch()

        binding.root.post {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

            if (!prefs.getBoolean(KEY_SETUP_COMPLETE, false)) {
                showSettingsDialog(firstRun = true)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        restartRefreshLoop()
    }

    override fun onStop() {
        refreshHandler.removeCallbacks(refreshRunnable)
        super.onStop()
    }

    // ==================================================
    // REFRESH / UI
    // ==================================================

    private fun restartRefreshLoop() {
        refreshHandler.removeCallbacks(refreshRunnable)

        val interval = getRefreshIntervalMs()
        if (interval > 0L) {
            refreshHandler.postDelayed(refreshRunnable, interval)
        }
    }

    private fun refreshFastfetch() {
        val watchStyle = isWatchStyle()

        applyOutputFont()

        binding.logoText.text = buildAndroidLogo()
        binding.logoText.visibility =
            if (shouldShowLogo(watchStyle)) View.VISIBLE else View.GONE

        binding.sampleText.text = buildFastfetchOutput()
    }

    private fun shouldShowLogo(watchStyle: Boolean): Boolean {
        return !watchStyle && getShowLogo()
    }

    private fun isWatchStyle(): Boolean {
        return getDeviceType() == "Watch" || getOutputMode() == MODE_WATCH
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
            when (getOutputFontName()) {
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
    // OUTPUT BUILDER
    // ==================================================

    private fun buildFastfetchOutput(): CharSequence {
        val deviceType = getDeviceType()
        val requestedMode = getOutputMode()
        val mode = if (deviceType == "Watch") MODE_WATCH else requestedMode
        val watchStyle = mode == MODE_WATCH

        val username = getUsername()
        val title = "$username@${Build.MODEL}"
        val separator = "-".repeat(title.length)

        val manufacturer =
            Build.MANUFACTURER.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString()
            }

        val firstAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "Unknown"
        val allAbis =
            Build.SUPPORTED_ABIS
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", ")
                ?: "Unknown"

        @Suppress("DEPRECATION")
        val displayMode = windowManager.defaultDisplay.mode

        val displayText =
            "${displayMode.physicalWidth}x${displayMode.physicalHeight} @ " +
                    "${displayMode.refreshRate.roundToInt()} Hz"

        val density = resources.displayMetrics.densityDpi
        val orientation = getOrientationName()

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

        val memoryText =
            "${formatGiB(usedRam)} / ${formatGiB(totalRam)} ($ramPercent%)"

        val statFs = StatFs(filesDir.absolutePath)
        val totalStorage = statFs.totalBytes
        val usedStorage = totalStorage - statFs.availableBytes

        val storagePercent =
            if (totalStorage > 0) {
                (usedStorage.toDouble() / totalStorage * 100.0).roundToInt()
            } else {
                0
            }

        val fileSystem = getFileSystemType()
        val battery = getBatterySnapshot()

        val soc = getSocName()
        val locale = resources.configuration.locales[0].toLanguageTag()

        val nativeLines =
            try {
                stringFromJNI().trim().lines()
            } catch (_: Throwable) {
                emptyList()
            }

        fun nativeLine(name: String): String? {
            return nativeLines.firstOrNull {
                it.startsWith("$name:")
            }
        }

        val kernel = nativeLine("Kernel")
        val uptime = nativeLine("Uptime")
        val cpu = nativeLine("CPU")

        val gpu =
            nativeLine("GPU")
                ?.removePrefix("GPU: ")
                ?.trim()

        val gpuVendor =
            nativeLine("GPU Vendor")
                ?.removePrefix("GPU Vendor: ")
                ?.trim()

        val swap = nativeLine("Swap")

        val nativeArchitecture =
            nativeLine("Architecture")
                ?.removePrefix("Architecture: ")
                ?.trim()

        val bootloader = nativeLine("Bootloader")
        val verifiedBoot = nativeLine("Verified Boot")
        val root = nativeLine("Root")

        val rawSelinux = nativeLine("SELinux")

        val selinux =
            when (rawSelinux) {
                null,
                "SELinux: Unknown" -> "SELinux: Unknown [Restricted]"
                else -> rawSelinux
            }

        val openGlFull =
            cleanOpenGlValue(
                nativeLine("OpenGL ES")
            )

        val openGlVersion =
            getOpenGlVersion(openGlFull)

        val vulkan =
            nativeLine("Vulkan")
                ?.removePrefix("Vulkan: ")
                ?.trim()

        val networkLines = getNetworkLines()
        val thermalLines = getThermalLines()

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

        groups[MODULE_DISPLAY] =
            buildDisplayLines(
                mode = mode,
                displayText = displayText,
                density = density,
                orientation = orientation
            )

        groups[MODULE_HARDWARE] =
            buildHardwareLines(
                mode = mode,
                soc = soc,
                cpu = cpu,
                gpu = gpu,
                gpuVendor = gpuVendor
            )

        groups[MODULE_GRAPHICS] =
            buildGraphicsLines(
                mode = mode,
                openGlFull = openGlFull,
                openGlVersion = openGlVersion,
                vulkan = vulkan
            )

        groups[MODULE_MEMORY] =
            buildMemoryLines(
                mode = mode,
                memoryText = memoryText,
                swap = swap,
                usedStorage = usedStorage,
                totalStorage = totalStorage,
                storagePercent = storagePercent,
                fileSystem = fileSystem
            )

        groups[MODULE_NETWORK] =
            buildNetworkOutput(
                mode = mode,
                networkLines = networkLines
            )

        groups[MODULE_POWER] =
            buildPowerLines(
                mode = mode,
                deviceType = deviceType,
                battery = battery,
                thermalLines = thermalLines
            )

        groups[MODULE_ANDROID] =
            buildAndroidLines(
                mode = mode,
                nativeArchitecture = nativeArchitecture,
                allAbis = allAbis
            )

        groups[MODULE_SECURITY] =
            buildSecurityLines(
                mode = mode,
                selinux = selinux,
                bootloader = bootloader,
                verifiedBoot = verifiedBoot,
                root = root
            )

        groups[MODULE_MISC] =
            buildMiscLines(
                mode = mode,
                locale = locale
            )

        getModuleOrder().forEach { module ->
            if (!isModuleEnabled(module)) {
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

        lastPlainOutput =
            if (includeLogo) {
                buildPlainFastfetch(infoLines)
            } else {
                infoLines.joinToString("\n")
            }

        return renderInfo(infoLines)
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
            lines.add("Process: ${getProcessBitness()}")
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

                    if (nativeArchitecture != null) {
                        add(
                            "Architecture: $nativeArchitecture | ABI: $allAbis"
                        )
                    } else {
                        add("ABI: $allAbis")
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
                    "Features: ${getFeatureSummary()}"
                )
            }

            else -> {
                listOf(
                    "Locale: $locale",
                    "App: Fastfetch Android ${getAppVersion()}",
                    "Features: ${getFeatureSummary()}"
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

        val smallestWidth =
            resources.configuration.smallestScreenWidthDp

        return if (smallestWidth >= 600) {
            "Tablet"
        } else {
            "Phone"
        }
    }

    // ==================================================
    // DEVICE / RUNTIME HELPERS
    // ==================================================

    private fun getSocName(): String {
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
                PackageManager.FEATURE_CAMERA_ANY
            )
        ) {
            features.add("Camera")
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

        return if (features.isEmpty()) {
            "None reported"
        } else {
            features.joinToString(", ")
        }
    }

    private fun getAppVersion(): String {
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

        if (ipv6 != null) {
            lines.add(
                "IPv6: ${ipv6.address.hostAddress}/${ipv6.prefixLength}"
            )
        }

        val dns =
            properties.dnsServers
                .take(2)
                .mapNotNull { it.hostAddress }

        if (dns.isNotEmpty()) {
            lines.add(
                "DNS: ${dns.joinToString(", ")}"
            )
        }

        return lines
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
        if (lastPlainOutput.isBlank()) {
            refreshFastfetch()
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
            refreshFastfetch()
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

    private fun renderInfo(
        infoLines: List<String>
    ): SpannableStringBuilder {
        val output = SpannableStringBuilder()
        val accentColor = getAccentColor()

        infoLines.forEachIndexed { index, line ->
            if (index == 0 || index == 1) {
                output.appendColored(
                    line,
                    accentColor
                )
            } else {
                appendModuleLine(
                    output,
                    line,
                    accentColor
                )
            }

            output.append("\n")
        }

        val blocks =
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

        blocks.forEach { color ->
            output.appendColored(
                "██",
                color
            )
        }

        return output
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
    // SETTINGS STORAGE
    // ==================================================

    private fun prefs() =
        getSharedPreferences(
            PREFS_NAME,
            MODE_PRIVATE
        )

    private fun getUsername(): String {
        return prefs()
            .getString(
                KEY_USERNAME,
                DEFAULT_USERNAME
            )
            ?: DEFAULT_USERNAME
    }

    private fun getOutputMode(): String {
        val saved =
            prefs()
                .getString(
                    KEY_OUTPUT_MODE,
                    MODE_COMPACT
                )
                ?: MODE_COMPACT

        return if (saved in OUTPUT_MODES) {
            saved
        } else {
            MODE_COMPACT
        }
    }

    private fun getShowLogo(): Boolean {
        return prefs()
            .getBoolean(
                KEY_SHOW_LOGO,
                true
            )
    }

    private fun getAccentName(): String {
        return prefs()
            .getString(
                KEY_ACCENT,
                ACCENT_CYAN
            )
            ?: ACCENT_CYAN
    }

    private fun getOutputFontName(): String {
        val saved =
            prefs()
                .getString(
                    KEY_OUTPUT_FONT,
                    OUTPUT_FONT_QUICKSAND
                )
                ?: OUTPUT_FONT_QUICKSAND

        return if (saved in OUTPUT_FONT_VALUES) {
            saved
        } else {
            OUTPUT_FONT_QUICKSAND
        }
    }

    private fun getAccentColor(): Int {
        return when (getAccentName()) {
            ACCENT_GREEN ->
                Color.rgb(61, 220, 132)

            ACCENT_PURPLE ->
                Color.rgb(190, 110, 255)

            ACCENT_ORANGE ->
                Color.rgb(255, 165, 60)

            ACCENT_WHITE ->
                Color.rgb(235, 235, 235)

            else ->
                Color.rgb(0, 210, 255)
        }
    }

    private fun getRefreshIntervalMs(): Long {
        return prefs()
            .getLong(
                KEY_REFRESH_INTERVAL,
                DEFAULT_REFRESH_INTERVAL
            )
    }

    private fun isModuleEnabled(
        module: String
    ): Boolean {
        return prefs()
            .getBoolean(
                "$KEY_MODULE_PREFIX$module",
                true
            )
    }

    private fun getModuleOrder(): List<String> {
        val raw =
            prefs()
                .getString(
                    KEY_MODULE_ORDER,
                    DEFAULT_MODULE_ORDER
                )
                ?: DEFAULT_MODULE_ORDER

        return normalizeModuleOrder(raw)
    }

    private fun normalizeModuleOrder(
        raw: String
    ): List<String> {
        val requested =
            raw
                .split(",")
                .map {
                    it.trim().lowercase(Locale.US)
                }
                .filter {
                    it in VALID_MODULES
                }
                .distinct()
                .toMutableList()

        VALID_MODULES.forEach { module ->
            if (module !in requested) {
                requested.add(module)
            }
        }

        return requested
    }

    private fun sanitizeUsername(
        raw: String
    ): String {
        var username =
            raw
                .replace("\n", "")
                .replace("\r", "")
                .replace("@", "")
                .trim()

        if (
            username.length >
            MAX_USERNAME_LENGTH
        ) {
            username =
                username.take(
                    MAX_USERNAME_LENGTH
                )
        }

        if (username.isBlank()) {
            username = DEFAULT_USERNAME
        }

        return username
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
            getUsername()
        )

        usernameInput.selectAll()

        presetSpinner.setSelection(
            OUTPUT_MODES.indexOf(
                getOutputMode()
            ).coerceAtLeast(0)
        )

        val initialPresetPosition =
            presetSpinner.selectedItemPosition

        accentSpinner.setSelection(
            ACCENT_VALUES.indexOf(
                getAccentName()
            ).coerceAtLeast(0)
        )

        fontSpinner.setSelection(
            OUTPUT_FONT_VALUES.indexOf(
                getOutputFontName()
            ).coerceAtLeast(0)
        )

        refreshSpinner.setSelection(
            REFRESH_VALUES.indexOf(
                getRefreshIntervalMs()
            ).coerceAtLeast(0)
        )

        showLogoSwitch.isChecked =
            getShowLogo()

        displaySwitch.isChecked =
            isModuleEnabled(
                MODULE_DISPLAY
            )

        hardwareSwitch.isChecked =
            isModuleEnabled(
                MODULE_HARDWARE
            )

        graphicsSwitch.isChecked =
            isModuleEnabled(
                MODULE_GRAPHICS
            )

        memorySwitch.isChecked =
            isModuleEnabled(
                MODULE_MEMORY
            )

        networkSwitch.isChecked =
            isModuleEnabled(
                MODULE_NETWORK
            )

        powerSwitch.isChecked =
            isModuleEnabled(
                MODULE_POWER
            )

        androidSwitch.isChecked =
            isModuleEnabled(
                MODULE_ANDROID
            )

        securitySwitch.isChecked =
            isModuleEnabled(
                MODULE_SECURITY
            )

        miscSwitch.isChecked =
            isModuleEnabled(
                MODULE_MISC
            )

        orderInput.setText(
            getModuleOrder()
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

            prefs()
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

            refreshFastfetch()
            restartRefreshLoop()
            dialog.dismiss()
        }

        reset.setOnClickListener {
            prefs()
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

            refreshFastfetch()
            restartRefreshLoop()
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

    external fun stringFromJNI(): String

    companion object {
        private const val PREFS_NAME =
            "fastfetch_settings"

        private const val KEY_USERNAME =
            "username"

        private const val KEY_OUTPUT_MODE =
            "output_mode"

        private const val KEY_SHOW_LOGO =
            "show_logo"

        private const val KEY_ACCENT =
            "accent"

        private const val KEY_OUTPUT_FONT =
            "output_font"

        private const val KEY_REFRESH_INTERVAL =
            "refresh_interval"

        private const val KEY_MODULE_ORDER =
            "module_order"

        private const val KEY_MODULE_PREFIX =
            "module_"

        private const val KEY_SETUP_COMPLETE =
            "username_setup_complete"

        private const val DEFAULT_USERNAME =
            "android"

        private const val MAX_USERNAME_LENGTH =
            24

        private const val DEFAULT_REFRESH_INTERVAL =
            2_000L

        private const val THERMAL_HEADROOM_INTERVAL =
            10_000L

        private const val MODE_MINIMAL =
            "minimal"

        private const val MODE_COMPACT =
            "compact"

        private const val MODE_DETAILED =
            "detailed"

        private const val MODE_WATCH =
            "watch"

        private const val MODULE_DISPLAY =
            "display"

        private const val MODULE_HARDWARE =
            "hardware"

        private const val MODULE_GRAPHICS =
            "graphics"

        private const val MODULE_MEMORY =
            "memory"

        private const val MODULE_NETWORK =
            "network"

        private const val MODULE_POWER =
            "power"

        private const val MODULE_ANDROID =
            "android"

        private const val MODULE_SECURITY =
            "security"

        private const val MODULE_MISC =
            "misc"

        private const val DEFAULT_MODULE_ORDER =
            "display,hardware,graphics,memory,network,power,android,security,misc"

        private val VALID_MODULES =
            listOf(
                MODULE_DISPLAY,
                MODULE_HARDWARE,
                MODULE_GRAPHICS,
                MODULE_MEMORY,
                MODULE_NETWORK,
                MODULE_POWER,
                MODULE_ANDROID,
                MODULE_SECURITY,
                MODULE_MISC
            )

        private val OUTPUT_MODES =
            listOf(
                MODE_MINIMAL,
                MODE_COMPACT,
                MODE_DETAILED,
                MODE_WATCH
            )

        private val PRESET_LABELS =
            listOf(
                "Minimal",
                "Compact",
                "Detailed",
                "Watch Preview"
            )

        private const val ACCENT_CYAN =
            "cyan"

        private const val ACCENT_GREEN =
            "green"

        private const val ACCENT_PURPLE =
            "purple"

        private const val ACCENT_ORANGE =
            "orange"

        private const val ACCENT_WHITE =
            "white"

        private val ACCENT_VALUES =
            listOf(
                ACCENT_CYAN,
                ACCENT_GREEN,
                ACCENT_PURPLE,
                ACCENT_ORANGE,
                ACCENT_WHITE
            )

        private val ACCENT_LABELS =
            listOf(
                "Cyan",
                "Green",
                "Purple",
                "Orange",
                "White"
            )

        private const val OUTPUT_FONT_QUICKSAND =
            "quicksand"

        private const val OUTPUT_FONT_ROBOTO =
            "roboto_mono"

        private val OUTPUT_FONT_VALUES =
            listOf(
                OUTPUT_FONT_QUICKSAND,
                OUTPUT_FONT_ROBOTO
            )

        private val OUTPUT_FONT_LABELS =
            listOf(
                "Quicksand",
                "Roboto Mono"
            )

        private val REFRESH_VALUES =
            listOf(
                0L,
                1_000L,
                2_000L,
                5_000L,
                10_000L
            )

        private val REFRESH_LABELS =
            listOf(
                "Off",
                "1 second",
                "2 seconds",
                "5 seconds",
                "10 seconds"
            )

        init {
            System.loadLibrary(
                "fastfetchandroid"
            )
        }
    }
}
