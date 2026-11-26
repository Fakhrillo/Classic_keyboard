package com.fakhrillo.classickeyboard

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Setup screen: enable the keyboard, pick it, toggle sound/vibration, try it out. */
class MainActivity : Activity() {

    private lateinit var enableButton: Button
    private lateinit var chooseButton: Button
    private val imm get() = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        val pad = (20 * resources.displayMetrics.density).toInt()

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            fitsSystemWindows = true
        }
        fun add(v: View) = column.addView(
            v,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = pad / 2 }
        )

        add(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 30f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            setTextColor(0xFF1C1C1E.toInt())
        })
        add(TextView(this).apply {
            text = "A typewriter for your phone. Hold the space bar to switch keyboards; double-tap shift for caps lock."
            textSize = 15f
        })

        enableButton = Button(this).apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        }
        add(enableButton)
        chooseButton = Button(this).apply {
            setOnClickListener { imm.showInputMethodPicker() }
        }
        add(chooseButton)

        add(Switch(this).apply {
            text = "Typewriter sounds"
            textSize = 16f
            isChecked = prefs.getBoolean(Prefs.SOUND, true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Prefs.SOUND, on).apply() }
        })
        add(Switch(this).apply {
            text = "Vibrate on key press"
            textSize = 16f
            isChecked = prefs.getBoolean(Prefs.VIBRATE, true)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(Prefs.VIBRATE, on).apply() }
        })
        add(EditText(this).apply {
            hint = "Try it here"
            minLines = 3
            typeface = Typeface.SERIF
        })

        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refresh() // the keyboard picker is a dialog; coming back from it lands here
    }

    private fun refresh() {
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        enableButton.text = if (enabled) "1. Enabled in system settings ✓" else "1. Enable in system settings"
        chooseButton.text = if (selected) "2. Selected as current keyboard ✓" else "2. Choose Classic Keyboard"
        chooseButton.isEnabled = enabled
    }
}
