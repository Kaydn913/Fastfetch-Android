package com.kaydn.fastfetchandroid

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import java.util.Locale
import kotlin.math.roundToInt

// ==================================================
// TRACING
// ==================================================

// android.os.Trace section around refresh-thread work (shows up as ff:*
// in atrace/Perfetto; costs next to nothing when nobody is tracing).
internal inline fun <T> traced(name: String, block: () -> T): T {
    android.os.Trace.beginSection(name)
    try {
        return block()
    } finally {
        android.os.Trace.endSection()
    }
}

// ==================================================
// REFRESH RESULT
// ==================================================

// What one refresh tick hands from the refresh thread to the main thread.
data class FastfetchResult(
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

/**
 * Builds the info lines (title, header and each enabled module, in the
 * saved order and preset) from AppSettings and DeviceInfo. Runs on the
 * refresh thread and must never touch views. It takes the Activity
 * because the display, density, locale, memory and storage readings
 * come from it.
 */
class OutputBuilder(
    private val activity: Activity,
    private val settings: AppSettings,
    private val deviceInfo: DeviceInfo,
    // For the plain-text copy, which puts the ASCII logo next to the info.
    private val renderer: OutputRenderer
) {

    fun shouldShowLogo(watchStyle: Boolean): Boolean {
        return !watchStyle && settings.getShowLogo()
    }

    // ==================================================
    // OUTPUT BUILDER
    // ==================================================

    // Runs on the refresh thread. Must not touch views.
    fun buildFastfetchOutput(): FastfetchResult {
        val deviceType = traced("ff:deviceType") { deviceInfo.getDeviceType() }
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
        val staticNative = traced("ff:jni") { deviceInfo.getParsedStaticNativeLines() }
        val dynamicNative = traced("ff:jni") { deviceInfo.getDynamicNativeLines() }

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
                val displayMode = activity.windowManager.defaultDisplay.mode

                "${displayMode.physicalWidth}x${displayMode.physicalHeight} @ " +
                        "${displayMode.refreshRate.roundToInt()} Hz"
            }

            groups[MODULE_DISPLAY] =
                buildDisplayLines(
                    mode = mode,
                    displayText = displayText,
                    density = activity.resources.displayMetrics.densityDpi,
                    orientation = deviceInfo.getOrientationName()
                )
        }

        if (settings.isModuleEnabled(MODULE_HARDWARE)) {
            groups[MODULE_HARDWARE] =
                buildHardwareLines(
                    mode = mode,
                    soc = traced("ff:soc") { deviceInfo.getSocName() },
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
                    activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

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
                val statFs = StatFs(activity.filesDir.absolutePath)
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
                    fileSystem = deviceInfo.getFileSystemType()
                )
        }

        if (settings.isModuleEnabled(MODULE_NETWORK)) {
            groups[MODULE_NETWORK] =
                buildNetworkOutput(
                    mode = mode,
                    networkLines = traced("ff:network") { deviceInfo.getNetworkLines() }
                )
        }

        if (settings.isModuleEnabled(MODULE_POWER)) {
            groups[MODULE_POWER] =
                buildPowerLines(
                    mode = mode,
                    deviceType = deviceType,
                    battery = traced("ff:battery") { deviceInfo.getBatterySnapshot() },
                    thermalLines = traced("ff:thermal") { deviceInfo.getThermalLines() }
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
                    locale = activity.resources.configuration.locales[0].toLanguageTag()
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
                renderer.buildPlainFastfetch(infoLines)
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
            lines.add("Sensors: ${deviceInfo.getSensorCount()}")
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
        battery: DeviceInfo.BatterySnapshot,
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
                    add(deviceInfo.getRuntimeLine())
                    add("Process: ${deviceInfo.getProcessBitness()}")
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
                    val process = "Process: ${deviceInfo.getProcessBitness()}"

                    if (nativeArchitecture != null) {
                        add(
                            "Architecture: $nativeArchitecture | ABI: $allAbis | $process"
                        )
                    } else {
                        add("ABI: $allAbis | $process")
                    }

                    add(deviceInfo.getRuntimeLine())
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
                    "App: Fastfetch Android ${deviceInfo.getAppVersion()}",
                    "Features: ${traced("ff:features") { deviceInfo.getFeatureSummary() }}"
                )
            }

            else -> {
                listOf(
                    "Locale: $locale",
                    "App: Fastfetch Android ${deviceInfo.getAppVersion()}",
                    "Features: ${traced("ff:features") { deviceInfo.getFeatureSummary() }}"
                )
            }
        }
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

    companion object {
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
    }
}
