package ru.drdroid.crowplayer.companion

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) {
        if (!::sp.isInitialized) sp = c.getSharedPreferences("crowplayer", Context.MODE_PRIVATE)
    }

    var nightMode: Boolean
        get() = sp.getBoolean("nightMode", false)
        set(v) = sp.edit().putBoolean("nightMode", v).apply()

    var autoNight: Boolean
        get() = sp.getBoolean("autoNight", true)
        set(v) = sp.edit().putBoolean("autoNight", v).apply()

    var screenBrightness: Int
        get() = sp.getInt("screenBrightness", 75)
        set(v) = sp.edit().putInt("screenBrightness", v).apply()

    var ledBrightness: Int
        get() = sp.getInt("ledBrightness", 42)
        set(v) = sp.edit().putInt("ledBrightness", v).apply()
}
