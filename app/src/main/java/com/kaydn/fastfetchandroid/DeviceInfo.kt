package com.kaydn.fastfetchandroid

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.file.Files
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Reads the device's information: native (JNI) lines, battery, device
 * type, SoC and runtime, features, OpenGL and filesystem, network and
 * thermals. Most of it runs on the refresh thread; getDeviceType() is
 * also called from the main thread, which its cache is made for.
 *
 * It takes the Activity, not the application context, on purpose: the
 * orientation and the phone/tablet width check depend on the window. The
 * JNI functions stay declared in MainActivity (their C++ symbol names are
 * tied to it), so they're passed in.
 */
class DeviceInfo(
    private val activity: Activity,
    // Read on every tick, for "Hide IPv6 address".
    private val settings: AppSettings,
    private val staticInfoFromJNI: () -> String,
    private val dynamicInfoFromJNI: () -> String
) {
    // Only touched on the refresh thread (from getThermalLines).
    private var cachedThermalHeadroom: Float? = null
    private var lastThermalHeadroomRead = 0L

    // ==================================================
    // NATIVE (JNI) INFO
    // ==================================================

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

    fun getParsedStaticNativeLines(): Map<String, String> {
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
    fun getDynamicNativeLines(): List<String> {
        return try {
            dynamicInfoFromJNI()
                .trim()
                .lines()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // ==================================================
    // POWER / BATTERY
    // ==================================================

    data class BatterySnapshot(
        val present: Boolean,
        val percent: Int,
        val state: String,
        val tempC: Double?,
        val voltageV: Double?,
        val source: String?
    )

    fun getBatterySnapshot(): BatterySnapshot {
        val batteryIntent =
            activity.registerReceiver(
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

    fun getDeviceType(): String {
        val fixedType = cachedDeviceType ?: synchronized(Companion) {
            cachedDeviceType ?: readFixedDeviceType().also { cachedDeviceType = it }
        }

        // Phone/tablet classification follows configuration width (folding,
        // external displays); only build identity and package features are fixed.
        return fixedType.ifEmpty {
            if (activity.resources.configuration.smallestScreenWidthDp >= 600) "Tablet" else "Phone"
        }
    }

    private fun readFixedDeviceType(): String {
        val pm = activity.packageManager

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

    fun getSocName(): String {
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

    fun getProcessBitness(): String {
        return if (android.os.Process.is64Bit()) {
            "64-bit"
        } else {
            "32-bit"
        }
    }

    fun getOrientationName(): String {
        return when (activity.resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "Landscape"
            Configuration.ORIENTATION_PORTRAIT -> "Portrait"
            else -> "Undefined"
        }
    }

    fun getRuntimeLine(): String {
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

    fun getSensorCount(): Int {
        return try {
            val sensorManager =
                activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager

            sensorManager
                .getSensorList(Sensor.TYPE_ALL)
                .size
        } catch (_: Exception) {
            0
        }
    }

    fun getFeatureSummary(): String {
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
        val pm = activity.packageManager
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
            activity.getSystemService(CameraManager::class.java)
                .cameraIdList
                .isNotEmpty()
        } catch (_: Exception) {
            // Camera service unavailable: fall back to built-in cameras
            // only, never "external camera supported".
            activity.packageManager.hasSystemFeature(
                PackageManager.FEATURE_CAMERA
            ) || activity.packageManager.hasSystemFeature(
                PackageManager.FEATURE_CAMERA_FRONT
            )
        }
    }

    fun getAppVersion(): String {
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
            activity.packageManager
                .getPackageInfo(activity.packageName, 0)
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

    fun getFileSystemType(): String {
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
                    activity.filesDir.toPath()
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

    fun getNetworkLines(): List<String> {
        val lines = mutableListOf<String>()

        val connectivityManager =
            activity.getSystemService(
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

    fun getThermalLines(): List<String> {
        val lines = mutableListOf<String>()

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.Q
        ) {
            return lines
        }

        val powerManager =
            activity.getSystemService(
                Context.POWER_SERVICE
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

    companion object {
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

        private const val THERMAL_HEADROOM_INTERVAL =
            10_000L
    }
}
