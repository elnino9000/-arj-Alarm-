package com.sarjalarmi.sarjalarmi

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "sarj/native")
            .setMethodCallHandler { call, result ->
                try {
                    handle(call, result)
                } catch (e: Exception) {
                    result.error("ERR", e.message, null)
                }
            }
    }

    override fun onResume() {
        super.onResume()
        if (Cfg.enabled(this)) {
            ChargeService.start(this)
            Watchdog.schedule(this)
        }
    }

    private fun batteryIntent(): Intent? =
        registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun handle(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getState" -> result.success(state())

            "set" -> {
                val k = call.argument<String>("key") ?: ""
                val v = call.argument<Any>("value")
                when (v) {
                    is Boolean -> Cfg.putBool(this, k, v)
                    is Number -> Cfg.putInt(this, k, v.toInt())
                }
                result.success(null)
            }

            "setEnabled" -> {
                val on = call.argument<Boolean>("value") ?: true
                Cfg.putBool(this, "enabled", on)
                if (on) {
                    val b = batteryIntent()
                    if (b != null) {
                        val lv = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                        val pct = if (lv >= 0 && sc > 0) lv * 100 / sc else 0
                        val plug = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                        Cfg.putBool(this, "wasPlugged", plug)
                        Cfg.putBool(this, "rang", pct >= Cfg.threshold(this))
                    }
                    ChargeService.start(this)
                    Watchdog.schedule(this)
                } else {
                    ChargeService.instance?.stopAlarm()
                    stopService(Intent(this, ChargeService::class.java))
                    Watchdog.cancel(this)
                }
                result.success(null)
            }

            "stopAlarm" -> {
                ChargeService.instance?.stopAlarm()
                result.success(null)
            }

            "preview" -> {
                val s = ChargeService.instance
                if (s != null) s.startAlarm() else ChargeService.start(this, ChargeService.ACTION_PREVIEW)
                result.success(null)
            }

            "requestNotif" -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                    !Cfg.notifAsked(this)
                ) {
                    Cfg.putBool(this, "notifAsked", true)
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
                } else {
                    val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    startActivity(i)
                }
                result.success(null)
            }

            "requestBattery" -> {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
                result.success(null)
            }

            "openAutostart" -> {
                val candidates = listOf(
                    ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                    ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                    ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                    ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
                )
                var opened = false
                for (c in candidates) {
                    try {
                        startActivity(Intent().setComponent(c))
                        opened = true
                        break
                    } catch (e: Exception) {
                    }
                }
                if (!opened) {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                }
                result.success(null)
            }

            "openAppSettings" -> {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                )
                result.success(null)
            }

            "openFullScreen" -> {
                try {
                    if (Build.VERSION.SDK_INT >= 34) {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                                .setData(Uri.parse("package:$packageName"))
                        )
                    } else {
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.parse("package:$packageName"))
                        )
                    }
                } catch (e: Exception) {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                }
                result.success(null)
            }

            else -> result.notImplemented()
        }
    }

    private fun state(): Map<String, Any> {
        val b = batteryIntent()
        var pct = -1
        var plug = false
        if (b != null) {
            val lv = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            pct = if (lv >= 0 && sc > 0) lv * 100 / sc else -1
            plug = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return mapOf(
            "level" to pct,
            "plugged" to plug,
            "enabled" to Cfg.enabled(this),
            "threshold" to Cfg.threshold(this),
            "volume" to Cfg.volume(this),
            "vibrate" to Cfg.vibrate(this),
            "duration" to Cfg.duration(this),
            "stopOnUnplug" to Cfg.stopOnUnplug(this),
            "ringing" to ChargeService.ringing,
            "running" to (ChargeService.instance != null),
            "notifOk" to nm.areNotificationsEnabled(),
            "exempt" to pm.isIgnoringBatteryOptimizations(packageName),
            "fullScreen" to (Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()),
            "maker" to Build.MANUFACTURER.lowercase()
        )
    }
}
