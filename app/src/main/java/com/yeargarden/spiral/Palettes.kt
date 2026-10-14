package com.yeargarden.spiral

import android.content.Context
import android.graphics.Color
import kotlin.random.Random

// 一套配色：壁纸里用到的所有颜色都在这里。想加新配色，在下面 Palettes.all 里照抄一行、改十六进制颜色即可。
class Palette(
    val name: String,
    bg1Hex: String,     // 背景渐变：上方（略亮）
    bg2Hex: String,     // 背景渐变：四周（更暗）
    inkHex: String,     // 主文字、大数字
    dimHex: String,     // 次要文字（「天」、出处）
    faintHex: String,   // 年份
    pastHex: String,    // 已过去的天数（暗点）
    accentHex: String,  // 剩余天数的小球、今天那颗球的光晕
    hotHex: String,     // 正文最后一句、今天那颗球吸气时的亮色
    hiHex: String,      // 球体高光
    edgeHex: String,    // 球体背光边缘
    fadeHex: String     // 下半屏渐入的近乎纯黑
) {
    val bg1: Int = Color.parseColor(bg1Hex)
    val bg2: Int = Color.parseColor(bg2Hex)
    val ink: Int = Color.parseColor(inkHex)
    val dim: Int = Color.parseColor(dimHex)
    val faint: Int = Color.parseColor(faintHex)
    val past: Int = Color.parseColor(pastHex)
    val accent: Int = Color.parseColor(accentHex)
    val hot: Int = Color.parseColor(hotHex)
    val hi: Int = Color.parseColor(hiHex)
    val edge: Int = Color.parseColor(edgeHex)
    val fade: Int = Color.parseColor(fadeHex)
}

internal fun withAlpha(color: Int, alpha: Int): Int =
    (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

object Palettes {
    val all: List<Palette> = listOf(
        Palette("琥珀夜", "#171320", "#0A0810", "#F2ECE2", "#9B93A4", "#7D7488", "#4A4258", "#F2A65A", "#FFD9A0", "#FFF3E0", "#C2661A", "#030205"),
        Palette("月光蓝", "#121A2B", "#070B14", "#E9EEF6", "#8F9BB2", "#6E7A92", "#2F3A52", "#7FB6F5", "#CFE6FF", "#EEF6FF", "#2A5FA8", "#020308"),
        Palette("翡翠",   "#102019", "#060D0A", "#EAF2EC", "#8FA699", "#6C8277", "#2B4037", "#5FD0A3", "#C8F5E0", "#EFFFF7", "#1C7556", "#020604"),
        Palette("玫瑰",   "#231520", "#0C0709", "#F7EDEF", "#AA939C", "#8A7480", "#46303B", "#F38BA5", "#FFD0DC", "#FFF0F4", "#B23A5C", "#060204"),
        Palette("紫晶",   "#1B1535", "#09061A", "#EFEAF8", "#9D93B8", "#7B7198", "#382F5E", "#B49BFF", "#E3D8FF", "#F5F0FF", "#6544CF", "#04020A"),
        Palette("朱砂",   "#1A1514", "#070606", "#F3EEE8", "#A39A94", "#837A74", "#3A3330", "#EE5B43", "#FFB7A5", "#FFE9E3", "#971F10", "#050303")
    )

    const val AUTO = -1                       // 模式：每次锁屏随机换
    const val PREFS = "spiral_settings"
    const val KEY_MODE = "palette"            // 0..N-1 = 固定用第几套；AUTO = 每次锁屏随机换
    const val KEY_AUTO_IDX = "auto_idx"       // 随机模式下，当前正在用的那一套

    fun mode(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MODE, 0)

    fun current(ctx: Context): Palette {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val m = sp.getInt(KEY_MODE, 0)
        val idx = if (m == AUTO) sp.getInt(KEY_AUTO_IDX, 0) else m
        return all[idx.coerceIn(0, all.size - 1)]
    }

    // 锁屏的瞬间调用：随机模式下换成另一套（和当前不同）；固定模式什么都不做
    fun rotate(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getInt(KEY_MODE, 0) != AUTO) return
        val cur = sp.getInt(KEY_AUTO_IDX, 0)
        var next = Random.nextInt(all.size)
        if (next == cur) next = (next + 1) % all.size
        sp.edit().putInt(KEY_AUTO_IDX, next).apply()
    }
}
