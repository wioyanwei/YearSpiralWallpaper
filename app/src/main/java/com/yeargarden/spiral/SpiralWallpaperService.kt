package com.yeargarden.spiral

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import java.util.Calendar
import org.json.JSONArray
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

// ───────── 版面（以 1080×2410 的手机为基准，想微调位置只改这几个比例）─────────
private const val SHOW_YEAR = true            // 顶部年份；不想要就改成 false
private const val SHOW_DATE_LINE = false      // 底部「月日 星期 时钟」一行；锁屏已有系统日期，默认关
private const val YEAR_BASELINE = 0.085f      // 年份基线（占屏幕高度）
private const val SPIRAL_CENTER = 0.435f      // 螺旋圆心：放在系统日期行下面，留出呼吸空间
private const val RADIUS_OF_WIDTH = 0.34f     // 螺旋半径上限（占屏幕宽度）
private const val RADIUS_OF_HEIGHT = 0.158f   // 螺旋半径上限（占屏幕高度）
private const val QUOTE_TOP = 0.785f          // 穿越的话的起始位置：在系统解锁图标下方
private const val DATE_BASELINE = 0.92f       // 底部日期行基线
private const val HERO_ITALIC = false         // 螺旋下的大数字是否也用斜体（默认直立，更稳）

// 定义：穿越回来的年份 = 当前年份 + TRAVEL_YEAR_OFFSET（默认多一年，每年自动往后推）
private const val TRAVEL_YEAR_OFFSET = 1

// 字幕节奏（秒）：滚动一行用时；每一行停留多久由字数决定（见 setQuote）
private const val QUOTE_GLIDE = 0.9f
private const val QUOTE_SCROLL = false   // true = 字幕式向上滚动；false = 静态显示整段（默认）

// ───────── 动效参数 ─────────
private const val PARALLAX_AMP = 0.016f   // 视差最大位移（占屏幕宽度）
private const val DEPTH_DUST = 0.3f       // 各层的「深度」：越大，随倾斜移动得越多
private const val DEPTH_PAST = 0.65f
private const val DEPTH_LIT = 1.0f
private const val DUST_N = 80             // 星尘数量
private const val BREATH_DAY = 3.4f       // 今天那颗球的呼吸周期（秒）：白天
private const val BREATH_NIGHT = 5.2f     // 深夜更慢
private const val NIGHT_STEPS = 6         // 昼夜过渡分几档（每档重画一次静态层）
private const val RITUAL_SECONDS = 1.6f   // 午夜仪式总时长
private const val NEWYEAR_SECONDS = 6.2f  // 跨年仪式总时长
private const val LOW_BATTERY_PERCENT = 20
private const val EMBER_SPR_R = 30f       // 余烬精灵图里球体半径（像素）
private const val EMBER_SPR_HALF = 48f    // 余烬精灵图半边长（含光晕）

// 球体的一组颜色：随昼夜、电量变化
private class Tint(val accent: Int, val hot: Int, val hi: Int, val edge: Int)

private val EMBER_TINT = Tint(
    Color.parseColor("#D8452E"), Color.parseColor("#FF9473"),
    Color.parseColor("#FFD9CC"), Color.parseColor("#6E1208")
)

private fun ease(x: Float): Float = x * x * (3f - 2f * x)

// 语录库：app/src/main/assets/quotes.json，每条 {"lines": [...], "by": "出处"}。
// 文字里的 {Y} 会换成「当前年份 + TRAVEL_YEAR_OFFSET」（只有原创穿越语用到）。
private class Quote(val lines: List<String>, val by: String)

private val FALLBACK_QUOTE = Quote(
    listOf("我是从{Y}年1月1日穿越回来的，", "当时的遗憾还记得吗？", "现在又拥有了一次重来的机会，", "我会做出什么改变？"), ""
)

// 一年的最后一天（12 月 31 日）：固定显示这段，不参与随机抽取
private val FINALE_QUOTE = Quote(
    listOf("这是这一年的最后一天。", "该告别的，好好告别；", "该感谢的，不要错过。", "明天，一切重新开始。"), ""
)

private fun loadQuotes(ctx: Context): List<Quote> = try {
    val text = ctx.assets.open("quotes.json").bufferedReader().use { it.readText() }
    val arr = JSONArray(text)
    val out = ArrayList<Quote>()
    for (i in 0 until arr.length()) {
        val o = arr.getJSONObject(i)
        val ls = o.getJSONArray("lines")
        val lines = ArrayList<String>()
        for (j in 0 until ls.length()) lines.add(ls.getString(j))
        if (lines.isNotEmpty()) out.add(Quote(lines, o.optString("by", "")))
    }
    if (out.isEmpty()) listOf(FALLBACK_QUOTE) else out
} catch (e: Exception) {
    listOf(FALLBACK_QUOTE)
}

class SpiralWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = SpiralEngine()

    inner class SpiralEngine : Engine() {

        private val ctx: Context = this@SpiralWallpaperService
        private val handler = Handler(Looper.getMainLooper())
        private var visible = false

        // 字体：优先用打包进来的 Fraunces，找不到就退回系统衬线体
        private fun loadFont(file: String, fallback: Typeface): Typeface =
            try {
                Typeface.createFromAsset(ctx.assets, "fonts/$file")
            } catch (e: Exception) {
                fallback
            }

        private val serif600 = loadFont("fraunces-600.ttf", Typeface.create(Typeface.SERIF, Typeface.BOLD))
        private val serif500 = loadFont("fraunces-500.ttf", Typeface.create(Typeface.SERIF, Typeface.NORMAL))
        private val serif500i = loadFont("fraunces-500-italic.ttf", Typeface.create(Typeface.SERIF, Typeface.ITALIC))
        private val serif600i = loadFont("fraunces-600-italic.ttf", Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC))
        private val sansRegular: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        private val sansMedium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        // ───────── 静态层缓存（每天、换配色、昼夜档位变化、换尺寸才重画）─────────
        private var bgBmp: Bitmap? = null      // 背景 + 年份（整屏）
        private var pastBmp: Bitmap? = null    // 已过去的暗点（只覆盖螺旋范围）
        private var litBmp: Bitmap? = null     // 剩余天数的亮球（只覆盖螺旋范围）
        private var emberSprite: Bitmap? = null
        private var cacheKey = ""
        private var geo: SpiralGeo? = null
        private var layerOx = 0f               // 螺旋图层左上角在屏幕上的位置
        private var layerOy = 0f
        private var uPx = 0f
        private var todayX = 0f
        private var todayY = 0f
        private var todayR = 0f
        private var bigSize = 0f
        private var bigBase = 0f
        private var numCx = 0f
        private var tint = Tint(Color.WHITE, Color.WHITE, Color.WHITE, Color.GRAY)

        // 复用的画笔
        private val spherePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val specPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 255, 255, 255) }
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val dustPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val emberPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val emberDst = RectF()
        // 底部日期行：汉字用系统字体，数字统一用 Fraunces
        private val dateDim = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sansMedium }
        private val dateNumDim = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = serif500i }
        private val dateNumInk = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = serif600i }

        // ───────── 配色与动效开关（设置页改了立刻生效）─────────
        private var pal: Palette = Palettes.all[0]
        private var fxParallax = true
        private var fxDust = true
        private var fxTouch = true
        private var fxHaptic = true
        private var fxRitual = true
        private var fxNight = true
        private var fxEmber = true
        private var replayPending = false

        private fun loadFlags() {
            fxParallax = Fx.on(ctx, Fx.PARALLAX)
            fxDust = Fx.on(ctx, Fx.DUST)
            fxTouch = Fx.on(ctx, Fx.TOUCH)
            fxHaptic = Fx.on(ctx, Fx.HAPTIC)
            fxRitual = Fx.on(ctx, Fx.RITUAL)
            fxNight = Fx.on(ctx, Fx.NIGHT)
            fxEmber = Fx.on(ctx, Fx.EMBER)
        }

        private val settings: SharedPreferences by lazy {
            ctx.getSharedPreferences(Palettes.PREFS, Context.MODE_PRIVATE)
        }

        private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            pal = Palettes.current(ctx)
            loadFlags()
            if (key == Fx.REPLAY) replayPending = true
            if (key == Fx.REPLAY_NY) replayNyPending = true
            updateSensors()
        }

        // ───────── 1. 陀螺仪视差 ─────────
        // 用重力传感器（没有就用加速度计）。取「快速滤波值 − 缓慢漂移的基准」，
        // 所以握持角度本身不会让画面偏到一边，只有「动作」才产生视差。
        private var sensorManager: SensorManager? = null
        private var sensorRegistered = false
        private var tiltInit = false
        private var fastGx = 0f
        private var fastGy = 0f
        private var baseGx = 0f
        private var baseGy = 0f
        private var tiltTargetX = 0f
        private var tiltTargetY = 0f
        private var tiltX = 0f
        private var tiltY = 0f

        private val sensorListener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val gx = e.values[0]
                val gy = e.values[1]
                if (!tiltInit) {
                    fastGx = gx
                    fastGy = gy
                    baseGx = gx
                    baseGy = gy
                    tiltInit = true
                }
                fastGx += (gx - fastGx) * 0.2f
                fastGy += (gy - fastGy) * 0.2f
                baseGx += (gx - baseGx) * 0.006f
                baseGy += (gy - baseGy) * 0.006f
                tiltTargetX = ((fastGx - baseGx) / 9.81f / 0.3f).coerceIn(-1f, 1f)
                tiltTargetY = ((fastGy - baseGy) / 9.81f / 0.3f).coerceIn(-1f, 1f)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        private fun updateSensors() {
            val want = visible && fxParallax
            if (want == sensorRegistered) return
            try {
                if (want) {
                    val sm = sensorManager
                        ?: (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).also { sensorManager = it }
                    val s = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
                        ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                    if (s != null) {
                        tiltInit = false
                        sm.registerListener(sensorListener, s, SensorManager.SENSOR_DELAY_UI)
                        sensorRegistered = true
                    }
                } else {
                    sensorManager?.unregisterListener(sensorListener)
                    sensorRegistered = false
                    tiltTargetX = 0f
                    tiltTargetY = 0f
                }
            } catch (e: Exception) {
            }
        }

        // 背景星尘：位置、大小、亮度、闪烁相位都固定，只随倾斜轻微位移
        private val dustX = FloatArray(DUST_N)
        private val dustY = FloatArray(DUST_N)
        private val dustR = FloatArray(DUST_N)
        private val dustA = IntArray(DUST_N)
        private val dustPh = FloatArray(DUST_N)
        private val dustSp = FloatArray(DUST_N)

        init {
            val rnd = java.util.Random(11)
            for (i in 0 until DUST_N) {
                dustX[i] = rnd.nextFloat()
                dustY[i] = rnd.nextFloat() * 0.74f           // 不铺到下方文字区域
                dustR[i] = 0.7f + rnd.nextFloat() * 1.3f
                dustA[i] = 40 + rnd.nextInt(90)
                dustPh[i] = rnd.nextFloat() * 6.28f
                dustSp[i] = rnd.nextFloat() * 0.8f
            }
        }

        // ───────── 2. 触摸互动 ─────────
        private val touch = TouchFx(sansRegular, serif500i) { vibrateTick() }
        private val vibrator: Vibrator? by lazy {
            try {
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } catch (e: Exception) {
                null
            }
        }

        @Suppress("DEPRECATION")
        private fun vibrateTick() {
            if (!fxHaptic) return
            val v = vibrator ?: return
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                } else if (Build.VERSION.SDK_INT >= 26) {
                    v.vibrate(VibrationEffect.createOneShot(12, 90))
                } else {
                    v.vibrate(12)
                }
            } catch (e: Exception) {
            }
        }

        override fun onTouchEvent(event: MotionEvent) {
            super.onTouchEvent(event)
            if (!fxTouch) return
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> touch.onDown(event.x, event.y, SystemClock.uptimeMillis())
                MotionEvent.ACTION_MOVE -> touch.onMove(event.x, event.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touch.onUp()
            }
        }

        // 有些桌面只转发「点一下」：给一次涟漪
        override fun onCommand(
            action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean
        ): Bundle? {
            if (fxTouch && action == WallpaperManager.COMMAND_TAP) {
                touch.tap(x.toFloat(), y.toFloat(), SystemClock.uptimeMillis())
            }
            return super.onCommand(action, x, y, z, extras, resultRequested)
        }

        // ───────── 3. 午夜「星火寂灭」仪式 ─────────
        private var ritualStart = 0L
        private var ritualOldRemain = 0
        private var ritualY = -1
        private var checkedDayKey = 0

        // 跨年仪式：旧年最后一颗球熄灭 → 年份换新 → 一圈光从中心向外铺开，剩余天数从 0 数到满 → 今天那颗球点燃
        private var newYearStart = 0L
        private var newYearOld = 0
        private var replayNyPending = false
        private var darkBmp: Bitmap? = null    // 整个螺旋全是暗点（仪式开头用，用到才画）
        private var spCx = 0f
        private var spCy = 0f
        private val wavePath = Path()
        private val yearPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var yearCx = 0f
        private var yearBase = 0f

        private fun startRitual(oldRemain: Int, yesterdayIdx: Int) {
            ritualStart = SystemClock.uptimeMillis()
            ritualOldRemain = oldRemain
            ritualY = yesterdayIdx
        }

        // 记下上次看到的是哪一天；亮屏（或 00:00 时正亮着）发现换了一天，就播放一次
        private fun checkDayChange(year: Int, today: Int, remain: Int, todayIdx: Int) {
            val cur = year * 1000 + today
            if (cur == checkedDayKey) return
            checkedDayKey = cur
            if (isLastDayOfYear()) setQuote(FINALE_QUOTE)   // 过了零点进入最后一天，立刻换上终章
            val stored = settings.getInt(Fx.LAST_DAY, 0)
            if (stored == cur) return
            settings.edit().putInt(Fx.LAST_DAY, cur).apply()
            if (stored == 0 || !fxRitual) return                 // 刚装好的第一天不播
            if (stored / 1000 < year) {                          // 跨年：播放跨年仪式
                startNewYear(stored / 1000)
                return
            }
            if (stored / 1000 == year && today > stored % 1000 && todayIdx >= 1) {
                startRitual(remain + 1, todayIdx - 1)
            }
        }

        private fun startNewYear(oldYear: Int) {
            newYearStart = SystemClock.uptimeMillis()
            newYearOld = oldYear
            ritualStart = 0L
        }

        private fun newYearTime(now: Long): Float {
            if (newYearStart == 0L) return -1f
            val t = (now - newYearStart) / 1000f
            if (t > NEWYEAR_SECONDS) {
                newYearStart = 0L
                return -1f
            }
            return t
        }

        // 返回仪式进行到第几秒；没有仪式返回 -1
        private fun ritualTime(now: Long): Float {
            if (ritualStart == 0L) return -1f
            val t = (now - ritualStart) / 1000f
            if (t > RITUAL_SECONDS) {
                ritualStart = 0L
                return -1f
            }
            return t
        }

        // ───────── 4. 昼夜与电量 ─────────
        private var lowBattery = false
        private var lastBatteryCheck = 0L
        private var nightT = 0f
        private var breathPh = 0f
        private var lastFrameAt = 0L

        private fun checkBattery(now: Long) {
            if (lastBatteryCheck != 0L && now - lastBatteryCheck < 20_000L) return
            lastBatteryCheck = now
            try {
                val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                lowBattery = level >= 0 && scale > 0 && level * 100 / scale < LOW_BATTERY_PERCENT && plugged == 0
            } catch (e: Exception) {
            }
        }

        // 夜间程度：22:30～05:30 为 1，21:30～22:30 与 05:30～06:30 线性过渡
        private fun nightFactor(cal: Calendar): Float {
            val h = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
            return when {
                h >= 22.5f || h < 5.5f -> 1f
                h >= 21.5f -> h - 21.5f
                h < 6.5f -> 6.5f - h
                else -> 0f
            }
        }

        // 夜里：亮色向更深的「篝火金」靠拢；电量低：绯红余烬
        private fun computeTint(step: Int, ember: Boolean): Tint {
            if (ember) return EMBER_TINT
            val t = step / NIGHT_STEPS.toFloat()
            return Tint(
                blend(pal.accent, pal.edge, 0.38f * t),
                blend(pal.hot, pal.accent, 0.45f * t),
                blend(pal.hi, pal.hot, 0.35f * t),
                pal.edge
            )
        }

        // ───────── 穿越的话（语录）─────────
        private val qText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sansRegular }
        private val qNum = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = serif500i }
        private var qSlots: List<String> = emptyList()   // 正文各行 + 最后一行出处
        private var qBodyCount = 0                       // 正文行数（出处不算）
        private var qHolds = FloatArray(0)               // 每个槽位停留多久（最后一个是「空位」）
        private var qWidth = 0f
        private var qHeight = 0f
        private var qLayoutDirty = true
        private var qX = 0f          // 左对齐的起点
        private var qBase = 0f       // 焦点行的基线
        private var qStep = 0f       // 行距
        private var qStart = SystemClock.uptimeMillis()

        // 「洗牌袋」：把全部语录洗乱，一条一条抽，抽完一整轮才会再出现重复；袋子存在 SharedPreferences 里，重启也不丢。
        private val quotes: List<Quote> = loadQuotes(ctx)
        private val prefs by lazy { ctx.getSharedPreferences("quote_bag", Context.MODE_PRIVATE) }

        private fun isLastDayOfYear(): Boolean {
            val cal = Calendar.getInstance()
            return cal.get(Calendar.DAY_OF_YEAR) == cal.getActualMaximum(Calendar.DAY_OF_YEAR)
        }

        private fun pickNext() {
            if (isLastDayOfYear()) {            // 一年的最后一天：固定显示终章，不消耗洗牌袋
                setQuote(FINALE_QUOTE)
                return
            }
            val bag = (prefs.getString("bag", "") ?: "").split(",")
                .mapNotNull { it.toIntOrNull() }.filter { it in quotes.indices }.toMutableList()
            if (bag.isEmpty()) {
                bag.addAll(quotes.indices.shuffled())
                val last = prefs.getInt("last", -1)
                if (bag.size > 1 && bag[0] == last) {          // 新一轮的第一条不要和上一轮最后一条相同
                    val first = bag[0]
                    bag[0] = bag[bag.size - 1]
                    bag[bag.size - 1] = first
                }
            }
            val idx = bag.removeAt(0)
            prefs.edit().putString("bag", bag.joinToString(",")).putInt("last", idx).apply()
            setQuote(quotes[idx])
        }

        private fun setQuote(q: Quote) {
            val year = Calendar.getInstance().get(Calendar.YEAR) + TRAVEL_YEAR_OFFSET
            val body = q.lines.map { it.replace("{Y}", "$year") }
            qBodyCount = body.size
            qSlots = if (q.by.isEmpty()) body else body + "—— ${q.by}"
            // 停留时间（滚动模式用）：约 1.6 秒 + 每字 0.12 秒（限制在 2.2～4 秒）；正文最后一句多停 1.2 秒；出处停 2.4 秒；末尾空位 0.5 秒
            val holds = FloatArray(qSlots.size + 1)
            for (i in qSlots.indices) {
                holds[i] = if (i >= qBodyCount) 2.4f
                else min(4f, max(2.2f, 1.6f + 0.12f * qSlots[i].length)) + (if (i == qBodyCount - 1) 1.2f else 0f)
            }
            holds[qSlots.size] = 0.5f
            qHolds = holds
            qLayoutDirty = true
            qStart = SystemClock.uptimeMillis()
        }

        private val screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                pickNext()          // 锁屏的瞬间就换好下一条，下次亮屏直接是新的
                Palettes.rotate(ctx)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
            pal = Palettes.current(ctx)
            loadFlags()
            settings.registerOnSharedPreferenceChangeListener(settingsListener)
            pickNext()
            try {
                ctx.registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
            } catch (e: Exception) {
            }
        }

        private val drawRunner = Runnable { draw() }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            handler.removeCallbacks(drawRunner)
            if (v) {
                qStart = SystemClock.uptimeMillis()   // 每次亮屏，字幕都从第一句重新开始
                lastFrameAt = 0L
                handler.post(drawRunner)
            }
            updateSensors()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            draw()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            visible = false
            handler.removeCallbacks(drawRunner)
            updateSensors()
        }

        override fun onDestroy() {
            super.onDestroy()
            visible = false
            handler.removeCallbacks(drawRunner)
            updateSensors()
            settings.unregisterOnSharedPreferenceChangeListener(settingsListener)
            try {
                ctx.unregisterReceiver(screenOffReceiver)
            } catch (e: Exception) {
            }
            bgBmp?.recycle()
            pastBmp?.recycle()
            litBmp?.recycle()
            emberSprite?.recycle()
            darkBmp?.recycle()
            darkBmp = null
            bgBmp = null
            pastBmp = null
            litBmp = null
            emberSprite = null
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
            if (visible) handler.postDelayed(drawRunner, 33)   // 约 30 帧/秒
        }

        private fun drawFrame(c: Canvas) {
            val now = SystemClock.uptimeMillis()
            val w = c.width
            val h = c.height
            val cal = Calendar.getInstance()
            val year = cal.get(Calendar.YEAR)
            val today = cal.get(Calendar.DAY_OF_YEAR)
            val total = cal.getActualMaximum(Calendar.DAY_OF_YEAR)
            val remain = total - today

            checkBattery(now)
            val nightStep = if (fxNight) (nightFactor(cal) * NIGHT_STEPS).roundToInt() else 0
            nightT = nightStep / NIGHT_STEPS.toFloat()
            val ember = fxEmber && lowBattery
            val key = "${w}x$h-$year-$today-${pal.name}-n$nightStep-e$ember"

            if (bgBmp == null || key != cacheKey) {
                tint = computeTint(nightStep, ember)
                buildCache(w, h, year, today, total, ember)
                cacheKey = key
            }
            val g = geo ?: return

            checkDayChange(year, today, remain, g.todayIdx)
            if (replayPending) {
                replayPending = false
                if (fxRitual && g.todayIdx >= 1) startRitual(remain + 1, g.todayIdx - 1)
            }
            val rt = ritualTime(now)
            if (replayNyPending) {
                replayNyPending = false
                if (fxRitual) startNewYear(year - 1)
            }
            val nt = newYearTime(now)
            val finale = remain == 0

            // 呼吸相位按真实经过的时间累加：昼夜切换呼吸周期时不会跳变
            val dt = if (lastFrameAt == 0L) 0.033f else min(0.1f, (now - lastFrameAt) / 1000f)
            lastFrameAt = now
            val period = if (finale) BREATH_NIGHT else BREATH_DAY + (BREATH_NIGHT - BREATH_DAY) * nightT
            breathPh = (breathPh + dt / period) % 1f

            // 视差位移（像素）：平滑逼近目标，免得抖动
            tiltX += (tiltTargetX - tiltX) * 0.15f
            tiltY += (tiltTargetY - tiltY) * 0.15f
            val offX = if (fxParallax) -tiltX * PARALLAX_AMP * w else 0f
            val offY = if (fxParallax) tiltY * PARALLAX_AMP * w else 0f

            bgBmp?.let { c.drawBitmap(it, 0f, 0f, null) }
            if (fxDust) drawDust(c, now, w, h, offX, offY)

            if (nt >= 0f) {
                drawNewYear(c, g, nt, remain, year, offX, offY)
            } else {
                pastBmp?.let { c.drawBitmap(it, layerOx + offX * DEPTH_PAST, layerOy + offY * DEPTH_PAST, null) }
                if (ember) {
                    drawEmbers(c, g, now, offX, offY)
                } else {
                    litBmp?.let { c.drawBitmap(it, layerOx + offX * DEPTH_LIT, layerOy + offY * DEPTH_LIT, null) }
                }
                drawYear(c, "$year", 255)

                // 剩余天数：午夜仪式时，旧数字向上滚走、新数字从下方滚入
                if (rt >= 0f) {
                    val e = ease(((rt - 0.5f) / 0.8f).coerceIn(0f, 1f))
                    val dy = 0.32f * bigSize
                    drawHero(c, ritualOldRemain, (255f * (1f - e)).toInt(), -dy * e)
                    drawHero(c, remain, (255f * e).toInt(), dy * (1f - e))
                } else {
                    drawHero(c, remain, 255, 0f)
                }

                // 午夜仪式里，今天的球从小到大被点燃，光晕闪一下
                var scaleMul = 1f
                var haloBoost = 1f
                if (rt >= 0f) {
                    val igp = ((rt - 0.6f) / 0.8f).coerceIn(0f, 1f)
                    scaleMul = 0.5f + 0.5f * ease(igp)
                    haloBoost = 1f + 1.6f * sin(PI.toFloat() * igp)
                }
                drawBreathingBall(c, scaleMul, haloBoost, finale, offX, offY)
                if (rt >= 0f) drawRitual(c, g, rt, offX, offY)
            }

            drawQuote(c)

            if (fxTouch) {
                touch.step()
                touch.draw(c, g, pal, tint.accent, tint.hot, now, offX, offY, DEPTH_PAST, DEPTH_LIT, year, w.toFloat())
            }
            if (SHOW_DATE_LINE) drawDateLine(c, cal, w, h)
        }

        // 立体小圆球：径向渐变（高光偏左上）+ 一点镜面反光
        private fun drawSphere(
            c: Canvas, x: Float, y: Float, r: Float, hi: Int, mid: Int, edge: Int, alpha: Int = 255
        ) {
            spherePaint.shader = RadialGradient(
                x - r * 0.35f, y - r * 0.40f, r * 1.25f,
                intArrayOf(hi, mid, edge), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            spherePaint.alpha = alpha
            c.drawCircle(x, y, r, spherePaint)
            specPaint.alpha = 215 * alpha / 255
            c.drawCircle(x - r * 0.36f, y - r * 0.40f, r * 0.17f, specPaint)
            specPaint.alpha = 215
        }

        // ───────── 静态层 ─────────
        private fun buildCache(w: Int, h: Int, year: Int, today: Int, total: Int, ember: Boolean) {
            bgBmp?.recycle()
            pastBmp?.recycle()
            litBmp?.recycle()
            emberSprite?.recycle()
            darkBmp?.recycle()
            darkBmp = null
            litBmp = null
            emberSprite = null

            val fw = w.toFloat()
            val fh = h.toFloat()
            val u = min(fw * RADIUS_OF_WIDTH, fh * RADIUS_OF_HEIGHT)   // 螺旋半径
            val s = u / 172f                                           // 网页版 viewBox 的缩放比
            val cx = fw / 2f
            val cy = fh * SPIRAL_CENTER
            uPx = u
            val g = SpiralGeo(cx, cy, u, total, today)
            geo = g
            todayX = g.x[g.todayIdx]
            todayY = g.y[g.todayIdx]
            todayR = g.r[g.todayIdx]

            // —— 背景层（整屏）：渐变 + 下半屏渐入近乎纯黑 + 年份 ——
            val bg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bg)
            val bgPaint = Paint()
            bgPaint.shader = RadialGradient(
                cx, fh * 0.28f, max(fw, fh) * 0.85f,
                intArrayOf(pal.bg1, pal.bg2), floatArrayOf(0f, 0.8f), Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, fw, fh, bgPaint)

            val fade = Paint()
            fade.shader = LinearGradient(
                0f, fh * 0.52f, 0f, fh,
                intArrayOf(
                    withAlpha(pal.fade, 0), withAlpha(pal.fade, 35), withAlpha(pal.fade, 112),
                    withAlpha(pal.fade, 189), withAlpha(pal.fade, 224)
                ),
                floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f), Shader.TileMode.CLAMP
            )
            c.drawRect(0f, fh * 0.52f, fw, fh, fade)

            // 年份每帧动态画（跨年仪式里要从旧年份渐变成新年份）
            yearPaint.typeface = serif500i
            yearPaint.textSize = 0.115f * u
            yearPaint.letterSpacing = 0.12f
            yearPaint.textAlign = Paint.Align.CENTER
            yearCx = cx
            yearBase = fh * YEAR_BASELINE
            spCx = cx
            spCy = cy
            bgBmp = bg

            // —— 螺旋图层：只覆盖螺旋范围（含光晕的余量），省内存；视差时整层平移 ——
            val half = u * 1.12f
            val size = (half * 2f).toInt() + 2
            layerOx = cx - size / 2f
            layerOy = cy - size / 2f

            val past = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val pc = Canvas(past)
            pc.translate(-layerOx, -layerOy)
            val pastPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.past }
            for (k in 0 until g.todayIdx) pc.drawCircle(g.x[k], g.y[k], g.r[k], pastPaint)
            pastBmp = past

            if (!ember) {
                val lit = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val lc = Canvas(lit)
                lc.translate(-layerOx, -layerOy)
                val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = tint.accent
                    maskFilter = BlurMaskFilter(2.6f * s * 2.0f, BlurMaskFilter.Blur.NORMAL)
                }
                for (k in g.todayIdx + 1 until total) {
                    val ball = g.r[k] * 1.2f
                    lc.drawCircle(g.x[k], g.y[k], ball, glowPaint)
                    drawSphere(lc, g.x[k], g.y[k], ball, tint.hi, tint.accent, tint.edge)
                }
                litBmp = lit
            } else {
                // 余烬：一张小精灵图（球体 + 光晕），每帧按各自的闪烁亮度画
                val spr = Bitmap.createBitmap((EMBER_SPR_HALF * 2).toInt(), (EMBER_SPR_HALF * 2).toInt(), Bitmap.Config.ARGB_8888)
                val sc = Canvas(spr)
                val gl = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = tint.accent
                    maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
                }
                sc.drawCircle(EMBER_SPR_HALF, EMBER_SPR_HALF, EMBER_SPR_R, gl)
                drawSphere(sc, EMBER_SPR_HALF, EMBER_SPR_HALF, EMBER_SPR_R, tint.hi, tint.accent, tint.edge)
                emberSprite = spr
            }

            // —— 剩余天数的文字尺寸（每帧动态画，因为午夜要翻页）——
            bigSize = 0.413f * u
            val spiralBottom = cy + u + 3.6f * s
            bigBase = spiralBottom + 0.16f * u + 0.72f * bigSize
            numCx = cx
            numPaint.typeface = if (HERO_ITALIC) serif600i else serif600
            numPaint.textSize = bigSize
            unitPaint.typeface = serif500
            unitPaint.textSize = bigSize * 0.34f

            // —— 穿越的话：只在这里排版（字号、行距、位置），文字本身每帧画 ——
            val wSize = 0.097f * u
            qText.textSize = wSize
            qNum.textSize = wSize
            qStep = 1.8f * wSize
            qWidth = fw
            qHeight = fh
            qLayoutDirty = true
            // 三行窗口的中间那一行（焦点行）：落在原来四行文字的垂直中线上
            qBase = fh * QUOTE_TOP + 0.13f * u + wSize + 1.5f * qStep
        }

        private fun drawNumber(c: Canvas, remain: Int, alpha: Int, dy: Float) {
            if (alpha <= 2) return
            numPaint.color = withAlpha(pal.ink, alpha)
            unitPaint.color = withAlpha(pal.dim, alpha)
            val str = "$remain"
            val gap = 0.04f * uPx
            val numW = numPaint.measureText(str)
            val unitW = unitPaint.measureText("天")
            val x0 = numCx - (numW + gap + unitW) / 2f
            c.drawText(str, x0, bigBase + dy, numPaint)
            c.drawText("天", x0 + numW + gap, bigBase + dy, unitPaint)
        }

        // 大数字：剩余 0 天（一年的最后一天）时，换成「最后一天」四个字
        private fun drawHero(c: Canvas, remain: Int, alpha: Int, dy: Float) {
            if (remain != 0) {
                drawNumber(c, remain, alpha, dy)
                return
            }
            if (alpha <= 2) return
            numPaint.color = withAlpha(pal.ink, alpha)
            val oldTypeface = numPaint.typeface
            val oldSize = numPaint.textSize
            numPaint.typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            numPaint.textSize = bigSize * 0.62f
            val wText = numPaint.measureText("最后一天")
            c.drawText("最后一天", numCx - wText / 2f, bigBase + dy, numPaint)
            numPaint.typeface = oldTypeface
            numPaint.textSize = oldSize
        }

        private fun drawYear(c: Canvas, text: String, alpha: Int) {
            if (!SHOW_YEAR || alpha <= 2) return
            yearPaint.color = withAlpha(pal.faint, alpha)
            c.drawText(text, yearCx, yearBase, yearPaint)
        }

        private fun ensureDark(g: SpiralGeo): Bitmap? {
            darkBmp?.let { return it }
            val ref = pastBmp ?: return null
            val b = Bitmap.createBitmap(ref.width, ref.height, Bitmap.Config.ARGB_8888)
            val dc = Canvas(b)
            dc.translate(-layerOx, -layerOy)
            val pp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.past }
            for (k in 0 until g.total) dc.drawCircle(g.x[k], g.y[k], g.r[k], pp)
            darkBmp = b
            return b
        }

        // ───────── 跨年仪式（约 6 秒）─────────
        //  0.0～1.0  满屏暗点，只剩旧年最后一颗球在缓慢呼吸，年份还是旧的，数字是 0
        //  1.0～2.2  最后一颗球熄灭；1.0～2.0 年份由旧换新
        //  2.0～4.6  一圈光从螺旋中心向外铺开，所到之处暗点亮成小球；剩余天数同步从 0 数到满
        //  4.9～5.7  今天那颗球被点燃，光晕闪一下，之后进入日常的呼吸
        private fun drawNewYear(c: Canvas, g: SpiralGeo, t: Float, remain: Int, year: Int, offX: Float, offY: Float) {
            val dark = ensureDark(g)
            if (dark != null) {
                c.drawBitmap(dark, layerOx + offX * DEPTH_PAST, layerOy + offY * DEPTH_PAST, null)
            }

            val yf = ease(((t - 1.0f) / 1.0f).coerceIn(0f, 1f))
            drawYear(c, "$newYearOld", (255f * (1f - yf)).toInt())
            drawYear(c, "$year", (255f * yf).toInt())

            // 旧年最后一颗球
            val yi = g.total - 1
            val fadeA = 1f - ease(((t - 1.0f) / 1.2f).coerceIn(0f, 1f))
            if (fadeA > 0.01f) {
                val pul = (0.5 - 0.5 * cos(2.0 * PI * t / 3.0)).toFloat()
                val b = g.r[yi] * 1.2f * (1f + 0.25f * pul)
                val x = g.x[yi] + offX * DEPTH_LIT
                val y = g.y[yi] + offY * DEPTH_LIT
                haloPaint.shader = RadialGradient(
                    x, y, b * 3.4f,
                    intArrayOf(withAlpha(tint.accent, (150f * fadeA).toInt()), withAlpha(tint.accent, 0)),
                    null, Shader.TileMode.CLAMP
                )
                c.drawCircle(x, y, b * 3.4f, haloPaint)
                drawSphere(c, x, y, b, tint.hi, tint.accent, tint.edge, (255f * fadeA).toInt())
            }

            // 光圈向外铺开：螺旋里的点按序号向外排，所以半径越大，序号越靠后
            val wp = ((t - 2.0f) / 2.6f).coerceIn(0f, 1f)
            if (wp > 0f) {
                val rMin = 8f * g.s
                val radius = rMin + (uPx - rMin) * kotlin.math.sqrt(wp) + 5f * g.s * wp
                val wx = spCx + offX * DEPTH_LIT
                val wy = spCy + offY * DEPTH_LIT
                litBmp?.let {
                    wavePath.reset()
                    wavePath.addCircle(wx, wy, radius, Path.Direction.CW)
                    c.save()
                    c.clipPath(wavePath)
                    c.drawBitmap(it, layerOx + offX * DEPTH_LIT, layerOy + offY * DEPTH_LIT, null)
                    c.restore()
                }
                if (wp < 1f) {
                    ringPaint.color = withAlpha(tint.hot, (170f * (1f - wp)).toInt())
                    ringPaint.strokeWidth = max(2f, g.s * 2.2f)
                    c.drawCircle(wx, wy, radius, ringPaint)
                }
            }

            // 剩余天数：随光圈从 0 数到满
            drawNumber(c, (remain * ease(wp)).roundToInt(), 255, 0f)

            // 今天那颗球被点燃
            val igp = ((t - 4.9f) / 0.8f).coerceIn(0f, 1f)
            drawBreathingBall(c, ease(igp), 1f + 1.6f * sin(PI.toFloat() * igp), false, offX, offY)
        }

        // ───────── 动态层：背景星尘 ─────────
        private fun drawDust(c: Canvas, now: Long, w: Int, h: Int, offX: Float, offY: Float) {
            val t = (now % 3_600_000L) / 1000f
            val k = w / 1080f
            for (i in 0 until DUST_N) {
                val tw = 0.55f + 0.45f * sin(t * (0.5f + dustSp[i]) + dustPh[i])
                dustPaint.color = withAlpha(pal.hot, (dustA[i] * tw).toInt())
                c.drawCircle(
                    dustX[i] * w + offX * DEPTH_DUST, dustY[i] * h + offY * DEPTH_DUST,
                    dustR[i] * k, dustPaint
                )
            }
        }

        // ───────── 动态层：低电量余烬（每颗各自闪烁）─────────
        private fun drawEmbers(c: Canvas, g: SpiralGeo, now: Long, offX: Float, offY: Float) {
            val sp = emberSprite ?: return
            val t = (now % 3_600_000L) / 1000f
            for (k in g.todayIdx + 1 until g.total) {
                val fl = 0.5f + 0.5f * sin(t * (1.3f + (k % 5) * 0.37f) + k * 1.7f) * sin(t * 0.7f + k * 0.9f)
                emberPaint.alpha = (255f * (0.25f + 0.75f * fl)).toInt()
                val scale = g.r[k] * 1.2f / EMBER_SPR_R
                val half = EMBER_SPR_HALF * scale
                val px = g.x[k] + offX * DEPTH_LIT
                val py = g.y[k] + offY * DEPTH_LIT
                emberDst.set(px - half, py - half, px + half, py + half)
                c.drawBitmap(sp, null, emberDst, emberPaint)
            }
        }

        // ───────── 动态层：今天的圆球（呼吸：光晕起伏 + 涟漪扩散）─────────
        private fun drawBreathingBall(
            c: Canvas, scaleMul: Float, haloBoost: Float, finale: Boolean, offX: Float, offY: Float
        ) {
            if (todayR <= 0f || scaleMul <= 0.02f) return
            val ph = breathPh
            val pulse = (0.5 - 0.5 * cos(2.0 * PI * ph)).toFloat()

            val x = todayX + offX * DEPTH_LIT
            val y = todayY + offY * DEPTH_LIT
            // 一年的最后一天：这是唯一亮着的一颗，更大一些
            val ball = todayR * 2.1f * (if (finale) 1.35f else 1f) * (1f + 0.28f * pulse) * scaleMul

            val haloR = ball * (2.6f + 2.2f * pulse)
            val nightDim = 1f - 0.3f * nightT
            val haloAlpha = ((95 + 95 * pulse) * nightDim * haloBoost).roundToInt().coerceIn(0, 255)
            haloPaint.shader = RadialGradient(
                x, y, haloR,
                intArrayOf(withAlpha(tint.accent, haloAlpha), withAlpha(tint.accent, 0)),
                null, Shader.TileMode.CLAMP
            )
            c.drawCircle(x, y, haloR, haloPaint)

            val ringAlpha = ((1f - ph).pow(1.6f) * 150f * nightDim).roundToInt()
            if (ringAlpha > 2) {
                ringPaint.color = withAlpha(tint.accent, ringAlpha)
                ringPaint.strokeWidth = max(2f, todayR * 0.22f)
                c.drawCircle(x, y, ball * (1.2f + 3.6f * ph), ringPaint)
            }

            val mid = blend(tint.accent, tint.hot, 0.4f + 0.6f * pulse)
            val edge = blend(tint.edge, tint.accent, 0.5f * pulse)
            drawSphere(c, x, y, ball, tint.hi, mid, edge)
        }

        // ───────── 动态层：午夜仪式（昨天的球化作星尘，流向今天）─────────
        private fun drawRitual(c: Canvas, g: SpiralGeo, rt: Float, offX: Float, offY: Float) {
            val yi = ritualY
            if (yi < 0 || yi >= g.total) return
            val yx = g.x[yi] + offX * DEPTH_PAST
            val yy = g.y[yi] + offY * DEPTH_PAST
            val tx = todayX + offX * DEPTH_LIT
            val ty = todayY + offY * DEPTH_LIT

            // 1) 昨天那颗亮球渐渐熄灭，沉淀为底下的暗点
            val fade = 1f - ease((rt / 1.0f).coerceIn(0f, 1f))
            if (fade > 0.01f) {
                val b = g.r[yi] * 1.2f
                haloPaint.shader = RadialGradient(
                    yx, yy, b * 3.2f,
                    intArrayOf(withAlpha(tint.accent, (150f * fade).toInt()), withAlpha(tint.accent, 0)),
                    null, Shader.TileMode.CLAMP
                )
                c.drawCircle(yx, yy, b * 3.2f, haloPaint)
                drawSphere(c, yx, yy, b, tint.hi, tint.accent, tint.edge, (255f * fade).toInt())
            }

            // 2) 一缕星尘，沿一道弧线从昨天流向今天
            val mx = (yx + tx) / 2f
            val my = (yy + ty) / 2f - 0.25f * uPx
            for (i in 0 until 16) {
                val p = ((rt - i * 0.03f) / 0.9f).coerceIn(0f, 1f)
                if (p <= 0f || p >= 1f) continue
                val e = ease(p)
                val wob = sin(i * 2.4f) * 0.04f * uPx
                val a0 = (1f - e) * (1f - e)
                val a1 = 2f * (1f - e) * e
                val a2 = e * e
                val px = a0 * yx + a1 * (mx + wob) + a2 * tx
                val py = a0 * yy + a1 * (my + wob * 0.5f) + a2 * ty
                dustPaint.color = withAlpha(tint.hot, (210f * sin(PI.toFloat() * p)).toInt())
                c.drawCircle(px, py, max(1.5f, g.s * (1.6f - 0.8f * p)), dustPaint)
            }
        }

        // ───────── 穿越的话：静态整段，或字幕式滚动 ─────────
        private fun drawQuote(c: Canvas) {
            val lines = qSlots
            if (lines.isEmpty() || qWidth <= 0f) return
            if (qLayoutDirty) {                       // 整块文字按最宽的一行左对齐并居中
                var maxW = 0f
                for (line in lines) maxW = max(maxW, lineWidth(line, qText, qNum))
                qX = (qWidth - maxW) / 2f
                qLayoutDirty = false
            }
            if (!QUOTE_SCROLL) {
                drawQuoteStatic(c, lines)
                return
            }
            val slots = lines.size + 1
            var period = 0f
            for (i in 0 until slots) period += qHolds[i] + QUOTE_GLIDE
            var t = ((SystemClock.uptimeMillis() - qStart) / 1000f) % period
            var idx = 0
            while (idx < slots - 1 && t >= qHolds[idx] + QUOTE_GLIDE) {
                t -= qHolds[idx] + QUOTE_GLIDE
                idx++
            }
            val hold = qHolds[idx]
            val pos = if (t < hold) {
                idx.toFloat()
            } else {
                val x = min(1f, (t - hold) / QUOTE_GLIDE)
                idx + x * x * (3f - 2f * x)           // 缓入缓出
            }
            val base = floor(pos).toInt()
            for (k in base - 1..base + 2) {
                val d = k - pos                       // >0 在焦点行下面，<0 在上面
                val ad = abs(d)
                if (ad >= 1.5f) continue
                val cs = cos(PI.toFloat() * ad / 3f)
                val a = (255f * cs * cs).roundToInt()
                if (a <= 2) continue
                val slot = ((k % slots) + slots) % slots
                if (slot >= lines.size) continue      // 空位：什么都不画
                val col = when {
                    slot >= qBodyCount -> pal.dim
                    slot == qBodyCount - 1 -> pal.hot
                    else -> pal.ink
                }
                qText.color = col
                qNum.color = col
                qText.alpha = a
                qNum.alpha = a
                drawRuns(c, lines[slot], qX, qBase + d * qStep, qText, qNum)
            }
        }

        // 静态显示：整段一次画出，以焦点行为中线上下对称排开；行数多时自动收紧行距，不压到解锁图标和底部提示
        private fun drawQuoteStatic(c: Canvas, lines: List<String>) {
            val n = lines.size
            val step = if (n > 1) min(qStep, qHeight * 0.1f / (n - 1)) else qStep
            var by = qBase - (n - 1) * step / 2f
            for ((i, line) in lines.withIndex()) {
                val col: Int
                val a: Int
                when {
                    i >= qBodyCount -> { col = pal.dim; a = 255 }          // 出处
                    i == qBodyCount - 1 -> { col = pal.hot; a = 255 }      // 正文最后一句：暖色
                    else -> { col = pal.ink; a = 150 }                     // 前面几句：约 59% 亮度
                }
                qText.color = col
                qNum.color = col
                qText.alpha = a
                qNum.alpha = a
                drawRuns(c, line, qX, by, qText, qNum)
                by += step
            }
        }

        // 把一行文字切成连续的「数字/英文」段和「汉字」段：数字段用 numPaint，其余用 textPaint
        private fun isLatin(ch: Char): Boolean = ch.code < 128

        private fun lineWidth(line: String, textPaint: Paint, numPaint: Paint): Float {
            var total = 0f
            var start = 0
            while (start < line.length) {
                val latin = isLatin(line[start])
                var end = start + 1
                while (end < line.length && isLatin(line[end]) == latin) end++
                total += (if (latin) numPaint else textPaint).measureText(line, start, end)
                start = end
            }
            return total
        }

        private fun drawRuns(c: Canvas, line: String, x: Float, base: Float, textPaint: Paint, numPaint: Paint) {
            var px = x
            var start = 0
            while (start < line.length) {
                val latin = isLatin(line[start])
                var end = start + 1
                while (end < line.length && isLatin(line[end]) == latin) end++
                val paint = if (latin) numPaint else textPaint
                paint.textAlign = Paint.Align.LEFT
                c.drawText(line, start, end, px, base, paint)
                px += paint.measureText(line, start, end)
                start = end
            }
        }

        // ───────── 可选：底部一行（月日 / 星期 / 时钟）─────────
        private fun drawDateLine(c: Canvas, cal: Calendar, w: Int, h: Int) {
            val size = 0.125f * uPx
            dateDim.color = pal.dim
            dateNumDim.color = pal.dim
            dateNumInk.color = pal.ink
            dateDim.textSize = size
            dateNumDim.textSize = size
            dateNumInk.textSize = size
            val month = cal.get(Calendar.MONTH) + 1
            val day = cal.get(Calendar.DAY_OF_MONTH)
            val week = arrayOf("日", "一", "二", "三", "四", "五", "六")[cal.get(Calendar.DAY_OF_WEEK) - 1]
            val time = String.format("%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            val sp = " " to dateDim      // 组与组之间的空隙（一个全角空格）
            drawCentered(
                c, w / 2f, h * DATE_BASELINE, 0f,
                listOf(
                    "$month" to dateNumDim, "月" to dateDim, "$day" to dateNumDim, "日" to dateDim,
                    sp,
                    "周$week" to dateDim,
                    sp,
                    time to dateNumInk
                )
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
