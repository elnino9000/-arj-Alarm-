package com.sarjalarmi.sarjalarmi

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Telefon açılınca, uygulama güncellenince, şarj takılınca ve 15 dakikada bir
 * "bekçi" olarak servisin ayakta olduğundan emin olur.
 */
class EventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Cfg.enabled(context)) {
            ChargeService.start(context)
            Watchdog.schedule(context)
        }
    }
}

object Watchdog {
    private const val EVERY = 15 * 60 * 1000L

    private fun pending(c: Context): PendingIntent {
        val i = Intent(c, EventReceiver::class.java).setAction("com.sarjalarmi.WATCHDOG")
        return PendingIntent.getBroadcast(
            c, 7, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(c: Context) {
        try {
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + EVERY,
                EVERY,
                pending(c)
            )
        } catch (e: Exception) {
        }
    }

    fun cancel(c: Context) {
        try {
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pending(c))
        } catch (e: Exception) {
        }
    }
}
