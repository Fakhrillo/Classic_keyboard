package com.fakhrillo.classickeyboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders each keyboard page to PNGs under build/renders and checks key hit-testing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderTest {

    private fun newView(): TypewriterKeyboardView {
        val v = TypewriterKeyboardView(RuntimeEnvironment.getApplication())
        v.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        return v
    }

    private fun save(v: View, name: String) {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        val dir = File("build/renders").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tap(v: View, action: Int, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        v.dispatchTouchEvent(MotionEvent.obtain(t, t, action, x, y, 0))
    }

    @Test
    fun rendersPages() {
        val v = newView()
        save(v, "letters")
        v.page = Layouts.symbols
        save(v, "symbols")
        v.page = Layouts.emojiPages[0]
        save(v, "emoji")
    }

    @Test
    fun keyDownTypesAndSinksTheCap() {
        val v = newView()
        val typed = ArrayList<String>()
        v.listener = object : TypewriterKeyboardView.Listener {
            override fun onKey(key: Key) { typed += if (key.code == Code.TEXT) key.label else key.code.name }
            override fun onSpaceLongPress() = Unit
        }
        // Top-left key, a key in the staggered second row, the space bar, and backspace.
        fun strike(index: Int, shot: String? = null) {
            val c = v.keyCenter(index)
            tap(v, MotionEvent.ACTION_DOWN, c.x, c.y)
            if (shot != null) { v.draw(Canvas(Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888))); save(v, shot) }
            tap(v, MotionEvent.ACTION_UP, c.x, c.y)
        }
        strike(0)
        strike(14, "pressed")
        strike(30)
        strike(27)
        assertEquals(listOf("Q", "G", "SPACE", "BACKSPACE"), typed)
    }
}
