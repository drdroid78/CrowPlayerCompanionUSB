package ru.drdroid.crowplayer.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.os.PendingIntent
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

class UsbCdcService : Service() {
    companion object {
        const val ACTION_USB_PERMISSION = "ru.drdroid.crowplayer.companion.USB_PERMISSION"
        const val ACTION_USB_ATTACH = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
        const val ACTION_USB_DETACH = "android.hardware.usb.action.USB_DEVICE_DETACHED"
        @Volatile var instance: UsbCdcService? = null
        fun start(c: Context) { c.startForegroundService(Intent(c, UsbCdcService::class.java)) }
        fun status(): Boolean = instance?.isReady ?: false
    }

    private var connection: UsbDeviceConnection? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    private val outQueue = ConcurrentLinkedQueue<ByteArray>()
    private var ready = false
    val isReady: Boolean get() = ready

    override fun onCreate() {
        super.onCreate()
        instance = this
        val ch = NotificationChannel("cdc", "CrowPlayer USB", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        startForeground(1, Notification.Builder(this, "cdc")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentText("CrowPlayer USB link")
            .build())
        val f = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION); addAction(ACTION_USB_ATTACH); addAction(ACTION_USB_DETACH)
        }
        registerReceiver(usbReceiver, f)
        tryConnect()
    }

    private fun tryConnect() {
        val usb = getSystemService(USB_SERVICE) as UsbManager
        val dev = usb.deviceList.values.firstOrNull { findCdc(it) != null }
        if (dev == null) { Log.w("Cdc", "CDC device not found"); return }
        if (usb.hasPermission(dev)) open(dev)
        else {
            val pi = PendingIntent.getBroadcast(this, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(packageName), PendingIntent.FLAG_MUTABLE)
            usb.requestPermission(dev, pi)
        }
    }

    private fun findCdc(dev: UsbDevice): UsbInterface? =
        dev.interfaces.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_CDC }
            ?: dev.interfaces.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_COMM }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION_USB_PERMISSION -> {
                    if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        (i.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE))?.let { open(it) }
                    } else Log.w("Cdc", "USB permission denied")
                }
                ACTION_USB_ATTACH -> tryConnect()
                ACTION_USB_DETACH -> { ready = false; connection = null; epIn = null; epOut = null }
            }
        }
    }

    private fun open(dev: UsbDevice) {
        if (ready) return
        val iface = findCdc(dev) ?: return
        val conn = (getSystemService(USB_SERVICE) as UsbManager).openDevice(dev) ?: return
        conn.claimInterface(iface, true)
        for (ep in iface.endpoints) {
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
        }
        if (epIn == null || epOut == null) { Log.e("Cdc", "bulk endpoints not found"); return }
        connection = conn; ready = true
        thread(isDaemon = true) { readerLoop() }
        thread(isDaemon = true) { writerLoop() }
        MediaBridge.onUsbReady()
        sendLine("""{"type":"hello"}""")
        Log.i("Cdc", "CDC opened: ${dev.deviceName}")
    }

    private fun readerLoop() {
        val buf = ByteArray(4096); val sb = StringBuilder()
        while (ready) {
            val n = connection?.bulkTransfer(epIn, buf, buf.size, 500) ?: -1
            if (n > 0) {
                sb.append(String(buf, 0, n, Charsets.UTF_8))
                while (true) {
                    val idx = sb.indexOf('\n'); if (idx < 0) break
                    val line = sb.substring(0, idx).trim(); sb.delete(0, idx + 1)
                    if (line.isNotEmpty()) MediaBridge.onPanelCommand(line)
                }
            } else Thread.sleep(20)
        }
    }

    private fun writerLoop() {
        while (ready) {
            val pkt = outQueue.poll() ?: run { Thread.sleep(5); continue }
            var off = 0
            while (off < pkt.size) {
                val n = connection?.bulkTransfer(epOut, pkt, off, minOf(4096, pkt.size - off), 1000) ?: -1
                if (n <= 0) { Thread.sleep(10); continue }
                off += n
            }
        }
    }

    fun sendLine(json: String) { if (ready) outQueue.add((json + "\n").toByteArray(Charsets.UTF_8)) }
    fun sendRaw(bytes: ByteArray) { if (ready) outQueue.add(bytes) }

    override fun onDestroy() {
        ready = false; instance = null
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }
    override fun onBind(i: Intent?): IBinder? = null
}
