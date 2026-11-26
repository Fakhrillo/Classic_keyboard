package com.fakhrillo.classickeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.SparseArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import kotlin.math.abs
import kotlin.math.min

enum class ShiftState { OFF, ONCE, LOCK }

/**
 * The keyboard is pre-rendered 3D artwork (see prototype/index.html): one image
 * of the whole mechanism at rest, plus one patch per key showing that key swung
 * down. This view draws the rest image, fades the patches in for pressed keys,
 * and paints the legends on top so every page can share the same artwork.
 */
@SuppressLint("ViewConstructor")
class TypewriterKeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onKey(key: Key)
        fun onSpaceLongPress()
    }

    var listener: Listener? = null
    var hapticsEnabled = true

    var page: Page = Layouts.letters
        set(value) {
            if (field === value) return
            field = value
            cancelTouches()
            bind()
            invalidate()
        }

    var shift: ShiftState = ShiftState.OFF
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** One key position in the artwork. Units are key pitches, y pointing up, as in the prototype. */
    private class Slot(
        val index: Int, val row: Int, val cx: Float, val cy: Float, val w: Float, val reach: Float,
        val patch: Rect,
    ) {
        var key: Key? = null
        var press = 0f
        var down = false
        var bitmap: Bitmap? = null
    }

    private val slots = ArrayList<Slot>()
    private val pointers = SparseArray<Slot>()
    private val rest: Bitmap
    private val artW: Int
    private val artH: Int
    private val unitPx: Float   // artwork pixels per key pitch
    private val viewCy: Float   // unit y at the vertical centre of the artwork
    private val ground: Int

    // Placement of the artwork inside the view.
    private var scale = 1f
    private var left = 0f
    private var navInset = 0
    private var lastFrame = 0L

    private val serif = Typeface.createFromAsset(context.assets, "fonts/gelasio_medium.ttf")
    private val serifFigures = Typeface.createFromAsset(context.assets, "fonts/source_serif_medium.ttf")
    private val sans = Typeface.create("sans-serif", Typeface.NORMAL)

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val src = Rect()
    private val dst = RectF()
    private val path = Path()
    private val bounds = Rect()

    private val repeatDelete = object : Runnable {
        override fun run() {
            val slot = deleteSlot ?: return
            slot.key?.let { listener?.onKey(it) }
            postDelayed(this, 55)
        }
    }
    private var deleteSlot: Slot? = null
    private var spaceHeld = false
    private val spaceLongPress = Runnable {
        if (spaceHeld) {
            spaceHeld = false
            listener?.onSpaceLongPress()
        }
    }

    init {
        val lines = context.assets.open("kb/keys.txt").bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
        val head = lines[0].split(' ')
        artW = head[0].toInt()
        artH = head[1].toInt()
        unitPx = head[2].toFloat()
        viewCy = head[3].toFloat()
        for (line in lines.drop(1)) {
            val f = line.split(' ')
            val x = f[6].toInt()
            val y = f[7].toInt()
            slots += Slot(
                f[0].toInt(), f[1].toInt(), f[2].toFloat(), f[3].toFloat(), f[4].toFloat(), f[5].toFloat(),
                Rect(x, y, x + f[8].toInt(), y + f[9].toInt()),
            )
        }
        rest = load("kb/rest.png")
        ground = rest.getPixel(4, 4)
        bind()
    }

    private fun load(name: String): Bitmap {
        val opts = BitmapFactory.Options().apply { inScaled = false }
        return context.assets.open(name).use { BitmapFactory.decodeStream(it, null, opts)!! }
    }

    /** Assigns the current page's keys to the artwork slots, row by row. */
    private fun bind() {
        val keys = page.flatten()
        check(keys.size == slots.size) { "page has ${keys.size} keys, artwork has ${slots.size}" }
        slots.forEachIndexed { i, s ->
            s.key = keys[i]
            s.press = 0f
            s.down = false
        }
    }

    // ---- layout -----------------------------------------------------------

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        @Suppress("DEPRECATION")
        val bottom = insets.systemWindowInsetBottom
        if (bottom != navInset) {
            navInset = bottom
            requestLayout()
        }
        return insets
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val maxH = resources.displayMetrics.heightPixels * 0.5f
        scale = min(w / artW.toFloat(), maxH / artH)
        left = (w - artW * scale) / 2
        setMeasuredDimension(w, (artH * scale).toInt() + navInset)
    }

    private fun px(unitX: Float) = left + (artW / 2f + (unitX - 5f) * unitPx) * scale
    private fun py(unitY: Float) = (artH / 2f - (unitY - viewCy) * unitPx) * scale

    /** Centre of a key in view pixels; used by tests. */
    internal fun keyCenter(index: Int) = slots[index].let { PointF(px(it.cx), py(it.cy)) }

    // ---- drawing ----------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else min(0.05f, (now - lastFrame) / 1000f)
        lastFrame = now
        var animating = false

        canvas.drawColor(ground)
        dst.set(left, 0f, left + artW * scale, artH * scale)
        bitmapPaint.alpha = 255
        canvas.drawBitmap(rest, null, dst, bitmapPaint)

        for (s in slots) {
            val target = if (s.down) 1f else 0f
            if (s.press != target) {
                s.press += (target - s.press) * min(1f, dt * if (s.down) 60f else 28f)
                if (abs(s.press - target) < 0.02f) s.press = target else animating = true
            }
            if (s.press > 0f) {
                val bmp = s.bitmap ?: load("kb/k${s.index}.png").also { s.bitmap = it }
                dst.set(left + s.patch.left * scale, s.patch.top * scale, left + s.patch.right * scale, s.patch.bottom * scale)
                bitmapPaint.alpha = (s.press * 255).toInt()
                canvas.drawBitmap(bmp, null, dst, bitmapPaint)
            }
        }
        for (s in slots) drawLegend(canvas, s)

        if (animating) postInvalidateOnAnimation() else lastFrame = 0L
    }

    private fun dim(color: Int, press: Float): Int {
        val k = 1f - 0.45f * press
        return Color.rgb((Color.red(color) * k).toInt(), (Color.green(color) * k).toInt(), (Color.blue(color) * k).toInt())
    }

    private fun drawLegend(canvas: Canvas, s: Slot) {
        val key = s.key ?: return
        val u = unitPx * scale
        // A pressed cap swings toward the bottom of the screen and falls into shade.
        val cx = px(s.cx - 0.025f * s.press)
        val cy = py(s.cy - s.reach * s.press)
        val ink = dim(if (key.red) INK_ON_RED else INK, s.press)
        if (key.icon != Icon.NONE) {
            iconPaint.color = ink
            canvas.save()
            canvas.translate(cx, cy)
            drawIcon(canvas, key.icon, u)
            canvas.restore()
            return
        }
        val label = key.label
        if (label.isEmpty()) return
        textPaint.color = ink
        val first = label.codePointAt(0)
        if (first > 0x2000 && first !in 0x20A0..0x20CF && first != 0x2022) {
            // Emoji: centre the glyph box on the cap.
            textPaint.typeface = Typeface.DEFAULT
            textPaint.textSize = 0.44f * u
            textPaint.alpha = (255 * (1f - 0.35f * s.press)).toInt()
            val fm = textPaint.fontMetrics
            canvas.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2, textPaint)
            textPaint.alpha = 255
            return
        }
        val cap: Float
        when {
            label.length == 1 && label[0] in 'A'..'Z' -> { textPaint.typeface = serif; cap = 0.365f }
            label == "123" -> { textPaint.typeface = serifFigures; cap = 0.32f }
            label.length > 1 -> { textPaint.typeface = sans; cap = 0.27f }
            else -> { textPaint.typeface = sans; cap = 0.34f }
        }
        // Size the text by its cap height so every face sits the same.
        textPaint.textSize = 100f
        textPaint.getTextBounds("H", 0, 1, bounds)
        textPaint.textSize = 100f * cap * u / bounds.height()
        canvas.drawText(label, cx, cy + cap * u / 2, textPaint)
    }

    private fun drawIcon(canvas: Canvas, icon: Icon, u: Float) {
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = 0.03f * u
        iconPaint.pathEffect = null
        path.rewind()
        when (icon) {
            Icon.SHIFT -> {
                path.moveTo(0f, -0.23f * u)
                path.lineTo(0.22f * u, -0.01f * u)
                path.lineTo(0.105f * u, -0.01f * u)
                path.lineTo(0.105f * u, 0.23f * u)
                path.lineTo(-0.105f * u, 0.23f * u)
                path.lineTo(-0.105f * u, -0.01f * u)
                path.lineTo(-0.22f * u, -0.01f * u)
                path.close()
                if (shift != ShiftState.OFF) iconPaint.style = Paint.Style.FILL_AND_STROKE
                canvas.drawPath(path, iconPaint)
                if (shift == ShiftState.LOCK) {
                    iconPaint.style = Paint.Style.STROKE
                    canvas.drawLine(-0.105f * u, 0.31f * u, 0.105f * u, 0.31f * u, iconPaint)
                }
            }
            Icon.BACKSPACE -> {
                iconPaint.pathEffect = CornerPathEffect(0.05f * u)
                path.moveTo(-0.265f * u, 0f)
                path.lineTo(-0.1f * u, -0.195f * u)
                path.lineTo(0.265f * u, -0.195f * u)
                path.lineTo(0.265f * u, 0.195f * u)
                path.lineTo(-0.1f * u, 0.195f * u)
                path.close()
                canvas.drawPath(path, iconPaint)
                iconPaint.pathEffect = null
                canvas.drawLine(0.01f * u, -0.075f * u, 0.16f * u, 0.075f * u, iconPaint)
                canvas.drawLine(0.16f * u, -0.075f * u, 0.01f * u, 0.075f * u, iconPaint)
            }
            Icon.ENTER -> {
                iconPaint.strokeWidth = 0.038f * u
                iconPaint.pathEffect = CornerPathEffect(0.06f * u)
                path.moveTo(0.07f * u, -0.21f * u)
                path.lineTo(0.25f * u, -0.21f * u)
                path.lineTo(0.25f * u, 0.09f * u)
                path.lineTo(-0.24f * u, 0.09f * u)
                canvas.drawPath(path, iconPaint)
                iconPaint.pathEffect = null
                path.rewind()
                path.moveTo(-0.1f * u, -0.06f * u)
                path.lineTo(-0.25f * u, 0.09f * u)
                path.lineTo(-0.1f * u, 0.24f * u)
                canvas.drawPath(path, iconPaint)
            }
            Icon.EMOJI -> {
                canvas.drawCircle(0f, 0f, 0.2f * u, iconPaint)
                iconPaint.style = Paint.Style.FILL
                dst.set(-0.125f * u, -0.09f * u, 0.125f * u, 0.16f * u)
                path.addArc(dst, 0f, 180f)
                path.quadTo(0f, 0.085f * u, 0.125f * u, 0.035f * u)
                canvas.drawPath(path, iconPaint)
                canvas.drawCircle(-0.075f * u, -0.065f * u, 0.026f * u, iconPaint)
                canvas.drawCircle(0.075f * u, -0.065f * u, 0.026f * u, iconPaint)
            }
            Icon.NONE -> Unit
        }
    }

    // ---- touch ------------------------------------------------------------

    private fun findKey(x: Float, y: Float): Slot? {
        if (y > artH * scale) return null
        // Rows are evenly spaced, so the nearest row wins, then the nearest cap in it.
        val row = slots.minByOrNull { abs(py(it.cy) - y) }?.row ?: return null
        var best: Slot? = null
        var bestDist = Float.MAX_VALUE
        for (s in slots) {
            if (s.row != row) continue
            val dist = abs(x - px(s.cx)) - s.w / 2 * unitPx * scale
            if (dist < bestDist) {
                bestDist = dist
                best = s
            }
        }
        return if (bestDist < unitPx * scale) best else null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val slot = findKey(e.getX(i), e.getY(i)) ?: return true
                val key = slot.key ?: return true
                val shown = page
                slot.down = true
                slot.press = 1f
                invalidate()
                if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                // Like a real typewriter, the character lands on the way down.
                listener?.onKey(key)
                if (page !== shown) return true // the key switched pages; this touch is spent
                pointers.put(e.getPointerId(i), slot)
                when (key.code) {
                    Code.BACKSPACE -> {
                        deleteSlot = slot
                        postDelayed(repeatDelete, 380)
                    }
                    Code.SPACE -> {
                        spaceHeld = true
                        postDelayed(spaceLongPress, 600)
                    }
                    else -> Unit
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                pointers.get(id)?.let { release(it) }
                pointers.remove(id)
            }
            MotionEvent.ACTION_CANCEL -> cancelTouches()
        }
        return true
    }

    private fun release(slot: Slot) {
        slot.down = false
        if (slot === deleteSlot) {
            deleteSlot = null
            removeCallbacks(repeatDelete)
        }
        if (slot.key?.code == Code.SPACE) {
            spaceHeld = false
            removeCallbacks(spaceLongPress)
        }
        invalidate()
    }

    private fun cancelTouches() {
        for (i in 0 until pointers.size()) pointers.valueAt(i).down = false
        pointers.clear()
        deleteSlot = null
        spaceHeld = false
        removeCallbacks(repeatDelete)
        removeCallbacks(spaceLongPress)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelTouches()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val INK = 0xFFD2D1C0.toInt()
        const val INK_ON_RED = 0xFFE2BCC0.toInt()
    }
}
