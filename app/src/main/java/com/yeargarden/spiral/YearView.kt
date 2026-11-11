package com.yeargarden.spiral

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

// App 内的可触摸年历：和壁纸同一套螺旋；手指划过时圆点散开、变亮，触到某一天就轻震并浮出日期。
// （壁纸本身在锁屏上收不到触摸，所以这里是触摸互动最可靠的地方。）
class YearView(ctx: Context) : View(ctx) {

    private val pal = Palettes.current(ctx)
    private val sans: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)

    private fun font(file: String, fallback: Typeface): Typeface =
        try {
            Typeface.createFromAsset(context.assets, "fonts/$file")
        } catch (e: Exception) {
            fallback
        }

    private val italic = font("fraunces-500-italic.ttf", Typeface.create(Typeface.SERIF, Typeface.ITALIC))
    private val serif500 = font("fraunces-500.ttf", Typeface.create(Typeface.SERIF, Typeface.NORMAL))
    private val serif600 = font("fraunces-600.ttf", Typeface.create(Typeface.SERIF, Typeface.BOLD))

    private val touch = TouchFx(sans, italic) { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }

    private var geo: SpiralGeo? = null
    private var staticBmp: Bitmap? = null
    private var year = 0
    private var todayWd = 0
    private val todayMid: Int get() = Week.mid(todayWd, 0f)
    private val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val specPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD7FFFFFF.toInt() }

    private fun sphere(c: Canvas, x: Float, y: Float, r: Float, hi: Int, mid: Int, edge: Int) {
        spherePaint.shader = RadialGradient(
            x - r * 0.35f, y - r * 0.40f, r * 1.25f,
            intArrayOf(hi, mid, edge), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(x, y, r, spherePaint)
        c.drawCircle(x - r * 0.36f, y - r * 0.40f, r * 0.17f, specPaint)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val cal = Calendar.getInstance()
        year = cal.get(Calendar.YEAR)
        val today = cal.get(Calendar.DAY_OF_YEAR)
        val total = cal.getActualMaximum(Calendar.DAY_OF_YEAR)
        val remain = total - today
        todayWd = Week.weekday(year, today - 1)
        val fw = w.toFloat()
        val fh = h.toFloat()
        val u = min(fw * 0.44f, fh * 0.28f)
        val cx = fw / 2f
        val cy = fh * 0.40f
        val g = SpiralGeo(cx, cy, u, total, today)
        geo = g

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint()
        bg.shader = RadialGradient(cx, fh * 0.3f, max(fw, fh) * 0.85f, intArrayOf(pal.bg1, pal.bg2), floatArrayOf(0f, 0.8f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, fw, fh, bg)

        val pastPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.accent
            maskFilter = BlurMaskFilter(g.s * 5.2f, BlurMaskFilter.Blur.NORMAL)
        }
        for (k in 0 until total) {
            when {
                k < g.todayIdx -> {
                    pastPaint.color = Week.past(Week.weekday(year, k), 0f)
                    c.drawCircle(g.x[k], g.y[k], g.r[k] * 1.2f * Week.PAST_SCALE, pastPaint)
                }
                k > g.todayIdx -> {
                    val wd = Week.weekday(year, k)
                    val b = g.r[k] * 1.2f
                    glow.color = Week.mid(wd, 0f)
                    glow.alpha = 70
                    c.drawCircle(g.x[k], g.y[k], b, glow)
                    sphere(c, g.x[k], g.y[k], b, Week.hi(wd, 0f), Week.mid(wd, 0f), Week.edge(wd, 0f))
                }
            }
        }

        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = italic
        p.textSize = 0.115f * u
        p.letterSpacing = 0.12f
        p.color = pal.faint
        p.textAlign = Paint.Align.CENTER
        c.drawText("$year", cx, fh * 0.085f, p)
        p.letterSpacing = 0f

        val bigSize = 0.413f * u
        val base = cy + u + 3.6f * g.s + 0.16f * u + 0.72f * bigSize
        val numP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = serif600; textSize = bigSize; color = pal.ink }
        val unitP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = serif500; textSize = bigSize * 0.34f; color = pal.dim }
        val gap = 0.04f * u
        val numW = numP.measureText("$remain")
        val unitW = unitP.measureText("天")
        val x0 = cx - (numW + gap + unitW) / 2f
        c.drawText("$remain", x0, base, numP)
        c.drawText("天", x0 + numW + gap, base, unitP)

        val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans; textSize = 0.1f * u; color = pal.dim; textAlign = Paint.Align.CENTER }
        c.drawText("手指划过螺旋，触摸每一天", cx, fh * 0.9f, hint)
        staticBmp = bmp
    }

    override fun onDraw(c: Canvas) {
        val g = geo ?: return
        staticBmp?.let { c.drawBitmap(it, 0f, 0f, null) }
        val now = SystemClock.uptimeMillis()

        // 今天那颗球：呼吸
        val ph = (now % 3400L) / 3400f
        val pulse = (0.5 - 0.5 * cos(2.0 * PI * ph)).toFloat()
        val x = g.x[g.todayIdx]
        val y = g.y[g.todayIdx]
        val ball = g.r[g.todayIdx] * 2.1f * (1f + 0.28f * pulse)
        val haloR = ball * (2.6f + 2.2f * pulse)
        haloPaint.shader = RadialGradient(
            x, y, haloR,
            intArrayOf(withAlpha(todayMid, (95 + 95 * pulse).toInt()), withAlpha(todayMid, 0)),
            null, Shader.TileMode.CLAMP
        )
        c.drawCircle(x, y, haloR, haloPaint)
        sphere(c, x, y, ball, Week.hi(todayWd, 0f), mix(todayMid, Color.WHITE, 0.3f * pulse), mix(Week.edge(todayWd, 0f), todayMid, 0.5f * pulse))

        touch.step()
        touch.draw(c, g, pal, pal.accent, pal.hot, now, 0f, 0f, 0f, 0f, year, width.toFloat())
        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> touch.onDown(e.x, e.y, SystemClock.uptimeMillis())
            MotionEvent.ACTION_MOVE -> touch.onMove(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touch.onUp()
        }
        return true
    }
}
