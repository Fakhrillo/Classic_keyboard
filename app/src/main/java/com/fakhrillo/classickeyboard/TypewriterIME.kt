package com.fakhrillo.classickeyboard

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import kotlin.random.Random

class TypewriterIME : InputMethodService(), TypewriterKeyboardView.Listener {

    private var view: TypewriterKeyboardView? = null
    private var shift = ShiftState.OFF
    private var emojiPage = 0
    private var lastShiftTap = 0L
    private var lastSpace = 0L

    private var pool: SoundPool? = null
    private var sndKey = 0
    private var sndSpace = 0
    private var sndDing = 0
    private var soundOn = true

    override fun onCreate() {
        super.onCreate()
        pool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build().also {
                sndKey = it.load(this, R.raw.key, 1)
                sndSpace = it.load(this, R.raw.space, 1)
                sndDing = it.load(this, R.raw.ding, 1)
            }
    }

    override fun onDestroy() {
        pool?.release()
        pool = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        // Blend the system navigation bar into the keyboard body.
        window?.window?.let { w ->
            @Suppress("DEPRECATION")
            w.navigationBarColor = 0xFFDBDBE3.toInt()
            if (Build.VERSION.SDK_INT >= 26) {
                @Suppress("DEPRECATION")
                w.decorView.systemUiVisibility = w.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
        return TypewriterKeyboardView(this).also {
            it.listener = this
            view = it
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val prefs = getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        soundOn = prefs.getBoolean(Prefs.SOUND, true)
        view?.hapticsEnabled = prefs.getBoolean(Prefs.VIBRATE, true)

        val numeric = when (info.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME -> true
            else -> false
        }
        view?.page = if (numeric) Layouts.symbols else Layouts.letters
        shift = ShiftState.OFF
        autoShift()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        autoShift()
    }

    /** Follows the editor's capitalisation hints unless caps lock is on. */
    private fun autoShift() {
        if (shift != ShiftState.LOCK) {
            val info = currentInputEditorInfo
            val caps = if (info != null && info.inputType != InputType.TYPE_NULL) {
                currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0
            } else 0
            shift = if (caps != 0) ShiftState.ONCE else ShiftState.OFF
        }
        view?.shift = shift
    }

    override fun onKey(key: Key) {
        val ic = currentInputConnection
        val v = view ?: return
        val now = SystemClock.uptimeMillis()
        when (key.code) {
            Code.TEXT -> {
                play(sndKey)
                val letter = v.page === Layouts.letters
                val text = if (letter && shift == ShiftState.OFF) key.label.lowercase() else key.label
                ic?.commitText(text, 1)
                if (letter) autoShift()
            }
            Code.SPACE -> {
                play(sndSpace)
                val before = ic?.getTextBeforeCursor(2, 0)
                if (now - lastSpace < 500 && before != null && before.length == 2 &&
                    before[1] == ' ' && before[0].isLetterOrDigit()
                ) {
                    ic.deleteSurroundingText(1, 0)
                    ic.commitText(". ", 1)
                    lastSpace = 0
                } else {
                    ic?.commitText(" ", 1)
                    lastSpace = now
                }
                if (v.page === Layouts.symbols || v.page === Layouts.symbolsAlt) v.page = Layouts.letters
                autoShift()
            }
            Code.BACKSPACE -> {
                play(sndKey, 0.5f)
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            }
            Code.ENTER -> {
                play(sndDing, 0.6f)
                sendKeyChar('\n')
            }
            Code.SHIFT -> {
                play(sndKey, 0.5f)
                shift = when {
                    shift == ShiftState.LOCK -> ShiftState.OFF
                    now - lastShiftTap < 350 -> ShiftState.LOCK
                    shift == ShiftState.ONCE -> ShiftState.OFF
                    else -> ShiftState.ONCE
                }
                lastShiftTap = now
                v.shift = shift
            }
            Code.LETTERS -> switchTo(Layouts.letters)
            Code.SYMBOLS -> switchTo(Layouts.symbols)
            Code.SYMBOLS_ALT -> switchTo(Layouts.symbolsAlt)
            Code.EMOJI -> switchTo(Layouts.emojiPages[emojiPage])
            Code.EMOJI_NEXT -> {
                emojiPage = (emojiPage + 1) % Layouts.emojiPages.size
                switchTo(Layouts.emojiPages[emojiPage])
            }
        }
        if (key.code != Code.SPACE) lastSpace = 0
    }

    private fun switchTo(page: Page) {
        play(sndKey, 0.5f)
        view?.page = page
    }

    override fun onSpaceLongPress() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    private fun play(id: Int, volume: Float = 0.8f) {
        if (!soundOn || id == 0) return
        // A little pitch jitter keeps repeated strikes from sounding mechanical in the wrong way.
        pool?.play(id, volume, volume, 1, 0, 0.94f + Random.nextFloat() * 0.12f)
    }
}

object Prefs {
    const val NAME = "prefs"
    const val SOUND = "sound"
    const val VIBRATE = "vibrate"
}
