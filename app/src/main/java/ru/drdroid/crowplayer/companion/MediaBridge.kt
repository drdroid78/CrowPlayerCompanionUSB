package ru.drdroid.crowplayer.companion

import android.content.ComponentName
import android.graphics.Bitmap
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject
import java.util.Calendar

class MediaBridge : NotificationListenerService() {
    companion object {
        private const val ART = 80
        private var mgr: MediaSessionManager? = null
        private var ctrl: MediaController? = null
        private var lastArtKey = ""
        private var lastMetaKey = ""
        private val handler = Handler(Looper.getMainLooper())
        private var bridgeInstance: MediaBridge? = null

        fun onUsbReady() { bridgeInstance?.pushMeta(force = true) }
        fun pushNow() { handler.post { bridgeInstance?.pushMeta(force = true) } }

        fun onPanelCommand(line: String) = handler.post {
            val j = runCatching { JSONObject(line) }.getOrNull() ?: return@post
            when (j.optString("cmd")) {
                "play_pause" -> {
                    val c = ctrl ?: return@post
                    if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
                    else c.transportControls.play()
                }
                "next" -> ctrl?.transportControls?.skipToNext()
                "prev" -> ctrl?.transportControls?.skipToPrevious()
                "like" -> sendLike()
                // "volume" — громкость управляется усилителем через UART на панели,
                // компаньон команду игнорирует.
            }
        }

        private fun sendLike() {
            val c = ctrl ?: return
            val md = c.metadata ?: return
            val current = md.getRating(android.media.MediaMetadata.METADATA_KEY_USER_RATING)?.hasHeart() == true
            // Явно шлём и true, и false — снятие лайка передаётся сердечным рейтингом false.
            c.transportControls.setRating(Rating.newHeartRating(!current))
        }

        private fun isNightNow(): Boolean {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            return h >= 22 || h < 7
        }
    }

    override fun onListenerConnected() {
        bridgeInstance = this
        mgr = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        attachSessions()
        handler.post(tick)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) { if (ctrl == null) attachSessions() }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { if (ctrl == null) attachSessions() }

    private fun attachSessions() {
        val m = mgr ?: return
        val sessions = runCatching {
            m.getActiveSessions(ComponentName(this, MediaBridge::class.java))
        }.getOrNull() ?: return
        val c = sessions.firstOrNull { s ->
            s.playbackState?.state in listOf(PlaybackState.STATE_PLAYING, PlaybackState.STATE_PAUSED)
        } ?: sessions.firstOrNull() ?: return
        if (c === ctrl) { pushMeta(); return }
        ctrl?.unregisterCallback(cb)
        ctrl = c
        c.registerCallback(cb, handler)
        lastMetaKey = ""; lastArtKey = ""
        pushMeta(force = true)
    }

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(md: android.media.MediaMetadata?) = pushMeta(force = true)
        override fun onPlaybackStateChanged(ps: PlaybackState?) = pushMeta(force = true)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (ctrl?.playbackState?.state == PlaybackState.STATE_PLAYING) pushMeta()
            handler.postDelayed(this, 1000)
        }
    }

    private fun pushMeta(force: Boolean = false) {
        val c = ctrl ?: return
        val md = c.metadata ?: return
        val svc = UsbCdcService.instance ?: return

        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        val pos = c.playbackState?.position ?: 0L
        val liked = md.getRating(android.media.MediaMetadata.METADATA_KEY_USER_RATING)?.hasHeart() == true

        val autoActive = Prefs.autoNight && isNightNow()   // авто-ночь: только по расписанию
        val meta = JSONObject().apply {
            put("type", "meta")
            put("title", md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: "")
            put("artist", md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "")
            put("state", if (playing) "playing" else "paused")
            put("positionMs", pos)
            put("durationMs", md.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION))
            put("liked", liked)
            put("nightMode", autoActive || Prefs.nightMode)
            put("autoNight", Prefs.autoNight)
            put("screenBrightness", Prefs.screenBrightness)
            put("ledBrightness", Prefs.ledBrightness)
            put("epochMs", System.currentTimeMillis())
        }
        val key = meta.optString("title") + "|" + playing + "|" + liked + "|" + (pos / 1000)
        if (force || key != lastMetaKey) { lastMetaKey = key; svc.sendLine(meta.toString()) }
        sendArt(md, svc)
    }

    private fun sendArt(md: android.media.MediaMetadata, svc: UsbCdcService) {
        val bmp = md.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART) ?: return
        val key = md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: return
        if (key == lastArtKey) return
        val s = Bitmap.createScaledBitmap(bmp, ART, ART, true)
        val px = IntArray(ART * ART); s.getPixels(px, 0, ART, 0, 0, ART, ART)
        val out = ByteArray(ART * ART * 2)
        for (i in px.indices) {
            val r = (px[i] shr 16) and 0xFF; val g = (px[i] shr 8) and 0xFF; val b = px[i] and 0xFF
            val v = ((r and 0xF8) shl 8) or ((g and 0xFC) shl 3) or (b shr 3)
            out[2 * i] = (v shr 8).toByte()      // big-endian, как читает прошивка
            out[2 * i + 1] = v.toByte()
        }
        lastArtKey = key
        svc.sendLine(JSONObject().put("type", "art").put("total", out.size).toString())
        svc.sendRaw(out)
    }
}
