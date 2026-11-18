package com.sarjalarmi.sarjalarmi

import android.content.Context
import android.content.SharedPreferences

object Cfg {
    private fun sp(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("sarj", Context.MODE_PRIVATE)

    fun enabled(c: Context) = sp(c).getBoolean("enabled", true)
    fun threshold(c: Context) = sp(c).getInt("threshold", 80)
    fun volume(c: Context) = sp(c).getInt("volume", 100)
    fun vibrate(c: Context) = sp(c).getBoolean("vibrate", true)
    fun duration(c: Context) = sp(c).getInt("duration", 3)
    fun stopOnUnplug(c: Context) = sp(c).getBoolean("stopOnUnplug", true)
    fun rang(c: Context) = sp(c).getBoolean("rang", false)
    fun wasPlugged(c: Context) = sp(c).getBoolean("wasPlugged", false)
    fun notifAsked(c: Context) = sp(c).getBoolean("notifAsked", false)

    fun putBool(c: Context, k: String, v: Boolean) {
        sp(c).edit().putBoolean(k, v).apply()
    }

    fun putInt(c: Context, k: String, v: Int) {
        sp(c).edit().putInt(k, v).apply()
    }
}
