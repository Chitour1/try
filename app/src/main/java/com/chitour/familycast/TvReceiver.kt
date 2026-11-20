package com.chitour.familycast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

fun Context.isTv(): Boolean =
    (getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature("android.software.leanback")

fun Context.tvName(): String {
    val custom = Store.pref("tvName")
    if (custom.isNotBlank()) return custom
    val n = try { Settings.Global.getString(contentResolver, "device_name") } catch (e: Exception) { null }
    return n?.takeIf { it.isNotBlank() } ?: Build.MODEL
}

/** خدمة الاستقبال على التلفاز: تستقبل أوامر الهاتف وتعلن عن التلفاز في الشبكة باسمه */
class ReceiverService : Service() {
    private var server: TvServer? = null
    private var nsd: NsdManager? = null
    private var reg: NsdManager.RegistrationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("rx", "جهاز الاستقبال", NotificationManager.IMPORTANCE_LOW))
            val pi = PendingIntent.getActivity(this, 1, Intent(this, TvActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(this, "rx").setContentTitle("عرض عائلي")
                .setContentText("جهاز الاستقبال جاهز").setSmallIcon(R.drawable.ic_notif).setContentIntent(pi).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(9, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(9, n)
        } catch (e: Exception) { }
        if (server == null) {
            try { server = TvServer(applicationContext).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) } } catch (e: Exception) { }
            register()
        }
        return START_STICKY
    }

    private fun register() {
        try {
            nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply { serviceName = tvName(); serviceType = TYPE; port = PORT }
            reg = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            }
            nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg)
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        try { reg?.let { nsd?.unregisterService(it) } } catch (e: Exception) { }
        try { server?.stop() } catch (e: Exception) { }
        server = null
        super.onDestroy()
    }

    companion object {
        const val PORT = 8766
        const val TYPE = "_familycast._tcp"
        fun start(ctx: Context) {
            try { ctx.startForegroundService(Intent(ctx, ReceiverService::class.java)) } catch (e: Exception) {
                try { ctx.startService(Intent(ctx, ReceiverService::class.java)) } catch (x: Exception) { }
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (ctx.isTv()) ReceiverService.start(ctx)
    }
}

/** خادم صغير على التلفاز يتلقى: /load (فيلم) و /cmd (أمر) ويعطي /status (الحالة) */
class TvServer(private val ctx: Context) : NanoHTTPD(ReceiverService.PORT) {
    private val main = Handler(Looper.getMainLooper())

    private fun json(s: String): Response {
        val r = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", s)
        r.addHeader("Access-Control-Allow-Origin", "*")
        return r
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.uri) {
                "/info" -> json(JSONObject().put("app", "familycast").put("name", ctx.tvName()).toString())
                "/status" -> json(TvActivity.statusJson)
                "/history" -> json(TvLib.history())
                "/library" -> {
                    val files = HashMap<String, String>()
                    session.parseBody(files)
                    TvLib.save(JSONObject(files["postData"] ?: "{}"))
                    main.post { TvActivity.instance?.libraryChanged() }
                    json("{\"ok\":true}")
                }
                "/load", "/cmd" -> {
                    val files = HashMap<String, String>()
                    session.parseBody(files)
                    val j = JSONObject(files["postData"] ?: "{}")
                    val isLoad = session.uri == "/load"
                    main.post {
                        val a = TvActivity.instance
                        if (isLoad) {
                            if (a != null && !a.isFinishing) a.load(j) else TvActivity.pendingLoad = j
                            bringUp()
                        } else a?.cmd(j)
                    }
                    json("{\"ok\":true}")
                }
                else -> json("{\"app\":\"familycast\"}")
            }
        } catch (e: Exception) {
            NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.toString())
        }
    }

    private fun bringUp() {
        try {
            ctx.startActivity(Intent(ctx, TvActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (e: Exception) { }
    }
}
