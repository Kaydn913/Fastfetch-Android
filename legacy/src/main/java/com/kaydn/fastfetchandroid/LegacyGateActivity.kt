package com.kaydn.fastfetchandroid

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
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

        val density = resources.displayMetrics.density
        val padding = (32 * density).roundToInt()
        val gap = (20 * density).roundToInt()

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
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = quicksand
        }

        // Shown as text too, since TV boxes often have no browser.
        val link = TextView(this).apply {
            text = LATEST_RELEASE_URL
            textSize = 16f
            setTextColor(LINK_COLOR)
            typeface = quicksand
            setPadding(
                0,
                gap,
                0,
                gap
            )
        }

        val button = Button(this).apply {
            text = getString(R.string.legacy_gate_button)
            typeface = quicksand

            setOnClickListener {
                openLatestRelease()
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(
                padding,
                padding,
                padding,
                padding
            )

            addView(message)
            addView(link)
            addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        setContentView(layout)

        // So a TV remote's D-pad lands on it.
        button.requestFocus()
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
    }
}
