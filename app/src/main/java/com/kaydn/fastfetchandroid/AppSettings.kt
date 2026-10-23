package com.kaydn.fastfetchandroid

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import java.util.Locale

// ==================================================
// SETTINGS SCHEMA
// ==================================================
//
// Every saved setting: the prefs file, its keys, the defaults and the
// choices the settings dialog offers. The key strings are what's on
// people's devices already, so they must never change.

internal const val PREFS_NAME =
    "fastfetch_settings"

internal const val KEY_USERNAME =
    "username"

internal const val KEY_OUTPUT_MODE =
    "output_mode"

internal const val KEY_SHOW_LOGO =
    "show_logo"

internal const val KEY_HIDE_IPV6 =
    "hide_ipv6"

internal const val KEY_ACCENT =
    "accent"

internal const val KEY_OUTPUT_FONT =
    "output_font"

internal const val KEY_REFRESH_INTERVAL =
    "refresh_interval"

internal const val KEY_MODULE_ORDER =
    "module_order"

internal const val KEY_MODULE_PREFIX =
    "module_"

internal const val KEY_SETUP_COMPLETE =
    "username_setup_complete"

internal const val DEFAULT_USERNAME =
    "android"

internal const val MAX_USERNAME_LENGTH =
    24

internal const val DEFAULT_REFRESH_INTERVAL =
    2_000L

internal const val MODE_MINIMAL =
    "minimal"

internal const val MODE_COMPACT =
    "compact"

internal const val MODE_DETAILED =
    "detailed"

internal const val MODE_WATCH =
    "watch"

internal const val MODULE_DISPLAY =
    "display"

internal const val MODULE_HARDWARE =
    "hardware"

internal const val MODULE_GRAPHICS =
    "graphics"

internal const val MODULE_MEMORY =
    "memory"

internal const val MODULE_NETWORK =
    "network"

internal const val MODULE_POWER =
    "power"

internal const val MODULE_ANDROID =
    "android"

internal const val MODULE_SECURITY =
    "security"

internal const val MODULE_MISC =
    "misc"

internal const val DEFAULT_MODULE_ORDER =
    "display,hardware,graphics,memory,network,power,android,security,misc"

internal val VALID_MODULES =
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

internal val OUTPUT_MODES =
    listOf(
        MODE_MINIMAL,
        MODE_COMPACT,
        MODE_DETAILED,
        MODE_WATCH
    )

internal val PRESET_LABELS =
    listOf(
        "Minimal",
        "Compact",
        "Detailed",
        "Watch Preview"
    )

internal const val ACCENT_CYAN =
    "cyan"

internal const val ACCENT_GREEN =
    "green"

internal const val ACCENT_PURPLE =
    "purple"

internal const val ACCENT_ORANGE =
    "orange"

internal const val ACCENT_WHITE =
    "white"

internal val ACCENT_VALUES =
    listOf(
        ACCENT_CYAN,
        ACCENT_GREEN,
        ACCENT_PURPLE,
        ACCENT_ORANGE,
        ACCENT_WHITE
    )

internal val ACCENT_LABELS =
    listOf(
        "Cyan",
        "Green",
        "Purple",
        "Orange",
        "White"
    )

internal const val OUTPUT_FONT_QUICKSAND =
    "quicksand"

internal const val OUTPUT_FONT_ROBOTO =
    "roboto_mono"

internal val OUTPUT_FONT_VALUES =
    listOf(
        OUTPUT_FONT_QUICKSAND,
        OUTPUT_FONT_ROBOTO
    )

internal val OUTPUT_FONT_LABELS =
    listOf(
        "Quicksand",
        "Roboto Mono"
    )

internal val REFRESH_VALUES =
    listOf(
        0L,
        1_000L,
        2_000L,
        5_000L,
        10_000L
    )

internal val REFRESH_LABELS =
    listOf(
        "Off",
        "1 second",
        "2 seconds",
        "5 seconds",
        "10 seconds"
    )

// ==================================================
// SETTINGS STORAGE
// ==================================================

/**
 * Reads the saved settings. Every getter reads the prefs again, so a
 * change saved by the settings dialog shows up on the next refresh. Safe
 * to call from the refresh thread (SharedPreferences reads are).
 */
class AppSettings(
    context: Context
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    /** For the settings dialog's Save and Reset. */
    fun edit(): SharedPreferences.Editor {
        return prefs.edit()
    }

    fun isSetupComplete(): Boolean {
        return prefs
            .getBoolean(
                KEY_SETUP_COMPLETE,
                false
            )
    }

    fun getUsername(): String {
        return prefs
            .getString(
                KEY_USERNAME,
                DEFAULT_USERNAME
            )
            ?: DEFAULT_USERNAME
    }

    fun getOutputMode(): String {
        val saved =
            prefs
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

    fun getShowLogo(): Boolean {
        return prefs
            .getBoolean(
                KEY_SHOW_LOGO,
                true
            )
    }

    fun getHideIpv6(): Boolean {
        return prefs
            .getBoolean(
                KEY_HIDE_IPV6,
                false
            )
    }

    fun getAccentName(): String {
        return prefs
            .getString(
                KEY_ACCENT,
                ACCENT_CYAN
            )
            ?: ACCENT_CYAN
    }

    fun getOutputFontName(): String {
        val saved =
            prefs
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

    fun getAccentColor(): Int {
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

    fun getRefreshIntervalMs(): Long {
        return prefs
            .getLong(
                KEY_REFRESH_INTERVAL,
                DEFAULT_REFRESH_INTERVAL
            )
    }

    fun isModuleEnabled(
        module: String
    ): Boolean {
        return prefs
            .getBoolean(
                "$KEY_MODULE_PREFIX$module",
                true
            )
    }

    fun getModuleOrder(): List<String> {
        val raw =
            prefs
                .getString(
                    KEY_MODULE_ORDER,
                    DEFAULT_MODULE_ORDER
                )
                ?: DEFAULT_MODULE_ORDER

        return normalizeModuleOrder(raw)
    }
}

// ==================================================
// SETTINGS HELPERS
// ==================================================
//
// Plain functions with no Android types, so they're unit tested.

/**
 * Turns a typed module order ("power, display,network") into a full,
 * valid order: unknown names and repeats are dropped, and any module the
 * user left out is added at the end in its default position.
 */
internal fun normalizeModuleOrder(
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

internal fun sanitizeUsername(
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
