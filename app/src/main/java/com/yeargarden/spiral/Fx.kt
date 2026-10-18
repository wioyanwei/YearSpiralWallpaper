package com.yeargarden.spiral

import android.content.Context

// 动效开关（设置页里一一对应）。做减法：星尘、低电量余烬默认关闭，其余围绕「时间流逝」的默认打开。
object Fx {
    const val PARALLAX = "fx_parallax"   // 陀螺仪视差
    const val DUST = "fx_dust"           // 背景星尘
    const val TOUCH = "fx_touch"         // 触摸涟漪
    const val HAPTIC = "fx_haptic"       // 触摸到某一天时的微震
    const val RITUAL = "fx_ritual"       // 午夜「星火寂灭」仪式
    const val NIGHT = "fx_night"         // 夜间柔和（22:00 后）
    const val EMBER = "fx_ember"         // 低电量绯红余烬

    const val REPLAY_NY = "newyear_replay" // 设置页「播放一次跨年仪式」按钮写入的时间戳
    const val REPLAY = "ritual_replay"   // 设置页「播放一次午夜动画」按钮写入的时间戳
    const val LAST_DAY = "last_seen_day" // 上次看到的日期（年*1000+第几天），用来判断「新的一天」

    // 设置页里显示的：键、标题、说明
    val toggles: List<Triple<String, String, String>> = listOf(
        Triple(PARALLAX, "陀螺仪视差", "倾斜手机时，外圈亮球比内圈移动得更多，有景深感"),
        Triple(DUST, "背景星尘（默认关）", "若隐若现的细小光点，随倾斜轻微位移"),
        Triple(TOUCH, "触摸涟漪", "手指划过螺旋时，圆点轻轻散开并变亮（部分手机锁屏收不到触摸）"),
        Triple(HAPTIC, "触摸微震", "触到某一天时轻轻震一下，并浮出日期"),
        Triple(RITUAL, "午夜与跨年仪式", "新的一天第一次亮屏：昨天的星火化作星尘，今天被点燃；跨年时旧年最后一颗球熄灭，新一年的光圈由中心铺开"),
        Triple(NIGHT, "夜间柔和", "22:00 后光色变暗、呼吸变慢"),
        Triple(EMBER, "低电量余烬（默认关）", "电量低于 20%（未充电）时，亮球变成微闪的绯红余烬")
    )

    fun default(key: String): Boolean = key != DUST && key != EMBER

    fun on(ctx: Context, key: String): Boolean =
        ctx.getSharedPreferences(Palettes.PREFS, Context.MODE_PRIVATE).getBoolean(key, default(key))
}
