package com.sarjalarmi.sarjalarmi

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Alarm çalarken kilit ekranının da üstünde açılan tam ekran "DURDUR" ekranı. */
class AlarmActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val check = object : Runnable {
        override fun run() {
            if (!ChargeService.ringing) {
                finish()
                return
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.parseColor("#0B1020"))
        root.setPadding(48, 48, 48, 48)

        val bolt = TextView(this)
        bolt.text = "⚡"
        bolt.textSize = 72f
        bolt.gravity = Gravity.CENTER

        val title = TextView(this)
        title.text = "Şarj %${Cfg.threshold(this)} oldu"
        title.setTextColor(Color.WHITE)
        title.textSize = 32f
        title.setTypeface(null, Typeface.BOLD)
        title.gravity = Gravity.CENTER

        val sub = TextView(this)
        sub.text = "Şarjı çekebilirsiniz"
        sub.setTextColor(Color.parseColor("#8B94B3"))
        sub.textSize = 18f
        sub.gravity = Gravity.CENTER
        sub.setPadding(0, 12, 0, 72)

        val btn = Button(this)
        btn.text = "DURDUR"
        btn.textSize = 28f
        btn.setTextColor(Color.WHITE)
        btn.setTypeface(null, Typeface.BOLD)
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#FF5C6C"))
        bg.cornerRadius = 90f
        btn.background = bg
        btn.setOnClickListener {
            ChargeService.instance?.stopAlarm()
            finish()
        }

        root.addView(bolt)
        root.addView(title)
        root.addView(sub)
        root.addView(
            btn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 330
            )
        )
        setContentView(root)
        handler.post(check)
    }

    override fun onDestroy() {
        handler.removeCallbacks(check)
        super.onDestroy()
    }
}
