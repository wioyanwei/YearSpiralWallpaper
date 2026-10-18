package com.yeargarden.spiral

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// 螺旋的几何：每一天一个点（黄金角螺旋，与壁纸、App 内年历共用）
class SpiralGeo(val cx: Float, val cy: Float, val u: Float, val total: Int, today: Int) {
    val s: Float = u / 172f
    val todayIdx: Int = today - 1
    val x = FloatArray(total)
    val y = FloatArray(total)
    val r = FloatArray(total)

    init {
        val golden = Math.toRadians(137.50776405003785)
        val rMin = 8f * s
        for (k in 0 until total) {
            val t = k / (total - 1f)
            val radius = rMin + (u - rMin) * sqrt(t)
            val ang = k * golden
            x[k] = cx + radius * cos(ang).toFloat()
            y[k] = cy + radius * sin(ang).toFloat()
            r[k] = (2.1f + 1.5f * t) * s
        }
    }
}

internal fun mix(a: Int, b: Int, t: Float): Int {
    val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt()
    val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt()
    val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    return Color.rgb(r, g, bl)
}

// 触摸互动：手指像一个微型引力场，经过的圆点轻轻散开并变亮；碰到某一天时浮出日期，
// 若是剩余的某一天，再触发一次 onTick（微震）。壁纸与 App 内年历共用这一份。
class TouchFx(sans: Typeface, serifItalic: Typeface, private val onTick: () -> Unit) {

    var down = false
        private set
    private var x = 0f
    private var y = 0f
    private var amt = 0f
    private var start = 0L
    private var sel = -1
    private var labelA = 0f
    private var labelText = ""

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sans }
    private val numP = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = serifItalic }

    fun onDown(px: Float, py: Float, now: Long) {
        down = true
        x = px
        y = py
        start = now
        amt = max(amt, 0.4f)
    }

    fun onMove(px: Float, py: Float) {
        x = px
        y = py
    }

    fun onUp() {
        down = false
    }

    // 只收到「点一下」的桌面（没有划动事件）：给一次短暂的涟漪
    fun tap(px: Float, py: Float, now: Long) {
        down = false
        x = px
        y = py
        start = now
        amt = 1f
    }

    // 每帧调用一次：推进强度（按下时快速升到 1，松手后慢慢退去）
    fun step() {
        amt = if (down) min(1f, amt + 0.3f) else amt * 0.9f
        if (amt < 0.01f && !down) {
            amt = 0f
            sel = -1
            labelA = 0f
        }
    }

    fun draw(
        c: Canvas, g: SpiralGeo, pal: Palette, accent: Int, hot: Int, now: Long,
        offX: Float, offY: Float, depthPast: Float, depthLit: Float, year: Int, screenW: Float
    ) {
        if (amt <= 0.01f) return
        val field = 0.30f * g.u
        var best = -1
        var bestD = Float.MAX_VALUE
        for (k in 0 until g.total) {
            val past = k < g.todayIdx
            val depth = if (past) depthPast else depthLit
            val px = g.x[k] + offX * depth
            val py = g.y[k] + offY * depth
            val dx = px - x
            val dy = py - y
            val dist = sqrt(dx * dx + dy * dy)
            if (dist < bestD) {
                bestD = dist
                best = k
            }
            if (dist < field) {
                val f = 1f - dist / field
                val f2 = f * f * amt
                val push = f2 * field * 0.2f
                val nx = if (dist > 0.5f) dx / dist else 0f
                val ny = if (dist > 0.5f) dy / dist else 0f
                val rr = g.r[k] * (if (past) 1f else 1.2f) * (1f + 0.55f * f2)
                val base = if (past) mix(pal.past, hot, 0.6f) else mix(accent, hot, 0.5f)
                dotPaint.color = withAlpha(base, (255f * min(1f, f2 * 1.6f)).toInt())
                c.drawCircle(px + nx * push, py + ny * push, rr, dotPaint)
            }
        }

        // 手指处向外扩散的涟漪
        val ph = ((now - start) % 1100L) / 1100f
        ringPaint.color = withAlpha(hot, (150f * (1f - ph) * amt).toInt())
        ringPaint.strokeWidth = max(2f, g.u * 0.008f)
        c.drawCircle(x, y, field * (0.15f + 0.85f * ph), ringPaint)

        // 选中离手指最近的那一天（够近才算）
        val hit = 0.075f * g.u
        val newSel = if (bestD < hit) best else -1
        if (newSel != sel) {
            sel = newSel
            if (sel >= 0) {
                labelText = labelFor(sel, g, year)
                if (sel > g.todayIdx) onTick()
            }
        }
        labelA += ((if (sel >= 0) 1f else 0f) - labelA) * 0.35f
        if (labelA > 0.03f && labelText.isNotEmpty()) drawLabel(c, g, pal, hot, screenW)
    }

    private fun labelFor(k: Int, g: SpiralGeo, year: Int): String {
        val cal = Calendar.getInstance()
        cal.set(Calendar.YEAR, year)
        cal.set(Calendar.DAY_OF_YEAR, k + 1)
        val m = cal.get(Calendar.MONTH) + 1
        val d = cal.get(Calendar.DAY_OF_MONTH)
        val tail = when {
            k == g.todayIdx -> "今天"
            k > g.todayIdx -> "还有${k - g.todayIdx}天"
            else -> "已过${g.todayIdx - k}天"
        }
        return "${m}月${d}日 · $tail"
    }

    private fun drawLabel(c: Canvas, g: SpiralGeo, pal: Palette, hot: Int, screenW: Float) {
        val size = 0.11f * g.u
        textP.textSize = size
        numP.textSize = size
        val tw = measureMixed(labelText)
        val padX = size * 0.8f
        val padY = size * 0.55f
        val w = tw + padX * 2f
        val h = size + padY * 2f
        val left = max(10f, min(screenW - w - 10f, x - w / 2f))
        var top = y - g.u * 0.16f - h
        if (top < 10f) top = y + g.u * 0.16f
        val a = 255f * labelA
        pillPaint.color = withAlpha(pal.bg2, (a * 0.85f).toInt())
        c.drawRoundRect(RectF(left, top, left + w, top + h), h / 2f, h / 2f, pillPaint)
        textP.color = withAlpha(hot, a.toInt())
        numP.color = withAlpha(hot, a.toInt())
        drawMixed(c, labelText, left + padX, top + padY + size * 0.82f)
    }

    // 数字（ASCII）用 Fraunces 斜体，汉字和符号用系统字体
    private fun isLatin(ch: Char): Boolean = ch.code < 128

    private fun measureMixed(s: String): Float {
        var total = 0f
        var start = 0
        while (start < s.length) {
            val latin = isLatin(s[start])
            var end = start + 1
            while (end < s.length && isLatin(s[end]) == latin) end++
            total += (if (latin) numP else textP).measureText(s, start, end)
            start = end
        }
        return total
    }

    private fun drawMixed(c: Canvas, s: String, x0: Float, base: Float) {
        var px = x0
        var start = 0
        while (start < s.length) {
            val latin = isLatin(s[start])
            var end = start + 1
            while (end < s.length && isLatin(s[end]) == latin) end++
            val p = if (latin) numP else textP
            c.drawText(s, start, end, px, base, p)
            px += p.measureText(s, start, end)
            start = end
        }
    }
}
