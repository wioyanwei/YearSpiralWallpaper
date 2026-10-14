package com.yeargarden.spiral

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

// 设置页：选配色。同时也是系统壁纸选择页里「设置」按钮打开的页面。
class MainActivity : AppCompatActivity() {

    private val rows = ArrayList<LinearLayout>()
    private val checks = ArrayList<TextView>()
    private val modes = ArrayList<Int>()

    // 配色小样：背景渐变 + 几颗小球
    private class Swatch(ctx: Context, private val pals: List<Palette>) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            p.shader = LinearGradient(0f, 0f, 0f, h, pals[0].bg1, pals[0].bg2, Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(0f, 0f, w, h), h * 0.28f, h * 0.28f, p)
            p.shader = null
            val colors: List<Int> =
                if (pals.size == 1) listOf(pals[0].accent, pals[0].accent, pals[0].hot) else pals.map { it.accent }
            val n = colors.size
            for (i in 0 until n) {
                p.color = colors[i]
                c.drawCircle(w * (i + 1f) / (n + 1f), h / 2f, h * 0.15f, p)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        fun px(v: Int): Int = (v * dp).toInt()

        val root = ScrollView(this)
        root.setBackgroundColor(Color.parseColor("#0A0810"))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(px(20), px(56), px(20), px(32))
        root.addView(col)

        fun text(s: String, sizeSp: Float, color: String): TextView {
            val t = TextView(this)
            t.text = s
            t.textSize = sizeSp
            t.setTextColor(Color.parseColor(color))
            return t
        }

        col.addView(text("年历螺旋", 24f, "#F2ECE2"))
        val sub = text("选一套配色，立即生效", 14f, "#9B93A4")
        sub.setPadding(0, px(6), 0, px(14))
        col.addView(sub)

        fun addRow(title: String, desc: String, pals: List<Palette>, mode: Int) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(px(14), px(12), px(14), px(12))
            val sw = Swatch(this, pals)
            row.addView(sw, LinearLayout.LayoutParams(px(96), px(48)))
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(px(14), 0, px(8), 0)
            box.addView(text(title, 17f, "#F2ECE2"))
            if (desc.isNotEmpty()) box.addView(text(desc, 12f, "#9B93A4"))
            row.addView(box, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val check = text("✓", 20f, "#F2A65A")
            row.addView(check)
            row.setOnClickListener {
                getSharedPreferences(Palettes.PREFS, Context.MODE_PRIVATE).edit().putInt(Palettes.KEY_MODE, mode).apply()
                refresh()
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = px(10)
            col.addView(row, lp)
            rows.add(row)
            checks.add(check)
            modes.add(mode)
        }

        for ((i, pal) in Palettes.all.withIndex()) addRow(pal.name, "", listOf(pal), i)
        addRow("每次锁屏随机换", "锁屏的瞬间换成另一套", Palettes.all, Palettes.AUTO)

        val btn = Button(this)
        btn.text = "设为动态壁纸"
        btn.setOnClickListener {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            intent.putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this, SpiralWallpaperService::class.java)
            )
            startActivity(intent)
        }
        val blp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        blp.topMargin = px(28)
        col.addView(btn, blp)

        setContentView(root)
        refresh()
    }

    // 选中的那一行描边并显示 ✓
    private fun refresh() {
        val dp = resources.displayMetrics.density
        val current = Palettes.mode(this)
        for (i in rows.indices) {
            val selected = modes[i] == current
            val bg = GradientDrawable()
            bg.cornerRadius = 14f * dp
            bg.setColor(Color.parseColor("#14101C"))
            bg.setStroke((2 * dp).toInt(), Color.parseColor(if (selected) "#F2A65A" else "#2A2433"))
            rows[i].background = bg
            checks[i].visibility = if (selected) View.VISIBLE else View.INVISIBLE
        }
    }
}
