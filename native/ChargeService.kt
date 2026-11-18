package com.sarjalarmi.sarjalarmi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Sürekli çalışan ön plan servisi. Pil değişimlerini dinler,
 * şarj takılıyken yüzde eşiğe ulaşınca alarm çalar.
 */
class ChargeService : Service() {

    companion object {
        const val ACTION_PREVIEW = "com.sarjalarmi.PREVIEW"
        const val ACTION_STOP_ALARM = "com.sarjalarmi.STOP_ALARM"
        private const val CH_STATUS = "durum"
        private const val CH_ALARM = "alarm"
        private const val ID_STATUS = 1
        private const val ID_ALARM = 2

        @Volatile
        var instance: ChargeService? = null

        val ringing: Boolean
            get() = instance?.isRinging == true

        fun start(ctx: Context, action: String? = null) {
            try {
                val i = Intent(ctx, ChargeService::class.java)
                if (action != null) i.action = action
                ctx.startForegroundService(i)
            } catch (e: Exception) {
                // Arka plandan başlatma engellenmiş olabilir; bir sonraki olayda tekrar denenir.
            }
        }
    }

    private var isRinging = false
    private var player: MediaPlayer? = null
    private var wake: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null
    private var savedVolume = -1
    private var lastLevel = -1
    private var lastPlugged = false
    private var registered = false
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopAlarm() }

    private val battReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            onBattery(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
        goForeground()
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(battReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(battReceiver, filter)
        }
        registered = true
        if (sticky != null) onBattery(sticky)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        when (intent?.action) {
            ACTION_PREVIEW -> startAlarm()
            ACTION_STOP_ALARM -> stopAlarm()
        }
        if (!Cfg.enabled(this) && !isRinging) {
            shutDown()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoStop)
        releaseAlarmResources()
        if (registered) {
            try {
                unregisterReceiver(battReceiver)
            } catch (e: Exception) {
            }
            registered = false
        }
        instance = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- pil mantığı

    private fun onBattery(i: Intent) {
        val lv = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (lv >= 0 && sc > 0) lv * 100 / sc else -1
        val plug = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        lastLevel = pct
        lastPlugged = plug
        if (pct < 0) return

        val th = Cfg.threshold(this)
        val was = Cfg.wasPlugged(this)

        if (plug != was) {
            Cfg.putBool(this, "wasPlugged", plug)
            if (plug) {
                // Şarj yeni takıldı. Zaten eşiğin üstündeyse hemen çalma.
                Cfg.putBool(this, "rang", pct >= th)
            } else {
                Cfg.putBool(this, "rang", false)
                if (isRinging && Cfg.stopOnUnplug(this)) stopAlarm()
            }
        }
        if (pct < th) Cfg.putBool(this, "rang", false)

        if (plug && pct >= th && !Cfg.rang(this) && Cfg.enabled(this)) {
            Cfg.putBool(this, "rang", true)
            startAlarm()
        }
        updateStatus()
    }

    // ---------------------------------------------------------------- alarm

    fun startAlarm() {
        if (isRinging) return
        isRinging = true

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sarjalarmi:alarm")
            wake?.acquire(15 * 60 * 1000L)
        } catch (e: Exception) {
        }

        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val target = Math.max(1, Math.round(max * Cfg.volume(this) / 100f))
            am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
        } catch (e: Exception) {
        }

        playSound()
        if (Cfg.vibrate(this)) startVibration()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(ID_ALARM, buildAlarmNotification())
        } catch (e: Exception) {
        }

        try {
            startActivity(
                Intent(this, AlarmActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            )
        } catch (e: Exception) {
        }

        val d = Cfg.duration(this)
        if (d > 0) handler.postDelayed(autoStop, d * 60_000L)
        updateStatus()
    }

    fun stopAlarm() {
        handler.removeCallbacks(autoStop)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(ID_ALARM)
        if (!isRinging) return
        isRinging = false
        releaseAlarmResources()
        updateStatus()
        if (!Cfg.enabled(this)) shutDown()
    }

    private fun releaseAlarmResources() {
        try {
            player?.stop()
        } catch (e: Exception) {
        }
        try {
            player?.release()
        } catch (e: Exception) {
        }
        player = null
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
        }
        if (savedVolume >= 0) {
            try {
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
            } catch (e: Exception) {
            }
            savedVolume = -1
        }
        try {
            if (wake?.isHeld == true) wake?.release()
        } catch (e: Exception) {
        }
        wake = null
    }

    private fun playSound() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val uris = ArrayList<Uri>()
        try {
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)?.let { uris.add(it) }
        } catch (e: Exception) {
        }
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.let { uris.add(it) }
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)?.let { uris.add(it) }

        for (u in uris) {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(attrs)
                mp.setDataSource(this, u)
                mp.isLooping = true
                mp.prepare()
                mp.start()
                player = mp
                return
            } catch (e: Exception) {
                try {
                    mp.release()
                } catch (e2: Exception) {
                }
            }
        }

        // Son çare: uygulamanın içindeki yerleşik ses
        val mp = MediaPlayer()
        try {
            val afd = resources.openRawResourceFd(R.raw.sarj_alarm)
            mp.setAudioAttributes(attrs)
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.isLooping = true
            mp.prepare()
            mp.start()
            player = mp
        } catch (e: Exception) {
            try {
                mp.release()
            } catch (e2: Exception) {
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun startVibration() {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator = v
            val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            v.vibrate(effect, attrs)
        } catch (e: Exception) {
        }
    }

    // ---------------------------------------------------------------- bildirimler

    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val status = NotificationChannel(CH_STATUS, "Şarj alarmı durumu", NotificationManager.IMPORTANCE_LOW)
        status.setShowBadge(false)
        nm.createNotificationChannel(status)

        val alarm = NotificationChannel(CH_ALARM, "Şarj alarmı", NotificationManager.IMPORTANCE_HIGH)
        alarm.setSound(null, null)
        alarm.enableVibration(false)
        alarm.setBypassDnd(true)
        nm.createNotificationChannel(alarm)
    }

    private fun openAppIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        return PendingIntent.getActivity(
            this, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun alarmScreenIntent(): PendingIntent {
        val i = Intent(this, AlarmActivity::class.java)
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION
        return PendingIntent.getActivity(
            this, 2, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildStatusNotification(): Notification {
        val th = Cfg.threshold(this)
        val text = when {
            isRinging -> "Alarm çalıyor"
            lastLevel < 0 -> "%$th'e ulaşınca çalacak"
            lastPlugged -> "Şarj oluyor · %$lastLevel · %$th'de çalacak"
            else -> "%$lastLevel · şarja takınca %$th'de çalacak"
        }
        return Notification.Builder(this, CH_STATUS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle("Şarj Alarmı açık")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openAppIntent())
            .build()
    }

    @Suppress("DEPRECATION")
    private fun buildAlarmNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ChargeService::class.java).setAction(ACTION_STOP_ALARM),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val th = Cfg.threshold(this)
        return Notification.Builder(this, CH_ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Şarj %$th oldu")
            .setContentText("Şarjı çekebilirsiniz. Susturmak için dokunun.")
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(alarmScreenIntent())
            .setFullScreenIntent(alarmScreenIntent(), true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "DURDUR", stop)
            .build()
    }

    private fun goForeground() {
        val n = buildStatusNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ID_STATUS, n)
            }
        } catch (e: Exception) {
        }
    }

    private fun updateStatus() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(ID_STATUS, buildStatusNotification())
        } catch (e: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun shutDown() {
        try {
            stopForeground(true)
        } catch (e: Exception) {
        }
        stopSelf()
    }
}
