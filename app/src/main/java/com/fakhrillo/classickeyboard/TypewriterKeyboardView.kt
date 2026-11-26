package com.fakhrillo.classickeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.util.SparseArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class ShiftState { OFF, ONCE, LOCK }

/**
 * The whole keyboard is drawn on a single canvas: a comb of type bars along the
 * top, a lever running from every key cap up to the comb, and round glossy
 * key caps that sink (and kick their type bar) when struck.
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
            buildBoxes()
            invalidate()
        }

    var shift: ShiftState = ShiftState.OFF
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private class Box(val key: Key, val row: Int, val cx: Float, val cy: Float, val halfW: Float, val rodX: Float, val rodTop: Float, val tine: Int) {
        var press = 0f
        var down = false
    }

    private val d = resources.displayMetrics.density
    private val boxes = ArrayList<Box>()
    private val pointers = SparseArray<Box>()

    // Geometry, all derived from the key unit in onMeasure/onSizeChanged.
    private var unit = 0f
    private var rowPitch = 0f
    private var topArea = 0f
    private var bottomArea = 0f
    private var dia = 0f
    private var combY = 0f
    private var navInset = 0

    private val tineX = FloatArray(17)
    private val tineKick = FloatArray(17)
    private var lastFrame = 0L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.SERIF
        color = CREAM
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CREAM
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val shadowBitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadows = HashMap<Int, Bitmap>()
    private val rect = RectF()
    private val path = Path()

    private var rimShader: Shader? = null
    private var faceShader: Shader? = null
    private var redShader: Shader? = null
    private var pillFaceShader: Shader? = null
    private var pillRedShader: Shader? = null
    private var metalShader: Shader? = null

    private val repeatDelete = object : Runnable {
        override fun run() {
            val box = deleteBox ?: return
            listener?.onKey(box.key)
            kick(box)
            postDelayed(this, 55)
        }
    }
    private var deleteBox: Box? = null
    private var spaceHeld = false
    private val spaceLongPress = Runnable {
        if (spaceHeld) {
            spaceHeld = false
            listener?.onSpaceLongPress()
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
        val screenH = resources.displayMetrics.heightPixels
        val contentW = min(w - 12 * d, 560 * d)
        unit = contentW / 10f
        rowPitch = min(unit * 1.22f, screenH * 0.46f / (4 + TOP + BOTTOM))
        topArea = rowPitch * TOP
        bottomArea = rowPitch * BOTTOM
        val h = topArea + rowPitch * 4 + bottomArea + navInset
        setMeasuredDimension(w, h.toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        dia = min(unit * 1.0f, rowPitch * 0.92f)
        combY = topArea * 0.8f
        val r = dia / 2
        rimShader = LinearGradient(0f, -r, 0f, r, intArrayOf(0xFFB4B4B9.toInt(), 0xFF77777C.toInt(), 0xFF4A4A4F.toInt()), null, Shader.TileMode.CLAMP)
        faceShader = RadialGradient(0f, -r * 0.35f, r * 1.25f, 0xFF2C2C2F.toInt(), 0xFF151516.toInt(), Shader.TileMode.CLAMP)
        redShader = RadialGradient(0f, -r * 0.35f, r * 1.25f, 0xFFC2333A.toInt(), 0xFF6A1014.toInt(), Shader.TileMode.CLAMP)
        pillFaceShader = LinearGradient(0f, -r, 0f, r, 0xFF2A2A2D.toInt(), 0xFF151516.toInt(), Shader.TileMode.CLAMP)
        pillRedShader = LinearGradient(0f, -r, 0f, r, 0xFFC2333A.toInt(), 0xFF6A1014.toInt(), Shader.TileMode.CLAMP)
        metalShader = LinearGradient(0f, 0f, 0f, topArea, 0xFF4A4A4F.toInt(), 0xFF1C1C1E.toInt(), Shader.TileMode.CLAMP)
        bgPaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFFE2E3E7.toInt(), 0xFFC3C5CB.toInt(), Shader.TileMode.CLAMP)
        shadows.values.forEach { it.recycle() }
        shadows.clear()
        buildBoxes()
    }

    private fun buildBoxes() {
        boxes.clear()
        if (unit <= 0f || width == 0) return
        val left = (width - unit * 10) / 2
        for (i in tineX.indices) tineX[i] = left + unit * (1f + i * 0.5f)
        for ((ri, row) in page.withIndex()) {
            var total = 0f
            for (k in row) total += k.weight
            var x = (width - total * unit) / 2
            val cy = topArea + rowPitch * (ri + 0.5f)
            for (k in row) {
                val kw = k.weight * unit
                val cx = x + kw / 2
                val halfW = if (k.weight == 1f) dia / 2 else (kw - (unit - dia)) / 2
                // Shift and backspace hang their levers off the outer edge, clear of the caps above.
                val rodX = when {
                    ri != 2 || k.code == Code.TEXT -> cx
                    cx < width / 2 -> cx - halfW * 0.9f
                    else -> cx + halfW * 0.9f
                }
                var tine = 0
                for (i in tineX.indices) if (abs(tineX[i] - rodX) < abs(tineX[tine] - rodX)) tine = i
                // A lever that lines up with a type bar runs up into it; any other one
                // stops out of sight behind a cap in the top row.
                val aligned = abs(tineX[tine] - rodX) < unit * 0.05f
                val rodTop = if (ri > 0 && aligned) combY else topArea + rowPitch * 0.5f
                boxes += Box(k, ri, cx, cy, halfW, rodX, rodTop, tine)
                x += kw
            }
        }
    }

    // ---- drawing ----------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 16f else min(48f, (now - lastFrame).toFloat())
        lastFrame = now
        var animating = false
        for (b in boxes) {
            val target = if (b.down) 1f else 0f
            if (b.press != target) {
                val step = dt / if (b.down) 45f else 110f
                b.press = if (b.press < target) min(target, b.press + step) else max(target, b.press - step)
                animating = true
            }
        }
        for (i in tineKick.indices) {
            if (tineKick[i] > 0f) {
                tineKick[i] = max(0f, tineKick[i] - dt / 170f)
                animating = true
            }
        }

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        drawLevers(canvas)
        drawSpaceLinkage(canvas)
        for (b in boxes) drawKey(canvas, b)

        if (animating) postInvalidateOnAnimation() else lastFrame = 0L
    }

    private fun sink(b: Box) = b.press * dia * 0.075f

    private fun drawLevers(canvas: Canvas) {
        val rodW = max(1.5f * d, unit * 0.06f)
        val off = 2.2f * d
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND

        // Soft shadows cast on the body, down and to the right.
        paint.color = 0x26000000
        paint.strokeWidth = rodW
        for (b in boxes) if (b.row < 3) canvas.drawLine(b.rodX + off, b.rodTop + off, b.rodX + off, b.cy + off, paint)
        paint.strokeWidth = unit * 0.12f
        for (i in tineX.indices) canvas.drawLine(tineX[i] + off, tineTop(i) + off * 2, tineX[i] + off, topArea + rowPitch * 0.5f, paint)

        // Levers.
        paint.color = 0xFF29292C.toInt()
        paint.strokeWidth = rodW
        for (b in boxes) if (b.row < 3) canvas.drawLine(b.rodX, b.rodTop, b.rodX, b.cy + sink(b), paint)
        paint.color = 0x55FFFFFF
        paint.strokeWidth = rodW * 0.3f
        for (b in boxes) if (b.row < 3) canvas.drawLine(b.rodX - rodW * 0.25f, b.rodTop, b.rodX - rodW * 0.25f, b.cy + sink(b), paint)

        // Type bars: thick hooked bars rising out from behind the top row.
        val barW = unit * 0.12f
        val hook = unit * 0.1f
        val bottom = topArea + rowPitch * 0.5f
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        for (i in tineX.indices) {
            val x = tineX[i]
            val top = tineTop(i)
            path.rewind()
            path.moveTo(x, bottom)
            path.lineTo(x, top + hook)
            rect.set(x - hook * 2, top, x, top + hook * 2)
            path.arcTo(rect, 0f, -150f)
            paint.color = Color.BLACK // shaders are modulated by the paint's alpha
            paint.shader = metalShader
            paint.strokeWidth = barW
            canvas.drawPath(path, paint)
            paint.shader = null
            paint.color = 0x30FFFFFF
            paint.strokeWidth = barW * 0.18f
            canvas.drawLine(x - barW * 0.22f, top + hook * 1.4f, x - barW * 0.22f, bottom, paint)
        }
    }

    private fun tineTop(i: Int): Float {
        val k = tineKick[i]
        val lift = if (k > 0f) sin(Math.PI * (1f - k)).toFloat() * topArea * 0.16f else 0f
        return topArea * 0.2f - lift
    }

    private fun drawSpaceLinkage(canvas: Canvas) {
        val bar = boxes.firstOrNull { it.key.code == Code.SPACE } ?: return
        val y0 = bar.cy + sink(bar)
        val y1 = bar.cy + dia / 2 + bottomArea * 0.75f + sink(bar)
        val rodW = max(1.5f * d, unit * 0.06f)
        paint.shader = null
        paint.strokeCap = Paint.Cap.ROUND
        for (f in LINKAGE) {
            val x = bar.cx + bar.halfW * f
            val bracket = abs(f) > 0.6f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = rodW
            paint.color = 0x26000000
            canvas.drawLine(x + 2 * d, y0, x + 2 * d, y1 + 2 * d, paint)
            paint.color = 0xFF29292C.toInt()
            canvas.drawLine(x, y0, x, if (bracket) y1 else y1 - bottomArea * 0.2f, paint)
            if (bracket) {
                paint.style = Paint.Style.FILL
                val bw = unit * 0.34f
                val top = bar.cy + dia / 2 + bottomArea * 0.12f + sink(bar)
                rect.set(x - bw, top, x + bw * 0.4f, top + bottomArea * 0.32f)
                canvas.drawRoundRect(rect, 2 * d, 2 * d, paint)
                paint.color = 0x33FFFFFF
                canvas.drawRect(rect.left + d, rect.top + d, rect.right - d, rect.top + 2 * d, paint)
            }
        }
    }

    private fun shadowFor(halfW: Float, r: Float): Bitmap {
        val id = (halfW.toInt() shl 12) or r.toInt()
        return shadows.getOrPut(id) {
            val blur = dia * 0.14f
            val pad = blur * 2
            val bmp = Bitmap.createBitmap((halfW * 2 + pad * 2).toInt(), (r * 2 + pad * 2).toInt(), Bitmap.Config.ARGB_8888)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = 0x8C000000.toInt()
            p.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            Canvas(bmp).drawRoundRect(pad, pad, pad + halfW * 2, pad + r * 2, r, r, p)
            bmp
        }
    }

    private fun drawKey(canvas: Canvas, b: Box) {
        val r = dia / 2
        val hw = b.halfW
        val p = b.press
        val round = b.key.weight == 1f

        // Shadow tightens under the cap as it sinks.
        val sh = shadowFor(hw, r)
        val lift = 1f - 0.7f * p
        canvas.drawBitmap(sh, b.cx - sh.width / 2f + dia * 0.05f * lift, b.cy - sh.height / 2f + dia * 0.13f * lift, shadowBitmapPaint)

        canvas.save()
        canvas.translate(b.cx, b.cy + sink(b))
        val s = 1f - 0.025f * p
        canvas.scale(s, s)

        // Chrome rim, dark groove, glossy face.
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK // shaders are modulated by the paint's alpha
        paint.shader = rimShader
        rect.set(-hw, -r, hw, r)
        canvas.drawRoundRect(rect, r, r, paint)
        paint.shader = null
        paint.color = 0xFF070708.toInt()
        val g = r * 0.15f
        rect.set(-hw + g, -r + g, hw - g, r - g)
        canvas.drawRoundRect(rect, r, r, paint)
        val f = r * 0.2f
        paint.shader = when {
            b.key.red && round -> redShader
            b.key.red -> pillRedShader
            round -> faceShader
            else -> pillFaceShader
        }
        rect.set(-hw + f, -r + f, hw - f, r - f)
        canvas.drawRoundRect(rect, r, r, paint)
        paint.shader = null

        // Specular line along the top of the rim.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.045f
        paint.color = 0x70FFFFFF
        val hi = r * 0.045f
        if (round) {
            rect.set(-r + hi, -r + hi, r - hi, r - hi)
            canvas.drawArc(rect, 215f, 110f, false, paint)
        }
        if (p > 0f) {
            paint.style = Paint.Style.FILL
            paint.color = Color.argb((60 * p).toInt(), 0, 0, 0)
            rect.set(-hw + f, -r + f, hw - f, r - f)
            canvas.drawRoundRect(rect, r, r, paint)
        }

        when (b.key.icon) {
            Icon.NONE -> drawLabel(canvas, b.key.label, hw, r)
            else -> drawIcon(canvas, b.key.icon, r)
        }
        canvas.restore()
    }

    private fun drawLabel(canvas: Canvas, label: String, hw: Float, r: Float) {
        if (label.isEmpty()) return
        val plain = label.length == 1
        textPaint.textSize = if (plain) r * 1.0f else if (label[0].code > 0x2000) r * 0.95f else r * 0.8f
        val maxW = (hw - r * 0.3f) * 2
        val w = textPaint.measureText(label)
        if (w > maxW) textPaint.textSize *= maxW / w
        val fm = textPaint.fontMetrics
        canvas.drawText(label, 0f, -(fm.ascent + fm.descent) / 2, textPaint)
    }

    private fun drawIcon(canvas: Canvas, icon: Icon, r: Float) {
        val u = r * 0.92f
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = r * 0.075f
        path.rewind()
        when (icon) {
            Icon.SHIFT -> {
                path.moveTo(0f, -0.5f * u)
                path.lineTo(0.48f * u, 0f)
                path.lineTo(0.22f * u, 0f)
                path.lineTo(0.22f * u, 0.38f * u)
                path.lineTo(-0.22f * u, 0.38f * u)
                path.lineTo(-0.22f * u, 0f)
                path.lineTo(-0.48f * u, 0f)
                path.close()
                if (shift != ShiftState.OFF) iconPaint.style = Paint.Style.FILL_AND_STROKE
                canvas.drawPath(path, iconPaint)
                if (shift == ShiftState.LOCK) {
                    iconPaint.style = Paint.Style.STROKE
                    canvas.drawLine(-0.22f * u, 0.6f * u, 0.22f * u, 0.6f * u, iconPaint)
                }
            }
            Icon.BACKSPACE -> {
                path.moveTo(-0.62f * u, 0f)
                path.lineTo(-0.26f * u, -0.36f * u)
                path.lineTo(0.56f * u, -0.36f * u)
                path.lineTo(0.56f * u, 0.36f * u)
                path.lineTo(-0.26f * u, 0.36f * u)
                path.close()
                path.moveTo(0f, -0.15f * u)
                path.lineTo(0.3f * u, 0.15f * u)
                path.moveTo(0.3f * u, -0.15f * u)
                path.lineTo(0f, 0.15f * u)
                canvas.drawPath(path, iconPaint)
            }
            Icon.ENTER -> {
                path.moveTo(0.45f * u, -0.32f * u)
                path.lineTo(0.45f * u, 0.12f * u)
                path.lineTo(-0.42f * u, 0.12f * u)
                path.moveTo(-0.18f * u, -0.12f * u)
                path.lineTo(-0.42f * u, 0.12f * u)
                path.lineTo(-0.18f * u, 0.36f * u)
                canvas.drawPath(path, iconPaint)
            }
            Icon.EMOJI -> {
                iconPaint.strokeWidth = r * 0.07f
                canvas.drawCircle(0f, 0f, 0.46f * u, iconPaint)
                rect.set(-0.26f * u, -0.24f * u, 0.26f * u, 0.26f * u)
                canvas.drawArc(rect, 25f, 130f, false, iconPaint)
                iconPaint.style = Paint.Style.FILL
                canvas.drawCircle(-0.17f * u, -0.12f * u, 0.055f * u, iconPaint)
                canvas.drawCircle(0.17f * u, -0.12f * u, 0.055f * u, iconPaint)
            }
            Icon.NONE -> Unit
        }
    }

    // ---- touch ------------------------------------------------------------

    private fun findKey(x: Float, y: Float): Box? {
        if (boxes.isEmpty()) return null
        val row = ((y - topArea) / rowPitch).toInt().coerceIn(0, 3)
        var best: Box? = null
        var bestDist = Float.MAX_VALUE
        for (b in boxes) {
            if (b.row != row) continue
            val dist = abs(x - b.cx) - b.halfW
            if (dist < bestDist) {
                bestDist = dist
                best = b
            }
        }
        return if (bestDist < unit) best else null
    }

    private fun kick(b: Box) {
        tineKick[b.tine] = 1f
        b.press = max(b.press, 0.6f)
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val box = findKey(e.getX(i), e.getY(i)) ?: return true
                val shown = page
                box.down = true
                kick(box)
                if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                // Like a real typewriter, the character lands on the way down.
                listener?.onKey(box.key)
                if (page !== shown) return true // the key switched pages; this touch is spent
                pointers.put(e.getPointerId(i), box)
                when (box.key.code) {
                    Code.BACKSPACE -> {
                        deleteBox = box
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

    private fun release(box: Box) {
        box.down = false
        if (box === deleteBox) {
            deleteBox = null
            removeCallbacks(repeatDelete)
        }
        if (box.key.code == Code.SPACE) {
            spaceHeld = false
            removeCallbacks(spaceLongPress)
        }
        invalidate()
    }

    private fun cancelTouches() {
        for (i in 0 until pointers.size()) pointers.valueAt(i).down = false
        pointers.clear()
        deleteBox = null
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
        const val TOP = 0.95f
        const val BOTTOM = 0.5f
        const val CREAM = 0xFFEFE6D0.toInt()
        val LINKAGE = floatArrayOf(-0.74f, -0.38f, 0.38f, 0.74f)
    }
}
