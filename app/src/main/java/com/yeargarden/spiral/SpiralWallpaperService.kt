package com.yeargarden.spiral

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ───────── 颜色（与最初的网页卡片一致）─────────
private val BG_1 = Color.parseColor("#171320")
private val BG_2 = Color.parseColor("#0A0810")
private val INK = Color.parseColor("#F2ECE2")
private val INK_DIM = Color.parseColor("#9B93A4")
private val INK_FAINT = Color.parseColor("#7D7488")
private val DOT_PAST = Color.parseColor("#3A3346")
private val ACCENT = Color.parseColor("#F2A65A")
private val ACCENT_HOT = Color.parseColor("#FFD9A0")
private val SPHERE_HI = Color.parseColor("#FFF3E0")     // 球体高光
private val SPHERE_EDGE = Color.parseColor("#C2661A")   // 球体背光边缘

// ───────── 版面（以 1080×2410 的手机为基准，想微调位置只改这几个比例）─────────
private const val SHOW_YEAR = true            // 顶部年份；不想要就改成 false
private const val SHOW_DATE_LINE = false      // 底部「月日 星期 时钟」一行；锁屏已有系统日期，默认关
private const val YEAR_BASELINE = 0.085f      // 年份基线（占屏幕高度）
private const val SPIRAL_CENTER = 0.42f       // 螺旋圆心：放在系统日期行下面
private const val RADIUS_OF_WIDTH = 0.34f     // 螺旋半径上限（占屏幕宽度）
private const val RADIUS_OF_HEIGHT = 0.165f   // 螺旋半径上限（占屏幕高度）
private const val QUOTE_TOP = 0.785f          // 穿越的话的分割线位置：在系统解锁图标下方
private const val DATE_BASELINE = 0.92f       // 底部日期行基线

// 定义：穿越回来的年份 = 当前年份 + TRAVEL_YEAR_OFFSET（默认多一年，每年自动往后推）
private const val TRAVEL_YEAR_OFFSET = 1

private fun whisperLines(currentYear: Int): Array<String> = arrayOf(
    "我是从 ${currentYear + TRAVEL_YEAR_OFFSET}年 1月 1 日穿越回来的，",
    "当时的遗憾还记得吗？",
    "现在又拥有了一次重来的机会，",
    "我会做出什么改变？"
)

class SpiralWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = SpiralEngine()

    inner class SpiralEngine : Engine() {

        private val handler = Handler(Looper.getMainLooper())
        private var visible = false

        // 字体：优先用打包进来的 Fraunces，找不到就退回系统衬线体
        private fun loadFont(file: String, fallback: Typeface): Typeface =
            try {
                Typeface.createFromAsset(this@SpiralWallpaperService.assets, "fonts/$file")
            } catch (e: Exception) {
                fallback
            }

        private val serif600 = loadFont("fraunces-600.ttf", Typeface.create(Typeface.SERIF, Typeface.BOLD))
        private val serif500 = loadFont("fraunces-500.ttf", Typeface.create(Typeface.SERIF, Typeface.NORMAL))
        private val serifItalic = loadFont("fraunces-300-italic.ttf", Typeface.create(Typeface.SERIF, Typeface.ITALIC))
        private val sansRegular: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        private val sansBold: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        private val sansMedium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        // 静态层缓存：背景、螺旋、文字都画在这里，每天或换尺寸才重画
        private var cache: Bitmap? = null
        private var cacheKey = ""
        private var uPx = 0f          // 螺旋半径（像素）
        private var todayX = 0f
        private var todayY = 0f
        private var todayR = 0f

        // 复用的画笔与路径
        private val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val specPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 255, 255, 255) }
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val dateDim = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sansMedium; color = INK_DIM }
        private val dateInk = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sansBold; color = INK }

        private val drawRunner = Runnable { draw() }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            handler.removeCallbacks(drawRunner)
            if (v) handler.post(drawRunner)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            draw()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            visible = false
            handler.removeCallbacks(drawRunner)
        }

        override fun onDestroy() {
            super.onDestroy()
            visible = false
            handler.removeCallbacks(drawRunner)
            cache?.recycle()
            cache = null
        }

        private fun draw() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) drawFrame(canvas)
            } catch (e: Exception) {
                // 表面被系统回收时忽略这一帧
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas)
                    } catch (e: Exception) {
                    }
                }
            }
            handler.removeCallbacks(drawRunner)
            if (visible) handler.postDelayed(drawRunner, 33)   // 约 30 帧/秒，只有今天的圆球在动
        }

        private fun drawFrame(c: Canvas) {
            val w = c.width
            val h = c.height
            val cal = Calendar.getInstance()
            val year = cal.get(Calendar.YEAR)
            val today = cal.get(Calendar.DAY_OF_YEAR)
            val total = cal.getActualMaximum(Calendar.DAY_OF_YEAR)
            val key = "${w}x$h-$year-$today"

            val cached = cache
            val bmp: Bitmap = if (cached != null && key == cacheKey) {
                cached
            } else {
                cached?.recycle()
                val fresh = buildCache(w, h, year, today, total)
                cache = fresh
                cacheKey = key
                fresh
            }

            c.drawBitmap(bmp, 0f, 0f, null)
            drawBreathingBall(c)
            if (SHOW_DATE_LINE) drawDateLine(c, cal, w, h)
        }

        // 立体小圆球：径向渐变（高光偏左上）+ 一点镜面反光
        private fun drawSphere(c: Canvas, x: Float, y: Float, r: Float, hi: Int, mid: Int, edge: Int) {
            spherePaint.shader = RadialGradient(
                x - r * 0.35f, y - r * 0.40f, r * 1.25f,
                intArrayOf(hi, mid, edge), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            c.drawCircle(x, y, r, spherePaint)
            c.drawCircle(x - r * 0.36f, y - r * 0.40f, r * 0.17f, specPaint)
        }

        // ───────── 静态层 ─────────
        private fun buildCache(w: Int, h: Int, year: Int, today: Int, total: Int): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val fw = w.toFloat()
            val fh = h.toFloat()

            val u = min(fw * RADIUS_OF_WIDTH, fh * RADIUS_OF_HEIGHT)   // 螺旋半径
            val s = u / 172f                                           // 网页版 viewBox 的缩放比
            val cx = fw / 2f
            val cy = fh * SPIRAL_CENTER
            uPx = u

            // 背景：与网页版一致的径向渐变
            val bg = Paint()
            bg.shader = RadialGradient(
                cx, fh * 0.28f, max(fw, fh) * 0.85f,
                intArrayOf(BG_1, BG_2), floatArrayOf(0f, 0.8f), Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, fw, fh, bg)

            // 螺旋背后淡淡的琥珀色光晕
            val halo = Paint(Paint.ANTI_ALIAS_FLAG)
            halo.shader = RadialGradient(
                cx, cy, u * 1.25f,
                intArrayOf(Color.argb(34, 242, 166, 90), Color.argb(0, 242, 166, 90)),
                null, Shader.TileMode.CLAMP
            )
            c.drawCircle(cx, cy, u * 1.25f, halo)

            // 黄金角螺旋：已过去的天数是暗点，剩余的天数是发光的小圆球
            val pastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DOT_PAST }
            val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ACCENT
                maskFilter = BlurMaskFilter(2.6f * s * 2.0f, BlurMaskFilter.Blur.NORMAL)
            }
            val golden = Math.toRadians(137.50776405003785)
            val rMin = 8f * s
            val todayIdx = today - 1

            for (k in 0 until total) {
                val t = k / (total - 1f)
                val radius = rMin + (u - rMin) * sqrt(t)
                val ang = k * golden
                val x = cx + radius * cos(ang).toFloat()
                val y = cy + radius * sin(ang).toFloat()
                val r = (2.1f + 1.5f * t) * s
                when {
                    k < todayIdx -> c.drawCircle(x, y, r, pastPaint)
                    k == todayIdx -> {          // 今天：留给每帧的呼吸动画
                        todayX = x
                        todayY = y
                        todayR = r
                    }
                    else -> {
                        val ball = r * 1.2f
                        c.drawCircle(x, y, ball, glowPaint)
                        drawSphere(c, x, y, ball, SPHERE_HI, ACCENT, SPHERE_EDGE)
                    }
                }
            }

            val p = Paint(Paint.ANTI_ALIAS_FLAG)

            // 顶部年份
            if (SHOW_YEAR) {
                p.typeface = serif500
                p.textSize = 0.115f * u
                p.letterSpacing = 0.12f
                p.color = INK_FAINT
                p.textAlign = Paint.Align.CENTER
                c.drawText("$year", cx, fh * YEAR_BASELINE, p)
                p.letterSpacing = 0f
            }

            // 螺旋下方：剩余天数（字体、比例与最初设计一致）
            val remain = total - today
            val bigSize = 0.413f * u
            val spiralBottom = cy + u + 3.6f * s
            val bigBase = spiralBottom + 0.16f * u + 0.72f * bigSize
            val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = serif600; textSize = bigSize; color = INK
            }
            val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = serif500; textSize = bigSize * 0.34f; color = INK_DIM
            }
            val numStr = "$remain"
            val gap = 0.04f * u
            val numW = numPaint.measureText(numStr)
            val unitW = unitPaint.measureText("天")
            val x0 = cx - (numW + gap + unitW) / 2f
            c.drawText(numStr, x0, bigBase, numPaint)
            c.drawText("天", x0 + numW + gap, bigBase, unitPaint)

            // 一行小字：已过去的进度
            val subSize = 0.09f * u
            val subDim = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sansRegular; textSize = subSize; color = INK_DIM }
            val subAccent = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sansBold; textSize = subSize; color = ACCENT }
            val pct = (today * 100f / total).roundToInt()
            val sub1Base = bigBase + 0.14f * u + subSize
            drawCentered(
                c, cx, sub1Base, 0f,
                listOf("$year 年已经过去 " to subDim, "$today" to subAccent, " 天（$pct%）" to subDim)
            )

            // 分割线 + 穿越的话（左对齐，整体居中；放在系统解锁图标下方）
            val wSize = 0.097f * u
            val wLine = 1.8f * wSize
            val wPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = serifItalic; textSize = wSize; color = INK
            }
            val whisper = whisperLines(year)
            var maxW = 0f
            for (line in whisper) maxW = max(maxW, wPaint.measureText(line))
            val wx = (fw - maxW) / 2f
            val divY = fh * QUOTE_TOP
            val divPaint = Paint().apply { color = Color.argb(26, 242, 236, 226) }
            c.drawRect(wx, divY, wx + maxW, divY + max(2f, 0.006f * u), divPaint)
            var by = divY + 0.13f * u + wSize
            for (line in whisper) {
                c.drawText(line, wx, by, wPaint)
                by += wLine
            }

            return bmp
        }

        // ───────── 动态层：今天的圆球（呼吸：光晕起伏 + 涟漪扩散）─────────
        private fun drawBreathingBall(c: Canvas) {
            if (todayR <= 0f) return
            val tSec = (SystemClock.uptimeMillis() % 3_600_000L) / 1000f

            // 呼吸：3.4 秒一个周期，柔和起伏 0 → 1 → 0
            val ph = (tSec % 3.4f) / 3.4f
            val pulse = (0.5 - 0.5 * cos(2.0 * PI * ph)).toFloat()

            val ball = todayR * 1.9f * (1f + 0.28f * pulse)

            // 外圈光晕随呼吸扩张
            val haloR = ball * (2.6f + 2.2f * pulse)
            val haloAlpha = (70 + 120 * pulse).roundToInt()
            haloPaint.shader = RadialGradient(
                todayX, todayY, haloR,
                intArrayOf(Color.argb(haloAlpha, 242, 166, 90), Color.argb(0, 242, 166, 90)),
                null, Shader.TileMode.CLAMP
            )
            c.drawCircle(todayX, todayY, haloR, haloPaint)

            // 一圈向外扩散并淡出的涟漪
            val ringAlpha = ((1f - ph).pow(1.6f) * 150f).roundToInt()
            if (ringAlpha > 2) {
                ringPaint.color = Color.argb(ringAlpha, 242, 166, 90)
                ringPaint.strokeWidth = max(2f, todayR * 0.22f)
                c.drawCircle(todayX, todayY, ball * (1.2f + 3.6f * ph), ringPaint)
            }

            // 球体本身：吸气时更亮、更暖
            val mid = blend(ACCENT, ACCENT_HOT, 0.4f + 0.6f * pulse)
            val edge = blend(SPHERE_EDGE, ACCENT, 0.5f * pulse)
            drawSphere(c, todayX, todayY, ball, SPHERE_HI, mid, edge)
        }

        // ───────── 可选：底部一行（月日 / 星期 / 时钟）─────────
        private fun drawDateLine(c: Canvas, cal: Calendar, w: Int, h: Int) {
            val size = 0.125f * uPx
            dateDim.textSize = size
            dateInk.textSize = size
            val month = cal.get(Calendar.MONTH) + 1
            val day = cal.get(Calendar.DAY_OF_MONTH)
            val week = arrayOf("日", "一", "二", "三", "四", "五", "六")[cal.get(Calendar.DAY_OF_WEEK) - 1]
            val time = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            drawCentered(
                c, w / 2f, h * DATE_BASELINE, size * 0.9f,
                listOf("${month}月${day}日" to dateDim, "周$week" to dateDim, time to dateInk)
            )
        }

        private fun drawCentered(
            c: Canvas, cx: Float, base: Float, gap: Float, parts: List<Pair<String, Paint>>
        ) {
            var total = gap * (parts.size - 1)
            for ((txt, paint) in parts) total += paint.measureText(txt)
            var x = cx - total / 2f
            for ((txt, paint) in parts) {
                paint.textAlign = Paint.Align.LEFT
                c.drawText(txt, x, base, paint)
                x += paint.measureText(txt) + gap
            }
        }

        private fun blend(a: Int, b: Int, t: Float): Int {
            val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt()
            val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt()
            val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt()
            return Color.rgb(r, g, bl)
        }
    }
}
