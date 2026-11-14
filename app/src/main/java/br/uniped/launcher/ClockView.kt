package br.uniped.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Relógio analógico estilo gadget do Windows Vista.
 * O mostrador e o brilho de vidro são desenhados uma vez só (em bitmap);
 * a cada segundo só os ponteiros são redesenhados.
 */
class ClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private var faceBmp: Bitmap? = null
    private var glossBmp: Bitmap? = null

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            // alinha na virada do segundo
            postDelayed(this, 1000L - (System.currentTimeMillis() % 1000L) + 5L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(ticker)
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        faceBmp?.recycle()
        glossBmp?.recycle()
        faceBmp = null
        glossBmp = null
        if (w <= 0 || h <= 0) return
        val face = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        paintFace(Canvas(face), w, h)
        faceBmp = face
        val gloss = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        paintGloss(Canvas(gloss), w, h)
        glossBmp = gloss
    }

    private fun paintFace(c: Canvas, w: Int, h: Int) {
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f - 1f
        if (r <= 0f) return

        // aro escuro
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy - r * 0.35f, r * 1.1f,
            intArrayOf(0xFF7A7F84.toInt(), 0xFF202326.toInt(), 0xFF050607.toInt()),
            floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r, paint)

        // mostrador claro
        paint.shader = RadialGradient(
            cx, cy - r * 0.25f, r * 0.9f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFE9EEF1.toInt(), 0xFFAEB7BD.toInt()),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, r * 0.86f, paint)

        // marcas das horas
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.BUTT
        paint.color = 0xFF30363B.toInt()
        for (i in 0 until 12) {
            val rad = Math.toRadians(i * 30.0)
            val dx = sin(rad).toFloat()
            val dy = -cos(rad).toFloat()
            val big = i % 3 == 0
            paint.strokeWidth = if (big) r * 0.07f else r * 0.035f
            val r1 = r * (if (big) 0.66f else 0.72f)
            val r2 = r * 0.80f
            c.drawLine(cx + dx * r1, cy + dy * r1, cx + dx * r2, cy + dy * r2, paint)
        }
        paint.shader = null
    }

    private fun paintGloss(c: Canvas, w: Int, h: Int) {
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f - 1f
        if (r <= 0f) return
        paint.style = Paint.Style.FILL
        oval.set(cx - r * 0.78f, cy - r * 0.86f, cx + r * 0.78f, cy + r * 0.08f)
        paint.shader = LinearGradient(
            0f, oval.top, 0f, oval.bottom,
            0x70FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP
        )
        c.drawOval(oval, paint)
        paint.shader = null
    }

    private fun hand(
        c: Canvas, cx: Float, cy: Float, deg: Float,
        len: Float, tail: Float, width: Float, color: Int
    ) {
        val rad = Math.toRadians((deg - 90f).toDouble())
        val dx = cos(rad).toFloat()
        val dy = sin(rad).toFloat()
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = width
        paint.color = color
        c.drawLine(cx - dx * tail, cy - dy * tail, cx + dx * len, cy + dy * len, paint)
    }

    override fun onDraw(c: Canvas) {
        val face = faceBmp ?: return
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 1f
        if (r <= 0f) return

        c.drawBitmap(face, 0f, 0f, null)

        // ponteiros
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR)
        val m = cal.get(Calendar.MINUTE)
        val s = cal.get(Calendar.SECOND)
        hand(c, cx, cy, (h + m / 60f) * 30f, r * 0.45f, r * 0.10f, r * 0.08f, 0xFF1C1F22.toInt())
        hand(c, cx, cy, (m + s / 60f) * 6f, r * 0.66f, r * 0.10f, r * 0.05f, 0xFF1C1F22.toInt())
        hand(c, cx, cy, s * 6f, r * 0.72f, r * 0.16f, r * 0.022f, 0xFFD8322A.toInt())

        // pino central
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = 0xFFD8322A.toInt()
        c.drawCircle(cx, cy, r * 0.055f, paint)

        // brilho de vidro
        glossBmp?.let { c.drawBitmap(it, 0f, 0f, null) }
    }
}
