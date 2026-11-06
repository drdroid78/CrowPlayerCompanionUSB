package ru.drdroid.crowplayer.companion

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Prefs.init(this)
        setContentView(R.layout.activity_main)
        UsbCdcService.start(this)

        findViewById<TextView>(R.id.btnNotif).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Switch>(R.id.swAutoNight).apply {
            isChecked = Prefs.autoNight
            setOnCheckedChangeListener { _, v -> Prefs.autoNight = v; MediaBridge.pushNow() }
        }
        findViewById<Switch>(R.id.swNight).apply {
            isChecked = Prefs.nightMode
            setOnCheckedChangeListener { _, v -> Prefs.nightMode = v; MediaBridge.pushNow() }
        }
        findViewById<SeekBar>(R.id.sbScreen).apply {
            progress = Prefs.screenBrightness
            setOnSeekBarChangeListener(simple { Prefs.screenBrightness = it; MediaBridge.pushNow() })
        }
        findViewById<SeekBar>(R.id.sbLeds).apply {
            progress = Prefs.ledBrightness
            setOnSeekBarChangeListener(simple { Prefs.ledBrightness = it; MediaBridge.pushNow() })
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.tvStatus).text =
            if (UsbCdcService.status()) "USB: панель подключена"
            else "Подключите панель по USB…"
    }

    private fun simple(f: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, v: Int, u: Boolean) { if (u) f(v) }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }
}
