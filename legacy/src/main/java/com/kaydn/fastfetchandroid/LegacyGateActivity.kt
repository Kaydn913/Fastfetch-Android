package com.kaydn.fastfetchandroid

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewConfigurationCompat
import kotlin.math.roundToInt

// ==================================================
// LEGACY GATE
// ==================================================
//
// The legacy edition's launcher screen (legacy module only). On Android
// 4.1-7.0 it opens MainActivity right away. On newer Android it explains
// that the normal Fastfetch Android is the one to use, and never opens
// MainActivity or loads the native library. MainActivity checks again
// itself, for tasks reopened from recents.

class LegacyGateActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Edition.supportsThisAndroid()) {
            startActivity(
                Intent(
                    this,
                    MainActivity::class.java
                )
            )

            finish()
            return
        }

        showUnsupportedMessage()
    }

    // ==================================================
    // REFUSAL SCREEN
    // ==================================================

    private fun showUnsupportedMessage() {
        supportActionBar?.hide()

        val metrics = resources.displayMetrics
        val density = metrics.density

        // Watches and other tiny screens get smaller, centered text. Round
        // watches also need room at the edges, where the screen's corners
        // are cut off. Watches have no browser, so they get no button.
        val isWatch = isWatch()
        val isRound = isRoundScreen()
        val isSmall =
            isWatch || resources.configuration.smallestScreenWidthDp < SMALL_SCREEN_DP

        val sidePadding =
            when {
                isRound -> (metrics.widthPixels * ROUND_INSET).roundToInt()
                isSmall -> (12 * density).roundToInt()
                else -> (32 * density).roundToInt()
            }

        val verticalPadding =
            when {
                isRound -> (metrics.heightPixels * ROUND_INSET).roundToInt()
                isSmall -> (12 * density).roundToInt()
                else -> (32 * density).roundToInt()
            }

        val gap = ((if (isSmall) 10 else 20) * density).roundToInt()
        val textGravity = if (isSmall) Gravity.CENTER_HORIZONTAL else Gravity.START

        val quicksand = ResourcesCompat.getFont(
            this,
            R.font.quicksand_regular
        )

        val message = TextView(this).apply {
            text = getString(
                R.string.legacy_gate_message,
                Build.VERSION.RELEASE,
                Build.VERSION.SDK_INT
            )
            textSize = if (isSmall) 14f else 20f
            gravity = textGravity
            setTextColor(Color.WHITE)
            typeface = quicksand
        }

        // Shown as text too, since TV boxes and watches often have no
        // browser.
        val link = TextView(this).apply {
            text = LATEST_RELEASE_URL
            textSize = if (isSmall) 12f else 16f
            gravity = textGravity
            setTextColor(LINK_COLOR)
            typeface = quicksand
            setPadding(
                0,
                gap,
                0,
                gap
            )
        }

        val button =
            if (isWatch) {
                null
            } else {
                Button(this).apply {
                    text = getString(R.string.legacy_gate_button)
                    typeface = quicksand

                    setOnClickListener {
                        openLatestRelease()
                    }
                }
            }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (isSmall) Gravity.CENTER else Gravity.CENTER_VERTICAL
            setPadding(
                sidePadding,
                verticalPadding,
                sidePadding,
                verticalPadding
            )

            addView(
                message,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            addView(
                link,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            if (button != null) {
                addView(
                    button,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }

        // Scrolls when the text doesn't fit (watches, small windows).
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.BLACK)
            addView(content)
        }

        setContentView(scroll)

        // A TV remote's D-pad lands on the button. Small screens focus the
        // scroll view itself instead: on a watch it then gets the crown's
        // rotation, and the text starts at the top rather than scrolling
        // down to a focused button.
        if (button != null && !isSmall) {
            button.requestFocus()
        } else {
            scroll.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            scroll.isFocusable = true
            scroll.isFocusableInTouchMode = true
            scroll.requestFocus()
        }

        // Android 7.1 watches send the crown's turns to the focused view,
        // but ScrollView only scrolls for them from Android 8.0 on.
        if (isWatch && Build.VERSION.SDK_INT < 26) {
            scroll.setOnGenericMotionListener { _, event ->
                scrollForCrown(
                    scroll = scroll,
                    event = event
                )
            }
        }
    }

    // SOURCE_ROTARY_ENCODER and AXIS_SCROLL are API 26 constants compiled
    // into the app. This only runs on watches, which send them from 7.1 on.
    @SuppressLint("InlinedApi")
    private fun scrollForCrown(
        scroll: ScrollView,
        event: MotionEvent
    ): Boolean {
        val fromCrown =
            (event.source and InputDevice.SOURCE_ROTARY_ENCODER) ==
                InputDevice.SOURCE_ROTARY_ENCODER

        if (event.action != MotionEvent.ACTION_SCROLL || !fromCrown) {
            return false
        }

        val scrollFactor =
            ViewConfigurationCompat.getScaledVerticalScrollFactor(
                ViewConfiguration.get(this),
                this
            )

        scroll.scrollBy(
            0,
            (-event.getAxisValue(MotionEvent.AXIS_SCROLL) * scrollFactor).roundToInt()
        )

        return true
    }

    // UI_MODE_TYPE_WATCH is API 20, but it's a constant compiled into the
    // app, so older Android just never matches it.
    @SuppressLint("InlinedApi")
    private fun isWatch(): Boolean {
        val type = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK

        return type == Configuration.UI_MODE_TYPE_WATCH
    }

    private fun isRoundScreen(): Boolean {
        return Build.VERSION.SDK_INT >= 23 &&
            resources.configuration.isScreenRound
    }

    private fun openLatestRelease() {
        try {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(LATEST_RELEASE_URL)
                )
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(
                this,
                R.string.legacy_gate_no_browser,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    companion object {
        private const val LATEST_RELEASE_URL =
            "https://github.com/Kaydn913/Fastfetch-Android/releases/latest"

        private const val LINK_COLOR =
            0xFF8AB4F8.toInt()

        // Below this smallest width (dp), use the compact watch layout.
        private const val SMALL_SCREEN_DP =
            320

        // How far in from each edge a round screen is fully visible: the
        // square that fits in the circle leaves (1 - 1/sqrt 2) / 2 = 14.6%.
        private const val ROUND_INSET =
            0.146f
    }
}
