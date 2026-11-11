package com.yeargarden.spiral

import android.graphics.Color
import java.util.Calendar

// 七色方案：周一到周日各一个颜色。
// 剩余的日子 = 该颜色的发光球体；已过的日子 = 同色相、压暗、缩小的实心小点。
// 形态区分「过去 / 未来」，色相区分「星期几」，所以同一星期几的球不论过去还是未来，都连成同一条螺旋。
object Week {
    val COLORS = intArrayOf(
        Color.rgb(255, 40, 60),    // 周一 红
        Color.rgb(255, 140, 0),    // 周二 橙
        Color.rgb(255, 236, 0),    // 周三 黄
        Color.rgb(0, 226, 80),     // 周四 绿
        Color.rgb(0, 220, 255),    // 周五 青
        Color.rgb(40, 80, 255),    // 周六 蓝
        Color.rgb(255, 40, 220)    // 周日 品红
    )
    const val PAST_DIM = 0.42f     // 已过的小点：亮度保留多少
    const val PAST_SCALE = 0.62f   // 已过的小点：相对球体半径的比例

    // 这一年的 1 月 1 日是星期几（周一 = 0 … 周日 = 6）
    fun firstWeekday(year: Int): Int {
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, Calendar.JANUARY, 1)
        return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7      // 周日=1→6，周一=2→0
    }

    // 螺旋里第 k 颗（0 起）是星期几
    fun weekday(year: Int, k: Int): Int = (firstWeekday(year) + k) % 7

    private fun shade(c: Int, f: Float) =
        Color.rgb((Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

    // night：0～1，夜里整体收暗一点（和夜间柔和一致）
    fun mid(wd: Int, night: Float): Int = shade(COLORS[wd], 1f - 0.3f * night)
    fun edge(wd: Int, night: Float): Int = shade(mid(wd, night), 0.55f)
    fun hi(wd: Int, night: Float): Int = mix(mid(wd, night), Color.WHITE, 0.55f)
    fun past(wd: Int, night: Float): Int = shade(mid(wd, night), PAST_DIM)
}
