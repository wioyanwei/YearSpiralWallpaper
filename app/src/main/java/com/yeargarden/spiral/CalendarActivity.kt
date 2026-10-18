package com.yeargarden.spiral

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

// 可触摸的年历（见 YearView）
class CalendarActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(YearView(this))
    }
}
