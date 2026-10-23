package com.kaydn.fastfetchandroid

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView

/**
 * The settings dialog, which is also the first-run welcome. It reads and
 * writes AppSettings, and calls onSaved after Save or Reset so the main
 * screen can apply the new settings.
 */
class SettingsDialog(
    private val activity: Activity,
    private val settings: AppSettings,
    // The UI font, shared with MainActivity's buttons.
    private val quicksandTypeface: Typeface?,
    // Only asked when the dialog opens, like before.
    private val isWatchDevice: () -> Boolean,
    private val onSaved: () -> Unit
) {

    fun show(
        firstRun: Boolean
    ) {
        val dialog = Dialog(activity)

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
            activity.resources.displayMetrics.density

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
                if (isWatchDevice()) {
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

            onSaved()
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

            onSaved()
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
                activity.resources
                    .displayMetrics
                    .widthPixels

            val maxWidth =
                (
                        620 *
                                activity.resources
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
                activity,
                R.layout.spinner_item,
                values
            )

        adapter.setDropDownViewResource(
            R.layout.spinner_dropdown_item
        )

        spinner.adapter =
            adapter
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
}
